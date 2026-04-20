const MAX_POINTS = 60;
const MAX_XLOG = 300;
const HOST_COLORS = ['#38bdf8', '#a78bfa', '#34d399', '#fbbf24', '#f87171', '#ec4899', '#fb923c', '#60a5fa'];

const state = {
    hosts: {},
    history: {},
    xlog: [],
    alarmsActive: [],
    alarmsHistory: [],
    endpoints: [],
    statusBuckets: [],
    slowQueries: [],
    probes: [],
    historyRange: '6h',
    historyData: [],
    scope: 'all',
    charts: {},
    hostColorIndex: {},
    pageStart: Date.now()
};

const RANGE_MS = { '1h': 3600e3, '6h': 6*3600e3, '24h': 24*3600e3, '7d': 7*24*3600e3 };

// ===== Helpers =====
function colorForHost(hostId) {
    if (state.hostColorIndex[hostId] == null) {
        state.hostColorIndex[hostId] = Object.keys(state.hostColorIndex).length;
    }
    return HOST_COLORS[state.hostColorIndex[hostId] % HOST_COLORS.length];
}
function fmtBytes(b) {
    if (!b || b <= 0) return '-';
    const u = ['B', 'KB', 'MB', 'GB', 'TB'];
    let i = 0, v = b;
    while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
    return v.toFixed(v < 10 ? 2 : 1) + u[i];
}
function fmtNum(n, d = 1) {
    if (n == null || isNaN(n)) return '--';
    return Number(n).toFixed(d);
}
function fmtUptime(s) {
    if (!s) return '-';
    const d = Math.floor(s / 86400);
    const h = Math.floor((s % 86400) / 3600);
    const m = Math.floor((s % 3600) / 60);
    if (d > 0) return `${d}일 ${h}시간`;
    if (h > 0) return `${h}시간 ${m}분`;
    return `${m}분`;
}
function pad2(n) { return n < 10 ? '0' + n : '' + n; }
function fmtTime(ts) {
    const d = new Date(ts);
    return `${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`;
}
function fmtTimeShort(ts) {
    const d = new Date(ts);
    return `${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`;
}
function fmtClock(d) {
    return `${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`;
}
function escapeHtml(s) {
    if (s == null) return '';
    return String(s).replace(/[&<>"']/g, c => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    })[c]);
}
function severityClass(pct) {
    if (pct == null || isNaN(pct)) return '';
    if (pct >= 90) return 'danger';
    if (pct >= 75) return 'warn';
    return '';
}

// ===== Clock =====
function updateClock() {
    document.getElementById('clock').textContent = fmtClock(new Date());
    const elapsed = Math.floor((Date.now() - state.pageStart) / 1000);
    document.getElementById('footer-uptime').textContent = `세션 ${fmtUptime(elapsed)}`;
}
setInterval(updateClock, 1000);
updateClock();

// ===== Maintenance mute =====
const maintBtn = document.getElementById('maint-btn');
const maintLabel = document.getElementById('maint-label');
let maintActive = false;
if (maintBtn) {
    maintBtn.addEventListener('click', async () => {
        if (!maintActive) {
            const minutes = parseInt(prompt('모든 알림을 몇 분 동안 뮤트할까요? (기본 60)', '60') || '60', 10);
            if (!minutes || minutes <= 0) return;
            try {
                const r = await fetch('/api/maintenance/mute', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ durationSec: minutes * 60 })
                });
                if (!r.ok) throw new Error('mute failed');
                maintActive = true;
                maintBtn.classList.add('active');
                maintLabel.textContent = `뮤트 중 (${minutes}분)`;
            } catch (e) {
                console.warn('mute failed', e);
                alert('뮤트 실패');
            }
        } else {
            try {
                const r = await fetch('/api/maintenance/unmute', { method: 'POST' });
                if (!r.ok) throw new Error('unmute failed');
                maintActive = false;
                maintBtn.classList.remove('active');
                maintLabel.textContent = '알림 뮤트';
            } catch (e) {
                console.warn('unmute failed', e);
            }
        }
    });
}

// ===== Sidebar (host list) =====
function renderHostList() {
    const container = document.getElementById('host-items');
    const hostIds = Object.keys(state.hosts).sort();

    container.innerHTML = hostIds.map(id => {
        const snap = state.hosts[id];
        const status = snap.status || 'unknown';
        const procCount = (snap.apps || []).length;
        const color = colorForHost(id);
        const selected = state.scope === id ? 'selected' : '';
        const cpu = snap.host ? fmtNum(snap.host.cpuUsedPct, 0) + '%' : '-';
        const hostname = snap.host?.hostname || id;
        return `
            <button class="host-item ${selected}" data-scope="${id}">
                <span class="host-icon" style="color:${color}">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2"/><line x1="9" y1="9" x2="15" y2="9"/><line x1="9" y1="13" x2="15" y2="13"/><line x1="9" y1="17" x2="13" y2="17"/></svg>
                    <span class="status-dot ${status}"></span>
                </span>
                <div class="host-body">
                    <span class="host-name">${escapeHtml(snap.displayName || id)}</span>
                    <span class="host-meta">${escapeHtml(hostname)} · CPU ${cpu} · ${procCount} proc</span>
                </div>
            </button>`;
    }).join('');

    document.getElementById('tree-count').textContent = hostIds.length;
    document.getElementById('all-badge').textContent = hostIds.length;

    container.querySelectorAll('.host-item').forEach(el => {
        el.addEventListener('click', () => setScope(el.dataset.scope));
    });
}

document.querySelector('.host-item[data-scope="all"]').addEventListener('click', () => setScope('all'));

function setScope(scope) {
    state.scope = scope;
    document.querySelectorAll('.host-item').forEach(el => el.classList.toggle('selected', el.dataset.scope === scope));
    fetchHistoryForScope();
    renderAll();
    fetchEndpoints();
    fetchStatusDistribution();
    fetchSlowQueries();
}

// ===== Summary chips =====
function updateSummary() {
    const ids = Object.keys(state.hosts);
    let up = 0, down = 0, procs = 0;
    ids.forEach(id => {
        const s = state.hosts[id];
        if (s.status === 'UP') up++; else down++;
        procs += (s.apps || []).length;
    });
    document.getElementById('total-count').textContent = ids.length;
    document.getElementById('up-count').textContent = up;
    document.getElementById('down-count').textContent = down;
    document.getElementById('procs-count').textContent = procs;

    const nowMs = Date.now();
    const windowMs = 60000;
    const recent = state.xlog.filter(r => r.timestamp >= nowMs - windowMs);
    const rps = (recent.length / 60).toFixed(1);
    document.getElementById('rps-count').textContent = rps;

    const alertBar = document.getElementById('alert-bar');
    if (down > 0) {
        const downIds = ids.filter(id => state.hosts[id].status !== 'UP');
        const downNames = downIds.map(id => state.hosts[id].displayName || id).join(', ');
        alertBar.style.display = 'flex';
        document.getElementById('alert-text').textContent = `${down}개 서버 응답 없음: ${downNames}`;
    } else {
        alertBar.style.display = 'none';
    }
}

// ===== Scope banner =====
function renderScopeBanner() {
    const title = document.getElementById('scope-title');
    const subtitle = document.getElementById('scope-subtitle');
    const right = document.getElementById('scope-banner-right');

    if (state.scope === 'all') {
        const count = Object.keys(state.hosts).length;
        title.textContent = '전체 호스트';
        subtitle.textContent = count ? `${count}개 서버 집계 뷰` : '수집 대기 중';
        right.innerHTML = '';
    } else {
        const snap = state.hosts[state.scope];
        title.textContent = snap?.displayName || state.scope;
        const h = snap?.host;
        subtitle.textContent = h
            ? `${h.hostname || ''} · ${h.osName || ''} · 코어 ${h.cpuCores || '-'}`
            : '데이터 수집 중';
        right.innerHTML = h ? `
            <div class="scope-meta">
                <span class="scope-meta-label">가동 시간</span>
                <span class="scope-meta-value">${fmtUptime(h.uptimeSeconds)}</span>
            </div>
            <div class="scope-meta">
                <span class="scope-meta-label">마지막 수신</span>
                <span class="scope-meta-value">${fmtTime(snap.timestamp)}</span>
            </div>` : '';
    }

    document.getElementById('footer-scope').textContent = title.textContent;
}

