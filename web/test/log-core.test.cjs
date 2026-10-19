const test = require('node:test');
const assert = require('node:assert/strict');
const Core = require('../log-core.js');
const Tests = require('../test-core.js');
const base = 1700000000000;
const header = { type: 'header', format: 'qbm-log', version: 1, sessionId: 's', segment: 0, startedUtcMs: base, startedElapsedNs: '9007199254740993000', device: 'test' };
const stamp = ms => (BigInt(header.startedElapsedNs) + BigInt(ms) * 1000000n).toString();
const packet = (ms, updates, extra = {}) => ({ type: 'packet', sessionId: 's', segment: 0, sim: 1, seq: ms + 1, captureId: 'one', tNs: stamp(ms), updates, ...extra });
const u = (metric, value, extra = {}) => ({ entity: 'lte:0', metric, value, unit: 'dB', aggregation: 'mean', sum: value, count: 1, ...extra });
function dataset(records) { const end = records.reduce((latest, r) => { const t = BigInt(r.endNs || r.tNs); return t > latest ? t : latest; }, BigInt(header.startedElapsedNs)); return Core.parseText([header, ...records, { type: 'footer', sessionId: 's', segment: 0, tNs: String(end) }].map(JSON.stringify).join('\n')); }
const id = (d, metric, source = 'packet', sim = 1) => d.summary().fields.find(f => f.metric === metric && f.source === source && f.sim === sim).id;

test('5 ms SINR and 20 ms RSRP synchronize causally without inventing initial values', () => {
  const d = dataset([packet(0, [u('sinr', 10)]), packet(5, [u('sinr', 12)]), packet(20, [u('rsrp', -90)]), packet(25, [u('sinr', 15)]), packet(40, [u('rsrp', -85)])]);
  const r = id(d, 'rsrp'), s = id(d, 'sinr');
  assert.equal(d.frame({ mode: 'packet', atMs: base + 10 }).values[r].value, null);
  const at25 = d.frame({ mode: 'packet', atMs: base + 25 });
  assert.equal(at25.values[r].value, -90); assert.equal(at25.values[r].ageMs, 5); assert.equal(at25.values[r].carried, true);
  assert.equal(at25.values[s].value, 15);
  assert.equal(d.frame({ mode: 'packet', atMs: base + 39 }).values[r].value, -90);
});

test('64 bit clock anchor is subtracted before conversion to number', () => {
  const d = dataset([packet(0, [u('sinr', 1)]), packet(5, [u('sinr', 2)])]);
  assert.equal(d.summary().endMs - d.summary().startMs, 5);
});

test('carry-forward ends at the recorded session boundary', () => {
  const d = dataset([packet(0, [u('sinr', 10)]), packet(20, [u('sinr', 12)])]);
  assert.equal(d.frame({ mode: 'packet', atMs: base + 20 }).values[id(d, 'sinr')].value, 12);
  assert.equal(d.frame({ mode: 'packet', atMs: base + 21 }).values[id(d, 'sinr')].value, null);
  assert.equal(d.frame({ mode: 'packet', atMs: base + 21 }).event, null);
});

test('SIMs and identities never borrow carried values', () => {
  const d = dataset([packet(0, [u('rsrp', -90, { identity: '1:2' })]), packet(5, [u('sinr', 7, { identity: '1:2' })], { sim: 2 }),
    packet(20, [u('sinr', 20, { identity: '3:4' })])]);
  assert.equal(d.frame({ mode: 'packet', atMs: base + 20, sim: 1 }).values[id(d, 'rsrp')].value, null);
  assert.equal(d.frame({ mode: 'packet', atMs: base + 20, sim: 2 }).values[id(d, 'sinr', 'packet', 2)].value, 7);
});

test('explicit gaps clear observations and make crossing aggregates incomplete', () => {
  const d = dataset([packet(0, [u('sinr', 10)]), { type: 'gap', sessionId: 's', tNs: stamp(10), source: 'packet', reason: 'overflow', dropped: 1 }, packet(20, [u('rsrp', -80)])]);
  assert.equal(d.frame({ mode: 'packet', atMs: base + 15 }).values[id(d, 'sinr')].value, null);
  const data = d.rows({ mode: 'packet', intervalMs: 20 });
  assert.equal(data.rows.at(-1).values[id(d, 'sinr')], null);
  assert.ok(d.summary().warnings.includes('overflow'));
});

