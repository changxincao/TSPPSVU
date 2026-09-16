package Test;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Machine-readable audit of the TRB supplemental-experiment interfaces.
 *
 * <p>This catalog is the single map from P0--P5 and reviewer comment IDs to
 * executable entry points.  Every row states its input, operation, output and
 * whether it starts a first-stage optimization.  A class name in this catalog
 * means that an interface exists; it does not mean that the experiment has
 * already been run or that its numerical result is publication-ready.</p>
 *
 * <p>Status meanings: READY = the stated interface is implemented; PARTIAL =
 * only the stated subset is implemented; PLANNED = deliberately not yet
 * implemented; POSTPROCESS = executable analysis with no new first-stage
 * solve; WRITING_ONLY = response/manuscript work rather than an experiment.</p>
 */
public final class TRBReviewerSupplementalExperimentCatalog {

    public enum Status {
        READY,
        PARTIAL,
        PLANNED,
        POSTPROCESS,
        WRITING_ONLY
    }

    /** One auditable reviewer operation. */
    public record ExperimentSpec(
            String pack,
            String reviewerComments,
            Status status,
            String entryPoint,
            boolean firstStageOptimization,
            String input,
            String operation,
            String output,
            String remainingWork) {
    }

    private TRBReviewerSupplementalExperimentCatalog() {
    }

