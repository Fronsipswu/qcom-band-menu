'use strict';
'require view';
'require rpc';
'require ui';
'require uci';
'require dom';
'require qbm-diagd.modem as modemInfo';

/*
 * Band locking through qbm-qmi (qcom-bandlockd over the QMI port). Every
 * reply carries the modem's whole state, so the page re-renders from it.
 * A Fibocom FM350 (no QMI) is locked with AT+GTACT instead (renderAt).
 */

var callQuery = rpc.declare({ object: 'luci.qbm-diagd', method: 'qmi_query', params: [ 'sim', 'cmd' ] });
var callQmi = rpc.declare({ object: 'luci.qbm-diagd', method: 'qmi', params: [ 'sim', 'request' ] });
var callAtBands = rpc.declare({ object: 'luci.qbm-diagd', method: 'at_bands' });
var callAtBandsSet = rpc.declare({ object: 'luci.qbm-diagd', method: 'at_bands_set', params: [ 'rat', 'p1', 'p2', 'bands' ] });
var callReconnect = rpc.declare({ object: 'luci.qbm-diagd', method: 'reconnect' });
var callRefreshIp = rpc.declare({ object: 'luci.qbm-diagd', method: 'refresh_ip' });

/*
 * AT+GTACT (Fibocom AT manual 11.1.14): <rat>,<PreferredAct1>,<PreferredAct2>,
 * then band codes of every RAT: 1-10 UMTS, 101-171 LTE (B = code - 100),
 * 501... NR ("50" + band: 5041 = n41). "=?" lists the supported RATs, the
 * preferred-RAT codes and each RAT's hardware bands (GSM, UMTS, LTE, CDMA,
 * EVDO, NR). Preferred RAT codes: 2 WCDMA, 3 LTE, 6 NR.
 */
var GTACT_P1 = 6, GTACT_P2 = 3;   /* preferred RATs: NR first, then LTE */

/*
 * The FM350 rejects an AT+GTACT whose band list does not cover every RAT, so a
 * RAT the user deselects is still sent with one band that is only deployed on
 * AT&T networks (WCDMA B5, LTE B30, NR n30). The mask is always sent as
 * <rat>=20 (NR/LTE/WCDMA) with NR then LTE preferred; the fallback bands are
 * what actually turn the unwanted RATs off here.
 */
var GTACT_FALLBACK = { wcdma: 5, lte: 130, nr: 5030 };

function bandOf(code) {
	var s = String(code);
	if (/^50\d+$/.test(s)) return { rat: 'nr', band: +s.slice(2) };
	if (code > 100 && code < 200) return { rat: 'lte', band: code - 100 };
	if (code > 0 && code < 100) return { rat: 'wcdma', band: code };
	return null;
}

function codeOf(rat, band) {
	return rat === 'nr' ? +('50' + band) : rat === 'lte' ? band + 100 : band;
}

/* at_bands reply -> { valid, rats, hw: {nr, lte, wcdma}, rat, p1, p2, cur: {nr, lte, wcdma} } */
function parseGtact(r) {
	var st = { valid: false, rats: [], hw: { nr: [], lte: [], wcdma: [] }, cur: { nr: [], lte: [], wcdma: [] } };
	var replies = (r && r.ok && r.replies) || [];
	function line(reply) {
		return String(reply || '').split('\n').map(function(l) { return l.trim(); }).filter(function(l) { return /^\+GTACT:/.test(l); })[0];
	}
	var test = line(replies[0]), now = line(replies[1]);
	st.raw = now || null;
	if (test) {
		var groups = [], m, re = /\(([^)]*)\)/g;
		while ((m = re.exec(test))) groups.push(m[1] ? m[1].split(',').map(Number) : []);
		st.rats = groups[0] || [];
		[ 'wcdma', 'lte', 'nr' ].forEach(function(rat, i) {
			(groups[[ 4, 5, 8 ][i]] || []).forEach(function(c) {
				var b = bandOf(c);
				if (b && b.rat === rat) st.hw[rat].push(b.band);
			});
		});
	}
	if (now) {
		var f = now.replace(/^\+GTACT:\s*/, '').split(',').map(function(x) { return x.trim(); });
		st.rat = +f[0];
		st.p1 = f[1] !== '' && f[1] != null ? +f[1] : null;
		st.p2 = f[2] !== '' && f[2] != null ? +f[2] : null;
		f.slice(3).forEach(function(x) {
			var b = bandOf(+x);
			if (b) st.cur[b.rat].push(b.band);
		});
		st.valid = !!test;
	}
	return st;
}

