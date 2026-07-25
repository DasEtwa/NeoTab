# Performance and stability benchmarks

This directory keeps NeoTab benchmark claims separate from the project overview. Every published result includes its methodology, validation status, raw profiler result, and limitations.

## Published studies

| Study | Status | Evidence |
| --- | --- | --- |
| NeoTab 1.4.0 vs. TAB 6.1.0, five repeated 10-minute runs per plugin | Valid | [Summary and all five run pairs](tab-vs-neotab/README.md) |
| NeoTab two-hour active-churn attempt | Invalid and excluded | [Failure report](stress-tests/2-hour-runtime-test.md) |

The invalid two-hour attempt is retained deliberately. A test that collected samples but failed its requested workload is diagnostic evidence, not a performance result.

## Reading these results

- Process RSS and CPU cover the entire Paper JVM, not one plugin in isolation.
- JVM heap, non-heap, GC, TPS, and tick time also describe the server process and server health.
- A short test cannot prove that a plugin has no memory leak.
- Results from different scenarios, server versions, client protocols, or feature profiles are not directly interchangeable.
- All valid repetitions are published. Runs are not removed because their values are inconvenient.

The shared procedure is documented in [methodology.md](methodology.md).
