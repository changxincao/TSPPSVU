package Test.analysis.synthetic;

import Basic.ProcurementParams;
import Basic.Sample;
import Helper.basicHelper.Config;
import Helper.basicHelper.InstanceGenerator;
import Test.analysis.synthetic.TRBReviewerDecisionRelevantDemandGenerator.Mode;
import Test.analysis.synthetic.TRBReviewerDecisionRelevantDemandGenerator.Settings;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.ReplicationData;
import Test.analysis.synthetic.TRBReviewerR3M3SyntheticSavedInstanceSolveRunner.Method;
import Test.analysis.synthetic.TRBReviewerR3M3SyntheticSolveBridge.PreparedInput;
import Test.analysis.synthetic.TRBReviewerSyntheticProcurementFactoryV3.Regime;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Screens several synthetic mechanisms under one unchanged solver interface.
 *
 * <p>Reviewer linkage: this is a diagnostic precursor to R3-3, not a formal
 * paper runner.  It compares smooth group AR, persistent group-share regimes,
 * a cost-aware upper bound, and a cost-independent group-specialized carrier
 * market.  Training seeds and query seeds are separated, so each fixed
 * training sample is evaluated at several independent contexts.</p>
 */
public final class TRBReviewerSyntheticStrategyScreening {
    private static final int I = configuredCarrierCount();
    private static final int J = 23;
    private static final int GROUPS = 4;
    private static final int S = 50;
    private static final long BASELINE_SEED = 20260809L;
    private static final long GROUP_SEED = 20260810L;
    private static final long PROCUREMENT_SEED = 101L;
    private static final double C_H = 1.0;

    private TRBReviewerSyntheticStrategyScreening() {
    }

