# Appraan — Status

Milestone 3a: COMPLETE — G1 promotion estimate, region census with humongous
reporting, old-GC proof via a promotion-heavy demo mode, and graceful
target-exit handling. Milestone 2 (embedded web UI) remains as below.
- [x] `dev.appraan.metrics.G1Stats` (pure, unit-tested): promotion estimate
      from `jdk.G1HeapSummary` old-gen growth during young GCs only;
      eden/survivor/old + region count from the latest After-GC summary;
      region census from `jdk.G1HeapRegionInformation` (enabled explicitly
      in the JFR recording), humongous = Starts/Continues Humongous regions.
      All state keyed by gcId / region index, so re-fed dump events cannot
      double count (tested).
- [x] Wiring: Metrics folds the new events in; console prints a `G1:` line
      (total + per-window promotion); `/api/gc` gains `promotion` + `g1`
      (incl. per-type census and `regionCensusAt`); dashboard GC card shows
      promotion, G1 split and humongous. Non-G1 targets report `n/a`.
- [x] `LoadApp promote` mode: retains a trickle of medium objects to force
      promotion and old GCs (default demo behaviour unchanged).
- [x] Defect found by proof and fixed: a target exiting mid-observation
      crashed Appraan with a bare `FileNotFoundException` (attach socket
      gone). It now ends early with a clear message and exit 0.
- [x] Tests: 72 plain-Java checks passing (46 existing + 26 G1StatsTest).
- [x] PROOF RUN 3 in `proof/run3/` (Temurin 21.0.13, G1, 256 MB heap):
      - vs a promotion/humongous workload (30 s, `appraan3.log`): young
        116, **old 40** (first old-GC proof; M1/M2 only ever showed old=0),
        promotion(est.) 43.3 → 194.7 MiB over 116 young GCs (~+30 MiB/5 s
        window), humongous census 27–78 regions tracking the workload.
      - vs shipped `LoadApp promote` (25 s, `appraan-promote.log`): young
        177, old 20, promotion 122.4 → 442.5 MiB; humongous correctly 0
        (16 KiB objects). Live `/api/gc` matched the console block of its
        snapshot exactly (promotion 217,401,888 B = 207.3 MiB, old
        44.4 MiB, 156 regions, census sums to the region count).
      - Humongous cross-check (live JSON at ~14 s): 9 Starts + 18 Continues
        = 27 regions, 27.0 MiB used — exactly the nine 2 MiB arrays
        (3 × 1 MiB regions each) the workload had retained at that moment.
      - Target-exit run (`appraan4.log`): 12 s target, 30 s observation —
        Appraan ended early with a clear message, exit 0.

Milestone 2: COMPLETE — embedded web UI (dashboard + JSON API + CPU flame graph)
over the same metric model.
- [x] M2 started; DESIGN.md mapping: UI renders the shared metric model
      (Metrics/TargetInfo/OsCollector); DESIGN names no UI flags, so `--port`
      (default 7676) and `--no-ui` are added as additive CLI options.
- [x] Collapsed-stack collection in Metrics (Stacks helper, pure + unit-tested).
- [x] UI package: JSON API, dashboard page, flame graph SVG, UiServer.
- [x] CLI wiring (--port / --no-ui), build, tests.
- [x] Proof run 2 (proof/run2.log + snapshot artefacts), README/DESIGN updates.

## Milestone 2 — Done
- [x] `dev.appraan.ui`: Snapshot (immutable per-poll capture), Json/JsonApi
      (zero-dependency JSON, RFC 8259 escaping; endpoints /api/summary, /api/gc,
      /api/cpu, /api/cores, /api/stacks as pure Snapshot -> String functions),
      FlameGraph (server-side SVG from collapsed stacks, width ∝ samples,
      widestPath helper), Pages (single-file dashboard polling the API;
      server-rendered /flame page), UiServer (JDK HttpServer, loopback only,
      daemon threads; start failure degrades to console-only).
- [x] Metrics also folds ExecutionSample events into collapsed stacks, so the
      flame graph, JSON API and console top-leaf table share one collection.
- [x] Tests (`./run-tests.sh`, plain Java): StacksTest 16, JsonTest 20,
      FlameGraphTest 10 — 46 checks, all passing.
