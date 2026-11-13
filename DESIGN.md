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

## Milestone 2 — embedded web UI

The console's metric model is rendered by a second output: an embedded web
UI (JDK `HttpServer`, zero dependencies), started alongside the collectors
unless `--no-ui` is given (`--port` sets the loopback port, default 7676).

- **Snapshot model.** After each JFR poll, the CLI captures an immutable
  `Snapshot` of TargetInfo + Metrics + the OS collector. HTTP handlers only
  read snapshots, so a page never observes a half-updated state, and the
  console block and the UI publish identical numbers for a round. Per-core
  CPU is read exactly once per round and shared: the Linux collector is
  delta-based, so a second read would measure a millisecond window instead
  of the round (found by proof run 2; see STATUS.md).
- **Pages.** `/` is a single self-contained HTML file (inline CSS/vanilla
  JS, no external assets) polling the JSON API every 2 s. `/flame` is a
  server-rendered SVG flame graph built from collapsed stacks of the same
  `jdk.ExecutionSample` events as the top-leaf table (one collection, two
  views — they cannot disagree).
- **JSON API.** `/api/summary`, `/api/gc`, `/api/cpu`, `/api/cores`,
  `/api/stacks` — pure functions from `Snapshot` to JSON, so they are
  unit-testable and snapshot-able without a running server.
- **Loopback only.** The UI exposes live internals of the observed process;
  it binds to 127.0.0.1 by design. A UI start failure (port in use) degrades
  to console-only output, never fails the observation.

Why Attach + JFR instead of JMX/RMI or agents:

- The Attach API uses a **local** mechanism (Unix socket file / Windows named pipe) —
  no TCP, no ports, no changes to the target's launch flags in the common case.
- JFR is built into every HotSpot JVM ≥ 11, has ~1% overhead with profile settings,
  and its event model is **identical on Linux and Windows** — the JVM seam gives us
  platform parity for free.
- `jdk.jfr.consumer` parses recordings with JDK classes only: zero dependencies.

## Milestone 3b — kernel collector integration (appraanagent)

The Windows kernel layer is not re-implemented in Java: the verified
appraan-windows trackers (ops by category + latency, TCP connection life,
off-CPU with wait reasons) run in a separate elevated process,
**appraanagent**, and hand Appraan JSON snapshots. Three constraints
shape the design:

- **One ETW session.** Windows allows effectively one NT Kernel Logger
  session, and each standalone tool stops any stale session on start —
  so the integration unit is a *single* sidecar process hosting all
  trackers in one `KernelSession`, not three tools side by side.
- **Elevation decoupling.** ETW needs an elevated process; the Attach API
  wants the target's own user and *fails* across that boundary. The
  agent runs elevated on its own; Appraan never elevates.
- **Zero dependencies, both sides.** The snapshot document is hand-built
  JSON on the .NET side and parsed by a small hand-rolled parser here
  (`dev.appraan.kernel`); the model (`KernelData`) is OS-neutral — the
  parity seam — and only the producer is Windows-specific.

**Handoff.** Primary: the agent POSTs each snapshot to
`POST /api/kernel/ingest` on the UI server (loopback-only, like the
whole UI). Snapshots are cumulative, so a missed push self-heals on the
next success; the agent warns once per failure streak and never stops.
Fallback/recorder: the agent's `--out` file (atomic temp+rename writes)
polled by Appraan via `--kernel-file`. `KernelFeed` keeps the freshest
snapshot across both sources (push wins — it is fresher by
construction), tracks its age, and labels data **stale** after 15 s.
The same feed composes the Kernel panel's guidance states server-side
(no data / waiting-for-file / stale / live), including — on Windows —
the exact elevated `appraanagent` command for the observed PID and this
instance's bound port; on Linux the panel never shows that instruction
and displays the /proc parity data instead.

**Trust note.** The ingest endpoint is loopback-only, the same trust
boundary as the UI itself: by default any local process could post a
snapshot. That is acceptable for a local developer tool, and it is
stated plainly here rather than implied. For anything beyond that, see
the feed security modes below.