    /**
     * Usage: {@code <output-dir> [candidate-regex] [training-reps]
     * [queries-per-training] [oos-draws]
     * [DATA_ONLY|FAST|CSAA_ONLY|DRO|DRO_ONLY|EXACT|RCSAA_ONLY] [lambda]
     * [threads] [paper-procurement-seed] [C_h] [seed-offset]}.
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 11) {
            throw new IllegalArgumentException(
                    "Usage: <output-dir> [candidate-regex] [training-reps] "
                            + "[queries-per-training] [oos-draws] "
                            + "[DATA_ONLY|FAST|CSAA_ONLY|DRO|DRO_ONLY|EXACT|RCSAA_ONLY] "
                            + "[lambda] [threads] [paper-procurement-seed] [C_h] [seed-offset]");
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        String candidateRegex = args.length > 1 && !"ALL".equalsIgnoreCase(args[1])
                ? args[1] : ".*";
        int trainingReplications = args.length > 2 ? Integer.parseInt(args[2]) : 2;
        int queriesPerTraining = args.length > 3 ? Integer.parseInt(args[3]) : 10;
        int oosDraws = args.length > 4 ? Integer.parseInt(args[4]) : 200;
        String profile = args.length > 5 ? args[5].toUpperCase(Locale.ROOT) : "FAST";
        double lambda = args.length > 6 ? Double.parseDouble(args[6]) : 0.1;
        int threads = args.length > 7 ? Integer.parseInt(args[7]) : 4;
        int paperProcurementSeed = args.length > 8 ? Integer.parseInt(args[8]) : 0;
        double cH = args.length > 9 ? Double.parseDouble(args[9]) : C_H;
        int seedOffset = args.length > 10 ? Integer.parseInt(args[10]) : 0;
        if (trainingReplications <= 0 || queriesPerTraining <= 0
                || oosDraws <= 0 || threads <= 0 || !(lambda >= 0.0)
                || !(cH > 0.0) || !Double.isFinite(cH) || seedOffset < 0) {
            throw new IllegalArgumentException("Invalid screening counts or parameters.");
        }
        requireEmptyDirectory(root);
        Files.createDirectories(root);

        Method[] methods = methods(profile);
        List<Candidate> candidates = candidates().stream()
                .filter(candidate -> candidate.name.matches(candidateRegex)).toList();
        if (candidates.isEmpty()) throw new IllegalArgumentException("No candidate matches " + candidateRegex);

        List<ResultRow> resultRows = new ArrayList<>();
        List<DiagnosticRow> diagnosticRows = new ArrayList<>();
        Map<String, Candidate> huReferenceCandidates = new LinkedHashMap<>();
        Map<String, Materialized> huReferences = new LinkedHashMap<>();
        Map<String, Candidate> huCapacityReferenceCandidates = new LinkedHashMap<>();
        Map<String, Materialized> huCapacityReferences = new LinkedHashMap<>();
        int huCandidatesValidated = 0;
        for (Candidate candidate : candidates) {
            Materialized materialized = materialize(candidate, paperProcurementSeed);
            if (candidate.market == Market.HU_2016) {
                validateHuMarket(candidate, materialized);
                String capacityProfile = huCapacityProfile(candidate);
                Candidate huReferenceCandidate = huReferenceCandidates.get(capacityProfile);
                Materialized huReference = huReferences.get(capacityProfile);
                if (huReference == null) {
                    huReferenceCandidates.put(capacityProfile, candidate);
                    huReferences.put(capacityProfile, materialized);
                } else {
                    validateHuPairing(huReferenceCandidate, huReference,
                            candidate, materialized);
                }
                String mqcProfile = huMqcProfile(candidate);
                Candidate capacityReferenceCandidate =
                        huCapacityReferenceCandidates.get(mqcProfile);
                Materialized capacityReference = huCapacityReferences.get(mqcProfile);
                if (capacityReference == null) {
                    huCapacityReferenceCandidates.put(mqcProfile, candidate);
                    huCapacityReferences.put(mqcProfile, materialized);
                } else {
                    validateHuCapacityPairing(capacityReferenceCandidate,
                            capacityReference, candidate, materialized);
                }
                huCandidatesValidated++;
            }
            writeCandidateMetadata(root.resolve(candidate.name).resolve("candidate.properties"),
                    candidate, materialized, trainingReplications, queriesPerTraining,
                    oosDraws, profile, lambda, paperProcurementSeed, cH, seedOffset);

            for (int trainingReplication = 1;
                 trainingReplication <= trainingReplications;
                 trainingReplication++) {
                long trainingSeed = 1_000_000L + seedOffset + trainingReplication;
                for (int query = 1; query <= queriesPerTraining; query++) {
                    long querySeed = 2_000_000L
                            + 1000L * (seedOffset + trainingReplication) + query;
                    Settings settings = settings(candidate, oosDraws);
                    ReplicationData demand = TRBReviewerDecisionRelevantDemandGenerator.generate(
                            settings, materialized.baseline, materialized.laneGroups,
                            trainingSeed, querySeed);
                    log1pContexts(demand);
                    diagnosticRows.add(diagnose(candidate.name, trainingReplication,
                            query, demand, materialized.params, threads, cH));

                    for (Method method : methods) {
                        Config config = TRBReviewerR3M3SyntheticSavedInstanceSolveRunner.config(
                                method, settings.observedLagPeriods, cH, lambda, threads, 600);
                        Path output = root.resolve(candidate.name)
                                .resolve(String.format(Locale.US, "train_%02d/query_%03d/%s",
                                        trainingReplication, query, method));
                        TRBReviewerR3M3SyntheticMainSolve.Result result =
                                TRBReviewerR3M3SyntheticMainSolve.run(method.name(),
                                        demand, materialized.params, config, output);
                        resultRows.add(new ResultRow(candidate.name, trainingReplication,
                                query, trainingSeed, querySeed, result));
                    }
                }
            }
        }
        writeResults(root.resolve("screening_results.csv"), resultRows);
        writeDiagnostics(root.resolve("data_diagnostics.csv"), diagnosticRows);
        writeMethodSummary(root.resolve("method_summary.csv"), resultRows);
        writePairSummary(root.resolve("paired_method_comparisons.csv"), resultRows);
        writePooledOosSummary(root.resolve("pooled_oos_method_summary.csv"), root, resultRows);
        if (huCandidatesValidated > 0) {
            Files.write(root.resolve("hu_market_pairing_validation.txt"), List.of(
                    "status=PASSED",
                    "carrierCount=" + I,
                    "huMarketSizeNormalization=" + huMarketSizeScale(),
                    "huCandidatesValidated=" + huCandidatesValidated,
                    "fixedWithinCapacityProfileAcrossMqcCells="
                            + "baseline,laneGroups,carriers,eligibility,rates,"
                            + "capacities,spotRates,h,alpha,beta",
                    "fixedAcrossCapacityProfilesWithinMqcCell="
                            + "baseline,laneGroups,carriers,eligibility,rates,"
                            + "spotRates,h,mqc,alpha,beta; only capacities change",
                    "mqcPairing=same carrier-specific uniform quantile across "
                            + "all selected U[0.1,mqcHigh] profiles",
                    "capacityPairing=same lane-specific uniform quantile under "
                            + "U[0.3d_j,0.5d_j], U[0.55d_j,0.75d_j], "
                            + "and U[0.8d_j,1.0d_j]"),
                    StandardCharsets.UTF_8);
        }
    }

    private static List<Candidate> candidates() {
        List<Candidate> candidates = new ArrayList<>(List.of(
                new Candidate("C0_COMMON_RANDOM", Mode.LINEAR_GROUP_AR,
                        0.20, 0.60, 0.00, 0.20, Grouping.EXOGENOUS, Market.RANDOM_V3),
                new Candidate("C1_GROUP_AR_RANDOM", Mode.LINEAR_GROUP_AR,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS, Market.RANDOM_V3),
                new Candidate("C2_GROUP_AR_STRONG_RANDOM", Mode.LINEAR_GROUP_AR,
                        0.20, 0.20, 0.50, 0.10, Grouping.EXOGENOUS, Market.RANDOM_V3),
                new Candidate("C3_MARKOV_RANDOM", Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS, Market.RANDOM_V3),
                new Candidate("C4_MARKOV_COST_AWARE", Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.COST_AWARE, Market.RANDOM_V3),
                new Candidate("C5_COMMON_SPECIALIZED", Mode.LINEAR_GROUP_AR,
                        0.20, 0.60, 0.00, 0.20, Grouping.EXOGENOUS, Market.GROUP_SPECIALIZED),
                new Candidate("C6_GROUP_AR_SPECIALIZED", Mode.LINEAR_GROUP_AR,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS, Market.GROUP_SPECIALIZED),
                new Candidate("C7_MARKOV_SPECIALIZED", Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS, Market.GROUP_SPECIALIZED),
                new Candidate("C8_GROUP_AR_HIGH_CV_RANDOM", Mode.LINEAR_GROUP_AR,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS, Market.RANDOM_V3),
                new Candidate("C9_COMMON_RANDOM_MIN_H", Mode.LINEAR_GROUP_AR,
                        0.20, 0.60, 0.00, 0.20, Grouping.EXOGENOUS, Market.RANDOM_V3),
                new Candidate("C10_GROUP_AR_RANDOM_MIN_H", Mode.LINEAR_GROUP_AR,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS, Market.RANDOM_V3),
                new Candidate("C11_COMMON_RANDOM_MAX_H", Mode.LINEAR_GROUP_AR,
                        0.20, 0.60, 0.00, 0.20, Grouping.EXOGENOUS, Market.RANDOM_V3),
                new Candidate("C12_GROUP_AR_RANDOM_MAX_H", Mode.LINEAR_GROUP_AR,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS, Market.RANDOM_V3),
                new Candidate("C13_COMMON_PAPER_MARKET", Mode.LINEAR_GROUP_AR,
                        0.20, 0.60, 0.00, 0.20, Grouping.EXOGENOUS, Market.PAPER_DEFAULT),
                new Candidate("C14_GROUP_AR_PAPER_MARKET", Mode.LINEAR_GROUP_AR,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS, Market.PAPER_DEFAULT),
                // Hu et al. (2016) procurement market: each carrier covers a
                // U[0.1,0.8] share of lanes; MQC is U[0.1,b] of its eligible
                // baseline demand, with b in {0.2,0.4,0.6}.  The R7 demand
                // process is held fixed; paired MIN_H/MAX_H candidates expose
                // the MQC-penalty mechanism without changing other fields.
                new Candidate("HU_R7_COUPLED3_MQC20_MIN_H", Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS, Market.HU_2016),
                new Candidate("HU_R7_COUPLED3_MQC40_MIN_H", Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS, Market.HU_2016),
                new Candidate("HU_R7_COUPLED3_MQC60_MIN_H", Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS, Market.HU_2016),
                new Candidate("HU_R7_COUPLED3_CAP55_75_MQC20_MIN_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS, Market.HU_2016),
                new Candidate("HU_R7_COUPLED3_CAP55_75_MQC40_MIN_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS, Market.HU_2016),
                new Candidate("HU_R7_COUPLED3_CAP55_75_MQC60_MIN_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS, Market.HU_2016),
                new Candidate("HU_R7_COUPLED3_MQC20_MAX_H", Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS, Market.HU_2016),
                new Candidate("HU_R7_COUPLED3_MQC40_MAX_H", Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS, Market.HU_2016),
                new Candidate("HU_R7_COUPLED3_MQC60_MAX_H", Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS, Market.HU_2016),
                new Candidate("HU_R7_COUPLED3_CAP55_75_MQC20_MAX_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS, Market.HU_2016),
                new Candidate("HU_R7_COUPLED3_CAP55_75_MQC25_MAX_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS, Market.HU_2016),
                new Candidate("HU_R7_COUPLED3_CAP55_75_MQC30_MAX_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS, Market.HU_2016),
                new Candidate("HU_R7_COUPLED3_CAP55_75_MQC35_MAX_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS, Market.HU_2016),
                new Candidate("HU_R7_COUPLED3_CAP55_75_MQC40_MAX_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS, Market.HU_2016),
                new Candidate("HU_R7_COUPLED3_CAP55_75_MQC60_MAX_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS, Market.HU_2016),
                new Candidate("HU_R7_COUPLED3_CAP80_100_MQC20_MIN_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS, Market.HU_2016),
                new Candidate("HU_R7_COUPLED3_CAP80_100_MQC40_MIN_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS, Market.HU_2016),
                new Candidate("HU_R7_COUPLED3_CAP80_100_MQC60_MIN_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS, Market.HU_2016),
                new Candidate("HU_R7_COUPLED3_CAP80_100_MQC20_MAX_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS, Market.HU_2016),
                new Candidate("HU_R7_COUPLED3_CAP80_100_MQC40_MAX_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS, Market.HU_2016),
                new Candidate("HU_R7_COUPLED3_CAP80_100_MQC60_MAX_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS, Market.HU_2016),
                // Predeclared demand-mechanism checks under one fixed min-rate market.
                new Candidate("G1_COMMON_PAPER_P125_Q100_MIN_H", Mode.LINEAR_GROUP_AR,
                        0.20, 0.60, 0.00, 0.20, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.25, 1.00),
                new Candidate("G2_GROUP_STRONG_PAPER_P125_Q100_MIN_H", Mode.LINEAR_GROUP_AR,
                        0.20, 0.20, 0.50, 0.10, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.25, 1.00),
                new Candidate("G3_MARKOV_PAPER_P125_Q100_MIN_H", Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.25, 1.00),
                new Candidate("G4_GROUP_HIGH_CV_PAPER_P125_Q100_MIN_H", Mode.LINEAR_GROUP_AR,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.25, 1.00),
                new Candidate("G5_COMMON_HIGH_CV_PAPER_P125_Q100_MIN_H", Mode.LINEAR_GROUP_AR,
                        0.20, 0.60, 0.00, 0.20, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.25, 1.00),
                new Candidate("G6_GROUP_STRONG_HIGH_CV_PAPER_P125_Q100_MIN_H", Mode.LINEAR_GROUP_AR,
                        0.20, 0.20, 0.50, 0.10, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.25, 1.00),
                new Candidate("G7_MARKOV_HIGH_CV_PAPER_P125_Q100_MIN_H", Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.25, 1.00),
                new Candidate("G8_MARKOV_CALIBRATED_CV_PAPER_P125_Q100_MIN_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.25, 1.00),
                // Mechanism checks: keep G8's market and calibrated CV fixed,
                // changing only regime predictability and lane-share contrast.
                new Candidate("H1_MARKOV_PERSISTENT_CALIBRATED_CV_PAPER_P125_Q100_MIN_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.25, 1.00),
                new Candidate("H2_MARKOV_STRONG_SHARE_CALIBRATED_CV_PAPER_P125_Q100_MIN_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.25, 1.00),
                new Candidate("H3_MARKOV_PERSISTENT_STRONG_SHARE_CALIBRATED_CV_PAPER_P125_Q100_MIN_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.25, 1.00),
                // Reviewer low-volatility level fixed at half the earlier
                // Olist-calibrated innovation CV; the market remains G8's.
                new Candidate("L1_MARKOV_LOW_CV_PAPER_P125_Q100_MIN_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.25, 1.00),
                new Candidate("L2_MARKOV_STRONG_SHARE_LOW_CV_PAPER_P125_Q100_MIN_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.25, 1.00),
                new Candidate("L3_MARKOV_STRONG_SHARE_LOW_CV_PAPER_P150_Q100_MIN_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.50, 1.00),
                new Candidate("L4_MARKOV_STRONG_SHARE_LOW_CV_PAPER_P125_Q090_MIN_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.25, 0.90),
                new Candidate("L5_MARKOV_STRONG_SHARE_LOW_CV_PAPER_P150_Q090_MIN_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.50, 0.90),
                // Total-demand persistence gate. M2 is deliberately identical
                // to L2 and acts as a regression control.
                new Candidate("M1_MARKOV_STRONG_SHARE_LOW_CV_TOTAL_AR60_PAPER_P125_Q100_MIN_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.25, 1.00),
                new Candidate("M2_MARKOV_STRONG_SHARE_LOW_CV_TOTAL_AR80_PAPER_P125_Q100_MIN_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.25, 1.00),
                new Candidate("M3_MARKOV_STRONG_SHARE_LOW_CV_TOTAL_AR90_PAPER_P125_Q100_MIN_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.25, 1.00),
                // Common-shock gate at the empirically plausible total-history
                // coefficient 0.85; lane marginal CV remains unchanged.
                new Candidate("N1_MARKOV_STRONG_SHARE_LOW_CV_TOTAL_AR85_CORR10_PAPER_P125_Q100_MIN_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.25, 1.00),
                new Candidate("N2_MARKOV_STRONG_SHARE_LOW_CV_TOTAL_AR85_CORR30_PAPER_P125_Q100_MIN_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.25, 1.00),
                new Candidate("N3_MARKOV_STRONG_SHARE_LOW_CV_TOTAL_AR85_CORR50_PAPER_P125_Q100_MIN_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.25, 1.00),
                // Predeclared low/normal/high total-demand regime amplitudes.
                // The total regime replaces the smooth total AR for these candidates.
                new Candidate("O1_MARKOV_TOTAL_LEVEL120_STRONG_SHARE_LOW_CV_CORR30_PAPER_P125_Q100_MIN_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.25, 1.00),
                new Candidate("O2_MARKOV_TOTAL_LEVEL130_STRONG_SHARE_LOW_CV_CORR30_PAPER_P125_Q100_MIN_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.25, 1.00),
                new Candidate("O3_MARKOV_TOTAL_LEVEL140_STRONG_SHARE_LOW_CV_CORR30_PAPER_P125_Q100_MIN_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.25, 1.00),
                new Candidate("P1_MARKOV_TOTAL_ORDERED_LEVEL120_STRONG_SHARE_LOW_CV_CORR30_PAPER_P125_Q100_MIN_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.25, 1.00),
                new Candidate("P2_MARKOV_TOTAL_ORDERED_LEVEL130_STRONG_SHARE_LOW_CV_CORR30_PAPER_P125_Q100_MIN_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.25, 1.00),
                new Candidate("P3_MARKOV_TOTAL_ORDERED_LEVEL140_STRONG_SHARE_LOW_CV_CORR30_PAPER_P125_Q100_MIN_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.25, 1.00),
                new Candidate("Q1_MARKOV_TOTAL_ORDERED_LEVEL130_TOTAL_PERSIST90_STRONG_SHARE_LOW_CV_CORR30_PAPER_P125_Q100_MIN_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.25, 1.00),
                new Candidate("Q2_MARKOV_TOTAL_ORDERED_LEVEL130_TOTAL_PERSIST95_STRONG_SHARE_LOW_CV_CORR30_PAPER_P125_Q100_MIN_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.25, 1.00),
                // Parsimony checks for Q2.  Both candidates remove the
                // lane-share regime, leaving exactly one scalar dynamic state.
                new Candidate("R1_TOTAL_ORDERED_MARKOV_ONLY_LEVEL130_TOTAL_PERSIST95_NO_SHARE_LOW_CV_CORR30_PAPER_P125_Q100_MIN_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.25, 1.00),
                new Candidate("R2_TOTAL_AR_ONLY_TOTAL_AR85_NO_SHARE_LOW_CV_CORR30_PAPER_P125_Q100_MIN_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.25, 1.00),
                // One-equation distributed-lag autoregressions.  No latent
                // regimes or lane groups enter the demand mean.
                new Candidate("R3_LANE_AR60_ONLY_LOW_CV_CORR30_PAPER_P125_Q100_MIN_H",
                        Mode.LINEAR_GROUP_AR,
                        0.40, 0.00, 0.00, 0.60, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.25, 1.00),
                new Candidate("R4_LANE_AR80_ONLY_LOW_CV_CORR30_PAPER_P125_Q100_MIN_H",
                        Mode.LINEAR_GROUP_AR,
                        0.20, 0.00, 0.00, 0.80, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.25, 1.00),
                new Candidate("R5_LANE_AR80_ONLY_CALIBRATED_CV_CORR30_PAPER_P125_Q100_MIN_H",
                        Mode.LINEAR_GROUP_AR,
                        0.20, 0.00, 0.00, 0.80, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.25, 1.00),
                new Candidate("R6_COUPLED3_TOTAL_ORDERED_LEVEL130_TOTAL_PERSIST95_DIRECT_SHARE2_LOW_CV_CORR30_PAPER_P125_Q100_MIN_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.25, 1.00),
                new Candidate("R7_COUPLED3_TOTAL_ORDERED_LEVEL130_TOTAL_PERSIST95_DIRECT_SHARE2_CALIBRATED_CV_CORR30_PAPER_P125_Q100_MIN_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.25, 1.00),
                // Predeclared stability matrix.  It crosses two demand
                // structures, two innovation levels, two MQC scales, and the
                // min/max eligible-rate penalty without changing the market,
                // random streams, capacity, or solver configuration.
                new Candidate("STAB_LANE_AR80_LOW_CV_P050_Q100_MIN_H",
                        Mode.LINEAR_GROUP_AR,
                        0.20, 0.00, 0.00, 0.80, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 0.50, 1.00),
                new Candidate("STAB_LANE_AR80_LOW_CV_P050_Q100_MAX_H",
                        Mode.LINEAR_GROUP_AR,
                        0.20, 0.00, 0.00, 0.80, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 0.50, 1.00),
                new Candidate("STAB_LANE_AR80_LOW_CV_P100_Q100_MIN_H",
                        Mode.LINEAR_GROUP_AR,
                        0.20, 0.00, 0.00, 0.80, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.00, 1.00),
                new Candidate("STAB_LANE_AR80_LOW_CV_P100_Q100_MAX_H",
                        Mode.LINEAR_GROUP_AR,
                        0.20, 0.00, 0.00, 0.80, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.00, 1.00),
                new Candidate("STAB_LANE_AR80_CALIBRATED_CV_P050_Q100_MIN_H",
                        Mode.LINEAR_GROUP_AR,
                        0.20, 0.00, 0.00, 0.80, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 0.50, 1.00),
                new Candidate("STAB_LANE_AR80_CALIBRATED_CV_P050_Q100_MAX_H",
                        Mode.LINEAR_GROUP_AR,
                        0.20, 0.00, 0.00, 0.80, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 0.50, 1.00),
                new Candidate("STAB_LANE_AR80_CALIBRATED_CV_P100_Q100_MIN_H",
                        Mode.LINEAR_GROUP_AR,
                        0.20, 0.00, 0.00, 0.80, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.00, 1.00),
                new Candidate("STAB_LANE_AR80_CALIBRATED_CV_P100_Q100_MAX_H",
                        Mode.LINEAR_GROUP_AR,
                        0.20, 0.00, 0.00, 0.80, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.00, 1.00),
                new Candidate("STAB_COUPLED3_TOTAL_ORDERED_LEVEL130_TOTAL_PERSIST95_DIRECT_SHARE2_LOW_CV_CORR30_P050_Q100_MIN_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 0.50, 1.00),
                new Candidate("STAB_COUPLED3_TOTAL_ORDERED_LEVEL130_TOTAL_PERSIST95_DIRECT_SHARE2_LOW_CV_CORR30_P050_Q100_MAX_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 0.50, 1.00),
                new Candidate("STAB_COUPLED3_TOTAL_ORDERED_LEVEL130_TOTAL_PERSIST95_DIRECT_SHARE2_LOW_CV_CORR30_P100_Q100_MIN_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.00, 1.00),
                new Candidate("STAB_COUPLED3_TOTAL_ORDERED_LEVEL130_TOTAL_PERSIST95_DIRECT_SHARE2_LOW_CV_CORR30_P100_Q100_MAX_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.00, 1.00),
                new Candidate("STAB_COUPLED3_TOTAL_ORDERED_LEVEL130_TOTAL_PERSIST95_DIRECT_SHARE2_CALIBRATED_CV_CORR30_P050_Q100_MIN_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 0.50, 1.00),
                new Candidate("STAB_COUPLED3_TOTAL_ORDERED_LEVEL130_TOTAL_PERSIST95_DIRECT_SHARE2_CALIBRATED_CV_CORR30_P050_Q100_MAX_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 0.50, 1.00),
                new Candidate("STAB_COUPLED3_TOTAL_ORDERED_LEVEL130_TOTAL_PERSIST95_DIRECT_SHARE2_CALIBRATED_CV_CORR30_P100_Q100_MIN_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.00, 1.00),
                new Candidate("STAB_COUPLED3_TOTAL_ORDERED_LEVEL130_TOTAL_PERSIST95_DIRECT_SHARE2_CALIBRATED_CV_CORR30_P100_Q100_MAX_H",
                        Mode.MARKOV_GROUP_SHARE,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, 1.00, 1.00)));
        double[] mqcScales = {0.25, 0.50, 0.75, 1.00, 1.25, 1.50, 2.00};
        double[] capacityScales = {0.75, 0.90, 1.00, 1.10, 1.25};
        for (double mqcScale : mqcScales) {
            for (double capacityScale : capacityScales) {
                candidates.add(new Candidate(String.format(Locale.US,
                        "F_C14_P%03d_Q%03d", Math.round(100 * mqcScale),
                        Math.round(100 * capacityScale)), Mode.LINEAR_GROUP_AR,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, mqcScale, capacityScale));
                candidates.add(new Candidate(String.format(Locale.US,
                        "F_C14_P%03d_Q%03d_MIN_H", Math.round(100 * mqcScale),
                        Math.round(100 * capacityScale)), Mode.LINEAR_GROUP_AR,
                        0.20, 0.30, 0.35, 0.15, Grouping.EXOGENOUS,
                        Market.PAPER_DEFAULT, mqcScale, capacityScale));
            }
        }
        return List.copyOf(candidates);
    }

    private static Materialized materialize(Candidate candidate, int paperProcurementSeed) {
        double[] baseline = TRBReviewerGroupSpecializedProcurementFactory.moderateBaseline(
                J, 2300.0, 0.65, BASELINE_SEED);
        int demandGroups = candidate.name.contains("COUPLED3") ? 3 : GROUPS;
        int[] exogenousGroups = TRBReviewerGroupSpecializedProcurementFactory.balancedLaneGroups(
                J, demandGroups, GROUP_SEED);
        ProcurementParams randomMarket = TRBReviewerSyntheticProcurementFactoryV3.generate(
                baseline, I, 0.75, Regime.MQC_VERY_TIGHT, PROCUREMENT_SEED).params();
        int[] groups = candidate.grouping == Grouping.COST_AWARE
                ? TRBReviewerGroupSpecializedProcurementFactory.costAwareLaneGroups(
                        randomMarket, GROUPS, GROUP_SEED)
                : exogenousGroups;
        ProcurementParams params;
        if (candidate.market == Market.GROUP_SPECIALIZED) {
            params = TRBReviewerGroupSpecializedProcurementFactory.generate(
                    baseline, groups, I, PROCUREMENT_SEED);
        } else if (candidate.market == Market.PAPER_DEFAULT
                || candidate.market == Market.HU_2016) {
            Config config = new Config();
            config.seed = paperProcurementSeed;
            InstanceGenerator.GenConfig generation = new InstanceGenerator.GenConfig();
            if (candidate.market == Market.HU_2016) {
                generation.coverAllLanes = false;
                generation.coverPctLow = 0.10;
                generation.coverPctHigh = 0.80;
                generation.mqcLow = 0.10;
                generation.mqcHigh = huMqcHigh(candidate);
                if (candidate.name.contains("CAP55_75")) {
                    double[] bounds = huCapacityBounds(candidate);
                    generation.capacityFactorLow = bounds[0];
                    generation.capacityFactorHigh = bounds[1];
                } else {
                    generation.capacityMode = huCapacityMode(candidate);
                }
            }
            params = InstanceGenerator.generate(
                    I, baseline, generation, config);
            if (candidate.market == Market.HU_2016 && huMarketSizeScale() != 1.0) {
                params = withMqcAndCapacityScales(params,
                        huMarketSizeScale(), huMarketSizeScale());
            }
        } else {
            params = randomMarket;
        }
        if (candidate.mqcScale != 1.0 || candidate.capacityScale != 1.0) {
            params = withMqcAndCapacityScales(
                    params, candidate.mqcScale, candidate.capacityScale);
        }
        if (candidate.name.endsWith("_MIN_H")) params = withMinimumRatePenalty(params);
        if (candidate.name.endsWith("_MAX_H")) params = withMaximumRatePenalty(params);
        return new Materialized(baseline, groups, params);
    }

    /** Replaces only h_i by the minimum eligible contract rate of carrier i. */
    private static ProcurementParams withMinimumRatePenalty(ProcurementParams source) {
        double[] penalty = new double[source.I];
        for (int i = 0; i < source.I; i++) {
            double minimum = Double.POSITIVE_INFINITY;
            for (int j = 0; j < source.J; j++) {
                if (source.eligible[i][j]) minimum = Math.min(minimum, source.r[i][j]);
            }
            if (!Double.isFinite(minimum)) {
                throw new IllegalStateException("Carrier has no eligible lane: " + i);
            }
            penalty[i] = minimum;
        }
        return new ProcurementParams(source.carriers, source.J,
                source.e.clone(), source.p.clone(), penalty,
                clone(source.q), clone(source.r), clone(source.eligible),
                source.alpha, source.beta);
    }

