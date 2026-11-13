package dev.appraan.ui;

import dev.appraan.kernel.KernelData;
import dev.appraan.kernel.KernelFeed;
import dev.appraan.metrics.Metrics;
import dev.appraan.os.ProcessIo;

import java.util.Map;

/**
 * HTML pages. The dashboard is a single self-contained file (inline CSS/JS,
 * no external assets) that polls the JSON API; the flame page is rendered
 * server-side from a {@link Snapshot} so it works even with JS disabled and
 * can be saved verbatim as a static artefact.
 */
public final class Pages {

    private Pages() {
    }

    /** {@code GET /} — the live dashboard shell; data arrives via /api/*. */
    public static String dashboardHtml() {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <title>Appraan — live JVM observer</title>
                <style>
                  body { background:#0d1117; color:#e8ecf1; font-family:system-ui,sans-serif; margin:2rem; }
                  h1 { font-size:1.3rem; margin:0 0 .2rem; }
                  h2 { font-size:1rem; margin:0 0 .6rem; color:#9fb4c8; text-transform:uppercase; letter-spacing:.06em; }
                  .sub { color:#8b98a5; margin-bottom:1.4rem; }
                  .grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(320px,1fr)); gap:1rem; }
                  .card { background:#161b22; border:1px solid #2b3440; border-radius:8px; padding:1rem 1.1rem; }
                  .big { font-size:1.5rem; font-variant-numeric:tabular-nums; }
                  .mono, td, th { font-variant-numeric:tabular-nums; }
                  table { border-collapse:collapse; width:100%; font-size:.88rem; }
                  td, th { padding:.18rem .4rem; text-align:left; border-bottom:1px solid #232b36; }
                  th { color:#9fb4c8; font-weight:600; }
                  td.n, th.n { text-align:right; }
                  .bar { background:#22304a; height:.7rem; border-radius:3px; overflow:hidden; }
                  .bar > div { background:#e8833a; height:100%; }
                  a { color:#6cb6ff; }
                  .err { color:#ff7b72; }
                  .live-dot { display:inline-block; width:.6rem; height:.6rem; border-radius:50%;
                    background:#8b98a5; vertical-align:baseline; margin-right:.3rem; }
                  .live-dot.on { background:#3fb950; }
                  .live-dot.stale { background:#d29922; }
                  .hint { border:1px solid #d29922; background:#2a2113; border-radius:6px;
                    padding:.6rem .8rem; margin-bottom:.6rem; }
                  .hint code { display:block; margin-top:.4rem; color:#ffd479; white-space:pre-wrap;
                    word-break:break-all; }
                  .dim { opacity:.55; }
                  .small { font-size:.8rem; color:#8b98a5; }
                </style>
                </head>
                <body>
                <h1>Appraan — the life of your application, live</h1>
                <div class="sub">Observing PID <span id="pid">…</span> · <span id="version">…</span>
                  · elapsed <span id="elapsed">…</span>s
                  · <span class="live-dot" id="live-dot"></span><span id="live-text">…</span>
                  · updated <span id="updated">…</span>
                  · <a href="/kernel">Kernel →</a> · <a href="/flame">CPU flame graph →</a></div>
                <div class="grid">
                  <section class="card"><h2>Target</h2>
                    <table>
                      <tr><th>GC collector</th><td id="gc-name">…</td></tr>
                      <tr><th>Java version</th><td id="java-version">…</td></tr>
                    </table>
                    <table id="flags" style="margin-top:.5rem"></table>
                    <p class="mono" id="cmdline" style="font-size:.78rem;color:#8b98a5;white-space:pre-wrap"></p>
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
                    <p style="font-size:.78rem;color:#8b98a5">Source: <span id="alloc-source">…</span></p>
                  </section>
                  <section class="card"><h2>CPU — top leaf methods</h2>
                    <p style="margin:.1rem 0 .5rem"><span id="cpu-samples">…</span> execution samples</p>
                    <table id="cpu-top"><tr><th class="n">Samples</th><th class="n">%</th><th>Method</th></tr></table>
                  </section>
                  <section class="card"><h2>Per-core CPU</h2>
                    <p style="margin:.1rem 0 .5rem">Collector: <span id="cores-collector">…</span></p>
                    <table id="cores"></table>
                  </section>
                  <section class="card"><h2>Kernel <span style="text-transform:none;letter-spacing:0">· <a href="/kernel">full page →</a></span></h2>
                    <div id="kernel-hint"></div>
                    <div id="kernel-body">
                      <p class="small" style="margin:.1rem 0 .2rem" id="kernel-host"></p>
                      <p style="margin:.1rem 0 .5rem" id="kernel-offcpu"></p>
                      <table id="kernel-cats"></table>
                      <div id="kernel-hist" class="small" style="margin-top:.4rem"></div>
                      <p style="margin:.6rem 0 .3rem" id="kernel-tcp"></p>
                      <table id="kernel-recent"></table>
                      <table id="kernel-reasons" style="margin-top:.5rem"></table>
                      <div id="kernel-procio" style="margin-top:.6rem"></div>
                    </div>
                  </section>
                </div>
                <p class="err" id="err"></p>
                <script>
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
                  if (!k.available) {
                    $('kernel-host').textContent = '';
                    $('kernel-offcpu').textContent = '';
                    rows($('kernel-cats'), []);
                    $('kernel-hist').textContent = '';
                    $('kernel-tcp').textContent = '';
                    rows($('kernel-recent'), []);
                    rows($('kernel-reasons'), []);
                  } else {
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
                    rows($('kernel-cats'), kd.ops.categories.map(c => [
                      {text: c.name},
                      {text: String(c.count), num: true},
                      {text: c.avgUs === null ? '—' : c.avgUs + ' µs avg', num: true},
                      {text: c.maxUs === null ? '—' : c.maxUs + ' µs max', num: true}]));
                    const histLines = [];
                    for (const c of kd.ops.categories) {
                      if (c.hist) {
                        histLines.push(c.name + ' hist: ' + Object.entries(c.hist)
                          .filter(([, v]) => v > 0).map(([b, v]) => b + ' ' + v).join(' · '));
                      }
                    }
                    $('kernel-hist').textContent = histLines.join('  |  ');
                    $('kernel-tcp').textContent =
                      'TCP: ' + kd.tcp.closedTotal + ' closed · TX ' + fmtBytes(kd.tcp.txBytes)
                      + ' · RX ' + fmtBytes(kd.tcp.rxBytes) + ' · ' + kd.tcp.stillOpen + ' still open';
                    rows($('kernel-recent'), kd.tcp.recent.slice(0, 8).map(cn => [
                      {text: cn.comm || ''},
                      {text: cn.laddr + ':' + cn.lport + ' → ' + cn.raddr + ':' + cn.rport},
                      {text: cn.txKb + '/' + cn.rxKb + ' KB', num: true},
                      {text: cn.ms + ' ms', num: true}]));
                    rows($('kernel-reasons'), oc.reasons.slice(0, 5).map(r => [
                      {text: r.name},
                      {text: String(r.episodes), num: true},
                      {text: Math.round(r.totalMs) + ' ms', num: true},
                      {text: r.pct + '%', num: true}]));
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
                async function refresh() {
                  try {
                    const [summary, gc, cpu, cores, kernel] = await Promise.all([
                      fetch('/api/summary').then(r => r.json()),
                      fetch('/api/gc').then(r => r.json()),
                      fetch('/api/cpu').then(r => r.json()),
                      fetch('/api/cores').then(r => r.json()),
                      fetch('/api/kernel').then(r => r.json())]);
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
                    const dot = $('live-dot');
                    if (ageSec < staleAfterSec) { dot.className = 'live-dot on'; $('live-text').textContent = 'LIVE'; }
                    else { dot.className = 'live-dot stale'; $('live-text').textContent = 'STALE'; }
                    $('updated').textContent = new Date(summary.capturedAt).toLocaleTimeString()
                      + (ageSec >= 1 ? ' (' + Math.floor(ageSec) + 's ago)' : '');
                    renderKernel(kernel);
                    $('pid').textContent = summary.pid;
                    $('version').textContent = summary.version;
                    $('elapsed').textContent = summary.elapsedSeconds;
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
                    const head = document.createElement('tr');
                    head.innerHTML = '<th class="n">Samples</th><th class="n">%</th><th>Method</th>';
                    const table = $('cpu-top');
                    table.replaceChildren(head);
                    for (const e of cpu.topLeaf) {
                      const tr = document.createElement('tr');
                      const a = document.createElement('td'); a.className = 'n'; a.textContent = e.samples;
                      const b = document.createElement('td'); b.className = 'n'; b.textContent = e.percent;
                      const c = document.createElement('td'); c.textContent = e.method;
                      tr.append(a, b, c); table.appendChild(tr);
                    }
                    $('cores-collector').textContent = cores.collector;
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
                  } catch (e) {
                    $('err').textContent = 'Live update failed (is Appraan still running?): ' + e;
                  }
                }
                refresh();
                setInterval(refresh, 1000);
                </script>
                </body>
                </html>
                """;
    }

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
                  body { background:#0d1117; color:#e8ecf1; font-family:system-ui,sans-serif; margin:2rem; }
                  table { border-collapse:collapse; font-size:.9rem; margin-top:1rem; }
                  td, th { padding:.2rem .5rem; text-align:left; border-bottom:1px solid #232b36; }
                  td.n, th.n { text-align:right; font-variant-numeric:tabular-nums; }
                  a { color:#6cb6ff; }
                </style>
                </head>
                <body>
                <p><a href="/">← dashboard</a> · <a href="/kernel">Kernel →</a></p>
                <h1>CPU flame graph — PID """);
        sb.append(FlameGraph.esc(s.pid())).append("</h1>\n");
        sb.append("<p>").append(s.stackSamples())
                .append(" samples with stacks of ").append(s.executionSamples())
                .append(" execution samples · width ∝ samples · hover a frame for counts · ")
                .append("snapshot at ").append(FlameGraph.esc(String.valueOf(s.capturedAt()))).append("</p>\n");
        sb.append("<p><button id=\"zoom-reset\" type=\"button\">Reset zoom</button> ")
                .append("<span id=\"zoom-info\">Click a frame to zoom into it.</span></p>\n");
        sb.append(FlameGraph.svg(s.collapsedStacks(), FlameGraph.DEFAULT_WIDTH));
        sb.append("""
                <script>
                (function () {
                  const frames = Array.from(document.querySelectorAll('g.frame'));
                  if (!frames.length) return;
                  const svg = document.querySelector('svg');
                  const W = svg.viewBox.baseVal.width;
                  const info = document.getElementById('zoom-info');
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
                    info.textContent = frame
                      ? 'Zoomed: ' + frame.dataset.name + ' — '
                        + (parseFloat(frame.dataset.w) * 100).toFixed(1) + '% of samples'
                      : 'Click a frame to zoom into it.';
                  }
                  for (const g of frames) {
                    g.style.cursor = 'pointer';
                    g.addEventListener('click', () =>
                      applyZoom(parseFloat(g.dataset.x), parseFloat(g.dataset.w), g));
                  }
                  document.getElementById('zoom-reset')
                    .addEventListener('click', () => applyZoom(0, 1, null));
                })();
                </script>
                """);
        sb.append("<h2>Top leaf methods (same samples as the graph)</h2>\n<table>\n");
        sb.append("<tr><th class=\"n\">Samples</th><th class=\"n\">%</th><th>Method</th></tr>\n");
        for (Map.Entry<String, Long> e : s.topLeafMethods()) {
            sb.append("<tr><td class=\"n\">").append(e.getValue())
                    .append("</td><td class=\"n\">")
                    .append(String.format(java.util.Locale.ROOT, "%.1f", s.leafPercent(e.getValue())))
                    .append("</td><td>").append(FlameGraph.esc(e.getKey()))
                    .append("</td></tr>\n");
        }
        sb.append("</table>\n");
        sb.append("<script>setTimeout(() => location.reload(), 15000);</script>\n");
        sb.append("</body>\n</html>\n");
        return sb.toString();
    }

    /**
     * {@code GET /kernel} — the dedicated Kernel page: the same content as the
     * dashboard's Kernel panel, server-rendered (works with JS disabled):
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
                  body { background:#0d1117; color:#e8ecf1; font-family:system-ui,sans-serif; margin:2rem; }
                  h2 { font-size:1rem; color:#9fb4c8; text-transform:uppercase; letter-spacing:.06em; margin-top:1.6rem; }
                  table { border-collapse:collapse; font-size:.9rem; margin-top:.4rem; }
                  td, th { padding:.2rem .5rem; text-align:left; border-bottom:1px solid #232b36; }
                  td.n, th.n { text-align:right; font-variant-numeric:tabular-nums; }
                  a { color:#6cb6ff; }
                  .hint { border:1px solid #d29922; background:#2a2113; border-radius:6px;
                    padding:.6rem .8rem; margin:1rem 0; max-width:60rem; }
                  .hint code { display:block; margin-top:.4rem; color:#ffd479; white-space:pre-wrap;
                    word-break:break-all; }
                  .dim { opacity:.55; }
                  .small { font-size:.82rem; color:#8b98a5; }
                </style>
                </head>
                <body>
                <p><a href="/">← dashboard</a> · <a href="/flame">CPU flame graph →</a></p>
                """);
        sb.append("<h1>Kernel — PID ").append(FlameGraph.esc(s.pid())).append("</h1>\n");

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
            if (view.stale()) {
                sb.append("<div class=\"dim\">\n");
            }
            sb.append("<p>Source: ").append(FlameGraph.esc(view.source()))
                    .append(" · snapshot age ").append(view.ageSeconds()).append("s")
                    .append(view.stale() ? " · <b>STALE</b>" : "")
                    .append(" · agent window ").append(d.windowSeconds()).append("s</p>\n");
            if (d.host() != null) {
                sb.append("<p>host: ").append(FlameGraph.esc(d.host()));
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

            KernelData.OffCpu oc = d.offCpu();
            sb.append("<h2>Off-CPU</h2>\n<p>")
                    .append(String.format(java.util.Locale.ROOT, "%.1f", oc.offCpuPct()))
                    .append("% off-CPU · on ").append(Math.round(oc.onCpuMs()))
                    .append(" ms · off ").append(Math.round(oc.offCpuMs()))
                    .append(" ms · ").append(oc.episodes())
                    .append(" episodes · max episode ").append(Math.round(oc.maxEpisodeMs()))
                    .append(" ms</p>\n");
            if (!oc.reasons().isEmpty()) {
                sb.append("<table><tr><th>Wait reason</th><th class=\"n\">Episodes</th>")
                        .append("<th class=\"n\">Total</th><th class=\"n\">Share</th></tr>\n");
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
                sb.append("<table><tr><th>Thread</th><th class=\"n\">Episodes</th>")
                        .append("<th class=\"n\">Off-CPU</th><th class=\"n\">Max episode</th>")
                        .append("<th class=\"n\">On-CPU</th><th class=\"n\">Off %</th><th></th></tr>\n");
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

            sb.append("<h2>Kernel operations by category</h2>\n");
            sb.append("<table><tr><th>Category</th><th class=\"n\">Count</th>")
                    .append("<th class=\"n\">With latency</th><th class=\"n\">Avg</th>")
                    .append("<th class=\"n\">Max</th><th>Kinds</th></tr>\n");
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
            sb.append("<p class=\"small\">Diagnostics: pending file ops ")
                    .append(d.diagnostics().pendingFileOps())
                    .append(" · unmatched completions ").append(d.diagnostics().unmatchedCompletions())
                    .append(" · duplicate starts ").append(d.diagnostics().duplicateStarts())
                    .append(" · evicted pending ").append(d.diagnostics().evictedPending())
                    .append("</p>\n");

            KernelData.Tcp tcp = d.tcp();
            sb.append("<h2>TCP connections</h2>\n<p>")
                    .append(tcp.closedTotal()).append(" closed · TX ")
                    .append(Metrics.formatBytes(tcp.txBytes())).append(" · RX ")
                    .append(Metrics.formatBytes(tcp.rxBytes())).append(" · ")
                    .append(tcp.stillOpen()).append(" still open · ")
                    .append(tcp.unmatchedDisconnects()).append(" unmatched disconnects</p>\n");
            if (!tcp.recent().isEmpty()) {
                sb.append("<table><tr><th>Process</th><th>Local → remote</th>")
                        .append("<th class=\"n\">TX/RX</th><th class=\"n\">Lifetime</th></tr>\n");
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
            if (view.stale()) {
                sb.append("</div>\n");
            }
        }

        ProcessIo procIo = s.procIo();
        if (procIo != null) {
            sb.append("<h2>Process I/O — Linux /proc (parity subset)</h2>\n<p>")
                    .append("Read ").append(Metrics.formatBytes(procIo.readBytes()))
                    .append(" · write ").append(Metrics.formatBytes(procIo.writeBytes()))
                    .append(" · read/write syscalls ").append(procIo.syscr())
                    .append(" / ").append(procIo.syscw())
                    .append(" · cancelled writes ").append(Metrics.formatBytes(procIo.cancelledWriteBytes()))
                    .append("</p>\n<p class=\"small\">Cumulative since process start, from ")
                    .append("/proc/&lt;pid&gt;/io — semantics differ from the ETW snapshot ")
                    .append("windows above: these counters never reset.</p>\n");
        }

        sb.append("<script>setTimeout(() => location.reload(), 5000);</script>\n");
        sb.append("</body>\n</html>\n");
        return sb.toString();
    }
}
