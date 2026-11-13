'use strict';
'require baseclass';
'require qbm-diagd.radio as radio';

/*
 * qbm-diagd/serving: the serving cell card of the QBM app's Network page, for
 * LuCI. The NAS decoders (network/Qmi.kt, NasDecode.kt, NasEnums.kt) and the
 * card's model (NetworkSnapshot.kt: mode, mode label, RRC chip, serving cell
 * fields) are ported function by function. Frames come raw from qbm-qmi's
 * read-only nas_read, as the app gets them from the daemon.
 */

function FormatError(msg) { this.name = 'QmiFormatError'; this.message = msg; }
FormatError.prototype = Object.create(Error.prototype);

var INT16_INVALID = -32768, INT32_INVALID = -2147483648, UINT32_INVALID = 0xFFFFFFFF;

function hexBytes(hex) {
	if (typeof hex !== 'string' || hex.length % 2) throw new FormatError('odd hex length');
	var b = new Uint8Array(hex.length / 2);
	for (var i = 0; i < b.length; i++) {
		var v = parseInt(hex.substr(2 * i, 2), 16);
		if (isNaN(v)) throw new FormatError('bad hex digit');
		b[i] = v;
	}
	return b;
}

/* One validated NAS response: [flags][txid u16][msg u16][len u16] + TLVs. */
function parseFrame(b) {
	if (b.length < 7) throw new FormatError('frame shorter than header');
	if (b[0] !== 0x02) throw new FormatError('not a response frame');
	var msg = b[3] | b[4] << 8, len = b[5] | b[6] << 8;
	if (7 + len !== b.length) throw new FormatError('body length does not match frame size');
	var tlvs = {}, p = 7;
	while (p < b.length) {
		if (p + 3 > b.length) throw new FormatError('truncated TLV header');
		var tag = b[p], l = b[p + 1] | b[p + 2] << 8;
		if (p + 3 + l > b.length) throw new FormatError('TLV overruns frame');
		tlvs[tag] = b.subarray(p + 3, p + 3 + l);
		p += 3 + l;
	}
	var res = tlvs[2];
	if (!res) throw new FormatError('missing result TLV');
	if (res.length !== 4) throw new FormatError('result TLV length');
	var result = res[0] | res[1] << 8;
	return { msg: msg, ok: result === 0, result: result, error: res[2] | res[3] << 8,
	         tlv: function(t) { return tlvs[t] || null; } };
}

/* Bounds-checked little-endian reader over one TLV value. */
function Reader(b) { this.b = b; this.o = 0; }
Reader.prototype = {
	need: function(n) { if (this.o + n > this.b.length) throw new FormatError('TLV value too short'); },
	remaining: function() { return this.b.length - this.o; },
	u8: function() { this.need(1); return this.b[this.o++]; },
	i8: function() { var v = this.u8(); return v > 127 ? v - 256 : v; },
	u16: function() { this.need(2); var v = this.b[this.o] | this.b[this.o + 1] << 8; this.o += 2; return v; },
	i16: function() { var v = this.u16(); return v > 32767 ? v - 65536 : v; },
	u32: function() { this.need(4); var b = this.b, o = this.o; this.o += 4; return (b[o] | b[o + 1] << 8 | b[o + 2] << 16 | b[o + 3] << 24) >>> 0; },
	i32: function() { return this.u32() | 0; },
	/* As Kotlin's signed Long: exact for NCIs (36 bits); all-ones filler is -1 */
	u64: function() { var lo = this.u32(), hi = this.u32() | 0; return hi * 4294967296 + lo; },
	bytes: function(n) { this.need(n); var v = this.b.subarray(this.o, this.o + n); this.o += n; return v; },
	skip: function(n) { this.need(n); this.o += n; },
	done: function() { if (this.o !== this.b.length) throw new FormatError('TLV has unparsed bytes'); }
};

/* ── Enums (NasEnums.kt) ───────────────────────────────────────────────── */

var LTE_ENUM = [ 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 17, 33, 34, 35, 36, 37, 38, 39, 40,
	18, 19, 20, 21, 24, 25, 41, 42, 43, 23, 26, 32, 125, 126, 127, 28, 29, 30, 66, 250,
	46, 27, 31, 71, 47, 48, 67, 68, 49, 85, 72, 73, 86 ];
var NR_ENUM = [ 1, 2, 3, 5, 7, 8, 20, 28, 38, 41, 50, 51, 66, 70, 71, 74, 75, 76, 77, 78, 79, 80, 81,
	82, 83, 84, 85, 257, 258, 259, 260, 261, 12, 25, 34, 39, 40, 65, 86, 48, 14, 13, 18,
	26, 30, 29, 53 ];
var WCDMA_ENUM = { 80: 1, 81: 2, 82: 3, 83: 4, 84: 5, 85: 6, 86: 7, 87: 8, 88: 9, 90: 11, 91: 19 };
var GSM_ENUM = { 40: '450', 41: '480', 42: '750', 43: '850', 44: 'E-900', 45: 'P-900', 46: 'R-900', 47: '1800', 48: '1900' };

function bandLabel(e) {
	if (e >= 120 && e < 120 + LTE_ENUM.length) return 'B' + LTE_ENUM[e - 120];
	if (e >= 250 && e < 250 + NR_ENUM.length) return 'n' + NR_ENUM[e - 250];
	if (WCDMA_ENUM[e] != null) return 'B' + WCDMA_ENUM[e];
	if (GSM_ENUM[e] != null) return GSM_ENUM[e];
	return 'raw ' + e;
}