// ===== KPI cards =====
function renderKpi() {
    const snaps = scopedSnaps();
    const validHosts = snaps.filter(s => s.host).map(s => s.host);

    const cpu = avg(validHosts.map(h => h.cpuUsedPct));
    const memPct = avg(validHosts.map(h => h.memTotal > 0 ? (h.memUsed / h.memTotal) * 100 : null));
    const memUsed = sum(validHosts.map(h => h.memUsed));
    const memTotal = sum(validHosts.map(h => h.memTotal));
    const load = avg(validHosts.map(h => h.loadAvg1));
    const load5 = avg(validHosts.map(h => h.loadAvg5));
    const load15 = avg(validHosts.map(h => h.loadAvg15));
    const cores = sum(validHosts.map(h => h.cpuCores));

    let diskMax = null, diskMaxMount = null, mountCount = 0;
    validHosts.forEach(h => {
        (h.disks || []).forEach(d => {
            mountCount++;
            if (d.total > 0) {
                const pct = (d.used / d.total) * 100;
                if (diskMax == null || pct > diskMax) {
                    diskMax = pct;
                    diskMaxMount = d.mount;
                }
            }
        });
    });

    setKpi('cpu', cpu, {
        badge: state.scope === 'all' ? '평균' : `${validHosts[0]?.cpuCores || '-'}코어`,
        sub: cores ? `${cores}코어 총합` : '수집 대기'
    });
    setKpi('mem', memPct, {
        badge: state.scope === 'all' ? '평균' : '사용',
        sub: memTotal > 0 ? `${fmtBytes(memUsed)} / ${fmtBytes(memTotal)}` : '수집 대기'
    });
    setKpi('disk', diskMax, {
        badge: '최대',
        sub: mountCount ? `${mountCount}개 마운트${diskMaxMount ? ' · ' + diskMaxMount : ''}` : '마운트 없음'
    });
    setLoadKpi(load, load5, load15);
}

function setKpi(key, pct, opts) {
    const value = document.getElementById(`kpi-${key}-value`);
    const bar = document.getElementById(`kpi-${key}-bar`);
    const sub = document.getElementById(`kpi-${key}-sub`);
    const badge = document.getElementById(`kpi-${key}-badge`);

    if (pct == null || isNaN(pct)) {
        value.textContent = '--';
        bar.style.width = '0';
        bar.className = 'kpi-bar-fill';
    } else {
        value.textContent = pct.toFixed(pct >= 10 ? 0 : 1);
        bar.style.width = Math.min(100, Math.max(0, pct)) + '%';
        bar.className = 'kpi-bar-fill ' + severityClass(pct);
    }
    if (opts.sub) sub.textContent = opts.sub;
    if (opts.badge) badge.textContent = opts.badge;
}

function setLoadKpi(load, load5, load15) {
    const value = document.getElementById('kpi-load-value');
    const sub = document.getElementById('kpi-load-sub');
    if (load == null || isNaN(load)) {
        value.textContent = '--';
        sub.textContent = '5m -- · 15m --';
    } else {
        value.textContent = load.toFixed(2);
        sub.textContent = `5m ${fmtNum(load5, 2)} · 15m ${fmtNum(load15, 2)}`;
    }
}

function avg(arr) {
    const valid = arr.filter(v => v != null && !isNaN(v));
    if (!valid.length) return null;
    return valid.reduce((s, v) => s + v, 0) / valid.length;
}
function sum(arr) {
    return arr.filter(v => v != null && !isNaN(v)).reduce((s, v) => s + v, 0);
}

// ===== Charts =====
function baseChart(opts = {}) {
    const { min, max } = opts;
    return {
        type: 'line',
        data: { labels: [], datasets: [] },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            animation: false,
            interaction: { intersect: false, mode: 'nearest', axis: 'x' },
            plugins: {
                legend: {
                    display: opts.legend !== false,
                    position: 'top',
                    align: 'end',
                    labels: {
                        color: '#b3bdd0',
                        font: { family: 'Inter', size: 11, weight: '500' },
                        boxWidth: 10,
                        boxHeight: 2,
                        padding: 8,
                        usePointStyle: false
                    }
                },
                tooltip: {
                    backgroundColor: '#0a0f1e',
                    borderColor: '#2f3f6b',
                    borderWidth: 1,
                    titleFont: { family: 'Inter', size: 12, weight: '600' },
                    bodyFont: { family: 'JetBrains Mono', size: 11 },
                    titleColor: '#b3bdd0',
                    bodyColor: '#e8ecf5',
                    padding: 10,
                    cornerRadius: 6,
                    displayColors: true,
                    boxWidth: 8,
                    boxHeight: 8
                }
            },
            scales: {
                x: {
                    ticks: { color: '#7b869e', font: { family: 'JetBrains Mono', size: 10 }, maxTicksLimit: 6 },
                    grid: { color: 'rgba(34, 48, 85, 0.4)', drawTicks: false }
                },
                y: {
                    beginAtZero: true,
                    min, max,
                    ticks: { color: '#7b869e', font: { family: 'JetBrains Mono', size: 10 }, maxTicksLimit: 5, padding: 8 },
                    grid: { color: 'rgba(34, 48, 85, 0.4)', drawTicks: false }
                }
            },
            elements: {
                line: { borderWidth: 2, tension: 0.35 },
                point: { radius: 0, hitRadius: 12, hoverRadius: 4 }
            }
        }
    };
}

