'use strict';
'require baseclass';

/*
 * qbm-diagd/radio: the QBM app's signal scales, colours and table bars for
 * LuCI, ported from the Android app (ui/SignalBar.kt, ui/NetworkScreen.kt,
 * network/RadioFrequencies.kt). The band tables are converted from the
 * Kotlin source, not retyped.
 */

/* Five quality levels, best first (SignalBar.kt LEVEL_COLORS). */
var LEVEL = [ '#1E9E50', '#7CB342', '#EAB01F', '#F07E26', '#E0453A' ];

/* min, max, the four thresholds between the levels; worse: a higher value is
 * worse (TX power, BLER). */
var SCALES = {
	rsrp: { min: -140, max: -60, t: [ -85, -95, -105, -115 ] },
	rsrq: { min: -25,  max: -3,  t: [ -6, -10, -15, -20 ] },
	sinr: { min: -10,  max: 35,  t: [ 22, 15, 10, 3 ] },
	tx:   { min: -27,  max: 26,  t: [ 0, 10, 17, 23 ], worse: true },
	bler: { min: 0,    max: 30,  t: [ 2, 10, 15, 25 ], worse: true }
};

/* Per-band display colour (NetworkScreen.kt BAND_COLORS). */
var BAND_COLORS = {
	1:'#EE8700',2:'#3F70EE',3:'#81A83D',4:'#FF01FC',5:'#DFCA02',6:'#BF9309',7:'#CB0481',8:'#A96B2F',
	9:'#5FEE1D',10:'#077E1E',11:'#49CDF0',12:'#8B1CC3',13:'#CD6B95',14:'#0FBB67',15:'#510A3A',
	16:'#93590C',17:'#D5A8DE',18:'#17F8B0',19:'#594783',20:'#3DF7F0',21:'#476668',22:'#89B53A',
	23:'#CB040D',24:'#0D54DF',25:'#4FA3B1',26:'#91F283',27:'#D34156',28:'#1CAF8C',29:'#57E0FA',
	30:'#03B00D',31:'#45FFDF',32:'#9400BF',33:'#C99D84',34:'#0BED56',35:'#4D3C29',36:'#8F8BFB',
	37:'#D1DACD',38:'#798CED',39:'#557972',40:'#117FDC',41:'#439857',42:'#85E729',43:'#C736FC',
	44:'#0986CE',45:'#4BD5A0',46:'#0C00F2',47:'#CF7345',48:'#11C317',49:'#5312EA',50:'#FFE1FC',
	51:'#4131CF',52:'#8380A1',53:'#C5CF73',54:'#071F46',55:'#496E18',56:'#8BBDEA',57:'#CD0CBD',
	58:'#0F5C8F',59:'#51AB61',60:'#FD7A74',61:'#3FCA46',62:'#811919',63:'#C368EB',64:'#05B8BD',
	65:'#470790',66:'#D18799',67:'#CBA534',68:'#0DF506',69:'#4F44D9',70:'#FB13EC',71:'#3D63BE',
	72:'#7FB290',73:'#C10163',74:'#035135',75:'#45A007',76:'#87EFD9',77:'#C93EAC',78:'#DD0000',
	79:'#4DDD50',80:'#F9AC63',81:'#3BFC35',82:'#7D4B08',83:'#BF9ADA',84:'#01EAAC',85:'#43397F',
	86:'#858851',87:'#C7D723',88:'#0927F6',89:'#4B76C8',90:'#F745DB',91:'#3995AD',92:'#7BE47F',
	93:'#BD3352',94:'#FF8224',95:'#41D2F6',96:'#8321C9',97:'#C5709B',98:'#07C06D',99:'#490F40',
	100:'#DFC84B',101:'#FF5DC3',102:'#1FF33A',103:'#3F88B2',104:'#5F1D2A',105:'#7FB2A1',106:'#9F4719',
	107:'#BFDC90',108:'#DF7108',109:'#FF0680',110:'#BFD7C6',125:'#3FD097',126:'#5F650F',127:'#7FFA86',
	250:'#5FE099',251:'#7F7511',252:'#9F0A89',253:'#BF9F00',254:'#DF3478',255:'#FFC9EF',256:'#1F5F67',
	257:'#FF8B66',258:'#FF8B66',259:'#7F1ECE',260:'#3FEF14',261:'#5F848C',262:'#7F1904',263:'#9FAE7B'
};

