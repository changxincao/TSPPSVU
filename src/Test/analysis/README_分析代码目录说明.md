# analysis 代码目录说明

本目录按“功能主线 + 演化时间”拆分，根目录只保留兼容入口，真实实现放在子目录。

## 目录结构
- `legacy/`
  - 3 月 6 日前后形成的解释型分析工具。
  - 主要做单样本、供应商选择原因、spot/contract 价格关系、rolling 成本需求解释等。
- `covariate/`
  - 3 月 10 日起的协变量相关性与权重诊断工具。
  - 包括 quick-eval、theta 权重扫描。
- `synthetic/`
  - 3 月 10 日以后围绕构造数据的生成、求解与比较。
- `brazil/`
  - 3 月 11 日以后围绕巴西数据的 raw/PCA、CSAA/RCSAA、purchase/approval 口径求解入口。
- `realhistory/`
  - 3 月 12 日以后围绕原始真实数据 baseline/CSAA 的入口。

## 兼容策略
根目录中的同名 Java 类和 Python 脚本是兼容包装层：
- 旧的 `Test.analysis.Xxx` 启动方式继续可用。
- 实际实现已经迁移到对应子包。

## 建议使用方式
- 新增分析工具时，优先放入对应子目录。
- 只有确实需要兼容旧脚本时，才在根目录新增包装入口。