function lteMhz(e) { return { 0: 1.4, 1: 3, 2: 5, 3: 10, 4: 15, 5: 20 }[e]; }
function rfMhz(e) {
	return { 0: 1.4, 1: 3, 2: 5, 3: 10, 4: 15, 5: 20, 6: 5, 7: 10, 8: 15, 9: 20, 10: 25, 11: 30, 12: 40,
	         13: 50, 14: 60, 15: 80, 16: 90, 17: 100, 18: 200, 19: 400, 20: 0.2, 22: 5, 23: 10, 24: 70 }[e];
}
function nrScsKhz(e) { return { 0: 15, 1: 30, 2: 60, 3: 120, 4: 240 }[e]; }

var RRC_LTE = { 0: 'Null', 1: 'Idle (any cell)', 2: 'Idle', 3: 'Connecting', 4: 'Connected', 5: 'Releasing',
                6: 'Inactive', 7: 'Idle (not camped)', 8: 'Suspended', 9: 'IRAT to LTE' };
var RRC_NR = { 0: 'Idle', 1: 'Connected', 2: 'Inactive' };
var RRC_WCDMA = { 0: 'Idle', 1: 'CELL_PCH', 2: 'URA_PCH', 3: 'CELL_FACH', 4: 'CELL_DCH' };
function rrcText(table, v) { return table[v] != null ? table[v] : 'raw ' + v; }

var SERVICE = 2, LIMITED = 1, LIMITED_REGIONAL = 3;
var RADIO_IF = { GSM: 0x04, UMTS: 0x05, LTE: 0x08, NR5G: 0x0C };

/* ── Decoders (NasDecode.kt) ───────────────────────────────────────────── */

function asciiDigits(b) {
	var s = '';
	for (var i = 0; i < b.length && b[i] !== 0xFF; i++) s += String.fromCharCode(b[i]);
	return s.length && /^[0-9]+$/.test(s) ? s : null;
}

/* 3GPP TS 24.008 packed BCD PLMN. */
function bcdPlmn(b) {
	if (b.length !== 3) return null;
	function d(v) { return v <= 9 ? String(v) : null; }
	var mcc = [ d(b[0] & 15), d(b[0] >> 4 & 15), d(b[1] & 15) ];
	var mnc3 = b[1] >> 4 & 15;
	var mnc = [ d(b[2] & 15), d(b[2] >> 4 & 15), mnc3 === 15 ? null : d(mnc3) ].filter(function(x) { return x != null; });
	if (mcc.indexOf(null) >= 0 || mnc.length < 2) return null;
	return { mcc: mcc.join(''), mnc: mnc.join('') };
}

function plmnFrom(mccB, mncB) {
	var m = asciiDigits(mccB), n = asciiDigits(mncB);
	return m && n ? { mcc: m, mnc: n } : null;
}

function decodeSysInfo(f) {
	var status = {}, common = {};
	[ [0x12, 'GSM'], [0x13, 'WCDMA'], [0x14, 'LTE'], [0x4A, 'NR'] ].forEach(function(x) {
		var v = f.tlv(x[0]);
		if (v) { var r = new Reader(v); status[x[1]] = { status: r.u8(), trueStatus: r.u8() }; }
	});
	[ [0x17, 'GSM'], [0x18, 'WCDMA'], [0x19, 'LTE'], [0x4B, 'NR'] ].forEach(function(x) {
		var v = f.tlv(x[0]), rat = x[1];
		if (!v) return;
		if (v.length < 26) throw new FormatError('sys info common block too short');
		var r = new Reader(v);
		r.skip(4);
		var roamValid = r.u8() === 1, roam = r.u8();
		var forbValid = r.u8() === 1, forb = r.u8();
		var lacValid = r.u8() === 1, lac = r.u16();
		var cellValid = r.u8() === 1, cell = r.u32();
		r.skip(3);
		var netValid = r.u8() === 1, mcc = r.bytes(3), mnc = r.bytes(3);
		var tac = null;
		if (rat === 'LTE' && r.remaining() >= 3) { var tv = r.u8() === 1, t = r.u16(); if (tv) tac = t; }
		common[rat] = {
			roaming: roamValid ? roam !== 0 : null,
			forbidden: forbValid ? forb !== 0 : null,
			lac: lacValid && rat !== 'LTE' && rat !== 'NR' ? lac : null,
			cellId: cellValid && rat !== 'NR' ? cell : null,
			plmn: netValid ? plmnFrom(mcc, mnc) : null,
			tac: tac
		};
	});
	var primaryPlmn = null, pv = f.tlv(0x5F);
	if (pv) { var pr = new Reader(pv); pr.u8(); primaryPlmn = plmnFrom(pr.bytes(3), pr.bytes(3)); }
	function one(tag, fn) { var v = f.tlv(tag); return v ? fn(new Reader(v), v) : null; }
	return {
		status: status, common: common, primaryPlmn: primaryPlmn,
		nrPci: one(0x54, function(r) { return r.u16(); }),
		nrArfcn: one(0x60, function(r) { return r.u32(); }),
		nrScsKhz: one(0x5D, function(r) { var s = nrScsKhz(r.i32()); return s == null ? null : s; }),
		nrTac: one(0x50, function(r, v) { return v.length === 3 ? (v[0] << 16 | v[1] << 8 | v[2]) : null; }),
		nrNci: one(0x5E, function(r) { return r.u64(); }),
		endcAvailable: one(0x4E, function(r) { return r.u8() !== 0; }),
		dcnrRestricted: one(0x4F, function(r) { return r.u8() !== 0; }),
		inService: function(rat) { return !!status[rat] && status[rat].status === SERVICE; }
	};
}

