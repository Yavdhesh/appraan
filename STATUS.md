# Appraan — Status

Milestone 2 (in progress): embedded web UI (dashboard + JSON API + CPU flame graph)
over the same metric model. Checkpoint log:
- [x] M2 started; DESIGN.md mapping: UI renders the shared metric model
      (Metrics/TargetInfo/OsCollector); DESIGN names no UI flags, so `--port`
      (default 7676) and `--no-ui` are added as additive CLI options.
- [x] Collapsed-stack collection in Metrics (Stacks helper, pure + unit-tested).
- [ ] UI package: JSON API, dashboard page, flame graph SVG, UiServer.
- [ ] CLI wiring (--port / --no-ui), build, tests.
- [ ] Proof run 2 (proof/run2.log + snapshot artefacts), README/DESIGN updates.

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

## Remaining (future milestones, not M1)
- [ ] Embedded web UI + flame-graph rendering over the same metric model.
- [ ] Promotion rate, G1 region/humongous reporting; old-GC proof with a
      promotion-heavy demo mode.
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