- [x] PROOF RUN 2 in `proof/run2.log` + `proof/run2/`: Appraan vs LoadApp
      (pid 4043, same flags as run 1), 30 s.
      - Live HTTP verified by curl (all 7 routes HTTP 200 mid-run; bodies
        saved with -live suffix). The in-process java.net.http client is
        blocked by this sandbox's network policy (connection intercepted
        before reaching the server) — recorded honestly in the proof log;
        RenderProof covered the renderer path from its own live session.
      - Curl-fetched JSON matched the console block from the same snapshot
        EXACTLY (young=17, pauses 34/3 ms, 728 samples, isPrime=707,
        825.5 MiB total / 161.4 MiB/s, cores 36/33/47/23). Whole run
        mirrors M1: young GCs 17 → 72, allocation ~146–161 MiB/s.
      - Flame graph: 728 samples live; widest root-to-leaf path ends at
        `dev.appraan.demo.LoadApp.isPrime` (707 samples, 97.1%), equal to
        the Metrics top leaf.
      - Defect found + fixed during proof: Snapshot re-read the delta-based
        per-core collector a millisecond after the console, so /api/cores
        showed a micro-window delta. Fix (12e10b0): one per-core read per
        round, shared by console and snapshot; the proof numbers above are
        from the fixed build.

Milestone 1: skeleton, target discovery (Attach API), live JFR pipeline, OS collector SPI, demo target, proof run.

## Done
- [x] Project layout (`src/main/java/dev/appraan/{cli,attach,discover,jfr,metrics,os,demo}`), local git repo.
- [x] build.sh + build.bat → `dist/appraan.jar` (plain javac/jar, zero dependencies).
- [x] Target discovery via Attach API + jcmd: VM.version, VM.flags (GC flags + heap sizes,
      parsed from the `-XX:+Name` / `-XX:Name=value` token format jcmd actually emits),
      GC.heap_info, VM.command_line. Clear errors for missing PID and non-JVM PID.
- [x] Live JFR pipeline: `JFR.start settings=profile disk=true` in target, `JFR.dump`
      snapshot every interval, parsed with `jdk.jfr.consumer`, high-water-mark filtering;
      GC lifecycle events additionally deduped exactly (young/old by start time, pauses by gcId).
- [x] Metrics: young/old GC counts, total/max pause, time since last young/old GC;
      allocation via ObjectAllocationSample weight (fallback chain: TLAB events →
      GCHeapSummary-delta estimate; active source always printed);
      CPU execution samples with top-5 leaf methods.
- [x] OS collector SPI (`OsCollector`) selected by os.name: Linux `/proc/stat` per-core
      busy% working; Windows MXBean system-load approximation (compiles, selectable,
      untested on real Windows — this VM is Linux); unsupported-OS fallback returns n/a.
- [x] Demo target `dev.appraan.demo.LoadApp` (cpu-burner / allocator / sleeper /
      lock-contender-a / lock-contender-b on named threads).
- [x] README.md + DESIGN.md (architecture, metric → source map, platform-parity contract).
- [x] PROOF RUN in `proof/run1.log`: Appraan vs LoadApp (Temurin 21.0.13, -Xmx256m, G1), 30 s.
      - Discovery summary printed with GC=G1 and heap flags (MaxHeapSize 256 MiB etc.).
      - Young GCs counted live: 16 → 72 over the run (~+11 per 5 s window), old=0,
        pause total 85 ms, max 3 ms.
      - CPU: ~500 execution samples/window; top leaf `dev.appraan.demo.LoadApp.isPrime`
        by a wide margin (the burner), as designed.
      - Allocation: steady **~155 MiB/s** in every window, total 4.6 GiB over 30 s —
        cross-checks with GC cadence (~11 young GCs/5 s against a ~50–70 MB eden).
      - Per-core CPU printed each window from the Linux collector.

## Remaining (future milestones)
- [ ] Offline artefact mode: heap dump, thread dump, GC log analysis.
- [ ] Kernel collectors: Linux eBPF sidecars; Windows ETW / eBPF-for-Windows —
      per the parity contract in DESIGN.md ("kernel operations by category, with
      latency" is the cross-platform feature; per-syscall-name detail is Linux-only).

## Notes / blockers encountered
- Sandbox TCP interception was NOT a problem: the Attach API's local socket worked
  between sandbox JVMs exactly as designed; no JMX/RMI used anywhere.
- `--release` cannot be combined with `--add-exports` on system modules, so the
  build uses `-source 21 -target 21`. `executeJCmd` requires
  `--add-exports jdk.attach/sun.tools.attach=ALL-UNNAMED` at build and run time
  (exact commands in README.md).
- Allocation sampling artefact found and fixed: JFR's allocation sampler emits
  inflated `weight` values while its throttle adapts right after recording start
  (first window showed GiB-scale totals). Appraan now does a 2 s warm-up poll with
  allocation accounting suspended; all proof numbers above are post-fix.
- Snapshot dumps re-parse the recording from its start each interval (O(total)
  per poll). Fine at M1 scale; incremental chunk reading is future work.
