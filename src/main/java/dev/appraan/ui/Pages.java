package dev.appraan.ui;

import dev.appraan.kernel.KernelData;
import dev.appraan.kernel.KernelFeed;
import dev.appraan.metrics.Metrics;
import dev.appraan.os.ProcessIo;

import java.util.Map;

/**
 * HTML pages. The dashboard ({@code GET /}) is a single self-contained
 * page — inline CSS/JS, no external assets — that shows everything for
 * one PID in one place: KPI headline cards, target/GC/allocation detail,
 * CPU top leaf + per-core CPU, thread stacks, the CPU flame graph
 * rendered inline by {@link FlameGraph} (with click-to-zoom), and the
 * full kernel panel. Live values arrive via the JSON API (1 s polling);
 * the flame graph is server-rendered into the page and refreshed from
 * {@code /flame} every 15 s. The dedicated {@code /flame} and
 * {@code /kernel} pages remain as deep links and share the same styles
 * and components; both are server-rendered from a {@link Snapshot} so
 * they work even with JS disabled.
 */
public final class Pages {

    private Pages() {
    }

    /**
     * The one stylesheet for every page: a light slate theme, white
     * cards with rounded corners and subtle shadows, and a dark slate
     * header band. System font stack only — no webfonts, so the UI is
     * fully offline.
     */
    private static final String CSS = """
              :root {
                --bg:#f1f5f9; --card:#ffffff; --ink:#0f172a; --muted:#64748b;
                --line:#e2e8f0; --line-soft:#eef2f7; --accent:#2563eb;
                --orange:#f97316; --red:#dc2626; --hdr:#0f172a;
              }
              * { box-sizing:border-box; }
              body { background:var(--bg); color:var(--ink); margin:0;
                -webkit-font-smoothing:antialiased;
                font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Helvetica Neue",Arial,sans-serif; }
              header.band { background:var(--hdr); color:#f8fafc; padding:.95rem 1.5rem;
                display:flex; flex-wrap:wrap; align-items:center; gap:.45rem 1.4rem;
                box-shadow:0 1px 4px rgba(15,23,42,.35); }
              header.band .brand { font-size:1.35rem; font-weight:700; letter-spacing:.01em; }
              header.band .tag { color:#94a3b8; font-size:.88rem; }
              header.band .meta { color:#cbd5e1; font-size:.88rem; font-variant-numeric:tabular-nums; }
              header.band a { color:#93c5fd; text-decoration:none; }
              header.band a:hover { text-decoration:underline; }
              header.band .spacer { flex:1; }
              .badge { display:inline-flex; align-items:center; gap:.42rem; padding:.24rem .75rem;
                border-radius:999px; font-size:.78rem; font-weight:700; letter-spacing:.06em;
                background:#334155; color:#e2e8f0; }
              .badge .dot { width:.55rem; height:.55rem; border-radius:50%; background:#94a3b8; }
              .badge.on { background:#14532d; color:#bbf7d0; }
              .badge.on .dot { background:#4ade80; }
              .badge.stale { background:#713f12; color:#fde68a; }
              .badge.stale .dot { background:#fbbf24; }
              main { max-width:1440px; margin:0 auto; padding:1.25rem 1.5rem 2rem; }
              .kpis { display:grid; grid-template-columns:repeat(auto-fit,minmax(190px,1fr));
                gap:1rem; margin-bottom:1rem; }
              .kpi { background:var(--card); border:1px solid var(--line); border-radius:12px;
                padding:.9rem 1.1rem; box-shadow:0 1px 3px rgba(15,23,42,.07); }
              .kpi .klabel { font-size:.72rem; font-weight:700; letter-spacing:.07em;
                text-transform:uppercase; color:var(--muted); }
              .kpi .kval { font-size:1.5rem; font-weight:650; margin-top:.18rem;
                font-variant-numeric:tabular-nums; white-space:nowrap; }
              .kpi .ksub { font-size:.8rem; color:var(--muted); margin-top:.12rem;
                font-variant-numeric:tabular-nums; }
              .grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(340px,1fr)); gap:1rem; }
              .card { background:var(--card); border:1px solid var(--line); border-radius:12px;
                padding:1rem 1.15rem 1.1rem; box-shadow:0 1px 3px rgba(15,23,42,.07); }
              .card.wide { grid-column:1 / -1; }
              h2 { font-size:.82rem; margin:0 0 .7rem; color:#475569;
                text-transform:uppercase; letter-spacing:.07em; }
              h2 .plain { text-transform:none; letter-spacing:0; font-weight:500; }
              h3 { font-size:.92rem; margin:1rem 0 .35rem; color:var(--ink); font-weight:650; }
              h3:first-of-type { margin-top:.2rem; }
              table { border-collapse:collapse; width:100%; font-size:.88rem; }
              td, th { padding:.22rem .4rem; text-align:left; border-bottom:1px solid var(--line-soft); }
              th { color:#64748b; font-weight:600; }
              td.n, th.n { text-align:right; }
              .mono, td, th { font-variant-numeric:tabular-nums; }
              .big { font-size:1.2rem; font-weight:650; }
              .bar { background:#e2e8f0; height:.65rem; border-radius:999px; overflow:hidden; }
              .bar > div { background:var(--orange); height:100%; border-radius:999px; }
              a { color:var(--accent); }
              .err { color:var(--red); }
              .hint { border:1px solid #f59e0b; background:#fffbeb; color:#92400e;
                border-radius:8px; padding:.65rem .85rem; margin-bottom:.7rem; font-size:.9rem; }
              .hint code { display:block; margin-top:.45rem; background:#fef3c7; color:#92400e;
                border-radius:6px; padding:.45rem .6rem; white-space:pre-wrap; word-break:break-all; }
              .dim { opacity:.55; }
              .small { font-size:.8rem; color:var(--muted); }
              .note { margin:.1rem 0 .5rem; }
              .btn { font:inherit; font-size:.85rem; padding:.35rem .8rem; border-radius:8px;
                border:1px solid #cbd5e1; background:#fff; color:var(--ink); cursor:pointer; }
              .btn:hover { background:#f8fafc; border-color:#94a3b8; }
              .flame-wrap svg { width:100%; height:auto; display:block; border-radius:8px; }
              .stackpath { font-family:ui-monospace,Menlo,Consolas,monospace;
                font-size:.74rem; color:#334155; word-break:break-all; }
            """;

