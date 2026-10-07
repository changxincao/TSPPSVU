package Model;

import Basic.ProcurementParams;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumVar;
import ilog.cplex.IloCplex;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/** Exact sample-wise separation oracle for box-supported scaled-L1 W1 DRO. */
final class WassersteinBoxOracle {
    private WassersteinBoxOracle() {
    }

    static Result solve(WassersteinBoxInput input,
                        int sample,
                        double[] y,
                        double eta,
                        int threads,
                        double timeLimitSeconds) throws Exception {
        return solve(input, sample, y, eta, threads, timeLimitSeconds, false);
    }

    static Result solve(WassersteinBoxInput input, int sample, double[] y, double eta,
                        int threads, double timeLimitSeconds, boolean allowFeasible) throws Exception {
        long started = System.nanoTime();
        ProcurementParams params = input.params;
        if (y == null || y.length != params.I) {
            throw new IllegalArgumentException("Carrier decision dimension mismatch.");
        }
        if (!Double.isFinite(eta) || eta < -1e-9) {
            throw new IllegalArgumentException("Invalid eta " + eta);
        }
        IloCplex cplex = new IloCplex();
        Diagnostics diagnostics = null;
        try {
            diagnostics = Diagnostics.open();
            cplex.setOut(diagnostics == null ? null : diagnostics.log);
            if (diagnostics != null) cplex.setWarning(diagnostics.log);
            cplex.setParam(IloCplex.Param.Threads, threads);
            if (Double.isFinite(timeLimitSeconds))
                cplex.setParam(IloCplex.Param.TimeLimit, Math.max(1e-3, timeLimitSeconds));

            int I = params.I;
            int J = params.J;
            double[] nominal = input.demand[sample];
            IloNumVar[] alpha = new IloNumVar[J];
            IloNumVar[] beta = new IloNumVar[I];
            IloNumVar[] gamma = new IloNumVar[I];
            IloNumVar[][] sigma = new IloNumVar[I][J];
            IloNumVar[] moveLower = cplex.boolVarArray(J);
            IloNumVar[] moveUpper = cplex.boolVarArray(J);
            IloNumVar[] alphaWhenLower = new IloNumVar[J];
            IloNumVar[] alphaWhenUpper = new IloNumVar[J];

            for (int j = 0; j < J; j++) {
                double alphaLower = input.alphaLowerBound(j);
                double alphaUpper = params.e[j];
                alpha[j] = cplex.numVar(alphaLower, alphaUpper, "a_" + j);
                alphaWhenLower[j] = binaryProduct(cplex, alpha[j], moveLower[j],
                        alphaLower, alphaUpper, "al_" + j);
                alphaWhenUpper[j] = binaryProduct(cplex, alpha[j], moveUpper[j],
                        alphaLower, alphaUpper, "au_" + j);
                IloLinearNumExpr endpointChoice = cplex.linearNumExpr();
                endpointChoice.addTerm(1.0, moveLower[j]);
                endpointChoice.addTerm(1.0, moveUpper[j]);
                cplex.addLe(endpointChoice, 1.0);
            }

            for (int i = 0; i < I; i++) {
                beta[i] = cplex.numVar(0.0, params.h[i], "b_" + i);
                double carrierBound = 0.0;
                for (int j = 0; j < J; j++) {
                    if (!params.eligible[i][j]) continue;
                    carrierBound = Math.max(carrierBound,
                            Math.max(0.0, params.e[j] + params.h[i] - params.r[i][j]));
                }
                gamma[i] = cplex.numVar(0.0, carrierBound, "g_" + i);
                for (int j = 0; j < J; j++) {
                    if (!params.eligible[i][j]) continue;
                    double laneBound = Math.max(0.0,
                            params.e[j] + params.h[i] - params.r[i][j]);
                    sigma[i][j] = cplex.numVar(0.0, laneBound, "s_" + i + "_" + j);
                    IloLinearNumExpr lhs = cplex.linearNumExpr();
                    lhs.addTerm(1.0, alpha[j]);
                    lhs.addTerm(1.0, beta[i]);
                    lhs.addTerm(-1.0, gamma[i]);
                    lhs.addTerm(-1.0, sigma[i][j]);
                    cplex.addLe(lhs, params.r[i][j]);
                }
            }

            IloLinearNumExpr objective = cplex.linearNumExpr();
            for (int j = 0; j < J; j++) {
                double movementLower = nominal[j] - input.lower[j];
                double movementUpper = input.upper[j] - nominal[j];
                objective.addTerm(nominal[j], alpha[j]);
                objective.addTerm(-movementLower, alphaWhenLower[j]);
                objective.addTerm(-movementLower * eta / input.scale[j], moveLower[j]);
                objective.addTerm(movementUpper, alphaWhenUpper[j]);
                objective.addTerm(-movementUpper * eta / input.scale[j], moveUpper[j]);
            }
            for (int i = 0; i < I; i++) {
                if (y[i] > 0.5) {
                    objective.addTerm(params.p[i], beta[i]);
                    objective.addTerm(-params.M[i], gamma[i]);
                }
                // We use the equivalent fixed lane-cap formulation x_ij <= q_ij.
                // Total capacity sum_j x_ij <= M_i y_i forces x=0 when y_i=0.
                for (int j = 0; j < J; j++) {
                    if (params.eligible[i][j]) {
                        objective.addTerm(-params.q[i][j], sigma[i][j]);
                    }
                }
            }
            cplex.addMaximize(objective);
            double remaining = timeLimitSeconds - (System.nanoTime() - started) / 1e9;
            if (remaining <= 0.0) throw new TimeLimitException("W1 oracle budget exhausted during modeling");
            if (Double.isFinite(remaining)) cplex.setParam(IloCplex.Param.TimeLimit, remaining);
            if (diagnostics != null) diagnostics.begin(cplex, input, sample, y, eta, threads, remaining);
            long optimizerStart = System.nanoTime();
            boolean solved = cplex.solve();
            double optimizerTimeSec = (System.nanoTime() - optimizerStart) / 1e9;
            if (diagnostics != null) diagnostics.end(cplex, solved, optimizerTimeSec);
            if (!solved || (!allowFeasible && cplex.getStatus() != IloCplex.Status.Optimal)) {
                if (String.valueOf(cplex.getCplexStatus()).contains("TimeLim"))
                    throw new TimeLimitException("W1 oracle has no accepted incumbent: " + cplex.getStatus());
                throw new IllegalStateException(
                        "Wasserstein separation failed: " + cplex.getStatus());
            }

            double[] alphaValue = cplex.getValues(alpha);
            double[] yCoefficient = new double[I];
            double constant = 0.0;
            for (int i = 0; i < I; i++) {
                yCoefficient[i] = params.p[i] * cplex.getValue(beta[i])
                        - params.M[i] * cplex.getValue(gamma[i]);
                for (int j = 0; j < J; j++) {
                    if (params.eligible[i][j]) {
                        constant -= params.q[i][j] * cplex.getValue(sigma[i][j]);
                    }
                }
            }

            double[] worstDemand = nominal.clone();
            double etaCoefficient = 0.0;
            for (int j = 0; j < J; j++) {
                if (cplex.getValue(moveLower[j]) > 0.5) {
                    worstDemand[j] = input.lower[j];
                    etaCoefficient -= (nominal[j] - input.lower[j]) / input.scale[j];
                } else if (cplex.getValue(moveUpper[j]) > 0.5) {
                    worstDemand[j] = input.upper[j];
                    etaCoefficient -= (input.upper[j] - nominal[j]) / input.scale[j];
                }
            }
            double affineConstant = constant;
            for (int j = 0; j < J; j++) {
                affineConstant += alphaValue[j] * worstDemand[j];
            }
            return new Result(cplex.getObjValue(), alphaValue, yCoefficient,
                    constant, worstDemand, affineConstant, etaCoefficient,
                    optimizerTimeSec, cplex.getBestObjValue());
        } finally {
            try { cplex.end(); }
            finally { if (diagnostics != null) diagnostics.log.close(); }
        }
    }

