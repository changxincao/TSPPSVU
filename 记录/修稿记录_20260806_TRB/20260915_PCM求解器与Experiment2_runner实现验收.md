# PCM 求解器与 Experiment 2 runner 实现验收（2026-09-15）

## 实现范围

按最新决定，Moment 基准只实现 PCM，不把 Mean--MAD 接入正式实验。新增 `analysis/trb_svu/solve_pcm.py`：在连续 box 支撑上固定逐 lane 均值，限制逐 lane 方差以及总需求方差；二阶段 flow、spot、MQC shortfall 对 demand 与 second-moment lift 使用 affine decision rules；由 RSOME 建模、MOSEK 11 求解。新增 `src/Test/analysis/synthetic/TRBSVUPcmSolver.java`：从带权需求样本计算经验均值、边际方差、总需求方差及 $1.5\max_s d_j^s$ 支撑上界，将边际和总量方差同时乘 $\kappa^2$，调用 Python 求解器并读取承运人选择。

`TRBSVUExperiment2Runner` 已加入 `U-PCM` 和 `C-PCM`。U 使用等权历史样本，C 使用 Experiment 1 已选定 contextual family 产生的权重；两者共享需求点、采购市场、$\kappa\in\{1,1.25,1.5,2\}$ 和 validation origins，各自仅根据训练/验证数据选择 $\kappa$。最终选择在同一100期历史样本上重算矩并求解，承运人决策使用原有等式 recourse evaluator 在同一1000个条件OOS draws上评价，进入 Experiment 2 统一CSV。`TRBSVUExperiment12Main` 的 `both` 模式现会输出原六种方法加两种 PCM，共8行。

## 公式与实现映射

对归一化权重 $\pi_s$：
\[
\hat\mu_j=\sum_s\pi_s d_j^s,\quad
\hat v_j=\sum_s\pi_s(d_j^s-\hat\mu_j)^2,\quad
\hat v_\Sigma=\sum_s\pi_s\left[\sum_j(d_j^s-\hat\mu_j)\right]^2.
\]
输入 RSOME 的矩界分别为 $\kappa^2\hat v_j$ 与 $\kappa^2\hat v_\Sigma$。总量项不是各边际方差的重复，因为它还包含 $2\sum_{j<k}\mathrm{Cov}(D_j,D_k)$。未估计完整协方差矩阵。

RSOME模型内部达到 Optimal 时，Java `Solution.certifiedOptimal=true` 仅表示所声明的 lifted-affine MISOCP 已求到最优；`solverStatus` 明确写为 `OPTIMAL_PCM_LIFTED_AFFINE_APPROXIMATION`，不能据此声称完全自适应二阶段 PCM-DRO 得到精确最优解。

## 验收证据

1. 修改的四个Java文件在Java 21/22兼容源路径下编译通过；Python脚本通过 `py_compile`。
2. 矩计算自检通过：$\kappa$ 从1变2时，所有边际方差界和总需求方差界精确放大4倍，经验均值与box支撑保持不变。
3. 独立的7-carrier、4-lane、5-scenario PCM smoke solve 使用本地 `.venv-rsome`、RSOME和MOSEK 11得到 `Optimal`，目标值 `7394.944368214047`。
4. 7-carrier、4-lane、100-history、1-validation-origin 的完整 Experiment 1/2 微型联通测试通过。Experiment 2 方法集合为 `[RSAA, RCSAA, U-Chi2, C-Chi2, U-W1, C-W1, U-PCM, C-PCM]`；U/C-PCM均完成求解和OOS评价。该微型结果只验证接口与口径，不构成方法效果证据。
5. 增加歧义集嵌套方向检查。在相同7×4市场、相同5个训练需求和相同box上，$\kappa=1,1.25,1.5,2$ 的lifted-affine PCM最优目标依次为`7358.047747931419`、`7394.944368214047`、`7434.848575039398`、`7520.945580264744`。目标随方差上界放宽而非降，符合 $\mathcal P(1)\subseteq\mathcal P(1.25)\subseteq\mathcal P(1.5)\subseteq\mathcal P(2)$ 下最小化最坏期望值应非降的理论方向。

## 进一步正确性判断与 lift 解释

当前PCM集合与数值说明书一致：Java归一化传入权重后计算精确加权均值、逐lane中心二阶矩和中心化总需求二阶矩；$\kappa^2$只放大两类方差上界，均值和support不变；box为同一训练窗口的$[0,1.5\max_s d_j^s]$。对$\kappa\ge1$，产生这些经验矩的离散经验分布本身落在box内且满足矩界，因此集合非空。U/C只换权重，采购参数、需求点和OOS不换。需求平衡、总容量、lane容量、MQC不足和选人上下界均与现行等式模型一致。

lift解决的是“二次矩无法直接作为线性期望约束进入event-wise ambiguity set”的问题。引入$L_j\ge(D_j-\mu_j)^2$和$L_\Sigma\ge[\sum_j(D_j-\mu_j)]^2$，再限制$E[L_j]$和$E[L_\Sigma]$，其关于$D$的投影与原二次矩集合相同：原分布可取$L$等于平方；反向由$L$支配平方和期望上界立即得到原矩界。平方epigraph为二阶锥，RSOME可据此生成确定性锥重构。

让物理recourse同时对$D$和$L$仿射适应，并不是定义PCM集合所必需，而是Bertsimas--Sim--Zhang/RSOME使用的lifted-LDR策略近似。仅令recourse对$D$仿射时，在线性期望成本和固定均值下，成本期望很容易只剩均值项，高阶矩不进入目标；增加$L$基函数后，期望成本含$E[L]$系数，方差/cross-moment能够影响策略与目标。RSOME官方partial-cross-moment多阶段库存示例确实令生产、库存和成本辅助决策适应原随机向量与moment lift。必须同时披露：$L$是数学辅助随机量而非现实新增观测，epigraph中同一$D$可对应多个$L$，所以该模型是文献定义的lifted策略类，不等于任意可测于需求的完全自适应recourse。本项目最终只采用其一阶段$y$，OOS时固定$y$并对每个真实需求重新求解物理二阶段LP，不部署lifted运输规则；这使其适合作为计算型矩基准，但不能将模型目标当成完全自适应PCM的精确证书。

实现目前没有发现会改变数学模型的代码错误。剩余风险是报告和规模：`certifiedOptimal=true`只表示MOSEK把lifted-affine MISOCP求至Optimal，CSV仍应依靠显式的`OPTIMAL_PCM_LIFTED_AFFINE_APPROXIMATION`状态或后续增加formulation-scope字段防止误读；$\kappa$网格与训练内选择是本文实验设计，不是PCM理论唯一设定；20×60下策略系数约十几万，尚无时间/内存证据。以上均不否定当前实现，但限制其论文表述和正式运行前的门控要求。

## 尚未验证的边界

尚未运行正式 $I=20,J=60,H=100$、30 validation origins、4个 $\kappa$、U/C两种权重及20次replications。lifted-affine变量量随 recourse dimensions 和 lift dimension快速增长，因此正式运行时间和MOSEK内存需求未知。当前 runner 若某个PCM候选在时限内未达到RSOME `Optimal`会明确失败，不会把未认证决策静默写入正式结果。正式批量运行前宜先做一个20×60单origin/单$\kappa$的性能门控，但本轮按要求没有启动大算例。
