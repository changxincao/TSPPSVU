# legacy

## 这个目录总体做什么
这一组是较早形成的解释型分析工具，主要不是为了批量跑求解，而是为了回答“结果为什么会这样”。
重点关注三类问题：
- 单样本求解与样本外结果之间为什么差很多。
- 某些供应商为什么总是被选中，背后的成本结构和需求结构是什么。
- 合同价、spot 价、供应商可服务线路之间的关系是什么。

换句话说，这一组代码更偏“结果解释”和“诊断表生成”，适合在你已经有试验输出之后，进一步拆解原因。

## 文件作用
- `RollingCostDemandAnalyzer.java`
  - 对 rolling trial 下的需求、线路成本、最低成本供应商、大头线路等做统计。
  - 主要回答“需求是否集中在少数线路”“这些线路的低成本供应商是谁”。

- `SharpSampleVsOosAnalyzer.java`
  - 对比某个样本内表现很尖锐的 sample 和其对应样本外表现，找出差异来自哪些线路和需求结构。
  - 主要回答“为什么这两个 sample 看起来相近，但样本外差很多”。

- `SingleSampleDemandSelectionAnalyzer.java`
  - 分析 single-sample 结果文件，比较不同 sample 下的大头需求线路与选中供应商的关系。
  - 主要回答“single-sample 下供应商集合为什么常常很稳定”。

- `SpotPriceRatioAnalyzer.java`
  - 分析 spot price 与合同价 `r_ij` 的比例关系。
  - 主要回答“spot 相对合同价到底高多少，哪些线路/供应商差距最大”。

- `SupplierLineCostExporter.java`
  - 把“供应商在各条线路上的成本排序”导出成表，同时可附带线路需求统计。
  - 主要用于做静态成本结构展示。

- `SupplierSelectionReasonAnalyzer.java`
  - 综合 global trial 结果、线路需求、成本结构，解释为什么某些供应商反复被选。
  - 这是偏综合解释的分析器，适合在已有求解结果后做归因。