function initCharts() {
    state.charts.cpu = new Chart(document.getElementById('chart-cpu'), baseChart({ min: 0, max: 100 }));
    state.charts.mem = new Chart(document.getElementById('chart-mem'), baseChart({ min: 0, max: 100 }));
    state.charts.load = new Chart(document.getElementById('chart-load'), baseChart());
    state.charts.disk = new Chart(document.getElementById('chart-disk'), baseChart({ min: 0, max: 100 }));
    state.charts.net = new Chart(document.getElementById('chart-net'), baseChart());
    state.charts.status = new Chart(document.getElementById('chart-status'), {
        type: 'bar',
        data: { labels: [], datasets: [] },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            animation: false,
            plugins: {
                legend: {
                    display: true, position: 'top', align: 'end',
                    labels: {
                        color: '#b3bdd0', font: { family: 'Inter', size: 11, weight: '500' },
                        boxWidth: 10, boxHeight: 10, padding: 8
                    }
                },
                tooltip: {
                    backgroundColor: '#0a0f1e', borderColor: '#2f3f6b', borderWidth: 1,
                    padding: 10, cornerRadius: 6,
                    titleFont: { family: 'Inter', size: 12, weight: '600' },
                    bodyFont: { family: 'JetBrains Mono', size: 11 },
                    titleColor: '#b3bdd0', bodyColor: '#e8ecf5'
                }
            },
            scales: {
                x: {
                    stacked: true,
                    ticks: { color: '#7b869e', font: { family: 'JetBrains Mono', size: 10 }, maxTicksLimit: 6 },
                    grid: { display: false }
                },
                y: {
                    stacked: true, beginAtZero: true,
                    ticks: { color: '#7b869e', font: { family: 'JetBrains Mono', size: 10 }, maxTicksLimit: 5, padding: 8 },
                    grid: { color: 'rgba(34, 48, 85, 0.4)', drawTicks: false }
                }
            }
        }
    });

    state.charts.scatter = new Chart(document.getElementById('xlog-scatter'), {
        type: 'scatter',
        data: { datasets: [] },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            animation: false,
            onClick: (evt, elements, chart) => {
                if (!elements.length) return;
                const el = elements[0];
                const point = chart.data.datasets[el.datasetIndex].data[el.index];
                if (point && point.r) openDetailModal(point.r);
            },
            onHover: (evt, elements) => {
                if (evt.native && evt.native.target) {
                    evt.native.target.style.cursor = elements.length ? 'pointer' : 'default';
                }
            },
            plugins: {
                legend: { display: false },
                tooltip: {
                    backgroundColor: '#0a0f1e',
                    borderColor: '#2f3f6b',
                    borderWidth: 1,
                    padding: 10,
                    titleFont: { family: 'Inter', size: 12, weight: '600' },
                    bodyFont: { family: 'JetBrains Mono', size: 11 },
                    titleColor: '#b3bdd0',
                    bodyColor: '#e8ecf5',
                    cornerRadius: 6,
                    callbacks: {
                        title: ctx => fmtTime(ctx[0].raw.x),
                        label: ctx => {
                            const r = ctx.raw.r;
                            return `${r.method} ${r.path}  ·  ${r.status}  ·  ${r.elapsedMs}ms`;
                        }
                    }
                }
            },
            scales: {
                x: {
                    type: 'linear',
                    ticks: {
                        color: '#7b869e',
                        font: { family: 'JetBrains Mono', size: 10 },
                        maxTicksLimit: 6,
                        callback: v => fmtTime(v)
                    },
                    grid: { color: 'rgba(34, 48, 85, 0.4)', drawTicks: false }
                },
                y: {
                    beginAtZero: true,
                    title: { display: true, text: '응답 시간 (ms)', color: '#7b869e', font: { family: 'Inter', size: 11, weight: '500' } },
                    ticks: { color: '#7b869e', font: { family: 'JetBrains Mono', size: 10 }, maxTicksLimit: 5, padding: 8 },
                    grid: { color: 'rgba(34, 48, 85, 0.4)', drawTicks: false }
                }
            }
        }
    });
}

function rebuildCharts() {
    ['cpu', 'mem', 'load', 'disk'].forEach(k => {
        const ch = state.charts[k];
        if (!ch) return;

        const hostIds = state.scope === 'all' ? Object.keys(state.hosts).sort() : [state.scope];
        ch.options.plugins.legend.display = hostIds.length > 1;

        const maxLen = Math.max(0, ...hostIds.map(id => (state.history[id] || []).length));
        ch.data.datasets = hostIds.map(id => {
            const hist = state.history[id] || [];
            const color = colorForHost(id);
            const data = hist.map(s => metricValue(k, s));
            return {
                label: state.hosts[id]?.displayName || id,
                data: padStart(data, maxLen),
                borderColor: color,
                backgroundColor: hostIds.length === 1 ? color + '20' : 'transparent',
                fill: hostIds.length === 1
            };
        });

        if (maxLen > 0) {
            const sample = hostIds.map(id => state.history[id] || []).find(h => h.length === maxLen) || [];
            ch.data.labels = sample.map(s => fmtTime(s.timestamp));
        } else {
            ch.data.labels = [];
        }
        ch.update('none');
    });
    rebuildNetChart();
    rebuildStatusChart();
    rebuildScatter();
}

function rebuildNetChart() {
    const ch = state.charts.net;
    if (!ch) return;
    const hostIds = state.scope === 'all' ? Object.keys(state.hosts).sort() : [state.scope];
    const maxLen = Math.max(0, ...hostIds.map(id => (state.history[id] || []).length));

    const datasets = [];
    hostIds.forEach(id => {
        const hist = state.history[id] || [];
        const color = colorForHost(id);
        const rx = hist.map(s => toMbps(s.host?.netRxBps));
        const tx = hist.map(s => toMbps(s.host?.netTxBps));
        const prefix = hostIds.length > 1 ? (state.hosts[id]?.displayName || id) + ' ' : '';
        datasets.push({
            label: prefix + 'RX',
            data: padStart(rx, maxLen),
            borderColor: color, backgroundColor: color + '20', fill: false
        });
        datasets.push({
            label: prefix + 'TX',
            data: padStart(tx, maxLen),
            borderColor: color, borderDash: [4, 4], fill: false
        });
    });
    ch.data.datasets = datasets;
    if (maxLen > 0) {
        const sample = hostIds.map(id => state.history[id] || []).find(h => h.length === maxLen) || [];
        ch.data.labels = sample.map(s => fmtTime(s.timestamp));
    } else {
        ch.data.labels = [];
    }
    ch.options.plugins.legend.display = datasets.length > 0;
    if (ch.options.scales?.y) {
        ch.options.scales.y.title = { display: true, text: 'Mb/s', color: '#7b869e', font: { family: 'Inter', size: 11, weight: '500' } };
    }
    ch.update('none');
}

function toMbps(bps) {
    if (bps == null || isNaN(bps)) return 0;
    return +(bps * 8 / 1_000_000).toFixed(2);
}

function rebuildStatusChart() {
    const ch = state.charts.status;
    if (!ch) return;
    const buckets = state.statusBuckets || [];
    const labels = buckets.map(b => fmtTime(b.timestamp));
    const pick = idx => buckets.map(b => [b.s2xx, b.s3xx, b.s4xx, b.s5xx][idx]);
    ch.data.labels = labels;
    ch.data.datasets = [
        { label: '2xx', data: pick(0), backgroundColor: '#34d399', stack: 's' },
        { label: '3xx', data: pick(1), backgroundColor: '#38bdf8', stack: 's' },
        { label: '4xx', data: pick(2), backgroundColor: '#fbbf24', stack: 's' },
        { label: '5xx', data: pick(3), backgroundColor: '#f87171', stack: 's' }
    ];
    ch.update('none');
}

function padStart(arr, len) {
    const pad = new Array(Math.max(0, len - arr.length)).fill(null);
    return pad.concat(arr);
}

function metricValue(kind, snap) {
    if (!snap || !snap.host) return null;
    const h = snap.host;
    switch (kind) {
        case 'cpu': return +Number(h.cpuUsedPct).toFixed(1);
        case 'mem': return h.memTotal > 0 ? +((h.memUsed / h.memTotal) * 100).toFixed(1) : 0;
        case 'load': return +Number(h.loadAvg1).toFixed(2);
        case 'disk': {
            const disks = h.disks || [];
            if (!disks.length) return 0;
            const max = Math.max(...disks.map(d => d.total > 0 ? (d.used / d.total) * 100 : 0));
            return +max.toFixed(1);
        }
    }
    return null;
}

// ===== Processes =====
function renderProcs() {
    const tbody = document.querySelector('#procs-table tbody');
    const sub = document.getElementById('procs-sub');
    const empty = document.getElementById('procs-empty');

    const snaps = scopedSnaps();
    const rows = [];
    snaps.forEach(snap => {
        (snap.apps || []).forEach(a => {
            rows.push({ hostId: snap.hostId, disp: snap.displayName || snap.hostId, a });
        });
    });
    rows.sort((x, y) => y.a.memRss - x.a.memRss);
    sub.textContent = `${rows.length}개 프로세스`;

    if (!rows.length) {
        tbody.innerHTML = '';
        empty.style.display = 'block';
        return;
    }
    empty.style.display = 'none';
    tbody.innerHTML = rows.slice(0, 100).map(r => `
        <tr>
            <td class="host-cell"><span class="host-color" style="background:${colorForHost(r.hostId)}"></span>${escapeHtml(r.disp)}</td>
            <td class="proc-name">${escapeHtml(r.a.name)}</td>
            <td class="num">${r.a.pid}</td>
            <td class="num">${fmtBytes(r.a.memRss)}</td>
            <td class="num">${fmtUptime(r.a.uptimeSeconds)}</td>
            <td class="cmdline" title="${escapeHtml(r.a.cmdline)}">${escapeHtml(r.a.cmdline || '-')}</td>
        </tr>`).join('');
}

