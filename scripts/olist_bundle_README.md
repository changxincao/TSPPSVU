# Olist：5组固定采购市场，D / SAA-All / RF / Exponential

此包只准备输入和接口。正式求解尚未启动。整个目录可复制到远程电脑，不需要原项目或原始CSV。

## 启动

1. 修改 `config.json` 的Java、CPLEX jar/native目录、MOSEK jar和Python路径。Java至少21，Python需要NumPy/scikit-learn。MOSEK jar只是现有Java公共类的依赖；此实验不调用MOSEK、不需要其求解许可证。CPLEX必须有有效许可证。包不包含任何许可证或凭据。
2. 检查而不求解：`powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run_olist_batch.ps1 -CheckOnly`
3. 远程后台启动：`powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start_olist_detached.ps1`

后台调度器由远程本机WMI服务创建，不作为SSH shell的子进程；关闭SSH或控制电脑不会停止它。若系统不允许WMI创建进程，入口明确报错，不宣称启动成功。远程电脑本身关机仍会停止。重启后重新启动会审计已有文件、只补未完成部分。若上次调度器退出但Java worker仍运行，入口拒绝重复启动，先等待其结束。配置变化会拒绝复用旧结果，必须使用新结果目录。

## 输入和实验协议

- `inputs/markets.tsv`：5组市场清单、采购种子20261020–20261024、输入SHA256。RF种子统一20261020。
- `inputs/market_NNN/instance.tsv`：单个可读完整算例，含104周×23 lane真实需求，15家承运人、coverage、费率、容量、MQC及选择上下限。5组真实需求完全相同，只变采购市场。远程不重新生成市场。
- 需求基准为全部104周逐lane均值；这是回顾性半合成市场校准，不声称仅使用测试期之前的数据生成采购参数。
- 选择2–12家（用户已确认）；约50% lane coverage，q为lane均值的0.3–0.5；MQC系数0.15–0.35；持续carrier费率因子0.7–1.3、lane因子0.9–1.1，spot为eligible平均费率的2–3倍，罚金单位成本为该carrier最小eligible费率。
- 当前仅D、SAA-All、Exp-CSAA、RF-CSAA；不运行DRO、RCSAA、W1或矩模型。
- D：前50周逐lane算术均值组成一个确定性需求场景；SAA-All：同样前50周全部需求场景，等权1/50。均不使用context、不选择lag/参数、不跑验证模型；输出中的lag=1、parameter=0只是统一窗口和字段的占位，validation Mean/SD为NaN（不适用）。
- Exp/RF逐周选择lag `k=1,2,3`。仅用滞后需求特征，max归一化仅拟合当前训练窗口，不裁剪大于1的query。
- 四方法都评价相同51个最终周：0-based week53–103，每次用前50周训练。Exp/RF的15个validation origins分别使用各自前35周固定长度rolling训练；验证成本是该origin的下一期已实现真实需求成本，不使用最终周需求选参。
- Exp快速试验候选B：0.1,0.25,0.5,1,2,5。欧氏距离、Exponential核，h=B/n^(1/(dimension+4))。先覆盖集中至近等权区间；不是已经证明省略的B无效。若验证最优频繁命中边界，再补充网格，保留原试验结果。
- RF500树，min_samples_leaf=1,2,5,10，multi-output demand训练，同叶节点权重。零权重保留0；正权重floor 1e-8后归一化。
- 平均验证成本最小；平局按验证SD、更小参数、更小k。每候选必须完成全部15origin；未完成不按部分均值排名。
- 默认4个独立JVM任务并行，每CPLEX4线程，单次4小时、gap1e-4。一个任务包含某市场某方法的全部51周；共20个任务，每市场按D、SAA、Exp、RF列入队列。可修改配置。超时可行解保留，标状态/认证/gap后继续评价。
- 同一market/method的相同origin结果复用：65个唯一origin，Exp1170次、RF780次验证，四方法另各51次最终求解；5市场理论上共10770次模型求解，另有实现需求recourse评价。D/SAA只增加510次模型，无重复调参。RF可另缓存权重。

## 输出和恢复

`results/market_NNN/` 内保存protocol、输入副本和rolling计划。

`validation_pool/METHOD/k*_p*/week_NNN/` 保存可复用的逐origin详细结果。

`trial_NNN/METHOD/` 内保存完整candidate成绩、逐origin结果、selection；`final/` 保存最后求解。D/SAA的candidate表只有表头，validation成绩标不适用，没有虚构origin结果。

每次模型保存weights、model_scenarios（实际进入模型的需求和权重）、训练max尺度、CPLEX原生日志、求解器stats、求解决策与incumbent、目标、bound/gap、建模+求解墙钟时间、求解器内部时间、ESS、正权重样本数。result.tsv为30列，training_size是历史观察数，scenario_count是模型场景数：D为50和1，其余final为50和50。D的模型权重ESS=1不代表只使用1个历史观察。评价保存逐lane/逐carrier成本与运输量、合同/现货/MQC成本、现货占比、总体/逐lane容量利用率。每周评价的是一次真实需求，不是1000个模拟OOS。

incumbent在评价前落盘；每完成origin即保存；异常不丢已完成记录。每个方法/周失败独立记录，其他周继续。最终完整输出后才写complete；批量调度另检查每任务51周的实际必需输出、当前完整候选清单及选参排名，不只相信marker。失败方法任务可重试一次，仍失败不阻止其他任务；重启时重新审计。

`control/status.json`保存市场/方法/状态/PID/尝试次数/退出码；`events.log`、各attempt stdout/stderr、逐任务audit保存调度追踪。不得将缺失、失败或未完成记为零成本/零改善。

`payload_manifest.tsv`记录可迁移文件的初始哈希；修改config后该文件哈希会变化，实际求解协议另冻结使用的运行设置、依赖版本及模型代码哈希。所有class均重新编译为Java21 major65，不搬用旧Java22 bin目录。
