# Two-hour active-churn attempt — invalid

> **This run is excluded from performance and stability conclusions.**

On 2026-07-25, NeoTab 1.4.0 was observed for two hours with 20 bots and fixed-rate reconnect churn. The profiler collected 240 process samples and 240 fresh Paper samples, recorded zero driver errors and zero premature disconnects, and completed teardown.

The requested workload was not sustained at the required rate. Only 24,450 fixed-rate churn actions completed; validation required at least 35,279 of the 35,999 scheduled actions. The profiler therefore wrote `invalid-result.json` and rejected the run.

The samples must not be used to claim two-hour stability, performance, or leak freedom. The attempt is retained to show the validation boundary and to guide a future sustainable-rate rerun.

Raw diagnostic result: [2026-07-25-invalid-result.json](raw/2026-07-25-invalid-result.json)
