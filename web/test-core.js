(function (root, factory) {
  const api = factory(); api.factorySource = factory.toString();
  if (typeof module === 'object' && module.exports) module.exports = api; else root.QbmTests = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';
  const finite = v => typeof v === 'number' && Number.isFinite(v);
  const numeric = v => v === '' || v == null ? null : Number.isFinite(Number(v)) ? Number(v) : null;
  const mean = values => { const v = values.filter(finite); return v.length ? v.reduce((s, n) => s + n / v.length, 0) : null; };
  const ratio = (n, d) => d > 0 ? n * 100 / d : null;
  const profiles = Object.freeze({
    UMTS: { rtt: 500, down: .75, up: .3, web: 180, streaming: 80, height: 360, b2600: false },
    LTE: { rtt: 150, down: 2.5, up: .5, web: 60, streaming: 85, height: 720, b2600: false },
    NR_NSA: { rtt: 150, down: 2.5, up: .5, web: 60, streaming: 85, height: 720, b2600: false },
    NR_SA: { rtt: 150, down: 2.5, up: .5, web: 60, streaming: 85, height: 720, b2600: false },
    NR_NSA_2600: { rtt: 150, down: 5, up: 1.25, web: 60, streaming: 85, height: 720, b2600: true },
    NR_SA_2600: { rtt: 110, down: 5, up: 1.25, web: 25, streaming: 90, height: 1080, b2600: true }
  });
  const names = { PING: 'ICMP latency', FTP_DOWNLOAD: 'FTP download', FTP_UPLOAD: 'FTP upload', WEB_PAGE: 'Webpage loading', STREAMING: 'YouTube streaming',
    HTTP_DOWNLOAD: 'HTTP download', HTTP_UPLOAD: 'HTTP upload', MIC_POINT: 'MIC point sequence', PARALLEL_DOWNLOAD: 'Parallel HTTP download', PARALLEL_UPLOAD: 'Parallel HTTP upload', UDP_LATENCY: 'UDP latency / loss' };
  function regime(config) {
    if (config.custom || config.regime === 'CUSTOM') return 'CUSTOM';
    if (config.regime === 'MIC') return 'MIC';
    if (config.regime && config.regime !== 'NBTC') return 'UNKNOWN';
    return ['PING', 'FTP_DOWNLOAD', 'FTP_UPLOAD', 'WEB_PAGE', 'STREAMING'].includes(config.kind) ? 'NBTC' : 'CUSTOM';
  }
  function accepts(o) { return !!o && ((o.config && Array.isArray(o.attempts)) || (o.regime === 'MIC' && Array.isArray(o.points) && o.cohort)); }
  function normalize(o) {
    if (o.config && Array.isArray(o.attempts)) return [{ ...o, id: String(o.id || 'report-' + o.startedAtMs), config: { ...o.config } }];
    if (o.regime === 'MIC' && o.cohort && Array.isArray(o.points)) {
      const c = o.cohort;
      const points = o.points.map((p, i) => ({ id: String(p.reportId || 'campaign-point-' + i), startedAtMs: Number(p.startedAtMs || 0), endedAtMs: p.endedAtMs,
        environment: c.device || '', config: { regime: 'MIC', kind: c.kind || 'MIC_POINT', campaignId: c.campaignId, pointId: p.pointId, operator: c.operator,
          declaredTechnology: c.declaredTechnology, micArea: c.area, url: c.downloadUrl, uploadUrl: c.uploadUrl, host: c.udpHost, port: c.udpPort,
          repetitions: 3, parallelConnections: c.connections, warmupSeconds: c.warmupSeconds, warmupBytes: c.warmupBytes,
          measureSeconds: c.measureSeconds, measureBytes: c.measureBytes, udpCount: c.udpCount, udpIntervalMs: c.udpIntervalMs, udpTimeoutMs: c.udpTimeoutMs },
        attempts: Array.isArray(p.trials) ? p.trials : [], importedNotes: p.limitations || [] }));
      return [{ id: 'campaign:' + [c.campaignId, c.operator, c.device, c.declaredTechnology, c.area, c.kind].join('|'), config: { regime: 'MIC', kind: 'MIC_CAMPAIGN', campaignId: c.campaignId },
        startedAtMs: points.length ? Math.min(...points.map(p => p.startedAtMs)) : 0, endedAtMs: points.length ? Math.max(...points.map(p => p.endedAtMs || p.startedAtMs)) : 0,
        attempts: [], campaignPoints: points, importedNotes: o.limitations || [] }, ...points];
    }
    return [];
  }
  function deduplicate(reports) {
    const map = new Map();
    for (const report of reports) { const previous = map.get(report.id); if (!previous || (report.attempts?.length || 0) > (previous.attempts?.length || 0) || report.endedAtMs > previous.endedAtMs) map.set(report.id, report); }
    return Array.from(map.values()).sort((a, b) => (a.startedAtMs || 0) - (b.startedAtMs || 0));
  }
  function describe(r) { return { id: r.id, regime: regime(r.config), kind: r.config.kind, label: r.config.kind === 'MIC_CAMPAIGN' ? 'MIC campaign · ' + r.config.campaignId : (names[r.config.kind] || r.config.kind || 'Test') + (r.config.sessionLabel ? ' · ' + r.config.sessionLabel : '') + (r.config.pointId ? ' · ' + r.config.pointId : ''), startedAtMs: Number(r.startedAtMs || 0), attemptCount: r.attempts.length }; }
  const bool = v => v === true || String(v).toLowerCase() === 'true';
  function parseJson(text) { try { return text ? JSON.parse(text) : null; } catch (_) { return null; } }
  function fromCsv(rows) {
    const groups = new Map();
    for (const row of rows) {
      if (!row.session_id || !row.function || !row.outcome) continue;
      const id = row.session_id;
      if (!groups.has(id)) groups.set(id, { id, config: { kind: row.function, regime: row.mode || 'CUSTOM', custom: row.mode === 'CUSTOM', profile: row.declared_profile,
        repetitions: numeric(row.requested_repetitions), timeoutSeconds: numeric(row.timeout_seconds), transferBytes: numeric(row.expected_bytes), pingPayloadBytes: numeric(row.ping_payload_bytes),
        intervalSeconds: numeric(row.interval_seconds), operator: row.operator, locationLabel: row.location, sessionLabel: row.session_label, campaignId: row.campaign_id, pointId: row.point_id,
        micArea: row.mic_area, declaredTechnology: row.declared_technology, url: row.endpoint, uploadUrl: row.upload_endpoint },
        startedAtMs: Date.parse(row.started_utc) || 0, endedAtMs: null, attempts: [], importedNotes: ['Imported CSV: settings and instrument metadata absent from this export cannot be verified.'], csv: true });
      const r = groups.get(id), time = Date.parse(row.started_utc);
      if (!Number.isFinite(time)) { r.importedNotes.push('A row has an invalid timestamp.'); continue; }
      r.startedAtMs = Math.min(r.startedAtMs, time);
      r.attempts.push({ number: numeric(row.attempt), startedAtMs: time, elapsedRealtimeMs: numeric(row.elapsed_realtime_ms), outcome: row.outcome,
        radio: parseJson(row.radio_reference_json), measurement: { bytes: numeric(row.bytes), durationMs: numeric(row.duration_ms), throughputMbps: numeric(row.mbps), rttMs: numeric(row.rtt_ms), httpStatus: numeric(row.http_status),
          playbackStarted: bool(row.playback_started), playbackCompleted: bool(row.playback_completed), mediaDurationSeconds: numeric(row.media_duration_seconds), observedQuality: row.quality || null,
          freezeDurationMs: numeric(row.freeze_ms) || 0, playbackDurationMs: numeric(row.playback_ms) || 0, contentVerified: row.content_verified === '' ? false : bool(row.content_verified),
          measurementAvailable: row.measurement_available === '' ? false : bool(row.measurement_available), serviceAccessed: bool(row.service_accessed), startupDelayMs: numeric(row.startup_delay_ms),
          error: row.error || null, detail: row.detail || null, mic: parseJson(row.mic_measurement_json) } });
    }
    return Array.from(groups.values());
  }
  function transferRate(transfer, expected = 3) {
    if (!transfer || transfer.error || !Array.isArray(transfer.streams) || transfer.streams.length !== expected || transfer.expectedStreams !== expected) return null;
    const indexes = new Set(); let sum = 0;
    for (const s of transfer.streams) {
      if (indexes.has(s.index) || !s.completed || s.error || !finite(s.bytes) || s.bytes < 0 || !finite(s.durationMs) || s.durationMs <= 0) return null;
      indexes.add(s.index); sum += s.bytes * 8 / (s.durationMs * 1000);
    }
    return sum;
  }
  function pointMean(report, direction) {
    const eligible = report.attempts.filter(a => a.outcome !== 'CANCELLED');
    if ((report.endedAtMs == null && !report.csv) || eligible.length !== 3 || new Set(eligible.map(a => a.number)).size !== 3) return null;
    const values = eligible.map(a => a.measurement?.measurementAvailable === false ? null : transferRate(a.measurement?.mic?.[direction]));
    return values.every(finite) ? mean(values) : null;
  }
  function metric(name, value, unit, target = null, comparison = null, verified = true, reason = '') {
    const margin = finite(value) && finite(target) ? comparison === '<=' || comparison === '<' ? target - value : value - target : null;
    let status = !finite(value) ? 'NO DATA' : comparison == null ? 'MEASURED' : !verified ? 'UNVERIFIED' :
      (comparison === '>=' ? value >= target : comparison === '<=' ? value <= target : comparison === '<' ? value < target : value > target) ? 'MEETS TARGET' : 'OUTSIDE TARGET';
    return { name, value, unit, target, comparison, margin, marginUnit: unit === '%' ? 'pp' : unit, status, reason };
  }
  function inspect(report, options = {}) {
    if (report.config.kind === 'MIC_CAMPAIGN') return campaign(report);
    const c = report.config, reg = regime(c), attempts = report.attempts, eligible = attempts.filter(a => a.outcome !== 'CANCELLED'), successes = eligible.filter(a => a.outcome === 'SUCCESS');
    const measurements = eligible.map(a => a.measurement || {}), metrics = [], notes = [...(report.importedNotes || [])];
    notes.push('Assessments use raw attempts, not smoothed plot/export bins. These are session measurements; location coverage and operator-wide compliance are not established.');
    const evidence = eligible.every(a => a.measurement && a.measurement.measurementAvailable !== false &&
      (!(a.outcome === 'SUCCESS' || (c.kind === 'STREAMING' && a.measurement.playbackStarted)) || a.measurement.contentVerified !== false));
    const target = (name, value, unit, threshold, cmp = '>=') => metrics.push(metric(name, value, unit, threshold, cmp, evidence, evidence ? '' : 'Instrument or content verification is incomplete.'));
    const measured = (name, value, unit) => metrics.push(metric(name, value, unit));
    if (eligible.some(a => !['SUCCESS', 'FAILED', 'TIMED_OUT'].includes(a.outcome))) notes.push('Unknown attempt outcomes were found; review the input before relying on its statistics.');
    if (reg === 'NBTC' && profiles[c.profile]) {
      const p = profiles[c.profile], upload = c.kind === 'FTP_UPLOAD';
      if (c.kind === 'PING') {
        target('2.3.1 RTT < ' + p.rtt + ' ms', ratio(successes.filter(a => finite(a.measurement?.rttMs) && a.measurement.rttMs < p.rtt).length, eligible.length), '%', 80);
        measured('Mean RTT of replies', mean(successes.map(a => a.measurement?.rttMs)), 'ms');
        measured('Packet loss', ratio(eligible.length - successes.length, eligible.length), '%');
      } else if (c.kind === 'FTP_DOWNLOAD' || c.kind === 'FTP_UPLOAD') {
        target('2.3.2 FTP success', ratio(successes.length, eligible.length), '%', upload ? 70 : 80);
        target('2.3.3 FTP ≥ ' + (upload ? p.up : p.down) + ' Mbps (successful transfers)',
          ratio(successes.filter(a => finite(a.measurement?.throughputMbps) && a.measurement.throughputMbps >= (upload ? p.up : p.down)).length, successes.length), '%', 75);
        const avg = mean(successes.map(a => a.measurement?.throughputMbps));
        if (p.b2600) target('2.3.11 Mean FTP bitrate', avg, 'Mbps', upload ? 5 : 20); else measured('Mean FTP bitrate', avg, 'Mbps');
      } else if (c.kind === 'WEB_PAGE') {
        target('2.3.4 Complete webpage within ' + p.web + ' s', ratio(successes.filter(a => finite(a.measurement?.durationMs) && a.measurement.durationMs <= p.web * 1000).length, eligible.length), '%', 90);
        measured('Mean complete page load time', mean(successes.map(a => a.measurement?.durationMs)), 'ms');
      } else if (c.kind === 'STREAMING') {
        const started = measurements.filter(m => m.playbackStarted), accessed = measurements.filter(m => m.serviceAccessed || m.playbackStarted);
        target('2.3.5 Streaming accessibility', ratio(accessed.length, eligible.length), '%', p.streaming);
        target('2.3.6 Complete playback (started sessions)', ratio(started.filter(m => m.playbackCompleted).length, started.length), '%', p.streaming);
        const frozen = started.reduce((s, m) => s + (Number(m.freezeDurationMs) || 0), 0), playing = started.reduce((s, m) => s + (Number(m.playbackDurationMs) || 0), 0);
        target('2.3.6/1 Freezing proportion', frozen + playing > 0 ? frozen * 100 / (frozen + playing) : null, '%', 5, '<=');
      }
      if (!c.referenceServerConfirmed) notes.push('NBTC reference server/content confirmation is absent or unavailable.');
      if (report.endedAtMs == null) notes.push('Session completion is unverified (unfinished report or CSV without a completion marker).');
      const count = c.kind === 'STREAMING' ? 100 : c.area === 'COMMUNITY' ? 60 : c.area === 'SITE' ? 40 : 120;
      if (eligible.length < count) notes.push('Only ' + eligible.length + ' eligible samples; selected NBTC campaign category calls for ' + count + '.');
      notes.push('NBTC values follow the 2024 measurement guidance and governing standard; speed margins and percentage-point margins are distinct.');
    } else if (reg === 'MIC') {
      notes.push('MIC defines measurement methods and speed distributions, not minimum throughput or latency pass/fail targets. Method checks below are not network-quality thresholds.');
      for (const direction of ['download', 'upload']) {
        if (c.kind === 'MIC_POINT' || c.kind === 'PARALLEL_' + direction.toUpperCase()) {
          const avg = pointMean(report, direction), m = metric(direction === 'download' ? 'Download point mean (3 trials)' : 'Upload point mean (3 trials)', avg, 'Mbps');
          m.status = avg == null ? 'INCOMPLETE POINT' : report.csv ? 'PROVISIONAL POINT MEAN' : 'MIC POINT MEAN'; metrics.push(m);
        }
      }
      const udp = measurements.filter(m => m.measurementAvailable !== false).map(m => m.mic?.udp).filter(u => u && !u.error && u.received + u.lost === u.sent);
      measured('UDP mean RTT (replies)', mean(udp.flatMap(u => u.rttsMs || [])), 'ms');
      measured('UDP packet loss', ratio(udp.reduce((s, u) => s + u.lost, 0), udp.reduce((s, u) => s + u.sent, 0)), '%');
      const check = (name, valid, why) => { metrics.push({ name, value: null, unit: '', target: null, comparison: null, margin: null, status: valid == null ? 'NOT VERIFIED' : valid ? 'METHOD CHECK MET' : 'METHOD CHECK NOT MET', reason: why }); };
      check('Three trials at one point', eligible.length === 3 && new Set(eligible.map(a => a.number)).size === 3, eligible.length + ' non-cancelled trials; incomplete directions are not replaced by zero.');
      if (c.kind !== 'UDP_LATENCY') {
        const transfers = measurements.flatMap(m => [m.mic?.download, m.mic?.upload]).filter(Boolean);
        check('Three concurrent connections per transfer', transfers.length ? transfers.every(t => t.expectedStreams === 3 && t.streams?.length === 3) : null, 'Connection counts are checked; concurrency requires the recorded client implementation.');
      }
      if (c.kind === 'MIC_POINT' || c.kind === 'UDP_LATENCY') check('60 UDP probes per trial', udp.length === 3 && udp.every(u => u.sent === 60), 'MIC uses 60 probes at 500 ms cadence with 2 s reply deadlines.');
      check('Stationary outdoor placement', c.stationaryConfirmed === true ? true : null, 'Operator confirmation; GPS is omitted.');
      check('Shared-server conditions', c.referenceServerConfirmed === true ? true : null, 'Domestic IX connection, capacity and admission control are operator-confirmed.');
      const area = c.micArea === 'RESIDENTIAL' ? [15, 21] : [12, 18];
      const validHours = eligible.every(a => { const d = new Date(Number(a.startedAtMs) + 9 * 3600000); return d.getUTCHours() >= area[0] && d.getUTCHours() < area[1]; });
      check('Japan measurement time window', eligible.length ? validHours : null, area[0] + ':00–' + area[1] + ':00 Asia/Tokyo; the guideline does not mandate weekdays.');
      check('Measured RAT and signal references', eligible.length ? eligible.every(a => a.radio?.technology && a.radio?.signal && Number(a.elapsedRealtimeMs) - Number(a.radio.observedAtElapsedMs) >= 0 && Number(a.elapsedRealtimeMs) - Number(a.radio.observedAtElapsedMs) <= 5000) : null, 'Current reference data is separate from declared technology.');
      notes.push('The supplied MIC guideline is July 2015. This independent client does not establish compatibility with the designated common software/server or nationwide geographic coverage.');
    } else {
      if (reg === 'NBTC') notes.push('Unknown NBTC radio profile: no regulatory target has been inferred.');
      else notes.push('Custom or unknown profile: official NBTC/MIC performance thresholds are not applied.');
      measured('Successful attempts', ratio(successes.length, eligible.length), '%');
      measured('Mean transfer rate', mean(successes.map(a => a.measurement?.throughputMbps)), 'Mbps');
      measured('Mean RTT', mean(successes.map(a => a.measurement?.rttMs)), 'ms');
      metrics.forEach(m => { if (finite(m.value)) m.status = 'CUSTOM'; });
    }
    const fields = [
      { id: 'throughput_mbps', label: 'Transfer rate', unit: 'Mbps' }, { id: 'download_mbps', label: 'Download rate', unit: 'Mbps' },
      { id: 'upload_mbps', label: 'Upload rate', unit: 'Mbps' }, { id: 'rtt_ms', label: 'Round-trip time', unit: 'ms' },
      { id: 'duration_ms', label: 'Attempt duration', unit: 'ms' }, { id: 'bytes', label: 'Payload', unit: 'bytes' },
      { id: 'packet_loss_pct', label: 'Packet loss', unit: '%' }, { id: 'freeze_pct', label: 'Freezing proportion', unit: '%' }, { id: 'success_pct', label: 'Attempt success', unit: '%' }
    ];
    const rows = attempts.map((a, i) => {
      const m = a.measurement || {}, udp = m.mic?.udp, frozen = Number(m.freezeDurationMs || 0), playing = Number(m.playbackDurationMs || 0), cancelled = a.outcome === 'CANCELLED';
      const values = {
        throughput_mbps: finite(m.throughputMbps) ? m.throughputMbps : null,
        download_mbps: transferRate(m.mic?.download, m.mic?.download?.expectedStreams) ?? (c.kind === 'FTP_DOWNLOAD' || c.kind === 'HTTP_DOWNLOAD' ? m.throughputMbps ?? null : null),
        upload_mbps: transferRate(m.mic?.upload, m.mic?.upload?.expectedStreams) ?? (c.kind === 'FTP_UPLOAD' || c.kind === 'HTTP_UPLOAD' ? m.throughputMbps ?? null : null),
        rtt_ms: finite(m.rttMs) ? m.rttMs : udp && !udp.error ? mean(udp.rttsMs || []) : null,
        duration_ms: numeric(m.durationMs), bytes: numeric(m.bytes),
        packet_loss_pct: udp && !udp.error ? ratio(udp.lost, udp.sent) : c.kind === 'PING' && m.measurementAvailable !== false && !cancelled ? a.outcome === 'SUCCESS' ? 0 : 100 : null,
        freeze_pct: frozen + playing > 0 ? frozen * 100 / (frozen + playing) : null,
        success_pct: cancelled ? null : a.outcome === 'SUCCESS' ? 100 : 0
      };
      return { t: Number(a.startedAtMs || report.startedAtMs || 0), attempt: a.number ?? i + 1, outcome: a.outcome || 'UNKNOWN', values };
    });
    for (const custom of options.customTargets || []) {
      const f = fields.find(f => f.id === custom.fieldId);
      if (!f || !finite(Number(custom.target)) || !['>=', '<=', '<', '>'].includes(custom.comparison)) continue;
      const m = metric('User target · mean ' + f.label, mean(rows.filter(r => r.outcome !== 'CANCELLED').map(r => r.values[f.id])), f.unit, Number(custom.target), custom.comparison);
      m.reason = 'User-defined target; not an MIC or NBTC requirement.'; m.status = 'CUSTOM · ' + m.status; metrics.push(m);
    }
    return { report: describe(report), metrics, rows, fields: fields.filter(f => rows.some(r => r.values[f.id] != null)),
      summary: { attempted: eligible.length, success: successes.length, failed: eligible.length - successes.length, cancelled: attempts.length - eligible.length }, notes: Array.from(new Set(notes)) };
  }
  function campaign(report) {
    const points = report.campaignPoints || [], names = new Map();
    points.forEach(p => { const id = String(p.config.pointId || '').trim(); if (id) names.set(id, (names.get(id) || 0) + 1); });
    const rows = points.map((p, i) => { const unique = !!p.config.pointId && names.get(String(p.config.pointId).trim()) === 1; return { t: p.startedAtMs, attempt: i + 1, outcome: unique ? 'POINT' : 'DUPLICATE',
      values: { download_mbps: unique ? pointMean(p, 'download') : null, upload_mbps: unique ? pointMean(p, 'upload') : null } }; });
    const fields = [{ id: 'download_mbps', label: 'Download point mean', unit: 'Mbps' }, { id: 'upload_mbps', label: 'Upload point mean', unit: 'Mbps' }], metrics = [];
    for (const f of fields) {
      const values = rows.map(r => r.values[f.id]).filter(finite).sort((a, b) => a - b);
      const q = p => { if (!values.length) return null; const at = (values.length - 1) * p, lo = Math.floor(at); return values[lo] + (values[Math.min(lo + 1, values.length - 1)] - values[lo]) * (at - lo); };
      for (const [name, v] of [['Minimum', values[0] ?? null], ['Q1', q(.25)], ['Median', q(.5)], ['Q3', q(.75)], ['Maximum', values[values.length - 1] ?? null]]) metrics.push(metric(f.label + ' · ' + name, v, 'Mbps'));
    }
    return { report: { ...describe(report), attemptCount: points.length }, metrics, rows, fields, summary: { attempted: points.length, success: rows.filter(r => r.values.download_mbps != null && r.values.upload_mbps != null).length,
      failed: rows.filter(r => r.values.download_mbps == null || r.values.upload_mbps == null).length, cancelled: 0 },
      notes: ['Local MIC point-mean distribution; no national coverage or minimum-speed verdict.', 'Exactly three numeric trials per point and direction; incomplete/duplicate point IDs are excluded, never replaced by zero.', 'Type 7 quartiles; full minimum/maximum include outliers.', ...(report.importedNotes || [])] };
  }
  return { accepts, normalize, deduplicate, fromCsv, describe, inspect, pointMean, transferRate, profiles };
});
