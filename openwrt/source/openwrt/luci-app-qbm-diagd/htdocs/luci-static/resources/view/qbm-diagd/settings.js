'use strict';
'require view';
'require form';
'require rpc';
'require ui';
'require qbm-diagd.modem as modemInfo';

var callPorts = rpc.declare({ object: 'luci.qbm-diagd', method: 'ports' });
var callQuery = rpc.declare({ object: 'luci.qbm-diagd', method: 'qmi_query', params: [ 'sim', 'cmd' ] });
var callStatus = rpc.declare({ object: 'luci.qbm-diagd', method: 'status' });
var callMtkRestore = rpc.declare({ object: 'luci.qbm-diagd', method: 'mtk_restore' });

function describe(p) {
	var bits = [];
	if (p.interface) bits.push(_('interface %s').format(p.interface));
	if (p.class && p.class !== '//') bits.push(p.class);
	if (p.driver) bits.push(p.driver);
	return bits.join(', ');
}

return view.extend({
	load: function() {
		return modemInfo.identify().then(function(modem) {
			return Promise.all([
				L.resolveDefault(callPorts(), { ports: [] }),
				modem.family === 'mtk' ? null : L.resolveDefault(callQuery(1, 'query'), null),
				modem,
				L.resolveDefault(callStatus(), null)
			]);
		});
	},

	render: function(data) {
		var res = (data && data[0]) || {};
		var qmi = (data && data[1]) || {};
		var modem = (data && data[2]) || {};
		var status = (data && data[3]) || {};
		var mtk = modem.family === 'mtk';
		var hw = (qmi && qmi.state && qmi.state.hardware) || {};
		var ports = res.ports || [];
		var m, s, o;

		m = new form.Map('qbm-diagd', _('Modem Statistics'),
			_('qbm-diagd reads LTE and 5G NR logs from the modem\'s USB DIAG port for the Overview. While it runs it owns that port: other DIAG tools on the same port will not work at the same time. Band and cell locking use the QMI port instead.'));

		s = m.section(form.NamedSection, 'main', 'qbm-diagd');
		s.addremove = false;

		o = s.option(form.Flag, 'enabled', _('Statistics service'));
		o.rmempty = false;
		o.default = '1';

		o = s.option(form.Value, 'device', _('DIAG port'),
			_('Automatic tries each /dev/ttyUSB* port until one answers as DIAG; it also sends DIAG requests to the modem\'s other ports, which ignore them. Pick the port to avoid that. On Qualcomm modems the DIAG port is usually USB interface 00, with class ff/ff/30 on newer models. On a Fibocom FM350 it is the debug port, USB interface 09, found by itself.'));
		o.value('auto', _('Automatic'));
		ports.forEach(function(p) {
			o.value(p.dev, '%s (%s)'.format(p.dev, describe(p)));
		});
		o.default = 'auto';
		o.rmempty = false;

		o = s.option(form.Value, 'qmi_device', _('QMI port'),
			_('Used by Band Locking and Cell Locking. Automatic picks the first /dev/cdc-wdm* (qmi_wwan). The port is shared with the mobile data connection (uqmi): it is opened only for each request.'));
		o.value('auto', _('Automatic'));
		((res && res.qmi) || []).forEach(function(q) {
			o.value(q.dev, '%s (%s)'.format(q.dev, [ q.driver, q.interface ? _('interface %s').format(q.interface) : '' ].filter(Boolean).join(', ')));
		});
		o.default = 'auto';
		o.rmempty = false;

		var atDesc = _('Used for AT commands like Airplane mode toggle. Automatic tries each port until one answers AT+CGMM.');
		if (res.at_device)
			atDesc += ' ' + _('(currently detected: %s)').format(res.at_device);
		o = s.option(form.Value, 'at_device', _('AT port'), atDesc);
		o.value('auto', _('Automatic'));
		ports.forEach(function(p) {
			o.value(p.dev, '%s (%s)'.format(p.dev, describe(p)));
		});
		o.default = 'auto';
		o.rmempty = false;

		o = s.option(form.Flag, 'mtk_trim', _('Trim MTK DIAG traces'),
			_('Fibocom FM350 only. Turns off the modem\'s DHL debug traces so its debug port carries about 36 KB/s instead of about 4.4 MB/s, which the router has to read in full otherwise. The LTE/NR logs QBM uses are not affected. The modem saves these filters in its NVRAM: they are written once, and only while they differ.'));
		o.rmempty = false;
		o.default = '1';

		o = s.option(form.Button, '_mtk_restore', _('Restore MTK DIAG profile'),
			mtk ? _('Writes the FM350\'s original trace filters back (saved in its NVRAM) and unchecks Trim MTK DIAG traces. Use it before handing the modem to another logging tool.')
			    : _('Only for a Fibocom FM350; no FM350 detected.'));
		o.inputtitle = _('Restore');
		o.inputstyle = 'reset';
		o.readonly = !mtk;
		o.onclick = function() {
			if (!confirm(_('Write the FM350\'s original trace filters back? Its debug port then carries the full trace stream (about 4.4 MB/s) again.'))) return;
			return callMtkRestore().then(function(r) {
				if (r && r.ok) {
					ui.addNotification(null, E('p', _('Restoring the MTK DIAG profile: qbm-diagd writes the original trace filters within a few seconds. Trim MTK DIAG traces is now unchecked.')), 'info');
					window.setTimeout(function() { location.reload(); }, 3000);
				} else {
					ui.addNotification(null, E('p', _('Restore failed: %s').format((r && r.error) || _('no reply from qbm-diagd'))), 'danger');
				}
			});
		};
		if (mtk && status.trace) {
			o = s.option(form.DummyValue, '_mtk_trace', _('MTK trace filters'));
			o.cfgvalue = function() {
				return status.trace + (status.firmware ? ' (' + _('firmware %s').format(status.firmware) + ')' : '');
			};
		}

		o = s.option(form.MultiValue, 'filter_bands', _('Filter shown bands'),
			_('Select bands to show on the Band Locking page, just like in QBM. Leave empty to show all bands reported by the modem hardware.'));
		o.rmempty = true;

		var stdNr = [ 1, 2, 3, 5, 7, 8, 12, 13, 14, 18, 20, 25, 26, 28, 29, 30, 34, 38, 39, 40, 41, 46, 48, 50, 51, 53, 65, 66, 70, 71, 74, 75, 76, 77, 78, 79, 257, 258, 260, 261 ];
		var stdLte = [ 1, 2, 3, 4, 5, 7, 8, 11, 12, 13, 14, 17, 18, 19, 20, 21, 24, 25, 26, 28, 29, 30, 31, 32, 34, 38, 39, 40, 41, 42, 43, 46, 48, 66, 71 ];
		var stdWcdma = [ 1, 2, 4, 5, 6, 8, 9, 19 ];
		var stdGsm = [ 850, 900, 1800, 1900 ];

		function mergeBands(hwList, stdList) {
			var set = {};
			(stdList || []).forEach(function(b) { set[b] = true; });
			(hwList || []).forEach(function(b) { set[b] = true; });
			return Object.keys(set).map(Number).sort(function(a, b) { return a - b; });
		}

		var allNr = mergeBands(hw.nr, stdNr);
		var allLte = mergeBands(hw.lte, stdLte);
		var allWcdma = mergeBands(hw.wcdma, stdWcdma);
		var allGsm = mergeBands(hw.gsm, stdGsm);

		allNr.forEach(function(b) {
			o.value('n' + b, 'NR n' + b);
		});
		allLte.forEach(function(b) {
			o.value('B' + b, 'LTE B' + b);
		});
		allWcdma.forEach(function(b) {
			o.value('W' + b, 'WCDMA B' + b);
		});
		allGsm.forEach(function(b) {
			o.value('GSM' + b, 'GSM ' + b);
		});

		var product = ports.filter(function(p) { return p.product; })[0];
		if (modem.known) {
			o = s.option(form.DummyValue, '_modem', _('Modem'));
			o.cfgvalue = function() {
				return [ modem.manufacturer, modem.model ].filter(Boolean).join(' ') + (modem.revision ? ' (' + modem.revision + ')' : '') +
					(mtk ? ' · ' + _('MediaTek: statistics from its debug port, band locking with AT+GTACT') : '');
			};
		} else if (product) {
			o = s.option(form.DummyValue, '_modem', _('Modem'));
			o.cfgvalue = function() {
				return '%s %s (%s)'.format(product.manufacturer || '', product.product, product.usb_id);
			};
		} else if (!ports.length) {
			o = s.option(form.DummyValue, '_modem', _('Modem'));
			o.cfgvalue = function() {
				return _('No USB serial ports found. Is the modem connected, and is kmod-usb-serial-option installed?');
			};
		}

		return m.render();
	}
});
