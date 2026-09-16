package Model;

import ilog.concert.*;
import ilog.cplex.IloCplex;
import mosek.fusion.*;

import java.util.*;

import Basic.Data;
import Basic.ProcurementParams;
import Basic.Sample;
import Helper.basicHelper.Config;
import Helper.basicHelper.OutputManager;
import Helper.basicHelper.check_infeasibility;


//感觉求解不动，不如直接代入模型求解。
//mosek主问题加入的线性约束一多明显就求解不动了。
public class DROBenders {
	// Formal runs keep all scenarios in the Benders master. Any very small weight
	// is floored to FORMAL_WEIGHT_FLOOR and then renormalized, so no sample is
	// dropped and 1/sqrt(pi_w) remains numerically bounded.
	private static final double FORMAL_WEIGHT_FLOOR = 1e-8;

	public static class Solution {
		public final double objValue;
		public final double[] y;
		public final double solveTimeSec;
		public final int iterations;
		public final int cutsAdded;

		public Solution(double objValue, double[] y, double solveTimeSec, int iterations, int cutsAdded) {
			this.objValue = objValue;
			this.y = y;
			this.solveTimeSec = solveTimeSec;
			this.iterations = iterations;
			this.cutsAdded = cutsAdded;
		}
	}

	// 一个 cut：对应某个场景 w 的一个 dual 极点(或最优对偶解)
	private static class Cut {
		final int w;
		final double constant; // sum_j d_j^w * alpha_j - sum_{i,j} q_ij * sigma_ij
		final double[] yCoeff; // coeff_i = (p_i * beta_i - M_i * gamma_i)

		Cut(int w, double constant, double[] yCoeff) {
			this.w = w;
			this.constant = constant;
			this.yCoeff = yCoeff;
		}
	}

	private static class Master {
		Model M;
		List<Cut> cuts;
		int numCuts;
		int I;
		Variable y;
		Variable eta;
		Variable psi;
		Variable nu;
		double[] invSqrtPi;
		boolean[] activeScenario;

		public Master(ProcurementParams P, int W, int I, double[] sqrtPi, double[] invSqrtPi, boolean[] activeScenario,
				Config cfg) {
			cuts = new ArrayList<>();
			this.I = I;
			this.invSqrtPi = invSqrtPi;
			this.activeScenario = activeScenario;
			this.M = new Model("DRO-Master");
			numCuts = 0;
			// 变量
			y = M.variable("y", I, Domain.binary());
			eta = M.variable("eta", 1, Domain.unbounded());
			psi = M.variable("psi", W, Domain.unbounded());
			nu = M.variable("nu", 1, Domain.greaterThan(0.0));

			// 选择数量约束：alpha <= sum y <= beta
			Expression sumY = Expr.sum(y);
			M.constraint("minSelected", sumY, Domain.greaterThan(P.alpha));
			M.constraint("maxSelected", sumY, Domain.lessThan(P.beta));

			// SOC: ||psi||_2 <= nu <=> (nu, psi) in QCone
			M.constraint("soc", Expr.vstack(nu, psi), Domain.inQCone());

//              // 初始化 cuts：用 dual 零可行解 => g=0
//              // 加 eta >= psi_w / sqrt(pi_w)  (等价于 constant=0, yCoeff=0)
			for (int w = 0; w < W; w++) {
				if (!activeScenario[w]) {
					continue;
				}
				Cut c = new Cut(w, -1e8, new double[I]);
				cuts.add(c);
				Expression lhs = eta;
				lhs = Expr.sub(lhs, Expr.mul(invSqrtPi[w], psi.index(w)));

				// - sum_i yCoeff_i * y_i
				for (int i = 0; i < I; i++) {
					double a = c.yCoeff[i];
					if ( Math.abs(a) < 1e-6)
						continue;
					lhs = Expr.sub(lhs, Expr.mul(a, y.index(i)));
				}
				M.constraint("cut_" + (numCuts++), lhs, Domain.greaterThan(c.constant));

			}

			// 目标： eta - sum_w sqrt(pi_w)*psi_w + lambda*nu
			Expression obj = eta;
			obj = Expr.sub(obj, Expr.dot(sqrtPi, psi));
			obj = Expr.add(obj, Expr.mul(cfg.lambda, nu));
			M.objective(ObjectiveSense.Minimize, obj);

			// solver params
			M.setSolverParam("numThreads", cfg.threads);
			M.setSolverParam("mioMaxTime", cfg.timeLimitSeconds);
			
			//定死主问题的解
//			double[] yV=new double[] {0.0, 1, 0.0, 1, 1, 0.0, 1.0, 0.0, 1.0, 0.0, 0.0,1, 0.0, 0.0, 0.0};
//			for(int i=0;i<yV.length;i++) {
//				M.constraint(y.index(i),Domain.equalsTo(yV[i]));
//			}
		}