/* LTE: [band, first EARFCN, last EARFCN, DL MHz at the first, UL MHz or null]
 * (RadioFrequencies.kt LTE_BAND_PLANS). */
var LTE_PLANS = [
	[1,0,599,2110,1920],[2,600,1199,1930,1850],[3,1200,1949,1805,1710],[4,1950,2399,2110,1710],
	[5,2400,2649,869,824],[6,2650,2749,875,830],[7,2750,3449,2620,2500],[8,3450,3799,925,880],
	[9,3800,4149,1844.9,1749.9],[10,4150,4749,2110,1710],[11,4750,4949,1475.9,1427.9],[12,5010,5179,729,699],
	[13,5180,5279,746,777],[14,5280,5379,758,788],[17,5730,5849,734,704],[18,5850,5999,860,815],
	[19,6000,6149,875,830],[20,6150,6449,791,832],[21,6450,6599,1495.9,1447.9],[22,6600,7399,3510,3410],
	[23,7500,7699,2180,2000],[24,7700,8039,1525,1626.5],[25,8040,8689,1930,1850],[26,8690,9039,859,814],
	[27,9040,9209,852,807],[28,9210,9659,758,703],[29,9660,9769,717,null],[30,9770,9869,2350,2305],
	[31,9870,9919,462.5,452.5],[32,9920,10359,1452,null],[33,36000,36199,1900,1900],[34,36200,36349,2010,2010],
	[35,36350,36949,1850,1850],[36,36950,37549,1930,1930],[37,37550,37749,1910,1910],[38,37750,38249,2570,2570],
	[39,38250,38649,1880,1880],[40,38650,39649,2300,2300],[41,39650,41589,2496,2496],[42,41590,43589,3400,3400],
	[43,43590,45589,3600,3600],[44,45590,46589,703,703],[46,46790,54539,5150,5150],[48,55240,56739,3550,3550],
	[65,65536,66435,2110,1920],[66,66436,67335,2110,1710],[67,67336,67535,738,null],[68,67536,67835,753,698],
	[69,67836,68335,2570,null],[70,68336,68585,1995,1695],[71,68586,68935,617,663],[72,68936,68985,461,451],
	[73,68986,69035,460,450],[74,69036,69465,1475,1427],[75,69466,70315,1432,null],[76,70316,70365,1427,null],
	[85,70366,70545,728,698],[87,70546,70595,420,410],[88,70596,70645,422,412]
];

/* NR: [band, first, last NR-ARFCN], most widely deployed first (NR_BAND_DL). */
var NR_DL = [
	[1,422000,434000],[3,361000,376000],[28,151600,160600],[41,499200,537999],
	[78,620000,653333],[77,620000,680000],[7,524000,538000],[5,173800,178800],
	[8,185000,192000],[20,158200,164200],[38,514000,524000],[40,460000,480000],
	[66,422000,440000],[71,123400,130400],[2,386000,398000],[25,386000,399000],
	[12,145800,149200],[13,149200,151200],[14,151600,153600],[18,172000,175000],
	[26,171800,178800],[29,143400,145600],[30,470000,472000],[34,402000,405000],
	[39,376000,384000],[48,636667,646666],[50,286400,303400],[51,285400,286400],
	[53,496700,499000],[65,422000,440000],[70,399000,404000],[74,295000,303600],
	[75,286400,303400],[76,285400,286400],[79,693334,733333],[257,2054166,2104165],
	[258,2016667,2070832],[261,2070833,2084999],[260,2229166,2279165],[46,743334,795000]
];

