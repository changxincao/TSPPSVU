# Contextual Wasserstein TSPP：模型推导与求解方案

> **2026-09-29 更新提示：** 本文保留早期理论推导。当前代码的 CCG 流程、正权重样本 min/max 支撑、代表性最坏点与完整最坏概率分布之间的边界，以 `20260929_W1_CCG完整流程_最坏点诊断与最坏分布边界.md` 为准。本文中旧的支撑倍数或拟议实现不得覆盖该最新口径。

日期：2026-08-15  
状态：理论推导与实现设计；本轮不改现有模型、不运行数值实验。  
对应意见：R2-2、R3-1，并可用于回应 R4-M18、R4-M27。  
主要参考：Xia et al. (2023), *Transportation Research Part C*, 155, 104314。

## 1. 结论先行

当前 TSPP 可以建立真正“移动需求支持点”的 contextual 1-Wasserstein DRO。需求位于二阶段等式约束的 RHS、并限定为非负，不会使模型在理论上不可处理；现货变量保证给定任意允许需求时二阶段可行。

但是，Xia et al. (2023) 的紧凑 MILP **不能逐行照搬**：其随机损失关于不确定向量是单个仿射函数，而当前 TSPP 的二阶段最优值函数是多个仿射函数的上包络。正确做法是：

1. 保留当前 Nadaraya--Watson（NW）条件权重；
2. 在该加权经验分布周围建立 support-moving 1-Wasserstein 球；
3. 使用训练期定义的有界多面体需求支撑；
4. 对二阶段补救函数作 LP 对偶，将每个有效对偶片代入 Xia et al. 的 Wasserstein 对偶块；
5. 小规模用枚举作 exact gate，大规模采用 master--separation 动态生成 cuts。

所以，该扩展数学上可行，但属于一个新的求解模块，不是把现有 modified-chi-square DRO 的参数换成 Wasserstein 半径即可。

## 2. 当前二阶段模型

采用返修冻结口径：需求平衡为等式，且 \(h_i=\min_{j\in J_i} r_{ij}\)。给定承运人选择 \(y\) 和需求 \(d\in\mathbb R_+^J\)，二阶段问题为

\[
\begin{aligned}
Q(y,d)=\min_{x,v,u\ge 0}\quad
&\sum_i\sum_{j\in J_i}r_{ij}x_{ij}
 +\sum_j e_jv_j+\sum_i h_i u_i\\
\mathrm{s.t.}\quad
&\sum_{i:j\in J_i}x_{ij}+v_j=d_j, &&\forall j,\\
&\sum_{j\in J_i}x_{ij}+u_i\ge g_i y_i, &&\forall i,\\
&\sum_{j\in J_i}x_{ij}\le M_i y_i, &&\forall i,\\
&x_{ij}\le q_{ij}y_i, &&\forall i,\ j\in J_i .
\end{aligned}
\]

其中 \(v_j\) 是现货采购。对任意有限 \(d\ge0\)，取 \(x=0\)、\(v=d\)、\(u_i=g_i y_i\) 即可行，因此模型在需求支撑上具有相对完整补救。

一阶段可行域为

\[
\mathcal Y=\left\{y\in\{0,1\}^{I}:\alpha\le\sum_i y_i\le\beta\right\}.
\]

本模型与 src/Model/DROModel.java 中的需求等式、MQC、总容量和 lane 容量一致。

## 3. 二阶段对偶与分段线性结构

为需求等式引入自由变量 \(a_j\in\mathbb R\)；为 MQC 下界、总容量上界和 lane 容量上界分别引入 \(b_i,c_i,s_{ij}\ge0\)。二阶段对偶为