function decodeSigInfo(f) {
	function one(tag, fn) { var v = f.tlv(tag); return v ? fn(new Reader(v)) : null; }
	var wcdma = one(0x13, function(r) { return [ r.i8(), r.i16() ]; });
	var lte = one(0x14, function(r) { return [ r.i8(), r.i8(), r.i16(), r.i16() ]; });
	var nr = one(0x17, function(r) { return [ r.i16(), r.i16() ]; });
	function valid(v) { return v === INT16_INVALID ? null : v; }
	return {
		gsmRssi: one(0x12, function(r) { return r.i8(); }),
		wcdmaRssiRaw: wcdma && wcdma[0], wcdmaEcioRaw: wcdma && wcdma[1],
		wcdmaRscpRaw: one(0x19, function(r) { return r.i16(); }),
		lteRssi: lte && lte[0], lteRsrq: lte && lte[1], lteRsrp: lte && lte[2],
		lteSnr: lte && valid(lte[3]) != null ? lte[3] / 10 : null,
		nrRsrp: nr ? valid(nr[0]) : null,
		nrSnr: nr && valid(nr[1]) != null ? nr[1] / 10 : null,
		nrRsrq: one(0x18, function(r) { return valid(r.i16()); })
	};
}

function lteMeas(r) {
	var m = { pci: r.u16(), rsrq: r.i16() / 10, rsrp: r.i16() / 10, rssi: r.i16() / 10 };
	r.i16();   /* srxlev */
	return m;
}

function decodeCellLocation(f) {
	var lte = null, v = f.tlv(0x13);
	if (v) {
		var r = new Reader(v);
		var idle = r.u8() !== 0, plmn = r.bytes(3), tac = r.u16(), eci = r.u32(), earfcn = r.u16(), pci = r.u16();
		r.skip(4);
		var count = r.u8(), e = f.tlv(0x27);
		if (e) earfcn = new Reader(e).u32();
		var meas = [];
		for (var i = 0; i < count; i++) meas.push(lteMeas(r));
		r.done();
		lte = { idle: idle, plmn: bcdPlmn(plmn), tac: tac, eci: eci, earfcn: earfcn, pci: pci, intraFreq: meas };
	}
	var inter = [];
	v = f.tlv(0x14);
	if (v) {
		var ri = new Reader(v); ri.u8();
		var n = ri.u8(), extV = f.tlv(0x28), ext = null;
		if (extV) { ext = new Reader(extV); if (ext.u8() !== n) throw new FormatError('LTE inter-freq count mismatch'); }
		for (var g = 0; g < n; g++) {
			var ef = ri.u16(); ri.skip(3);
			var cells = ri.u8();
			if (ext) ef = ext.u32();
			var list = [];
			for (var c = 0; c < cells; c++) list.push(lteMeas(ri));
			inter.push({ earfcn: ef, cells: list });
		}
		ri.done(); if (ext) ext.done();
	}
	var nr = null;
	v = f.tlv(0x2F);
	if (v) {
		var rn = new Reader(v);
		var np = rn.bytes(3), tb = rn.bytes(3), nci = rn.u64(), npci = rn.u16();
		var rsrq = rn.i16(), rsrp = rn.i16(), snr = rn.i16();
		rn.done();
		nr = { plmn: bcdPlmn(np), tac: tb[0] << 16 | tb[1] << 8 | tb[2], nci: nci, pci: npci, rsrq: rsrq / 10, rsrp: rsrp / 10, snr: snr / 10 };
	}
	var wcdma = null;
	v = f.tlv(0x11);
	if (v) {
		var rw = new Reader(v);
		var cid = rw.u16(), wp = rw.bytes(3), lac = rw.u16(), uarfcn = rw.u16(), psc = rw.u16();
		var rscp = rw.i16(), ecio = rw.i16(), wn = rw.u8(), nb = [];
		for (var k = 0; k < wn; k++) nb.push({ uarfcn: rw.u16(), psc: rw.u16(), rscp: rw.i16(), ecio: rw.i16() });
		var geran = rw.u8(); rw.skip(geran * 6);
		rw.done();
		var c32 = f.tlv(0x17);
		if (c32) cid = new Reader(c32).u32();
		wcdma = { cellId: cid, plmn: bcdPlmn(wp), lac: lac, uarfcn: uarfcn, psc: psc, rscp: rscp, ecio: ecio, neighbors: nb };
	}
	var gsm = null;
	v = f.tlv(0x10);
	if (v) {
		var rg = new Reader(v);
		var gcid = rg.u32(), gp = rg.bytes(3), glac = rg.u16(), garfcn = rg.u16(), bsic = rg.u8();
		var ta = rg.u32(), rxlev = rg.u16(), gn = rg.u8(), gnb = [];
		for (var q = 0; q < gn; q++) {
			var ncid = rg.u32(), npl = rg.bytes(3), nlac = rg.u16(), nar = rg.u16(), nbs = rg.u8(), nrx = rg.u16();
			gnb.push({ cellId: ncid === UINT32_INVALID ? null : ncid, plmn: bcdPlmn(npl), lac: ncid === UINT32_INVALID ? null : nlac,
			           arfcn: nar, bsic: nbs === 0xFF ? null : nbs, rxlev: nrx });
		}
		rg.done();
		gsm = {
			cell: { cellId: gcid === UINT32_INVALID ? null : gcid, plmn: bcdPlmn(gp), lac: gcid === UINT32_INVALID ? null : glac,
			        arfcn: garfcn, bsic: bsic === 0xFF ? null : bsic, rxlev: rxlev },
			timingAdvance: ta === UINT32_INVALID ? null : ta, neighbors: gnb
		};
	}
	var tav = f.tlv(0x1E), nav = f.tlv(0x2E);
	var taUs = tav ? new Reader(tav).i32() : null;
	return {
		lte: lte, lteInterFreq: inter,
		lteTimingAdvanceUs: taUs === -1 ? null : taUs,
		nrArfcn: nav ? new Reader(nav).u32() : null,
		nr: nr, wcdma: wcdma, gsm: gsm
	};
}