    /**
     * The flame-graph zoom behaviour, shared by {@code /flame} and the
     * dashboard's inline flame card. Written against classes (not ids)
     * and scoped to a container element, so the dashboard can re-bind it
     * after swapping in a freshly rendered SVG. Zooming is a pure
     * client-side rescale of the {@code data-*} attributes
     * {@link FlameGraph} emits on every frame.
     */
    private static final String FLAME_ZOOM_JS = """
            let flameZoomReset = null;
            function initFlameZoom(scope) {
              const frames = Array.from(scope.querySelectorAll('g.frame'));
              if (!frames.length) { flameZoomReset = null; return; }
              const svg = scope.querySelector('svg');
              const W = svg.viewBox.baseVal.width;
              const info = scope.querySelector('.zoom-info');
              function fitLabel(name, wPx) {
                const maxChars = Math.floor(wPx / 6.5);
                return (name.length > maxChars && maxChars > 3)
                  ? name.substring(0, maxChars - 1) + '…' : name;
              }
              function applyZoom(zx, zw, frame) {
                const scale = 1 / zw;
                for (const g of frames) {
                  const x = parseFloat(g.dataset.x), w = parseFloat(g.dataset.w);
                  const inside = x >= zx - 1e-9 && (x + w) <= (zx + zw) + 1e-9;
                  const rect = g.querySelector('rect');
                  const text = g.querySelector('text');
                  if (!inside) { g.style.display = 'none'; continue; }
                  g.style.display = '';
                  const nx = (x - zx) * scale * W;
                  const nw = w * scale * W;
                  rect.setAttribute('x', nx.toFixed(1));
                  rect.setAttribute('width', Math.max(nw - 0.5, 0.5).toFixed(1));
                  if (text) {
                    text.setAttribute('x', (nx + 3).toFixed(1));
                    if (nw > 32) {
                      text.style.display = '';
                      text.textContent = fitLabel(g.dataset.name, nw);
                    } else {
                      text.style.display = 'none';
                    }
                  }
                }
                if (info) {
                  info.textContent = frame
                    ? 'Zoomed: ' + frame.dataset.name + ' — '
                      + (parseFloat(frame.dataset.w) * 100).toFixed(1) + '% of samples'
                    : 'Click a frame to zoom into it.';
                }
              }
              for (const g of frames) {
                g.style.cursor = 'pointer';
                g.addEventListener('click', () =>
                  applyZoom(parseFloat(g.dataset.x), parseFloat(g.dataset.w), g));
              }
              flameZoomReset = () => applyZoom(0, 1, null);
              const reset = scope.querySelector('.zoom-reset');
              if (reset && !reset.dataset.zoomBound) {
                reset.dataset.zoomBound = '1';
                reset.addEventListener('click', () => { if (flameZoomReset) flameZoomReset(); });
              }
            }
            """;

