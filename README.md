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
     -jar dist/appraan.jar <PID> [--seconds N] [--interval S] [--port P] [--no-ui]
```

(Windows: same command in `cmd`/PowerShell, with `dist\appraan.jar`.)

- `<PID>` — the target JVM's process id. The target must be a HotSpot JVM
  (OpenJDK/Temurin/Oracle JDK) running as the same user, without
  `-XX:+DisableAttachMechanism`.
- `--seconds N` — total observation time (default 30).
  **`--seconds 0` = UI mode: run indefinitely until Ctrl+C**, with a
  clean shutdown (JFR recording and web UI stopped, final summary
  printed).
- `--interval S` — status print interval (default 5).
- `--port P` — loopback port for the embedded web UI (default 7676;
  0 = pick a free port). Ignored with `--no-ui`.
- `--no-ui` — disable the embedded web UI (console output only).
- `--kernel-file PATH` — poll an appraanagent `--out` file for kernel
  snapshots (fallback feed; the agent's push feed needs no option and
  takes precedence when it arrives).
- `--kernel-key-file PATH`, `--https-keystore PATH`,
  `--https-keystore-password PW|env` — feed/UI security modes; see
  "Kernel feed transport & security configuration" below.
- The `--add-exports` flag is needed because `executeJCmd` lives in the
  non-exported `sun.tools.attach` package of the `jdk.attach` module.

No TCP/JMX is used to talk to the target: discovery and JFR control go through
the local Attach API, and JFR snapshots are parsed from local files. The only
TCP listener Appraan opens is its own web UI, bound to loopback.

## Web UI

While Appraan runs, open **http://127.0.0.1:7676/** in a browser:

- `/` — a single-page dashboard (self-contained: no external assets) with the
  target's discovery summary, live GC counts and pauses, allocation rate,
  CPU top leaf methods, per-core CPU, and the **Kernel** panel (below).
  It polls every 1 s and shows a **LIVE / STALE** indicator with the
  last-update time.
- `/flame` — a server-rendered CPU **flame graph** (SVG; width ∝ sample
  share, hover a frame for counts) built from the same JFR execution
  samples, with the top-leaf table beside it. **Click a frame to zoom**
  into it; "Reset zoom" restores the full graph.
- `/kernel` — the dedicated **Kernel** page: kernel operations by
  category with latency + histograms, TCP connection summary and recent
  connections, off-CPU summary with wait reasons and top threads, from
  the appraanagent feed (Windows) — plus the Linux /proc process-I/O
  parity counters where that is what the platform offers.
- JSON API backing the pages, for scripting: `/api/summary`, `/api/gc`,
  `/api/cpu`, `/api/cores`, `/api/stacks` (collapsed stacks),
  `/api/kernel` (freshest kernel snapshot + source/age/staleness +
  guidance hint).
- `POST /api/kernel/ingest` — the appraanagent push endpoint (see
  "Kernel feed" below).

The UI renders immutable snapshots of the same metric model the console
prints — after every poll the console block and the UI publish identical
numbers (verified in `proof/run2.log`). The UI binds to the loopback
interface only: it exposes live internals of the observed process, so it
is deliberately not reachable from other machines. If the port is taken,
Appraan warns and continues with console output only.

## Kernel feed (Windows: appraanagent)

On Windows, kernel metrics — operations by category with latency, TCP
connection life, off-CPU time with wait reasons — come from
**appraanagent**, the elevated sidecar in the
[appraan-windows](https://github.com/Yavdhesh/appraan-windows) repo (one
ETW session hosting all three trackers; Windows allows only one kernel
logger session, and Appraan itself never needs elevation). The agent
pushes one JSON snapshot per interval to
`POST /api/kernel/ingest`; a `--out` file feed (Appraan side:
`--kernel-file <path>`) is the fallback/recorder. The Kernel panel and
`/kernel` page show the snapshot's age and mark it **stale** after
15 s; with no feed on Windows they show the exact elevated agent
command for the observed PID. The three-window live test (target JVM /
elevated agent / Appraan) is in appraan-windows' RUNBOOK,
"appraanagent — the Appraan sidecar".

On Linux, until the eBPF collector lands, the same panel shows the
target's per-process I/O from `/proc/<pid>/io` (syscalls + bytes,
**cumulative since process start** — labelled as such; the semantics
differ from the ETW snapshot windows).

Trust note: the ingest endpoint is loopback-only — the same trust
boundary as the UI itself. By default, any local process could post a
snapshot; that is acceptable for a local developer tool and is stated
plainly. If it is not acceptable in your environment, use one of the
modes below.

## Kernel feed transport & security configuration

**Default — plain JSON over HTTP, no encryption.** With no
transport/security flags anywhere, appraanagent POSTs the literal JSON
snapshot (`Content-Type: application/json`, no `Content-Encoding`, no
envelope) to Appraan's plain-HTTP UI server, and Appraan accepts it.
Everything in this section beyond the default is **opt-in**, per flag.
The agent's `--out` file is plain JSON in *every* mode (it is a local
recording, protected by the filesystem, not by a wire format). Both
programs print their effective mode at startup (e.g.
`Kernel feed security: plain JSON over HTTP (no encryption)`), and
never print key material or passwords.

### Complete flag table

| Side | Flag / config | Argument | Default | Effect | Must pair with |
|---|---|---|---|---|---|
| Appraan | `--port` | port number | `7676` | Loopback port of the UI + ingest server (HTTP or HTTPS) | The agent's `--push` URL must use this same port |
| appraanagent | `--push` | URL | — (or use `--out`) | POST each snapshot to Appraan's ingest endpoint. `http://…` = plain HTTP; `https://…` = TLS | A running Appraan UI on that port |
| appraanagent | `--gzip` | — | off | Gzip-compress the push body at maximum (smallest-size) compression; sets `Content-Encoding: gzip`. Appraan accepts gzipped bodies with no flag of its own | Nothing — independent opt-in |
| appraanagent | `--gen-key` | `<path>` | — | Standalone: write a fresh shared key file and exit (no PID/session needed) | Then distribute the file to both sides (Option B) |
| appraanagent | `--key-file` | `<path>` | off | Seal every push in an AES-256-GCM envelope (Option B) | Appraan `--kernel-key-file` with the **same** key file |
| Appraan | `--kernel-key-file` | `<path>` | off | Ingest becomes **fail-closed**: only valid envelopes are accepted; plain/gzipped posts get HTTP 400 | Agent `--key-file` with the **same** key file |
| Appraan | `--https-keystore` | `<path>` (PKCS12) | off | The **whole UI + ingest** runs HTTPS on `--port` (Option A) | `--https-keystore-password`; agent pushes to the `https://` URL |
| Appraan | `--https-keystore-password` | `<password>` or `env` | — | Keystore password. The literal value `env` reads it from the environment variable below instead of the command line | `--https-keystore` |
| Appraan | `APPRAAN_HTTPS_PASSWORD` (env var) | the password | unset | Where the keystore password lives when the flag value is `env` — keeps it out of process lists and shell history | `--https-keystore-password env` |
| appraanagent | `--tls-cert` | `<path>` (.cer, DER or PEM) | system trust | For `https://` pushes: trust **exactly** this server certificate (raw-byte pinning), nothing else | Appraan `--https-keystore` whose certificate this is |