		public MasterResult solve() throws SolutionError {
			M.solve();
			double objVal = M.primalObjValue();
			double etaVal = eta.level()[0];
			double[] yVal = y.level();
			 double nuVal  = nu.level()[0];    
			double[] psiVal = psi.level();
			 return new MasterResult(objVal, etaVal, nuVal, yVal, psiVal);
		}

		public void addCuts(List<Cut> addedCuts) {
			cuts.addAll(addedCuts);

			// 加 cuts: eta >= constant + yCoeff*y + psi_w/sqrt(pi_w)
			// 等价写成： eta - psi_w/sqrt(pi_w) - sum_i yCoeff_i y_i >= constant

			for (Cut c : addedCuts) {
				int w = c.w;
				if (!activeScenario[w]) {
					continue;
				}
//				System.out.println(c.w+" "+c.constant+" "+Arrays.toString(c.yCoeff));
				Expression lhs = eta;
				lhs = Expr.sub(lhs, Expr.mul(invSqrtPi[w], psi.index(w)));
				
				// - sum_i yCoeff_i * y_i
				for (int i = 0; i < I; i++) {
					double a = c.yCoeff[i];
					if ( Math.abs(a) < 1e-6)
						continue;
					lhs = Expr.sub(lhs, Expr.mul(a, y.index(i)));
				}
				M.constraint("cut_" + (numCuts++), lhs, Domain.greaterThan(c.constant));
			}
		}

		public void close() {
			if (M != null)
				M.dispose();
		}
	}

	// -------------------------
	// Master 求解（MOSEK）
	// -------------------------
	private static class MasterResult {
	    final double obj;
	    final double eta;
	    final double nu;          // <-- add
	    final double[] y;
	    final double[] psi;

	    MasterResult(double obj, double eta, double nu, double[] y, double[] psi) {
	        this.obj = obj;
	        this.eta = eta;
	        this.nu = nu;
	        this.y = y;
	        this.psi = psi;
	    }
	}


	// -------------------------
	// Dual 子问题（CPLEX LP）
	// DQ(y,d): max d*alpha + sum_i (p_i beta_i - M_i gamma_i) y_i - sum_{i,j} q_ij
	// sigma_ij
	// s.t. alpha_j + beta_i - gamma_i - sigma_ij <= r_ij (eligible)
	// alpha_j <= e_j
	// beta_i <= h_i
	// alpha,beta,gamma,sigma >=0
	// -------------------------
	private static class DualSolution {
		final double Qvalue; // 最优值 = Q(y,d)
		final double constantPart; // sum_j d_j alpha_j - sum_{i,j} q_ij sigma_ij
		final double[] yCoeff; // (p_i beta_i - M_i gamma_i)

		DualSolution(double Qvalue, double constantPart, double[] yCoeff) {
			this.Qvalue = Qvalue;
			this.constantPart = constantPart;
			this.yCoeff = yCoeff;
		}
	}

