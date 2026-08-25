# CastMirror

Open-source screen mirroring to a Chromecast — **no Google Play Services required**, built for
GrapheneOS and other de-Googled Android.

Android's built-in "Cast screen" feature is part of Google Play Services. On a de-Googled ROM it is
simply absent. CastMirror is a clean-room implementation of the Google Cast Streaming protocol —
device discovery, the TLS control channel, `OFFER`/`ANSWER` negotiation, AES-128-CTR frame
encryption, RTP packetization, RTCP feedback and retransmission — so it can mirror your screen to a
Chromecast using only the Android framework, with nothing from Google on the device.

> **Status: early (v0.2.0).** Screen mirroring works end-to-end with H.264. It has been tested on a
> limited set of hardware; expect rough edges and please file issues.

---

## Features

- Mirror your screen to any Chromecast (or Cast-enabled TV) on the local network.
- **No Google Play Services, no proprietary libraries** — Android framework + Kotlin only.
- Automatic receiver discovery (`NsdManager` plus a built-in mDNS fallback), with a manual
  "enter IP" escape hatch for networks where multicast is blocked.
- Adjustable resolution (720p / 1080p), frame rate, bitrate, and codec.
- Aspect-preserving letterboxing that follows device rotation smoothly.
- A Quick Settings tile.
- A built-in **Probe** button that runs the full control handshake against a receiver and reports
  the result — handy for diagnosing a device that will not connect.
- Wi-Fi and CPU wake locks held for the duration of a session so the stream survives the screen
  turning off.

---

## Current limitations

- **H.264 only.** VP8 is present in the protocol layer but not supported end-to-end; the default
  receiver profile is H.264.
- **No audio yet.** Capturing system audio requires a privileged permission that ordinary apps
  cannot hold, so mirroring is video-only for now.
- Resolution is fixed for the duration of a session (a Cast protocol constraint); rotation is
  handled by letterboxing rather than re-negotiating.
- First-generation Chromecast (2013) may need legacy TLS ciphers that the UI does not yet expose.

---

## Screenshots

<!-- TODO: add screenshots of the device list, an active session, and the Quick Settings tile. -->
_Coming soon._

---

## How it works

CastMirror is an implementation of the [Google Cast Streaming
protocol](https://chromium.googlesource.com/openscreen/) — the same protocol a Chromecast expects
from any Cast sender. The project is split in two:

- **`core/`** — a pure-JVM library (no Android APIs, no third-party dependencies) implementing the
  protocol: mDNS discovery, the Cast v2 control channel, `OFFER`/`ANSWER` negotiation, frame
  encryption, RTP/RTCP, retransmission and pacing. It has its own test suite that runs on any JDK.
  See [`core/README.md`](core/README.md) for the architecture.
- **`app/`** — the Android layer: screen capture via `MediaProjection`, an OpenGL compositor that
  letterboxes the captured screen into the fixed encode frame, an H.264 `MediaCodec` encoder, a
  foreground service that runs the session, and the UI.

When you start mirroring, the app requests screen-capture consent, launches the mirroring receiver
app on the Chromecast, negotiates a video stream, and streams encrypted H.264 over RTP with RTCP
feedback driving retransmission and bitrate adaptation. Two receiver "dialects" are supported so it
works with both Google's Android mirroring receiver and the Chrome mirroring receiver.

---

## Building

### With Gradle

```bash
./gradlew :core:coreTest      # run the protocol core's test suite
./gradlew assembleDebug       # -> app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 21 and an Android SDK with platform 36. The app module depends only on the Android
framework, the Kotlin standard library, and the `:core` module — no AndroidX, no Material, no
Compose.

### With GitHub Actions

Push to `main`, then download the APK from the workflow run:
**Actions → the run → Artifacts → `castmirror-debug-<sha>`** (GitHub serves it as a `.zip`; unzip to
get the `.apk`). The workflow runs the protocol test suite first, so a regression fails fast.

Toolchain versions (kept in step across the project): Gradle 9.3.1, Android Gradle Plugin 9.1.0,
Kotlin 2.3.20, JDK 21, compileSdk 36, minSdk 26.

### Signed release builds

Pushing a tag that starts with `v` (e.g. `v0.2.0`) triggers
[`release.yml`](.github/workflows/release.yml), which builds a signed release APK and attaches it to
a draft GitHub Release. It expects these repository secrets:

| Secret | What it is |
|---|---|
| `KEYSTORE_BASE64` | your PKCS#12 keystore, base64-encoded (`base64 -w0 release.p12`) |
| `KEYSTORE_PASSWORD` | the keystore password |
| `KEY_ALIAS` | the signing key alias |
| `KEY_PASSWORD` | the key password |

No keystore or secret is ever committed to the repository.

---

## Installing on GrapheneOS

```bash
adb install -r app-debug.apk
```

Or copy the APK to the device and open it — the file manager needs the "install unknown apps"
permission first.

On first launch the app asks for:

- **Notifications** — the ongoing notification carries the **Stop** action. If you deny it, the app
  still works; the notification is just hidden.
- **Screen capture** — requested through the system dialog each time you start mirroring.

The install-time network permissions (`INTERNET`, Wi-Fi state, multicast, wake lock) are not
prompted for.

---

## Using it

1. Put your phone and the Chromecast on the same Wi-Fi network (without AP/client isolation).
2. Open CastMirror; your receiver should appear under **Receivers**. If not, use **Enter IP…**.
3. Optionally press **Probe** to confirm the full handshake works on that device.
4. Press **Start** and grant screen capture. The status line walks through
   Connecting → Launching → Negotiating → Mirroring.
5. Stop from the app or the notification's **Stop** action.

### Settings

| Setting | Notes |
|---|---|
| **Resolution** | 720p (safe default) or 1080p. The encode frame is landscape; rotation is letterboxed into it. |
| **Frame rate** | 24 / 30 / 60 fps. 60 roughly doubles the bitrate demand. |
| **Bitrate** | Ceiling in kbps. The encoder is CBR but backs off automatically under packet loss. |
| **Video codec** | H.264 (recommended). VP8 is exposed but not fully supported. |
| **Receiver app** | Which receiver id / protocol dialect to launch. The default ladder tries Google's mirroring receiver, then Chrome's. |
| **Mirror system audio** | Disabled — not possible without a privileged permission. |
| **Verbose diagnostics** | Off by default. On, it logs a detailed per-second stats line plus RTCP/keyframe/retransmit events, for diagnosing quality problems. |

All settings and the selected device are remembered between launches.

---

## License

[MIT No Attribution](LICENSE) — do anything you like, no attribution required.
