# macOS live view over Wi-Fi (and USB): code map and behaviour on a degrading link (ZV-E10 Viewer, main @ 2826642)

Scope: the read-only analysis of the Swift package in this repository (Sources/ZVCamera, Sources/ZVCore, Sources/ZVViewer, Sources/zv-probe, Tests, docs, git history). Code claims cite `repo-relative path:line`. Apple/xnu facts cite web URLs. "T0" means the moment the link stalls. Nothing was built or run, and no camera was touched.

## 1. Architecture and data path: types, the live-view loop step by step, threads/queues

### Takeaway
On a Mac, live view is a pull loop. A detached `.userInitiated` task calls `CameraSession.nextLiveViewFrame()` over and over, which sends PTP GetObject(0xFFFFC002). The loop retries after 20 ms on AccessDenied. It hands each JPEG to a `bufferingNewest(1)` AsyncStream. A second detached task decodes it with ImageIO, computes histogram levels and hops to `@MainActor` to publish a `CGImage` that SwiftUI draws. "Pipelining" means one PTP transaction in flight at a time, overlapped with the decode and render of the previous frame. Requests are never pipelined on the wire.

### Cited Findings
- **Layers.** SwiftUI views (`LiveScreen`, `WaitingView`) → `CameraController` (`@MainActor @Observable`) → `CameraSession` (actor) → `PTPTransport` protocol → `ICCameraTransport` (USB) or `PTPIPTransport` (actor, Wi-Fi), as drawn in `docs/architecture.md:17-30`. The protocol has just `send(_:_:outData:) async throws -> PTPReply` plus `events: AsyncStream<PTPEvent>` (`Sources/ZVCore/Session/PTPTransport.swift:14-18`). Errors are `TransportError.disconnected | timeout(operation:) | failed(String)` (`Sources/ZVCore/Session/PTPTransport.swift:20-24`).
- **Files and roles in the streaming path.**
  - `PTPIPChannel` handles one NWConnection, packet queue and waiter (`Sources/ZVCamera/PTPIPTransport.swift:7-166`).
  - `PTPIPTransport` handles Init, the event channel, OpenSession and serial transactions (`Sources/ZVCamera/PTPIPTransport.swift:170-339`).
  - `PTPIP`, `PTPIPPacketReader` and `PTPIPTransaction` handle framing and parsing (`Sources/ZVCore/PTPIP/PTPIP.swift:27-183`).
  - `WiFiPolicy` decides when to dial and how to back off (`Sources/ZVCore/PTPIP/WiFiPolicy.swift:4-50`).
  - `SSDP` and `WiFiDiscovery` find the camera (`Sources/ZVCore/PTPIP/SSDP.swift:4-38`, `Sources/ZVCamera/PTPIPTransport.swift:370-415`).
  - `CameraSession` handles the handshake, properties, writes and the live-view poll (`Sources/ZVCore/Session/CameraSession.swift:9-160`).
  - `ConnectionTracker` handles USB device choice and retry (`Sources/ZVCore/Session/ConnectionTracker.swift:4-45`).
  - `WriteCoalescer` allows one write per property in flight (`Sources/ZVCore/Session/WriteCoalescer.swift:5-44`).
  - `LiveViewFrame` extracts the JPEG (`Sources/ZVCore/Sony/LiveViewFrame.swift:3-18`).
  - `CameraController` owns the connection logic, the live-view tasks and the stats (`Sources/ZVViewer/CameraController.swift:579-1025`).
  - `LiveScreen` renders the frame, the stale overlay and the status pill (`Sources/ZVViewer/LiveScreen.swift:123-341`).
  - `RootView` switches screens by phase (`Sources/ZVViewer/ZVViewerApp.swift:31-44`).
  - `WaitingView` is the no-camera screen (`Sources/ZVViewer/WaitingView.swift:4-87`).
  - `ScreenState` tracks overlays and `pillInfo` (`Sources/ZVViewer/ScreenState.swift:8-94`).
  - `ICCameraTransport` and `CameraDiscovery` cover USB (`Sources/ZVCamera/ICCameraTransport.swift:6-124`, `Sources/ZVCamera/CameraDiscovery.swift:12-53`).
- **Step 1, request.** `nextLiveViewFrame()` loops: `Task.checkCancellation()`, then `rawSend(SonyOp.getObject, [Sony.liveViewHandle])` (`Sources/ZVCore/Session/CameraSession.swift:86-89`). The op is 0x1009 and the handle 0xFFFF_C002 (`Sources/ZVCore/Sony/SonyCodes.swift:7,56`).
- **Step 2, reply handling** (`Sources/ZVCore/Session/CameraSession.swift:90-101`):
  - OK with a JPEG returns the frame.
  - OK without a JPEG sleeps 20 ms.
  - AccessDenied 0x200F or DeviceBusy 0x2019 sleeps 20 ms.
  - InvalidObjectHandle 0x2009 sleeps 50 ms.
  - Any other code throws `SessionError.operationFailed`.
  - The codes are defined in `Sources/ZVCore/PTP/PTPContainer.swift:3-8`.
  - Per the docs, about half the polls return AccessDenied (`docs/protocol.md:152`). On Wi-Fi each retry costs a full network round trip (`docs/protocol.md:322`).
- **Step 3, JPEG extraction.** `LiveViewFrame.jpeg` copies the reply into `[UInt8]` and reads a u32 offset. If `FF D8` sits at that offset it returns `Data(bytes[offset...])`. Otherwise it scans for `FF D8 FF` (`Sources/ZVCore/Sony/LiveViewFrame.swift:5-17`). The Sony header offset is 1524 on the ZV-E10 (`docs/protocol.md:148`).
- **Step 4, hand-off and "pipelining".**
  - `AsyncStream.makeStream(of: Data.self, bufferingPolicy: .bufferingNewest(1))` (`Sources/ZVViewer/CameraController.swift:857-859`).
  - The producer `Task.detached(priority: .userInitiated)` runs `sink.yield(try await session.nextLiveViewFrame())` in a loop and never waits for the consumer (`Sources/ZVViewer/CameraController.swift:860-877`).
  - The comment explains why: a Wi-Fi request takes 25–35 ms, so the next frame is requested while the current one decodes (`Sources/ZVViewer/CameraController.swift:857-858`; `docs/protocol.md:310`).
  - Depth: one GetObject in flight (the transport serialises), at most one undecoded JPEG buffered (older ones dropped silently), and one JPEG in decode or show.
- **Step 5, decode.**
  - The consumer `Task.detached(priority: .userInitiated)` runs `for await jpeg in frames` (`Sources/ZVViewer/CameraController.swift:878-885`).
  - `decode` uses `CGImageSourceCreateWithData` + `CGImageSourceCreateImageAtIndex(..., [kCGImageSourceShouldCacheImmediately: true])`, an eager ImageIO decode off the main thread (`Sources/ZVViewer/CameraController.swift:1005-1008`).
  - `histogramLevels` draws into a 256×144 RGBA `CGContext` with `interpolationQuality = .none` and counts luma (`Sources/ZVViewer/CameraController.swift:1012-1024`).
- **Step 6, publish on the main actor.** `await self?.show(image, jpeg:, levels:)` (`Sources/ZVViewer/CameraController.swift:883`). `show` runs on `@MainActor` (`Sources/ZVViewer/CameraController.swift:11-13, 899-911`):
  - sets `frame` and `lastJPEG`;
  - computes the white level, the 64-bin histogram and `LumaStats`;
  - appends a frame gap;
  - updates `lastFrameAt` and `frameTimes`;
  - calls `tickFrameStats()`.
- **Step 7, render.** `Image(decorative: frame, scale: 1).resizable().aspectRatio(contentMode: .fit)`. While stale, opacity drops to 0.45 with the label "Нет картинки с камеры…" (`Sources/ZVViewer/LiveScreen.swift:123-138`). Rendering is plain SwiftUI, with no Metal, display-link pacing or jitter buffer.
- **Step 8, fps counter and gap stats.**
  - `fps` is the count of `frameTimes` in the last 2 s divided by 2 (`Sources/ZVViewer/CameraController.swift:947-950`).
  - `isFrameStale` is true when the phase is settling or ready, a frame exists and none has arrived for more than 1.5 s (`Sources/ZVViewer/CameraController.swift:951-952`).
  - `shownFPS` and `frameGapMs` (the longest gap in 10 s, counting the currently open gap) refresh every 500 ms (`Sources/ZVViewer/CameraController.swift:922-931`).
  - They come from a 500 ms MainActor ticker task (`Sources/ZVViewer/CameraController.swift:125-133`). The pill shows "N к/с" (`Sources/ZVViewer/LiveScreen.swift:217-221`).
  - The popover says gaps longer than 200 ms show as a jerk (`Sources/ZVViewer/LiveScreen.swift:333-335`).