function decodeRfBandInfo(f) {
	var ext = f.tlv(0x11), v = ext || f.tlv(0x01);
	if (!v) return [];
	var r = new Reader(v), n = r.u8(), bands = [];
	for (var i = 0; i < n; i++) bands.push([ r.u8(), r.u16(), ext ? r.u32() : r.u16() ]);
	r.done();
	var widths = {}, w = f.tlv(0x12);
	if (w) {
		var rw = new Reader(w), c = rw.u8();
		for (var k = 0; k < c; k++) { var rat = rw.u8(); widths[rat] = rw.i32(); }
		rw.done();
	}
	return bands.map(function(b) {
		var mhz = widths[b[0]] != null ? rfMhz(widths[b[0]]) : null;
		return { radioIf: b[0], bandEnum: b[1], channel: b[2], bandwidthMhz: mhz == null ? null : mhz };
	});
}

function decodeArfcnList(f) {
	function lteList(tag) {
		var v = f.tlv(tag); if (!v) return null;
		var r = new Reader(v), c = r.u8(), out = [];
		for (var i = 0; i < c; i++) {
			var dlOnly = r.u8() !== 0, dl = r.u32(), ul = r.u32(), bw = lteMhz(r.i32());
			out.push({ downlinkOnly: dlOnly, dlEarfcn: dl, ulEarfcn: ul, bandwidthMhz: bw == null ? null : bw });
		}
		r.done();
		return out;
	}
	function u32List(tag) {
		var v = f.tlv(tag); if (!v) return null;
		var r = new Reader(v), c = r.u8(), out = [];
		for (var i = 0; i < c; i++) out.push(r.u32());
		r.done();
		return out;
	}
	function one(tag, fn) { var v = f.tlv(tag); return v ? fn(new Reader(v)) : null; }
	return {
		lteServing: lteList(0x18), lteNeighbor: lteList(0x1A),
		lteRrc: one(0x19, function(r) { return r.i32(); }),
		nrRrc: one(0x1B, function(r) { return r.i32(); }),
		nrServing: u32List(0x1C), nrNeighbor: u32List(0x1D),
		wcdmaServingUarfcn: one(0x14, function(r) { return r.u16(); }),
		wcdmaRrc: one(0x16, function(r) { return r.i32(); }),
		gsmServingArfcn: one(0x10, function(r) { return r.u16(); })
	};
}

function decodeLteCaInfo(f) {
	var pcell = null, v = f.tlv(0x13);
	if (v) {
		var r = new Reader(v), pci = r.u16(), ch = r.u16(), bw = r.i32(), band = r.u16(), e = f.tlv(0x16);
		if (e) ch = new Reader(e).u32();
		var m = lteMhz(bw);
		pcell = { pci: pci, earfcn: ch, bandwidthMhz: m == null ? null : m, bandEnum: band, state: null, index: null, uplinkConfigured: null };
	}
	var scells = [];
	v = f.tlv(0x19);
	if (v) {
		var rs = new Reader(v), c = rs.u8();
		for (var i = 0; i < c; i++) {
			var spci = rs.u16(), sch = rs.u32(), sbw = rs.i32(), sband = rs.u16(), st = rs.i32(), idx = rs.u8(), ul = rs.u8();
			var sm = lteMhz(sbw);
			scells.push({ pci: spci, earfcn: sch, bandwidthMhz: sm == null ? null : sm, bandEnum: sband,
			              state: [ 'DECONFIGURED', 'CONFIGURED', 'ACTIVATED' ][st] || 'UNKNOWN', index: idx, uplinkConfigured: ul !== 0 });
		}
		rs.done();
	}
	return { pcell: pcell, scells: scells };
}

function decodeErrRate(f) {
	function one(tag) { var v = f.tlv(tag); if (!v) return null; var x = new Reader(v).u8(); return x === 0xFF ? null : x; }
	return [ one(0x12), one(0x13) ];
}

function printableAscii(b) {
	var s = '';
	for (var i = 0; i < b.length; i++) s += String.fromCharCode(b[i]);
	s = s.replace(/^[\u0000- ]+|[\u0000- ]+$/g, '');
	return s.length && /^[\x20-\x7e]+$/.test(s) ? s : null;
}

function decodeName(enc, b) {
	if (!b.length) return null;
	if (enc === 1) {
		/* UCS-2: broken surrogates and an odd last byte become U+FFFD, as Java's decoder does */
		var s = new TextDecoder('utf-16be').decode(b).trim();
		return s.length ? s : null;
	}
	return enc === 0 ? printableAscii(b) : null;
}

function decodeHomeNetwork(f) {
	var v = f.tlv(0x01); if (!v) return null;
	var r = new Reader(v), mcc = r.u16(), mnc = r.u16(), n = r.u8(), desc = r.bytes(n);
	r.done();
	return { mcc: mcc, mnc: mnc, description: printableAscii(desc) };
}

