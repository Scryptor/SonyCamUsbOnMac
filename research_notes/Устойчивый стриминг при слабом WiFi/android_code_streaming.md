# Android live-view streaming today (ZV-E10 Viewer, `android/`) and its behaviour when Wi-Fi degrades

Snapshot: repo HEAD `2826642` (main, clean working tree), Android app 0.2.2 / versionCode 6 (`android/app/build.gradle.kts:15-16`). All code citations are repo-relative `path:line` at that commit. Within one bullet, `:NN` repeats the last file named. External sources are linked; anything not verified is in Inferences or Gaps.

## 1. Architecture and data path (camera → screen), threads and dispatchers

### Takeaway
A frame goes through one synchronous PTP/IP transaction (GetObject 0xFFFFC002) on the single command socket. A fetch coroutine on `Dispatchers.IO` hands raw JPEG bytes to a `Channel.CONFLATED`, a decode coroutine on `Dispatchers.Default` runs `BitmapFactory.decodeByteArray`, and `show()` on Main sets a Compose `mutableStateOf<ImageBitmap?>`. The "pipelining" only overlaps network fetch with decode and display: there is never more than one PTP request in flight. Every AccessDenied poll is a full network round trip. Every frame allocates a fresh full-size Bitmap and copies the bytes about four times.

