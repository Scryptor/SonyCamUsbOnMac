# App-level resilience for pull-based PTP/IP live view over weak Wi-Fi, and how to test it reproducibly

Scope: client-only techniques for ZV-E10 Viewer (macOS Swift/Network.framework + Android Kotlin). No camera firmware changes, no root in the shipped app.

Live view works like this:
- repeated PTP GetObject on handle 0xffffc002;
- over one TCP command/data channel, plus a separate TCP event channel, to port 15740;
- one PTP transaction is answered at a time;
- about half of the polls get AccessDenied ("frame not ready").

Researched 2026-10-25. Items under "Inferences" are my reasoning from the cited material plus the project facts in the brief. None of them has been tested on the camera.

---

## 1. Adaptive rate control for a pull-based, one-transaction-at-a-time frame stream

Covers RTT/gap/goodput estimation, request depth, delay- vs loss-based control, smoothing, fps vs frame size, and bounded latency.

### Takeaway
With strictly one PTP transaction outstanding, the stream is already self-clocked. It can never put more than about one frame into the network queue. So classic window control (BBR/LEDBAT/Copa/GCC) does not carry over as "how many requests to keep in flight".

What does carry over:
- **Their estimators:**
  - windowed-minimum base delay;
  - a short "standing" minimum;
  - harmonic-mean throughput with an error margin;
  - EWMA and percentiles over 3 s windows.
- **Their control shape:** multiplicative decrease of about 0.85, slow increase of at most 8%/s, hold states and hysteresis.

The app should use these to:
- pace polls so AccessDenied polls are not wasted;
- keep 15–20% headroom on a weak link so commands stay responsive;
- present an even cadence, even if it is lower.

Without a frame-size knob, frame rate is the only lever.

