'use strict';
'require view';
'require poll';
'require rpc';
'require ui';
'require dom';
'require qbm-diagd.radio as radio';
'require qbm-diagd.serving as serving';
'require qbm-diagd.modem as modemInfo';

/*
 * Network: laid out as the QBM app's Network page (ui/NetworkScreen.kt). On
 * top the serving cell card (SummaryCard: mode pill, RRC chip, operator row
 * and the cell's fields; no signal bars), from qbm-qmi's read-only NAS reads
 * through qbm-diagd/serving.js. Below it the live carrier statistics from
 * qbm-diagd: carrier aggregation tables with
 * one column per carrier (P, S1, S2...) and one row per parameter, signal and
 * BLER on level-coloured bars, RBs and MCS on blue bars, then the throughput
 * chart and the Uplink card.
 *
 * Every "stats" call returns the window since the previous call, so this
 * page is meant to be the only reader while it is open.
 */

var callStats = rpc.declare({ object: 'luci.qbm-diagd', method: 'stats', params: [ 'sim' ] });
var callStatus = rpc.declare({ object: 'luci.qbm-diagd', method: 'status' });
var callServing = rpc.declare({ object: 'luci.qbm-diagd', method: 'serving', params: [ 'sim' ] });
var callOperator = rpc.declare({ object: 'luci.qbm-diagd', method: 'operator', params: [ 'sim', 'mcc', 'mnc' ] });
var callQuery = rpc.declare({ object: 'luci.qbm-diagd', method: 'qmi_query', params: [ 'sim', 'cmd' ] });
var callReconnect = rpc.declare({ object: 'luci.qbm-diagd', method: 'reconnect' });
/* Fibocom FM350 (no QMI): the serving cell from AT+COPS? and AT+GTCCINFO?. */
var callAtServing = rpc.declare({ object: 'luci.qbm-diagd', method: 'at_serving' });

/* 'mtk' for an FM350, else 'qcom'; set before the first refresh. */
var modemFamily = 'qcom';

/* The NR bands the modem supports (QMI), read once per SIM: they settle an
 * NR-ARFCN two bands share (n77/n78) when the modem has only one of them, as
 * in the app. Empty until read, or without the QMI port. */
var nrHwBands = [];

/* The serving PLMN (QMI), for the n77/n78 stand-in; null until read. */
var servingPlmn = null;

/* A carrier's band from its NR-ARFCN when the modem hasn't named it: one the
 * modem's supported bands leave; for n77/78 the PLMN's usual band
 * (radio.nrBandGuess) until the modem names it; otherwise none ("–") while
 * two bands share the ARFCN (n38/41, n5/26), as the app does with DIAG on. */
function nrArfcnBand(arfcn) {
	var l = isNum(arfcn) ? radio.nrBandLabel(arfcn, nrHwBands) : null;
	if (!l || l.indexOf('/') < 0) return l;
	var g = radio.nrBandGuess(arfcn, servingPlmn);
	return g ? 'n' + g : null;
}

var HISTORY = 90;               /* throughput graph points (3 min at 2 s) */

var CSS = '\
.qbm-tile, .qbm-card, .qbm-graph { border:1px solid rgba(128,128,128,.3); border-radius:8px }\
.qbm-card { padding:.55em .9em .6em; margin:.4em 0 1.1em }\
.qbm-card-title { margin:1.1em 0 .2em; font-size:1.05em; font-weight:600 }\
.qbm-graph { padding:.5em .75em; margin:.4em 0 1.1em }\
.qbm-plot { position:relative; margin:.4em 0 .1em; touch-action:pan-y; cursor:crosshair }\
.qbm-plot svg { display:block; width:100%; height:130px }\
.qbm-gl { position:absolute; left:2px; font-size:10px; opacity:.55; transform:translateY(1px); pointer-events:none; font-variant-numeric:tabular-nums }\
.qbm-now { margin-left:auto; display:flex; gap:1em; font-variant-numeric:tabular-nums }\
.qbm-cur { position:absolute; top:0; bottom:0; width:0; border-left:1.5px solid currentColor; opacity:.5; pointer-events:none }\
.qbm-dot { position:absolute; width:8px; height:8px; margin:-4px 0 0 -4px; border-radius:50%; background:currentColor; box-shadow:0 0 0 2px var(--qbm-bg, #fff); pointer-events:none }\
:root[data-darkmode="true"] .qbm-dot { box-shadow:0 0 0 2px #222 }\
.qbm-tip { position:absolute; z-index:10; padding:.35em .6em; border-radius:6px; font-size:11px; line-height:1.35; white-space:nowrap; pointer-events:none; font-variant-numeric:tabular-nums; background:#fff; border:1px solid rgba(128,128,128,.35); box-shadow:0 2px 8px rgba(0,0,0,.15) }\
:root[data-darkmode="true"] .qbm-tip { background:#2a2a2a; border-color:rgba(255,255,255,.15) }\
.qbm-tip-t { font-weight:400; opacity:.65; margin-bottom:2px }\
.qbm-tip-total { font-weight:700 }\
.qbm-tip-sub { font-weight:500; padding-left:.5em }\
.qbm-legend { display:flex; gap:1.25em; font-size:85%; opacity:.9 }\
.qbm-legend i { display:inline-block; width:.9em; height:.25em; vertical-align:middle; margin-right:.35em; border-radius:2px; background:currentColor }\
.qbm-empty { opacity:.7; font-style:italic; padding:.4em 0 }\
.qbm-serving { padding:.75em 1em .7em; margin-top:.2em }\
.qbm-pillrow { display:flex; align-items:center; gap:.6em; flex-wrap:wrap }\
.qbm-pill { border-radius:10px; padding:.2em .75em; font-size:1.2em; font-weight:700; background:var(--qbm-track) }\
.qbm-pill.on { background:var(--qbm-primary); color:#fff }\
.qbm-chip { border-radius:999px; padding:.1em .7em; font-size:.85em; background:rgba(128,128,128,.12) }\
.qbm-chip.on { background:rgba(52,130,255,.14); color:var(--qbm-primary) }\
.qbm-replay-chip { font-weight:600; font-variant-numeric:tabular-nums; background:rgba(52,130,255,.2) !important; color:var(--qbm-primary) !important }\
.qbm-pillrow .qbm-sims { margin-left:auto; display:flex; gap:.25em; flex-wrap:wrap }\
.qbm-fields { margin-top:.75em } .qbm-card > .qbm-fields:first-child { margin-top:0 }\
.qbm-frow { display:grid; grid-template-columns:1.08fr 1.08fr .84fr; padding:2px 0; column-gap:.5em }\
.qbm-f { min-width:0 }\
.qbm-f .k { font-size:.8em; opacity:.65; white-space:nowrap; overflow:hidden; text-overflow:ellipsis }\
.qbm-f .v { font-size:.95em; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; font-variant-numeric:tabular-nums }\
.qbm-f .v.click { cursor:pointer; text-decoration:underline dotted rgba(128,128,128,.6) }\
.qbm-foot { font-size:.8em; opacity:.6; margin:.2em 0 .6em }\
.qbm-ca { container-type:inline-size; font-size:13px; --l:64px }\
.qbm-ca.many { font-size:12px } .qbm-ca.lots { font-size:11px }\
.qbm-ca-row { display:grid; grid-template-columns:var(--l) repeat(var(--k), minmax(0,1fr)); align-items:center; padding:3px 0; border-bottom:1px solid var(--qbm-line) }\
.qbm-ca-row:last-child { border-bottom:0 }\
.qbm-ca-row > .l { font-size:.92em; opacity:.65; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; padding-right:4px }\
.qbm-ca-row > .c { padding:0 3px; text-align:center; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; font-variant-numeric:tabular-nums; min-width:0 }\
.qbm-ca-row.head > .c { font-weight:600 }\
@container (max-width: 440px) { .qbm-ca-row { grid-template-columns:max(60px, calc(var(--l) * .8)) repeat(var(--k), minmax(0,1fr)); font-size:11.5px } .qbm-ca-row > .c { padding:0 2px } }\
.qbm-nb-scroll { overflow-x:auto }\
.qbm-nb-table { width:100%; font-size:13px; font-variant-numeric:tabular-nums; container-type:inline-size }\
.qbm-nb-run { margin-bottom:10px }\
.qbm-nb-run:last-child { margin-bottom:0 }\
.qbm-nb-row { display:grid; align-items:center; padding:3px 0; border-bottom:1px solid var(--qbm-line) }\
.qbm-nb-row:last-child { border-bottom:0 }\
.qbm-nb-row.head { border-bottom:1px solid var(--qbm-line); font-size:.85em; opacity:.65; padding:2px 0 4px }\
.qbm-nb-row.head .qbm-nb-cell { font-weight:normal }\
.qbm-nb-row.has-beam { grid-template-columns:minmax(42px, .67fr) minmax(42px, .58fr) minmax(42px, .72fr) minmax(64px, 1.04fr) minmax(75px, 1fr) minmax(75px, 1fr) }\
.qbm-nb-row.no-beam { grid-template-columns:minmax(46px, .7fr) minmax(46px, .75fr) minmax(68px, 1.15fr) minmax(75px, 1.15fr) minmax(75px, 1.15fr) }\
.qbm-nb-cell { min-width:0; padding:0 3px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis }\
.qbm-nb-cell.center { text-align:center }\
.qbm-nb-cell.pci { font-weight:600 }\
@container (max-width: 440px) { .qbm-nb-row { font-size:11.5px } .qbm-nb-cell { padding:0 2px } }\
';