### Cited Findings
- **Entry and scope.** `MainActivity.onCreate` builds `CameraController(applicationContext, lifecycleScope)` and calls `start()` (`android/app/src/main/java/local/zve10viewer/MainActivity.kt:25-27`). `lifecycleScope` is `SupervisorJob() + Dispatchers.Main.immediate` and is "Canceled when the Lifecycle is destroyed" ([AndroidX Lifecycle.kt](https://raw.githubusercontent.com/androidx/androidx/androidx-main/lifecycle/lifecycle-common/src/commonMain/kotlin/androidx/lifecycle/Lifecycle.kt)). So every `scope.launch {}` in `CameraController` without an explicit dispatcher runs on the main thread.
- **Connection → session.** `tryWiFi` runs `PtpIpTransport.connect(...)` inside `withContext(Dispatchers.IO)` (`android/app/src/main/java/local/zve10viewer/CameraController.kt:871-874`), then calls `runSession(transport, TransportKind.wifi, onFailure = { wifiLost() }, onEnded = { wifiLost() })` (`:889`). `runSession` creates `CameraSession` and `WriteCoalescer`, then launches a Main coroutine that runs `session.start(this)`, sets phase `settling`, calls `startStreams`, then `session.waitUntilReady()`, then sets phase `ready` (`:707-731`). Live view starts in `settling`, before the 3 s settle delay (`:726-728`; `android/app/src/main/java/local/zve10viewer/session/CameraSession.kt:42,112-116`).
- **The four streaming jobs** in `startStreams` (`android/app/src/main/java/local/zve10viewer/CameraController.kt:931-981`):
  - (A) a properties collector on Main (`:932-934`);
  - (B) `session.ended.await(); onEnded()` on Main (`:935-938`);
  - `val frames = Channel<ByteArray>(Channel.CONFLATED)` (`:941`);
  - (C) the fetch loop, `scope.launch(Dispatchers.IO)` (`:942-973`);
  - (D) the decode loop, `scope.launch(Dispatchers.Default)` (`:974-980`).
- **Fetch loop (C).**
  - Each pass: `t0`, `session.nextLiveViewFrame()`, `t1` (`:948-950`). It logs `ZVFrames` "пауза … мс: запрос … мс (ожиданий N)" when the inter-arrival gap exceeds 100 ms (`:952-955`). Then `frames.send(jpeg)` (`:957`).
  - `TransportException` with `disconnected` → `break` (`:960-961`). Any other `TransportException` or `Exception` → `errors++` and `delay(minOf(100L * errors, 1000L))` (`:962-967`). `errors` resets after a frame (`:951`). `finally { frames.close() }` (`:970-972`).
- **`nextLiveViewFrame`** (`android/app/src/main/java/local/zve10viewer/session/CameraSession.kt:150-173`): loops `ensureActive()` then `raw(GET_OBJECT, [LIVE_VIEW_HANDLE])` (`:153-154`; handle `0xFFFF_C002` in `android/app/src/main/java/local/zve10viewer/sony/Sony.kt:62`). Outcomes:
  - OK + JPEG → return (`CameraSession.kt:156-160`);
  - OK without JPEG → `delay(20)` (`:161`);
  - ACCESS_DENIED (0x200F) / DEVICE_BUSY (0x2019) → `waits++`, `delay(5)` (`:165-168`);
  - INVALID_OBJECT_HANDLE (0x2009) → `delay(50)` (`:169`);
  - anything else → `SessionException.OperationFailed` (`:170`).
  - `lastLiveViewWaits` keeps the AccessDenied count before the last frame (`:146-148,158`). Response codes: `android/app/src/main/java/local/zve10viewer/ptp/Ptp.kt:20-26`.
- **`raw()`** wraps `transport.send(...)` in `withContext(Dispatchers.IO)`. On a `TransportException` with `disconnected` it calls `session.close()` and rethrows (`android/app/src/main/java/local/zve10viewer/session/CameraSession.kt:255-263`).
- **`PtpIpTransport.send`** (`android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:78-116`):
  - It is `@Synchronized` (`:78`). It takes `tid = nextTransactionId++` (`:81`) and sets `command.soTimeout = timeoutMs ?: defaultTimeoutMs` (`:83`; default 8000 from `:161,179`).
  - It writes the OperationRequest (`:86`), plus Start/End Data for data-out (`:87-90`), then flushes (`:91`).
  - It then reads packets until `PtpIpTransaction.consume` returns a reply (`:92-103`), calling `quickAck()` before every socket read (`:94,210`) and answering Probe Requests inline (`:95-98`).
- **Byte path, with copies.**
  - `readPacket` reads up to 64 KB (`READ_BUFFER`, `android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:203`) into a fixed buffer (`:211`) and feeds `PtpIpPacketReader`, which copies it into its own growable buffer (`android/app/src/main/java/local/zve10viewer/ptp/PtpIp.kt:121-136`).
  - The reader emits each complete packet with `copyOfRange` (`:145`).
  - `PtpIpTransaction` appends DATA/END_DATA payloads of the matching tid into a `ByteArrayOutputStream` presized from START_DATA (`:172-186`) and returns `data.toByteArray()` (`:193`).
  - `LiveViewFrame.jpeg` reads the u32 offset, checks `FF D8`, and returns `copyOfRange(offset, end)` (`android/app/src/main/java/local/zve10viewer/sony/Sony.kt:173-179`). Its fallback scans for `FF D8 FF` (`:180-186`).
- **Frame size.** The real fixture is a 22,260-byte GetObject reply (`git show --stat d5e16a1`, `lv_frame_raw.bin`) holding a 20,736-byte JPEG (`android/app/src/test/java/local/zve10viewer/CoreTest.kt:72-76`). That matches the 1524-byte Sony header offset in `docs/protocol.md:148`. The docs also say a frame is ~46 KB and arrives in one Data piece over Wi-Fi (`docs/protocol.md:303`). Frames are 1024×576 in movie mode (`docs/protocol.md:151`).
- **Decode loop (D).**
  - `BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)` with default options and no `Options` object (`android/app/src/main/java/local/zve10viewer/CameraController.kt:976`). There is no `inBitmap`, `inMutable`, `ImageDecoder` or `recycle()` anywhere in `android/app/src/main` (grep: no matches).
  - The optional histogram uses `Bitmap.createScaledBitmap(bitmap, 256, 144, false)` + `getPixels` per frame (`:977,1118-1123`).
  - Then `withContext(Dispatchers.Main) { show(bitmap.asImageBitmap(), jpeg, levels) }` (`:978`). The decoder suspends until Main has run `show()` before it takes the next frame.
- **`show()` on Main** (`:1016-1031`):
  - `measure(jpeg)` hashes every JPEG with `contentHashCode()` and logs a 3-s summary (`:998-1014`);
  - `frame = bitmap` (`mutableStateOf`, `:100-101`) and `lastJpeg = jpeg`;
  - histogram state is updated when it is on;
  - the frame-gap and frame-time deques are appended (`:1026-1029`), then `tickFrameStats()` runs.
- **Compose.**
  - `App` switches on `phase`: `searching`/`permission`/`notPcRemote` → `WaitingScreen`, otherwise `LiveScreen` (`android/app/src/main/java/local/zve10viewer/ui/Screen.kt:157-161`).
  - `Video` draws `Image(frame, contentScale = ContentScale.Fit)` and drops alpha to 0.45 with the text "Нет картинки с камеры…" when `isFrameStale`. It shows a spinner when `frame == null` (`:323-338`).
  - Only `Video` reads `controller.frame`. The pill reads `shownFps`, not `fps` (`:385`), so per-frame recomposition is limited to the video and the histogram (`:358-359`).
- **fps and smoothness counters** (`android/app/src/main/java/local/zve10viewer/CameraController.kt`):
  - `fps = frameTimes.size / 2.0` over a 2 s window (`:1057-1061`).
  - `shownFps` and `frameGapMs` are refreshed twice a second by the 500 ms loop (`:605-615,1033-1041`). `frameGapMs` is the maximum gap over 10 s, including the gap still open now (`:1036-1040`).
  - `isFrameStale = live && frame != null && now - lastFrameAt > 1500` (`:1062-1063`).
  - The frames card shows "Самая долгая пауза за 10 с" and the note "normally ~40 ms; >200 ms is visible" (`android/app/src/main/java/local/zve10viewer/ui/Screen.kt:464-474`).
- **Threads.**
  - The `ptpip-events` daemon thread blocks on the event socket (`android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:74-76,137-153`).
  - The fetch loop holds the transport monitor on an IO-pool thread while it is blocked in `read()`.
  - Decode runs on Default; state and UI on Main.
  - The handshake, the event collector, coalescer writes, the 500 ms tick and the 1 s `wifiTick` all run on Main, with blocking I/O shifted to IO:
    - the event collector is launched in the `runSession` coroutine's scope (`android/app/src/main/java/local/zve10viewer/session/CameraSession.kt:238-252`);
    - coalescer writes are `scope.launch` (`android/app/src/main/java/local/zve10viewer/settings/Presets.kt:104-122`);
    - the tick loops are at `android/app/src/main/java/local/zve10viewer/CameraController.kt:605-622`;
    - the I/O shifts are `CameraSession.kt:256` and `CameraController.kt:822,871`.
- **Why pipelining exists.** Over Wi-Fi a frame request takes 25–35 ms, and doing it in sequence with decode gave 21 fps instead of 25 (comment `android/app/src/main/java/local/zve10viewer/CameraController.kt:939-940`; `docs/protocol.md:310`; commit `6703fdf`).
- **USB shares the pipeline.** USB uses the same `runSession`/`startStreams`/`nextLiveViewFrame`; only the transport differs (`android/app/src/main/java/local/zve10viewer/CameraController.kt:683-704`).

### Inferences
- **Effective depth.** At most 1 GetObject in flight, 1 frame waiting in the CONFLATED slot, and 1 being decoded or shown. PTP/IP transactions are strictly serial under the `@Synchronized` monitor, so there is no request-level pipelining. Each AccessDenied costs one RTT + 5 ms. When RTT + frame transfer exceeds the camera's ~40 ms frame period, fps becomes RTT-bound (≈ 1 / (RTT + transfer)).
- **Allocations.**
  - Per frame: about 4 byte-array copies of ~20–46 KB (reader feed, packet `copyOfRange`, `toByteArray`, JPEG `copyOfRange`).
  - A new 1024×576 ARGB_8888 Bitmap (~2.36 MB) per decoded frame is ~59 MB/s at 25 fps.
  - With the histogram on, add a 256×144 scaled Bitmap and a 36,864-int array.
  - This GC/native-allocation churn is a plausible jank source. Nothing in the repo measures it.
- **Fetch never throttles.** The fetch loop drives the network at full rate whatever the display does. Conflation drops frames the decoder or Main could not take.
- **Monitor re-entry.** After an OK frame, the fetch thread re-enters `send()` immediately. A CONFLATED `send` does not suspend, and `withContext(Dispatchers.IO)` while already on IO does not dispatch. Other callers therefore mostly get the monitor during the 5 ms AccessDenied pauses.

### Gaps
- No repo data on per-frame decode time, GC pauses, or Compose frame time on the Redmi Pad Pro.
- The real live-view frame size distribution over Wi-Fi is unknown: 22 KB fixture vs "~46 KB" in `docs/protocol.md:303`.

## 2. Socket setup and PTP/IP stream handling

### Takeaway
The app uses two plain blocking `java.net.Socket`s with `TCP_NODELAY` and `SO_KEEPALIVE` left at the OS's 2 h default timers. The connect timeout is 5 s. The command socket has a per-`read()` soTimeout of 8 s; the event socket has an infinite one. `TCP_QUICKACK` is set via a dup'd fd before every command-socket read. There is no buffer sizing, keepalive tuning, `TCP_USER_TIMEOUT`, DSCP, linger or half-close. Stream resync after errors relies on TCP integrity plus tid filtering.

### Cited Findings
- **`open()`** is `Socket().apply { tcpNoDelay = true; keepAlive = true; onSocket(this); connect(InetSocketAddress(host, PtpIp.PORT), 5000) }`, with the comment "camera can disappear without closing" (`android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:195-201`). Port 15740 (`android/app/src/main/java/local/zve10viewer/ptp/PtpIp.kt:24`).
- **No other socket tuning.** Grep over `android/app/src/main` finds no `bindSocket`, `socketFactory`, `trafficClass`, `receiveBufferSize`/`sendBufferSize`, `SO_RCVBUF`/`SO_SNDBUF`, `soLinger`, `shutdownOutput`/`shutdownInput`, `TCP_KEEP*` or `TCP_USER_TIMEOUT`. `TCP_QUICKACK` is the only raw socket option (`android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:204-205`).
- **Linux defaults that therefore apply** ([kernel ip-sysctl](https://docs.kernel.org/networking/ip-sysctl.html)):
  - keepalive: `tcp_keepalive_time` 2 h, `tcp_keepalive_intvl` 75 s, `tcp_keepalive_probes` 9;
  - retries: `tcp_retries2` 15, a "hypothetical timeout of 924.6 seconds";
  - `tcp_orphan_retries` 8; `tcp_syn_retries` 6.
- **Mac comparison.** The Mac counterpart sets keepalive idle 5 s, interval 2 s, count 3 on `NWProtocolTCP.Options` (`Sources/ZVCamera/PTPIPTransport.swift:30-36`).
- **`setSoTimeout` semantics.** It bounds each `read()`; after `SocketTimeoutException` "the Socket is still valid". `close()` makes "any thread currently blocked in an I/O operation" throw `SocketException` ([Java SE Socket javadoc](https://docs.oracle.com/javase/8/docs/api/java/net/Socket.html)). The code comment says the timeout is "for the pause between packets: a big RAW over Wi-Fi takes longer but flows continuously" (`android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:82`).
- **Connect handshake** (`:161-193`):
  - Command socket `soTimeout = initTimeoutMs` (5000 by default; 60 000 while pairing, `android/app/src/main/java/local/zve10viewer/CameraController.kt:872`) (`android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:166`). Then Init Command Request (`:167`) and Ack/Fail parse (`:169-171`).
  - The event socket is opened (`:172`) with `soTimeout = 5000` for the Init Event Ack (`:174-177`), then `soTimeout = 0` (infinite) (`:178`).
  - A `SocketTimeoutException` gives "камера не ответила на приветствие" (`:186-188`).
- **`TCP_QUICKACK`.**
  - `commandFd = runCatching { ParcelFileDescriptor.fromSocket(command) }.getOrNull()` (`:67`). `quickAck()` sets `IPPROTO_TCP`/`12` = 1 through `Os.setsockoptInt` (`:123-126,205`) before every read on the command socket (`:94,210`).
  - Failures are swallowed (`runCatching`, `:125`); a null fd makes it a silent no-op (`:124`). It is not applied on the event socket (`:142`).
  - Rationale, from the comment at `:118-122`, commit `6703fdf` and `docs/protocol.md:309`: the camera holds its short AccessDenied reply until the previous segment is ACKed, and Android delayed ACKs by ~40 ms. Each such reply took ~60 ms instead of ~25, giving 16–19 fps instead of 25.
- **Command reads.** `readPacket` tries `reader.next()` first, then `beforeRead()`, then `read(buffer)` of up to 64 KB. `n < 0` → `IOException("камера закрыла соединение")` (`:207-215`).
- **Event reads** run on a dedicated daemon thread (`:137-153`). Probe Requests are answered from that thread (`:143-145`). EVENT packets go to an UNLIMITED channel with `trySend` (`:68,146`). Any exception, including EOF, ends the loop and calls `close()` on the whole transport, closing both sockets (`:149-152,128-135`).
- **Probes.** On the command socket a Probe Request is answered only while a `send()` is in progress (`:95-98`). The app never sends its own Probe Request; only `probeResponse` exists (`android/app/src/main/java/local/zve10viewer/ptp/PtpIp.kt:42-43,72`).
- **Packet reader.** It starts at 64 KB (`:160`), compacts in place when room allows (`:124-125`), and otherwise grows by doubling (`:127`). It shrinks back to 64 KB once a buffer over 4 MB is drained (`:150-151`). A length below 8 → `PtpParseException` (`:143`). A unit test assembles a 32 MB packet from 1460-byte chunks in under 3 s (`android/app/src/test/java/local/zve10viewer/PtpIpTest.kt:57-72`).
- **Transaction assembly.**
  - START_DATA presizes the buffer up to 256 MB (`android/app/src/main/java/local/zve10viewer/ptp/PtpIp.kt:175-181,200`).
  - DATA/END_DATA with a foreign tid are skipped (`:183-185`); an OPERATION_RESPONSE with a foreign tid is ignored (`:187-190`); unknown packet types are ignored (`:195`).
  - Tested in `android/app/src/test/java/local/zve10viewer/PtpIpTest.kt:93-105`.
- **Large objects.** The camera sends big files as 2 MB Data packets, which TCP delivers in 1–60 KB pieces (`android/app/src/main/java/local/zve10viewer/ptp/PtpIp.kt:111-115`; `docs/protocol.md:303`). Download speed to the tablet is ~5 MB/s (`docs/protocol.md:303`).
- **tid.** The transaction id is monotonic per transport instance, starting at 1 (`android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:70,81`). A new transport is created per connection (`android/app/src/main/java/local/zve10viewer/CameraController.kt:871-874`).

### Inferences
- **Resync after a timeout.** After a `SocketTimeoutException` the socket and the reader state are kept, which is correct. The late reply of the abandoned tid is drained and discarded by the next transaction's tid filter. The cost is that the next transaction first waits for the rest of the stale reply: a ~20–46 KB frame, or up to a whole multi-MB capture object if a download timed out.
- **Wedge risk.** A `PtpParseException` (length < 8) escapes `send()` without `close()` or a reader reset. Only `SocketTimeoutException` and `IOException` are caught (`android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:104-115`). The bad header stays at `start`, so every later `send()` throws immediately, before any read. Live view then loops forever in its 100 ms→1 s backoff and is never classified as disconnected. This is unlikely over TCP, but it is permanent if it happens.
- **Size-unbounded reads.** soTimeout does not cover writes, and the per-read timeout never trips while data trickles in, so a transaction has no overall deadline (for example, a 2 MB packet at 50 KB/s takes 40 s without a timeout).
- **Close behaviour.** `close()` closes the dup'd fd and then the socket (`:131-132`), with no `shutdownOutput()` and no linger. The kernel then sends FIN, and retransmits it as an orphan (bounded by `tcp_orphan_retries`). A FIN sent during an outage can therefore still reach the camera if the link returns in time. If received data is still unread at close, Linux typically sends RST instead, which is not retransmitted. That is standard Linux behaviour, not verified here.

### Gaps
- Whether, how often, and on which channel the ZV-E10 sends PTP/IP Probe Requests is unknown; `docs/protocol.md:297` says only that they must be answered.
- The camera's own TCP RTO, retry limits and keepalive are unknown.
- The `ParcelFileDescriptor.fromSocket` dup semantics were not verified against the Android reference (the page could not be retrieved). The code closes both descriptors, which is correct either way.

## 3. Android network handling and lifecycle

### Takeaway
The app is not network-aware. Sockets are unbound, so they follow the default network. There is no `NetworkCallback`, no WifiLock, no WakeLock and no foreground service; only `FLAG_KEEP_SCREEN_ON`. Nothing handles `onPause`/`onStop`, so the session and full-rate live view keep running in the background until the Activity is destroyed. Rotation does not recreate the Activity.

### Cited Findings
- **Manifest.**
  - Permissions are only `INTERNET` (`android/app/src/main/AndroidManifest.xml:6`) and `ACCESS_NETWORK_STATE` (`:8`), plus required `usb.host` (`:4`).
  - Absent: `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `CHANGE_NETWORK_STATE`, `WAKE_LOCK`, `NEARBY_WIFI_DEVICES`, `FOREGROUND_SERVICE`, `CHANGE_WIFI_MULTICAST_STATE`.
  - Activity: `exported="true"`, `launchMode="singleTask"`, `configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|density"` (`:16-20`), with a USB-attach intent filter for Sony vendor 1356 = 0x054C (`:26-31`; `android/app/src/main/res/xml/device_filter.xml:4`).
- **No network or power APIs.** Grep over `android/app/src/main` finds no `bindSocket`, `bindProcessToNetwork`, `NetworkCallback`/`registerNetworkCallback`/`requestNetwork`, `socketFactory`, `WifiManager`/`WifiLock`, `PowerManager`/`WakeLock`, `startForeground`, or any `onPause`/`onStop`/`onResume`/`onStart`/`repeatOnLifecycle`/`ProcessLifecycleOwner`.
- **The only `ConnectivityManager` use** is `connectionDetails()`. It reads `getNetworkCapabilities(activeNetwork)` and keeps `signalStrength` only if that network is Wi-Fi (`android/app/src/main/java/local/zve10viewer/CameraController.kt:1046-1055`). It is polled every 1 s while the connection card is open (`android/app/src/main/java/local/zve10viewer/ui/Screen.kt:431-437`) and labelled "хороший/средний/слабый" at ≥ −60 / ≥ −70 dBm (`:443,501-505`).
- **SSDP discovery** uses an unbound `DatagramSocket` with `soTimeout = 200`, resends M-SEARCH every 700 ms, and always runs the full 4 s window; the loop has no early exit (`android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:225-251`).
- **Android default-network semantics** ([Android: Reading network state](https://developer.android.com/develop/connectivity/network-ops/reading-network-state)):
  - "All Android apps have a default network, which is determined by the system"; it "can change at any time"; after a change, "all remaining connections on the previous default network are forcefully terminated".
  - `NET_CAPABILITY_VALIDATED` means actual internet access.
  - "Binding to a background network requires the CHANGE_NETWORK_STATE permission"; network callbacks need no permission.
- **Repo docs on networks without internet.**
  - Android marks the camera's own network "no internet access" and "may switch back to a network with internet unless you confirm staying" (`docs/protocol.md:313`).
  - In Wi-Fi Direct the camera is 192.168.122.1 and clients get 192.168.0.x/16 (`docs/protocol.md:312`). The app detects that mode by address only (`android/app/src/main/java/local/zve10viewer/ptp/PtpIp.kt:26-29`; `android/app/src/main/java/local/zve10viewer/CameraController.kt:886`).
- **Sockets destroyed on address removal.** Android's netd destroys TCP sockets bound to an IP address when that address is removed, using SOCK_DESTROY (bug 26976388, Feb 2016) ([AOSP netd commit 8554248](https://android.googlesource.com/platform/system/netd/+/8554248%5E%21/)). The matching test accepts `ECONNABORTED` or `ECONNRESET` on the peer. A 2018 report attributes TCP drops on Wi-Fi loss to this mechanism ([WireGuard list](https://lists.zx2c4.com/pipermail/wireguard/2018-July/003101.html), seen via search summary only).
- **Lifecycle.**
  - `MainActivity` overrides only `onCreate` and `onDestroy` (`android/app/src/main/java/local/zve10viewer/MainActivity.kt:16-33`).
  - `onCreate` sets `FLAG_KEEP_SCREEN_ON` (unconditionally, including on the waiting screen) and hides the system bars (`:18-24`).
  - `onDestroy` → `controller.stop()` → unregister the USB receiver, close any in-progress connection socket, `teardown()` (`:30-33`; `android/app/src/main/java/local/zve10viewer/CameraController.kt:625-629`).
  - `lifecycleScope` is cancelled only at DESTROYED, not at STOPPED ([AndroidX Lifecycle.kt](https://raw.githubusercontent.com/androidx/androidx/androidx-main/lifecycle/lifecycle-common/src/commonMain/kotlin/androidx/lifecycle/Lifecycle.kt)).
- **Persisted Wi-Fi settings.** `wifiEnabled` defaults to true (`android/app/src/main/java/local/zve10viewer/Storage.kt:62-64`); `wifiHost` and `wifiCameraName` are persisted (`:67-73`); the GUID lives in SharedPreferences `"zv"` (`android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:33-46`).

### Inferences
- **Wi-Fi Direct, no internet.**
  - On a Wi-Fi-only tablet the camera network is the only, hence default, network, so unbound sockets reach 192.168.122.1. That matches the field result of 23–25 fps (`docs/protocol.md:312`).
  - If any other network is default (a cellular model, a VPN, or Android moving to a saved Wi-Fi with internet), new sockets and SSDP follow that default and cannot reach the camera. Existing connections may be terminated by the default-network change.
  - The app has no callback to notice or recover. It sees only `IOException`s and timeouts, then retries forever against the wrong network.
- **Router mode with a WAN outage.** The Wi-Fi network may lose VALIDATED. On a device with another network, the same default-switch risk applies.
- **Brief Wi-Fi disassociation.** When the link drops and the IPv4 address is removed, netd destroys our sockets. `read()` fails at once, `IOException` → session lost (`android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:112-115`), even if Wi-Fi returns within a second. With no address and no link, no FIN or RST can reach the camera, so the camera keeps the stale session for ~2.5 min (`docs/protocol.md:308`) and the reconnect is locked out. A same-SSID L2 roam that keeps the IP only stalls the stream.
- **Screen off with the power button.**
  - The Activity is STOPPED but not destroyed, so the fetch, decode and Main `show()` keep running at full rate (wasted battery and airtime) while Compose stops drawing.
  - With no WifiLock, Wi-Fi power save may add latency.
  - Doze or HyperOS background freezing could block network access → 2×8 s timeouts → session lost. The FIN may then be firewalled, causing a stale camera session when the user returns. None of this has been measured.
- **Back.** On Android 12+ the root launcher Activity is usually moved to the background rather than destroyed, so the session also keeps running after Back. This is from general Android 12 behaviour and was not verified here.
- **Other lifecycle events.**
  - Rotation and resize are absorbed by `configChanges` and have no effect.
  - Unlisted config changes (`uiMode`/dark mode, locale, `fontScale`) recreate the Activity. That triggers `onDestroy` → `teardown()` (a clean FIN) and a new controller with a fresh `WiFiPolicy`, so it reconnects quickly.
  - Process death makes the kernel close the sockets; FIN is sent if the link is up.

### Gaps
- Exact WifiLock semantics could not be retrieved from the WifiManager reference: `WIFI_MODE_FULL_LOW_LATENCY` (API 29, reportedly active only with the app in the foreground and the screen on) and `WIFI_MODE_FULL_HIGH_PERF` deprecation status. Verify before proposing.
- Whether the user's Redmi Pad Pro has cellular, and HyperOS "Wi-Fi assistant"/auto-switch and background-freezer behaviour, are not in the repo.
- Whether the HyperOS kernel enables SOCK_DESTROY: the original 2015 kernel option was reportedly off by default, per search summaries of [LWN](https://lwn.net/Articles/668090/) and netdev, not fetched. Whether a brief Wi-Fi loss on this tablet actually removes the IP is device-specific.
- In Wi-Fi Direct mode, whether the camera's AP tears down the PTP/IP session when our station disassociates is unknown.

## 4. Timeouts, deadlines, retries, watchdogs, and what counts as "session lost"

### Takeaway
The only Wi-Fi liveness mechanism is the 8 s per-read soTimeout on the command socket. Two consecutive timeouts (OpenSession exempt) mean "disconnected", so a silent link is detected in about 16 s. The event socket never times out, and nothing probes it. The 1.5 s "stale frame" flag is cosmetic. "Session lost" means any `IOException`/EOF on either socket, two command timeouts in a row, or a handshake failure.

### Cited Findings

| Mechanism | Value | Code |
|---|---|---|
| TCP connect, command and event sockets | 5000 ms | `android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:200` |
| Init Command Ack wait | 5000 ms; 60 000 ms while pairing | `android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:161,166`; `android/app/src/main/java/local/zve10viewer/CameraController.kt:872` |
| Init Event Ack wait | 5000 ms | `android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:174` |
| Event socket after init | 0 (infinite) | `android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:178` |
| Per-read timeout, command socket, every op including live view | 8000 ms default; there is no per-transaction deadline | `android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:83,161,179` |
| Consecutive timeouts → disconnected | `timeoutsInRow >= 2`; reset on any reply | `android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:71,100,104-111` |
| OpenSession | 1500 ms × 4 attempts; timeouts not counted | `android/app/src/main/java/local/zve10viewer/session/CameraSession.kt:77-87`; `android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:105-106` |
| GetDeviceInfo, SDIO Connect 0x9201 phases 1/2/3, PriorityMode, GetAllExtPropInfo | default 8 s per read, no retry | `android/app/src/main/java/local/zve10viewer/session/CameraSession.kt:91,94-95,105-107` |
| GetExtDeviceInfo 0x9202 | up to 20 × (8 s per read) with 100 ms gaps until data | `android/app/src/main/java/local/zve10viewer/session/CameraSession.kt:96-104` |
| Settle before `ready` | 3000 ms | `android/app/src/main/java/local/zve10viewer/session/CameraSession.kt:42,112-116` |
| Setting write confirmation | 2500 ms deadline, poll every 150 ms | `android/app/src/main/java/local/zve10viewer/session/CameraSession.kt:43-44,124-131,220-230` |
| Capture: object appears | 10 000 ms, +2500 ms after each file | `android/app/src/main/java/local/zve10viewer/session/CameraSession.kt:46,200-216` |
| AF wait before full press | 1000 ms | `android/app/src/main/java/local/zve10viewer/session/CameraSession.kt:187` |
| Live view: AccessDenied/DeviceBusy / OK without JPEG / InvalidObjectHandle | 5 / 20 / 50 ms | `android/app/src/main/java/local/zve10viewer/session/CameraSession.kt:161,165-169` |
| Live-view error backoff | `min(100 × n, 1000)` ms | `android/app/src/main/java/local/zve10viewer/CameraController.kt:960-968` |
| Frame-gap log threshold | 100 ms | `android/app/src/main/java/local/zve10viewer/CameraController.kt:952` |
| "No picture" flag | 1500 ms, display only | `android/app/src/main/java/local/zve10viewer/CameraController.kt:1063`; `android/app/src/main/java/local/zve10viewer/ui/Screen.kt:328-333` |
| fps window / max-gap window | 2 s / 10 s | `android/app/src/main/java/local/zve10viewer/CameraController.kt:1060,1036` |
| `wifiTick` period | 1000 ms | `android/app/src/main/java/local/zve10viewer/CameraController.kt:617-622` |
| SSDP | 4.0 s window, resend 700 ms, receive 200 ms | `android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:225-238` |
| Backoff after a lost session | 2, 4, 8, 10, 10… s; reset on `ready` | `android/app/src/main/java/local/zve10viewer/wifi/WiFiPolicy.kt:41-53`; `android/app/src/main/java/local/zve10viewer/CameraController.kt:731`; test `android/app/src/test/java/local/zve10viewer/WiFiPolicyTest.kt:25-36` |
| Stale-session hint | after 3 failures to a camera found by SSDP | `android/app/src/main/java/local/zve10viewer/wifi/WiFiPolicy.kt:23-29`; `android/app/src/main/java/local/zve10viewer/CameraController.kt:838-842,856-860` |
| Recording-mode write wait / C.Temp wait | 60 × 100 ms / 40 × 100 ms | `android/app/src/main/java/local/zve10viewer/CameraController.kt:333-338,357-367` |
| USB per-transfer timeout | 8000 ms; failures never mark "disconnected" | `android/app/src/main/java/local/zve10viewer/usb/UsbPtpTransport.kt:29,97,207-209` |
| USB retry | 2, 4, 8, 10 s | `android/app/src/main/java/local/zve10viewer/CameraController.kt:922-929` |

- **Paths that end a Wi-Fi session.**
  1. `send()` on a closed transport → `TransportException(disconnected = true)` (`android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:80`).
  2. Any `IOException` in `send()` → `close()` + "связь по Wi-Fi оборвалась", disconnected (`:112-115`). This covers EOF (`:212`), connection reset, broken pipe, socket closed by another thread, and the `ECONNABORTED` from a netd socket destroy.
  3. A second consecutive `SocketTimeoutException` on a non-OpenSession op → `close()` + "камера не отвечает", disconnected (`:104-110`).
  4. Event-thread exception or EOF → `close()` (`:149-152`) → `eventChannel.close()` (`:134`) → `transport.events.collect` completes → `CameraSession.close()` (`android/app/src/main/java/local/zve10viewer/session/CameraSession.kt:249-250`) → `ended` completes (`:235`) → `onEnded` → `wifiLost()` (`android/app/src/main/java/local/zve10viewer/CameraController.kt:935-938,889,915-920`).
  5. `raw()` sees `disconnected` → `session.close()` → `ended` (`android/app/src/main/java/local/zve10viewer/session/CameraSession.kt:259-261`).
  6. Any exception in `start()` or `waitUntilReady()` → `onFailure` → `wifiLost()`; on Wi-Fi this includes `NotPcRemote` (`android/app/src/main/java/local/zve10viewer/CameraController.kt:732-745`).
- **Not lost:** a single timeout ("камера не ответила вовремя", not disconnected) (`android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:111`), `OperationFailed`, and `PtpParseException`. All go into the live-view backoff loop (`android/app/src/main/java/local/zve10viewer/CameraController.kt:962-967`).

### Inferences
- **Silent outage.** Time to "session lost" is ≈ 8 s + 0.1 s backoff + 8 s ≈ 16.1 s, whoever owns the two timed-out transactions (live view, a user command or a refresh all count). From 1.5 s on, the user sees a dimmed frozen frame with "Нет картинки с камеры…".
- **No recovery from the stale flag.** No watchdog reacts to `isFrameStale` or `frameGapMs`. Nothing re-requests a stalled frame early. Nothing detects a half-dead event channel.

### Gaps
- The real distribution of stall lengths on weak Wi-Fi, and how often each path above fires in the field, is not measured or logged in the repo.

## 5. Reconnection after a loss

### Takeaway
A loss triggers a full `teardown()`: the last frame, the properties and the REC timer are wiped and the UI drops to the waiting screen. One backoff (2/4/8/10 s) applies before the next attempt. After that, `wifiTick` retries back-to-back, each cycle being the last address then SSDP, about 5–15 s per failing cycle. Recovery is a complete re-handshake (2× Init, OpenSession, Sony 0x9201/0x9202, PriorityMode) plus the 3 s settle. The "camera holds the stale session ~2.5 min" problem has no mitigation beyond a hint.

### Cited Findings
- **`wifiLost()`** = `teardown()`, then `seconds = wifiPolicy.sessionLost(now)`, then message "Связь по Wi-Fi пропала. Повтор через $seconds с…" (`android/app/src/main/java/local/zve10viewer/CameraController.kt:915-920`).
- **`teardown()`** (`:1074-1108`):
  - Cancels all session jobs, closes the session and the transport.
  - Resets `transportKind`, `wifiDirect`, the recording driver, `targets`/`pending`, `deviceInfo`, `properties`, `frame = null`, `lastJpeg = null`, the histogram, fps and gap statistics, `recordingStartedAt = null`, `isApplyingPreset`/`isCapturing`, and the review; sets `phase = searching`.
  - It does not touch `shots`, `presets` or `statusMessage`; the last clears itself after 5 s (`:588-595`).
- **What the user sees.**
  - `phase = searching` → `WaitingScreen` ("Подключите камеру", USB and Wi-Fi instructions) (`android/app/src/main/java/local/zve10viewer/ui/Screen.kt:157-159,172-199`).
  - A "Ищу <name> в сети…" spinner when Wi-Fi is on and paired (`:209-212`), the `wifiStatus` hint (`:214`) and the message (`:220-223`).
  - During the next handshake `phase = connecting` → `LiveScreen` with a spinner, since `frame == null` (`:160,334-336`). In `settling`, frames show but controls are disabled (`isWritable` requires `ready`, `android/app/src/main/java/local/zve10viewer/CameraController.kt:193-194`) and the pill dot is orange (`android/app/src/main/java/local/zve10viewer/ui/Screen.kt:380-381`).
- **Backoff gate.**
  - `mayAttempt = pairing || (enabled && now >= nextAttempt)` (`android/app/src/main/java/local/zve10viewer/wifi/WiFiPolicy.kt:15`). `nextAttempt` is set only by `sessionLost` (`:48-53`) and cleared by `sessionReady` (`:42-45`).
  - Failed attempts do not move `nextAttempt`.
- **`wifiTick`** (`android/app/src/main/java/local/zve10viewer/CameraController.kt:806-854`):
  - guarded by `wifiWanted` (`:784-792`) and `wifiBusy` (`:807-808`);
  - tries `prefs.wifiHost` first (`:810-819`), then SSDP on IO (`:822`);
  - if nothing is found → `nothingFound()` and stop (`:824-828`); `maySendInit` gating for unpaired cameras (`:830-837`); stale-hint accounting when the last address is among those found (`:838-842`);
  - then tries the other found hosts (`:843-850`).
- **`tryWiFi`** (`:863-913`):
  - On success it saves host and name (`:877-878`) and starts `runSession` (`:889`).
  - On `PtpIpRejected`, when not pairing, it sets `prefs.wifiCameraName = null` and shows "not paired" (`:891-900`). That happens for any Init Fail reason: the transport throws `PtpIpRejected(reply.reason)` without looking at the reason (`android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:170`; `android/app/src/main/java/local/zve10viewer/ptp/PtpIp.kt:16,88`).
  - Any other exception → `attemptFailed(discovered)` + stale hint, with no logging (`android/app/src/main/java/local/zve10viewer/CameraController.kt:904-909`).
- **Re-handshake.** `CameraSession.start` runs, in order (`android/app/src/main/java/local/zve10viewer/session/CameraSession.kt:72-109`; order tested in `android/app/src/test/java/local/zve10viewer/CoreTest.kt:134-156`):
  1. OpenSession (1.5 s × 4);
  2. GetDeviceInfo;
  3. Connect(1), Connect(2);
  4. GetExtDeviceInfo (≤ 20 × 100 ms);
  5. Connect(3);
  6. PriorityMode = 1;
  7. GetAllExtPropInfo;
  8. start listening for events.

  Live view then starts (`android/app/src/main/java/local/zve10viewer/CameraController.kt:726-727`); after the 3 s settle and a refresh the phase becomes `ready` (`:728-731`).
- **Documented camera behaviour.**
  - The first command after (re)pairing may go unanswered, and the camera answers in 8–30 ms a second later; hence the 1.5 s OpenSession retry (`docs/protocol.md:307`). On USB, the old full 8 s timeout made users wait 11 s for the picture (`docs/protocol.md:266`; commit `e8846e6` "quick reconnect after app restart").
  - The camera keeps one PTP/IP connection at a time (`docs/protocol.md:301`). If our close does not reach it, it keeps the dropped connection ~2.5 min and turns new connections away; a normal close frees it immediately (`docs/protocol.md:308`).
- **Stale-session handling today** is a hint only, after 3 failures to a camera visible via SSDP: "Камера найдена ($host), но пока не отвечает — видимо, держит прошлое подключение. Обычно это проходит за 1–2 минуты; если нет — выключите и снова включите на камере PC Remote" (`android/app/src/main/java/local/zve10viewer/CameraController.kt:856-860`; `android/app/src/main/java/local/zve10viewer/wifi/WiFiPolicy.kt:28-29`).
- **REC timer.** `recordingStartedAt` is cleared in teardown (`android/app/src/main/java/local/zve10viewer/CameraController.kt:1103`) and set to "now" again when 0xD21D == 1 is next seen (`:983-987`).

### Inferences
- **Best case: an RST, or our FIN delivered.**
  - Detection is immediate, then a 2 s backoff, then up to 1 s until the next `wifiTick`.
  - Connect + Init take a few tens of ms on a LAN. OpenSession takes 8–30 ms, or +1.5 s per unanswered try. The rest of the handshake is a few hundred ms.
  - The first frame lands about 2.5–5 s after the loss; controls follow 3 s later.
- **Silent outage:** add the ~16 s detection. The user watches a dimmed frozen frame for ~14.5 s, then a waiting screen with no image.
- **Camera holding a stale session.**
  - Each failing cycle takes up to 5 s connect (or 5 s Init), then 4 s of SSDP (which never exits early), then ≤ 1 s, so about 10–15 s.
  - This repeats until the camera's ~2.5 min hold expires; nothing shortens it. Worst-case blackout is about 16 s + 150 s.
  - The FIN that `close()` queued at ~16 s may still be retransmitted after the link returns, if the socket was not destroyed. The camera might then free the session earlier, within tens of seconds of link return, depending on the kernel's RTO backoff. This is unverified.
- **Ride-through.** The camera evidently keeps session state for ~2.5 min after losing contact. Outages shorter than that might be survivable on the same TCP connection if the app did not give up and close at 16 s. Today's policy turns any outage longer than 16 s into a forced re-handshake, and often into a 2.5 min lockout.
- **Reconnect message.** "Повтор через 2 с…" stays as written; it does not count down or reflect later attempts (`android/app/src/main/java/local/zve10viewer/CameraController.kt:919`).
- **Init Fail misread.** If the camera ever answers with an Init Fail reason other than 1 (for example "busy" while it holds the old session), the app would wrongly forget the pairing and stop auto-reconnecting until the user pairs again. The hint text suggests the camera stays silent instead.

### Gaps
- What exactly the camera does with a new connection while holding a stale one (TCP refused, accepted but silent, or Init Fail with which reason) is not recorded; docs say only "отбрасывает" (`docs/protocol.md:308`).
- There are no measured Android reconnect durations or field logs in the repo.

## 6. Failure-mode walkthrough (a)–(h)

### Takeaway
Stalls under 8 s are ridden out with a frozen picture (dimmed from 1.5 s). Stalls of 8–16 s usually survive thanks to the tid filter. Anything longer than about 16 s, any RST, any event-socket error and any Wi-Fi disassociation ends the session, blanks the UI, and risks the ~2.5 min stale-session lockout. User commands queue behind the stalled frame on the same monitor.

### Cited Findings (code paths)
- **(a) 300 ms stall mid-frame.**
  - `read()` blocks for less than the 8 s soTimeout, so nothing throws (`android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:83,211`). The frame arrives late and the next request goes out at once.
  - The user sees a ~300 ms freeze, with `frameGapMs` ≥ 300 (`android/app/src/main/java/local/zve10viewer/CameraController.kt:1036-1040`) and a `ZVFrames` "пауза" log line (`:952-955`). There is no stale overlay below 1500 ms (`:1063`).
- **(b) 2 s stall.** Same path. Between 1.5 and 2.0 s the overlay "Нет картинки с камеры…" appears over the 45%-alpha frame; it is evaluated every 500 ms and on each frame (`:605-615,1057-1064`; `android/app/src/main/java/local/zve10viewer/ui/Screen.kt:328-333`). No exception is thrown.
- **(c) 10 s stall.**
  - At 8 s: `SocketTimeoutException` → `timeoutsInRow = 1` → non-disconnected `TransportException` (`android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:104-111`) → live-view backoff 100 ms (`android/app/src/main/java/local/zve10viewer/CameraController.kt:960-963`) → a new GetObject with tid + 1 on the same socket and reader.
  - When data resumes, the abandoned tid's Data and Response are skipped (`android/app/src/main/java/local/zve10viewer/ptp/PtpIp.kt:177,184,190`). The new reply returns and `timeoutsInRow` resets to 0 (`android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:100`). The session survives.
- **(d) 40 s stall.**
  - A second timeout at about 16.1 s → `close()` → disconnected (`:107-110`) → `session.close()` (`android/app/src/main/java/local/zve10viewer/session/CameraSession.kt:260`) → `wifiLost()` → waiting screen with "Повтор через 2 с…" (`android/app/src/main/java/local/zve10viewer/CameraController.kt:915-920`).
  - Attempts while the link is still dead fail: connect has a 5 s timeout (`android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:200`) and SSDP finds nothing (`android/app/src/main/java/local/zve10viewer/CameraController.kt:824-828`).
  - After the link returns, the outcome depends on whether the camera ever received our FIN; see section 5.
- **(e) TCP RST / connection reset.** The `read`/`write` throws → `IOException` branch → `close()` + disconnected (`android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:112-115`). The event thread dies too (`:149-152`). The UI switches to the waiting screen at once and the reconnect follows the 2 s backoff.
- **(f) Wi-Fi drop / roaming / screen off.**
  - There is no network or lifecycle handling (section 3). A disassociation that removes the address destroys the sockets (netd SOCK_DESTROY), which takes path (e). An L2 roam that keeps the IP takes path (a)–(c).
  - Screen off: live view keeps running (`android/app/src/main/java/local/zve10viewer/MainActivity.kt:16-33`; no `onStop` handling). It fails only if the OS blocks the network, through paths (c)/(d).
- **(g) Event channel dies while the command channel is fine.**
  - `readEvents` catch → `close()` closes both sockets (`android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:149-152,128-135`). The in-flight GetObject read gets `SocketException` → disconnected, and the event flow ends → `CameraSession.close()` (`android/app/src/main/java/local/zve10viewer/session/CameraSession.kt:249-250`). The whole session is lost although live view was healthy.
  - If the event channel dies silently (no RST or FIN), it is never detected: soTimeout 0 (`android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:178`), keepalive at 2 h defaults, and the app never probes. Camera-side changes then stop refreshing the properties, while live view continues.
  - The reverse case: a command-channel `IOException` or double timeout → `close()` also kills the event socket (`:112-115,128-135`).
- **(h) The user changes a setting or presses the shutter while the link is degraded.**
  - **Setting change.** `request()` → `WriteCoalescer.submit` (Main) → `session.set` (`android/app/src/main/java/local/zve10viewer/CameraController.kt:371-377,716-721`; `android/app/src/main/java/local/zve10viewer/settings/Presets.kt:108-122`) → `raw()` → an IO thread blocks on the `@Synchronized` monitor until the in-flight GetObject ends (`android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:78`).
    - Then come the SET (8 s per read) and `waitFor`, which re-reads all properties every 150 ms for up to 2.5 s (`android/app/src/main/java/local/zve10viewer/session/CameraSession.kt:124-131,220-230`). Each of those re-reads competes with live view again.
    - On failure, the coalescer only logs `ZVController` "запись 0x… = …: <exception>" and clears `pending` (`android/app/src/main/java/local/zve10viewer/CameraController.kt:717-720`). The user sees the value silently stay unchanged. Its timeouts count toward the shared 2-in-a-row rule.
  - **Shutter.** `capturePhoto` sets `isCapturing = true`, which disables every setting control (`:194,450`).
    - The session does half press → ≤ 1 s AF wait → full press → full release → half release (`android/app/src/main/java/local/zve10viewer/session/CameraSession.kt:179-191`). On error it tries to release both buttons via `runCatching` (`:192-197`), which cannot succeed once the transport is closed.
    - With download on, the GetObject of 0xFFFFC001 in 2 MB packets holds the monitor for the whole file (`:200-216`), so live view freezes for the duration.
  - **Record.** The REC toggle is two CONTROL_DEVICE calls + `waitFor(0xD21D)` (`:135-143`). The controller ignores failures (`runCatching`, `android/app/src/main/java/local/zve10viewer/CameraController.kt:400`).

### Inferences
- **(a)/(b) Timing.** Recovery is immediate on data arrival. TCP's exponential RTO backoff ([RFC 6298](https://www.rfc-editor.org/rfc/rfc6298)) on whichever side lost segments can stretch a 2 s outage into roughly 3–4 s of visible freeze.
- **(c) Timing.** The user sees about 10 s or more of dimmed frozen frame, then a seamless resume. It is borderline: RTO backoff can push the first byte after the outage past the second soTimeout at ~16.1 s. Outages of about 12–16 s can therefore still end in a disconnect.
- **(d) Timing.** Recovery takes about 40 s + 1–15 s if the camera freed the session (our retransmitted FIN arrived), or up to about 16 s + 150 s if it did not.
- **(e) Timing.** About 3–5 s to the first frame and +3 s to controls, provided the camera itself sent the RST and freed the session.
- **(f) Wi-Fi drop.** This is likely the most damaging real-world case on weak signal: disassociation, then socket destroy, then a lost FIN, then a ~2.5 min lockout even after Wi-Fi is back.
- **(h) Command wait.**
  - Under good conditions a user command waits about one transaction (~25–35 ms).
  - During a stall it waits up to the 8 s per-read timeout. While data trickles it is unbounded.
  - During a capture download it waits for the whole file: ~5 MB/s nominal, and RAW+JPEG takes ~20 s on Wi-Fi Direct (`docs/protocol.md:303,312`).

### Gaps
- What the camera does if the link drops between S2 press and release (does it release the buttons or keep shooting?) is not documented.
- No repo test or log quantifies any of (a)–(h) on the real tablet.

## 7. Control vs live-view interleaving on the single command channel

### Takeaway
All PTP traffic (live-view polls, event-triggered property dumps, setting writes and their confirmation polls, capture, presets) is serialised only by the JVM monitor of `PtpIpTransport.send`. There is no queue, no priority for user actions, no fairness guarantee, and no coalescing of event-driven refreshes.

### Cited Findings
- **The only serialiser** is `@Synchronized override fun send` (`android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:78-79`), described as "transactions go strictly one at a time" (`:53-56`). `CameraSession` has no mutex or actor of its own (`android/app/src/main/java/local/zve10viewer/session/CameraSession.kt:57-275`). The Mac makes `CameraSession` and `PTPIPTransport` actors (`docs/architecture.md:180,183`).
- **Concurrent callers of `transport.send`:**
  - the live-view loop (`android/app/src/main/java/local/zve10viewer/CameraController.kt:942-973`);
  - one `GetAllExtPropInfo` per 0xC203 event (`android/app/src/main/java/local/zve10viewer/session/CameraSession.kt:240-244`);
  - coalescer writes + confirmation polls (`android/app/src/main/java/local/zve10viewer/settings/Presets.kt:104-122`; `android/app/src/main/java/local/zve10viewer/session/CameraSession.kt:124-131,220-230`);
  - the recording-mode driver (`android/app/src/main/java/local/zve10viewer/CameraController.kt:283-330`);
  - the colour-temperature job (`:344-369`);
  - capture (`:447-495`) and destination writes (`:437-443,462-464`);
  - preset application (`android/app/src/main/java/local/zve10viewer/settings/Presets.kt:70-98`).
- **Event queue.** Events land in `Channel.UNLIMITED` (`android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:68`) and are collected one by one. Every 0xC203 triggers a full refresh; there is no conflation (`android/app/src/main/java/local/zve10viewer/session/CameraSession.kt:240-244`). The refresh time is logged at debug level under `ZVFrames` (`:242-244`).
- **Size of one refresh.** The full property dump is ~2.8 KB (`props_settled.bin`, 2825 bytes, `git show --stat d5e16a1`). Over Wi-Fi the camera returns 95 properties versus 93 over USB (`docs/protocol.md:299`).

### Inferences
- **Blocking time for a user command:**
  - normally ≤ one frame transaction (~25–35 ms) plus any events queued ahead;
  - up to 8 s per stalled read on a degraded link;
  - the entire download during a capture.
- **Monitor fairness.** ART monitors are not FIFO, and the fetch thread re-enters right after each OK frame, so commands mostly get in during the 5 ms AccessDenied pauses. User actions get no priority over polls.
- **Event bursts.** A burst of N property events produces N sequential refreshes, each one RTT + 2.8 KB, stealing live-view slots. On a weak link this "refresh debt" lowers fps and adds timeout exposure.

### Gaps
- How often 0xC203 fires during normal use (dial turns, auto exposure, recording) is not quantified in the repo.

## 8. Observability: existing metrics and logs, and how to extend them

### Takeaway
On-screen: fps (2 s window), the maximum inter-frame gap over 10 s, a stale flag (> 1.5 s), and the RSSI of the active network. Logcat: frame-gap and 3-s smoothness summaries (`ZVFrames`), event refresh timing, write results (`ZVController`), USB reset and stale-container logs (`ZVTransport`). The Wi-Fi transport and the reconnect path log nothing, and there are no frame-size, throughput, RTT or timeout counters.

### Cited Findings
- **On-screen.**
  - The `shownFps` pill (`android/app/src/main/java/local/zve10viewer/ui/Screen.kt:385`).
  - The frames card: max gap over 10 s, with the note "~40 ms normal, > 200 ms visible" (`:464-474`).
  - The connection card: camera IP, tablet IP (`command.localAddress`) and signal in dBm, refreshed each second (`:429-448`; `android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:64`; `android/app/src/main/java/local/zve10viewer/CameraController.kt:1043-1055`).
- **Logcat** (`android/app/src/main/java/local/zve10viewer/CameraController.kt` unless noted):
  - `ZVFrames` Info: gap > 100 ms with the request duration and AccessDenied count (`:952-955`).
  - `ZVFrames` Info every 3 s: frames, repeated identical JPEGs (by `contentHashCode`), median, 90th percentile and maximum interval (`:998-1014`).
  - `ZVFrames` Debug: 0xC203 refresh time and other event codes (`android/app/src/main/java/local/zve10viewer/session/CameraSession.kt:242-246`).
  - `ZVController`: recording-mode steps (`:293-296`), each write result (`:718`), "подключение не удалось" with the exception (`:743`), and a failed capture (`:488`).
  - `ZVTransport` (USB only): reset status and duration (`android/app/src/main/java/local/zve10viewer/usb/UsbPtpTransport.kt:82`), skipped stale containers (`:108`), unexpected containers (`:121`).
- **Silent spots.** `PtpIpTransport.kt` has no log calls at all (grep). `tryWiFi` swallows non-rejection failures without logging (`android/app/src/main/java/local/zve10viewer/CameraController.kt:904-909`). `wifiLost()` records no cause (`:915-920`).
- **No automation hook.** The Android app has no HTTP or automation server (grep: no `ServerSocket`). `docs/automation-api.md` describes only the Mac app's API.
- **Hooks already present:** per-frame `t0`/`t1` (`:948-950`), `lastLiveViewWaits` (`android/app/src/main/java/local/zve10viewer/session/CameraSession.kt:146-148`), and `frameGaps`/`frameTimes` (`android/app/src/main/java/local/zve10viewer/CameraController.kt:183-186`).

### Inferences
- **Extension ideas**, for the later report:
  - per-transaction latency and bytes in `PtpIpTransport.send`, so frame size and throughput can be computed;
  - counters for timeouts (`timeoutsInRow` history), tid-skipped stale packets, Probe Requests seen on each channel, and AccessDenied per frame;
  - the disconnect cause and reconnect phase durations (connect / Init / OpenSession / handshake / first frame);
  - per-second RSSI (already read by `connectionDetails`) logged alongside fps and gaps;
  - optionally a debug overlay or a CSV log pulled over adb.
- **TCP-level stats.** These would need `TCP_INFO`. `android.system.Os` does not obviously expose it without JNI; that is unverified, see Gaps.

### Gaps
- Whether `TCP_INFO` (retransmits, RTT) can be read from Kotlin without JNI on API 29+ was not verified.

## 9. USB path (comparison) and USB optimisation opportunities

### Takeaway
USB uses the same session and live-view loop over a synchronous `bulkTransfer` transport: 16 KB IN chunks, 8 s timeouts, a PTP reset on open, tid filtering, and an async `UsbRequest` only for the interrupt (event) endpoint. USB errors never count as "disconnected"; only `USB_DEVICE_DETACHED` ends a session. Throughput is limited by one outstanding 16 KB transfer at a time plus extra copies, and the reset runs on the main thread.

### Cited Findings
- **Endpoints and reset.** Bulk IN, bulk OUT and interrupt IN are found by type and direction (`android/app/src/main/java/local/zve10viewer/usb/UsbPtpTransport.kt:42-56`). Then `reset()` and `startEventReader()` run from the constructor (`:57-58`). `reset()` (`:66-92`) does:
  1. Device Reset 0x66, 1000 ms;
  2. CLEAR_FEATURE(ENDPOINT_HALT) on both bulk endpoints, 1000 ms each;
  3. Get Device Status 0x67 up to 20 × (500 ms timeout + 100 ms sleep) until 0x2001;
  4. drain bulk IN with 150 ms reads until two consecutive empties, at most 64 reads.

  Rationale: an interrupted previous exchange leaves the camera refusing commands (`docs/protocol.md:265`; commit `42a834f`).
- **`send()`** (`android/app/src/main/java/local/zve10viewer/usb/UsbPtpTransport.kt:94-124`):
  - `@Synchronized`; tid 0 outside a session and 1, 2, … after OpenSession (`:98-99,115-118`);
  - writes through the offset variant of `bulkTransfer` (`:135-142`);
  - drops containers whose tid or data-phase code does not match (`:105-110`).
- **`readContainer()`** (`:150-178`):
  - synchronous `bulkTransfer(bulkIn, chunk, 16 KB, timeout)` (`:154`);
  - skips up to 4 zero-length packets (`:156-160`);
  - allocates the full container from the header length (`:163-168`) and loops more 16 KB reads, `System.arraycopy`-ing each chunk (`:171-176`);
  - `Container.payload` copies once more (`:145`).
  - `CHUNK = 16 * 1024`, with the comment that reads larger than 16 KB do not get through the kernel on some devices (`:213-214`). The docs add that a 256 KB read leaves the reply stuck in the camera (`docs/protocol.md:264`).
- **Events** use a single `UsbRequest` on the interrupt endpoint, queued, then `connection.requestWait()` with no timeout on the `ptp-events` daemon thread (`android/app/src/main/java/local/zve10viewer/usb/UsbPtpTransport.kt:184-205`). A synchronous read with a 500 ms timeout used to delay frame replies by 150–225 ms every ~1.5 s (`docs/protocol.md:267`; comment `:180-183`). When that thread breaks out (`:194-195`), it does not close `eventChannel`.
- **Failure semantics.** `fail()` throws `TransportException(message, disconnected = closed)`, so it is never "disconnected" while the transport is open (`:207-209`). The USB session therefore ends only through `ACTION_USB_DEVICE_DETACHED` → `teardown()` (`android/app/src/main/java/local/zve10viewer/CameraController.kt:662-671`) or an `onEnded` with `device != null` (`:703`).
- **Reset runs on Main.** `findCamera()` and `connect()` run from the 500 ms Main loop or the broadcast receiver (`:605-609,648-655`). `UsbPtpTransport(connection, usbInterface)`, and with it `reset()` and its `Thread.sleep`s, is built inside `connect()` on Main (`:694-695`).
- **Measured USB performance:** 25 fps with 90% of intervals ≤ 47–49 ms after the 5 ms AccessDenied wait (`docs/protocol.md:268`).

### Inferences
- **USB optimisation ideas:**
  - Keep several `UsbRequest`s queued on bulk IN so the host controller always has a buffer armed. Caveat: `requestWait()` returns any completed request on the connection, and the event thread already calls it (`android/app/src/main/java/local/zve10viewer/usb/UsbPtpTransport.kt:195`), so request reaping must be centralised in one thread.
  - Read straight into the destination array with the offset `bulkTransfer` variant (already used for OUT, `:138`) instead of `chunk` + `arraycopy` (`:172-174`).
  - Drop the `payload` copy (`:145`).
  - Reuse frame buffers.
  - Move `reset()` off Main.
  - Keep 16 KB per transfer unless larger sizes are proven on this tablet.
- **Robustness:** the USB path has no stall-to-disconnect logic and a silent event-thread exit. A wedged camera on the cable would loop in the live-view backoff forever.

### Gaps
- The Android API documentation for `bulkTransfer` and `UsbRequest` size limits per API level was not checked; the repo's 16 KB rule is empirical.

## 10. Manifest, build configuration, and unit-test coverage

### Takeaway
The build is a minimal-permission app (compile and target SDK 34, minSdk 29) with no networking libraries. The 64 JVM tests cover PTP/IP framing, tid filtering, the reconnect policy, the handshake order and OpenSession retries. They do not cover the socket transport, timeouts, the live-view loop, reconnection or any degraded-link behaviour.

### Cited Findings
- **Build.** `compileSdk = 34`, `minSdk = 29`, `targetSdk = 34` (`android/app/build.gradle.kts:9-14`). Dependencies are only Compose BOM 2024.06.00, activity-compose 1.9.0, core-ktx 1.13.1, lifecycle-runtime-ktx 2.8.3 and kotlinx-coroutines-android 1.8.1 (`:39-46`). The release build is signed with the debug key, without minify (`:19-25`).
- **Manifest**: see section 3 (`android/app/src/main/AndroidManifest.xml:4-31`).
- **Test count.** 64 `@Test`s: Core 10, PtpIp 12, Histogram 6, RecordingModes 13, Session 11, SlowQuick 4, WhiteBalance 4, WiFiPolicy 4 (grep count). This matches "64 теста на JVM" in `docs/architecture.md:298`.
- **`PtpIpTest`** (`android/app/src/test/java/local/zve10viewer/PtpIpTest.kt`):
  - Init request layout (`:27-31`); operation request and data-phase flag (`:33-37`); Start/End Data (`:39-42`).
  - Reader assembly from chunks (`:44-55`); a 32 MB packet from 1460-byte chunks under 3 s (`:57-72`); bad length rejected (`:74-83`).
  - Init Ack/Fail (`:85-91`); transaction collects its data and skips another tid (`:93-105`); response without data (`:107-111`); event parse (`:113-116`).
  - SSDP parse (`:118-126`); access-point address (`:128-133`).
- **`WiFiPolicyTest`** (`android/app/src/test/java/local/zve10viewer/WiFiPolicyTest.kt`): attempt gating (`:10-15`), Init gating (`:17-23`), backoff 2/4/8/10/10 and reset (`:25-36`), and counting failures only for discovered cameras plus the stale hint (`:38-51`).
- **`CoreTest`** (`android/app/src/test/java/local/zve10viewer/CoreTest.kt`): handshake order (`:134-156`), a silent OpenSession retried (`:158-166`, with the fake throwing a non-disconnected `TransportException`, `:201-206`), not-PC-Remote (`:168-177`), and JPEG extraction from the real live-view fixture (`:72-78`).
- **`SessionTest`** (`android/app/src/test/java/local/zve10viewer/SessionTest.kt`): `FakeCamera` with instant replies (`:153-214`). It tests set/confirm, ignored and disallowed writes, preset dependencies, capture download, shutter release after DEVICE_BUSY, the REC toggle and the coalescer (`:44-137`). Its only transport-level failure is DEVICE_BUSY on a button press.
- **Not tested:** no test exercises `PtpIpTransport` (sockets, `timeoutsInRow`, Probe replies, `close`, the event thread), `WiFiDiscovery`, `startStreams`, `nextLiveViewFrame` or the `wifiTick`/`tryWiFi` reconnect flow (no references in `android/app/src/test`).

### Inferences
- Every degraded-link behaviour in sections 4–6 is untested. A fake PTP/IP server, or a socket pair with injected delays, drops and resets, would be needed to regression-test changes.

### Gaps
- None beyond the above.

## 11. Docs vs code, and the git history behind the current constants

### Takeaway
The docs describe the Android Wi-Fi mechanisms accurately: QUICKACK, pipelining, the 1.5 s OpenSession, the stale session and no-internet risks. Several documented risks have no code counterpart, and a few numbers are platform-specific. All the Wi-Fi code landed within about 2.5 hours on 2026-10-25, so its weak-signal behaviour has had little field exposure.

### Cited Findings
- **Docs vs code differences.**
  - **AccessDenied wait.** `docs/protocol.md:152` says retry AccessDenied after 20 ms (Mac; `Sources/ZVCore/Session/CameraSession.swift:94-96`). Android uses 5 ms on both USB and Wi-Fi (`android/app/src/main/java/local/zve10viewer/session/CameraSession.kt:165-168`). The 5 ms is documented only in the USB-Android section (`docs/protocol.md:268`), not in the Wi-Fi section.
  - **fps.** `docs/protocol.md:150` says ~20–24 fps; Android reports 25 fps on USB and Wi-Fi (`docs/protocol.md:268,310`) and 23–25 on Wi-Fi Direct (`:312`).
  - **Frame size.** `docs/protocol.md:303` says a preview frame is ~46 KB in one piece; the repo fixture is 22,260 bytes (section 1).
  - **Per-command timeout.** `docs/protocol.md:48` gives 8 s per command (Mac, ImageCaptureCore). On Android Wi-Fi the 8 s applies per `read()`, with no per-command deadline (`android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:82-83`).
  - **Threading model.** `docs/architecture.md:180,183` (Mac actors) vs Android's single `@Synchronized` monitor (section 7).
  - **Transient path loss.** `docs/protocol.md:311` and `Sources/ZVCamera/PTPIPTransport.swift:54-58`: the Mac tolerates `NWConnection .waiting` as "not a loss". Android has no equivalent; any `IOException` is a loss (`android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:112-115`).
  - **Keepalive.** The Mac uses keepalive 5/2/3 (`Sources/ZVCamera/PTPIPTransport.swift:33-36`); Android uses OS defaults (section 2).
  - **Mac-only references.** `docs/architecture.md:102` mentions `connectAttachedIfAny`, which is Mac-only; Android re-acquires the cable via its 500 ms poll (`android/app/src/main/java/local/zve10viewer/CameraController.kt:605-609`).
  - **Signal source.** `docs/architecture.md:131` describes signal via CoreWLAN (Mac); Android uses `ConnectivityManager.activeNetwork` capabilities (`android/app/src/main/java/local/zve10viewer/CameraController.kt:1046-1055`).
  - **Documented risks with no code.** `docs/protocol.md:308` (stale session ~2.5 min) has only a hint in code (section 5). `docs/protocol.md:313` (Android leaving a no-internet network) has no handling (section 3).
  - **Consistent with code:** `docs/architecture.md:129-131` (two-task pipeline; backoff 100 ms→1 s; fps over 2 s; stale > 1.5 s; max gap over 10 s) and `docs/architecture.md:302-304`.
- **History** (`git log --oneline -- android`):
  - `d5e16a1` (2026-10-24 22:32): USB transport, session, live view.
  - `42a834f` (22:47): async event wait, PTP reset, stale-reply filtering (`docs/protocol.md:265-268`).
  - `e8846e6`: quick reconnect after app restart, i.e. the OpenSession 1.5 s × 4 (`docs/protocol.md:266`).
  - `6703fdf` (2026-10-25 00:39): Wi-Fi PTP/IP. Adds `TCP_QUICKACK` (16–19 → 25 fps), the next frame requested while the current one decodes, and the doubling packet reader for 2 MB packets.
  - `8bc52a5` (01:28): Wi-Fi Direct (23–25 fps; RAW+JPEG ~20 s; no protocol change).
  - `7a8b040` (03:05): `WiFiPolicy` (2/4/8/10 s backoff after a lost Wi-Fi session; no Init to unpaired cameras; stale hint after 3 failures to a visible camera); cancellable pairing via the `onSocket` hand-out before connect (diff to `android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:160-201`); "a Wi-Fi session ending in not-PC-Remote counts as a loss".
  - Later commits (`2133ab7`, `c6a3679`, `810b2f9`, `d1f4f72`, `66455ef`) did not touch the transport.
- **Constants inherited from the Mac:** the 8 s timeout and the "two timeouts in a row" rule (`Sources/ZVCamera/PTPIPTransport.swift:185,294-300`), and the 1.5 s × 4 OpenSession, which on the Mac lives inside the transport (`:259-263`).

### Inferences
- Wi-Fi went from first commit to code review in about 2.5 hours (00:39 → 03:05), and the docs say Wi-Fi was tested in AP mode and Wi-Fi Direct on the tablet (`docs/protocol.md:276,312`). Behaviour under weak signal (sections 4–6) has probably never been exercised deliberately.

### Gaps
- No commit or doc records a weak-signal experiment, packet captures, or why 8 s and 2-in-a-row were chosen beyond parity with the Mac.

## 12. Concrete weaknesses for weak-signal robustness and smoothness (each with evidence and a one-line fix idea)

### Takeaway
The biggest problems are, in order:
1. the all-or-nothing loss policy: give up after ~16 s, tear down, blank the UI, full re-handshake;
2. the self-inflicted ~2.5 min stale-session lockout when our FIN or RST cannot reach the camera, which is especially likely after a Wi-Fi disassociation;
3. no awareness of Android networks or lifecycle;
4. one 8 s timeout and one counter shared by every operation;
5. an event socket that can kill or silently starve the session.

Smoothness costs come from per-frame allocations, full-RTT AccessDenied polls, unconflated event refreshes and the lack of priority for user commands.

### Cited Findings
1. **All-or-nothing loss handling.** Any `IOException` or a second timeout tears everything down, nulls the last frame, sends the UI to the waiting screen and resets the REC timer (`android/app/src/main/java/local/zve10viewer/CameraController.kt:915-920,1074-1108`; `android/app/src/main/java/local/zve10viewer/ui/Screen.kt:157-159`; `android/app/src/main/java/local/zve10viewer/CameraController.kt:983-987`). *Fix idea:* split "link degraded / reconnecting" from "camera gone"; keep the last frame, properties and controls visible, and resume transparently.
2. **Giving up at ~16 s turns recoverable outages into lockouts.** `timeoutsInRow >= 2` → `close()` (`android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:104-110`), while the camera holds the session ~2.5 min (`docs/protocol.md:308`). *Fix idea:* keep the same TCP connection open through outages up to a longer deadline. Re-issue only the live-view request, and close (or reconnect) once the network is confirmed back.
3. **No stale-session mitigation.** Only a hint after 3 failures (`android/app/src/main/java/local/zve10viewer/CameraController.kt:856-860`). `close()` has no linger or `shutdownOutput` and nothing ensures delivery after the link returns (`android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:128-135`). *Fix idea:* close cleanly while the network still works (lifecycle, network loss notifications). Before declaring loss, check whether the network still exists, and measure whether a delayed FIN or RST frees the camera.
4. **Keepalive at OS defaults (2 h idle).** `keepAlive = true` only (`:198`); kernel defaults in [ip-sysctl](https://docs.kernel.org/networking/ip-sysctl.html). The Mac uses 5/2/3 (`Sources/ZVCamera/PTPIPTransport.swift:33-36`). *Fix idea:* set `TCP_KEEPIDLE`/`TCP_KEEPINTVL`/`TCP_KEEPCNT` and possibly `TCP_USER_TIMEOUT` through `Os.setsockoptInt` on the dup'd fd, like QUICKACK. The Linux constants are commonly 4/5/6 and 18; verify them.
5. **One per-read timeout (8 s) and one shared counter for all ops.** Live-view polls, refreshes, user writes and downloads share them (`android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:71,83,100,106-107`). *Fix idea:* per-operation deadlines — a short live-view stall detector (~1–2 s) to re-request without counting toward "dead", and longer budgets for downloads and handshake steps.
6. **No overall transaction deadline.** soTimeout is per `read()` and ignores trickling data (`:82-83`; [Java Socket](https://docs.oracle.com/javase/8/docs/api/java/net/Socket.html)). *Fix idea:* track elapsed time per transaction and the bytes/s of in-flight transfers.
7. **The event socket can kill the session or starve it silently.** Any event-thread error closes both sockets (`android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:149-152`). Its timeout is infinite and nothing probes it (`:178`; `android/app/src/main/java/local/zve10viewer/ptp/PtpIp.kt:72`). *Fix idea:* reopen only the event channel, or fall back to periodic property polling; send our own Probe Requests or use keepalive on the event socket.
8. **Not network-aware.** No `NetworkCallback`, no binding to the camera's Wi-Fi network, unbound sockets and unbound SSDP (grep; `android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:195-201,228`); risk documented in `docs/protocol.md:313`; default-network semantics in [Android docs](https://developer.android.com/develop/connectivity/network-ops/reading-network-state). *Fix idea:* `requestNetwork` / `registerNetworkCallback` for `TRANSPORT_WIFI` without requiring INTERNET, bind sockets with `Network.bindSocket`/`socketFactory` (needs `CHANGE_NETWORK_STATE` for a background network), and react to `onLost`/`onAvailable` with immediate resume.
9. **Brief Wi-Fi drops are fatal.** netd destroys sockets when the address is removed ([AOSP netd](https://android.googlesource.com/platform/system/netd/+/8554248%5E%21/)), which leads to `IOException` → loss (`android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:112-115`), with no FIN able to reach the camera. *Fix idea:* treat loss with a network-gone cause as "reconnect immediately when the camera network is available again", and keep the UI.
10. **No lifecycle handling, WifiLock or foreground service.** `MainActivity` handles only `onCreate`/`onDestroy` (`android/app/src/main/java/local/zve10viewer/MainActivity.kt:16-33`); the permissions are minimal (`android/app/src/main/AndroidManifest.xml:6,8`). *Fix idea:* stop live view on `onStop` and close cleanly or pause; resume on `onStart`; hold a low-latency WifiLock while streaming (semantics to verify, needs `WAKE_LOCK`).
11. **The first retry always waits ≥ 2 s, even after an instant RST or ENETUNREACH.** See `android/app/src/main/java/local/zve10viewer/wifi/WiFiPolicy.kt:48-53`. *Fix idea:* make the first retry immediate (with jitter) and back off only on repeated failures.
12. **Slow reconnect cycles.**
    - Direct connect waits up to 5 s for TCP and then 5 s for Init (`android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:161,200`).
    - SSDP always burns its full 4 s window, even after the camera has answered (`:233-247`).
    - The two run strictly one after the other (`android/app/src/main/java/local/zve10viewer/CameraController.kt:810-822`).

    *Fix idea:* shorter LAN connect timeouts, SSDP running in parallel with an early exit, and racing the last host against discovered ones.
13. **Any Init Fail clears the pairing.** No reason-code check (`android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:170`; `android/app/src/main/java/local/zve10viewer/CameraController.kt:891-899`). *Fix idea:* forget the pairing only for reason 1; treat other reasons as "retry later".
14. **A parse error can wedge the stream.** `PtpParseException` from the reader is not handled as a connection error (`android/app/src/main/java/local/zve10viewer/ptp/PtpIp.kt:143`; `android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:104-115`). *Fix idea:* on a framing error, close and reconnect (or reset the reader).
15. **Each AccessDenied is a full round trip.** 5 ms wait then a re-poll (`android/app/src/main/java/local/zve10viewer/session/CameraSession.kt:165-168`); "every repeat costs a whole exchange" (`docs/protocol.md:322`). *Fix idea:* pace polls to the camera's ~40 ms frame cadence using the measured RTT, and drop rate gracefully (fewer polls, never a disconnect) when RTT grows.
16. **Per-frame allocations and copies.**
    - Every frame gets a new Bitmap with no `inBitmap` (`android/app/src/main/java/local/zve10viewer/CameraController.kt:976`).
    - The bytes are copied about four times (`android/app/src/main/java/local/zve10viewer/ptp/PtpIp.kt:134,145,193`; `android/app/src/main/java/local/zve10viewer/sony/Sony.kt:178`).
    - The histogram adds a scaled Bitmap every frame (`android/app/src/main/java/local/zve10viewer/CameraController.kt:1118-1123`).

    *Fix idea:* reuse a mutable `inBitmap` of the frame size, decode directly from the reply with an offset, and pool buffers.
17. **Event refreshes are not coalesced, and the event queue is unbounded.** One full property dump per 0xC203 (`android/app/src/main/java/local/zve10viewer/session/CameraSession.kt:240-244`); `Channel.UNLIMITED` (`android/app/src/main/java/local/zve10viewer/wifi/PtpIpTransport.kt:68`). *Fix idea:* collapse to one pending refresh, rate-limited, and deprioritised on a weak link.
18. **No command priority or fairness.** A stalled frame blocks user commands for up to 8 s or more, and downloads freeze live view (`:78-83`; `android/app/src/main/java/local/zve10viewer/session/CameraSession.kt:200-216`). *Fix idea:* a single command queue with priority: user actions before the next poll, and live view skipped while a command waits.
19. **Silent failures in the UI.** A failed write only clears `pending` (`android/app/src/main/java/local/zve10viewer/CameraController.kt:716-721`); REC failures are swallowed (`:400`); the reconnect text is static (`:919`). *Fix idea:* show a "weak signal / reconnecting / not applied" state and keep the REC timer from the camera's state.
20. **Observability gaps.** No logs in `PtpIpTransport`, failures swallowed in `tryWiFi`, no frame-size, throughput, RTT, timeout or reconnect metrics (grep; `:904-909`). *Fix idea:* structured transport metrics and a per-second link log (fps, maximum gap, RSSI, RTT, timeouts) to tune the thresholds above with data.
21. **USB robustness and threading.** `fail()` never marks a disconnect (`android/app/src/main/java/local/zve10viewer/usb/UsbPtpTransport.kt:207-209`); the event-thread exit leaves the event channel open (`:189-204`); `reset()` blocks Main (`android/app/src/main/java/local/zve10viewer/CameraController.kt:694-695`; `android/app/src/main/java/local/zve10viewer/usb/UsbPtpTransport.kt:66-92`); IN reads are synchronous 16 KB with extra copies (`:154,171-176,145`). *Fix idea:* move the open off Main, close the event channel on reader exit, use queued bulk-IN `UsbRequest`s with a single reaper, and add a stall→reconnect path.

### Inferences
- **Most damaging today on weak signal:** #2, #3 and #9 together. A short Wi-Fi disassociation or an outage longer than 16 s can cost about 2.5 minutes without video, even though both devices recover in seconds.
- **Most visible smoothness factors:** #15, #16, #17 and #18 on links with elevated RTT. Their size has not been measured (see section 8).

### Gaps
- WifiLock low-latency semantics, the HyperOS background and Wi-Fi-assistant behaviour, the camera's response to new connections while it holds a stale one, and the camera's Probe Request behaviour all need verification on the device or in vendor docs before the fixes above are finalised.