**Feed format — decision record.** JSON over HTTP/1.1 on loopback
(`POST /api/kernel/ingest`). Considered: binary encoding and gRPC.
Deferred deliberately: snapshots are a few KB every ~2 s on loopback,
so transit cost is unmeasurable at this scale; gRPC would require
protobuf + an HTTP/2 stack (the JDK's built-in HttpServer is HTTP/1.1
only) in both the agent and the jar, breaking the zero-dependency rule
for no measured gain. The endpoint is content-type based, leaving room
for a binary format later; the documented first step if payloads grow
is gzip (built into both the JDK and .NET stacks). Revisit when
snapshot size × rate makes transit measurable.

*Updates (user decisions, in order):* (1) gzip was adopted — then
refined by the plainer rule that now governs: **the default feed is
literal plain JSON over HTTP** (no compression, no encryption, no
HTTPS), and everything else is an opt-in flag. Gzip survives as the
agent's `--gzip` flag (maximum compression when present; Appraan's
ingest always accepts gzipped bodies for back-compat). (2) Encryption
is available in **both** modes behind flags: a shared-key AES-256-GCM
envelope (agent `--key-file`, Appraan `--kernel-key-file`, fail-closed
when configured; envelope header carries an authenticated flags byte
recording whether the sealed payload was gzipped) and full-UI TLS
(Appraan `--https-keystore`, agent `--tls-cert` pinning). The complete
configuration reference — flag table, grouped Option A (HTTPS) and
Option B (shared-key) walkthroughs with file-placement tables, and the
behaviour matrix — lives in the README, "Kernel feed transport &
security configuration"; it is the single source for these flags.

**Linux parity subset (honest).** Until the eBPF collector lands, Linux
fills what it truthfully can for the target PID from `/proc/<pid>/io`
(syscr, syscw, read_bytes, write_bytes, cancelled_write_bytes), exposed
through the same `OsCollector` SPI into the snapshot model. The
semantics differ from the ETW windows — /proc counters are cumulative
since process start — and every surface (console, panel, /kernel page,
API `basis` field) labels them as such. Per-category ops/latency and
off-CPU wait reasons remain Windows-only until eBPF; the model already
has their shape, so the Linux collector will slot in behind it.

**Pod/collector foresight.** The snapshot contract already carries
optional top-level `host` (the agent fills it with the machine name)
and `labels` (agent `--label k=v`) fields, parsed and displayed today,
so the future central-collector mode needs no contract break.

## Future: central collector mode (NEXT milestone — not built)

Appraan running *without* a local target: a central collector whose
ingest is keyed by (host, pid, labels). Agents push from docker/k8s
pods — an Appraan agent mode for JVM + Linux kernel data,
appraanagent for Windows kernel data. The single-machine loop (this
milestone) is the special case where the collector happens to also
hold the Attach session. Explicitly out of scope here; the
`host`/`labels` contract fields above are this milestone's only
preparation for it.

**Agreed UI spec (user-confirmed 2026-11-13: "tab1-PID1, tab2-PID2"):**

- **Layout.** One Appraan UI with a tab bar. Tab 0 is **Overview**: a
  grid of all known PIDs with headline health per process — allocation
  rate, GC pressure, CPU, kernel ops rate, last-seen. Then **one tab
  per PID**, each tab a single page merging that PID's JVM panels +
  Kernel view + flame graph (the three views this milestone ships as
  separate pages become one page per process).
- **Tab identity.** A PID alone is *not* unique in containers (every
  pod's JVM is often PID 1): the registry key is **(host, pid)** plus
  labels, and the tab label is
  `app/pod label · PID · host`, falling back gracefully when labels
  are absent (host + PID always suffice).
- **Routes.** Each PID tab is a real route (`/pid/<host>/<pid>`) so
  links are shareable; the tab bar is generated from the registry,
  never hard-coded.
- **Lifecycle.** A PID whose feed stops goes **stale** (greyed tab,
  "last seen Xs ago"), is retained for a **tombstone period of
  10 minutes** for post-mortem viewing, then expires from the bar —
  with the expired count noted on Overview.
- **Polling.** The active tab refreshes at the UI's 1 s cadence;
  background tabs refresh only their liveness summaries (cheap
  registry data, not full snapshots).

This is the agreed spec for the next milestone — a pinning of intent,
not a description of the current build.

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
