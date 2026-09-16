# realhistory

## 这个目录总体做什么
这一组用于原始真实数据的实验入口。
这里的数据口径不是巴西 Olist 那套周度 OD 需求表，而是更早那版带外生变量的真实历史数据。

它的重点是验证：
- baseline 外生协变量本身有没有信息量。
- 在这份真实数据上，CSAA 相对 SAA 是否还有明显提升。

## 文件作用
- `RealHistoryBaselineSolveComparison.java`
  - 原始真实数据的主入口。
  - 负责按 rolling 方式比较 `SAA / CSAA / Mean / Complete`。
  - 使用的是 baseline 协变量设计，例如过去总需求和外生变量组合。
