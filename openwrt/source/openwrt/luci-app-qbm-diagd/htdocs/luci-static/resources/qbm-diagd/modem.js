'use strict';
'require baseclass';
'require rpc';

/*
 * Which modem is attached, from the standard identification commands
 * (AT+CGMI, AT+CGMM, AT+GMR; the backend caches the answer). A Fibocom FM350
 * is MediaTek-based ("mtk"): statistics come from its debug port, band and RAT
 * locking from AT+GTACT, and there is no QMI. Anything else is treated as
 * Qualcomm ("qcom"), as before 2.0.0.
 */

var callModem = rpc.declare({ object: 'luci.qbm-diagd', method: 'modem' });

/* The value line of one AT reply: not the echo, a result or an unsolicited report. */
function value(reply) {
	var lines = String(reply || '').split('\n').map(function(l) { return l.trim(); });
	for (var i = 0; i < lines.length; i++) {
		var l = lines[i];
		if (!l || /^AT/i.test(l) || l === 'OK' || /ERROR/.test(l) || l.charAt(0) === '+') continue;
		return l;
	}
	return null;
}

var cached = null;

function identify() {
	if (cached) return cached;
	cached = L.resolveDefault(callModem(), null).then(function(r) {
		var replies = (r && r.ok && r.replies) || [];
		var m = { manufacturer: value(replies[0]), model: value(replies[1]), revision: value(replies[2]) };
		m.family = m.model && /FM350/i.test(m.model) ? 'mtk' : 'qcom';
		m.known = !!m.model;
		return m;
	});
	return cached;
}

return baseclass.extend({
	identify: identify,
	value: value
});
