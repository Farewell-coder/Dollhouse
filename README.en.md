<div align="center">

<img src="assets/icon.png" width="112" height="112" alt="Dollhouse">

# Dollhouse

An Android desktop pet app that keeps floating dolls, chat, and memory management in one place.

[![Latest](https://img.shields.io/github/v/release/Farewell-coder/Dollhouse?style=flat-square&label=latest&color=6b4ee6)](https://github.com/Farewell-coder/Dollhouse/releases/latest)
[![Android](https://img.shields.io/badge/Android-7.0%2B-3ddc84?style=flat-square)](#install)
[![License](https://img.shields.io/badge/License-GPL--3.0-blue?style=flat-square)](LICENSE)

[![Download latest](https://img.shields.io/badge/Download_latest-APK-3ddc84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/Farewell-coder/Dollhouse/releases/latest)
[![中文](https://img.shields.io/badge/Language-%E4%B8%AD%E6%96%87-555555?style=for-the-badge)](README.md)

</div>

---

The current doll is a whale girl named "Little Fatty Fish". She blinks, sways while idle, and peeks out at screen edges. You can drag her around; tap her for a speech bubble, triple-tap for a mini input box. You can also start a chat while staying in other apps, and open the full chat screen when you need the history.

## Features

- **Doll interaction**: drag, edge snapping, position saving, size adjustment, and animation.
- **Chat**: multi-turn conversation, streaming replies, session history; the mini box and the full chat screen share the same session. AI chat requires your own online service configuration — no offline model is bundled.
- **Memory**: automatic session summarization, automatic long-term memory saving, and automatic memory trimming are three independent switches. The memory library is a **force-directed graph** — drag, zoom, tap a node for details, tap an edge for the relationship, or link memories by hand.
- **Providers and models**: manage multiple service configurations, model lists, and favorites. Actual protocol, streaming, and tool-calling compatibility depends on the service you use.
- **Appearance**: follow system, light, dark, and pure black themes; wallpaper color extraction, doll size, background image, and frosted glass. Backgrounds resolve in two layers — **per doll / global** — so you can set a chat background and bubble color for a single doll. Falls back to built-in colors when wallpaper extraction is unavailable.
- **My profile**: reachable from the avatar at the top-left of the chat list; set your own avatar, nickname, and signature.
- **Image handling**: background cropping and text recognition, with OCR provided by Google ML Kit.
- **Optional device control**: system command access through Shizuku; installing and starting lamda adds tapping, swiping, typing, app launching, and screen reading. None of these are required for the desktop pet or a configured chat.
- **Background operation**: foreground service, boot restore, and keep-alive settings improve survival, though system battery and background policies still apply.

## Install

1. Download the APK from [Releases](https://github.com/Farewell-coder/Dollhouse/releases) and allow installing from unknown sources.
2. Open the app, grant the overlay permission, and start the desktop pet; the notification permission is used to show the running notification.
3. For chat, fill in your own service configuration in Settings. If the app is often killed in the background, follow the in-app guidance for battery optimization and vendor autostart options.

Requires Android 7.0 (API 24) or newer; targets Android 14 (API 34). Built and installed on OPPO / ColorOS 14, which does not mean it has been verified on every vendor and system version.

Device control requires installing, starting, and authorizing [Shizuku](https://github.com/RikkaApps/Shizuku) yourself. [lamda](https://github.com/firerpa/lamda) is an optional external component and is not distributed with the APK; the current download is about 204 MB, and after downloading it still has to be unpacked and started inside the app — follow the in-app prompts. Third-party components are governed by their own terms.

## Permissions and data

- The overlay permission is used for the doll and chat windows; the foreground service and notification for background operation; the boot broadcast to restore the doll; the battery optimization allowlist is optional.
- Network access is used for your configured chat service, for online tools, and for optional component downloads; vibration is used for touch feedback. Images are picked through the system picker.
- Conversations, memories, and settings are stored in the app's private directory on this device. When chatting online, the relevant conversations, memories, and tool results are sent to your configured service as required by the feature; credentials are used for request authentication. Check for sensitive information before exporting or sharing a configuration.
- ML Kit and its Google dependencies are governed by their own terms and data handling rules; this is not a promise that the app never touches the network or never produces SDK diagnostics.
- Authorized device commands may read the screen, files, and settings, and may change device state. Model-generated instructions can be wrong; tool output or a granted permission should not be treated as a security guarantee.

## v0.1.0
The action system is rebuilt: every action now belongs to one of five slots — **always-on / edge / drag** (each doll has exactly one of each; they cannot be deleted or regrouped, and their content is swapped by clearing and importing a new set of images), plus **random / manual** (freely added and removed, and only these two can be swapped between each other).
- **The pet actually plays them now**: idle plays the always-on slot, snapping to a screen edge plays the edge slot (using that action's own cut-off ratio), picking it up plays the drag slot, and leaving the edge switches back automatically.
- **Random-action switch** (Control page, per doll): when on, one random action plays every 13-30 seconds - never while docked to an edge or while dragging. Older profiles get it turned on once during the upgrade.
- **Gesture actions** are narrowed to members of the "manual" group.
- **Editor**: the actions page opens with three fixed slot cards, followed by two collapsible groups; an edge action's detail page gains a draggable cut-off line; the top bar gains "restore built-in actions".
- Also fixed: playing the edge action after leaving the edge, random actions interrupting a gesture action, the cut-off line jumping on drag, the list not refreshing after a delete, and full-rate frame looping while the screen is off.

**Interface layer rebuilt**: fixed "tapping a button on the new page hits the page underneath". Several screens are stacked on top of each other, and each one used to decide on its own whether it should accept touches, so nobody defined the combined state. It is now one shared decision, and the layer below stops responding entirely while a transition plays. The profile drawer is a real screen now (open something inside it, come back, the drawer is still there exactly as it was), and a right swipe on the home screen opens it while a left swipe closes it, with the start area kept clear of the system gesture edge.

**Appearance and consistency**: secondary pages get proper clearance below the top edge; every bottom bar in the app now uses the same capsule; the thin light border around the chat screen is gone; display size moved into "Edit doll → Appearance"; "Restore" only shows up on the built-in default profile.

**App icon**: new artwork with recomputed proportions. The adaptive canvas is 108dp but the launcher only shows the central 72dp, so filling the whole canvas cut off about a third of the composition; it is now scaled into the safe area by the doll's circumscribed radius, with the black corners removed by connected region and extended outward using the outline's own edge colour.

See the [changelog](CHANGELOG.md) for details. Version: versionName **0.1.0** / versionCode **10**.

## Build

Requires JDK 17 and Android SDK 36; the project uses Gradle 8.11.1, AGP 8.9.1, and Kotlin 2.2.10. All source is Kotlin — no Java sources.

```bash
git clone https://github.com/Farewell-coder/Dollhouse.git
cd Dollhouse
# Configure your local SDK path in local.properties.
# gradle.properties contains a local aapt2 path for on-device arm64 builds;
# remove that override on a normal desktop, or point it at a valid path.
./gradlew assembleDebug
```

For release signing, follow `keystore.properties.example` to configure your own key, then run `./gradlew assembleRelease`. Output lands in `app/build/outputs/apk/`. Signing credentials and build outputs are not committed; a package you sign yourself cannot overwrite the official one.

## Third-party notices

| Source | Purpose | License or terms |
| --- | --- | --- |
| [Lucide](https://github.com/lucide-icons/lucide) | Android adaptation of icon paths | ISC, with MIT for Feather-derived parts |
| [Shizuku API](https://github.com/RikkaApps/Shizuku-API) | Client for authorization and system APIs | MIT; not equivalent to the license of a standalone manager |
| Kotlin, coroutines, Hilt / Dagger, AndroidX, and others | Runtime and dependencies | Apache-2.0 and others, itemized in the notices |
| Google ML Kit / Play services | Text recognition and its dependencies | Google ML Kit / Android SDK terms, with separate third-party terms |

Full copyright, license, and build dependency listings are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and [licenses](licenses/).

## License and disclaimer

Dollhouse's own code is under [GPL-3.0](LICENSE), Copyright (C) 2026 Farewell-coder; third-party content follows its own license. Porting and adaptation notes are in the third-party notices and the changelog.

This project is provided "as is" and is not guaranteed to work on every device. AI replies and device operations can be wrong; verify important information and high-privilege operations yourself. Nothing here reduces the rights granted by the applicable open-source license or excludes liability that cannot be excluded by law.