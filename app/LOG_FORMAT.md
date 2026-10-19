# Portable modem logs and desktop replay

The archive is independent of the phone's temporary replay ring. The user chooses
a folder with Android's document picker (an SD-card subfolder is supported) and
starts/stops recording from the Network page. No all-files permission is needed.
The destination receives independently finalized UTF-8 NDJSON gzip segments. Open
all segments from a session together in `web/index.html`; files stay on the PC.

## Envelope and clocks

The first line of each segment has `type: "header"`, `format: "qbm-log"`,
`version: 1`, `sessionId`, `segment`, `startedUtcMs`, `startedElapsedNs`, device
metadata and `packetCapture`. `startedElapsedNs` is a decimal string in Android's
`CLOCK_BOOTTIME` domain. Record `tNs` uses the same domain. The browser derives UTC
as `startedUtcMs + (tNs - startedElapsedNs) / 1e6`; it does not use the PC's current
clock or assume that every poll lasts exactly one second. Nanoseconds and the
opaque Qualcomm timestamp remain strings to avoid JavaScript integer truncation.

Every archive record carries its session and archive sequence. Packet sequence
numbers are independent and reset when a new native capture begins. A helper
restart, queue overflow, missing segment or incomplete gzip file is surfaced as a
gap or warning. Carry-forward must not cross a known gap or a carrier identity
change. A stopped segment ends with a footer; interruption may leave the final
segment incomplete while earlier closed segments remain usable.

## UI and packet records

`ui` records preserve the post-processing `NetworkSnapshot`, logical publication
time, sample acquisition time, operator/error/frozen/DIAG state, and flattened
metric updates. These are logical UI data, not screenshots: Compose animation and
display-frame conflation are not recorded. The original polling inputs may be
retained for diagnosis. `poll` records, when present, describe acquisitions that
were not necessarily displayed. UI replay uses published `ui` records.

`packet` records preserve the native callback's BOOTTIME arrival timestamp,
`monoNs` (the helper's MONOTONIC clock), `modemTs` (opaque Qualcomm timestamp),
capture ID, packet sequence, SIM attribution, log code, full DCI packet bytes as
hex, and sparse decoded updates. Packet resolution means a received DIAG log
packet; a packet can contain multiple transport blocks or measurement records.
Their sufficient statistics are retained without fabricating subrecord times.
The original modem timestamp is retained for investigation; it is not silently
assumed to be UTC or interchangeable with either Android clock.

Updates have `entity` (for example `lte:0` or `nr:1`), `identity` (channel/cell
identity), `metric`, `value`, `unit`, and `aggregation`. Only newly observed packet
values are updates. The optional `resets` array clears complete entities before
the packet's updates. An explicit null clears a value. Absent fields mean no new
observation, not zero. Each SIM, session and carrier has independent state.

## Aggregation contract

The phone does not average every value in the same way:

| Aggregation | Stored evidence | Coarser-interval calculation |
| --- | --- | --- |
| `mean` | `sum`, `count` | Sum the original sums and counts, then divide. |
| `ratio` | `numerator`, `denominator`, `scale` | Sum numerator/denominator and apply scale (e.g. BLER percent). |
| `mode` | `counts`, optional `order` and `map` | Sum category votes, choose by native tie order, then map the winning category if required. |
| `last` | latest observed `value` | Last observation at or before the interval endpoint. |
| `sum` | event increment `value` | Add actual increments; do not repeatedly add carried values. |

LTE MCS excludes reserved-index retransmissions from its mean; LTE RBs use TB
counts and LTE layers use qualifying record counts. NR MCS/RBs/layers use their
decoder's TB counts. Signal measurements use the packet/record weights supplied
by the decoder. SS-SINR, CSI-SINR and beam SNR are distinct. Filtered LTE signal
fallbacks are preserved separately from unfiltered observations. Configuration
and timing advance use last observations with carrier identity boundaries.
LTE transmission mode retains the underlying scheme votes: choosing a scheme
before mapping to TM is different from pooling votes by mapped TM.

Scheduled DL and UL bytes are increments. Rates are derived over an explicit
interval as `bytes * 8 / (intervalMs * 1000)` Mbps, including idle time. A packet's
arrival gap is not a valid throughput denominator. The browser exposes packet
byte counts and interval-derived rates separately.

Native `stats` replies additionally expose observed-window `aggregates` before
reset while packet capture is enabled. These can accompany UI records so coarsening does not average averages
without their weights. Exact UI replay always prefers the stored display values.
UI-only data cannot reconstruct observations within an already aggregated poll;
the viewer labels that limitation rather than inventing higher resolution.
Native loss is reported by `gap` records and the controller's combined count.
The segment footer's `dropped` count describes the Android archive queue.

Packet replay synchronizes asynchronous measurements by causal last observation
carry forward: for example a 20 ms RSRP value stays visible at subsequent 5 ms
SINR updates until the next RSRP update, a reset or a gap. Unknown initial values
remain missing. Export offers this synchronized grid separately from the mobile
aggregation rules above. Plot decimation affects drawing only, not exported data.

## Desktop exports and test reports

Replay exports offer selected fields, SIM/session, time bounds and interval in
JSON, CSV and NDJSON. Exports identify source mode and aggregation policy, and
retain missing values. Raw packet bytes remain in the source archive.

The viewer also imports NBTC/MIC test-report JSON and CSV produced by the separate
test-script feature (PR #7). It recomputes descriptive statistics and NBTC target
margins from raw attempts, including failures and excluding cancellations. MIC
has no minimum-speed pass/fail threshold: show method checks, three-trial point
means and campaign distributions. User-defined targets are clearly separate.
Imported summaries are not evidence of nationwide coverage or operator-wide
certification. See the viewer's README for supported inputs and practical limits.
