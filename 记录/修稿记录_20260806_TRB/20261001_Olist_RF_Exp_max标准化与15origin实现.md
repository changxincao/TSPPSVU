# Olist：RF-CSAA、Exp-CSAA与15个rolling origins

## 本轮用户要求与落实范围

2026-10-01用户要求：参照原Olist流程，max标准化，先实现RF和Exponential，15个rolling validation origins；随后明确采购市场采用当前生成方式，承运人15家。

本轮新增独立入口，不改旧Olist结果，不部署远程，不启动正式Olist求解。已做真实数据窗口/权重检查，以及独立两lane微型CPLEX与端到端检查。

## 输入与采购市场

- 读取现有purchase时间聚合的104×23周度宽表：`analysis/巴西数据分析/新版_purchase时间/输入/聚合需求表_日度与周度/按purchase时间_五大区23OD_周度宽表_10供应商实验输入.csv`。
- 文件名中的“10供应商”是历史命名，CSV只有需求列；新入口另行生成15家采购市场，不据文件名设I。
- 不重抽需求、不改变单位、不更改周序、不额外添加人工trend、宏观或促销变量。
- 保留全104周逐lane均值作为采购尺度：`dbar_j=(1/104)sum_t d_jt`。这是统一的半合成市场事后标定，不能写成采购参数仅由测试前数据设定；各方法的权重与选参仍仅使用历史窗口。
- 调用`TRBSVUProcurementGenerator.generate(15,dbar,marketSeed)`：逐carrier随机覆盖约50% lane，修复完全无人覆盖的lane。J=23时每家round(0.5×23)=12条，实际约52.17%。
- `r_ij = laneRate_j × F_i × L_ij`，laneRate~U(20,100)，F_i~U(0.7,1.3)，L_ij~U(0.9,1.1)；F_i在lane间保持承运人持续性。
- eligible容量`q_ij~U(0.3,0.5)dbar_j`；`M_i=sum_j q_ij`。
- MQC数量`g_i~U(0.15,0.35)sum_{j eligible}dbar_j`；罚率`h_i=min_{j eligible}r_ij`。
- spot费率`e_j~U(2,3) × mean_{i eligible}r_ij`。不是旧Olist的1.5–2.5倍median，也不是再乘1.25。
- 选择人数按目前Medium/Moderate测试的80%上限，为2–12家。工厂默认上限70%=11家，故仅包装返回的市场上限为12，其余市场数组及随机抽取不变。这里不是自动沿用旧I10/full coverage/1–7家。
- 默认市场种子和RF种子暂记20261020，入口中显式可改并写入protocol；这是新的半合成市场，不能称为与旧seed=0市场严格配对。

## 旧代码与真正rolling的区别

旧`BrazilOlistAdaptiveCVSolveComparison`外层是50周滚动，但内层将50分成35+15后，**固定前35周**评价15个验证周。它并不是15次滚动更新训练窗口。本轮按用户的rolling-origin要求改为每次35周固定长度滚动预测下一周。

协变量：`theta_t=(d_{t-1},...,d_{t-k})`，保留原lane-specific原始滞后需求，k∈{1,2,3}。23/46/69维；不使用当周需求组成context。

所有候选都使用同一组待预测周、同一15个验证origin及同一固定采购市场。

对0-based待预测周p：

- 外层历史为p−50至p−1，共50周。
- 验证origin为p−15至p−1，共15个。
- 验证周v仅以v−35至v−1的35条样本建经验分布，预测v；训练窗口逐origin向前移一周。
- 用15次实际已实现需求的二阶段成本均值选参；平局比较样本SD，再选较小数值参数，再选较小k。
- 选参以后，用p−50至p−1的全部50条样本重新构造权重、求解；仅将其决策代入第p周真实需求评价。
- 不为Olist伪造1000个条件OOS样本；每个origin/正式周只有一个真实已实现需求向量。

第一组0-based：验证38–52，第一origin训练3–37，最后origin训练17–51；最终训练3–52，测试53。换成论文1-based：预测第54周，验证第39–53周，最终使用第4–53周。共预测第54–104周，51个正式周，与旧kmax=3对齐。

## max标准化与权重

每次窗口单独fit：`m_l=max_{s∈training}|theta_sl|`，缩放为`theta_l/m_l`；Olist各项非负，所以就是训练max。整列训练为零时除数取1并记录，不以query或验证目标补max。query可以超过1，不clip。

仅缩放协变量；需求场景、容量、费率、MQC保持原单位。

Exp使用Euclidean距离：`rho_s=sqrt(sum_l((theta_sl-theta_query,l)/m_l)^2)`；`h=B/n^(1/(dimension+4))`；`pi_s∝exp(-rho_s/h)`，log-shift防下溢。n为该次实际训练样本数，验证35、正式50。

