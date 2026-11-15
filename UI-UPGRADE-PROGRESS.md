# UI Upgrade — Progress

User request (2026-11-15): (1) modern look and feel; (2) ONE dashboard page
for a PID — JVM summary, GC incl. G1, CPU/cores, thread stacks, flame graph
INLINE (click-to-zoom + Reset), kernel panel INLINE. No navigation to
/flame or /kernel needed to see values.

## Plan

- [x] **Scoped** — read Pages.java, FlameGraph.java, UiServer.java,
      Snapshot.java, JsonApi.java, RenderProof.java, build/test scripts.
      Findings:
      - `/` is a static JS shell (`Pages.dashboardHtml()`) polling
        /api/summary|gc|cpu|cores|kernel every 1 s. No flame, no stacks
        section today; kernel panel is a reduced subset of /kernel.
      - `/flame` and `/kernel` are server-rendered from Snapshot /
        KernelFeed.View. FlameGraph.svg() is a pure function — reusable
        inline as-is (zoom via g.frame data-* + a small script).
      - All KPI data exists in current JSON: alloc rate + heap used
        (/api/summary), heap max = flags.MaxHeapSize (raw bytes string),
        young/old counts (/api/gc), CPU = average of per-core busy
        (/api/cores — no per-process CPU exists in the model; KPI will be
        labelled "CPU (avg across cores)", honestly).
      - /api/stacks already serves collapsed stacks — dashboard just
        never fetched it. /api/kernel already carries kinds, latOps,
        diagnostics and off-CPU threads — dashboard just didn't render
        them. => NO JsonApi / endpoint changes needed at all.
      - No test asserts dashboard/flame/kernel markup (only RenderProof
        calls Pages.dashboardHtml() — signature change touches it).
- [x] **Implemented** — Pages.java rework (done 2026-11-15):
      - Shared modern CSS for all three pages: light slate theme, white
        cards (radius 12, subtle shadow), dark slate header band with
        Appraan name + PID + LIVE/STALE badge pill, KPI card row.
      - dashboardHtml(Snapshot): header band; KPI row (alloc rate,
        heap used/max, young GC, old GC, CPU avg-across-cores); cards:
        Target, GC (incl. G1), Allocation, CPU top leaf, Per-core CPU,
        Thread stacks (NEW, /api/stacks), Flame graph (NEW wide card,
        server-rendered inline SVG + shared zoom script + 15 s
        fetch-swap refresh from /flame with fallback), Kernel (wide
        card, extended to /kernel parity: kinds, latOps, diagnostics,
        off-CPU threads, unmatched disconnects; units in words).
      - LIVE/STALE cadence logic (747698f) and 1 s polling copied
        verbatim. Kernel hint/empty/stale behaviour unchanged.
      - flamePage/kernelPage restyled with the same CSS; routes,
        content and auto-reload kept. Zoom script factored into one
        shared snippet (class-scoped, rebindable after SVG swap).
      - UiServer: `/` passes snapshots.get() to dashboardHtml.
      - RenderProof: updated call + light dashboard assertions.
- [x] **Tests green** — ./build.sh + ./run-tests.sh with JDK 24:
      **169/169 checks passed**, identical to baseline (KernelCryptoTest
      19, KernelDataTest 45, KernelFeedTest 26, G1StatsTest 26,
      StacksTest 16, FlameGraphTest 10, JsonTest 20, KernelIngestTest 7).
- [x] **Proof done** — proof/run5.log: live LoadApp (PID 4696, JDK 24)
      + Appraan --seconds 0 --kernel-file proof/run5/kernel-fixture.json
      (copy of the M3b fixture; 5 s touch loop emulated the agent's
      --out rewrites, feed stayed LIVE). curl: /, /flame, /kernel and
      all six /api/* routes 200; 28/28 dashboard inline assertions PASS
      (inline flame SVG = 54 frames / 1,706 samples / isPrime leaf;
      kernel card structure + units-in-words headings; cadence STALE
      logic present). NOT verified: in-browser interactive click-zoom
      (no browser in this VM) — same caveat as earlier milestones.
- [x] **Docs** — STATUS.md new "UI UPGRADE" entry at the top; README
      "Web UI" section rewritten for the single-page dashboard.
- [ ] **Pushed** — commit + push origin main; record SHA here.

## Hard constraints honoured

Zero dependencies / fully offline (inline CSS+JS only, system fonts);
all JSON endpoints byte-unchanged; /flame + /kernel routes kept;
747698f LIVE/STALE logic + 1 s polling untouched; kernel empty-state
guidance + ~15 s kernel stale untouched; units spelled in words.

## Status log

- 2026-11-15: scoped (see above). Implementation next.