- **Threads, queues, actors and priorities:**
  - Each `PTPIPChannel` has its own serial `DispatchQueue(label: "zv.ptpip.channel")` with no explicit QoS. NWConnection callbacks, receive completions and timeout timers (`asyncAfter`) all run there (`Sources/ZVCamera/PTPIPTransport.swift:9, 69-70, 107, 131`).
  - Packets are handed over under an `NSLock` by resuming a `CheckedContinuation` (`Sources/ZVCamera/PTPIPTransport.swift:142-156`).
  - `PTPIPTransport` and `CameraSession` are actors on the cooperative pool (`Sources/ZVCamera/PTPIPTransport.swift:170`; `Sources/ZVCore/Session/CameraSession.swift:9`).
  - The live-view producer and consumer are both `Task.detached(priority: .userInitiated)` (`Sources/ZVViewer/CameraController.swift:860, 878`).
  - The snapshot listener and the 1 s `wifiTick` and 500 ms stats loops are MainActor tasks (`Sources/ZVViewer/CameraController.swift:126-140, 849-856`).
  - The SSDP search blocks on `DispatchQueue.global(qos: .userInitiated)` (`Sources/ZVCamera/PTPIPTransport.swift:374-379`).
  - USB command timeouts use `DispatchQueue.global()` (`Sources/ZVCamera/ICCameraTransport.swift:43`). The openSession and closeSession timers run on the main queue (`Sources/ZVCamera/CameraDiscovery.swift:49`; `Sources/ZVCamera/ICCameraTransport.swift:58`).
  - The automation listener runs on `.main` (`Sources/ZVViewer/AutomationServer.swift:36, 56`).

### Inferences
- The only per-frame main-actor work is cheap: 256-level loops and property sets. Decode and downscale run off-main. The main thread is therefore unlikely to cause frame drops, apart from SwiftUI's own redraw of a 1024×576 `CGImage` per frame.
- Because the producer never blocks, decode slowness drops frames (newest wins) rather than adding latency. Arrival jitter from Wi-Fi goes straight to the screen, because there is no pacing.
- Roughly half the polls get AccessDenied because one cycle (RTT 25–35 ms, plus a 20 ms sleep after a denial) is shorter than the camera's 40 ms frame interval. Each displayed frame costs about 2 round trips on Wi-Fi.

### Gaps
- The code does not show whether ImageIO uses hardware JPEG decode for 1024×576 frames, and no decode timing is logged.

## 2. Wi-Fi transport internals (Network.framework options, reads, framing, probes, ACKs, 2 MB packets, desync)

### Takeaway
The Wi-Fi path uses Network.framework `NWConnection` TCP, one connection per PTP/IP channel. The only options set are `noDelay` and keepalive 5 s / 2 s / 3. Reads are up to 1 MiB and are reassembled into whole PTP/IP packets. A transaction filters packets by transaction ID, so stale replies from an abandoned transaction cannot desync the parser. However, the 8 s timer measures complete packets rather than bytes, no PTP/IP Cancel is ever sent, and macOS has no ACK tuning (Android needed TCP_QUICKACK).

### Cited Findings
- **API.** Network.framework is used, not BSD sockets, for PTP/IP: `NWConnection(host:port:using: NWParameters(tls: nil, tcp: tcp))` (`Sources/ZVCamera/PTPIPTransport.swift:37-38`). BSD sockets are used only for the SSDP UDP search (`Sources/ZVCamera/PTPIPTransport.swift:382-415`).
- **TCP options set** (`Sources/ZVCamera/PTPIPTransport.swift:30-36`):
  - `noDelay = true`;
  - `enableKeepalive = true`, `keepaliveIdle = 5`, `keepaliveInterval = 2`, `keepaliveCount = 3`. The comment says the camera can vanish without closing, so keepalive should notice.
  - Nothing else is set: no `connectionTimeout`, `connectionDropTime`, `persistTimeout`, `retransmitFinDrop`, `disableAckStretching`, `disableECN` or `enableFastOpen`. On `NWParameters` there is no `serviceClass`, `requiredInterfaceType`, `prohibitedInterfaceTypes`, multipath or peer-to-peer setting. A grep of Sources finds none of these names.
- **Apple's documented semantics for these options** ([Apple: NWProtocolTCP.Options](https://developer.apple.com/documentation/network/nwprotocoltcp/options)):
  - `keepaliveIdle`: "The number of seconds of idleness that TCP waits before sending keepalive probes."
  - `keepaliveCount`: "The number of keepalive probes that TCP sends before terminating the connection."
  - `keepaliveInterval`: "The number of seconds that TCP waits between sending keepalive probes."
  - Also available but unused: `connectionDropTime` ("The timeout, in seconds, for TCP retransmission attempts"), `connectionTimeout` (handshake), `disableAckStretching` ("disables TCP acknowledgment stretching") and `retransmitFinDrop` ("drop its connection after not receiving an ACK packet after a FIN packet").
- **xnu keepalive rule** ([xnu tcp_timer.c](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/netinet/tcp_timer.c), [xnu tcp_timer.h](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/netinet/tcp_timer.h)):
  - On each TCPT_KEEP firing, the stack drops the connection when `idle_time >= TCP_CONN_KEEPIDLE(tp) + TCP_CONN_MAXIDLE(tp)`.
  - `idle_time = tcp_now - tp->t_rcvtime`, the time since the last segment was received.
  - `TCP_CONN_MAXIDLE(tp) = KEEPCNT × KEEPINTVL`.
  - The check applies in any state `<= TCPS_CLOSING` or `FIN_WAIT_2`, that is regardless of outstanding data.
  - The default (unconfigured) xnu values are idle 2 h, interval 75 s and count 8.
