# PTP/IP mechanisms and Sony camera-side behaviour for keeping ZV-E10 live view alive on weak Wi-Fi

Scope tags used on every finding: **[SPEC]** CIPA PTP-IP text; **[SONY-DOC/ZV-E10]** Sony document that explicitly lists the ZV-E10; **[SONY-DOC/other]** Sony document whose statement is generic or shown on another model; **[OTHER MODEL]** measured on a different Sony body; **[ZV-E10 measured]** this project's own measurements; **[IMPL]** behaviour of an open-source implementation; **[RFC]** TCP standard. Anything under "Inferences" is reasoning, not a documented fact. Source caveat: the most detailed Sony source, the "Camera Control PTP 3 Reference" (Sony Corporation, 723 pages, PDF dated July 2025), is officially available only to approved corporate applicants of Camera Remote Command. A copy is public in a third-party GitHub repo (jakkuh/sony-alpha-python). Use it for facts (codes, field layouts), not for redistribution.

## 1. PTP/IP (CIPA DC-005 / DC-X005) features for liveness, cancellation and recovery

### Takeaway
A public CIPA PTP-IP text exists. It defines everything needed for liveness and cancel: ProbeRequest/ProbeResponse (13/14) on the event connection with a recommended 10 s timeout, a Cancel packet (11) that aborts an in-flight GetObject, TCP keep-alive plus Nagle off, and Init Fail reasons Rejected/Busy/Unspecified. It has no session resumption: a PTP session dies with its two TCP connections, so every reconnect is a full handshake.

