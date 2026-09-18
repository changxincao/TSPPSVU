# Experiment 1 IDE并行调度入口

入口类：`Test.analysis.synthetic.TRBSVUExperiment1IdeMain`。

该入口把正式任务定义为“一个冻结算例 × 一种方法”。每个任务在独立Java进程中完成该方法的完整链条：25个训练内validation origins（固定50个历史样本，`1:50->51,...,25:74->75`）、候选参数选择、使用选中参数和全部75个历史样本的final solve、共同1000个OOS需求评价。不同任务使用独立目录、日志和checkpoint；任务失败不会覆盖其他任务，重启时会复用协议指纹一致的已完成checkpoint。

默认配置直接写在类顶部：输入目录、输出目录、并行任务数6、每个求解器线程数4、单次求解上限14400秒、validation origins 25、replication 0--19及全部8种Experiment 1方法。正式入口拒绝将origin数改回旧协议；可在IDE中修改非协议常量，也可在Run Configuration的Program arguments中覆盖，例如：

```text
--input=analysis/TRB_reviewer_revision/101_svu_experiment12_I15J50S75_20260917
--output=analysis/TRB_reviewer_revision/121_svu_experiment1_I15J50S75_20260917
--replications=0-19
--methods=D,SAA-All,Tuned-SAA,CSAA-Exp,CSAA-Gau,CSAA-Epa,CSAA-Tri,RF-CSAA
--parallel=6
--solver-threads=4
--limit-seconds=14400
--validation-origins=25
```

先加`--dry-run`只检查任务清单，不启动求解。正式输出的每方法结果位于`输出目录/rep_XXX/方法/`，其中包括`task.log`、validation明细和汇总、final solve及权重、OOS逐样本明细和汇总、各阶段checkpoint与`complete.txt`。单个 contextual worker 只写`validation/context_candidate.csv`，不再把自己的单方法候选冒充跨 family 的最终选择。五个 contextual workers 全部成功后，coordinator 按 Experiment 1 原有 Mean、SD、参数顺序规则在 Exp/Gau/Epa/Tri/RF 中统一选择一次 $C^\star$，并写入标准路径`输出目录/rep_XXX/validation/experiment1_selected_context.csv`；同时复制并核对冻结`instance/instance.tsv`，使该 replication 目录可直接作为 Experiment 4 的 baseline root。若只请求了部分 contextual methods，则不生成 replication-level $C^\star$。

`complete.txt`及checkpoint同时校验算例、方法、求解设置、候选网格、Java源码和RF脚本指纹，避免设置变化后静默复用旧结果。协议或算例指纹不匹配的旧checkpoint现在按cache miss处理并由新结果原子替换，不会因旧目录存在而直接终止。

默认`parallel=6`、`solver-threads=4`只适合有足够独占CPU资源的吞吐运行；它最多产生24个solver threads。用于论文Time列的正式计时运行应使用`--parallel=1`，或至少保证`parallel × solver-threads`不超过独占分配的硬件线程数，并在记录中保存实际设置。

为支持安全的单方法任务，`TRBSVUExperiment1Runner`新增了带方法集合的`run`重载；原无参方法集合的`run(instance)`仍运行全部方法，原Experiment 1/2入口行为不变。