function isNum(v) { return radio.isNum(v); }
function fixed(v, d) { return isNum(v) ? v.toFixed(d) : null; }
function round(v) { return isNum(v) ? String(Math.round(v)) : null; }
function frac(v, max) { return isNum(v) && max ? Math.min(1, Math.max(0, v / max)) : null; }

/* A plain table cell: the value, or a grey dash. */
function txt(v, cls, style) {
	if (v == null || v === '') return E('span', { 'class': 'qbm-muted' }, '–');
	return E('span', { 'class': cls || null, 'style': style || null }, String(v));
}

function bandCell(label) {
	var c = radio.bandColor(label);
	return txt(label, null, c ? 'color:' + c : null);
}

/*
 * The CA table: one column per carrier, one row per parameter. Up to four
 * carriers the columns keep the width four would share and sit from the left
 * (a lone PCell isn't centred in empty space); from five they share the
 * width, and the text gets smaller.
 */
function caTable(carriers, rows) {
	var k = Math.max(4, carriers.length);
	var cls = 'qbm-ca' + (carriers.length >= 7 ? ' lots' : carriers.length >= 5 ? ' many' : '');
	function row(label, cells, head) {
		return E('div', { 'class': 'qbm-ca-row' + (head ? ' head' : '') }, [ E('div', { 'class': 'l' }, label) ].concat(cells.map(function(c) {
			return E('div', { 'class': 'c' }, c);
		})));
	}
	return E('div', { 'class': cls, 'style': '--k:' + k }, [
		/* The primaries' roles (P; MCG and SCG in NSA) are blue, the SCells grey. */
		row('', carriers.map(function(c) {
			return E('span', { 'class': /^(P|MCG|SCG)$/.test(c.role) ? 'qbm-primary' : 'qbm-muted' }, c.role);
		}), true)
	].concat(rows.map(function(r) {
		return row(r[0], carriers.map(r[1]));
	})));
}

function card(title, body) {
	return E('div', {}, [ E('div', { 'class': 'qbm-card-title' }, title), E('div', { 'class': 'qbm-card' }, body) ]);
}

function hasLteActivity(c) {
	return isNum(c.rsrp) || isNum(c.rsrq) || isNum(c.sinr) ||
		(isNum(c.mbps) && c.mbps > 0) || (isNum(c.rbs) && c.rbs > 0) ||
		(isNum(c.ul_mbps) && c.ul_mbps > 0) || (isNum(c.ul_rbs) && c.ul_rbs > 0) ||
		isNum(c.ul_pwr) || isNum(c.ul_pucch) ||
		(isNum(c.tx) && c.tx > 0) || (isNum(c.rx) && c.rx > 0);
}

function hasNrActivity(c) {
	return isNum(c.rsrp) || isNum(c.rsrq) || isNum(c.sinr) || isNum(c.csinr) ||
		(isNum(c.mbps) && c.mbps > 0) || (isNum(c.rbs) && c.rbs > 0) ||
		(isNum(c.ul_mbps) && c.ul_mbps > 0) || (isNum(c.ul_rbs) && c.ul_rbs > 0) ||
		(isNum(c.rx) && c.rx > 0);
}

/* LTE carriers in slot order, as the app's DIAG CA table. */
function lteCarriers(st, mode) {
	if (mode === 'NR_SA' || mode === 'WCDMA' || mode === 'GSM') return [];
	var raw = st.carriers || [];
	if (!mode && (st.nr || []).length > 0 && !raw.some(hasLteActivity)) return [];
	return raw.slice().sort(function(a, b) { return a.slot - b.slot; }).map(function(c) {
		var band = c.band > 0 ? c.band : (isNum(c.earfcn) ? radio.lteBand(c.earfcn) : null);
		return Object.assign({}, c, {
			rat: 'lte',
			role: c.slot === 0 ? 'P' : 'S' + c.slot,
			bandLabel: band ? 'B' + band : null,
			maxRb: 100
		});
	}).filter(radio.validLteCa);
}

/* NR carriers: the PCell first, then the rest by CC; P, S1, S2... */
function nrCarriers(st, mode) {
	if (mode === 'LTE' || mode === 'WCDMA' || mode === 'GSM') return [];
	var list = st.nr || [];
	if (!list.length) return [];
	if (!mode && (st.carriers || []).length > 0 && (st.carriers || []).some(hasLteActivity) && !list.some(hasNrActivity)) return [];
	var primary = list.filter(function(c) { return c.pcell === true; })[0] ||
		list.slice().sort(function(a, b) { return a.cc - b.cc; })[0];
	var ordered = [ primary ].concat(list.filter(function(c) { return c !== primary; }).sort(function(a, b) { return a.cc - b.cc; }));
	return ordered.map(function(c, i) {
		var band = c.band > 0 && radio.nrBandFits(c.band, c.arfcn) ? c.band : null;
		return Object.assign({}, c, {
			rat: 'nr',
			role: i === 0 ? 'P' : 'S' + i,
			bandNum: band,
			bandLabel: band ? 'n' + band : nrArfcnBand(c.arfcn),
			maxRb: radio.nrMaxRb(band, c.arfcn, c.scs, c.bw)
		});
	});
}

function hasUplink(c) {
	return isNum(c.ul_mbps) || isNum(c.ul_pwr) || isNum(c.ul_pucch) || !!c.ul_mod;
}

/* The uplink card's columns: in NSA one set across both RATs (MCG, the LTE
 * SCells, SCG, the NR SCells, numbered S1, S2... across both); the
 * primaries always, an SCell only when it sent something this round. */
function uplinkCarriers(lte, nr, mtk) {
	var both = lte.length && nr.length;
	var cols = lte.map(function(c, i) {
		return Object.assign({}, c, { role: both ? (i === 0 ? 'MCG' : 'S' + i) : c.role });
	}).concat(nr.map(function(c, i) {
		return Object.assign({}, c, { role: both ? (i === 0 ? 'SCG' : 'S' + (lte.length - 1 + i)) : c.role });
	}));
	return cols.filter(function(c) {
		/* The primary cell is always the anchor. On the FM350, a secondary
		 * only earns a column when it reports UL throughput: a cell with
		 * grants (RBs/MCS) but no PHY UL is just scheduling noise. */
		if (/^(P|MCG|SCG)$/.test(c.role)) return true;
		return mtk ? isNum(c.ul_mbps) : hasUplink(c);
	});
}

/*
 * The modulation split as one row per order (16QAM, 64QAM, 256QAM,
 * 1024QAM): each one's share of the window's transport blocks on a blue bar.
 * QPSK (and BPSK on the uplink) isn't shown, so the rows can add up to less
 * than 100 %. pct: the daemon's mod_pct (QPSK..1024QAM) or ul_mod_pct
 * (BPSK..1024QAM); off: the index of 16QAM in it.
 */
function modRows(key, off, include1024) {
	var names = include1024 ? [ '16QAM', '64QAM', '256QAM', '1024QAM' ] : [ '16QAM', '64QAM', '256QAM' ];
	return names.map(function(name, i) {
		return [ name, function(c) {
			var v = c[key] && c[key][off + i];
			return isNum(v) ? radio.gradientBar(v.toFixed(1) + '%', v / 100) : radio.emptyTrack();
		} ];
	});
}

function formatBw(bw) {
	var p = radio.plain(bw);
	return p ? p + ' MHz' : null;
}

/* SINR 40 dB with a weak RSRQ is the modem's value for an SCell that carries
 * no traffic (idle), not a measurement: "-" unless RSRQ is at least -4 dB. */
function lteSinr(c) {
	if (isNum(c.sinr) && c.sinr >= 40 && !(isNum(c.rsrq) && c.rsrq >= -4)) return null;
	return c.sinr;
}

