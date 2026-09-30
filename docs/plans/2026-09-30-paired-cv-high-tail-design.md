# Paired CV [0.5,0.7] tail queue

User approved CV [0.5,0.7] on 2026-09-30. Add five markets with seeds
20261020 through 20261024, forty ordinary random queries each, and 1000 OOS
draws per query. Keep the current Normal DGP, common loadings [0.3,0.5],
procurement parameters, contexts, selection bounds, and method grids.

Reuse the existing generator without Java/model changes. The new input and
output live under a separate `paired_cv_high050070_20260930` root on
100.71.236.93. A detached remote waiter starts the 25 method/market tasks
only after the current three-CV controller has a terminal status with zero
running and queued tasks. Keep four concurrent tasks, four solver threads,
and the existing 14400-second model limit. Do not restart current workers.

The queue runner gains a Cells parameter with the original three cells as
the unchanged default. The new wrapper supplies only cv050070. Existing
failure/retry and training-only context selection remain unchanged.
Deploy the generalized runner under a new `_cells` filename so the original
three-cell controller's script file is not overwritten.

Verification: PowerShell parse and queue-default tests; generated seed/CV
manifests; forty random queries per market; hashes of procurement and
context/query manifest files match the existing paired cells; detached
waiter has no optimizer tasks until its predecessor finishes.
