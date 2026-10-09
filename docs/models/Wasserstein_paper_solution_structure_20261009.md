# Wasserstein 正文与附录求解部分的写作结构记录（2026-10-09）

## 1. 正文口径

正文只保留一个很短的总述，不展开具体求解流程：

> We consider two type-1 Wasserstein DRO models using scaled \(L_1\) and \(L_\infty\) ground metrics, respectively. Both models are solved using a common column-and-constraint generation framework.

具体模型、CCG 和 separation 细节均放到附录。

## 2. 附录总体组织

当前决定是不重新完整推导一般 Wasserstein 对偶或已有 CCG 理论，而是：

1. 在现有 W-DRO 模型定义之后，说明求解框架参考相关 Wasserstein DRO 分解文献，并采用 CCG。
2. 用一个简洁的算法伪代码完整描述共同的 CCG 外层流程。
3. 然后分别说明两种 ground metric 下的 sample-wise separation algorithm。
4. 两种 metric 共用同一个 CCG 外层，不重复写两套 CCG。

建议的附录结构：

### A. Wasserstein DRO model
保留现有 ambiguity set、support、ground metric 和 W-DRO 模型定义。

### B. Common CCG solution framework
不重新推导一般理论。引用已有文献后，直接说明本文采用 CCG。
只给出求解所需的核心 sample-wise separation problem 记号，并提供 Algorithm 伪代码。

伪代码逻辑：
- 初始化：对每个正权重历史原子保留初始 demand point；
- 求解 restricted master，得到 \(y\)、Wasserstein dual variable 和 sample-wise epigraph variables；
- 对每个历史原子求解 separation problem；
- 若发现 violated worst-case demand realization，则将对应 recourse block / support point 加入 master；
- 更新上下界；
- gap 达到 tolerance 时停止。

论文中不写代码层的 timeout fallback、日志、重复点检查、solver 状态处理等实现细节。

### C. Separation under the scaled \(L_1\) ground metric
这一部分不是本文的新算法贡献。
利用 box support + scaled-\(L_1\) 下已有的有限边界 / endpoint 结构，参考 Gamboa et al.、Duque et al.、Saif and Delage、Byeon et al. 等相关工作。
说明当前实现通过一个 MILP 隐式选择每个 lane 的 lower / nominal / upper 位置，不显式枚举所有组合。
不重复写完整 CCG。

### D. Separation under the scaled \(L_\infty\) ground metric
这一部分是本文需要重点说明的新增分析。
外层仍使用同一个 CCG，仅改变 separation oracle。

当前正式实现利用本文采购模型在实验参数下的单调 recourse 结构：
- 给定一个共同的标准化移动幅度 \(\tau\)，最坏 demand 取局部 box 的上端；
- 只需要检查由各 lane 达到 support upper bound 所产生的有限 saturation breakpoints；
- 最多检查 \(|\mathcal J|+1\) 个 \(\tau\) 候选；
- 每个候选只需求解一个连续 LP；
- 因此 sample-wise \(L_\infty\) separation 可以精确有限化。

这一结论建议在附录中以 proposition + 简短 proof 的形式给出，或以 separation algorithm + supporting proposition 的形式给出。

## 3. 核心写作原则

- 不把 scaled \(L_1\) 和 scaled \(L_\infty\) 写成两套完整 CCG。
- CCG 外层流程只写一次。
- 两种方法的区别集中在 separation oracle。
- scaled \(L_1\) 主要引用已有文献，保持简短。
- scaled \(L_\infty\) 因缺少可直接照搬的现有专用处理方式，应明确给出本文的有限化结果与精确求解逻辑。
- 注意术语：两者都是 type-1 Wasserstein，区别是 ground metric；不要写成 Wasserstein-1 与 Wasserstein-infinity。
