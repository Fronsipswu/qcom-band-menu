# qcom-band-menu-app

Qualcomm Android app for locking bands, RAT and NR mode

## Mobile data tests

Open **Test script** on the network screen and select **NBTC**, **MIC** or **Custom**.
NBTC provides ICMP latency, FTP download/upload, browser webpage loading and YouTube
streaming. MIC provides a three-trial point sequence of parallel HTTP download,
upload and UDP latency/loss, plus individual component functions. Custom exposes
all functions, including generic HTTP GET/POST and editable parallel/UDP settings.
Save the selected function, then use the start/stop control. Each regime and
function remembers its own configuration.

NBTC presets follow the 2022 standard as amended in 2024 and the **2024 replacement
measurement guidelines**, effective 1 January 2025. Configure your campaign's
reference endpoints and select the modem's actual 3G/4G/5G mode and band. Radio
profile selection does not change the modem. Tests use Android's cellular data
network, independently of the modem screen's SIM tab.

Results include success ratios, latency, transfer rates, browser loading and
streaming observations, with per-attempt evidence and CSV/JSON export. The latest
20 NBTC/Custom sessions survive app restarts; MIC campaign records are retained
without that pruning limit. FTP passwords remain in memory only. FTP upload
files use unique names and remain on the server for campaign administrators to
clean up. Browser/streaming tests require the player to stay in the foreground;
YouTube quality is verified manually per attempt.

MIC follows the published July 2015 Japanese mobile effective-speed method as an
independent client. It reports three-trial point means and distributions of
comparable point means, with min/Q1/median/Q3/max boxplots and campaign exports.
It does not apply minimum-speed pass/fail thresholds or claim to replace MIC's
designated common software, servers and geographic campaign process.

See [NBTC_DATA_TESTS.md](NBTC_DATA_TESTS.md) and [MIC_DATA_TESTS.md](MIC_DATA_TESTS.md)
for endpoint preparation, exact parameters, source/page references, sampling
requirements and measurement limits.
GPS/maps, calls, SMS/MMS and operator-wide quarterly certification are outside the
data-test implementation.

## Building

The pinned build uses JDK 17, Android API 37 and Gradle's checked-in wrapper.
Composite checkouts of `miuix` and `AndroidLiquidGlass` are required. Follow the
checkout revisions and AndroidLiquidGlass adjustments in
[the CI workflow](../.github/workflows/ci.yml). `QBM_DEPS_DIR` can point to an
existing directory containing those two checkouts; by default they are expected
at the repository root. Configure the Android SDK using Android Studio,
`ANDROID_HOME`, or an untracked `local.properties`.

From this `app` directory on Linux/macOS:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

From PowerShell, use the same wrapper JAR (a Windows wrapper batch file is not
included):

```powershell
java -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :app:assembleDebug :app:testDebugUnitTest
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. CI performs
the same build and unit-test tasks and publishes the APK as a workflow artifact.
Live modem, cellular-routing, reference FTP/HTTP and YouTube behavior additionally
need testing on the target Android device.