暂沿用现有扩展B网格：`{0.1,0.25,0.5,0.8,0.9,1,2,3,5,10,30,50,100}`，各k分别验证；这里未根据方法OOS结果删候选。

RF复用现有`TRBSVUForestWeights`及`analysis/trb_svu/rf_leaf_weights.py`，500棵多输出回归树，叶节点参数`{1,2,5,10}`，与k联合在15个origin内选择；目标为23维真实需求，不额外标准化目标。每棵树对与query同叶的历史观测等权分配，再跨树平均。保留原Python明确设置，包括bootstrap=true、max_features=1.0、n_jobs=1。

两方法复用已冻结数值处理：严格零权重保留为零；正权重小于1e-8时抬至floor后重新归一化；保存最终实际使用权重及ESS，不把1e-8宣称为新的样本筛选规则。

## 求解、时间与记录

RF-CSAA和Exp-CSAA均调用同一个加权`SAAModel`，CPLEX求解；需求约束显式为等式。只是输入概率不同，不是两套优化模型。

每次4个CPLEX线程，默认单次14400秒，gap为通常的1e-4；没有额外最优性门槛。可行incumbent可以验证/评价，状态、certifiedOptimal、bound、gap均保存；完全没有可行决策则该origin失败，候选不参加排名。只有15个origin都完成的候选才可排名，不以缺失origin平均替代。

当前入口顺序执行方法/周，不额外启动并行调度器；每个模型内部4线程。用户本轮仅要求代码准备，未请求正式开跑。

相邻外层周共享的验证origin具有完全相同的35周训练、query、采购市场和参数。其详细结果集中在`validation_pool/METHOD/k*_p*/week_*`，只求解一次；每个trial的candidate目录用`origins.tsv`保存其自身15个origin的全部结果用于重新计算均值/SD。不是共享最优参数，仍为每个外层周独立使用自己的15个origin选参。51个外层周一共只有65个不同验证origin，因此本轮完整网格最多65×3×(13+4)=3315次独立验证模型，而非51×15×3×17=39015次重复模型；另有51×2=102次最终模型。

每origin目录保存：`weights.tsv`、`max_scaling.tsv`、`incumbent.tsv`、`result.tsv`、`lane_oos.tsv`、`carrier_oos.tsv`、`logs/cplex.log`、`results/cplex_stats.csv`。result包含决策/人数、模型目标/界/gap、建模求解墙钟时间、optimizer时间、ESS、真实成本及合同/现货/MQC分解、运输数量、spot share、总体和逐lane容量利用率。

模型完成立即写incumbent，即使OOS评价失败，重启可以复用决策，不重复求解。每origin结果原子落盘；task结束后保存候选汇总、selection、final_result，再原子写complete。开始task先失效旧complete；恢复检查相关输出的行数，而非仅看complete。

protocol校验需求文件哈希、涉及的Java类哈希、RF脚本哈希、Python依赖版本、种子、线程/时限、grid等。改变设置应换目录，不静默复用旧checkpoint。

准备阶段输出一个可读`input_snapshot.tsv`（包含lane/market/104周真实需求）、protocol和rolling_plan，不修改原CSV。

## 实现位置与启动

- `src/Test/analysis/brazil/OlistContextualData.java`：真实需求读取、市场、context、rolling切分、max缩放。
- `src/Test/analysis/brazil/OlistContextualRunner.java`：两方法验证、选参、最终求解及恢复，IDE main。
- `src/Test/analysis/brazil/OlistContextualSelfCheck.java`：窗口、权重及微型流程检查。
- `OutputManager.atDirectory`：新增显式任务目录入口，避免并行时历史tag规则造成日志冲突；原构造方法不变。

直接IDE启动main默认`PREPARE_ONLY=true`，只准备数据。`run`参数或显式将该开关改false才求解。INPUT/OUTPUT、种子、起止trial、参数网格、线程及时间在入口顶部可见。默认输出`analysis_runs/olist_exp_rf_max_20261001`。

## 本轮验证证据

- Java 21目标编译通过。
- 真Olist2295个验证窗口=51×3×15：目标/context只读过去，窗口边界、独立max、query不clip、Exp归一化均通过。
- RF500树四档叶节点参数均输出35条有效概率，归一化通过；市场同seed复现、容量/MQC/min-h/coverage检查通过。
- 两lane微型CPLEX求解与等式recourse目标一致。
- 两lane/54期微型端到端两方法验证→selection→final→OOS通过；resume不改CPLEX日志，证明没有重复求解；改变时限时拒绝旧protocol。
- 正式104×23 Olist的51周两方法未求解，因此没有任何本轮正式OOS方法排序结论。
