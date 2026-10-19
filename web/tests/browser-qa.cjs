/* Optional browser QA. Install playwright-core outside the viewer or in your test environment.
 * QBM_PLAYWRIGHT_PATH selects that module; QBM_BROWSER_PATH selects an installed Chromium browser.
 * The shipped viewer has no package/runtime dependencies. */
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { pathToFileURL } = require('node:url');
const zlib = require('node:zlib');
const { chromium } = require(process.env.QBM_PLAYWRIGHT_PATH || 'playwright-core');
const core = require('../log-core.js');
const output = process.env.QBM_QA_OUTPUT || path.join(os.tmpdir(), 'qbm-viewer-qa');
fs.mkdirSync(output, { recursive: true });
const entry = pathToFileURL(path.resolve(__dirname, '../index.html')).href;
const executablePath = process.env.QBM_BROWSER_PATH || (process.platform === 'win32' ? 'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe' : undefined);
const demo = core.demoText();
const compressed = path.join(output, 'synthetic-recording.jsonl.gz');
const second = path.join(output, 'synthetic-duplicate.jsonl.gz');
const bad = path.join(output, 'invalid.jsonl.gz');
fs.writeFileSync(compressed, zlib.gzipSync(demo)); fs.copyFileSync(compressed, second);
fs.writeFileSync(bad, Buffer.from([0x1f, 0x8b, 0, 0, 1]));
const reportFile = path.join(output, 'synthetic-nbtc-report.json');
const t0 = Date.UTC(2026, 9, 20, 3);
const dualRecords = [];
for (const sim of [1, 2]) {
  const sessionId = 'qa-session-' + sim, tNs = String(1000000000 + (sim - 1) * 1000000000);
  dualRecords.push({ type: 'header', format: 'qbm-log', version: 1, sessionId, segment: 0,
    startedUtcMs: t0, startedElapsedNs: '1000000000', device: { model: 'Synthetic SIM ' + sim } });
  dualRecords.push({ type: 'ui', sessionId, archiveSeq: 1, segment: 0, tNs, sim,
    snapshot: { sim, modeLabel: sim === 1 ? 'LTE' : 'NR SA', operatorName: 'Synthetic operator', cells: [],
      carrierGroups: [{ title: 'Synthetic carrier', carriers: [{ role: 'P', band: sim === 1 ? 'B3' : 'n78', channel: '640000', pci: 42,
        nr: { rsrp: -85, sinr: 18, cqi: 12, mbps: 80, mcs: 18, layers: 2 } }] }],
      antennas: [{ rat: 'NR', chains: [{ chain: 0, tuned: true, rsrp: -84 }, { chain: 1, tuned: false, rsrp: -100 }] }],
      neighbors: [{ rat: 'NR', channelLabel: 'ARFCN', channel: 640020, idLabel: 'PCI', id: '43', band: { label: 'n78' }, metrics: [{ label: 'RSRP', value: -94, unit: 'dBm' }] }] },
    updates: [{ entity: sim === 1 ? 'lte:0' : 'nr:0', metric: 'rsrp', value: sim === 1 ? -95 : -85, unit: 'dBm', aggregation: 'last' },
      { entity: sim === 1 ? 'lte:0' : 'nr:0', metric: 'sinr', value: sim === 1 ? 8 : 18, unit: 'dB', aggregation: 'last' }] });
  dualRecords.push({ type: 'footer', sessionId, segment: 0, archiveSeq: 2, tNs });
}
const dualGzip = zlib.gzipSync(dualRecords.map(record => JSON.stringify(record)).join('\n') + '\n');
const categoryFile = path.join(output, 'synthetic-categories.jsonl.gz');
const categoryId = 'qa-categories';
const categories = [{ type: 'header', format: 'qbm-log', version: 1, sessionId: categoryId, segment: 0,
  startedUtcMs: t0, startedElapsedNs: '1000000000', device: { model: 'Synthetic categorical fixture' } }];