    /** Restores the paper instance rule h_i=max eligible r_ij. */
    private static ProcurementParams withMaximumRatePenalty(ProcurementParams source) {
        double[] penalty = new double[source.I];
        for (int i = 0; i < source.I; i++) {
            double maximum = Double.NEGATIVE_INFINITY;
            for (int j = 0; j < source.J; j++) {
                if (source.eligible[i][j]) maximum = Math.max(maximum, source.r[i][j]);
            }
            if (!Double.isFinite(maximum)) {
                throw new IllegalStateException("Carrier has no eligible lane: " + i);
            }
            penalty[i] = maximum;
        }
        return new ProcurementParams(source.carriers, source.J,
                source.e.clone(), source.p.clone(), penalty,
                clone(source.q), clone(source.r), clone(source.eligible),
                source.alpha, source.beta);
    }

    /** Scales only MQC quantities p_i and lane capacities q_ij. */
    private static ProcurementParams withMqcAndCapacityScales(ProcurementParams source,
                                                               double mqcScale,
                                                               double capacityScale) {
        double[] mqc = source.p.clone();
        double[][] capacity = clone(source.q);
        for (int i = 0; i < source.I; i++) {
            mqc[i] *= mqcScale;
            for (int j = 0; j < source.J; j++) capacity[i][j] *= capacityScale;
        }
        return new ProcurementParams(source.carriers, source.J,
                source.e.clone(), mqc, source.h.clone(), capacity,
                clone(source.r), clone(source.eligible), source.alpha, source.beta);
    }

