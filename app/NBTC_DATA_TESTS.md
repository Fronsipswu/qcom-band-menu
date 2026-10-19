# NBTC mobile data test reference

The test-script menu provides individual data tests with NBTC presets and editable
Custom configurations. A device run produces measurement evidence and a comparison
with the selected numerical targets. It does not establish an operator's quarterly
regulatory compliance: coverage, sampling locations, device preparation, approved
servers and reporting remain responsibilities of the measurement campaign.

This reference was checked on 20 October 2026 against the supplied documents in
`D:\Temp\NBTCRegulator` and the NBTC sources below. Page references are PDF page
numbers, starting at 1, unless an annex page is explicitly identified.

## Using the app

1. Enable the intended Android default-data SIM and select the desired modem RAT
   and band using the existing controls. Test traffic uses the available cellular
   network; the modem's displayed SIM tab does not select the data subscription.
2. Open **Test script**, choose a function, then an **NBTC preset** and radio profile
   or **Custom**. Each function has independently saved settings. Configure the
   endpoint, repetitions, idle interval and optional operator/site/session labels.
   Custom exposes payload size, deadline and protocol-specific parameters.
3. For an NBTC measurement, obtain the designated reference server IP, FTP account
   and files, HTTP page, or reference YouTube video from the campaign organizer.
   These documents contain no usable server addresses or credentials. Confirm the
   reference-content checkbox only for the actual campaign assets. Check the
   planned payload amount, especially 500 MB downloads repeated across a route.
4. Save, then use the network screen's start button. Stop ends the run and retains
   the partial session. Browser tests keep a visible page/player; leaving the app
   interrupts the current browser measurement. Verify YouTube quality separately
   for every attempt and play the entire video without pausing, seeking or changing
   speed. Without quality confirmation, playback evidence remains unverified.
5. Open **Test results** to browse sessions, numerical measurements, denominator
   counts, errors and individual attempts. Export CSV or JSON through Android's
   file picker. JSON includes settings, assessment and limitations; CSV contains
   sample rows and context. FTP passwords are excluded from preferences and
   reports and must be entered again after the app restarts.

The latest **20 sessions** are retained in private app storage across restarts.
Export evidence needed for longer campaigns before that history rolls over.
The app does not submit or publish an operator's quarterly report.

FTP upload creates a unique `qbm-<time>-<uuid>.bin` file inside the configured
directory for each attempt. Files are **not deleted remotely**, including a
possible partial file after interruption; arrange server-side cleanup. Custom
HTTP downloads require the exact expected resource byte count, and HTTP uploads
send a binary POST body to an endpoint that accepts it.

Webpage mode measures a restricted, stable reference page and HTTP(S) GET
subresources. Redirects, non-GET resource requests, workers and WebSockets are
unsupported; compressed responses that ignore `Accept-Encoding: identity` fail
verification. Configure the final HTTP URL for NBTC mode and final URLs for every
resource. A general interactive website may fail these measurement restrictions
even when it works in an ordinary browser. Browser timestamps and YouTube's
buffering/loaded-fraction observations are application measurements, not a packet
capture or frame-by-frame video analysis. Startup delay and later buffering are
recorded separately.

## Sources and precedence

