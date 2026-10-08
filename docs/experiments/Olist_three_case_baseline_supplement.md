# Olist 001–003 基础方法补齐入口（2026-10-08）

## 范围

使用 `olist_rf_fixedtrend_seed10_20261003` 中的原始 market_001、002、003，市场种子分别为351023、351024、351025。直接复制冻结输入和该批Java运行库，不重新生成市场，不修改真实需求、采购参数或trend。完整十市场的历史结果保留；三市场是此前探测后选择的案例子集。

新增任务仅为D、SAA、Exp-CSAA：3市场×3方法，每任务51个真实测试周。已有RF-CSAA的153个周结果通过带SHA256的 `rf_result_references.tsv` 引用，不复制大型验证缓存，不重复RF求解。已有RF中心χ²-DRO也无需重算。

## 方法和输出

- D：前50周需求均值，求解一个确定性场景，无超参数验证。
- SAA：相同前50周需求等权，无超参数验证。
- Exp-CSAA：滞后阶数1、2、3；带宽0.1、0.25、0.5、1、2、5。每个测试周用此前15个rolling origins、各35周历史选参，再用50周历史正式求解。重叠验证窗口沿用现有checkpoint复用逻辑。
- RF-CSAA：引用原结果，500棵树，leaf=1、2、5、10，原逐周lag/leaf验证结果不变。

延用原固定日历trend：周序号/104，验证与最终求解均不按窗口重新缩放trend；其他需求滞后特征仍按训练窗口max标准化。所有方法使用相同目标周、真实OOS需求和采购市场。新输出完整保存验证候选、选参、决策、成本分解、权重、求解日志、状态、bound、gap及时间。

新目录 `results/market_NNN/trial_NNN/{D,SAA,EXP}` 与原目录RF引用共同组成四方法比较。汇总时逐条核对引用哈希、目标周及需求一致性，执行原 `OlistContextualBatchMain audit` 检查各方法51周完整性；不能只依赖complete标记。准备脚本的文件/协议检查不代替该完整语义审查。

## 准备与启动分开

在已有原始实验目录的机器上执行（路径按部署位置填写）：

```powershell
& .\scripts\prepare_olist_three_case_baselines.ps1 `
  -SourceRoot 'D:\ccx\TSPP_SVU\experiments\olist_rf_fixedtrend_seed10_20261003' `
  -Root 'D:\ccx\TSPP_SVU\experiments\olist_three_case_baselines_20261008'
```

入口仅准备文件，不启动Java、Python、调度器或优化求解。目标目录必须不存在；拒绝覆盖。运行环境沿用原config。运行前用生成目录的 `scripts/run_olist_batch.ps1 -Root ... -CheckOnly` 做无求解预检。授权启动后用 `scripts/start_olist_detached.ps1 -Root ...`，最多4进程、各4求解线程，原求解限时保持不变。此处不接入其他等待队列。

## 是否补W1/RCSAA

本轮不补。人工主实验已承担多种鲁棒模型的比较，Olist可集中展示真实需求下D/SAA/两类CSAA及RF中心χ²-DRO的表现；无需每个实验都展开所有模型。论文结论限定为实际比较过的方法，不将Olist的χ²结果外推为W1或RCSAA的实证结果。若某条审稿意见明确要求真实数据上的这些特定模型，再单独补充。