for (const [index, [offset, rrc, modulation, active]] of [[0, 'Connected', 'QPSK', true], [1000, 'Idle', '64QAM', false],
  [1500, null, null, null], [2000, 'Connected', '64QAM', true]].entries()) {
  categories.push({ type: 'packet', sessionId: categoryId, archiveSeq: index + 1, seq: index + 1, captureId: 'categories', segment: 0,
    tNs: String(1000000000 + offset * 1000000), sim: 1, updates: [
      { entity: 'lte:0', metric: 'rrc_state', value: rrc, aggregation: 'last' },
      { entity: 'lte:0', metric: 'modulation', value: modulation, aggregation: 'last' },
      { entity: 'lte:0', metric: 'carrier_active', value: active, aggregation: 'last' }] });
}
categories.push({ type: 'footer', sessionId: categoryId, segment: 0, archiveSeq: 5, tNs: '3000000000' });
fs.writeFileSync(categoryFile, zlib.gzipSync(categories.map(record => JSON.stringify(record)).join('\n') + '\n'));
fs.writeFileSync(reportFile, JSON.stringify({ schemaVersion: 2, id: 'browser-qa-nbtc',
  config: { kind: 'PING', regime: 'NBTC', custom: false, profile: 'LTE', repetitions: 10, intervalSeconds: 1, timeoutSeconds: 5,
    host: '192.0.2.1', referenceServerConfirmed: true, area: 'ROUTE', operator: 'Synthetic operator' },
  startedAtMs: t0, endedAtMs: t0 + 10000, network: 'Synthetic fixture', environment: 'Browser QA',
  attempts: Array.from({ length: 10 }, (_, i) => ({ number: i + 1, startedAtMs: t0 + i * 1000, elapsedRealtimeMs: 10000 + i * 1000,
    outcome: i < 8 ? 'SUCCESS' : 'FAILED', measurement: { bytes: i < 8 ? 32 : 0, durationMs: i < 8 ? 90 : 5000,
      rttMs: i < 8 ? 90 : null, contentVerified: true, measurementAvailable: true, error: i < 8 ? null : 'Synthetic packet loss' } })) }));

