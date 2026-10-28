# OS, TCP-stack and radio factors that stall a 2.4 GHz camera's TCP live view on Android and macOS, and what the app can control

Legend used throughout: **[APP]** means the receiving app (ZV-E10 Viewer) can control it. **[USER]** means only the user, router or OS settings can. **[CAM]** means only the camera, which is the bulk TCP sender and, in Wi-Fi Direct, also the AP. Project facts taken from the brief (TCP_QUICKACK fix, 2.5 min stale session) are marked "(brief)". Source code was read on 2026-10-25 from current `main`/`master` branches (AOSP packages/modules/Wifi and Connectivity, Apple XNU, Linux). Line-level behaviour may differ on the exact kernel or OS build a device ships.

## 1. Radio/MAC realities: retries vs TCP loss, rate adaptation at weak RSSI, contention, Bluetooth/USB 3 coexistence, two hops vs direct AP, channel advice

### Takeaway
A weak 2.4 GHz link fails gradually. MAC-layer retries and rate fallback first raise latency and cut throughput. Only after that does TCP see loss, and that loss tends to come in bursts. The stream needs about 4.4 Mbit/s of payload (22 KB × 25 fps), which needs roughly MCS1–2 or better sustained on every wireless hop. The margin is thin: router mode with both devices on the same 2.4 GHz channel doubles the airtime per frame, and Bluetooth or USB 3 interference eats more of the 2.4 GHz band. The receiving app controls almost none of this. The levers are placement and channel/band choice [USER], plus keeping the client off the camera's channel (5 GHz or Ethernet) in router mode [USER].

