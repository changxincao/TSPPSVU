# 旧序列 seed 的三档 CV / 最佳 CSAA 测试

## 本次确认

目标机器：`100.71.236.93`，根目录 `E:\ccx_work\TSPP_SVU_Contextual`。旧机器和既有结果不改动。
复用明确的案例种子 `20261020, 20261021, 20261022, 20261023, 20261024`，不用现在正式批量入口的六位随机种子映射。
独立生成三档：逐 lane CV 区间 `[0.3,0.5]`、`[0.4,0.6]`、`[0.1,0.3]`。每档5个市场，每市场40个普通随机 query，无 constructed/high-R query。

## 固定数据口径

- I=15，J=50，history=75，OOS=1000/query；每个市场在所有方法间共享同一批 query/OOS。
- `a_j ~ U(10,100)`；四类系数独立 `beta_jk ~ U(0,10*a_j)`；非中心化线性需求水平。
- M/P/A 随机 U(0,1)；历史 trend=t/75（t=0,...,74），query trend=1。
- 正态残差；共同载荷 rho_j ~ U(0.3,0.5)，`rho_j Z0 + sqrt(1-rho_j^2) Zj`。
- 负需求沿用当前重抽规则，固定共同 Z0、重抽 lane 独立 Zj；不偷偷改变均值修正或分布。
- 三档共用原始 CV quantile，按各自区间线性映射。采购参数、系数、历史 context、query 精确配对；由于负值重抽的次数可变，不能声称不同 CV 档的所有最终残差样本逐项相同。
- 采购基准 `a + .5*(beta_M+beta_P+beta_A) + (74/150)*beta_T`。
- coverage=50%；q~U(.3,.5)*采购基准；费率=laneRate*U(.7,1.3)的carrier因子*U(.9,1.1)的pair因子；spot markup U(2,3)；MQC share U(.15,.35)；h=min eligible rate；选人上下限2和12。

## 方法和训练内选择

- TRI：B={.8,.9,1,2}。
- Exp：B={.1,.25,.5,.8}。Medium 历史高频为 .25/.5/.8；保留 .1 以涵盖旧 Low 的较小带宽。
- RF：500棵树，min_samples_leaf={1,2,5}，其余原RF定义不变。
- 同时保留 D 和 SAA-All，便于评估 CSAA 的实际增益。
- 每个候选仍使用相同25个rolling origin；每次训练50期，验证下一期；全25个origin有效才能成为可用compact-kernel候选。
- 最终用75个历史样本重求解，随后在同一1000个OOS需求中评估。
- 每个市场的 C* 仅由三类方法的训练内验证成本确定，沿用均值、SD、较小参数的平局规则；不能用最终OOS挑 C*。
- query处为空支持时沿用已记录的验证排序 fallback，B*与实际 B_eff 分开记录。
- 下一阶段鲁棒须显式使用 `-Dtrb.svu.contextSelection=best`，读取 `validation/experiment1_selected_context.csv`；默认旧入口仍维持原TRI约定，避免改变既有批次。
- 先运行CPLEX普通方法；C-chi-square目前使用MOSEK，MOSEK配置与鲁棒启动后续处理。本轮没有把C-chi-square错称为CPLEX方法，也未自动启动MM/PCM/W1/RCSAA。

## 调度和保存

新入口 `TRBSVUGeneratePairedCvCasesMain`；新队列 `scripts/run_paired_cv_csaa_remote.ps1`。
代码部署与实验结果各放独立目录。队列最多4个方法/市场任务并行，每模型CPLEX4线程，保留当前14400秒单模型时限。
最小调度任务是“某CV档、某市场、某方法”的验证加40个query测试，三档共75个方法/市场任务（含D/SAA）。
子进程失败移至队尾重试一次，不阻断其余任务；两次失败仍记录FAILED，最终为PARTIAL而不是伪造完成。
远程控制器使用已有 `CreateProcessW` breakaway/no-window 启动器，不依赖本机或SSH连接维持。
每origin checkpoint、最终solve/OOS checkpoint、参数和权重、决策、bound/gap、成本分解均沿用已有详细输出。

## 本地验证

全源码以Java21目标编译通过。
`TRBSVUPairedCvSelfCheck` 验证了第一案例的五个旧分量seed；显式[.4,.6]与原MEDIUM生成调用的history/OOS需求逐项完全一致；三档历史/query context完全配对；候选数组防外部修改、拒绝空/重复/非法网格。
`TRBSVUProtocolRegressionSelfCheck`、`TRBSVUSyntheticDemandGeneratorSelfCheck`、`TRBSVUStatisticsSelfCheck`均通过。
新代码只增加显式CV/网格和选择入口，原正式网格、随机seed协议、核函数、距离定义、CPLEX默认MIP容差没有改变。

