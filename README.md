# Appraan

**The life of your application, live.** Appraan attaches to a running JVM by PID and
shows, in real time: GC behaviour (young/old counts, pauses, time since last GC),
allocation rate, CPU hot methods from JFR execution samples, and per-core CPU —
with kernel-level observation (IO, syscalls, TCP life) arriving behind the same
metric model in later milestones. Identical on Linux and Windows; one jar, zero
dependencies.

See [DESIGN.md](DESIGN.md) for the architecture, the metric → source map and the
platform-parity contract. See [STATUS.md](STATUS.md) for milestone progress.

## Build (JDK 21)

Linux / macOS:

```bash
./build.sh          # produces dist/appraan.jar
```

Windows:

```bat
build.bat           :: produces dist\appraan.jar
```

Plain `javac` + `jar` only — no Maven/Gradle needed. Set `JAVA_HOME` if `javac`
is not on your `PATH`.

## Run

```bash
java --add-modules jdk.attach \
     --add-exports jdk.attach/sun.tools.attach=ALL-UNNAMED \
     -jar dist/appraan.jar <PID> [--seconds N] [--interval S]
```

(Windows: same command in `cmd`/PowerShell, with `dist\appraan.jar`.)

- `<PID>` — the target JVM's process id. The target must be a HotSpot JVM
  (OpenJDK/Temurin/Oracle JDK) running as the same user, without
  `-XX:+DisableAttachMechanism`.
- `--seconds N` — total observation time (default 30).
- `--interval S` — status print interval (default 5).
- The `--add-exports` flag is needed because `executeJCmd` lives in the
  non-exported `sun.tools.attach` package of the `jdk.attach` module.

No TCP/JMX is used: discovery and JFR control go through the local Attach API,
and JFR snapshots are parsed from local files.

## Try it against the bundled demo app

```bash
# terminal 1 — a JVM with a CPU burner, an allocator, a sleeper and lock contention
java -Xmx256m -XX:+UseG1GC -cp dist/appraan.jar dev.appraan.demo.LoadApp
#   prints: LoadApp started, pid=<PID>

# terminal 2
java --add-modules jdk.attach --add-exports jdk.attach/sun.tools.attach=ALL-UNNAMED \
     -jar dist/appraan.jar <PID> --seconds 30 --interval 5
```

Sample output (from `proof/run1.log`, Temurin 21, G1, 256 MB heap):

```
=== Appraan: observing PID 8806 ===
Target JVM
  pid:            8806
  version:        OpenJDK 64-Bit Server VM version 21.0.13+9-LTS
  GC collector:   G1
  flag UseG1GC = true
  flag InitialHeapSize = 124 MiB (130023424)
  flag MaxHeapSize = 256 MiB (268435456)
  ...
--- t=+5s ------------------------------------------------------
GC:    young=16 (+16)  old=0  |  pause total=18ms  max=1ms  |  last GC young: 258ms ago, old: never
Alloc: rate=156.1 MiB/s  total=794.7 MiB  |  heap used (latest JFR)=7.8 MiB  |  source: jdk.ObjectAllocationSample weight (2204 sampled events)
CPU:   689 execution samples total (+689 this window)  top leaf methods:
           482  dev.appraan.demo.LoadApp.isPrime
Cores: [0]=6% [1]=6% [2]=5% [3]=5%
```

## Milestone 1 scope

Working: PID attach + target discovery, live JFR pipeline (GC counts/pauses,
allocation rate with a documented fallback chain, CPU sample top methods),
per-core CPU on Linux via the OS-collector SPI, console status output, demo app.

Stubbed / later: embedded web UI and flame-graph rendering, Windows per-core CPU
beyond the MXBean approximation, eBPF (Linux) and ETW / eBPF-for-Windows
(Windows) kernel collectors, heap/thread dump and GC-log offline analysis,
promotion rate and G1 region/humongous reporting.
