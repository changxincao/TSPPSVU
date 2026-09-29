# Wasserstein W1 两阶段 RHS：精确重构、双算法与代码验证

> **2026-09-29 更新提示：** 本文用于保留文献与多算法审计。当前正式 CCG 的逐步流程、正权重样本 min/max box，以及“oracle 代表性最坏点不等于完整最坏概率分布”的严格说明，请优先读取 `20260929_W1_CCG完整流程_最坏点诊断与最坏分布边界.md`。本文中的旧 1.5 倍支撑规则已经作废。

日期：2026-08-23

## 0. 先给结论

当前运输服务采购问题可以用 Wasserstein 做，但要分清两种口径。

1. 朴素的“1-Wasserstein + 无界非负支撑 `R_+^J` + RHS 需求不确定”在当前 `>=`/现货补救结构下，往往会退化成加权 SAA 加一个与一阶段决策无关的常数，不能作为有独立机制的强基准。
2. 若把需求支撑显式限制为训练窗内可解释的有界 box / polyhedron，并把中心分布写成“样本 + 权重”的形式，那么 Wasserstein 可以精确重构，也可以用经典的 exact decomposition 路线求解。
3. 你仓库里已经有一套与上述第 2 点一致的 bounded-box 1-Wasserstein 原型：`WassersteinBoxInput`、`WassersteinBoxOracle`、`ContextualWassersteinBoxCcgSolver`、`ContextualWassersteinBoxSingleCutBendersSolver`、`ContextualWassersteinBoxRegularizedSingleCutBendersSolver`。2026-08-24 已把原先只支持 `h_i<=r_ij` 的 nominal/upper 专用oracle扩展为 lower/nominal/upper 通用oracle，因此 `h=min r` 与 `h=max r` 均可进入同一精确算法。当前边界仍是 box 支撑、scaled-L1 地面距离、需求等式和连续二阶段补救。
4. 因此，如果要在修稿中补 Wasserstein，建议把它定位为 bounded-box contextual W1 或 finite-support W1；不要把无界 `R_+^J` 的朴素 1-Wasserstein 当成主基准。

## 1. 当前问题属于哪一类 Wasserstein 结构

固定一阶段承运人选择 `y` 后，二阶段补救是连续线性规划，需求只进入需求平衡的 RHS：

\[
\sum_i x_{ij}+v_j=d_j,\qquad x,v,u\ge 0.
\]

因此它属于两阶段、线性补救、RHS / constraint uncertainty，而不是只改概率的纯 SAA 问题。

关键点在于：如果支撑只写成无界非负锥 `\Xi=R_+^J`，现货变量使得所有 `d\ge0` 都可行；而二阶段值函数关于需求的边际斜率又被现货费用上界住。这个结构会触发文献中的退化或边界最坏点现象。若支撑是有界 box / polyhedron，则最坏点会落在训练窗可解释的边界上，模型才会真的对 `y` 产生辨识力。

## 2. 哪些文献能直接用，哪些不能直接用

### 2.1 Hanasusanto & Kuhn (2018)

这篇是 Wasserstein 两阶段线性规划的经典起点。它的价值在于说明：1-Wasserstein / 2-Wasserstein 的两阶段模型在很多情形下可以被重构为有限凸程序；但它也提醒我们，支撑集合一旦变成无界锥，朴素 Wasserstein 的机制会非常弱。

官方来源：
https://pubsonline.informs.org/doi/abs/10.1287/opre.2017.1698

### 2.2 Mohajerin Esfahani & Kuhn (2018)

这篇是 Wasserstein tractability 的总入口。它说明 worst-case expectation 可以化成有限凸程序，某些情形甚至是 LP；但这并不自动意味着你的采购问题在无界 RHS 支撑下会有“好用”的稳健效果。

官方来源：
https://link.springer.com/article/10.1007/s10107-017-1172-1

### 2.3 Zhao & Guan (2018)

这篇早期工作把 Wasserstein 风格的 two-stage risk model 与 two-stage robust optimization 联系起来，说明当支撑、ground metric 和 recourse 结构合适时，Wasserstein 可以重构成半无限或 Benders 型模型。

官方来源：
https://www.sciencedirect.com/science/article/pii/S0167637718300506
DOI：
https://doi.org/10.1016/j.orl.2018.01.011

### 2.4 Gamboa et al. (2021)

这篇文章最重要的不是又一个 Wasserstein 结果，而是它把两阶段 RHS Wasserstein 的 exact decomposition 明确拆成三条标准路线：

- Column-Constraint Generation (CCG)
- Single-cut Benders
- Multi-cut Benders

这正是你要写的 “single-cut 正则化 cut” 的直接参照。

官方来源：
https://optimization-online.org/2020/10/8069/
期刊 DOI：
https://doi.org/10.1016/j.orl.2021.07.007

### 2.5 Saif & Delage (2021)

这篇 facility location 的 Wasserstein 工作对当前采购模型很有参考价值，因为它明确给出两种 exact iterative algorithms，并讨论了 box support 与 support lifting。

官方来源：
https://www.sciencedirect.com/science/article/pii/S0377221720308304
DOI：
https://doi.org/10.1016/j.ejor.2020.09.026

### 2.6 Duque, Mehrotra & Morton (2022)

这篇直接研究 two-stage stochastic programming 的 Wasserstein / optimal transport 形式，重点就是 RHS uncertainty。它给出的最重要边界信息是：bounded support 才容易形成真正有用的 exact decomposition；无界支撑往往会出现你不想要的退化。

官方来源：
https://epubs.siam.org/doi/abs/10.1137/20M1370227?journalCode=sjope8

### 2.7 Byeon, Fang & Kim (2025)

这篇是更新、更适合边界审计的文献。它研究 two-stage distributionally robust conic linear programming over 1-Wasserstein balls，给出 worst-case realization 落在 sample / boundary point 的条件，并提出 cutting-plane-based algorithms。

官方来源：
https://doi.org/10.1137/23M1626839

## 3. 你的问题应该怎样重构 Wasserstein

### 3.1 输入协议

你希望“输入尽可能只含每个 sample 的数据，以及每个 sample 的概率”，这个方向是对的。最自然的接口就是：

- 样本需求 `d[s]`
- 样本权重 `probability[s]`
- 支撑上界 `upper[j]`
- lane 尺度 `scale[j]`
- Wasserstein 半径 `radius`

于是：

- SAA 只需把 `probability[s]` 设成均匀权重；
- contextual Wasserstein 只需把 `probability[s]` 设成 NW / kernel / kNN 等方法给出的权重；
- Wasserstein 模块本身不需要再知道协变量。