    private static void validateHuPairing(Candidate referenceCandidate,
                                          Materialized reference,
                                          Candidate candidate,
                                          Materialized current) {
        if (!Arrays.equals(reference.baseline, current.baseline)
                || !Arrays.equals(reference.laneGroups, current.laneGroups)) {
            throw new IllegalStateException("HU cells do not share demand structure.");
        }
        ProcurementParams left = reference.params;
        ProcurementParams right = current.params;
        if (!left.carriers.equals(right.carriers) || left.alpha != right.alpha
                || left.beta != right.beta || !Arrays.equals(left.e, right.e)
                || !Arrays.equals(left.h, right.h)
                || !Arrays.deepEquals(left.eligible, right.eligible)
                || !Arrays.deepEquals(left.r, right.r)
                || !Arrays.deepEquals(left.q, right.q)) {
            throw new IllegalStateException(
                    "HU MQC cells changed a frozen procurement field.");
        }
        double referenceHigh = huMqcHigh(referenceCandidate);
        double currentHigh = huMqcHigh(candidate);
        double marketScale = huMarketSizeScale();
        for (int i = 0; i < left.I; i++) {
            double eligibleDemand = 0.0;
            int eligibleCount = 0;
            for (int j = 0; j < left.J; j++) {
                if (left.eligible[i][j]) {
                    eligibleDemand += reference.baseline[j];
                    eligibleCount++;
                }
            }
            int minimumCount = Math.max(1, (int) Math.round(0.10 * left.J));
            int maximumCount = Math.max(1, (int) Math.round(0.80 * left.J));
            if (eligibleCount < minimumCount || eligibleCount > maximumCount) {
                throw new IllegalStateException("HU coverage outside configured bounds.");
            }
            double referenceQuantile = (left.p[i] / eligibleDemand / marketScale - 0.10)
                    / (referenceHigh - 0.10);
            double currentQuantile = (right.p[i] / eligibleDemand / marketScale - 0.10)
                    / (currentHigh - 0.10);
            if (Math.abs(referenceQuantile - currentQuantile) > 1.0e-12) {
                throw new IllegalStateException(
                        "HU MQC cells do not reuse the same uniform draw.");
            }
        }
    }