test('dropped ranges mask retained observations inside the uncertain range and clear at its end', () => {
  const d = dataset([packet(0, [u('sinr', 10)]), { type: 'gap', sessionId: 's', tNs: stamp(5), endNs: stamp(20), source: 'packet', reason: 'overflow' },
    packet(10, [u('sinr', 15)]), packet(30, [u('rsrp', -80)])]);
  assert.equal(d.frame({ mode: 'packet', atMs: base + 15 }).values[id(d, 'sinr')].value, null);
  assert.equal(d.frame({ mode: 'packet', atMs: base + 25 }).values[id(d, 'sinr')].value, null);
});

test('weighted signal/MCS means and BLER ratios use original denominators', () => {
  const d = dataset([packet(0, []), packet(5, [u('mcs', 1, { sum: 1, count: 1 }), u('bler', 100, { aggregation: 'ratio', numerator: 1, denominator: 1, scale: 100 })]),
    packet(20, [u('mcs', 9, { sum: 81, count: 9 }), u('bler', 0, { aggregation: 'ratio', numerator: 0, denominator: 9, scale: 100 })])]);
  const row = d.rows({ mode: 'packet', intervalMs: 20 }).rows.at(-1);
  assert.equal(row.values[id(d, 'mcs')], 8.2); assert.equal(row.values[id(d, 'bler')], 10);
});

test('mode aggregation sums votes and last-value configuration stays categorical', () => {
  const d = dataset([packet(0, []), packet(5, [u('modulation', 'QPSK', { aggregation: 'mode', counts: { QPSK: 4, '64QAM': 3 } }), u('state', 'active', { aggregation: 'last' })]),
    packet(20, [u('modulation', '64QAM', { aggregation: 'mode', counts: { QPSK: 0, '64QAM': 9 } })])]);
  const row = d.rows({ mode: 'packet', intervalMs: 20 }).rows.at(-1);
  assert.equal(row.values[id(d, 'modulation')], '64QAM'); assert.equal(row.values[id(d, 'state')], 'active');
});

test('mode tie order and raw scheme mapping reproduce native UI decisions', () => {
  const d = dataset([packet(0, []), packet(5, [u('mod', 'QPSK', { aggregation: 'mode', order: ['QPSK', '16QAM'], counts: { QPSK: 1, '16QAM': 1 } }),
    u('tm', 4, { aggregation: 'mode', counts: { '4': 3, '8': 2, '9': 2 }, order: ['4', '8', '9'], map: { '4': 4, '8': 9, '9': 9 } })]), packet(20, [])]);
  const row = d.rows({ mode: 'packet', intervalMs: 20 }).rows.at(-1);
  assert.equal(row.values[id(d, 'mod')], 'QPSK'); assert.equal(row.values[id(d, 'tm')], 4);
  assert.equal(typeof row.values[id(d, 'tm')], 'number');
});

test('aligned row statistics distinguish repeated carried values from actual updates', () => {
  const d = dataset([packet(0, [u('rsrp', -90)]), packet(5, [u('sinr', 1)]), packet(10, [u('sinr', 2)]), packet(20, [u('rsrp', -80)])]);
  const s = d.series({ mode: 'packet' }).stats[id(d, 'rsrp')];
  assert.equal(s.count, 4); assert.equal(s.observationCount, 2);
});

