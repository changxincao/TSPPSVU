# TSPP literature demand audit and D--SAA probes

## Literature facts used in this audit

| Study | Demand and samples | Procurement-side structure | Reported stochastic value |
|---|---|---|---|
| Ma, Kwon and Lee (2010) | lane demand `U[1,20]`; finite low/normal/high scenarios; normally distributed scenario probabilities; usually 3--40 scenarios | package bids; third-party rate `U[1,5]`; bid rate `U[1.5,2]` times third-party rate; package lower/upper overrides | VSS `0.40%--7.15%`; deterministic objective can occasionally be lower |
| Zhang et al. (2014) | `D_j=50+100 Beta(alpha,beta)`; five Beta families; `M=10,N=10,N*=100`; OOS=10000 | package bids; lower/upper acceptable package volume; shortage penalty and extra service cost; winner cap about 80% | Beta(5,5): usually identical to nominal; larger variance/asymmetry: improvements from small values up to 22.86% in Table 8 |
| Zhang et al. (2015) | mean 100; variance sensitivity; Gamma OOS with 100 draws | robust package WDP; penalty twice package shipping rate | deterministic better at variance 600; robust better at variance 9000 |
| Li et al. (2023) | lane mean `U[10,50]`; fluctuation ratio `U[.1,.5]`; uniform interval draws; training 5/10/15; OOS=500 | package-level MQC and maximum volume; penalty twice bid rate; budget-overrun objective | SP improves overrun/worst/SD, but this is not the same objective or contract structure as the present model |

## Direct probes in the present equality-demand model

All complete cells use five paired procurement seeds, exact certified solutions,
training sizes 10 and 60, and 2000 independent OOS draws. Negative percentages
mean that SAA is better than D.

### Independent lane Beta demands, current procurement market, S=60

| Distribution | Mean | SD | Q95 | CVaR95 | Max | Same selection |
|---|---:|---:|---:|---:|---:|---:|
| Beta(5,5) | 0.00% | 0.00% | 0.00% | 0.00% | 0.00% | 5/5 |
| Beta(1,1) | -0.19% | -0.50% | -0.17% | -0.31% | -0.90% | 3/5 |
| Beta(.5,.5) | -0.32% | -2.48% | -0.63% | -0.79% | -1.62% | 3/5 |
| Beta(2,5) | -0.09% | +2.20% | +0.08% | +0.06% | +0.05% | 4/5 |
| Beta(1,5) | -0.07% | -7.88% | -0.91% | -1.01% | -0.70% | 3/5 |

Capacity at 75% still gives only `-0.16%` Mean for Beta(.5,.5), with OOS spot
share near 2%. At 50%, spot share reaches about 13%--15% in the two completed
markets, but SAA with 60 samples remains identical to D; the third market is
rejected because scaling would make one carrier's MQC exceed its total capacity.
A balanced 50%-coverage market with total market capacity around twice baseline
demand also yields identical D and 60-sample SAA decisions in all five markets.

### Common network-wide Beta shock

| Demand case | S | Mean | SD | Q95 | CVaR95 | Max | Same selection |
|---|---:|---:|---:|---:|---:|---:|---:|
| Beta(.5,.5), current capacity | 60 | -0.68% | -0.80% | -0.97% | -0.97% | -0.97% | 2/5 |
| Beta(.5,.5), 75% capacity | 60 | +0.00% | -1.19% | -0.66% | -0.67% | -0.67% | 3/5 |
| Beta(1,5), current capacity | 60 | -0.09% | -1.13% | -0.56% | -0.78% | -0.96% | 4/5 |

## Conclusion

Copying a high-variance marginal distribution from the TSPP literature is not
enough in the present model. Independent lane disturbances diversify at the
network level, selection is usually at its 70% upper bound, and there is no
fixed signing cost. Consequently, the mean-demand and expected-cost problems
often select the same carriers. A perfectly common network-wide Beta component
restores a small but stable SAA value, consistent with finite low/normal/high
network scenarios in Ma et al. This is a mechanism endpoint, not a proposed
replacement of the formal DGP. The formal generator already combines
heterogeneous common loadings in `[0.2,0.6]` with idiosyncratic lane noise.
Thus the unresolved question is whether that predeclared correlation strength
is appropriate, not whether a common shock needs to be added. It must not be
chosen from OOS rankings.

The largest structural difference from Zhang et al. (2014) is not the Beta
distribution. Their first-stage decision accepts XOR packages. A single
scenario-wise package quantity is shared by all lanes in that package, is
bounded by package-level lower and upper volumes, and each lane is assigned to
at most one package. In the present model, selecting a carrier has no fixed
cost, each lane may be split across multiple selected carriers, and all flows
are independently reoptimized after demand is observed. This much more
flexible recourse makes the mean-demand solution frequently optimal for the
sample-average problem as well. Capacity surplus suppresses the effect, but a
50% capacity probe already generated 13%--15% spot share without changing the
60-sample decision in the completed markets, so capacity alone is not the root
cause.