### 1. Default mode — plain JSON / HTTP / no encryption

The baseline. Nothing to generate, nothing to copy.

```powershell
# Window B (elevated) — the agent, default transport
dotnet run -c Release --project .\src\appraanagent -- <PID> --push http://127.0.0.1:7676/api/kernel/ingest --interval 2
```

```bash
# Window C — Appraan, default transport
java --add-modules jdk.attach --add-exports jdk.attach/sun.tools.attach=ALL-UNNAMED \
     -jar dist/appraan.jar <PID> --seconds 0
```

On the wire: the raw JSON document, `Content-Type: application/json`.
Startup confirms it: the agent prints
`kernel feed: plain JSON over HTTP (no encryption)` and Appraan prints
the same for its ingest side.

### 2. Optional gzip mode

Identical to the default, plus `--gzip` on the agent. Appraan needs no
flag — it always accepts gzipped bodies (and plain ones).

```powershell
dotnet run -c Release --project .\src\appraanagent -- <PID> --push http://127.0.0.1:7676/api/kernel/ingest --interval 2 --gzip
```

On the wire: the JSON gzip-compressed at maximum compression,
`Content-Encoding: gzip`. Worth it when snapshots grow or the feed
crosses a real network on the way to a future central collector;
irrelevant cost/benefit on loopback at ~2 s intervals. Gzip also
composes *inside* the Option B envelope (the envelope records, in an
authenticated flags byte, whether its payload was gzipped).

### Option A: HTTPS (TLS)

**What it protects.** Everything in transit — the whole web UI *and*
the kernel feed — plus server identity: the agent (and your browser)
can be sure they are talking to *your* Appraan, not another local
process that grabbed the port. Choose it when more than one user shares
the machine, when policy wants TLS everywhere, or as groundwork for a
non-loopback collector. It does not, by itself, authenticate the
*agent* to Appraan (any local process that trusts the cert can still
POST) — that is Option B's job; the two combine.

**Step by step.**