/*
 * The FM350's last band selection per RAT, kept in the browser: a RAT that is
 * switched off goes to the modem as its fallback band only, so the modem no
 * longer holds its bands. The page shows these for an unchecked RAT and sends
 * them again when the RAT is checked. Saved on every Apply (all three RATs,
 * checked or not) and from the modem's own mask for each RAT that is on.
 */
var REMEMBER_KEY = 'qbm-gtact-bands';
function remembered() {
	try {
		var v = JSON.parse(localStorage.getItem(REMEMBER_KEY) || '{}');
		var out = {};
		[ 'nr', 'lte', 'wcdma' ].forEach(function(r) { out[r] = Array.isArray(v[r]) ? v[r].filter(function(b) { return b > 0; }) : []; });
		return out;
	} catch (e) { return { nr: [], lte: [], wcdma: [] }; }
}
function remember(sel) {
	var v = remembered();
	[ 'nr', 'lte', 'wcdma' ].forEach(function(r) { if (sel[r] && sel[r].length) v[r] = sel[r].slice(); });
	try { localStorage.setItem(REMEMBER_KEY, JSON.stringify(v)); } catch (e) {}
}

function atOk(r) {
	var first = (r && r.ok && r.replies && r.replies[0]) || '';
	return /(^|\n)OK(\n|$)/.test(first);
}

var CSS = '\
.qbm-bar { display:flex; flex-wrap:wrap; align-items:center; gap:.5em 1em; margin-bottom:1em }\
.qbm-bar .qbm-meta { opacity:.75; font-size:90% }\
.qbm-sims { margin-left:auto; display:flex; gap:.25em }\
.qbm-grid { display:grid; grid-template-columns:repeat(auto-fill,minmax(5.2em,1fr)); gap:.35em; margin:.5em 0 .75em }\
.qbm-band { display:flex; align-items:center; gap:.35em; border:1px solid rgba(128,128,128,.35); border-radius:4px; padding:.25em .45em; cursor:pointer; user-select:none; font-variant-numeric:tabular-nums }\
.qbm-band.on { border-color:#2a7ab9; background:rgba(42,122,185,.12) }\
.qbm-band.changed { box-shadow:0 0 0 2px rgba(201,104,43,.6) }\
.qbm-band input { margin:0 }\
.qbm-actions { display:flex; flex-wrap:wrap; gap:.4em; align-items:center }\
.qbm-actions .qbm-meta { opacity:.75; font-size:90%; margin-left:.5em }\
.qbm-row { display:flex; flex-wrap:wrap; gap:.75em 1.25em; align-items:center; margin:.5em 0 .75em }\
.qbm-row label { display:flex; align-items:center; gap:.35em; cursor:pointer }\
.qbm-row input[type=number] { width:6em }\
';

function sim() {
	try { return +(localStorage.getItem('qbm-sim') || 1) === 2 ? 2 : 1; } catch (e) { return 1; }
}
function setSim(n) {
	try { localStorage.setItem('qbm-sim', String(n)); } catch (e) {}
}

function errorText(r) {
	if (!r) return _('No reply from the router.');
	var e = r.error || {};
	if (typeof e === 'string') return e;
	var t = e.message || _('Request failed.');
	if (e.rejected_bands && e.rejected_bands.length)
		t += ' ' + _('Not supported by this modem: %s').format(e.rejected_bands.join(', '));
	if (e.stage === 'modem_rejected')
		t += ' ' + _('(modem result %d, error %d)').format(e.result || 0, e.code || 0);
	return t;
}

