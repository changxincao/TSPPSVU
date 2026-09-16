package Test.analysis.synthetic;

import Basic.ProcurementParams;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Distribution;
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
        checkMarket(a.params, otherCell.params);
        TRBSVUSyntheticCase.ValidationWindow first = a.validationWindow(70, 70);
        TRBSVUSyntheticCase.ValidationWindow last = a.validationWindow(99, 70);
        require(first.train().size() == 70 && first.train().get(0) == a.history.get(0)
                        && first.realized() == a.history.get(70), "First validation origin is wrong.");
        require(last.train().get(0) == a.history.get(29)
                        && last.realized() == a.history.get(99), "Last validation origin is wrong.");
        System.out.println("PASS synthetic case: shared 20x60 market, 100 history, "
                + "30 possible 70-row validation origins, 1000 paired OOS draws.");
    }

    private static void checkMarket(ProcurementParams p, ProcurementParams paired) {
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
            }
            require(count >= 6, "Lane has fewer than six eligible carriers.");
            double markup = p.e[j] / (sumRate / count);
            require(markup >= 1.5 && markup <= 2.5, "Spot markup outside range.");
        }
        for (int i = 0; i < p.I; i++) {
            double minimumRate = Double.POSITIVE_INFINITY;
            for (int j = 0; j < p.J; j++) {
                if (p.eligible[i][j]) minimumRate = Math.min(minimumRate, p.r[i][j]);
            }
            require(p.h[i] == minimumRate && p.p[i] < p.M[i],
                    "MQC penalty or capacity relationship is wrong.");
            require(p.p[i] == paired.p[i] && p.h[i] == paired.h[i],
                    "Paired MQC market mismatch.");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
