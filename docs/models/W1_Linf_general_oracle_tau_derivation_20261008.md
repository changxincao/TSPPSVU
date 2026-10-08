# W1 + L∞ ground distance：一般 oracle、τ 的有限化与 CCG 完整推导

日期：2026-10-08。

本文整理本次讨论的数学推导，重点是：从“给定 τ 后最坏需求可以取在局部 box 的顶点”，推导到“连续 τ 只需检查有限个支撑截断时刻”。全文采用符号推导，不使用数值例子。

**范围说明：**一般推导不要求成本单调性，也不要求 \(h_i\le r_{ij}\)。当前仓库的 `WassersteinInfinityMonotoneOracle.java` 则采用满足该条件时的简化算法。本文说明两者的关系，不表示一般 MILP oracle 已实现或已完成数值验证。本次仅新增文档，不修改求解器、实验数据、参数或运行队列。

## 1. 模型、符号与必要假设

固定一阶段承运人选择 \(y\)。令 \(Q(y,d)\) 为需求 \(d\) 下的最优二阶段合同运输、现货运输和 MQC 罚金之和。

本推导要求：

- 二阶段为连续线性规划；需求只进入约束右端项，对偶可行域不随需求改变。
- 支撑为紧的 box：\(\Xi=\prod_{j=1}^J[\underline d_j,\overline d_j]\)。
- 二阶段在整个支撑上可行且最优值有限。当前模型中，无限供给的现货及 MQC 缺口变量提供完全补救；运输成本、现货成本和罚金非负，排除无界负成本。
- 各历史中心 \(d^s\in\Xi\)，距离尺度 \(s_j>0\)。

本文用 \(p_s\) 表示经验概率，用 \(g_i\) 表示 MQC 数量，以免混淆。代码 `params.p[i]` 是 MQC 数量，对应本文的 \(g_i\)，不是经验概率。

经验分布为：

\[
\widehat{\mathbb P}=\sum_s p_s\delta_{d^s},\qquad p_s>0,\quad\sum_s p_s=1.
\]

这里只列出正权重原子。零权重原子不贡献期望，不需要对应的 oracle；支撑如何由训练数据估计是另一个独立的输入约定。

ground distance 定义为：

\[
c_\infty(d,d^s)=\max_j\frac{|d_j-d_j^s|}{s_j}.
\]

Wasserstein 阶数仍然是 1：