    private static void validateHuCapacityPairing(Candidate referenceCandidate,
                                                  Materialized reference,
                                                  Candidate candidate,
                                                  Materialized current) {
        if (!huMqcProfile(referenceCandidate).equals(huMqcProfile(candidate))) {
            throw new IllegalStateException("HU capacity pairing changed the MQC profile.");
        }
        if (!Arrays.equals(reference.baseline, current.baseline)
                || !Arrays.equals(reference.laneGroups, current.laneGroups)) {
            throw new IllegalStateException("HU capacity cells do not share demand structure.");
        }
        ProcurementParams left = reference.params;
        ProcurementParams right = current.params;
        if (!left.carriers.equals(right.carriers) || left.alpha != right.alpha
                || left.beta != right.beta || !Arrays.equals(left.e, right.e)
                || !Arrays.equals(left.p, right.p) || !Arrays.equals(left.h, right.h)
                || !Arrays.deepEquals(left.eligible, right.eligible)
                || !Arrays.deepEquals(left.r, right.r)) {
            throw new IllegalStateException(
                    "HU capacity cells changed a non-capacity procurement field.");
        }
        double[] referenceBounds = huCapacityBounds(referenceCandidate);
        double[] currentBounds = huCapacityBounds(candidate);
        double marketScale = huMarketSizeScale();
        for (int i = 0; i < left.I; i++) {
            for (int j = 0; j < left.J; j++) {
                if (!left.eligible[i][j]) continue;
                double demand = reference.baseline[j];
                double referenceQuantile = (left.q[i][j] / demand / marketScale
                        - referenceBounds[0])
                        / (referenceBounds[1] - referenceBounds[0]);
                double currentQuantile = (right.q[i][j] / demand / marketScale
                        - currentBounds[0])
                        / (currentBounds[1] - currentBounds[0]);
                if (Math.abs(referenceQuantile - currentQuantile) > 1.0e-12) {
                    throw new IllegalStateException(
                            "HU capacity cells do not reuse the same uniform draw.");
                }
            }
        }
    }

    private static void validateHuMarket(Candidate candidate, Materialized market) {
        ProcurementParams params = market.params;
        double mqcHigh = huMqcHigh(candidate);
        double[] capacityBounds = huCapacityBounds(candidate);
        double marketScale = huMarketSizeScale();
        int minimumCount = Math.max(1, (int) Math.round(0.10 * params.J));
        int maximumCount = Math.max(1, (int) Math.round(0.80 * params.J));
        for (int i = 0; i < params.I; i++) {
            int eligibleCount = 0;
            double eligibleDemand = 0.0;
            double minimumRate = Double.POSITIVE_INFINITY;
            double maximumRate = Double.NEGATIVE_INFINITY;
            for (int j = 0; j < params.J; j++) {
                if (!params.eligible[i][j]) continue;
                eligibleCount++;
                eligibleDemand += market.baseline[j];
                minimumRate = Math.min(minimumRate, params.r[i][j]);
                maximumRate = Math.max(maximumRate, params.r[i][j]);
                double capacityRatio = params.q[i][j] / market.baseline[j];
                if (capacityRatio < marketScale * capacityBounds[0] - 1.0e-12
                        || capacityRatio > marketScale * capacityBounds[1] + 1.0e-12) {
                    throw new IllegalStateException("HU capacity outside configured bounds.");
                }
            }
            if (eligibleCount < minimumCount || eligibleCount > maximumCount) {
                throw new IllegalStateException("HU coverage outside configured bounds.");
            }
            double mqcRatio = params.p[i] / eligibleDemand;
            if (mqcRatio < marketScale * 0.10 - 1.0e-12
                    || mqcRatio > marketScale * mqcHigh + 1.0e-12) {
                throw new IllegalStateException("HU MQC outside configured bounds.");
            }
            double expectedPenalty = candidate.name.endsWith("_MAX_H")
                    ? maximumRate : minimumRate;
            if (Math.abs(params.h[i] - expectedPenalty) > 1.0e-12) {
                throw new IllegalStateException(
                        "HU h_i does not match the declared MIN_H/MAX_H rule.");
            }
        }
    }

    private static double[][] clone(double[][] matrix) {
        double[][] copy = new double[matrix.length][];
        for (int row = 0; row < matrix.length; row++) copy[row] = matrix[row].clone();
        return copy;
    }

    private static boolean[][] clone(boolean[][] matrix) {
        boolean[][] copy = new boolean[matrix.length][];
        for (int row = 0; row < matrix.length; row++) copy[row] = matrix[row].clone();
        return copy;
    }

    private static Settings settings(Candidate candidate, int oosDraws) {
        if (candidate.market == Market.HU_2016) {
            return TRBReviewerR7CoverageMqcGridExperiment.r7Settings(oosDraws);
        }
        Settings settings = new Settings();
        settings.mode = candidate.mode;
        settings.trainingSampleCount = S;
        settings.observedLagPeriods = 3;
        settings.warmupPeriods = 60;
        settings.oosSampleCount = oosDraws;
        // The high-volatility candidates use one predeclared reviewer level;
        // the calibrated candidate uses the earlier Olist moment estimate.
        // No outcome-driven CV grid is searched here.
        settings.innovationCv = innovationCv(candidate);
        settings.latentCrossLaneCorrelation = latentCrossLaneCorrelation(candidate);
        settings.longRunWeight = candidate.longRunWeight;
        settings.globalHistoryWeight = candidate.globalWeight;
        settings.groupHistoryWeight = candidate.groupWeight;
        settings.laneHistoryWeight = candidate.laneWeight;
        settings.regimePersistence = regimePersistence(candidate);
        settings.activeGroupMultiplier = activeGroupMultiplier(candidate);
        settings.inactiveGroupMultiplier = inactiveGroupMultiplier(candidate);
        settings.regimeShareWeight = regimeShareWeight(candidate);
        settings.markovTotalHistoryWeight = markovTotalHistoryWeight(candidate);
        settings.totalRegimeLogStep = totalRegimeLogStep(candidate);
        settings.totalRegimePersistence = totalRegimePersistence(candidate);
        settings.orderedTotalRegimeTransitions = candidate.name.contains("TOTAL_ORDERED");
        settings.coupledTotalAndShareRegime = candidate.name.contains("COUPLED3");
        return settings;
    }