function lteRows(phyUl) {
	var rows = [
		[ _('Band'), function(c) { return bandCell(c.bandLabel); } ],
		[ 'BW', function(c) { return txt(formatBw(c.bw)); } ],
		[ 'EARFCN', function(c) { return txt(c.earfcn); } ],
		[ 'PCI', function(c) { return txt(c.pci); } ],
		[ 'RSRP', function(c) { return radio.valueBar(c.rsrp, 'rsrp', 'dBm'); } ],
		[ 'RSRQ', function(c) { return radio.valueBar(c.rsrq, 'rsrq', 'dB'); } ],
		[ 'SINR', function(c) { return radio.valueBar(lteSinr(c), 'sinr', 'dB'); } ],
		[ _('Tx/Rx'), function(c) { return txt(isNum(c.tx) || isNum(c.rx) ? (c.tx || '-') + '×' + (c.rx || '-') : null); } ],
		[ _('TxMode'), function(c) { return txt(isNum(c.tm) ? 'TM' + c.tm : null); } ],
		/* The mean scheduled layers; the rank when no TBs came. */
		[ _('Rank'), function(c) { return txt(round(isNum(c.layers) ? c.layers : c.rank)); } ],
		[ 'RBs', function(c) { return radio.gradientBar(round(c.rbs), frac(c.rbs, c.maxRb)); } ],
		[ 'MCS', function(c) { return radio.gradientBar(round(c.mcs), frac(c.mcs, 31)); } ]
	].concat(modRows('mod_pct', 1, false), [
		[ 'BLER', function(c) { return radio.valueBar(c.bler, 'bler', '%'); } ],
		[ 'PHY DL', function(c) { return txt(fixed(c.mbps, 1), 'qbm-dl'); } ]
	]);
	if (phyUl) rows.push([ 'PHY UL', function(c) { return txt(fixed(c.ul_mbps, 1), 'qbm-ul'); } ]);
	return rows;
}

function nrRows(phyUl, mtk) {
	var rows = [
		[ _('Band'), function(c) { return bandCell(c.bandLabel); } ],
		[ 'BW', function(c) { return txt(formatBw(c.bw)); } ],
		[ 'NARFCN', function(c) { return txt(c.arfcn); } ],
		[ 'PCI', function(c) { return txt(c.pci); } ],
		[ 'RSRP', function(c) { return radio.valueBar(c.rsrp, 'rsrp', 'dBm'); } ],
		[ 'RSRQ', function(c) { return radio.valueBar(c.rsrq, 'rsrq', 'dB'); } ],
		[ 'SINR', function(c) { return radio.valueBar(c.sinr, 'sinr', 'dB'); } ],
		[ 'CSINR', function(c) { return radio.valueBar(c.csinr, 'sinr', 'dB'); } ],
		/* CSI-RS ports the network configured x the modem's RX antennas. */
		[ _('Port/Rx'), function(c) { return txt(isNum(c.csi_ports) || isNum(c.rx) ? (c.csi_ports || '-') + '×' + (c.rx || '-') : null); } ],
		[ _('Rank'), function(c) { return txt(round(c.layers)); } ],
		/* RBs over the cell's maximum (FR, SCS, bandwidth); no bar until known. */
		[ 'RBs', function(c) { return radio.gradientBar(round(c.rbs), frac(c.rbs, c.maxRb)); } ],
		[ 'MCS', function(c) { return radio.gradientBar(round(c.mcs), frac(c.mcs, 31)); } ]
	].concat(modRows('mod_pct', 1, !mtk), [
		[ 'BLER', function(c) { return radio.valueBar(c.bler, 'bler', '%'); } ],
		[ 'PHY DL', function(c) { return txt(fixed(c.mbps, 1), 'qbm-dl'); } ]
	]);
	if (phyUl) rows.push([ 'PHY UL', function(c) { return txt(fixed(c.ul_mbps, 1), 'qbm-ul'); } ]);
	return rows;
}

/* NSA ENDC: LTE carriers followed by NR carriers. */
function endcCarriers(lte, nr, hideEmpty) {
	if (hideEmpty) {
		/* FM350: a configured SCell with no measurement is stale. Keep the
		 * MCG/SCG anchors even while they have no value yet. */
		lte = lte.length ? [ lte[0] ].concat(lte.slice(1).filter(function(c) { return isNum(c.rsrp); })) : [];
		nr = nr.length ? [ nr[0] ].concat(nr.slice(1).filter(function(c) { return isNum(c.rsrp); })) : [];
	}
	return lte.map(function(c, i) {
		return Object.assign({}, c, { role: i === 0 ? 'MCG' : 'S' + i });
	}).concat(nr.map(function(c, i) {
		return Object.assign({}, c, { role: i === 0 ? 'SCG' : 'S' + (lte.length - 1 + i) });
	}));
}

function endcRows(phyUl, mtk) {
	var rows = [
		[ _('Band'), function(c) { return bandCell(c.bandLabel); } ],
		[ 'BW', function(c) { return txt(formatBw(c.bw)); } ],
		[ 'ARFCN', function(c) { return txt(isNum(c.earfcn) ? c.earfcn : c.arfcn); } ],
		[ 'PCI', function(c) { return txt(c.pci); } ],
		[ 'RSRP', function(c) { return radio.valueBar(c.rsrp, 'rsrp', 'dBm'); } ],
		[ 'RSRQ', function(c) { return radio.valueBar(c.rsrq, 'rsrq', 'dB'); } ],
		[ 'SINR', function(c) { return radio.valueBar(c.rat === 'nr' ? c.sinr : lteSinr(c), 'sinr', 'dB'); } ],
		[ 'CSINR', function(c) { return c.rat === 'nr' ? radio.valueBar(c.csinr, 'sinr', 'dB') : txt(null); } ],
		[ _('Tx/Rx'), function(c) {
			if (c.rat === 'nr')
				return txt(isNum(c.csi_ports) || isNum(c.rx) ? (c.csi_ports || '-') + '×' + (c.rx || '-') : null);
			return txt(isNum(c.tx) || isNum(c.rx) ? (c.tx || '-') + '×' + (c.rx || '-') : null);
		} ],
		[ _('Rank'), function(c) { return txt(round(isNum(c.layers) ? c.layers : c.rank)); } ],
		[ 'RBs', function(c) { return radio.gradientBar(round(c.rbs), frac(c.rbs, c.maxRb)); } ],
		[ 'MCS', function(c) { return radio.gradientBar(round(c.mcs), frac(c.mcs, 31)); } ]
	].concat(modRows('mod_pct', 1, !mtk), [
		[ 'BLER', function(c) { return radio.valueBar(c.bler, 'bler', '%'); } ],
		[ 'PHY DL', function(c) { return txt(fixed(c.mbps, 1), 'qbm-dl'); } ]
	]);
	if (phyUl) rows.push([ 'PHY UL', function(c) { return txt(fixed(c.ul_mbps, 1), 'qbm-ul'); } ]);
	return rows;
}

function uplinkRows(sa) {
	var rows = [
		[ _('Band'), function(c) { return bandCell(c.bandLabel); } ],
		[ 'BW', function(c) { return txt(formatBw(c.bw)); } ],
		[ 'RBs', function(c) { return radio.gradientBar(round(c.ul_rbs), frac(c.ul_rbs, c.maxRb)); } ],
		[ 'MCS', function(c) { return radio.gradientBar(round(c.ul_mcs), frac(c.ul_mcs, 31)); } ]
	].concat(modRows('ul_mod_pct', 2, false));
	/* NR only: there is no LTE uplink rank or TX mode. */
	if (sa) rows.push(
		[ _('Rank'), function(c) { return txt(round(c.ul_layers)); } ],
		[ _('TX mode'), function(c) { return txt(c.ul_tx_mode); } ]);
	rows.push(
		[ 'PUSCH', function(c) { return radio.valueBar(c.ul_pwr, 'tx', 'dBm'); } ],
		[ 'PUCCH', function(c) { return radio.valueBar(c.ul_pucch, 'tx', 'dBm'); } ],
		[ 'BLER', function(c) { return radio.valueBar(c.ul_bler, 'bler', '%'); } ],
		[ 'PHY UL', function(c) { return txt(fixed(c.ul_mbps, 1), 'qbm-ul'); } ]);
	return rows;
}

function sum(list, key) {
	var s = 0, any = false;
	list.forEach(function(c) { if (isNum(c[key])) { s += c[key]; any = true; } });
	return any ? s : null;
}

/* The grid step for a peak (ThroughputChart.kt niceStep): the smallest
 * 1-2-5 step whose five steps keep the peak under 80 % of the top. */