// ===== Disks =====
function renderDisks() {
    const list = document.getElementById('disks-list');
    const sub = document.getElementById('disks-sub');
    const snaps = scopedSnaps();
    const rows = [];
    snaps.forEach(snap => {
        (snap.host?.disks || []).forEach(d => {
            const ratio = d.total > 0 ? d.used / d.total : 0;
            rows.push({ hostId: snap.hostId, disp: snap.displayName || snap.hostId, d, pct: ratio * 100 });
        });
    });
    rows.sort((a, b) => b.pct - a.pct);
    sub.textContent = `${rows.length}개 마운트`;

    if (!rows.length) {
        list.innerHTML = '<div class="empty-state">디스크 정보 없음</div>';
        return;
    }
    list.innerHTML = rows.map(r => {
        const cls = severityClass(r.pct);
        const color = colorForHost(r.hostId);
        const showHostTag = state.scope === 'all';
        return `
            <div class="disk-row">
                <div class="disk-head">
                    <div class="disk-left">
                        <span class="disk-mount">${escapeHtml(r.d.mount)}</span>
                        ${r.d.fsType ? `<span class="disk-fs">${escapeHtml(r.d.fsType)}</span>` : ''}
                        ${showHostTag ? `<span class="disk-host-tag" style="color:${color}">${escapeHtml(r.disp)}</span>` : ''}
                    </div>
                    <div class="disk-right">
                        <span class="mono">${fmtBytes(r.d.used)} / ${fmtBytes(r.d.total)}</span>
                        <span class="disk-pct ${cls}">${r.pct.toFixed(1)}%</span>
                    </div>
                </div>
                <div class="disk-bar-bg"><div class="disk-bar ${cls}" style="width:${Math.min(100, r.pct).toFixed(1)}%"></div></div>
            </div>`;
    }).join('');
}

// ===== Certs =====
function renderCerts() {
    const list = document.getElementById('certs-list');
    const empty = document.getElementById('certs-empty');
    const sub = document.getElementById('certs-sub');
    const snaps = scopedSnaps();

    const rows = [];
    snaps.forEach(snap => {
        (snap.certs || []).forEach(c => {
            rows.push({ hostId: snap.hostId, disp: snap.displayName || snap.hostId, c });
        });
    });
    rows.sort((a, b) => (a.c.daysLeft ?? 9999) - (b.c.daysLeft ?? 9999));

    sub.textContent = rows.length ? `${rows.length}개 인증서` : '-';

    if (!rows.length) {
        list.innerHTML = '';
        empty.style.display = 'block';
        return;
    }
    empty.style.display = 'none';

    const showHostTag = state.scope === 'all';
    list.innerHTML = rows.map(r => {
        const d = r.c.daysLeft;
        let badgeCls = 'ok';
        if (d == null || d < 0) badgeCls = 'expired';
        else if (d <= 7) badgeCls = 'danger';
        else if (d <= 30) badgeCls = 'warn';

        const expiresAt = r.c.notAfter ? new Date(r.c.notAfter * 1000) : null;
        const expiresStr = expiresAt
            ? expiresAt.toLocaleDateString('ko-KR', { year: 'numeric', month: '2-digit', day: '2-digit' })
            : '-';
        const color = colorForHost(r.hostId);
        const daysLabel = d == null ? '?' : d < 0 ? '만료' : `${d}일`;
        const sansStr = r.c.sans ? r.c.sans.split(',').slice(0, 4).join(', ') + (r.c.sans.split(',').length > 4 ? ' …' : '') : '';

        return `
            <div class="cert-row">
                <div class="cert-left">
                    <div class="cert-subject">${escapeHtml(r.c.subject || '-')}</div>
                    <div class="cert-meta">
                        ${r.c.issuer ? `<span>${escapeHtml(r.c.issuer)}</span>` : ''}
                        ${sansStr ? `<span class="cert-sans" title="${escapeHtml(r.c.sans)}">${escapeHtml(sansStr)}</span>` : ''}
                        ${showHostTag ? `<span class="cert-host-tag" style="color:${color}">${escapeHtml(r.disp)}</span>` : ''}
                    </div>
                </div>
                <div class="cert-right">
                    <span class="cert-days ${badgeCls}">${daysLabel}</span>
                    <span class="cert-expires mono">${expiresStr}</span>
                </div>
            </div>`;
    }).join('');
}

// ===== XLog =====
function addRequestsFromSnapshot(snap) {
    if (!snap.requests || !snap.requests.length) return;
    snap.requests.forEach(r => state.xlog.push({ ...r, hostId: snap.hostId }));
    while (state.xlog.length > MAX_XLOG) state.xlog.shift();
}

function scopedXlog() {
    const arr = state.scope === 'all' ? state.xlog : state.xlog.filter(r => r.hostId === state.scope);
    return arr.slice().sort((a, b) => b.timestamp - a.timestamp);
}

function renderXlog() {
    const data = scopedXlog();
    const tbody = document.querySelector('#xlog-table tbody');
    const sub = document.getElementById('xlog-sub');
    const latencyEl = document.getElementById('xlog-latency');
    const empty = document.getElementById('xlog-empty');

    sub.textContent = `${data.length}개 요청`;

    const avgElapsed = data.length
        ? Math.round(data.reduce((s, r) => s + (r.elapsedMs || 0), 0) / data.length)
        : null;
    if (latencyEl) {
        latencyEl.textContent = avgElapsed == null ? 'avg -- ms' : `avg ${avgElapsed} ms`;
    }

    if (!data.length) {
        tbody.innerHTML = '';
        empty.style.display = 'block';
    } else {
        empty.style.display = 'none';
        const sliced = data.slice(0, 150);
        state.xlogRendered = sliced;
        tbody.innerHTML = sliced.map((r, idx) => {
            const statusCls = `status-${Math.floor(r.status / 100)}`;
            const methodCls = `method-${r.method || 'GET'}`;
            const elapsedCls = r.elapsedMs >= 2000 ? 'very-slow' : r.elapsedMs >= 500 ? 'slow' : '';
            const elapsedStr = r.elapsedMs >= 1000
                ? (r.elapsedMs / 1000).toFixed(2) + ' s'
                : r.elapsedMs + ' ms';
            let rowCls = 'clickable';
            if (r.status >= 500) rowCls += ' row-err';
            else if (r.status >= 400) rowCls += ' row-warn';
            return `
            <tr class="${rowCls}" data-xlog-idx="${idx}">
                <td class="xlog-time">${fmtTime(r.timestamp)}</td>
                <td><span class="method-pill ${methodCls}">${escapeHtml(r.method || '-')}</span></td>
                <td class="xlog-path" title="${escapeHtml(r.path)}">${escapeHtml(r.path || '-')}</td>
                <td class="num"><span class="status-badge ${statusCls}">${r.status || '-'}</span></td>
                <td class="num xlog-elapsed ${elapsedCls}">${elapsedStr}</td>
                <td class="xlog-ip">${escapeHtml(r.remoteIp || '-')}</td>
            </tr>`;
        }).join('');
    }
    rebuildScatter();
}