## 远程环境

原机器已有CPLEX22，但Java20不足以运行当前Java21目标代码；已有Python39虚拟环境的base解释器已经不存在。
因此在部署目录中放置独立Java22运行时（jlink）和Python3.12.3嵌入环境，不修改系统Java/Python/PATH。
RF依赖：NumPy1.26.4，SciPy1.17.1，scikit-learn1.8.0，joblib1.5.3，threadpoolctl3.6.0；运行时依赖版本进入RF checkpoint协议。
输入生成与CPLEX/RF远程启动状态应以 `control/status.json`、`control/events.csv` 和各任务stdout/stderr以及实际checkpoint为准；打包/上传成功不等于求解已经完成。

## 实际启动核验

2026-09-30约21:02（北京时间）成功脱离SSH启动远程控制器PID=23368。
部署：`E:\ccx_work\TSPP_SVU_Contextual\deployment_20260930`。
输入/输出：`E:\ccx_work\TSPP_SVU_Contextual\paired_cv_best_csaa_20260930`。
生成完成三档15个市场、600个随机query；逐市场核对三档的carriers/carrier_lane/lanes/queries manifest的SHA256完全一致，核对实际CV数值区间、每市场40个query及caseSeed20261020+rep均通过。
21:04核验控制器RUNNING，4个实际Java工作进程，71个任务等待、失败0；首批为cv030050/rep000的Exp、TRI、RF、SAA。四份stderr均空，CPLEX显示4线程和14400秒设置；RF首个验证模型已获得Optimal。
代码提交 `eef6ed6`；仅提交源码/队列/自检，不提交数据、环境、solver jar、日志及许可证。

## 追加CV [0.5,0.7]尾部队列（2026-09-30用户确认）

用户确认新增lane CV U(0.5,0.7)，不是旧High的U(0.7,0.9)。新目录：`E:\ccx_work\TSPP_SVU_Contextual\paired_cv_high050070_20260930`，内部单元`cv050070`。

仍为5个case seed20261020–024，每个40个普通随机query、每query1000 OOS；Normal、共同载荷U(0.3,0.5)、I15/J50/H75、采购参数及选人上下限2/12均保持不变。方法为D、SAA-All、Exp、Tri、RF，共25个方法/市场任务。参数网格与原三档相同：Tri B={.8,.9,1,2}；Exp B={.1,.25,.5,.8}；RF500树、min_samples_leaf={1,2,5}；25个rolling origin、每次训练50；4并行、每求解器4线程、单模型14400秒。不自动追加鲁棒求解。

新增输入通过现有`TRBSVUGeneratePairedCvCasesMain`生成，不改Java/DGP或求解模型。与原三档逐rep核对carriers.csv、carrier_lane.csv、lanes.csv、queries.tsv共60项SHA256相同；核对75条历史协变量及全部40个query的协变量与三档相同；每个市场50条lane实际CV均落在[.5,.7]。仅需求实现及其CV字段改变，Normal负值重抽规则仍沿用原实现。

### 队列实现与核验

`run_paired_cv_csaa_remote.ps1`只增加Cells参数，默认仍是原三档；任务和最终C*汇总均使用相同Cells。远程另存为`run_paired_cv_csaa_remote_cells.ps1`，不覆盖活动控制器原脚本。

新增`run_paired_cv_high_tail_remote.ps1`由原CreateProcessW脱离SSH启动器启动，等待原三档控制器status为FINISHED/PARTIAL且running=queued=0后才运行cv050070；若前序控制器异常退出而无终态，则报错并保留High未启动状态，防止失去前序任务状态时额外启动优化器。High运行中的失败仍沿用移至队尾重试一次、不阻断其他任务的逻辑。

PowerShell语法及默认Cells检查通过；模拟FINISHED、PARTIAL、先RUNNING后FINISHED、前序异常退出四个测试通过。远程两个新增脚本SHA256与本机一致。生成工具与脚本不依赖本机维持连接。

首次启动命令因该SSH PowerShell进程没有ExecutionPolicy Bypass而在执行启动器前被拦截；没有启动任何求解进程。随后只为该进程补上Bypass，未修改系统执行策略，成功启动等待器PID28324。

22:54:42实查新队列`WAITING_FOR_CURRENT_THREE_CV`，queued25、running0。22:55:01原队列仍RUNNING、queued52、running4、failed0；4个原优化JVM继续运行，没有增加并行数、停止或重启原任务。等待器在SSH断开后仍存活且status继续更新。新队列后续结果应以自身control/status.json、events.csv和每个query输出为准，排队成功不等于已求解。

新增脚本及设计已提交`20c0a3e`并成功推送`origin/main`。远程生成数据、运行环境、许可证和结果未进入代码提交；本记录副本保存为新增High目录的README.md。