- **Connection state handling** (`Sources/ZVCamera/PTPIPTransport.swift:44-77`):
  - `.ready` (first time only) resumes `start` and starts the receive loop.
  - `.waiting` is ignored, both before and after ready. The comment cites the macOS Local Network re-check and Wi-Fi reconnecting.
  - `.failed`: before ready it fails `start` with `.failed`; it always calls `fail(.disconnected)`.
  - `.cancelled` calls `fail(.disconnected)`.
  - A `queue.asyncAfter(timeout)` cancels the connection and throws `.timeout(operation: 0)` if it never became ready.
  - No `viabilityUpdateHandler`, `betterPathUpdateHandler`, `pathUpdateHandler`, `restart()` or `NWPathMonitor` is used anywhere in Sources (grep). Apple documents `.waiting` as "waiting for a network path change", and documents viability and better-path handlers for exactly these events ([Apple: NWConnection.State](https://developer.apple.com/documentation/network/nwconnection/state-swift.enum); [Apple: NWConnection](https://developer.apple.com/documentation/network/nwconnection)).
- **Close.** `cancel()` calls `connection.cancel()` and `fail(.disconnected)` (`Sources/ZVCamera/PTPIPTransport.swift:114-117`). Apple: `cancel()` "gracefully disconnects", whereas `forceCancel()` "immediately disconnects". `forceCancel` is unused. `PTPIPTransport.close()` cancels the event task and both channels and finishes the event stream. It sends no PTP CloseSession and no PTP/IP Cancel (`Sources/ZVCamera/PTPIPTransport.swift:246-253`).
- **Reads.**
  - `receive(minimumIncompleteLength: 1, maximumLength: 1 << 20)` re-arms after every chunk. An error or `isComplete` (FIN) means `fail(.disconnected)` (`Sources/ZVCamera/PTPIPTransport.swift:130-140`).
  - `deliver` feeds the reader under the lock. Each complete packet goes to the single waiter or into the `packets` FIFO (`Sources/ZVCamera/PTPIPTransport.swift:142-156`).
  - `nextPacket(timeout:)` returns a queued packet, or the stored failure, or registers one waiter with an id plus an `asyncAfter` expiry that throws `.timeout(operation: 0)` (`Sources/ZVCamera/PTPIPTransport.swift:88-128`).
  - The design comment: "Ничего не теряется, даже если ожидание оборвалось по тайм-ауту: пакет останется в очереди" (`Sources/ZVCamera/PTPIPTransport.swift:5-6`).
- **Framing.**
  - `PTPIPPacketReader` appends chunks to a `Data`. A packet is complete when `buffer.count >= length`. Only `length < 8` throws `badLength`; there is no maximum-length check (`Sources/ZVCore/PTPIP/PTPIP.swift:132-153`).
  - Each packet copies its payload and the remainder (`Data(buffer.dropFirst(...))`). This is linear per packet, with no quadratic append (the Android Kotlin reader had to be fixed for that, `git show 6703fdf`).
  - `deliver` uses `while let packet = try? reader.next()`, so a framing error is swallowed and the buffer stays wedged (`Sources/ZVCamera/PTPIPTransport.swift:146`).
- **Transactions.**
  - `transact` takes a FIFO lock (`acquire` / `release` with a `busy` flag and an array of continuations) (`Sources/ZVCamera/PTPIPTransport.swift:324-338`).
  - It assigns `tid = nextTransactionID` (with wrapping increment) and writes OperationRequest. For data out it adds StartData plus one EndData carrying the whole payload (`Sources/ZVCamera/PTPIPTransport.swift:269-280`; `Sources/ZVCore/PTPIP/PTPIP.swift:68-89`).
  - It then loops on `nextPacket(timeout: timeout)`. It answers ProbeRequest (13) with ProbeResponse (14) and feeds everything else to `PTPIPTransaction.consume` (`Sources/ZVCamera/PTPIPTransport.swift:281-293`).
  - `consume` ignores StartData. It appends Data and EndData only for a matching tid, and returns on an OperationResponse with a matching tid. Its doc comment: "Пакеты чужих транзакций — остатки оборванного обмена — пропускаются" (`Sources/ZVCore/PTPIP/PTPIP.swift:155-183`). This is tested in `Tests/ZVCoreTests/PTPIPTests.swift:66-76`.
- **Event channel.** A `Task` loops on `event.nextPacket(timeout: nil)` (no timeout). It answers ProbeRequest and yields parsed Event packets into an unbounded `AsyncStream` (created at `Sources/ZVCamera/PTPIPTransport.swift:189`). When the channel fails it calls `close()` on the whole transport (`Sources/ZVCamera/PTPIPTransport.swift:308-322`).
- **Probes.** The app only answers camera probes, on both channels (`Sources/ZVCamera/PTPIPTransport.swift:285-288, 313-314`). It never sends a ProbeRequest itself, and there is no builder for one (`Sources/ZVCore/PTPIP/PTPIP.swift:91`). The Wi-Fi plan had listed "ping/pong" (`docs/superpowers/plans/2026-10-24-wifi-ptpip.md:13`).
- **PTP/IP Cancel (type 11)** is defined (`Sources/ZVCore/PTPIP/PTPIP.swift:46`) but never sent (grep).
- **Writes.** `connection.send(content:, completion: .contentProcessed)`. Any error means `.disconnected`, and there is no timeout (`Sources/ZVCamera/PTPIPTransport.swift:79-85`).
- **Delayed ACK.** The protocol notes say the camera holds its short AccessDenied reply until the previous segment is ACKed. Android's ~40 ms delayed ACK cut the rate to 16–19 fps until TCP_QUICKACK was set before each command-socket read (`docs/protocol.md:309`; `git show 6703fdf`). The Mac code has no equivalent: `disableAckStretching` is unset (`Sources/ZVCamera/PTPIPTransport.swift:30-36`). The docs say the Mac reaches 25 fps with pipelining (`docs/protocol.md:310`).
- **Big objects.** Files arrive as 2 MB Data packets, and a live-view frame comes "одним куском" (`docs/protocol.md:303`).

### Inferences
- **The 8 s timeout covers whole packets, not bytes.** The waiter is resumed only for complete packets, and partial bytes never re-arm its timer (`Sources/ZVCamera/PTPIPTransport.swift:106-110, 142-156`). The timer therefore measures "time to receive one complete PTP/IP packet". A 2 MB Data packet on a link below about 2 MB / 8 s ≈ 262 KB/s (≈ 2.1 Mbit/s) times out even though bytes keep flowing. The comment at `Sources/ZVCamera/PTPIPTransport.swift:283` assumes otherwise. Live-view packets (22–46 KB) would need a link below about 3–6 KB/s to hit this.
- **No parser desync, but head-of-line blocking.** After a timeout the camera keeps sending the abandoned transaction's data, because no Cancel is sent. The tid filter discards it, but the next transaction waits for all of it to drain. That is trivial for a 22 KB frame and long for a multi-MB file.
- **Corruption is not caught quickly.** The reader can only wedge on a framing bug, since TCP does not corrupt data. It would then surface after 2 × 8 s as two timeouts rather than as an immediate error.
- **NWConnection keepalive.** This assumes Network.framework applies the options to the xnu per-connection keepalive (t_keepidle / t_keepintvl / t_keepcnt). Then each PTP/IP socket is dropped by the kernel once 5 + 3×2 = 11 s pass without receiving a segment, whether or not data is outstanding. For a healthy idle event channel that means probes every ~5 s, and the camera must be ACKing them, since sessions outlive 11 s of event silence.

### Gaps
- It is untested whether the camera's TCP sends small segments only after an ACK when talking to macOS, that is whether macOS ACK stretching or delayed ACK costs latency on weak links. There is no measurement of `disableAckStretching`.
- It is unknown how the camera reacts to a new OperationRequest that arrives while it is still sending an abandoned transaction's data (queue it, DeviceBusy, or misbehave).
- It is unconfirmed that NWProtocolTCP keepalive options map 1:1 onto the xnu fields. This was not verified in Apple docs.

## 3. Timeouts, deadlines, retries, watchdogs, and what counts as "session lost"

### Takeaway
On Wi-Fi the binding failure detector is in practice the TCP keepalive, which drops a socket after 11 s without receiving anything. The event channel can drop 6–11 s into an outage. Only after that come the app's two consecutive 8 s packet timeouts (about 16 s). Any socket death tears down the whole transport, which ends the session. The only "soft" layers are the 1.5 s stale-frame indicator and the producer's 100 ms–1 s error backoff.

### Cited Findings
**Every timeout, deadline and retry (Wi-Fi, live view and session):**

| What | Value | Where |
|---|---|---|
| TCP connect, command and event channels | 5 s app timer, then `cancel` | `Sources/ZVCamera/PTPIPTransport.swift:197, 207, 70-75` |
| Init Command Ack wait (`initTimeout`) | 5 s normally, 60 s while pairing; zv-probe `wifi-pair` 90 s | `Sources/ZVCamera/PTPIPTransport.swift:195, 202`; `Sources/ZVViewer/CameraController.swift:800`; `Sources/zv-probe/main.swift:49` |
| Init Event Ack wait | 5 s | `Sources/ZVCamera/PTPIPTransport.swift:210` |
| OpenSession 0x1002 | up to 4 attempts × 1.5 s; OK or 0x201E (SessionAlreadyOpen) accepted; `timeoutsInRow` reset between attempts | `Sources/ZVCamera/PTPIPTransport.swift:256-267` |
| Per-transaction, applied per complete PTP/IP packet | 8 s (`PTPIPTransport.init` default; `CameraController` uses the default) | `Sources/ZVCamera/PTPIPTransport.swift:185, 243, 284`; `Sources/ZVViewer/CameraController.swift:793` |
| Teardown rule | 2 consecutive timeouts (any operation) close the transport | `Sources/ZVCamera/PTPIPTransport.swift:181, 294-301` |
| TCP keepalive | idle 5 s, interval 2 s, count 3, so the kernel drops after 11 s receive-idle (see §2) | `Sources/ZVCamera/PTPIPTransport.swift:33-36` |
| Event-channel read | no timeout | `Sources/ZVCamera/PTPIPTransport.swift:312` |
| Socket write | no timeout | `Sources/ZVCamera/PTPIPTransport.swift:79-85` |
| Sony handshake | 0x1001, 0x9201(1), 0x9201(2), 0x9202 (up to 20 tries, 100 ms apart), 0x9201(3), 0x9205 PriorityMode, 0x9209; each op uses the generic 8 s timeout | `Sources/ZVCore/Session/CameraSession.swift:27-47` |
| Settle | 3 s, then 0x9209, then `isReady` | `Sources/ZVCore/Session/PTPTransport.swift:50-51`; `Sources/ZVCore/Session/CameraSession.swift:50-54` |
| Write confirmation | 0x9205, then refresh every 150 ms for up to 2.5 s | `Sources/ZVCore/Session/PTPTransport.swift:50-51`; `Sources/ZVCore/Session/CameraSession.swift:65-72, 112-123` |
| Capture | focus wait 1 s; object-in-memory wait 10 s; +2.5 s per additional file | `Sources/ZVCore/Photo/PhotoCapture.swift:27, 42, 52`; `Sources/ZVCore/Session/PTPTransport.swift:42` |
| Live-view poll pauses | 20 ms after AccessDenied, DeviceBusy or OK-without-JPEG; 50 ms after InvalidObjectHandle | `Sources/ZVCore/Session/CameraSession.swift:92-98` |
| Live-view producer backoff on other errors | `min(100 ms × failures, 1000 ms)`, reset on success; exits only on `.disconnected` or cancellation | `Sources/ZVViewer/CameraController.swift:862-876` |
| Stall indicator | `isFrameStale` when there is no frame for more than 1.5 s; evaluated per frame and every 500 ms | `Sources/ZVViewer/CameraController.swift:126-133, 947-953` |
| fps window / gap window | 2 s / 10 s | `Sources/ZVViewer/CameraController.swift:922-931, 947-950` |
| Reconnect pause after a lost session | 2, 4, 8, 10, 10… s, reset when a session reaches `.ready` | `Sources/ZVCore/PTPIP/WiFiPolicy.swift:37-49`; `Tests/ZVCoreTests/WiFiPolicyTests.swift:21-33` |
| Dial loop | `wifiTick` every 1 s (skipped while an attempt runs, via `wifiBusy`) | `Sources/ZVViewer/CameraController.swift:135-140, 739-742` |
| SSDP | 4 s window, M-SEARCH every 0.7 s, `SO_RCVTIMEO` 200 ms | `Sources/ZVCamera/PTPIPTransport.swift:373, 386-387, 397-407` |
| Stale-session hint | after 3 failed attempts to a camera found by SSDP | `Sources/ZVCore/PTPIP/WiFiPolicy.swift:22-28`; `Sources/ZVViewer/CameraController.swift:771-775, 785-789, 834-835` |
| Recording-mode driver | waits up to 6 s (60 × 100 ms) per write, +500 ms after a format write, 300 ms re-checks | `Sources/ZVViewer/CameraController.swift:256-284` |
| Colour-temperature mode switch | waits 4 s | `Sources/ZVViewer/CameraController.swift:302-313` |
| USB | command 8 s; openSession 10 s; closeSession 3 s; retry 2/4/8/10 s; cable-access hint 5 s | `Sources/ZVCamera/ICCameraTransport.swift:14, 43-47, 55-59`; `Sources/ZVCamera/CameraDiscovery.swift:42-53`; `Sources/ZVCore/Session/ConnectionTracker.swift:40-44`; `Sources/ZVCore/Session/CableAccessWatch.swift:7` |

**Definition of "session lost".**
- The trigger is `CameraSession.close()`, which finishes the `snapshots` stream (`Sources/ZVCore/Session/CameraSession.swift:138-142`). The `CameraController` snapshot loop then ends and calls `onEnded()`, which is `wifiLost()` for Wi-Fi (`Sources/ZVViewer/CameraController.swift:849-856, 815-817`).
- `CameraSession.close()` runs in two cases:
  - `rawSend` catches `TransportError.disconnected` (`Sources/ZVCore/Session/CameraSession.swift:145-152`);
  - the transport event stream finishes (`Sources/ZVCore/Session/CameraSession.swift:127-136`).
- That stream finishes in `PTPIPTransport.close()` (`Sources/ZVCamera/PTPIPTransport.swift:246-253`), which is called when:
  - two timeouts happen in a row (`Sources/ZVCamera/PTPIPTransport.swift:294-301`);
  - any command-channel disconnect happens: a receive error or FIN, `.failed`, `.cancelled`, or a send error (`Sources/ZVCamera/PTPIPTransport.swift:302-305, 59-64, 82, 134-135`);
  - the event channel dies (`Sources/ZVCamera/PTPIPTransport.swift:319-320`);
  - a connect fails (`Sources/ZVCamera/PTPIPTransport.swift:215-218`).
- A single timeout never ends the session. It only throws `.timeout` to the caller; `CameraSession` does not catch it (`Sources/ZVCore/Session/CameraSession.swift:145-152`).
- A Wi-Fi session that reports not-PC-Remote also counts as a loss (`Sources/ZVViewer/CameraController.swift:670-676`).

### Inferences
**Effective session-loss thresholds for a total outage** (combining §2's xnu rule with the code):
- The event channel is dropped at `last_rx + 11 s`. While idle its last receive (a probe ACK, every ~5 s) falls within [T0−5 s, T0], so the drop lands at T0+6…11 s.
- The command channel, during live view, has its last receive at about T0, so it is dropped at T0+11 s.
- The app-level 2 × 8 s rule would fire at about T0+16.1 s, so it only matters when TCP keeps receiving but PTP replies stop (for example, a hung camera that still ACKs).
- In practice, any outage over ~6–11 s loses the session. Under ~6 s it never does.

### Gaps
- Real-world timing of the keepalive drop was never measured on this app. There are no logs (see §7).

## 4. Reconnection after a loss: backoff, re-establishment sequence, duration, UI, stale camera session

### Takeaway
A loss triggers a full teardown: the frame, properties and UI state are wiped and the waiting screen appears. Then come a 2/4/8/10 s pause, a re-dial of the last host, and SSDP if that fails. The whole Init, OpenSession and Sony handshake re-runs. Live view resumes about 2.5–3.5 s after a clean loss, and controls about 3 s later. If the camera never received the app's FIN or RST (the usual case when the link itself died), it rejects new connections for ~2.5 min, and the app only shows a hint.

### Cited Findings
- **Teardown on loss.** `wifiLost()` calls `teardown()`, then `wifiPolicy.sessionLost(now:)` (2/4/8/10 s), sets `connectionMessage = "Связь по Wi-Fi пропала. Повтор через \(seconds) с…"`, and calls `connectAttachedIfAny()` so a cable camera can take over (`Sources/ZVViewer/CameraController.swift:841-846, 595-598`).
- **`teardown()`** (`Sources/ZVViewer/CameraController.swift:966-1003`):
  - cancels all session tasks;
  - closes the transport asynchronously (`Task { await close?() }`);
  - nils `session`, `coalescer`, `targets`, `pending`, `frame`, `lastJPEG`, the stats, `histogram`, `properties`, `deviceInfo`, `isRecording`, `recordingStartedAt` and `review`;
  - sets `phase = .searching`.
- `RootView` then shows `WaitingView` instead of `LiveScreen` (`Sources/ZVViewer/ZVViewerApp.swift:36-43`), with the connection message under the instructions (`Sources/ZVViewer/WaitingView.swift:19-21`). `LiveScreen.onDisappear` clears `pillInfo` (`Sources/ZVViewer/LiveScreen.swift:66-67`). Shots and presets are not cleared (they are not in `teardown`).
- **Dial loop.**
  - `wifiTick` needs:
    - `wifiWanted`: no session, no attached cable camera (unless that camera is not in PC Remote), and Wi-Fi enabled or pairing in progress;
    - `!wifiBusy`;
    - `mayAttempt`: `now >= nextAttempt`.
    - Code: `Sources/ZVViewer/CameraController.swift:719-725, 739-742`; `Sources/ZVCore/PTPIP/WiFiPolicy.swift:14-16`.
  - It first tries `Prefs.wifiHost` (`tryWiFi(last, discovered: false)`). On failure it runs a 4 s SSDP search and tries the other found hosts. A failure at the last host while SSDP still sees it counts toward the stale hint (`Sources/ZVViewer/CameraController.swift:743-783`).
  - Failed attempts do not move `nextAttempt`. Only `sessionLost` does (`Sources/ZVCore/PTPIP/WiFiPolicy.swift:22-49`).
- **Re-establishment sequence.**
  - Each attempt creates a new `PTPIPTransport` with the same GUID identity (`Sources/ZVViewer/CameraController.swift:793`).
  - `connect` runs: command TCP connect (5 s), InitCommandRequest, InitCommandAck (5 s), event TCP connect (5 s), InitEventRequest, InitEventAck (5 s), start of event listening, then OpenSession (4 × 1.5 s) (`Sources/ZVCamera/PTPIPTransport.swift:195-220, 256-267`).
  - `runSession` then runs (`Sources/ZVViewer/CameraController.swift:640-684`):
    - `phase = .connecting`;
    - `session.start()`: GetDeviceInfo 0x1001, 0x9201 phases 1 and 2, 0x9202 (≤20 × 100 ms), 0x9201 phase 3, PriorityMode 0xD25A = 1, GetAllExtPropInfo 0x9209, then event listening (`Sources/ZVCore/Session/CameraSession.swift:27-47`);
    - `phase = .settling`;
    - `startStreams`, which restarts live view;
    - `waitUntilReady` (3 s plus 0x9209);
    - `phase = .ready`, which calls `wifiPolicy.sessionReady()` (`Sources/ZVViewer/CameraController.swift:661-667, 816`).
- **Speed when the camera answers.** Documented: "через секунду камера отвечает за 8–30 мс" (`docs/protocol.md:307`). The camera ignores setting writes for the first ~3 s (`docs/protocol.md:67`), which is why settle exists.
- **Worst-case single attempt** before giving up: 5 + 5 + 5 + 5 + 6 s = 26 s (`Sources/ZVCamera/PTPIPTransport.swift:197-213, 256-267`).
- **Stale camera session.** "Оборванное подключение камера держит ~2,5 минуты. Если до неё не дошло закрытие соединения … новые подключения она отбрасывает, пока не выйдет срок. Нормальное закрытие освобождает её сразу" (`docs/protocol.md:308`). Handling today:
  - a hint after 3 failures to a discovered camera: "Камера найдена (…), но пока не отвечает — видимо, держит прошлое подключение. Обычно это проходит за 1–2 минуты; если нет — выключите и снова включите на камере PC Remote" (`Sources/ZVViewer/CameraController.swift:785-789`; `Sources/ZVCore/PTPIP/WiFiPolicy.swift:27-28`);
  - user-guide text (`docs/user-guide.md:390`);
  - acceptance of 0x201E SessionAlreadyOpen on OpenSession (`Sources/ZVCamera/PTPIPTransport.swift:260`);
  - nothing else: no session resume, no keeping sockets alive across an outage, no forced RST.
- **Init Fail.** Any Init Fail reason becomes `PTPIPError.rejected(reason:)` (`Sources/ZVCamera/PTPIPTransport.swift:203-205`). Outside pairing, `tryWiFi` then clears `Prefs.wifiCameraName` and stops dialling ("программа с ней не сопряжена") (`Sources/ZVViewer/CameraController.swift:819-829`). Only reason 1 (not paired) is documented (`Sources/ZVCore/PTPIP/PTPIP.swift:22-23`; `docs/protocol.md:290`).

### Inferences
- **Typical time after a clean loss** (the camera RST'd, or it received our FIN): backoff 2 s + tick ≤1 s + connect and handshake ~0.1–0.5 s, so the first frame arrives about 2.5–3.5 s after the loss and `.ready` about 3 s later. The user sees the full waiting screen, then a spinner on a black `LiveScreen` (`frame == nil`, `Sources/ZVViewer/LiveScreen.swift:135-137`), then video.
- **After an outage of more than ~6–11 s,** the Mac's FIN or RST is sent into the dead link, so a stale camera session is the expected outcome. Recovery then takes up to ~2.5 min, plus the attempt cadence (≈ 1 s tick + 5–10 s attempt + 4 s SSDP).
  - This window is set by the camera, and it implies the camera itself would tolerate a ~2-minute silence on the old connection.
  - The Mac's 11 s keepalive is the component that turns a 15-second outage into a 2.5-minute one.
- **The REC timer** restarts from the reconnect moment while the camera keeps recording. `recordingStartedAt` is cleared in teardown and re-set when 0xD21D reads 1 (`Sources/ZVViewer/CameraController.swift:888-893, 996-997`).
- **Risk if the stale camera answers a new Init with Init Fail** using a reason other than 1: the app would wrongly "un-pair" itself.

### Gaps
- No measured Wi-Fi reconnect time (loss to first frame, loss to ready) exists in the repo.
- What the camera does with a new connection while holding a stale one is not recorded: TCP refuse, accept and stay silent, or Init Fail with some reason.
- The ~2.5 min reference point is unclear: from the last packet, or from the camera's own dead-peer detection.
- If the link returns after the Mac's sockets were dropped, the camera's retransmissions should trigger an RST from the Mac and could free the camera quickly. Untested.

## 5. Failure-mode walkthrough (a)–(h)

### Takeaway
Stalls under ~1.5 s are invisible apart from a gap statistic. From 1.5 s up to ~6 s the frame dims with "Нет картинки с камеры…" and recovers on its own. Beyond ~6–11 s the keepalive kills the session, the UI falls back to the waiting screen, and a stale camera session often makes recovery take minutes. User actions during a stall queue behind the in-flight transaction and can be the second timeout that tears the session down.

### Cited Findings
- **(a) 300 ms stall mid-frame.**
  - Code path: partial bytes accumulate in `PTPIPPacketReader` (`Sources/ZVCore/PTPIP/PTPIP.swift:142-152`). The 8 s waiter timer is far away (`Sources/ZVCamera/PTPIPTransport.swift:106-110`). Keepalive needs ≥5 s idle (`Sources/ZVCamera/PTPIPTransport.swift:34`).
  - User sees: the last frame is held, with no dimming (threshold 1.5 s, `Sources/ZVViewer/CameraController.swift:952`). `frameGapMs` shows ≥300 ms for 10 s (`Sources/ZVViewer/CameraController.swift:922-931`). One older frame may be displayed, then fresh ones.
  - Recovery: as soon as TCP delivers.
- **(b) 2 s stall.**
  - Code path: as in (a). No timeouts fire and keepalive does not drop.
  - User sees: dimming plus "Нет картинки с камеры…" 1.5–2.0 s after the last frame (500 ms tick) (`Sources/ZVViewer/CameraController.swift:126-133, 947-953`; `Sources/ZVViewer/LiveScreen.swift:128-134`).
  - A write in flight may run out of its 2.5 s confirmation window. It then silently ends as `notApplied`, and `pending` clears (`Sources/ZVCore/Session/CameraSession.swift:65-72, 112-123`; `Sources/ZVViewer/CameraController.swift:651-654, 955-958`).
  - Recovery: automatic when the link returns, with no reconnect.
- **(c) 10 s stall.**
  - Code path, likely outcome: the event-channel keepalive drop at T0+6…11 s leads to `listenForEvents` breaking, then `close()`, `CameraSession.close()`, the end of `snapshots`, and `wifiLost()` (`Sources/ZVCamera/PTPIPTransport.swift:311-320`; `Sources/ZVCore/Session/CameraSession.swift:127-142`; `Sources/ZVViewer/CameraController.swift:849-856, 841-846`).
  - Code path, if the event channel survives: at T0+8 s the command packet timeout fires (`timeoutsInRow = 1`, `.timeout`). The producer sleeps 100 ms and re-polls (`Sources/ZVCamera/PTPIPTransport.swift:294-301`; `Sources/ZVViewer/CameraController.swift:871-875`). The link returns at T0+10 s. Stale packets of the old tid are skipped (`Sources/ZVCore/PTPIP/PTPIP.swift:155-183`), the new reply resets `timeoutsInRow` (`Sources/ZVCamera/PTPIPTransport.swift:289-291`) and live view continues.
  - User sees: a dimmed frame from 1.5 s, then likely the waiting screen with "Связь по Wi-Fi пропала. Повтор через 2 с…".
  - Recovery: 2.5–3.5 s after the loss if the camera releases the session; otherwise up to ~2.5 min (§4).
- **(d) 40 s stall.**
  - Code path: the session is lost by T0+11 s at the latest (command-channel keepalive) and teardown runs.
  - During the outage, each attempt fails on the 5 s TCP connect timeout (`.waiting` and `.preparing` are ignored until the timer fires) (`Sources/ZVCamera/PTPIPTransport.swift:54-58, 70-75`). SSDP finds nothing and calls `wifiPolicy.nothingFound()` (`Sources/ZVViewer/CameraController.swift:755-761`). One cycle takes about 10 s.
  - When the link returns: the camera is reachable, but it probably holds the stale session. Attempts fail until it expires, and the stale hint appears after 3 discovered failures (`Sources/ZVViewer/CameraController.swift:771-775, 785-789`).
  - User sees: the waiting screen for the whole time.
  - Recovery: from ≤ ~10 s after the link returns (best case) up to ~2.5 min.
- **(e) TCP RST or connection reset.**
  - Code path: a receive error or `.failed` leads to `fail(.disconnected)`, which resumes the waiter. `transact` catches `.disconnected` and calls `close()`, ending the session (`Sources/ZVCamera/PTPIPTransport.swift:59-61, 130-139, 158-165, 302-305`). An RST on the event channel ends its loop the same way (`Sources/ZVCamera/PTPIPTransport.swift:312, 319-320`).
  - User sees: the waiting screen immediately.
  - Recovery: a camera-initiated reset presumably leaves no stale session, so live view returns after ≈2.5–3.5 s and controls after about +3 s (§4). If the RST came from PC Remote being switched off, attempts fail and the "not PC Remote" outcome counts as a loss, so the backoff grows (`Sources/ZVViewer/CameraController.swift:670-676`).
- **(f) The Mac's Wi-Fi drops briefly, roams, or sleeps and wakes.**
  - Code path: there is no path, viability or sleep handling: no handlers and no `NSWorkspace` sleep or wake observers (grep). `.waiting` is ignored (`Sources/ZVCamera/PTPIPTransport.swift:54-58`), so the timelines above apply unchanged.
  - The app holds a power or App Nap assertion only in automation mode. It is `.userInitiatedAllowingIdleSystemSleep`, which also permits idle sleep (`Sources/ZVViewer/AutomationServer.swift:14-16, 38-39`). Normal use can therefore idle-sleep or App-Nap during unattended viewing. App Nap is documented to drop live view to 6–12 fps when the window is covered (`Sources/ZVViewer/AutomationServer.swift:14-15`).
  - In Wi-Fi Direct the camera AP has no internet (`docs/protocol.md:312`). The app neither pins the interface nor notices macOS switching networks.
- **(g) One channel dies while the other is fine.**
  - Event channel dies, command channel fine: the whole transport closes and the session is lost, even though live view only needs the command channel (`Sources/ZVCamera/PTPIPTransport.swift:308-322`).
  - Command channel dies, event channel fine: the next or in-flight transaction (within ≤20 ms during live view) gets `.disconnected` from the waiter or the send completion, and `close()` takes down both (`Sources/ZVCamera/PTPIPTransport.swift:82, 97-100, 302-305`).
  - A silent half-dead event channel is caught only by keepalive (about 11 s), because its read has no timeout (`Sources/ZVCamera/PTPIPTransport.swift:312`).
- **(h) The user changes a setting or presses the shutter during degradation.**
  - `phase` stays `.ready` during a stall, so `isWritable` stays true and controls stay enabled (`Sources/ZVViewer/CameraController.swift:145-147`).
  - `request()` sets `targets` and `pending`. The coalescer runs one write per property (`Sources/ZVViewer/CameraController.swift:317-322`; `Sources/ZVCore/Session/WriteCoalescer.swift:15-43`).
  - `session.set` queues FIFO behind the in-flight GetObject (`Sources/ZVCamera/PTPIPTransport.swift:269-272, 324-338`). Then `waitFor` adds a refresh every 150 ms for up to 2.5 s, each a full 0x9209 transaction (`Sources/ZVCore/Session/CameraSession.swift:65-72, 112-123`).
  - Errors are swallowed (`try? await session.set`) and `pending` clears silently, so the UI simply shows the camera's value (`Sources/ZVViewer/CameraController.swift:651-654, 955-963`). A write timeout counts toward the two-in-a-row rule (`Sources/ZVCamera/PTPIPTransport.swift:294-301`).
  - Shutter: `capturePhoto` sends half press, an AF wait of up to 1 s, full press, release, release, then download polling (`Sources/ZVCore/Photo/PhotoCapture.swift:16-58`). On error it releases both buttons best-effort and the app shows "Снимок не удался: \(error)" with a raw enum description (`Sources/ZVViewer/CameraController.swift:423-426`).
  - `toggleRecording` has no release-on-failure path between press and release (`Sources/ZVCore/Session/CameraSession.swift:75-83`).
  - On teardown, queued intent (`targets`, `pending`, the recording-mode driver) is discarded (`Sources/ZVViewer/CameraController.swift:976-982`).

### Inferences
- **Recovery adds TCP time on top of the stall.** The camera's RTO backoff (unknown embedded stack) can stretch a 300 ms or 2 s radio stall into a longer visible freeze before data flows again.
- **Roaming.** Roaming between APs of the same network usually keeps the IP, so it behaves like (a) or (b). An IP or subnet change makes the old sockets useless until keepalive drops them (≤11 s), and the last-host re-dial fails until the Mac is back on the camera's network.
- **Sleep and wake.** If the Mac sleeps longer than the camera's ~2.5 min window, the camera frees itself and reconnect after wake should work. A shorter sleep risks the stale-session path. Not verifiable from code.

### Gaps
- None of (a)–(h) has measured timings in the repo. Whether xnu's `tcp_now` advances during sleep, and so how keepalive behaves across sleep, was not checked. The Mac has not been tested in Wi-Fi Direct.

## 6. Control vs live-view interleaving on the single PTP/IP command channel

### Takeaway
Every PTP operation goes through one FIFO lock in `PTPIPTransport`. That includes live-view polls, event-driven property refreshes, write confirmations and photo downloads. Live view therefore never preempts a user command, and vice versa. A command waits for the one transaction in flight: about 25–35 ms on a good link, up to 8 s in a stall, and the whole download time during a photo transfer.

### Cited Findings
- **The lock.** `acquire()` sets `busy`, or appends a continuation. `release()` hands off to the first waiter. Every `transact` holds it from request write to response (`Sources/ZVCamera/PTPIPTransport.swift:269-306, 324-338`).
- **Reentrancy.** `CameraSession` is an actor, but its methods suspend at `transport.send`. The live-view loop, refreshes, writes and captures therefore interleave at transaction granularity (`Sources/ZVCore/Session/CameraSession.swift:86-103, 145-159`). The docs say "команды к камере идут строго по одной" (`docs/architecture.md:180`).
- **Event-driven refresh.** Each 0xC203 event triggers a full GetAllExtPropInfo, processed sequentially from an unbounded stream with no coalescing (`Sources/ZVCore/Session/CameraSession.swift:127-136`; `Sources/ZVCamera/PTPIPTransport.swift:189`).
- **Write confirmation** polls refresh every 150 ms for up to 2.5 s per write (`Sources/ZVCore/Session/PTPTransport.swift:50-51`; `Sources/ZVCore/Session/CameraSession.swift:112-123`). The recording-mode driver serialises further: one property at a time, waits of up to 6 s, plus 0.5 s after a format write (`Sources/ZVViewer/CameraController.swift:233-284`).
- **Photo download.** Download is a single GetObject(0xFFFFC001) per file, with 2 MB data packets on Wi-Fi (`Sources/ZVCore/Photo/PhotoCapture.swift:43-56`; `docs/protocol.md:303`). Live-view polls queue behind each file's GetObject for its whole transfer. Between files, during the 150 ms object-in-memory polling, they interleave. For scale, RAW+JPEG takes about 12–14 s from tap to file on a Mac over Wi-Fi versus about 4 s over USB (`README.md:122`). The repo does not split out how much of that is the GetObject transfer itself.
- **Live-view backoff.** Between polls the producer releases the lock during the 20 ms sleeps (`Sources/ZVCore/Session/CameraSession.swift:93-98`).

### Inferences
- **Worst-case wait for a user command:**
  - good link: one live-view transaction, about 25–35 ms;
  - degraded link: one frame transfer (frame size ÷ throughput, plus RTT);
  - stall: up to 8 s, until that transaction times out, or until keepalive kills the socket;
  - during a download: until the file finishes, or the 2 MB-packet timeout of §2 fires.
- **Chatter costs frames.** On a weak link every extra 0x9209 (≈2.8 KB plus an RTT) directly costs live-view frames. A write therefore lowers fps for ~2.5 s, and so do bursts of 0xC203 events (dial turning, recording).
- **Timeouts are shared.** Because all operations share one timeout counter, a background refresh or user write that times out right after a live-view timeout tears the session down.

### Gaps
- How often the camera emits 0xC203 while recording or streaming is not documented, so the refresh load under normal use is unknown.

## 7. Observability, and how to measure streaming quality under degradation

### Takeaway
The only streaming metrics are `fps` (2 s window), `frameGapMs` (worst gap in 10 s) and the 1.5 s stale flag. There is no logging, no transport counters (timeouts, reconnects, RTT, AccessDenied ratio, bytes/s) and no RSSI in the API. The automation API can still drive a soak test by polling `/state` and `/frame`, but it sees only coarse aggregates.

### Cited Findings
- **App metrics.** `fps`, `shownFPS`, `frameGapMs`, `isFrameStale` and `frameGaps` (`Sources/ZVViewer/CameraController.swift:26-30, 84-87, 98, 906-911, 922-953`).
- **Popovers.**
  - `FramesInfo` shows the longest gap in 10 s (`Sources/ZVViewer/LiveScreen.swift:318-341`).
  - `ConnectionInfo` shows the camera IP, the Mac IP and the Mac's own RSSI from CoreWLAN, refreshed every 1 s only while open (`Sources/ZVViewer/LiveScreen.swift:275-316`; `Sources/ZVViewer/CameraController.swift:933-944`).
  - Signal words: ≥−60 dBm "хороший", ≥−70 "средний", else "слабый" (`Sources/ZVViewer/LiveScreen.swift:313-315`). The hint says that in router mode the signal shown is Mac ↔ router (`Sources/ZVViewer/LiveScreen.swift:297-299`).
- **Logging.** The only log call in the app is the `NSLog` for a failed automation start (`Sources/ZVViewer/ZVViewerApp.swift:20`). Sources contain no `os_log`, `Logger` or signposts (grep).
- **Automation API** (enabled by `--automation` or `ZV_AUTOMATION=1`, on 127.0.0.1; `Sources/ZVViewer/AutomationServer.swift:18-41`):
  - `GET /state` returns `phase`, `fps` (rounded to 0.1), `frameGapMs`, `transport`, `wifi{enabled,pairing,host,cameraName,status}` and `frameSize` (only when a frame exists) (`Sources/ZVViewer/AutomationServer.swift:230-306`; `docs/automation-api.md:62-80`).
  - `GET /frame` returns `lastJPEG` (`Sources/ZVViewer/AutomationServer.swift:102-104`).
  - `connectionMessage` and RSSI are not exposed.
  - Automation mode holds `beginActivity`, so it is immune to App Nap where normal runs are not (`Sources/ZVViewer/AutomationServer.swift:14-16, 38-39`).
- **Scripts and tools.**
  - `scripts/zvapi.py` makes a single request with the token read from the info file (`scripts/zvapi.py:1-18`).
  - `scripts/e2e.py` has a `wait(pred, timeout, interval=0.15)` helper and asserts `fps >= 15` and `frameSize == [1024, 576]` (`scripts/e2e.py:43-58, 71`).
  - `zv-probe --wifi <host> lv N <path>` measures sequential, non-pipelined fps and frame dimensions over Wi-Fi; `seq … lvN` measures fps inside sequences (`Sources/zv-probe/main.swift:56-72, 138-152, 183-188`).
- **Tests.** No test covers `PTPIPTransport` or `PTPIPChannel` timing (only ZVCore parsing in `Tests/ZVCoreTests/PTPIPTests.swift`). `FakeCamera` can script live-view replies and a disconnect, but cannot inject latency, stalls or timeouts (`Tests/ZVCoreTests/FakeCamera.swift:15-150`).

### Inferences
- **A degraded-link soak test with today's API:**
  - Log `/state` at 2–5 Hz with `phase`, `fps`, `frameGapMs` and `transport`.
  - Derive session losses (`ready` to `searching`), time to first frame (`frameSize` appears) and time to `ready`.
  - Sample `/frame` byte sizes.
  - Correlate with RSSI collected outside the app.
  - Reproduce outages with host-side packet filtering, delay or loss towards the camera's IP on port 15740. Fixed durations of 0.3, 2, 10 and 40 s cover cases (a)–(d), with Network Link Conditioner or pf/dummynet as the tool. These tools are not in the repo, and their effect on LAN traffic must be confirmed.
- **Per-frame jitter** (p90 gap) is not visible through `/state` aggregates. Adding transport counters and a per-frame timing log would be needed for proper evaluation.

### Gaps
- There is no baseline dataset of fps or gap versus RSSI for the Mac in the repo.

## 8. USB path for comparison

### Takeaway
USB shares `CameraSession` and the same pipelined live-view tasks, including the 20 ms AccessDenied pause. The transport is ImageCaptureCore `requestSendPTPCommand` with an 8 s per-command timeout and no app-level serialisation or two-timeouts teardown. A USB session ends only on device removal.

### Cited Findings
- **USB transport.**
  - `ICCameraTransport.send` encodes the USB command container and calls `device.requestSendPTPCommand`. A `Once` races the completion against an 8 s `DispatchQueue.global().asyncAfter` timeout (`Sources/ZVCamera/ICCameraTransport.swift:14, 28-49`).
  - Errors map to `.disconnected` if the device was removed, else `.failed`. Timeouts map to `.timeout` (`Sources/ZVCamera/ICCameraTransport.swift:36-47`).
  - There is no lock and no `timeoutsInRow`.
  - Session end comes from `didRemove`, which calls `markRemoved` and finishes the event stream, which closes the `CameraSession` (`Sources/ZVCamera/ICCameraTransport.swift:73-82`; `Sources/ZVCore/Session/CameraSession.swift:127-136`).
  - Retries are 2/4/8/10 s while the device is attached (`Sources/ZVCore/Session/ConnectionTracker.swift:40-44`; `Sources/ZVViewer/CameraController.swift:600-637`).
  - openSession has a 10 s timeout and close a 3 s timeout (`Sources/ZVCamera/CameraDiscovery.swift:42-53`; `Sources/ZVCamera/ICCameraTransport.swift:53-63`).
- **AccessDenied pause.** 20 ms on the Mac for both transports (`Sources/ZVCore/Session/CameraSession.swift:93-96`), versus 5 ms on Android USB. Android's result: "кадр у камеры готов раз в 40 мс, и длинная пауза сбивает ровность"; 25 fps with 90% of intervals ≤47–49 ms (`docs/protocol.md:268`). The live-view section still says to repeat after 20 ms (`docs/protocol.md:152`).

### Inferences
- **USB optimisations:**
  - Switch to a 5 ms pause, or adaptive pacing (USB round trips are cheap).
  - Concurrent `requestSendPTPCommand` calls from the live-view loop and control are left to ImageCaptureCore to serialise. This is unverified, but no USB interleaving problems are documented.
  - USB tolerates stalls better than Wi-Fi: a timeout never tears down the session.

### Gaps
- No Mac USB fps or jitter numbers after the pipelining change are recorded in the repo. The docs give "20–24" in `docs/protocol.md:150` and "до 25" in `README.md:26`.

## 9. Frame sizes, resolution and bandwidth

### Takeaway
A live-view frame is 1024×576 in movie mode. The repo fixture is 22,260 B (a 1,524 B Sony header plus a 20,736 B JPEG). The Wi-Fi notes quote ~46 KB. At 25 fps that is about 4.5–9.2 Mbit/s of TCP payload over a 2.4 GHz-only camera radio, plus ~50 request/response round trips per second.

### Cited Findings
- **Fixture.** Inspecting `Tests/ZVCoreTests/Fixtures/lv_frame_raw.bin` read-only: 22,260 B total, header offset 1,524, JPEG 20,736 B, baseline SOF0 1024×576. The test asserts the 20,736 B JPEG (`Tests/ZVCoreTests/SonyParsingTests.swift:43-47`; `Tests/ZVCoreTests/CameraSessionWriteTests.swift:57-62`).
- **Docs.**
  - "Кадр превью (~46 КБ) приходит одним куском" (`docs/protocol.md:303`).
  - 1024×576 in video mode; in photo mode the size follows the aspect ratio, 1024×1024 at 1:1 (`docs/protocol.md:151`; `README.md:165`).
  - LiveViewImageQuality 0xD26A 1 and 2 give identical frames (`docs/protocol.md:155`).
  - The ZV-E10 radio is 2.4 GHz only (`docs/protocol.md:278`; `README.md:115`).
  - A Wi-Fi photo download runs at about 5 MB/s on the tablet (`docs/protocol.md:303`).
- **Property refresh** (0x9209) is about 2.8 KB: `Tests/ZVCoreTests/Fixtures/props_settled.bin` is 2,825 B (93 properties over USB; Wi-Fi reports 95, `docs/protocol.md:299`).
- **PTP/IP framing per poll** (built in `Sources/ZVCore/PTPIP/PTPIP.swift:68-89, 122-128`):
  - OperationRequest for GetObject: 22 B.
  - OperationResponse: 14 B.
  - Frame: StartData 20 B, plus Data/EndData 12 B of header plus payload.

### Inferences
- **Bandwidth at 25 fps:** 22.3 KB/frame ≈ 0.56 MB/s ≈ 4.5 Mbit/s; 46 KB/frame ≈ 1.15 MB/s ≈ 9.2 Mbit/s. Photo-mode 1:1 frames carry ~1.8× more pixels, so they are likely larger.
- **Router mode doubles the airtime.** If the Mac is also on Wi-Fi, each frame crosses the air twice (camera → AP → Mac).
- **Weak links cap the rate.** When the camera's 2.4 GHz link falls to low PHY rates, transfer time per frame plus RTT exceeds 40 ms and fps falls proportionally. Image quality has no effect, so the only adaptation knob is the request rate.

### Gaps
- No distribution of frame sizes per scene or mode, and no measured Mac Wi-Fi throughput, exists in the repo.

## 10. Documented behaviour vs code, and the history behind current constants

### Takeaway
All transport constants date from the first PTP/IP commit and were never tuned for weak links: keepalive 5/2/3, 5 s connect and Init, 8 s per transaction, two timeouts to tear down, OpenSession 1.5 s × 4. Later Wi-Fi commits addressed permission flicker, pipelining, policy and backoff. Several docs overstate the backoff or give stale numbers.

### Cited Findings
- **History.** `git log -- Sources/ZVCamera/PTPIPTransport.swift` shows only 42fed12, 2435efd, 8b6800c and 7a8b040.
  - **42fed12** (2026-10-24) introduced `noDelay`, keepalive 5/2/3 ("камера может пропасть без закрытия соединения … keepalive это заметит"), the 5 s connect, Init and Ack timeouts, the 8 s transaction timeout, OpenSession 1.5 s × 4 and `timeoutsInRow >= 2` (`git show 42fed12`).
  - **2435efd** stopped treating `.waiting` as failure. Before it, `.failed` and `.waiting` were handled alike. It also added the stale-connection hint after 3 failures. Motivation: macOS flags live connections "Local network prohibited" for ~1.5 s right after the user allows Local Network access (`docs/protocol.md:311`).
  - **8b6800c** added pipelining via `bufferingNewest(1)` ("25 fps over Wi-Fi (was ~22)"), the fps counter, `frameGapMs` and `localHost`.
  - **7a8b040** moved the decisions into `WiFiPolicy`: 2/4/8/10 s after lost sessions, Init only to paired cameras, the stale hint only for discovered cameras. It also added `adopt()` for cancellable pairing and moved SSDP onto a GCD queue.
  - **aa173ad** replaced "break on the first live-view error" with the failures and backoff loop and introduced `isFrameStale` at 1.5 s.
  - **7359ada** added the DeviceBusy retry in live view.
  - **6703fdf** (Android) documents the TCP_QUICKACK finding.
  - The Python spike measured ~22 fps over Wi-Fi (`docs/superpowers/plans/2026-10-24-wifi-ptpip.md:3`).
- **Differences between docs and code:**
  - **Backoff wording.** `docs/user-guide.md:99` ("повторяет попытки с растущей паузой: 2, 4, 8, потом каждые 10 секунд") and `docs/architecture.md:102` describe the pauses as between attempts. In code the pause grows only per lost session (`Sources/ZVCore/PTPIP/WiFiPolicy.swift:42-49`). Failed attempts re-run on every 1 s tick (`Sources/ZVViewer/CameraController.swift:739-783`). The message "Повтор через N с…" is static (`Sources/ZVViewer/CameraController.swift:844`).
  - **Frame size.** "~46 КБ" (`docs/protocol.md:303`) vs the 22,260 B fixture (§9).
  - **Frame rate.** The live-view section says "Около 20–24 кадров в секунду" (`docs/protocol.md:150`), predating pipelining, while the Wi-Fi section and README say 25 (`docs/protocol.md:310`; `README.md:26, 122`).
  - **Timeout comment.** The comment says the timeout is on the pause between packets, so a big RAW is slow but continuous (`Sources/ZVCamera/PTPIPTransport.swift:283`). The timer actually measures complete 2 MB packets (§2).
  - **Missing keepalive.** `docs/architecture.md:58` lists "Два тайм-аута подряд — обрыв" but not the keepalive, which in practice fires first (§3).
  - **Frozen-picture advice.** `docs/user-guide.md:396` advises re-plugging the cable when "Нет картинки с камеры…" persists, which does not fit Wi-Fi. On Wi-Fi the app instead tears down after ~6–16 s.
  - **AccessDenied pause.** `docs/protocol.md:152` says retry after 20 ms, consistent with Mac code. `docs/protocol.md:268` recommends 5 ms (Android).

### Inferences
- The constants look like reasonable first choices for "camera switched off or left the network" detection. None of them was evaluated against short radio outages.

### Gaps
- No commit or doc records why 5/2/3 or 8 s were chosen beyond the keepalive comment.

## 11. Concrete weaknesses for weak-signal robustness and smoothness (evidence and a one-line fix idea each)

### Takeaway
The biggest problems:
- **Outages become disconnects.** An outage of ~6–16 s or more is turned into a full disconnect: keepalive, the event-channel-kills-all rule and two-timeout teardown.
- **A disconnect becomes a long wait.** It often costs ~2.5 min, because the camera never sees our close, and the UI loses everything meanwhile.
- **Large transfers can kill the session.** On slow links, big transfers can tear the session down by themselves.
- **Smoothness has no adaptation.** There is no adaptive pacing, no jitter buffer, no early weak-link signal and no App Nap or sleep protection.

### Cited Findings
- **W1. Keepalive 5/2/3 caps tolerable outages at about 11 s** (event channel ~6–11 s) (`Sources/ZVCamera/PTPIPTransport.swift:33-36`; xnu rule in §2). Fix idea: much longer keepalive, or none on the command channel, plus app-level liveness with a long grace period (≥60–120 s, below the camera's ~2.5 min window).
- **W2. Losing the event channel kills the whole transport and session** (`Sources/ZVCamera/PTPIPTransport.swift:308-322`). Fix idea: treat it as degraded (poll 0x9209 slowly) and try to re-open only the event channel; keep live view running.
- **W3. Two consecutive 8 s timeouts of any operation tear down; the counter is shared by live view, writes and refreshes** (`Sources/ZVCamera/PTPIPTransport.swift:181, 294-301`). Fix idea: base liveness on "time since last received byte" with a long grace period, and enter a "weak link / reconnecting in place" state instead of closing.
- **W4. The per-packet deadline counts complete PTP/IP packets.** A 2 MB packet below ~262 KB/s times out mid-transfer. No PTP/IP Cancel is sent, so the abandoned file keeps streaming and the next timeout tears down (`Sources/ZVCamera/PTPIPTransport.swift:88-112, 142-156, 282-293`; `Sources/ZVCore/PTPIP/PTPIP.swift:46, 142-152`; `docs/protocol.md:303`). Fix idea: re-arm the deadline on every received chunk, and send a Cancel (type 11) or drain deliberately.
- **W5. Session loss wipes frame, properties, pending writes and REC time, and replaces `LiveScreen` with `WaitingView`** (`Sources/ZVViewer/CameraController.swift:966-1003`; `Sources/ZVViewer/ZVViewerApp.swift:36-43`). Fix idea: keep the last frame dimmed with a "reconnecting…" overlay and countdown, keep properties and recording start, and resume in place.
- **W6. The stale camera session (~2.5 min) after an outage is only explained, never avoided** (`Sources/ZVViewer/CameraController.swift:771-775, 785-789`; `docs/protocol.md:308`). Fix idea: avoid closing during transient outages (W1–W3); after the link returns, re-dial the last host fast and log what the camera answers.
- **W7. Any Init Fail reason clears the stored pairing outside pairing mode** (`Sources/ZVViewer/CameraController.swift:819-829`; `Sources/ZVCamera/PTPIPTransport.swift:203-205`; `Sources/ZVCore/PTPIP/PTPIP.swift:22-23`). Fix idea: un-pair only on reason 1; treat other reasons as busy or stale and keep retrying.
- **W8. No viability, better-path or path monitoring; `.waiting` silently ignored; no sleep or wake handling** (`Sources/ZVCamera/PTPIPTransport.swift:47-68`; grep for `viabilityUpdateHandler`, `NWPathMonitor` and `NSWorkspace` sleep notifications returns none). Fix idea: use `viabilityUpdateHandler` or `NWPathMonitor` to show "link lost" at once and pause polling; on wake or path return, probe immediately.
- **W9. The stall UI is binary and late (1.5 s), and controls stay enabled while stalled because `phase` stays `.ready`** (`Sources/ZVViewer/CameraController.swift:145-147, 947-953`; `Sources/ZVViewer/LiveScreen.swift:128-134`). Fix idea: a graded link-quality indicator from frame age, RTT and fps; queue or disable writes while stalled.
- **W10. Poll pacing is fixed: 20 ms after AccessDenied, no RTT awareness, and every denial is a full Wi-Fi round trip** (`Sources/ZVCore/Session/CameraSession.swift:86-103`; `docs/protocol.md:322`). Fix idea: schedule polls from the measured frame interval and RTT, and lower the target fps when RTT or loss grows. Image quality cannot be lowered (`docs/protocol.md:155`), so rate is the only knob.
- **W11. No jitter buffer or display pacing: frames are shown as they arrive** (`Sources/ZVViewer/CameraController.swift:859, 878-885, 899-901`). Fix idea: an optional 1–2 frame playout buffer paced by the display refresh when jitter is high.
- **W12. Event-driven refresh is uncoalesced (unbounded stream, one 0x9209 per event), and write confirmation polls every 150 ms; both compete with live view** (`Sources/ZVCamera/PTPIPTransport.swift:189`; `Sources/ZVCore/Session/CameraSession.swift:112-136`; `Sources/ZVCore/Session/PTPTransport.swift:50-51`). Fix idea: coalesce events with a dirty flag or `bufferingNewest(1)`; slow the confirmation polling on Wi-Fi.
- **W13. Photo downloads monopolise the single channel: live view freezes during each file's GetObject transfer, a multi-second part of the 12–14 s tap-to-file time on a good link** (`Sources/ZVCore/Photo/PhotoCapture.swift:43-56`; `Sources/ZVCamera/PTPIPTransport.swift:269-306`; `README.md:122`). Fix idea: show "downloading" instead of "no picture", and investigate chunked or partial reads interleaved with polls. Camera support for that is unverified.
- **W14. No macOS ACK tuning (`disableAckStretching` unset), while Android needed TCP_QUICKACK for the same camera quirk** (`Sources/ZVCamera/PTPIPTransport.swift:30-36`; `docs/protocol.md:309`). Fix idea: A/B-test `disableAckStretching = true` on the AccessDenied round-trip time.
- **W15. No power or App Nap assertion outside automation: a covered window drops to 6–12 fps and idle sleep can kill the session** (`Sources/ZVViewer/AutomationServer.swift:14-16, 38-39`). Fix idea: hold `beginActivity([.userInitiated, .latencyCritical, .idleDisplaySleepDisabled])` while live view runs.
- **W16. No transport observability** (no logs or counters; RSSI only in a popover; `connectionMessage` not in `/state`) (`Sources/ZVViewer/ZVViewerApp.swift:20`; `Sources/ZVViewer/AutomationServer.swift:230-306`; `Sources/ZVViewer/CameraController.swift:940-944`). Fix idea: `Logger` and signposts in `PTPIPTransport`, plus a `/state.link` block with RTT p50/p90, AccessDenied ratio, timeouts, reconnects, last-byte age and RSSI.
- **W17. Slow reconnect cadence: at least 2 s backoff plus a 1 s tick, and each failed last-host try adds a 4 s SSDP search before the next re-dial** (`Sources/ZVViewer/CameraController.swift:135-140, 743-761`; `Sources/ZVCore/PTPIP/WiFiPolicy.swift:44-49`; `Sources/ZVCamera/PTPIPTransport.swift:373`). Fix idea: re-dial the last host every ~1 s with a short connect timeout, in parallel with SSDP.
- **W18. Failed writes are silent, and user intent is dropped on teardown** (`Sources/ZVViewer/CameraController.swift:651-654, 955-958, 976-982`). Fix idea: surface write failures; optionally re-apply the last targets after resume.
- **W19. Minor transport hygiene and tests:**
  - socket writes have no timeout (`Sources/ZVCamera/PTPIPTransport.swift:79-85`);
  - the framing error is swallowed and the reader wedges (`Sources/ZVCamera/PTPIPTransport.swift:146`; `Sources/ZVCore/PTPIP/PTPIP.swift:147`);
  - each `nextPacket` schedules an 8 s `asyncAfter`, so hundreds of timers are pending during live view (`Sources/ZVCamera/PTPIPTransport.swift:106-110`);
  - there are no `PTPIPTransport` tests, and `FakeCamera` has no stall injection (`Tests/ZVCoreTests/FakeCamera.swift:85-150`).
  - Fix idea: one re-armed timer per channel, fail fast on framing errors, and a fake PTP/IP server test harness with scripted stalls.
- **W20. The 20 ms AccessDenied pause is shared with USB; Android got steadier 25 fps at 5 ms** (`Sources/ZVCore/Session/CameraSession.swift:93-96`; `docs/protocol.md:268`). Fix idea: 5 ms on USB, adaptive pacing (W10) on Wi-Fi.
- **W21. The REC timer resets after a reconnect** (`Sources/ZVViewer/CameraController.swift:888-893, 996-997`). Fix idea: keep `recordingStartedAt` across an in-place resume while 0xD21D stays 1.

### Inferences
- W1–W6 together explain why a ~10–15 s radio dropout costs the user minutes. Fixing W1–W3 alone, so TCP is allowed to recover, likely avoids the stale-session problem for outages under the camera's ~2.5 min window. That depends on the camera keeping its side alive, which is unverified.
- W10–W12 are the main levers for smoothness at reduced bandwidth. W13 is the main smoothness problem even on a good link.

### Gaps
- Every fix idea is unmeasured. The camera-side behaviours that decide the best design are not documented in the repo:
  - dead-peer timeout and its reference point;
  - reaction to a new connection while stale;
  - Init Fail reasons;
  - behaviour on a new request during an abandoned transfer.