这正对应仓库中的 `WassersteinBoxInput`。

### 3.2 支撑集

若要一个既可解又有意义的 Wasserstein 基准，支撑最好写成训练窗内可解释的 box / polyhedron：

\[
\Xi=\{d: 0\le d_j\le \bar d_j,\ \forall j\}.
\]

不要只写 `d>=0`，因为在当前结构下那样很容易退化成没有独立稳健作用的模型。

当前代码把下界固定为0，上界 `upper[j]` 由实验入口传入，并没有在求解器内部读取baseline或OOS。按2026-08-24决定，pilot只保留一条训练期规则：

\[
\boxed{\bar d_j=1.5\max_{s\in\mathcal S_{\rm train}}\hat d_j^s.}
\]

旧的`NOMINAL_200`和`TRAIN_MAX_125`不再是当前入口选项，baseline不参与box构造。该规则仍是需要在正式实验前预先声明的训练数据规则；不得查看OOS后调整。若synthetic DGP本身有已知真支撑，可另用真界作为机制对照；无界lognormal若使用该bounded-W1，必须明确它是训练期截断box，而不是声称lognormal天然有界。Duque--Mehrotra--Morton的数值实验也采用下界0并按生成训练样本的逐维最大值构造box，上述1.5倍是在其思路上增加训练期安全余量，不是该文献原样参数。

### 3.3 地面距离

这里要区分两件事：`W1`中的“1”是Wasserstein阶数，ground metric本身仍可选`l1/l2/l∞`。当前代码采用scaled-L1：

\[
c(d,d')=\sum_j \frac{|d_j-d'_j|}{\tau_j}.
\]

这对长尾 lane 更合适，因为它把不同 lane 的量纲拉回同一尺度上。若不做标准化，极大 lane 会主导 Wasserstein 半径。对本文的exact RHS分解，scaled-L1也是最有直接文献和算法依据的选择：Gamboa et al.、Duque--Mehrotra--Morton及Saif--Delage的box/RHS路线都利用L1的逐维可分性。Byeon--Fang--Kim允许一般`lp` ground norm，并在其facility-location实验中发现L2的OOS表现更平滑，但同时说明L1+hyperrectangle才具有每维`lower/sample/upper`的有限候选结构；这是一项算例证据，不能据此断言L2普遍优于L1。本文首版保留scaled-L1，若以后比较L2，需要新的conic/cutting-plane oracle，不能只改一行距离公式。

### 3.4 box为什么重要，耦合支撑又是什么

box

\[
\Xi=\prod_j[0,\bar d_j]
\]

只表示每条lane各有上下界；它**不表示各lane随机独立**。它的算法意义是笛卡尔积结构使固定recourse dual后的内层问题按lane分解，所以每个坐标只需在`0/样本值/上界`三点中选一个。多维时不是显式枚举`3^J`：oracle为每个lane设置两个二元变量，同时在一个MILP中隐式选择三点组合。

若再加入`sum_j d_j<=D_max`或一般`Ad<=b`，表达的是需求向量的物理组合限制，例如所有lane不能同时达到各自上界。此时各坐标不再独立，最优点可能是`Ad=b`与若干分段区域的交点，而不一定逐坐标都属于`{0,hat d_j^s,bar d_j}`。模型仍可能精确求解，但必须保留连续`d`、绝对值分段变量和`Ad<=b`重写一般separation MILP；不能把当前三端点box oracle原封不动地使用。

## 4. 两条精确求解路线

### 4.1 CCG / Benders 型 exact iteration

基本结构是：

1. 外层 master 选择 `y`、`eta` 和每个样本的 `t[s]`；
2. 内层 oracle 对每个 sample 解一个最坏扰动问题；
3. 若 oracle 找到违反 cut 的点，就把它加进 master；
4. 迭代到上下界 gap 收敛。

这条路线和 `ContextualWassersteinBoxCcgSolver` 一致。

### 4.2 single-cut Benders 与 level regularization

single-cut 的做法是把所有样本的最坏响应按概率加权聚合成一条 cut，而不是每个 sample 单独保留一组 cuts。`ContextualWassersteinBoxSingleCutBendersSolver` 做的就是这件事。

`ContextualWassersteinBoxRegularizedSingleCutBendersSolver` 则是在 single-cut 外面加 level-method 正则化。要强调的是：它不改变 Wasserstein 模型，只改变查询点路径。

## 5. 你仓库里现有代码到底对不对

结论是：**对，但有边界。**

### 5.1 已经对上的部分

- `WassersteinBoxInput`：
  - 接收 `demand[][] + probability[] + upper + scale + radius`；
  - 同时兼容 SAA 权重和 contextual 权重；
  - 不需要协变量直接进入 Wasserstein 模块。

- `WassersteinBoxOracle`：
  - 实现 bounded-box 下的 sample-wise exact separation；
  - 每个lane的最坏坐标在 lower、nominal 与 upper 三点之间切换；
  - 与文献中“极点 / 边界点最坏”的结构吻合。

- `ContextualWassersteinBoxCcgSolver`：
  - 是 primal-block CCG；
  - master + oracle 结构正确；
  - 可作为 exact proof-of-concept。

- `ContextualWassersteinBoxSingleCutBendersSolver`：
  - 是 aggregate single-cut Benders；
  - 结构上对应 Gamboa 一类方法。

- `ContextualWassersteinBoxRegularizedSingleCutBendersSolver`：
  - 是 level regularization；
  - 只改变迭代路径，不改模型本身。

### 5.2 需要在修稿里明确写出的限制

1. 这套代码不是一般的 “standard 1-Wasserstein + 无界 `R_+^J`” 模型，而是 **box-supported** exact pilot。
2. `h_i <= r_ij`不是Wasserstein模型本身的要求。固定一阶段决策后，recourse对偶中的需求斜率为`alpha_j`。当`h_i<=r_ij`时，可取`alpha_j>=0`，lower不会优于nominal；当`h_i>r_ij`时，增加需求可能以低于MQC罚金的运输替代shortfall，`alpha_j`可以为负，lower可能成为最坏端点。当前通用oracle已用`alpha_j in [min{0,min_i(r_ij-h_i)},e_j]`及每lane两个endpoint binaries同时处理两种情况，不再拒绝`h=max r`。
3. 这套代码不应被写成“已经解决了所有 contextual Wasserstein 问题”。它只证明：在 bounded-box + scaled-L1 + equality-demand 口径下，exact Wasserstein 是可以做的。

