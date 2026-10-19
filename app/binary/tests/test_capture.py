#!/usr/bin/env python3
"""Compile/run the real C decoder with synthetic DIAG packets on a POSIX host.

Windows: wsl -d Ubuntu-24.04 -- python3 /mnt/d/qcom-band-menu/app/binary/tests/test_capture.py
No Android device, vendor libdiag, captured subscriber traffic or third-party Python packages are needed.
"""
import json
import math
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class CaptureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="qbm-capture-test-")
        binary = Path(cls.temp.name) / "capture-test"
        subprocess.run([os.environ.get("CC", "cc"), "-std=c11", "-O1", "-D_GNU_SOURCE", "-DHAVE_TIMEGM",
                        "-fsanitize=address,undefined", "-fno-omit-frame-pointer",
                        str(ROOT / "tests/capture_test.c"), str(ROOT / "rrc_csi.c"), str(ROOT / "rrc_nr_gen.c"),
                        "-lm", "-ldl", "-pthread", "-o", str(binary)], check=True)
        run = subprocess.run([str(binary)], check=True, text=True, capture_output=True)
        cls.cases = {row["case"]: row["data"] for row in map(json.loads, run.stdout.splitlines())}

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_packet_order_clocks_and_original_bytes(self):
        packets = self.cases["packets"]["events"]
        self.assertEqual(list(range(1, 11)), [p["seq"] for p in packets])
        self.assertEqual(5_000_000, int(packets[1]["tNs"]) - int(packets[0]["tNs"]))
        self.assertEqual(20_000_000, int(packets[2]["tNs"]) - int(packets[1]["tNs"]))
        for packet in packets:
            self.assertEqual(7_000_000_000, int(packet["tNs"]) - int(packet["monoNs"]))
            self.assertEqual("0123456789abcdef", packet["modemTs"])
            raw = bytes.fromhex(packet["raw"])
            offset = 12 if packet["simTagged"] else 0
            self.assertEqual(int(packet["code"], 16), int.from_bytes(raw[offset + 2:offset + 4], "little"))
            self.assertEqual(int(packet["modemTs"], 16), int.from_bytes(raw[offset + 4:offset + 12], "little"))

    def test_weighted_packet_statistics_are_not_one_second_samples(self):
        packets = self.cases["packets"]["events"]
        first = {u["metric"]: u for u in packets[1]["updates"] if u["entity"] == "lte:0"}
        second = {u["metric"]: u for u in packets[2]["updates"] if u["entity"] == "lte:0"}
        self.assertEqual((30, 2, 15), (first["mcs"]["sum"], first["mcs"]["count"], first["mcs"]["value"]))
        self.assertEqual((4, 1), (second["mcs"]["sum"], second["mcs"]["count"]))
        self.assertEqual(3000, first["dl_bytes"]["value"])
        self.assertEqual(1000, first["dl_good_bytes"]["value"])
        self.assertEqual((1, 2, 100), (first["bler"]["numerator"], first["bler"]["denominator"], first["bler"]["scale"]))
        self.assertEqual({"QPSK": 1, "16QAM": 1}, first["mod"]["counts"])
        self.assertEqual(["QPSK", "16QAM", "64QAM", "256QAM", "1024QAM"], first["mod"]["order"])
        self.assertEqual("QPSK", first["mod"]["value"])
        self.assertFalse(any(u["metric"] in ("mbps", "ul_mbps") for p in packets for u in p["updates"]))

    def test_capture_drain_preserves_live_ui_and_poll_window_aggregates(self):
        plain = self.cases["stats_off"]
        captured = self.cases["stats_on"].copy()
        aggregates = captured.pop("aggregates")
        self.assertEqual("1000000000", captured.pop("windowStartMonoNs"))
        self.assertEqual("1100000000", captured.pop("windowEndMonoNs"))
        self.assertEqual(plain, captured)
        updates = {u["metric"]: u for u in aggregates if u["entity"] == "lte:0"}
        self.assertEqual((34, 3), (updates["mcs"]["sum"], updates["mcs"]["count"]))
        self.assertEqual(3500, updates["dl_bytes"]["value"])
        idle = {u["metric"]: u for u in aggregates if u["entity"] == "lte:1"}
        self.assertEqual(0, idle["dl_bytes"]["value"])
        self.assertNotIn("rsrp", idle)

    def test_dual_sim_and_equal_repeated_last_observations(self):
        packets = [p for p in self.cases["packets"]["events"] if p["code"] == "0xB8A7"]
        self.assertEqual(2, len(packets))
        for packet in packets:
            self.assertEqual(2, packet["sim"])
            self.assertTrue(packet["simTagged"])
            updates = {u["metric"]: u["value"] for u in packet["updates"] if u["entity"] == "nr:2"}
            self.assertEqual({"cqi": 12, "ri": 3}, updates)

    def test_lte_combined_rsrp_base_fix_is_preserved(self):
        packets = [p for p in self.cases["packets"]["events"] if p["code"] == "0xB193"]
        values = [next(u["value"] for u in p["updates"] if u["metric"] == "rsrp") for p in packets]
        self.assertEqual([-102.5, -100], values)

    def test_sparse_signal_updates_and_unknown_layout_raw_retention(self):
        packets = self.cases["packets"]["events"]
        snr = [p for p in packets if p["code"] == "0xB8D8"]
        for packet, expected in zip(snr, (10, 20)):
            self.assertEqual(1, len(packet["updates"]))
            self.assertAlmostEqual(expected, packet["updates"][0]["value"])
            self.assertEqual("sinr", packet["updates"][0]["metric"])
        self.assertEqual([], packets[-1]["updates"])
        self.assertFalse(packets[-1]["simTagged"])

    def test_release_identity_reset_and_final_drain_after_disable(self):
        released = self.cases["released"]
        self.assertEqual(self.cases["packets"]["captureId"], released["captureId"])
        first, second = released["events"]
        self.assertIn("lte:0", first["resets"])
        self.assertIn("lte:1", first["resets"])
        self.assertTrue(all(u["identity"] == "1650:33" for u in second["updates"]))

    def test_bounded_queue_reports_loss_and_preserves_remaining_order(self):
        queue = self.cases["queue"]
        self.assertLessEqual(queue["bytes"], 8 * 1024 * 1024)
        self.assertLessEqual(queue["records"], 8192)
        self.assertEqual(9000, queue["records"] + queue["dropped"])
        response = self.cases["overflow"]
        self.assertEqual(queue["dropped"], response["dropped"])
        self.assertEqual(queue["dropped"], response["totalDropped"])
        events = response["events"]
        self.assertEqual(256, len(events))
        self.assertEqual(list(range(queue["dropped"] + 1, queue["dropped"] + 257)), [p["seq"] for p in events])
        self.assertTrue(self.cases["assertions"])

    def test_rrc_configuration_index_alias_is_retired_when_actual_carrier_arrives(self):
        configured, live = self.cases["aliases"]["events"]
        self.assertTrue(any(u["entity"] == "nr:3" and u["identity"] == "640000:9" for u in configured["updates"]))
        self.assertIn("nr:3", live["resets"])
        self.assertTrue(any(u["entity"] == "nr:1" and u["identity"] == "640000:9" for u in live["updates"]))

    def test_transmission_mode_chooses_raw_scheme_before_mapping(self):
        packets = [p for p in self.cases["modes"]["events"] if p["code"] == "0xB126"]
        modes = [next(u for u in p["updates"] if u["metric"] == "tm") for p in packets]
        self.assertEqual({"4": 3, "8": 2, "9": 2}, modes[0]["counts"])
        self.assertEqual(4, modes[0]["value"])  # Pooling 8+9 as TM9 would incorrectly choose 9.
        self.assertEqual([str(i) for i in range(16)], modes[0]["order"])
        self.assertEqual(9, modes[0]["map"]["8"])
        self.assertEqual(9, modes[0]["map"]["9"])
        self.assertIsNone(modes[0]["map"]["0"])
        self.assertEqual("mode", modes[0]["aggregation"])
        stats = self.cases["mode_stats"]
        window = next(u for u in stats["aggregates"] if u["metric"] == "tm")
        self.assertEqual({"4": 3, "8": 4, "9": 4}, window["counts"])
        winner = max(window["order"], key=lambda key: window["counts"].get(key, 0))
        self.assertEqual("8", winner)
        self.assertEqual(stats["carriers"][0]["tm"], window["map"][winner])
        self.assertEqual(9, window["value"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