    /**
     * Returns the frozen interface inventory.  Missing model extensions remain
     * PLANNED instead of being represented by dead runner stubs.
     */
    public static List<ExperimentSpec> specs() {
        return List.of(
                // P0: constraint-(6) correction and core-result reconstruction.
                spec("P0", "R2-3", Status.READY,
                        "Test.BrazilOlistConstraint6EqualityFixedDecisionAudit", false,
                        "Existing 51-week decisions, weekly demand and generated procurement parameters.",
                        "Fix each historical y and compare >= versus = second-stage recourse.",
                        "Per-week excess-allocation, cost and feasibility diagnostics.",
                        "Diagnostic only; it does not rebuild the paper's selected parameters."),
                spec("P0", "R2-3; all numerical comments", Status.READY,
                        "Test.TRBReviewerConstraint6EqualityFullExperiment; "
                                + "Test.BrazilOlistConstraint6EqualityFastReplay; "
                                + "Test.BrazilOlistConstraint6EqualityExactRcsaaGate; "
                                + "Test.BrazilOlistConstraint6EqualityDroGate", true,
                        "The formal weekly input; frozen selections are used only by the diagnostic replay gates.",
                        "Run the existing 35/15 nested-selection and rolling pipeline for D/SAA/CSAA/DRO with demand equality; optionally replay the exact-RCSAA gate.",
                        "Re-selected parameters plus trial-level decisions, objectives, OOS costs, cost components and runtimes.",
                        "Interfaces are implemented; the equality-corrected numerical tables have not yet been run."),

                // P1: alternative kernel under the identical validation protocol.
                spec("P1", "R1-M1; R4-M15", Status.READY,
                        "Test.TRBReviewerKernelAblation", true,
                        "Olist daily demand, carrier count and common trial range.",
                        "Run exponential and Gaussian kernels through the same 35/15 rolling CV; re-select k, C_h and lambda per kernel under demand equality.",
                        "Candidate/selection CSVs plus paired trial costs, decisions, cost components, runtimes and a kernel comparison summary.",
                        "Interface is ready but has not been run; kNN and RF are not part of this kernel-only operation."),

                // P2: fair baselines and mechanism identification.
                spec("P2", "R4-M28", Status.READY,
                        "Test.TRBReviewerSAAWindowSensitivity", true,
                        "Weekly demand and common OOS trial range.",
                        "Solve equal-weight SAA for W=26/39/50 on identical test weeks under demand equality.",
                        "Per-window costs, decisions, components and solve time.",
                        "This is sensitivity, not tuned-window SAA; inner selection of W is separate."),
                spec("P2", "R4-M27", Status.PARTIAL,
                        "scripts/run_olist_v2_knn_saa_baseline.ps1; "
                                + "Test.analysis.synthetic.TRBReviewerOlistDynamicDgpV2Gate", true,
                        "The same standardized lag-demand contexts and frozen train/validation/OOS split.",
                        "Select K from {5,10,20,30,50} on independent calibration markets, then solve kNN-weighted SAA on paired evaluation markets without OOS leakage.",
                        "Calibration rows, chosen K, paired query-level OOS costs, pooled Mean/SD/Q95/CVaR95, decisions, certification and runtime.",
                        "The synthetic multi-market interface and K=S regression are validated, but the formal run and the 51-week Olist rolling kNN-CSAA comparison are still outstanding."),
                spec("P2", "R4-M30", Status.READY,
                        "Test.BrazilOlistR4M30MatchedCardinalityExperiment", true,
                        "The paired Olist rolling instance, context weights and per-week RCSAA robustness level.",
                        "Enumerate exact RCSAA and risk-neutral CSAA at exactly the RCSAA-selected carrier count.",
                        "Paired Mean/SD/Q95/CVaR95, decisions, Jaccard, wins and cost components.",
                        "Implemented and run as a mechanism diagnostic; refresh after final Olist parameter selection."),
                spec("P2/P5", "R4-M31; R4-M14", Status.PARTIAL,
                        "Test.BrazilOlistAdaptiveCVSolveComparison; Test.TRBReviewerBandwidthStability", true,
                        "Nested 35/15 rolling validation candidate and selection CSVs.",
                        "Select k/C_h/lambda without OOS leakage, then postprocess selection frequencies and switching.",
                        "Candidate validation scores, chosen parameters, trial costs and stability summaries.",
                        "Boundary-hit flags, risk-frontier table and validation-curve export are not yet unified."),

                // P3: algorithm/scalability/structural stress.
                spec("P3", "R3-4", Status.READY,
                        "Test.analysis.synthetic.TRBReviewerR3M4CarrierScalability", true,
                        "Frozen lognormal/CV0.30 demand with J=23, S=50, I=10/20/30 and fixed C_h/lambda.",
                        "Run Algorithm 1's primal neighborhood search with fixed kappa=2 under one global time limit; for I=10 optionally verify against enumeration.",
                        "Status, certified gap, nodes, iterations, cuts, candidate count, runtime and enumeration consistency.",
                        "Interface is implemented but formal multi-seed results have not been run."),
                spec("P3", "R4-M22; R4-M33; R4-M34", Status.PARTIAL,
                        "Test.TRBReviewerKappaCarrierScalability", true,
                        "Olist J=23 demand, W=50, carrier grid, kappa grid and fixed model parameters.",
                        "Vary I and kappa under demand equality while keeping J and S fixed.",
                        "Trial objectives, OOS costs, selected carriers, ESS and elapsed time.",
                        "No J/S scaling, 10/I economic scaling, certified gap, iteration, cut, node or candidate counts."),
                spec("P3", "R3-4; R4-M33--M35", Status.PARTIAL,
                        "Test.analysis.synthetic.TRBReviewerSyntheticExperimentMatrix; "
                                + "Test.analysis.synthetic.TRBReviewerSyntheticProcurementFactory; "
                                + "Test.analysis.synthetic.TRBReviewerR3M3SyntheticInstanceBuilder; "
                                + "Test.analysis.synthetic.TRBReviewerR3M3SyntheticSavedInstanceSolveRunner", true,
                        "Frozen synthetic cells on the (I,J,S) staircase and one saved instance per cell.",
                        "Generate scale-comparable procurement parameters and solve one method on each frozen cell.",
                        "Inspectable instances, paired draw-level OOS costs and method solve summaries.",
                        "Matrix/build/one-method interfaces exist; batch orchestration and algorithm gap/cut/node logging are not implemented."),
                spec("P3", "R4-M24", Status.PLANNED,
                        "NOT_IMPLEMENTED: effective-Big-M comparison", true,
                        "Representative frozen instances and analytically derived old/tight M values.",
                        "Solve identical instances with old and tight M and verify objective/decision consistency.",
                        "Root gap, nodes, runtime, warnings and objective equality.",
                        "Derivation and solver-stat export must be frozen before coding."),
                spec("P3", "R4-M6", Status.PARTIAL,
                        "Test.analysis.synthetic.TRBReviewerR4M6CoverageMqcMechanismExperiment", true,
                        "A frozen demand/procurement instance and coverage levels 100/75/50 percent.",
                        "Apply an outcome-independent balanced mask, retain q/r on eligible pairs, and recompute p/h by the frozen generation rules.",
                        "Mean/tail cost, cost components, selected carriers, coverage and runtime by condition.",
                        "Single-instance natural/capacity-normalized mechanism runner is implemented; formal multi-seed coverage results remain to be run."),

                // P4: multi-DGP, multiple independent replications and scale-ready saved instances.
                spec("P4", "R3-3; R4-M9; R4-M11; R4-M32; R4-M35", Status.READY,
                        "Test.analysis.synthetic.TRBReviewerR3M3SyntheticFormalExperiment; "
                                + "Test.analysis.synthetic.TRBReviewerR3M3SyntheticPairedStatistics", true,
                        "Independent calibration seeds plus lognormal/uniform by CV0.15/0.30 evaluation seeds.",
                        "Select k/C_h/lambda only on disjoint calibration replications, freeze them by DGP cell, then run D/SAA/CSAA/DRO/exact-RCSAA on paired saved instances.",
                        "Calibration candidates, frozen selections, immutable instances, per-method timing/status/tail summaries and seed-level paired confidence intervals.",
                        "Formal multi-seed numerical results have not been run; observed-demand-centered perturbation remains optional."),
                spec("P4", "R3-3 reviewer-literal observed-demand-centered check", Status.PLANNED,
                        "NOT_IMPLEMENTED: Olist observed-center OOS evaluator", false,
                        "Frozen Olist decisions and each realized test-week demand.",
                        "Around each observed demand, draw lognormal/uniform perturbations and re-evaluate fixed y only.",
                        "Paired conditional recourse distributions for the literal reviewer suggestion.",
                        "Optional supplement; it must not replace the leakage-free synthetic main DGP."),

                // P5: postprocessing and diagnostic evidence.
                spec("P5", "R4-M9--M11; R4-M32; R4-m8", Status.PARTIAL,
                        "Test.TRBReviewerPairedCostStatistics", false,
                        "Olist method trial CSVs paired by actual test period.",
                        "Compute absolute distributions, paired differences, win rates, randomization p-value and ordinary bootstrap CI.",
                        "Absolute and paired summary/detail CSVs including runtime summaries.",
                        "Olist requires moving/stationary-block bootstrap; synthetic outputs use the dedicated R3-M3 statistics class."),
                spec("P5", "R1-M2; R4-M29", Status.POSTPROCESS,
                        "Test.TRBReviewerOperationalMetrics", false,
                        "Weekly demand and label=trial.csv files containing yBinary.",
                        "Re-evaluate fixed y under demand equality and calculate management/operational metrics; no first-stage re-solve.",
                        "Carrier count, contract/spot volume, MQC shortfall/penalty, capacity utilization and switching.",
                        "Requires exactly the same generated procurement seed/config as the source decisions."),
                spec("P5", "R4-M14; part of R4-M31", Status.PARTIAL,
                        "Test.TRBReviewerBandwidthStability", false,
                        "Per-trial selected k/C_h/lambda CSV.",
                        "Count frequencies, adjacent-window switching and normalized entropy.",
                        "Frequency, transition and stability files.",
                        "Does not yet report configured-boundary hits or validation curves."),
                spec("P5", "R4-M12; R4-M13", Status.PARTIAL,
                        "Test.DataDiagnostics; Test.analysis.covariate.ThetaWeightScan", false,
                        "Weekly demand/context windows and a frozen kernel grid.",
                        "Inspect standardization, weight concentration, ESS and current-context distances.",
                        "Diagnostic CSVs for weight/ESS behavior.",
                        "A dedicated distance-concentration/effective-dimension and per-lane distance-contribution export is still missing; PCA/RF is not required."),
                spec("P5", "R4-M20; R4-M19", Status.PLANNED,
                        "NOT_IMPLEMENTED: unified RCSAA-DRO consistency postprocessor", false,
                        "Paired RCSAA/DRO trial CSVs with yBinary, model objective and OOS costs.",
                        "Compute decision equality, selected-set Jaccard, objective gap and paired OOS gap by lambda.",
                        "Consistency/risk-frontier CSV and claim-scope evidence.",
                        "An old top-five utility has comparison disabled and is not a formal replacement."),
                spec("P5", "R3-2", Status.READY,
                        "Test.SAACSAAPlainBatchRunner; Test.DROBatchRunner; "
                                + "Test.analysis.synthetic.TRBReviewerR3M3SyntheticMainSolve", true,
                        "SAA/CSAA/RCSAA/DRO trial CSVs produced on the same machine and trials.",
                        "Record optimizer wall time for all methods; synthetic outputs additionally separate preparation and OOS evaluation and expose solver status/bound/gap.",
                        "Per-trial solve times plus preparation/optimizer/OOS/total timing where applicable.",
                        "Formal same-machine equality-corrected tables have not yet been run."),

                // Reviewer requests that are not numerical experiments.
                spec("WRITING", "R2-2; R3-1; R4-M16--M18", Status.WRITING_ONLY,
                        "Response letter/manuscript", false,
                        "Current modified-chi-square ambiguity set and literature.",
                        "Explain ambiguity-set choice, fixed-support limitation and lambda interpretation.",
                        "Revised theory/motivation/limitations text.",
                        "Do not implement Wasserstein or moment ambiguity sets in this revision."),
                spec("WRITING", "R3-5; R4-M7; R4-M8; R4-M38", Status.WRITING_ONLY,
                        "Data/reproducibility statement", false,
                        "Demand provenance, generated fields, formulas, seeds, instance files and solver configuration.",
                        "Disclose real/generated percentages and artifact availability policy.",
                        "Structured provenance and reproducibility table.",
                        "Code/data can be committed for release on acceptance/publication rather than sent privately now."),
                spec("WRITING", "R4-M5; R4-M25; R4-M26; R4-M36; R4-M39", Status.WRITING_ONLY,
                        "Scope and limitations section", false,
                        "Current contract, spot-capacity and shared-context assumptions.",
                        "Bound claims and list contract quantity, bundle, reserve capacity, finite spot and lane-specific context as future extensions.",
                        "Revised scope/limitations text.",
                        "No unfrozen finite-spot, PCA/RF or complex-contract model should be coded."));
    }

