# Appraan — Design

Appraan is a live JVM observer: point it at a PID and it shows what the process is
doing — CPU, allocation, GC, threads — and (in later milestones) what the kernel is
doing on its behalf: IO, syscalls, TCP connections. One jar, zero dependencies,
identical features and UI on Linux and Windows.

**Core principle: metrics are defined by their MEANING, never by a platform
implementation.** "Kernel operations by category, with latency" is a metric;
"syscalls counted with eBPF" is one platform's way of fulfilling it. Every metric
in the model has a meaning, a unit and a source per platform — see the parity
contract below.

## Architecture (Milestone 1)

```
                ┌────────────────────────── Appraan process ───────────────────────────┐
                │                                                                      │
  PID ──► CLI ──┼─► Attach (TargetVm) ── jcmd over local attach socket ──┐             │
                │        │                                               │             │
                │        ├─► Discovery (VM.version / VM.flags /          ▼             │
                │        │    GC.heap_info / VM.command_line)     target JVM           │
                │        │                                        JFR.start (profile)  │
                │        └─► LiveJfr ── JFR.dump every interval ─► snapshot .jfr file  │
                │                     parse with jdk.jfr.consumer,                     │
                │                     high-water-mark filter (no event counted twice)  │
                │                              │                                       │
                │                              ▼                                       │
                │                     Metrics (GC, allocation, CPU samples)            │
                │                              │                                       │
                │   OsCollector SPI ──► Linux: /proc/stat                              │
                │                     Windows: JDK MXBean (approximation, M1)          │
                │                              │                                       │
                │                              ▼                                       │
                │                     Console status block every interval              │
                │                     (embedded web UI from Milestone 2, same model)   │
                └──────────────────────────────────────────────────────────────────────┘
```

Why Attach + JFR instead of JMX/RMI or agents:

- The Attach API uses a **local** mechanism (Unix socket file / Windows named pipe) —
  no TCP, no ports, no changes to the target's launch flags in the common case.
- JFR is built into every HotSpot JVM ≥ 11, has ~1% overhead with profile settings,
  and its event model is **identical on Linux and Windows** — the JVM seam gives us
  platform parity for free.
- `jdk.jfr.consumer` parses recordings with JDK classes only: zero dependencies.

## Metric → source map

| Metric (meaning) | JVM source (both OSes) | Kernel source — Linux | Kernel source — Windows |
|---|---|---|---|
| CPU flame graph | JFR `jdk.ExecutionSample` stacks | eBPF/perf stacks (later) | ETW CPU sampled profile (later) |
| CPU stats per core | — | `/proc/stat` (M1) → eBPF | performance counters; MXBean system load (M1 approx.) |
| Thread distribution & state | JFR thread events + attach thread dump | `/proc/<pid>/task` | JDK ThreadMXBean / ETW |
| Thread core occupancy, CPU time | — | `/proc/<pid>/task/<tid>/stat` | ETW CSwitch (processor at switch) |
| On-CPU / off-CPU time, scheduling gap ("time between two CPU times") | JFR park/monitor events (approx.) | eBPF `sched_switch`/`sched_wakeup` | ETW CSwitch + wait reasons |
| Off-CPU flame graph | — | eBPF stacks captured at deschedule | ETW stacks at CSwitch |
| Allocation rate | JFR `jdk.ObjectAllocationSample` weight; TLAB events; GCHeapSummary-delta estimate (fallback chain, source always printed) | — | — |
| Young GC count / frequency / time between | JFR `jdk.YoungGarbageCollection` | — | — |
| Old GC count / frequency / time between | JFR `jdk.OldGarbageCollection` | — | — |
| GC pause total / max | JFR `jdk.GarbageCollection` (`sumOfPauses`, `longestPause`), deduped by gcId | — | — |
| Promotion rate | JFR GC heap summaries (old-gen growth per young GC) | — | — |
| Regions (G1) | JFR G1 heap summary events | — | — |
| Humongous allocations | JFR allocation events ≥ ½ region + G1 humongous region growth | — | — |
| Heap dump / thread dump artefacts | jcmd `GC.heap_dump` / `Thread.print` (offline mode) | — | — |
| GC log analysis | Unified GC log parser (offline mode) | — | — |
| IO ops done by app, IO latency | — | eBPF block/file tracepoints by PID | ETW FileIO / DiskIO providers |
| Syscalls made by app, syscall latency | — | eBPF raw syscall tracepoints (names available) | **no per-name source — see parity contract** |
| TCP life for the app (connections, duration, bytes, retransmits) | — | eBPF TCP state tracepoint (`tcplife`-style) | ETW TCPIP provider; eBPF-for-Windows (network hooks) |
| Memory bandwidth | — | perf / Intel PCM (system level) | performance counters (system level) |