    private static double innovationCv(Candidate candidate) {
        if (candidate.name.contains("LOW_CV")) return 0.1725;
        if (candidate.name.contains("CALIBRATED_CV")) return 0.345;
        return candidate.name.contains("HIGH_CV") ? 0.45 : 0.30;
    }

    private static double regimePersistence(Candidate candidate) {
        return candidate.name.contains("PERSISTENT") ? 0.90 : 0.85;
    }

    private static double activeGroupMultiplier(Candidate candidate) {
        if (candidate.name.contains("DIRECT_SHARE2")) return 2.00;
        return candidate.name.contains("STRONG_SHARE") ? 2.00 : 1.60;
    }

    private static double inactiveGroupMultiplier(Candidate candidate) {
        if (candidate.name.contains("DIRECT_SHARE2")) return 1.00;
        return candidate.name.contains("STRONG_SHARE") ? 0.65 : 0.80;
    }

    private static double regimeShareWeight(Candidate candidate) {
        if (candidate.name.contains("NO_SHARE")) return 0.0;
        if (candidate.name.contains("DIRECT_SHARE2")) return 1.0;
        return candidate.name.contains("STRONG_SHARE") ? 0.75 : 0.60;
    }

    private static double markovTotalHistoryWeight(Candidate candidate) {
        if (candidate.name.contains("TOTAL_AR60")) return 0.60;
        if (candidate.name.contains("TOTAL_AR85")) return 0.85;
        if (candidate.name.contains("TOTAL_AR90")) return 0.90;
        return 0.80;
    }

    private static double latentCrossLaneCorrelation(Candidate candidate) {
        if (candidate.name.contains("CORR10")) return 0.10;
        if (candidate.name.contains("CORR50")) return 0.50;
        return 0.30;
    }

    private static double totalRegimeLogStep(Candidate candidate) {
        if (candidate.name.contains("LEVEL120")) return Math.log(1.20);
        if (candidate.name.contains("LEVEL130")) return Math.log(1.30);
        if (candidate.name.contains("LEVEL140")) return Math.log(1.40);
        return 0.0;
    }

    private static double totalRegimePersistence(Candidate candidate) {
        if (candidate.name.contains("TOTAL_PERSIST90")) return 0.90;
        if (candidate.name.contains("TOTAL_PERSIST95")) return 0.95;
        return 0.85;
    }

    private static double huMqcHigh(Candidate candidate) {
        if (candidate.name.contains("MQC20")) return 0.20;
        if (candidate.name.contains("MQC25")) return 0.25;
        if (candidate.name.contains("MQC30")) return 0.30;
        if (candidate.name.contains("MQC35")) return 0.35;
        if (candidate.name.contains("MQC40")) return 0.40;
        if (candidate.name.contains("MQC60")) return 0.60;
        throw new IllegalArgumentException(
                "HU_2016 candidate must declare a supported MQC profile: "
                        + candidate.name);
    }

    private static String huMqcProfile(Candidate candidate) {
        if (candidate.name.contains("MQC20")) return "MQC20";
        if (candidate.name.contains("MQC25")) return "MQC25";
        if (candidate.name.contains("MQC30")) return "MQC30";
        if (candidate.name.contains("MQC35")) return "MQC35";
        if (candidate.name.contains("MQC40")) return "MQC40";
        if (candidate.name.contains("MQC60")) return "MQC60";
        throw new IllegalArgumentException("Unknown HU MQC profile: " + candidate.name);
    }

    private static String huCapacityProfile(Candidate candidate) {
        if (candidate.name.contains("CAP55_75")) return "CAP55_75";
        if (candidate.name.contains("CAP80_100")) return "CAP80_100";
        return "CAP30_50";
    }

    private static InstanceGenerator.CapacityMode huCapacityMode(Candidate candidate) {
        return candidate.name.contains("CAP80_100")
                ? InstanceGenerator.CapacityMode.LOOSE
                : InstanceGenerator.CapacityMode.TIGHT;
    }

    private static double[] huCapacityBounds(Candidate candidate) {
        if (candidate.name.contains("CAP55_75")) return new double[]{0.55, 0.75};
        return candidate.name.contains("CAP80_100")
                ? new double[]{0.80, 1.00}
                : new double[]{0.30, 0.50};
    }

    private static int configuredCarrierCount() {
        int carriers = Integer.getInteger("trb.synthetic.carriers", 10);
        if (carriers <= 0) {
            throw new IllegalArgumentException(
                    "trb.synthetic.carriers must be positive: " + carriers);
        }
        return carriers;
    }

    private static double huMarketSizeScale() {
        return 10.0 / I;
    }

    private static Method[] methods(String profile) {
        return switch (profile) {
            case "DATA_ONLY" -> new Method[0];
            case "FAST" -> new Method[]{Method.D, Method.SAA, Method.CSAA};
            case "CSAA_ONLY" -> new Method[]{Method.CSAA};
            case "DRO" -> new Method[]{Method.D, Method.SAA, Method.CSAA, Method.DRO};
            case "DRO_ONLY" -> new Method[]{Method.DRO};
            case "EXACT" -> new Method[]{Method.DRO, Method.RCSAA_ENUMERATE};
            case "RCSAA_ONLY" -> new Method[]{Method.RCSAA_ENUMERATE};
            default -> throw new IllegalArgumentException("Unknown profile: " + profile);
        };
    }

    private static DiagnosticRow diagnose(String candidate,
                                          int trainingReplication,
                                          int query,
                                          ReplicationData demand,
                                          ProcurementParams params,
                                          int threads,
                                          double cH) {
        Config config = TRBReviewerR3M3SyntheticSavedInstanceSolveRunner.config(
                Method.CSAA, 3, cH, 0.1, threads, 600);
        PreparedInput prepared = TRBReviewerR3M3SyntheticSolveBridge.prepare(demand, params, config);
        int laneCount = demand.baselineDemand.length;
        double[] saaMean = new double[laneCount];
        double[] csaaMean = new double[laneCount];
        double sumWeightSquares = 0.0;
        double maxWeight = 0.0;
        for (Sample sample : prepared.solveData.samples) {
            sumWeightSquares += sample.weight * sample.weight;
            maxWeight = Math.max(maxWeight, sample.weight);
            for (int j = 0; j < laneCount; j++) {
                saaMean[j] += sample.demand()[j] / prepared.solveData.samples.size();
                csaaMean[j] += sample.weight * sample.demand()[j];
            }
        }
        return new DiagnosticRow(candidate, trainingReplication, query,
                normalizedL1(saaMean, demand.conditionalMean),
                normalizedL1(csaaMean, demand.conditionalMean),
                1.0 / sumWeightSquares, maxWeight,
                sum(demand.conditionalMean) / sum(demand.baselineDemand));
    }

    private static void log1pContexts(ReplicationData demand) {
        for (Sample sample : demand.trainingSamples) log1p(sample.theta.values());
        log1p(demand.thetaNow.values());
        for (Sample sample : demand.oosSamples) log1p(sample.theta.values());
    }

    private static void log1p(double[] values) {
        for (int index = 0; index < values.length; index++) {
            if (values[index] < 0.0 || !Double.isFinite(values[index])) {
                throw new IllegalArgumentException("LOG1P requires finite nonnegative contexts.");
            }
            values[index] = Math.log1p(values[index]);
        }
    }