## 6. 给审稿回复的建议口径

如果审稿人问为什么不直接做 Wasserstein，最稳妥的说法是：

1. 本文的 modified chi-square 保护的是场景概率 / 权重误差，保留有限支持，和当前 RCSAA 的 mean–SD 正则化关系直接对应；
2. Wasserstein 当然可以建模，但必须区分真正的“无支撑约束”`Xi=R^J`与仍含非负支撑约束的`Xi=R_+^J`。前者并不直接适用于当前非负需求等式 recourse；后者在`h=min r`、需求成本单调且上方无界时，W1保护项会退化为由固定现货边际成本决定的决策无关常数，但在`h>r`、低需求端可能触发MQC损失时不一定退化；
3. 因此若要补 Wasserstein，必须显式引入有界支撑、地面距离和半径选择，并使用 exact CCG 或 Benders 分解；
4. 这已经是一个新的数值模块，而不是简单把现有 chi-square 模型里的一个参数换掉。

## 7. 本轮的落地建议

如果你只想补一个最小但严谨的 Wasserstein 基准，我建议：

- 输入保持 `WassersteinBoxInput.fromData(...)` 这种样本 + 概率接口；
- SAA 用等权，contextual 版本用训练期权重；
- `upper` 当前冻结为每条lane训练最大需求的1.5倍；
- ground metric 用 scaled-L1；
- 主实验用 `ContextualWassersteinBoxCcgSolver`，`SingleCut` 和 `RegularizedSingleCut` 做算法对照。

如果你要的是“standard W1 + 无界非负支撑 + 当前 RHS 结构”的理论强基准，不能笼统声称它总会改变或总不会改变一阶段选择：`h=min r`的单调 recourse 会使上尾 Lipschitz 保护项成为常数；`h>r`时下边界仍可能产生决策相关的低需求风险。当前 bounded box 的目的，是给两端移动都提供明确、可审计的物理范围。

## 8. 参考文献与官方链接

- Hanasusanto, G. A. and Kuhn, D. (2018). Conic programming reformulations of two-stage distributionally robust linear programs over Wasserstein balls. *Operations Research*, 66(3), 849–869. https://pubsonline.informs.org/doi/abs/10.1287/opre.2017.1698
- Mohajerin Esfahani, P. and Kuhn, D. (2018). Data-driven distributionally robust optimization using the Wasserstein metric: Performance guarantees and tractable reformulations. *Mathematical Programming*, 171, 115–166. https://link.springer.com/article/10.1007/s10107-017-1172-1
- Zhao, C. and Guan, Y. (2018). Data-driven risk-averse stochastic optimization with Wasserstein metric. *Operations Research Letters*, 46, 262–267. https://doi.org/10.1016/j.orl.2018.01.011
- Gamboa, C. A., Valladão, D. M., Street, A., and Homem-de-Mello, T. (2021). Decomposition methods for Wasserstein-based data-driven distributionally robust problems. *Operations Research Letters*, 49(5), 696–702. https://doi.org/10.1016/j.orl.2021.07.007
- Saif, A. and Delage, E. (2021). Data-driven distributionally robust capacitated facility location problem. *European Journal of Operational Research*, 291(3), 995–1007. https://doi.org/10.1016/j.ejor.2020.09.026
- Duque, D., Mehrotra, S. and Morton, D. P. (2022). Distributionally robust two-stage stochastic programming. *SIAM Journal on Optimization*, 32(3), 1499–1522. https://epubs.siam.org/doi/abs/10.1137/20M1370227?journalCode=sjope8
- Byeon, G., Fang, K. and Kim, K. (2025). Two-stage distributionally robust conic linear programming over 1-Wasserstein balls. *SIAM Journal on Optimization*, 35(1), 506–536. https://doi.org/10.1137/23M1626839

## 9. 本文件对应的仓库代码

- `src/Model/WassersteinBoxInput.java`
- `src/Model/WassersteinBoxOracle.java`
- `src/Model/ContextualWassersteinBoxSolver.java`
- `src/Model/ContextualWassersteinBoxCcgSolver.java`
- `src/Model/ContextualWassersteinBoxSingleCutBendersSolver.java`
- `src/Model/ContextualWassersteinBoxRegularizedSingleCutBendersSolver.java`

## 10. 代码正确性验证与当前完成边界

本轮不仅做了边界审计与文献对齐，也实际新增了上述输入、oracle、C&CG、single-cut 和 level-regularized single-cut 实现，并修正旧 `ContextualWassersteinBoxSolver` 的概率归一化口径：精确零概率不再被 probability floor 改成正概率。

验证采用独立穷举参考，而不是让几种分解算法彼此循环对拍。参考程序枚举所有可行的一阶段承运人选择和各样本的有效 `{lower, nominal, upper}` 组合，再解剩余的 `eta` 线性问题。正式门槛包括三部分：

- 6 个手工边界 case：`equal/contextual/exact-zero probability` 分别取 `radius=0/0.4`，四种算法共 24 条结果，均与独立参考一致；连同参考行共 30 行。
- 8 个额外随机小实例：C&CG、single-cut、regularized single-cut 和 dual-vertex 共 32 条结果，全部 certified；相对独立穷举参考的最大目标绝对误差为 `1.42108547152e-14`。
- 一个专门的`h>r` lower-endpoint反例：正确三端点目标为12，故意删除lower后的错误目标为6；四种算法均返回12，证明lower分支实际参与而不是只取消输入检查。

输入拒绝门槛覆盖负半径、总概率为零和需求落在box外；`h_i>r_ij`现在是合法且已验证的输入。更新后的结果位于`analysis/wasserstein_general_box_exact_validation_20260824/`，其中`validation.txt`与`lower_endpoint_gate.txt`均通过。

概率处理也必须分层说明。早期W1原型曾把每个`p_s`先替换为`max(p_s,1e-8)`再统一归一化，这会给理论上为零的中心样本人为增加微小质量。现在`WassersteinBoxInput`只检查`p_s>=0`和总质量为正，再除以总和；因此输入`[0,0.2,0.8]`仍精确为`[0,0.2,0.8]`，oracle会跳过零质量中心原子。这个修改只纠正中心分布语义，不改变Wasserstein球的支撑box。需要注意：现有指数/Gaussian核的`WeightCalculator`本身仍使用`1e-8`数值floor，而kNN会产生精确零权重；因此“W1模块保留零”不等于“所有上游contextual估计器都不做floor”。正式W1若采用kNN中心可直接保留稀疏零权重；若采用当前kernel中心，应如实说明上游floor，或在W1专用权重构造中另行取消，而不能混淆两层。

