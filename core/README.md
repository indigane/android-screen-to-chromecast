# castmirror core

The pure-JVM protocol core of a standalone Chromecast screen-mirroring sender: discovery, the Cast
v2 control channel, OFFER/ANSWER negotiation, AES-128-CTR frame encryption, RTP packetization,
RTCP, retransmission and pacing.

**No Android APIs, no third-party dependencies.** It builds and tests with `kotlinc` plus a JDK,
and is designed to be dropped into an Android app module as-is — the Android layer supplies only
screen capture, the encoder, and the UI.

---

## Contents

- [Build and test](#build-and-test)
- [Architecture](#architecture)
- [Offer profiles: GMS vs openscreen](#offer-profiles-gms-vs-openscreen)
- [API for the Android layer](#api-for-the-android-layer)
  - [SessionController](#sessioncontroller)
  - [FrameSource](#framesource)
  - [EncodedFrame](#encodedframe)
  - [H.264 requirements](#h264-requirements)
  - [Timestamps](#timestamps)
  - [State machine and callbacks](#state-machine-and-callbacks)
  - [Threading](#threading)
- [Discovery](#discovery)
- [castprobe](#castprobe)
- [Divergences and unverified points](#divergences-and-unverified-points)

---

## Build and test

```bash
source /home/claude/work/tools/kotlin-env.sh   # kotlinc 2.3.20 + JDK 21
./core/build.sh                                # compile main + tests, run the suite
./core/build.sh --jar                          # also build castprobe.jar
```

`build.sh` exits non-zero if any check fails. There is no JUnit: the suite is a plain `main()`
(`core/src/test/kotlin/app/castmirror/core/TestMain.kt`) with a ~90-line assertion harness, so it
runs anywhere a JVM does and needs no dependency resolution.

Everything is exercised in memory — the end-to-end test drives a fake Chromecast over piped
streams and a fake UDP transport, all the way from `TLS connect` to RTP packets on the wire.

---

## Architecture

```
app.castmirror.core
├── SessionController          orchestrator: discovery result in, pixels out
├── CastConstants              app ids, protocol constants
├── util/
│   ├── Json                   hand-rolled JSON model, parser, writer (order-preserving)
│   └── Bytes / ByteWriter     big-endian helpers, hex
├── discovery/
│   ├── MdnsMessage            DNS/mDNS wire codec (PTR/SRV/TXT/A, name compression)
│   ├── MdnsDiscovery          MulticastSocket client + MdnsResolver
│   └── CastDevice             resolved receiver: fn, md, ca, ve, st, host, port
├── cast/channel/              the TLS control channel
│   ├── CastMessage            hand-rolled protobuf + 4-byte BE length framing
│   ├── CastSocket             stream abstraction (+ trust-all TLS factory)
│   ├── ConnectParams          the CONNECT payload shape
│   ├── CastChannel            reader/writer threads, requestId correlation, PING/PONG
│   └── ReceiverMessages       RECEIVER_STATUS and friends
└── cast/streaming/
    ├── messages/              OFFER/ANSWER/CAPABILITIES + SessionMessenger (4 s reply timeout)
    │   └── OfferProfile       which sender dialect to speak
    ├── crypto/FrameCrypto     AES-128-CTR, one keystream per frame
    ├── rtp/RtpPacketizer      12-byte RTP + 7-byte Cast header, byte-oriented chunking
    ├── rtcp/                  SenderReportBuilder, CompoundRtcpParser, NTP, frame-id expansion
    ├── Sender                 per-stream window, ACK/NACK, kickstart, RTT, stats
    ├── SenderPacketRouter     one send thread (10 ms bursts) + one receive thread
    └── UdpTransport           DatagramChannel impl + in-memory fake
```

The wire format is a port of the [openscreen](https://chromium.googlesource.com/openscreen/)
project's `Sender` — `sender_impl.cc`, `rtp_packetizer.cc`, `frame_crypto.cc`, `compound_rtcp_*.cc`
and `sender_packet_router.cc`. openscreen does not build the OFFER itself (`sender_session.cc` is a
separate concern); this core builds the OFFER in Kotlin, in the two documented dialects.

---

## Offer profiles: GMS vs openscreen

Two incompatible-in-the-details dialects exist, and the one you send must match the receiver app
you launch. `OfferProfile` captures the difference.

| | `GMS_SYSTEM_MIRRORING` (**default**) | `OPENSCREEN_CHROME` |
|---|---|---|
| app id (A/V, audio-only) | `674A0243`, `8E6C866D` | `0F5096E8`, `85CDB22F` |
| audio codec | `aac` | `opus` |
| video codecs | `h264` (+`vp9` opt-in) | `h264`, `vp8` |
| audio payload type | **127** | 96 (opus) / 97 (aac) |
| first video payload type | **96** | 101 (h264) / 100 (vp8) |
| second video payload type | 103 (vp9) | 100 (vp9 → VP8's value) |
| `targetDelay` | **100** | 400 |
| `storeTime` | = `targetDelay` | *(not emitted)* |
| `offer.udpPort` | **2346** | *(not emitted)* |
| `intraRefreshEnable` | `false` | *(not emitted)* |
| `codecParameter`, `protection`, `receiverRtcpDscp` | *(omitted)* | `""`, `""`, optional |
| `profile` / `level` | `"main"` / `"4"` | `""` / `""` |
| `errorRecoveryMode` | `"castv2"` | `""` |
| `rtpExtensions` | `["adaptive_playout_delay"]` | *(none)* |
| `maxBitRate` / `maxFrameRate` | 6000000 / `"60000/1001"` | 5000000 / `"30"` |
| resolution | 1280×720, `{height,width}` | 1920×1080, `{width,height}` |
| SSRCs | fixed 1 / 11 / 12 | random, audio `[1,50000]`, video `[50001,100000]` |
| AES key + IV | one pair shared by all streams | fresh pair per stream |
| `seqNum` start | 1 | large random |
| LAUNCH extras | none | `language`, `supportedAppTypes` |

GMS is the default because it is what every Android phone sends, and therefore the best-tested
path on real Chromecast firmware. Google's own sender has **no** launch fallback — it launches one
app id and gives up — so the default ladder's second rung (`0F5096E8` + openscreen) is our
addition, and reaching it is a signal worth logging rather than a routine event.

`GmsProfileTest` compares the generated OFFER against a fixed reference OFFER key by key.

---

## API for the Android layer

### SessionController

```kotlin
val controller = SessionController(
    host = device.host,          // from CastDevice / NsdManager
    port = device.port,          // always the SRV port, never a constant
    config = SessionConfig(
        launchLadder = SessionConfig.ladderFor(device.hasVideoOutput()),
        resolutions  = listOf(Resolution(1280, 720)),
        maxBitRate   = 6_000_000,
        connectParams = ConnectParams.gms(
            appPackage     = context.packageName,
            deviceModel    = Build.MODEL,
            deviceProduct  = Build.PRODUCT,
            androidRelease = Build.VERSION.RELEASE,
        ),
        // audioCodecs stays null => video-only. See "Audio" below.
    ),
    frameSourceProvider = { stream -> MediaCodecFrameSource(stream, mediaProjection) },
)
controller.addListener(myListener)
controller.start()      // blocks until STREAMING, then pumps on background threads
...
controller.stop()       // sends STOP + CLOSE, tears everything down. Idempotent.
```

`start()` runs the whole bring-up and throws `SessionException` (carrying the `SessionState` it
failed in) on any error. Everything the Android layer needs afterwards is on the controller:
`state`, `launchedAppId`, `activeProfile`, `sessionId`, `transportId`, `answer`, `streams`,
`router`, `failure`.

Sockets are injected (`channelProvider`, `udpProvider`), which is why the whole flow is testable
in memory — and how you would add a proxy or a packet log.

**Audio.** `audioCodecs` defaults to `null`, meaning video-only. GMS always offers audio, but it
captures system audio with `REMOTE_SUBMIX` (`CAPTURE_AUDIO_OUTPUT`) or an `AudioPolicy`
(`MODIFY_AUDIO_ROUTING`) — both `signature|privileged` and unobtainable on GrapheneOS. The public
substitute is `AudioPlaybackCaptureConfiguration` off the same `MediaProjection`, which captures
strictly less. A video-only OFFER is byte-identical to the audio+video one minus the index-0
stream, with video moved to index 0, and `sendIndexes` resolution follows automatically.

### FrameSource

One `FrameSource` is created per negotiated stream. This is the entire contract:

```kotlin
interface FrameSource {
    fun nextFrame(timeoutMs: Long, nextFrameId: Int): EncodedFrame?
    fun requestKeyFrame() {}
    fun requestBitrate(bitsPerSecond: Int) {}
    fun close() {}
}
```

**Frame-id ownership is the sender's, not yours.** `nextFrameId` is passed in and the returned
frame *must* carry `frameId == nextFrameId`; a frame with any other id is skipped.

This is not a stylistic choice. The sender sheds frames under congestion — when the in-flight
media budget or the 120-frame id span is exhausted, `enqueue` drops the frame and **does not
consume the id**. A source keeping its own counter would desynchronise on the very first drop, and
then every subsequent frame would be rejected. libcast has the same rule: its encoder driver calls
`GetNextFrameId()` before producing each frame.

A shed frame breaks the reference chain — the next delta frame references a frame the receiver
never saw. That is expected and handled: the receiver sends a PLI, and `requestKeyFrame()` follows.

`requestKeyFrame()` is called on every pump iteration while the sender needs one, so rate-limit it
(GMS allows one per second) and map it to:

```kotlin
codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
```

Note that `needsKeyFrame()` is true before the first key frame is enqueued, so the first frame you
return should be one.

### EncodedFrame

```kotlin
EncodedFrame(
    frameId           = nextFrameId,        // dictated by the sender; ids start at 0 (FrameId::first())
    isKeyFrame        = (info.flags and BUFFER_FLAG_KEY_FRAME) != 0,
    referencedFrameId = if (isKey) nextFrameId else lastKeyFrameId,
    rtpTimestamp      = base + info.presentationTimeUs * 90_000 / 1_000_000,
    data              = accessUnit,         // UNENCRYPTED; the core encrypts
    captureTimeMs     = info.presentationTimeUs / 1000,
    newPlayoutDelayMs = 0,                  // non-zero only to change delay mid-session
)
```

- **`referencedFrameId`** must equal `frameId` for a key frame (enforced). For a delta frame, GMS
  uses the **last key frame id** rather than `frameId - 1`, which is more robust under loss; either
  is legal.
- **`data` is plaintext.** The core runs AES-128-CTR over the whole access unit and only then
  chunks the ciphertext into 1449-byte RTP payloads. Never encrypt per packet — the keystream must
  continue across packet boundaries.
- **`captureTimeMs`** should be a `CLOCK_MONOTONIC`-based value (`presentationTimeUs / 1000`).
  It drives in-flight accounting and the sender report's NTP↔RTP anchor.

### H.264 requirements

Cast has **no out-of-band codec-configuration channel** — no `avcC`, no `codecPrivate`, nothing in
the OFFER. The receiver's decoder sees only RTP payload bytes.

- **Annex-B** start codes, not AVCC/length-prefixed.
- **SPS and PPS prepended in-band to every IDR.** Set
  `MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES` (`"prepend-sps-pps-to-idr-frames"`, API 29+) and
  catch the `IllegalArgumentException` some encoders throw, falling back to prepending the cached
  CSD manually. GMS does exactly this try/catch.
- The `BUFFER_FLAG_CODEC_CONFIG` buffer itself must **never** be sent as a Cast frame. Cache it,
  drop it.
- **No B-frames.** Cast's frame model is a linear chain of `referencedFrameId`; out-of-order decode
  is not representable. Set `KEY_MAX_B_FRAMES = 0`.
- Drop every non-key frame until the first key frame after start or re-sync (GMS does).

VP8/VP9 have no CSD problem — key frames are self-describing.

### Timestamps

| Clock | Source | Used for |
|---|---|---|
| `presentationTimeUs` | `MediaCodec.BufferInfo`, `CLOCK_MONOTONIC` | frame ordering, RTP timestamp |
| RTP timestamp | 90 kHz video / 48 kHz audio ticks | RTP header, sender report |
| NTP timestamp | wall clock since 1900 | sender report (computed by the core) |

`rtpTimestamp` is in the stream's timebase (`NegotiatedStream.rtpTimebase`): 3000 ticks per frame
at 30 fps video, 480 per 10 ms Opus frame. It must be **strictly** monotonically increasing —
`MediaCodec` can emit equal presentation times on a static screen, so coalesce or skip such frames.
Never derive it from `System.currentTimeMillis()`; NTP corrections make it jump backwards.
`RtpTime.ticksFromMicros(micros, timebase)` does the conversion.

### State machine and callbacks

```
IDLE → CONNECTING → CONNECTED → LAUNCHING → NEGOTIATING → STREAMING → STOPPING → TERMINATED
                                                                                 └→ FAILED
```

```kotlin
interface SessionListener {
    fun onStateChanged(from: SessionState, to: SessionState) {}
    fun onApplicationLaunched(appId: String, status: ReceiverStatus) {}
    fun onNegotiated(answer: Answer, streams: List<NegotiatedStream>) {}
    fun onCapabilities(capabilities: ReceiverCapabilities) {}
    fun onEnded(cause: Throwable?) {}          // cause == null for a clean stop
}
```

The session tears itself down and moves to `FAILED` when the control channel dies, when the app
sends `CLOSE`, or when an unsolicited `RECEIVER_STATUS` no longer lists our `sessionId` (the user
pressed Home, or another sender took over).

`onNegotiated` is where the Android layer learns what to encode. Each `NegotiatedStream` carries
`codecName`, `rtpTimebase`, `index`, `profile`, the `Sender`, and **`effectiveResolution`** — the
offered resolution unless the receiver's `display.dimensions` was smaller in *both* axes, in which
case GMS's rule replaces it. Encode to that.

Per-stream counters are on `NegotiatedStream.sender.stats()`: packets/octets sent, frames enqueued
and dropped, retransmissions, kickstarts, smoothed RTT, checkpoint frame id.

### Threading

Deliberately minimal, plain threads and locks — no coroutines, nothing that is not in the JDK.

| Thread | Owner | Job |
|---|---|---|
| `cast-channel-reader` | `CastChannel` | de-frame and dispatch inbound `CastMessage`s |
| `cast-channel-writer` | `CastChannel` | drain the write queue so callers never block on the socket |
| `cast-channel-heartbeat` | `CastChannel` | PING every 5 s (disable with `heartbeatIntervalMs = 0`) |
| `cast-rtp-send` | `SenderPacketRouter` | 10 ms burst pacer: RTCP first, then RTP up to the budget |
| `cast-rtcp-recv` | `SenderPacketRouter` | blocking receive, demultiplex by SSRC |
| `cast-pump-video` / `-audio` | `SessionController` | one per negotiated stream: `nextFrame` → `enqueue` |

All are daemon threads. `Sender`'s public methods are `@Synchronized`, so the send and receive
threads may both call in. `SessionListener` and `CastChannelListener` callbacks run on the
reader/pump threads — **post to your own handler before touching UI.**

`SessionController.start()` blocks the calling thread until `STREAMING`, so call it off the main
thread.

---

## Discovery

`NsdManager` is the primary path on Android (it is AOSP and works on GrapheneOS). This core
provides the fallback, which is worth having because it returns the TXT record verbatim — you want
`md` and `ca` exactly as published, and `NsdManager`'s TXT attributes are unreliable on Android
8–11 and its resolves are serialised.

```kotlin
val devices = MdnsDiscovery.discover(timeoutMs = 5_000)   // blocking
```

Needs `CHANGE_WIFI_MULTICAST_STATE` and a held `MulticastLock`. `MdnsResolver` is a pure,
side-effect-free accumulator if you want to feed it packets from elsewhere. Always also offer a
manual "enter an IP" escape hatch — mDNS is routinely broken by AP client isolation, mesh routers
and VLANs.

`CastDevice.hasVideoOutput()` (`ca & 0x01`) picks the launch ladder, exactly as GMS does.

---

## castprobe

A one-shot CLI that runs the whole control path against a real receiver and prints everything,
without sending media. Run it from a laptop on the Chromecast's LAN as the one-time device test.

```bash
./core/build.sh --jar
java -cp castprobe.jar app.castmirror.tools.CastProbeKt discover
java -cp castprobe.jar app.castmirror.tools.CastProbeKt 192.168.1.50
java -cp castprobe.jar app.castmirror.tools.CastProbeKt 192.168.1.50 --profile=openscreen
java -cp castprobe.jar app.castmirror.tools.CastProbeKt 192.168.1.50 --audio --vp9
```

`--profile=gms|openscreen` selects the dialect (default `gms`, matching the core). `--help` lists
the rest. Getting `ANSWER OK` with a `udpPort` proves the entire control path on that device.

---

## Divergences and unverified points

Deliberate divergences from GMS, all documented in-source:

1. **`videoCodecs` defaults to `["h264"]`,** not GMS's `h264` + `vp9`. This core starts one frame
   source per negotiated stream, so a two-codec offer implies two encoders. Pass
   `videoCodecs = listOf("h264", "vp9")` to reproduce GMS exactly.
2. **Video-only by default** (see [Audio](#framesource) above). GMS always offers audio.
3. **A second launch rung.** GMS has no fallback ladder.
4. **A 4000 ms ANSWER timeout and `seqNum` correlation.** GMS has neither — it relies on the outer
   Cast session teardown. libcast's `kReplyTimeout` is 4000 ms and we keep it.
5. **`STATISTICS` messages are parsed and dropped,** as GMS does; its real statistics come from
   the native library.

Points marked `// SPEC-UNVERIFIED:` in the source are things no primary source confirms: the
Android-TV payload-type quirk's firmware coverage, mDNS TXT keys `bs`/`nf`/`cd`/`rm`/`pk`, `ca`
bits 5–10 and 13+, the `rs` key's semantics, 1st-gen Chromecast TLS requirements, and the bodies of
`GET_STATUS`/`PRESENTATION` on the webrtc namespace. Two points once uncertain are now settled and
no longer carry the marker: the app id Google's system caster launches (`674A0243`) and the
`sdkType` vs `skdType` spelling (`sdkType`).