\[
W_{1,c_\infty}(\mathbb P,\widehat{\mathbb P})
=\inf_{\Pi\in\Gamma(\mathbb P,\widehat{\mathbb P})}
\mathbb E_\Pi[c_\infty(D,D')].
\]

这是“一阶 Wasserstein + L∞ ground distance”，不是 Wasserstein-∞。

## 2. η 从哪里来，和 ε、τ 有什么区别

原问题为：

\[
\min_{y\in\mathcal Y}\;
\sup_{\mathbb P:\,\operatorname{supp}(\mathbb P)\subseteq\Xi,
\ W_{1,c_\infty}(\mathbb P,\widehat{\mathbb P})\le\varepsilon}
\mathbb E_\mathbb P[Q(y,D)].
\]

在上述有限、连续、紧支撑条件下，Wasserstein 对偶表达式为：

\[
\min_{y\in\mathcal Y,\eta\ge0}
\left\{\eta\varepsilon+\sum_s p_s\phi_s(y,\eta)\right\},
\]

\[
\phi_s(y,\eta)=\max_{d\in\Xi}
\{Q(y,d)-\eta c_\infty(d,d^s)\}.
\]

若有额外确定性一阶段费用，可在外层另加；不影响以下推导。

三个量各自承担不同角色：

| 符号 | 含义 | 如何得到 |
| --- | --- | --- |
| \(\varepsilon\) | Wasserstein 平均搬运预算/半径 | 实验中通过训练内验证选择 |
| \(\eta\ge0\) | 搬运预算的对偶价格 | 与 \(y\) 一起由主问题优化 |
| \(\tau\ge0\) | 某个 sample-wise oracle 内的允许最大标准化移动幅度 | 由 oracle 优化 |

主问题权衡 \(\eta\varepsilon\) 与 \(\sum_s p_s\phi_s(y,\eta)\)：增大 η 会抬高第一项，却不会抬高第二项。η 不需要单独 CV，也不是半径 ε。

**没有 \(\tau\le\varepsilon\) 这一限制。**ε 限制分布层面的平均搬运成本；τ 是单个原子的候选移动幅度，两者不是同一个量。

## 3. 引入 τ：把距离罚项变成局部 box

固定当前 \(y,\eta\)，对一个历史原子 \(d^s\)：

\[
\phi_s(y,\eta)
=\max_{\tau\ge0,\ d\in\Xi}
\{Q(y,d)-\eta\tau:\ |d_j-d_j^s|\le s_j\tau,\ \forall j\}.
\]

等价性的理由有两个方向：任何可行 \((d,\tau)\) 都满足 \(c_\infty(d,d^s)\le\tau\)，因而其目标不超过原 oracle 在同一 d 的目标；反过来，对任何 d，取 \(\tau=c_\infty(d,d^s)\) 就能达到原目标。η=0 时同样成立，但 τ 不一定唯一。

给定 τ 后，允许需求构成局部 box：

\[
\ell_{sj}(\tau)=\max\{\underline d_j,d_j^s-s_j\tau\},\qquad
u_{sj}(\tau)=\min\{\overline d_j,d_j^s+s_j\tau\}.
\]

记：

\[
H_s(\tau)=\max_{\ell_s(\tau)\le d\le u_s(\tau)}Q(y,d),\qquad
F_s(\tau)=H_s(\tau)-\eta\tau.
\]

因此 \(\phi_s(y,\eta)=\max_{\tau\ge0}F_s(\tau)\)。以下固定 y、η 和原子 s，省略 s 下标。

## 4. 为什么给定 τ，可以选需求顶点

固定 y，由二阶段 LP 强对偶，可以写成：

\[
Q(y,d)=\max_{v\in\mathcal V_y}
\{a(v)^\top d+c_y(v)\},
\]

其中 \(\mathcal V_y\) 不随 d 改变。固定一个对偶可行解 v 时，括号内是 d 的仿射函数。因此 Q 是 d 的凸函数。

任意局部 box 内的需求都可以表示成其顶点的凸组合。由凸性，该需求的 Q 值不超过顶点 Q 值的最大值。因此至少存在一个顶点最优解。

这不是说所有最坏需求都必须是顶点；平坦区域可能存在内部最优解，但选择一个顶点不损失最优值。

以 \(\delta_j\in\{0,1\}\) 表示取端方式：

\[
d_j^\delta(\tau)=(1-\delta_j)\ell_j(\tau)+\delta_j u_j(\tau).
\]

于是：

\[
H(\tau)=\max_{\delta\in\{0,1\}^J}Q(y,d^\delta(\tau)),
\]

\[
F(\tau)=\max_\delta\{Q(y,d^\delta(\tau))-\eta\tau\}.
\]

固定 δ 只是固定每条 lane 取上端或下端的规则，不是固定需求数值。lane 间仍通过运输容量等约束耦合，不能逐 lane 贪心挑选最坏端点。

## 5. 哪些 τ 是支撑截断时刻

每条 lane 有两个时刻：

\[
t_j^-=\frac{d_j^s-\underline d_j}{s_j},\qquad
t_j^+=\frac{\overline d_j-d_j^s}{s_j}.
\]

在 \(t_j^-\) 之前，下端随 τ 线性下降，之后固定在支撑下界；在 \(t_j^+\) 之前，上端随 τ 线性上升，之后固定在支撑上界。

将下面的集合排序、去重：

\[
\mathcal T_s=\{0\}\cup\{t_j^-,t_j^+:j=1,\ldots,J\}.
\]

取两个相邻时刻 \(\tau_L,\tau_U\)。这一段内部没有新的支撑截断，所以：

\[
\ell_j(\tau)=L_{j0}+L_{j1}\tau,\qquad
u_j(\tau)=U_{j0}+U_{j1}\tau,
\quad \tau\in[\tau_L,\tau_U].
\]

常数函数也包括在内，其斜率为 0。将其代入固定的取端规则：

\[
d_j^\delta(\tau)
=(1-\delta_j)(L_{j0}+L_{j1}\tau)
+\delta_j(U_{j0}+U_{j1}\tau)
=A_j^\delta+B_j^\delta\tau.
\]

因此在这一段内，\(d^\delta(\tau)=A^\delta+B^\delta\tau\)。跨过截断时刻后，A、B 可能改变，不能把同一表达式直接延伸到整个 τ 范围。

## 6. 从固定 τ 的顶点结果，逐步推导 τ 只需查区间两端

### 6.1 固定取端规则，再展开二阶段对偶

利用第 4 节的对偶表达式：

\[
\begin{aligned}
Q(y,d^\delta(\tau))-\eta\tau
&=\max_{v\in\mathcal V_y}
\{a(v)^\top(A^\delta+B^\delta\tau)+c_y(v)-\eta\tau\}\\
&=\max_{v\in\mathcal V_y}
\{[a(v)^\top B^\delta-\eta]\tau+a(v)^\top A^\delta+c_y(v)\}.
\end{aligned}
\]

固定 δ 和 v 后，关于 τ 的表达式是一条直线。记为：

\[
f_{\delta,v}(\tau)=m_{\delta,v}\tau+b_{\delta,v},
\]

\[
m_{\delta,v}=a(v)^\top B^\delta-\eta,\qquad
b_{\delta,v}=a(v)^\top A^\delta+c_y(v).
\]

这里 m、b 仅是直线的斜率、截距，不是新增的模型参数。于是：

\[
\boxed{F(\tau)=\max_{\delta,v}f_{\delta,v}(\tau),
\quad\tau\in[\tau_L,\tau_U].}
\]

### 6.2 先对任意一条直线比较内部与两端

任意内部位置可以写成：

\[
\tau_\theta=(1-\theta)\tau_L+\theta\tau_U,\qquad0\le\theta\le1.
\]

由于 \(f_{\delta,v}\) 是仿射函数，对任意固定 δ、v：

\[
f_{\delta,v}(\tau_\theta)
=(1-\theta)f_{\delta,v}(\tau_L)+\theta f_{\delta,v}(\tau_U).
\]

根据 F 的最大值定义：

\[
f_{\delta,v}(\tau_L)\le F(\tau_L),\qquad
f_{\delta,v}(\tau_U)\le F(\tau_U).
\]

所以每条直线都满足：

\[
f_{\delta,v}(\tau_\theta)
\le(1-\theta)F(\tau_L)+\theta F(\tau_U).
\]

### 6.3 再从所有直线中选择最大的

上式右边不依赖 δ、v，而左边的所有选择都满足该上界。因此取最大值以后仍然有：

\[
\begin{aligned}
F(\tau_\theta)
&=\max_{\delta,v}f_{\delta,v}(\tau_\theta)\\
&\le(1-\theta)F(\tau_L)+\theta F(\tau_U)\\
&\le\max\{F(\tau_L),F(\tau_U)\}.
\end{aligned}
\]

由此得到：

\[
\boxed{\max_{\tau\in[\tau_L,\tau_U]}F(\tau)
=\max\{F(\tau_L),F(\tau_U)\}.}
\]

这也证明了 F 在该段内凸。关键不是三个位置使用相同的最优 δ、v，而是每个固定选择都满足相同上界，最后再取最大值。最优运输方案、最坏顶点和对偶解可以在段内切换。

### 6.4 用 Q 的凸性写成另一条等价证明

在内部位置选一个最坏顶点，其取端方式记为 \(\delta^\star\)。在两端沿用同一种取端方式，得到 \(d^L,d^U\)。仿射关系给出：

\[
d^{\delta^\star}(\tau_\theta)=(1-\theta)d^L+\theta d^U.
\]

两端的需求不一定分别最坏，但分别可行。因此：

\[
\begin{aligned}
H(\tau_\theta)
&=Q(y,(1-\theta)d^L+\theta d^U)\\
&\le(1-\theta)Q(y,d^L)+\theta Q(y,d^U)\\
&\le(1-\theta)H(\tau_L)+\theta H(\tau_U).
\end{aligned}
\]

再减去 \(\eta\tau_\theta=(1-\theta)\eta\tau_L+\theta\eta\tau_U\)，就得到与第 6.3 节相同的 F 不等式。这是同一个结论的第二种证明，不是额外假设。

## 7. 合并各段，得到精确的有限 τ 集合

令：

\[
\tau_{\max}=\max_j\{t_j^-,t_j^+\}.
\]

当 \(\tau\ge\tau_{\max}\)，允许范围已经是完整支撑 box，H 不再改变；由于 η 非负，F 不再增加。η=0 时可能保持不变，但仍可在 \(\tau_{\max}\) 取得相同最优值。

结合每段只需检查两端的结论：

\[
\boxed{
\phi_s(y,\eta)
=\max_{\tau\in\mathcal T_s}
\left\{\max_{\delta\in\{0,1\}^J}Q(y,d^\delta(\tau))-\eta\tau\right\}.
}
\]

\(\mathcal T_s\) 最多含 \(2J+1\) 个不同值。它由输入确定，不依赖当前 y、η，可预先计算。

需特别区分：

- 不是说 F 整体凸，而是相邻支撑截断时刻之间凸。
- 不是说 F 只有这些折点。段内还可能因最优顶点或运输方案切换出现其他折点，但无需检查它们。
- 不是只检查 0 和最大 τ，而是检查所有支撑截断时刻。
- 不是近似离散网格，而是数学上精确的有限候选集合。
- 最多 \(2J+1\) 是固定 τ 子问题的数量，不是候选需求点总数。每个固定 τ 仍有联合端点选择问题。

## 8. 一般情况下，给定 τ 的子问题如何成为 MILP

固定 τ 后，\(\ell_j,u_j\) 都是常数。二阶段对偶中 \(a_jd_j\) 变为：

\[
a_jd_j=\ell_ja_j+(u_j-\ell_j)a_j\delta_j.
\]

只有连续对偶变量与二元取端变量的乘积需要处理。若有不损失最优值的有限界 \(L_j\le a_j\le e_j\)，令 \(\omega_j=a_j\delta_j\)，用：

\[
\omega_j\ge L_j\delta_j,\qquad\omega_j\le e_j\delta_j,
\]

\[
\omega_j\ge a_j-e_j(1-\delta_j),\qquad
\omega_j\le a_j-L_j(1-\delta_j)
\]

精确线性化。因此固定 τ 可建立一个至多 J 个取端二元变量的 MILP，不必显式枚举全部 \(2^J\) 个顶点。

对本项目固定二元 y 的运输模型，可使用：

\[
L_j=\min\{0,\min_{i:(i,j)\in E}(r_{ij}-h_i)\}.
\]

无 eligible carrier 时取 \(L_j=0\)。其依据是对偶约束：

\[
a_j+b_i-\gamma_i-\sigma_{ij}\le r_{ij},\qquad
0\le b_i\le h_i,\quad\gamma_i,\sigma_{ij}\ge0,
\]

即：

\[
a_j\le r_{ij}-b_i+\gamma_i+\sigma_{ij},
\]

而右侧至少为 \(r_{ij}-h_i\)。因此任何小于 \(L_j\) 的 a_j 都可以提高至 \(L_j\)，仍满足所有此类约束及 \(a_j\le e_j\)；由于 \(d_j\ge0\)，对偶最大化目标不会降低。

所以 \(a_j\ge L_j\) 是最优性保持的限制，不是说原始所有对偶可行解都天然满足它。这里使用非负需求和非负现货成本，但不使用 \(h_i\le r_{ij}\)。

该段是一般 MILP oracle 的数学构造，尚未表示代码中已提供这一实现；数值实施仍需核对全部对偶约束、求解状态与全局界。

## 9. 放回 CCG：η、τ 和最坏需求分别在哪一层更新

当前主问题保留已发现需求点对应的补救 block，概念形式为：

\[
\min_{y\in\mathcal Y,\eta\ge0,\theta}
\eta\varepsilon+\sum_s p_s\theta_s,
\]

\[
\theta_s\ge Q(y,d^k)-\eta c_\infty(d^k,d^s)
\quad\text{对已加入的需求点成立}.
\]

实际实现使用对应的原始二阶段变量、目标表达式和约束表示补救 block，不把 Q 当作一个直接可调用的线性表达式。

初始化可对每个正权重原子加入其名义需求点 \(d^s\)。该点距离为 0，使相应 \(\theta_s\) 至少受到名义补救成本的约束，避免空的初始主问题。

每轮步骤为：

1. 求解当前主问题，得到 \(y^{(k)},\eta^{(k)},\theta^{(k)}\)。
2. 对每个正权重历史原子，固定 \(y^{(k)},\eta^{(k)}\)。
3. 遍历该原子的有限 τ 候选，对每个 τ 求固定 τ 的一般 MILP，选出最大的 \(Q-\eta^{(k)}\tau\) 及其需求。
4. 用这些 oracle 值计算当前 y、η 的完整目标，并检查主问题遗漏的违反点。
5. 把必要的需求 block 加入主问题，重新优化 y、η。τ 属于各 oracle，不是外层冻结的参数。

主问题最小化的 best bound 提供下界；若各 oracle 全局求解完成，其真实最大值可用于计算当前解的目标上界。若 oracle 只有可行最大化解，它的 incumbent 只是 oracle 的下界，不能直接冒充整体目标上界；应使用有效的 oracle 上界并保留未认证状态。

τ 的有限化并没有取消 CCG，也没有保证一般 oracle 很快：每轮每个历史原子仍可能需要多个 MILP。

## 10. 与当前单调性简化实现的关系

截至本文整理时，仓库 `src/Model/WassersteinInfinityMonotoneOracle.java` 会显式检查 eligible pair 上的 \(h_i\le r_{ij}\)，并检查非负现货成本与罚金。

在这些条件及连续补救结构下，Q 对各需求分量非递减。因此给定 τ 后，局部 box 的最坏需求可直接取全上端：

\[
d_j(\tau)=\min\{\overline d_j,d_j^s+s_j\tau\}.
\]

仅需上端饱和时刻：

\[
\mathcal T_s^{\mathrm{mono}}
=\{0\}\cup\{(\overline d_j-d_j^s)/s_j:j=1,\ldots,J\}.
\]

最多 \(J+1\) 个候选，每个候选只需连续 LP；无需上下端组合的二元变量。它是一般 oracle 在特定成本条件下的精确简化，不是所有输入都适用的通用 LP。

一般推导与特例实现可以得到相同最优值，前提是特例条件成立且各子问题达到相应的求解精度。本文并未执行一般 MILP 与现有 LP 的新的数值对照。

## 11. 一句话复用摘要

固定 τ 后，由补救成本对需求的凸性，最坏需求可选在局部 box 的顶点。以支撑截断时刻对 τ 分段后，每种固定取端规则产生的需求在段内仿射变化；再固定一个补救对偶解，目标即为 τ 的仿射函数。所有取端规则和对偶解的逐点最大值在该段内凸，故段内最大值可在两端取得。于是一般 L∞ oracle 只需检查 0 及全部上下界截断时刻，每个候选再解联合取端的 MILP；当前满足成本单调性条件的实现进一步简化为仅检查上端截断时刻、每个候选求 LP。

## 12. 关联文件与记录

- `src/Model/WassersteinInfinityMonotoneOracle.java`：当前单调性特例的 L∞ oracle。
- `src/Model/WassersteinBoxOracle.java`：现有 scaled-L1 oracle，不是本文一般 L∞ MILP 的实现。
- `src/Model/ContextualWassersteinBoxCcgSolver.java`：外层 CCG 实现。
- `记录/修稿记录_20260806_TRB/20261007_W1小半径表现解释与无穷范数精确求解思路.md`：此前 L∞ 思路和单调性特例讨论；带日期的实验结论应按对应快照解读。

本文的 box 专用有限 τ 推导作为独立数学说明保存，不声称某篇现有论文已经逐式给出同一 oracle。一般理论、特例实现与数值验证状态应分别表述。
