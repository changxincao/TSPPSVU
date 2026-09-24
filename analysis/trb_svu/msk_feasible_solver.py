"""RSOME/MOSEK adapter that preserves a time-limit incumbent.

RSOME's bundled MOSEK adapter discards every non-optimal primal solution.
For long PCM MISOCP runs, MOSEK can return a valid incumbent with status
``Feasible``.  This adapter is intentionally identical in formulation to the
bundled adapter; it only retains that incumbent and exposes its MIP bound.
"""

from __future__ import annotations

import sys
import time
import warnings

import numpy as np
from mosek.fusion import Domain, Expr, Matrix, Model, ObjectiveSense
from scipy.sparse import coo_matrix

from rsome.gcp import GCProg
from rsome.lp import Solution
from rsome.socp import SOCProg


version = Model.getVersion()
name = "Mosek"
info = f"{name} {version} (feasible-incumbent preserving adapter)"


def solve(form, display=True, log=False, params=None):
    params = {} if params is None else params
    qmat = form.qmat if isinstance(form, (SOCProg, GCProg)) else []
    xmat = form.xmat if isinstance(form, GCProg) else []
    lmi = form.lmi if isinstance(form, GCProg) else []

    idx_cont = [i for i, value in enumerate(form.vtype) if value == "C"]
    idx_bin = [i for i, value in enumerate(form.vtype) if value == "B"]
    idx_int = [i for i, value in enumerate(form.vtype) if value == "I"]
    idx_ub = [i for i, value in enumerate(form.ub) if value != np.inf]
    idx_lb = [i for i, value in enumerate(form.lb) if value != -np.inf]

    is_eq = form.sense == 1
    coo_ineq = coo_matrix(form.linear[~is_eq])
    linear_ineq = Matrix.sparse(coo_ineq.shape[0], coo_ineq.shape[1],
                                coo_ineq.row, coo_ineq.col, coo_ineq.data)
    coo_eq = coo_matrix(form.linear[is_eq])
    linear_eq = Matrix.sparse(coo_eq.shape[0], coo_eq.shape[1],
                              coo_eq.row, coo_eq.col, coo_eq.data)
    const_ineq = form.const[~is_eq]
    const_eq = form.const[is_eq]
    num_constr, num_var = form.linear.shape

    with Model() as mosek_model:
        num_cont = len(idx_cont)
        xc = mosek_model.variable("xc", num_cont)
        x = Expr.mul(Matrix.sparse(num_var, num_cont, idx_cont,
                                   list(range(num_cont)), np.ones(num_cont)), xc)
        if idx_bin:
            num_bin = len(idx_bin)
            xb = mosek_model.variable("xb", num_bin, Domain.binary())
            x = Expr.add(Expr.mul(Matrix.sparse(num_var, num_bin, idx_bin,
                                                list(range(num_bin)), np.ones(num_bin)), xb), x)
        if idx_int:
            num_int = len(idx_int)
            xi = mosek_model.variable("xi", num_int, Domain.integral(Domain.unbounded()))
            x = Expr.add(Expr.mul(Matrix.sparse(num_var, num_int, idx_int,
                                                list(range(num_int)), np.ones(num_int)), xi), x)

        mosek_model.objective(ObjectiveSense.Minimize,
                              Expr.mul(form.obj.reshape((1, form.obj.size)), x))
        c_ineq = mosek_model.constraint(Expr.mul(linear_ineq, x), Domain.lessThan(const_ineq))
        c_eq = mosek_model.constraint(Expr.mul(linear_eq, x), Domain.equalsTo(const_eq))
        c_ub = mosek_model.constraint(x.pick(idx_ub), Domain.lessThan(form.ub[idx_ub]))
        c_lb = mosek_model.constraint(x.pick(idx_lb), Domain.greaterThan(form.lb[idx_lb]))
        for cone in qmat:
            mosek_model.constraint(x.pick(cone), Domain.inQCone())
        for cone in xmat:
            mosek_model.constraint(x.pick([cone[1], cone[2], cone[0]]), Domain.inPExpCone())
        for psd in lmi:
            temp = coo_matrix(psd["linear"])
            psd_linear = Matrix.sparse(temp.shape[0], form.linear.shape[1],
                                       temp.row, temp.col, temp.data)
            left = Expr.reshape(Expr.sub(Expr.mul(psd_linear, x), psd["const"].flatten()),
                                psd["dim"], psd["dim"])
            mosek_model.constraint(left, Domain.inPSDCone(psd["dim"]))

        for parameter, value in params.items():
            mosek_model.setSolverParam(parameter, value)
        if log:
            mosek_model.setLogHandler(sys.stdout)
        if display:
            print("Being solved by Mosek...", flush=True)
            time.sleep(0.2)

        started = time.time()
        mosek_model.solve()
        solve_time = time.time() - started
        status = str(mosek_model.getPrimalSolutionStatus()).split(".")[-1]
        if display:
            print(f"Solution status: {status}")
            print(f"Running time: {solve_time:0.4f}s")

        if status not in {"Optimal", "Feasible"}:
            warnings.warn("MOSEK did not return a usable primal solution.")
            return Solution("Mosek", np.nan, None, status, solve_time)

        x_sol = coo_matrix((np.ones(num_cont), (idx_cont, np.arange(num_cont))),
                           (num_var, num_cont)) @ xc.level()
        if idx_bin:
            x_sol += coo_matrix((np.ones(num_bin), (idx_bin, np.arange(num_bin))),
                                (num_var, num_bin)) @ xb.level()
        if idx_int:
            x_sol += coo_matrix((np.ones(num_int), (idx_int, np.arange(num_int))),
                                (num_var, num_int)) @ xi.level()

        dual = None
        if status == "Optimal" and all(form.vtype == "C"):
            pi = np.ones(num_constr) * np.nan
            upi = np.zeros(num_var)
            lpi = np.zeros(num_var)
            pi[is_eq] = c_eq.dual()
            pi[~is_eq] = c_ineq.dual()
            upi[idx_ub] = c_ub.dual()
            lpi[idx_lb] = c_lb.dual()
            dual = {"pi": pi, "upi": upi, "lpi": lpi}

        solution = Solution("Mosek", float(x_sol @ form.obj), x_sol,
                            status, solve_time, y=dual)
        solution.best_bound = float(mosek_model.getSolverDoubleInfo("mioObjBound"))
        denominator = max(1.0, abs(solution.objval))
        solution.relative_gap = max(0.0, (solution.objval - solution.best_bound) / denominator)
        return solution
