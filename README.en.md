<p align="center"><a href="README.md">Русский</a> · <b>English</b></p>

<h1 align="center">Sony Cam Viewer</h1>

<p align="center">
  <b>Live video from a Sony camera and a remote control for it — on an Android tablet and on a Mac.</b><br>
  Over a USB-C cable or Wi-Fi. Open it and the picture is there; the main settings are one tap away.
</p>

<p align="center">
  <a href="https://github.com/Scryptor/SonyCamViewer/releases/latest"><img alt="Latest version" src="https://img.shields.io/github/v/release/Scryptor/SonyCamViewer?label=version&color=2f81f7"></a>
  <img alt="Android 10+" src="https://img.shields.io/badge/Android-10%2B-3ddc84?logo=android&logoColor=white">
  <img alt="macOS 14+" src="https://img.shields.io/badge/macOS-14%2B%20%C2%B7%20Apple%20Silicon-black?logo=apple">
</p>

<p align="center">
  <a href="https://github.com/Scryptor/SonyCamViewer/releases/latest/download/Sony-Cam-Viewer.apk"><b>⬇ Download for Android (APK)</b></a>
  &nbsp;·&nbsp;
  <a href="https://github.com/Scryptor/SonyCamViewer/releases/latest/download/Sony-Cam-Viewer.dmg"><b>⬇ Download for Mac (DMG)</b></a>
</p>

![Sony Cam Viewer on a tablet: four cameras, three linked and recording; histogram, RGB waveform, frame overlay panel, Monitor, zoom and touch focus, value bar](docs/screenshots/0-overview.jpg)