    private static void writeCandidateMetadata(Path path,
                                               Candidate candidate,
                                               Materialized materialized,
                                               int trainingReplications,
                                               int queriesPerTraining,
                                               int oosDraws,
                                               String profile,
                                               double lambda,
                                               int paperProcurementSeed,
                                               double cH,
                                               int seedOffset) throws Exception {
        Files.createDirectories(path.getParent());
        Settings effectiveSettings = settings(candidate, oosDraws);
        double[] coverage = coverageStats(materialized.params);
        List<String> lines = List.of(
                "candidate=" + candidate.name,
                "demandMode=" + effectiveSettings.mode,
                "grouping=" + candidate.grouping,
                "market=" + candidate.market,
                "carrierCount=" + I,
                "linearWeights=" + effectiveSettings.longRunWeight + ","
                        + effectiveSettings.globalHistoryWeight + ","
                        + effectiveSettings.groupHistoryWeight + ","
                        + effectiveSettings.laneHistoryWeight,
                "innovationCv=" + effectiveSettings.innovationCv,
                "latentCrossLaneCorrelation="
                        + effectiveSettings.latentCrossLaneCorrelation,
                "regimePersistence=" + effectiveSettings.regimePersistence,
                "activeGroupMultiplier=" + effectiveSettings.activeGroupMultiplier,
                "inactiveGroupMultiplier=" + effectiveSettings.inactiveGroupMultiplier,
                "regimeShareWeight=" + effectiveSettings.regimeShareWeight,
                "markovTotalHistoryWeight="
                        + effectiveSettings.markovTotalHistoryWeight,
                "totalRegimeLogStep=" + effectiveSettings.totalRegimeLogStep,
                "totalRegimePersistence="
                        + effectiveSettings.totalRegimePersistence,
                "totalRegimeTransitions=" + (effectiveSettings.orderedTotalRegimeTransitions
                        ? "ordered_adjacent" : "unordered"),
                "coupledTotalAndShareRegime="
                        + effectiveSettings.coupledTotalAndShareRegime,
                "mqcPenalty=" + (candidate.name.endsWith("_MIN_H")
                        ? "minimum eligible r_ij"
                        : candidate.name.endsWith("_MAX_H")
                                ? "maximum eligible r_ij"
                                : candidate.market == Market.PAPER_DEFAULT
                                        ? "maximum eligible r_ij"
                                        : "capacity-weighted eligible r_ij"),
                "mqcQuantityScale=" + candidate.mqcScale,
                "capacityScale=" + candidate.capacityScale,
                "capacityRule=" + (candidate.market == Market.HU_2016
                        ? "U[" + huCapacityBounds(candidate)[0] + ","
                                + huCapacityBounds(candidate)[1]
                                + "] times lane baseline demand, then multiplied by "
                                + huMarketSizeScale() + " to preserve total market scale"
                        : "market-specific"),
                "coverageRule=" + (candidate.market == Market.HU_2016
                        ? "carrier coverage fraction U[0.1,0.8]"
                        : candidate.market == Market.PAPER_DEFAULT
                                ? "all lanes" : "market-specific"),
                "mqcQuantityRule=" + (candidate.market == Market.HU_2016
                        ? "U[0.1," + huMqcHigh(candidate)
                                + "] times eligible baseline demand, then multiplied by "
                                + huMarketSizeScale() + " to preserve total market scale"
                        : "market-specific"),
                "actualCoverageMin=" + coverage[0],
                "actualCoverageMean=" + coverage[1],
                "actualCoverageMax=" + coverage[2],
                "baselineRankExponent=0.65",
                "baselineTotal=" + sum(materialized.baseline),
                "baselineTop1Share=" + topShare(materialized.baseline, 1),
                "baselineTop5Share=" + topShare(materialized.baseline, 5),
                "laneGroups=" + Arrays.toString(materialized.laneGroups),
                "trainingReplications=" + trainingReplications,
                "queriesPerTraining=" + queriesPerTraining,
                "oosDraws=" + oosDraws,
                "profile=" + profile,
                "paperProcurementSeed=" + paperProcurementSeed,
                "seedOffset=" + seedOffset,
                "C_h=" + cH,
                "lambda=" + lambda,
                "demandEquality=true",
                "contextTransform=LOG1P_then_training_only_zscore");
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    private static void writeResults(Path path, List<ResultRow> rows) throws Exception {
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            out.write("candidate,trainingReplication,query,trainingSeed,querySeed,method,selectedCount,yBinary,"
                    + "meanOosCost,sdOosCost,q95OosCost,cvar95OosCost,meanTransportCost,meanSpotCost,"
                    + "meanPenaltyCost,certifiedOptimal,relativeGap,optimizerTimeSec");
            out.newLine();
            for (ResultRow row : rows) {
                var result = row.result;
                out.write(String.format(Locale.US,
                        "%s,%d,%d,%d,%d,%s,%d,%s,%.12f,%.12f,%.12f,%.12f,%.12f,%.12f,%.12f,%s,%.12g,%.9f%n",
                        row.candidate, row.trainingReplication, row.query,
                        row.trainingSeed, row.querySeed, result.methodLabel,
                        result.selectedCount, csv(result.yBinary), result.meanOosCost,
                        result.sdOosCost, result.q95OosCost, result.cvar95OosCost,
                        result.meanTransportCost, result.meanSpotCost,
                        result.meanPenaltyCost, result.certifiedOptimal,
                        result.relativeGap, result.optimizerTimeSeconds));
            }
        }
    }

