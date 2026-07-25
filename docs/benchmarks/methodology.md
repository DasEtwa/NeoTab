# Benchmark methodology

## Measurement scope

NeoProfiler collects whole-process RSS, CPU, thread and I/O metrics from the Paper JVM. NeoProfilerProbe adds whole-JVM heap, non-heap and garbage-collection counters plus Paper TPS and average tick time.

These measurements do not provide exact per-plugin RAM or CPU ownership. A package-filtered heap histogram, when used, is only estimated attributed heap and still excludes shared objects, native allocations, metaspace, code cache, thread stacks, and library ownership.

## Repeated A/B procedure

Comparisons use five independently started JVMs per plugin. The order alternates to reduce bias from JVM warm-up, filesystem cache state, machine temperature, and background processes:

1. NeoTab, then TAB
2. TAB, then NeoTab
3. NeoTab, then TAB
4. TAB, then NeoTab
5. NeoTab, then TAB

Each plugin run uses:

- a fresh server JVM;
- the same cloned Paper server, world, common plugins, Java version, heap flags, scenario, seed, and bot count;
- a validated 45-second warm-up that is excluded from the reported measurements;
- a ten-minute measurement with one sample every 30 seconds;
- graceful server shutdown before the next plugin starts.

The published aggregate reports the median, arithmetic mean, sample standard deviation, minimum, and maximum of the five per-run means. Paired TAB-minus-NeoTab deltas are also calculated for each run position. The 20 samples within one run are not treated as 20 independent repetitions.

## Active-player scenario

- 20 Minecraft 1.20.6 bots on a native protocol 766 endpoint
- simultaneous initial join storm
- one fixed disconnect/reconnect per second with a 500 ms reconnect delay
- one additional seeded random disconnect/reconnect every five seconds with a 1,000 ms reconnect delay
- movement every second
- jump every five seconds
- sprint every seven seconds
- chat every 30 seconds
- deterministic seed `5133647`

Fixed churn is scheduled against a real clock. Completed join counts may differ by one or two events between ten-minute runs at the boundary, while still representing less than 0.3% workload variation. Exact event counts are published per run.

## Validation contract

A run is accepted only when the profiler writes `result.json` with `valid: true` and exits successfully. Validation requires:

- initial 20/20 bot readiness and a peak of 20 connected bots;
- no protocol or driver errors;
- no premature disconnects;
- no pending reconnects after teardown;
- server-log join and leave counts matching driver connection and intentional-disconnect counts;
- all requested actions meeting their scheduling tolerance;
- exactly 20 process samples and 20 fresh Paper/probe samples for the ten-minute scenario;
- zero active bots after teardown.

An `invalid-result.json` is never converted into an idle or partial performance result.

## 2026-07-25 environment

| Component | Value |
| --- | --- |
| Host OS | Windows 10 Pro 22H2, build 19045 |
| CPU | Intel Core i7-2600, 4 cores / 8 logical processors |
| Visible memory | 16 GiB |
| Java | Eclipse Temurin 21.0.11+10 LTS |
| JVM flags | `-Xms256M -Xmx512M` |
| Server | Paper `1.20.6-151-a4f0f5c` (Minecraft 1.20.6) |
| Common plugins | LuckPerms 5.5.53, PlaceholderAPI 2.12.2, NeoProfilerProbe 0.1.0-SNAPSHOT |
| Network | Isolated loopback endpoint, `online-mode=false` |

The machine was not a dedicated laboratory host. A persistent lobby and Velocity proxy were running during the series, and ordinary operating-system background activity was not disabled. Alternating order and repeated runs reduce but do not eliminate that noise.
