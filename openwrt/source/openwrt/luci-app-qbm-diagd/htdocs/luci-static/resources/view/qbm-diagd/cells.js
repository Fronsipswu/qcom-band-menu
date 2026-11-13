'use strict';
'require view';
'require rpc';
'require ui';
'require dom';
'require poll';
'require qbm-diagd.modem as modemInfo';

/*
 * Cell locking through qbm-qmi, with the cells the modem sees right now
 * (from qbm-diagd's DIAG statistics) as one-click lock targets.
 */

var callQuery = rpc.declare({ object: 'luci.qbm-diagd', method: 'qmi_query', params: [ 'sim', 'cmd' ] });
var callQmi = rpc.declare({ object: 'luci.qbm-diagd', method: 'qmi', params: [ 'sim', 'request' ] });
var callStats = rpc.declare({ object: 'luci.qbm-diagd', method: 'stats', params: [ 'sim' ] });

var SCS = [ 15, 30, 60, 120, 240 ];

var CSS = '\
.qbm-bar { display:flex; flex-wrap:wrap; align-items:center; gap:.5em 1em; margin-bottom:1em }\
.qbm-bar .qbm-meta { opacity:.75; font-size:90% }\
.qbm-sims { margin-left:auto; display:flex; gap:.25em }\
.qbm-lock { border:1px solid rgba(128,128,128,.35); border-radius:6px; padding:.6em .8em; margin:.5em 0 1em }\
.qbm-lock .k { font-size:85%; opacity:.75 }\
.qbm-lock .v { font-size:120%; font-weight:600; font-variant-numeric:tabular-nums }\
.qbm-lock.on { border-color:#c9682b; background:rgba(201,104,43,.08) }\
.qbm-form { display:flex; flex-wrap:wrap; gap:.5em .75em; align-items:flex-end; margin:.5em 0 1em }\
.qbm-form label { display:flex; flex-direction:column; font-size:85%; gap:.2em }\
.qbm-form input { width:8em } .qbm-form input.wide { width:14em } .qbm-form select { width:7em }\
.qbm-num { font-variant-numeric:tabular-nums; white-space:nowrap }\
.qbm-scroll { overflow-x:auto }\
.qbm-empty { opacity:.7; font-style:italic; padding:.5em 0 }\
.qbm-meta { opacity:.75; font-size:90% }\
';

function sim() {
	try { return +(localStorage.getItem('qbm-sim') || 1) === 2 ? 2 : 1; } catch (e) { return 1; }
}
function setSim(n) {
	try { localStorage.setItem('qbm-sim', String(n)); } catch (e) {}
}
function isNum(v) { return typeof v === 'number' && isFinite(v); }
function fmt(v, d, u) { return isNum(v) ? v.toFixed(d) + (u ? ' ' + u : '') : '–'; }

function errorText(r) {
	if (!r) return _('No reply from the router.');
	var e = r.error || {};
	if (typeof e === 'string') return e;
	var t = e.message || _('Request failed.');
	if (e.stage === 'modem_rejected')
		t += ' ' + _('(modem result %d, error %d)').format(e.result || 0, e.code || 0);
	return t;
}

function table(titles, rows, empty) {
	if (!rows.length) return E('div', { 'class': 'qbm-empty' }, empty);
	return E('div', { 'class': 'qbm-scroll' }, E('table', { 'class': 'table' }, [
		E('tr', { 'class': 'tr table-titles' }, titles.map(function(t) { return E('th', { 'class': 'th' }, t); }))
	].concat(rows.map(function(r) {
		return E('tr', { 'class': 'tr' }, r.map(function(c, i) { return E('td', { 'class': 'td', 'data-title': titles[i] }, c); }));
	}))));
}

function intList(s) {
	return String(s || '').split(/[\s,;]+/).filter(function(x) { return x !== ''; }).map(Number);
}