(async () => {
  const browser = await chromium.launch({ executablePath, headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 }, acceptDownloads: true });
  const errors = [], external = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('request', request => { if (/^https?:/.test(request.url())) external.push(request.url()); });
  async function ready() { await page.waitForFunction(() => !document.getElementById('loadingBanner').hidden || window.QbmViewer?.getState().loaded); await page.waitForFunction(() => document.getElementById('loadingBanner').hidden && window.QbmViewer?.getState().loaded); }
  async function download(format) {
    await page.locator('#exportFormat').selectOption(format);
    await page.waitForFunction(() => !document.getElementById('downloadButton').disabled);
    const waiting = page.waitForEvent('download'); await page.locator('#downloadButton').click();
    const item = await waiting, destination = path.join(output, item.suggestedFilename());
    await item.saveAs(destination); return fs.readFileSync(destination, 'utf8');
  }
  try {
    await page.goto(entry);
    await page.locator('#dropZone').waitFor({ state: 'visible' });
    await page.screenshot({ path: path.join(output, 'welcome-desktop.png'), fullPage: true });
    await page.locator('#demoButton').click(); await ready();
    await page.waitForFunction(() => document.getElementById('snapshotView').textContent.includes('Synthetic'));
    assert.match(await page.locator('#recordingTag').textContent(), /SYNTHETIC/);
    await page.locator('#scrubber').evaluate(el => { el.value = '12000'; el.dispatchEvent(new Event('input', { bubbles: true })); });
    await page.waitForTimeout(150);
    const cursor = await page.evaluate(() => QbmViewer.getState().cursorMs);
    await page.locator('#playButton').click(); await page.waitForTimeout(300); await page.locator('#playButton').click();
    assert.ok((await page.evaluate(() => QbmViewer.getState().cursorMs)) > cursor);
    await page.screenshot({ path: path.join(output, 'replay-desktop.png'), fullPage: true });
    await page.locator('#sourceMode').selectOption('packet');
    await page.waitForFunction(() => document.getElementById('replayModeBadge').textContent.includes('Packet'));
    await page.locator('#exploreTab').click();
    await page.waitForFunction(() => document.querySelectorAll('#plots canvas').length > 0 && document.querySelectorAll('#statsTable tbody tr').length > 0);
    await page.locator('#rangeStart').fill('8'); await page.locator('#rangeEnd').fill('10');
    await page.waitForTimeout(180);
    const narrowed = await page.evaluate(() => QbmViewer.getState()); assert.equal(narrowed.endMs - narrowed.startMs, 2000);
    const canvas = page.locator('#plots canvas').first(), box = await canvas.boundingBox();
    await page.mouse.move(box.x + box.width * .3, box.y + 90); await page.mouse.down();
    await page.mouse.move(box.x + box.width * .7, box.y + 90, { steps: 6 }); await page.mouse.up();
    await page.waitForTimeout(180);
    const brushed = await page.evaluate(() => QbmViewer.getState()); assert.ok(brushed.endMs - brushed.startMs < 2000);
    await page.locator('#resetRange').click(); await page.waitForTimeout(180);
    await page.screenshot({ path: path.join(output, 'explore-desktop.png'), fullPage: true });
    await page.locator('#exportTab').click(); await page.waitForFunction(() => !document.getElementById('downloadButton').disabled);
    const json = JSON.parse(await download('json')); assert.ok(json.rows.length > 2000, 'Exports must not inherit chart decimation');
    const csv = await download('csv'); assert.ok(csv.split('\n').length > 2000);
    const ndjson = (await download('ndjson')).trim().split('\n').map(line => JSON.parse(line)); assert.ok(ndjson.length > 2000);
    await page.screenshot({ path: path.join(output, 'export-desktop.png'), fullPage: true });
    await page.locator('#fileInput').setInputFiles([compressed, second]); await ready();
    assert.match(await page.locator('#recordingName').textContent(), /2 files/);
    await page.locator('#fileInput').setInputFiles(bad);
    await page.locator('#errorBanner').waitFor({ state: 'visible' });
    assert.ok(await page.evaluate(() => QbmViewer.getState().loaded), 'A failed import must preserve the existing workspace');
    await page.locator('#dismissError').click();
    await page.evaluate(({ bytes }) => {
      const transfer = new DataTransfer(); transfer.items.add(new File([new Uint8Array(bytes)], 'two-sim.jsonl.gz', { type: 'application/gzip' }));
      document.dispatchEvent(new DragEvent('drop', { dataTransfer: transfer, bubbles: true, cancelable: true }));
    }, { bytes: Array.from(dualGzip) });
    await ready();
    await page.locator('#simFilter').selectOption('2'); await page.locator('#sessionFilter').selectOption('qa-session-2');
    await page.locator('#scrubber').evaluate(el => { el.value = el.max; el.dispatchEvent(new Event('input', { bubbles: true })); });
    await page.waitForFunction(() => document.getElementById('snapshotView').textContent.includes('NR SA'));
    assert.match(await page.locator('#snapshotView').textContent(), /CQI/);
    assert.match(await page.locator('#snapshotView').textContent(), /receiver chains/);
    assert.match(await page.locator('#snapshotView').textContent(), /Neighbor cells/);
    assert.match(await page.locator('#readingsView').textContent(), /-85/);
    assert.doesNotMatch(await page.locator('#readingsView').textContent(), /SIM 1/);
    await page.screenshot({ path: path.join(output, 'multi-sim-replay.png'), fullPage: true });
    await page.locator('#fileInput').setInputFiles(reportFile); await ready();
    await page.waitForFunction(() => document.querySelectorAll('#testMetrics tbody tr').length > 0);
    assert.equal(await page.locator('#testRegime').textContent(), 'NBTC');
    assert.match(await page.locator('#testMetrics').textContent(), /80/);
    assert.match(await page.locator('#testMetrics').textContent(), /0 pp/);
    await page.locator('#userTargetPanel summary').click();
    await page.locator('#userTargetComparator').selectOption('<='); await page.locator('#userTargetValue').fill('100');
    await page.locator('#applyUserTarget').click(); await page.waitForFunction(() => document.getElementById('testMetrics').textContent.includes('User target'));
    assert.match(await page.locator('#testMetrics').textContent(), /CUSTOM/);
    await page.screenshot({ path: path.join(output, 'tests-desktop.png'), fullPage: true });
    await page.locator('#exportTab').click();
    const reportJson = JSON.parse(await download('json')); assert.equal(reportJson.rows.length, 10); assert.ok(reportJson.assessment);
    const kotlinFixture = path.resolve(__dirname, '../../app/app/src/test/resources/logs/cross-layer.qbm.ndjson.gz');
    if (fs.existsSync(kotlinFixture)) {
      await page.locator('#fileInput').setInputFiles(kotlinFixture); await ready();
      await page.waitForFunction(() => document.getElementById('snapshotView').textContent.includes('LTE'));
      assert.match(await page.locator('#readingsView').textContent(), /-95|-96|12|10/);
      await page.locator('#sourceMode').selectOption('packet');
      await page.locator('#scrubber').evaluate(el => { el.value = el.max; el.dispatchEvent(new Event('input', { bubbles: true })); });
      await page.waitForFunction(() => document.getElementById('replayModeBadge').textContent.includes('Packet'));
      await page.locator('#exportTab').click();
      const native = JSON.parse(await download('json')); assert.ok(native.rows.length > 0);
    }
    await page.locator('#fileInput').setInputFiles([compressed, reportFile]); await ready();
    await page.locator('#testsTab').click(); await page.locator('#fitTestRange').click(); await page.waitForTimeout(120);
    assert.match(await page.locator('#testRows').textContent(), /SUCCESS/);
    await page.locator('#exportTab').click(); await page.locator('#exportDataset').selectOption('test');
    assert.equal(JSON.parse(await download('json')).rows.length, 10, 'Mixed-date test exports must use the test interval');
    await page.locator('#fileInput').setInputFiles(categoryFile); await ready(); await page.locator('#exploreTab').click();
    await page.waitForFunction(() => document.querySelectorAll('#plots canvas').length === 3);
    assert.match(await page.locator('#plots').textContent(), /Categorical/);
    assert.match(await page.locator('#plots canvas').first().getAttribute('aria-label'), /Connected, Idle/);
    assert.match(await page.locator('#statsTable').textContent(), /Distinct/);
    assert.match(await page.locator('#statsTable').textContent(), /Connected/);
    await page.screenshot({ path: path.join(output, 'categorical-timelines.png'), fullPage: true });
    await page.locator('#exportTab').click();
    const categoricalExport = JSON.parse(await download('json'));
    assert.equal(categoricalExport.rows.length, 4);
    assert.ok(Object.values(categoricalExport.rows[0].values).includes('QPSK'));
    assert.ok(Object.values(categoricalExport.rows[1].values).includes('64QAM'));
    assert.ok(Object.values(categoricalExport.rows[1].values).includes(false));
    assert.ok(Object.values(categoricalExport.rows[2].values).every(value => value === null));
    assert.ok(categoricalExport.rows.every(row => Object.values(row.values).every(value => value !== 0)), 'Categories and unknowns must never be coerced to zero');
    await page.locator('#clearButton').click(); await page.locator('#welcome').waitFor({ state: 'visible' });
    assert.equal(await page.evaluate(() => QbmViewer.getState().loaded), false);
    await page.setViewportSize({ width: 390, height: 844 }); await page.locator('#demoButton').click(); await ready();
    await page.waitForTimeout(100); await page.screenshot({ path: path.join(output, 'replay-mobile.png'), fullPage: true });
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 2), 'Mobile layout must not overflow horizontally');
    await page.locator('#exploreTab').click(); await page.waitForTimeout(150);
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 2));
    await page.screenshot({ path: path.join(output, 'explore-mobile.png'), fullPage: true });
    assert.deepEqual(errors, [], 'No uncaught browser errors'); assert.deepEqual(external, [], 'Offline viewer must make no HTTP(S) requests');
    console.log(JSON.stringify({ passed: true, browser: await browser.version(), exports: json.rows.length,
      assertions: ['file:// startup', 'synthetic demo', 'playback', 'native mode', 'live interval filters', 'chart brush', 'full-resolution JSON/CSV/NDJSON', 'multi-file gzip', 'failed-import recovery', 'gzip drag/drop', 'SIM/session filters', 'carrier/antenna/neighbor replay', 'NBTC report analysis/export', 'percentage-point margins', 'separate user-defined comparison', 'Kotlin-produced gzip import/export', 'mixed capture/report dates', 'categorical state/modulation/boolean plots', 'categorical statistics and lossless export', 'clear', 'mobile layout', 'no network requests'], output }, null, 2));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