function rebuildScatter() {
    const ch = state.charts.scatter;
    if (!ch) return;
    const data = scopedXlog();
    const byHost = {};
    data.forEach(r => {
        const id = r.hostId || '_';
        if (!byHost[id]) byHost[id] = [];
        byHost[id].push({ x: r.timestamp, y: r.elapsedMs, r });
    });
    ch.data.datasets = Object.entries(byHost).map(([id, pts]) => ({
        label: state.hosts[id]?.displayName || id,
        data: pts,
        backgroundColor: colorForHost(id),
        pointRadius: 4,
        pointHoverRadius: 6
    }));
    ch.update('none');
}

// ===== Aggregate =====
function scopedSnaps() {
    if (state.scope === 'all') return Object.values(state.hosts);
    return state.hosts[state.scope] ? [state.hosts[state.scope]] : [];
}

// ===== Alarms =====
function renderAlarms() {
    const strip = document.getElementById('alarm-strip');
    const active = state.alarmsActive || [];
    if (!active.length) {
        strip.style.display = 'none';
        strip.innerHTML = '';
        return;
    }
    strip.style.display = 'flex';
    strip.innerHTML = active.slice(0, 5).map(a => {
        const sev = (a.severity || 'WARN').toLowerCase();
        const ackCls = a.acknowledged ? 'acked' : '';
        const ackBtn = a.acknowledged
            ? `<span class="alarm-acked" title="확인: ${escapeHtml(a.acknowledgedBy || '')}">✓ ${escapeHtml(a.acknowledgedBy || '')}</span>`
            : `<button class="alarm-ack-btn" data-ack-id="${escapeHtml(a.id)}" title="확인(ack)">ack</button>`;
        return `
        <div class="alarm-chip sev-${sev} ${ackCls}">
            <span class="alarm-sev-dot"></span>
            <span class="alarm-host">${escapeHtml(a.hostName || a.hostId || '')}</span>
            <span class="alarm-type">${escapeHtml(a.type || '')}</span>
            <span class="alarm-msg">${escapeHtml(a.message || '')}</span>
            <span class="alarm-time mono">${fmtTime(new Date(a.firedAt).getTime())}</span>
            ${ackBtn}
        </div>`;
    }).join('') + (active.length > 5 ? `<span class="alarm-more">+${active.length - 5}</span>` : '');

    strip.querySelectorAll('.alarm-ack-btn').forEach(btn => {
        btn.addEventListener('click', async () => {
            const id = btn.dataset.ackId;
            btn.disabled = true;
            btn.textContent = '...';
            try {
                const r = await fetch(`/api/alarms/${encodeURIComponent(id)}/ack`, { method: 'POST' });
                if (!r.ok) throw new Error('ack failed');
            } catch (e) {
                console.warn('ack failed', e);
                btn.disabled = false;
                btn.textContent = 'ack';
            }
        });
    });
}

// ===== Endpoints =====
async function fetchEndpoints() {
    try {
        const hostId = state.scope === 'all' ? '' : state.scope;
        const url = '/api/endpoints?windowSec=300' + (hostId ? '&hostId=' + encodeURIComponent(hostId) : '');
        const r = await fetch(url);
        state.endpoints = await r.json().catch(() => []);
        renderEndpoints();
    } catch (e) {
        console.warn('endpoints fetch failed', e);
    }
}

function renderEndpoints() {
    const tbody = document.querySelector('#endpoints-table tbody');
    const sub = document.getElementById('endpoints-sub');
    const empty = document.getElementById('endpoints-empty');
    const rows = state.endpoints || [];
    sub.textContent = `${rows.length}개 엔드포인트 · 최근 5분`;
    if (!rows.length) {
        tbody.innerHTML = '';
        empty.style.display = 'block';
        return;
    }
    empty.style.display = 'none';
    tbody.innerHTML = rows.slice(0, 50).map(e => {
        const errCls = e.errorRatePct >= 5 ? 'danger' : e.errorRatePct > 0 ? 'warn' : '';
        const p95Cls = e.p95Ms >= 2000 ? 'very-slow' : e.p95Ms >= 500 ? 'slow' : '';
        return `
        <tr>
            <td><span class="method-pill method-${escapeHtml(e.method || 'GET')}">${escapeHtml(e.method || '-')}</span></td>
            <td class="ep-path mono" title="${escapeHtml(e.pathPattern)}">${escapeHtml(e.pathPattern || '-')}</td>
            <td class="num mono">${e.count}</td>
            <td class="num mono">${e.avgElapsedMs} ms</td>
            <td class="num mono ${p95Cls}">${e.p95Ms} ms</td>
            <td class="num mono">${e.p99Ms} ms</td>
            <td class="num mono">${e.maxElapsedMs} ms</td>
            <td class="num mono ep-err ${errCls}">${e.errorRatePct.toFixed(1)}%</td>
        </tr>`;
    }).join('');
}

async function fetchStatusDistribution() {
    try {
        const hostId = state.scope === 'all' ? '' : state.scope;
        const url = '/api/status-distribution?windowSec=300&bucketSec=15' + (hostId ? '&hostId=' + encodeURIComponent(hostId) : '');
        const r = await fetch(url);
        state.statusBuckets = await r.json().catch(() => []);
        rebuildStatusChart();
    } catch (e) {
        console.warn('status distribution fetch failed', e);
    }
}

// ===== Network & Ports =====
function renderNetPorts() {
    const kpi = document.getElementById('netports-kpi');
    const portsList = document.getElementById('ports-list');
    const sub = document.getElementById('netports-sub');
    const snaps = scopedSnaps();

    let totalRx = 0, totalTx = 0, totalTcp = 0;
    const allPorts = [];
    snaps.forEach(s => {
        totalRx += s.host?.netRxBps || 0;
        totalTx += s.host?.netTxBps || 0;
        totalTcp += s.host?.tcpEstablished || 0;
        (s.host?.listenPorts || []).forEach(p => allPorts.push({ ...p, hostId: s.hostId, disp: s.displayName || s.hostId }));
    });

    kpi.innerHTML = `
        <div class="netports-stat">
            <div class="netports-k">TCP 연결 (ESTABLISHED)</div>
            <div class="netports-v mono">${totalTcp}</div>
        </div>
        <div class="netports-stat">
            <div class="netports-k">RX</div>
            <div class="netports-v mono">${fmtBytes(totalRx)}/s</div>
        </div>
        <div class="netports-stat">
            <div class="netports-k">TX</div>
            <div class="netports-v mono">${fmtBytes(totalTx)}/s</div>
        </div>`;

    sub.textContent = `${totalTcp} 연결 · ${allPorts.length} LISTEN`;

    if (!allPorts.length) {
        portsList.innerHTML = '<div class="empty-state" style="padding:16px">포트 정보 없음 (에이전트에 ss 명령어 필요)</div>';
        return;
    }
    allPorts.sort((a, b) => a.port - b.port);
    const showHost = state.scope === 'all';
    portsList.innerHTML = allPorts.map(p => `
        <div class="port-row">
            <span class="port-num mono">${p.port}</span>
            <span class="port-proto">${escapeHtml(p.proto || 'tcp')}</span>
            <span class="port-proc">${escapeHtml(p.process || '-')}</span>
            ${p.pid ? `<span class="port-pid mono">PID ${p.pid}</span>` : ''}
            ${showHost ? `<span class="port-host" style="color:${colorForHost(p.hostId)}">${escapeHtml(p.disp)}</span>` : ''}
        </div>`).join('');
}

// ===== Main render =====
function renderAll() {
    renderHostList();
    updateSummary();
    renderScopeBanner();
    renderKpi();
    rebuildCharts();
    renderProcs();
    renderDisks();
    renderCerts();
    renderNetPorts();
    renderEndpoints();
    renderAlarms();
    renderXlog();
    renderProbes();
    renderHistoryChart();
    syncModalChart();
}