### Cited Findings
- [SPEC] The public text is "CIPA DC-X005-2005, PTP-IP (tentative name)", dated 28 July 2005. It is a draft for comment, copyright FotoNation, 10 pages, and can be downloaded from cipa.jp. It hides every numeric packet-type and reason value as `0x0000NNNN`. — [CIPA DC-X005-2005 PDF](https://www.cipa.jp/std/documents/e/DC-X005.pdf)
- [IMPL] The numeric packet types that all implementations agree on are: 1 InitCommandRequest, 2 InitCommandAck, 3 InitEventRequest, 4 InitEventAck, 5 InitFail, 6 OperationRequest, 7 OperationResponse, 8 Event, 9 StartData, 10 Data, **11 Cancel**, 12 EndData, 13 ProbeRequest, 14 ProbeResponse. The project table in protocol.md lacks type 11. — [Wireshark packet-ptpip.c](https://github.com/wireshark/wireshark/blob/master/epan/dissectors/packet-ptpip.c); [malc0mn/ptp-ip packets.go](https://github.com/malc0mn/ptp-ip/blob/HEAD/ip/packets.go); [RobertDamerius PTPIPTypes.hpp](https://github.com/RobertDamerius/SonyRemoteCameraControl/blob/HEAD/source/rcc/PTPIPTypes.hpp); [project docs/protocol.md](../../docs/protocol.md)
- [SPEC] The spec defines three Init Fail reasons:
  - FAIL_REJECTED_INITIATOR: the responder bonds devices and this initiator is not allowed.
  - FAIL_BUSY: the responder has too many active connections, and the initiator MAY retry later.
  - FAIL_UNSPECIFIED: everything else.

  Implementations give them the numbers 1, 2 and 3. After Init Fail the responder SHALL close the rejected connection. Init Fail can travel on either connection; the responder also sends it on the event connection if it cannot allocate resources. — [CIPA DC-X005 §2.2.3.1, §2.3.5](https://www.cipa.jp/std/documents/e/DC-X005.pdf); [malc0mn/ptp-ip packets.go](https://github.com/malc0mn/ptp-ip/blob/HEAD/ip/packets.go). This fits the project's observation that reason 1 means "not paired".
- [SPEC] Connection setup goes in this order: Command/Data TCP, then InitCommandRequest (GUID, friendly name, version), then an Ack with a Connection Number, then the Event TCP connection, then InitEventRequest carrying that number, then InitEventAck. If the second TCP connection fails, the first SHALL be closed, and the initiator MAY retry later. The spec recommends a **30 s timeout for PTP-IP connection establishment**. — [CIPA §2.2.3.1](https://www.cipa.jp/std/documents/e/DC-X005.pdf)
- [SPEC] Both TCP connections SHOULD have **TCP keep-alive enabled on both peers** and **Nagle disabled on both peers**. The PTP requirement for a reliable channel is mapped to TCP with keep-alive. — [CIPA §1.7, §2.2.3.2](https://www.cipa.jp/std/documents/e/DC-X005.pdf)
- [SPEC] The initiator starts shutdown. An error that discredits a channel's reliability is a reason to shut the connection down, and both TCP connections MUST be closed. — [CIPA §2.2.3.3](https://www.cipa.jp/std/documents/e/DC-X005.pdf)
- [SPEC] Session semantics:
  - A PTP session also ends when the connection is lost, for example through communication timeouts.
  - The two TCP connections identify the session, so no SessionID is sent.
  - The responder MAY limit concurrent sessions by limiting the connections it accepts, or MAY answer OpenSession with Device_Busy. The initiator SHOULD then release the connections and retry later.
  - No resumption mechanism exists.

  — [CIPA §1.6.2, §1.6.3, §2.4](https://www.cipa.jp/std/documents/e/DC-X005.pdf)
- [SPEC] A disconnect or network loss is detected by three criteria: the network layer reports the medium is gone, a socket on either channel breaks, or a transmission on either channel times out. PTP-IP itself has no flow control; it relies on TCP. — [CIPA §2.5, §2.6](https://www.cipa.jp/std/documents/e/DC-X005.pdf)
- [SPEC] **Probe**:
  - Either peer may send a ProbeRequest to check that the other is alive, and the receiver MUST reply immediately with a ProbeResponse.
  - If no reply comes within a reasonable time, the checking side closes the PTP-IP session(s).
  - Both packets are empty (8-byte header only) and travel **on the Event TCP connection**. A ProbeResponse must never be sent unsolicited.
  - Probes should be used sparingly so they do not load the LAN. The initiator should probe only during long transactions (format is the example).
  - The responder should probe only when **a new session request arrives while other sessions are active**, to check whether the existing connections are still alive.
  - The recommended probe timeout is **10 s**.

  — [CIPA §2.3.13–2.3.14](https://www.cipa.jp/std/documents/e/DC-X005.pdf)
- [SPEC] **Cancel**:
  - The Cancel packet carries only a TransactionID. It goes from initiator to responder and is sent **on both the Command/Data and the Event connections**.
  - For a data-in transfer such as GetObject, the responder MUST interrupt as soon as possible, stop sending data packets, and send an OperationResponse with Transaction_Cancelled (0x201F).
  - If the event-channel copy arrives first, the responder waits for the copy on the command channel. A Cancel that arrives after the response has started is ignored.
  - Data packets MAY be fragmented specifically so that cancel is practical.
  - Asynchronous operations are cancelled the same way and end with an event.

  — [CIPA §2.3.10, §2.3.12, §2.7, Annex 5.6.1](https://www.cipa.jp/std/documents/e/DC-X005.pdf)
- [SPEC] A StartData total length of 0xFFFFFFFFFFFFFFFF means the size is unknown, which is meant for streaming. GUID-based bonding is an application-layer access control, and authentication is outside PTP-IP. The protocol version is 0x00010000. — [CIPA §2.3.9, Annex 5.3, §3](https://www.cipa.jp/std/documents/e/DC-X005.pdf)
- [IMPL] libgphoto2's event reader treats any non-Event packet as unhandled and logs an error. A TODO comment says cancel and ping/pong are not handled, so it never answers probes and never sends Cancel. gphoto's old reverse-engineered PTP/IP page lists Cancel as untested. — [libgphoto2 ptpip.c](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/ptpip.c); [gphoto PTP/IP doc](https://gphoto.sourceforge.io/doc/ptpip.php)

### Inferences
- Reconnecting means a full handshake: Init on two sockets, OpenSession, Sony SDIO_Connect phases 1 to 3 with GetExtDeviceInfo, and the ~3 s settle. The stale-session block (section 2) can add to that. The cheapest way to survive short dropouts is therefore not to tear down at all. Keep both sockets open, let TCP retransmit, and use application timeouts measured in seconds, in line with the spec's 10 s probe and 30 s setup guidance, not sub-second ones.
- ProbeRequest on the event socket is the spec-sanctioned liveness check that costs no PTP transaction and does not disturb the GetObject pipeline. Whether the ZV-E10 answers an initiator probe is unverified and needs one experiment. The app MUST answer camera probes in any case and must drain the event socket continuously.
- The responder-side probe on a new session request is exactly the mechanism that would clear a stale session within about 10 s. The ZV-E10's ~2.5 min hold suggests it does not implement it, or does not apply it to a half-open connection.
- Cancel could abort a GetObject mid-transfer, but it saves little for 22–46 KB live-view frames, which are mostly in flight already. It matters for downloads that arrive as 2 MB data packets. Sony support for Cancel is undocumented.

### Gaps
- The numeric packet and reason codes are hidden in the public draft. The values come only from implementations, though all of them agree.
- The final ratified CIPA DC-005 may differ from the 2005 draft, which is the only free text. Not verified.
- Whether ISO 15740:2008/2013 contains a PTP-IP annex was not checked.

## 2. Sony session lifecycle over Wi-Fi: drops, stale sessions, reconnection

### Takeaway
Sony documents only the clean path (CloseSession, then release the connection) and GUID pairing (up to 20 hosts stored). Nothing public explains the ~2.5 min stale-session hold or a way to break it. Evidence from Sony's SDK (a 5-minute auto-reconnect window) and from an FX3 study shows two things. A stale session is cleared when the camera actually receives a disconnect. Re-attaching without user action is normal in AP mode, but Wi-Fi Direct after a camera restart needs menu action.

### Cited Findings
- [ZV-E10 measured] The camera holds one PTP/IP connection at a time and remembers several paired hosts. If the FIN never arrives, it keeps the dead session ~2.5 min and refuses new connections. A normal close frees it immediately. — [project docs/protocol.md](../../docs/protocol.md)
- [SONY-DOC/ZV-E10] To disconnect, the initiator must issue CloseSession and then release the PTP connection. — [Sony Camera Control PTP 3 Reference p.53](https://github.com/jakkuh/sony-alpha-python/blob/main/docs/Camera%20Control%20PTP%203%20Reference.pdf)
- [SONY-DOC/ZV-E10] Pairing works like this:
  - The camera shows the friendly name from InitCommandRequest.
  - After the user presses OK, it sends InitCommandAck and saves the GUID.
  - Pairing is needed only on the first connection, because **up to 20 entries** are stored.

  — [Sony PTP 3 Reference pp.40–41](https://github.com/jakkuh/sony-alpha-python/blob/main/docs/Camera%20Control%20PTP%203%20Reference.pdf)
- [SONY-DOC/ZV-E10] Discovery is SSDP/UPnP for the DigitalImaging service, followed by dd.xml and DigitalImagingDesc.xml:
  - X_ServerVersion ≥ 3.00 means PTP-IP is supported.
  - X_PTP_PairingNecessity says whether pairing is required.
  - X_SSH_Support, if enabled, forces an SSH tunnel to localhost:15740 using aes128-ctr; port 15740 is then closed to direct connections.

  PTP-IP works in Wi-Fi Direct (AP mode), Wi-Fi station mode and wired LAN. — [Sony PTP 3 Reference pp.36–40](https://github.com/jakkuh/sony-alpha-python/blob/main/docs/Camera%20Control%20PTP%203%20Reference.pdf)
- [SONY-DOC/ZV-E10] Sony advises polling the camera status with SDIO_GetAllExtDevicePropInfo rather than trusting vendor events, because older models do not guarantee event delivery. Events are acceptable for 2019+ models only if the host keeps draining the queue. Otherwise events can be dropped. — [Sony PTP 3 Reference p.35](https://github.com/jakkuh/sony-alpha-python/blob/main/docs/Camera%20Control%20PTP%203%20Reference.pdf)
- [SONY-DOC/ZV-E10] If SDIO_GetExtDeviceInfo returns zero data, retry until it succeeds, then send SDIO_Connect phase 3. — [Sony PTP 3 Reference p.42](https://github.com/jakkuh/sony-alpha-python/blob/main/docs/Camera%20Control%20PTP%203%20Reference.pdf)
- [SONY-DOC/ZV-E10] The help guide says three relevant things:
  - If the camera detects unauthorized access, it may stop accepting communication, and the user must reconnect from the beginning.
  - Smartphone Connection must be off for PC Remote.
  - Initializing the camera deletes pairing information.

  — [ZV-E10 Help Guide, PC Remote Function](https://helpguide.sony.net/ilc/2070/v1/en/contents/TP0002392805.html)
- [SONY-DOC/other] Sony's SDK has a built-in reconnect. On a physical loss it goes Connected → **Reconnecting**: the main loop stops and a reconnect-monitoring loop runs. If the link returns it goes back to Connected. **After 5 minutes it gives up** and goes to Disconnected. CrReconnecting_OFF disables this. It is valid in Remote Control and Remote Transfer modes but not in Contents Transfer mode. Sony's example uses a USB cable. — [CrSDK API Reference: Changes in SDK connection status](https://github.com/Johnnybyzhang/CrSDK-API-Reference/blob/HEAD/op_flow_and_seq/op_changes_in_sdk_connection_status.html); [CrSDK: Connect a Camera](https://github.com/Johnnybyzhang/CrSDK-API-Reference/blob/HEAD/op_flow_and_seq/op_connect_a_camera.html)
- [SONY-DOC/other] The SDK reports these PTP-IP and connection conditions:
  - Warnings: CrWarning_NetworkErrorOccurred and CrWarning_NetworkErrorRecovered (a transient network error with recovery, without a disconnect), and CrWarning_Connect_Reconnecting and CrWarning_Connect_Reconnected.
  - Connection errors: CrError_Connect_FailRejected (pairing error), FailBusy (processing in progress), FailUnspecified, SessionAlreadyOpened, TimeOut, and CrError_Reconnect_TimeOut.

  The three Fail codes mirror the PTP-IP Init Fail reasons. — [CrSDK OnWarning (other)](https://github.com/Johnnybyzhang/CrSDK-API-Reference/blob/HEAD/status_code_error/OnWarning_other.html); [CrSDK Error codes](https://github.com/Johnnybyzhang/CrSDK-API-Reference/blob/HEAD/status_code_error/ErrorCode.html)
- [SONY-DOC/other, ILCE-1 example] A Wi-Fi Direct link cannot be re-established automatically when the camera starts; the user must use the menu. Use Access Point mode for automatic connection at start-up. — [CrSDK environment setup (wireless LAN)](https://github.com/Johnnybyzhang/CrSDK-API-Reference/blob/HEAD/other/environment_setup.html)
- [OTHER MODEL: ILME-FX3A over USB, CrSDK 2.02.00, third-party measurements 2026] The CrSDKPy study recorded the following:
  - A loss produces the Reconnecting warning (0x20002), never OnDisconnected, and recovery produces a second OnConnected.
  - Unplug/replug recovered in ~23–28 s, and live view resumed by itself with the first JPEG 38 ms after recovery. A power-cycle recovery took ~44.5 s.
  - After a host died without disconnecting, the next Connect was accepted but never completed and hit the tool's 15 s deadline. That failed attempt's Disconnect cleared the camera's stale session, and the next connect took ~0.6 s.
  - A rapid reconnect can fail with SessionAlreadyOpened (0x8210), so a short delay helps.
  - After USB re-enumeration the body showed a mode prompt that needed an operator.

  — [CrSDKPy FX3_CHARACTERIZATION.md](https://github.com/PICKLERICK2005/CrSDKPy/blob/HEAD/docs/FX3_CHARACTERIZATION.md)
- [IMPL, Sony "Send to Computer" PTP/IP mode, older bodies] Sony expects vendor operations 0x9280 and 0x9281 at init and at exit in PTP/IP transfer mode, with 9280 before 9281. Otherwise the camera returns DeviceBusy. This is a different mode from PC Remote, but it shows that Sony bodies have vendor-specific Wi-Fi teardown. — [libgphoto2 library.c](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/library.c); [libgphoto2 issue #16](https://github.com/gphoto/libgphoto2/issues/16)
- [RFC] Half-open connection discovery: if the peer that lost state sends a SYN on the same 4-tuple, the side with the stale ESTABLISHED connection answers with an ACK. The restarted side then sends RST, and the stale side aborts. TCP keep-alives are optional and off by default, with a default interval of at least 2 hours. A connection is closed after R2 retransmissions, and for a SYN R2 is at least 3 minutes. — [RFC 9293 §3.5.1, Appendix B](https://www.rfc-editor.org/rfc/rfc9293.html)

### Inferences
- The ~2.5 min hold matches the camera's TCP retransmission give-up on unacknowledged data (an R2-type timer) or a camera idle timer better than it matches keep-alive, whose default is ≥2 h. Test both kinds of drop: one while streaming, which leaves unacknowledged data in flight, and one while idle, which may hold longer.
- Ways to release the camera sooner, all untested on the ZV-E10, best first:
  - (a) Keep the old sockets alive through the outage. When the link returns, send CloseSession on the old command socket and close both sockets so the FIN reaches the camera. This matches the observation that a normal close frees the camera at once.
  - (b) If the old sockets are gone, reconnect from the same local source ports (SO_REUSEADDR/bind). This triggers RFC 9293 half-open discovery, and the camera's stale TCB is reset.
  - (c) Do an abortive close (SO_LINGER 0, which sends RST) of the old sockets once the link is back.
  - (d) Retry Init with the same GUID and log the Init Fail reason (2 = Busy would fit the spec). Watch whether the camera sends a ProbeRequest on the old event socket.
- A host OS may destroy the sockets of a lost Wi-Fi network, so no FIN is ever sent. This is especially likely on Android. Holding the sockets, or reusing the ports, sidesteps that.
- Sony's own 5-minute reconnect window suggests Sony expects outages of minutes and re-attaches without user action in AP mode. Wi-Fi Direct after a camera power cycle needs the user.
- The help-guide warning about unauthorized access suggests rapid, aggressive reconnect storms are risky. Use backoff.

### Gaps
- No Sony document or user report describes the ZV-E10's Wi-Fi session timeout, its answer to InitCommandRequest while a stale session exists, its TCP keep-alive settings, or whether it leaves PC Remote or shows an error after a drop.
- No public CrSDK document gives the SDK's PTP-IP read timeouts or retry intervals.

## 3. Sony Camera Remote SDK and Camera Remote Command: reconnect, Wi-Fi guidance, live-view properties, PTP code mapping

### Takeaway
The CrSDK does not support the original ZV-E10, but Sony's PTP-level "Camera Remote Command" lists the ZV-E10, and its compatibility table marks 0xD26A Live View Image Quality (Low/High), 0xD221 Live View Status and 0xD267 Live View Area as supported. In the SDK, Low = 640 px wide and High = 1024 px wide. Sony's Wi-Fi advice is: stay within 5 m, turn Bluetooth off, use 5 GHz where available, and shrink live view for a stable frame rate.

### Cited Findings
- [SONY-DOC/other] The CrSDK compatibility list (API reference, late 2025) includes ZV-E1 and ZV-E10M2 but not the ZV-E10. ZV-E10M2 is supported over USB and wireless in Remote and Contents-transfer modes. Multiple hosts per camera (up to 5) apply only to footnoted models such as MPC-2610. — [CrSDK compatibility](https://github.com/Johnnybyzhang/CrSDK-API-Reference/blob/HEAD/other/compatibility.html); [Sony CrSDK page](https://support.d-imaging.sony.co.jp/app/sdk/en/index.html)
- [SONY-DOC/ZV-E10] Camera Remote Command, Sony's PTP-level spec, is free but released only to corporate customers. It lists the ZV-E10 among supported devices.
  - PTP-IP support arrived in 2024.1.0 (2024-10-16). The latest version is 2.02.00 (2026-06-10).
  - Only the latest firmware is supported, and each model's interfaces are listed only in the README.
  - Sony warns that "Camera Control PTP 2" commands may become unusable on some models from 2027.
  - Version 2.00.00 removed wrong compatibility check marks for 25 models, including the ZV-E10.

  — [Sony Camera Remote Command page](https://support.d-imaging.sony.co.jp/app/cameraremotecommand/en/index.html)
- [SONY-DOC/other] Connect() has a 5th parameter CrReconnectingSet, which defaults to ON. The mode and the reconnect setting cannot change while connected. OnConnected reports Remote-Control Protocol version 300, which corresponds to the 0x012C extension version checked through SDIO_GetExtDeviceInfo. The camera may not accept shooting immediately after connecting. — [CrSDK: Connect a Camera](https://github.com/Johnnybyzhang/CrSDK-API-Reference/blob/HEAD/op_flow_and_seq/op_connect_a_camera.html); [Sony PTP 3 Reference p.42–43](https://github.com/jakkuh/sony-alpha-python/blob/main/docs/Camera%20Control%20PTP%203%20Reference.pdf)
- [SONY-DOC/other] Sony's Wi-Fi caveats:
  - Wi-Fi throughput is lower and more interference-prone than wired, and Sony targets indoor use.
  - Camera Wi-Fi chips and antennas differ, so throughput and stability differ by model.
  - Recommended settings: keep the camera **within 5 m** of the host or AP, **turn off Bluetooth**, and **use 5 GHz** where the camera can switch bands.

  — [CrSDK environment setup (wireless LAN)](https://github.com/Johnnybyzhang/CrSDK-API-Reference/blob/HEAD/other/environment_setup.html)
- [SONY-DOC/other] Live view is updated at **30 fps when communication is good**, and the rate drops sharply when bandwidth is narrow or images are being transferred. When there is no new frame, GetLiveViewImage returns CrWarning_Frame_NotUpdated, and the SDK numbers frames. — [CrSDK: Get a Live View Image](https://github.com/Johnnybyzhang/CrSDK-API-Reference/blob/HEAD/op_flow_and_seq/op_get_a_liveview_image.html)
- [SONY-DOC/other] Live view stability depends on traffic on the link, frequent releases and transfers, frequent get/set of properties, and host performance. For a stable live-view frame rate, Sony advises minimizing live-view image size and reducing releases and transfers. — [CrSDK tips & troubleshooting](https://github.com/Johnnybyzhang/CrSDK-API-Reference/blob/HEAD/tips_trouble_shooting/tips_trouble_shooting.html)
- [SONY-DOC/other] SDK live-view properties:
  - LiveView_Image_Quality: Low = width 640 px, High = width 1024 px, height by aspect ratio.
  - LiveViewImageQualityByNumericalValue: compression level as a UInt8 range; it does not change dimensions.
  - LiveViewProtocol: Main (default) or Alt, which delivers live view over HTTP.
  - LiveViewStatus: Disable, Enable or NotSupport.

  — [CrSDK dp_LiveView_Image_Quality](https://github.com/Johnnybyzhang/CrSDK-API-Reference/blob/HEAD/dp/dp_LiveView_Image_Quality.html); [dp_LiveViewImageQualityByNumericalValue](https://github.com/Johnnybyzhang/CrSDK-API-Reference/blob/HEAD/dp/dp_LiveViewImageQualityByNumericalValue.html); [dp_LiveViewProtocol](https://github.com/Johnnybyzhang/CrSDK-API-Reference/blob/HEAD/dp/dp_LiveViewProtocol.html); [dp_LiveViewStatus](https://github.com/Johnnybyzhang/CrSDK-API-Reference/blob/HEAD/dp/dp_LiveViewStatus.html)
- [SONY-DOC/ZV-E10] Mapping to PTP codes:
  - **0xD26A** Live View Image Quality: UINT8 enumeration, 1 = Low, 2 = High, Get/Set. Sony says it controls the data size of live-view images and *may* change width and height.
  - **0xD221** Live View Status: 0 = disabled, 1 = enabled, 2 = not supported.
  - **0xD267** Live View Area (x, y).
  - **0xD231** Live View Display Effect: 1 = ON, 2 = OFF.
  - **0xE0CE** Live View Image Quality by Numerical Value.
  - 0xD278 LiveViewURL.

  In the compatibility table, 0xD26A, 0xD221 and 0xD267 are checked for the ZV-E10. 0xE0CE is checked only for ILME-FR7 and BRC-AM7 (pan/tilt models), and Sony's text ties it and the HTTP LiveViewURL to pan/tilt cameras. libgphoto2's ptp.h uses the same codes for D221, D231, D267 and D26A. — [Sony PTP 3 Reference pp.48–50, 94–100, 458, 492, 616](https://github.com/jakkuh/sony-alpha-python/blob/main/docs/Camera%20Control%20PTP%203%20Reference.pdf); [libgphoto2 ptp.h](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/ptp.h)
- [SONY-DOC/ZV-E10] 0xD251 Device Overheating State reads 0 = not overheating, 1 = pre-overheating, 2 = overheating. This settles the "не сверено" (unverified) entry in protocol.md. 0xD049 Auto Power OFF Temperature (Standard/High) is *not* checked for the ZV-E10. — [Sony PTP 3 Reference pp.86, 219, 478](https://github.com/jakkuh/sony-alpha-python/blob/main/docs/Camera%20Control%20PTP%203%20Reference.pdf)
- [SONY-DOC/other] The SDK defines CrWarning_ControlMonitoring_Result_WifiHighTemperature for its streaming and monitoring feature on pro models, so Sony bodies can refuse Wi-Fi functions when hot. — [CrSDK OnWarning (API)](https://github.com/Johnnybyzhang/CrSDK-API-Reference/blob/HEAD/status_code_error/OnWarning_api.html)

### Inferences
- The ZV-E10 is listed for 0xD26A, yet the project's USB test showed identical frames for 1 and 2. Possible reasons: the camera ignores the property, it changes only compression and not size (Sony says "may"), the change needs a live-view restart, or it is effective only on some transports. Retest over Wi-Fi. Compare JPEG byte counts, not just dimensions. Check IsEnabled, re-read the value after setting it, and toggle a mode to restart live view.
- If Low works, 640 px wide carries about 39% of the pixels of 1024 px. That could be roughly 2–2.5× fewer bytes per frame. This is an estimate.
- The ZV-E10 has no documented frame-rate or bitrate control, so apart from 0xD26A the host can adapt only through its own polling rate.

### Gaps
- No "Display Speed Priority vs Image Quality Priority" live-view mode was found in the SDK or PTP property lists. The Imaging Edge Desktop Remote pages show no live-view quality option either.
- The numeric CrWarning values (only 0x20002, reported for the FX3A) and the SDK's internal Wi-Fi timeouts are undocumented.

## 4. Live-view data at 0xFFFFC002: format, sizes, pacing, adaptation

### Takeaway
Sony documents the 0xFFFFC002 dataset as four u32 fields (JPEG offset/size, focal-frame-info offset/size), then reserved bytes, the JPEG and FocalFrameInfo. Sony says to call GetObjectInfo only once, to treat AccessDenied with a zero-length image as "no new frame", and to keep to the 30 fps ceiling. No source says the camera lowers resolution by itself on Wi-Fi; only the frame rate falls.

### Cited Findings
- [SONY-DOC/ZV-E10] LiveView Dataset layout:
  - u32 Offset to Live View Image
  - u32 Live View Image Size
  - u32 Offset to Focal Frame Info
  - u32 Focal Frame Info Size
  - reserved bytes (variable)
  - the JPEG
  - FocalFrameInfo

  — [Sony PTP 3 Reference p.107](https://github.com/jakkuh/sony-alpha-python/blob/main/docs/Camera%20Control%20PTP%203%20Reference.pdf). [ZV-E10 measured] The JPEG offset is 1524. — [project docs/protocol.md](../../docs/protocol.md)
- [SONY-DOC/ZV-E10] Get the 0xFFFFC002 object info **only once**, for performance. While live view is enabled, ObjectCompressedSize is an estimated worst case, not the real size. When live view is not enabled the camera stalls and returns InvalidObjectHandle (0x2009). The intended sequence is: read 0xD221, wait while it is Disabled, call GetObjectInfo once, then loop GetObject. — [Sony PTP 3 Reference pp.48, 106](https://github.com/jakkuh/sony-alpha-python/blob/main/docs/Camera%20Control%20PTP%203%20Reference.pdf)
- [SONY-DOC/ZV-E10] **The live-view maximum is 30 fps.** The host should not issue the operation "over two times within 33 msec". This is probably the Japanese-English idiom for "two or more times", which would mean at most one request per ~33 ms. When no new frame exists, the data phase carries a zero-size image, the response is AccessDenied (0x200F), and the host retries. — [Sony PTP 3 Reference p.107](https://github.com/jakkuh/sony-alpha-python/blob/main/docs/Camera%20Control%20PTP%203%20Reference.pdf)
- [IMPL] libgphoto2's Sony preview:
  - It calls GetObjectInfo before every GetObject.
  - On InvalidObjectHandle it waits 50 ms; a comment says this state can last up to 1 s on the ZV-1.
  - On AccessDenied it waits 20 ms, up to 50 tries, with a 1 s port timeout.
  - It uses the first u32 as the JPEG offset, because a header once contained FF D8.

  — [libgphoto2 library.c](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/library.c)
- [SONY-DOC/other] The live-view JPEG runs from SOI to EOI, and its size depends on the live-view quality setting and the aspect ratio. Focus and face frames come as separate live-view properties in the SDK. — [CrSDK: Get a Live View Image](https://github.com/Johnnybyzhang/CrSDK-API-Reference/blob/HEAD/op_flow_and_seq/op_get_a_liveview_image.html)
- [SONY-DOC/other, ILCE-1 example] Sony's example dd.xml contains X_ScalarWebAPI_LiveView_URL pointing to an HTTP liveview stream. — [Sony PTP 3 Reference p.38](https://github.com/jakkuh/sony-alpha-python/blob/main/docs/Camera%20Control%20PTP%203%20Reference.pdf)
- [SONY-DOC/other] Imaging Edge Desktop Remote's live-view pages cover magnification, the AF area and overlays, with no quality or speed option. — [Imaging Edge Remote: Live View display](https://support.d-imaging.sony.co.jp/app/imagingedge/en/instruction/4_4_liveview_display.php)

### Inferences
- If the apps call GetObjectInfo per frame or per restart, dropping it saves one round trip per frame, which matters most on weak Wi-Fi.
- Sony's pacing note argues for at most about two outstanding requests and ~33 ms spacing. On a weak link, deeper pipelining mainly adds queueing. The command channel is one TCP stream, so a lost segment of one frame delays every later response (head-of-line blocking). An adaptive policy that lowers the request rate and depth when RTT or retransmission grows fits the camera's contract.
- Check whether the ZV-E10's own dd.xml advertises any HTTP liveview URL. It probably does not, since the ZV-E10 predates and lacks the old Camera Remote API.

### Gaps
- No source gives typical ZV-E10 JPEG sizes per quality setting, or confirms or denies automatic resolution reduction over Wi-Fi.
- The FocalFrameInfo structure was not extracted.
- Whether Imaging Edge Mobile or Creators' App offer a live-view quality choice was not found.

## 5. ZV-E10 Wi-Fi hardware limits (versus ZV-E10 II)

### Takeaway
The original ZV-E10 has 2.4 GHz-only 802.11 b/g/n Wi-Fi in infrastructure mode and Bluetooth 4.1 on the same band. The ZV-E10 II adds 5 GHz (802.11a/b/g/n/ac). Sony itself recommends ≤5 m, Bluetooth off and 5 GHz, so on the original body the radio environment is the dominant limit.

### Cited Findings
- [SONY-DOC/ZV-E10] Official specifications:
  - Wireless LAN: IEEE 802.11 b/g/n, 2.4 GHz band.
  - Security: WPA2-PSK. WEP and WPA are not supported from firmware 2.02 onward; this project's camera runs 2.00.
  - Connection: WPS or manual. Access method: infrastructure mode.
  - Bluetooth 4.1 (2.4 GHz).
  - Networked standby draws 2.0 W, and low-power and networked standby modes start within 20 min by default.

  — [ZV-E10 Help Guide: Specifications](https://helpguide.sony.net/ilc/2070/v1/en/contents/TP1000201578.html)
- [SONY-DOC/ZV-E10] PC Remote offers three connection methods: USB, Wi-Fi Direct (the camera is the AP; SSID and password shown under Wi-Fi Direct Info.) and Wi-Fi Access Point (pairing required). Security (IPsec) is offered. — [ZV-E10 Help Guide: PC Remote](https://helpguide.sony.net/ilc/2070/v1/en/contents/TP0002392805.html); [Sony ZV-E10 PC Remote tutorial](https://support.d-imaging.sony.co.jp/support/tutorial/zv/zv-e10/l/pcremote.php)
- [SONY-DOC/other] The ZV-E10 II has IEEE 802.11a/b/g/n/ac Wi-Fi on 2.4 and 5 GHz. This was read from a Sony store listing via search, not fetched directly. The CrSDK supports ZV-E10M2 over Wi-Fi. — [Sony SG store ZV-E10 II specifications](https://store.sony.com.sg/blogs/8180386398363_45200989814939/8180386398363__specifications); [CrSDK compatibility](https://github.com/Johnnybyzhang/CrSDK-API-Reference/blob/HEAD/other/compatibility.html). Some retail listings give Bluetooth 4.2 for the II; the official ZV-E10 spec says 4.1.
- [ZV-E10 measured] Results so far: 25 fps live view on a good router link, 23–25 fps over Wi-Fi Direct, and about 5 MB/s file download to the tablet. — [project docs/protocol.md](../../docs/protocol.md)

### Inferences
- At 25 fps, frames of ~22–46 KB need about 4.4–9.2 Mbit/s of goodput. This is an estimate. A good 2.4 GHz 802.11n link has headroom. At a weak signal, the low PHY rates plus retries in a congested 2.4 GHz band can drop below that, so frame rate must fall unless frames shrink.
- Bluetooth shares the 2.4 GHz band, so turning it off on the camera, as Sony advises, may cut contention. Test it.
- Moving the host closer, or using Wi-Fi Direct to avoid a second hop, may help more than any protocol change.

### Gaps
- No sources were found on antenna placement or range, on how the Wi-Fi Direct channel is chosen, or on whether PC Remote suppresses power-save or auto power-off.
- No sources were found on heat effects on the ZV-E10's Wi-Fi, or user reports of PC Remote Wi-Fi drops. Searches returned only general smartphone-connection troubleshooting.

## 6. Prior-art implementations: timeouts, probes, reconnect patterns

### Takeaway
Open implementations rarely do more than libgphoto2: fixed 2.5 s socket timeouts, no probes, no cancel, no reconnect. The more robust ones add TCP keep-alive and NODELAY as the spec says, a probe heartbeat on the event channel every 5–20 s, a few misses before declaring the link dead, exponential backoff, and stopping live view before reconnecting. No public material was found for Cascable, qDslrDashboard, Smart Shooter, Helicon or airnef.

### Cited Findings
- [IMPL] libgphoto2's PTP/IP layer:
  - Connect, read and write all use a fixed 2 s + 500 ms timeout on non-blocking sockets with select.
  - An event check is a 1 ms select.
  - No setsockopt for keep-alive or NODELAY was found.
  - Init Fail is mapped to AccessDenied.
  - The event connection is retried twice after 100 ms on ECONNREFUSED, a Ricoh Theta workaround.
  - There is no probe or cancel handling (TODO) and no reconnect.

  — [libgphoto2 ptpip.c](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/ptpip.c); [ptpip-private.h](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/ptpip-private.h)
- [IMPL] malc0mn/ptp-ip (Go, Fuji-focused) enables TCP keep-alive and NODELAY on every connection, citing the spec. It uses a 10 s dial timeout, a 30 s read timeout and a retrying dialer. — [malc0mn/ptp-ip ip.go](https://github.com/malc0mn/ptp-ip/blob/HEAD/ip/ip.go)
- [IMPL] Fimagena/libptp (Java) sends a ProbeRequest on the event connection after 20 s of inactivity and ignores the replies. It answers any incoming ProbeRequest on the connection it arrived on. — [libptp PtpIpConnection.java](https://github.com/Fimagena/libptp/blob/HEAD/com/fimagena/libptp/ptpip/PtpIpConnection.java)
- [IMPL, OTHER MODEL: Sony ILME-FX3 firmware 7.00] RobertDamerius/SonyRemoteCameraControl uses a 3 s receive timeout on the command channel. It implements a ProbeRequest helper that sends on the *command* channel and expects the ProbeResponse there, which deviates from the spec. No call site was found in SonyCamera.cpp, so it is unproven that the FX3 answers. — [PTPIPDevice.cpp](https://github.com/RobertDamerius/SonyRemoteCameraControl/blob/HEAD/source/rcc/PTPIPDevice.cpp); [PTPIPTypes.hpp](https://github.com/RobertDamerius/SonyRemoteCameraControl/blob/HEAD/source/rcc/PTPIPTypes.hpp)
- [IMPL, Sony interop marked "awaiting hardware"] ZENCHE (a multi-platform app, doc dated 2026-08) works like this:
  - A ProbeRequest heartbeat every 5 s on the event channel, with a 3 s timeout. It calls this a deliberate deviation from the spec's 10 s.
  - It answers camera probes immediately and keeps consuming events.
  - Three consecutive misses move it to "reconnecting", with backoff of 1, 2, 4, 8 and 16 s capped at 30 s. A manual disconnect never auto-reconnects, and live view stops before a reconnect.
  - On Apple platforms, NWConnection `.waiting` is not treated as failed, and an immediate probe is sent on returning to the foreground.

  — [ZENCHE PTPIP_PROTOCOL.md](https://github.com/Tauber01/ZENCHE/blob/HEAD/docs/PTPIP_PROTOCOL.md)
- [IMPL] CrSDKPy models the SDK's states as Connected → Reconnecting → Reconnected (a second OnConnected) with no Disconnected in between, and treats a transient busy as not a lost link. — [CrSDKPy FX3_CHARACTERIZATION.md](https://github.com/PICKLERICK2005/CrSDKPy/blob/HEAD/docs/FX3_CHARACTERIZATION.md)
- [IMPL, newer Sony bodies] The author of sony-alpha-python found the SSH authentication path far more reliable than pairing in testing. The ZV-E10 has no SSH and uses pairing. — [jakkuh/sony-alpha-python](https://github.com/jakkuh/sony-alpha-python)
- [IMPL] sony-pm-alt uses gphoto2 over ptpip for Sony "Send to Computer" mode. It notes that Sony needs non-standard packets for the camera to show "Sending...". — [falk0069/sony-pm-alt](https://github.com/falk0069/sony-pm-alt)

### Inferences
- A pattern that fits both the spec and the camera:
  - Socket timeouts of seconds.
  - A ProbeRequest heartbeat on the event socket, but only if the ZV-E10 answers it. Otherwise use a cheap PTP read between frames.
  - N misses, then an orderly teardown (CloseSession + FIN when possible), then reconnect with backoff.
  - Stop live view polling during recovery.
  - Treat transient OS states such as macOS `waiting` as stalls, not failures.

### Gaps
- No public technical write-ups were found for Cascable, qDslrDashboard, Smart Shooter or Helicon Remote on Wi-Fi liveness, reconnects or live-view sizing. The searches returned only reviews.
- airnef was not examined.

## 7. Old Sony Camera Remote API (JSON-RPC/HTTP liveview): lessons

### Takeaway
The legacy API offered exactly two live-view sizes, L (XGA-scale) and M (VGA-scale). On some models "L" raised quality instead of size, the same caveat Sony now attaches to 0xD26A. The API also pushed frames over a long-lived HTTP stream with an optional frame-info payload, rather than per-frame request/response.

### Cited Findings
- [SONY-DOC/other, via client libraries] startLiveviewWithSize offered two sizes: "L" = XGA-size scale, which varies by model, and some models change quality instead of size; "M" = VGA-size scale. getSupportedLiveviewSize lists what a camera offers. — [pysony (Bloodevil/sony_camera_api)](https://github.com/Bloodevil/sony_camera_api/blob/HEAD/src/pysony.py); [kazyx kz-remote-api CameraParams.cs](https://github.com/kazyx/kz-remote-api)
- [SONY-DOC/other, via client libraries] The liveview stream is HTTP. Each packet has a common header (start byte 0xFF, payload type, sequence number, timestamp) and a payload header. Payload type 1 is a JPEG and type 2 is frame information, which setLiveviewFrameInfo switches on. — [pysony](https://github.com/Bloodevil/sony_camera_api/blob/HEAD/src/pysony.py)
- [SONY-DOC/other] In Wi-Fi Direct the legacy JSON server was at 192.168.122.1:8080. That is the same camera address the ZV-E10 uses in Wi-Fi Direct. — [farhi/matlab-sonyalpha](https://github.com/farhi/matlab-sonyalpha)

### Inferences
- A push stream tolerates RTT better than polling, because no request per frame is needed. The ZV-E10's PTP path does not offer one; newer models have LiveViewProtocol "Alt" over HTTP. The practical lesson for the ZV-E10 is the size and quality switch (0xD26A) plus host-side pacing.

### Gaps
- No primary Sony document for the legacy API was retrieved, only client-library docstrings. The ZV-E10 does not support that API, which Sony phased out in favour of the CrSDK.
