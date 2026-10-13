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
- Available as both an Android app and a command-line utility

<img height="600" alt="image" src="https://github.com/user-attachments/assets/dbd960de-2027-4221-931d-ef63e448bc7b" />
<img height="600" alt="image" src="https://github.com/user-attachments/assets/3a369f98-895e-4c53-94f1-f9e44227b51b" />


# Requirements

- Qualcomm device
- Root access
- Android 11 or newer for the app version
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


# Acknowledgements
Special thanks to [h3nnes](https://github.com/h3nnes) for developing the app version and for discovering the separate NR-SA and NR-NSA band-locking commands.

This project also benefited greatly from the documentation, source code, and QMI protocol definitions provided by the [libqmi project](https://github.com/linux-mobile-broadband/libqmi).

The DIAG helper's NR RRC decoder (`app/binary/rrc_nr_gen.c`) is generated with [asn1c](https://github.com/mouse07410/asn1c), the ASN.1 compiler by Lev Walkin ([original project](https://github.com/vlm/asn1c)) as maintained by mouse07410 and contributors, from the 3GPP TS 38.331 ASN.1 definitions. asn1c and its runtime are under the BSD 2-Clause license; see [app/binary/ASN1C-LICENSE](app/binary/ASN1C-LICENSE).