	public Solution solve(Data data, Config cfg, OutputManager out) throws Exception {
		ProcurementParams P = data.params;
		List<Sample> samples = data.samples;
		int W = samples.size();
		int I = P.I;

		if (cfg.lambda <= 0.0) {
			throw new IllegalArgumentException("DRO 模型需要 lambda>0 才能保证有界（否则目标可能无界）。");
		}

		// 预处理 pi_w、sqrt(pi_w)、1/sqrt(pi_w)
		double[] pi = new double[W];
		double[] sqrtPi = new double[W];
		double[] invSqrtPi = new double[W];
		boolean[] activeScenario = new boolean[W];
		int activeScenarioCount = 0;
		double[] adjustedPi = new double[W];
		double totalMass = 0.0;
		for (int w = 0; w < W; w++) {
			double pw = samples.get(w).weight;
			if (!Double.isFinite(pw) || pw < 0.0) {
				throw new IllegalStateException(
						"Invalid DRO weight before flooring. idx=" + w + ", weight=" + pw + ", C_h=" + cfg.C_h);
			}
			adjustedPi[w] = Math.max(pw, FORMAL_WEIGHT_FLOOR);
			totalMass += adjustedPi[w];
		}
		if (!(totalMass > 0.0)) {
			throw new IllegalStateException("No positive DRO weights remain after formal flooring. C_h=" + cfg.C_h
					+ ", lambda=" + cfg.lambda);
		}
		for (int w = 0; w < W; w++) {
			pi[w] = adjustedPi[w] / totalMass;
			sqrtPi[w] = Math.sqrt(pi[w]);
			invSqrtPi[w] = 1.0 / sqrtPi[w];
			activeScenario[w] = true;
			activeScenarioCount++;
		}
		if (activeScenarioCount == 0) {
			throw new IllegalStateException("No active DRO scenarios remain after formal flooring. C_h=" + cfg.C_h
					+ ", lambda=" + cfg.lambda);
		}

		long t0 = System.nanoTime();

		// -------------------------
		// Master (MOSEK Fusion)
		// -------------------------
		Master m = new Master(P, W, I, sqrtPi, invSqrtPi, activeScenario, cfg);

//        List<Cut> cuts = new ArrayList<>();

		int iter = 0;
		int cutsAdded = 0;
		boolean anyViolation = true;
		double bestLB = -Double.MAX_VALUE;
		double bestUB =  Double.MAX_VALUE;

		while (iter < cfg.maxBendersIter) {
			anyViolation = false;
			iter++;

			// 1) 解 master
			MasterResult mr = m.solve();
			// LB：master 目标是全局下界（对原问题）
		    double lbCand = mr.obj;
		    if (lbCand > bestLB) bestLB = lbCand;
			
			// 2) 对每个场景解 dual 子问题，检查违反并生成 cut

			List<Cut> newCuts = new ArrayList<>();
			double etaHat = -Double.MAX_VALUE;
			for (int w = 0; w < W; w++) {
				if (!activeScenario[w]) {
					continue;
				}
				double[] d = samples.get(w).demand();
				DualSolution ds = solveDualSubproblemCPLEX(P, d, mr.y);

				double rhs = ds.Qvalue + mr.psi[w] * invSqrtPi[w];
				if (rhs > etaHat) etaHat = rhs;
				if (mr.eta + cfg.tol < rhs) {
					anyViolation = true;
					newCuts.add(new Cut(w, ds.constantPart, ds.yCoeff));
				}
			}
			
			 // UB：把 (y, psi, nu, etaHat) 代入目标
		    double dot = 0.0;
		    for (int w = 0; w < W; w++) dot += sqrtPi[w] * mr.psi[w];
		    double ubCand = etaHat - dot + cfg.lambda * mr.nu;

		    if (ubCand < bestUB) bestUB = ubCand;

		    double gapAbs = bestUB - bestLB;
		    double gapRel = gapAbs / bestUB;
		    System.out.printf(Locale.US,
		            "iter=%d  LB=%.6f  UB=%.6f  gapAbs=%.6f  gapRel=%.2e  newCuts=%d  totalCuts=%d%n",
		            iter, bestLB, bestUB, gapAbs, gapRel, newCuts.size(), (cutsAdded + newCuts.size())
		        );
			m.addCuts(newCuts);

			if (!anyViolation) {
				long t1 = System.nanoTime();
				m.close();
				return new Solution(mr.obj, mr.y, (t1 - t0) / 1e9, iter, cutsAdded);
			}

			cutsAdded += newCuts.size();
		}
		m.close();
		throw new IllegalStateException("Benders/constraint-generation 达到最大迭代次数仍未收敛。maxIter=" + cfg.maxBendersIter);
	}

	// 先这样验证正确性吧，现在这个迭代可能会存在问题的，每次迭代都要重新建立主问题求解，不知道mosek能不能动态加入cut以后重新求解。先不管