return view.extend({
	state: null,

	load: function() {
		var n = sim(), self = this;
		return modemInfo.identify().then(function(m) {
			self.modem = m;
			if (m.family === 'mtk')
				return Promise.all([ L.resolveDefault(callAtBands(), null), uci.load('qbm-diagd') ]).then(function(res) {
					return { at: res[0] };
				});
			return self.loadQmi(n);
		});
	},

	loadQmi: function(n) {
		/* The NR independent SA/NSA probe writes the current masks back
		 * unchanged (as the Android app does on launch); without write
		 * access, fall back to a plain read. */
		return Promise.all([
			L.resolveDefault(callQmi(n, JSON.stringify({ cmd: 'query_nr_independent_capability' })), null).then(function(r) {
				return (r && r.ok) ? r : L.resolveDefault(callQuery(n, 'query'), null);
			}),
			uci.load('qbm-diagd')
		]).then(function(res) {
			return res[0];
		});
	},

	send: function(req, okText, confirmText) {
		var self = this;
		if (confirmText && !confirm(confirmText)) return Promise.resolve();
		return callQmi(sim(), JSON.stringify(req)).then(function(r) {
			if (r && r.state) self.state = r.state;
			if (r && r.ok) ui.addNotification(null, E('p', okText), 'info');
			else ui.addNotification(null, E('p', errorText(r)), 'danger');
			self.redraw(r);
		}).catch(function(e) {
			ui.addNotification(null, E('p', _('Request failed: %s').format(e.message || e)), 'danger');
		});
	},

	bandGroup: function(title, prefix, hw, current, cmd, note, opts) {
		var self = this;
		opts = opts || {};
		var cur = {}, chosen = {};
		(current || []).forEach(function(b) { cur[b] = chosen[b] = true; });
		var count = E('span', { 'class': 'qbm-meta' });
		var boxes = {};

		function refresh() {
			var on = hw.filter(function(b) { return chosen[b]; }), changed = 0;
			hw.forEach(function(b) {
				var el = boxes[b];
				el.classList.toggle('on', !!chosen[b]);
				el.classList.toggle('changed', !!chosen[b] !== !!cur[b]);
				el.querySelector('input').checked = !!chosen[b];
				if (!!chosen[b] !== !!cur[b]) changed++;
			});
			count.textContent = _('%d of %d enabled').format(on.length, hw.length) + (changed ? ' · ' + _('%d changed').format(changed) : '');
			if (opts.onChange) opts.onChange(on);
		}

		var grid = E('div', { 'class': 'qbm-grid' }, hw.map(function(b) {
			var el = E('label', { 'class': 'qbm-band' }, [
				E('input', { 'type': 'checkbox', 'change': function(ev) { chosen[b] = ev.target.checked; refresh(); } }),
				prefix + b
			]);
			boxes[b] = el;
			return el;
		}));

		var body = [
			E('h3', {}, title),
			note ? E('div', { 'class': 'cbi-section-descr' }, note) : '',
			hw.length ? grid : E('em', {}, _('The modem reports no hardware bands for this.')),
			E('div', { 'class': 'qbm-actions' }, hw.length ? [
				E('button', { 'class': 'cbi-button', 'click': function() { hw.forEach(function(b) { chosen[b] = true; }); refresh(); } }, _('All')),
				E('button', { 'class': 'cbi-button', 'click': function() { hw.forEach(function(b) { chosen[b] = false; }); refresh(); } }, _('None')),
				E('button', { 'class': 'cbi-button', 'click': function() { chosen = {}; Object.keys(cur).forEach(function(b) { chosen[b] = true; }); refresh(); } }, _('Undo')),
				opts.noApply ? null : E('button', { 'class': 'cbi-button cbi-button-apply', 'click': ui.createHandlerFn(this, function() {
					var bands = hw.filter(function(b) { return chosen[b]; });
					if (!bands.length) {
						ui.addNotification(null, E('p', _('Select at least one band.')), 'warning');
						return;
					}
					if (typeof cmd === 'function') return cmd(bands);
					return self.send({ cmd: cmd, bands: bands }, _('%s bands applied: %s').format(title, bands.map(function(b) { return prefix + b; }).join(' ')));
				}) }, _('Apply')),
				count
			].filter(Boolean) : [])
		];
		if (hw.length) refresh();
		return E('div', { 'class': 'cbi-section' }, body);
	},

	renderState: function(r) {
		var self = this, st = this.state;
		var rawFilter = uci.get('qbm-diagd', 'main', 'filter_bands');
		var filterList = null;
		if (rawFilter) {
			var arr = Array.isArray(rawFilter) ? rawFilter : String(rawFilter).trim().split(/\s+/);
			arr = arr.filter(Boolean);
			if (arr.length) filterList = arr;
		}
		var filterSet = {};
		if (filterList) {
			filterList.forEach(function(b) { filterSet[b] = true; });
		}

		var bar = E('div', { 'class': 'qbm-bar' }, [
			filterList ? E('span', { 'class': 'qbm-meta' }, _('Filter active')) : '',
			E('div', { 'class': 'qbm-sims' }, [1, 2].map(function(n) {
				return E('button', {
					'class': 'cbi-button' + (sim() === n ? ' cbi-button-positive' : ''),
					'click': function() { setSim(n); location.reload(); }
				}, _('SIM %d').format(n));
			}))
		]);

		if (!st || !st.valid) {
			return E('div', {}, [ bar, E('div', { 'class': 'alert-message warning' }, [
				E('p', {}, errorText(r)),
				E('p', {}, _('Band locking talks to the modem over its QMI port (%s). Check the port under Settings, and that the modem is connected.').format('/dev/cdc-wdm*'))
			]) ]);
		}

		var hw = st.hardware || {};
		var cap = st.nr_independent_capability || {};
		var independent = cap.independent_lock_supported !== false;

		var nrBands = (hw.nr || []).filter(function(b) { return !filterList || filterSet['n' + b]; });
		var lteBands = (hw.lte || []).filter(function(b) { return !filterList || filterSet['B' + b]; });
		var wcdmaBands = (hw.wcdma || []).filter(function(b) { return !filterList || filterSet['W' + b] || filterSet['B' + b]; });
		var gsmBands = (hw.gsm || []).filter(function(b) { return !filterList || filterSet['GSM' + b] || filterSet[String(b)]; });

		/* Network mode */
		var rat = st.rat || {};
		var ratBoxes = {};
		var ratNames = [ [ 'nr', 'NR' ], [ 'lte', 'LTE' ], [ 'wcdma', 'WCDMA' ], [ 'gsm', 'GSM' ] ];
		var autoBox = E('input', { 'type': 'checkbox', 'checked': rat.auto ? '' : null, 'change': function(ev) {
			Object.keys(ratBoxes).forEach(function(k) { ratBoxes[k].disabled = ev.target.checked; });
		} });
		var ratRow = E('div', { 'class': 'qbm-row' }, [ E('label', {}, [ autoBox, _('Automatic') ]) ].concat(ratNames.map(function(n) {
			var b = E('input', { 'type': 'checkbox', 'checked': rat[n[0]] ? '' : null, 'disabled': rat.auto ? '' : null });
			ratBoxes[n[0]] = b;
			return E('label', {}, [ b, n[1] ]);
		})));

		var modes = [ [ 'both', _('SA + NSA') ], [ 'sa', _('SA only') ], [ 'nsa', _('NSA only') ] ];
		var modeRow = E('div', { 'class': 'qbm-row' }, modes.map(function(m) {
			return E('label', {}, [ E('input', { 'type': 'radio', 'name': 'qbm-nr-mode', 'value': m[0], 'checked': st.nr_mode === m[0] ? '' : null }), m[1] ]);
		}));

		var sections = [
			bar,
			E('div', { 'class': 'cbi-section' }, [
				E('h3', {}, _('Network mode')),
				ratRow,
				E('div', { 'class': 'qbm-actions' }, [
					E('button', { 'class': 'cbi-button cbi-button-apply', 'click': ui.createHandlerFn(this, function() {
						var rats = autoBox.checked ? 'auto' : ratNames.filter(function(n) { return ratBoxes[n[0]].checked; }).map(function(n) { return n[0]; }).join(',');
						if (!rats) { ui.addNotification(null, E('p', _('Select at least one network type.')), 'warning'); return; }
						return self.send({ cmd: 'rat_set', rat: rats }, _('Network mode applied.'));
					}) }, _('Apply'))
				]),
				E('h4', {}, _('5G mode')),
				modeRow,
				E('div', { 'class': 'qbm-actions' }, [
					E('button', { 'class': 'cbi-button cbi-button-apply', 'click': ui.createHandlerFn(this, function() {
						var sel = modeRow.querySelector('input:checked');
						if (!sel) return;
						return self.send({ cmd: 'mode_set', mode: sel.value }, _('5G mode applied.'));
					}) }, _('Apply')),
					E('span', { 'class': 'qbm-meta' }, st.nr_mode ? _('current: %s').format(st.nr_mode) : _('current: unknown'))
				])
			])
		];

		if (independent) {
			if (!filterList || nrBands.length) {
				sections.push(this.bandGroup(_('NR SA'), 'n', nrBands, st.nr_sa, 'nr_sa_set'));
				sections.push(this.bandGroup(_('NR NSA'), 'n', nrBands, st.nr_nsa, 'nr_nsa_set'));
			}
		} else {
			if (!filterList || nrBands.length) {
				sections.push(this.bandGroup(_('NR'), 'n', nrBands, st.nr_sa, 'nr_set',
					_('This modem does not take separate SA and NSA band locks: one set applies to both.')));
			}
		}
		if (!filterList || lteBands.length) {
			sections.push(this.bandGroup(_('LTE'), 'B', lteBands, st.lte, 'lte_set'));
		}
		if (wcdmaBands.length) sections.push(this.bandGroup(_('WCDMA'), '', wcdmaBands, st.wcdma, 'wcdma_set'));
		if (gsmBands.length) sections.push(this.bandGroup(_('GSM'), '', gsmBands, st.gsm, 'gsm_set'));

		/* Usage preference, reset */
		var usage = (st.usage_pref || {}).mode;
		sections.push(E('div', { 'class': 'cbi-section' }, [
			E('h3', {}, _('Usage preference')),
			E('div', { 'class': 'qbm-row' }, [
				E('button', { 'class': 'cbi-button' + (usage === 'data' ? ' cbi-button-positive' : ''), 'click': ui.createHandlerFn(this, function() {
					return self.send({ cmd: 'usage_pref_set', mode: 'data' }, _('Usage preference: data.'));
				}) }, _('Data centric')),
				E('button', { 'class': 'cbi-button' + (usage === 'voice' ? ' cbi-button-positive' : ''), 'click': ui.createHandlerFn(this, function() {
					return self.send({ cmd: 'usage_pref_set', mode: 'voice' }, _('Usage preference: voice.'));
				}) }, _('Voice centric'))
			]),
			E('h3', {}, _('Reset')),
			E('div', { 'class': 'qbm-actions' }, [
				E('button', { 'class': 'cbi-button cbi-button-reset', 'click': ui.createHandlerFn(this, function() {
					return self.send({ cmd: 'reset' }, _('All bands reset to the modem\'s hardware defaults.'),
						_('Enable every band the modem supports, for all network types?'));
				}) }, _('Reset all bands to hardware defaults')),
				E('button', { 'class': 'cbi-button', 'click': ui.createHandlerFn(this, function() {
					return self.send({ cmd: 'reattach' }, _('Re-attach sent.'), _('Detach from the network and attach again? The connection drops for a few seconds.'));
				}) }, _('Re-attach to network'))
			])
		]));

		return E('div', {}, sections);
	},

	/* After a band change the data session drops. Bring the modem's address
	 * into the interface, as Network > Reconnect does (without airplane mode). */
	refreshIp: function() {
		return callRefreshIp().then(function(r) {
			if (r && r.ok) ui.addNotification(null, E('p', _('Updating the IP address after the band change. This takes a few seconds.')), 'info');
			else ui.addNotification(null, E('p', (r && r.error && (r.error.message || r.error)) || _('Address update failed.')), 'danger');
		}).catch(function(e) {
			ui.addNotification(null, E('p', _('Request failed: %s').format(e.message || e)), 'danger');
		});
	},

	/* Same as Network > Reconnect: airplane mode, then the address is refreshed. */
	reconnect: function() {
		return callReconnect().then(function(r) {
			if (r && r.ok) ui.addNotification(null, E('p', _('Reconnecting. The interface comes back up with the new IP address in about a minute.')), 'info');
			else ui.addNotification(null, E('p', (r && r.error && (r.error.message || r.error)) || _('Reconnect failed.')), 'danger');
		}).catch(function(e) {
			ui.addNotification(null, E('p', _('Request failed: %s').format(e.message || e)), 'danger');
		});
	},

	/* AT+GTACT with this RAT setting and these band codes; the reply carries
	 * the modem's setting afterwards (AT+GTACT?), which the page re-renders. */
	sendAt: function(rat, p1, p2, codes, okText, confirmText) {
		var self = this;
		if (confirmText && !confirm(confirmText)) return Promise.resolve();
		return callAtBandsSet(String(rat), p1 == null ? '' : String(p1), p2 == null ? '' : String(p2), codes.join(',')).then(function(r) {
			var st = parseGtact({ ok: r && r.ok, replies: [ self.atState.test, r && r.replies && r.replies[1] ] });
			if (st.valid) { st.hw = self.atState.hw; st.rats = self.atState.rats; self.atState = Object.assign(st, { test: self.atState.test }); }
			if (atOk(r)) {
				ui.addNotification(null, E('p', okText), 'info');
				self.refreshIp();
			}
			else ui.addNotification(null, E('p', _('The modem rejected AT+GTACT: %s').format(((r && r.replies && r.replies[0]) || (r && r.error && r.error.message) || _('no reply')).trim())), 'danger');
			dom.content(self.root, self.renderAt());
		}).catch(function(e) {
			ui.addNotification(null, E('p', _('Request failed: %s').format(e.message || e)), 'danger');
		});
	},

	renderAt: function() {
		var self = this, st = this.atState, m = this.modem || {};
		var rawFilter = uci.get('qbm-diagd', 'main', 'filter_bands');
		var filterList = null, filterSet = {};
		if (rawFilter) {
			var arr = (Array.isArray(rawFilter) ? rawFilter : String(rawFilter).trim().split(/\s+/)).filter(Boolean);
			if (arr.length) filterList = arr;
		}
		(filterList || []).forEach(function(b) { filterSet[b] = true; });

		if (!st || !st.valid) {
			return E('div', {}, [ E('div', { 'class': 'alert-message warning' }, [
				E('p', {}, _('The modem did not answer AT+GTACT. Check the AT port under Settings, and that the modem is connected.'))
			]) ]);
		}

		/* The RAT code is always 20 now; the "mode" lives in the band masks, so
		 * read it back from them: a RAT is selected only when it has a band
		 * other than its fallback (B5/B30/n30). */
		function isFallback(rat, b) { return codeOf(rat, b) === GTACT_FALLBACK[rat]; }
		var curBands = {};
		[ 'nr', 'lte', 'wcdma' ].forEach(function(r) {
			curBands[r] = (st.cur[r] || []).filter(function(b) { return !isFallback(r, b); });
		});
		var isAuto = st.rat === 10;
		var ratNow = isAuto ? [] : [ 'nr', 'lte', 'wcdma' ].filter(function(r) { return curBands[r].length > 0; });
		/* The modem's mask is the truth for each RAT that is on; an RAT that is
		 * off (fallback only) shows the bands it had last. */
		if (!isAuto) remember(curBands);
		var memory = remembered();
		var shown = {};
		[ 'nr', 'lte', 'wcdma' ].forEach(function(r) {
			shown[r] = curBands[r].length ? curBands[r] : memory[r].filter(function(b) { return st.hw[r].indexOf(b) >= 0; });
		});
		var ratBoxes = {};
		var ratNames = [ [ 'nr', 'NR' ], [ 'lte', 'LTE' ], [ 'wcdma', 'WCDMA' ] ].filter(function(n) {
			return st.hw[n[0]].length || ratNow.indexOf(n[0]) >= 0;
		});
		var autoBox = E('input', { 'type': 'checkbox', 'checked': isAuto ? '' : null, 'change': function(ev) {
			Object.keys(ratBoxes).forEach(function(k) { ratBoxes[k].disabled = ev.target.checked; });
			updatePreview();
		} });
		var ratRow = E('div', { 'class': 'qbm-row' }, [ E('label', {}, [ autoBox, _('Auto') ]) ].concat(ratNames.map(function(n) {
			var b = E('input', { 'type': 'checkbox', 'checked': ratNow.indexOf(n[0]) >= 0 ? '' : null, 'disabled': isAuto ? '' : null,
				'change': function() { updatePreview(); } });
			ratBoxes[n[0]] = b;
			return E('label', {}, [ b, n[1] ]);
		})));

		/* The modem's setting as AT+GTACT? reports it, per RAT. */
		function ratLine(r, name, prefix) {
			var all = st.cur[r] || [];
			var text = isAuto ? _('all bands (auto)')
				: curBands[r].length ? curBands[r].map(function(b) { return prefix + b; }).join(' ')
				: all.length ? _('off (fallback %s only)').format(all.map(function(b) { return prefix + b; }).join(' '))
				: _('off');
			return E('tr', { 'class': 'tr' }, [
				E('td', { 'class': 'td', 'style': 'width:7em; font-weight:bold' }, name),
				E('td', { 'class': 'td' }, text)
			]);
		}
		var current = E('div', { 'class': 'cbi-section' }, [
			E('h3', {}, _('Current setting')),
			E('table', { 'class': 'table' }, [
				E('tr', { 'class': 'tr' }, [
					E('td', { 'class': 'td', 'style': 'width:7em; font-weight:bold' }, _('Mode')),
					E('td', { 'class': 'td' }, isAuto ? _('Auto')
						: _('Preferred %s').format([ st.p1, st.p2 ].filter(function(x) { return x != null; })
							.map(function(p) { return { 2: 'WCDMA', 3: 'LTE', 6: 'NR' }[p] || p; }).join(', ') || '–'))
				]),
				ratLine('nr', 'NR', 'n'),
				ratLine('lte', 'LTE', 'B'),
				st.hw.wcdma.length || (st.cur.wcdma || []).length ? ratLine('wcdma', 'WCDMA', 'B') : ''
			])
		]);

		var sections = [
			current,
			E('div', { 'class': 'cbi-section' }, [
				E('h3', {}, _('Network mode')),
				ratRow
			])
		];

		/* Band selections per RAT, shared by the single Apply below. */
		var picked = { nr: [], lte: [], wcdma: [] };
		var preview = E('code', { 'class': 'qbm-meta' }, '');

		function currentMask() {
			if (autoBox.checked) {
				var all = [];
				[ 'wcdma', 'lte', 'nr' ].forEach(function(r) { st.hw[r].forEach(function(b) { all.push(codeOf(r, b)); }); });
				return { rat: 10, p1: null, p2: null, codes: all, error: null };
			}
			var rats = [ 'nr', 'lte', 'wcdma' ].filter(function(r) { return ratBoxes[r] && ratBoxes[r].checked; });
			if (!rats.length) return { error: _('Select at least one network type.') };
			if (st.rats.length && st.rats.indexOf(20) < 0)
				return { error: _('This modem does not offer the NR/LTE/WCDMA combination.') };
			var codes = [], missing = null;
			[ 'wcdma', 'lte', 'nr' ].forEach(function(r) {
				if (rats.indexOf(r) >= 0) {
					if (!picked[r].length) { missing = missing || r; return; }
					picked[r].forEach(function(b) { codes.push(codeOf(r, b)); });
				} else {
					codes.push(GTACT_FALLBACK[r]);
				}
			});
			if (missing) return { error: _('Select at least one %s band.').format(missing.toUpperCase()) };
			return { rat: 20, p1: GTACT_P1, p2: GTACT_P2, codes: codes, error: null };
		}

		function updatePreview() {
			var m = currentMask();
			preview.textContent = m.error || ('AT+GTACT=' + [ m.rat, m.p1 == null ? '' : m.p1, m.p2 == null ? '' : m.p2 ].join(',')
				+ (m.codes.length ? ',' + m.codes.join(',') : ''));
		}

		function group(title, rat, prefix) {
			var hw = st.hw[rat].filter(function(b) {
				var key = rat === 'nr' ? 'n' + b : rat === 'lte' ? 'B' + b : 'W' + b;
				return !filterList || filterSet[key] || (rat === 'wcdma' && filterSet['B' + b]);
			});
			if (filterList && !hw.length) return;
			var note = curBands[rat].length || isAuto ? null
				: shown[rat].length ? _('%s is off. These are its last bands: they are sent again when %s is checked.').format(title, title)
				: _('The modem lists no %s band in its current setting.').format(title);
			sections.push(self.bandGroup(title, prefix, hw, shown[rat], null, note, {
					noApply: true,
					onChange: function(bands) { picked[rat] = bands; updatePreview(); }
				}));
		}
		group(_('NR'), 'nr', 'n');
		group(_('LTE'), 'lte', 'B');
		if (st.hw.wcdma.length) group(_('WCDMA'), 'wcdma', 'B');

		sections.push(E('div', { 'class': 'cbi-section' }, [
			E('h3', {}, _('Apply')),
			E('div', { 'class': 'qbm-actions' }, [
				E('button', { 'class': 'cbi-button cbi-button-apply', 'click': ui.createHandlerFn(this, function() {
					var m = currentMask();
					if (m.error) { ui.addNotification(null, E('p', m.error), 'warning'); return; }
					if (m.rat !== 10) remember(picked);
					return self.sendAt(m.rat, m.p1, m.p2, m.codes,
						m.rat === 10 ? _('Auto mode applied.') : _('Band mask applied.'));
				}) }, _('Apply')),
				preview
			]),
			E('div', { 'class': 'cbi-section-descr' }, _('One AT+GTACT for all three network types. A type left unchecked is sent with its fallback band (WCDMA B5 / LTE B30 / NR n30): the FM350 rejects a mask that leaves a type out.'))
		]));
		updatePreview();

		sections.push(E('div', { 'class': 'cbi-section' }, [
			E('h3', {}, _('Reset')),
			E('div', { 'class': 'qbm-actions' }, [
				E('button', { 'class': 'cbi-button cbi-button-reset', 'click': ui.createHandlerFn(this, function() {
					var all = [];
					[ 'wcdma', 'lte', 'nr' ].forEach(function(r) { st.hw[r].forEach(function(b) { all.push(codeOf(r, b)); }); });
					var rat = (st.rats.length && st.rats.indexOf(20) < 0) ? st.rat : 20;
					return self.sendAt(rat, GTACT_P1, GTACT_P2, all, _('Every hardware band enabled.'),
						_('Enable every band the modem supports, for all network types?'));
				}) }, _('Enable all hardware bands')),
				E('button', { 'class': 'cbi-button', 'click': ui.createHandlerFn(this, function() {
					if (!confirm(_('Reconnect the modem? Airplane mode is toggled and the modem re-attaches. The connection is lost for about a minute.'))) return;
					return self.reconnect();
				}) }, _('Re-attach to network'))
			])
		]));
		return E('div', {}, sections);
	},

	redraw: function(r) {
		dom.content(this.root, this.renderState(r));
	},

	render: function(r) {
		this.root = E('div', {});
		if (r && 'at' in r) {
			this.atState = parseGtact(r.at);
			this.atState.test = r.at && r.at.replies ? r.at.replies[0] : null;
			dom.content(this.root, this.renderAt());
			return E([], [ E('style', {}, CSS), E('h2', {}, _('%s Band Locking').format((this.modem && this.modem.model) || 'FM350')), this.root ]);
		}
		this.state = r && r.state ? r.state : null;
		this.redraw(r);
		return E([], [
			E('style', {}, CSS),
			E('h2', {}, _('Band Locking')),
			this.root
		]);
	},

	handleSaveApply: null,
	handleSave: null,
	handleReset: null
});