\[
\begin{aligned}
Q(y,d)=\max_{a,b,c,s}\quad
&\sum_j a_jd_j+\sum_i g_i y_i b_i-\sum_iM_i y_i c_i
-\sum_i\sum_{j\in J_i}q_{ij}y_i s_{ij}\\
\mathrm{s.t.}\quad
&a_j+b_i-c_i-s_{ij}\le r_{ij}, &&\forall i,\ j\in J_i,\\
&a_j\le e_j, &&\forall j,\\
&b_i\le h_i, &&\forall i,\\
&a_j\in\mathbb R,\quad b_i,c_i,s_{ij}\ge0 .
\end{aligned}
\]

\(a_j\) 必须是自由变量，因为需求约束是等式；不能仅凭需求非负就把其对偶域写成非负。在一般 MQC 参数下，增加需求可能同时减少短缺罚金，故其符号需要由最优对偶决定。对当前 \(h_i=\min_{j\in J_i}r_{ij}\) 口径，运输增量不低于可节省的单位 MQC 罚金，值函数预计保持单调非减，但这属于模型结构产生的性质，不能替代等式约束对应的自由对偶域。项目的 RCSAADecompositionSupport 已按 enforceDemandEquality 实现这一口径；旧 DROBenders 仍把它限制为非负，不能直接复用。

令对偶可行域产生的有效仿射片为 \(k\in\mathcal K\)，定义

\[
\ell_k(y,d)=a_k^\top d+c_k(y),
\]

\[
c_k(y)=\sum_i y_i\left(g_i b_i^k-M_i c_i^k-\sum_{j\in J_i}q_{ij}s_{ij}^k\right).
\]

则

\[
Q(y,d)=\max_{k\in\mathcal K}\{a_k^\top d+c_k(y)\}.
\]

这正是与 Xia et al. 连接的地方：Xia et al. 只有一个随机仿射损失；本问题有一组由二阶段对偶产生的仿射片。

现有分解代码还使用了 \(x_{ij}\le q_{ij}\) 加 \(\sum_jx_{ij}\le M_i y_i\) 的对偶写法，因此 \(-q_{ij}s_{ij}\) 位于常数项。对二元 \(y\)，它与上面的 \(x_{ij}\le q_{ij}y_i\) 在原问题最优值上等价；实现时必须固定一种写法，保证 primal、dual 和 cut 完全一致。

## 4. Contextual Wasserstein 歧义集

### 4.1 条件经验中心

对当前 context \(\theta\)，保留 NW 权重：

\[
\widehat{\mathbb P}_{\theta}
=\sum_{s=1}^{S}\pi_s(\theta)\delta_{d^s},
\qquad \pi_s(\theta)\ge0,\quad \sum_s\pi_s(\theta)=1.
\]

Xia et al. 使用等权经验分布。若本研究也改为等权，会同时改变“是否使用 context”和“歧义集形状”，不能公平识别 Wasserstein 的作用。因此建议只替换歧义集，保留当前 NW 权重。

### 4.2 lane 尺度化距离

Olist 各 lane 尺度差异很大，建议采用训练期标准化的 \(L_1\) ground metric：

