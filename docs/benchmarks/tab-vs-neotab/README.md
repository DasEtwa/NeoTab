# NeoTab 1.4.0 vs. TAB 6.1.0

Five validated ten-minute runs per plugin were completed on 2026-07-25. Every run used 20 active bots, sustained reconnect churn, movement, jumping, sprinting, and chat.

> **This is a default-configuration comparison, not a perfectly feature-matched microbenchmark.**

Both plugins completed all five runs without protocol errors, premature disconnects, server exceptions, or loss of TPS. The repeated results do not support a reliable RSS or CPU winner because those values changed direction between pairs and showed substantial run-to-run variation.

TAB used more average JVM heap and accumulated more GC time in all five pairs. TAB also recorded lower average tick time in all five pairs. These are whole-server observations under different default feature profiles, not isolated ownership measurements.

## Five-run aggregate

Each cell is calculated from the five per-run averages. `±` is the sample standard deviation across those five runs. The final column is the mean paired delta, TAB minus NeoTab; lower values mean less resource use or lower tick time.

| Metric | NeoTab median | NeoTab mean ± SD | TAB median | TAB mean ± SD | Mean paired delta |
| --- | ---: | ---: | ---: | ---: | ---: |
| Whole-process RSS | 894.7 MiB | 898.0 ± 9.6 MiB | 912.4 MiB | 905.4 ± 12.5 MiB | +7.3 MiB |
| Whole-process CPU | 50.27% | 49.09 ± 10.95% | 49.31% | 51.56 ± 12.15% | +2.47 pp |
| Process threads | 88.25 | 91.88 ± 8.52 | 92.10 | 96.25 ± 7.61 | +4.37 |
| JVM heap used | 399.0 MiB | 399.1 ± 8.5 MiB | 417.0 MiB | 420.1 ± 9.0 MiB | +20.9 MiB |
| JVM non-heap used | 218.6 MiB | 221.2 ± 5.1 MiB | 223.0 MiB | 221.4 ± 4.5 MiB | +0.2 MiB |
| Average tick time | 8.55 ms | 9.25 ± 2.45 ms | 6.02 ms | 7.45 ± 2.56 ms | -1.80 ms |
| One-minute TPS | 20.0046 | 20.0030 ± 0.0028 | 20.0033 | 20.0028 ± 0.0027 | -0.0002 |
| Observed GC time delta | 2,775 ms | 2,890 ± 632 ms | 3,761 ms | 3,794 ± 543 ms | +904 ms |
| Observed GC cycles delta | 376 | 380 ± 53 | 419 | 445 ± 66 | +65 |

## Consistency of paired differences

| Observation | Runs agreeing | Paired mean ± SD | Interpretation |
| --- | ---: | ---: | --- |
| TAB used more JVM heap | 5/5 | +20.93 ± 9.38 MiB | Consistent in this series |
| TAB accumulated more GC time | 5/5 | +904 ± 184 ms | Consistent in this series |
| TAB had lower average tick time | 5/5 | -1.80 ± 0.42 ms | Consistent in this series |
| TAB used more process RSS | 3/5 | +7.33 ± 15.72 MiB | Direction changed; inconclusive |
| TAB used more process CPU | 3/5 | +2.47 ± 3.80 pp | Direction changed; inconclusive |

Run 01 alone showed TAB using 11.2 MiB less RSS. The five-run series instead produced a +7.3 MiB mean paired delta with a 15.7 MiB standard deviation and two pairs in the opposite direction. This reversal is why a single favorable run is not used as a public conclusion.

## Tested default feature profiles

NeoTab defaults enabled its animated tab header/footer, three-tick `smooth` update preset, PlaceholderAPI/LuckPerms integration when present, and ActionBar welcome behavior. Its sidebar scoreboard was disabled.

TAB defaults enabled header/footer, tab-list name formatting, scoreboard teams and sorting, and the player-list ping objective. Its sidebar scoreboard, below-name objective, boss bar, and layout were disabled.

The plugins therefore did not perform an identical set of visual features. The comparison answers how their fresh default configurations behaved in this scenario; it does not isolate like-for-like implementation cost.

## Runs and raw results

| Run | Order | NeoTab joins/leaves | TAB joins/leaves | Report |
| ---: | --- | ---: | ---: | --- |
| 01 | NeoTab → TAB | 735 / 735 | 735 / 735 | [Run 01](2026-07-25-run-01.md) |
| 02 | TAB → NeoTab | 735 / 735 | 734 / 734 | [Run 02](2026-07-25-run-02.md) |
| 03 | NeoTab → TAB | 733 / 733 | 734 / 734 | [Run 03](2026-07-25-run-03.md) |
| 04 | TAB → NeoTab | 734 / 734 | 735 / 735 | [Run 04](2026-07-25-run-04.md) |
| 05 | NeoTab → TAB | 734 / 734 | 735 / 735 | [Run 05](2026-07-25-run-05.md) |

Every entry had 20 process samples, 20 fresh Paper samples, zero driver errors, and zero premature disconnects.

## Artifacts

- NeoTab 1.4.0 SHA-512: `9d7efbce94b4cdcfde9a7e9beefa93a5461fa26e9963cbc34bb4280b9edc7e71a45443f12805eaa29bd583db85562c569cb05e63d2ea93123603fc304264a641`
- TAB 6.1.0 Paper 1.20.5–1.21.4 build SHA-512: `f8b09549f4d6318d95c21e5617076bf0126719b3792037ed315ba20a9f208b6a3dd89acb3d756ad23a7761ca6d105eb122136ebbb0417a9ea48d4b0ad05a5e4b`
- Machine-readable aggregate: [summary.json](summary.json)
- Shared methodology: [../methodology.md](../methodology.md)

## Limitations

- Process and JVM measurements cover the complete server, not exact plugin ownership.
- Five runs improve confidence but do not make this controlled laboratory research.
- The feature profiles were defaults, not feature-matched configurations.
- The tests ran for ten minutes each and cannot prove long-term leak freedom.
- The old i7-2600 host, persistent lobby/proxy, OS scheduling, JIT, GC, filesystem cache, and temperature contribute noise.
- No statistical significance test is claimed from five pairs.