return view.extend({
	state: null,
	stats: null,

	load: function() {
		var n = sim();
		return modemInfo.identify().then(function(m) {
			/* no cell locking for the FM350 yet: no QMI, and no AT command for it */
			if (m.family === 'mtk') return [ null, null, m ];
			return Promise.all([
				L.resolveDefault(callQuery(n, 'query'), null),
				L.resolveDefault(callStats(n), null),
				m
			]);
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

	lockLte: function(earfcn, pcis) {
		var req = pcis.length === 1
			? { cmd: 'lte_cell_lock_set', earfcn: earfcn, pci: pcis[0] }
			: { cmd: 'lte_cell_lock_multi_pci_set', earfcn: earfcn, pci_list: pcis };
		return this.send(req, _('LTE locked to EARFCN %d, PCI %s.').format(earfcn, pcis.join(', ')),
			_('Lock LTE to EARFCN %d, PCI %s? The modem will only use this cell on LTE until the lock is cleared.').format(earfcn, pcis.join(', ')));
	},

	lockNr: function(arfcn, pci, scs, band) {
		return this.send({ cmd: 'nr_cell_lock_pci_set', arfcn: arfcn, pci: pci, scs_khz: scs, band: band },
			_('NR locked to ARFCN %d, PCI %d (n%d, %d kHz).').format(arfcn, pci, band, scs),
			_('Lock 5G NR to ARFCN %d, PCI %d on n%d (%d kHz SCS)? The modem will only use this NR cell until the lock is cleared.').format(arfcn, pci, band, scs));
	},

	renderLocks: function(st) {
		var self = this;
		var lte = st.lte_cell_lock || {}, nr = st.nr_cell_lock || {};
		var locks = lte.locks || [];

		var lteNow = locks.length
			? locks.map(function(l) { return _('EARFCN %d · PCI %d').format(l.earfcn, l.pci); }).join('; ')
			: _('Not locked');

		var nrNow = _('Not locked');
		if (nr.type === 'pci' && nr.pci_lock)
			nrNow = _('ARFCN %d · PCI %d · %s · %d kHz').format(nr.pci_lock.arfcn, nr.pci_lock.pci,
				(nr.pci_lock.bands || []).map(function(b) { return 'n' + b; }).join(' '), nr.pci_lock.scs_khz);
		else if (nr.type === 'arfcn' && nr.arfcn_lock)
			nrNow = _('ARFCN only: %s').format(JSON.stringify(nr.arfcn_lock));
		else if (nr.type && nr.type !== 'none')
			nrNow = _('%s lock active').format(nr.type);
		var nrOn = nr.type && nr.type !== 'none';

		/* LTE form */
		var lEarfcn = E('input', { 'type': 'number', 'min': 0, 'placeholder': '1300' });
		var lPci = E('input', { 'class': 'wide', 'placeholder': _('214, or 214,215') });
		/* NR form */
		var nKind = E('select', {}, [ E('option', { 'value': 'pci' }, _('Cell (PCI)')), E('option', { 'value': 'arfcn' }, _('Frequency only')) ]);
		var nArfcn = E('input', { 'type': 'number', 'min': 0, 'placeholder': '636666' });
		var nPci = E('input', { 'type': 'number', 'min': 0, 'max': 1007, 'placeholder': '412' });
		var nBand = E('input', { 'type': 'number', 'min': 1, 'max': 512, 'placeholder': '78' });
		var nScs = E('select', {}, SCS.map(function(k) { return E('option', { 'value': k, 'selected': k === 30 ? '' : null }, k + ' kHz'); }));
		var pciWrap = E('label', {}, [ _('PCI'), nPci ]), bandWrap = E('label', {}, [ _('Band (n)'), nBand ]);
		nKind.addEventListener('change', function() {
			pciWrap.style.display = bandWrap.style.display = nKind.value === 'pci' ? '' : 'none';
		});
		/* PLMN form */
		var pl = st.plmn_lock || {};
		var mcc = E('input', { 'type': 'number', 'min': 0, 'max': 999, 'placeholder': 'MCC', 'value': pl.mcc || '' });
		var mnc = E('input', { 'type': 'number', 'min': 0, 'max': 999, 'placeholder': 'MNC', 'value': pl.mnc || '' });
		this.fill = { lEarfcn: lEarfcn, lPci: lPci, nArfcn: nArfcn, nPci: nPci, nBand: nBand, nScs: nScs, nKind: nKind };

		return [
			E('div', { 'class': 'cbi-section' }, [
				E('h3', {}, _('LTE cell lock')),
				E('div', { 'class': 'qbm-lock' + (locks.length ? ' on' : '') }, [ E('div', { 'class': 'k' }, _('Current')), E('div', { 'class': 'v' }, lteNow) ]),
				E('div', { 'class': 'qbm-form' }, [
					E('label', {}, [ _('EARFCN'), lEarfcn ]),
					E('label', {}, [ _('PCI(s)'), lPci ]),
					E('button', { 'class': 'cbi-button cbi-button-apply', 'click': ui.createHandlerFn(this, function() {
						var e = parseInt(lEarfcn.value, 10), p = intList(lPci.value);
						if (!(e >= 0) || !p.length || p.some(function(x) { return !(x >= 0 && x <= 503); })) {
							ui.addNotification(null, E('p', _('Enter an EARFCN and one or more PCIs (0–503).')), 'warning'); return;
						}
						return self.lockLte(e, p);
					}) }, _('Lock')),
					E('button', { 'class': 'cbi-button cbi-button-reset', 'click': ui.createHandlerFn(this, function() {
						return self.send({ cmd: 'lte_cell_lock_clear' }, _('LTE cell lock cleared.'));
					}) }, _('Clear lock'))
				])
			]),
			E('div', { 'class': 'cbi-section' }, [
				E('h3', {}, _('5G NR cell lock')),
				E('div', { 'class': 'qbm-lock' + (nrOn ? ' on' : '') }, [ E('div', { 'class': 'k' }, _('Current')), E('div', { 'class': 'v' }, nrNow) ]),
				E('div', { 'class': 'qbm-form' }, [
					E('label', {}, [ _('Lock to'), nKind ]),
					E('label', {}, [ _('ARFCN'), nArfcn ]),
					pciWrap, bandWrap,
					E('label', {}, [ _('SCS'), nScs ]),
					E('button', { 'class': 'cbi-button cbi-button-apply', 'click': ui.createHandlerFn(this, function() {
						var a = parseInt(nArfcn.value, 10), scs = +nScs.value;
						if (!(a >= 0)) { ui.addNotification(null, E('p', _('Enter an NR-ARFCN.')), 'warning'); return; }
						if (nKind.value === 'arfcn')
							return self.send({ cmd: 'nr_cell_lock_arfcn_set', arfcn: a, scs_khz: scs }, _('NR locked to ARFCN %d.').format(a),
								_('Lock 5G NR to ARFCN %d (%d kHz SCS)?').format(a, scs));
						var p = parseInt(nPci.value, 10), b = parseInt(nBand.value, 10);
						if (!(p >= 0 && p <= 1007) || !(b >= 1 && b <= 512)) {
							ui.addNotification(null, E('p', _('Enter a PCI (0–1007) and a band.')), 'warning'); return;
						}
						return self.lockNr(a, p, scs, b);
					}) }, _('Lock')),
					E('button', { 'class': 'cbi-button cbi-button-reset', 'click': ui.createHandlerFn(this, function() {
						return self.send({ cmd: 'nr_cell_lock_clear' }, _('NR cell lock cleared.'));
					}) }, _('Clear lock'))
				])
			]),
			E('div', { 'class': 'cbi-section' }, [
				E('h3', {}, _('Network operator (PLMN) lock')),
				E('div', { 'class': 'cbi-section-descr' }, pl.mode === 'manual'
					? _('Locked to %s-%s.').format(pl.mcc, pl.mnc)
					: _('Automatic: the modem picks the operator.')),
				E('div', { 'class': 'qbm-form' }, [
					E('label', {}, [ _('MCC'), mcc ]),
					E('label', {}, [ _('MNC'), mnc ]),
					E('button', { 'class': 'cbi-button cbi-button-apply', 'click': ui.createHandlerFn(this, function() {
						if (mcc.value === '' || mnc.value === '') return;
						return self.send({ cmd: 'plmn_lock_set', mcc: +mcc.value, mnc: +mnc.value }, _('Operator lock applied.'));
					}) }, _('Lock')),
					E('button', { 'class': 'cbi-button cbi-button-reset', 'click': ui.createHandlerFn(this, function() {
						return self.send({ cmd: 'plmn_lock_clear' }, _('Operator lock cleared.'));
					}) }, _('Automatic'))
				])
			]),
			E('div', { 'class': 'cbi-section' }, [
				E('div', { 'class': 'qbm-form' }, [
					E('button', { 'class': 'cbi-button', 'click': ui.createHandlerFn(this, function() {
						return self.send({ cmd: 'reattach' }, _('Re-attach sent.'),
							_('Detach from the network and attach again, so new locks take effect now? The connection drops for a few seconds.'));
					}) }, _('Re-attach to network')),
					E('span', { 'class': 'qbm-meta' }, _('A new lock takes effect the next time the modem selects a cell; re-attach to apply it now.'))
				])
			])
		];
	},

	renderLive: function() {
		var self = this, st = this.stats;
		var lteRows = [], nrRows = [], nbRows = [];
		if (st && st.ok) {
			(st.carriers || []).forEach(function(c) {
				if (!isNum(c.earfcn) || !isNum(c.pci)) return;
				lteRows.push([
					E('strong', {}, c.slot === 0 ? _('PCell') : _('SCell %d').format(c.slot)),
					isNum(c.band) ? 'B' + c.band : '–',
					E('span', { 'class': 'qbm-num' }, String(c.earfcn)), E('span', { 'class': 'qbm-num' }, String(c.pci)),
					E('span', { 'class': 'qbm-num' }, fmt(c.rsrp, 1, 'dBm')), E('span', { 'class': 'qbm-num' }, fmt(c.sinr, 1, 'dB')),
					E('button', { 'class': 'cbi-button cbi-button-action', 'click': ui.createHandlerFn(self, function() {
						return self.lockLte(c.earfcn, [ c.pci ]);
					}) }, _('Lock'))
				]);
			});
			var nrByArfcn = {};
			(st.nr || []).forEach(function(c) {
				if (!isNum(c.arfcn) || !isNum(c.pci)) return;
				if (isNum(c.band) && isNum(c.scs)) nrByArfcn[c.arfcn] = c;
				var lockable = isNum(c.band) && isNum(c.scs);
				nrRows.push([
					E('strong', {}, c.pcell === true ? _('PCell') : _('CC %d').format(c.cc)),
					isNum(c.band) ? 'n' + c.band : '–',
					E('span', { 'class': 'qbm-num' }, String(c.arfcn)), E('span', { 'class': 'qbm-num' }, String(c.pci)),
					isNum(c.scs) ? c.scs + ' kHz' : '–',
					E('span', { 'class': 'qbm-num' }, fmt(c.rsrp, 1, 'dBm')), E('span', { 'class': 'qbm-num' }, fmt(c.sinr, 1, 'dB')),
					lockable
						? E('button', { 'class': 'cbi-button cbi-button-action', 'click': ui.createHandlerFn(self, function() { return self.lockNr(c.arfcn, c.pci, c.scs, c.band); }) }, _('Lock'))
						: E('button', { 'class': 'cbi-button', 'click': function() { self.prefillNr(c.arfcn, c.pci, c.scs, c.band); } }, _('Fill in'))
				]);
			});
			(st.nr_nb || []).forEach(function(n) {
				var serving = nrByArfcn[n[0]];
				nbRows.push([
					E('span', { 'class': 'qbm-num' }, String(n[0])), E('span', { 'class': 'qbm-num' }, String(n[1])),
					E('span', { 'class': 'qbm-num' }, String(n[2])),
					E('span', { 'class': 'qbm-num' }, fmt(n[3], 1, 'dBm')), E('span', { 'class': 'qbm-num' }, fmt(n[4], 1, 'dB')),
					serving
						? E('button', { 'class': 'cbi-button cbi-button-action', 'click': ui.createHandlerFn(self, function() { return self.lockNr(n[0], n[1], serving.scs, serving.band); }) }, _('Lock'))
						: E('button', { 'class': 'cbi-button', 'click': function() { self.prefillNr(n[0], n[1], null, null); } }, _('Fill in'))
				]);
			});
		}

		var note = st && st.ok
			? _('Cells the modem is using or measuring now, from the DIAG statistics (refreshed every 5 s).')
			: _('Live cells need the statistics service (DIAG). Enable it under Settings; manual locking above works without it.');

		return E('div', { 'class': 'cbi-section' }, [
			E('h3', {}, _('Cells seen now')),
			E('div', { 'class': 'cbi-section-descr' }, note),
			E('h4', {}, _('LTE')),
			table([ _('Carrier'), _('Band'), _('EARFCN'), _('PCI'), _('RSRP'), _('SINR'), '' ], lteRows, _('No LTE cells.')),
			E('h4', {}, _('5G NR')),
			table([ _('Carrier'), _('Band'), _('ARFCN'), _('PCI'), _('SCS'), _('SS-RSRP'), _('SS-SINR'), '' ], nrRows, _('No NR cells.')),
			E('h4', {}, _('5G NR neighbour beams')),
			table([ _('ARFCN'), _('PCI'), _('SSB'), _('RSRP'), _('RSRQ'), '' ], nbRows, _('No NR neighbours reported.'))
		]);
	},

	prefillNr: function(arfcn, pci, scs, band) {
		var f = this.fill;
		if (!f) return;
		f.nKind.value = 'pci'; f.nKind.dispatchEvent(new Event('change'));
		f.nArfcn.value = arfcn; f.nPci.value = pci;
		if (band) f.nBand.value = band;
		if (scs) f.nScs.value = scs;
		f.nArfcn.scrollIntoView({ block: 'center' });
		(band ? f.nArfcn : f.nBand).focus();
	},

	renderAll: function(r) {
		var st = this.state;
		var bar = E('div', { 'class': 'qbm-bar' }, [
			E('div', { 'class': 'qbm-sims' }, [1, 2].map(function(n) {
				return E('button', {
					'class': 'cbi-button' + (sim() === n ? ' cbi-button-positive' : ''),
					'click': function() { setSim(n); location.reload(); }
				}, _('SIM %d').format(n));
			}))
		]);
		var body = [ bar ];
		if (!st || !st.valid)
			body.push(E('div', { 'class': 'alert-message warning' }, [
				E('p', {}, errorText(r)),
				E('p', {}, _('Cell locking talks to the modem over its QMI port (%s). Check the port under Settings, and that the modem is connected.').format('/dev/cdc-wdm*'))
			]));
		else
			body = body.concat(this.renderLocks(st));
		this.liveRoot = E('div', {}, this.renderLive());
		body.push(this.liveRoot);
		return E('div', {}, body);
	},

	redraw: function(r) {
		dom.content(this.root, this.renderAll(r));
	},

	render: function(data) {
		var self = this, r = data[0], m = data[2];
		if (m && m.family === 'mtk') {
			return E([], [
				E('style', {}, CSS),
				E('h2', {}, _('Cell Locking')),
				E('div', { 'style': 'opacity:.5; pointer-events:none' }, [
					E('div', { 'class': 'qbm-lock' }, [ E('div', { 'class': 'k' }, _('Cell lock')), E('div', { 'class': 'v' }, _('Not available')) ])
				]),
				E('div', { 'class': 'alert-message notice' }, _('Cell locking is not available for the %s yet. Band and network mode locking work on the Band Locking page.').format(m.model || 'FM350'))
			]);
		}
		this.state = r && r.state ? r.state : null;
		this.stats = data[1];
		this.root = E('div', {});
		this.redraw(r);
		poll.add(function() {
			return L.resolveDefault(callStats(sim()), null).then(function(s) {
				self.stats = s;
				if (self.liveRoot) dom.content(self.liveRoot, self.renderLive());
			});
		}, 5);
		return E([], [
			E('style', {}, CSS),
			E('h2', {}, _('Cell Locking')),
			this.root
		]);
	},

	handleSaveApply: null,
	handleSave: null,
	handleReset: null
});