以上是小规模数学/实现正确性门槛，不是论文尺度的性能实验。尚未完成的是：把该求解器接入正式 synthetic/rolling 管线、训练内选择 Wasserstein 半径、在共同 OOS draws 上与 SAA/CSAA/modified-chi-square DRO 做大规模配对比较。

## 11. 一般多面体支撑与概率 floor 跨方法审计（2026-08-24）

### 11.1 三端点结论解决了什么、没有解决什么

对第 `s` 个中心样本，box + scaled-L1 下只需考虑每个lane的`lower/nominal/upper`三类位置。这说明最坏需求向量存在于至多`3^J`个有限候选组合中，但不会直接告诉算法是哪一个组合。当前`WassersteinBoxOracle`没有显式枚举`3^J`，而是使用每个lane两个二元变量，在给定一阶段决策`y`和Wasserstein对偶变量`eta`时解一个MILP来选出最坏组合。外层CCG或Benders仍需迭代，因为`y`和`eta`也是待优化变量。

### 11.2 加入总需求上限或一般多面体约束后是否仍能处理

若把支撑改为

`Xi={d: 0<=d<=U, sum_j d_j<=Dmax}`

或更一般的

`Xi={d: 0<=d<=U, A d<=b}`，

Wasserstein对偶和外层cutting-plane框架仍成立。第`s`个分离问题仍是

`max_{d in Xi, pi in Pi} b(y,pi)+pi^T d-eta*sum_j |d_j-dhat_sj|/tau_j`。

变化在于：box时的逐lane独立三选一结构消失，候选点不再只是原box的`3^J`组合，而是`Xi`被各超平面`d_j=dhat_sj`切分后所得多面体的顶点，可能包含`A d=b`与若干lower/nominal/upper分段面的交点。理论上仍是有限的，也可用exact scenario/vertex generation处理；实践上必须全局求解这个耦合分离问题，当前box oracle不能只加一行`Ad<=b`后继续声称正确。

现有文献的能力边界如下。Gamboa et al.和Duque--Mehrotra--Morton给出的直接MILP分离主要依赖hyperrectangle + L1；Saif--Delage明确研究bounded polyhedral support + L1，并在其两阶段capacitated facility-location结构上给出dual-vertex CCG与support-lifting column generation两种exact算法；Byeon--Fang--Kim允许一般非空闭凸支撑并给出有限epsilon收敛的cutting-plane框架，但一般分离成为需要全局求解的非凸QCQP，只有box等特殊结构才容易化成MILP。Hanasusanto--Kuhn的限定必须单独说明：其一般polyhedral support有限维精确重构主要针对`2-Wasserstein + Euclidean squared distance`，得到polynomial-size copositive program；这是理论精确但copositive cone本身通常不可处理，文中实际使用SDP cone hierarchy近似。该文`1-Wasserstein`的tractable LP定理要求不含support constraints，即`Xi=R^K`，不能用来声称“bounded general polyhedron + W1”存在可直接交给CPLEX/MOSEK的紧凑LP。对当前采购模型，`sum d<=Dmax`是值得单独开发专用oracle的较简单耦合支撑；一般`Ad<=b`在理论上可以纳入，但不能预先承诺计算效率或直接复用现有代码。

### 11.3 SAA、kernel、kNN、modified-chi-square与W1的floor兼容性

只读代码审计与现有回归门槛得到以下结论。

- SAA直接使用`1/S`，不存在floor问题。
- 指数/Gaussian kernel在`WeightCalculator`中先用log-sum-exp计算，再把每个归一化权重下限设为`1e-8`并再次归一化。因此kernel-CSAA看到的全是正权重。
- kNN明确给最近的`k`个样本权重`1/k`，其余样本权重严格为0。`SAAModel`按输入权重原样建模，所以零权重场景只造成冗余变量/约束，不改变kNN-CSAA目标。
- exact RCSAA的`RCSAADecompositionSupport`和modified-chi-square的`DROModel/DROBenders`会再次把所有权重抬到`1e-8`后归一化。对已经floor过的kernel权重，第二次处理的数值差异极小但并非字面完全相同；对kNN的精确零权重，它会给非邻居人为增加总计约`(S-k)*1e-8/(1+(S-k)*1e-8)`的质量，因此数学上不再是严格kNN中心。
- `WassersteinBoxInput`只检查非负并归一化，不施加floor。三种新exact算法对零概率中心不调用oracle；CCG master中保留的零概率初始block不进入目标且不限制`y/eta`，只带来少量冗余。包含精确零概率的随机门槛已经与独立参考一致。

因此当前各方法在数值上可运行，但kNN跨CSAA、RCSAA/modified-chi-square与W1的中心分布语义尚未完全统一。最小且严格的正式口径是：先用完整训练窗`N`个样本拟合standardizer并计算到query的距离，再选出最近`k`个样本；随后在进入任何求解器之前删除其余`N-k`个零权重样本，只保留`k`个邻居并赋正实数概率`1/k`。零质量原子从离散经验分布中删除是严格等价的，但必须区分原始训练信息量`N`和求解器有效支持大小`N_eff=k`；不同query的邻居集合也可能不同，不能全局只删一次。kernel则保留当前上游一次floor并归一化，后续求解器理想上只验证和归一化而不重复floor。这里的概率是正实数，不是整数。

现有两个无求解器回归门槛已再次执行并通过：`TRBReviewerKnnWeightValidation PASSED`与`SOLVE_BRIDGE_VALIDATION_OK`。本节是审计结论，尚未修改旧modified-chi-square/RCSAA的floor代码，也尚未新增统一的active-sample压缩入口。

### 11.4 矩歧义集最终表述

对本文希望比较的“连续box/polyhedral支撑 + 均值/协方差约束 + unrestricted two-stage recourse”，当前没有可直接落地到`J=23`并可扩展求解的精确基准。RSOME只能通过affine/LDR recourse形成受限策略近似。有限固定支持上的概率矩LP虽然精确，但只是对既定样本重分配概率，不是标准的support-moving矩DRO。因此实践结论可以写成“当前问题不采用矩DRO精确baseline”，但不能写成数学上所有矩DRO都不存在精确方法；单阶段、有限支持或特殊结构仍有精确特例。

## 12. 无支撑退化条件、理论来源与代码复核（2026-08-24）

### 12.1 “无支撑时是常数”只在什么条件下成立

对固定一阶段决策`y`，把线性recourse写成

