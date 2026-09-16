package Model;

public enum RCSAASolverVariant {
    // 保留原先的 DRO 近似 extensive form 求解路径
    DRO_EXTENSIVE,
    // 旧版：同一个 master 中同时累计 lower cut 和 incumbent upper cut
    LBBD_EXACT,
    // 旧版：lower cut + 全局上界 + 邻域/删解搜索
    LBBD_SEARCH,
    // 新版：lower side 直接显式展开 recourse primal，只迭代 upper/equality cut
    LBBD_PRIMAL_EXACT,
    // 新版：lower side 直接显式展开 recourse primal，并结合 search 逻辑
    LBBD_PRIMAL_SEARCH,
    // 直接枚举所有满足一阶段选择约束的 y，逐个精确评估原始 RCSAA 目标
    ENUMERATE_EXACT
}