function niceStep(peak) {
	for (var decade = 1; ; decade *= 10)
		for (var i = 0, m = [ 1, 2, 5 ]; i < 3; i++)
			if (peak <= decade * m[i] * 5 * 0.8) return decade * m[i];
}

function clock(t) {
	var d = new Date(t);
	return [ d.getHours(), d.getMinutes(), d.getSeconds() ].map(function(x) { return (x < 10 ? '0' : '') + x; }).join(':');
}

function carrierBandName(c) {
	if (c.rat === 'nr') {
		var b = c.bandLabel || (c.band ? 'n' + c.band : nrArfcnBand(c.arfcn) || '');
		return (b || '–').replace(/^NR\s+/, '');
	}
	var b = c.bandLabel || (c.band ? 'B' + c.band : (isNum(c.earfcn) ? 'B' + radio.lteBand(c.earfcn) : ''));
	return (b || '–').replace(/^LTE\s+/, '');
}

function bandContributions(carriers, key) {
	var bands = [];
	carriers.forEach(function(c) {
		var name = carrierBandName(c);
		var v = isNum(c[key]) ? c[key] : 0;
		bands.push({ name: name, mbps: v });
	});
	return bands;
}

/*
 * Combined PHY DL and UL throughput (all carriers): DL and UL now in the
 * header (as the app's cursor box, each in its line's colour), five grid
 * steps labelled as in the app. Hovering (or touching) shows a cursor with
 * the two values at that moment in a box above the line; the hover point
 * is kept in hover.x (0..1 of the width) across refreshes.
 */
function graph(hist, hover, onHover, onLeave) {
	var W = 600, H = 130, n = HISTORY;
	var peak = 0;
	hist.forEach(function(p) { peak = Math.max(peak, p.dl || 0, p.ul || 0); });
	var step = niceStep(peak), top = step * 5;
	function x(i) { return W * (n - hist.length + i) / (n - 1); }
	function y(v) { return H - H * Math.min(1, (v || 0) / top); }

	var ns = 'http://www.w3.org/2000/svg';
	function el(name, attrs) {
		var e = document.createElementNS(ns, name);
		for (var k in attrs) e.setAttribute(k, attrs[k]);
		return e;
	}
	var svg = el('svg', { viewBox: '0 0 ' + W + ' ' + H, preserveAspectRatio: 'none', role: 'img',
	                      'aria-label': _('Downlink and uplink throughput') });
	for (var g = 0; g <= 5; g++)
		svg.appendChild(el('line', { x1: 0, x2: W, y1: H - H * g / 5, y2: H - H * g / 5, stroke: 'currentColor',
		                             'stroke-opacity': g ? '0.12' : '0.25', 'vector-effect': 'non-scaling-stroke' }));
	[ [ 'ul', 'qbm-ul' ], [ 'dl', 'qbm-dl' ] ].forEach(function(sr) {
		if (hist.length < 2) return;
		svg.appendChild(el('polyline', {
			points: hist.map(function(p, i) { return x(i).toFixed(1) + ',' + y(p[sr[0]]).toFixed(1); }).join(' '),
			fill: 'none', 'class': sr[1], stroke: 'currentColor', 'stroke-width': 2, 'stroke-linejoin': 'round',
			'vector-effect': 'non-scaling-stroke'
		}));
	});

	var last = hist[hist.length - 1];
	var labels = [ 1, 2, 3, 4, 5 ].map(function(g) {
		return E('span', { 'class': 'qbm-gl', 'style': 'top:' + (100 - g * 20) + '%' }, String(step * g));
	});
	var cursor = E('div', { 'class': 'qbm-cur', 'style': 'display:none' });
	var dotDl = E('div', { 'class': 'qbm-dot qbm-dl', 'style': 'display:none' });
	var dotUl = E('div', { 'class': 'qbm-dot qbm-ul', 'style': 'display:none' });
	var tip = E('div', { 'class': 'qbm-tip', 'style': 'display:none' });
	var plot = E('div', { 'class': 'qbm-plot' }, [ svg ].concat(labels, [ cursor, dotUl, dotDl, tip ]));

	function show(frac) {
		if (frac == null || hist.length < 1) {
			[ cursor, dotDl, dotUl, tip ].forEach(function(e) { e.style.display = 'none'; });
			return null;
		}
		/* the nearest sample to the pointer */
		var i = Math.round(frac * (n - 1)) - (n - hist.length);
		i = Math.max(0, Math.min(hist.length - 1, i));
		var p = hist[i], px = x(i) / W * 100;
		cursor.style.left = px + '%'; cursor.style.display = '';
		dotDl.style.left = dotUl.style.left = px + '%';
		dotDl.style.top = y(p.dl) / H * 100 + '%'; dotUl.style.top = y(p.ul) / H * 100 + '%';
		dotDl.style.display = dotUl.style.display = '';
		var content = [
			E('div', { 'class': 'qbm-tip-t' }, clock(p.t)),
			E('div', { 'class': 'qbm-dl qbm-tip-total' }, 'DL TOTAL: ' + (fixed(p.dl, 1) || '0.0') + ' Mbps')
		];
		(p.dl_bands || []).forEach(function(b) {
			content.push(E('div', { 'class': 'qbm-dl qbm-tip-sub' }, b.name + ': ' + (fixed(b.mbps, 1) || '0.0') + ' Mbps'));
		});
		content.push(E('div', { 'class': 'qbm-ul qbm-tip-total', 'style': 'margin-top:4px' }, 'UL TOTAL: ' + (fixed(p.ul, 1) || '0.0') + ' Mbps'));
		(p.ul_bands || []).forEach(function(b) {
			content.push(E('div', { 'class': 'qbm-ul qbm-tip-sub' }, b.name + ': ' + (fixed(b.mbps, 1) || '0.0') + ' Mbps'));
		});
		dom.content(tip, content);
		tip.style.display = '';
		/* above the higher of the two points, kept inside the plot */
		var pw = plot.clientWidth || 1, tw = tip.offsetWidth, th = tip.offsetHeight;
		var left = Math.max(0, Math.min(pw - tw, px / 100 * pw - tw / 2));
		var topPx = Math.min(y(p.dl), y(p.ul)) / H * plot.clientHeight - th - 8;
		/* no room above the point: beside it, inside the plot */
		if (topPx < 0) {
			topPx = 2;
			left = (px / 100 * pw + 12 + tw > pw) ? (px / 100 * pw - tw - 12) : (px / 100 * pw + 12);
			left = Math.max(0, Math.min(pw - tw, left));
		}
		tip.style.left = left + 'px';
		tip.style.top = topPx + 'px';
		return i;
	}
	function move(ev) {
		var r = plot.getBoundingClientRect();
		hover.x = Math.max(0, Math.min(1, (ev.clientX - r.left) / r.width));
		var idx = show(hover.x);
		if (typeof onHover === 'function')
			onHover(idx, ev);
	}
	plot.addEventListener('pointermove', move);
	plot.addEventListener('pointerdown', move);
	plot.addEventListener('pointerleave', function(ev) {
		if (ev.pointerType === 'mouse') {
			hover.x = null;
			show(null);
			if (typeof onLeave === 'function')
				onLeave(ev);
		}
	});

	var node = E('div', { 'class': 'qbm-graph' }, [
		E('div', { 'class': 'qbm-legend' }, [
			E('span', { 'class': 'qbm-dl' }, [ E('i'), _('Downlink') ]),
			E('span', { 'class': 'qbm-ul' }, [ E('i'), _('Uplink') ]),
			E('span', { 'class': 'qbm-now' }, [
				E('b', { 'class': 'qbm-dl' }, 'DL ' + (last ? fixed(last.dl, 1) : '–') + ' Mbps'),
				E('b', { 'class': 'qbm-ul' }, 'UL ' + (last ? fixed(last.ul, 1) : '–') + ' Mbps')
			])
		]),
		plot
	]);
	node.show = show;
	if (hover.x != null) requestAnimationFrame(function() {
		var idx = show(hover.x);
		if (typeof onHover === 'function' && idx != null)
			onHover(idx);
	});
	return node;
}