### Cited Findings
**GCC (draft-ietf-rmcat-gcc-02)** — [draft-ietf-rmcat-gcc-02](https://datatracker.ietf.org/doc/html/draft-ietf-rmcat-gcc-02)
- Delay variation is computed per group of packets sent within burst_time = **5 ms**.
- It is smoothed by a scalar Kalman filter (q = 10^-3, e(0) = 0.1), and samples above 3·sqrt(var) are clamped.
- Over-use threshold:
  - starts at **12.5 ms**;
  - adapts with **K_u = 0.01 / K_d = 0.00018**;
  - is clamped to **[6, 600] ms**.
- Over-use is signalled only if it persists **10 ms**, and never while the gradient is falling.
- The rate controller has Increase/Hold/Decrease states:
  - multiplicative increase of at most **8%/s**;
  - additive increase near convergence, with response_time = **100 ms + RTT**;
  - decrease to **0.85 × R_hat** (allowed range 0.8–0.95), where R_hat is measured over **0.5–1 s**;
  - the cap is A < 1.5·R_hat.
- The loss-based controller cuts the rate ×(1 − 0.5p) above **10%** loss, raises it ×1.05 below **2%**, and holds in between.

**LEDBAT (RFC 6817)** — [RFC 6817](https://www.rfc-editor.org/rfc/rfc6817.html)
- Queuing delay = filtered current delay − base delay.
- Base delay is the minimum over **10 one-minute buckets**.
- The current-delay filter uses ≥ **4 samples per RTT**, none older than one RTT.
- The target added delay is **≤ 100 ms**.
- The window gain is ≤ 1, and the window is halved on loss.
- The congestion timeout starts at 1 s and doubles; any cap must be ≥ 60 s.
- Rationale: queues build before loss occurs, so rising delay is the earlier signal.

**BBR (draft-ietf-ccwg-bbr-06, July 2026)** — [draft-ietf-ccwg-bbr](https://datatracker.ietf.org/doc/html/draft-ietf-ccwg-bbr)
- Min-RTT filter: **10 s**.
- ProbeRTT at most every **5 s**, lasting **200 ms**.
- Max-bandwidth filter: 2 ProbeBW cycles.
- Gains and thresholds:
  - Startup pacing gain **2.77**;
  - cwnd gain **2**;
  - drain **0.5**;
  - loss threshold **2%** per round;
  - β **0.7**;
  - **headroom 0.15**.
- BDP = bw × min_rtt. In-flight data above BDP builds a standing queue.
- Application-limited delivery-rate samples are flagged so they do not lower the bandwidth estimate.

**Copa (NSDI 2018)** — [Arun & Balakrishnan, NSDI'18](https://www.usenix.org/system/files/conference/nsdi18/nsdi18-arun.pdf)
- Target rate = **1/(δ·dq)**, where dq = RTTstanding − RTTmin.
- RTTstanding is the minimum RTT over the last **srtt/2**, not the latest sample. The authors say this makes it robust to ACK compression and jitter, and they name wireless links as one cause of ACK compression.
- RTTmin is taken over the **shorter of 10 s and the flow's age**, to handle route changes.
- Default **δ = 0.5**.
- The velocity term doubles only after the direction has held for 3 RTTs.
- A "nearly empty queue" test, dq < 0.1·(RTTmax − RTTmin) over 4 RTTs, detects buffer-filling competitors.

**MPC / RobustMPC (video ABR)** — [Yin et al., SIGCOMM'15](https://conferences.sigcomm.org/sigcomm/2015/pdf/papers/p325.pdf)
- Throughput predictor: **harmonic mean of the last 5 chunks**.
- RobustMPC uses the lower bound **Ĉ/(1+err)**, where err is the **maximum absolute percentage error of the past 5 predictions**.
- Look-ahead horizon: **5**.
- QoE weights: λ = 1 for switching; μ = μs = 3000 (1 s of stall costs as much as a 3000 kbps quality drop).
- RobustMPC improved median normalized QoE by **15% / 10% / 5%** on the FCC / HSDPA / synthetic traces.

**TCP's own smoothing (RFC 6298)** — [RFC 6298](https://www.rfc-editor.org/rfc/rfc6298.html)
- SRTT and RTTVAR are EWMAs with α = 1/8 and β = 1/4.
- RTO = SRTT + max(G, 4·RTTVAR), with a minimum of 1 s.
- RTO doubles on each expiry; any cap must be ≥ 60 s.
- Retransmitted segments must not be used as RTT samples (Karn's rule).

**libgphoto2's handling of the same Sony live-view object** — [libgphoto2 camlibs/ptp2/library.c](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/library.c)
- Polls GetObject(0xffffc002) up to **50 times** with a **1 s** port timeout.
- On AccessDenied (a code comment says it happens when polling too fast) it sleeps **20 ms** and retries.
- On InvalidObjectHandle (a code comment says this can last up to about 1 s on the ZV-1) it sleeps **50 ms**.
- The returned blob begins with a little-endian offset to the JPEG start marker.

**Wi-Fi queueing and client radio factors**
- At the AP, a new queueing scheme plus an airtime-fairness scheduler cut latency under load by roughly 10× in the paper's tests. It was merged in Linux 4.11. — [Høiland-Jørgensen et al., "Ending the Anomaly", USENIX ATC'17](https://www.usenix.org/conference/atc17/technical-sessions/presentation/hoilan-jorgesen)
- Android 10+ low-latency Wi-Fi lock (WIFI_MODE_FULL_LOW_LATENCY): — [AOSP: Wi-Fi low latency mode](https://source.android.com/docs/core/connect/wifi-low-latency)
  - It disables 802.11 power save (doze).
  - It is active only when all hold: Wi-Fi is on with internet access, the app holds the lock in the foreground, and the screen is on.
  - Scan and roam tuning is vendor-specific.
  - WIFI_MODE_FULL_HIGH_PERF (API 12) is a separate mode.
- macOS AWDL (AirDrop/AirPlay/Bonjour peer discovery) is widely reported to take the Wi-Fi radio off-channel for about 50–100 ms roughly once per second, causing periodic latency spikes. — [Meter PSA](https://meter.com/mac-osx-awdl-psa) (vendor/community source)
- One Apple Developer Forums report says AWDL/AirDrop caused severe throughput loss and latency spikes on the macOS 27.2 beta, and that turning AirDrop off helped. — [Apple Developer Forums 847171](https://developer.apple.com/forums/thread/847171) (single report)

### Inferences
**What depth 1 means**
- PTP sequencing makes the depth on the wire effectively **1**. The apps' "pipelining" overlaps decoding with the next request; it does not put two requests on the wire.
- So the flow adds at most about one frame (22–60+ KB) to any queue, and per-frame latency is that frame's own transfer time.
- App-induced bufferbloat is therefore bounded. On weak Wi-Fi, latency grows from three other causes:
  - MAC-layer retries and rate drops;
  - cross-traffic in the AP queue;
  - TCP RTO backoff after loss.
- The controller's job is pacing and headroom, not congestion avoidance.

**Two regimes**

| Regime | Link | AccessDenied | fps |
|---|---|---|---|
| (a) Camera-limited | good | about 50% | about the camera's ~25 |
| (b) Link-limited | weak | about 0% | lower; per-frame time exceeds the camera frame period |

Tell them apart by the AccessDenied ratio together with TTFB inflation.

**What to log per transaction**
- Timestamps: t_req_sent, t_first_byte (StartData or Response), t_end_data, t_decoded, t_presented.
- Also bytes and outcome.

Derived signals:
- `ttfb` ≈ RTT plus the camera's service time;
- `goodput = bytes/(t_end − t_first_byte)`;
- inter-frame gap between successful frames;
- AccessDenied ratio.

**Estimators to borrow**
- `base_ttfb`: minimum over 10 s (BBR/Copa).
- `standing_ttfb`: minimum over the last ~3 samples or srtt/2 (Copa).
- `dq = standing − base`: the inflation caused by queuing and MAC retries.
- Goodput prediction: harmonic mean of the last 5 frames, divided by (1 + max error of the last 5) (RobustMPC).
- Display numbers: EWMA with α = 1/8 (RFC 6298).
- Gap percentiles: p50/p95 per 3 s window (Moonlight samples in 3 s windows; see §4).

**Proposed controller (GCC-shaped, untested)**
- Target poll rate f, with Increase/Hold/Decrease states.
- Over-use means either of:
  - `dq > ~50–100 ms` for 2–3 frames in a row (LEDBAT's 100 ms is the outer bound);
  - predicted utilisation `(size/goodput_lb + base_ttfb)·f > 0.8–0.85` (BBR's 0.15 headroom).
- On over-use: f ← max(f_min, 0.85 × measured fps), then hold for about 100 ms + RTT.
- Increase by at most 8%/s, or +1 fps/s near the last good rate.
- f_min of about 5–8 fps for focusing. This is a product choice, not sourced.

**Pacing in regime (a)**
- Estimate the camera frame period as the median interval between successful frames over the last ~30.
- After an AccessDenied, wait `max(0, expected_next − now − ttfb/2)` before polling again, instead of a fixed 20 ms or no wait at all.
- On a weak link this saves the airtime each wasted RTT would cost.

**Frame rate vs frame size**
- If the link is goodput-bound, cutting bytes per frame would be the better lever. If it is RTT-bound, cut fps.
- The ZV-E10 likely has no PTP live-view size knob (see Gaps). So the path is fps reduction plus a UI that tolerates freezes.

**Bounded latency**
- No frame queues: the latest frame wins (§2), and nothing is requested speculatively.
- Then age ≈ camera pipeline + ≤ 1 camera frame period + ttfb + transfer + decode. None of these terms is unbounded.

**Platform hygiene**
- Android:
  - Hold `WIFI_MODE_FULL_LOW_LATENCY` while live view is visible. Router mode qualifies.
  - Camera-AP / Wi-Fi Direct mode may not qualify because of the internet-access condition. Measure both.
- macOS:
  - Consider `NWProtocolTCP.Options.disableAckStretching = true`. It is the Mac analogue of the Android TCP_QUICKACK lesson in project memory.
  - Keep `noDelay = true` on the command channel.
  - Treat AWDL as a known source of ~1 Hz spikes.

### Gaps
- **Live-view size/quality property.** No source says whether the ZV-E10 exposes one over PTP/IP.
  - Sony's Camera Remote SDK page lists live-view monitoring, but its property list was not accessible ([Sony Camera Remote SDK](https://support.d-imaging.sony.co.jp/app/sdk/en/index.html)).
  - The camera menu has a "Display Quality" High/Standard setting. High uses more battery, and the camera may force Standard when hot ([Sony help guide, ZV-E10](https://helpguide.sony.net/ilc/2070/v1/zh-cn/contents/TP0001118600.html)).
  - It is unknown whether that setting changes the PTP live-view JPEG. Compare JPEG byte sizes at High and at Standard.
- **Pull-client precedent.** I found no published MJPEG-over-HTTP pull client with an adaptive poll-rate algorithm. The pull-specific controller above is my own synthesis.
- **Camera TCP internals.** The camera's TCP RTO and keepalive parameters are unknown.

---

## 2. Rendering: latest-frame-wins vs jitter buffer, dropping stale frames, frame age, stutter-free presentation

### Takeaway
For a viewfinder, keep latest-frame-wins: capacity-1 conflated hand-offs from network to decode to presentation. Then:
- decode off the main thread into reused buffers;
- present on the display's vsync;
- measure freezes with the W3C WebRTC definitions, so smoothness becomes a number.

A deliberate jitter buffer of at most one frame period makes sense only as an optional mode. Every buffered frame adds latency.

### Cited Findings
**W3C WebRTC stats** — [W3C webrtc-stats](https://www.w3.org/TR/webrtc-stats/)
- **Freeze:** a gap between two rendered frames ≥ max(3·avg, avg + 150 ms), where avg is the mean duration of the last 30 rendered frames.
- **Pause:** more than **5 s** since the last rendered frame.
- Counters:
  - `freezeCount`, `totalFreezesDuration`;
  - `pauseCount`, `totalPausesDuration`;
  - `framesDropped` (dropped before decode or for missing the display deadline);
  - `totalInterFrameDelay` and `totalSquaredInterFrameDelay` (these allow variance to be computed);
  - `jitterBufferDelay` and `jitterBufferTargetDelay`;
  - `totalProcessingDelay` (receipt to decode).

**Android bitmap reuse** — [Android: Manage bitmap memory](https://developer.android.com/topic/performance/graphics/manage-memory)
- `BitmapFactory.Options.inBitmap` (API 11+) reuses a mutable bitmap.
- From API 19, a buffer can be reused if the new image's byte count fits within the old allocation. Earlier versions need identical sizes.
- Google says reuse removes the allocation and deallocation cost.
- A suitable match is not guaranteed.

**Apple ImageIO decode timing** — [Apple ImageIO docs](https://developer.apple.com/documentation/imageio/kcgimagesourceshouldcacheimmediately)
- `kCGImageSourceShouldCacheImmediately` defaults to false. Decoding is then postponed until the image is rendered, which may happen on the main thread.
- Setting it to true decodes at image-creation time, for example on the decoder queue.

**Moonlight stats**
- Moonlight's stats overlay reports frames lost in the network separately from frames dropped because of network jitter. — [GL.iNet forum citing Moonlight stats docs](https://forum.gl-inet.com/t/performance-metrics-in-moonlight-streaming-app-worsened-after-upgrading/52484) (secondary)

### Inferences
**Pipeline**
- Socket reader → capacity-1 drop-oldest slot → one serial decoder → capacity-1 slot → presenter on the display link.
- Presenters:
  - macOS: `CADisplayLink` / `CVDisplayLink`, then `layer.contents = cgImage`.
  - Android: `Choreographer` / Compose `withFrameNanos`, or a `SurfaceView` / `TextureView` to avoid recomposition per frame.
- If two frames are ready within one vsync, show the newer one. Count the skipped frame as `framesDroppedLate`, the analogue of WebRTC's framesDropped.

**Decode cost**
- macOS: create the CGImage with ShouldCacheImmediately = true on the decoder queue. Optionally downsample to view size with `CGImageSourceCreateThumbnailAtIndex`.
- Android: keep an `inBitmap` pool of 2–3 bitmaps (one shown, one being decoded). Frames are about 1024×576.

**Frame age**
- No camera timestamp is known, so show `age = t_present − t_req_sent`.
- This is a lower bound: the camera returns its latest already-captured frame, and its internal delay is unknown.
- Calibrate the offset once with a glass-to-glass test (§6).
- Trigger a "stale" cue from age, not fps: for example age > 500 ms, or a WebRTC-defined freeze.

**Optional smoothing**
- When the user is not interacting, delay presentation by min(1 frame period, p50 jitter).
- Leave it off by default for focusing.

**During and after stalls**
- Never clear the view; keep the last decoded image (§4).
- After a reconnect, drop any frame whose request was sent before the reconnect.

### Gaps
- I did not retrieve WebRTC's adaptive jitter-buffer algorithm, so there are no cited parameters for an adaptive smoothing delay.
- I found no transcript of Apple's WWDC image-performance guidance. Only the ImageIO flag's semantics are cited.

---

## 3. Telling a stalled link from a dead one, keeping the session through stalls, and reconnecting fast and safely

### Takeaway
TCP gives no timely "dead" signal on a busy connection:
- Linux defaults take about 15 minutes;
- keepalive does not run while data is unacknowledged.

Apple advises not tearing down a connection that has temporarily lost viability, because Wi-Fi often returns and the same connection can carry on.

So the app needs a layered judgement:
- **Progress watchdogs** that count bytes received, never deadlines on whole transactions.
- **An independent liveness probe** on the event channel:
  - the PTP/IP Probe Request/Response packet types, if the camera answers them;
  - otherwise TCP keepalive on that idle socket.
- **OS path signals.**

Declare a link dead late (tens of seconds), because a false "dead" on this camera can cost about 150 s of lockout. Reconnect immediately once, then back off with jitter and a cap. Also retry on network change. Reset backoff only after the first frame arrives. Make re-establishment cheap by caching device state.

### Cited Findings
**TCP liveness on Linux** — [Cloudflare: When TCP sockets refuse to die](https://blog.cloudflare.com/when-tcp-sockets-refuse-to-die/)
- With data outstanding, `tcp_retries2 = 15` gives a theoretical **924.6 s**; Cloudflare observed death at **~940 s**.
- Keepalive is suspended while the retransmission timer runs: it only works when the send buffer is empty.
- An idle ESTABLISHED socket without keepalive lives forever.
- Keepalive 5/3/3 (idle/interval/count) reset a dead connection after about **14.3 s**.
- `TCP_USER_TIMEOUT` caps how long transmitted data may remain unacknowledged. It overrides tcp_retries2 and KEEPCNT.
- Their advice:
  - set it slightly below idle + interval·count;
  - be careful that app-level timeouts do not kill slow but healthy transfers;
  - check send-buffer drain via `TCP_INFO.tcpi_notsent_bytes` (their example treats > 12,000 B/s as healthy).

**Linux socket options** — [linux/include/uapi/linux/tcp.h](https://github.com/torvalds/linux/blob/master/include/uapi/linux/tcp.h)
- Option numbers usable unprivileged through `Os.setsockoptInt` on Android, as already done for QUICKACK = 12:

| Option | Number |
|---|---|
| TCP_KEEPIDLE | 4 |
| TCP_KEEPINTVL | 5 |
| TCP_KEEPCNT | 6 |
| TCP_QUICKACK | 12 |
| **TCP_THIN_LINEAR_TIMEOUTS** | **16** |
| TCP_THIN_DUPACK | 17 |
| **TCP_USER_TIMEOUT** | **18** |
| TCP_NOTSENT_LOWAT | 25 |

**Linux thin streams** — [Linux Documentation/networking/tcp-thin.rst](https://github.com/torvalds/linux/blob/master/Documentation/networking/tcp-thin.rst)
- A stream with fewer than 4 packets in flight cannot trigger fast retransmit.
- Per-socket opt-in, off by default: the kernel then skips exponential backoff and fast-retransmits on the first duplicate ACK.

**RTO backoff** — [RFC 6298](https://www.rfc-editor.org/rfc/rfc6298.html)
- RTO doubles on each expiry, and any cap must be ≥ 60 s.

**Network.framework** — [Apple: NWProtocolTCP.Options](https://developer.apple.com/documentation/network/nwprotocoltcp/options)
- `NWProtocolTCP.Options` offers:
  - `connectionDropTime` (retransmission timeout before drop);
  - `connectionTimeout` (handshake);
  - `enableKeepalive`, `keepaliveIdle`, `keepaliveInterval`, `keepaliveCount`;
  - `persistTimeout`;
  - `retransmitFinDrop` (drop the connection if a FIN is not acknowledged);
  - `noDelay`, `disableAckStretching`.

**Apple's guidance (WWDC18 "Introducing Network.framework")** — [WWDC18 session 715](https://developer.apple.com/videos/play/wwdc2018/715/)
- `.waiting` is not a failure; the connection retries as the network changes.
- On viability loss, inform the user but "don't necessarily tear down your connection". The Wi-Fi may return and the connection resume where it left off.
- When a better path appears, close the old connection only after the new one is fully ready.
- Avoid reachability preflight checks; use NWPathMonitor when a waiting connection is not enough.

**PTP/IP packet types** — [Wireshark packet-ptpip.c](https://github.com/wireshark/wireshark/blob/master/epan/dissectors/packet-ptpip.c)
- Defined by CIPA DC-X005-2005:
  - **Probe Request (type 13)** and **Probe Response (type 14)**;
  - Operation Request (6) and Operation Response (7);
  - Start Data, Data, End Data;
  - Cancel, Event, Init Fail.

**libgphoto2 PTP/IP timeouts** — [libgphoto2 ptpip.c](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/ptpip.c), [ptpip-private.h](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/ptpip-private.h)
- Each read and write is bounded with select() at **2 s + 500 ms**. That is a per-I/O progress timeout, not a per-transaction one.
- It returns a timeout error if no bytes arrive within that window.

**Progress-based timeouts in common clients**
- curl `--speed-limit` / `--speed-time` aborts a transfer that stays below N bytes/s for T seconds. T defaults to **30 s**; with only speed-time given, the limit is 1 B/s. — [curl man page](https://curl.se/docs/manpage.html)
- ffmpeg — [ffmpeg protocols](https://ffmpeg.org/ffmpeg-protocols.html):
  - `rw_timeout`: maximum wait per network read/write, in µs.
  - TCP `timeout`: raises an error when no data arrives for that long.
  - HTTP reconnect options: `reconnect`, `reconnect_streamed`, `reconnect_on_network_error`, `reconnect_delay_max`, `reconnect_max_retries`, `reconnect_delay_total_max`.

**Moonlight** — [moonlight-common-c ControlStream.c](https://github.com/moonlight-stream/moonlight-common-c/blob/master/src/ControlStream.c), [VideoStream.c](https://github.com/moonlight-stream/moonlight-common-c/blob/master/src/VideoStream.c)
- Control stream (ENet, a reliable UDP library) timeout: **10 s**.
- Loss-stats/ping thread interval: **100 ms**.
- Graceful-disconnect linger: **2 s**.
- First-frame timeout: **10 s**.

**Backoff**
- gRPC — [gRPC connection-backoff.md](https://github.com/grpc/grpc/blob/master/doc/connection-backoff.md):
  - initial **1 s**, multiplier **1.6**, jitter **±20%**, max **120 s**, minimum connect timeout **20 s**;
  - backoff resets only when the server's SETTINGS frame arrives, i.e. when the server has really accepted the connection.
- AWS (Marc Brooker, 2015) — [AWS Architecture Blog](https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/):
  - jitter is essential;
  - "full" and "decorrelated" jitter both greatly reduce work and contention;
  - no jitter performs worst;
  - "equal jitter" is the weakest of the jittered variants.

**Two-stage "lost then disconnected" in video SDKs**
- LiveKit's LOST quality level means a participant lost connection temporarily or permanently. The disconnected event fires only after a timeout. — [LiveKit ConnectionQuality](https://docs.livekit.io/reference/client-sdk-js/enums/ConnectionQuality.html)
- Stream's SDK separates a fast reconnect (small spinner, video tiles stay) from a full reconnect (a not-connected view). — [getstream.io cookbook](https://getstream.io/video/docs/flutter/ui-cookbook/connection-unstable/)

### Inferences
**Cost asymmetry**
- A false "dead" tears down the session. If the FIN is lost, the camera holds the stale session for about 150 s.
- A false "alive" only prolongs a frozen frame.
- So detect stalls fast (for the UI) and death slowly. The exception is a hard error: RST, ECONNRESET, a received FIN, or Init Fail.

**Three liveness layers (proposed)**
1. **Command-channel progress watchdog.**
   - Count bytes received since the last tick. Never put a deadline on a whole GetObject or a 2 MB packet: libgphoto2's 2.5 s per-I/O limit would end a session during a 3 s fade.
   - "Stalled" = no bytes for ≥ 1–1.5 s while a transaction is outstanding.
   - Track mid-frame stalls separately.
2. **Event-channel probe.** This channel's TCP state is not stuck behind a backed-off bulk retransmission.
   - While the command channel is stalled, send a PTP/IP Probe Request every 1–2 s and expect a Probe Response.
   - If the ZV-E10 does not answer probes (check with zv-probe), use TCP keepalive on the event socket instead: about 3–5 s idle, 1–2 s interval, count 5.
   - A probe answer during a command stall means the camera is alive and the transfer is recovering, so keep waiting.
3. **OS signals.**
   - macOS: NWConnection viability and state, NWPathMonitor.
   - Android: `ConnectivityManager.NetworkCallback`.
   - "Interface gone" alone still should not trigger teardown for the first ~10–20 s.

**Verdicts (proposed)**

| Verdict | Condition |
|---|---|
| Degraded | 3 s-window p95 gap > 2–4× nominal |
| Stalled | watchdog ≥ 1–1.5 s |
| Suspect | ≥ 5 s (the WebRTC "pause") |
| Dead | (no command progress for ≥ 20–30 s **and** probe or keepalive failing) **or** a hard error |

- Why 20–30 s: under RFC 6298's 1 s minimum, several RTO doublings add up to 1 + 2 + 4 + 8 = 15 s. That window lets the camera's retransmission land after the link returns.
- OS-level backstop:
  - Android: TCP_USER_TIMEOUT ≈ 45–60 s on the command socket.
  - macOS: `connectionDropTime` set similarly.
- Do not set either low: that would move the premature teardown into the kernel.

**Resuming after a fade**
- With a doubling RTO, after an outage of length T the sender may wait up to about T more after the link returns before it retransmits.
- Our request and probe traffic is a thin stream.
- On Android, enable **TCP_THIN_LINEAR_TIMEOUTS** on both sockets so our retransmissions stay linear.
- The camera's backoff for JPEG data cannot be influenced from the client, so the UI must tolerate a few seconds of silence after a fade.
- macOS has no documented equivalent.

**Teardown hygiene**
- When the app does close:
  - send CloseSession if possible;
  - then do a normal close or half-close.
- Never send RST (SO_LINGER = 0) while the link is merely degraded.
- Keep `retransmitFinDrop` at false, so the kernel keeps retransmitting the FIN and the camera is likelier to see it once the link recovers.
- Do not exit the process immediately; Moonlight lingers 2 s.

**Reconnect policy (refines the current 2/4/8/10 s)**
- **First retry:** immediate, after a hard close by the camera or our own clean close.
- **Then:** full-jitter exponential backoff, base 1 s × 1.6–2, capped at about 8–10 s.
- **Network change** (path available, SSID or IP changed): retry at once and reset backoff.
- **Backoff reset:** only after OpenSession succeeds and the first frame arrives (the gRPC principle). A TCP connect alone is not enough.
- **Stale-session refusals:**
  - Poll every 5–10 s for up to ~3 min, with an honest countdown in the UI.
  - Treat the refusals as expected, so they do not escalate the backoff.
- **No migration:** Apple's "open the new connection before closing the old" advice cannot apply, because the camera accepts only one connection. That is another reason to ride out stalls on the existing socket.

**Fast, idempotent re-establishment**
- Try the last known IP first and run SSDP in parallel.
- Reuse the GUID.
- Show cached DeviceInfo and property descriptors immediately.
- Restart live view before refreshing properties, and refresh lazily after the first frame.
- For commands whose response was lost, re-read the state instead of re-sending the command.
- Never auto-retry non-idempotent actions: shutter, record start/stop, delete.

### Gaps
- Does the ZV-E10 answer PTP/IP Probe Requests? No source covers Sony; test it.
- Does reusing the GUID shorten the ~150 s stale-session hold?
- What are the camera's own keepalive, RTO and user-timeout values?
- Does the Redmi Pad's kernel honour TCP_THIN_LINEAR_TIMEOUTS? Verify with getsockopt and a fade test.
- The fetched Apple page does not document `connectionDropTime` defaults or the macOS RTO cap.
- No source was found for how Sony's own apps decide a connection is lost.

---

## 4. Graceful-degradation UX and how comparable products handle weak links

### Takeaway
The common pattern is a few quantised states with hysteresis, shown as non-blocking overlays on a picture that stays visible:
- Moonlight: OKAY / POOR;
- LiveKit: EXCELLENT / GOOD / POOR / LOST;
- Stream: an "unstable" banner, plus separate fast- and full-reconnect views.

Apple's guidance is to tell the user and keep the connection.

For live view this means:
- freeze the last frame, dimmed, with a weak-signal chip and the elapsed time;
- show "reconnecting" only during real reconnects;
- show an honest countdown when the camera holds a stale session;
- never blank the screen;
- offer one actionable tip.

### Cited Findings
**Moonlight (exact behaviour from source)**
- Connection status comes from frame loss per **3 s** interval:
  - **POOR** when one interval reaches **≥ 30%**, or two consecutive intervals reach **≥ 15%**;
  - **OKAY** again at **≤ 5%**;
  - no warnings during the first sampling period after the first frame, while the network settles.
  - — [moonlight-common-c ControlStream.c](https://github.com/moonlight-stream/moonlight-common-c/blob/master/src/ControlStream.c)
- The Qt client overlay — [moonlight-qt session.cpp](https://github.com/moonlight-stream/moonlight-qt/blob/master/app/streaming/session.cpp):
  - At bitrates above 5000 kbps, it says the connection to the PC is slow and suggests lowering the bitrate. Otherwise it shows a plain poor-connection message.
  - It hides itself when the status returns to OKAY.
  - Users can turn connection warnings off in preferences.
  - The stream keeps playing underneath.
- The Android client changelog (9.7.2) added a warning when the connection is too unstable. — [F-Droid](https://f-droid.org/bo/packages/com.limelight/), [APKMirror](https://www.apkmirror.com/apk/cameron-gutman/moonlight-game-streaming/moonlight-game-streaming-9-7-3-release/moonlight-game-streaming-9-7-3-android-apk-download/) (secondary, via search summary)

**Video SDKs and Apple**
- Stream (video SDK) — [getstream.io](https://getstream.io/video/docs/flutter/ui-cookbook/connection-unstable/):
  - an orange connection-unstable banner with a bad-Wi-Fi icon;
  - shown only at "poor" quality, and only to the local participant, who can act on it;
  - a corner spinner during a fast reconnect, with video tiles kept;
  - a full reconnecting view otherwise.
- LiveKit — [LiveKit KB](https://kb.livekit.io/articles/2455399507-how-is-connection-quality-determined), [ConnectionQuality](https://docs.livekit.io/reference/client-sdk-js/enums/ConnectionQuality.html):
  - four levels, scored mainly from packet loss and bitrate shortfall;
  - jitter and RTT are excluded from the score.
- Apple: tell the user connectivity is gone, but keep the connection. — [WWDC18 715](https://developer.apple.com/videos/play/wwdc2018/715/)

**Drones (anecdotal)**
- DJI Inspire 1 reportedly sends 720p whatever the camera settings, adjusts bitrate automatically to signal quality, and reaches farther at lower bitrates. — [InspirePilots forum](https://inspirepilots.com/threads/image-transmission-settings.315/)
- DJI weak-signal warnings can be false positives. A Dronelink staff member suggests ignoring them if the video looks fine. — [Dronelink support](https://support.dronelink.com/hc/en-us/community/posts/360034816553-Weak-video-error)

**Other tools and apps**
- Open GoPro's SDK handles connection upkeep, including periodic keep-alives, automatically. — [open-gopro on PyPI](https://pypi.org/project/open-gopro/)
- OBS Media Source reconnects behave inconsistently in user reports. One user measured about 60 s to recover with the reconnect-delay slider at 1 s. — [OBS forum](https://obsproject.com/forum/threads/srt-input-stream-has-trouble-reconnecting.150872/), [OBS forum: Media Source Recovery](https://obsproject.com/forum/threads/media-source-recovery.146967/) (anecdotal)
- A 2015-era review notes that camera makers' own remote apps are often basic or unreliable. — [Macworld Cascable 3 review](https://www.macworld.com/article/230834/cascable-3-for-ios-review-use-your-iphone-as-a-full-featured-remote-for-your-wi-fi-enabled-dlslr-ca.html)

**Mac tip (community source)**
- AWDL stutter can be reduced by turning AirDrop off or moving the router's 5 GHz channel, for example to 149. — [Meter PSA](https://meter.com/mac-osx-awdl-psa), [Apple Developer Forums](https://developer.apple.com/forums/thread/847171)

### Inferences
**Proposed UX state machine**

| State | Entry condition | Exit condition | Display |
|---|---|---|---|
| Live | — | — | Normal picture |
| Degraded | 3 s-window p95 gap > 2× nominal for 2 windows, or > 4× for 1 window (the Moonlight 15%/30% analogue) | ≤ 1.25× for 1 window | Signal chip with fps/latency; nothing blocks |
| Stalled | Watchdog ≥ 1–1.5 s | — | Last frame dimmed, with a "waiting for camera… N s" chip |
| Reconnecting | A new session is actually being opened | — | Last frame plus spinner and attempt count; on a stale-session refusal, a countdown up to ~2½ min with an explanation |
| Disconnected | User cancels, or the long ceiling is reached | — | — |

- Suppress warnings for about 3 s after the first frame.
- Keep any banner up for at least about 3 s to prevent flicker.

**Indicator**
- Show 3–4 bars computed from stream metrics (fps, p95 gap, frame age), not from RSSI. This follows the DJI false-alarm lesson and LiveKit's choice of measured loss and bitrate.
- Optionally add frame age to the existing status pill.

**Controls while stalled**
- Settings show as "pending" and coalesce, latest value wins (§5).
- The shutter is disabled with a tooltip and never queued: a delayed shot on recovery would surprise the user.

**One tip, in context**
- "Move closer."
- "Use 5 GHz", or "Wi-Fi Direct skips the router hop."
- Mac: turning AirDrop off can reduce periodic stutter.
- Android: keep the screen on and the app in front, since the low-latency lock needs both.

### Gaps
- I found no primary documentation of weak-signal UX for Sony Creators' App / Imaging Edge Mobile, Cascable, Parsec, Steam Link, VLC, MotionEye or IP Webcam. Searches returned only reviews and anecdotes.
- No usability study gives numeric thresholds for weak-signal vs reconnecting states. The thresholds above are proposals.

---

## 5. Prioritising control traffic over live view on the shared PTP/IP channel

### Takeaway
The camera answers one transaction at a time, so every live-view GetObject in flight blocks the user's next command. On a weak link that is a full frame transfer of hundreds of ms; a 2 MB download packet takes seconds.

Recommendations:
- One owner per socket, running a strict-priority transaction scheduler: commands > event-driven refreshes > live view > downloads.
- No speculative live-view requests while anything else is queued.
- Settings coalesce, latest value wins.
- On weak links, pause or thin live view during downloads. Alternatively, chunk downloads, if the camera supports partial reads (unverified).

### Cited Findings
**PTP/IP framing** — [Wireshark packet-ptpip.c](https://github.com/wireshark/wireshark/blob/master/epan/dissectors/packet-ptpip.c), [libgphoto2 ptpip.c](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/ptpip.c)
- Each Operation Request carries a transaction ID.
- Data returns as Start Data, Data…, End Data tagged with that ID, followed by an Operation Response.
- A Cancel packet type exists.

**libgphoto2 interleaving** — [libgphoto2 library.c](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/library.c), [libgphoto2 ptpip.c](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/ptpip.c)
- Live-view polls and control share one serialized session. The Sony QX capture path polls GetObject(0xffffc002) between half-press and full-press steps, treating AccessDenied as "image ready", in loops of up to 500 ms.
- Its command-channel read also runs a fast event-channel check (`PTP_EVENT_CHECK_FAST`).

**Head-of-line blocking analogue** — [RFC 9112 §9.3.2](https://www.rfc-editor.org/rfc/rfc9112.html#section-9.3.2)
- HTTP/1.1 pipelining requires responses in request order, so one slow response delays everything behind it.

**Separate control channel in Moonlight** — [moonlight-common-c ControlStream.c](https://github.com/moonlight-stream/moonlight-common-c/blob/master/src/ControlStream.c)
- Moonlight carries control on its own reliable ENet channel, apart from video, so control never waits behind frames.

**Headroom constant** — [draft-ietf-ccwg-bbr](https://datatracker.ietf.org/doc/html/draft-ietf-ccwg-bbr)
- BBR keeps **15% headroom**.

### Inferences
**Worst-case command latency**
- It equals the time left on the in-flight transaction.

| Item | Goodput | Time |
|---|---|---|
| 22–60 KB frame | 4.5 Mbit/s | ~40–110 ms |
| 22–60 KB frame | 1 Mbit/s | ~180–480 ms |
| 22–60 KB frame | 0.3 Mbit/s | ~0.6–1.6 s |
| 2 MB packet | 5 MB/s | ~0.4 s |
| 2 MB packet | 0.5 MB/s | ~4 s |

- Therefore:
  - never queue more than one live-view request at the camera;
  - check the command queue before each poll;
  - on weak links, leave idle gaps (the 15–20% headroom from §1) so a command usually finds the channel free.

**Scheduler**
- One actor/coroutine owns the command socket, with priorities:
  1. user commands;
  2. refreshes triggered by events (GetDevicePropDesc after PropertyChanged);
  3. the live-view poll;
  4. downloads and thumbnails.
- A command that arrives during a frame waits for that frame to finish. Do not cancel mid-frame: Sony's handling of PTP/IP Cancel is unknown.
- Show "pending" if a command waits longer than about 150 ms.

**Coalescing**
- While a write is in flight, keep only the newest requested value per property.
- Under a weak link, dragging an ISO dial should send 1–2 writes, not 15.
- Reconcile afterwards by reading the property back or from the event.

**Downloads**

| Link | Approach |
|---|---|
| Good | Interleave one live-view frame between download transactions |
| Weak | Pause live view with a "Downloading 3/12 MB" chip, or defer downloads; let the user choose "prioritise transfer" |
| Either, with GetPartialObject | Chunks sized to about 200 ms of goodput, so a frame fits between chunks |

- GetPartialObject is standard PTP op 0x101B, but support on the ZV-E10 in PC-remote mode is unverified.

**Event channel**
- Events keep arriving during long transfers, because the event channel is its own TCP connection.
- The follow-up reads still queue on the command channel, so de-duplicate them per property.

**Prefetch**
- The current decode overlap is fine if the next request goes out only when the command queue is empty.
- Sending a request before the previous response completes (depth 2) would make commands wait behind two frames.

### Gaps
- Unverified: ZV-E10 support for GetPartialObject or a Sony partial-read variant, and how it reacts to a mid-transfer PTP/IP Cancel.
- Unverified: whether the camera queues or rejects a second Operation Request sent before the previous Response.
- No source covers how Sony's apps arbitrate live view against downloads.

---

## 6. Reproducible bad-network testing: tools, a custom PTP/IP impairment proxy, physical methods, metrics and acceptance criteria

### Takeaway
Combine three layers:
1. **Packet-level impairment on the Mac.** Network Link Conditioner, or scripted `dnctl` + `pfctl` dummynet pipes, for loss, RTO and bandwidth effects.
2. **A user-space asyncio PTP/IP impairment proxy** forwarding both channels. It scripts exact stalls, mid-frame pauses, resets, and a "lost FIN / lockout" emulation, without locking out the real camera. Running it on the Mac (preferably wired) means the Android tablet can be tested without root.
3. **Physical attenuation**: distance, router TX power or attenuators, for real MAC retries and rate adaptation.

Drive everything from seeded schedules and log per-transaction JSONL. Score with the WebRTC freeze and pause definitions, plus reconnect and command-latency metrics.

### Cited Findings
**Network Link Conditioner (NLC)**
- What it does — [NSHipster: Network Link Conditioner](https://nshipster.com/network-link-conditioner/):
  - It comes in "Additional Tools for Xcode" (Hardware folder).
  - Presets: 100% Loss, 3G, DSL, EDGE, High Latency DNS, LTE, Very Bad Network, WiFi, WiFi 802.11ac.
  - It controls bandwidth, latency and loss, and applies system-wide.
  - On iOS devices it lives under Settings > Developer.
- It is reportedly a front end for `dnctl`/`pfctl` that cannot be limited to specific connections. — [Atomic Object](https://spin.atomicobject.com/simulating-poor-network-connectivity-mac-osx/), [Hacker News comment](https://news.ycombinator.com/item?id=8601001) (informal)

**dummynet on macOS (dnctl + pfctl)**
- From the local macOS 27 man page — [macOS dnctl(8)](x-man-page://8/dnctl) (local `man dnctl`), [FreeBSD ipfw(8)](https://man.freebsd.org/cgi/man.cgi?query=ipfw&sektion=8):
  - Syntax: `dnctl pipe N config bw 300Kbit/s delay 250ms plr 0.05 queue 50`.
  - `plr` is a single float 0..1. FreeBSD's ipfw adds a 4-argument Gilbert-Elliott form that the macOS page does not list.
  - Delay is rounded to the clock tick.
  - The default queue is 50 slots. The page warns that 50 full-size Ethernet packets mean about 20 s of queueing on a 30 kbit/s pipe.
  - The pf rule example is `dummynet all pipe 1`.
- Workflow — [Atomic Object](https://spin.atomicobject.com/simulating-poor-network-connectivity-mac-osx/):
  - Load the rule: `echo "dummynet out proto tcp from any to <host> pipe 1" | sudo pfctl -f -`, then `sudo pfctl -e`.
  - Clean up: `pfctl -F /etc/pf.conf`, `dnctl -q flush`, `pfctl -d`.
  - Root is required, and PF resolves hostnames once.
  - One commenter could not get the delay to apply, so check with `dnctl list` and `pfctl -sa`.
- A Mattermost guide uses separate in and out pipes, and notes that NLC is hard to script in CI. — [Mattermost wiki](https://mattermost.atlassian.net/wiki/spaces/WD/pages/3005743105/HowTo+Change+Bandwidth+and+Latency+on+MacOS) (via search snippet)

**Linux tc netem (needs a Linux box or router)** — [tc-netem(8)](https://man7.org/linux/man-pages/man8/tc-netem.8.html)
- Options:
  - `delay T [jitter [corr]]` with uniform / normal / pareto / paretonormal distributions;
  - `loss random`, `loss state` (4-state Markov) and `loss gemodel` (Gilbert-Elliott);
  - `rate` with packet overhead;
  - `slot` (packets or bytes per slot), which roughly approximates bursty MACs; the page names Wi-Fi among them;
  - `seed` for reproducible randomness.
- Caveats:
  - reordering needs delay;
  - large jitter reorders packets unless a pfifo child qdisc is used;
  - for realistic TCP, place netem at the receiver's ingress.

**Toxiproxy** — [Shopify/toxiproxy](https://github.com/Shopify/toxiproxy)
- Toxics:
  - `latency` (with jitter);
  - `bandwidth` (KB/s);
  - `slow_close`;
  - `timeout` (0 keeps the connection open and drops data);
  - `reset_peer`;
  - `slicer` (small chunks with delay);
  - `limit_data` (close after N bytes);
  - `packet_loss` (drops chunks, with correlation).
- Each toxic applies per direction, with a per-connection `toxicity` probability.
- Control: an HTTP API on :8474 (unauthenticated unless `TOXIPROXY_AUTH_TOKEN` is set) and `toxiproxy-cli`.

**Facebook ATC** — [Facebook Engineering, 2015](https://engineering.fb.com/2015/03/23/production-engineering/augmented-traffic-control-a-tool-to-simulate-network-conditions/)
- Augmented Traffic Control shapes bandwidth, latency, loss, corruption and reordering on a Wi-Fi gateway, so unmodified phones can be tested.
- Facebook built it after a cellular rig proved impractical.
- Maintenance status not verified.

**Physical methods**
- OpenWrt TX power — [Morse Micro community](https://community.morsemicro.com/t/how-to-keep-the-tx-power-unchanged-after-a-power-cycle/950), [OpenWrt forum](https://forum.openwrt.org/t/is-possible-to-set-tx-power-to-0-on-25-12/247232):
  - `iw dev <if> set txpower fixed <mBm>` (1000 mBm = 10 dBm) does not persist; UCI `wireless.radio0.txpower` does.
  - Drivers may clamp the value (a 0 dBm request reportedly gave 3 dBm), and the regulatory domain sets limits.
- Rate-vs-Range lab method — [QA Cafe CDRouter RvR guide](https://support.qacafe.com/cdrouter/user-guide/cdrouter-wifi-rvr-user-guide/), [Candela LANforge RvR cookbook](https://www.candelatech.com/cookbook/wifire/Chamber_View_Rate_vs_Range_Test_Throughput_Over_Different_Signal_Strengths), [Candela CT714b](https://www.candelatech.com/ct714b_product.php):
  - programmable attenuators per Broadband Forum TR-398 §6.3.1;
  - AP and station in separate isolation chambers, cabled through the attenuator;
  - about 25 dB of baseline path loss;
  - attenuators of 0–95 dB in 0.25 dB steps.

**Glass-to-glass latency** — [RidgeRun wiki](https://developer.ridgerun.com/wiki/index.php/Jetson_TX2/NVIDIA_Jetson_TX1/TX2_Video_Latency), [Pengutronix Optick](https://pengutronix.de/en/blog/2024-05-06-linux-automation-s-optick-glass-to-glass-latency-measurement-tool.html), [vay.io](https://vay.io/how-to-measure-glass-to-glass-video-latency/)
- Method: put a stopwatch and the receiving display in one camera frame.
- Precision is limited by the stopwatch display's refresh rate; RidgeRun measured 43 ms for an online stopwatch.
- Report a histogram (min, mean, max, jitter), not a single number.
- An LED plus photosensor (Pengutronix Optick) gives electronic timing.

**Standard metrics**
- WebRTC freeze: ≥ max(3·avg, avg + 150 ms) over 30 frames. Pause: > 5 s.
- Counters: freeze/pause counts and durations, framesDropped, inter-frame delay sums and sums of squares. — [W3C webrtc-stats](https://www.w3.org/TR/webrtc-stats/)
- Moonlight's poor-connection rule (30% in one interval, or 15% in two; recovery at 5%; 3 s windows) can be reused as a gate. — [moonlight-common-c](https://github.com/moonlight-stream/moonlight-common-c/blob/master/src/ControlStream.c)

**Confounders**
- macOS AWDL spikes. — [Meter PSA](https://meter.com/mac-osx-awdl-psa), [Apple Developer Forums](https://developer.apple.com/forums/thread/847171)
- The Android low-latency lock's preconditions. — [AOSP](https://source.android.com/docs/core/connect/wifi-low-latency)

### Inferences
**Custom asyncio impairment proxy (the core tool)**
- **Topology:**
  - Listen on `MAC_IP:15740` and open a matching upstream to `camera:15740` for each inbound connection.
  - The first connection is the command channel; the one opening with Init Event Request is the event channel.
  - Bytes pass unchanged, including GUID and Init, so pairing should not be affected. Verify this, because the camera sees the proxy's IP.
- **Parsing:** read the 8-byte PTP/IP headers only to observe and target: tag Start Data / Data / End Data / Response by transaction ID, and log timestamps.
- **Setup:**
  - Both apps need a debug "camera host override". With it, the tablet needs no root.
  - Keep the Mac wired to the router so the Wi-Fi hop is not doubled.
- **Impairments:** per direction and per channel, from a seeded scenario file, with a small HTTP control API like Toxiproxy's:
  - latency with jitter;
  - token-bucket caps (4 / 2 / 1 / 0.5 / 0.25 Mbit/s);
  - full stalls of 0.5–40 s with both connections kept open;
  - mid-frame pauses after N bytes of a Data packet;
  - a slow drip of 1–10 KB/s;
  - RST toward the app;
  - FIN toward the app only, keeping the camera side open (emulates a FIN that never reached the camera);
  - lockout emulation: refuse or hold new Inits for 150 s after an app-side drop;
  - stalls on the event channel only.
- **Limits:**
  - A proxy terminates TCP, so it cannot reproduce packet loss, retransmission or RTO backoff.
  - Toxiproxy-style "packet_loss" would corrupt the PTP byte stream.
  - For packet-level effects, add `dnctl` pipes on the proxy's ports, e.g. `dummynet in proto tcp from any to any port 15740 pipe 1` (standard pf syntax, untested here).
  - Use plr 0.01–0.10, or toggle plr 1.0 from a script for blackouts.

**NLC vs dnctl**
- NLC suits exploratory runs. It is system-wide, so it also slows dev and Claude traffic.
- For repeatable runs, script dnctl/pfctl with explicitly small queues (e.g. `queue 20`), unless bufferbloat is the scenario.
- Confirm once that NLC shapes LAN traffic: `ping <camera>` under "Very Bad Network". Loopback is likely unaffected.

**Scenario matrix** (5–10 min per run, 3 seeds each)

| ID | Scenario |
|---|---|
| S0 | Baseline |
| S1 | RTT +50 / +100 / +200 ms with ±25% jitter |
| S2 | Bandwidth caps 4 / 2 / 1 / 0.5 / 0.25 Mbit/s |
| S3 | Random loss 1 / 3 / 5 / 10% |
| S4 | Burst loss (netem gemodel, if a Linux box is available) |
| S5 | Blackouts 0.5–40 s, periodic and random |
| S6 | Mid-frame pauses |
| S7 | Slow drip |
| S8 | RST mid-transfer |
| S9 | Lockout emulation |
| S10 | Command load (a setting write every 2 s, a shutter every 30 s) during S2 / S3 / S5 |
| S11 | RAW+JPEG download during S2 |
| S12 | Real events: router reboot, client Wi-Fi off/on, AP or band change, Mac sleep/wake, Android screen off/on |

Physical runs:
- a fixed walking route with walls;
- OpenWrt TX power in ~3 dB steps from 20 to 2 dBm;
- a metal box or microwave-oven enclosure is only a crude qualitative attenuator, with uncontrolled leakage and no sourced parameters.

**Soak**
- Run 2–8 h with a seeded random schedule of S1–S8 events plus the command load, driven in the e2e.py / zv-probe style.
- Record battery drain too: project memory shows about 11% in 40 min of streaming.

**Metrics** (per-transaction JSONL, aggregated per 3 s window and per run)
- Smoothness: fps; p50 / p95 / p99 inter-frame gap; freezeCount, totalFreezesDuration and freeze ratio; pauseCount.
- Latency: frame age, plus periodic glass-to-glass samples.
- Link: AccessDenied ratio; TTFB, base TTFB, dq; goodput.
- Stalls: stall count and a duration histogram.
- Teardowns, split into hard-error and watchdog-initiated.
- Recovery: reconnect count; time to first frame after link recovery and after reconnect start; stale-session refusals.
- Commands: p50 / p95 latency by type; time spent "pending".
- Rendering: late-dropped frames; decode time.

**Proposed acceptance criteria**
None are published for this product class. These are derived from the cited definitions and the 25 fps baseline.

| Scenario | Criterion |
|---|---|
| Baseline | ≥ 24 fps, p95 gap ≤ 60 ms, freeze ratio < 0.5% |
| 1 Mbit/s | No teardown, frames at the link-limited rate, command p95 ≤ 500 ms |
| **Blackouts ≤ 20 s** | **Same session resumes, zero teardowns, first frame ≤ 2 s after link return (p95)**; a longer tail is logged where camera RTO dominates |
| Blackouts > 30 s | At most one reconnect, never a blank screen |
| RST / FIN | Reconnect attempt within 100 ms; first frame ≤ 3 s if the camera accepts |
| Lockout emulation | Countdown shown; backoff never above ~10 s |
| Regression gate | No 3 s window with ≥ 30% of expected frames missing, outside injected impairments |

### Gaps
- No Apple documentation was retrievable on whether NLC shapes LAN or loopback traffic, or on its preset values. Verify empirically.
- Unconfirmed: whether macOS pf's `dummynet` keyword accepts `port` qualifiers. The local `man pf.conf` does not document `dummynet` at all, though `man dnctl` and Atomic Object use it. Test first.
- No parameters are sourced for microwave or metal-box attenuation. Prefer TX power or attenuators.
- No public acceptance criteria exist for comparable camera-remote apps (Cascable, Sony, GoPro). The criteria above are proposals.
