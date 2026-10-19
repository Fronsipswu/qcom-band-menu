(function (root, factory) {
  const tests = typeof module === 'object' && module.exports ? require('./test-core.js') : root.QbmTests;
  const api = factory(tests); api.factorySource = factory.toString();
  if (typeof module === 'object' && module.exports) module.exports = api; else root.QbmLog = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function (Tests) {
  'use strict';
  const LIMITS = Object.freeze({ records: 750000, updates: 3000000, decodedBytes: 512 * 1024 * 1024, lineBytes: 2 * 1024 * 1024, exportRows: 500000, queryCells: 2000000 });
  const number = v => typeof v === 'number' && Number.isFinite(v);
  const scalar = v => v === null || typeof v === 'string' || typeof v === 'boolean' || number(v);
  const object = () => Object.create(null);
  const fieldId = (source, session, sim, entity, metric) => [source === 'packet' ? 'p' : 'u', session, sim, entity, metric].map(encodeURIComponent).join('|');
  const ns = value => { try { return BigInt(String(value)); } catch (_) { return null; } };
  const label = name => String(name).replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase());
  const csvCell = value => {
    let text = value == null ? '' : String(value);
    if (typeof value === 'string' && /^[\s]*[=+@-]/.test(text)) text = "'" + text;
    return '"' + text.replace(/"/g, '""') + '"';
  };
  function parseCsv(text) {
    const rows = []; let row = [], field = '', quoted = false;
    text = text.replace(/^\uFEFF/, '');
    for (let i = 0; i < text.length; i++) {
      const c = text[i];
      if (quoted) { if (c === '"' && text[i + 1] === '"') { field += '"'; i++; } else if (c === '"') quoted = false; else field += c; }
      else if (c === '"' && field === '') quoted = true;
      else if (c === ',') { row.push(field); field = ''; }
      else if (c === '\n' || c === '\r') {
        if (c === '\r' && text[i + 1] === '\n') i++;
        row.push(field); if (row.some(Boolean)) rows.push(row); row = []; field = '';
      } else field += c;
    }
    if (quoted) throw new Error('CSV contains an unterminated quoted field.');
    row.push(field); if (row.some(Boolean)) rows.push(row);
    if (!rows.length) return [];
    const header = rows.shift();
    return rows.map(values => { const o = object(); header.forEach((key, i) => { o[key] = values[i] || ''; }); return o; });
  }
  function statistics(values) {
    const a = values.filter(number); if (!a.length) return { count: 0, min: null, max: null, mean: null, stdDev: null, median: null, p95: null };
    let mean = 0, m2 = 0; a.forEach((v, i) => { const d = v - mean; mean += d / (i + 1); m2 += d * (v - mean); });
    const s = a.slice().sort((x, y) => x - y);
    const q = p => { const at = (s.length - 1) * p, lo = Math.floor(at); return s[lo] + (s[Math.min(lo + 1, s.length - 1)] - s[lo]) * (at - lo); };
    return { count: a.length, min: s[0], max: s[s.length - 1], mean, stdDev: Math.sqrt(m2 / a.length), median: q(.5), p95: q(.95) };
  }
  function upperBound(a, t) { let lo = 0, hi = a.length; while (lo < hi) { const m = (lo + hi) >>> 1; if (a[m].t <= t) lo = m + 1; else hi = m; } return lo; }
  function normalizedUpdates(record) {
    if (Array.isArray(record.updates)) return record.updates;
    const result = [];
    function walk(v, path) {
      if (v && typeof v === 'object' && !Array.isArray(v) && typeof v.label === 'string' && ('value' in v)) {
        result.push({ entity: path.slice(0, -1).join('.') || 'ui', metric: v.label, value: v.value ?? v.text ?? null, unit: v.unit || '', aggregation: 'last' }); return;
      }
      if (Array.isArray(v)) v.forEach((child, i) => walk(child, path.concat(i)));
      else if (v && typeof v === 'object') Object.keys(v).forEach(key => { if (!['raw', 'reads', 'd', 'r'].includes(key)) walk(v[key], path.concat(key)); });
      else if (scalar(v) && path.length) result.push({ entity: 'ui', metric: path.join('.'), value: v, unit: '', aggregation: 'last' });
    }
    walk(record.snapshot || {}, []); return result;
  }
  class Dataset {
    constructor() {
      this.headers = new Map(); this.records = []; this.events = []; this.fields = new Map(); this.histories = new Map(); this.reports = [];
      this.warnings = []; this.seen = new Set(); this.segments = new Map(); this.footerSegments = new Set(); this.updateCount = 0; this.gaps = []; this.counterIndexes = new Map(); this.sessionEnds = new Map(); this.sourceStarts = new Map();
    }
    warn(message) { if (!this.warnings.includes(message)) this.warnings.push(message); }
    add(record, sourceName = '') {
      if (!record || typeof record !== 'object') throw new Error('A log record must be a JSON object.');
      if (Tests && Tests.accepts(record)) { this.reports.push(...Tests.normalize(record)); return; }
      if (record.type === 'header') {
        if (record.format !== 'qbm-log' || Number(record.version) !== 1) throw new Error('Unsupported capture format/version. This viewer supports qbm-log version 1.');
        const id = String(record.sessionId || '');
        if (!id || ns(record.startedElapsedNs) === null || !number(Number(record.startedUtcMs))) throw new Error('Capture header is missing its session or clock anchors.');
        const previous = this.headers.get(id);
        if (previous && (String(previous.startedElapsedNs) !== String(record.startedElapsedNs) || Number(previous.startedUtcMs) !== Number(record.startedUtcMs)))
          throw new Error('Conflicting clock anchors for session ' + id + '.');
        this.headers.set(id, record);
        if (!this.segments.has(id)) this.segments.set(id, new Set());
        this.segments.get(id).add(Number(record.segment || 0)); return;
      }
      if (record.type === 'footer') { this.footerSegments.add(String(record.sessionId) + ':' + Number(record.segment || 0)); }
      if (!['ui', 'packet', 'poll', 'state', 'gap', 'footer'].includes(record.type)) {
        if ('t' in record && 'r' in record) this.warn('Legacy cache records contain polling inputs only. Record a portable archive for full UI and packet replay.');
        else this.warn('Skipped an unsupported record type in ' + sourceName + '.');
        return;
      }
      if (this.records.length >= LIMITS.records) throw new Error('Import limit reached (' + LIMITS.records.toLocaleString() + ' records). Open a smaller set of gzip segments.');
      const id = String(record.sessionId || ''), seq = record.archiveSeq ?? record.recordSeq;
      const key = seq != null ? id + ':a:' + seq : record.type === 'packet' ? id + ':p:' + record.captureId + ':' + record.seq : null;
      if (key && this.seen.has(key)) return;
      if (key) this.seen.add(key);
      // Raw bytes stay in the source archive. Index decoded observations without duplicating huge hex strings.
      const compact = { ...record }; delete compact.raw; delete compact.rawhex;
      this.records.push(compact);
    }
    finish() {
      for (const [id, segments] of this.segments) {
        const sorted = Array.from(segments).sort((a, b) => a - b);
        for (let i = 1; i < sorted.length; i++) if (sorted[i] > sorted[i - 1] + 1) this.warn('Missing gzip segment(s) in session ' + id + '; carry-forward is reset at the next available segment.');
        for (const segment of sorted) if (!this.footerSegments.has(id + ':' + segment)) this.warn('Segment ' + segment + ' of ' + id + ' has no completion footer; it may be partial.');
      }
      const timed = [];
      this.records.forEach((r, order) => {
        const session = String(r.sessionId || ''), h = this.headers.get(session), timeNs = ns(r.tNs);
        if (!h || timeNs === null) { this.warn('Skipped records with missing session clock metadata.'); return; }
        const delta = timeNs - ns(h.startedElapsedNs), t = Number(h.startedUtcMs) + Number(delta) / 1e6;
        if (!number(t) || delta < 0n) { this.warn('Skipped an invalid capture timestamp.'); return; }
        timed.push({ r, t, order, session, sim: Number(r.sim || 0) });
        if (r.type === 'gap' && ns(r.endNs) !== null && ns(r.endNs) > timeNs) {
          const endT = Number(h.startedUtcMs) + Number(ns(r.endNs) - ns(h.startedElapsedNs)) / 1e6;
          timed.push({ r: { ...r, tNs: r.endNs, gapEnd: true }, t: endT, order: this.records.length + order, session, sim: Number(r.sim || 0) });
        }
      });
      timed.sort((a, b) => a.t - b.t || a.order - b.order);
      for (const e of timed) this.sessionEnds.set(e.session, Math.max(this.sessionEnds.get(e.session) ?? -Infinity, e.t));
      const state = new Map(), identities = new Map(), captures = new Map(), lastSegments = new Map();
      const clear = (predicate, t, order) => {
        for (const [id, v] of state) if (predicate(v.field)) { this._observe(id, null, t, order, null, true); state.delete(id); }
      };
      for (const entry of timed) {
        const { r, t, order, session, sim } = entry;
        const segment = Number(r.segment || 0), lastSegment = lastSegments.get(session);
        if (lastSegment != null && segment > lastSegment + 1) clear(f => f.sessionId === session, t, order);
        if (lastSegment == null || segment > lastSegment) lastSegments.set(session, segment);
        if (r.type === 'gap') {
          clear(f => f.sessionId === session && (!sim || f.sim === sim) && (r.source !== 'packet' || f.source === 'packet'), t, order);
          if (!r.gapEnd) {
            const h = this.headers.get(session), end = ns(r.endNs);
            const endT = end === null ? t : Math.max(t, Number(h.startedUtcMs) + Number(end - ns(h.startedElapsedNs)) / 1e6);
            this.gaps.push({ t, endT, order, session, sim, source: r.source || 'all' }); this.warn(r.reason || 'Capture contains dropped records.');
          }
          continue;
        }
        if (!['ui', 'packet'].includes(r.type)) continue;
        const source = r.type;
        const sourceKey = session + '|' + source;
        if (!this.sourceStarts.has(sourceKey)) this.sourceStarts.set(sourceKey, t);
        if (source === 'packet' && r.simTagged === false) this.warn('Some DIAG packets are not subscription-tagged; their fallback SIM attribution is unverified.');
        if (source === 'packet' && r.captureId != null) {
          const previous = captures.get(session);
          if (previous != null && previous !== r.captureId) { clear(f => f.sessionId === session && f.source === 'packet', t, order); this.warn('Native capture restarted; carried observations were cleared.'); }
          captures.set(session, r.captureId);
        }
        const updates = normalizedUpdates(r), currentIds = new Set();
        for (const entity of r.resets || []) clear(f => f.sessionId === session && f.sim === sim && f.source === source && f.entity === String(entity), t, order);
        const aggregates = new Map((r.aggregates || []).map(u => [String(u.entity) + ':' + String(u.metric), u]));
        const indexed = [];
        for (const original of updates) {
          if (!original || typeof original.metric !== 'string' || !scalar(original.value)) continue;
          const entity = String(original.entity || 'device'), metric = original.metric;
          const identityKey = [source, session, sim, entity].join('|');
          if ('identity' in original) {
            const identity = original.identity == null ? null : String(original.identity);
            if (identities.has(identityKey) && identities.get(identityKey) !== identity) clear(f => f.sessionId === session && f.sim === sim && f.source === source && f.entity === entity, t, order);
            identities.set(identityKey, identity);
          }
          const id = fieldId(source, session, sim, entity, metric);
          let agg = aggregates.get(entity + ':' + metric);
          if (!agg && source === 'ui' && metric === 'modulation') agg = aggregates.get(entity + ':mod');
          if (!agg && source === 'ui' && (metric === 'rsrp' || metric === 'rsrq')) agg = aggregates.get(entity + ':f' + metric);
          if (source === 'ui' && ['mbps_sched', 'dl_mbps', 'ul_mbps'].includes(metric)) {
            const bytes = aggregates.get(entity + ':' + (metric === 'ul_mbps' ? 'ul_bytes' : 'dl_bytes'));
            if (bytes && number(bytes.value)) agg = { aggregation: 'rate', amount: bytes.value, durationMs: Number(r.windowMs) || 0 };
          }
          if (original.aggregateEligible === false) agg = null;
          let descriptor = this.fields.get(id);
          if (!descriptor) {
            descriptor = { id, sessionId: session, source, sim, entity, metric, label: original.label || entity + ' · ' + label(metric), unit: String(original.unit || ''),
              aggregation: agg?.aggregation || original.aggregation || 'last', type: typeof original.value === 'number' ? 'number' : 'string', count: 0 };
            this.fields.set(id, descriptor); this.histories.set(id, []);
          }
          if (number(original.value)) descriptor.type = 'number';
          if (agg?.aggregation) descriptor.aggregation = agg.aggregation;
          const weights = original.aggregateEligible === false ? { ...original, sampledOnly: true } : agg || (source === 'ui' && descriptor.aggregation !== 'last' ? { held: true } : original);
          const observation = this._observe(id, original.value, t, order, weights, false);
          observation.fresh = source === 'packet' || r.snapshotFresh !== false;
          if (source === 'ui' && number(Number(r.snapshotTakenAtElapsedMs)) && r.snapshotTakenAtElapsedMs != null) {
            const h = this.headers.get(session);
            observation.observedAtMs = Number(h.startedUtcMs) + Number(BigInt(Math.trunc(Number(r.snapshotTakenAtElapsedMs))) * 1000000n - ns(h.startedElapsedNs)) / 1e6;
          }
          currentIds.add(id); indexed.push(observation);
          if (original.value === null) state.delete(id); else state.set(id, { field: descriptor });
        }
        if (source === 'ui') clear(f => f.sessionId === session && f.sim === sim && f.source === source && !currentIds.has(f.id), t, order);
        this.events.push({ t, order, sessionId: session, sim, source, updates: indexed, snapshot: r.snapshot || null,
          metadata: { code: r.code, seq: r.seq, archiveSeq: r.archiveSeq, modemTs: r.modemTs, captureId: r.captureId, sim, simTagged: r.simTagged, tNs: r.tNs }, frozen: r.frozen,
          operatorName: r.operatorName, error: r.error, diagError: r.diagError, presentation: r.presentation || {}, visible: r.visible, resumed: r.resumed });
      }
      for (const descriptor of Array.from(this.fields.values())) {
        if (descriptor.source === 'packet' && ['dl_bytes', 'ul_bytes', 'dl_good_bytes'].includes(descriptor.metric)) {
          const metric = descriptor.metric.replace('_bytes', '_mbps'), id = fieldId('packet', descriptor.sessionId, descriptor.sim, descriptor.entity, metric);
          if (!this.fields.has(id)) this.fields.set(id, { ...descriptor, id, metric, label: descriptor.entity + ' · ' + label(metric) + ' (interval-derived)', unit: 'Mbps', aggregation: 'rate', derivedFrom: descriptor.id, type: 'number' });
        }
      }
      this.records = []; this.seen.clear();
      this.reports = Tests ? Tests.deduplicate(this.reports) : [];
      const starts = this.reports.map(r => Number(r.startedAtMs) || 0), ends = this.reports.map(r => Math.max(Number(r.endedAtMs || r.startedAtMs) || 0, ...r.attempts.map(a => Number(a.startedAtMs) || 0)));
      if (this.events.length) { starts.push(this.events[0].t); ends.push(...this.sessionEnds.values()); }
      this.startMs = Math.min(...starts); this.endMs = Math.max(...ends);
      if (!Number.isFinite(this.startMs)) this.startMs = this.endMs = 0;
      if (!this.events.length && !this.reports.length) throw new Error('No supported capture or test-report records were found. Open qbm-log .ndjson.gz segments or exported test-report JSON/CSV.');
      return this;
    }
    _observe(id, value, t, order, weights, cleared) {
      if (++this.updateCount > LIMITS.updates) throw new Error('Too many observations for one import. Open fewer capture segments.');
      const o = { id, value, t, order, weights, cleared };
      this.histories.get(id).push(o); this.fields.get(id).count++; return o;
    }
    summary() {
      return { kind: this.events.length && this.reports.length ? 'mixed' : this.events.length ? 'replay' : 'tests',
        sessions: Array.from(this.headers, ([id, h]) => ({ id, label: String(h.device?.model || h.device || id), startedUtcMs: Number(h.startedUtcMs), startMs: this.events.find(e => e.sessionId === id)?.t ?? Number(h.startedUtcMs), endMs: this.sessionEnds.get(id) ?? Number(h.startedUtcMs) })),
        sims: Array.from(new Set(this.events.map(e => e.sim).filter(Boolean))).sort(), startMs: this.startMs, endMs: this.endMs,
        recordCount: this.events.length, packetCount: this.events.filter(e => e.source === 'packet').length, uiCount: this.events.filter(e => e.source === 'ui').length,
        warnings: this.warnings.slice(), fields: Array.from(this.fields.values()), testReports: this.reports.map(Tests.describe) };
    }
    selected(options = {}) {
      const mode = options.mode || 'ui', ids = options.fieldIds;
      return Array.from(this.fields.values()).filter(f => f.source === mode && (!options.sessionId || f.sessionId === options.sessionId) && (!options.sim || f.sim === Number(options.sim)) && (ids == null || ids.includes(f.id)));
    }
    latest(id, at, order = Infinity) { const h = this.histories.get(id) || []; let i = upperBound(h, at) - 1; while (i >= 0 && h[i].t === at && h[i].order > order) i--; return i >= 0 ? h[i] : null; }
    gapAt(field, at) { return this.gaps.some(g => at >= g.t && at <= g.endT && g.session === field.sessionId && (!g.sim || g.sim === field.sim) && (g.source === 'all' || g.source === field.source)); }
    inSession(field, at) { return at >= (this.sourceStarts.get(field.sessionId + '|' + field.source) ?? Infinity) && at <= (this.sessionEnds.get(field.sessionId) ?? -Infinity); }
    frame(options = {}) {
      const atMs = number(options.atMs) ? options.atMs : this.startMs, values = object();
      for (const f of this.selected(options)) {
        const o = this.latest(f.derivedFrom || f.id, atMs);
        const value = !this.inSession(f, atMs) || this.gapAt(f, atMs) ? null : f.derivedFrom ? this.reduce(f, atMs - (options.rateWindowMs || 1000), atMs, false) : o?.value ?? null;
        const observedAt = o ? o.observedAtMs ?? o.t : null;
        values[f.id] = { value, observedAtMs: observedAt, ageMs: o ? Math.max(0, atMs - observedAt) : null, carried: !!o && observedAt < atMs, unit: f.unit, label: f.label };
      }
      let event = null;
      const index = upperBound(this.events, atMs) - 1;
      for (let i = index; i >= 0; i--) {
        const e = this.events[i];
        if (e.source === (options.mode || 'ui') && (!options.sim || e.sim === Number(options.sim)) && (!options.sessionId || e.sessionId === options.sessionId)) { event = e; break; }
      }
      const invalidated = event && (atMs > this.sessionEnds.get(event.sessionId) || this.gaps.some(g => g.t <= atMs && (g.endT > event.t || (g.endT === event.t && g.order > event.order)) && g.session === event.sessionId && (!g.sim || g.sim === event.sim) && (g.source === 'all' || g.source === event.source)));
      return { atMs, values, snapshot: invalidated || !event?.snapshot ? null : { ...event.snapshot, operatorName: event.operatorName ?? event.snapshot.operatorName },
        event: invalidated ? null : event?.metadata || null, frozen: event?.frozen || false, error: event?.error || null,
        diagError: event?.diagError || null, presentation: event?.presentation || {}, visible: event?.visible, resumed: event?.resumed };
    }
    reduce(f, from, to, includeStart) {
      if (!this.inSession(f, to)) return null;
      if (f.aggregation === 'rate' && f.source === 'packet' && from < (this.sourceStarts.get(f.sessionId + '|packet') ?? Infinity)) return null;
      if (this.gaps.some(g => to >= g.t && from <= g.endT && g.session === f.sessionId && (!g.sim || g.sim === f.sim) && (g.source === 'all' || g.source === f.source))) return null;
      const h = this.histories.get(f.derivedFrom || f.id) || [];
      let a = upperBound(h, from); if (includeStart) while (a > 0 && h[a - 1].t === from) a--;
      const b = upperBound(h, to);
      if (f.source === 'packet' && (f.aggregation === 'sum' || f.aggregation === 'rate')) {
        const key = f.derivedFrom || f.id;
        let index = this.counterIndexes.get(key);
        if (!index) {
          index = { sum: new Float64Array(h.length + 1), resets: new Uint32Array(h.length + 1) };
          h.forEach((o, i) => {
            index.sum[i + 1] = index.sum[i] + (number(o.weights?.amount) ? o.weights.amount : number(o.value) ? o.value : 0);
            index.resets[i + 1] = index.resets[i] + (o.cleared || o.value == null ? 1 : 0);
          }); this.counterIndexes.set(key, index);
        }
        if (!b || h[b - 1].value == null || index.resets[b] !== index.resets[a]) return null;
        const amount = index.sum[b] - index.sum[a];
        return f.aggregation === 'rate' ? to > from ? amount * 8 / ((to - from) * 1000) : null : amount;
      }
      const all = h.slice(a, b);
      const isReset = o => o.cleared || (o.value === null && !(o.weights?.aggregation === 'mode' && o.weights?.counts));
      const lastReset = all.findLastIndex ? all.findLastIndex(isReset) : (() => { for (let i = all.length - 1; i >= 0; i--) if (isReset(all[i])) return i; return -1; })();
      // A known loss/reset inside a bucket makes its aggregate incomplete, even if later values resume.
      if (lastReset >= 0) return null;
      const samples = all.filter(o => (o.value !== null || (o.weights?.aggregation === 'mode' && o.weights?.counts)) && !o.weights?.held), latest = this.latest(f.derivedFrom || f.id, to);
      const agg = f.aggregation;
      if (agg === 'last') return latest?.value ?? null;
      if (agg === 'sum' || agg === 'rate') {
        if (!latest || latest.value == null) return null;
        if (agg === 'rate' && f.source === 'ui') {
          const observed = samples.filter(o => number(o.weights?.amount) && number(o.weights?.durationMs) && o.weights.durationMs > 0);
          return observed.length ? observed.reduce((s, o) => s + o.weights.amount, 0) * 8 / (observed.reduce((s, o) => s + o.weights.durationMs, 0) * 1000) : latest.value;
        }
        const amount = samples.reduce((sum, o) => sum + (number(o.weights?.amount) ? o.weights.amount : number(o.value) ? o.value : 0), 0);
        return agg === 'rate' ? to > from ? amount * 8 / ((to - from) * 1000) : null : amount;
      }
      if (!samples.length) return latest?.value ?? null;
      if (agg === 'mean') {
        let sum = 0, count = 0;
        samples.forEach(o => { const w = o.weights; if (number(w?.sum) && number(w?.count) && w.count > 0) { sum += w.sum; count += w.count; } else if (number(o.value)) { sum += o.value; count++; } });
        return count ? sum / count : null;
      }
      if (agg === 'ratio') {
        let numerator = 0, denominator = 0, scale = 100, valid = false;
        samples.forEach(o => { const w = o.weights; if (number(w?.numerator) && number(w?.denominator)) { numerator += w.numerator; denominator += w.denominator; scale = number(w.scale) ? w.scale : 100; valid = true; } });
        return valid ? denominator ? numerator * scale / denominator : null : statistics(samples.map(o => o.value)).mean;
      }
      if (agg === 'mode') {
        const votes = new Map(), order = [], mapping = object();
        samples.forEach(o => { for (const key of o.weights?.order || []) if (!order.includes(String(key))) order.push(String(key)); if (o.weights?.map) Object.assign(mapping, o.weights.map); });
        samples.forEach(o => { if (o.weights?.counts && typeof o.weights.counts === 'object') Object.entries(o.weights.counts).forEach(([key, count]) => { if (number(count) && count > 0) votes.set(key, (votes.get(key) || 0) + count); }); else votes.set(String(o.value), (votes.get(String(o.value)) || 0) + 1); });
        const winner = Array.from(votes).sort((a, b) => b[1] - a[1] || (order.includes(a[0]) ? order.indexOf(a[0]) : 100000) - (order.includes(b[0]) ? order.indexOf(b[0]) : 100000) || a[0].localeCompare(b[0], 'en', { numeric: true }))[0]?.[0];
        if (winner == null) return null;
        return winner in mapping ? mapping[winner] : f.type === 'number' && Number.isFinite(Number(winner)) ? Number(winner) : winner;
      }
      return latest?.value ?? null;
    }
    rows(options = {}) {
      if (options.reportId) return this.testRows(options);
      const fields = this.selected(options), from = number(options.startMs) ? Math.max(this.startMs, options.startMs) : this.startMs;
      const to = number(options.endMs) ? Math.min(this.endMs, options.endMs) : this.endMs;
      if (to < from) throw new Error('End time must be at or after start time.');
      if (!fields.length) return { rows: [], columns: [], notes: ['Select at least one field.'] };
      const interval = Number(options.intervalMs || 0); if (!Number.isFinite(interval) || interval < 0) throw new Error('Interval must be zero (native events) or a positive number of milliseconds.');
      const notes = this.warnings.slice(), times = [], eventOrders = [], eventRefs = [];
      if (interval > 0) {
        if ((to - from) / interval > LIMITS.exportRows) throw new Error('This interval produces too many rows. Choose a coarser interval or shorter time range.');
        for (let t = from; t <= to; t = from + times.length * interval) times.push(t);
        if (times[times.length - 1] !== to) times.push(to);
      } else {
        const seen = new Set();
        for (const e of this.events) if (e.t >= from && e.t <= to && e.source === (options.mode || 'ui') && (!options.sim || e.sim === Number(options.sim)) && (!options.sessionId || e.sessionId === options.sessionId)) {
          // Preserve distinct same-time packets; a frame lookup includes ties, so use event order below.
          times.push(e.t); eventOrders.push(e.order); eventRefs.push(e); seen.add(e.t);
        }
        if (times.length > LIMITS.exportRows) throw new Error('Too many native rows for export. Choose a shorter time range or a resampling interval.');
        if (seen.size !== times.length) notes.push('Multiple events share a display timestamp; original nanoseconds and sequence remain in the archive.');
      }
      if (options.mode === 'ui' && interval > 0) notes.push('UI samples cannot recover packet detail inside a polling window. Stored window weights are used when present; other means use sampled display values.');
      if (times.length * fields.length > LIMITS.queryCells) throw new Error('This selection contains more than 2 million data cells. Choose fewer fields, a shorter range or a coarser interval.');
      if (options.mode === 'packet' && fields.some(f => f.derivedFrom)) notes.push('Throughput is derived from byte increments over the selected interval (1 s lookback for native-event plots), not from packet arrival gaps.');
      const rows = times.map((t, index) => {
        const values = object();
        for (const f of fields) {
          if (!this.inSession(f, t) || this.gapAt(f, t)) values[f.id] = null;
          else if (interval > 0 && options.aggregation !== 'locf') values[f.id] = this.reduce(f, index ? times[index - 1] : t, t, index === 0);
          else if (f.derivedFrom) values[f.id] = this.reduce(f, t - (interval || 1000), t, false);
          else values[f.id] = this.latest(f.id, t, interval > 0 ? Infinity : eventOrders[index])?.value ?? null;
        }
        const event = interval > 0 ? null : eventRefs[index];
        return { t, eventOrder: event?.order, sessionId: event?.sessionId, sim: event?.sim, tNs: event?.metadata.tNs,
          seq: event?.metadata.seq, archiveSeq: event?.metadata.archiveSeq, modemTs: event?.metadata.modemTs, code: event?.metadata.code, values };
      });
      return { rows, columns: fields.map(({ id, label, unit }) => ({ id, label, unit })), notes };
    }
    series(options = {}) {
      const result = this.rows(options), stats = object();
      for (const f of result.columns) {
        stats[f.id] = statistics(result.rows.map(row => row.values[f.id]));
        if (!options.reportId) {
          const descriptor = this.fields.get(f.id), history = this.histories.get(descriptor?.derivedFrom || f.id) || [];
          stats[f.id].observationCount = history.filter(o => !o.cleared && o.value != null && o.fresh !== false && (!number(options.startMs) || o.t >= options.startMs) && (!number(options.endMs) || o.t <= options.endMs)).length;
          if (descriptor?.type === 'string') {
            const votes = new Map(); let count = 0;
            for (const row of result.rows) if (row.values[f.id] != null) { const v = row.values[f.id]; votes.set(v, (votes.get(v) || 0) + 1); count++; }
            stats[f.id] = { ...stats[f.id], count, distinct: votes.size, mode: Array.from(votes).sort((a, b) => b[1] - a[1])[0]?.[0] ?? null };
          }
        }
      }
      const fullCount = result.rows.length, cap = Math.max(2, Number(options.maxPoints) || fullCount);
      if (fullCount > cap) {
        const chosen = new Set([0, fullCount - 1]);
        // Never join a line across a missing interval or erase a categorical state transition.
        for (const f of result.columns) for (let i = 1; i < fullCount; i++) {
          const previous = result.rows[i - 1].values[f.id], value = result.rows[i].values[f.id];
          if ((previous == null) !== (value == null) || (this.fields.get(f.id)?.type === 'string' && previous !== value)) { chosen.add(i - 1); chosen.add(i); }
        }
        // Preserve extrema as well as the shape, rather than silently hiding narrow peaks.
        const buckets = Math.max(1, Math.floor(cap / Math.max(2, result.columns.length * 2)));
        for (let b = 0; b < buckets; b++) {
          const a = Math.floor(b * fullCount / buckets), z = Math.min(fullCount, Math.floor((b + 1) * fullCount / buckets));
          for (const f of result.columns) {
            let min = -1, max = -1;
            for (let i = a; i < z; i++) if (number(result.rows[i].values[f.id])) {
              if (min < 0 || result.rows[i].values[f.id] < result.rows[min].values[f.id]) min = i;
              if (max < 0 || result.rows[i].values[f.id] > result.rows[max].values[f.id]) max = i;
            }
            if (min >= 0) chosen.add(min); if (max >= 0) chosen.add(max);
          }
        }
        result.rows = Array.from(chosen).sort((a, b) => a - b).map(i => result.rows[i]);
        result.notes.push('Plot decimated from ' + fullCount + ' rows; statistics and exports use all requested rows. Categorical and missing-value transitions are retained even when they exceed the display-point target.');
      }
      return { ...result, stats, fullCount };
    }
    testResults(options = {}) { const report = this.reports.find(r => r.id === options.reportId) || this.reports[0]; if (!report) throw new Error('No test report is loaded.'); return Tests.inspect(report, options); }
    testRows(options = {}) {
      const result = this.testResults(options), selected = result.fields.filter(f => !options.fieldIds?.length || options.fieldIds.includes(f.id));
      let rows = result.rows.filter(r => (!number(options.startMs) || r.t >= options.startMs) && (!number(options.endMs) || r.t <= options.endMs))
        .map(r => ({ ...r, values: Object.fromEntries(selected.map(f => [f.id, r.values[f.id] ?? null])) }));
      const interval = Number(options.intervalMs || 0), notes = result.notes.slice();
      if (interval > 0 && rows.length) {
        const origin = rows[0].t, groups = new Map();
        for (const row of rows) { const key = origin + Math.floor((row.t - origin) / interval) * interval; if (!groups.has(key)) groups.set(key, []); groups.get(key).push(row); }
        rows = Array.from(groups, ([t, group]) => ({ t, values: Object.fromEntries(selected.map(f => [f.id, options.aggregation === 'locf' ? group[group.length - 1].values[f.id] : statistics(group.map(r => r.values[f.id])).mean])) }));
        notes.push('Graph/export bins summarize test attempts only. Regulatory assessments continue to use the original unsmoothed attempts.');
      }
      return { rows, columns: selected, notes, assessment: result.metrics, report: result.report };
    }
    previewExport(options = {}) { const data = this.rows(options); return { ...data, estimatedRows: data.rows.length, rows: data.rows.slice(0, Math.min(50, Number(options.limit) || 8)) }; }
    exportData(options = {}) {
      const data = this.rows(options), format = options.format || 'json'; let text, mime;
      if (!data.columns.length) throw new Error('Select at least one field to export.');
      if (format === 'csv') {
        mime = 'text/csv';
        const header = ['time_utc', 'time_ms', 'source', 'aggregation', 'session_id', 'sim', 'time_ns', 'packet_seq', 'archive_seq', 'modem_timestamp', 'log_code', 'attempt', 'outcome', 'regime', 'test_function', ...data.columns.map(f => f.id)];
        const rows = data.rows.map(r => [new Date(r.t).toISOString(), r.t, options.reportId || options.mode || 'ui', options.aggregation || 'mobile', r.sessionId, r.sim, r.tNs, r.seq, r.archiveSeq, r.modemTs, r.code, r.attempt, r.outcome, data.report?.regime, data.report?.kind, ...data.columns.map(f => r.values[f.id])]);
        text = [header, ...rows].map(row => row.map(csvCell).join(',')).join('\r\n') + '\r\n';
      } else if (format === 'ndjson') {
        mime = 'application/x-ndjson';
        text = JSON.stringify({ type: 'export-header', format: 'qbm-export', version: 1, source: options.reportId || options.mode || 'ui', aggregation: options.aggregation || 'mobile', columns: data.columns, notes: data.notes, sessions: options.reportId ? undefined : Array.from(this.headers.values()), assessment: data.assessment }) + '\n' + data.rows.map(row => JSON.stringify(row)).join('\n') + '\n';
      } else if (format === 'json') {
        mime = 'application/json'; text = JSON.stringify({ format: 'qbm-export', version: 1, source: options.reportId || options.mode || 'ui', aggregation: options.aggregation || 'mobile', columns: data.columns, notes: data.notes, sessions: options.reportId ? undefined : Array.from(this.headers.values()), assessment: data.assessment, report: data.report, rows: data.rows }, null, 2);
      } else throw new Error('Choose JSON, CSV or NDJSON.');
      return { text, mime, filename: 'qbm-' + (options.reportId ? 'test-results' : options.mode || 'ui') + '.' + format, rowCount: data.rows.length };
    }
  }
  async function readFiles(files, onProgress = () => {}) {
    const dataset = new Dataset(); let totalBytes = 0, records = 0;
    for (const file of files) {
      const signature = new Uint8Array(await file.slice(0, 2).arrayBuffer()), gzip = signature[0] === 31 && signature[1] === 139;
      let stream = file.stream();
      if (gzip) { if (typeof DecompressionStream === 'undefined') throw new Error('This browser cannot decompress gzip. Use current Chrome, Edge or Firefox, or decompress the log first.'); stream = stream.pipeThrough(new DecompressionStream('gzip')); }
      const reader = stream.getReader(), decoder = new TextDecoder(), name = file.name || 'input';
      let tail = '', document = '', mode = null, complete = true, previousCount = 0;
      function line(text) {
        if (!text.trim()) return;
        if (text.length > LIMITS.lineBytes) throw new Error('A log line is too large to safely import.');
        dataset.add(JSON.parse(text), name); records++;
      }
      try {
        while (true) {
          const chunk = await reader.read(); if (chunk.done) break;
          totalBytes += chunk.value.byteLength; if (totalBytes > LIMITS.decodedBytes) throw new Error('Decoded input exceeds 512 MiB. Open fewer capture segments.');
          const text = decoder.decode(chunk.value, { stream: true });
          if (mode === 'document') document += text;
          else {
            tail += text;
            let split;
            while ((split = tail.indexOf('\n')) >= 0) {
              const first = tail.slice(0, split).replace(/^\uFEFF/, '');
              if (mode === null) { try { const obj = JSON.parse(first); mode = obj.type ? 'lines' : 'document'; } catch (_) { mode = 'document'; } }
              if (mode === 'document') { document = tail; tail = ''; break; }
              line(first); tail = tail.slice(split + 1);
            }
          }
          if (records - previousCount >= 500 || mode === 'document') { onProgress({ phase: 'Reading', records, bytes: totalBytes, file: name }); previousCount = records; await new Promise(resolve => setTimeout(resolve, 0)); }
        }
        const final = decoder.decode(); if (mode === 'document') document += final; else tail += final;
      } catch (e) {
        complete = false;
        if (!gzip || !records || /limit|exceed|too large|Unsupported|Conflicting/.test(e.message)) throw e;
        dataset.warn('Incomplete gzip segment ' + name + ': only complete recovered records were imported.');
      } finally { reader.releaseLock(); }
      if (mode === 'document' || mode === null) {
        const text = (document || tail).replace(/^\uFEFF/, '').trim();
        if (!text) continue;
        if (text[0] === '{' || text[0] === '[') {
          const parsed = JSON.parse(text); if (Array.isArray(parsed)) parsed.forEach(r => dataset.add(r, name)); else dataset.add(parsed, name);
        } else {
          const reports = Tests ? Tests.fromCsv(parseCsv(text)) : []; if (!reports.length) throw new Error('CSV is not a supported mobile test-report export.'); dataset.reports.push(...reports);
        }
      } else if (complete && tail.trim()) line(tail);
    }
    onProgress({ phase: 'Indexing', records, bytes: totalBytes }); return dataset.finish();
  }
  function parseText(input) {
    const data = new Dataset();
    for (const text of Array.isArray(input) ? input : [input]) {
      const clean = String(text).replace(/^\uFEFF/, '').trim();
      let parsed, isDocument = false;
      try { parsed = JSON.parse(clean); isDocument = true; } catch (e) { if (!clean.includes('\n')) throw e; }
      if (isDocument) { if (Array.isArray(parsed)) parsed.forEach(r => data.add(r)); else data.add(parsed); }
      else clean.split(/\r?\n/).filter(Boolean).forEach(line => data.add(JSON.parse(line)));
    }
    return data.finish();
  }
  function demoText() {
    const base = 1700000000000, startNs = 1000000000n, lines = [], sessionId = 'synthetic-demo';
    lines.push({ type: 'header', format: 'qbm-log', version: 1, sessionId, segment: 0, startedUtcMs: base, startedElapsedNs: String(startNs), device: { model: 'Synthetic example' }, packetCapture: true });
    let archiveSeq = 0, packetSeq = 0;
    for (let ms = 0; ms <= 30000; ms += 5) {
      const updates = [{ entity: 'lte:0', identity: ms < 15000 ? '1300:42' : '1650:77', metric: 'sinr', value: 15 + 8 * Math.sin(ms / 2700), unit: 'dB', aggregation: 'mean', sum: 15 + 8 * Math.sin(ms / 2700), count: 1 }];
      if (ms % 20 === 0) updates.push({ entity: 'lte:0', identity: ms < 15000 ? '1300:42' : '1650:77', metric: 'rsrp', value: -91 + 7 * Math.sin(ms / 4700), unit: 'dBm', aggregation: 'mean', sum: -91 + 7 * Math.sin(ms / 4700), count: 1 });
      updates.push({ entity: 'lte:0', metric: 'dl_bytes', value: Math.round(40000 + 20000 * Math.sin(ms / 3200)), unit: 'bytes', aggregation: 'sum' });
      lines.push({ type: 'packet', sessionId, archiveSeq: ++archiveSeq, seq: ++packetSeq, captureId: 'demo', segment: 0, tNs: String(startNs + BigInt(ms) * 1000000n), sim: 1, code: ms % 20 ? '0xB8D8' : '0xB193', modemTs: 'synthetic', updates });
      if (ms % 1000 === 0) lines.push({ type: 'ui', sessionId, archiveSeq: ++archiveSeq, segment: 0, tNs: String(startNs + BigInt(ms) * 1000000n), sim: 1,
        snapshot: { modeLabel: 'LTE · Synthetic example', operatorName: 'Demo network', cells: [{ title: 'Serving cell', fields: [{ label: 'Band', value: 'B3' }, { label: 'PCI', value: ms < 15000 ? '42' : '77' }], metrics: updates.filter(u => u.metric !== 'dl_bytes').map(u => ({ label: label(u.metric), value: u.value, unit: u.unit })) }] },
        updates: updates.filter(u => u.metric !== 'dl_bytes').map(u => ({ ...u, value: Number(u.value.toFixed(2)) })) });
    }
    lines.push({ type: 'footer', sessionId, segment: 0, tNs: String(startNs + 30000000000n), reason: 'synthetic example complete' });
    return lines.map(r => JSON.stringify(r)).join('\n');
  }
  return { Dataset, parseText, readFiles, parseCsv, statistics, csvCell, demoText, LIMITS };
});