```bash
# 1. Create the PKCS12 keystore with a localhost certificate
#    (CN=localhost, SAN dns:localhost + ip:127.0.0.1). keytool is in the JDK.
keytool -genkeypair -alias appraan -keyalg RSA -keysize 2048 -validity 365 \
  -keystore appraan.p12 -storetype PKCS12 -dname "CN=localhost" \
  -ext "SAN=dns:localhost,ip:127.0.0.1"

# 2. Export the public certificate (PEM form — browsers/curl need PEM;
#    the agent accepts DER or PEM)
keytool -exportcert -alias appraan -keystore appraan.p12 -rfc -file appraan.cer
```

**Where each file belongs.**

| Artifact | Created by | Lives on | Secret? |
|---|---|---|---|
| `appraan.p12` (keystore, holds the **private key**) | `keytool -genkeypair` | The **Appraan machine only** | **Yes** — never copy it to the agent machine |
| Keystore password | You, at keytool time | Appraan's start command or its `APPRAAN_HTTPS_PASSWORD` env var only | **Yes** |
| `appraan.cer` (public certificate) | `keytool -exportcert` | Copied to the **appraanagent machine** | **No** — safe to distribute freely |

**Flags.** Appraan: `--https-keystore appraan.p12
--https-keystore-password <pw>` — or `--https-keystore-password env`
with `APPRAAN_HTTPS_PASSWORD` set, so the password stays out of the
process list. Agent: push to the `https://` URL and add
`--tls-cert appraan.cer` to pin this self-signed certificate. If you
omit `--tls-cert`, the agent uses the operating system's trust store
instead (right for a CA-issued certificate; a self-signed one will be
rejected — that rejection is the pinning working as intended).

**Worked example (both sides).**

```bash
# Window C — Appraan, whole UI on HTTPS
APPRAAN_HTTPS_PASSWORD=<pw> java --add-modules jdk.attach \
  --add-exports jdk.attach/sun.tools.attach=ALL-UNNAMED \
  -jar dist/appraan.jar <PID> --seconds 0 \
  --https-keystore appraan.p12 --https-keystore-password env
```

```powershell
# Window B (elevated) — the agent, TLS push with the pinned certificate
dotnet run -c Release --project .\src\appraanagent -- <PID> --push https://127.0.0.1:7676/api/kernel/ingest --interval 2 --tls-cert .\appraan.cer
```