	private DualSolution solveDualSubproblemCPLEX(ProcurementParams P, double[] d, double[] yVal) throws Exception {
		int I = P.I, J = P.J;

		IloCplex cplex = new IloCplex();
		cplex.setOut(null);

		// vars
		IloNumVar[] alpha = new IloNumVar[J];
		for (int j = 0; j < J; j++)
			alpha[j] = cplex.numVar(0.0, Double.POSITIVE_INFINITY, "a_" + j);

		IloNumVar[] beta = new IloNumVar[I];
		IloNumVar[] gamma = new IloNumVar[I];
		for (int i = 0; i < I; i++) {
			beta[i] = cplex.numVar(0.0, Double.POSITIVE_INFINITY, "b_" + i);
			gamma[i] = cplex.numVar(0.0, Double.POSITIVE_INFINITY, "g_" + i);
		}

		// sigma only for eligible (i,j)
		IloNumVar[][] sigma = new IloNumVar[I][J];
		for (int i = 0; i < I; i++) {
			for (int j = 0; j < J; j++) {
				if (!P.eligible[i][j])
					continue;
				sigma[i][j] = cplex.numVar(0.0, Double.POSITIVE_INFINITY, "s_" + i + "_" + j);
			}
		}

		// constraints:
		// alpha_j + beta_i - gamma_i - sigma_ij <= r_ij
		for (int i = 0; i < I; i++) {
			for (int j = 0; j < J; j++) {
				if (!P.eligible[i][j])
					continue;
				IloLinearNumExpr lhs = cplex.linearNumExpr();
				lhs.addTerm(1.0, alpha[j]);
				lhs.addTerm(1.0, beta[i]);
				lhs.addTerm(-1.0, gamma[i]);
				lhs.addTerm(-1.0, sigma[i][j]);
				cplex.addLe(lhs, P.r[i][j], "c_x_" + i + "_" + j);
			}
		}

		// alpha_j <= e_j
		for (int j = 0; j < J; j++) {
			cplex.addLe(alpha[j], P.e[j], "c_s_" + j);
		}

		// beta_i <= h_i
		for (int i = 0; i < I; i++) {
			cplex.addLe(beta[i], P.h[i], "c_u_" + i);
		}

		// objective
		IloLinearNumExpr obj = cplex.linearNumExpr();
		for (int j = 0; j < J; j++)
			obj.addTerm(d[j], alpha[j]);

		for (int i = 0; i < I; i++) {
			double yi = yVal[i]; // fixed
			obj.addTerm(P.p[i] * (yi > 0.5 ? 1.0 : 0.0), beta[i]);
			obj.addTerm(-P.M[i] * (yi > 0.5 ? 1.0 : 0.0), gamma[i]);
		}

		for (int i = 0; i < I; i++) {
			for (int j = 0; j < J; j++) {
				if (!P.eligible[i][j])
					continue;
				obj.addTerm(-P.q[i][j], sigma[i][j]);
			}
		}

		cplex.addMaximize(obj);

		boolean ok = cplex.solve();
		if (!ok) {
			cplex.exportModel("inf.lp");
			check_infeasibility.check(".\\inf.lp");
			throw new IllegalStateException("Dual subproblem infeasible/unbounded? Status=" + cplex.getCplexStatus());

		}

		double Q = cplex.getObjValue();

		// extract dual solution to build cut
		double[] aVal = new double[J];
		for (int j = 0; j < J; j++)
			aVal[j] = cplex.getValue(alpha[j]);

		double[] bVal = new double[I];
		double[] gVal = new double[I];
		for (int i = 0; i < I; i++) {
			bVal[i] = cplex.getValue(beta[i]);
			gVal[i] = cplex.getValue(gamma[i]);
		}

		double constant = 0.0;
		for (int j = 0; j < J; j++)
			constant += d[j] * aVal[j];

		for (int i = 0; i < I; i++) {
			for (int j = 0; j < J; j++) {
				if (!P.eligible[i][j])
					continue;
				constant -= P.q[i][j] * cplex.getValue(sigma[i][j]);
			}
		}

		double[] yCoeff = new double[I];
		for (int i = 0; i < I; i++) {
			yCoeff[i] = P.p[i] * bVal[i] - P.M[i] * gVal[i];
		}

		cplex.close();
		return new DualSolution(Q, constant, yCoeff);
	}
}
