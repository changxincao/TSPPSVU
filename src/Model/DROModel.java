package Model;

import mosek.fusion.*;

import java.util.ArrayList;
import java.util.List;

import Basic.Data;
import Basic.ProcurementParams;
import Basic.Sample;
import Helper.basicHelper.Config;
import Helper.basicHelper.OutputManager;

public class DROModel {
	// Formal runs keep all scenarios in the DRO master. Any very small weight is
	// floored to FORMAL_WEIGHT_FLOOR and then all scenario weights are
	// renormalized, so 1/sqrt(pi_w) stays numerically bounded while no sample is
	// dropped from the solve.
	private static final double FORMAL_WEIGHT_FLOOR = 1e-8;

	public Solution solve(Data data, Config cfg) throws SolutionError {
        if (cfg != null && (cfg.rcsaaCompactRepairAnchors || cfg.rcsaaCompactSwitchedDual) && !cfg.rcsaaCompactDual)
            throw new IllegalArgumentException("Static repair anchors require compact");
        if (cfg != null && (cfg.rcsaaRepairCuts || cfg.rcsaaCompactDual)) {
            boolean exactPrimal = cfg.rcsaaSolverVariant == RCSAASolverVariant.LBBD_PRIMAL_EXACT;
            boolean searchPrimal = cfg.rcsaaSolverVariant == RCSAASolverVariant.LBBD_PRIMAL_SEARCH;
            if (cfg.solveMode != SolveMode.RCSAA || (!exactPrimal && !searchPrimal)
                    || (cfg.rcsaaCompactDual && (!exactPrimal || cfg.rcsaaRepairCuts)))
                throw new IllegalArgumentException("Repair/compact requires the corresponding RCSAA primal variant");
        }
		// 当 solveMode=RCSAA 时，这里作为统一入口，根据配置分发到不同实现。
		// 默认仍走原先的 DRO extensive form，避免影响现有实验脚本。
		if (cfg != null && cfg.solveMode == SolveMode.RCSAA) {
			try {
				Solution result;
				switch (cfg.rcsaaSolverVariant) {
				case LBBD_EXACT:
					result = new RCSAALBBDExactSolver().solve(data, cfg);
					break;
				case LBBD_SEARCH:
					result = new RCSAALBBDSearchSolver().solve(data, cfg);
					break;
				case LBBD_PRIMAL_EXACT:
					result = new RCSAALBBDPrimalExactSolver().solve(data, cfg);
					break;
				case LBBD_PRIMAL_SEARCH:
					result = new RCSAALBBDPrimalSearchSolver().solve(data, cfg);
					break;
				case ENUMERATE_EXACT:
					result = new RCSAAEnumerateSolver().solve(data, cfg);
					break;
				case DRO_EXTENSIVE:
					result = solveDroApproxExtensive(data, cfg);
					break;
				default:
					throw new IllegalStateException("Unsupported RCSAA solver variant: " + cfg.rcsaaSolverVariant);
				}
				return result;
			} catch (SolutionError ex) {
				throw ex;
			} catch (SolverTerminationException ex) {
				throw ex;
			} catch (Exception ex) {
				throw new IllegalStateException("RCSAA solve failed under variant=" + cfg.rcsaaSolverVariant, ex);
			}
		}
		return solveDroApproxExtensive(data, cfg);
	}