function decodePlmnName(f) {
	var v = f.tlv(0x10); if (!v) return null;
	var r = new Reader(v);
	var spnEnc = r.u8(), spn = r.bytes(r.u8());
	var sEnc = r.u8(); r.skip(2); var short = r.bytes(r.u8());
	var lEnc = r.u8(); r.skip(2); var long = r.bytes(r.u8());
	r.done();
	return { spn: decodeName(spnEnc, spn), shortName: decodeName(sEnc, short), longName: decodeName(lEnc, long) };
}

/* ── Formatting (NetworkSnapshot.kt) ───────────────────────────────────── */

function fmt(v) { return v % 1 === 0 ? String(v) : v.toFixed(1); }
function mhz(v) { return fmt(v) + ' MHz'; }
function plmnText(p) { return p ? p.mcc + '-' + p.mnc : null; }

/* gNB / cell from an NCI, the gNB ID taking gnbBits of its 36 bits. */
function formatNrNci(nci, gnbBits) {
	var cellBits = Math.min(14, Math.max(4, 36 - (gnbBits || 22))), div = Math.pow(2, cellBits);
	return Math.floor(nci / div) + ' / ' + (nci % div);
}

var MODES = {
	UNKNOWN: [ 'Unavailable', '' ], NO_SERVICE: [ 'No service', '' ], LIMITED: [ 'Limited service', '' ],
	GSM: [ 'GSM', '2G' ], WCDMA: [ 'WCDMA', '3G' ], LTE: [ 'LTE', '4G' ], NR_NSA: [ 'NR NSA', '5G' ], NR_SA: [ 'NR SA', '5G' ]
};

/*
 * The card's model from one round: reads is { '004d': frame-or-null, ... }
 * (null: the read failed); diag the same round's qbm-diagd stats, or null.
 */