    /**
     * {@code GET /} — the single dashboard page for one PID. The shell,
     * KPI row and cards are filled from the JSON API every second; the
     * flame graph is rendered into the page from {@code s} (the snapshot
     * current at page load) and refreshed in place every 15 s.
     */
    public static String dashboardHtml(Snapshot s) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <title>Appraan — live JVM observer</title>
                <style>
                """);
        sb.append(CSS);
        sb.append("""
                </style>
                </head>
                <body>
                <header class="band">
                  <span class="brand">Appraan</span>
                  <span class="tag">the life of your application, live</span>
                  <span class="spacer"></span>
                  <span class="meta">PID <span id="pid">…</span> · <span id="version">…</span></span>
                  <span class="meta">elapsed <span id="elapsed">…</span>s · updated <span id="updated">…</span></span>
                  <span class="badge" id="live-badge"><span class="dot"></span><span id="live-text">…</span></span>
                  <span class="meta"><a href="/flame">Flame graph</a> · <a href="/kernel">Kernel</a></span>
                </header>
                <main>
                <div class="kpis">
                  <div class="kpi"><div class="klabel">Allocation rate</div>
                    <div class="kval" id="kpi-alloc">…</div><div class="ksub" id="kpi-alloc-sub"> </div></div>
                  <div class="kpi"><div class="klabel">Heap used / max</div>
                    <div class="kval" id="kpi-heap">…</div><div class="ksub" id="kpi-heap-sub"> </div></div>
                  <div class="kpi"><div class="klabel">Young GCs</div>
                    <div class="kval" id="kpi-young">…</div><div class="ksub" id="kpi-young-sub"> </div></div>
                  <div class="kpi"><div class="klabel">Old GCs</div>
                    <div class="kval" id="kpi-old">…</div><div class="ksub" id="kpi-old-sub"> </div></div>
                  <div class="kpi"><div class="klabel">CPU (avg across cores)</div>
                    <div class="kval" id="kpi-cpu">…</div><div class="ksub" id="kpi-cpu-sub"> </div></div>
                </div>
                <div class="grid">
                  <section class="card"><h2>Target</h2>
                    <table>
                      <tr><th>GC collector</th><td id="gc-name">…</td></tr>
                      <tr><th>Java version</th><td id="java-version">…</td></tr>
                    </table>
                    <table id="flags" style="margin-top:.5rem"></table>
                    <p class="mono" id="cmdline" style="font-size:.78rem;color:#64748b;white-space:pre-wrap"></p>
                  </section>
                  <section class="card"><h2>Garbage collection</h2>
                    <table>
                      <tr><th>Young GCs</th><td class="n big" id="gc-young">…</td></tr>
                      <tr><th>Old GCs</th><td class="n big" id="gc-old">…</td></tr>
                      <tr><th>Pause total</th><td class="n" id="gc-pause-total">…</td></tr>
                      <tr><th>Pause max</th><td class="n" id="gc-pause-max">…</td></tr>
                      <tr><th>Last young GC</th><td class="n" id="gc-since-young">…</td></tr>
                      <tr><th>Last old GC</th><td class="n" id="gc-since-old">…</td></tr>
                      <tr><th>Promotion (est., G1)</th><td class="n" id="gc-promotion">…</td></tr>
                      <tr><th>G1 eden / survivor / old</th><td class="n" id="g1-split">…</td></tr>
                      <tr><th>Humongous regions</th><td class="n" id="g1-humongous">…</td></tr>
                    </table>
                  </section>
                  <section class="card"><h2>Allocation</h2>
                    <table>
                      <tr><th>Rate</th><td class="n big" id="alloc-rate">…</td></tr>
                      <tr><th>Total allocated</th><td class="n" id="alloc-total">…</td></tr>
                      <tr><th>Heap used (latest)</th><td class="n" id="alloc-heap">…</td></tr>
                    </table>
                    <p class="small" style="margin-top:.5rem">Source: <span id="alloc-source">…</span></p>
                  </section>
                  <section class="card"><h2>CPU — top leaf methods</h2>
                    <p class="note"><span id="cpu-samples">…</span> execution samples</p>
                    <table id="cpu-top"><tr><th class="n">Samples</th><th class="n">%</th><th>Method</th></tr></table>
                  </section>
                  <section class="card"><h2>Per-core CPU</h2>
                    <p class="note">Collector: <span id="cores-collector">…</span></p>
                    <table id="cores"></table>
                  </section>
                  <section class="card wide"><h2>Thread stacks — top collapsed stacks</h2>
                    <p class="note"><span id="stacks-total">…</span> samples with stacks · root-first call paths, hottest first</p>
                    <table id="stacks"></table>
                  </section>
                """);
        sb.append("  <section class=\"card wide\" id=\"flame-card\"><h2>CPU flame graph "
                + "<span class=\"plain\">· <a href=\"/flame\">full page →</a></span></h2>\n");
        sb.append("    <p class=\"note\"><span id=\"flame-samples\">")
                .append(s.stackSamples()).append(" samples with stacks of ")
                .append(s.executionSamples())
                .append(" execution samples · width ∝ samples · hover a frame for counts</span>")
                .append(" · <span id=\"flame-updated\">graph as rendered on page load, ")
                .append(FlameGraph.esc(String.valueOf(s.capturedAt()))).append("</span></p>\n");
        sb.append("""
                    <p><button class="btn zoom-reset" type="button">Reset zoom</button>
                      <button class="btn" id="flame-refresh" type="button">Refresh graph</button>
                      <span class="zoom-info small">Click a frame to zoom into it.</span></p>
                    <div class="flame-wrap" id="flame-box">
                """);
        sb.append(FlameGraph.svg(s.collapsedStacks(), FlameGraph.DEFAULT_WIDTH));
        sb.append("""
                    </div>
                  </section>
                  <section class="card wide"><h2>Kernel — appraanagent metrics <span class="plain">· <a href="/kernel">full page →</a></span></h2>
                    <div id="kernel-hint"></div>
                    <div id="kernel-body">
                      <p class="small note" id="kernel-host"></p>
                      <h3 class="ksec">Off-CPU (times in milli seconds)</h3>
                      <p class="note" id="kernel-offcpu"></p>
                      <table id="kernel-reasons"></table>
                      <table id="kernel-threads" style="margin-top:.5rem"></table>
                      <h3 class="ksec">Kernel operations by category (latency in micro seconds)</h3>
                      <table id="kernel-cats"></table>
                      <div id="kernel-hist" class="small" style="margin-top:.4rem"></div>
                      <p class="small" id="kernel-diag" style="margin-top:.4rem"></p>
                      <h3 class="ksec">TCP connections</h3>
                      <p class="note" id="kernel-tcp"></p>
                      <table id="kernel-recent"></table>
                      <div id="kernel-procio" style="margin-top:.6rem"></div>
                    </div>
                  </section>
                </div>
                <p class="err" id="err"></p>
                </main>
                <script>
                """);
        sb.append(FLAME_ZOOM_JS);
        sb.append(DASHBOARD_JS);
        sb.append("""
                </script>
                </body>
                </html>
                """);
        return sb.toString();
    }

    /**
     * The dashboard's polling script: refreshes every card from the JSON
     * API every second and the inline flame graph every 15 s. The
     * LIVE/STALE judgement is cadence-based (see the comment in the
     * script): STALE means roughly 2.5 missed captures, floored at 6 s.
     */
    private static final String DASHBOARD_JS = """
            const $ = id => document.getElementById(id);
            const fmtBytes = b => {
              if (b < 0) return 'n/a';
              const u = ['B','KiB','MiB','GiB','TiB']; let v = b, i = 0;
              while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
              return (i === 0 ? v : v.toFixed(1)) + ' ' + u[i];
            };
            function rows(table, list) {
              table.replaceChildren();
              for (const cells of list) {
                const tr = document.createElement('tr');
                for (const c of cells) {
                  const td = document.createElement('td');
                  if (c.num) td.className = 'n';
                  td.textContent = c.text;
                  tr.appendChild(td);
                }
                table.appendChild(tr);
              }
            }
            function renderTable(table, headers, list) {
              table.replaceChildren();
              const hr = document.createElement('tr');
              for (const h of headers) {
                const th = document.createElement('th');
                if (h.num) th.className = 'n';
                th.textContent = h.text;
                hr.appendChild(th);
              }
              table.appendChild(hr);
              for (const cells of list) {
                const tr = document.createElement('tr');
                for (const c of cells) {
                  const td = document.createElement('td');
                  td.className = [c.num ? 'n' : '', c.cls || ''].join(' ').trim();
                  td.textContent = c.text;
                  tr.appendChild(td);
                }
                table.appendChild(tr);
              }
            }
            function renderKernel(k) {
              const hintBox = $('kernel-hint');
              hintBox.replaceChildren();
              const body = $('kernel-body');
              if (k.hint && k.hint.state !== 'live') {
                const box = document.createElement('div');
                box.className = 'hint';
                const msg = document.createElement('div');
                msg.textContent = k.hint.message || '';
                box.appendChild(msg);
                if (k.hint.command) {
                  const code = document.createElement('code');
                  code.textContent = k.hint.command;
                  box.appendChild(code);
                }
                hintBox.appendChild(box);
              }
              body.classList.toggle('dim', !!k.stale);
              const secs = document.querySelectorAll('.ksec');
              if (!k.available) {
                secs.forEach(el => el.style.display = 'none');
                $('kernel-host').textContent = '';
                $('kernel-offcpu').textContent = '';
                $('kernel-reasons').replaceChildren();
                $('kernel-threads').replaceChildren();
                $('kernel-cats').replaceChildren();
                $('kernel-hist').textContent = '';
                $('kernel-diag').textContent = '';
                $('kernel-tcp').textContent = '';
                $('kernel-recent').replaceChildren();
              } else {
                secs.forEach(el => el.style.display = '');
                const kd = k.kernel, oc = kd.offCpu;
                $('kernel-host').textContent = kd.host
                  ? 'host: ' + kd.host + (kd.labels
                      ? ' · ' + Object.entries(kd.labels).map(([a, b]) => a + '=' + b).join(' · ')
                      : '')
                  : '';
                const topReason = oc.reasons.length ? oc.reasons[0] : null;
                $('kernel-offcpu').textContent =
                  'Off-CPU ' + oc.offCpuPct + '% · on ' + Math.round(oc.onCpuMs) + ' ms · off '
                  + Math.round(oc.offCpuMs) + ' ms · ' + oc.episodes + ' episodes · max '
                  + Math.round(oc.maxEpisodeMs) + ' ms'
                  + (topReason ? ' · top reason ' + topReason.name + ' ' + topReason.pct + '%' : '')
                  + ' · source ' + k.source + ', ' + k.ageSeconds + 's old' + (k.stale ? ' (stale)' : '');
                renderTable($('kernel-reasons'),
                  [{text: 'Wait reason'}, {text: 'Episodes', num: true},
                   {text: 'Total (in milli seconds)', num: true}, {text: 'Share', num: true}],
                  oc.reasons.map(r => [
                    {text: r.name},
                    {text: String(r.episodes), num: true},
                    {text: String(Math.round(r.totalMs)), num: true},
                    {text: r.pct + '%', num: true}]));
                renderTable($('kernel-threads'),
                  [{text: 'Thread'}, {text: 'Episodes', num: true},
                   {text: 'Off-CPU (in milli seconds)', num: true},
                   {text: 'Max episode (in milli seconds)', num: true},
                   {text: 'On-CPU (in milli seconds)', num: true},
                   {text: 'Off %', num: true}, {text: ''}],
                  oc.threads.map(t => [
                    {text: 'tid ' + t.tid},
                    {text: String(t.episodes), num: true},
                    {text: String(Math.round(t.offCpuMs)), num: true},
                    {text: String(Math.round(t.maxMs)), num: true},
                    {text: String(Math.round(t.onCpuMs)), num: true},
                    {text: t.offCpuPct + '%', num: true},
                    {text: t.partial ? 'partial' : ''}]));
                renderTable($('kernel-cats'),
                  [{text: 'Category'}, {text: 'Count', num: true},
                   {text: 'With latency', num: true},
                   {text: 'Avg latency (in micro seconds)', num: true},
                   {text: 'Max latency (in micro seconds)', num: true},
                   {text: 'Kinds'}],
                  kd.ops.categories.map(c => [
                    {text: c.name},
                    {text: String(c.count), num: true},
                    {text: String(c.latOps), num: true},
                    {text: c.avgUs === null ? '—' : String(c.avgUs), num: true},
                    {text: c.maxUs === null ? '—' : String(c.maxUs), num: true},
                    {text: Object.entries(c.kinds).map(([a, b]) => a + ' ' + b).join(' · ')}]));
                const histLines = [];
                for (const c of kd.ops.categories) {
                  if (c.hist) {
                    histLines.push(c.name + ' hist: ' + Object.entries(c.hist)
                      .filter(([, v]) => v > 0).map(([b, v]) => b + ' ' + v).join(' · '));
                  }
                }
                $('kernel-hist').textContent = histLines.join('  |  ');
                const dg = kd.ops.diagnostics;
                $('kernel-diag').textContent = 'Diagnostics: pending file ops ' + dg.pendingFileOps
                  + ' · unmatched completions ' + dg.unmatchedCompletions
                  + ' · duplicate starts ' + dg.duplicateStarts
                  + ' · evicted pending ' + dg.evictedPending;
                $('kernel-tcp').textContent =
                  'TCP: ' + kd.tcp.closedTotal + ' closed · TX ' + fmtBytes(kd.tcp.txBytes)
                  + ' · RX ' + fmtBytes(kd.tcp.rxBytes) + ' · ' + kd.tcp.stillOpen + ' still open'
                  + ' · ' + kd.tcp.unmatchedDisconnects + ' unmatched disconnects';
                renderTable($('kernel-recent'),
                  [{text: 'Process'}, {text: 'Local → remote'},
                   {text: 'TX/RX', num: true}, {text: 'Lifetime (in milli seconds)', num: true}],
                  kd.tcp.recent.map(cn => [
                    {text: cn.comm || ''},
                    {text: cn.laddr + ':' + cn.lport + ' → ' + cn.raddr + ':' + cn.rport},
                    {text: cn.txKb + '/' + cn.rxKb + ' KB', num: true},
                    {text: cn.ms + ' ms', num: true}]));
              }
              const pi = $('kernel-procio');
              pi.replaceChildren();
              if (k.procIo) {
                const p = document.createElement('p');
                p.className = 'small';
                p.textContent = 'Linux /proc process I/O (cumulative since process start): read '
                  + fmtBytes(k.procIo.readBytes) + ' · write ' + fmtBytes(k.procIo.writeBytes)
                  + ' · syscalls r/w ' + k.procIo.syscr + '/' + k.procIo.syscw;
                pi.appendChild(p);
              }
            }
            async function refreshFlame() {
              try {
                const html = await fetch('/flame').then(r => r.text());
                const fresh = new DOMParser().parseFromString(html, 'text/html').querySelector('svg');
                const box = $('flame-box');
                if (!fresh || !box) return;
                box.replaceChildren(document.importNode(fresh, true));
                initFlameZoom($('flame-card'));
                $('flame-updated').textContent = 'graph updated ' + new Date().toLocaleTimeString();
              } catch (e) { /* keep the graph already on the page */ }
            }
            async function refresh() {
              try {
                const [summary, gc, cpu, cores, kernel, stacks] = await Promise.all([
                  fetch('/api/summary').then(r => r.json()),
                  fetch('/api/gc').then(r => r.json()),
                  fetch('/api/cpu').then(r => r.json()),
                  fetch('/api/cores').then(r => r.json()),
                  fetch('/api/kernel').then(r => r.json()),
                  fetch('/api/stacks').then(r => r.json()).catch(() => null)]);
                $('err').textContent = '';
                const capMs = Date.parse(summary.capturedAt);
                const ageSec = (Date.now() - capMs) / 1000;
                // LIVE/STALE is judged against the observer's own capture
                // cadence (learned from successive snapshots; the default
                // interval is 5s), not a fixed 3s: a healthy observer must
                // not flap STALE between its own snapshots. STALE means
                // roughly 2.5 missed captures.
                if (window._lastCapMs && capMs > window._lastCapMs) {
                  window._cadenceSec = (capMs - window._lastCapMs) / 1000;
                }
                if (capMs !== window._lastCapMs) window._lastCapMs = capMs;
                const staleAfterSec = Math.max(6, (window._cadenceSec || 5) * 2.5);
                const badge = $('live-badge');
                if (ageSec < staleAfterSec) { badge.className = 'badge on'; $('live-text').textContent = 'LIVE'; }
                else { badge.className = 'badge stale'; $('live-text').textContent = 'STALE'; }
                $('updated').textContent = new Date(summary.capturedAt).toLocaleTimeString()
                  + (ageSec >= 1 ? ' (' + Math.floor(ageSec) + 's ago)' : '');
                renderKernel(kernel);
                $('pid').textContent = summary.pid;
                $('version').textContent = summary.version;
                $('elapsed').textContent = summary.elapsedSeconds;
                $('kpi-alloc').textContent = fmtBytes(summary.allocation.rateBytesPerSec) + '/s';
                $('kpi-alloc-sub').textContent = 'total ' + fmtBytes(summary.allocation.totalBytes) + ' allocated';
                const maxHeap = parseInt(summary.flags.MaxHeapSize, 10);
                $('kpi-heap').textContent = fmtBytes(summary.allocation.heapUsedBytes)
                  + (Number.isFinite(maxHeap) ? ' / ' + fmtBytes(maxHeap) : '');
                $('kpi-heap-sub').textContent = Number.isFinite(maxHeap) && maxHeap > 0
                  ? Math.round(100 * summary.allocation.heapUsedBytes / maxHeap) + '% of max heap'
                  : 'heap used (latest snapshot)';
                $('kpi-young').textContent = gc.young.count;
                $('kpi-young-sub').textContent = 'last ' + gc.young.sinceLast;
                $('kpi-old').textContent = gc.old.count;
                $('kpi-old-sub').textContent = 'last ' + gc.old.sinceLast
                  + ' · pause max ' + gc.pauses.maxMs + ' ms';
                $('gc-name').textContent = summary.gcCollector;
                $('java-version').textContent = summary.javaVersion;
                $('cmdline').textContent = summary.commandLine;
                rows($('flags'), Object.entries(summary.flags)
                  .map(([k, v]) => [{text: k}, {text: String(v), num: true}]));
                $('gc-young').textContent = gc.young.count;
                $('gc-old').textContent = gc.old.count;
                $('gc-pause-total').textContent = gc.pauses.totalMs + ' ms';
                $('gc-pause-max').textContent = gc.pauses.maxMs + ' ms';
                $('gc-since-young').textContent = gc.young.sinceLast;
                $('gc-since-old').textContent = gc.old.sinceLast;
                $('gc-promotion').textContent = gc.g1.available
                  ? fmtBytes(gc.promotion.bytes) + ' over ' + gc.promotion.gcCount + ' young GCs'
                  : 'n/a (non-G1 collector)';
                $('g1-split').textContent = gc.g1.available
                  ? fmtBytes(gc.g1.edenUsedBytes) + ' / ' + fmtBytes(gc.g1.survivorUsedBytes)
                    + ' / ' + fmtBytes(gc.g1.oldUsedBytes) + ' · ' + gc.g1.regions + ' regions'
                  : 'n/a (non-G1 collector)';
                $('g1-humongous').textContent = gc.g1.available
                  ? gc.g1.humongous.regions + ' regions, ' + fmtBytes(gc.g1.humongous.usedBytes) + ' used'
                  : 'n/a (non-G1 collector)';
                $('alloc-rate').textContent = fmtBytes(summary.allocation.rateBytesPerSec) + '/s';
                $('alloc-total').textContent = fmtBytes(summary.allocation.totalBytes);
                $('alloc-heap').textContent = fmtBytes(summary.allocation.heapUsedBytes);
                $('alloc-source').textContent = summary.allocation.source;
                $('cpu-samples').textContent = cpu.executionSamples;
                $('flame-samples').textContent = cpu.stackSamples + ' samples with stacks of '
                  + cpu.executionSamples + ' execution samples · width ∝ samples · hover a frame for counts';
                renderTable($('cpu-top'),
                  [{text: 'Samples', num: true}, {text: '%', num: true}, {text: 'Method'}],
                  cpu.topLeaf.map(e => [
                    {text: String(e.samples), num: true},
                    {text: String(e.percent), num: true},
                    {text: e.method}]));
                $('cores-collector').textContent = cores.collector;
                const avgBusy = cores.cores.length
                  ? cores.cores.reduce((a, c) => a + c.busyPercent, 0) / cores.cores.length : 0;
                $('kpi-cpu').textContent = avgBusy.toFixed(1) + '%';
                $('kpi-cpu-sub').textContent = cores.cores.length + ' cores · ' + cores.collector;
                const ct = $('cores'); ct.replaceChildren();
                for (const c of cores.cores) {
                  const tr = document.createElement('tr');
                  const a = document.createElement('td'); a.textContent = 'core ' + c.core;
                  const b = document.createElement('td'); b.style.width = '60%';
                  const bar = document.createElement('div'); bar.className = 'bar';
                  const fill = document.createElement('div'); fill.style.width = c.busyPercent + '%';
                  bar.appendChild(fill); b.appendChild(bar);
                  const d = document.createElement('td'); d.className = 'n'; d.textContent = c.busyPercent + '%';
                  tr.append(a, b, d); ct.appendChild(tr);
                }
                if (stacks) {
                  $('stacks-total').textContent = stacks.totalSamples;
                  const total = stacks.totalSamples || 1;
                  renderTable($('stacks'),
                    [{text: 'Samples', num: true}, {text: '%', num: true},
                     {text: 'Stack (root → leaf)'}],
                    stacks.stacks.slice(0, 10).map(e => [
                      {text: String(e.samples), num: true},
                      {text: (e.samples * 100 / total).toFixed(1), num: true},
                      {text: e.stack.split(';').join(' → '), cls: 'stackpath'}]));
                }
              } catch (e) {
                $('err').textContent = 'Live update failed (is Appraan still running?): ' + e;
              }
            }
            initFlameZoom(document.getElementById('flame-card'));
            $('flame-refresh').addEventListener('click', refreshFlame);
            refresh();
            setInterval(refresh, 1000);
            setInterval(refreshFlame, 15000);
            """;

    /** {@code GET /flame} — server-rendered flame graph + consistent top-leaf table. */
    public static String flamePage(Snapshot s) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <title>Appraan — CPU flame graph</title>
                <style>
                """);
        sb.append(CSS);
        sb.append("""
                </style>
                </head>
                <body>
                <header class="band">
                  <span class="brand">Appraan</span>
                  <span class="tag">CPU flame graph</span>
                  <span class="spacer"></span>
                  <span class="meta"><a href="/">← dashboard</a> · <a href="/kernel">Kernel →</a></span>
                </header>
                <main>
                <div style="display:grid;gap:1rem">
                <section class="card wide flame-scope">
                """);
        sb.append("<h2>CPU flame graph — PID ").append(FlameGraph.esc(s.pid())).append("</h2>\n");
        sb.append("<p class=\"note\">").append(s.stackSamples())
                .append(" samples with stacks of ").append(s.executionSamples())
                .append(" execution samples · width ∝ samples · hover a frame for counts · ")
                .append("snapshot at ").append(FlameGraph.esc(String.valueOf(s.capturedAt()))).append("</p>\n");
        sb.append("<p><button class=\"btn zoom-reset\" type=\"button\">Reset zoom</button> ")
                .append("<span class=\"zoom-info small\">Click a frame to zoom into it.</span></p>\n");
        sb.append("<div class=\"flame-wrap\">\n");
        sb.append(FlameGraph.svg(s.collapsedStacks(), FlameGraph.DEFAULT_WIDTH));
        sb.append("</div>\n</section>\n");
        sb.append("<section class=\"card wide\"><h2>Top leaf methods (same samples as the graph)</h2>\n<table>\n");
        sb.append("<tr><th class=\"n\">Samples</th><th class=\"n\">%</th><th>Method</th></tr>\n");
        for (Map.Entry<String, Long> e : s.topLeafMethods()) {
            sb.append("<tr><td class=\"n\">").append(e.getValue())
                    .append("</td><td class=\"n\">")
                    .append(String.format(java.util.Locale.ROOT, "%.1f", s.leafPercent(e.getValue())))
                    .append("</td><td>").append(FlameGraph.esc(e.getKey()))
                    .append("</td></tr>\n");
        }
        sb.append("</table>\n</section>\n</div>\n</main>\n");
        sb.append("<script>\n");
        sb.append(FLAME_ZOOM_JS);
        sb.append("initFlameZoom(document.querySelector('.flame-scope'));\n</script>\n");
        sb.append("<script>setTimeout(() => location.reload(), 15000);</script>\n");
        sb.append("</body>\n</html>\n");
        return sb.toString();
    }

    /**
     * {@code GET /kernel} — the dedicated Kernel page: the same content as the
     * dashboard's Kernel card, server-rendered (works with JS disabled):
     * hint/empty/stale states with the appraanagent guidance, ops categories
     * with latency + histograms, TCP summary + recent connections, off-CPU
     * summary + wait reasons + top threads, and the Linux /proc parity block.
     */
    public static String kernelPage(KernelFeed.View view, Snapshot s) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <title>Appraan — Kernel</title>
                <style>
                """);
        sb.append(CSS);
        sb.append("""
                </style>
                </head>
                <body>
                <header class="band">
                  <span class="brand">Appraan</span>
                  <span class="tag">Kernel</span>
                  <span class="spacer"></span>
                  <span class="meta"><a href="/">← dashboard</a> · <a href="/flame">CPU flame graph →</a></span>
                </header>
                <main>
                """);
        sb.append("<h1 style=\"font-size:1.25rem;margin:.2rem 0 1rem\">Kernel — PID ")
                .append(FlameGraph.esc(s.pid())).append("</h1>\n");

        KernelFeed.Hint hint = view.hint();
        if (hint != null && !"live".equals(hint.state())) {
            sb.append("<div class=\"hint\"><div>").append(FlameGraph.esc(hint.message()))
                    .append("</div>");
            if (hint.command() != null) {
                sb.append("<code>").append(FlameGraph.esc(hint.command())).append("</code>");
            }
            sb.append("</div>\n");
        }

        KernelData d = view.data();
        if (d != null) {
            sb.append("<p class=\"note\">Source: ").append(FlameGraph.esc(view.source()))
                    .append(" · snapshot age ").append(view.ageSeconds()).append("s")
                    .append(view.stale() ? " · <b>STALE</b>" : "")
                    .append(" · agent window ").append(d.windowSeconds()).append("s</p>\n");
            if (d.host() != null) {
                sb.append("<p class=\"note\">host: ").append(FlameGraph.esc(d.host()));
                if (!d.labels().isEmpty()) {
                    sb.append(" · ");
                    String lsep = "";
                    for (Map.Entry<String, String> e : d.labels().entrySet()) {
                        sb.append(lsep).append(FlameGraph.esc(e.getKey())).append('=')
                                .append(FlameGraph.esc(e.getValue()));
                        lsep = " · ";
                    }
                }
                sb.append("</p>\n");
            }
            sb.append("<div class=\"grid").append(view.stale() ? " dim" : "").append("\">\n");

            KernelData.OffCpu oc = d.offCpu();
            sb.append("<section class=\"card wide\"><h2>Off-CPU (times in milli seconds)</h2>\n<p>")
                    .append(String.format(java.util.Locale.ROOT, "%.1f", oc.offCpuPct()))
                    .append("% off-CPU · on ").append(Math.round(oc.onCpuMs()))
                    .append(" ms · off ").append(Math.round(oc.offCpuMs()))
                    .append(" ms · ").append(oc.episodes())
                    .append(" episodes · max episode ").append(Math.round(oc.maxEpisodeMs()))
                    .append(" ms</p>\n");
            if (!oc.reasons().isEmpty()) {
                sb.append("<table><tr><th>Wait reason</th><th class=\"n\">Episodes</th>")
                        .append("<th class=\"n\">Total (in milli seconds)</th>")
                        .append("<th class=\"n\">Share</th></tr>\n");
                for (KernelData.Reason r : oc.reasons()) {
                    sb.append("<tr><td>").append(FlameGraph.esc(r.name()))
                            .append("</td><td class=\"n\">").append(r.episodes())
                            .append("</td><td class=\"n\">").append(Math.round(r.totalMs()))
                            .append(" ms</td><td class=\"n\">")
                            .append(String.format(java.util.Locale.ROOT, "%.1f", r.pct()))
                            .append("%</td></tr>\n");
                }
                sb.append("</table>\n");
            }
            if (!oc.threads().isEmpty()) {
                sb.append("<table style=\"margin-top:.6rem\"><tr><th>Thread</th>")
                        .append("<th class=\"n\">Episodes</th>")
                        .append("<th class=\"n\">Off-CPU (in milli seconds)</th>")
                        .append("<th class=\"n\">Max episode (in milli seconds)</th>")
                        .append("<th class=\"n\">On-CPU (in milli seconds)</th>")
                        .append("<th class=\"n\">Off %</th><th></th></tr>\n");
                for (KernelData.ThreadStat t : oc.threads()) {
                    sb.append("<tr><td>").append(t.tid())
                            .append("</td><td class=\"n\">").append(t.episodes())
                            .append("</td><td class=\"n\">").append(Math.round(t.offCpuMs()))
                            .append(" ms</td><td class=\"n\">").append(Math.round(t.maxMs()))
                            .append(" ms</td><td class=\"n\">").append(Math.round(t.onCpuMs()))
                            .append(" ms</td><td class=\"n\">")
                            .append(String.format(java.util.Locale.ROOT, "%.1f", t.offCpuPct()))
                            .append("</td><td>").append(t.partial() ? "partial" : "")
                            .append("</td></tr>\n");
                }
                sb.append("</table>\n");
            }
            sb.append("</section>\n");

            sb.append("<section class=\"card wide\">")
                    .append("<h2>Kernel operations by category (latency in micro seconds)</h2>\n");
            sb.append("<table><tr><th>Category</th><th class=\"n\">Count</th>")
                    .append("<th class=\"n\">With latency</th>")
                    .append("<th class=\"n\">Avg latency</th><th class=\"n\">Max latency</th>")
                    .append("<th>Kinds</th></tr>\n");
            for (KernelData.Category c : d.categories()) {
                sb.append("<tr><td>").append(FlameGraph.esc(c.name()))
                        .append("</td><td class=\"n\">").append(c.count())
                        .append("</td><td class=\"n\">").append(c.latOps())
                        .append("</td><td class=\"n\">")
                        .append(c.avgUs() == null ? "—" : c.avgUs() + " µs")
                        .append("</td><td class=\"n\">")
                        .append(c.maxUs() == null ? "—" : c.maxUs() + " µs")
                        .append("</td><td class=\"small\">");
                String sep = "";
                for (Map.Entry<String, Long> k : c.kinds().entrySet()) {
                    sb.append(sep).append(FlameGraph.esc(k.getKey())).append(' ').append(k.getValue());
                    sep = " · ";
                }
                sb.append("</td></tr>\n");
                if (!c.hist().isEmpty()) {
                    sb.append("<tr><td></td><td class=\"small\" colspan=\"5\">hist: ");
                    String hsep = "";
                    for (Map.Entry<String, Long> hb : c.hist().entrySet()) {
                        if (hb.getValue() == 0) {
                            continue;
                        }
                        sb.append(hsep).append(FlameGraph.esc(hb.getKey())).append(' ')
                                .append(hb.getValue());
                        hsep = " · ";
                    }
                    sb.append("</td></tr>\n");
                }
            }
            sb.append("</table>\n");
            sb.append("<p class=\"small\" style=\"margin-top:.5rem\">Diagnostics: pending file ops ")
                    .append(d.diagnostics().pendingFileOps())
                    .append(" · unmatched completions ").append(d.diagnostics().unmatchedCompletions())
                    .append(" · duplicate starts ").append(d.diagnostics().duplicateStarts())
                    .append(" · evicted pending ").append(d.diagnostics().evictedPending())
                    .append("</p>\n</section>\n");

            KernelData.Tcp tcp = d.tcp();
            sb.append("<section class=\"card wide\"><h2>TCP connections</h2>\n<p>")
                    .append(tcp.closedTotal()).append(" closed · TX ")
                    .append(Metrics.formatBytes(tcp.txBytes())).append(" · RX ")
                    .append(Metrics.formatBytes(tcp.rxBytes())).append(" · ")
                    .append(tcp.stillOpen()).append(" still open · ")
                    .append(tcp.unmatchedDisconnects()).append(" unmatched disconnects</p>\n");
            if (!tcp.recent().isEmpty()) {
                sb.append("<table><tr><th>Process</th><th>Local → remote</th>")
                        .append("<th class=\"n\">TX/RX</th>")
                        .append("<th class=\"n\">Lifetime (in milli seconds)</th></tr>\n");
                for (KernelData.Conn cn : tcp.recent()) {
                    sb.append("<tr><td>").append(FlameGraph.esc(cn.comm()))
                            .append("</td><td>").append(FlameGraph.esc(cn.laddr())).append(':')
                            .append(cn.lport()).append(" → ").append(FlameGraph.esc(cn.raddr()))
                            .append(':').append(cn.rport())
                            .append("</td><td class=\"n\">").append(cn.txKb()).append(" / ")
                            .append(cn.rxKb()).append(" KB</td><td class=\"n\">")
                            .append(cn.ms()).append(" ms</td></tr>\n");
                }
                sb.append("</table>\n");
            }
            sb.append("</section>\n</div>\n");
        }

        ProcessIo procIo = s.procIo();
        if (procIo != null) {
            sb.append("<section class=\"card wide\" style=\"margin-top:1rem\">")
                    .append("<h2>Process I/O — Linux /proc (parity subset)</h2>\n<p>")
                    .append("Read ").append(Metrics.formatBytes(procIo.readBytes()))
                    .append(" · write ").append(Metrics.formatBytes(procIo.writeBytes()))
                    .append(" · read/write syscalls ").append(procIo.syscr())
                    .append(" / ").append(procIo.syscw())
                    .append(" · cancelled writes ").append(Metrics.formatBytes(procIo.cancelledWriteBytes()))
                    .append("</p>\n<p class=\"small\">Cumulative since process start, from ")
                    .append("/proc/&lt;pid&gt;/io — semantics differ from the ETW snapshot ")
                    .append("windows above: these counters never reset.</p>\n</section>\n");
        }

        sb.append("</main>\n");
        sb.append("<script>setTimeout(() => location.reload(), 5000);</script>\n");
        sb.append("</body>\n</html>\n");
        return sb.toString();
    }
}
