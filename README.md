# HomeCam

HomeCam turns an old Android phone into a home CCTV camera, and lets you watch it live from another phone. Both phones run the same app: one in **Camera mode**, the other in **Viewer mode**.

- Live video over home Wi-Fi, Tailscale, or the internet over IPv6. No port forwarding, so it works behind Jio AirFiber's CGNAT.
- Every request needs an access token. Pairing takes one QR scan.
- Records to the camera phone's own storage on a loop. When the size limit is reached, the oldest footage is deleted.
- Motion detection saves a snapshot and shows a notification. You can configure it from the viewer.
- Live video is encoded only while someone is watching, which keeps the old phone cool.
- In-app updates come from GitHub Releases.

**Download:** get the latest APK from [Releases](https://github.com/kumarnitish378/HomeCam/releases/latest).

---

## Contents

- [How it works](#how-it-works)
- [Install](#install)
- [Pairing and watching](#pairing-and-watching)
- [Ways to connect](#ways-to-connect)
- [Recording](#recording)
- [Motion detection](#motion-detection)
- [Updates](#updates)
- [HTTP API](#http-api)
- [Security](#security)
- [Building from source](#building-from-source)
- [Releasing](#releasing)
- [Troubleshooting](#troubleshooting)
- [Project structure](#project-structure)

---

## How it works

```
Camera phone (CameraStreamService, foreground)
  CameraX ──┬─ ImageAnalysis (YUV) ─ FrameEncoder ─┬─ MJPEG server :8080 ──┐
            │                                      └─ MotionDetector       │
            └─ VideoCapture (H.264) ─ RollingRecorder → 5-min MP4 files    │
                                                                           │
            HTTPS :8443 (RemoteTlsProxy, optional) ─ forwards to :8080 ────┤
                                                                           │
      Home Wi-Fi (192.168.x.x) · Tailscale (100.x.x.x) · Internet (IPv6) ──┘
                                                                           │
Viewer phone ── tries every route at once, uses the first that answers ────┘
  Camera list · Live screen · Recordings · Motion settings
```

Camera mode runs as a foreground service, so it keeps going with the screen off. It holds a CPU wake lock and a Wi-Fi lock while streaming.

---

## Install

1. Download `HomeCam-vX.Y.Z.apk` from [Releases](https://github.com/kumarnitish378/HomeCam/releases) on **both** phones.
2. Open it and allow **Install unknown apps** for your browser or file manager when Android asks.
3. Open HomeCam and choose a role:
   - **Use as Camera** on the old phone.
   - **Use as Viewer** on your daily phone.

Requirements: Android 8.0 (API 26) or newer. The camera phone should stay on its charger.

### Camera phone checklist

- Give it the camera and notification permissions.
- Tap **Allow Unrestricted** on the battery banner, or go to Settings → Apps → HomeCam → Battery → **Unrestricted**. On Realme/ColorOS, also allow auto-launch and background activity.
- If the phone has a charge limit (for example 85%), turn it on. A phone that stays on charge 24×7 can swell.
- Tap **Start Camera Service**.

---

## Pairing and watching

1. On the camera phone, the **Pair Viewer Phone** card shows a QR code.
2. On the viewer, tap **Scan QR**. To type the details instead, use **Enter Address & Token Manually** with the address, port and token.
3. The viewer saves the camera and opens the live screen.

The QR code holds every address the camera knows (LAN, Tailscale and internet), its access token, and its HTTPS certificate fingerprint:

```
homecam://pair?name=HomeCam%201&host=100.x.x.x&port=8080&t=<token>&h2=192.168.31.232&rh=<ipv6-or-duckdns>&rp=8443&fp=<sha256>
```

You also need to scan again after reinstalling the camera app or tapping ↻ on the pairing card (which makes a new token), because the token changes.

On the live screen:

| Control | What it does |
|---|---|
| Snapshot | Saves the current frame to the viewer's Gallery (`Pictures/HomeCam`) |
| Torch | Turns the camera phone's flash on or off |
| Switch lens | Switches between the front and back camera |
| Fullscreen | Hides the bars; pinch to zoom |
| 🎞 Recordings | Recording settings and the list of recorded segments |
| ⚙ Tune | Motion detection settings |
| ⓘ Info | Route in use, FPS, battery, temperature, lens |

---

## Ways to connect

The viewer tries all known routes in parallel and uses the first one that answers. The badge on the live screen shows which route is in use. If nothing answers, the "Reconnecting…" overlay lists each route and why it failed.

| Route | Works when | Setup |
|---|---|---|
| **Home Wi-Fi** | Both phones are on the same Wi-Fi | None |
| **Tailscale** | Both phones run Tailscale with the same account | Install [Tailscale](https://play.google.com/store/apps/details?id=com.tailscale.ipn) on both phones and sign in |
| **Internet (IPv6)** | The camera's router allows inbound IPv6 and the viewer's network has IPv6 | See below |

### Internet access over IPv6 (no Tailscale)

Jio puts home IPv4 behind CGNAT, but every device gets a public IPv6 address (`2409:…`). HomeCam can use that address directly.

1. On the camera phone, open ⚙ Settings → turn on **Internet access (IPv6)** → Save, then **Stop** and **Start** the camera.
2. The camera opens HTTPS on port **8443**. TLS uses a key stored in the Android Keystore, and the viewer pins its certificate fingerprint from the QR code. The access token is still required on every request.
3. In the router's IPv6 firewall, allow **inbound TCP 8443** to the camera phone.
4. Scan the QR again on the viewer.

The camera must be on Wi-Fi for this route. The viewer can use Wi-Fi or mobile data, as long as that network has IPv6.

**The IPv6 address changes.** Jio changes the prefix from time to time. When the viewer can reach the camera any other way, it picks up the new address automatically. To keep a fixed name that also works while you're away:

1. Create a free name at [duckdns.org](https://www.duckdns.org), for example `mycam`.
2. In camera Settings, enter the DuckDNS name and token.
3. The camera updates the name every time its IPv6 changes, and at least every 30 minutes. The QR then contains `mycam.duckdns.org`.

To test whether the port is open, turn off Wi-Fi and Tailscale on the viewer phone and open the following in Chrome:

```
https://[<camera-ipv6>]:8443/status?t=<token>
```

A "connection is not private" warning means the port is open (the certificate is self-signed, which is expected). A timeout means the router is blocking it.

---

## Recording

The camera phone records continuously, using the hardware H.264 encoder with no audio. Recordings are 5-minute MP4 segments stored in:

```
Android/data/<app id>/files/Movies/HomeCam/rec_yyyyMMdd_HHmmss.mp4
```

Before each new segment, the oldest segments are deleted to keep the folder under the limit and to leave at least 500 MB free on the phone.

| Setting | Options | Default |
|---|---|---|
| Recording | On / Off | On |
| Storage limit | 1–32 GB (camera), 1/2/4/8/16 GB (viewer) | 2 GB |
| Quality | Low ≈ 0.27 GB/h · Medium ≈ 0.54 GB/h · High ≈ 1.1 GB/h | Medium |

At 2 GB and Medium quality, about 4 hours of footage is kept.

You can change these settings on the camera (⚙ Settings) or from the viewer (🎞 Recordings). The viewer lists the segments. Tap one to download it over the active route and open it in a video player.

> **Recordings are deleted when the app is uninstalled.** Copy anything you need to keep first.

Some phones can't run live analysis and video recording at the same time. On those phones, live view keeps working and the camera screen shows a message.

---

## Motion detection

Every 5th frame, the camera compares a 64×48 brightness grid with the previous one. If more than the trigger percentage of grid points changed, it saves a JPEG snapshot and posts a notification on the camera phone. It then waits for the cooldown before triggering again. Only the newest 200 snapshots are kept.

| Setting | Range | Default |
|---|---|---|
| On / Off | — | On |
| Trigger (share of the image that must change) | 0.5–10% (lower = more sensitive) | 2% |
| Cooldown between alerts | 5–300 s | 30 s |

To configure it from the viewer, open the live screen → ⚙ Tune → **Save to camera**. On the camera phone, it's under ⚙ Settings → Motion detection.

---

## Updates

HomeCam checks this repository's releases every time it starts, and shows a dialog only when a newer version exists. You can also check by hand: on the mode picker screen, tap **Version x.y.z · Check for updates**.

The updater downloads only from `github.com/kumarnitish378/HomeCam/releases/download/`. It refuses an APK signed with a different key than the installed app, and then asks Android's installer to install it.

> In-app updates only work when every build is signed with the **same key**. See [Signing key](#signing-key).

---

## HTTP API

The camera serves plain HTTP on port **8080** (LAN and Tailscale) and, when enabled, HTTPS on **8443** (internet). Every endpoint requires the token, either as `Authorization: Bearer <token>` or as `?t=<token>`.

| Method | Path | Returns |
|---|---|---|
| GET | `/stream.mjpg` | `multipart/x-mixed-replace` MJPEG stream (plays in Chrome) |
| GET | `/snapshot.jpg` | One fresh JPEG (waits up to 3 s for a new frame) |
| GET | `/status` | JSON with FPS, viewers, battery, temperature, motion, recording, addresses and certificate fingerprint |
| POST | `/control/torch?on=1` | 204 |
| POST | `/control/lens?facing=back\|front` | 204 |
| GET / POST | `/config/motion?enabled=1&sensitivity=0.02&cooldown=30` | Motion settings JSON |
| GET / POST | `/config/recording?enabled=1&limitMb=2048&quality=MEDIUM` | Recording settings JSON |
| GET | `/recordings` | JSON list of segments, newest first |
| GET | `/recordings/rec_yyyyMMdd_HHmmss.mp4` | One segment |
| GET | `/motion` | JSON list of motion snapshots |
| GET | `/motion/{file}` | One snapshot |

Example:

```bash
curl -H "Authorization: Bearer $TOKEN" http://192.168.31.232:8080/status
```

---

## Security

- **Token on every request.** It's a 32-byte random token compared in constant time. An address is blocked for 10 minutes after 10 wrong tokens in 60 seconds.
- **Source allow-list on port 8080.** Only Tailscale (`100.64.0.0/10`), private LAN ranges and localhost are accepted. **Tailscale only** mode drops LAN access too. The server listens on IPv4 only, so the phone's public IPv6 address can't reach it.
- **Internet route is HTTPS only.** The camera's private key never leaves the Android Keystore, and viewers accept only the certificate whose fingerprint they got from the QR code.
- **Plain HTTP on the LAN.** Anyone on your Wi-Fi could see the token in transit. Use **Tailscale only** mode on networks you don't trust.
- **Motion snapshots and recordings stay in app storage** and are served only to authenticated viewers.

---

## Building from source

Requirements: Android Studio (recent stable) and JDK 17.

```bash
git clone https://github.com/kumarnitish378/HomeCam.git
cd HomeCam
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/
```

The project is Kotlin with Jetpack Compose, CameraX (core, camera2, lifecycle, video), a Ktor CIO server, OkHttp, ZXing and DataStore. It uses minSdk 26, targetSdk 35 and compileSdk 36.

The version comes from Gradle properties (CI sets them automatically):

```bash
./gradlew :app:assembleDebug -PappVersionName=1.2.0 -PappVersionCode=20
```

Without these properties, the build is versioned `0.0.0-dev`, and the in-app updater will always offer the latest release.

The debug build signs with `debug.keystore` in the project root (store and key password `android`, alias `androiddebugkey`).

---

## Releasing

[`.github/workflows/build.yml`](.github/workflows/build.yml) runs unit tests and builds the APK on every push to `main` and on every pull request. When you **publish a GitHub Release**, it also:

1. Sets `versionName` from the tag (`v1.2.0` → `1.2.0`) and `versionCode` from the run number.
2. Builds the APK and attaches it to the release as `HomeCam-v1.2.0.apk`.

To release: go to **Releases → Draft a new release**, create a tag such as `v1.2.0` on `main`, and click **Publish**. The APK appears under Assets about 5 minutes later.

### Signing key

Android installs an update only when it's signed with the same key as the installed app. Until this secret is set, CI signs every build with a **new throwaway key**. In that case you have to uninstall before each update, and in-app updates can't work.

Set it up once:

```bash
keytool -genkeypair -keystore homecam.keystore -storepass android \
  -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 \
  -validity 10000 -dname "CN=HomeCam,C=IN"
base64 -w0 homecam.keystore > ks.txt        # macOS: base64 -i homecam.keystore -o ks.txt
```

Then add the contents of `ks.txt` as a repository secret named **`DEBUG_KEYSTORE_BASE64`** (Settings → Secrets and variables → Actions). Keep `homecam.keystore` safe: losing it means every phone has to uninstall once more.

---

## Troubleshooting

| Problem | Fix |
|---|---|
| **"App not installed"** | The installed HomeCam was signed with a different key. Uninstall it (also from Secure Folder or other users), then install again. Setting up the [signing key](#signing-key) stops this from happening again. |
| Viewer can't connect | The "Reconnecting…" overlay lists each route and its error. Scan the QR again (the token or addresses may have changed). Make sure the camera service shows **STREAMING ACTIVE**. |
| `wrong token or blocked` | Scan the QR again. After 10 wrong attempts, wait 10 minutes. |
| `certificate does not match` | The camera app was reinstalled. Scan the QR again. |
| Internet route times out | Allow inbound TCP 8443 in the router's IPv6 firewall, and check that the viewer's network has IPv6. Use DuckDNS if the address changed while you were away. |
| Low FPS | Lower the resolution or quality in camera Settings. Low light makes the camera use longer exposures. Check that heat protection isn't active (above 42 °C the camera drops to 480p at 5 fps). |
| Stream stops with the screen off | Set battery to **Unrestricted** for HomeCam (and Tailscale), and allow background activity on Realme/Xiaomi/Oppo. |
| Recording error shown | The phone may not support recording and live analysis together, or storage may be almost full. |
| Update dialog says "different key" | Same cause as "App not installed": uninstall and install the release APK once. |

---

## Project structure

```
app/src/main/java/com/example/
├── MainActivity.kt            Mode picker, navigation, pairing hand-off
├── core/
│   ├── Settings.kt            DataStore settings (camera, motion, recording, remote)
│   ├── AuthToken.kt           Token generation, constant-time check, rate limit
│   ├── NetUtils.kt            LAN / Tailscale / global IPv6 discovery, allow-list
│   └── TlsIdentity.kt         Keystore EC key + self-signed cert for HTTPS
├── camera/
│   ├── CameraStreamService.kt Foreground service: camera, server, recorder, remote
│   ├── FrameEncoder.kt        YUV → NV21 → JPEG, fps cap, on-demand encoding
│   ├── FrameBus.kt            Latest frame + live state shared across components
│   ├── MjpegServer.kt         Ktor routes (stream, snapshot, status, config, files)
│   ├── MotionDetector.kt      Luma-grid motion detection and snapshots
│   ├── RollingRecorder.kt     Looping H.264 MP4 segments with size limit
│   ├── RemoteAccess.kt        HTTPS proxy for IPv6 + DuckDNS updater
│   └── CameraScreen.kt        Camera UI and settings
├── viewer/
│   ├── CameraRepository.kt    Saved cameras, parallel route probing, API calls
│   ├── MjpegClient.kt         MJPEG parser → Bitmaps
│   ├── CameraListScreen.kt    Saved cameras with online status
│   ├── LiveScreen.kt          Live view, controls, motion settings sheet
│   └── RecordingsSheet.kt     Recording settings + segment list and playback
├── pairing/                   QR generation and scanning
└── update/                    In-app updater (GitHub Releases)
```

The original design document is [`public/assets/aistudio/HomeCam_Android_Camera___Viewer_App_4107a5b3.pdf`](public/assets/aistudio/HomeCam_Android_Camera___Viewer_App_4107a5b3.pdf).