function snapshot(reads, diag, gnbBits, nrSupported) {
	var failed = [];
	/* reads[key]: undefined = not read this round, null = the read failed. */
	function decode(key, fn) {
		if (!(key in reads)) return null;
		var f = reads[key];
		if (!f) { failed.push(key); return null; }
		if (!f.ok) { if (key === '004d') failed.push(key); return null; }
		try { return fn(f); } catch (e) { if (e instanceof FormatError) { failed.push(key); return null; } throw e; }
	}
	var sys = decode('004d', decodeSysInfo);
	var sig = decode('004f', decodeSigInfo);
	var loc = decode('0043', decodeCellLocation);
	var rf = decode('0031', decodeRfBandInfo) || [];
	var arfcn = decode('00d2', decodeArfcnList);
	var ca = decode('00ac', decodeLteCaInfo);
	var err = decode('0052', decodeErrRate);
	var dLte = (diag && diag.carriers) || [], dNr = (diag && diag.nr) || [];

	/* A band derived from the NR-ARFCN. With DIAG on (diag non-null) an ARFCN
	 * two bands share (n77/78, n38/41, n5/26) gives none, until the modem
	 * names the band, or the modem supports only one of the two (nrSupported);
	 * without DIAG it's the pair ("n77/78"). n77/78 is the exception: the
	 * PLMN's usual band stands in (radio.nrBandGuess) until the modem names it. */
	function nrBandFromArfcn(ch) {
		var l = radio.nrBandLabel(ch, nrSupported);
		if (!l || l.indexOf('/') < 0) return l;
		var g = radio.nrBandGuess(ch, sys ? (sys.primaryPlmn || (common && common.plmn) || null) : null);
		return g ? 'n' + g : diag ? null : l;
	}

	function rfFor(radioIf) { return rf.filter(function(b) { return b.radioIf === radioIf; })[0] || null; }

	var mode = (function() {
		if (!sys) return 'UNKNOWN';
		var nrMeasured = (sig && (sig.nrRsrp != null || sig.nrRsrq != null)) ||
			dNr.some(function(c) { return c.rsrp != null || c.rsrq != null; });
		var nrLeg = nrMeasured && (arfcn ? ((arfcn.nrServing && arfcn.nrServing.length > 0) || arfcn.nrRrc != null) : sys.nrPci != null);
		if (sys.inService('LTE') && nrLeg) return 'NR_NSA';
		if (sys.inService('LTE')) return 'LTE';
		if (sys.inService('NR')) return 'NR_SA';
		if (sys.inService('WCDMA')) return 'WCDMA';
		if (sys.inService('GSM')) return 'GSM';
		for (var k in sys.status)
			if (sys.status[k].status === LIMITED || sys.status[k].status === LIMITED_REGIONAL) return 'LIMITED';
		return 'NO_SERVICE';
	})();
	var servingRat = { LTE: 'LTE', NR_NSA: 'LTE', NR_SA: 'NR', WCDMA: 'WCDMA', GSM: 'GSM' }[mode] || null;
	var common = servingRat && sys ? sys.common[servingRat] || null : null;

	var modeLabel = MODES[mode][0];
	if (mode === 'LTE' && ca && ca.pcell) {
		var n = 1 + ca.scells.filter(function(s) { return s.state === 'CONFIGURED' || s.state === 'ACTIVATED'; }).length;
		if (n >= 2) modeLabel += ' ' + n + 'xCA';
	}

	var rrc = null;
	if (arfcn) {
		if ((mode === 'LTE' || mode === 'NR_NSA') && arfcn.lteRrc != null) rrc = rrcText(RRC_LTE, arfcn.lteRrc);
		else if (mode === 'NR_SA' && arfcn.nrRrc != null) rrc = rrcText(RRC_NR, arfcn.nrRrc);
		else if (mode === 'WCDMA' && arfcn.wcdmaRrc != null) rrc = rrcText(RRC_WCDMA, arfcn.wcdmaRrc);
	}

	function row(list, label, v) { list.push({ label: label, value: v == null ? '–' : String(v) }); }

	function lteCell() {
		var l = loc && loc.lte, pcell = ca && ca.pcell;
		var pci = l ? l.pci : pcell ? pcell.pci : null;
		var earfcn = l ? l.earfcn : pcell ? pcell.earfcn : null;
		var band = rfFor(RADIO_IF.LTE);
		/* DIAG's PCell for this cell (forPcell): slot 0, same EARFCN and PCI */
		var d = earfcn != null && pci != null ? dLte.filter(function(c) {
			return c.slot === 0 && (c.earfcn == null || (c.earfcn === earfcn && c.pci === pci));
		})[0] : null;
		var f = [];
		row(f, 'PCI', pci);
		row(f, 'TAC', l ? l.tac : (sys && sys.common.LTE ? sys.common.LTE.tac : null));
		row(f, 'TA', diag ? diag.lte_ta : null);
		var be = band ? band.bandEnum : pcell ? pcell.bandEnum : null;
		row(f, 'Band', be != null ? bandLabel(be) : null);
		var bw = d && d.bw != null ? d.bw : pcell && pcell.bandwidthMhz != null ? pcell.bandwidthMhz : band ? band.bandwidthMhz : null;
		row(f, 'Bandwidth', bw != null ? mhz(bw) : null);
		row(f, 'EARFCN', earfcn);
		function eciOf(v) { return v != null && v >= 0 && v <= 0x0FFFFFFF ? v : null; }
		var eci = eciOf(l && l.eci);
		if (eci == null) eci = eciOf(sys && sys.common.LTE ? sys.common.LTE.cellId : null);
		if (eci != null) f.push({ label: 'eNB/Cell', value: Math.floor(eci / 256) + '/' + (eci % 256) });
		var fr = earfcn != null ? radio.lteFreq(earfcn) : null;
		if (fr) f.push({ label: 'DL / UL', value: fmt(fr.dl) + ' / ' + (fr.ul != null ? fmt(fr.ul) : '—') + ' MHz' });
		return { title: mode === 'NR_NSA' ? 'LTE MCG' : 'LTE primary cell', rat: 'LTE', fields: f };
	}

	function nrCell(sa) {
		var n = loc && loc.nr;
		var pci = sys && sys.nrPci != null ? sys.nrPci : n ? n.pci : null;
		var ch = sys && sys.nrArfcn != null ? sys.nrArfcn : loc && loc.nrArfcn != null ? loc.nrArfcn :
			arfcn && arfcn.nrServing && arfcn.nrServing.length ? arfcn.nrServing[0] : null;
		var band = rfFor(RADIO_IF.NR5G);
		var d = dNr.filter(function(c) { return c.pci === pci && (c.arfcn === ch || c.dl_arfcn === ch); })[0] || null;
		var bandText = band ? bandLabel(band.bandEnum)
			: d && d.band > 0 && radio.nrBandFits(d.band, d.arfcn) ? 'n' + d.band
			: ch != null ? nrBandFromArfcn(ch) : null;
		var freq = ch != null && radio.nrFreq(ch) != null ? mhz(radio.nrFreq(ch)) : null;
		var nci = sys && sys.nrNci != null ? sys.nrNci : n ? n.nci : null;
		if (nci != null && !(nci >= 0 && nci <= 0xFFFFFFFFF)) nci = null;
		var f = [];
		function put(label, v) { if (v != null) f.push({ label: label, value: String(v) }); else if (!sa) f.push({ label: label, value: '–' }); }
		var bw = d && d.bw != null ? d.bw : band ? band.bandwidthMhz : null;
		if (sa) {
			row(f, 'Band', bandText);
			row(f, 'Bandwidth', bw != null ? mhz(bw) : null);
			row(f, 'NARFCN', ch);
			row(f, 'SCS', sys && sys.nrScsKhz != null ? sys.nrScsKhz + ' kHz' : null);
			row(f, 'Frequency', freq);
			row(f, 'TA', diag ? diag.nr_ta : null);
			row(f, 'PCI', pci);
			row(f, 'TAC', sys && sys.nrTac != null ? sys.nrTac : n ? n.tac : null);
			row(f, 'gNB / Cell', nci != null ? formatNrNci(nci, gnbBits) : null);
		} else {
			put('Band', bandText);
			put('NARFCN', ch);
			put('Frequency', freq);
			put('PCI', pci);
			if (diag) put('TA', diag.nr_ta);
			else put('SCS', sys && sys.nrScsKhz != null ? sys.nrScsKhz + ' kHz' : d && d.scs ? d.scs + ' kHz' : null);
			if (diag) put('Bandwidth', bw != null ? mhz(bw) : null);
			else if (bw != null) f.push({ label: 'Bandwidth', value: mhz(bw) });
		}
		return { title: sa ? 'NR primary cell' : 'NR SCG', rat: 'NR', fields: f, nci: sa ? nci : null };
	}

	function wcdmaCell() {
		var w = loc && loc.wcdma, band = rfFor(RADIO_IF.UMTS);
		var uarfcn = w ? w.uarfcn : arfcn ? arfcn.wcdmaServingUarfcn : null;
		var f = [];
		var b = band ? bandLabel(band.bandEnum) : uarfcn != null && radio.wcdmaBand(uarfcn) != null ? 'B' + radio.wcdmaBand(uarfcn) : null;
		if (b) f.push({ label: 'Band', value: b });
		if (uarfcn != null) f.push({ label: 'UARFCN', value: String(uarfcn) });
		if (uarfcn != null && radio.wcdmaFreq(uarfcn) != null) f.push({ label: 'Frequency', value: mhz(radio.wcdmaFreq(uarfcn)) });
		if (w) {
			f.push({ label: 'PSC', value: String(w.psc) });
			f.push({ label: 'Cell ID', value: Math.floor(w.cellId / 65536) + ' / ' + (w.cellId % 65536) });
			f.push({ label: 'LAC', value: String(w.lac) });
		}
		if (err && err[1] != null) f.push({ label: 'BLER', value: err[1] + ' %' });
		if (!f.length && !w) return null;
		return { title: 'WCDMA serving cell', rat: 'WCDMA', fields: f };
	}

	function gsmCell() {
		var g = loc && loc.gsm, c = g && g.cell, band = rfFor(RADIO_IF.GSM), f = [];
		if (c && c.bsic != null) f.push({ label: 'BSIC', value: c.bsic + ' (NCC ' + (c.bsic >> 3) + ', BCC ' + (c.bsic & 7) + ')' });
		var a = c ? c.arfcn : arfcn ? arfcn.gsmServingArfcn : null;
		if (a != null) f.push({ label: 'ARFCN', value: String(a) });
		if (band) f.push({ label: 'Band', value: bandLabel(band.bandEnum) });
		if (c && c.lac != null) f.push({ label: 'LAC', value: String(c.lac) });
		if (c && c.cellId != null) f.push({ label: 'Cell ID', value: String(c.cellId) });
		if (err && err[0] != null) f.push({ label: 'RxQual', value: String(err[0]) });
		if (!f.length && !g) return null;
		return { title: 'GSM serving cell', rat: 'GSM', fields: f };
	}

	var cells = [];
	if (mode === 'LTE') cells.push(lteCell());
	else if (mode === 'NR_NSA') { cells.push(lteCell()); cells.push(nrCell(false)); }
	else if (mode === 'NR_SA') cells.push(nrCell(true));
	else if (mode === 'WCDMA') cells.push(wcdmaCell());
	else if (mode === 'GSM') cells.push(gsmCell());
	cells = cells.filter(function(c) { return c; });

	return {
		mode: mode, modeLabel: modeLabel, inService: MODES[mode][1] !== '',
		rrc: rrc,
		plmn: sys ? (sys.primaryPlmn || (common && common.plmn) || null) : null,
		roaming: common ? common.roaming : null,
		cells: cells,
		loc: loc,
		failedReads: failed
	};
}

