# Experiment 3 数据与 Experiment 4 配对比较实现记录（2026-09-15）

## 冻结结论

Experiment 3 当前只冻结数据，不冻结比较方法。按照数值实验说明书生成 Normal/Lognormal 与 Low/Medium/High 的 `2 x 3` 个 DGP cells，每个 cell 10 个 paired replications，共 60 个算例。固定规模为 `I=20, J=60, H=100, OOS=1000`。同一 replication 的六个 cells 共享 DGP 系数、采购市场、历史 context path、最终 test context 和各类种子；只改变需求分布族和波动档。Normal-Low 的 10 个算例直接复用 Experiment 1 的 baseline replications 0--9。

数据目录：`analysis/TRB_reviewer_revision/110_svu_experiment3_dgp_cases_20260915`。每个算例包含一个完整、人类可读的 `instance.tsv`、`dgp_parameters.csv` 和 `manifest.txt`；根目录的 `experiment3_cases.csv` 给出60个算例的路径、种子和SHA-256。

生成器为 `src/Test/analysis/synthetic/TRBSVUGenerateExperiment3CasesMain.java`。生成器具有幂等校验：目标文件已存在时不静默覆盖，而是重新构造期望算例并逐字节核对。2026-09-15最终复核结果为：60个 `instance.tsv`、60个DGP参数文件、60个manifest、60条索引记录；总算例文件大小83,967,567 bytes；Normal-Low与Experiment 1 baseline的10个SHA-256不一致数为0。

## Experiment 4 设计与实现

Experiment 4 不另造算例。正式实验应复用 Experiment 1/2 的20个 baseline Normal-Low replications、Experiment 1训练内选出的 contextual reference、同一采购市场、test context及1000个OOS draws。1--2个replication只可用于smoke test，不能替代20个replications的正式证据。

实现入口为 `src/Test/analysis/synthetic/TRBSVUExperiment4Main.java`，单个lambda的配对逻辑为 `TRBSVUExperiment4Runner.java`。每个lambda先求 contextual modified-chi-square DRO并立即保存，再求exact RCSAA；两者使用同一组经过严格零权重删除、小正权重 `1e-8` floor及重新归一化后的contextual reference probabilities，需求等式、采购参数及OOS输入完全相同。exact RCSAA使用当前冻结的compact primal exact solver，不使用repair cut。

lambda网格为 `{0.01, 0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10, 50, 100}`。输出包括两种方法的状态、是否认证最优、目标、bound、gap、时间、选中承运人及决策向量、OOS Mean/SD/q95/CVaR95/Max、目标差、决策一致性/Jaccard，以及modified-chi-square充分条件的组成量与是否成立。只有两种方法均获认证时才计算并解释训练目标差，否则写为NaN。

每个方法的solve checkpoint及OOS逐draw文件均原子落盘；每完成一个lambda即重写当前汇总。运行manifest在求解开始前写入，并把实例、Experiment 1选参文件、线程、时限、lambda网格、全部Java源码、RF脚本及RF Python环境（仅RF被选中时）绑定到protocol fingerprint。代码或运行口径改变后旧checkpoint会明确拒绝复用。

Experiment 4当前只完成代码级和小算例端到端验证，尚未启动正式20-replication求解，因为正式入口必须先读取各replication的 `experiment1_selected_context.csv`。小算例检查在同一lambda下成功求得DRO与exact RCSAA，两者的checkpoint均标记为认证最优，exact RCSAA认证gap为0；连续第二次运行正确恢复两份solve checkpoints及OOS文件，没有重复求解。

## 工作量口径

正式Experiment 4为20个replications乘11个lambda乘2种方法，共440次第一阶段模型求解。这里不需要新增20份数据；它是对现有20份baseline算例的严格配对复用。后续可先跑1--2个replication检查运行时间和输出，再启动正式批量调度。

## 暂定待办：正式求解前检查需求波动强度

当前Experiment 3的Low/Medium/High波动档暂时保留。正式大批量求解前，应先做不依赖任何优化方法结果的数据诊断，检查各档的实际lane-level CV、总需求CV、条件残差尺度以及1000个OOS draws的离散程度，并确认Low/Medium/High形成清晰、有序且实质性的差异。

如果诊断表明当前波动整体过小，导致采购决策几乎不受不确定性影响、普通方法与鲁棒方法缺少可识别的比较空间，则可能需要统一调整volatility ranges。调整必须遵循以下约束：在查看正式方法排序和OOS优劣之前完成；依据需求统计和经济量级而非“让某方法获胜”确定；修改后重新生成并冻结全部相关paired cases，不能只替换效果不理想的replication；同时保留旧版本和参数变化记录。

预期目标是使普通方法与鲁棒方法面对足够但合理的不确定性，从而能够识别稳健化的成本与收益，而不是预先要求两类方法必须出现某个方向或某个百分比的性能gap。波动是否需要调整以及采用何种新区间，目前尚未决定，需等待上述数据诊断。