### Cited Findings
- **802.11n PHY rates (HT20, 1 spatial stream):** MCS0 runs at 6.5 Mbit/s and MCS7 at 65 Mbit/s with the 800 ns guard interval (72.2 Mbit/s with the short GI). The steps are 6.5 / 13 / 19.5 / 26 / 39 / 52 / 58.5 / 65 — [Wikipedia: IEEE 802.11n-2009, data rates table](https://en.wikipedia.org/wiki/IEEE_802.11n-2009) (standard values, not re-fetched this session).
- **Typical sensitivity of a 2.4 GHz b/g/n 1x1 chip (Microchip ATWILC3000, 25 °C, 3.3 V):**
  - 802.11b: −95 dBm at 1 Mbit/s DSSS, −86 dBm at 11 Mbit/s.
  - 802.11g: −90 dBm at 6 Mbit/s, −73 dBm at 54 Mbit/s.
  - HT20: MCS0 −89, MCS1 −87, MCS2 −84, MCS3 −81.5, MCS4 −78, MCS5 −74, MCS6 −72, MCS7 −70 dBm.
  - The datasheet does not state the PER threshold.
  - This is one example chip. The camera's chipset is unknown.
  - [Microchip ATWILC3000 receiver performance](https://onlinedocs.microchip.com/oxy/GUID-04DF72D6-FAA7-410A-A90B-E7F4B7F3D676-en-US-2/GUID-EA53B129-6FBC-49B5-B0F9-0C763D3DA312.html)
- **What Android itself treats as weak on 2.4 GHz.** The AOSP Wi-Fi scorer defaults are −73 dBm ("low", `config_wifi_framework_wifi_score_low_rssi_threshold_24GHz`) and −83 dBm ("bad", `..._bad_rssi_threshold_24GHz`). OEM overlays can change them — [AOSP Wifi config.xml](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/main/service/ServiceWifiResources/res/values/config.xml)
- **Bluetooth coexistence, measured.** A Nirma University thesis measured Wi-Fi throughput with Bluetooth active:
  - The drop was about 38% at 2.4 GHz versus about 2% at 5 GHz. A2DP was among the profiles tested.
  - Inquiry gave an average drop of about 41%. LE_CC gave the minimum, about 10%.
  - Multi-profile setups lost about 60% on average, single-profile setups about 25%.
  - These figures come from the repository abstract; test devices were not checked.
  - [Nirma University repository](https://repository.nirmauni.ac.in/jspui/handle/123456789/11913)
- **USB 3 interference on Macs.** Apple says some USB 3 devices emit RF interference that disrupts 2.4 GHz Wi-Fi and Bluetooth. It advises keeping USB 3 devices and hubs away from the Mac's antenna area near the display hinge, using the ports farthest from it, and moving Wi-Fi to 5 GHz. Bluetooth has no 5 GHz option — [Apple Support, "Why do I have difficulty with Wi-Fi or Bluetooth devices when USB 3 devices are attached…" (apple.co short link)](https://apple.co/35fCYvX)
- **Apple devices on 2.4 GHz.** AWDL (AirDrop/Continuity) uses channel 6 as its 2.4 GHz "social" channel and 44/149 on 5 GHz. A device on another channel keeps hopping to these channels. Details are in section 4 — [Visser (IIJ), APNIC 58 slides](https://conference.apnic.net/58/assets/files/APNZ606/awdl-apnic58_1725341937.pdf)
- **Linux Wi-Fi WMM mapping.** The Linux Wi-Fi stack (cfg80211, used by mac80211-based Android drivers) maps outgoing packets to an 802.11 user priority. By default it uses the top 3 bits of the DSCP, per RFC 8325, with these exceptions: AF11–13 → UP 0, CS2 → 0, AF21–23 → 3, CS3 → 4, AF31–33 → 4, VA → 6, EF → 6, CS6 → 7. The DF, CS1, CS4 and AF41–43 (→ UP 4), CS5 (→ 5) and CS7 (→ 7) values keep the plain 3-bit mapping. An AP's QoS Map overrides all of this — [Linux net/wireless/util.c `cfg80211_classify8021d`](https://github.com/torvalds/linux/blob/master/net/wireless/util.c). This only prioritises the tablet's own uplink: ACKs and requests.

### Inferences
- **Payload arithmetic.** 22 KB × 25 fps ≈ 4.4 Mbit/s before TCP/IP/802.11 overhead. On a clean channel, TCP goodput on 802.11n is well below the PHY rate; 50–70% is a working assumption that depends on A-MPDU aggregation. So 25 fps needs the camera to sustain about MCS1–2 (13–19.5 Mbit/s PHY) or better. At MCS0 (6.5 Mbit/s) it is marginal. If the camera's rate control falls back to 802.11b DSSS rates, 25 fps is physically impossible. One 1500-byte frame at 1 Mbit/s takes about 12 ms of airtime, so a 16-segment JPEG takes about 190 ms. At 11 Mbit/s it is about 1.1 ms per frame, about 18 ms per JPEG, before contention and ACK overhead.
- **Expect lower fps and jitter before outright loss.** 802.11 retransmits unacknowledged frames at the MAC layer, typically stepping down the rate. A marginal link therefore first shows growing RTT, jitter and falling throughput (fps drops, frames arrive late). TCP loss appears when MAC retries are exhausted or a queue overflows. That loss is bursty: several consecutive segments disappear during a fade, a scan or an off-channel excursion. Bursty loss is the worst case for TCP recovery (section 2).
- **Router mode, both wireless clients on the same 2.4 GHz channel.** Every JPEG crosses the air twice: camera→AP, then AP→client. That roughly halves the airtime left for the stream and adds a second set of MAC retries and AP queueing. A camera stuck at a low rate also consumes a large share of channel airtime, which slows the AP→client hop. This is the classic 802.11 "rate anomaly"; the literature was not verified this session. If the client is on 5 GHz or Ethernet, only the camera→AP hop uses 2.4 GHz airtime, and the client hop no longer competes with the camera's hop.
- **Wi-Fi Direct is one hop but has its own costs.** The camera's soft AP picks its channel, beacon/DTIM interval and power-save handling [CAM]. The client must be on 2.4 GHz, so Bluetooth coexistence on the client matters most in Wi-Fi Direct. A Mac or tablet that also wants another network must either leave it (single STA) or time-share channels (STA+STA concurrency, AWDL), which adds latency.
- **Channel and width advice [USER].**
  - Use 20 MHz on 2.4 GHz. The camera is almost certainly 1x1 HT20, and 40 MHz in 2.4 GHz overlaps most of the band.
  - Pick channel 1, 6 or 11, whichever has the least neighbour traffic. If Macs, iPhones or iPads are on the network, channel 6 also matches AWDL's 2.4 GHz social channel, which reduces Apple devices' off-channel hopping (section 4).
  - Put the router or AP close to the camera with line of sight.
  - Keep USB 3 hubs and drives away from the Mac's hinge.
  - Move Bluetooth-heavy clients (AirPods, BT mice and keyboards) to 5 GHz in router mode.
- **Bluetooth on the client [USER].** Phones, tablets and Macs commonly share one antenna or radio between Wi-Fi and Bluetooth and time-slice 2.4 GHz. Active A2DP audio such as AirPods can take airtime slices from a 2.4 GHz Wi-Fi link (thesis: about 38% throughput loss at 2.4 GHz). That matters most in Wi-Fi Direct, where the client must be on 2.4 GHz. In router mode with a 5 GHz client it is mostly irrelevant (about 2%).

### Gaps
- **Camera radio internals are unknown [CAM].** This covers the camera's Wi-Fi chipset, TX power, rate-control algorithm, A-MPDU/Block-Ack support, MAC retry limits, WMM marking of its own packets, and its power-save/DTIM settings as AP. No public source was found, and the "802.11b/g/n, 2.4 GHz only" spec was not re-verified here. A pcap on a monitor-mode interface would show the camera's MCS and retry flags.
- **Standards figures not verified.** The IEEE 802.11n minimum-sensitivity table (commonly quoted as −82 dBm for MCS0 and −64 dBm for MCS7 at HT20) and the default MAC retry limits (dot11ShortRetryLimit/dot11LongRetryLimit) were not checked against the standard.
- **Bluetooth latency not measured.** No controlled measurement was found of the latency (as opposed to throughput) cost of AirPods/A2DP or BT HID on a Mac's or Android tablet's 2.4 GHz Wi-Fi.

## 2. TCP with an embedded sender and an app-side receiver: what the receiver controls, what only the camera controls, how stalls form, how to observe them, keepalive/user-timeout

### Takeaway
The receiving app controls only a few things:
- how fast its stack ACKs;
- the advertised window (read promptly; don't pin SO_RCVBUF);
- how and when it decides the peer is dead.

Everything that turns loss into a stall belongs to the camera's TCP stack [CAM]: RTO and its minimum, exponential backoff, SACK/RACK/TLP support, cwnd behaviour after idle, and how many retransmissions it tries before giving up.

A 22 KB frame is only about 16 MSS-sized segments. A loss near the end of a frame cannot trigger fast retransmit, because fewer than three duplicate ACKs follow. Recovery then falls back to an RTO, and repeated losses double it. The result is 1–6 s freezes that look like a dead link but are a live TCP connection waiting on a backed-off timer.

### Cited Findings
**Linux / Android receiver behaviour**
- **Linux timer constants** ([include/net/tcp.h](https://github.com/torvalds/linux/blob/master/include/net/tcp.h)):
  - `TCP_DELACK_MIN` = HZ/25 (40 ms); `TCP_DELACK_MAX` = HZ/5 (200 ms); `TCP_ATO_MIN` = 40 ms.
  - `TCP_MAX_QUICKACKS` = 16.
  - `TCP_RTO_MIN` = HZ/5 (200 ms); `TCP_TIMEOUT_INIT` = 1 s (RFC 6298 initial RTO); `TCP_RTO_MAX` = 120 s.
  - Keepalive defaults: 2 h idle, 75 s interval, 9 probes. `TCP_INIT_CWND` = 10.
- **Why request/response turns on delayed ACKs.** `tcp_event_data_sent()` bumps the "pingpong" (interactive) counter when the app sends data within one ATO of the last received packet. `tcp_in_quickack_mode()` returns false while the socket is in pingpong mode. The threshold is now a sysctl (`sysctl_tcp_pingpong_thresh`) — [net/ipv4/tcp_output.c](https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_output.c), [net/ipv4/tcp_input.c](https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c), [include/net/inet_connection_sock.h](https://github.com/torvalds/linux/blob/master/include/net/inet_connection_sock.h). A viewer that sends GetObject right after each response is exactly this pattern.
- **When Linux ACKs at once.** `__tcp_ack_snd_check()` ACKs immediately when more than one full-sized segment has arrived and the window advances, or in quickack mode, or when `ICSK_ACK_NOW` is set. Otherwise it schedules a delayed ACK. A lone sub-MSS segment, such as the tail of a JPEG or a small AccessDenied reply, therefore waits for the delayed-ACK timer in pingpong mode — [net/ipv4/tcp_input.c](https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c)
- **Linux re-enters quickack mode for 16 ACKs** (`TCP_MAX_QUICKACKS`) when it receives already-received data (a retransmission, `tcp_send_dupack`) or out-of-window data. It enters it for 2 ACKs on ECN CE or a missing ECT mark after ECT was seen — [net/ipv4/tcp_input.c](https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c)
- **What setting `TCP_QUICKACK=1` does.** The setter (`__tcp_sock_set_quickack`) leaves pingpong mode and, if an ACK is already scheduled, pushes it out at once (`ICSK_ACK_PUSHED` + `tcp_cleanup_rbuf`). Setting 0 enters pingpong mode — [net/ipv4/tcp.c](https://github.com/torvalds/linux/blob/master/net/ipv4/tcp.c). The man page says the flag is not permanent (it only switches mode) — [tcp(7)](https://man7.org/linux/man-pages/man7/tcp.7.html)
- **Other tcp(7) defaults** ([tcp(7)](https://man7.org/linux/man-pages/man7/tcp.7.html)):
  - `TCP_USER_TIMEOUT` (Linux 2.6.37+, milliseconds) is the longest that transmitted data may stay unacknowledged before the connection is closed. With `SO_KEEPALIVE` it overrides keepalive, and it does not change when retransmissions or probes are sent.
  - `TCP_KEEPIDLE` / `TCP_KEEPINTVL` / `TCP_KEEPCNT` are per-socket keepalive settings in seconds and counts.
  - `tcp_retries2` defaults to 15, roughly 13–30 minutes.
  - `tcp_sack` and `tcp_slow_start_after_idle` (RFC 2861 cwnd timeout after idle) are on by default.
  - `tcp_rmem` default is 87,380 bytes.
- **Setting SO_RCVBUF disables receive autotuning on Linux.** Setting it sets `SOCK_RCVBUF_LOCK`, and the autotuning paths in tcp_input.c skip locked sockets — [net/ipv4/tcp_input.c](https://github.com/torvalds/linux/blob/master/net/ipv4/tcp_input.c)
- **Linux `struct tcp_info` fields useful to a receiver:**
  - `tcpi_rcv_rtt` and `tcpi_rcv_space`;
  - `tcpi_rcv_ooopack` (out-of-order packets received, i.e. the camera's losses seen from our side);
  - `tcpi_rcv_wnd` and `tcpi_last_data_recv`;
  - `tcpi_options` with `TCPI_OPT_SACK`/`TCPI_OPT_WSCALE`/`TCPI_OPT_TIMESTAMPS` (shows whether the camera negotiated SACK, window scaling and timestamps).
  - `tcpi_rtt`, `tcpi_rttvar`, `tcpi_retrans` and `tcpi_total_retrans` describe our own sending direction (requests/ACKs), not the camera's retransmissions.
  - Option numbers: `TCP_NODELAY` 1, `TCP_KEEPIDLE` 4, `TCP_KEEPINTVL` 5, `TCP_KEEPCNT` 6, `TCP_INFO` 11, `TCP_QUICKACK` 12, `TCP_USER_TIMEOUT` 18, `TCP_NOTSENT_LOWAT` 25.
  - [include/uapi/linux/tcp.h](https://github.com/torvalds/linux/blob/master/include/uapi/linux/tcp.h)

**XNU / macOS receiver behaviour**
- **macOS delayed-ACK conditions.** `net.inet.tcp.delayed_ack` defaults to 3 ([tcp_input.c](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/netinet/tcp_input.c)). In mode 3, `tcp_cc_delay_ack()` delays an ACK only if all of these hold ([tcp_cc.c](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/netinet/tcp_cc.c)):
  - it is not sending a zero window;
  - no forced ACKs are pending;
  - unread data sits in the receive buffer above its low-water mark;
  - the receive window is not growing;
  - at most 1 MSS is unacknowledged (or the window has grown large);
  - less than half a BDP and less than 256 MSS are unacknowledged.
- **What `TCP_SENDMOREACKS` (0x103) does.** It sets `TF_QUICKACK`, commented "Force-ACK every other packet". With it, the ACK is not delayed once about 1 MSS is pending — [tcp_usrreq.c](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/netinet/tcp_usrreq.c), [tcp_var.h](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/netinet/tcp_var.h), [tcp.h](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/netinet/tcp.h)
- **macOS forces 16 immediate ACKs** (`TCP_FORCED_ACKS_COUNT`) after connection setup, after an idle period, whenever the reassembly queue is in use (loss), and on ECN CE — [tcp_input.c](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/netinet/tcp_input.c), [tcp_output.c](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/netinet/tcp_output.c)
- **A prompt reader gets prompt ACKs on macOS.** If an ACK was delayed and the app then empties the receive buffer, and the last segment had PSH set, `tcp_output()` sends the ACK immediately. It also sends an ACK or window update when the window opens by ≥2 MSS after the app drains the buffer — [tcp_output.c](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/netinet/tcp_output.c)
- **macOS delayed-ACK timer:** 100 ms, firing somewhere between 100 and 200 ms — [tcp_timer.c](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/netinet/tcp_timer.c)
- **Setting SO_RCVBUF disables receive autosizing on macOS.** It clears `SB_AUTOSIZE` ([uipc_socket.c](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/kern/uipc_socket.c)), and receive-buffer growth requires that flag ([tcp_input.c](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/netinet/tcp_input.c)).
- **Darwin `TCP_CONNECTION_INFO` (0x106).** It returns `struct tcp_connection_info` ([xnu bsd/netinet/tcp.h](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/netinet/tcp.h)):
  - `tcpi_options` with `TCPCI_OPT_TIMESTAMPS/SACK/WSCALE/ECN` and `tcpi_flags` with `TCPCI_FLAG_LOSSRECOVERY/REORDERING_DETECTED`;
  - `tcpi_rto`, `tcpi_srtt`, `tcpi_rttvar`, `tcpi_rttcur` and `tcpi_rcv_wnd`;
  - `tcpi_rxpackets/rxbytes`, `tcpi_rxoutoforderbytes` (the receiver-side loss/reordering signal) and `tcpi_txretransmitpackets/bytes`.
- **macOS retransmission and keepalive defaults** ([tcp_timer.h](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/netinet/tcp_timer.h), [tcp_timer.c](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/netinet/tcp_timer.c)):
  - Backoff multipliers are {1, 2, 4, 8, 16, 32, 64, 64…}, with `TCP_MAXRXTSHIFT` 12, maximum RTO 64 s, and an initial RTO of 1 s without RTT information.
  - Keepalive defaults: 2 h idle, 75 s interval, 8 probes.

**Sender-side standards (all [CAM])**
- **Retransmission timer (RFC 6298).** RTO = SRTT + 4·RTTVAR. An RTO below 1 s SHOULD be rounded up to 1 s. Each timeout doubles the RTO (back-off), and any maximum must be at least 60 s — [RFC 6298](https://www.rfc-editor.org/rfc/rfc6298) (not re-fetched). Linux deviates with its 200 ms floor (`TCP_RTO_MIN`, above).
- **Restart after idle (RFC 5681 §4.1).** After an idle period longer than the RTO, the sender should shrink cwnd to the restart window — [RFC 5681](https://www.rfc-editor.org/rfc/rfc5681) (not re-fetched). Linux implements this as `tcp_slow_start_after_idle` (default on, [tcp(7)](https://man7.org/linux/man-pages/man7/tcp.7.html)).
- **Recovery helpers the sender may or may not have.** RACK-TLP sends a probe about 2 RTTs after the last transmission so tail losses are recovered without waiting for an RTO — [RFC 8985](https://www.rfc-editor.org/rfc/rfc8985) (not re-fetched). SACK lets the sender repair several holes per RTT — [RFC 2018](https://www.rfc-editor.org/rfc/rfc2018) (not re-fetched).
- **Project facts (brief).** The camera withholds its small AccessDenied reply until the previous segment is ACKed. Android's ~40 ms delayed ACK capped live view at 16–19 fps until the app set TCP_QUICKACK before every read. If the client's FIN never reaches the camera, the camera keeps a stale session for about 2.5 min.

### Inferences
- **Why Android hit 40 ms but the Mac did not.** On Linux, the request-after-response pattern puts the socket into pingpong mode, and a lone tail segment's ACK then waits ≥40 ms. That stalls a camera that withholds its next reply until the ACK arrives. On XNU, a socket whose buffer the app drains promptly ACKs immediately when the last segment had PSH, or when the window update is ≥2 MSS. So a NWConnection that always has a receive outstanding gets near-quick ACKs without any option.
- **Android: keep re-arming TCP_QUICKACK before every read [APP].** It is non-permanent, and the next request re-increments the pingpong counter. Setting it also flushes an already-scheduled delayed ACK, which is exactly what the AccessDenied path needs.
- **macOS analogue [APP].** Setting `NWProtocolTCP.Options.disableAckStretching = true` (most likely implemented with `TCP_SENDMOREACKS`/`TF_QUICKACK`; undocumented) is the closest analogue and costs little. It is not a 1:1 equivalent of TCP_QUICKACK, and prompt reading matters more.
- **How 1–6 s stalls form [CAM].** Suppose the camera's stack is Linux-like, with RTO ≈ SRTT + 200 ms on an RTT of 5–20 ms. Losing the same segment (or its retransmission) repeatedly gives waits of about 0.2, 0.4, 0.8, 1.6 and 3.2 s. The cumulative stall is about 0.6 s after 2 consecutive losses, 1.4 s after 3, 3 s after 4 and 6.2 s after 5. With a strict RFC 6298 1 s floor it is 1, 3, 7 and 15 s. A few bad fades in a row therefore produce exactly the 1–6 s freezes seen. The receiver cannot shorten an RTO.
- **Tail losses and why pipelining helps.** A 22 KB JPEG is about 16 segments at MSS 1448. If one of the last ~3 segments of the last outstanding frame is lost, too few dupACKs follow for fast retransmit, so recovery waits for the RTO unless the camera has TLP/RACK or early retransmit. Keeping 2 requests pipelined [APP] means the next frame's segments follow the lost one, generating dupACKs/SACKs and triggering fast retransmit. That is a strong reason to keep pipeline depth at 2. Going deeper adds head-of-line latency without helping loss detection much.
- **Head-of-line blocking.** TCP delivers bytes in order, so one lost segment blocks the rest of that frame and any pipelined frame behind it. The event channel is a separate TCP connection and is not blocked, but it shares the radio.
- **After a stall the first frames come slowly [CAM].** If the stall exceeds the camera's RTO and its stack implements restart-after-idle or an RTO cwnd reset, those frames come back in slow start. Nothing on the receiver changes that.
- **Receive window [APP].** Two pipelined frames are about 45 KB in flight. The default Linux receive buffer (87 KB, autotuned upwards) and XNU autosizing both cover this. Do not set SO_RCVBUF: it disables autotuning on both OSes and would hurt 2 MB photo transfers. If it is set anyway, make it large (≥256–512 KB). What matters is reading on a dedicated thread or queue that is never blocked by JPEG decoding. A shrinking or zero window stalls the camera regardless of radio quality.
- **Detecting a dead peer without killing a merely stalled connection [APP].**
  - During a sender-side stall, the client's own last bytes (the GetObject request) have normally been ACKed already, or will be when the camera retransmits. The client's TCP therefore has nothing outstanding, and TCP_USER_TIMEOUT / connectionDropTime do not fire while the camera is alive and occasionally reachable.
  - Keepalive probes only go out after keepidle with no traffic. A live camera answers them even while its data is backed off.
  - A reasonable proposal (not measured), in place of the 2 h OS defaults:
    - SO_KEEPALIVE on, with idle 3–5 s, interval 1–2 s and 4–6 probes.
    - Android: TCP_USER_TIMEOUT of about 10–20 s. macOS: `connectionDropTime` of about 10–20 s.
  - Watchdogs are the primary tool:
    - Track time since the last byte and since the last complete frame.
    - Show "weak signal" after about 0.5–1 s without a frame.
    - Do not tear down while bytes or keepalive ACKs still trickle in.
    - Abort only on a TCP error, a Network loss callback (section 3/4), or a long hard timeout.
- **FIN delivery to the camera.** Close cleanly while the link is up: PTP CloseSession plus a normal close and FIN. An abortive close (SO_LINGER 0, RST) is a single unretransmitted packet and is more likely to be lost than a FIN. macOS `retransmitFinDrop` and Linux orphan retries only free local state. The camera's ~2.5 min stale-session timer is [CAM].
- **Observability [APP].** The receiver sees the camera's losses as out-of-order arrivals and arrival gaps:
  - Log per connection: Linux `tcpi_rcv_ooopack`, `tcpi_rcv_rtt`, `tcpi_last_data_recv`; XNU `tcpi_rxoutoforderbytes`, `tcpi_srtt`/`rttvar`. Correlate them with frame gaps.
  - Read `tcpi_options` once after connect to learn whether the camera's stack negotiated SACK, timestamps and window scaling. This answers the "does the embedded stack support SACK?" question without a pcap.
  - A pcap shows the camera's actual RTO, backoff and TLP behaviour (retransmission spacing).

### Gaps
- **The camera's TCP stack is unknown** (Linux-based or RTOS), along with its minimum RTO, backoff cap, SACK/TLP/RACK/early-retransmit support, retry count before aborting, and keepalive/session timer. The ~2.5 min stale session hints at a ~150 s idle timeout, but that is unconfirmed. Searches for Sony open-source notices covering ZV-E10 found only firmware pages (latest body firmware reported as 2.03) — [Sony Asia support](https://www.sony-asia.com/electronics/support/software/00272349). This needs measurement: SYN-ACK options, then retransmission timing under induced loss.
- **The Network.framework mapping is undocumented.** It is not documented whether `disableAckStretching` maps exactly to `TCP_SENDMOREACKS`, or whether Network.framework's TCP on macOS 27 is the in-kernel XNU stack for these connections.
- **Tablet kernel details not checked.** The Redmi Pad Pro's kernel version, its `tcp_pingpong_thresh` value and its `tcp_rmem` per-network settings were not verified.

## 3. Android specifics: Wi-Fi locks, power save, scans, binding to a no-internet network, "no internet" switching, WifiNetworkSpecifier, callbacks and RSSI APIs, socket options, DSCP/WMM, Doze

### Takeaway
On Android the app has real levers [APP]:
- hold `WIFI_MODE_FULL_LOW_LATENCY` while live view is on screen;
- bind its sockets to the camera's `Network`;
- watch NetworkCallback for loss and signal;
- keep TCP_QUICKACK per read;
- add keepalive and TCP_USER_TIMEOUT through raw Linux option numbers;
- read TCP_INFO through the NDK for diagnostics.

The low-latency lock disables Wi-Fi power save and requests the chip's low-latency mode. It works only when connected, with the screen on and the app in the foreground.

The biggest threat in Wi-Fi Direct is not the radio but the framework deciding that a no-internet network is "insufficient". The device can then move to a saved home network, or reconfigure routing on a phone with cellular. That needs a user choice ("stay connected") or the WifiNetworkSpecifier path, at the cost of a system dialog.

### Cited Findings
**Wi-Fi locks and power save**
- **`WIFI_MODE_FULL_LOW_LATENCY` (API 29) is active only when** the device is connected to an AP, the screen is on, and the app is in the foreground. Its documented trade-offs: battery life and throughput may be reduced, and Wi-Fi scanning frequency may be reduced. As a result the device may not roam or switch to the best AP, and location accuracy may drop. If an app holds both this lock and HIGH_PERF, LOW_LATENCY applies in the foreground with the screen on, HIGH_PERF otherwise — [WifiManager reference](https://developer.android.com/reference/android/net/wifi/WifiManager), [AOSP WifiManager.java](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/main/framework/java/android/net/wifi/WifiManager.java)
- **`WIFI_MODE_FULL_HIGH_PERF` is deprecated in API 34.** Requests for it now get a LOW_LATENCY lock with all its restrictions, because of power cost — [WifiManager reference](https://developer.android.com/reference/android/net/wifi/WifiManager). In AOSP the substitution applies from Android U (14) onwards and is gated by a device-config flag (`isHighPerfLockDeprecated() && SdkLevel.isAtLeastU()`) — [AOSP WifiLockManager.java](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/main/service/java/com/android/server/wifi/WifiLockManager.java). The original HIGH_PERF semantics were "no power save", held even with the screen off or the app in the background (javadoc in [AOSP WifiManager.java](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/main/framework/java/android/net/wifi/WifiManager.java)).
- **What the low-latency lock actually does in AOSP** (`WifiLockManager.setLowLatencyMode`):
  - It calls the chip HAL's low-latency mode and disables power save.
  - If the HAL reports low-latency unsupported, it only disables power save.
  - "Foreground" means `IMPORTANCE_FOREGROUND`. The only screen-on exemption is for apps with the automotive-projection companion permission.
  - [AOSP WifiLockManager.java](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/main/service/java/com/android/server/wifi/WifiLockManager.java)
  - `config_wifiLowLatencyLockDisableChipPowerSave` defaults to true, i.e. the framework may disable chip power save for the lock — [AOSP config.xml](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/main/service/ServiceWifiResources/res/values/config.xml)
- **Moonlight (Android game streaming) does this.** It acquires a HIGH_PERF lock always and a LOW_LATENCY lock on API 29+, both with `setReferenceCounted(false)`, for the whole stream. It catches a `SecurityException` some Samsung Galaxy S10 devices throw from `acquire()` — [moonlight-android Game.java](https://github.com/moonlight-stream/moonlight-android/blob/master/app/src/main/java/com/limelight/Game.java)

**Scans and RSSI polling**
- **Connected-state scans.** With the screen on, the framework scans on a 20 → 40 → 80 → 160 s schedule (`config_wifiConnectedScanIntervalScheduleSec`, AOSP default; OEMs may override). RSSI is polled every 3000 ms (`config_wifiPollRssiIntervalMilliseconds`) — [AOSP config.xml](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/main/service/ServiceWifiResources/res/values/config.xml)
- **App-triggered scans.** `startScan()` is deprecated since API 28. Throttling: each foreground app gets 4 scans per 2 minutes, all background apps together 1 per 30 minutes. Android 10+ adds a developer option to turn throttling off — [Wi-Fi scanning overview](https://developer.android.com/develop/connectivity/wifi/wifi-scan), [WifiManager.startScan](https://developer.android.com/reference/android/net/wifi/WifiManager)

**Network selection and "no internet"**
- **When Android looks for a better network.** `WifiNetworkSelector.isNetworkSufficient()` treats the current network as insufficient in these cases, and an insufficient network triggers a new network-selection pass that can move the device to another saved network:
  - it has no internet access and no internet is not "expected" (`hasNoInternetAccess() && !isNoInternetAccessExpected()`);
  - it is metered;
  - its link quality is insufficient and there is no active stream (Tx/Rx packets per second below a scoring threshold).
  - [AOSP WifiNetworkSelector.java](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/main/service/java/com/android/server/wifi/WifiNetworkSelector.java)
- **The "no internet" prompt.** ConnectivityService shows the NO_INTERNET prompt (`ACTION_PROMPT_UNVALIDATED`, about 8 s after connect) only if the user explicitly selected the network and has not yet accepted it unvalidated. When it prompts, it also stops automatically reconnecting to that network in the future (`onPreventAutomaticReconnect`). "Avoid bad Wi-Fi" is `NETWORK_AVOID_BAD_WIFI`; when avoidance is off, cellular yields to a bad Wi-Fi — [AOSP ConnectivityService.java](https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/main/service/src/com/android/server/ConnectivityService.java)
- **Vendor names for automatic switching vary:** "Auto Network Switch", "Smart Network Switch", "Avoid bad Wi-Fi connections", "Adaptive Wi-Fi". Local-device vendors tell users to disable them — [Blink support (third-party)](https://support.blinkforhome.com/en_GB/android-avoid-poor-wifi-connections)

**Binding sockets and WifiNetworkSpecifier**
- **`ConnectivityManager.bindProcessToNetwork(Network)` (API 23).** Every socket created afterwards that isn't explicitly bound, and all DNS lookups, go over that network. If the network disconnects, those sockets stop working by design. Google prefers per-socket binding via `Network.getSocketFactory()` and `Network.getAllByName` — [ConnectivityManager reference](https://developer.android.com/reference/android/net/ConnectivityManager)
- **Per-socket binding.** `Network.getSocketFactory()` returns sockets bound to that network, which stop working if it disconnects. `Network.bindSocket(Socket)` binds a not-yet-connected socket regardless of any process binding — [Network reference](https://developer.android.com/reference/android/net/Network)
- **Wi-Fi Network Request API (`WifiNetworkSpecifier` + `requestNetwork`, API 29):**
  - It shows a system dialog. The approval is stored per app and access point, so a later *specific* request for that AP connects without the dialog.
  - Pattern-based (non-specific) requests always need approval. Forgetting the network removes the approval.
  - It gives no internet to the app or device; the sample removes `NET_CAPABILITY_INTERNET`. The app releases it with `unregisterNetworkCallback`.
  - [Android: Wi-Fi Network Request API for peer-to-peer connectivity](https://developer.android.com/develop/connectivity/wifi/wifi-bootstrap)
- **STA+STA concurrency.** `WifiManager.isStaConcurrencyForLocalOnlyConnectionsSupported()` (API 31) reports whether the device can hold a WifiNetworkSpecifier local-only connection at the same time as its primary Wi-Fi — [WifiManager reference](https://developer.android.com/reference/android/net/wifi/WifiManager)
- **Not the capability for a camera AP.** `NET_CAPABILITY_LOCAL_NETWORK` (API 35) means a network where the device itself advertises addresses (tethering/hotspot, Thread border router), not a camera AP the device joins — [NetworkCapabilities reference](https://developer.android.com/reference/android/net/NetworkCapabilities)

**Callbacks and signal APIs**
- **`NetworkCallback.onLosing(network, maxMsToLive)`** fires when a network is about to be lost, typically because no requests are left for it. It is not guaranteed before `onLost`, and synchronous ConnectivityManager calls inside callbacks are race-prone — [NetworkCallback reference](https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback)
- **Location info is redacted by default.** `FLAG_INCLUDE_LOCATION_INFO` (API 31): from Android 12, callback objects omit location-sensitive info unless the app requests it, and the system then checks location permission — [NetworkCallback reference](https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback)
- **Where to get WifiInfo.** `WifiManager.getConnectionInfo()` is deprecated in API 31. WifiInfo now comes from `NetworkCapabilities.getTransportInfo()`, either in `onCapabilitiesChanged` or from `getNetworkCapabilities(Network)` — [WifiManager reference](https://developer.android.com/reference/android/net/wifi/WifiManager)
- **Useful WifiInfo methods** ([WifiInfo reference](https://developer.android.com/reference/android/net/wifi/WifiInfo)):
  - `getRssi()` (dBm, API 1; with MLO it returns the highest link) and `getLinkSpeed()` (API 1);
  - `getTxLinkSpeedMbps()` and `getRxLinkSpeedMbps()` (API 29);
  - `getMaxSupportedTxLinkSpeedMbps()` and `getWifiStandard()` (API 30), plus `getFrequency()` (API 21);
  - `getSSID()` returns `UNKNOWN_SSID` without sufficient permissions.
- **NetworkCapabilities signal fields.** `getSignalStrength()` (API 29) is bearer-specific; on Wi-Fi it is the RSSI. `getTransportInfo()` is API 29 — [NetworkCapabilities reference](https://developer.android.com/reference/android/net/NetworkCapabilities)

**Socket options from Java/Kotlin**
- **Public OsConstants cover little of TCP.** Among `TCP_*`, only `TCP_NODELAY` (API 21) and `TCP_USER_TIMEOUT` (API 26) are public. `SO_KEEPALIVE`, `SO_RCVBUF` and `IP_TOS` are public (API 21). `TCP_KEEPIDLE/KEEPINTVL/KEEPCNT/QUICKACK/INFO` are absent — [OsConstants reference](https://developer.android.com/reference/android/system/OsConstants). The public `android.system.Os` has `setsockoptInt` and `setsockoptTimeval` but no public `getsockoptInt` or raw `getsockopt`. Reading `TCP_INFO` therefore needs NDK/JNI `getsockopt(fd, IPPROTO_TCP, TCP_INFO)` — [Os reference](https://developer.android.com/reference/android/system/Os)
- **Raw Linux values to pass to `Os.setsockoptInt`:** TCP_KEEPIDLE 4, TCP_KEEPINTVL 5, TCP_KEEPCNT 6, TCP_INFO 11, TCP_QUICKACK 12, TCP_USER_TIMEOUT 18 — [include/uapi/linux/tcp.h](https://github.com/torvalds/linux/blob/master/include/uapi/linux/tcp.h). TCP_USER_TIMEOUT semantics are in [tcp(7)](https://man7.org/linux/man-pages/man7/tcp.7.html).

**DSCP / WMM**
- **DSCP → WMM.** On mac80211/cfg80211 drivers the uplink 802.11 user priority comes from DSCP: the 3 MSBs per RFC 8325, with the exceptions listed in section 1, or an AP-supplied QoS Map. UP then maps to an access category: 1–2 → BK, 0/3 → BE, 4–5 → VI, 6–7 → VO — [Linux net/wireless/util.c](https://github.com/torvalds/linux/blob/master/net/wireless/util.c) (the UP→AC step is the standard 802.11 mapping, not re-verified here). Java sets DSCP through `Socket.setTrafficClass()` / `IP_TOS`.

**Doze**
- **Doze suspends network access and ignores wake locks** for apps while the device is unplugged, stationary and screen-off. It ends when the screen turns on or the device moves or charges — [Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)

### Inferences
- **Low-latency lock [APP].** Acquire `WIFI_MODE_FULL_LOW_LATENCY` when the live-view screen starts and release it when it stops. Keep the screen on while streaming (`FLAG_KEEP_SCREEN_ON`), because the lock silently deactivates when the screen goes off.
  - Holding HIGH_PERF as well (the Moonlight pattern) adds a no-power-save fallback on API 29–33 when the lock conditions fail. On API 34+ it is just a second low-latency lock.
  - Benefit: with power save off, the AP doesn't buffer downlink frames for a dozing client until its next wake or beacon. In Wi-Fi Direct that AP is the camera; in router mode it is the router. That removes beacon/DTIM-scale delays (around 100 ms per beacon interval) whenever the client would otherwise nap between requests.
  - On many chips the HAL's low-latency mode also suppresses roam scans. That is vendor-specific.
  - Verify on the tablet with `adb shell dumpsys wifi`, which shows the WifiLockManager state and whether low-latency mode is supported.
- **Scans.**
  - Each connected scan sends the radio off-channel. At 20–160 s intervals this is a likely source of periodic sub-second hiccups.
  - The app must never call `startScan()` during live view, and should avoid location-based features that scan Wi-Fi.
  - With the low-latency lock, scanning "may be reduced" per the docs. Whether HyperOS honours that is unknown.
- **No-internet camera AP (Wi-Fi Direct).** `isNetworkSufficient()` is false for an unvalidated network unless no internet is "expected". So if a saved home network is in range, the framework may re-run network selection at the next connected scan, typically within 20–160 s of joining, and move the tablet off the camera mid-session. This is the most likely cause of "random" Wi-Fi Direct disconnects that are not radio-related. Mitigations:
  - (a) [USER] When Android asks, choose "Stay connected" or "don't ask again" for the camera AP; that is how "no internet expected" or acceptance gets recorded.
  - (b) [USER] Disable auto-connect for the home network during Direct sessions, or turn off vendor "Wi-Fi assistant / smart network switch" features (names vary).
  - (c) [APP] Join via `WifiNetworkSpecifier` with the exact SSID (and BSSID if known). The request keeps the camera network up as long as the app holds it, and the approval is cached after the first time. Costs: a system dialog at least once per AP, no auto-reconnect outside the app, and `bindProcessToNetwork` or socket-factory binding becomes mandatory because the network is not the default. On devices where `isStaConcurrencyForLocalOnlyConnectionsSupported()` is true, the home Wi-Fi can stay up at the same time. That also means multi-channel time-sharing (camera channel vs home channel), which adds latency, so prefer a single-STA setup for the best fps.
- **Always bind the PTP/IP and SSDP sockets to the camera's `Network` [APP].** Use `network.socketFactory.createSocket()`, or `network.bindSocket(s)` before `connect()`, and bind the DatagramSocket for SSDP too. On a phone with cellular, a no-internet Wi-Fi is typically not the default network, so unbound sockets to 192.168.122.1 may not route via Wi-Fi. A VPN can also capture traffic. In router mode binding is harmless and makes "network lost" explicit: bound sockets fail fast when that Network goes away. Treat `onLost` of that Network as end-of-session, then back off and reconnect, instead of waiting for TCP timeouts.
- **Signal UI [APP].**
  - Register a NetworkCallback for TRANSPORT_WIFI, or reuse the requestNetwork callback. Read `WifiInfo.rssi`, `txLinkSpeedMbps` and `rxLinkSpeedMbps` from `onCapabilitiesChanged`, or poll `getNetworkCapabilities(network)`. RSSI is only refreshed about every 3 s by the framework.
  - Useful thresholds mirror Android's own 2.4 GHz scorer: around −73 dBm is "low", around −83 dBm "bad".
  - Rx link speed is the client's receive PHY rate, the closest proxy for the camera→tablet MCS.
  - None of these need location permission; only SSID/BSSID do.
- **DSCP [APP, small benefit].** Setting `IP_TOS` to CS4 (0x80) or AF41 (0x88) on the live-view socket puts the tablet's ACKs and GetObject requests into AC_VI on cfg80211-based drivers. That helps only if the tablet's own uplink is busy. Downlink priority is decided by the camera, as AP or sender, and by the router's handling of the camera's DSCP [CAM/USER]. Qualcomm vendor drivers may classify differently.
- **Background use.** A viewer should stop streaming in the background. Background streaming would need a foreground service, and Doze would otherwise cut network access once the screen is off and the device is idle. With the screen on and the app in the foreground, Doze and App Standby are irrelevant.
- **TCP_INFO via JNI [APP].** Get the fd with `ParcelFileDescriptor.fromSocket(s).fd`, the same path already used for QUICKACK, call `getsockopt(TCP_INFO)` and log `tcpi_rcv_ooopack`, `tcpi_rcv_rtt`, `tcpi_last_data_recv` and `tcpi_options`. These diagnostics cost little.

### Gaps
- **No reliable source on HyperOS-specific behaviour.** That covers Xiaomi "Wi-Fi assistant", smart network switching, dual-Wi-Fi acceleration, their defaults on the Redmi Pad Pro, and whether HyperOS changes AOSP's connected-scan schedule or low-latency handling. Searches returned only generic per-network auto-connect guides — [TechBone](https://www.techbone.de/xiaomi/smartphone/wlan-automatisch-verbinden).
- **Tablet capabilities to check on the device:** whether the Redmi Pad Pro's Wi-Fi HAL supports `setLatencyMode` (shown as low-latency supported in `dumpsys wifi`), and whether it supports STA+STA local-only concurrency.
- **Lock coverage on a secondary STA.** It was not verified whether low-latency-lock effects apply to a WifiNetworkSpecifier connection held on a secondary STA interface.
- **Active-stream threshold.** The exact "active stream" packets-per-second threshold (`ScoringParams.getActiveTrafficPacketsPerSecond`) was not looked up. An active stream keeps a low-quality network "sufficient", but not a no-internet one.
- **Foreground-service type rules.** The foreground-service type requirements for Android 14+ (e.g., `connectedDevice`) were not verified in this session.
- **Qualcomm DSCP mapping.** Qualcomm's DSCP→UP classification in qcacld-family drivers was not checked.

## 4. macOS specifics: AWDL, background scans, CoreWLAN, Network.framework service class and TCP options, BSD options, ACK behaviour, App Nap/timers/QoS/Low Power Mode

### Takeaway
On macOS the dominant OS-level stall sources are radio-level, and there is no public API to stop them:
- **AWDL** keeps hopping off the infrastructure channel to its social channels (6 / 44 / 149). Reports range from rhythmic 10–90 ms spikes to 0.6–1 s spikes every few seconds, worst with AirDrop discoverable and other Apple devices nearby.
- **Location-driven Wi-Fi scans** have caused multi-second stalls about every 60 s in some setups.

Mitigation is user-side [USER]: AirDrop off, router channels aligned to 6/44/149, `awdl0 down` as a temporary hack, and trimming location consumers. App-side [APP]:
- prompt reading (macOS ACKs promptly when drained), with `disableAckStretching` as a low-cost extra;
- keepalive and `connectionDropTime` for dead-peer detection;
- viability/path handlers for UI;
- a ProcessInfo activity against App Nap;
- CoreWLAN RSSI/noise/txRate for a signal indicator (SSID needs Location).

### Cited Findings
**AWDL**
- **Root cause.** AWDL negotiates on three fixed social channels: 6 (2.4 GHz), 44 and 149 (5 GHz). The device must keep listening, advertising and negotiating there, so a Mac or iPad on another channel hops periodically. When Christoff Visser (IIJ) moved his AP to channel 6 or 44, the maximum ping RTT fell from 92.4 ms to 9.9 ms and stable throughput rose from 50 to 105 Mb/s. He reported no packet loss, only added latency, which returns when another Apple device appears — [Visser, "Apple Wireless Direct Link: Apple's Network Magic or Misery", APNIC 58 (Sep 2024) slides](https://conference.apnic.net/58/assets/files/APNZ606/awdl-apnic58_1725341937.pdf)
- **What disabling AWDL costs.** Per the same deck: AirPlay, AirDrop, Handoff, Sidecar, iPhone Mirroring, Universal Clipboard, Auto Unlock and Continuity Camera — [APNIC 58 slides](https://conference.apnic.net/58/assets/files/APNZ606/awdl-apnic58_1725341937.pdf)
- **RIPE 91 measurements (Oct 2025).** A Moonlight stream showed about 20 ms latency with 25 ms variance, and a Steam network test bounced between 3 ms and 90 ms. Visser grudgingly recommended matching Apple's channels — [The Register, 2025-10-23](https://www.theregister.com/2025/10/23/apple_airdrop_awdl_latency_research/)
- **AWDL timing model.** Nodes announce sequences of availability windows synchronised by an elected master. Outside those windows a node may tune its radio to another channel to talk to its AP. That time-sharing is the mechanism behind the spikes — [Stute, Kreitschmann, Hollick, "One Billion Apples' Secret Sauce", MobiCom 2018 (arXiv 1808.03156)](https://arxiv.org/abs/1808.03156)
- **The `awdl0 down` workaround.** One user saw 600–1000 ms spikes every 5–10 s. `sudo ifconfig awdl0 down` stopped them, but macOS re-enables AWDL after sleep and when a Wi-Fi scan starts. There is no permanent switch — [Apple Community thread 254004510](https://origin-discussions2-us-dr-prz.apple.com/thread/254004510). One MacRumors user reported that toggling awdl0 no longer helped on macOS 26.3.1 — [MacRumors forum](https://forums.macrumors.com/threads/2373916/)
- **macOS 27.2 developer beta (build 26B5086k, M4 Pro).** The reporter saw severe throughput collapse and latency spikes on several APs (Wi-Fi 7, 5/6 GHz, and an iPhone hotspot) that persisted in Safe Mode:
  - Turning AirDrop off in System Settings stopped the spikes even with AWDL still up.
  - `awdl0 down` gave 0% loss.
  - It is filed as FB24842970, with no Apple fix stated.
  - [Apple Developer Forums thread 847171](https://developer.apple.com/forums/thread/847171)
- **Tooling.** "AWDL Symphonizer" detects when Wi-Fi and AWDL sit on different channels and recommends router channels — [GitHub tbraun96/awdl-symphonizer](https://github.com/tbraun96/awdl-symphonizer)

**Location-driven Wi-Fi scans**
- **Ben Kuhn (2022).** Apple Maps requested a location scan every 60 s. That triggered a Wi-Fi scan, which stalled Wi-Fi traffic for a few seconds each time. The fix was revoking Maps' location access, or disabling Location Services entirely — [GIGAZINE summary](https://gigazine.net/gsc_news/en/20220516-apple-map-location-scan-latency); [Kuhn's tweet](https://twitter.com/benskuhn/status/1524791658381910023)
- **Older developer-forum reports.** One Developer Forums thread (2018 onwards) blames locationd scans about once a minute. It also names Qt apps whose bearer plugin polls Wi-Fi; workaround `QT_BEARER_POLL_TIMEOUT=-1` — [Apple Developer Forums thread 97805](https://developer.apple.com/forums/thread/97805) (details from search summary).

**CoreWLAN**
- **Signal values.** `CWInterface.rssiValue()`, `noiseMeasurement()` and `transmitRate()` are dynamic queries returning dBm, dBm and Mbps. Each returns 0 on error or when not associated (macOS 10.7+) — [rssiValue()](https://developer.apple.com/documentation/corewlan/cwinterface/rssivalue()), [noiseMeasurement()](https://developer.apple.com/documentation/corewlan/cwinterface/noisemeasurement()), [transmitRate()](https://developer.apple.com/documentation/corewlan/cwinterface/transmitrate())
- **SSID needs Location.** Since macOS 14, `ssid()` returns nil unless Location Services is on and the app is authorised; Apple DTS called the change intentional — [Developer Forums 732431](https://developer.apple.com/forums/thread/732431), [739945](https://developer.apple.com/forums/thread/739945). On Sequoia 15.1, scan results showed no SSIDs even when authorised — [769950](https://developer.apple.com/forums/thread/769950)

**Network.framework service class → WMM**
- **`NWParameters.ServiceClass` scope.** It prioritises *outgoing* packets and doesn't necessarily affect the priority of received packets. Apple advises using `.bestEffort` unless measurements show another class helps. `.interactiveVideo` doesn't suit bulk transfers. These are available from macOS 10.14 — [ServiceClass](https://developer.apple.com/documentation/network/nwparameters/serviceclass-swift.enum), [serviceClass property](https://developer.apple.com/documentation/network/nwparameters/serviceclass-swift.property), [interactiveVideo](https://developer.apple.com/documentation/network/nwparameters/serviceclass-swift.enum/interactivevideo)
- **XNU mapping and marking** ([xnu in_tclass.c](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/netinet/in_tclass.c), [xnu sys/socket.h](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/sys/socket.h)):
  - Service type → traffic class: SIG→SO_TC_VI, VI→SO_TC_VI, VO→SO_TC_VO, RD→SO_TC_RD.
  - DSCP when marking applies: VI→AF41, VO→EF, RD→AF21, and SIG→CS5 (RFC 4594 map) or CS3 (Fastlane map).
  - L3 DSCP is written only on interfaces with QoS marking enabled (Fastlane/RFC 4594 domains). Elsewhere marking is layer 2 only (Wi-Fi WMM). `SO_NETSVC_MARKING_LEVEL` (0x1119) reports which applies.
- **Use the service type, not IP_TOS.** XNU's legacy DSCP→Wi-Fi AC table puts raw `IP_TOS` values AF1x/AF2x/CS2 into BK and CS4/AF4x/CS5/EF into VI. Apple's comment tells apps to use the network service type socket option, not IP_TOS/IPV6_TCLASS, to select L2 QoS — [xnu in_tclass.c](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/netinet/in_tclass.c)

**TCP options and connection handlers**
- **NWProtocolTCP.Options (macOS 10.14+):**
  - `disableAckStretching` disables TCP ACK stretching.
  - `connectionDropTime` is the timeout in seconds for retransmission attempts.
  - `retransmitFinDrop` drops the connection when a FIN goes unacknowledged.
  - Also: `keepaliveIdle`, `enableKeepalive`, `persistTimeout` (RFC 6429), `connectionTimeout` (handshake) and `noDelay`.
  - [disableAckStretching](https://developer.apple.com/documentation/network/nwprotocoltcp/options/disableackstretching), [connectionDropTime](https://developer.apple.com/documentation/network/nwprotocoltcp/options/connectiondroptime), [retransmitFinDrop](https://developer.apple.com/documentation/network/nwprotocoltcp/options/retransmitfindrop), [keepaliveIdle](https://developer.apple.com/documentation/network/nwprotocoltcp/options/keepaliveidle), [persistTimeout](https://developer.apple.com/documentation/network/nwprotocoltcp/options/persisttimeout)
- **BSD equivalents in Darwin `netinet/tcp.h`:**
  - `TCP_KEEPALIVE` 0x10 (idle), `TCP_CONNECTIONTIMEOUT` 0x20, `TCP_RXT_CONNDROPTIME` 0x80 (stop retransmitting and drop after N s).
  - `TCP_RXT_FINDROP` 0x100 (drop after 3 FIN retransmissions), `TCP_KEEPINTVL` 0x101, `TCP_KEEPCNT` 0x102.
  - `TCP_SENDMOREACKS` 0x103, `TCP_CONNECTION_INFO` 0x106, `TCP_NOTSENT_LOWAT` 0x201, plus `SO_NET_SERVICE_TYPE` 0x1116.
  - [xnu bsd/netinet/tcp.h](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/netinet/tcp.h), [xnu sys/socket.h](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/sys/socket.h)
- **macOS ACK mechanics** (details in section 2): delayed ACK mode 3 with a 100–200 ms timer; immediate ACK when the app drains the buffer and PSH was set; 16 forced ACKs after idle or loss; TCP_SENDMOREACKS → TF_QUICKACK — [tcp_cc.c](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/netinet/tcp_cc.c), [tcp_output.c](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/netinet/tcp_output.c), [tcp_timer.c](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/netinet/tcp_timer.c)
- **Connection state handlers** (macOS 10.14+):
  - `viabilityUpdateHandler` reports when data can or cannot currently be sent and received.
  - `betterPathUpdateHandler` reports that a preferable path exists; migrate to a new connection and then cancel the old one.
  - Errors in `.waiting` are not fatal.
  - [viabilityUpdateHandler](https://developer.apple.com/documentation/network/nwconnection/viabilityupdatehandler), [betterPathUpdateHandler](https://developer.apple.com/documentation/network/nwconnection/betterpathupdatehandler), [State.waiting](https://developer.apple.com/documentation/network/nwconnection/state-swift.enum/waiting(_:))

**App Nap and timers**
- **App Nap** lowers process priority and throttles both timers and I/O. An app becomes eligible when it hasn't recently updated a visible window and holds no IOKit power-management or NSProcessInfo assertions — [Apple Energy Efficiency Guide: App Nap](https://developer.apple.com/library/archive/documentation/Performance/Conceptual/power_efficiency_guidelines_osx/AppNap.html)
- **Activity options.** `ProcessInfo.ActivityOptions.latencyCritical` asks for the highest timer and I/O precision; Apple says very few apps need it. `.userInitiated` marks user-requested work — [latencyCritical](https://developer.apple.com/documentation/foundation/processinfo/activityoptions/latencycritical), [userInitiated](https://developer.apple.com/documentation/foundation/processinfo/activityoptions/userinitiated)

### Inferences
- **AWDL's size for this stream.** A 10–90 ms excursion every ~1–2 s won't break TCP but makes 1–3 frames late at 25 fps (40 ms per frame). Pipelining depth 2 absorbs about one frame of jitter.
  - The reported 0.6–1 s spikes, or AirDrop-scan storms like the 27.2-beta report, would visibly freeze live view and can exceed the camera's RTO. That causes spurious retransmissions and a cwnd reset on the camera [CAM].
  - Because AWDL adds latency without loss (Visser), it mostly appears as periodic fps dips, not disconnects.
- **Channel strategy by topology [USER].**
  - Router mode: set the router's 2.4 GHz to channel 6. If the Mac is on 5 GHz, use channel 44 or 149 there. With the Mac on 5 GHz and the camera on 2.4 GHz, the two hops also don't share airtime (section 1).
  - Wi-Fi Direct: the camera picks its soft-AP channel [CAM]. If it is 1 or 11, the Mac must hop to channel 6 for AWDL. The app can read the channel from `CWInterface.wlanChannel()` and warn when it is not 6.
- **User mitigations, roughly in order of payoff/cost [USER]:**
  1. Set AirDrop to off or "No One" during sessions, and quit iPhone Mirroring and Sidecar.
  2. Align router channels to 6/44/149.
  3. Unpair or disconnect AirPods and BT audio from the Mac in Wi-Fi Direct mode (2.4 GHz coexistence).
  4. Revoke Wi-Fi-scan-triggering location consumers (Maps, Qt apps), or turn off location-based Wi-Fi system services. The exact System Settings label in macOS 27 is unverified.
  5. As a last resort, `sudo ifconfig awdl0 down` (temporary; breaks Continuity).
- **App-side detection.** There is no public API for AWDL state.
  - The app can check whether `awdl0` is UP/RUNNING via `getifaddrs()`/`SIOCGIFFLAGS` (interface flags are readable without root) and correlate periodic stall timing.
  - Show a hint such as "AirDrop/AWDL active, may cause stutter" instead of trying to control it.
  - Never call `CWInterface.scanForNetworks` during live view, since that would itself trigger the off-channel scan.
- **TCP options for NWConnection [APP]:**
  - Keep `noDelay = true`, because the requests are tiny.
  - `disableAckStretching = true` is cheap insurance.
  - Set `enableKeepalive = true` with keepaliveIdle 3–5 s, keepaliveInterval 1–2 s and keepaliveCount 4–6; `connectionDropTime` 10–20 s; and `retransmitFinDrop = true` (local cleanup only).
  - Keep a receive always outstanding on a high-QoS queue (`.userInteractive`/`.userInitiated`) so the buffer drains immediately. That is what triggers XNU's immediate ACK.
  - These numbers are proposals, not measured.
- **serviceClass [APP, small effect].** `.interactiveVideo` (or `.responsiveData`) on the command/data connection only changes WMM marking of the Mac's uplink requests and ACKs. On home or camera networks this is L2 only. It does not prioritise the camera's downlink. Apple advises measuring first, and bulk photo downloads share that connection, but their uplink is tiny. Never set raw IP_TOS on macOS: XNU puts AF2x into the background class.
- **Path and viability [APP].**
  - Treat `viability == false` as "link weak/unusable right now". Show it in the UI and don't cancel; the connection may recover after the camera's retransmissions.
  - Use `NWPathMonitor(requiredInterfaceType: .wifi)` and NWConnection `.waiting`/`.failed` to detect the Wi-Fi interface actually going away. Then cancel and use the existing 2/4/8/10 s back-off.
  - `betterPathUpdateHandler` is mostly irrelevant for a LAN camera; it is aimed at Wi-Fi-vs-cellular handover.
- **App Nap and timers [APP].** While live view runs, hold `beginActivity(options: [.userInitiated], reason: "Live view")` so App Nap's timer and I/O throttling cannot kick in when the window is occluded or minimised. Watchdog timers and frame pacing depend on timer precision. Use `.latencyCritical` only if measurements show timer jitter.
- **CoreWLAN signal UI [APP].** Poll `rssiValue()`, `noiseMeasurement()` and `transmitRate()` at about 1 Hz; SNR = RSSI − noise. `transmitRate()` is the Mac's own transmit PHY rate toward the AP or camera, only a proxy for the downlink MCS. These fields don't appear to need Location authorisation (only SSID/BSSID are documented as gated), but verify on macOS 27.

### Gaps
- **No documented API controls AWDL.** No source says whether Game Mode or anything else suppresses AWDL on macOS 26/27. No official Apple statement on AWDL timing or channel policy was found.
- **macOS 27 specifics.** Nothing was found on macOS 27 changes to Wi-Fi scanning, AWDL or Location Services labels; the 27.2-beta forum report is the only macOS 27 datapoint.
- **No public macOS API** was found to disable Wi-Fi power save, or to see its state.
- **Low Power Mode.** No source was found on how macOS Low Power Mode affects Wi-Fi, networking threads or decode.
- **Undocumented mapping.** Whether `disableAckStretching` maps exactly to `TCP_SENDMOREACKS`/`TF_QUICKACK` is undocumented.
- **Location permission for RSSI on recent macOS.** It was not directly confirmed whether `rssiValue/noiseMeasurement/transmitRate` need Location authorisation on macOS 14–27.

## 5. Practical community guidance from low-latency Wi-Fi streaming (Moonlight, Steam Link, Parsec, Sunshine, IP-camera/FPV viewers)

### Takeaway
Streaming communities converge on a few rules:
- put the sender on a wire and the client on 5 GHz, and avoid 2.4 GHz;
- on Android, hold Wi-Fi performance/low-latency locks for the whole stream;
- on Apple devices, align AP channels with AWDL's social channels or turn off AirDrop/AWDL during sessions;
- disable vendor "smart network switch" features for local-only devices;
- revoke location consumers that trigger periodic Wi-Fi scans on Macs.

ZV-E10 Viewer cannot wire the camera, so in router mode the closest equivalent is a client on 5 GHz or Ethernet with the router near the camera.

### Cited Findings
- **Moonlight's setup guide** strongly recommends 5 GHz Wi-Fi 5/6 for the client, highly recommends Ethernet for the host, and calls for a mid-to-high-end router with a strong link to the client. It suggests lowering resolution and bitrate on poor (mobile) links — [Moonlight Setup Guide](https://github.com/moonlight-stream/moonlight-docs/wiki/Setup-Guide)
- **Moonlight Android** holds `WIFI_MODE_FULL_HIGH_PERF` plus `WIFI_MODE_FULL_LOW_LATENCY` (API 29+) for the whole streaming activity and tolerates a `SecurityException` on some Samsung models — [moonlight-android Game.java](https://github.com/moonlight-stream/moonlight-android/blob/master/app/src/main/java/com/limelight/Game.java)
- **Moonlight and Steam Link stutter traced to AWDL.** Visser's stutter appeared with both apps and with plain ping from a MacBook. A wired client fixed it; so did moving the AP to channel 6 or 44 — [APNIC 58 slides](https://conference.apnic.net/58/assets/files/APNZ606/awdl-apnic58_1725341937.pdf), [The Register](https://www.theregister.com/2025/10/23/apple_airdrop_awdl_latency_research/)
- **Not every Mac stutter is AWDL.** In a Moonlight-qt issue about Mac-only stutter, ping showed no loss during the stutter and the reporter suspected background macOS activity — [moonlight-qt issue #159](https://github.com/moonlight-stream/moonlight-qt/issues/159) (from search summary, not opened).
- **Local-device makers advise disabling auto-switching.** They tell Android users to turn off features like Smart Network Switch, Auto Network Switch, Avoid bad Wi-Fi and Adaptive Wi-Fi when a phone talks to local devices — [Blink support](https://support.blinkforhome.com/en_GB/android-avoid-poor-wifi-connections)
- **Mac users scripting around AWDL:**
  - They detect AWDL/infrastructure channel mismatch — [awdl-symphonizer](https://github.com/tbraun96/awdl-symphonizer).
  - They re-down awdl0 whenever it comes back — [TidBITS Talk](https://talk.tidbits.com/t/wi-fi-latency-spikes-in-sonoma/26636) (from search summary).
  - They revoke Maps' location access to stop 60 s scan stalls — [GIGAZINE](https://gigazine.net/gsc_news/en/20220516-apple-map-location-scan-latency).

### Inferences
**A practical checklist for ZV-E10 Viewer users [USER]**
- Router mode is the most robust topology when the client is on 5 GHz or Ethernet and the router's 2.4 GHz radio is configured as follows:
  - placed near the camera;
  - 20 MHz width on channel 6, which suits AWDL;
  - optionally with legacy 802.11b rates disabled. The camera is b/g/n, and 1–11 Mbit/s DSSS rates cannot carry 25 fps anyway, though this trades away range.
- On a Mac, turn off AirDrop during sessions and keep USB 3 hubs away from the hinge.
- On the tablet:
  - In Wi-Fi Direct, accept "stay connected without internet" for the camera AP.
  - Disable auto-switch / Wi-Fi-assistant features.
  - Avoid Bluetooth audio on the same device.

**Matching app behaviour [APP]**
- low-latency Wi-Fi lock plus screen-on (Android);
- sockets bound to the camera Network;
- per-read QUICKACK (Android), and prompt-read plus `disableAckStretching` (macOS);
- pipelining depth 2;
- keepalive and user-timeout/drop-time in the 10–20 s range;
- a "weak signal" indicator driven by RSSI/link speed and frame-gap watchdogs rather than tearing down on short stalls;
- an AWDL/AirDrop hint on macOS.

These are consistent with the community practice above, but none was measured on this camera.

### Gaps
- **Not fetched.** Official Parsec, Steam Link and Sunshine documentation, and DJI/FPV or IP-camera viewer guidance, were not retrieved. Only Moonlight's docs/code and the AWDL research above were verified.
- **No numbers for the embedded case.** No community data was found on 2.4 GHz-only embedded senders (cameras, drones) with TCP pull-based live view. Most streaming guidance assumes UDP/RTP senders with their own FEC and rate control, which a PTP/IP TCP stream does not have.
