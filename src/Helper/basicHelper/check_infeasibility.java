package Helper.basicHelper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import ilog.concert.IloConstraint;
import ilog.concert.IloException;
import ilog.concert.IloLPMatrix;
import ilog.concert.IloModel;
import ilog.concert.IloModeler;
import ilog.concert.IloNumVar;
import ilog.concert.IloNumVarBound;
import ilog.concert.IloNumVarType;
import ilog.concert.IloRange;
import ilog.cplex.IloCplex;

public class check_infeasibility {
	
	public static void check(String path) {
	try {
	    IloCplex cplex = new IloCplex();
	    double s=System.currentTimeMillis();
	    cplex.importModel(path);
	    cplex.setParam(IloCplex.DoubleParam.EpRHS, 1.0e-5);   // 默认 ~1e-6

	    cplex.solve();
//	    System.out.println(cplex.getObjValue());
	    double e=System.currentTimeMillis();
	   
	         IloLPMatrix lp = (IloLPMatrix)cplex.LPMatrixIterator().next();
	         remove_constraints(cplex,lp);
	         IloRange[] rng = lp.getRanges();
	         System.out.println("约束数量:"+rng.length);
	         // Calculate the number of non-boolean variables.
	         final int numVars = cplex.getNcols() - cplex.getNbinVars();


	         // Gather array of constraints to be considered by the conflict
	         // refiner.
	         List<IloConstraint> constraints = new ArrayList<IloConstraint>();
	         for (int i = 0; i < rng.length; ++i) {
	            constraints.add(rng[i]);
	         }

	         // Add variable bounds to the constraints array.
	         for (IloNumVar v : lp.getNumVars()) {
	            if (v.getType() != IloNumVarType.Bool) {
	               constraints.add(cplex.lowerBound(v));
	               constraints.add(cplex.upperBound(v));
	            }
	         }

//	         cplex.setParam(IloCplex.Param.Conflict.Display, 2);
	         //展示的是求解过程
	         
	         // Define preferences for the constraints. Here, we give all
	         // constraints a preference of 1.0, so they will be treated
	         // equally.
	         double[] prefs = new double[constraints.size()];
	         for (int i = 0; i < prefs.length; ++i) {
	            prefs[i] = 1.0;
	         }

	         IloConstraint[] cons = constraints.toArray(
	            new IloConstraint[constraints.size()]);

	         // Run the conflict refiner. As opposed to letting the conflict
	         // refiner run to completion (as is done here), the user can set
	         // a resource limit (e.g., a time limit, an iteration limit, or
	         // node limit) and still potentially get a "possible" conflict.
	         if (cplex.refineConflict(cons, prefs)) {
	            // Display the solution status.
	            System.out.println("Solution status = " + cplex.getCplexStatus());

	            // Get the conflict status for the constraints that were specified.
	            IloCplex.ConflictStatus[] conflict = cplex.getConflict(cons);

	            // Count the number of conflicts found for each constraint group and
	            // print the results.
	            int numConConflicts = 0;
	            int numBoundConflicts = 0;
	            int numSOSConflicts = 0;
	            for (int i = 0; i < cons.length; ++i) {
	               IloConstraint c = cons[i];
	               if (conflict[i] == IloCplex.ConflictStatus.Member ||
	                   conflict[i] == IloCplex.ConflictStatus.PossibleMember) {
	                  if (c instanceof IloRange)
	                     numConConflicts++;
	                  else if (c instanceof IloNumVarBound)
	                     numBoundConflicts++;
	                  else
	                     numSOSConflicts++;
	               }
	            }

	            // Display a conflict summary.
	            System.out.println("Conflict Summary:");
	            System.out.println("  Constraint conflicts     = " + numConConflicts);
	            System.out.println("  Variable Bound conflicts = " + numBoundConflicts);
	            System.out.println("  SOS conflicts            = " + numSOSConflicts);

	            // Write the identified conflict in the LP format.
	            String confFile = "inf.lp";
	            System.out.printf("Writing conflict file to '%s'....%n", confFile);
	            cplex.writeConflict(confFile);

	            // Display three entire conflict subproblem.
	            try (BufferedReader br = new BufferedReader(new FileReader(confFile))) {
	               String line = null;
	               while ((line = br.readLine()) != null) {
	                  System.out.println(line);
	               }
	            }
	         }
	         else {
	            System.out.println("A conflict was not identified.");
	            System.out.println("Exiting....");
	            return;
	         }
	      } catch (Exception e) {
			e.printStackTrace();
		}
	   
	}
	
	
	public static void remove_constraints(IloCplex cplex,IloLPMatrix lpMatrix) throws IloException {
//		Iterator range_iterator = cplex.rangeIterator();
//		ArrayList<IloRange> constraints = new ArrayList<IloRange>();
//		while (range_iterator.hasNext()) {
//			IloRange range = (IloRange) range_iterator.next();
//			String constraintName = range.getName();
//		
//			}
		
		IloRange[] constraints = lpMatrix.getRanges();
		ArrayList<IloRange> removed_constraints = new ArrayList<IloRange>();
		//神奇，导入的模型获取约束就得通过matrix，通过迭代器获取是空的
		//而直接代码建立的模型获取约束就要通过迭代器matrix是空的，除非根据matrix的方式建立
		for(IloRange range:constraints) {
			String constraintName=range.getName();
//			if(constraintName.contains("FlowBalance_2")) {
//				removed_constraints.add(range);
//			}
//			for(int a=914;a<916;a++) {
//				if(constraintName.contains(String.valueOf(a))) {
//					removed_constraints.add(range);
//				}
//				
//			}
			
			
//			if(constraintName.contains("FlowBalance_1_A011_63900")) {
//				removed_constraints.add(range);
//			}

			
//			if(constraintName.contains("e_lb")) {
//				removed_constraints.add(range);
//			}
//			if(constraintName.contains("r_lb")) {
//				removed_constraints.add(range);
//				}
//			if(constraintName.contains("r_ub")) {
//				removed_constraints.add(range);
//				}
//			if(!constraintName.contains("_6_global_2#10728")) {
//				removed_constraints.add(range);
//				}
////			
		}
		for(IloRange range:removed_constraints) {
			cplex.remove(range);
		}
		}
		
		
	
	public static void main(String[] args) {
		
		check(".\\inf.lp");
	}
}
