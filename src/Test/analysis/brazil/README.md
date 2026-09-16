# brazil

## 这份 README 的用途
这个目录里的 Java 文件大多是巴西数据实验的入口类。它们的 `package` 历史比较杂：

- 有些是 `package Test`
- 有些是 `package Test.analysis.brazil`

为了避免把源码路径一挪就把 Eclipse 入口、已有运行配置和历史脚本一起弄坏，这一轮没有移动源码文件本体，而是通过：

- 文件头注释
- 本 README 的分类地图

来完成整理和分类。

## 一、基础主线入口
这几类是“基准输入、处理、输出”主线入口，本轮没有改目录层级，只补了文件头说明。

- `BrazilOlistRawSolveComparison.java`
  - raw covariate 下的主 baseline 入口。
  - 负责 Mean / SAA / CSAA 等基础方法的统一求解流程。

- `BrazilOlistThetaModeSolveRunner.java`
  - 更通用的主线入口。
  - 支持 raw / PCA theta 模式，以及 CSAA / RCSAA 变体切换。

- `BrazilOlistRCSAAComparison.java`
  - 早期专门比较 RCSAA / lambda 的入口。
  - 现在更多是历史主线入口。

- `BrazilAppendScenarioQUpperBound.java`
  - 实验输入修补工具。
  - 用来补写 scenario q upper bound，不负责选参或汇总。

## 二、35/15 与 40/10 主实验相关入口
这几类直接服务于滚动 CV、选参重跑、以及 exact-RCSAA 结果提取。

- `BrazilOlistExactDroCvDetailDump.java`
  - 35/15 详细验证导出。
  - 固定每个 trial 的 CSAA 最优 `k, C_h`，逐个 lambda 输出 exact-RCSAA 与 DRO 的验证样本细节。

- `BrazilOlistDroCv4010Rerun.java`
  - 40/10 DRO 重跑入口。
  - 输出逐 lambda 验证明细、10 个验证样本细节、最终选中参数、总体汇总和频率表。

- `BrazilOlistExactOnDroSelectedParamsOos.java`
  - 用 DRO 已选参数去跑 exact-RCSAA 的真实样本外结果。
  - 对应目录里“RCSAAEXT 套用 DRO 选中参数”的那批结果。

- `BrazilOlistSingleParamCompare.java`
  - 单参数点复算 / spot-check 工具。
  - 适合排查单个 trial、单个参数组合的差异。

- `BrazilOlistSingleRCSAAVariantRunner.java`
  - 单个 RCSAA solver variant 的最小运行入口。
  - 适合快速确认，不适合整批汇总。

## 三、固定 CSAA 最优 kC、全 lambda 扫描对照
这批代码服务于“固定每个 trial 的 CSAA 最优 `k, C_h`，然后扫描全 lambda”的三方法对照实验。

- `BrazilOlistDroLambdaOosSweep.java`
  - DRO 版本的 full-lambda 实际样本外扫描。

- `BrazilOlistExactLambdaOosSweep.java`
  - exact-RCSAA 枚举版本的 full-lambda 实际样本外扫描。

- `BrazilOlistPrimalExactK2LambdaOosSweep.java`
  - LBBD_PRIMAL_SEARCH(radius=2) 版本的 full-lambda 实际样本外扫描。

- `BrazilOlistExactLambdaOosSweepMerge.java`
  - 合并 exact-RCSAA full-lambda 扫描的 chunk 结果。

- `BrazilOlistFixedKCGridRCSAAOosSweep.java`
  - 固定 `k`、自定义 `C_h` 网格，再扫 lambda 的补充实验入口。
  - 用于 `C_h` 灵敏度、固定 `kC` 补充验证等。

- `BrazilOlistRCSAALBBDComparison.java`
  - 比较 exact-RCSAA 与 LBBD / primal-search 的目标值、样本外表现和求解时间。

## 四、早期补充实验与后处理
这批主要是从主实验结果往下做“高频参数、子集 trial、场景补充比较”的工具。

- `BrazilOlistTop5ParamRCSAADROComparison.java`
  - 统计高频参数组，并可选地对 top-N 参数组做 DRO / RCSAA 补充比较。

- `BrazilOlistTopParamScenarioComparison.java`
  - 在指定 scenario / trial 子集上比较少量高频参数组。

- `BrazilOlistTopParamSubsetHybridComparison.java`
  - 把 DRO、exact-RCSAA、primal-search 压缩到选中 trial 子集上做混合比较。

- `RunI15LbbdOnly.java`
  - 15 供应商 LBBD-only 的最小启动入口。
  - 更偏 debug / 单独启动工具。

## 五、这轮整理后的建议使用方式
如果你是按“实验目的”找代码，优先按下面顺序找：

1. 主实验 baseline / 主线流程
   - `BrazilOlistRawSolveComparison.java`
   - `BrazilOlistThetaModeSolveRunner.java`

2. 35/15、40/10 主实验补跑与细节导出
   - `BrazilOlistExactDroCvDetailDump.java`
   - `BrazilOlistDroCv4010Rerun.java`
   - `BrazilOlistExactOnDroSelectedParamsOos.java`

3. fixed-kC、full-lambda 三方法对照
   - `BrazilOlistDroLambdaOosSweep.java`
   - `BrazilOlistExactLambdaOosSweep.java`
   - `BrazilOlistPrimalExactK2LambdaOosSweep.java`
   - `BrazilOlistExactLambdaOosSweepMerge.java`

4. 补充实验 / 高频参数 / 子集 trial
   - `BrazilOlistTop5ParamRCSAADROComparison.java`
   - `BrazilOlistTopParamScenarioComparison.java`
   - `BrazilOlistTopParamSubsetHybridComparison.java`

## 六、代码副本在哪里
为了方便搬到大电脑运行，已经把一部分可搬运副本单独放到：

- `analysis/巴西数据分析/新版_purchase时间/输出/移到大电脑的代码副本`

那一层会继续按“主实验入口 / full-lambda 扫描 / 历史包”做目录分类，但不反向影响这里的工程源码结构。
