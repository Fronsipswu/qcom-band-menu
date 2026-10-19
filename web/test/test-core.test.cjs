const test = require('node:test');
const assert = require('node:assert/strict');
const T = require('../test-core.js');
const C = require('../log-core.js');
const base = 1700000000000;
function report(kind, attempts, config = {}) { return { id: 'r', config: { kind, regime: 'NBTC', profile: 'LTE', ...config }, startedAtMs: base, endedAtMs: base + 1000, attempts: attempts.map((a, i) => ({ number: i + 1, startedAtMs: base + i * 10, ...a })) }; }
const ok = measurement => ({ outcome: 'SUCCESS', measurement: { contentVerified: true, measurementAvailable: true, ...measurement } });

test('NBTC strict RTT boundary and percentage-point margin include failures', () => {
  const result = T.inspect(report('PING', [ok({ rttMs: 149 }), ok({ rttMs: 150 }), { outcome: 'FAILED', measurement: {} }, { outcome: 'CANCELLED', measurement: {} }]));
  assert.equal(result.metrics[0].value, 100 / 3); assert.equal(result.metrics[0].margin, 100 / 3 - 80); assert.equal(result.metrics[0].marginUnit, 'pp');
  assert.equal(result.metrics[0].status, 'OUTSIDE TARGET'); assert.equal(result.summary.cancelled, 1);
});

test('FTP bitrate ratio denominator is successful transfers only', () => {
  const result = T.inspect(report('FTP_UPLOAD', [ok({ throughputMbps: .5 }), ok({ throughputMbps: .2 }), { outcome: 'FAILED', measurement: {} }]));
  assert.equal(result.metrics[0].value, 200 / 3); assert.equal(result.metrics[0].target, 70);
  assert.equal(result.metrics[1].value, 50); assert.equal(result.metrics[1].margin, -25);
});

test('5G2600 average speed targets and NSA versus SA latency stay distinct', () => {
  const speed = T.inspect(report('FTP_UPLOAD', [ok({ throughputMbps: 7 })], { profile: 'NR_NSA_2600' }));
  assert.equal(speed.metrics.at(-1).target, 5); assert.equal(speed.metrics.at(-1).margin, 2);
  const normal = T.inspect(report('FTP_UPLOAD', [ok({ throughputMbps: 7 })], { profile: 'NR_NSA' }));
  assert.equal(normal.metrics.at(-1).target, null);
  assert.equal(T.inspect(report('PING', [ok({ rttMs: 120 })], { profile: 'NR_NSA_2600' })).metrics[0].value, 100);
  assert.equal(T.inspect(report('PING', [ok({ rttMs: 120 })], { profile: 'NR_SA_2600' })).metrics[0].value, 0);
});

test('freezing maximum margin is positive below the ceiling', () => {
  const r = report('STREAMING', [ok({ playbackStarted: true, playbackCompleted: true, freezeDurationMs: 4, playbackDurationMs: 96 })]);
  const m = T.inspect(r).metrics.at(-1); assert.equal(m.value, 4); assert.equal(m.margin, 1); assert.equal(m.status, 'MEETS TARGET');
});

test('instrument failure is unverified but an ordinary failed webpage is measurable', () => {
  const failed = { outcome: 'FAILED', measurement: { contentVerified: false, measurementAvailable: true } };
  assert.equal(T.inspect(report('WEB_PAGE', [failed])).metrics[0].status, 'OUTSIDE TARGET');
  failed.measurement.measurementAvailable = false;
  assert.equal(T.inspect(report('WEB_PAGE', [failed])).metrics[0].status, 'UNVERIFIED');
});

const transfer = rate => ({ expectedStreams: 3, error: null, streams: Array.from({ length: 3 }, (_, i) => ({ index: i, completed: true, bytes: rate * 1000000 / 8, durationMs: 3000 })) });
test('MIC uses exactly three numeric trials; failed upload retains valid download', () => {
  const r = report('MIC_POINT', [1, 10, 100].map(n => ({ outcome: 'FAILED', measurement: { measurementAvailable: true, mic: { download: transfer(n), upload: { expectedStreams: 3, streams: [], error: 'failed' } } } })), { regime: 'MIC' });
  assert.equal(T.pointMean(r, 'download'), 37); assert.equal(T.pointMean(r, 'upload'), null);
  const result = T.inspect(r); assert.equal(result.metrics[0].value, 37); assert.equal(result.metrics[0].target, null); assert.equal(result.metrics[0].status, 'MIC POINT MEAN');
  assert.ok(result.notes.some(n => n.includes('not minimum throughput')));
});

test('MIC point zero is valid; missing/unfinished/cancelled trials are not zeros', () => {
  const r = report('PARALLEL_DOWNLOAD', [0, 0, 0].map(n => ok({ mic: { download: transfer(n) } })), { regime: 'MIC' });
  assert.equal(T.pointMean(r, 'download'), 0);
  assert.equal(T.pointMean({ ...r, endedAtMs: null }, 'download'), null);
  r.attempts[2].outcome = 'CANCELLED'; assert.equal(T.pointMean(r, 'download'), null);
});

test('smoothing plots does not change raw-attempt regulatory assessment', () => {
  const r = report('PING', [ok({ rttMs: 100 }), ok({ rttMs: 200 })]);
  const d = C.parseText(JSON.stringify(r)), raw = d.testResults({ reportId: 'r' }).metrics[0].value;
  const data = d.rows({ reportId: 'r', intervalMs: 1000, fieldIds: ['rtt_ms'] });
  assert.equal(data.rows[0].values.rtt_ms, 150); assert.equal(data.assessment[0].value, raw); assert.equal(raw, 50);
});

test('unknown regulator/profile does not silently become an NBTC pass', () => {
  assert.ok(T.inspect(report('PING', [ok({ rttMs: 1 })], { profile: 'UNKNOWN' })).metrics.every(m => m.target === null));
  assert.ok(T.inspect(report('PING', [ok({ rttMs: 1 })], { regime: 'UNKNOWN' })).metrics.every(m => m.target === null));
});

test('user thresholds remain explicitly separate from MIC performance requirements', () => {
  const r = report('PARALLEL_DOWNLOAD', [1, 2, 3].map(n => ok({ mic: { download: transfer(n) } })), { regime: 'MIC' });
  const m = T.inspect(r, { customTargets: [{ fieldId: 'download_mbps', target: 5, comparison: '>=' }] }).metrics.at(-1);
  assert.equal(m.margin, -3); assert.match(m.status, /^CUSTOM/); assert.match(m.reason, /not an MIC or NBTC/);
});