test('real Kotlin serializer and gzip writer fixture imports without missing-segment warnings', async () => {
  const fs = require('node:fs'), path = require('node:path');
  const file = new Blob([fs.readFileSync(path.resolve(__dirname, '../../app/app/src/test/resources/logs/cross-layer.qbm.ndjson.gz'))]);
  file.name = 'cross-layer.qbm.ndjson.gz';
  const d = await Core.readFiles([file]), summary = d.summary();
  assert.equal(summary.uiCount, 1); assert.equal(summary.packetCount, 1);
  assert.ok(!summary.warnings.some(w => /footer|segment\(s\)/.test(w)));
  assert.equal(d.frame({ mode: 'ui', atMs: summary.startMs }).snapshot.carrierGroups[0].carriers[0].diag.txAntennas, 4);
  assert.equal(d.frame({ mode: 'packet', atMs: 1760000000060 }).values[id(d, 'rsrp')].value, -94);
  assert.ok(summary.fields.some(f => f.label === 'LTE RSRP'));
});

test('actual native decoder packet and window weights reconstruct the same MCS and BLER', () => {
  const fixture = require('../../app/app/src/test/resources/logs/native-decoder-fixture.json');
  const first = BigInt(fixture.packets[0].tNs), h = { ...header, startedElapsedNs: String(first), sessionId: 'native' };
  const events = fixture.packets.map((p, i) => ({ ...p, sessionId: 'native', segment: 0, archiveSeq: i + 1 }));
  const d = Core.parseText([h, ...events, { type: 'footer', sessionId: 'native', segment: 0, tNs: events.at(-1).tNs }].map(JSON.stringify).join('\n'));
  const mcs = d.summary().fields.find(f => f.sim === 1 && f.entity === 'lte:0' && f.metric === 'mcs');
  const bler = d.summary().fields.find(f => f.sim === 1 && f.entity === 'lte:0' && f.metric === 'bler');
  const firstTransfer = fixture.packets[1], secondTransfer = fixture.packets[2];
  const a = base + Number(BigInt(firstTransfer.tNs) - first) / 1e6, b = base + Number(BigInt(secondTransfer.tNs) - first) / 1e6;
  assert.ok(Math.abs(d.reduce(mcs, a, b, true) - 34 / 3) < 1e-10);
  assert.ok(Math.abs(d.reduce(bler, a, b, true) - 100 / 3) < 1e-10);
  const statsMcs = fixture.stats.aggregates.find(u => u.entity === 'lte:0' && u.metric === 'mcs');
  assert.equal(statsMcs.sum, 34); assert.equal(statsMcs.count, 3);
});

test('throughput uses byte increments over interval, not carried byte values or packet gaps', () => {
  const d = dataset([packet(0, []), packet(5, [u('dl_bytes', 1000, { aggregation: 'sum', unit: 'bytes' })]), packet(10, [u('sinr', 7)]), packet(20, [u('dl_bytes', 1000, { aggregation: 'sum', unit: 'bytes' })])]);
  const row = d.rows({ mode: 'packet', intervalMs: 20 }).rows.at(-1);
  assert.equal(row.values[id(d, 'dl_bytes')], 2000); assert.equal(row.values[id(d, 'dl_mbps')], .8);
});

test('UI mode preserves recorded display values and weighted rebinning avoids averaging averages', () => {
  const ui = (ms, value, sum, count, bytes, windowMs) => ({ type: 'ui', sessionId: 's', tNs: stamp(ms), sim: 1, snapshot: { modeLabel: 'LTE', exact: value }, windowMs,
    updates: [u('rsrp', value, { aggregation: 'last' }), u('mbps_sched', bytes * 8 / (windowMs * 1000), { aggregation: 'last', unit: 'Mbps' })],
    aggregates: [{ ...u('rsrp', value), sum, count }, u('dl_bytes', bytes, { aggregation: 'sum', unit: 'bytes' })] });
  const d = dataset([ui(0, -90, -90, 1, 1000, 1000), ui(10, -80, -720, 9, 1000, 2000)]);
  assert.equal(d.frame({ mode: 'ui', atMs: base + 5 }).snapshot.exact, -90);
  const f = d.fields.get(id(d, 'rsrp', 'ui'));
  assert.equal(d.reduce(f, base, base + 10, true), -81);
  assert.equal(d.reduce(d.fields.get(id(d, 'mbps_sched', 'ui')), base, base + 10, true), 2000 * 8 / 3000000);
});