`Q(y,d)=max_{pi in Pi}{a(y,pi)+alpha(pi)^T d}`。

在无界支撑和scaled-L1地面距离下，W1对偶中的保护斜率由

`L(y)=sup_{pi in Pi} max_j |alpha_j(pi)|*scale_j`

决定，最坏期望形如经验期望加`radius*L(y)`。一般模型中的`L(y)`可以依赖`y`，所以“没有support constraints”绝不等于整个目标函数是常数。当前采购模型的特殊性在于：需求只以固定系数进入RHS，承运人选择改变截距、容量和MQC项，但不改变无限增加需求时由现货变量决定的边际斜率。因此在`h=min r`且recourse对需求单调时，新增W1保护项是决策无关常数；原来的经验期望部分当然仍依赖`y`。若随机量乘在随`y`变化的约束系数、目标系数或recourse矩阵中，则`L(y)`会变化，无支撑W1仍可改变决策。

还必须指出，`Xi=R_+^J`本身已经是support constraint，不是Hanasusanto--Kuhn (2018)中`Xi=R^J`的“no support constraints”。当前需求等式加非负运输/spot变量也不对负需求具有complete recourse，所以该文的无支撑W1 LP不能原封不动套到本文。`h>r`时，`d=0`下边界可能成为MQC风险端点，即使没有有限上界也未必退化成常数。

### 12.2 当前实现的直接理论来源

Hanasusanto--Kuhn (2018)提供两阶段Wasserstein重构的总体理论基础，但其一般多面体支撑的精确有限模型主要是`2-Wasserstein` copositive program；理论精确不等于可直接由CPLEX/MOSEK精确求解。其可直接化为LP的`1-Wasserstein`结果要求`Xi=R^J`且无support constraints。

当前Java实现更直接对应Gamboa et al. (2021)：RHS uncertainty、rectangular support、L1 ground norm、有限端点重构，以及C&CG、single-cut Benders和regularized single-cut Benders。Duque--Mehrotra--Morton (2022)进一步支持连续bounded/unbounded support的两阶段切平面分析。当前代码在Gamboa的box结构上补全了`h>r`时必须保留的lower/nominal/upper三端点，而不是使用只含nominal/upper的单调特例。

### 12.3 2026-08-24重新编译与独立复核

本轮从源码对`WassersteinBoxInput`、共享MILP oracle、C&CG、single-cut、level-regularized single-cut、旧dual-vertex实现和pilot执行Java 21 `-Xlint:all`定向编译，编译成功且无warning。随后重新运行独立穷举门槛，输出位于`analysis/wasserstein_code_reaudit_20260824/`：30条手工/参考结果、32条随机算法结果全部certified；随机实例相对独立参考的最大目标绝对误差为`1.42108547152e-14`。`h>r`专门反例中，三端点正确目标为12，故意删除lower后的错误目标为6，四套算法均返回12。

公式和实现层面没有发现会使已认证最优解错误的缺陷。唯一需要控制的工程风险在旧`ContextualWassersteinBoxSolver`：它在正常收敛时与独立参考一致，但若达到迭代上限，会返回最后一次迭代的可行上界，而不是历次最小可行上界；结果会标记`certifiedOptimal=false`，不会伪造最优证书，但非认证incumbent可能不够好。正式实验应使用已保留`bestUpper/bestY`的新C&CG或single-cut实现，并拒绝把任何`certified=false`行纳入正式比较。pilot当前仍调用旧实现，因此在正式批量运行前应最小切换到新求解器，或至少强制所有行通过certification gate。

## 13. `h/r`、complete recourse、copositive与2018文献的详细辨析（2026-08-24）

### 13.1 `h<=r`为何与低需求端有关，但`h=max r`不自动推出“非常数”

以单承运人、单lane、容量足够且`0<=d<=p`为例，若把全部需求交给合同承运人，则

`Q(d)=r d+h(p-d)=hp+(r-h)d`。

所以低需求段的边际斜率为`r-h`。当`h<=r`时，该斜率非负，需求降低不会提高总成本；这是recourse关于需求单调不减的一个简明充分条件。取`h=min_j r_ij`可以保证对该承运人所有eligible lane都有`h<=r_ij`。取`h=max_j r_ij`则通常在较便宜lane上出现`h>r_ij`，低需求段斜率可能为负，`d=0`可能成为MQC坏端点。

但`h>r`只表示lower端风险“可能有效”，并不自动说明无界上方W1保护项一定依赖决策。若support为`R_+^J`且上方无界，有限最坏期望会强制`eta`至少覆盖高需求无限增长的spot斜率`L_up=max_j e_j*scale_j`。只有当某个有效低需求反向斜率`(h_i-r_ij)*scale_j`足够大，或lower端有限损失在`eta=L_up`下仍超过nominal，lower才会真正进入最坏值。若spot斜率已经更大，`h=max r`也可能仍得到“经验期望+radius乘固定常数”。因此`h<=r`是常数退化的充分条件而不是必要条件，`h=max r`则只是取消了保证，不等于必然非常数。

当前采用有限box`0<=d<=U`后，上方不能走向无穷远，`eta`不再被固定spot recession slope强制卡住；模型会在有限upper/lower移动与`radius*eta`之间权衡。此时即使`h=min r`，bounded-box W1仍可因不同承运人组合在有限需求范围内的容量、费率和spot暴露不同而改变一阶段决策。

### 13.2 complete recourse的准确含义

相对完全补救（relatively complete recourse）是：对每个可行一阶段决策`y`以及support中每个需求`d`，都至少存在一个可行的二阶段`x,spot,shortfall`。它只保证“总能补救”，不保证成本低。本文在非负需求support上有这一性质：无限spot保证任何高需求都能满足，非负shortfall变量保证MQC不足不会导致不可行。

若把support错误扩成整个`R^J`，负需求会要求非负的`x+spot`等于负数，二阶段立即不可行。因此当前模型并不对`Xi=R^J`具有complete recourse；Hanasusanto--Kuhn无support的W1 LP定理不能直接套用。若spot有上限，高需求也可能不可行；若删除MQC shortfall变量，低需求也可能使MQC约束不可行。

### 13.3 copositive精确为何不等于标准求解器可解

对称矩阵`M`若对所有非负向量`z>=0`满足`z^T M z>=0`，就称为copositive。它比半正定条件弱：PSD要求上述不等式对所有实向量成立。copositive矩阵集合是凸锥，但判断一个给定矩阵是否属于该锥本身一般是计算困难的；CPLEX和MOSEK没有可直接精确处理的copositive cone。