function antennaCarrierTitle(rat, role, bandLabel, pci, mode, isFirst) {
	var ratLabel = rat === 'nr' ? 'NR' : 'LTE';
	var roleLabel = role === 'P'
		? (rat === 'nr' && mode === 'NR_NSA' ? 'PSCell' : 'PCell')
		: ('SCell ' + String(role).replace(/^S/, ''));
	var color = bandLabel ? radio.bandColor(bandLabel) : null;
	var parts = [
		E('span', { 'style': 'font-weight:600' }, ratLabel + ' ' + roleLabel)
	];
	if (bandLabel)
		parts.push(E('span', { 'style': (color ? 'color:' + color + ';' : '') + ' margin-left:.5em; font-weight:600' }, bandLabel));
	if (pci != null)
		parts.push(E('span', { 'class': 'qbm-muted', 'style': 'margin-left:.5em;' }, '| PCI ' + pci));
	return E('div', { 'class': 'qbm-antenna-hdr', 'style': (isFirst ? 'margin-top:0;' : 'margin-top:.8em;') + ' margin-bottom:.3em; font-size:.95em;' }, parts);
}

function antennaTable(rat, chains, header, crsrp) {
	var cols, kinds;
	if (rat === 'nr' && crsrp) {
		cols = [ 'RSRP', 'CRSRP', 'SINR', 'CSINR' ];
		kinds = [ 'rsrp', 'rsrp', 'sinr', 'sinr' ];
	} else if (rat === 'nr') {
		cols = [ 'RSRP', 'SINR', 'CSINR' ];
		kinds = [ 'rsrp', 'sinr', 'sinr' ];
	} else {
		cols = [ 'RSRP', 'RSRQ', 'SINR' ];
		kinds = [ 'rsrp', 'rsrq', 'sinr' ];
	}
	var units = kinds.map(function(kd) { return kd === 'rsrp' ? 'dBm' : 'dB'; });
	var k = cols.length;
	function row(label, cells, head) {
		return E('div', { 'class': 'qbm-ca-row' + (head ? ' head' : '') }, [
			E('div', { 'class': 'l' }, label)
		].concat(cells.map(function(c) {
			return E('div', { 'class': 'c' }, c);
		})));
	}
	var out = [];
	if (header) {
		out.push(row('', cols.map(function(col) {
			return E('span', { 'class': 'qbm-muted' }, col);
		}), true));
	}
	chains.forEach(function(ch) {
		var cells = ch.values.map(function(v, idx) {
			return radio.valueBar(v, kinds[idx], units[idx]);
		});
		out.push(row('RX' + ch.i, cells));
	});
	return E('div', { 'class': 'qbm-ca', 'style': '--k:' + k + '; --l:64px' }, out);
}

/*
 * Antenna card: grouped per serving carrier (PCell, SCells, PSCell) as in
 * QBM (NetworkScreen.kt CarrierAntennaRows), with RX chains as rows and
 * RSRP, RSRQ/SINR, SINR/CSINR as columns. On the FM350 the NR tables add a
 * CRSRP column: the per-RX CSI-RS RSRP the MTK backend reports.
 */
function antennas(lte, nr, mode, mtk) {
	var carriers = [];
	lte.forEach(function(c) { carriers.push({ rat: 'lte', c: c }); });
	nr.forEach(function(c) { carriers.push({ rat: 'nr', c: c }); });
	var sections = [];
	var lastRat = null;
	carriers.forEach(function(item) {
		var rat = item.rat, c = item.c;
		var crsrp = mtk && rat === 'nr';
		var rxLimit = (isNum(c.rx) && c.rx > 0) ? Math.min(4, c.rx) : 4;
		var chains = [];
		for (var i = 0; i < rxLimit; i++) {
			var vals;
			if (rat === 'nr') {
				var rsrp = c.rsrp_rx ? c.rsrp_rx[i] : null;
				var sinr = c.sinr_rx ? c.sinr_rx[i] : null;
				var csinr = c.csinr_rx ? c.csinr_rx[i] : null;
				var crsrpv = crsrp && c.crsrp_rx ? c.crsrp_rx[i] : null;
				/* FM350: the dedicated CSI-RS measurement, else the TRS RSRP */
				if (crsrp && !isNum(crsrpv) && c.crsrp_trs_rx) crsrpv = c.crsrp_trs_rx[i];
				if (!isNum(rsrp) && !isNum(sinr) && !isNum(csinr) && !isNum(crsrpv)) continue;
				vals = crsrp ? [ rsrp, crsrpv, sinr, csinr ] : [ rsrp, sinr, csinr ];
			} else {
				var rsrp = c.rsrp_rx ? c.rsrp_rx[i] : null;
				var rsrq = c.rsrq_rx ? c.rsrq_rx[i] : null;
				var sinr = c.sinr_rx ? c.sinr_rx[i] : null;
				if (!isNum(rsrp) && !isNum(rsrq) && !isNum(sinr)) continue;
				vals = [ rsrp, rsrq, sinr ];
			}
			chains.push({ i: i, values: vals });
		}
		if (!chains.length) return;
		var showHdr = (lastRat !== rat);
		lastRat = rat;
		sections.push(antennaCarrierTitle(rat, c.role, c.bandLabel, c.pci, mode, sections.length === 0));
		sections.push(antennaTable(rat, chains, showHdr, crsrp));
	});
	if (!sections.length)
		return E('div', { 'class': 'qbm-empty' }, _('No per-antenna measurements in this window.'));
	return E('div', { 'class': 'qbm-antennas' }, sections);
}

function getLoc(reply, snap) {
	if (snap && snap.loc) return snap.loc;
	if (!reply || reply.ok !== true) return null;
	try {
		var frames = serving.framesOf(reply);
		if (frames && frames['0043']) return serving.decodeCellLocation(frames['0043']);
	} catch (e) {}
	return null;
}

function buildNeighbours(st, reply, mode, snap) {
	var loc = getLoc(reply, snap);
	var lteList = [];

	/* LTE neighbours (NetworkSnapshot.kt neighborList): intra-freq (excluding serving PCI) and inter-freq */
	if (loc && loc.lte && mode !== 'NR_SA' && mode !== 'WCDMA' && mode !== 'GSM') {
		var s = loc.lte;
		if (s.intraFreq && s.intraFreq.length) {
			s.intraFreq.forEach(function(m) {
				if (m.pci !== s.pci && isNum(m.pci)) {
					var rsrp = (isNum(m.rsrp) && m.rsrp > -200) ? m.rsrp : null;
					var rsrq = (isNum(m.rsrq) && m.rsrq > -200) ? m.rsrq : null;
					lteList.push({
						rat: 'lte',
						idLabel: 'PCI',
						pci: m.pci,
						channelLabel: 'EARFCN',
						channel: s.earfcn,
						beam: null,
						rsrp: rsrp,
						rsrq: rsrq,
						band: radio.lteBand(s.earfcn) ? 'B' + radio.lteBand(s.earfcn) : null
					});
				}
			});
		}
		if (loc.lteInterFreq && loc.lteInterFreq.length) {
			loc.lteInterFreq.forEach(function(g) {
				(g.cells || []).forEach(function(m) {
					if (isNum(m.pci)) {
						var rsrp = (isNum(m.rsrp) && m.rsrp > -200) ? m.rsrp : null;
						var rsrq = (isNum(m.rsrq) && m.rsrq > -200) ? m.rsrq : null;
						lteList.push({
							rat: 'lte',
							idLabel: 'PCI',
							pci: m.pci,
							channelLabel: 'EARFCN',
							channel: g.earfcn,
							beam: null,
							rsrp: rsrp,
							rsrq: rsrq,
							band: radio.lteBand(g.earfcn) ? 'B' + radio.lteBand(g.earfcn) : null
						});
					}
				});
			});
		}
	}
	/* Best quality first: sorted by RSRQ descending, unmeasured last (NetworkSnapshot.kt line 676) */
	lteList.sort(function(a, b) {
		var vA = isNum(a.rsrq) ? a.rsrq : -Infinity;
		var vB = isNum(b.rsrq) ? b.rsrq : -Infinity;
		return vB - vA;
	});

	/* WCDMA / GSM neighbours if applicable */
	var otherList = [];
	if (loc && loc.wcdma && mode === 'WCDMA') {
		(loc.wcdma.neighbors || []).forEach(function(m) {
			var rscp = (isNum(m.rscp) && m.rscp > -200) ? m.rscp : null;
			var ecio = (isNum(m.ecio) && m.ecio > -200) ? m.ecio : null;
			otherList.push({
				rat: 'wcdma',
				idLabel: 'PSC',
				pci: m.psc,
				channelLabel: 'UARFCN',
				channel: m.uarfcn,
				beam: null,
				rsrp: rscp,
				rsrq: ecio,
				band: null
			});
		});
		otherList.sort(function(a, b) {
			var vA = isNum(a.rsrq) ? a.rsrq : -Infinity;
			var vB = isNum(b.rsrq) ? b.rsrq : -Infinity;
			return vB - vA;
		});
	} else if (loc && loc.gsm && mode === 'GSM') {
		(loc.gsm.neighbors || []).forEach(function(m) {
			var rxlev = (isNum(m.rxlev) && m.rxlev > -200) ? m.rxlev : null;
			otherList.push({
				rat: 'gsm',
				idLabel: 'BSIC',
				pci: m.bsic,
				channelLabel: 'ARFCN',
				channel: m.arfcn,
				beam: null,
				rsrp: rxlev,
				rsrq: null,
				band: null
			});
		});
	}

	/* 5G NR neighbour beams from DIAG B97F (NetworkSnapshot.kt line 686) */
	var nrList = [];
	var rawNrNb = (st && st.nr_nb) || [];
	if (rawNrNb.length && mode !== 'LTE' && mode !== 'WCDMA' && mode !== 'GSM') {
		var known = {};
		var nr = nrCarriers(st, mode);
		nr.forEach(function(c) { if (c.bandNum) known[c.arfcn] = 'n' + c.bandNum; });
		rawNrNb.forEach(function(n) {
			var rsrp = (isNum(n[3]) && n[3] > -200) ? n[3] : null;
			var rsrq = (isNum(n[4]) && n[4] > -200) ? n[4] : null;
			nrList.push({
				rat: 'nr',
				idLabel: 'PCI',
				pci: n[1],
				channelLabel: 'NARFCN',
				channel: n[0],
				beam: n[2],
				rsrp: rsrp,
				rsrq: rsrq,
				/* A configured carrier's band, else the carriers' rule (with the
				 * n77/78 stand-in), else one band for the NR-ARFCN (the app's
				 * neighbour rule): not "n77/78" on every beam when the
				 * configuration (B825) came before the port was opened. */
				band: known[n[0]] || nrArfcnBand(n[0]) || (radio.nrBand(n[0], nrHwBands) != null ? 'n' + radio.nrBand(n[0], nrHwBands) : null)
			});
		});
		/* Strongest RSRP first: sorted by RSRP descending (NetworkSnapshot.kt line 682) */
		nrList.sort(function(a, b) {
			var vA = isNum(a.rsrp) ? a.rsrp : -Infinity;
			var vB = isNum(b.rsrp) ? b.rsrp : -Infinity;
			return vB - vA;
		});
	}

	return lteList.concat(otherList).concat(nrList);
}

