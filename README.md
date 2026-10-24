# Qualcomm QMI Band Control

Apply cellular forcings such as RAT preferences, band locking, and 5G-NR modes through QMI commands over Android's QRTR interface. The project is available in both command-line and Android app versions.

# Features

- Read/modify forcing profiles for SIM 1 and SIM 2
- Select RAT preferences and band locking for for GSM, WCDMA, LTE, and NR
- Configure NR-SA and NR-NSA band masks independently (Credit: [h3nnes](https://github.com/h3nnes))
- Select SA+NSA, NSA-only, or SA-only NR operating modes
- Query modem-reported hardware-supported bands
- Restore band locks to hardware defaults
- Instant Cell & ARFCN locking (app only)
- Live cell lock: the neighbours the modem reports, one tap to lock to (app only)
- Live Network page: serving cell, LTE carrier aggregation, NR cells, antennas and neighbour cells, read from the modem over QMI (app only)
- Band toggles that apply app-wide: a band hidden there is hidden from the neighbour lists too (app only)
- Advanced network metrics with DIAG mode (experimental)
  - MIMO, Rank, MCS, modulation schemes, RBs, CSI-RS ports etc
  - PHY DL/UL throughput
  - Detailed NRCA readings
  - Antenna measurements per receive chain for every LTE and NR carrier (PCell and SCells)
- Available as both an Android app and a command-line utility

<img height="600" alt="image" src="https://github.com/user-attachments/assets/56620800-fa04-4f80-a2db-914f86331a60" />
<img height="600"  alt="image" src="https://github.com/user-attachments/assets/e31a72ee-fb34-4c1f-8686-a4baf68045c2" />
<img height="600" alt="image" src="https://github.com/user-attachments/assets/3a369f98-895e-4c53-94f1-f9e44227b51b" />


# Requirements

- Qualcomm device
- Root access
- Android 11 or newer for the app version
- For DIAG mode: the vendor DIAG library (`/vendor/lib64/libdiag.so`) and a running `diag-router`
- A terminal environment, such as Termux or ADB shell, for command-line usage

Compatibility may vary between Qualcomm modem generations, device manufacturers, firmware versions, and dual-SIM implementations.

# Usage and Installation

## Android App

Download the APK from the repository's **Releases** section, install it, and grant root access when prompted.

The application uses the native backend to communicate directly with the modem through QMI over QRTR.

## Command-Line Version

The command-line version can be launched through Termux or directly through ADB.

### Termux Installation

1. Download the compiled `qcom-band-menu` binary and place it in Android's `Download` folder:

   ```text
   /sdcard/Download/qcom-band-menu
   ```

2. Make sure Termux has storage access. If required, run:

   ```bash
   termux-setup-storage
   ```

3. Run the following command from the normal Termux shell:

   ```bash
   cp /sdcard/Download/qcom-band-menu "$PREFIX/bin/qcom-band-menu" && chmod 755 "$PREFIX/bin/qcom-band-menu" && printf '%s\n' '#!/data/data/com.termux/files/usr/bin/sh' 'exec su -c /data/data/com.termux/files/usr/bin/qcom-band-menu' > "$PREFIX/bin/band" && chmod 755 "$PREFIX/bin/band" && band
   ```

Do not enter a root shell before running the installation command. The launcher itself uses `su -c` when starting the native binary.

After installation, launch the command-line interface at any time with:

```bash
band
```

The launcher is installed into Termux's existing executable path, so restarting Termux or editing `.profile` is not required.
# Cell Lock

The Cell-Lock page has two modes, chosen by the toggle beside the SIM tabs.

**Live** (the default) shows the neighbour cells the modem is reporting right now,
read over QMI every second, in the same table the Network page uses (PCI, band,
EARFCN, RSRP, RSRQ). Tapping a row locks to that cell:

| Row | Command sent |
| --- | --- |
| LTE neighbour | LTE cell lock, EARFCN + PCI |
| NR neighbour (frequency only, the modem reports no NR PCI) | NR-ARFCN lock, with the SCS shown |
| Locked row | releases that RAT's cell lock |

An NR serving cell that carries a PCI is locked as a cell (ARFCN + PCI + SCS +
band). The enable/disable control at the top stops the modem reads (the list
holds still while a cell is picked; locks still apply) and the **Bands** toggles
choose which bands are listed — the same filter as the Bands page, so it applies
to every page. WCDMA and GSM neighbours are listed with their measurements, but
the modem has no cell lock for them, which the row says.

**Manual** keeps the original text fields for typing a lock by hand (NR-ARFCN,
PCI, multi-PCI, gNB allowlist, LTE PCI, multi-cell and PLMN).

# How Does It Work?

`qcom-band-menu` sends QMI commands through Android's **QRTR** transport using `AF_QIPCRTR` datagram sockets.

```text
Android app / Termux / ADB
            |
            v
      qcom-band-menu
            |
            | AF_QIPCRTR / SOCK_DGRAM
            v
       QRTR control service
            |
            | dynamic service discovery
            v
     Qualcomm QMI services
            |
            +-- NAS (service 0x03)
            |     RAT preferences
            |     GSM/WCDMA/LTE/NR band masks
            |     SIM subscription binding
            |     NR-SA/NR-NSA masks
            |     NR operating mode
            |
            +-- DMS (service 0x02)
                  hardware-supported band capabilities
```

### Dynamic QRTR Service Discovery

QMI service ports are assigned at runtime and may differ between devices or modem firmware versions.

Instead of hardcoding a QRTR port, the native backend sends a lookup request to the QRTR control endpoint and searches for:

```text
QMI NAS service: 3
QMI DMS service: 2
QMI version:     1
QMI instance:    0
```

After receiving the modem's node and port, the backend opens a connected `AF_QIPCRTR` socket to that service.

### QMI Packet Format

The QMI packets sent over QRTR use the following header:

```text
Offset  Size  Field
0x00    1     QMI message type
0x01    2     Transaction ID, little-endian
0x03    2     Message ID, little-endian
0x05    2     TLV payload length, little-endian
0x07    ...   TLV payload
```

Message types used by the tool:

```text
0x00  Request
0x02  Response
0x04  Indication
```

Each QMI response normally contains a result TLV:

```text
TLV ID: 0x02
Value:
  uint16 result
  uint16 error
```

A successful command returns:

```text
result = 0x0000
error  = 0x0000
```

### Subscription Binding

Before reading or changing a forcing profile, the NAS client is bound to the selected SIM using:

```text
QMI NAS message: 0x0045
```

The bind value used by the tested implementation is:

```text
0 = SIM 1
1 = SIM 2
```

Binding is socket-scoped. The bind request and all following reads or writes must therefore use the same NAS connection.

This allows the tool to read and modify the forcing profile stored for each SIM independently.

### Reading the Current Configuration

The current RAT and band preferences are read using:

```text
QMI NAS message: 0x0034
Get System Selection Preference
```

Important response TLVs include:

```text
0x11  RAT preference
0x12  GSM/WCDMA band bitmap
0x15  LTE B1-B64 bitmap
0x23  Extended LTE bitmap
0x2C  NR-SA band bitmap
0x2D  NR-NSA band bitmap
0x2B  Current NR operating mode when returned as a 4-byte field
```

The NR operating-mode encoding is:

```text
0 = SA + NSA
1 = NSA only
2 = SA only
```

### Applying RAT and Band Settings

Forcing commands are sent using:

```text
QMI NAS message: 0x0033
Set System Selection Preference
```

The tool sends narrow, independent updates instead of rewriting the entire modem configuration.

Examples:

```text
RAT only
LTE bands only
NR-SA bands only
NR-NSA bands only
NR mode only
```

Important setter TLVs include:

```text
0x11  RAT preference
0x12  GSM/WCDMA band bitmap
0x15  LTE B1-B64 bitmap
0x17  Change duration
0x24  Extended LTE bitmap
0x2B  Combined NR band bitmap
0x2E  NR operating mode
0x2F  NR-SA band bitmap
0x30  NR-NSA band bitmap
```

LTE bands use bit position `band - 1`.

For example:

```text
B1  -> bit 0
B3  -> bit 2
B28 -> bit 27
```

NR bands use the same rule inside a 64-byte bitmap:

```text
n1  -> bit 0
n41 -> bit 40
n78 -> bit 77
```

### Hardware Band Detection

The hardware-supported band list is queried through:

```text
QMI DMS service: 2
QMI DMS message: 0x0045
Get Band Capabilities
```

The response contains:

```text
Legacy GSM/WCDMA capability bitmap
LTE capability bitmap
Extended LTE band array
NR band array
```

The tool converts these fields into band lists and uses them to:

- Reject unsupported band selections
- Display the modem's supported GSM, WCDMA, LTE, and NR bands
- Restore band masks to the hardware-supported defaults

### Command Verification

Some Qualcomm modem firmware updates the stored forcing state asynchronously.

After a setter returns a success, the backend briefly waits and queries the modem again. This avoids showing stale values immediately after an accepted command.

The command flow is therefore:

```text
Discover service
     |
Open QRTR socket
     |
Bind selected SIM
     |
Send QMI setter
     |
Check result TLV
     |
Wait for state propagation
     |
Query current state
     |
Return updated values to the CLI or Android app
```

# DIAG Network Metrics

QMI only reports what the modem summarizes for the user: the serving cell, signal levels, and a list of carrier frequencies. Per-carrier details such as MCS, resource blocks, rank, SINR of each secondary cell, or the physical-layer throughput are only available in the modem's **DIAG** logs.

DIAG mode reads these logs through the **Diag Consumer Interface (DCI)**, the same interface used by on-device diagnostic tools. A separate root helper, `qbm-diagd`, subscribes to a fixed set of log codes, decodes them on the phone, and passes a per-carrier summary to the app.

```text
Android app (Network page)
            |
            | abstract socket "qbm_diagd", one request per second
            v
        qbm-diagd (root)
            |
            | dlopen
            v
  /vendor/lib64/libdiag.so
            |
            | DCI client
            v
        diag-router
            |
            v
   Modem (MPSS) DIAG log stream
```

### DCI Only

The helper registers as a DCI client and only adds its own log subscriptions. It never takes the main logging session, never changes the main log masks, and never switches the logging mode.

Other DIAG tools and USB diagnostics therefore keep working while DIAG mode is on. The helper is read-only towards the radio.

The DCI session is opened with:

```text
Diag_LSM_Init
diag_register_dci_client      peripheral: modem (MPSS)
diag_dci_set_version(1)       extended header: SIM of each log
diag_log_stream_config        subscribe to the log codes below
```

On Snapdragon 865/870 the modem is an external X55, reached as a remote processor (MDM) rather than through the SoC's own MPSS. libdiag only reports which processor's MPSS supports DCI once a client is registered, so the helper registers on the SoC side first, then asks: with no MPSS there and one on the external modem, it moves to a client on the external modem. When the answer is unclear and the driver reports a remote device, it keeps a client on both and decodes whichever delivers logs first.

### Log Codes

LTE:

```text
0xB173  PDSCH stat indication     MCS, modulation, RBs, layers, CRC, TB size
0xB126  PDSCH demapper config     Tx/Rx antennas, rank, transmission mode
0xB139  PUSCH Tx report           uplink TB size
0xB184  CA metrics                PCell/SCell EARFCN, PCI, band, bandwidth, state
0xB193  Serving cell measurement  per-carrier RSRP, RSRQ, SINR, also per RX chain
0xB179  Intra-frequency measure   RSRP, RSRQ of deactivated SCells
0xB168  Random access MSG2        timing advance
```

NR:

```text
0xB887  PDSCH status              MCS, modulation, RBs, layers, TB size
0xB883  UL physical channel       uplink TB size
0xB97F  Measurements              SS-RSRP, SS-RSRQ, RSRP per RX chain
0xB8D8  SNR                       SS-SINR, CSI-SINR, also per RX chain
0xB8DD  SNR (X55 to X65)          SS-SINR, CSI-SINR, also per RX chain
0xB96A  Beam measurements (X65)   strongest beam's SNR
0xB8A7  CSF                       CQI, rank indicator
0xB825  RRC configuration         carriers, band, bandwidth, SCS
0xB823  Serving cell              PCell PCI, ARFCN, band
0xB88A  RACH attempt              timing advance
0xB821  RRC OTA                   CSI-RS ports
```

### Log Versions

The layout of each log code changes between modem generations. Every supported version is described in a layout table (`qbm_layouts.h`), so a packet is only decoded when its version is known and its length matches.

Unknown versions are skipped instead of being guessed.

The NR RRC messages carried by `0xB821` are decoded with an ASN.1 decoder generated from 3GPP TS 38.331 (see Acknowledgements), to read each cell's configured CSI-RS ports.


# Acknowledgements
Special thanks to [h3nnes](https://github.com/h3nnes) for developing the app version and for discovering the separate NR-SA and NR-NSA band-locking commands.

This project also benefited greatly from the documentation, source code, and QMI protocol definitions provided by the [libqmi project](https://github.com/linux-mobile-broadband/libqmi).

Thanks also to the [asn1c](https://github.com/mouse07410/asn1c) project, created by Lev Walkin ([original repository](https://github.com/vlm/asn1c)) and maintained by mouse07410 and other contributors. The DIAG helper's NR RRC decoder is generated with it from the 3GPP TS 38.331 ASN.1 definitions ([LICENSE](app/binary/ASN1C-LICENSE)).
