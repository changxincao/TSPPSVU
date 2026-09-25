package Test.analysis.synthetic;

import Basic.ProcurementParams;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Distribution;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Parameters;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Volatility;

import java.util.Arrays;

/** Checks that Experiments 1--2 can receive the same complete case. */
public final class TRBSVUSyntheticCaseSelfCheck {
    private TRBSVUSyntheticCaseSelfCheck() {
    }

    public static void main(String[] args) {
        TRBSVUSyntheticCase.Seeds seeds = new TRBSVUSyntheticCase.Seeds(11, 13, 17, 19, 23);
        TRBSVUSyntheticCase a = TRBSVUSyntheticCase.generate(20, 60, 100, 1000,
                Distribution.NORMAL, Volatility.LOW, seeds);
        TRBSVUSyntheticCase repeat = TRBSVUSyntheticCase.generate(20, 60, 100, 1000,
                Distribution.NORMAL, Volatility.LOW, seeds);
        TRBSVUSyntheticCase otherCell = TRBSVUSyntheticCase.generate(20, 60, 100, 1000,
                Distribution.LOGNORMAL, Volatility.HIGH, seeds);
        require(a.params.I == 20 && a.params.J == 60 && a.params.alpha == 2
                && a.params.beta == 14 && a.history.size() == 100 && a.oos.size() == 1000,
                "Main-case dimensions or selection bounds are wrong.");
        require(Arrays.equals(a.testContext.values(), otherCell.testContext.values()),
                "Paired DGP cells must share the final context.");
        for (int t = 0; t < 100; t++) {
            require(Arrays.equals(a.history.get(t).theta.values(),
                    otherCell.history.get(t).theta.values()), "Context pairing failed.");
            require(Arrays.equals(a.history.get(t).demand(),
                    repeat.history.get(t).demand()), "History reproducibility failed.");
        }
        for (int s = 0; s < 1000; s++) {
            require(Arrays.equals(a.oos.get(s).demand(), repeat.oos.get(s).demand()),
                    "OOS reproducibility failed.");
        }
        Parameters demandParameters = TRBSVUSyntheticCase.generateDetailed(
                20, 60, 100, 1000, Distribution.NORMAL, Volatility.LOW, seeds)
                .demandParameters();
        checkMarket(a.params, otherCell.params, demandParameters.linearTrendTypicalDemand());
        checkHomeGroupCoverage(demandParameters.linearTrendTypicalDemand());
        TRBSVUSyntheticCase.ValidationWindow first = a.validationWindow(70, 70);
        TRBSVUSyntheticCase.ValidationWindow last = a.validationWindow(99, 70);
        require(first.train().size() == 70 && first.train().get(0) == a.history.get(0)
                        && first.realized() == a.history.get(70), "First validation origin is wrong.");
        require(last.train().get(0) == a.history.get(29)
                        && last.realized() == a.history.get(99), "Last validation origin is wrong.");
        System.out.println("PASS synthetic case: shared 20x60 market, 100 history, "
                + "30 possible 70-row validation origins, 1000 paired OOS draws.");
    }

    private static void checkMarket(ProcurementParams p, ProcurementParams paired,
                                    double[] typicalDemand) {
        int expectedPerCarrier = (int) Math.round(0.50 * p.J);
        for (int j = 0; j < p.J; j++) {
            int count = 0;
            double sumRate = 0.0;
            for (int i = 0; i < p.I; i++) {
                require(p.eligible[i][j] == paired.eligible[i][j],
                        "Paired eligibility mismatch.");
                if (!p.eligible[i][j]) continue;
                count++;
                sumRate += p.r[i][j];
                require(p.q[i][j] == paired.q[i][j] && p.r[i][j] == paired.r[i][j],
                        "Paired rate/capacity mismatch.");
                double capacityRatio = p.q[i][j] / typicalDemand[j];
                require(capacityRatio >= 0.3 && capacityRatio <= 0.5,
                        "Eligible-pair capacity left U(0.3,0.5) demand scale.");
            }
            require(count >= 1, "Lane has no eligible carrier.");
            double markup = p.e[j] / (sumRate / count);
            require(markup >= 2.0 && markup <= 3.0, "Spot markup outside range.");
        }
        for (int i = 0; i < p.I; i++) {
            double minimumRate = Double.POSITIVE_INFINITY;
            double eligibleDemand = 0.0;
            int covered = 0;
            for (int j = 0; j < p.J; j++) {
                if (p.eligible[i][j]) {
                    covered++;
                    eligibleDemand += typicalDemand[j];
                    minimumRate = Math.min(minimumRate, p.r[i][j]);
                }
            }
            require(covered == expectedPerCarrier, "Carrier coverage is not 50%.");
            require(p.h[i] == minimumRate && p.p[i] < p.M[i],
                    "MQC penalty or capacity relationship is wrong.");
            double mqcShare = p.p[i] / eligibleDemand;
            require(mqcShare >= 0.15 && mqcShare <= 0.35,
                    "MQC quantity left U(0.15,0.35) eligible-demand scale.");
            require(p.p[i] == paired.p[i] && p.h[i] == paired.h[i],
                    "Paired MQC market mismatch.");
        }
    }

    private static void checkHomeGroupCoverage(double[] typicalDemand) {
        int groupCount = 5;
        int[] laneGroup = TRBSVUSyntheticDemandGenerator.balancedRegionalGroups(
                typicalDemand.length, groupCount, 17L);
        ProcurementParams first = TRBSVUProcurementGenerator.generateWithHomeGroupCoverage(
                20, typicalDemand, 13L, laneGroup, groupCount, 0.8);
        ProcurementParams repeat = TRBSVUProcurementGenerator.generateWithHomeGroupCoverage(
                20, typicalDemand, 13L, laneGroup, groupCount, 0.8);
        int expectedPerCarrier = (int) Math.round(0.50 * typicalDemand.length);
        require(Arrays.deepEquals(first.eligible, repeat.eligible),
                "Home-group coverage is not reproducible.");
        double strongestGroupCoverage = 0.0;
        double remainingGroupCoverage = 0.0;
        for (int i = 0; i < first.I; i++) {
            int covered = 0;
            int[] coveredByGroup = new int[groupCount];
            int[] lanesByGroup = new int[groupCount];
            for (int j = 0; j < first.J; j++) {
                lanesByGroup[laneGroup[j]]++;
                if (first.eligible[i][j]) {
                    covered++;
                    coveredByGroup[laneGroup[j]]++;
                }
            }
            require(covered == expectedPerCarrier,
                    "Home-group market did not preserve exact 50% carrier coverage.");
            double best = -1.0;
            int bestGroup = -1;
            for (int g = 0; g < groupCount; g++) {
                double rate = (double) coveredByGroup[g] / lanesByGroup[g];
                if (rate > best) {
                    best = rate;
                    bestGroup = g;
                }
            }
            strongestGroupCoverage += best;
            remainingGroupCoverage += (covered - coveredByGroup[bestGroup])
                    / (double) (first.J - lanesByGroup[bestGroup]);
        }
        double meanStrongest = strongestGroupCoverage / first.I;
        double meanRemaining = remainingGroupCoverage / first.I;
        require(meanStrongest >= 0.75 && meanRemaining < 0.50,
                "Home-group market did not create a home coverage preference.");
        for (int j = 0; j < first.J; j++) {
            boolean covered = false;
            for (int i = 0; i < first.I; i++) covered |= first.eligible[i][j];
            require(covered, "Home-group market left one lane uncovered.");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