/*
 * The neighbour table: Band | PCI | EARFCN | RSRP bar | RSRQ bar (NeighbourTable.kt).
 * Consecutive rows of the same RAT form a run with its own header.
 * A run with NR beams adds a Beam column after PCI: Band | PCI | Beam | NARFCN | RSRP | RSRQ.
 */
function renderNeighbourTable(rows) {
	if (!rows || !rows.length)
		return E('div', { 'class': 'qbm-empty' }, _('No neighbour cells reported.'));

	var runs = [];
	rows.forEach(function(t) {
		if (runs.length && runs[runs.length - 1][0].rat === t.rat) {
			runs[runs.length - 1].push(t);
		} else {
			runs.push([ t ]);
		}
	});

	var out = [];
	runs.forEach(function(run) {
		var first = run[0];
		var hasBeams = run.some(function(t) { return t.beam != null; });
		var idLabel = first.idLabel || 'PCI';
		var chLabel = first.channelLabel || 'ARFCN';
		var m1Label = (first.rat === 'wcdma') ? 'RSCP' : (first.rat === 'gsm' ? 'RxLev' : 'RSRP');
		var m2Label = (first.rat === 'wcdma') ? 'Ec/Io' : (first.rat === 'gsm' ? null : 'RSRQ');

		var rowCls = 'qbm-nb-row ' + (hasBeams ? 'has-beam' : 'no-beam');

		var headCells = [
			E('div', { 'class': 'qbm-nb-cell' }, _('Band')),
			E('div', { 'class': 'qbm-nb-cell' }, _(idLabel))
		];
		if (hasBeams)
			headCells.push(E('div', { 'class': 'qbm-nb-cell center' }, _('Beam')));
		headCells.push(E('div', { 'class': 'qbm-nb-cell' }, _(chLabel)));
		headCells.push(E('div', { 'class': 'qbm-nb-cell center' }, _(m1Label)));
		if (m2Label)
			headCells.push(E('div', { 'class': 'qbm-nb-cell center' }, _(m2Label)));

		var runNodes = [
			E('div', { 'class': rowCls + ' head' }, headCells)
		];

		run.forEach(function(t) {
			var cells = [
				E('div', { 'class': 'qbm-nb-cell' }, bandCell(t.band)),
				E('div', { 'class': 'qbm-nb-cell pci' }, txt(t.pci))
			];
			if (hasBeams)
				cells.push(E('div', { 'class': 'qbm-nb-cell center' }, txt(t.beam)));
			cells.push(E('div', { 'class': 'qbm-nb-cell' }, txt(t.channel)));
			cells.push(E('div', { 'class': 'qbm-nb-cell center' }, radio.valueBar(t.rsrp, 'rsrp', 'dBm')));
			if (m2Label)
				cells.push(E('div', { 'class': 'qbm-nb-cell center' }, radio.valueBar(t.rsrq, 'rsrq', 'dB')));

			runNodes.push(E('div', { 'class': rowCls }, cells));
		});

		out.push(E('div', { 'class': 'qbm-nb-run' }, runNodes));
	});

	return E('div', { 'class': 'qbm-nb-scroll' }, [
		E('div', { 'class': 'qbm-nb-table' }, out)
	]);
}

function neighboursCard(st, reply, mode, snap) {
	if (!snap) snap = getSnapshot(reply, st);
	var rows = buildNeighbours(st, reply, mode, snap);
	var hasServing = snap && snap.cells && snap.cells.length > 0;
	if (!rows.length && !hasServing)
		return null;
	var title = rows.length ? _('Neighbour cells (%d)').format(rows.length) : _('Neighbour cells');
	return card(title, renderNeighbourTable(rows));
}

/*
 * The serving card's field grid (NetworkScreen.kt FieldGrid): three columns
 * (1.08 : 1.08 : 0.84), a field spanning 1, 2 or 3 of them by its value's
 * length, packed in order; the operator row is packed on its own and goes
 * after the first extraAfter rows.
 */
function fieldGrid(fields, extra, extraAfter, onGnb) {
	function span(f) { return f.label === 'gNB / Cell' || f.value.length <= 11 ? 1 : f.value.length <= 24 ? 2 : 3; }
	function pack(list) {
		var rows = [], row = [], used = 0;
		list.forEach(function(f) {
			if (used + span(f) > 3) { rows.push(row); row = []; used = 0; }
			row.push(f); used += span(f);
		});
		if (row.length) rows.push(row);
		return rows;
	}
	var main = pack(fields), rows = main.slice(0, extraAfter).concat(pack(extra || []), main.slice(extraAfter));
	return E('div', { 'class': 'qbm-fields' }, rows.map(function(row) {
		return E('div', { 'class': 'qbm-frow' }, row.map(function(f) {
			var c = f.label === 'Band' ? radio.bandColor(f.value) : null;
			var gnb = f.label === 'gNB / Cell' && f.value !== '\u2013' && onGnb;
			return E('div', { 'class': 'qbm-f', 'style': 'grid-column:span ' + span(f) }, [
				E('div', { 'class': 'k' }, _(f.label)),
				E('div', {
					'class': 'v' + (gnb ? ' click' : ''), 'title': gnb ? _('gNB ID length: %d bits (click to change)').format(gnb.bits) : f.value,
					'style': c ? 'color:' + c : null, 'click': gnb ? gnb.click : null
				}, f.value)
			]);
		}));
	}));
}

function gnbBits() {
	try { var v = +localStorage.getItem('qbm-gnb-bits'); return v >= 22 && v <= 32 ? v : 22; } catch (e) { return 22; }
}

function getSnapshot(reply, st) {
	if (!reply || reply.ok !== true) return null;
	try {
		if (modemFamily === 'mtk') return serving.snapshotAt(reply, st, gnbBits(), nrHwBands);
		return serving.snapshot(serving.framesOf(reply), st, gnbBits(), nrHwBands);
	} catch (e) {
		return null;
	}
}