    private static ExperimentSpec spec(String pack,
                                       String comments,
                                       Status status,
                                       String entryPoint,
                                       boolean solves,
                                       String input,
                                       String operation,
                                       String output,
                                       String remaining) {
        return new ExperimentSpec(pack, comments, status, entryPoint, solves,
                input, operation, output, remaining);
    }

    /**
     * Prints the catalog as CSV, or writes it to args[0] when an output path is
     * supplied.  This command performs no optimization.
     */
    public static void main(String[] args) throws Exception {
        if (args.length > 1) {
            throw new IllegalArgumentException("Usage: [output.csv]");
        }
        if (args.length == 0) {
            writeCsv(new java.io.OutputStreamWriter(System.out, StandardCharsets.UTF_8));
            return;
        }
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        Path parent = output.getParent();
        if (parent != null) Files.createDirectories(parent);
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writeCsv(writer);
        }
    }

    private static void writeCsv(java.io.Writer writer) throws Exception {
        writer.write("pack,reviewerComments,status,entryPoint,firstStageOptimization,input,operation,output,remainingWork\n");
        for (ExperimentSpec spec : specs()) {
            writer.write(csv(spec.pack()));
            writer.write(',');
            writer.write(csv(spec.reviewerComments()));
            writer.write(',');
            writer.write(spec.status().name());
            writer.write(',');
            writer.write(csv(spec.entryPoint()));
            writer.write(',');
            writer.write(Boolean.toString(spec.firstStageOptimization()));
            writer.write(',');
            writer.write(csv(spec.input()));
            writer.write(',');
            writer.write(csv(spec.operation()));
            writer.write(',');
            writer.write(csv(spec.output()));
            writer.write(',');
            writer.write(csv(spec.remainingWork()));
            writer.write('\n');
        }
        writer.flush();
    }

    private static String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