    /** Opt-in diagnostics only: no model, solver parameter, or acceptance-policy changes. */
    private static final class Diagnostics {
        private static final AtomicLong SEQUENCE = new AtomicLong();
        private final Path directory;
        private final PrintStream log;
        private final long sequence;

        private Diagnostics(Path directory, PrintStream log) {
            this.directory = directory;
            this.log = log;
            this.sequence = SEQUENCE.incrementAndGet();
        }

        static Diagnostics open() throws Exception {
            String configured = System.getProperty("trb.svu.w1OracleDiagnosticDirectory", "");
            if (configured.isBlank()) return null;
            Path directory = Path.of(configured).toAbsolutePath().normalize();
            Files.createDirectories(directory);
            PrintStream log = new PrintStream(Files.newOutputStream(directory.resolve("oracle.log"),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND), true, StandardCharsets.UTF_8);
            return new Diagnostics(directory, log);
        }

        void begin(IloCplex cplex, WassersteinBoxInput input, int sample, double[] y,
                   double eta, int threads, double remaining) throws Exception {
            // Keep one current model, not a separate large model for every sample/iteration.
            cplex.exportModel(directory.resolve("current_oracle.sav").toString());
            Files.writeString(directory.resolve("current_oracle.txt"),
                    "diagnosticOnly=true\npid=" + ProcessHandle.current().pid()
                    + "\nsequence=" + sequence + "\nstarted=" + Instant.now()
                    + "\nsample=" + sample + "\nradius=" + input.radius + "\neta=" + eta
                    + "\nthreads=" + threads + "\ntimeLimitSeconds=" + remaining
                    + "\ny=" + Arrays.toString(y) + "\nnominal=" + Arrays.toString(input.demand[sample])
                    + "\nprobability=" + input.probability[sample]
                    + "\nlower=" + Arrays.toString(input.lower) + "\nupper=" + Arrays.toString(input.upper)
                    + "\nscale=" + Arrays.toString(input.scale) + "\n", StandardCharsets.UTF_8);
            String marker = String.format(java.util.Locale.ROOT,
                    "W1_ORACLE_BEGIN sequence=%d sample=%d eta=%.17g threads=%d time=%s%n",
                    sequence, sample, eta, threads, Instant.now());
            log.print(marker);
            log.flush();
            System.out.print(marker);
            System.out.flush();
        }

