# 计算规模实验：输入输出准备（2026-10-02）

## 当前边界

只准备本机代码、任务清单和输入输出契约。**不生成正式数据、不做CV/OOS、不启动求解、不连接远程或修改队列。** 主实验数据方案等远程比较确定后再定；不能使用某个旧生成器的默认参数代替最终方案。

已确定：三个I/J组合(20,60)、(30,120)、(40,200)，S=75/150，每规模3例；18例×5方法=90次。方法为SAA、CSAA、RCSAA switched compact、modified chi-square、W1 CCG。每求解器4线程、预算7200秒。不同方法共享同一算例；不要求75/150之间额外配对。

`scale_experiment_pending.properties` 中数据协议、CSAA类型和参数、三个鲁棒参数及其来源均为UNSET。`state=PENDING_MAIN_EXPERIMENT`时只能准备任务清单，不能导入正式输入或运行worker。最终冻结须复制到**新目录**，填写来源和参数并改为FROZEN；不在已有结果目录更换协议。

## 本机准备命令

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/prepare_scale_io.ps1 -OutputRoot '<新的本机准备目录>' -CplexJar '<cplex.jar绝对路径>' -MosekJar '<mosek.jar绝对路径>'
```

Java 21字节码编译，执行无求解器自检，输出`plan/experiment.properties`与`plan/tasks.tsv`。本机CPLEX/MOSEK jar用于编译；自检不调用native optimizer，不需要启动许可证求解。运行库路径必须显式传入，脚本不内置中文路径，避免Windows PowerShell 5的编码歧义。编译目录依赖本机bin，不是可直接复制的完整远程部署包。

## 输入（后续主实验冻结后才写入）

```text
plan/
  experiment.properties
  tasks.tsv
  inputs/<case_id>/
    instance.tsv          # 一份可读算例：采购参数、种子、S期历史、一个query；没有OOS
    context_weights.tsv   # row_index / sample_id / weight；保留全部S行，包括零权重
    generation.txt        # 实际数据生成参数、种子规则与来源；不只写“使用默认值”
    input.properties      # 数据、权重、生成记录和配置的SHA256
```

后续生成器适配器在同一Java包内调用`TRBSVUScaleExperiment.writeInput`，沿用现有`TRBSVUSyntheticCaseIO`，不重复实现采购/需求生成公式。当前**没有接入数据生成器**。输入固定后，SAA改为等权；其余四法共享冻结条件权重。权重文件不是每个方法重新计算一次。

`loadTextForSolve`显式允许没有OOS的文件；原来的`loadText`仍要求OOS，已有主实验读取行为不变。

## 单任务接口与输出

```text
TRBSVUScaleExperiment audit  <plan_root>
TRBSVUScaleExperiment worker <plan_root> <case_id> <method> <新的attempt目录>
TRBSVUScaleExperiment verify <plan_root> <case_id> <method> <attempt目录>
```

部署运行时应设置`-Dtrb.scale.runtimeFingerprint=<实际代码和运行环境的指纹>`；未打包的本机接口测试使用LOCAL_UNPACKAGED，不应据此跨版本复用正式结果。方法标识仅允许SAA/CSAA/RCSAA/C-Chi2/C-W1。

```text
results/<case_id>/<method>/<attempt>/
  run.properties
  weights.csv                 # 输入权重及求解器实际参考权重
  solve_started.txt
  result.csv                  # 目标、决策、状态、bound/gap、时间、ESS及现有W1诊断
  checkpoint/<method>.checkpoint  # 实际文件名由共用checkpoint类决定；人可读
  complete.properties         # 最后原子写入；校验result、weights、checkpoint哈希
  failure.txt                 # 如抛出普通异常，保留原因，不写complete
```

复用`TRBSVUResultWriter`和`TRBSVUFinalCheckpoint`。墙钟字段从调用共享建模求解接口前开始，到返回后结束；optimizerTimeSec保留原求解器内部时间。没有incumbent则目标和gap为NaN；无有效bound则gap为NaN；明显LB>UB标异常，不裁成0。超时无解也是可记录的终止结果，不等于成功最优；完成标记表示输出写全，不表示已最优。

## 后续接远程前仍需做

1. 确定主实验DGP、统一CSAA及代表参数，再接生成器、生成和核对18例。
2. 接入已有独立进程调度/脱离本机会话的远程启动器；每任务一个JVM，原生stdout/stderr单独保存，失败不阻塞后续，恢复先verify再跳过。
3. 本次只将7200秒传入现有模型接口，**未实现调度层建模+求解硬墙钟截止**。部署前要补齐外层总预算及异常终止记录，不能把每次原生优化限时当作整个流程严格限时。
4. 部署时重新编译完整Java21依赖并生成代码/运行库指纹，再做极小原生模型冒烟验证；本轮未验证求解速度、求解器许可证或远程可运行性。

本轮自检范围：18/90任务计数、待定配置拒绝运行、无OOS读取隔离、种子/权重/决策读写、零权重保留、时间字段、checkpoint恢复、缺失/损坏文件拒绝及无解/界异常标记。手写微型测试fixture在系统临时目录中创建并清理，不属于正式实验数据。
