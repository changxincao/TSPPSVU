# synthetic

## 这个目录总体做什么
这一组围绕构造数据展开，覆盖“怎么生成数据”和“生成后怎么跑 SAA / CSAA / RCSAA 比较”。
它的作用是验证：当协变量和需求结构被人为设计得更相关时，CSAA / RCSAA 是否真的能明显优于 SAA。

## 文件作用
- `synth_weekly_demand_generator.py`
  - 早期的周度合成需求生成脚本。
  - 主要用于从历史结构出发，生成一版可控的 synthetic 周度需求数据。

- `CostAwareSyntheticDemandGenerator.java`
  - 更完整的构造数据生成器。
  - 在需求生成时显式考虑线路成本结构、需求大头线路、协变量驱动方式等。
  - 主要用于构造“更有利于检验 CSAA / RCSAA 的数据”。

- `SyntheticSolveComparison.java`
  - 在构造数据上跑 baseline 主线比较：`SAA / CSAA / Mean / Complete`。
  - 主要回答“在 synthetic 数据上，CSAA 相对 SAA 能提高多少”。

- `SyntheticThetaModeSolveRunner.java`
  - 在构造数据上跑不同 theta 后处理模式，例如 `raw / pca3 / pca4 / pca5`。
  - 也可用于跑 `RCSAA`。
  - 主要回答“不同协变量形式、不同 `lambda` 下结果怎么变”。

- `TRBReviewerR3M3IndependentPathDemandGenerator.java`
  - 对应 Reviewer 3, Comment 3 的多 DGP 合成数据。
  - 每个训练行来自一条独立三阶动态路径；当前 context 来自另一条独立路径。
  - 固定当前 context 后，只重抽下一期创新，生成条件 OOS 需求。
  - 支持 lognormal / uniform、低 / 高波动，以及方法可见的 `k=1/2/3` lag。

- `TRBReviewerR3M3SyntheticDataValidation.java`
  - 只验证生成器，不调用优化模型。
  - 检查非负性、theta 排列、条件均值、目标 CV、seed 可复现性和 warm-up 50/100 差异。

- `TRBReviewerR3M3SyntheticInstanceIO.java`
  - 对应 R3-3，并支撑 R3-5/R4-M38 的可复现性披露。
  - 将一个完整算例保存为透明的 UTF-8 properties/CSV：训练样本、query、条件 OOS、条件均值、三期历史及完整采购参数。
  - 读回时不重新抽样，并拒绝覆盖非空目录。

- `TRBReviewerR3M3SyntheticInstanceBuilder.java`
  - 生成并保存一个完整、可重复使用的需求—采购算例；不调用求解器。
  - 同时写出采购参数的随机种子、容量/MQC 缩放及报价范围。

- `TRBReviewerR3M3SyntheticInstanceRoundTripValidation.java`
  - 对保存前后所有 double、样本元数据和采购参数做 bit-level 往返核验。

- `TRBReviewerR3M3SyntheticSolveBridge.java`
  - 把生成或读回的算例转换为当前 `Data` 接口。
  - 对 theta 仅使用训练集拟合标准化，并分别生成 D、SAA、CSAA、RCSAA/DRO 所需的场景与权重。
  - 正式返修接口会强制 `enforceDemandEquality=true`，OOS 数据不会进入求解输入。

- `TRBReviewerR3M3SyntheticMainSolve.java`
  - 每个 query 只求解一次一阶段决策，再固定该决策评价全部条件 OOS draws。
  - 输出逐 draw 的总成本、运输/现货/MQC 罚金，以及均值、分位数和 CVaR 等汇总。
  - 结果目录必须为空；汇总明确区分准备、优化器、OOS 评价和总时间，并记录 status/bound/gap/算法计数。

- `TRBReviewerR3M3SyntheticSavedInstanceSolveRunner.java`
  - 保存算例到当前主求解器的命令行入口。
  - 支持 D、SAA、CSAA、DRO extensive、exact RCSAA、exact primal LBBD 和 Algorithm-1 primal search；每种方法自动写入独立子目录并拒绝覆盖。
  - 成功后写 `run_config.properties`；恢复调度时必须核对实例、参数、线程、时限、等式口径和 solver variant。

- `TRBReviewerR3M3SyntheticFormalExperiment.java`
  - R3-3 正式多 DGP 调度器：lognormal/uniform × CV0.15/0.30 × 多 evaluation seeds。
  - 用完全独立的 calibration seeds 选择 `k/C_h/lambda`，随后冻结参数并在相同保存实例上配对运行 D/SAA/CSAA/DRO/exact-RCSAA。

- `TRBReviewerR3M3SyntheticPairedStatistics.java`
  - 同一 query 内按 draw 配对；跨 query/seed 才计算用于总体结论的 replication-level 置信区间。
  - 不把固定 query 下的 1000 个条件 OOS draws 冒充 1000 个独立实验重复。
  - 汇总同时保留 solver status/gap/certified，并输出缺失方法清单，不静默掩盖 timeout 或失败运行。

- `TRBReviewerR3M4CarrierScalability.java`
  - R3-4 专用 `I=10/20/30` 规模实验，固定 J、S、DGP、超参数和 `kappa=2`，运行论文 Algorithm 1 的邻域搜索。
  - 输出全局 time limit、status、bound/gap、nodes、iterations、cuts、candidate count，并可在 I=10 时与枚举核验。

- `TRBReviewerR3M3SavedInstanceBridgeValidation.java`
  - 只检查“保存 → 读回 → D/SAA/CSAA/RCSAA `Data`”全链路，不调用优化求解器。

- `TRBReviewerSyntheticProcurementFactory.java`
  - 对应 R3-4、R4-M33--M35 的规模接口。
  - 当承运人数由 10 扩到 20/30 时，按 `10/I` 缩放单承运人容量和 MQC，使总经济松紧近似可比。

- `TRBReviewerSyntheticExperimentMatrix.java`
  - 输出不启动求解的实验矩阵。
  - 主 DGP 为 lognormal/uniform × CV 0.15/0.30 × 配对 seeds；规模实验采用非全因子的 I/J/S 阶梯。