        void end(IloCplex cplex, boolean solved, double seconds) throws Exception {
            log.printf(java.util.Locale.ROOT,
                    "W1_ORACLE_END sequence=%d solved=%s status=%s cplexStatus=%s seconds=%.6f%n",
                    sequence, solved, cplex.getStatus(), cplex.getCplexStatus(), seconds);
            if (solved) log.printf(java.util.Locale.ROOT,
                    "W1_ORACLE_BOUNDS incumbent=%.17g bestBound=%.17g%n",
                    cplex.getObjValue(), cplex.getBestObjValue());
            log.flush();
            System.out.printf(java.util.Locale.ROOT,
                    "W1_ORACLE_END sequence=%d solved=%s status=%s seconds=%.6f%n",
                    sequence, solved, cplex.getStatus(), seconds);
        }
    }

    static final class TimeLimitException extends IllegalStateException {
        TimeLimitException(String message) { super(message); }
    }

    private static IloNumVar binaryProduct(IloCplex cplex,
                                            IloNumVar value,
                                            IloNumVar binary,
                                            double lower,
                                            double upper,
                                            String name) throws Exception {
        IloNumVar product = cplex.numVar(Math.min(0.0, lower),
                Math.max(0.0, upper), name);
        cplex.addLe(product, cplex.prod(upper, binary));
        cplex.addGe(product, cplex.prod(lower, binary));

        IloLinearNumExpr upperLink = cplex.linearNumExpr(-lower);
        upperLink.addTerm(1.0, value);
        upperLink.addTerm(lower, binary);
        cplex.addLe(product, upperLink);

        IloLinearNumExpr lowerLink = cplex.linearNumExpr(-upper);
        lowerLink.addTerm(1.0, value);
        lowerLink.addTerm(upper, binary);
        cplex.addGe(product, lowerLink);
        return product;
    }

    record Result(double value,
                  double[] alpha,
                  double[] yCoefficient,
                  double dualConstant,
                  double[] worstDemand,
                  double affineConstant,
                  double etaCoefficient,
                  double optimizerTimeSec,
                  double upperBound) {
        Result {
            alpha = alpha.clone();
            yCoefficient = yCoefficient.clone();
            worstDemand = worstDemand.clone();
        }
    }
}