/*
 * The same card's model for a modem without QMI (Fibocom FM350), from one AT
 * round: reply.replies = [ AT+COPS?, AT+GTCCINFO? ]. GTCCINFO serving lines
 * (Fibocom AT manual 11.1.15):
 *   NR:  1,9,mcc,mnc,tac,cellid,narfcn,pci,band,bandwidth,ss_sinr,rxlev,ss_rsrp,ss_rsrq
 *   LTE: 1,4,mcc,mnc,tac,cellid,earfcn,pci,band,bandwidth,rssnr,rxlev,rsrp,rsrq
 * tac and cellid are hex; bands are GTACT codes (103 = B3, 5041 = n41);
 * bandwidth is in 0.2 MHz (300 = 60 MHz). The operator name is COPS's.
 */
function atLines(reply) {
	return String(reply || '').split('\n').map(function(l) { return l.trim(); }).filter(Boolean);
}

function snapshotAt(reply, diag, gnbBits, nrSupported) {
	var failed = [];
	if (!reply || !reply.ok || !reply.replies) failed.push('AT');
	var replies = (reply && reply.replies) || [];
	var cops = atLines(replies[0]).map(function(l) { return /^\+COPS:\s*\d+,\d+,"([^"]*)"/.exec(l); }).filter(Boolean)[0];
	var lte = null, nr = null;
	atLines(replies[1]).forEach(function(l) {
		var f = l.split(',');
		if (f.length < 14 || f[0] !== '1') return;
		var c = { mcc: f[2], mnc: f[3], tac: parseInt(f[4], 16), cellId: parseInt(f[5], 16), ch: +f[6], pci: +f[7],
		          band: +f[8], bwMhz: +f[9] > 0 && +f[9] < 255 * 5 ? +f[9] / 5 : null, sinr: +f[10] };
		if (f[1] === '9' && !nr) nr = c;
		else if (f[1] === '4' && !lte) lte = c;
	});
	var dLte = (diag && diag.carriers) || [], dNr = (diag && diag.nr) || [];
	var mode = lte && nr ? 'NR_NSA' : lte ? 'LTE' : nr ? 'NR_SA' : failed.length ? 'UNKNOWN' : cops ? 'LIMITED' : 'NO_SERVICE';
	var serving = lte || nr;
	var plmn = serving && /^\d{3}$/.test(serving.mcc) && /^\d{1,3}$/.test(serving.mnc)
		? { mcc: serving.mcc, mnc: serving.mnc.length < 2 ? ('0' + serving.mnc) : serving.mnc } : null;

	function row(list, label, v) { list.push({ label: label, value: v == null ? '–' : String(v) }); }

	function lteCell() {
		var d = dLte.filter(function(c) { return c.slot === 0; })[0] || null;
		var f = [];
		row(f, 'PCI', lte.pci);
		row(f, 'TAC', isNaN(lte.tac) ? null : lte.tac);
		row(f, 'TA', diag ? diag.lte_ta : null);
		row(f, 'Band', lte.band > 100 && lte.band < 200 ? 'B' + (lte.band - 100) : null);
		var bw = d && d.bw != null ? d.bw : lte.bwMhz;
		row(f, 'Bandwidth', bw != null ? mhz(bw) : null);
		row(f, 'EARFCN', lte.ch);
		if (!isNaN(lte.cellId) && lte.cellId >= 0 && lte.cellId <= 0x0FFFFFFF)
			f.push({ label: 'eNB/Cell', value: Math.floor(lte.cellId / 256) + '/' + (lte.cellId % 256) });
		var fr = radio.lteFreq(lte.ch);
		if (fr) f.push({ label: 'DL / UL', value: fmt(fr.dl) + ' / ' + (fr.ul != null ? fmt(fr.ul) : '—') + ' MHz' });
		return { title: mode === 'NR_NSA' ? 'LTE MCG' : 'LTE primary cell', rat: 'LTE', fields: f };
	}

	function nrCell(sa) {
		var d = dNr.filter(function(c) { return c.pci === nr.pci && (c.arfcn === nr.ch || c.dl_arfcn === nr.ch); })[0] || null;
		var code = String(nr.band);
		var bandText = /^50\d+$/.test(code) ? 'n' + code.slice(2) : d && d.band > 0 ? 'n' + d.band : radio.nrBandLabel(nr.ch, nrSupported);
		var freq = radio.nrFreq(nr.ch) != null ? mhz(radio.nrFreq(nr.ch)) : null;
		var nci = !isNaN(nr.cellId) && nr.cellId >= 0 && nr.cellId <= 0xFFFFFFFFF ? nr.cellId : null;
		var bw = d && d.bw != null ? d.bw : nr.bwMhz;
		var f = [];
		row(f, 'Band', bandText);
		row(f, 'Bandwidth', bw != null ? mhz(bw) : null);
		row(f, 'NARFCN', nr.ch);
		row(f, 'SCS', d && d.scs ? d.scs + ' kHz' : null);
		row(f, 'Frequency', freq);
		row(f, 'TA', diag ? diag.nr_ta : null);
		row(f, 'PCI', nr.pci);
		if (sa) {
			row(f, 'TAC', isNaN(nr.tac) ? null : nr.tac);
			row(f, 'gNB / Cell', nci != null ? formatNrNci(nci, gnbBits) : null);
		}
		return { title: sa ? 'NR primary cell' : 'NR SCG', rat: 'NR', fields: f, nci: sa ? nci : null };
	}

	var cells = [];
	if (lte) cells.push(lteCell());
	if (nr) cells.push(nrCell(!lte));
	var modeLabel = MODES[mode][0];
	/* the count is the LTE carrier columns the CA card shows (radio.validLteCa) */
	var nCa = (mode === 'NR_SA' ? dNr : dLte.filter(radio.validLteCa)).length;
	/* FM350: NSA carries the LTE anchor plus the NR cell; the CA count there
	 * is just noise, so the label stays "NR NSA". */
	if (nCa >= 2 && mode !== 'NR_NSA') modeLabel += ' ' + nCa + 'xCA';

	return {
		mode: mode, modeLabel: modeLabel, inService: MODES[mode][1] !== '',
		rrc: null,
		plmn: plmn,
		roaming: null,
		operatorName: cops ? cops[1] : null,
		cells: cells,
		loc: null,
		failedReads: failed
	};
}