function applySnapshot(snap) {
    const id = snap.hostId;
    state.hosts[id] = snap;
    if (!state.history[id]) state.history[id] = [];
    state.history[id].push(snap);
    while (state.history[id].length > MAX_POINTS) state.history[id].shift();
    addRequestsFromSnapshot(snap);
    renderAll();
}

// ===== Connection =====
function setConnection(c) {
    const dot = document.getElementById('pulse-dot');
    const label = document.getElementById('connection-status');
    const wrap = label.parentElement;
    dot.className = 'conn-dot ' + (c ? 'connected' : 'disconnected');
    label.textContent = c ? '실시간 연결됨' : '연결 끊김';
    wrap.className = 'conn ' + (c ? 'connected' : 'disconnected');
}

async function loadInitial() {
    try {
        const [metricsRes, xlogRes, alarmsRes] = await Promise.all([
            fetch('/api/metrics'),
            fetch('/api/xlog'),
            fetch('/api/alarms')
        ]);
        const list = await metricsRes.json();
        const xlogList = await xlogRes.json().catch(() => []);
        const alarms = await alarmsRes.json().catch(() => ({ active: [], history: [] }));

        list.forEach(snap => {
            const id = snap.hostId;
            state.hosts[id] = snap;
            state.history[id] = [snap];
        });
        state.xlog = (xlogList || []).slice().sort((a, b) => a.timestamp - b.timestamp).slice(-MAX_XLOG);
        state.alarmsActive = alarms.active || [];
        state.alarmsHistory = alarms.history || [];
        await Promise.all([fetchEndpoints(), fetchStatusDistribution(), fetchSlowQueries(), fetchProbes(), fetchHistoryForScope()]);
        renderAll();
    } catch (e) {
        console.warn('Initial load failed:', e);
    }
}

function scheduleAnalyticsRefresh() {
    setInterval(() => {
        fetchEndpoints();
        fetchStatusDistribution();
        fetchSlowQueries();
        fetchProbes();
    }, 15000);
    setInterval(() => {
        fetchHistoryForScope();
    }, 60000);
}

// ===== Probes =====
async function fetchProbes() {
    try {
        const r = await fetch('/api/probes');
        state.probes = (await r.json()) || [];
        renderProbes();
    } catch (e) {
        console.warn('probes fetch failed:', e);
    }
}

function renderProbes() {
    const card = document.getElementById('probes-card');
    const list = document.getElementById('probes-list');
    const sub = document.getElementById('probes-sub');
    if (!state.probes || state.probes.length === 0) {
        card.style.display = 'none';
        return;
    }
    card.style.display = '';
    const up = state.probes.filter(p => p.status === 'UP').length;
    const slow = state.probes.filter(p => p.status === 'SLOW').length;
    const down = state.probes.filter(p => p.status === 'DOWN').length;
    sub.textContent = `${state.probes.length}개 · UP ${up} · SLOW ${slow} · DOWN ${down}`;
    list.innerHTML = state.probes.map(p => {
        const cls = p.status === 'UP' ? 'up' : (p.status === 'SLOW' ? 'warn' : 'down');
        const err = p.error ? ` · <span class="probe-err">${escapeHtml(p.error)}</span>` : '';
        const statusText = p.statusCode ? `HTTP ${p.statusCode}` : p.type.toUpperCase();
        return `<div class="probe-row probe-${cls}">
            <span class="probe-dot"></span>
            <span class="probe-name">${escapeHtml(p.name)}</span>
            <span class="probe-target mono">${escapeHtml(p.target)}</span>
            <span class="probe-meta">${statusText} · ${p.elapsedMs}ms${err}</span>
        </div>`;
    }).join('');
}

// ===== History =====
function historyTargetHostId() {
    if (state.scope && state.scope !== 'all') return state.scope;
    const hostIds = Object.keys(state.hosts).sort();
    return hostIds.length ? hostIds[0] : null;
}

async function fetchHistoryForScope() {
    if (state.scope === 'all') {
        state.historyData = [];
        document.getElementById('history-sub').textContent = '호스트를 선택하세요';
        renderHistoryChart();
        return;
    }
    const hostId = state.scope;
    const rangeMs = RANGE_MS[state.historyRange] || RANGE_MS['6h'];
    const to = Date.now();
    const from = to - rangeMs;
    try {
        const r = await fetch(`/api/metrics/${encodeURIComponent(hostId)}/range?from=${from}&to=${to}&maxPoints=300`);
        state.historyData = (await r.json()) || [];
        document.getElementById('history-sub').textContent =
            `${state.hosts[hostId]?.displayName || hostId} · ${state.historyData.length} points · ${state.historyRange}`;
        renderHistoryChart();
    } catch (e) {
        console.warn('history fetch failed:', e);
    }
}

function historyBaseConfig(title, datasets, yMax, yUnit) {
    return {
        type: 'line',
        data: { labels: [], datasets: datasets.map(d => ({
            label: d.label, data: [], borderColor: d.color, backgroundColor: d.color + '22',
            borderWidth: 1.5, pointRadius: 0, tension: 0.25, fill: datasets.length === 1
        })) },
        options: {
            animation: false, responsive: true, maintainAspectRatio: false,
            plugins: {
                legend: { display: datasets.length > 1, labels: { color: '#cbd5e1' } },
                title: { display: true, text: title, color: '#e2e8f0', font: { size: 12, weight: 'normal' } },
                tooltip: { mode: 'index', intersect: false }
            },
            interaction: { mode: 'index', intersect: false },
            scales: {
                x: { ticks: { color: '#64748b', maxTicksLimit: 6, autoSkip: true }, grid: { color: '#1e293b' } },
                y: {
                    min: 0, max: yMax,
                    ticks: {
                        color: '#64748b',
                        callback: yUnit === 'bytes'
                            ? (v) => fmtBytes(v) + '/s'
                            : (v) => v
                    },
                    grid: { color: '#1e293b' }
                }
            }
        }
    };
}

function renderHistoryChart() {
    const empty = document.getElementById('history-empty');
    const data = state.historyData || [];
    if (!state.charts.historyCpu) {
        const cpuCanvas = document.getElementById('history-cpu');
        const memCanvas = document.getElementById('history-mem');
        const loadCanvas = document.getElementById('history-load');
        const netCanvas = document.getElementById('history-net');
        if (!cpuCanvas || !memCanvas || !loadCanvas || !netCanvas) return;
        state.charts.historyCpu = new Chart(cpuCanvas,
            historyBaseConfig('CPU %', [{ label: 'CPU %', color: '#38bdf8' }], 100));
        state.charts.historyMem = new Chart(memCanvas,
            historyBaseConfig('Memory %', [{ label: 'Memory %', color: '#a78bfa' }], 100));
        state.charts.historyLoad = new Chart(loadCanvas,
            historyBaseConfig('Load 1m', [{ label: 'Load 1m', color: '#fbbf24' }], undefined));
        state.charts.historyNet = new Chart(netCanvas,
            historyBaseConfig('Network', [
                { label: 'RX', color: '#34d399' },
                { label: 'TX', color: '#f472b6' }
            ], undefined, 'bytes'));
    }
    if (data.length === 0) {
        empty.style.display = '';
        ['historyCpu', 'historyMem', 'historyLoad', 'historyNet'].forEach(k => {
            const c = state.charts[k];
            c.data.labels = [];
            c.data.datasets.forEach(ds => ds.data = []);
            c.update('none');
        });
        return;
    }
    empty.style.display = 'none';
    const labels = data.map(p => fmtTimeShort(p.ts));
    state.charts.historyCpu.data.labels = labels;
    state.charts.historyCpu.data.datasets[0].data = data.map(p => p.cpu);
    state.charts.historyCpu.update('none');
    state.charts.historyMem.data.labels = labels;
    state.charts.historyMem.data.datasets[0].data = data.map(p => p.mem);
    state.charts.historyMem.update('none');
    state.charts.historyLoad.data.labels = labels;
    state.charts.historyLoad.data.datasets[0].data = data.map(p => p.load1);
    state.charts.historyLoad.update('none');
    state.charts.historyNet.data.labels = labels;
    state.charts.historyNet.data.datasets[0].data = data.map(p => p.rxBps);
    state.charts.historyNet.data.datasets[1].data = data.map(p => p.txBps);
    state.charts.historyNet.update('none');
}