\[
\|G(d-d')\|_1
=\sum_j\frac{|d_j-d'_j|}{\tau_j},
\qquad G=\operatorname{diag}(1/\tau_j).
\]

\(\tau_j\) 必须仅由训练窗计算，并为近零尺度设固定下限。Xia et al. 同样使用 1-Wasserstein 与 \(L_1\) ground norm，其对偶范数为 \(L_\infty\)。

### 4.3 非负有界多面体支撑

推荐

\[
\Xi=\{d\in\mathbb R^J:Ad\le b\}\subseteq\mathbb R_+^J,
\]

并保证全部训练需求 \(d^s\in\Xi\)。首个版本可取

\[
\Xi_{\mathrm{box}}=\{d:0\le d_j\le\bar d_j,\ \forall j\}.
\]

更复杂版本可加入总需求或 lane-group budget，类似 Xia et al. 的 bounded polyhedral support；但不能把其车站/时段预算直接移植到 lane 问题。所有上界、分组和膨胀系数只能由训练数据或 DGP 已知支撑确定。

一般两阶段模型在无界非负锥上未必退化，但当前冻结模型具有更强的特殊结构：\(h_i\le r_{ij}\)、合同容量有限且现货 \(v_j\) 可无限补充。第 5.2 节证明，在该结构下若只使用无上界支撑 \(\mathbb R_+^J\)，1-Wasserstein 最坏期望恰好等于 NW-CSAA 期望加上与 \(y\) 无关的常数。因此它虽然可紧凑求解，却不会改变承运人选择。为了得到真正有区分度的 support-moving 基准，必须采用训练期有界支撑。

### 4.4 歧义集和外层模型

\[
\mathcal P_\varepsilon(\theta)=
\left\{\mathbb P:
\operatorname{supp}(\mathbb P)\subseteq\Xi,\ 
W_{1,G}(\mathbb P,\widehat{\mathbb P}_\theta)\le\varepsilon
\right\}.
\]

\[
\min_{y\in\mathcal Y}
\sup_{\mathbb P\in\mathcal P_\varepsilon(\theta)}
\mathbb E_{\mathbb P}[Q(y,d)].
\]

\(\varepsilon\) 是 Wasserstein 半径，应与现有 RCSAA/modified-chi-square 的 \(\lambda\) 分开命名和训练内选择。

## 5. Kantorovich 对偶和 Xia 型约束块

固定 \(y\) 后，1-Wasserstein 对偶给出

\[
\sup_{\mathbb P\in\mathcal P_\varepsilon(\theta)}
\mathbb E_{\mathbb P}[Q(y,d)]
=\min_{\eta\ge0}
\left\{\varepsilon\eta+\sum_s\pi_s(\theta)t_s:
t_s\ge\sup_{d\in\Xi}
\big[Q(y,d)-\eta\|G(d-d^s)\|_1\big]\right\}.
\]

代入 \(Q(y,d)=\max_k\ell_k(y,d)\)，对所有 \(s,k\) 需要

\[
t_s\ge
\sup_{d\in\Xi}
\left[a_k^\top d+c_k(y)-\eta\|G(d-d^s)\|_1\right].
\]

对 \(\Xi=\{d:Ad\le b\}\) 作 LP 对偶，引入 \(\mu_{sk}\ge0\)，得到

\[
\begin{aligned}
t_s&\ge c_k(y)+a_k^\top d^s+(b-Ad^s)^\top\mu_{sk},
&&\forall s,k,\\
\left\|G^{-T}(A^\top\mu_{sk}-a_k)\right\|_\infty
&\le\eta,
&&\forall s,k,\\
\mu_{sk}&\ge0.
\end{aligned}
\]

因此，在全部有效仿射片给定时，模型为

\[
\begin{aligned}
\min_{y,\eta,t,\mu}\quad
&\varepsilon\eta+\sum_s\pi_s(\theta)t_s\\
\mathrm{s.t.}\quad
&y\in\mathcal Y,\quad\eta\ge0,\\
&t_s\ge c_k(y)+a_k^\top d^s+(b-Ad^s)^\top\mu_{sk},
&&\forall s,k,\\
&\left\|G^{-T}(A^\top\mu_{sk}-a_k)\right\|_\infty\le\eta,
&&\forall s,k,\\
&\mu_{sk}\ge0.
\end{aligned}
\]

由于 ground norm 为 \(L_1\)，范数约束可线性化为

\[
-\eta\le
\left[G^{-T}(A^\top\mu_{sk}-a_k)\right]_j
\le\eta,\qquad\forall j,s,k.
\]

所以有效仿射片集合已知时，这是 MILP，不需要二阶锥。

### 5.1 box 支撑的显式形式

对 \(0\le d\le\bar d\)，引入 \(\mu_{sk}^+,\mu_{sk}^-\ge0\)：

\[
\begin{aligned}
t_s\ge{}&c_k(y)+a_k^\top d^s
+(\bar d-d^s)^\top\mu_{sk}^+
+(d^s)^\top\mu_{sk}^-,\\
&\left\|G^{-T}(\mu_{sk}^+-\mu_{sk}^--a_k)\right\|_\infty\le\eta.
\end{aligned}
\]

该版本最适合首个实现：符号清楚，容易用离散化小例子核验，也最接近 Xia et al. 的 bounded-support 推导。

### 5.2 当前模型满足的关键有界性条件

令

\[
\Delta_{ij}=(e_j+h_i-r_{ij})_+,\qquad
\Delta_i=\max_{j\in J_i}\Delta_{ij}.
\]

当前 \(h_i=\min_{j\in J_i}r_{ij}\)，因此 \(h_i\le r_{ij}\)。对第 3 节任意可行对偶解：

1. 若 \(a_j<0\)，把它提高到 0 后，约束仍满足，因为
   \(b_i-c_i-s_{ij}\le b_i\le h_i\le r_{ij}\)；同时 \(d_j\ge0\)，目标不会下降。因此存在最优解满足 \(0\le a_j\le e_j\)。
2. 因为 \(a_j+b_i-r_{ij}\le\Delta_{ij}\)，超过 \(\Delta_i\) 的 \(c_i\) 和超过 \(\Delta_{ij}\) 的 \(s_{ij}\) 都可以向下截断而不破坏可行性，并且不会降低最大化目标。

所以只需考虑紧多面体

\[
\begin{aligned}
\Pi^B=\{(a,b,c,s):\;&a_j+b_i-c_i-s_{ij}\le r_{ij},\\
&0\le a_j\le e_j,\quad 0\le b_i\le h_i,\\
&0\le c_i\le\Delta_i,\quad
0\le s_{ij}\le\Delta_{ij}\}.
\end{aligned}
\]

这一步非常重要：Saif--Delage 的精确 CCG 要求二阶段对偶的相关区域有界；当前模型不是直接照抄该条件，而是由 \(h_i\le r_{ij}\) 和现货上界 \(a_j\le e_j\) 推出了显式有效界。

### 5.3 无上界非负支撑会退化

对尺度化距离

\[
\|G(d-d')\|_1=\sum_j |d_j-d'_j|/\tau_j
\]

定义

\[
L=\max_j \tau_j e_j.
\]

因为 \(h_i\le r_{ij}\)，增加需求不会降低最优补救成本；任意需求增量又总能用现货满足，故

\[
|Q(y,d')-Q(y,d)|
\le \sum_j e_j|d'_j-d_j|
\le L\|G(d'-d)\|_1.
\]

合同容量有限，所以沿任意 lane \(j\) 将需求增至足够大以后，新增需求只能按现货费率 \(e_j\) 满足；上述 Lipschitz 常数的上界可被渐近达到。因此当
\(\Xi=\mathbb R_+^J\) 时，

\[
\sup_{\mathbb P:W_{1,G}(\mathbb P,\widehat{\mathbb P}_\theta)\le\varepsilon}
\mathbb E_{\mathbb P}[Q(y,d)]
=\sum_s\pi_s(\theta)Q(y,d^s)+\varepsilon L.
\]

\(\varepsilon L\) 与 \(y\) 无关，故其最优承运人决策与 NW-CSAA 完全相同。这是当前模型的结构性结论，不应推广为所有非负锥两阶段模型的一般定理。

## 6. 为什么不能直接复制 Xia et al.

Xia et al. 的不确定损失为单个仿射函数

\[
f(\xi)=\pi^\top\xi.
\]

所以每个经验样本只需一组支撑集对偶变量和一个范数约束。本问题是

\[
Q(y,d)=\max_{k\in\mathcal K}\{a_k^\top d+c_k(y)\}.
\]

每个可能成为最优的二阶段对偶片都需要一个 Xia 型约束块。若只取一个 \(a_k\)，会漏掉其他补救基并低估最坏期望成本。因此，Xia et al. 提供的是 Wasserstein 外层模板；Hanasusanto--Kuhn 和 Byeon--Fang--Kim 的两阶段 Wasserstein 结果才是求解层面的直接理论支撑。

## 7. 不枚举承运人组合的精确求解

~~~mermaid
flowchart LR
    A["训练需求与当前 context"] --> B["NW 加权经验分布"]
    B --> C["有界 support-moving W1 球"]
    C --> D["Master: y, eta, t 与已有仿射片"]
    D --> E["Wasserstein separation"]
    E --> F["二阶段 LP 对偶得到新片"]
    F --> G{"最大违反量 <= tolerance?"}
    G -- "否" --> H["加入 Xia 型 Wasserstein cut"]
    H --> D
    G -- "是" --> I["认证解、bound、gap 与 OOS 评价"]
~~~

不需要枚举一阶段 \(y\)。精确算法直接在 master 中保留二元变量 \(y\)，只动态生成二阶段对偶片。

### 7.1 有界 box 下的精确 master

令已经生成的对偶片集合为 \(\mathcal K'\)。对每个 \(s,k,j\) 引入 \(p_{skj}\ge0\)。由于第 5.2 节已经证明 \(a_j^k\ge0\)，有

\[
\sup_{0\le d_j\le\bar d_j}
\left\{a_j^kd_j-\eta |d_j-d_j^s|/\tau_j\right\}
=a_j^kd_j^s+(\bar d_j-d_j^s)
\left[a_j^k-\eta/\tau_j\right]_+.
\]

因此 restricted master 是 MILP：

\[
\begin{aligned}
\min\quad &\varepsilon\eta+\sum_s\pi_s(\theta)t_s\\
\mathrm{s.t.}\quad
&y\in\mathcal Y,\quad \eta\ge0,\\
&p_{skj}\ge a_j^k-\eta/\tau_j,\quad p_{skj}\ge0,
&&\forall s,k\in\mathcal K',j,\\
&t_s\ge c_k(y)+(a_k)^\top d^s
+\sum_j(\bar d_j-d_j^s)p_{skj},
&&\forall s,k\in\mathcal K'.
\end{aligned}
\]

这里 \(a_k,b_k,c_k,s_k\) 是已经生成的对偶点常数，所以
\(c_k(y)\) 对二元 \(y\) 是线性的。CPLEX/MOSEK 可直接求解该 master。

### 7.2 精确 MILP separation

给定 master 解 \((\bar y,\bar\eta,\bar t)\)，对每个训练样本 \(s\) 寻找最违反的对偶片。对每个 lane 引入 \(z_j\in\{0,1\}\)：\(z_j=0\) 表示最坏需求保持在 \(d_j^s\)，\(z_j=1\) 表示移动到 \(\bar d_j\)。再令 \(w_j=z_ja_j\)，利用 \(0\le a_j\le e_j\) 作精确线性化：

\[
\begin{aligned}
&0\le w_j\le e_jz_j,\\
&w_j\le a_j,\\
&w_j\ge a_j-e_j(1-z_j).
\end{aligned}
\]

分离问题为

\[
\begin{aligned}
\max_{(a,b,c,s)\in\Pi^B,z,w}\quad
&c(\bar y;a,b,c,s)+\sum_j a_jd_j^s\\
&+\sum_j(\bar d_j-d_j^s)
\left(w_j-\frac{\bar\eta}{\tau_j}z_j\right).
\end{aligned}
\]

这是一个普通 MILP。若其最优值大于 \(\bar t_s\) 加容差，就把所得对偶点作为新片加入 master；否则样本 \(s\) 已通过验证。

### 7.3 精确性和终止

- \(\Pi^B\) 是有界多面体，只有有限个极点；
- box+\(L_1\) 下每个坐标的最坏需求只可能是 \(d_j^s\) 或 \(\bar d_j\)；
- 每次违反都会加入一个新的有效对偶片；
- 所以该 CCG 在有限步内终止，并给出全局最优解（允许数值求解容差）。

这与 Saif--Delage 的 dual-vertex CCG 和 Byeon--Fang--Kim 的 scenario/cutting-plane 框架一致。它没有枚举承运人组合，也不需要预先列出全部对偶极点；代价是求解一系列 master MILP 和 sample-wise separation MILP，而不是一个小型的一次性 MILP。

需要明确：一般两阶段 RHS-uncertainty Wasserstein 模型本身是 NP-hard 的，因此不存在一个对所有实例都自动变成多项式规模LP的结论。这里的“可求解”是指存在精确、有限收敛、每一步均由商用求解器处理的 MILP-CCG；不是声称整个问题变成了容易的一次性LP。

### 7.4 generic polyhedral support

若采用 Xia 式总量/分组 budget，而不是 box，仍可使用第 5 节的 \(\mu_{sk}\) 对偶块和 Saif--Delage 的 KKT-MILP separation。算法仍可精确有限收敛，但实现明显比 box 版本复杂。

### 不应采用的简化

1. 只允许概率在现有 \(d^s\) 间转移：容易实现，但不产生新需求点，不能回答 R3-1；
2. 直接复制 Xia 的单仿射紧凑式：漏掉补救值函数的其他仿射片；
3. 用测试集确定支撑或半径：产生信息泄漏；
4. 用等权 Wasserstein 与 NW-chi-square 直接比较：同时改变 context 和歧义集，机制不可识别。

## 8. 半径选择和公平实验

\(\varepsilon\) 应采用训练内 nested rolling validation：

1. 外层 OOS 周完全隔离；
2. 内层按时间顺序选 \(\varepsilon\)，不能随机打乱周序列；
3. 所有候选使用同一训练窗、NW 权重、支撑和 ground metric；
4. \(\varepsilon=0\) 必须退化为对应 NW-CSAA；
5. 半径增大时训练最坏目标应非递减；
6. 与 chi-square DRO 使用相同 OOS 周/queries/draws，报告 Mean、SD、Q95、CVaR95、运输、现货、MQC 缺口/罚金、承运人数、状态、gap 和时间。

公平比较固定需求等式、\(h_i=\min r_{ij}\)、采购市场、训练/OOS 数据、NW 带宽和标准化，只改变歧义集及其训练内选择半径。

## 9. 实现前的正确性 gate

### 数学 gate

- 固定 \(y,d\) 时，二阶段 primal 与第 3 节 dual 误差不超过 \(10^{-7}\)；
- 等式口径下 \(a_j\) 必须允许取负；
- \(x\le qy\) 与 \(x\le q\) 加 total-cap 两套等价值写法不能混搭 cut；
- 全部训练样本必须位于 \(\Xi\)；
- \(G\) 与 \(\Xi\) 只能由训练数据计算。

### Wasserstein gate

- \(\varepsilon=0\) 的目标与决策和 NW-CSAA 一致；
- 单 lane、单样本例子用需求区间离散化，与对偶逐点核验；
- 增大 \(\varepsilon\) 后最坏训练目标不下降；
- separation 返回需求满足 \(Ad\le b\)；
- 终止时最大 cut violation 小于容差；
- \(I=10\) 枚举与动态 master 得到相同最优目标。

### 代码隔离

旧 DROBenders.java 的需求对偶变量下界为 0，适用于旧 \(\ge\) 口径，不可直接用于等式 Wasserstein。新实现应复用或抽取 RCSAADecompositionSupport 的自由变量逻辑并新增独立类，不能静默改变现有 chi-square 实验。

## 10. 可用于回复审稿人的文字

> Xia et al. (2023) consider a standard 1-Wasserstein ball around an equally weighted empirical distribution with bounded polyhedral support. Their loss is affine in the uncertain vector, which yields a compact MILP reformulation. In our contextual two-stage procurement problem, the demand-dependent recourse value is instead a convex piecewise-linear function represented by the recourse dual. Thus, a support-moving contextual Wasserstein extension is mathematically viable by combining the Kantorovich dual with recourse-dual cut generation, but it cannot be obtained by directly copying the single-affine reformulation in Xia et al. The current modified-chi-square set protects conditional probability misspecification on the observed support, whereas Wasserstein ambiguity can additionally perturb demand locations. A full Wasserstein benchmark therefore requires a separately calibrated support, ground metric, radius, and separation algorithm.

若不做数值实验，这段可用于“理论比较＋承认固定支持局限＋未来扩展”。若增加 Wasserstein baseline，则必须按第 8--9 节执行，不能用有限经验支持版本冒充 support-moving Wasserstein。

## 11. 最终判断

1. **理论可行性：确认。** 当前 RHS 非负需求与等式补救结构可以建立 Wasserstein DRO。
2. **推荐模型：** NW-contextual、scaled-\(L_1\)、training-only bounded box、support-moving 1-Wasserstein。
3. **推荐求解：** 不枚举 \(y\)；使用第 7 节的 exact MILP-master + MILP-separation CCG。
4. **不能照搬 Xia：** Xia 的单仿射损失只是本问题每个 recourse-dual 片的模板。
5. **实施工作量：** 中等偏高，需要独立 separation、cuts、半径 nested validation 和 primal--dual/小例子核验。
6. **无界支撑警告：** 只设 \(d\ge0\) 时，当前模型精确退化为 NW-CSAA 加决策无关常数，不能作为有区分度的 Wasserstein baseline。
7. **返修策略：** 若编辑没有明确要求数值 Wasserstein，对比讨论已经能回答 R2-2/R3-1；若补实验，必须采用有界 support-moving 版本。

## 12. 主要参考文献

- Xia, W., Liu, S., Ma, J., Liu, M., & Ge, Y.-E. (2023). *Distributionally robust optimization with 1-Wasserstein distance for coordinated train timetabling and limited-stop strategy on an urban rail transit line*. Transportation Research Part C, 155, 104314. https://doi.org/10.1016/j.trc.2023.104314
- Mohajerin Esfahani, P., & Kuhn, D. (2018). *Data-driven distributionally robust optimization using the Wasserstein metric: Performance guarantees and tractable reformulations*. Mathematical Programming, 171, 115--166. https://doi.org/10.1007/s10107-017-1172-1
- Hanasusanto, G. A., & Kuhn, D. (2018). *Conic programming reformulations of two-stage distributionally robust linear programs over Wasserstein balls*. Operations Research, 66(3), 849--869. https://doi.org/10.1287/opre.2017.1698
- Byeon, G., Fang, K., & Kim, K. (2025). *Two-stage distributionally robust conic linear programming over 1-Wasserstein balls*. SIAM Journal on Optimization, 35(1). https://doi.org/10.1137/23M1626839
- Saif, A., & Delage, E. (2021). *Data-driven distributionally robust capacitated facility location problem*. European Journal of Operational Research, 291(3), 995--1007. https://doi.org/10.1016/j.ejor.2020.09.026
- Wang, Z., You, K., Song, S., & Zhang, Y. (2020). *Second-order conic programming approach for Wasserstein distributionally robust two-stage linear programs*. https://arxiv.org/abs/2002.06751
- Wang, T., Chen, N., & Wang, C. (2021). *Distributionally Robust Prescriptive Analytics with Wasserstein Distance*. arXiv:2106.05724. https://arxiv.org/abs/2106.05724

检索日期：2026-08-15。最后一篇是 contextual NW-centered Wasserstein 的直接参考；正式引用前应再核对其最新发表状态。两阶段求解推导主要依赖前四篇。