Hanasusanto--Kuhn利用copositive cone把`2-Wasserstein`距离产生的二次项、连续support和recourse对偶中的双线性关系有限维地精确编码。这证明了模型存在紧凑的精确锥重构，但实际计算通常要用PSD加元素非负锥或更高阶SDP hierarchy近似；这些是保守近似，不应称为标准MOSEK精确解。

### 13.4 两篇2018文献以及cutting-plane记忆来源

Hanasusanto--Kuhn (2018)的主结果是：`2-Wasserstein+polyhedral support`得到copositive重构；`1-Wasserstein+Xi=R^J+无support constraints+固定recourse objective coefficients`得到可处理LP。该文提到的RHS cutting-plane段落位于其两阶段robust optimization推论之后，引用的是既有robust CCG/cutting-plane方法，例如Zeng--Zhao (2013)；该段说明每轮需解双线性maximization，并非该文给出的“bounded-support W1普通LP算法”。

Zhao--Guan (2018)更接近“2018年RHS Wasserstein cutting-plane”的记忆。该文从一开始就假设`Omega`为compact convex sample space，并假设相对完全补救以及`Q(x,xi)`在`Omega`上有界。它把最坏期望写成半无限约束

`Q(x,xi)-N lambda_i-beta*rho(xi,xi_i)<=0, for all xi in Omega`，

并指出当`Omega`凸且距离为L1/Linf时可形成半无限线性模型，再调用作者博士论文中的Benders方法。短文没有像Gamboa et al. (2021)那样把rectangular support的有限三端点重构和C&CG、single/multi-cut、regularized算法完整展开。

因此当前代码的文献链应理解为：Zhao--Guan提供Wasserstein到鲁棒半无限模型的基础；Hanasusanto--Kuhn说明一般两阶段问题的锥重构和可处理性边界；Gamboa et al.针对`RHS+rectangular support+L1`给出当前代码最直接采用的exact master-oracle分解。

### 13.5 旧求解器“返回最后一次而不是最好一次”的具体含义

分解第`k`轮会产生master下界`LB_k`，并对当前`(y^k,eta^k)`用完整oracle计算一个可行上界`UB_k`。不同整数承运人组合会使`UB_k`上下波动，正确incumbent必须始终保存`min_{t<=k} UB_t`及其`y^t,eta^t`。本轮日志中曾出现`UB=21.10,11.77,10.03,23.90,10.006`；若恰在第4轮停止，旧实现会返回23.90对应的最后决策，尽管第3轮已经找到10.03。

这不是“把错误答案认证为最优”：旧实现会把这种迭代上限结果标为`certifiedOptimal=false`。问题在于未认证结果质量不必要地差，若直接拿去做OOS比较会污染实验。本轮已经修复旧类，使其同样维护`bestUpperBound/bestY/bestEta`；并把正式`TRBReviewerContextualWassersteinBoxPilot`切换到新C&CG，分解轮数上限由200提高到500，认证容差由项目默认`1e-4`收紧为`1e-6`，且任何`certifiedOptimal=false`都会直接抛错而不写入正式比较。

新增的迭代上限回归故意把旧类限制为4轮。在可行上界序列`21.10,11.77,10.0333,23.90`下，修复后返回10.0333而不是最后一轮23.90；其固定`y`最优值与全局参考均为10.0067，因此该行仍正确标为`certified=false`。证据为`analysis/wasserstein_code_reaudit_v2_20260824/iteration_limit_incumbent_gate.txt`。全收敛的30条手工/参考和32条随机算法结果继续全部通过，随机最大误差仍为`1.42108547152e-14`。

正式入口另做了正半径集成smoke：`I=10,J=23,S=50`、`radiusMultiplier=0.025`、`upper=1.5*training max`、scaled-L1。新C&CG用6轮、205个support points和300次oracle得到`OPTIMAL_W1_CCG`，`certified=true,gap=0`；结果位于`analysis/wasserstein_pilot_positive_radius_smoke_v2_20260824/summary.csv`。半径0入口也验证与CSAA目标、决策和OOS逐项一致。

## 14. 离散中心、连续歧义集与决策相关左右斜率（2026-08-24）

### 14.1 离散中心不等于歧义集只能包含离散分布

SAA或contextual经验中心写成

`P_hat=sum_s p_s delta_{d_hat_s}`。

它是一个有限离散分布，但Wasserstein球定义为

`P_epsilon={Q in M(Xi): W_1(Q,P_hat)<=epsilon}`，

其中`M(Xi)`是支撑在`Xi`上并具有所需有限矩的全部概率测度。只要`Xi`是连续box、polytope、`R_+^J`或`R^J`，`Q`可以是离散分布、连续分布或二者的混合；模型并未要求最坏分布只能在原始样本上重新分配概率。Wasserstein相对于modified chi-square或有限支持概率DRO的关键区别，正是它允许把原子质量从`d_hat_s`搬到support中的新位置。某些线性/分段线性模型存在有限原子数的最坏分布或渐近最坏离散序列，这是极值结构或计算结论，不是歧义集本身被限制为离散分布。只有额外把`Xi`指定为有限集合`{d^1,...,d^K}`时，球内分布才只能在这些既定位置上取质量。

中心概率也不必等权。`p_s=1/S`给出SAA中心，NW、Gaussian kernel或kNN产生的正概率可直接形成contextual中心；零概率原子在测度上等价于删除。中心是离散的，只是因为训练信息以有限样本出现，并不会限制球内候选分布的连续性。

### 14.2 一般模型中“共同常数”的准确判据

设一般损失为`ell(x,xi)`。W1对偶中的每个中心原子比较的是

`ell(x,xi)-eta*distance(xi,xi_hat_s)`。

`ell`是业务损失，`eta*distance`是移动概率质量的数学惩罚；`eta`是Wasserstein运输预算的影子价格，而不是业务运输费。若support在某方向无界，损失沿该方向以每单位`L(x)`持续增长，则`eta<L(x)`会使对手无限搬移概率并获得无界净收益，所以有限性迫使`eta>=L(x)`。当全部不利方向的最大持续斜率为`kappa(x)`时，无support、凸损失和适当complete-recourse条件下，最坏期望形如

`empirical loss(x)+epsilon*kappa(x)`。

只有`kappa(x)`对所有决策相同时，第二项才是决策无关共同常数；经验损失本身从来没有变成常数。如果左、右尾斜率`L_left(x)`和`L_right(x)`都依赖决策，则

`kappa(x)=max{L_left(x),L_right(x)}`

