# Japan MIC mobile effective-speed measurements

The Japanese MIC method measures and publishes the distribution of mobile
download/upload speeds. It does **not** prescribe a minimum Mbps, maximum latency,
or packet-loss percentage that a mobile network must pass. Keep MIC evidence and
statistics separate from the Thai NBTC threshold assessment.

The device functions support the measurement portion of a campaign. The complete
MIC process also requires independently selected locations, the common measurement
software/server infrastructure, operator-neutral oversight and publication.
GPS/maps, automatic site selection, calls, SMS and MMS remain outside this app's
scope. A manually labelled point is not proof of the prescribed geographic sample.

## Using MIC and Custom modes

1. Open **Test script** and select **MIC**. Choose **Point sequence: DL + UL + UDP**
   for one location. The individual parallel-download, parallel-upload and UDP
   functions provide component measurements; they do not replace the complete
   point sequence.
2. Enter the cooperating download URL, upload URL and UDP echo host/port. MIC
   download requires an uncompressed **100,000,000-byte** reference object with
   that exact `Content-Length`. Upload uses a chunked binary HTTP POST and requires
   a successful server response after the complete body is consumed. A generic
   website or an endpoint that rejects chunked uploads is unsuitable.
3. Enter campaign ID, a unique point ID, operator and declared radio technology.
   Choose office/business or residential time window in **Asia/Tokyo**. Record
   the stationary-outdoor and shared-server declarations accurately. They are
   recorded campaign context, not independently verified facts.
4. Save and start from the network screen. MIC fixes three trials, three parallel
   connections, the reference warm-up/measurement windows and the UDP schedule.
   The operational HTTP phase timeout and idle interval remain configurable.
5. In **Test results**, inspect the point means, each trial's DL/UL streams, UDP
   counts/RTTs, measured radio references and errors. The point-sequence campaign
   panel shows comparable point means, boxplots and exclusion counts. Export the
   individual session or the displayed campaign summary as CSV/JSON.

**Custom** exposes all functions and the parallel-connection, warm-up, measurement
window and UDP timing parameters. Each regime/function keeps separate saved
settings. Custom results carry no MIC or NBTC assessment and are not merged into
the MIC campaign distribution.

MIC reports are retained across restarts without the ordinary 20-session pruning
rule, so a campaign is not discarded after twenty points. NBTC/Custom retains its
existing 20-session history. Export campaign evidence regularly to preserve an
independent copy; no data is submitted to MIC or a carrier automatically.

This is an independent reproduction of the published measurement method. It is
not the designated MIC/FCC client or a certified drop-in implementation of its
server protocols. The supplied PDFs omit essential server wire details. The
app therefore uses explicitly configured cooperating endpoints, records the
declarations/limitations and provides no claim of official campaign acceptance.

## Reviewed sources and currentness

Reviewed on **20 October 2026**. References below use physical PDF page numbers
starting at 1; printed document/appendix page numbers differ.