document.getElementById('history-range').addEventListener('click', (e) => {
    const btn = e.target.closest('.range-btn');
    if (!btn) return;
    state.historyRange = btn.dataset.range;
    document.querySelectorAll('#history-range .range-btn')
        .forEach(b => b.classList.toggle('active', b === btn));
    fetchHistoryForScope();
});

// ===== Slow queries =====
async function fetchSlowQueries() {
    try {
        const hostId = state.scope === 'all' ? '' : state.scope;
        const url = '/api/slow-queries?windowSec=900' + (hostId ? '&hostId=' + encodeURIComponent(hostId) : '');
        const r = await fetch(url);
        state.slowQueries = await r.json().catch(() => []);
        renderSlowQueries();
    } catch (e) {
        console.warn('slow-queries fetch failed', e);
    }
}

function renderSlowQueries() {
    const tbody = document.querySelector('#slowq-table tbody');
    const sub = document.getElementById('slowq-sub');
    const empty = document.getElementById('slowq-empty');
    const rows = state.slowQueries || [];
    sub.textContent = `${rows.length}개 패턴 · 최근 15분`;
    if (!rows.length) {
        tbody.innerHTML = '';
        empty.style.display = 'block';
        return;
    }
    empty.style.display = 'none';
    tbody.innerHTML = rows.slice(0, 50).map((q, i) => {
        const pCls = q.maxElapsedMs >= 5000 ? 'very-slow' : q.maxElapsedMs >= 1000 ? 'slow' : '';
        const rowsCls = q.maxRowsExamined >= 100000 ? 'danger' : q.maxRowsExamined >= 10000 ? 'warn' : '';
        const last = q.lastSeen ? fmtTime(q.lastSeen) : '-';
        const pat = (q.pattern || '').length > 120 ? (q.pattern.slice(0, 117) + '...') : (q.pattern || '-');
        return `
        <tr data-slowq-idx="${i}">
            <td class="sq-pat mono" title="${escapeHtml(q.pattern || '')}">${escapeHtml(pat)}</td>
            <td class="num mono">${q.count}</td>
            <td class="num mono">${q.avgElapsedMs} ms</td>
            <td class="num mono">${q.p95Ms} ms</td>
            <td class="num mono ${pCls}">${q.maxElapsedMs} ms</td>
            <td class="num mono sq-rows ${rowsCls}">${fmtNum(q.maxRowsExamined)}</td>
            <td class="sq-db">${escapeHtml(q.database || '-')}</td>
            <td class="num mono sq-last">${last}</td>
        </tr>`;
    }).join('');
}

function fmtNum(n) {
    if (n == null) return '-';
    if (n >= 1000000) return (n / 1000000).toFixed(1) + 'M';
    if (n >= 1000) return (n / 1000).toFixed(1) + 'K';
    return String(n);
}

function openSlowQueryDetail(stat) {
    if (!stat) return;
    const body = document.getElementById('detail-modal-body');
    const title = document.getElementById('detail-modal-title');
    if (title) title.textContent = '느린 쿼리 상세';
    const last = stat.lastSeen ? new Date(stat.lastSeen).toLocaleString() : '-';
    const pCls = stat.maxElapsedMs >= 5000 ? 'very-slow' : stat.maxElapsedMs >= 1000 ? 'slow' : '';
    body.innerHTML = `
        <div class="detail-banner ${stat.maxElapsedMs >= 5000 ? 'banner-err' : stat.maxElapsedMs >= 1000 ? 'banner-warn' : ''}">
            ${stat.maxElapsedMs >= 5000 ? '매우 느림 (≥ 5s)' : stat.maxElapsedMs >= 1000 ? '느림 (≥ 1s)' : '슬로우 쿼리'}
        </div>
        <div class="detail-grid">
            <div class="detail-field">
                <div class="detail-k">호출 건수</div>
                <div class="detail-v mono">${stat.count}</div>
            </div>
            <div class="detail-field">
                <div class="detail-k">DB</div>
                <div class="detail-v">${escapeHtml(stat.database || '-')}</div>
            </div>
            <div class="detail-field">
                <div class="detail-k">평균</div>
                <div class="detail-v mono">${stat.avgElapsedMs} ms</div>
            </div>
            <div class="detail-field">
                <div class="detail-k">p50 / p95 / p99</div>
                <div class="detail-v mono">${stat.p50Ms} / ${stat.p95Ms} / ${stat.p99Ms} ms</div>
            </div>
            <div class="detail-field">
                <div class="detail-k">최대</div>
                <div class="detail-v mono ${pCls}">${stat.maxElapsedMs} ms</div>
            </div>
            <div class="detail-field">
                <div class="detail-k">검사 행수 (최대/합계)</div>
                <div class="detail-v mono">${fmtNum(stat.maxRowsExamined)} / ${fmtNum(stat.totalRowsExamined)}</div>
            </div>
            <div class="detail-field detail-wide">
                <div class="detail-k">마지막 실행</div>
                <div class="detail-v mono">${last}</div>
            </div>
            <div class="detail-field detail-wide">
                <div class="detail-k">정규화 패턴</div>
                <div class="detail-v mono detail-reqline">${escapeHtml(stat.pattern || '-')}</div>
            </div>
            <div class="detail-field detail-wide">
                <div class="detail-k">샘플 SQL</div>
                <pre class="detail-sql">${escapeHtml(stat.sample || '-')}</pre>
            </div>
        </div>`;
    document.getElementById('detail-modal').style.display = 'flex';
}

function connect() {
    const sock = new SockJS('/ws');
    const stomp = Stomp.over(sock);
    stomp.debug = null;
    stomp.connect({}, () => {
        setConnection(true);
        stomp.subscribe('/topic/metrics', f => applySnapshot(JSON.parse(f.body)));
        stomp.subscribe('/topic/alarms', f => applyAlarm(JSON.parse(f.body)));
    }, () => {
        setConnection(false);
        setTimeout(connect, 3000);
    });
}

function applyAlarm(alarm) {
    const idx = state.alarmsActive.findIndex(a => a.id === alarm.id);
    if (alarm.state === 'FIRING') {
        if (idx >= 0) state.alarmsActive[idx] = alarm;
        else state.alarmsActive.push(alarm);
    } else if (alarm.state === 'RESOLVED') {
        if (idx >= 0) state.alarmsActive.splice(idx, 1);
    }
    state.alarmsHistory.unshift(alarm);
    state.alarmsHistory = state.alarmsHistory.slice(0, 200);
    renderAlarms();
}

// ===== Chart modal =====
let modalChart = null;
let modalChartKey = null;

function syncModalChart() {
    if (!modalChart || !modalChartKey) return;
    const source = state.charts[modalChartKey];
    if (!source) return;
    if (source.config.type === 'scatter') {
        modalChart.data.datasets = source.data.datasets.map(ds => ({
            ...ds,
            data: ds.data.map(p => ({ x: p.x, y: p.y, r: p.r })),
            pointRadius: 5,
            pointHoverRadius: 8
        }));
    } else {
        modalChart.data.labels = source.data.labels.slice();
        modalChart.data.datasets = source.data.datasets.map(ds => ({ ...ds, data: ds.data.slice() }));
    }
    modalChart.update('none');
}