通常也依赖决策，Wasserstein正则项就会改变决策。斜率依赖`x`不会破坏Kantorovich对偶或两阶段RHS重构；它只使保护项不能预先从目标中删掉。对于可表示为有限个仿射片最大值的损失，可枚举仿射片得到有限LP/MILP；片数很大时用Benders/constraint generation动态生成。若斜率以非凸方式依赖`x`，则分离可能成为全局非凸问题，此时理论对偶仍成立，但不能声称普通LP/MILP可直接求解。

### 14.3 `R_+`无上界与bounded polytope必须区分

在一维正支撑`Xi=R_+`下，右侧无界而左侧止于0。设某一仿射片为`b_k(x)+a_k(x)xi`，scaled-L1距离系数为`1/tau`。对中心样本`xi_hat>=0`，内部一维最大化精确为

`+infinity`，若`a_k(x)>eta/tau`；否则为

`max{a_k(x)*xi_hat, -eta*xi_hat/tau}`。

第一项对应停在nominal，第二项对应搬到lower=0；上方无穷远通过recession约束`eta>=tau*a_k(x)`控制，而不是用一个upper endpoint控制。若所有右尾斜率和左侧损失率都随`x`变化，完整模型仍可写成带有这些决策相关recession约束和lower/nominal epigraph的有限模型，前提是`a_k(x)`对`x`具有可处理的线性/凸表示。它一般不再是SAA加共同常数。

bounded box或一般bounded polytope没有无穷远方向，因此不需要上述recession强制。box下每个仿射片、每个lane的候选为lower、nominal和upper；一般耦合polytope则通过support-function对偶变量处理。有限边界使模型能够在`epsilon*eta`与移动到边界造成的有限业务损失之间权衡，保护项通常随决策变化。

### 14.4 Mohajerin Esfahani--Kuhn (2018)对support的准确要求与算法含义

上传的`10.1007/s10107-017-1172-1.pdf`在Section 5.3、Corollary 5.4(ii)、式(18b)明确处理RHS不确定的两阶段线性追索：

`ell(xi)=min_y{q^T y: Wy>=H xi+h}`。

该推论假设`Xi={xi:Cxi<=d}`是polytope，即有界多面体，并假设recourse对偶可行域`{theta>=0:W^T theta=q}`非空且紧。它先枚举该对偶多面体的全部顶点`v_k`，把recourse值函数写成有限个仿射函数的最大值，再通过support-function对偶得到精确有限模型(18b)。Remark 5.5指出顶点数`K`可能相对原问题描述呈指数增长，因此完整LP虽精确但可能很大。

由此自然得到exact row generation/Benders：主问题只保留部分`v_k`约束；固定当前一阶段决策与`eta`后，分离问题寻找最违反的recourse对偶顶点及support点；若存在违反则加入对应cut，若全局分离无违反且主问题已最优，则获得完整模型的最优证书。这与经典Benders按需生成对偶极点约束是同一逻辑。该2018论文给出的是完整重构，不是在第5.3节详细实现这套迭代算法；Zhao博士论文/Zhao--Guan提供半无限模型和Benders路线，Gamboa et al.进一步系统化了rectangular RHS模型的C&CG、single/multi-cut及regularized算法。

`Xi=R_+^J`是无界polyhedron而不是该Corollary所称的bounded polytope，不能不加说明地直接引用(18b)。正支撑无界情形仍可通过一般Wasserstein对偶精确处理，但必须显式加入recession条件，并单独证明最坏期望有限、recourse可行以及分离问题可全局求解。当前论文推荐的`0<=d<=1.5*training-max`是bounded box，正好落在Corollary 5.4(ii)的polytope框架内；当前一阶段含二元承运人变量，因此完整外层是MILP而不是纯LP。

### 14.5 对当前采购模型的映射

一般决策`x`对应承运人组合`y`，不确定量`xi`对应lane需求`d`，业务损失`ell(x,xi)`对应二阶段运输、现货和MQC成本。当前需求足够大时的右尾斜率由无限spot费率决定，基本不随承运人组合变化；`h=min r`又保证低需求不会提高补救成本。因此在`R_+^J`无上界支撑下，最大持续斜率容易成为统一spot斜率，W1退化为weighted SAA加共同常数。`h=max r`允许较便宜lane出现决策相关低需求损失，但若统一spot斜率仍覆盖这些反向斜率，也可能继续退化；它只是取消退化保证，不是自动产生有效Wasserstein机制。bounded box切断无穷远方向后，有限upper/lower损失随承运人组合、容量和MQC变化，因此当前通用三端点oracle可以在`h=min r`和`h=max r`下得到真正决策相关的W1保护。

## 15. Mohajerin Esfahani--Kuhn (2018) RHS两阶段Wasserstein式(18b)逐步推导（2026-08-24）

### 15.1 原始最坏期望

论文先固定或省略一阶段决策，考虑RHS不确定的线性追索值函数

`ell(xi)=min_z{q^T z: Wz>=H xi+h}`。

经验中心为`P_hat_N=(1/N) sum_i delta_{xi_hat_i}`，W1球为`B_epsilon(P_hat_N)`。目标是

`sup_{Q in B_epsilon(P_hat_N)} E_Q[ell(xi)]`。

候选`Q`不要求离散；经验中心离散只是因为训练数据有限。下面的有限模型来自测度问题的强对偶和极值结构，而不是预先把`Q`限制为有限支持。

### 15.2 Wasserstein测度问题对偶化

W1运输预算的Lagrange乘子记为`lambda>=0`。Kantorovich强对偶给出

`sup_Q E_Q[ell(xi)] = inf_{lambda>=0}{lambda*epsilon+(1/N)sum_i phi_i(lambda)}`，

其中

`phi_i(lambda)=sup_{xi in Xi}{ell(xi)-lambda*||xi-xi_hat_i||}`。

因此无限维概率测度优化先被化为一个标量`lambda`和`N`个确定性内部最大化。`lambda`是单位概率质量移动距离的影子价格，`lambda*epsilon`是使用Wasserstein预算的对偶费用。

### 15.3 二阶段LP对偶化并枚举recourse对偶顶点

二阶段对偶可行域为

`Pi={theta>=0:W^T theta=q}`。

在相对完全补救、有限值和LP强对偶条件下，

`ell(xi)=max_{theta in Pi} theta^T(H xi+h)`。

Corollary 5.4(ii)进一步假设`Pi`非空且紧，顶点为`v_k,k=1,...,K`。线性目标必在顶点取得，因此