    private static void writeDiagnostics(Path path, List<DiagnosticRow> rows) throws Exception {
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            out.write("candidate,trainingReplication,query,saaMeanL1,csaaMeanL1,aess,maxWeight,conditionalTotalRatio");
            out.newLine();
            for (DiagnosticRow row : rows) {
                out.write(String.format(Locale.US, "%s,%d,%d,%.12f,%.12f,%.12f,%.12f,%.12f%n",
                        row.candidate, row.trainingReplication, row.query,
                        row.saaMeanL1, row.csaaMeanL1, row.aess,
                        row.maxWeight, row.conditionalTotalRatio));
            }
        }
    }

    private static void writeMethodSummary(Path path, List<ResultRow> rows) throws Exception {
        Map<String, List<ResultRow>> grouped = new LinkedHashMap<>();
        for (ResultRow row : rows) {
            grouped.computeIfAbsent(row.candidate + "|" + row.result.methodLabel,
                    ignored -> new ArrayList<>()).add(row);
        }
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            // These are averages of query-conditional metrics.  They are not
            // quantiles of the OOS draws pooled across sampled contexts.
            out.write("candidate,method,n,meanCost,meanConditionalSd,meanConditionalQ95,"
                    + "meanConditionalCvar95,meanSelected,distinctDecisions,certifiedCount");
            out.newLine();
            for (Map.Entry<String, List<ResultRow>> entry : grouped.entrySet()) {
                List<ResultRow> values = entry.getValue();
                String[] key = entry.getKey().split("\\|", 2);
                Set<String> decisions = new LinkedHashSet<>();
                int certified = 0;
                double cost = 0.0;
                double sd = 0.0;
                double q95 = 0.0;
                double cvar95 = 0.0;
                double selected = 0.0;
                for (ResultRow value : values) {
                    cost += value.result.meanOosCost;
                    sd += value.result.sdOosCost;
                    q95 += value.result.q95OosCost;
                    cvar95 += value.result.cvar95OosCost;
                    selected += value.result.selectedCount;
                    decisions.add(value.result.yBinary);
                    if (value.result.certifiedOptimal) certified++;
                }
                int n = values.size();
                out.write(String.format(Locale.US,
                        "%s,%s,%d,%.12f,%.12f,%.12f,%.12f,%.12f,%d,%d%n",
                        key[0], key[1], n, cost / n, sd / n, q95 / n,
                        cvar95 / n, selected / n, decisions.size(), certified));
            }
        }
    }

    private static void writePairSummary(Path path, List<ResultRow> rows) throws Exception {
        Map<String, Map<String, ResultRow>> byQuery = new LinkedHashMap<>();
        for (ResultRow row : rows) {
            String key = row.candidate + "|" + row.trainingReplication + "|" + row.query;
            byQuery.computeIfAbsent(key, ignored -> new LinkedHashMap<>())
                    .put(row.result.methodLabel, row);
        }
        Map<String, PairAccumulator> pairs = new LinkedHashMap<>();
        for (Map<String, ResultRow> query : byQuery.values()) {
            addPair(pairs, query, "SAA", "D");
            addPair(pairs, query, "CSAA", "SAA");
            addPair(pairs, query, "DRO", "CSAA");
            addPair(pairs, query, "RCSAA_ENUMERATE", "CSAA");
            addPair(pairs, query, "RCSAA_ENUMERATE", "DRO");
        }
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            out.write("candidate,method,baseline,n,meanCostImprovementPct,meanConditionalSdImprovementPct,"
                    + "meanConditionalQ95ImprovementPct,meanConditionalCvar95ImprovementPct,"
                    + "meanWins,sdWins,q95Wins,decisionDifferenceRate");
            out.newLine();
            for (PairAccumulator pair : pairs.values()) {
                out.write(String.format(Locale.US,
                        "%s,%s,%s,%d,%.12f,%.12f,%.12f,%.12f,%d,%d,%d,%.12f%n",
                        pair.candidate, pair.method, pair.baseline, pair.n,
                        100.0 * pair.meanImprovement / pair.n,
                        100.0 * pair.sdImprovement / pair.n,
                        100.0 * pair.q95Improvement / pair.n,
                        100.0 * pair.cvarImprovement / pair.n,
                        pair.meanWins, pair.sdWins, pair.q95Wins,
                        (double) pair.decisionDifferences / pair.n));
            }
        }
    }

    /**
     * Summarizes the predictive distribution obtained by pooling OOS draws
     * across independently sampled query contexts.  This is the synthetic
     * analogue of a Table-2 distribution across OOS states; it must remain
     * separate from averages of query-conditional quantiles above.
     */
    private static void writePooledOosSummary(Path path,
                                              Path root,
                                              List<ResultRow> rows) throws Exception {
        Map<String, List<Double>> grouped = new LinkedHashMap<>();
        for (ResultRow row : rows) {
            Path costs = root.resolve(row.candidate)
                    .resolve(String.format(Locale.US, "train_%02d/query_%03d/%s/oos_costs.csv",
                            row.trainingReplication, row.query, row.result.methodLabel));
            List<String> lines = Files.readAllLines(costs, StandardCharsets.UTF_8);
            List<Double> values = grouped.computeIfAbsent(
                    row.candidate + "|" + row.result.methodLabel,
                    ignored -> new ArrayList<>());
            for (int line = 1; line < lines.size(); line++) {
                String[] fields = lines.get(line).split(",", 4);
                if (fields.length < 3) {
                    throw new IllegalStateException("Malformed OOS cost row: " + costs);
                }
                values.add(Double.parseDouble(fields[2]));
            }
        }
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            out.write("candidate,method,nDraws,pooledMean,pooledPopulationSd,pooledQ95,pooledCvar95");
            out.newLine();
            for (Map.Entry<String, List<Double>> entry : grouped.entrySet()) {
                String[] key = entry.getKey().split("\\|", 2);
                double[] values = entry.getValue().stream().mapToDouble(Double::doubleValue).toArray();
                Arrays.sort(values);
                double mean = sum(values) / values.length;
                double sumSquares = 0.0;
                for (double value : values) sumSquares += (value - mean) * (value - mean);
                double q95 = values[Math.max(0, (int) Math.ceil(0.95 * values.length) - 1)];
                double tail = 0.0;
                int tailCount = 0;
                for (double value : values) {
                    if (value >= q95) {
                        tail += value;
                        tailCount++;
                    }
                }
                out.write(String.format(Locale.US, "%s,%s,%d,%.12f,%.12f,%.12f,%.12f%n",
                        key[0], key[1], values.length, mean,
                        Math.sqrt(sumSquares / values.length), q95, tail / tailCount));
            }
        }
    }

    private static void addPair(Map<String, PairAccumulator> pairs,
                                Map<String, ResultRow> query,
                                String method,
                                String baseline) {
        ResultRow left = query.get(method);
        ResultRow right = query.get(baseline);
        if (left == null || right == null) return;
        String key = left.candidate + "|" + method + "|" + baseline;
        PairAccumulator pair = pairs.computeIfAbsent(key,
                ignored -> new PairAccumulator(left.candidate, method, baseline));
        pair.n++;
        pair.meanImprovement += (right.result.meanOosCost - left.result.meanOosCost)
                / right.result.meanOosCost;
        pair.sdImprovement += (right.result.sdOosCost - left.result.sdOosCost)
                / right.result.sdOosCost;
        pair.q95Improvement += (right.result.q95OosCost - left.result.q95OosCost)
                / right.result.q95OosCost;
        pair.cvarImprovement += (right.result.cvar95OosCost - left.result.cvar95OosCost)
                / right.result.cvar95OosCost;
        if (left.result.meanOosCost < right.result.meanOosCost) pair.meanWins++;
        if (left.result.sdOosCost < right.result.sdOosCost) pair.sdWins++;
        if (left.result.q95OosCost < right.result.q95OosCost) pair.q95Wins++;
        if (!left.result.yBinary.equals(right.result.yBinary)) pair.decisionDifferences++;
    }

    private static double normalizedL1(double[] estimate, double[] target) {
        double numerator = 0.0;
        double denominator = 0.0;
        for (int j = 0; j < target.length; j++) {
            numerator += Math.abs(estimate[j] - target[j]);
            denominator += target[j];
        }
        return numerator / denominator;
    }

    private static double topShare(double[] values, int count) {
        double[] copy = values.clone();
        Arrays.sort(copy);
        double numerator = 0.0;
        for (int k = 0; k < count; k++) numerator += copy[copy.length - 1 - k];
        return numerator / sum(copy);
    }

    private static double[] coverageStats(ProcurementParams params) {
        double minimum = 1.0;
        double maximum = 0.0;
        double total = 0.0;
        for (int i = 0; i < params.I; i++) {
            int eligible = 0;
            for (int j = 0; j < params.J; j++) {
                if (params.eligible[i][j]) eligible++;
            }
            double coverage = (double) eligible / params.J;
            minimum = Math.min(minimum, coverage);
            maximum = Math.max(maximum, coverage);
            total += coverage;
        }
        return new double[]{minimum, total / params.I, maximum};
    }

    private static double sum(double[] values) {
        double total = 0.0;
        for (double value : values) total += value;
        return total;
    }

    private static String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static void requireEmptyDirectory(Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (var entries = Files.list(path)) {
            if (entries.findAny().isPresent()) {
                throw new IllegalArgumentException("Output directory must be empty: " + path);
            }
        }
    }

    private enum Grouping {EXOGENOUS, COST_AWARE}

    private enum Market {RANDOM_V3, GROUP_SPECIALIZED, PAPER_DEFAULT, HU_2016}

    private record Candidate(String name,
                             Mode mode,
                             double longRunWeight,
                             double globalWeight,
                             double groupWeight,
                             double laneWeight,
                             Grouping grouping,
                             Market market,
                             double mqcScale,
                             double capacityScale) {
        Candidate(String name,
                  Mode mode,
                  double longRunWeight,
                  double globalWeight,
                  double groupWeight,
                  double laneWeight,
                  Grouping grouping,
                  Market market) {
            this(name, mode, longRunWeight, globalWeight, groupWeight,
                    laneWeight, grouping, market, 1.0, 1.0);
        }
    }

    private record Materialized(double[] baseline,
                                int[] laneGroups,
                                ProcurementParams params) {
    }

    private record ResultRow(String candidate,
                             int trainingReplication,
                             int query,
                             long trainingSeed,
                             long querySeed,
                             TRBReviewerR3M3SyntheticMainSolve.Result result) {
    }

    private record DiagnosticRow(String candidate,
                                 int trainingReplication,
                                 int query,
                                 double saaMeanL1,
                                 double csaaMeanL1,
                                 double aess,
                                 double maxWeight,
                                 double conditionalTotalRatio) {
    }

    private static final class PairAccumulator {
        final String candidate;
        final String method;
        final String baseline;
        int n;
        int meanWins;
        int sdWins;
        int q95Wins;
        int decisionDifferences;
        double meanImprovement;
        double sdImprovement;
        double q95Improvement;
        double cvarImprovement;

        PairAccumulator(String candidate, String method, String baseline) {
            this.candidate = candidate;
            this.method = method;
            this.baseline = baseline;
        }
    }
}