	private Solution solveDroApproxExtensive(Data data, Config cfg) throws SolutionError {
		// 这一支保留原有的 DRO 近似模型，不是本文新加的 RCSAA-LBBD 实现。
		ProcurementParams P = data.params;
		List<Sample> samples = data.samples;
		int W = samples.size();
		int I = P.I;
		int J = P.J;

		if (cfg.lambda <= 0.0) {
			throw new IllegalArgumentException("DRO 模型需要 lambda>0 才能保证有界（否则目标可能无界）。");
		}

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

		try (Model M = new Model("DRO-Extensive")) {
			if (cfg.writeSolverLogToConsole)
				M.setLogHandler(new java.io.PrintWriter(System.out, true));
			M.acceptedSolutionStatus(AccSolutionStatus.Feasible);

			// variables
			Variable y = M.variable("y", I, Domain.binary());
			Variable eta = M.variable("eta", 1, Domain.unbounded());
			Variable psi = M.variable("psi", W, Domain.unbounded());
			Variable nu = M.variable("nu", 1, Domain.greaterThan(0.0));

			// second-stage variables per scenario
			// x[i,j,w] only if eligible: for simplicity we create full and fix ineligible
			// to 0 via bounds
			// If you worry about size, you can store only eligible indices.
			Variable x = M.variable("x", new int[] { I, J, W }, Domain.greaterThan(0.0));
			Variable s = M.variable("s", new int[] { J, W }, Domain.greaterThan(0.0));
			Variable u = M.variable("u", new int[] { I, W }, Domain.greaterThan(0.0));

			// enforce ineligible x = 0
			for (int i = 0; i < I; i++) {
				for (int j = 0; j < J; j++) {
					if (P.eligible[i][j])
						continue;
					for (int w = 0; w < W; w++) {
						M.constraint("inelig_" + i + "_" + j + "_" + w, x.index(i, j, w), Domain.equalsTo(0.0));
					}
				}
			}

			// alpha <= sum y <= beta
			Expression sumY = Expr.sum(y);
			M.constraint("minSelected", sumY, Domain.greaterThan(P.alpha));
			M.constraint("maxSelected", sumY, Domain.lessThan(P.beta));

			// SOC: ||psi|| <= nu
			M.constraint("soc", Expr.vstack(nu, psi), Domain.inQCone());

			// scenario-wise recourse constraints + eta lower bound
			for (int w = 0; w < W; w++) {
				if (!activeScenario[w]) {
					continue;
				}
				double[] d = samples.get(w).demand();

				// demand: sum_i x_ijw + s_jw >= d_j
				for (int j = 0; j < J; j++) {
					Expression lhs = Expr.add(Expr.sum(x.slice(new int[] { 0, j, w }, new int[] { I, j + 1, w + 1 })),
							s.index(j, w));
					LinearDomain demandDomain = cfg.enforceDemandEquality
							? Domain.equalsTo(d[j])
							: Domain.greaterThan(d[j]);
					M.constraint("dem_" + j + "_" + w, lhs, demandDomain);
				}
				// x.slice({0,j,w}, {I, j+1, w+1}) 取的是一个子块：固定第 2 维为 j、第 3 维为w，第 1 维从 0到 I−1

				// carrier MQC and total cap: p_i y_i - u_iw <= sum_j x_ijw <= M_i y_i
				for (int i = 0; i < I; i++) {
					Expression sumX = Expr.sum(x.slice(new int[] { i, 0, w }, new int[] { i + 1, J, w + 1 }));

					// lower: sumX >= p_i y_i - u_iw
					Expression rhsLow = Expr.sub(Expr.mul(P.p[i], y.index(i)), u.index(i, w));
					M.constraint("mqcLow_" + i + "_" + w, Expr.sub(sumX, rhsLow), Domain.greaterThan(0.0));

					// upper: sumX <= M_i y_i
					Expression rhsUp = Expr.mul(P.M[i], y.index(i));
					M.constraint("capTot_" + i + "_" + w, Expr.sub(sumX, rhsUp), Domain.lessThan(0.0));

					// lane cap: x_ijw <= q_ij * y_i (与你 CPLEX 实现一致；也可改成 x<=q 再靠 sumX<=M y)
					for (int j = 0; j < J; j++) {
						if (!P.eligible[i][j])
							continue;
						Expression capRhs = Expr.mul(P.q[i][j], y.index(i));
						M.constraint("capLane_" + i + "_" + j + "_" + w, Expr.sub(x.index(i, j, w), capRhs),
								Domain.lessThan(0.0));
					}
				}

				// eta >= scenarioCost(x,s,u) + psi_w / sqrt(pi_w)
				Expression scenarioCost;

				// sum_{i,j} r_ij x_ijw
				List<Expression> exprs = new ArrayList<>();
				for (int i = 0; i < I; i++) {
					for (int j = 0; j < J; j++) {
						if (!P.eligible[i][j])
							continue;
						exprs.add(Expr.mul(P.r[i][j], x.index(i, j, w)));

					}
				}
				// + sum_j e_j s_jw
				for (int j = 0; j < J; j++) {
					exprs.add(Expr.mul(P.e[j], s.index(j, w)));
				}
				// + sum_i h_i u_iw
				for (int i = 0; i < I; i++) {
					exprs.add(Expr.mul(P.h[i], u.index(i, w)));
				}
				if (exprs.isEmpty()) {
					scenarioCost = Expr.constTerm(0.0);
				} else {
					Expression[] arr = exprs.toArray(new Expression[0]);
					scenarioCost = Expr.add(arr); // 一次性求和，避免 ExprAdd 深链
				}
				Expression rhsEta = Expr.add(scenarioCost, Expr.mul(invSqrtPi[w], psi.index(w)));
				M.constraint("etaLB_" + w, Expr.sub(eta, rhsEta), Domain.greaterThan(0.0));
			}

			// objective: eta - dot(sqrtPi, psi) + lambda * nu
			Expression obj = eta;
			obj = Expr.sub(obj, Expr.dot(sqrtPi, psi));
			obj = Expr.add(obj, Expr.mul(cfg.lambda, nu));
			M.objective(ObjectiveSense.Minimize, obj);

			// params
			M.setSolverParam("numThreads", cfg.threads);
			M.setSolverParam("mioMaxTime", cfg.timeLimitSeconds);
			
//			int[] yV=new int[] {1,3,4,6,8,11};
//			for(int i:yV) {
//				M.constraint(y.index(i),Domain.equalsTo(1));
//			}
		
			
			M.solve();

			String solverStatus = String.valueOf(M.getProblemStatus());
			double bestBound = trySolverDoubleInfo(M, "mioObjBound");
			double relativeGap = trySolverDoubleInfo(M, "mioObjRelGap");
			if (trySolverIntInfo(M, "mioObjBoundDefined") <= 0L) bestBound = Double.NaN;
			if (relativeGap < 0.0) relativeGap = Double.NaN;
			long nodeCount = trySolverIntInfo(M, "mioNumRelax");
			int selectedCuts = (int) trySolverIntInfo(M, "mioTotalNumSelectedCuts");
			SolutionStatus primalStatus = M.getPrimalSolutionStatus();
			if (primalStatus != SolutionStatus.Optimal
					&& primalStatus != SolutionStatus.Feasible) {
				throw new SolverTerminationException(
						solverStatus + "/" + primalStatus,
						bestBound, relativeGap, nodeCount);
			}
			double objVal = M.primalObjValue();
			double[] yVal = y.level();

			long t1 = System.nanoTime();
			Solution solution = new Solution(objVal, yVal, (t1 - t0) / 1e9);
			solution.solverStatus = solverStatus;
			solution.bestBound = bestBound;
			solution.relativeGap = relativeGap;
			solution.nodeCount = nodeCount;
			solution.cutCount = selectedCuts;
			solution.certifiedOptimal = Double.isFinite(relativeGap) && relativeGap <= cfg.tol;
			M.dispose();
			return solution;
		}
	}

	private static double trySolverDoubleInfo(Model model, String key) {
		try {
			return model.getSolverDoubleInfo(key);
		} catch (Throwable ignored) {
			return Double.NaN;
		}
	}

	private static long trySolverIntInfo(Model model, String key) {
		try {
			return model.getSolverIntInfo(key);
		} catch (Throwable ignored) {
			return -1L;
		}
	}
}