`ell(xi)=max_k v_k^T(H xi+h)=max_k{(H^T v_k)^T xi+v_k^T h}`。

定义`a_k=H^T v_k`和`b_k=v_k^T h`，二阶段值函数就成为有限个仿射片的最大值：

`ell(xi)=max_k{a_k^T xi+b_k}`。

代入第`i`个Wasserstein内部问题并交换有限max与sup：

`phi_i(lambda)=max_k{b_k+sup_{xi in Xi}[a_k^T xi-lambda||xi-xi_hat_i||]}`。

引入epigraph变量`s_i`后，只需对每个`i,k`保证

`b_k+sup_{xi in Xi}[a_k^T xi-lambda||xi-xi_hat_i||]<=s_i`。

### 15.4 多面体support与范数项的对偶化

令bounded polytope support为

`Xi={xi:Cxi<=d_bar}`，

其中用`d_bar`区分support RHS与当前论文的需求向量。对固定`i,k`，需要计算

`sup_{Cxi<=d_bar}{a_k^T xi-lambda||xi-xi_hat_i||}`。

利用对偶范数恒等式

`lambda||u||=sup_{||z||_*<=lambda} z^T u`，

可写成

`-lambda||xi-xi_hat_i||=inf_{||z||_*<=lambda}{-z^T(xi-xi_hat_i)}`。

在适用的凸性、闭性和强对偶条件下交换`sup_xi`与`inf_z`：

`inf_{||z||_*<=lambda}{z^T xi_hat_i+sup_{Cxi<=d_bar}(a_k-z)^T xi}`。

内部support function是一个LP，其对偶为

`sup_{Cxi<=d_bar}(a_k-z)^T xi=min_{gamma>=0}{d_bar^T gamma:C^T gamma=a_k-z}`。

消去`z=a_k-C^T gamma`，目标变成

`a_k^T xi_hat_i+gamma^T(d_bar-C xi_hat_i)`，

同时得到范数约束

`||C^T gamma-a_k||_*<=lambda`。

因此固定`i,k`的半无限约束精确等价于存在`gamma_ik>=0`使

`b_k+a_k^T xi_hat_i+gamma_ik^T(d_bar-C xi_hat_i)<=s_i`，

`||C^T gamma_ik-a_k||_*<=lambda`。

### 15.5 最终式(18b)

把`a_k=H^T v_k`和`b_k=v_k^T h`代回，得到论文Corollary 5.4(ii)的式(18b)：

`min lambda*epsilon+(1/N)sum_i s_i`

subject to, for all samples`i`and recourse-dual vertices`k`,

`v_k^T h+(H^T v_k)^T xi_hat_i+gamma_ik^T(d_bar-C xi_hat_i)<=s_i`，

`||C^T gamma_ik-H^T v_k||_*<=lambda`，

`gamma_ik>=0, lambda>=0`。

这一步已经把原始无限维“选择概率分布”问题精确化成有限凸规划。若ground norm为L1，则dual norm为Linf，第二条约束等价于逐分量`-lambda<=C^T gamma_ik-H^T v_k<=lambda`；若ground norm为Linf，则dual norm为L1，可加绝对值辅助变量线性化。因此论文Remark 5.5指出这两种距离下(18b)是LP。L2 ground norm对应L2 dual norm，形成SOCP约束。

### 15.6 box代入后的具体形式

对`Xi={xi:l<=xi<=u}`，取

`C=[I;-I]`，`d_bar=[u;-l]`，`gamma_ik=(gamma_ik^+,gamma_ik^-)>=0`。

于是

`C^T gamma_ik=gamma_ik^+-gamma_ik^-`，

`gamma_ik^T(d_bar-C xi_hat_i)=(gamma_ik^+)^T(u-xi_hat_i)+(gamma_ik^-)^T(xi_hat_i-l)`。

式(18b)变成

`b_k+a_k^T xi_hat_i+(gamma_ik^+)^T(u-xi_hat_i)+(gamma_ik^-)^T(xi_hat_i-l)<=s_i`，

`||gamma_ik^+-gamma_ik^--a_k||_*<=lambda`。

一维、L1 ground distance下，这与直接端点公式完全一致：

`sup_{l<=xi<=u}{a xi-lambda|xi-xi_hat|}`

`=max{a l-lambda(xi_hat-l), a xi_hat, a u-lambda(u-xi_hat)}`。

因此lower/nominal/upper三端点是box几何的直观表达，而`gamma^+,gamma^-`是同一support最大化的LP对偶表达；二者不是两个不同模型。

### 15.7 加回一阶段决策及当前TSPP

论文为简化符号省略了一阶段决策。若追索为

`Q(x,xi)=min_z{q^T z:Wz>=H(x)xi+h(x)}`，

则在(18b)中把`H,h`替换为`H(x),h(x)`。论文Remark 5.5指出，当`H(x),h(x)`线性依赖`x`且外层可行域凸时，得到有限凸规划；若`x`含二元变量，则得到相应的混合整数线性/锥模型。当前TSPP中需求加载矩阵`H`固定，承运人选择主要线性进入`h(y)`和对偶片截距，因此box+scaled-L1下的完整模型是MILP。

### 15.8 为什么完整(18b)可以改成exact Benders/row generation

式(18b)对所有`k=1,...,K`列约束，而recourse对偶多面体顶点数`K`可能指数级。完整枚举是exact full master，但计算上可能不可接受。可只保留`K' subset K`求restricted master，得到下界；固定当前一阶段决策和`lambda`后，对每个中心样本全局求解

`max_{theta in Pi, xi in Xi}{theta^T(Hxi+h(x))-lambda||xi-xi_hat_i||-s_i}`。

若最优值为正，得到违反的`theta^*,xi^*`，加入对应顶点块或线性cut；若所有样本的全局分离值均不为正，则当前解满足完整(18b)的全部隐式约束，master下界与完整可行上界在容差内一致，得到全局最优证书。这就是经典Benders/constraint generation按需生成对偶极点的逻辑。box+L1使当前分离可用有限界和endpoint binaries精确线性化为MILP；一般耦合polytope仍有同一理论框架，但分离不一定同样容易。

需要避免三种误述：第一，(18b)枚举的是recourse对偶顶点`v_k`，不是枚举Wasserstein球中的全部分布；第二，`gamma_ik`已经通过support-function对偶处理连续polytope，不要求枚举support中所有点；第三，2018论文给出完整有限重构并指出指数顶点瓶颈，但其Section 5.3没有把当前C&CG/single-cut/regularized算法逐项实现，后续动态分解属于对该完整模型的exact算法化。