/* The operator name for a PLMN, from GET_PLMN_NAME and GET_HOME_NETWORK
 * (NetworkMonitor.kt readOperatorName): the PLMN's name, else the home
 * network's when this PLMN is home or we're not roaming; a name that's only
 * digits is the modem echoing the PLMN, not a name. */
function operatorName(plmn, roaming, homeFrame, nameFrame) {
	function numeric(s) { return /^[0-9 \-]*$/.test(s); }
	var home = null, pn = null;
	try { home = homeFrame && homeFrame.ok ? decodeHomeNetwork(homeFrame) : null; } catch (e) {}
	try { pn = nameFrame && nameFrame.ok ? decodePlmnName(nameFrame) : null; } catch (e) {}
	var isHome = home != null && plmn && home.mcc === +plmn.mcc && home.mnc === +plmn.mnc;
	var names = pn ? [ pn.longName, pn.shortName, pn.spn ].filter(function(x) { return x && !numeric(x); }) : [];
	if (names.length) return names[0];
	if (home && (isHome || roaming === false) && home.description && !numeric(home.description)) return home.description;
	return null;
}

/* The frames of a qbm-qmi nas_read reply, by read key ('004d', ...). */
function framesOf(reply) {
	var out = {};
	var reads = reply && reply.ok && reply.nas_read && reply.nas_read.reads || [];
	reads.forEach(function(r) {
		var key = ('000' + r.msg.toString(16)).slice(-4);
		if (r.status !== 'ok' || !r.rx) { out[key] = null; return; }
		try { out[key] = parseFrame(hexBytes(r.rx)); } catch (e) { out[key] = null; }
	});
	return out;
}

return baseclass.extend({
	FormatError: FormatError,
	hexBytes: hexBytes, parseFrame: parseFrame, bandLabel: bandLabel, bcdPlmn: bcdPlmn,
	decodeSysInfo: decodeSysInfo, decodeSigInfo: decodeSigInfo, decodeCellLocation: decodeCellLocation,
	decodeRfBandInfo: decodeRfBandInfo, decodeArfcnList: decodeArfcnList, decodeLteCaInfo: decodeLteCaInfo,
	decodeHomeNetwork: decodeHomeNetwork, decodePlmnName: decodePlmnName, decodeErrRate: decodeErrRate,
	formatNrNci: formatNrNci, plmnText: plmnText,
	snapshot: snapshot, snapshotAt: snapshotAt, operatorName: operatorName, framesOf: framesOf
});
