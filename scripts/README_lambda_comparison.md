# RCSAA与卡方DRO：同λ比较（2026-10-02）

## 冻结范围

复用最终主实验的算例、query、历史需求、已选定CSAA及每个query实际使用的条件权重、同一批OOS。不生成新数据、不做CV、不重新选择带宽或RF叶节点参数。本轮只准备代码；输入与λ网格在主实验确定后填写。预计10个市场×40个query，每个λ有400对，但当前不生成这400份输入。

两种模型都不设人为求解时限，每求解器4线程，使用现有正常最优性口径。RCSAA显式走switched compact集成模型，不用repair或product compact。无限时通过关闭这两条路径的MOSEK限时与RCSAA外层时钟实现，不以巨大秒数代替；其他入口的正数限时不变。

仅保留原有求解/OOS输出，增加两项核心比较：

1. 同λ下两种模型返回的承运人决策是否完全一致。
2. 在DRO认证最优决策上，命题充分条件是否成立。以实际求解参考概率和各正权重训练场景的真实recourse cost计算`mu, sigma, Q_min`，检验`lambda*(mu-Q_min) <= sigma`。若所有成本相同则直接成立。保存阈值` sigma/(mu-Q_min)`及全部训练成本。

条件成立说明DRO最优决策也是RCSAA最优解，不要求求解器在并列最优解中返回同一个组合；不成立不代表必然不一致。若DRO尚未认证，条件状态为UNRESOLVED，不算false。两种均认证才把目标差解释为最优目标差；OOS只需可行决策即可评价，并保留其状态。

预期解释是“小λ下一致性较高，且主实验CV常选中小λ，因此DRO可能具有实用价值”；这是待验证假设，不预先写死结论、不筛掉反例。主实验CV频次从既有选参文件另行汇入，不在本入口重做CV。对应审稿意见R4-M19（条件成立频率）、M20（大λ下近似质量）。

## 输入与命令

从`lambda_decision_pending.properties`复制每个case/query的配置，填齐并设为FROZEN。保留`timeLimit=UNLIMITED`。`contextParameter`是实际B_eff或RF叶节点参数，`validationContextParameter`是原验证阶段选出的值。`runtimeFingerprint`记录实际部署代码及运行库标识，跨版本不得直接复用输出。

```text
java <classpath/native库配置> Test.analysis.synthetic.TRBSVULambdaDecisionComparison \
  config.properties instance.tsv context_weights.tsv 新的case-query输出目录
```

`instance.tsv`沿用主实验可读格式，必须包含OOS。权重文件为TSV：

```text
row_index sample_id weight
```

实际分隔符为tab，按完整训练窗口顺序保存，包括零权重行；读取核对行号、sample_id、权重非负有限、归一化。两种方法共享输入概率，严格零权重删除及小正权重floor沿用既有规则。`effective_reference.tsv`另存理论检验实际使用的求解参考概率。最终主实验目录的权重导出接线待数据源确定后完成，不擅自从旧实验猜来源。

## 输出与恢复

```text
case-query/
  configuration.properties / sources.txt / input_fingerprint.txt
  weights.csv / effective_reference.tsv
  comparison.csv                  # 每个λ写一行，逐λ更新
  checkpoints/                    # 每个方法/λ求完立即保存可读解
  lambda_<value>/
    C-Chi2.log / RCSAA.log
    C-Chi2_solve.csv / RCSAA_solve.csv
    C-Chi2_oos_summary.csv / RCSAA_oos_summary.csv
    *_oos_details.txt             # 指向共享的逐draw成本明细
    certificate.csv              # mu/sigma/min/阈值/条件判定
    *_failure.txt                # 若失败
  evaluations/<选人向量>/
    decision_summary.csv / decision_draws.csv
    oos_complete.properties      # 哈希、完整性检查
    training_costs.tsv / training_complete.properties
  complete.txt
```

同一query下同组合跨λ/方法复用OOS和训练成本；不同query绝不共享缓存。缓存按完整输入、配置和部署标识隔离，缺失/哈希异常不能冒充完成。日志记录恢复是否复用；完整求解checkpoint可以在OOS失败后恢复，避免重复MIP。单个方法或λ的普通异常保存后继续其他任务，最后以非零退出码报告有失败。原生JVM崩溃只能由未来外部进程调度处理，本入口不宣称能捕获DLL崩溃。

`complete.txt`表示全部步骤的输出已写完，不表示所有求解均已认证最优；分析必须读取status/certified字段。每个query独立启动一个JVM，不可在同一JVM内多线程并发本入口（它重定向各模型日志）。未部署远程队列。

## 汇总

建立包含全部预期query的CSV清单：`case_id,query_id,comparison_csv`，不只列已经完成的query。运行：

```text
python scripts/summarize_lambda_comparison.py manifest.csv lambda_summary.csv --lambdas <冻结的逗号分隔网格>
```

决策一致率以两者均认证的配对为分母，条件成立率以DRO认证且完成理论检验的query为分母，另报总计划数、缺失、未认证与未检验数，不把失败计作“不一致”。全部完成时两种分母均为400。OOS按每个完整市场先平均query，再平均市场；SD/Q95/CVaR/Max是条件指标平均，不是混合分布风险。Mean差额为每市场先汇总成本再求百分比，正数表示RCSAA成本更高。尚未完成市场不参与OOS均值并显式计数；原始query结果全部保留。

## 验证

`TRBSVULambdaComparisonSelfCheck`测试参数检查、命题边界/常数成本/零权重、缺失标记、CSV列数等。加`--native`执行1承运人×1lane×2训练样本、4个OOS的手写微型例：lambda=0.5与2；验证RCSAA目标13、DRO目标12（lambda=2），条件在0.5成立、在2不成立，且两者可同解。重复启动确认解与OOS缓存未重算，再检验损坏文件拒绝读取。临时fixture清理，不是正式实验数据。