test('same-time packet export respects archive event order', () => {
  const d = dataset([packet(0, [u('sinr', 10)], { seq: 1 }), packet(0, [u('sinr', 20)], { seq: 2 })]);
  assert.deepEqual(d.rows({ mode: 'packet' }).rows.map(r => r.values[id(d, 'sinr')]), [10, 20]);
});

test('plot decimation does not change exports, stats or narrow extrema', () => {
  const d = dataset(Array.from({ length: 101 }, (_, i) => packet(i, [u('sinr', i === 47 ? 999 : i)])));
  const field = id(d, 'sinr'), view = d.series({ mode: 'packet', fieldIds: [field], maxPoints: 10 });
  assert.ok(view.rows.length < 101); assert.ok(view.rows.some(r => r.values[field] === 999)); assert.equal(view.stats[field].count, 101);
  assert.equal(d.exportData({ mode: 'packet', fieldIds: [field], maxPoints: 10, format: 'json' }).rowCount, 101);
});

test('categorical state transitions and missing intervals survive chart decimation', () => {
  const records = Array.from({ length: 101 }, (_, i) => packet(i, [u('state', i < 40 ? 'Connected' : i < 45 ? null : 'Idle', { aggregation: 'last', unit: '' })]));
  const d = dataset(records), field = id(d, 'state'), plot = d.series({ mode: 'packet', fieldIds: [field], maxPoints: 4 });
  assert.equal(plot.stats[field].count, 96); assert.equal(plot.stats[field].distinct, 2); assert.equal(plot.stats[field].mode, 'Idle');
  assert.ok(plot.rows.some(r => r.values[field] === null)); assert.ok(plot.rows.some(r => r.values[field] === 'Connected')); assert.ok(plot.rows.some(r => r.values[field] === 'Idle'));
  assert.equal(plot.stats[field].mean, null);
});

test('CSV handles quoted commas/newlines and prevents formula execution', () => {
  assert.deepEqual(Core.parseCsv('name,value\r\n"A,""B""\nC",7\r\n')[0], Object.assign(Object.create(null), { name: 'A,"B"\nC', value: '7' }));
  assert.equal(Core.csvCell('=1+1'), '"\'=1+1"'); assert.equal(Core.csvCell(-90), '"-90"');
});

test('unknown capture versions and conflicting session anchors are rejected', () => {
  assert.throws(() => Core.parseText(JSON.stringify({ ...header, version: 2 })), /Unsupported/);
  assert.throws(() => Core.parseText([header, { ...header, startedUtcMs: base + 1 }].map(JSON.stringify).join('\n')), /Conflicting/);
});

test('gzip import works from streamed bytes and duplicate segments are deduplicated', async () => {
  const { gzipSync } = require('node:zlib');
  const text = [header, packet(0, [u('sinr', 10)], { archiveSeq: 1 }), { type: 'footer', sessionId: 's', segment: 0, tNs: stamp(1) }].map(JSON.stringify).join('\n');
  const file = new Blob([gzipSync(text)]); file.name = 'capture.gz';
  const d = await Core.readFiles([file, file]); assert.equal(d.summary().packetCount, 1);
});

test('test JSON and CSV both import with raw attempts available', async () => {
  const report = { id: 'test', config: { kind: 'PING', profile: 'LTE', regime: 'NBTC' }, startedAtMs: base, endedAtMs: base + 1000, attempts: [{ number: 1, startedAtMs: base, outcome: 'SUCCESS', measurement: { rttMs: 10, contentVerified: true, measurementAvailable: true } }] };
  const file = new Blob([JSON.stringify(report, null, 2)]); file.name = 'test.json';
  const d = await Core.readFiles([file]); assert.equal(d.summary().kind, 'tests'); assert.equal(d.testResults({ reportId: 'test' }).metrics[0].margin, 20);
  const csv = new Blob(['session_id,function,mode,declared_profile,attempt,started_utc,outcome,rtt_ms,content_verified,measurement_available\r\nt,PING,NBTC,LTE,1,2023-11-14T22:13:20Z,SUCCESS,12,true,true\r\n']); csv.name = 'test.csv';
  assert.equal((await Core.readFiles([csv])).summary().testReports.length, 1);
});