/* One band inside another: [a, b, first, last] (NR_AMBIGUOUS_PAIRS). */
var NR_PAIRS = [[77,78,620000,653333],[38,41,514000,524000],[5,26,173800,178800]];

/* Max DL resource blocks by 'FR/SCS kHz/BW MHz' (TS 38.104, NR_MAX_RB). */
var NR_MAX_RB = { '1/15/5':25,'1/15/10':52,'1/15/15':79,'1/15/20':106,'1/15/25':133,'1/15/30':160,'1/15/40':216,'1/15/50':270,'1/30/5':11,'1/30/10':24,'1/30/15':38,'1/30/20':51,'1/30/25':65,'1/30/30':78,'1/30/40':106,'1/30/50':133,'1/30/60':162,'1/30/70':189,'1/30/80':217,'1/30/90':245,'1/30/100':273,'1/60/10':11,'1/60/15':18,'1/60/20':24,'1/60/25':31,'1/60/30':38,'1/60/40':51,'1/60/50':65,'1/60/60':79,'1/60/70':93,'1/60/80':107,'1/60/90':121,'1/60/100':135,'2/60/50':66,'2/60/100':132,'2/60/200':264,'2/120/50':32,'2/120/100':66,'2/120/200':132,'2/120/400':264 };

var CSS = '\
:root { --qbm-primary:#3482FF; --qbm-dl:#B07800; --qbm-ul:#00897B; --qbm-track:rgba(128,128,128,.13); --qbm-line:rgba(128,128,128,.18) }\
:root[data-darkmode="true"] { --qbm-primary:#277AF7; --qbm-dl:#EAB01F; --qbm-ul:#40E0D0; --qbm-track:rgba(255,255,255,.08); --qbm-line:rgba(255,255,255,.1) }\
.qbm-dl { color:var(--qbm-dl) } .qbm-ul { color:var(--qbm-ul) } .qbm-primary { color:var(--qbm-primary) }\
.qbm-muted { opacity:.6 }\
.qbm-vbar { position:relative; overflow:hidden; border-radius:4px; background:var(--qbm-track); text-align:center; padding:1px 2px; white-space:nowrap; font-variant-numeric:tabular-nums }\
.qbm-vbar > i { position:absolute; left:0; top:0; bottom:0 }\
.qbm-vbar > span { position:relative }\
.qbm-vbar.blue > i { background:linear-gradient(to right, rgba(52,130,255,.8), rgba(52,130,255,.45)) }\
:root[data-darkmode="true"] .qbm-vbar.blue > i { background:linear-gradient(to right, rgba(39,122,247,.8), rgba(39,122,247,.45)) }\
.qbm-vbar.empty > span { opacity:.5 }\
.qbm-q { display:inline-block; width:.55em; height:.55em; border-radius:50%; margin-right:.35em; vertical-align:baseline }\
';

function isNum(v) { return typeof v === 'number' && isFinite(v); }

function rgba(hex, a) {
	var n = parseInt(hex.slice(1), 16);
	return 'rgba(' + (n >> 16 & 255) + ',' + (n >> 8 & 255) + ',' + (n & 255) + ',' + a + ')';
}