return view.extend({
	sim: 1,
	history: [],
	rounds: [],
	replayingIndex: null,

	load: function() {
		return Promise.all([
			L.resolveDefault(callStatus(), {}),
			modemInfo.identify().then(function(m) { modemFamily = m.family; })
		]).then(function(r) { return r[0]; });
	},

	/* The serving cell card(s): the summary card (mode pill, RRC chip,
	 * operator row, the serving cell's fields) and, in NSA, the NR SCG card. */
	renderServing: function(reply, st, replayTime, snap) {
		var self = this;
		/* Reconnect button and SIM 1 / SIM 2 (dual SIM) */
		var simButtons = (st && st.sim_tags) ? [1, 2].map(function(n) {
			return E('button', {
				'class': 'cbi-button' + (self.sim === n ? ' cbi-button-positive' : ''),
				'click': function() { self.sim = n; self.history = []; self.rounds = []; self.replayingIndex = null; self.servingReply = null; self.refresh(true); }
			}, _('SIM %d').format(n));
		}) : [];

		var reconnectBtn = E('button', {
			'class': 'cbi-button',
			'title': _('Airplane mode (AT+CFUN=4, then 1), then the connection is brought down and up so the IP address is refreshed'),
			'click': ui.createHandlerFn(self, function() {
				return self.reconnect();
			})
		}, _('Reconnect'));

		var sims = E('div', { 'class': 'qbm-sims' }, [ reconnectBtn ].concat(simButtons));

		if (!reply || reply.ok !== true) {
			var why = !reply ? _('waiting for the first read') :
				(reply.error && (reply.error.message || reply.error)) || _('unknown error');
			var pillRow = [ E('span', { 'class': 'qbm-pill' }, reply ? _('Unavailable') : _('Loading…')) ];
			if (replayTime)
				pillRow.push(E('span', { 'class': 'qbm-chip on qbm-replay-chip' }, _('Replay %s').format(clock(replayTime))));
			pillRow.push(sims);
			return E('div', { 'class': 'qbm-card qbm-serving' }, [
				E('div', { 'class': 'qbm-pillrow' }, pillRow),
				reply ? E('div', { 'class': 'qbm-foot', 'style': 'margin-top:.6em' },
					_('Serving cell (QMI port): %s. Check the QMI port under Settings, and that the modem is connected.').format(String(why).replace(/\.$/, ''))) : ''
			]);
		}

		if (!snap) snap = getSnapshot(reply, st);
		if (!snap) return E('div', { 'class': 'qbm-card qbm-serving' }, [
			E('div', { 'class': 'qbm-pillrow' }, [
				E('span', { 'class': 'qbm-pill' }, _('Unavailable')),
				sims
			])
		]);
		this.wantOperator(snap);
		var name = snap.plmn ? this.operatorNames[serving.plmnText(snap.plmn)] : null;
		var cells = snap.cells;
		var gnb = { bits: gnbBits(), click: function() {
			var b = gnbBits() >= 32 ? 22 : gnbBits() + 1;
			try { localStorage.setItem('qbm-gnb-bits', String(b)); } catch (e) {}
			self.redraw();
		} };

		var pillChildren = [
			E('span', { 'class': 'qbm-pill' + (snap.inService ? ' on' : '') }, _(snap.modeLabel))
		];
		if (snap.rrc)
			pillChildren.push(E('span', { 'class': 'qbm-chip' + (snap.rrc === 'Connected' ? ' on' : '') }, 'RRC: ' + _(snap.rrc)));
		if (replayTime)
			pillChildren.push(E('span', { 'class': 'qbm-chip on qbm-replay-chip' }, _('Replay %s').format(clock(replayTime))));
		pillChildren.push(sims);
		var pill = E('div', { 'class': 'qbm-pillrow' }, pillChildren);

		var operatorRow = (name != null || snap.plmn || snap.roaming != null) ? [
			{ label: 'Operator', value: name || '–' },
			{ label: 'PLMN', value: serving.plmnText(snap.plmn) || '–' },
			{ label: 'Roaming', value: snap.roaming === true ? _('Yes') : snap.roaming === false ? _('No') : '–' }
		] : [];
		var first = cells[0];
		var out = [ E('div', { 'class': 'qbm-card qbm-serving' }, [
			pill,
			(first && first.fields.length) || operatorRow.length
				? fieldGrid(first ? first.fields : [], operatorRow, snap.mode === 'GSM' ? 1 : 0, gnb) : ''
		]) ];
		cells.slice(1).forEach(function(c) {
			out.push(card(_(c.title), fieldGrid(c.fields, [], 0, gnb)));
		});
		if (!cells.length && [ 'NO_SERVICE', 'LIMITED', 'UNKNOWN' ].indexOf(snap.mode) < 0)
			out.push(E('div', { 'class': 'qbm-foot' }, _('The modem reports no serving cell details.')));
		if (snap.failedReads.length)
			out.push(E('div', { 'class': 'qbm-foot' }, _('Not available this round: %s').format(snap.failedReads.join(', '))));
		return E('div', {}, out);
	},

	/* The operator name, read once per PLMN (GET_PLMN_NAME, GET_HOME_NETWORK). */
	operatorNames: {},
	wantOperator: function(snap) {
		var self = this, key = serving.plmnText(snap.plmn);
		if (key && snap.operatorName) { this.operatorNames[key] = snap.operatorName; return; }   /* AT+COPS? */
		if (!key || key in this.operatorNames || modemFamily === 'mtk') return;
		this.operatorNames[key] = null;
		L.resolveDefault(callOperator(this.sim, snap.plmn.mcc, snap.plmn.mnc), null).then(function(r) {
			var f = serving.framesOf(r);
			self.operatorNames[key] = serving.operatorName(snap.plmn, snap.roaming, f['0025'], f['0044']);
			if (self.operatorNames[key]) self.redraw();
		});
	},

	renderDiagBefore: function(status, st, mode) {
		if (!status || status.ok !== true) {
			var why = status && status.error === 'not running'
				? _('The statistics service (qbm-diagd) is not running: carrier aggregation, throughput and antennas need it. Enable it under Settings, or check the system log.')
				: _('No answer from qbm-diagd: %s').format((status && status.error) || _('unknown error'));
			return [ E('div', { 'class': 'alert-message warning' }, why) ];
		}
		/* On demand, the daemon opens the port when this page starts reading:
		 * that's normal for the first polls; a warning only if it stays shut. */
		if (!status.port_open) {
			this.closedPolls = (this.closedPolls || 0) + 1;
			if (status.on_demand && this.closedPolls <= 5)
				return [ E('div', { 'class': 'qbm-empty' }, E('em', { 'class': 'spinning' }, _('Opening the DIAG port\u2026'))) ];
			return [ E('div', { 'class': 'alert-message warning' }, _('Statistics: waiting for the modem\'s DIAG port (%s).').format(status.dev)) ];
		}
		this.closedPolls = 0;
		if (!st) return [ E('div', { 'class': 'qbm-empty' }, _('No statistics yet.')) ];

		var lte = lteCarriers(st, mode), nr = nrCarriers(st, mode);
		var up = uplinkCarriers(lte, nr, st.lib === 'mtk-icd');
		var body = [];
		if (!lte.length && !nr.length && st.logs === 0)
			body.push(E('div', { 'class': 'alert-message notice' },
				_('Connected, but the modem has sent no logs yet. It logs while it is attached to a network.')));
		if ((mode === 'NR_NSA' || (!mode && lte.length && nr.length)) && lte.length && nr.length) {
			body.push(card(_('ENDC'), caTable(endcCarriers(lte, nr, st.lib === 'mtk-icd'), endcRows(!up.length, st.lib === 'mtk-icd'))));
		} else {
			if (lte.length) body.push(card(_('LTE carrier aggregation'), caTable(lte, lteRows(!up.length))));
			if (nr.length) body.push(card(_('NR carrier aggregation'), caTable(nr, nrRows(!up.length, st.lib === 'mtk-icd'))));
		}
		if (!lte.length && !nr.length && st.logs > 0)
			body.push(E('div', { 'class': 'qbm-empty' }, _('No serving carriers in this window.')));
		return body;
	},

	renderDiagAfter: function(status, st, mode, reply, snap) {
		var body = [];
		var hasStats = status && status.ok === true && status.port_open && st;
		if (hasStats) {
			var lte = lteCarriers(st, mode), nr = nrCarriers(st, mode);
			var up = uplinkCarriers(lte, nr, st.lib === 'mtk-icd');
			if (up.length) body.push(card(_('Uplink'), caTable(up, uplinkRows(!lte.length))));
			body.push(card(_('Antennas'), antennas(lte, nr, mode, st.lib === 'mtk-icd')));
		}
		var nb = neighboursCard(st, reply, mode, snap);
		if (nb) body.push(nb);
		return body;
	},

	renderDiag: function(status, st, mode) {
		var hasStats = status && status.ok === true && status.port_open && st;
		if (!hasStats)
			return this.renderDiagBefore(status, st, mode);
		var before = this.renderDiagBefore(status, st, mode);
		var graphNode = this.graphNode || this.createGraphNode();
		var after = this.renderDiagAfter(status, st, mode, this.servingReply);
		return before.concat([ graphNode ]).concat(after);
	},

	createGraphNode: function() {
		var self = this;
		var node = graph(this.history, this.hover, function(idx, ev) {
			if (!self.paused) return;
			if (idx == null) return;
			if (self.replayingIndex === idx) return;
			self.replay(idx);
		}, function(ev) {
			if (!self.paused) return;
			self.showFrozen();
		});
		this.graphShow = node.show;
		return node;
	},

	replay: function(i) {
		if (!this.rounds || !this.rounds[i]) return;
		this.replayingIndex = i;
		var r = this.rounds[i];
		var snap = getSnapshot(r.servingReply, r.st);
		var mode = snap ? snap.mode : null;
		dom.content(this.servingSlot, this.renderServing(r.servingReply, r.st, r.t, snap));
		dom.content(this.diagBeforeSlot, this.renderDiagBefore(r.status, r.st, mode));
		dom.content(this.diagAfterSlot, this.renderDiagAfter(r.status, r.st, mode, r.servingReply, snap));
	},

	showFrozen: function() {
		this.replayingIndex = null;
		var newest = this.rounds && this.rounds.length ? this.rounds[this.rounds.length - 1] : null;
		var status = newest ? newest.status : (this.last && this.last.status);
		var st = newest ? newest.st : (this.last && this.last.st);
		var servingReply = newest ? newest.servingReply : this.servingReply;
		var snap = getSnapshot(servingReply, st);
		var mode = snap ? snap.mode : null;
		dom.content(this.servingSlot, this.renderServing(servingReply, st, null, snap));
		dom.content(this.diagBeforeSlot, this.renderDiagBefore(status, st, mode));
		dom.content(this.diagAfterSlot, this.renderDiagAfter(status, st, mode, servingReply, snap));
	},

	redraw: function() {
		var st = this.last && this.last.st;
		var status = this.last && this.last.status;
		var reply = this.servingReply;
		var replayTime = null;
		if (this.paused && this.replayingIndex != null && this.rounds && this.rounds[this.replayingIndex]) {
			var r = this.rounds[this.replayingIndex];
			st = r.st;
			status = r.status;
			reply = r.servingReply;
			replayTime = r.t;
		}
		var snap = getSnapshot(reply, st);
		var mode = snap ? snap.mode : null;
		dom.content(this.servingSlot, this.renderServing(reply, st, replayTime, snap));
		dom.content(this.diagBeforeSlot, this.renderDiagBefore(status, st, mode));
		var hasStats = status && status.ok === true && status.port_open && st;
		if (hasStats) {
			if (!this.paused || !this.graphNode) {
				this.graphNode = this.createGraphNode();
				dom.content(this.graphSlot, this.graphNode);
			}
		} else {
			this.graphNode = null;
			dom.content(this.graphSlot, []);
		}
		dom.content(this.diagAfterSlot, this.renderDiagAfter(status, st, mode, reply, snap));
	},

	hover: { x: null },
	paused: false,

	/* Pausing stops every read. The first statistics after resuming would
	 * cover the whole pause, so that window is dropped. */
	setPaused: function(on) {
		this.paused = on;
		this.replayingIndex = null;
		if (!on) {
			this.skipStats = true;
			this.hover.x = null;
			if (this.graphShow) this.graphShow(null);
			this.refresh(true);
		} else {
			this.redraw();
		}
	},

	/* DIAG every poll (1 s); the serving cell every other one: each read opens
	 * the QMI port, which the data connection (uqmi) shares. */
	tick: 0,
	refresh: function(force) {
		var self = this;
		if ((this.paused || !poll.active()) && force !== true) return Promise.resolve();
		var wantServing = force === true || this.tick++ % 2 === 0;
		if (modemFamily !== 'mtk' && this.hwSim !== this.sim) {
			this.hwSim = this.sim;
			nrHwBands = [];
			servingPlmn = null;
			var hwFor = this.sim;
			L.resolveDefault(callQuery(hwFor, 'query_hardware'), null).then(function(r) {
				var nr = r && r.state && r.state.hardware && r.state.hardware.nr;
				if (self.sim === hwFor && Array.isArray(nr)) nrHwBands = nr;
			});
		}
		return Promise.all([
			L.resolveDefault(callStatus(), null),
			L.resolveDefault(callStats(this.sim), null),
			!wantServing ? Promise.resolve(undefined)
				: modemFamily === 'mtk' ? L.resolveDefault(callAtServing(), { ok: false, error: { message: _('no reply') } })
				: L.resolveDefault(callServing(this.sim), { ok: false, error: { message: _('no reply') } })
		]).then(function(r) {
			var status = r[0], st = r[1] && r[1].ok === true ? r[1] : null;
			if (st && st.sim && st.sim !== self.sim && !st.sim_tags) self.sim = 1;
			if (r[2] !== undefined) self.servingReply = r[2];
			if (self.paused || !poll.active()) return;
			self.last = { status: status, st: st };
			var snap = getSnapshot(self.servingReply, st);
			if (snap && snap.plmn) servingPlmn = snap.plmn;
			var mode = snap ? snap.mode : null;
			if (self.skipStats) {
				self.skipStats = false;
			} else if (status && status.ok === true && status.port_open && st) {
				var lte = lteCarriers(st, mode), nr = nrCarriers(st, mode);
				var dl = (sum(lte, 'mbps') || 0) + (sum(nr, 'mbps') || 0);
				var ul = (sum(lte, 'ul_mbps') || 0) + (sum(nr, 'ul_mbps') || 0);
				var dlBands = bandContributions(nr.concat(lte), 'mbps');
				var nrUp = nr.filter(function(c) { return c.pcell === true || hasUplink(c); });
				var lteUp = lte.filter(function(c) { return c.slot === 0 || hasUplink(c); });
				var ulBands = bandContributions(nrUp.concat(lteUp), 'ul_mbps');
				var now = Date.now();
				self.history.push({ t: now, dl: dl, ul: ul, dl_bands: dlBands, ul_bands: ulBands });
				while (self.history.length > HISTORY) self.history.shift();
				self.rounds.push({ t: now, status: status, st: st, servingReply: self.servingReply });
				while (self.rounds.length > HISTORY) self.rounds.shift();
			}
			self.redraw();
		});
	},

	render: function() {
		var self = this;
		this.servingSlot = E('div', { 'class': 'qbm-slot-serving' });
		this.diagBeforeSlot = E('div', { 'class': 'qbm-slot-diag-before' });
		this.graphSlot = E('div', { 'class': 'qbm-slot-graph' });
		this.diagAfterSlot = E('div', { 'class': 'qbm-slot-diag-after' });
		this.root = E('div', {}, [
			this.servingSlot,
			this.diagBeforeSlot,
			this.graphSlot,
			this.diagAfterSlot
		]);
		dom.content(this.servingSlot, E('em', { 'class': 'spinning' }, _('Loading…')));
		this.onDocPointerDown = function(ev) {
			if (self.paused && self.replayingIndex != null) {
				if (self.graphSlot && !self.graphSlot.contains(ev.target)) {
					self.hover.x = null;
					if (self.graphShow) self.graphShow(null);
					self.showFrozen();
				}
			}
		};
		document.addEventListener('pointerdown', this.onDocPointerDown);
		this.onPollStop = function() {
			self.setPaused(true);
		};
		this.onPollStart = function() {
			self.setPaused(false);
		};
		document.addEventListener('poll-stop', this.onPollStop);
		document.addEventListener('poll-start', this.onPollStart);
		this.paused = !poll.active();
		poll.add(function() { return self.refresh(); }, 1);
		this.refresh(true);
		return E([], [
			E('style', {}, radio.CSS + CSS),
			E('h2', {}, _('Network')),
			this.root
		]);
	},

	reconnect: function() {
		var self = this;
		if (!confirm(_('Reconnect the modem? Airplane mode is toggled and the modem re-attaches. The connection is lost for about a minute.')))
			return;
		this.replayingIndex = null;
		return callReconnect().then(function(r) {
			if (r && r.ok) {
				ui.addNotification(null, E('p', _('Reconnecting. The interface comes back up with the new IP address in about a minute.')), 'info');
				self.refresh(true);
			} else {
				var msg = (r && r.error && (r.error.message || r.error)) || _('Reconnect failed.');
				ui.addNotification(null, E('p', msg), 'danger');
			}
		}).catch(function(e) {
			ui.addNotification(null, E('p', _('Request failed: %s').format(e.message || e)), 'danger');
		});
	},

	handleSaveApply: null,
	handleSave: null,
	handleReset: null
});