function openChartModal(chartKey, title) {
    const source = state.charts[chartKey];
    if (!source) return;

    document.getElementById('chart-modal-title').textContent = title || '차트';
    const modal = document.getElementById('chart-modal');
    modal.style.display = 'flex';

    if (modalChart) {
        modalChart.destroy();
        modalChart = null;
    }
    const canvas = document.getElementById('chart-modal-canvas');

    const cfg = {
        type: source.config.type,
        data: JSON.parse(JSON.stringify(source.data)),
        options: JSON.parse(JSON.stringify(source.options))
    };
    // scatter chart의 raw 데이터는 serialize가 안되서 직접 복사
    if (source.config.type === 'scatter') {
        cfg.data.datasets = source.data.datasets.map(ds => ({
            ...ds,
            data: ds.data.map(p => ({ x: p.x, y: p.y, r: p.r })),
            pointRadius: 5,
            pointHoverRadius: 8
        }));
        cfg.options.plugins.tooltip = source.options.plugins.tooltip;
        cfg.options.scales.x.ticks.callback = source.options.scales.x.ticks.callback;
        cfg.options.onClick = (evt, elements, chart) => {
            if (!elements.length) return;
            const el = elements[0];
            const point = chart.data.datasets[el.datasetIndex].data[el.index];
            if (point && point.r) openDetailModal(point.r);
        };
        cfg.options.onHover = (evt, elements) => {
            if (evt.native && evt.native.target) {
                evt.native.target.style.cursor = elements.length ? 'pointer' : 'default';
            }
        };
    }
    // 확대 시 스타일 조정
    if (cfg.options.plugins?.legend) {
        cfg.options.plugins.legend.labels = cfg.options.plugins.legend.labels || {};
        cfg.options.plugins.legend.labels.font = { family: 'Inter', size: 13, weight: '500' };
        cfg.options.plugins.legend.labels.padding = 14;
    }
    if (cfg.options.scales?.x?.ticks) cfg.options.scales.x.ticks.font = { family: 'JetBrains Mono', size: 12 };
    if (cfg.options.scales?.y?.ticks) cfg.options.scales.y.ticks.font = { family: 'JetBrains Mono', size: 12 };
    if (cfg.options.elements?.line) cfg.options.elements.line.borderWidth = 2.5;

    modalChart = new Chart(canvas, cfg);
    modalChartKey = chartKey;
}

function closeChartModal() {
    const modal = document.getElementById('chart-modal');
    modal.style.display = 'none';
    if (modalChart) {
        modalChart.destroy();
        modalChart = null;
    }
    modalChartKey = null;
}

document.querySelectorAll('.expand-btn').forEach(btn => {
    btn.addEventListener('click', (e) => {
        e.stopPropagation();
        openChartModal(btn.dataset.chart, btn.dataset.title);
    });
});
document.getElementById('chart-modal-backdrop').addEventListener('click', closeChartModal);
document.getElementById('chart-modal-close').addEventListener('click', closeChartModal);
document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
        closeChartModal();
        closeDetailModal();
    }
});

// ===== Detail modal (XLog row) =====
function openDetailModal(req) {
    if (!req) return;
    const body = document.getElementById('detail-modal-body');
    const title = document.getElementById('detail-modal-title');
    if (title) title.textContent = 'API 요청 상세';
    const host = state.hosts[req.hostId];
    const hostName = host?.displayName || req.hostId || '-';
    const statusLevel = Math.floor((req.status || 0) / 100);
    const statusCls = `status-${statusLevel}`;
    const elapsedMs = req.elapsedMs || 0;
    const elapsedStr = elapsedMs >= 1000 ? (elapsedMs / 1000).toFixed(3) + ' s' : elapsedMs + ' ms';
    const elapsedCls = elapsedMs >= 2000 ? 'very-slow' : elapsedMs >= 500 ? 'slow' : '';
    const severityBanner = req.status >= 500 ? 'banner-err' : req.status >= 400 ? 'banner-warn' : '';
    const severityText = req.status >= 500 ? '서버 오류 (5xx)' : req.status >= 400 ? '클라이언트 오류 (4xx)' : req.status >= 300 ? '리다이렉트' : '정상 응답';
    const ts = new Date(req.timestamp);
    const tsStr = `${ts.getFullYear()}-${pad2(ts.getMonth()+1)}-${pad2(ts.getDate())} ${pad2(ts.getHours())}:${pad2(ts.getMinutes())}:${pad2(ts.getSeconds())}.${String(ts.getMilliseconds()).padStart(3,'0')}`;

    body.innerHTML = `
        ${severityBanner ? `<div class="detail-banner ${severityBanner}">${severityText}</div>` : ''}
        <div class="detail-summary">
            <span class="method-pill method-${req.method || 'GET'}">${escapeHtml(req.method || '-')}</span>
            <span class="detail-path mono">${escapeHtml(req.path || '-')}</span>
            <span class="status-badge ${statusCls}">${req.status || '-'}</span>
        </div>
        <div class="detail-grid">
            <div class="detail-field">
                <div class="detail-k">호스트</div>
                <div class="detail-v">${escapeHtml(hostName)}</div>
            </div>
            <div class="detail-field">
                <div class="detail-k">시각</div>
                <div class="detail-v mono">${tsStr}</div>
            </div>
            <div class="detail-field">
                <div class="detail-k">응답 시간</div>
                <div class="detail-v mono ${elapsedCls}">${elapsedStr}</div>
            </div>
            <div class="detail-field">
                <div class="detail-k">응답 크기</div>
                <div class="detail-v mono">${req.bytes ? fmtBytes(req.bytes) : '-'}</div>
            </div>
            <div class="detail-field">
                <div class="detail-k">원격 IP</div>
                <div class="detail-v mono">${escapeHtml(req.remoteIp || '-')}</div>
            </div>
            <div class="detail-field">
                <div class="detail-k">상태</div>
                <div class="detail-v">${req.status || '-'} · ${severityText}</div>
            </div>
            <div class="detail-field detail-wide">
                <div class="detail-k">로그 소스</div>
                <div class="detail-v mono" title="${escapeHtml(req.source || '')}">${escapeHtml(req.source || '-')}</div>
            </div>
            <div class="detail-field detail-wide">
                <div class="detail-k">요청 라인</div>
                <div class="detail-v mono detail-reqline">${escapeHtml(req.method || '-')} ${escapeHtml(req.path || '-')}</div>
            </div>
        </div>`;

    document.getElementById('detail-modal').style.display = 'flex';
}

function closeDetailModal() {
    document.getElementById('detail-modal').style.display = 'none';
}

document.querySelector('#xlog-table tbody').addEventListener('click', (e) => {
    const tr = e.target.closest('tr[data-xlog-idx]');
    if (!tr) return;
    const idx = parseInt(tr.dataset.xlogIdx, 10);
    const arr = state.xlogRendered || [];
    if (idx >= 0 && idx < arr.length) openDetailModal(arr[idx]);
});
document.querySelector('#slowq-table tbody').addEventListener('click', (e) => {
    const tr = e.target.closest('tr[data-slowq-idx]');
    if (!tr) return;
    const idx = parseInt(tr.dataset.slowqIdx, 10);
    const arr = state.slowQueries || [];
    if (idx >= 0 && idx < arr.length) openSlowQueryDetail(arr[idx]);
});
document.getElementById('detail-modal-backdrop').addEventListener('click', closeDetailModal);
document.getElementById('detail-modal-close').addEventListener('click', closeDetailModal);

initCharts();
loadInitial().then(() => { connect(); scheduleAnalyticsRefresh(); });