| ID | Supplied document and official link | Relevant pages |
| --- | --- | --- |
| G | [001056443.pdf — 移動系通信事業者が提供するインターネット接続サービスの実効速度計測手法及び利用者への情報提供手法等に関するガイドライン](https://www.soumu.go.jp/main_content/001056443.pdf), MIC, July 2015, 12 pages | Measurement conditions pp. 4–6; implementation/publication pp. 6–10 |
| R | [000371343.pdf — インターネットのサービス品質計測等の在り方に関する研究会 報告書](https://www.soumu.go.jp/main_content/000371343.pdf), study-group report, July 2015, 103 pages | Main conclusions pp. 38–50; reference appendix 3, pp. 75–93 |

The [MIC publication announcement of 31 July 2015](https://www.soumu.go.jp/menu_news/s-news/01kiban04_02000095.html)
currently links those exact two files. Direct downloads from MIC matched the
user-supplied files byte for byte:

| File | SHA-256 |
| --- | --- |
| `001056443.pdf` | `279b5d8a9bf894a1d6a76533d0d02fc8522be90cc6505bd4e0173af681a90e78` |
| `000371343.pdf` | `3962dc50bd20d7ab9e6979a4b1336ba86d03dc801a9a1d2f1b3d0e4e431056f7` |

The larger identifier `001056443` does not make the guideline a 2026 revision:
its cover says July 2015. The [official study-group index](https://www.soumu.go.jp/main_sosiki/kenkyu/speed_measurement/index.html)
also ends with that publication. A search for subsequent MIC mobile-method
revisions found no published replacement. As a current operational cross-check,
[SoftBank's 2026 survey description](https://www.softbank.jp/mobile/network/service/speed-survey/)
continues to describe three measurements per point and approximately 1,500 points.
Separate fixed-broadband and MVNO-industry guidelines are not imported into this
MNO mobile profile. This is a dated review of the sources found, not a guarantee
against future campaign-specific instructions.

## Point measurement sequence

One reference measurement consists of download, upload, then UDP latency/loss
(R p. 76). Repeat the measurement **three times at the same stationary outdoor
point**, and use the arithmetic mean of the three speed values as that point's
speed (G pp. 4–5; R p. 42). Preserve each repetition and each component outcome.
The appendix's example scenario has a five-second idle interval between repeated
measurements (R p. 92); this is a reference scenario setting, not a separate
five-second mandate in the short guideline.

The HTTP stages below measure controlled intervals of a transfer. They are
different from NBTC's complete-file single-session FTP tests and from generic
HTTP downloads that require an exact whole response size.

| Parameter | MIC reference setting | Source |
| --- | --- | --- |
| Download transport | HTTP GET, three concurrent connections | R p. 78 |
| Upload transport | HTTP POST, three concurrent connections | R p. 79 |
| Warm-up, independently per connection | End at 5 seconds from first payload or when transferred warm-up data exceeds 2.5 Mbyte, whichever occurs first | R pp. 78–79 |
| Measured interval, independently per connection | Stop at 20 Mbyte of measured payload or 20 seconds of measured time, whichever occurs first | R pp. 78–79, 92 |
| Combined throughput | Sum the three individual measured byte/time rates, then convert to Mbps | R pp. 78–79 |
| UDP echo payload | 16 bytes: an eight-byte timestamp and eight-byte sequence number | R pp. 76, 80 |
| UDP scheduling | 60 probes at 500 ms intervals over a 30-second sending window | R pp. 76, 80, 92 |
| UDP reply deadline | 2 seconds per probe | Japanese procedure R pp. 76, 80; scenario R p. 92 |
| Repetitions per point | Three; no removal of highest/lowest value | G p. 5 |

For connection `i`, retain warm-up bytes/time separately from measured bytes `B_i`
and measured duration `T_i`. The speed is:

```text
stream_i_Mbps = 8 * B_i / T_i_seconds / 1,000,000
stage_Mbps = stream_1_Mbps + stream_2_Mbps + stream_3_Mbps
point_download_Mbps = (download_1 + download_2 + download_3) / 3
point_upload_Mbps   = (upload_1 + upload_2 + upload_3) / 3
```

Do not include warm-up bytes/time in the measured rate, average the three stream
rates instead of summing them, or compute one combined `total bytes / slowest
stream time` value. Each connection has its own start and end boundaries. The
three streams run concurrently within a direction; download, upload and UDP run
sequentially. Each repetition must use fresh measurement state.

R pp. 78–79 describe a 100 Mbyte backing file on each connection, but explicitly
end the measured stage at the earlier 20 Mbyte/20-second boundary after warm-up.
They do not require completing a 100 Mbyte transfer for a valid speed sample.
The app's MIC download preset checks the reference object's full 100,000,000-byte
`Content-Length`, then reads only the warm-up and measured window. Custom permits
other sufficiently large objects. Upload
also needs a server that accepts the chosen streaming-body and termination
behavior. An application implementation must not silently advertise a 100 MB
Content-Length and report an incomplete request as an acknowledged full upload.
The app uses a cleanly terminated chunked POST and includes the server's final
acknowledgment delay in its upload rate as a conservative application measurement.
This differs from a packet-level timestamp of the final payload and is disclosed
in results.

G and R do not define Mbyte/MB as decimal or binary. Preserve exact byte limits
and the convention in exports. If decimal units are used, 2.5 MB means 2,500,000
bytes and 20 MB means 20,000,000 bytes; that is a documented implementation
convention, not a newly discovered MIC definition. Mbps uses 1,000,000 bits/s.

## UDP latency and packet loss

Send probes at the required cadence while receiving outstanding replies
concurrently. Waiting up to two seconds for one reply before sending the next
would change the prescribed 500 ms schedule. Allow the last probe's reply window
to finish after the 30-second sending window. Match source and payload/sequence,
count each probe at most once, and keep duplicate, malformed, foreign and late
responses from increasing the received count.

Keep sent, received and lost counts, received RTTs and RTT summary statistics.
Loss is `100 * lost / sent`. A scheduled probe that is actually sent but receives
no timely reply is lost; an instrument that cannot open the socket or execute the
test is unavailable, not a fabricated network-loss observation. Save failures
and partial evidence separately from completed measurements.

R p. 80 quotes an English FCC description using a three-second deadline and a
99th-percentile processing statement, while the Japanese MIC-specific procedure
on that same page and the scenario on p. 92 explicitly use two seconds. The MIC
profile follows the Japanese procedure and scenario. The copied FCC paragraph
does not justify replacing the two-second setting or trimming the campaign's
speed distribution.

The report specifies the two eight-byte fields but provides no byte order,
timestamp unit or UDP server port. R p. 76 lists timestamp before sequence; the
copied FCC description on p. 80 lists sequence before timestamp. A configured
echo endpoint may return the exact 16-byte payload unchanged, with RTT timed by
the client's monotonic clock. Its chosen wire layout must be documented and
verified with that endpoint. The app sends big-endian timestamp64 followed by a
unique sequence64, using a local monotonic nanosecond timestamp; the server only
needs to echo the opaque 16 bytes. It must not be presented as a proven implementation
of an unspecified MIC server protocol.

## Failures, point means and campaign statistics

G p. 5 rejects discarding the highest and lowest speed samples. R p. 87 requires
keeping `Fail` for a failed component and proceeding to the next component,
even when all components fail; R p. 89 uses `N/A` for information that cannot be
obtained. Retain both network failures and instrument failures in the evidence.

The documents do not specify how a failed/non-numeric repetition contributes to
a point mean. The app requires a finished session and exactly three non-cancelled
trials with complete numeric stream results for each direction independently.
Otherwise that direction's point mean is incomplete and excluded from its
distribution. It does not substitute zero, average only surviving values, trim
extremes, or automatically replace a low measurement with a better retry.
Incomplete points and excluded/missing counts remain visible. A completed,
established measured window with no transferred payload may legitimately have
zero throughput; that is different from an instrument or connection failure.

The national statistical population is the **point means**, approximately 1,500
of them, not the approximately 4,500 raw speed repetitions. Keep download and
upload distributions separate, and do not pool incompatible operators, device
groups, scenarios or campaigns. Point labels are manual evidence in this app;
repeated reports from the same point need explicit handling to prevent accidental
double weighting. The app excludes all reports sharing a duplicate point ID in
the same comparable group and reports that ambiguity; it does not select the best
visit. Groups separate operator, device/OS, declared technology, district category,
endpoints, function and effective settings. A group summary is a local subset,
not a claim of a nationwide sample including both district categories.

G p. 8 specifies five summary values: minimum, first quartile (25%), median,
third quartile (75%), maximum, displayed with a boxplot. The principal published
speed range is Q1–Q3, the central half of point means. The report's boxplot
explanation includes extrema/outliers rather than silently dropping them
(R p. 73). A single-point session is a point mean, not a national advertised range.

Neither supplied document specifies the interpolation algorithm for quartiles.
The app uses **Type 7 linear interpolation**: sort the point means, calculate
index `(n - 1) * p`, and interpolate between adjacent values for `p = .25, .5, .75`.
This convention is named in exports/tests and is not described as uniquely
mandated by MIC. The boxplot shows actual minimum/maximum, Q1, median and Q3,
without a pass/fail bitrate threshold.

## Reference server and common process

G pp. 5–6 requires the common MIC measurement software based on the FCC mobile
software, permitting necessary updates that do not change its basic functions.
The shared server is at a major domestic IX with direct connectivity and at least
1 Gbps between IX and server. The server must prevent measurement while congested;
20 simultaneous clients is an example used in the experiment, not the three
parallel connections within one phone's speed test.

R p. 85 describes queue-position monitoring and starting when the position reaches
zero. R p. 93 shows server configuration examples `MaxNumActiveSessions: 10` and
`MaxNumWaitingSessions: 100`, illustrating that server admission limits are
configurable. Neither document supplies an admission-control URL, request/response
schema, authentication scheme, speed-test path or upload-acknowledgment format.
No actual measurement server address or credentials are supplied.

The appendix describes fetching `testserver.json` and `scenario.json` by FTP,
then uploading result TSV files by FTP (R pp. 76, 91–92). The app's local/export
workflow does not automatically perform the common operator submission process.
Do not send results to an invented or publicly guessed server.

R p. 92 provides this illustrative scenario, with values represented as strings:

```json
{
  "Common": { "Count": "1", "Autorepeat": "1", "Idel": "5" },
  "Download": { "Time": "20", "Session": "3", "Count": "1", "Filesize": "20" },
  "Upload": { "Time": "20", "Session": "3", "Count": "1", "Filesize": "20" },
  "Latency": {
    "Interval": "500000", "Time": "30000000",
    "numberOfPacket": "60", "Timeout": "2000000"
  }
}
```

`Time` in Download/Upload and `Idel` are seconds; Latency timing values are
microseconds; `Filesize` is MB. The printed illustration includes comments and
typographic punctuation and is not a complete wire protocol. `Count: 1` in this
example does not override the guideline's three repetitions at each location.

The appendix also gives inconsistent preliminary server-selection probes:
R p. 76 describes five seconds at one-second intervals; p. 77 describes twenty
seconds at 500 ms intervals and selecting the lowest worst-case latency. G's
shared single-location server requirement does not resolve this implementation
detail. Selecting a configured campaign endpoint avoids inventing a normative
server-selection algorithm.

## Campaign conditions and required reference data

| Requirement | Source / implication |
| --- | --- |
| Outdoor, stationary measurement | G p. 4. A moving drive-test run cannot be relabelled as this reference campaign. |
| Office/business district window | 12:00–18:00 Japan time, G p. 5 |
| Residential district window | 15:00–21:00 Japan time, G p. 5 |
| Frequency | At least annually, timing chosen by the operator, G p. 5 |
| Campaign duration | Within two months, G p. 6 |
| Device grouping | Matching supported frequencies, communication standards and OS, G p. 5 |
| Reference observations | Location/time, actual RAT, device information, signal strength, delay and packet loss, G p. 5 |
| Publication | All point results with location/time, RAT and device context; national and appropriate regional distributions, G pp. 8–10 |

The supplied guideline does not explicitly restrict the time windows to weekdays.
SoftBank's current implementation states weekdays; that operator practice must
not be misquoted as text present in the 2015 guideline. Store the `Asia/Tokyo`
timezone and the selected district category with each session.

Geographic campaign selection uses ten cities: three cities in each of three
population bands plus Tokyo's special wards. Approximately 300 cells of 500 m
square are selected from high-employment or high-resident-population areas,
with at least 14 cells per city, seven of each category. Five random points per
cell give approximately 1,500 points, with three backup points and a 50 m square
allowance around a designated point (G pp. 4–5). These geographic operations
remain omitted as requested.

Actual radio technology and signal strength are reference observations; a manual
radio-profile choice is not a measurement of them. Record an available modem
snapshot or explicitly report unavailable fields. R p. 44 does not require
artificially forcing all operators onto the same radio standard before measurement;
it records the standard so results can be grouped afterward.

Preserve start/end timestamps, direction, three connection measurements, warm-up
and measured byte/time values, packet counters, raw RTTs, outcomes, errors,
endpoint/scenario settings, device/radio context, campaign/point labels and
aggregation conventions. A local result/export is supporting evidence. It does
not establish the common MIC software equivalence, IX placement, server admission,
operator-neutral site selection, geographic completeness or public disclosure.