A no-frills replacement for Imaging Edge Desktop (Remote) — for Sony cameras with PC Remote mode, see [Which cameras will work](#which-cameras-will-work). Free, no ads, no sign-up.

> [!IMPORTANT]
> **The app is young.** It hasn't yet been run for long in the field — on real shoots — so teething problems are possible. Your feedback is very welcome: what works, what doesn't, what's missing. Write in [Issues](https://github.com/Scryptor/SonyCamViewer/issues) — we'll fix everything. How to report a bug — see [Feedback](#feedback).

> [!NOTE]
> - **The app's interface is in Russian for now.** The names of buttons and menus below are given in English translation.
> - **There are layouts for an Android tablet and for a Mac so far.** The app will run on a phone, but the layout is designed for a big screen; a phone version is coming soon.

## Highlights

- 📺 **Full-screen live video** at up to 25 frames per second — over USB-C, over Wi-Fi through a router, or directly (Wi-Fi Direct). The app rides out a weak signal without dropping the connection.
- 🎛 **Settings just like on the camera's display**: shutter speed, aperture, ISO, white balance, exposure compensation, focus, recording mode. A tap opens the Fn menu over the frame, plus "All settings" by group.
- 🔴 **Video and photo capture** with shutter release and autofocus. REC with a timer and a red border around the frame; shots go to the tablet, the Mac or the card. On Android the shutter and recording can be mapped to the volume buttons.
- 🎥 **Up to 4 cameras at once**: the main one on the big screen, the others as live tiles. Switching is instant, even while recording.
- 🔗 **A group, or every camera on its own**: cameras linked to the main one follow its settings, presets and recording — one button for all; unlinked ones are set up separately.
- ⭐ **Presets**, separately for photo and video — your settings in one tap.
- 📊 **Histogram, waveform and vectorscope**, digital magnification up to 8× for focusing, a shot gallery for checking sharpness.
- 🖥 **Like on monitors**: zebra, false color, peaking (works at 8× magnification too), waveform and RGB parade, vectorscope, color picker (luma and color at a point in the frame), grid, center mark, 9:16 / 4:5 / 1:1 / 2.39 frame lines, safe area.
- 🎨 **LUTs for S-Log and HLG preview**: the flat log picture gets familiar contrast and color; "Auto" picks the LUT from the camera's profile, and you can add your own `.cube`. **Mirror and flip** the picture — for filming yourself or a camera on a rig. All on screen only: recording is not affected.
- 👻 **Reference frame overlay** — to repeat a scene: a previous frame, a shot or any image file over the live video. Opacity on a slider; clicking the thumbnail hides and brings back the reference: before / after in one tap.
- 🎯 **Focus and zoom from the screen**: tap-to-focus on the frame with the camera's own focus frames, power zoom (T / W), a manual focus scale with A and B marks — remember a focus point and pull to it smoothly over 0–8 seconds.
- 🧩 No drivers, no root, no third-party libraries. Open source.

## Getting started

1. **Download** the app: [APK for Android](https://github.com/Scryptor/SonyCamViewer/releases/latest/download/Sony-Cam-Viewer.apk) or [DMG for Mac](https://github.com/Scryptor/SonyCamViewer/releases/latest/download/Sony-Cam-Viewer.dmg) — details in [Installing on Mac](#installing-on-mac) and [on Android](#installing-on-android).
2. **On the camera**, enable PC Remote once: MENU → Network → PC Remote Function → PC Remote: **On** — see [Camera setup](#camera-setup).
3. **Connect** the camera with a USB-C cable — the picture appears in a few seconds. Or [over Wi-Fi](#connecting-over-wi-fi) — no cable.

<p align="center">
  <a href="#download">Download</a> ·
  <a href="#which-cameras-will-work">Cameras</a> ·
  <a href="#installing-on-android">Android</a> ·
  <a href="#installing-on-mac">Mac</a> ·
  <a href="#camera-setup">Camera setup</a> ·
  <a href="#connecting-over-wi-fi">Wi-Fi</a> ·
  <a href="#controls">Controls</a> ·
  <a href="#limitations">Limitations</a> ·
  <a href="#feedback">Feedback</a> ·
  <a href="#building-from-source">Building</a>
</p>

## Download

| | Mac | Android |
|---|---|---|
| **File** | [Sony-Cam-Viewer.dmg](https://github.com/Scryptor/SonyCamViewer/releases/latest/download/Sony-Cam-Viewer.dmg) | [Sony-Cam-Viewer.apk](https://github.com/Scryptor/SonyCamViewer/releases/latest/download/Sony-Cam-Viewer.apk) |
| **Requirements** | Apple Silicon Mac, macOS 14 or later | Android 10 or later, a tablet with USB OTG (phone layout — soon) |
| **Camera connects** | with a USB-C cable or over Wi-Fi | with a USB-C cable or over Wi-Fi |
| **How to install** | [Installing on Mac](#installing-on-mac) | [Installing on Android](#installing-on-android) |

All versions are on the [Releases](https://github.com/Scryptor/SonyCamViewer/releases) page.

**Documentation (in Russian):** [user guide](docs/user-guide.md) · [camera protocol](docs/protocol.md) · [code structure](docs/architecture.md) · [automation API](docs/automation-api.md)

## What it looks like

| Mac | Android tablet |
|---|---|
| ![Mac: main screen — histogram, value bar, zoom, mode, touch focus and record on the right](docs/screenshots/1-main.png) | ![Android: main screen — histogram, value bar, zoom, mode, touch focus and record on the right](docs/screenshots/6-android.png) |

| Monitor on the Mac: peaking, zebra, thirds, waveform | Monitor on the tablet: 9:16 frame, safe area, zebra, peaking |
|---|---|
| ![Mac: the 14-tile Monitor menu, peaking, 95 % zebra in the sun, thirds and waveform](docs/screenshots/13-mac-monitor.png) | ![Android: the 14-tile Monitor menu, 9:16 frame with safe area, zebra and peaking, waveform](docs/screenshots/15-android-monitor.png) |

| LUT on the Mac: S-Log3 with "Auto", vectorscope, color picker | LUT on the tablet: S-Log3 with "Auto", vectorscope, color picker |
|---|---|
| ![Mac: S-Log3 frame with the "Auto: S-Log3 Cine" LUT, vectorscope top left, color picker on the water — 39 %, 18 % gray, LUT menu open](docs/screenshots/19-mac-lut.png) | ![Android: S-Log3 frame with the "Auto" LUT, vectorscope, color picker on the water — 39 %, 18 % gray, histogram](docs/screenshots/20-android-lut.png) |

The camera shoots in S-Log3 (profile PP8), while the screen shows a picture with familiar contrast and color: "Auto" picked the LUT from the profile by itself. Without the LUT the same scene would look flat and gray. The LUT, vectorscope and color picker change only the screen — the recording stays in log. Details in the [guide](docs/user-guide.md#lut-превью-s-log-и-hlg).

| Focus and zoom on the Mac: focus scale, A and B marks, RGB parade | Focus marks on the tablet: scale in MF, A and B, pull time |
|---|---|
| ![Mac: zoomed to tele, AF point frame, manual focus scale with A and B marks, "⏱ 2 s" and RGB parade](docs/screenshots/14-mac-lens.png) | ![Android: zoomed to tele, manual focus scale with A and B marks, "⏱ 2 s" pull time button](docs/screenshots/16-android-lens.png) |

| Frame overlay on the Mac | Frame overlay on the tablet |
|---|---|
| ![Mac: 50 % overlay — a reference from the wide shot over the frame after zooming, the overlay panel top right](docs/screenshots/17-mac-overlay.png) | ![Android: 50 % overlay and the overlay panel — reference thumbnail, slider, "New frame", "From file…"](docs/screenshots/18-android-overlay.png) |

Frame overlay is for repeating a scene: the reference (a previous frame, a shot or any image file) lies semi-transparent over the live video, and wherever the pictures differ, everything doubles. The overlay panel has an opacity slider, and clicking the thumbnail hides and brings back the reference. Details in the [guide](docs/user-guide.md#наложение-кадра-повторить-сцену).

| Fn menu on the Mac | Fn menu on the tablet |
|---|---|
| ![Mac: Fn menu with white balance — color temperature and A–B / G–M shift](docs/screenshots/2-fn.png) | ![Android: Fn menu with white balance — AWB, color temperature slider and A–B / G–M shift](docs/screenshots/7-android-fn.png) |

| Camera strip and group on the Mac | Four cameras recording on the tablet |
|---|---|
| ![Mac: three cameras connected, two linked to the main one](docs/screenshots/9-mac-strip.png) | ![Android: four cameras, three linked and recording at the same time](docs/screenshots/12-android-group-rec.png) |

<details>
<summary><b>More screenshots</b>: waiting screen, all settings, photo mode, shot review, presets window</summary>

| Waiting screen on the Mac | Waiting screen on the tablet |
|---|---|
| ![Mac: waiting screen on first launch](docs/screenshots/10-mac-waiting.png) | ![Android: waiting screen on first launch](docs/screenshots/11-android-waiting.png) |

| All settings | Photo mode |
|---|---|
| ![Mac: all settings, the Exposure group](docs/screenshots/3-all.png) | ![Mac: photo mode, shutter and a shot in the strip](docs/screenshots/4-photo.png) |

| Review after shooting | Presets window on the tablet |
|---|---|
| ![Mac: shot review after shooting](docs/screenshots/5-review.png) | ![Android: Presets window — list on the left, each preset's values and what it changes on the right](docs/screenshots/8-android-presets.png) |

</details>

The screenshots show the virtual camera from `scripts/fake_camera.py`. Its viewfinder shows a mountain lake drawn by code, not footage from a real camera.

## Features

The same on the Mac and on Android:

<details>
<summary><b>All features in detail</b> — click to expand</summary>

- **Finds the camera by itself** — over a USB cable or over Wi-Fi on your home network — and shows full-screen live video at up to 25 frames per second. Unplug the cable and it connects over Wi-Fi or waits for the camera. See [Connecting over Wi-Fi](#connecting-over-wi-fi).
- **Multiple cameras.**
  - The app remembers every camera it has connected to and sees the ones on a cable or on the network.
  - The camera list opens by clicking the camera name in the status bar; it's also on the waiting screen. There you can connect another camera, pair a new one, give a camera your own name or forget it.
  - Several Wi-Fi cameras stay connected at once. The main one is on the big screen, the others are live tiles in the strip on the left.
  - Clicking a tile makes that camera the main one instantly, without reconnecting, including while recording. The tiles don't jump around; you reorder them by dragging.
  - While the chain icons on the tiles are off, every camera is on its own: each has its own settings; click a tile and you're setting up that camera.
  - The chain icon on a tile links the camera to the main one, forming a group. Group cameras follow the main one's settings, presets and recording — one button for all cameras.
  - If the main camera isn't in the group, the group waits: its cameras follow no one until a group camera becomes the main one again.
  - Linking doesn't overwrite what's already set on the camera. To set up one group camera separately: unlink, adjust, link again. More in the [guide](docs/user-guide.md#группа-связанные-камеры).
  - After a restart — the same main camera, the same strip, the same order and the same links.
- **Waiting screen** — while there's no camera.
  - The focus frame says what the app is doing and what you should do. Right below it is the camera list with "Connect" and "Pair".
  - The steps for connecting by cable and over Wi-Fi are expanded on first launch and collapsed afterwards.
  - If Wi-Fi is turned off in the system settings, "Turn on Wi-Fi" is right on this screen.
- **Debug** — ⌥⌘D on the Mac or a long press on the status bar on the tablet: a tabbed panel with everything the app knows about the connection (signal, camera response, stream, drops and recoveries), the preview, the camera and all of its properties, plus an event log; "Copy" gives it all as text. For newer-generation cameras the "Camera" tab also shows the camera's code version, the label lists it sent and the lens tables.
- **Status bar**: the camera and connection type, frame counter, battery. It's visible together with the other controls, and the button with a dot and Wi-Fi at the top left pins it — then it stays on screen even when the controls are hidden. Tapping the camera name (for example "ZV-E10 · Wi-Fi") shows the camera list, firmware, camera and device addresses and signal strength; the frame counter always shows the preview resolution ("23 fps · 576p"), and tapping it opens the preview quality choice — "Auto", "1024×576" or "640×360" — and a summary: that these are live view frames, not the recording, and the longest gap between frames.
- **Value bar at the bottom**, like on the camera's display: shutter speed, aperture, ISO, white balance, exposure compensation, focus; in video mode — the recording mode ("4K 25p 100M"), in photo mode — the image format. Tapping a value opens the Fn menu over the frame.
- **Photo, video and S&Q** (slow and quick motion) — the switch on the right, three icons in a capsule, does the same as the MODE button on the camera. In S&Q the status bar shows the frame rate and slow-down, for example "100 fps · ×4 slow". The camera won't let you change the frame rate itself (1–100 fps) over the cable: it's in the camera menu.
- **Recording mode in one choice**: format, frame rate and bitrate together — 4K 25p 100M, HD 100p 100M, HD 50p 50M and so on. 100p exists only in HD; the app switches the video format by itself.
- **Fn menu**: a 2×6 grid like on the camera — 11 settings and a way into all the others. White balance has color temperature and an A–B / G–M shift pad. The temperature slider works in any mode: move it and the camera switches to manual temperature.
- **"All settings"** by group: exposure, focus, color and picture, video, photo, camera.
- **Video recording** with a button. While recording there's a REC mark with a timer and a red border around the picture, like on monitors; the border can be turned off, made thicker or thinner, dimmer, and blinking.
- **Feedback on the capture buttons.** Your finger covers the button, so the feedback is around it and on the frame: a ring and a ripple, a darkening at the moment of release, a still frame flying into the strip. While the camera is busy there's an arc on the button and a caption to its left: "Focusing…", "Downloading 42 %", "Starting recording…". If recording didn't start, the reason shows there for a few seconds: "Camera busy — recording didn't start", "Camera started recording and stopped right away". On Android — vibration too, if the device has a motor.
- **Volume buttons** (Android only): shutter in photo mode, start and stop recording in video and S&Q — just like pressing the on-screen button, with the same feedback. Turned on in ⚙, off by default; they work on the frame screen and, while on, don't change the volume. The "Camera" button on Bluetooth phone remotes works the same way.
- **Photo mode.** The "Photo / Video / S&Q" switch on the right. Shutter with autofocus. Shots are saved to the Mac or tablet, to the card, or both. With RAW+JPEG both files are downloaded.
- **Review after shooting**: the shot is shown for 1–10 seconds, can be turned off. Your choice — the downloaded file or the frame from the camera.
- **Still frame from video**; recording isn't interrupted.
- **Recent shots strip and gallery**: swiping through (swipe, arrows), a strip of all the session's shots, zooming into a shot with the full resolution loaded in — to check sharpness. A bad shot — "Delete": the files go to the Mac's or tablet's trash, and for another 6 seconds it can be brought back with "Undo". The copy on the camera's card stays.
- **Digital live view magnification** with two fingers (on the Mac — trackpad pinch and ⌘= / ⌘− / ⌘0), up to 8×, with a mini map, the factor and a reset button. The camera doesn't know about it. The visible part is resampled bicubically, and the Wi-Fi preview is kept at full size for as long as possible.
- **Histogram** — with its own button; it can be dragged anywhere and doesn't hide with the other controls. The scale is divided into thirds; under the graph are the shares of shadows, midtones and highlights, crushed blacks, average brightness and clipping. Clipping and crushed blacks from 1 % are highlighted in yellow.
- **Waveform** — with its own button next to the histogram, dragged the same way: luma by frame column or RGB parade, lines at 0, 50 and 100 % of the preview's white.
- **Monitor** — the button at the top left (M on the Mac too) opens tiles at the bottom, like the Fn menu:
  - zebra 100 %, 95 % or 70 % for skin;
  - false color with a legend: crushed blacks, shadows, 18 % gray, skin, near white, clipping;
  - peaking — highlighting of sharp edges, four colors and three levels; with digital magnification it's computed on the magnified picture, and at 8× an edge is a thin line;
  - LUT — S-Log and HLG preview with familiar contrast and color: "Auto" picks the LUT from the camera's picture profile (S-Log2, S-Log3 with two gamuts, HLG); you can pick one by hand or add your own `.cube` file (up to 12; on the Mac you can just drop it on the window). The histogram, waveform and the other aids are computed on the LUT picture by default; the "Aids on the LUT picture" switch returns them to the camera signal;
  - vectorscope — the frame's colors on a circle: hue by direction, saturation by distance from the center; 75 % color bar targets and the skin tone line; tapping the panel switches to ×2 scale to make out pale log colors;
  - color picker — luma in percent, R, G, B and the false color zone under the mark (averaged over a 5×5 pixel square); while it's on, tapping the frame places it instead of focus, and the mark can be dragged;
  - grid (thirds or squares), center mark, 9:16, 4:5, 1:1, 4:3 and 2.39:1 frame lines with darkening outside, 90 or 80 % safe area;
  - frame overlay — a semi-transparent reference over the live picture for repeating a scene: finishing a shoot another day, repeating an angle with a second camera, making a before / after. The reference is the current frame, a shot from the gallery or any image file (on the Mac you can just drop it on the window). The overlay panel has the reference thumbnail (clicking hides and brings it back, on the Mac the O key too), an opacity slider from 5 to 100 %, and changing the reference. The reference is remembered between launches and magnifies together with the frame;
  - picture — mirror, flip upside down or both: filming yourself, a camera on a rig or a gimbal. Touch focus, the camera's focus frames and the overlay reference move together with the picture, so a tap lands where your finger points.

  Everything is computed on the preview and only on screen: recording isn't affected, and shots, still frames, the overlay reference and the camera itself stay as they are.
- **Touch focus**: turn on the button on the right and tapping the frame puts focus there (in the "Spot" and "Zone" areas — the AF point, in the others — like tapping the camera's screen: focus or tracking; while the color picker is on, a tap places it instead of focus). The camera's own focus frames are drawn over the frame and are always visible: turn the button off and tracking that has started continues, its frame stays visible, and taps don't disturb it; to cancel — "Stop tracking". When you turn it on, a hint explains how it works and what a tap will do right now, with a "Don't show again" checkbox; to see it again — right-click (Mac) or long-press (tablet) the button.
- **Power zoom**: "T" and "W" on the right — hold and the camera zooms; a power zoom lens (the PZ 16-50 kit lens) or the camera's Clear Image Zoom. The zoom position and scale are shown.
- **Focus scale** in manual focus: nearer and farther in steps of 1, 3, 7 or by dragging, like a ring, with a mark for the focus position. If the camera reports lens data, distances are labeled under the scale ("0.5 m", "1 m", "∞"), and the current one is next to the position mark.
- **Focus marks A and B** under the scale. "A+" remembers where focus is now; "A" smoothly pulls focus there over 0, 1, 2, 4 or 8 seconds — for example, from one subject to another without a jerk. The "⏱ 2 s" button switches the time; right-click (Mac) or long-press (tablet) on a mark gives "Remember here" and "Erase". A pull is stopped by tapping the moving mark or by any manual focus movement. When the marks first appear there's a "Focus marks" card with a "Don't show again" checkbox. Each camera has its own marks, and they live while the app is running.
- **Presets** — separate for photo and for video: save the settings under your own name and bring them back with one button. The list shows what each preset sets and which one is active now. Changed something on the camera after a preset — an orange dot appears on the button, and "Save to "…"" writes the changes into it; "New preset…" makes a new one. In the presets window you can edit any value, the order and what a preset changes, and "Preview" shows the preset on the camera while the button is held — let go and everything is back.
- **Controls hide** by themselves (after 1–30 seconds, set in settings) or on command.
- No drivers or third-party libraries: on the Mac — the system ImageCaptureCore and Network, on Android — the system USB host and plain sockets.

</details>

## Which cameras will work

The app was made on the ZV-E10 but isn't tied to that model: it connects to any Sony camera that has PC Remote mode over USB or Wi-Fi. How well it works depends on the camera's generation.

| | Cameras | What will work |
|:---:|---|---|
| ✅ | **ZV-E10** (firmware 2.00) | everything |
| 🟢 | **Sony from about 2020 on**: a7S III, a7C, a7 IV, a7R V, a1, a9 III, FX3, a6700 and others | most likely everything: they use the same protocol version as the ZV-E10. Possible differences: rare values are labeled in English, as in Sony's reference, or as `#N`; on cameras with a mode dial the "Photo / Video / S&Q" switch may not work; the menu items for enabling PC Remote are in other sections |
| 🟡 | **Sony before 2020**: a6000, a6400, a6600, a7 III, a7R III, RX100 VI and others | may connect and show video, but these cameras change shutter speed, ISO and exposure compensation in "+1 / −1" steps, while the app writes values directly, so changing them most likely won't work |

**Labels and the focus scale on other Sony cameras.** The app takes value names from the camera itself if it can send them (that's how Creative Look is labeled), and for the rest — from Sony's "Camera Control PTP 3 Reference" (the names there are in English), so `#N` shows up less often. If a camera has Creative Look but no Creative Style, Creative Look takes its place in the Fn menu and in "All settings". Cameras that report lens data get distances under the focus scale, and on cameras with absolute focus the A and B marks pull focus by writing the position. LUT "Auto" on cameras that report the profile's gamma and color mode takes them from the camera. Details in the [camera protocol](docs/protocol.md#другие-камеры-sony).

All of this was made from Sony's reference and debugged on a virtual camera; we haven't had a real camera that sends such labels and lens data. If a label, the scale or a distance doesn't look like it does in the camera menu, send the report from "Debug" (<kbd>⌥</kbd><kbd>⌘</kbd><kbd>D</kbd> on the Mac or a long press on the status bar on the tablet → "Camera" tab → "Copy"): it contains the camera's code version, the label lists and the lens tables. The report contains all the name lists the camera sends, and the button or FTP server lists may contain names you entered yourself — look it over before sending.

> [!TIP]
> Trying it on another Sony camera is safe: when connecting, the app only hands control over to the computer and doesn't touch the settings until you change them yourself. If you've tried it, tell us in [Issues](https://github.com/Scryptor/SonyCamViewer/issues) what works and what doesn't.

## Installing on Android

You need Android 10 or later and a tablet you can connect USB devices to (USB host, OTG): almost any modern one can do it. The app was made on a Redmi Pad Pro with Android 14. It will run on a phone too, but the layout is designed for a tablet screen for now; a phone version is coming soon.

1. Download [Sony-Cam-Viewer.apk](https://github.com/Scryptor/SonyCamViewer/releases/latest/download/Sony-Cam-Viewer.apk) straight to the tablet and open it. Android will ask to allow installing from this source — allow it.
2. [Set up the camera](#camera-setup) and connect it to the tablet with a USB-C cable.
3. Android will ask whether to give the app access to the camera — tap "OK". If the dialog has an "always open when connected" checkbox, tick it: then the app will start by itself as soon as you connect the camera.

Shots and still frames are saved to "Downloads/ZV-E10" and show up in the gallery.

> [!TIP]
> The tablet has a single port, so while the camera is connected it runs on battery. You can charge it at the same time through a USB-C hub with pass-through charging — or connect the camera [over Wi-Fi](#connecting-over-wi-fi). Both the camera's and the tablet's battery are shown in the status bar.

## Installing on Mac

You need an Apple Silicon Mac and macOS 14 or later.

1. Download [Sony-Cam-Viewer.dmg](https://github.com/Scryptor/SonyCamViewer/releases/latest/download/Sony-Cam-Viewer.dmg).
2. Open it and drag "Sony Cam Viewer" to "Applications".
3. Open the app for the first time — see below.
4. [Set up the camera](#camera-setup) and connect it to the Mac with a USB-C cable.

> [!IMPORTANT]
> The app isn't signed with an Apple developer certificate, so macOS won't open it on first launch. To open it:
> 1. try to launch the app and close the warning;
> 2. open System Settings → Privacy & Security and click "Open Anyway" at the bottom.
>
> Or with a single command in Terminal:
>
> ```bash
> xattr -dr com.apple.quarantine "/Applications/Sony Cam Viewer.app"
> ```

> [!NOTE]
> - On the first shot saved to the Downloads folder, macOS may ask for permission to access it: allow it.
> - If "Applications" has ZV-E10 Viewer (the app's old name), delete it after installing: the settings and presets are shared and will be kept.

## Camera setup

Once, on the camera:

| | Menu item | Value |
|:---:|---|---|
| 1 | MENU → Network → Smartphone Connect → Smartphone Connection | **Off** |
| 2 | MENU → Network → PC Remote Function → PC Remote | **On** |
| 3 | MENU → Network → PC Remote Function → PC Remote Cnct Method | **USB** |

Then connect the camera to the Mac or tablet with a USB-C cable. The picture appears in a few seconds.

The menu items are for the ZV-E10. On other Sony cameras they're named similarly but may be in other menu sections.

> [!NOTE]
> On the Mac, after installing and after every update, macOS will ask whether the app may access "files on a removable volume" — click "Allow". Without it macOS won't give the camera to the app; the app will remind you on the waiting screen.

> [!TIP]
> Camera connected but no picture? Close Imaging Edge and other apps that may have taken the camera, and reconnect the cable.

## Connecting over Wi-Fi

Without a cable the app finds the camera by itself — on your home network or on the network the camera hosts.

### Through a home router

Once:

1. On the camera: MENU → Network → Wi-Fi Settings → Access Point Set. — connect the camera to your home router. The ZV-E10 works only on 2.4 GHz; the Mac or tablet can be on the same network on any band.
2. MENU → Network → PC Remote Function → PC Remote: **On**, PC Remote Cnct Method: **Wi-Fi Access Point**.
3. Launch the app without a cable and on the waiting screen click "Pair a new camera…" under the camera list.
4. On the camera: MENU → Network → PC Remote Function → **Pairing** and confirm.

From then on the app connects by itself as soon as the camera appears on the network.

- The Mac and the tablet pair separately; the camera remembers both, but only one device can be connected at a time.
- If the camera is on a cable in USB PC Remote mode, the connection goes over USB, and a cable (for example, charging) doesn't break a Wi-Fi connection that's already running.
- Several cameras on the network — each is paired once, and you choose which to connect in the camera list.
- "Forget pairing" in settings gives the app a new name for cameras: all cameras will have to be paired again.
- On the Mac, allow the app access to the local network on first connection.

### Without a router (Wi-Fi Direct)

For example, outdoors: the camera hosts the network itself.

1. On the camera: PC Remote Cnct Method: **Wi-Fi Direct**. The camera shows its network name (`DIRECT-…`) and password.
2. Connect the tablet or Mac to this network. Android will warn that it has no internet — stay connected.
3. The app finds the camera by itself; the status bar will say "Wi-Fi Direct".

Preview on the tablet is 23–25 frames per second, RAW+JPEG takes about 20 seconds. A device already paired through a router connects without new pairing. A Mac connected to the camera's network stays without internet for that time.

### Speed and weak signal

| | By cable | Over Wi-Fi |
|---|---|---|
| Preview | up to 25 fps | up to 25 fps |
| RAW+JPEG, from press to file | about 4 s | about 14 s on the tablet, 12–14 s on the Mac |
| Camera battery | — | drains faster |

The app waits out connection stalls without dropping the connection: the last frame stays on screen with "Weak Wi-Fi signal" on top, and if there's no connection for longer than a couple of seconds — "Connection to the camera lost" and how long it's been. When the connection comes back, the picture carries on by itself. If the connection does drop, the app reconnects right away (usually in 1–2 seconds), and the frame and settings stay on screen.

Full preview quality is about 14 Mbit/s, which is close to the ceiling of the camera's Wi-Fi. So in "Auto" mode (the default) the app switches to 640×360 by itself when the signal is weak: the picture is smaller but runs smoothly, and full quality comes back when the signal is good. The resolution is always shown by the frame counter in the status bar ("576p", "360p"). Tapping the counter opens the choice: "Auto", "1024×576" — always full, "640×360" — always light; the same is in settings. By cable the preview is always full.

## Controls

### On Android

| Gesture | What it does |
|---|---|
| Tap the video | hide or bring back the controls; without taps they hide by themselves, after a number of seconds set in settings |
| Tap a value at the bottom | Fn menu; values change with a tap, the value strip can be scrolled with a finger |
| Two fingers on the video | digital magnification up to 8×; ✕ in the bottom right corner returns the whole frame |
| Tap a camera tile | make the camera the main one |
| Long-press a tile | camera menu; move your finger after it and the tile changes place |
| Tap a shot in the strip | gallery: swipe through, zoom with a pinch or double tap, close by swiping down or "Back"; "Delete" at the top — shot to the tablet's trash |
| Long-press a shot | open the file in another app |
| Long-press "Presets" | the "Presets" panel, where they're edited |
| Drag the histogram, waveform, vectorscope or overlay panel | put it anywhere |
| Tap the vectorscope | ×1 or ×2 scale |
| Tap the reference thumbnail on the overlay panel | hide or show the reference |
| Tap the frame while "touch focus" is lit | focus at that point; while the color picker is on — the picker at that point (the mark can be dragged) |
| Hold "T" or "W" | power zoom |
| Drag the focus scale (in MF) | nearer or farther, like a focus ring |
| Tap the "A+" or "B+" mark under the focus scale | remember where focus is now |
| Tap a set "A" or "B" mark | pull focus to it over the chosen time; tap the moving mark — stop |
| Long-press a mark | "Remember here" or "Erase" |
| Long-press the "⏱ 2 s" time button | the "Focus marks" card |
| Volume and "Camera" buttons (if turned on in ⚙) | shutter in photo mode, start and stop recording in video and S&Q |
| Long-press the status bar | debug panel |

- **Presets** — the button at the top left, separate for photo and video: a tap opens the list, ☆+ saves the settings — into the current preset or as a new one.
- **Monitor** — the button with stripes at the top left: 14 tiles at the bottom of the screen — zebra, false color, peaking and its color, LUT, vectorscope, color picker, grid, center mark, frame lines, safe area, frame overlay, "Picture" (mirror and flip) and "Everything — off"; a tap on a tile selects it, another tap turns it on or off. "Add LUT…" opens the system file picker.
- **Status bar** is visible together with the other controls, and the button with a dot and Wi-Fi at the top left pins it: while the button is lit blue (the default), the bar stays on screen even when the controls are hidden.
- **Vibration**: if the device has a vibration motor, the capture buttons respond with vibration (turned off in settings). The Redmi Pad Pro has no motor — there the feedback is on screen only.
- **Settings** — the ⚙ button at the top left: where to save shots (to the tablet and the card, the tablet only, the card only), how many seconds and what to show after shooting, the red border while recording, whether to connect over Wi-Fi and the preview quality over Wi-Fi, whether to hide the controls and after how many seconds, "Volume buttons — shutter and recording" (off by default).

### On the Mac

| Key | What it does |
|---|---|
| <kbd>F</kbd> | open or close the Fn menu |
| <kbd>M</kbd> | open or close the Monitor menu |
| <kbd>Z</kbd> <kbd>P</kbd> <kbd>C</kbd> | zebra, peaking, false color — turn on or off |
| <kbd>L</kbd> <kbd>V</kbd> <kbd>I</kbd> | LUT, vectorscope, color picker — turn on or off |
| <kbd>O</kbd> | frame overlay: turn on, hide or show the reference |
| <kbd>T</kbd> <kbd>W</kbd> (hold) | power zoom to tele or to wide |
| <kbd>←</kbd> <kbd>→</kbd> | change the selected setting in the Fn or Monitor menu; in the gallery — the neighboring shot |
| <kbd>1</kbd>–<kbd>9</kbd> | apply a preset |
| <kbd>⇧</kbd><kbd>⌘</kbd><kbd>P</kbd> | presets window |
| <kbd>Space</kbd> | take a shot in photo mode; close the shot review or the gallery |
| <kbd>S</kbd> | still frame in video mode |
| <kbd>H</kbd> | hide or show the controls |
| <kbd>Esc</kbd> | close a menu, the review or the gallery |
| <kbd>⌘</kbd><kbd>⌫</kbd> | in the gallery — shot to the Trash; <kbd>⌘</kbd><kbd>Z</kbd> — bring it back while "Undo" is visible |
| <kbd>⌘</kbd><kbd>=</kbd> <kbd>⌘</kbd><kbd>−</kbd> <kbd>⌘</kbd><kbd>0</kbd> | magnify or shrink the picture, return the whole frame |
| <kbd>⌥</kbd><kbd>⌘</kbd><kbd>D</kbd> | debug panel |
| <kbd>⌘</kbd><kbd>,</kbd> | app settings |

The letter keys also work in the Russian keyboard layout.

- **Mouse and trackpad.** The mouse wheel over the Fn menu scrolls the selected setting's values, over the focus scale it focuses by the step. A trackpad pinch magnifies the picture. The histogram, waveform, vectorscope and overlay panel can be dragged anywhere with the mouse; clicking the vectorscope switches the ×1 / ×2 scale. An image dragged from Finder onto the window becomes the overlay reference, and a `.cube` file becomes your own LUT. While "touch focus" is lit (the button on the right), clicking the frame puts focus there; while the color picker is on, it places the picker (the mark can be dragged).
- **Focus marks.** Clicking "A+" or "B+" under the focus scale remembers the focus, clicking a set mark pulls focus to it, clicking a moving one stops it; right-clicking a mark gives "Remember here" and "Erase", right-clicking the time button gives "How focus marks work…" (the "Focus marks" card).
- **Camera strip.** Clicking a tile makes the camera the main one, dragging changes its place.
- **Presets** — the button at the top left, separate for photo and video: a click opens the list, which shows what each preset sets; ☆+ saves the settings — into the current preset if it was changed on the camera, or as a new one; View → Presets… (<kbd>⇧</kbd><kbd>⌘</kbd><kbd>P</kbd>) opens the window where presets are renamed, reordered and edited one setting at a time.
- **Shots.** Clicking a shot in the strip opens the gallery; right-click gives "Show in Finder", "Open" or "Move to Trash".
- **Status bar** is pinned with the button with a dot and Wi-Fi at the top left — then it doesn't hide with the other controls.
- **App settings** — the ⚙ button at the top left or <kbd>⌘</kbd><kbd>,</kbd>: where to save shots (to the Mac and the card, the Mac only, the card only) and to which folder, how many seconds and what to show after shooting, the red border while recording (on, thickness, brightness, blinking), whether to connect over Wi-Fi and the preview quality over Wi-Fi, whether to hide the controls and after how many seconds.

All the features in detail are in the [user guide](docs/user-guide.md) (in Russian).

## Limitations

Some limitations come from the camera itself; the app can't get around them:

- **Preview only.** Over both USB and Wi-Fi the camera gives only a preview: 1024×576 in video mode, in photo mode depending on the aspect ratio (at 1:1 — 1024×1024), up to 25 frames per second. It doesn't get any better: the preview quality property doesn't change the picture over USB, and over Wi-Fi it can only make it smaller (640×360) — which the app uses on a weak signal. Better quality video comes from the camera's USB Streaming mode (the camera becomes a webcam) or HDMI capture, but that's without this app.
- **A still frame** from video is therefore a preview frame, 1024×576, not a full-size shot.
- **The exposure meter** (M.M.) in manual mode isn't reported by the camera; the app doesn't show it.
- **White balance.** The camera doesn't report the temperature of auto white balance and the presets ("Daylight", "Shade" and others): the app shows "auto" and approximate values.
- **S&Q.** The camera doesn't report and won't let you change the frame rate (1–100 fps) over the cable: the app infers it from the longest allowed shutter speed, and it's changed in the camera menu (S&Q Settings → Frame Rate).
- **One device over Wi-Fi.** Only one device connects to the camera at a time: the Mac or the tablet.
- **While recording** video you can't take a photo or switch modes.
- **Zoom.** The app zooms with a power zoom lens (on the ZV-E10 — the PZ 16-50 kit lens) or the camera's Clear Image Zoom, if the camera allows it; a zoom with a mechanical ring is by hand only. While Clear Image Zoom is above ×1.0, the camera doesn't allow touch focus. Two-finger magnification is digital, on screen only: shots and recording are always the whole frame, and detail is limited by the 1024×576 preview.
- **Zebra, false color, peaking, waveform, vectorscope, color picker** work on the preview, not on the recorded video: a guide, like the histogram. Peaking on a 1024×576 preview is most accurate together with magnification, and in a dark scene it highlights fewer edges.
- **LUT and "Auto"** follow the camera's factory profiles: PP7 — S-Log2, PP8 and PP9 — S-Log3, PP10 — HLG (on cameras that report the profile's gamma and color mode themselves — by those). If a profile in the camera has been reconfigured, "Auto" will be wrong — pick the LUT by hand. The preview is an 8-bit JPEG, so the LUT picture is a guide for the eye, not color grading. The preview has its own levels — it stretches camera codes 64…1019 to 0…255 (that's how the ZV-E10 does it) — and the LUT gets the camera code recomputed from the preview.
- **Focus marks** on cameras without absolute focus (the ZV-E10) drive the lens in "nearer / farther" steps, so focus lands on a mark to within one lens step. Marks aren't saved between launches.
- **Several cameras at once** have been tested on virtual cameras; not yet on several real ones.

## Feedback

The app needs feedback from real shoots — especially from other Sony cameras, other tablets and long recordings. Found a bug or something missing — open [Issues](https://github.com/Scryptor/SonyCamViewer/issues) (English is fine) and write:

- the camera and firmware, the Mac or tablet (model and system version);
- how the camera is connected: by cable, through a router or Wi-Fi Direct;
- what you did, what you expected and what happened; a screenshot or screen recording helps a lot;
- the log from the debug panel: <kbd>⌥</kbd><kbd>⌘</kbd><kbd>D</kbd> on the Mac or a long press on the status bar on the tablet → "Copy". The serial number, MAC address and GUID aren't shown in it. But for a newer-generation camera it contains (in the "Camera" tab) all the name lists the camera sends, and the button or FTP server lists may contain names you entered yourself: look the report over before sending.

Bugs get fixed quickly; new versions come out on the [Releases](https://github.com/Scryptor/SonyCamViewer/releases) page.

## Documentation

The documentation is in Russian for now.

| | |
|---|---|
| 📖 [User guide](docs/user-guide.md) | all features in order, app settings, troubleshooting, Android differences |
| 🔌 [Camera protocol](docs/protocol.md) | how the app talks to the camera over USB and Wi-Fi: commands, properties, value encoding, pitfalls, USB specifics on Android, PTP/IP, new operations of newer-generation cameras (labels, lens data, absolute focus) |
| 🧱 [Code structure](docs/architecture.md) | modules, data flows, tests, build and release — for the Mac and for Android |
| 🤖 [Automation API](docs/automation-api.md) | the Mac app's local HTTP API, for tests and scripts |

## Building from source

<details>
<summary><b>Mac</b> — Xcode 16 or later (Swift 6)</summary>

```bash
swift test               # unit tests, no camera needed
scripts/build-app.sh     # build/Sony Cam Viewer.app
scripts/make-dmg.sh      # build/Sony-Cam-Viewer.dmg
```

- `Sources/ZVCore` — the PTP protocol, Sony commands, the camera session, settings, presets, capture. No UI, covered by tests (`Tests/ZVCoreTests`, including on dumps from a real camera).
- `Sources/ZVCamera` — the camera connection: over USB through ImageCaptureCore, over Wi-Fi — PTP/IP on top of Network, finding the camera on the network.
- `Sources/ZVViewer` — the SwiftUI app itself.
- `Sources/zv-probe` — a command-line tool for experimenting with the camera: `info`, `props`, `lv`, `set`, `rec`; over Wi-Fi — `wifi-find`, `wifi-pair <address>` and `--wifi <address>` before a command.
- `Resources/AppIcon.icon` — the icon in Icon Composer format. `scripts/make-icon.sh` rebuilds `Assets.car` and `AppIcon.icns` from it; this needs Xcode 26.

Launched with the `--automation` flag, the app opens a local API for tests: see [Automation API](docs/automation-api.md).

</details>

<details>
<summary><b>Android</b> — JDK 17 and the Android SDK (platform 34)</summary>

The `android/` folder. Kotlin and Jetpack Compose, the same logic as in `ZVCore`.

```bash
cd android
./gradlew :app:testDebugUnitTest      # unit tests on dumps from a real camera
./gradlew :app:assembleRelease        # app/build/outputs/apk/release/app-release.apk
```

Helper scripts for working with a device over adb are in `android/scripts`; details in [Code structure](docs/architecture.md#приложение-для-android).

</details>

<details>
<summary><b>Without a camera</b> — virtual cameras and end-to-end tests</summary>

- `scripts/fake_camera.py` — a single camera (`--model newer` — a newer-generation camera with labels and lens data);
- `scripts/fake_cameras.py` — several different ones that answer network discovery;
- `scripts/e2e_*.py` — end-to-end tests on them.

</details>

The project description and development plan are in `docs/superpowers`.

---

<p align="center"><sub>An unofficial app: Sony didn't make it and doesn't support it. Sony and ZV-E10 are trademarks of Sony Group Corporation.</sub></p>