| ID | Document | Relevant pages |
| --- | --- | --- |
| G | [Current NBTC Office measurement/reporting guidelines, 20 November 2024](https://standard.nbtc.go.th/getattachment/Standards/มาตรฐานคุณภาพการให้บริการ-(QOS)/ประกาศ-สนง-กสทช-แนวทางการวัด_2567.pdf.aspx?lang=th-TH), gazetted 19 December 2024, effective 1 January 2025 | Methods pp. 12–21; submission tables pp. 31–38; publication tables pp. 46–50 |
| G2022 | Supplied `ประกาศสำนกงาน-กสทช-เรอง-แนวทางการวดและการรายงานคาชวดคณภาพฯ-พ-ศ-2565.pdf`: superseded guidelines, dated 15 December 2022 | Repealed by G clause 2; retained for tracing discrepancies in supplied reports |
| N | Supplied `mobile-qos-q2-2569.pdf`: National Telecom public report for April–June 2026 | Mobile data pp. 5–8 |
| AIS | Supplied `(web) รูปแบบรายงานคุณภาพการให้บริการ (QOS) AWN Q2_2026.pdf`: Advanced Wireless Network public report for April–June 2026 | Mobile data pp. 13–20 |
| H | Supplied `การปฏิบัติตามกฎหมาย.html`: True compliance website shell | Contains the URL of a separately hosted HTML document; it is not a measurement-method specification |
| S | [NBTC Quality of Telecommunications Service Standard, 8 December 2022](https://standard.nbtc.go.th/getattachment/Standards/มาตรฐานคุณภาพการให้บริการ-(QOS)/ประกาศ-กสทช-คุณภาพการให้บริการ-(ฉบับปี-2565).pdf.aspx?lang=th-TH) | Formal definitions and targets, pp. 14–19, 21 |
| A | [NBTC Standard amendment no. 2, 11 October 2024](https://standard.nbtc.go.th/getattachment/Standards/มาตรฐานคุณภาพการให้บริการ-(QOS)/ประกาศ-กสทช-QoS-ฉบับ-2.pdf.aspx?lang=th-TH), effective 1 January 2025 | Addition of video-freezing KPI 2.3.6/1, p. 5 |
| E | [ETSI TS 102 250-2 V2.7.1 (2019-11)](https://www.etsi.org/deliver/etsi_ts/102200_102299/10225002/02.07.01_60/ts_10225002v020701p.pdf) | FTP 6.1.7; ICMP 6.3; streaming 6.5.4/6.5.6; HTTP 6.8.6 |

The [NBTC QoS document index](https://standard.nbtc.go.th/Standards/มาตรฐานคุณภาพการให้บริการ-(QOS).aspx)
lists S, A, the **2024 replacement measurement guidelines**, and reporting templates. Use the actual
standard for definitions and targets when a reporting template contains a typo;
use G for the prescribed measurement procedures.

The current index and [NBTC consumer-protection law index](https://tcp.nbtc.go.th/th/LawsRegulations/AnnouncementoftheNational.aspx)
were checked on 20 October 2026, together with targeted official-site/Royal Gazette
searches for 2566–2569 (2023–2026), later amendments and replacement methods. The
latest published mobile-data documents found were S as amended by A, plus G.
The 2024 guideline annex is scanned: its data-method and target tables were read
from rendered pages, not inferred from the incomplete PDF text layer. No later
published replacement was located; this is a dated source review, not a claim
that future operator-specific instructions cannot exist.

SHA-256 fingerprints of the reviewed files:

| File / ID | SHA-256 |
| --- | --- |
| G, downloaded PDF | `aeb96834f0565e0365266c92b7b23efcaddbc58ac5279593f4bff4936953580a` |
| G2022 | `27e5eacc7bd6ae1e1847f2e98369d5438e2153836ef92c9a5dd140f3c5a8a1fe` |
| N | `7e7eceab2a00ae21684d0981809d9cc993de710f45173d3ad8fd4f58fc9582a1` |
| AIS | `355a61c63f3d1730599e3be385978fdefacc14715e2f5c5aab884662c128f2a6` |
| H | `5625a1fb447c9cd3197def6f2d3f9eef6573aa097a142c39f3e00f78ae855bf1` |
| S, downloaded PDF | `83458c91ce3c18b92134cf2e27d435d5d76d61647f12a5fa5d0a5a12e79d505d` |
| A, downloaded PDF | `98dfb6248199b9383d41fe658398cc2946c01adeb46fa4fb5b89176cd91d2a42` |

## Presets

Keep technology, 5G mode, and use of 2600 MHz distinct. For FTP, the 2600 MHz
criterion applies to both NSA and SA. For RTT, HTTP and streaming, the stricter
category applies to **SA using 2600 MHz**; NSA using 2600 MHz stays in the ordinary
150 ms / 60 s / 720p category.

| Setting / target | 3G | 4G | 5G without 2600 MHz | 5G NSA with 2600 MHz | 5G SA with 2600 MHz |
| --- | --- | --- | --- | --- | --- |
| RTT threshold, strictly below | 500 ms | 150 ms | 150 ms | 150 ms | 110 ms |
| RTT qualifying attempts | >=80% | >=80% | >=80% | >=80% | >=80% |
| FTP download file | 15 MB | 30 MB | 30 MB | 500 MB | 500 MB |
| FTP upload file | 2 MB | 4 MB | 4 MB | 100 MB | 100 MB |
| FTP timeout | 600 s | 600 s | 600 s | 600 s | 600 s |
| FTP download success | >=80% | >=80% | >=80% | >=80% | >=80% |
| FTP upload success | >=70% | >=70% | >=70% | >=70% | >=70% |
| FTP download bitrate threshold | 0.75 Mbps | 2.5 Mbps | 2.5 Mbps | 5 Mbps | 5 Mbps |
| FTP upload bitrate threshold | 0.3 Mbps | 0.5 Mbps | 0.5 Mbps | 1.25 Mbps | 1.25 Mbps |
| Successful FTP transfers meeting bitrate | >=75% | >=75% | >=75% | >=75% | >=75% |
| Average FTP download target, KPI 2.3.11 | None | None | None | >=20 Mbps | >=20 Mbps |
| Average FTP upload target, KPI 2.3.11 | None | None | None | >=5 Mbps | >=5 Mbps |
| HTTP complete webpage size | 3.5 MB | 3.5 MB | 3.5 MB | 3.5 MB | 3.5 MB |
| HTTP completion deadline | 180 s | 60 s | 60 s | 60 s | 25 s |
| HTTP successful attempts within deadline | >=90% | >=90% | >=90% | >=90% | >=90% |
| YouTube video resolution | 360p | 720p | 720p | 720p | 1080p |
| Minimum source video duration | 180 s | 180 s | 180 s | 180 s | 180 s |
| Streaming timeout | 300 s | 300 s | 300 s | 300 s | 300 s |
| Streaming accessibility target | >=80% | >=85% | >=85% | >=85% | >=90% |
| Streaming reproduction success target | >=80% | >=85% | >=85% | >=85% | >=90% |
| Video freezing time proportion | <=5% | <=5% | <=5% | <=5% | <=5% |

Table sources: G pp. 17–20, 31–38; N pp. 5–8; AIS pp. 13–20. The following documented
corrections use S: 4G upload success is 70% (p. 15), 2600 MHz upload bitrate is
1.25 **megabits/s** (p. 17), and KPI 2.3.11 applies only to 5G using 2600 MHz
(p. 21). G2022 p. 43 and N p. 5 print 80% for 4G upload, although G2022 p. 28 says 70%.
G2022 pp. 30/44 and N p. 6 print 1.25 **kilobits/s**. G2022 pp. 34/46 and N p. 8 mislabel
the average-download technology as non-2600 MHz. These are not silently adopted.
The newer G pp. 32/34 and AIS pp. 14/16 correct the upload percentage and unit;
AIS p. 20 also correctly restricts both average-bitrate targets to 5G on 2600 MHz.
G pp. 38/50 still contain the incorrect non-2600 MHz label for the average-download
row. The governing S definition and AIS's correctly labeled 2026 report are used.

The documents specify Mbyte/kbyte without a binary/decimal definition. Presets use
decimal MB (1,000,000 bytes); the operator must confirm the exact reference-object
byte count and use Custom if the server assets differ. Mbps always means
1,000,000 bits per second. The app must retain exact expected and observed byte
counts in its evidence.

2G measurements are explicitly exempted by G p. 14. They are not included in the
NBTC presets. G p. 14 additionally exempts 3G measurements where the operator can
provide a technology above 3G in the prescribed areas; the 3G preset remains
available for campaigns where that exemption does not apply. AIS's report omits
3G while NT's includes it. HTTP POST remains a configurable diagnostic operation and has no
NBTC KPI in these documents. SMS, MMS, calls, maps and GPS are outside this scope.

## Measurement and aggregation

### ICMP RTT — 2.3.1

Send 32-byte ICMP echo payloads to the **literal IP address** of the designated
NBTC central server (G p. 12). A DNS name, HTTP request, TCP handshake, or ping to
an arbitrary public host is not the prescribed probe. No approved server address
or credentials are present in the supplied documents, so users must supply them.

Count each echo attempt. Timeouts and packet loss remain in the denominator.
The qualifying numerator contains received replies with RTT **strictly less than**
the profile threshold. Report attempts, replies, qualifying count, packet loss,
mean RTT of received replies and qualifying percentage. A ping timeout or interval
is an operational setting; G does not prescribe their numerical values.

### FTP transfer — 2.3.2, 2.3.3 and 2.3.11

Use a single FTP data session, a designated server IP, the profile file size, and
a 10-minute timeout. Configure port, credentials and remote path. A completed
transfer requires the expected byte count and a successful server completion
response. Short files, interrupted connections, login errors and timeouts do not
become successful transfers merely because some bytes moved. G allows an idle
interval chosen to reset the test environment before the next attempt (G pp. 16–17).

Keep these calculations separate:

* Success ratio: completed transfers / attempted transfers x 100.
* Bitrate ratio: completed transfers reaching the bitrate threshold / completed
  transfers x 100. Failed transfers affect the success ratio, not this denominator.
* Per-transfer rate: payload bits / data-transfer seconds.
* Mean rate: arithmetic mean of the individual rates; do not divide total bytes
  by total elapsed time and label that the required mean.

For the G reference to E section 6.1.7 Method B, the start is completion of the
FTP data socket TCP handshake and the finish is the last content packet. Socket
API timestamps approximate these packet events; connection/login time is not
included in the reported data bitrate. Download timing ends at the last payload
read. Upload timing conservatively ends at the server's successful completion
reply: the last local socket write can merely enqueue bytes in the phone's send
buffer and must not be treated as proof that the server has received them.
The upload measurement therefore includes completion-reply overhead, which can
understate packet-level throughput. Packet-level certification requires an
independently validated measurement chain.

KPI 2.3.11 formally averages rates at measurement positions. Without GPS and a
campaign-wide position dataset, a run's sample mean is useful supporting evidence;
it is not an assertion that all required positions were measured.

### Browser HTTP load — 2.3.4

Load the designated NBTC webpage through a browser over **HTTP**, with the complete
3.5 MB reference website received within the profile deadline (G p. 18).
The content includes the page's resources. A status 200 response, an HTML document
alone, or a timed download of an arbitrary binary file cannot demonstrate complete
webpage loading. A browser-based implementation must detect failed resources,
count the received reference content, disable cached reuse, and record completion
time. The server must supply a stable test page with a known complete byte count.

Record successful complete loads / all attempted loads and the completion-time
statistics. Custom HTTP/HTTPS and POST requests remain useful diagnostics but
must not be labeled equivalent to the prescribed browser measurement.

### YouTube streaming — 2.3.5, 2.3.6 and 2.3.6/1

Use a YouTube video at the profile resolution, with source duration at least three
minutes and a five-minute timeout (G p. 19). Run the measurement sequence without
simultaneous other tests so it observes the selected service under idle-equipment
conditions. The former separate-device clause in G2022 p. 16 is absent from G;
it is not treated as a current mandatory requirement. Complete
playback, access failures, premature ends and stalls must be observed in a player;
downloading a video file is not a playback test.

Retain separate attempted, accessed, playback-started and completed counts. G
defines reproduction success as one minus E's reproduction cut-off ratio: the
denominator is playback sessions that actually started. Access failure is reported
by the separate accessibility KPI. S uses broader wording for the reproduction
denominator; retaining all counters also permits completed / all-attempted reporting
without losing the evidence. An empty denominator has no result, not 0% or 100%.

The freezing KPI is frozen time / (frozen time + normal playback time) x 100 (A p. 5;
G p. 20; N p. 8; AIS p. 19). G explicitly references ETSI TR 101 578 V1.3.1
(2018-10), section 4.3.34. Initial access wait is distinct from a freeze after playback starts.
Keep raw stall events and durations. Player buffering-state observations are an
approximation of video freezing, since decoder or rendering stalls may not produce
that state. Results should identify the observation method.

YouTube may adapt resolution and restrict embedded playback. The operator must
verify the actual playback quality; a requested quality or a quality checkbox is
not proof that the entire video played at that resolution. Preserve this limitation
with exported results. A user cancellation is an incomplete observation and must
not silently disappear or become a successful sample.

## Campaign conditions and reporting limits

G pp. 12–16 require measurements Monday–Friday between 08:00 and 20:00, adequate
signal, idle source/destination equipment, test equipment capable of the maximum
system speed, an uncapped SIM, and a connection directly to the operator's own
radio network with the relevant technology selected. Special events with unusually
high demand are excluded. A profile selection records the intended technology;
it does not prove that the modem stayed on that technology or band. RAT changes,
roaming, Wi-Fi and VPN routing need to be accounted for in campaign review.

The guideline also discusses computer tethering/WAN and TCP window settings.
Those are campaign preparation instructions for the applicable equipment.

Sampling is geographic, not a universal minimum number per button press:

| Campaign group | Minimum samples / condition |
| --- | --- |
| Regional main/secondary routes | 120 |
| Regional community routes | At least 5 provinces, at least 1 district each, 60 per district |
| Bangkok/metropolitan routes | 120 each quarter |
| Expressways, Q1 | 120 |
| Specified monitored locations, Q2 | 40 per location |
| Five other community locations, Q3 | 40 per location |
| Specified urban rail routes, Q4 | 40 per line |
| Streaming community routes | 100 across communities in the region |
| Streaming Bangkok/metropolitan routes | 100 |

Regional quarterly rotation is Q1 northeast, Q2 south, Q3 central/east, Q4 north
(G p. 12). The same area tables now apply to 5G. G removes the old 100-samples-per-5G-area
rule and the old separate list of quarterly 5G cities in G2022. Streaming retains
its 100-sample rule. G p. 15 adds Yellow/Pink rail lines to Q4 coverage. For an
additional location requiring investigation, G p. 16 specifies at least 40 samples
outdoors, at 1.5 metres above ground.
Location names and campaign labels may be entered manually. Route maps, coordinates,
distances and proof of geographic coverage remain omitted as requested.

Exports should retain the selected profile or Custom settings, endpoints without
passwords, timestamps/timezone, sample outcomes, bytes, durations, errors and
aggregation denominators. Keep failed and cancelled observations distinguishable.
Do not merge different technologies, directions, endpoints or reference-object
settings into one unlabeled compliance percentage. Export raw evidence before the
20-session history limit removes older reports.

Operator NMS outage statistics, activation time, billing complaints and call-centre
service metrics require operator records and cannot be inferred from a handset
ping or a drive-test run. Quarterly reports, public disclosure, source-evidence
retention and geographic completeness require a separate reporting workflow.
