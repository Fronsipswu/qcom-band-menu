# qcom-band-menu-app

Qualcomm Android app for locking bands, RAT and NR mode

## Portable compressed logs

The Network menu's **Save network logs** records to a folder selected through
Android's document picker, including an SD-card subfolder. Recording is independent
of the temporary replay cache. It writes independently finalized gzip segments,
retains processed UI snapshots, and can include timestamped Qualcomm packets,
decoded observations, aggregation weights and original packet bytes.

Stop recording before copying the session's `.jsonl.gz` files to a PC. Open
[`web/index.html`](../web/index.html) locally to replay, graph and export them.
The viewer also imports the test-report JSON/CSV format from the NBTC/MIC feature.
No hosted service or upload is required.

See [LOG_FORMAT.md](LOG_FORMAT.md) and the [viewer guide](../web/README.md) for
clock alignment, carry-forward, aggregation, gaps, storage and verification limits.

The packaged DIAG helper can be rebuilt with an Android NDK:

```console
python app/binary/build-diagd.py --ndk <path-to-android-ndk>
```

Run that command from the repository root. It rebuilds the arm64 asset with 16 KiB
page alignment. The existing Gradle wrapper and pinned composite dependencies
remain the Android build path; see `.github/workflows/ci.yml`.