**Verify it works.** Appraan's startup prints
`Web UI security: HTTPS (TLS), keystore appraan.p12` and an
`https://127.0.0.1:7676/` address; the browser loads
`https://127.0.0.1:7676/` (expect a warning unless the cert is in the
browser's trust store — it is self-signed); plain `http://` to the same
port fails. In TLS mode the Kernel panel's suggested agent command
still shows the `http://` form — switch it to `https://` and add
`--tls-cert` as above.

### Option B: Shared-key encryption (AES-256-GCM)

**What it protects.** The kernel feed itself: Appraan accepts *only*
snapshots sealed with the shared key, and the content is encrypted and
tamper-evident in transit. Choose it when other users or processes on
the machine must not be able to forge or read kernel snapshots. It
protects the feed only — the UI pages stay plain HTTP unless you also
use Option A.

**Step by step — generate the key.**

```powershell
# Any shell in the appraan-windows checkout; creates the key and exits
dotnet run -c Release --project .\src\appraanagent -- --gen-key appraan.key
```

The file is **one line: base64 of 32 random bytes** (a 256-bit key).
Treat it like a password file.

**Where the key belongs — read this carefully.** There is exactly
**one** key and it is **symmetric**: the *same* `appraan.key` file must
exist on **both** sides — Appraan reads its copy via
`--kernel-key-file`, appraanagent reads the identical copy via
`--key-file`. It is a shared **secret on both machines** (contrast
Option A, where only the public `.cer` travels). Practicalities:

- Copy it over a channel you trust (it's a secret in transit, once).
- Permissions: readable only by the user/service account that runs
  each side (e.g. `chmod 600` / NTFS ACL to the single account).
- **Rotation:** generate a new file, replace it on both sides, restart
  both sides. There is no overlap window — during rotation, mismatched
  sides simply reject pushes (400s in Appraan, failure-streak warnings
  in the agent) until both match again.

**Flags + behaviour.** Agent `--key-file appraan.key` seals every push
as `application/octet-stream`: `[version][flags][nonce][ciphertext +
GCM tag]`, the header authenticated as GCM AAD. Appraan
`--kernel-key-file appraan.key` turns ingest **fail-closed**: plain or
merely-gzipped posts are rejected with HTTP 400; only a valid envelope
is accepted. If Appraan has *no* key configured, an envelope post is
rejected too (the mode must match on both sides).

**Worked example (both sides).**

```powershell
# Window B (elevated) — the agent, sealing every push
dotnet run -c Release --project .\src\appraanagent -- <PID> --push http://127.0.0.1:7676/api/kernel/ingest --interval 2 --key-file .\appraan.key
```

```bash
# Window C — Appraan, fail-closed ingest
java --add-modules jdk.attach --add-exports jdk.attach/sun.tools.attach=ALL-UNNAMED \
     -jar dist/appraan.jar <PID> --seconds 0 --kernel-key-file appraan.key
```

**Verify it works.** Appraan's startup prints
`Kernel feed security: AES-256-GCM shared key from appraan.key —
fail-closed, envelopes only`, and the agent prints
`kernel feed: AES-256-GCM encrypted envelope (plain JSON inside)`.
A plain `curl` POST to the ingest endpoint is rejected (400) while
the agent's pushes succeed and the Kernel panel fills in.

### Which should I use? — behaviour matrix

| Mode | Flags | On the wire | Forged posts possible? | UI protected? |
|---|---|---|---|---|
| Plain (default) | — | Raw JSON over HTTP | Yes, by any local process (loopback trust boundary) | No |
| Gzip | agent `--gzip` | Gzipped JSON over HTTP | Yes (compression is not security) | No |
| Shared key | `--key-file` + `--kernel-key-file` | AES-256-GCM envelope over HTTP | No — fail-closed, tag-verified | No |
| TLS | `--https-keystore` (+ agent `--tls-cert`) | JSON (or gzipped JSON) over HTTPS | Only by processes that trust the cert | Yes |
| Key + TLS | all of the above | AES-256-GCM envelope over HTTPS | No | Yes |

Rule of thumb: default plain mode for a single-user dev box; add the
shared key when the machine is shared; add TLS when you want the UI
itself protected in transit or are preparing a networked collector;
combine both when each threat applies.

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

Since Milestone 3a, each block also carries a `G1:` line — promotion
estimate, G1 region split and humongous usage (sample from
`proof/run3/appraan-promote.log`, `LoadApp promote` mode):

```
GC:    young=53 (+53)  old=5  |  pause total=117ms  max=12ms  |  last GC young: 182ms ago, old: 586ms ago
G1:    eden=0 B  survivor=5.0 MiB  old=39.3 MiB  regions=134  |  promotion(est.) 122.4 MiB total (+122.4 MiB this window, 53 young GCs)  |  humongous: 0 regions, 0 B used
```

## Scope so far

Milestone 1 — working: PID attach + target discovery, live JFR pipeline
(GC counts/pauses, allocation rate with a documented fallback chain, CPU
sample top methods), per-core CPU on Linux via the OS-collector SPI,
console status output, demo app.

Milestone 2 — working: the embedded web UI described above (dashboard,
JSON API, CPU flame graph), fed by collapsed stacks collected alongside
the M1 leaf counts. Manual tests: `./run-tests.sh` (stack collapsing,
JSON escaping, flame-graph geometry — plain Java, no framework).
Proof: `proof/run2.log` + `proof/run2/` artefacts.

Milestone 3a — working: G1 promotion estimate (old-gen growth during young
GCs, from `G1HeapSummary`), G1 eden/survivor/old + region census with
humongous-region reporting (`G1HeapRegionInformation`), in the console
`G1:` line, `/api/gc` and the dashboard GC card; non-G1 targets report
`n/a`. `LoadApp promote` demo mode forces promotion and old GCs.
Proof: `proof/run3/` artefacts (first old-GC proof: old=40 in 30 s).

Milestone 3b — working: kernel collector integration. The appraanagent
sidecar (appraan-windows) feeds Appraan kernel snapshots by push
(gzip; optional AES-256-GCM envelope or TLS) or file; console `Kernel:`
block, dashboard Kernel panel, `/kernel` page, stale/hint states,
`--seconds 0` UI mode with graceful Ctrl+C shutdown, flame-graph zoom,
Linux /proc process-I/O parity subset. Proof: `proof/run4.log` +
`proof/run4/` artefacts (fixture built from real verified Windows runs;
the live agent→Appraan loop is verified on the user's Windows machine).

Next milestone: central collector mode — Appraan without a local
target, agents pushing from docker/k8s pods, one page per PID merging
JVM + kernel data (see DESIGN.md, "Future: central collector mode").
The snapshot contract already carries the `host`/`labels` fields it
needs.

Stubbed / later: Windows per-core CPU beyond the MXBean approximation,
eBPF (Linux) kernel collector behind the same kernel model,
heap/thread dump and GC-log offline analysis.