## Platform parity

The product promise is **no functionality difference between Linux and Windows**:
same metrics, same names, same UI. The parity contract for the kernel layer:

- **Linux fulfils kernel metrics via eBPF tracepoints.** Bonus detail available
  there: syscalls broken down **by name** (entry/exit pairing gives count + latency
  per syscall).
- **Windows fulfils kernel metrics via ETW category providers**: FileIO/DiskIO
  (IO ops + latency), TCPIP (connection life), Registry, CSwitch (thread switches
  **with wait reasons**), and page-fault events.
- **The one metric with no real Windows source is per-syscall-name count/latency.**
  Windows has no supported generic syscall tracing facility, and eBPF-for-Windows
  hooks are networking/process oriented. Therefore the cross-platform feature is
  defined as **"kernel operations by category, with latency"** — file, disk,
  network, registry, page faults — and that identical feature ships on both OSes.
  Per-syscall-name breakdown is a Linux-only *extra detail* inside the same panel,
  not a separate feature.
- **Off-CPU on Windows is feasible**: ETW CSwitch events carry wait reasons, and
  stacks can be captured at switch time — enough for off-CPU time and flame graphs.
- **Memory bandwidth is system-level on both platforms** (perf/PCM on Linux,
  hardware performance counters on Windows); per-process memory bandwidth is not
  claimed on either.

Parity strategy per layer:

| Layer | Linux | Windows | Parity mechanism |
|---|---|---|---|
| JVM observation | Attach API + JFR | Attach API + JFR | Identical — HotSpot attach and JFR are cross-platform by design |
| OS collector (M1) | `/proc/stat` | JDK `OperatingSystemMXBean` (system load) | Same `OsCollector` SPI, same metric model |
| OS collector (later) | eBPF (libbpf CO-RE sidecars) | ETW (+ eBPF-for-Windows for network) | Same SPI + metric meanings; category-level kernel ops |
| Output | Console status blocks → embedded web UI | Same | One renderer over the shared metric model |

Rules that protect parity:

1. No Linux-only constructs (paths, `/proc`, signals) outside the Linux collector.
2. A metric a platform cannot produce is reported as `n/a`, never fabricated.
3. New metrics are added to the meaning table first, then fulfilled per platform.

## Known limitations (Milestone 1)

- Allocation figures are **sampled estimates** from JFR. The allocation sampler's
  throttle adapts just after recording start and briefly emits inflated weights;
  Appraan polls once in a warm-up mode (allocation accounting suspended) so the
  reported rates/totals are steady-state values.
- Snapshot parsing re-reads the recording from its start each interval and filters
  by a timestamp high-water mark (GC events are additionally deduped exactly).
  Correct and simple; incremental chunk reading is a later optimisation.
- Per-core CPU on Windows is currently the MXBean whole-system figure (one value),
  labelled as an approximation in code and output.
- The demo workload produces young GCs only, so old-GC metrics showed `old=0 /
  never` in the proof run; the code paths are the same event pipeline.