return baseclass.extend({
	CSS: CSS,
	LEVEL: LEVEL,
	isNum: isNum,

	/* An LTE carrier counts (CA count, column) only with both RSRP and RSRQ:
	 * a configured SCell with no measurement is not an active carrier. */
	validLteCa: function(c) { return isNum(c.rsrp) && isNum(c.rsrq); },

	/* One decimal, rounded half away from zero, as the app's formatMetric. */
	metric: function(v) {
		if (!isNum(v)) return '–';
		var r = Math.round(Math.abs(v) * 10 + 1e-9) / 10;
		return ((v < 0 && r !== 0) ? '-' : '') + r.toFixed(1);
	},

	/* Plain quantities (bandwidth): no trailing ".0". */
	plain: function(v) {
		if (!isNum(v)) return null;
		return v % 1 === 0 ? String(v) : v.toFixed(1);
	},

	/* 0 = excellent ... 4 = bad. */
	level: function(kind, v) {
		var s = SCALES[kind];
		for (var i = 0; i < s.t.length; i++)
			if (s.worse ? v <= s.t[i] : v >= s.t[i]) return i;
		return 4;
	},

	color: function(kind, v) { return LEVEL[this.level(kind, v)]; },

	fraction: function(kind, v) {
		var s = SCALES[kind];
		return Math.min(1, Math.max(0, (v - s.min) / (s.max - s.min)));
	},

	/* The empty bar pill with a dash: a bar cell with no value. */
	emptyTrack: function() {
		return E('div', { 'class': 'qbm-vbar empty' }, E('span', {}, '–'));
	},

	/* A value on a level-coloured bar (the app's CellValueBar, gradientFill):
	 * the fill fades from the level colour to 65 % of it. */
	valueBar: function(v, kind, suffix) {
		if (!isNum(v)) return this.emptyTrack();
		var c = this.color(kind, v), w = (this.fraction(kind, v) * 100).toFixed(1);
		var s = suffix ? ((suffix === '%' || suffix.charAt(0) === ' ' || suffix.charAt(0) === ' ') ? suffix : ' ' + suffix) : '';
		var text = this.metric(v) + s;
		return E('div', { 'class': 'qbm-vbar', 'title': text }, [
			E('i', { 'style': 'width:' + w + '%;background:linear-gradient(to right,' + c + ',' + rgba(c, .65) + ')' }),
			E('span', {}, text)
		]);
	},

	/* A value on a fading blue bar (the MCS and RBs rows). A null fraction
	 * shows the plain value; a null text the empty track. */
	gradientBar: function(text, fraction) {
		if (text == null) return this.emptyTrack();
		if (fraction == null) return E('div', { 'class': 'qbm-vbar', 'style': 'background:none' }, E('span', {}, text));
		return E('div', { 'class': 'qbm-vbar blue' }, [
			E('i', { 'style': 'width:' + (Math.min(1, Math.max(0, fraction)) * 100).toFixed(1) + '%' }),
			E('span', {}, text)
		]);
	},

	/* A small level dot and the value, for tiles and lists. */
	signal: function(kind, v, unit) {
		return E('span', { 'style': 'font-variant-numeric:tabular-nums;white-space:nowrap' }, [
			isNum(v) ? E('span', { 'class': 'qbm-q', 'style': 'background:' + this.color(kind, v) }) : '',
			isNum(v) ? this.metric(v) + (unit ? ' ' + unit : '') : '–'
		]);
	},

	/* The band's display colour for a label like "B3" or "n78". */
	bandColor: function(label) {
		var n = label ? parseInt(String(label).replace(/^[Bn]/, ''), 10) : NaN;
		return BAND_COLORS[n] || null;
	},

	lteBand: function(earfcn) {
		for (var i = 0; i < LTE_PLANS.length; i++)
			if (earfcn >= LTE_PLANS[i][1] && earfcn <= LTE_PLANS[i][2]) return LTE_PLANS[i][0];
		return null;
	},

	/* Downlink and uplink MHz of an EARFCN (uplink null for SDL bands). */
	lteFreq: function(earfcn) {
		for (var i = 0; i < LTE_PLANS.length; i++) {
			var p = LTE_PLANS[i];
			if (earfcn >= p[1] && earfcn <= p[2]) {
				var off = (earfcn - p[1]) / 10;
				return { dl: p[3] + off, ul: p[4] == null ? null : p[4] + off };
			}
		}
		return null;
	},

	/* NR-ARFCN to MHz (TS 38.104 global raster). */
	nrFreq: function(a) {
		if (a >= 0 && a <= 599999) return a * 0.005;
		if (a >= 600000 && a <= 2016666) return 3000 + (a - 600000) * 0.015;
		if (a >= 2016667 && a <= 3279165) return 24250.08 + (a - 2016667) * 0.06;
		return null;
	},

	/* WCDMA band and downlink MHz of a UARFCN (TS 25.101 general channels). */
	wcdmaBand: function(u) {
		var r = [ [10562,10838,1], [9662,9938,2], [1162,1513,3], [1537,1738,4], [4357,4458,5], [2237,2563,7],
		          [2937,3088,8], [9237,9387,9], [3112,3388,10], [3712,3787,11], [712,763,19] ];
		for (var i = 0; i < r.length; i++) if (u >= r[i][0] && u <= r[i][1]) return r[i][2];
		return null;
	},
	wcdmaFreq: function(u) {
		var b = this.wcdmaBand(u);
		if (b == null) return null;
		var off = { 3: 1575, 4: 1805, 7: 2175, 8: 340, 10: 1490, 11: 736, 19: 735 }[b] || 0;
		return off + u * 0.2;
	},

	/* False only when the band is known and the NR-ARFCN is outside it. */
	nrBandFits: function(band, arfcn) {
		var r = NR_DL.filter(function(x) { return x[0] === band; });
		return !r.length || r.some(function(x) { return arfcn >= x[1] && arfcn <= x[2]; });
	},

	/* The NR band of an NR-ARFCN: the first in NR_DL's order (most widely
	 * deployed first), among the modem's supported bands (supported, may be
	 * empty) when that leaves one (nrBandForArfcn). */
	nrBand: function(arfcn, supported) {
		var c = NR_DL.filter(function(x) { return arfcn >= x[1] && arfcn <= x[2]; }).map(function(x) { return x[0]; });
		if (supported && supported.length) {
			var s = c.filter(function(b) { return supported.indexOf(b) >= 0; });
			if (s.length) return s[0];
		}
		return c.length ? c[0] : null;
	},

	/* A stand-in for an NR-ARFCN n77 and n78 share (620000-653333) until the
	 * modem names the band: n78, the band used there in most countries, but
	 * n77 where operators deploy that range as n77: USA (MCC 310-316), Canada
	 * (302) and Japan (440/441; NTT docomo, 440-10, uses n78). A PLMN that
	 * isn't known yet counts as elsewhere. null outside the overlap. */
	nrBandGuess: function(arfcn, plmn) {
		if (!isNum(arfcn) || arfcn < 620000 || arfcn > 653333) return null;
		var mcc = plmn ? +plmn.mcc : NaN, mnc = plmn ? +plmn.mnc : NaN;
		if (mcc === 440 && mnc === 10) return 78;
		if ((mcc >= 310 && mcc <= 316) || mcc === 302 || mcc === 440 || mcc === 441) return 77;
		return 78;
	},

	/* "n78", or "n77/78" where one band sits inside another, unless the
	 * modem's supported bands rule one of the two out (nrBandLabelForArfcn). */
	nrBandLabel: function(arfcn, supported) {
		var sup = supported || [];
		for (var i = 0; i < NR_PAIRS.length; i++) {
			var p = NR_PAIRS[i];
			if (arfcn < p[2] || arfcn > p[3]) continue;
			var left = [ p[0], p[1] ].filter(function(b) { return !sup.length || sup.indexOf(b) >= 0; });
			return left.length === 1 ? 'n' + left[0] : 'n' + p[0] + '/' + p[1];
		}
		var b = this.nrBand(arfcn, sup);
		return b != null ? 'n' + b : null;
	},

	nrMaxRb: function(band, arfcn, scs, bw) {
		if (!isNum(scs) || !isNum(bw)) return null;
		var fr = ((isNum(band) && band >= 257) || arfcn >= 2016667) ? 2 : 1;
		return NR_MAX_RB[fr + '/' + scs + '/' + bw] || null;
	}
});
