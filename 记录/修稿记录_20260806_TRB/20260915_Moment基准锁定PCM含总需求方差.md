# Moment 基准锁定：PCM（2026-09-15）

用户最新决定：正式替代歧义集比较不做 Mean–MAD，只保留 partial cross-moment（PCM）。这里的 PCM 是逐 lane 均值、逐 lane 方差上界，外加**一条总需求方差上界**；不是完整协方差矩阵，也不是只有边际方差的模型。无条件版本用等权历史样本估计矩；条件版本用同一组样本的 contextual 概率估计矩。二者只改变输入概率，不另造场景。

设参考权重为 $\pi_s$，$\hat\mu_j=\sum_s\pi_s d_j^s$，$\hat v_j=\sum_s\pi_s(d_j^s-\hat\mu_j)^2$，$\hat v_\Sigma=\sum_s\pi_s[\sum_j(d_j^s-\hat\mu_j)]^2$。所选 PCM 歧义集在共同需求支撑 $\mathcal U$ 上要求 $E[D_j]=\hat\mu_j$、$E[(D_j-\hat\mu_j)^2]\le\kappa^2\hat v_j$ 以及 $E[(\sum_j(D_j-\hat\mu_j))^2]\le\kappa^2\hat v_\Sigma$。最后一项等于各 lane 方差加两倍所有跨 lane 协方差之和，只使用一个汇总相关性统计量。为与最新数值说明书一致，暂保留已有 $\kappa\in\{1,1.25,1.5,2\}$ 训练内验证网格，并对两类方差界施加相同 $\kappa^2$；这不是旧 pilot 已实现的功能，须在正式 runner 中实现后核验。

准确区分精确性：旧 Java `ExactMeanMadDROSolver` 的特定精确化需要每个 eligible pair 都满足 $h_i\le r_{ij}$；$h_i=\min_{j:e_{ij}=1}r_{ij}$ 是满足条件的一种充分设定，并非“数学上只能取 min”。当前决定不使用 MAD 与该条件是否满足是两件事。现有 `analysis/alternative_ambiguity_pilot_20260828/run_rsome_moment_pilot.py` 的 PCM 分支已包含上述边际和总量方差、连续 box 支撑及 U/C 权重，但固定经验界（$\kappa=1$）；它让 recourse 对需求和二阶矩 lift 仿射适应，以 MOSEK 求解 **lifted-affine 策略近似**，不能宣称为完全自适应二阶段 PCM-DRO 的精确求解。旧 Java `ExactPartialMomentDROSolver` 另有含总量方差的精确交换算法；小规模曾有证书，大一些的旧算例未认证，不作为正式 SVU 实验的已验证求解器。旧 MAD 脚本/求解器保留为历史代码，但不进入正式实验、调参或论文比较。

本轮只修改工作区可编辑 TeX 的 Moment 小节和候选方法名称，未修改求解器、未运行实验。此前 `20260914_Moment现状_新边际方差基准与旧代码差距.md` 记录的是当时“仅边际方差”的文档状态；本记录和修订后的 TeX 优先，避免把旧审计误读为最新选择。工作区外的旧 PDF/副本尚未同步，不能作为最新 PCM 公式引用。
