<div align="center">

<br>

<img src="assets/icon.png" width="108" height="108" alt="Dollhouse">

# Dollhouse

**A little house with its lights on, waiting for someone lovely to move in.**

<br>

<a href="https://github.com/Farewell-coder/Dollhouse/releases/latest"><img src="https://img.shields.io/badge/Download-Releases-6C5CE7?style=flat-square&logo=android&logoColor=white" alt="Download Releases"></a>
&nbsp;
<a href="#getting-started"><img src="https://img.shields.io/badge/Android-7.0+-F3F1FF?style=flat-square&logo=android&logoColor=6C5CE7" alt="Android 7.0+"></a>
&nbsp;
<a href="LICENSE"><img src="https://img.shields.io/badge/Open_source-GPL--3.0-F3F1FF?style=flat-square&logo=opensourceinitiative&logoColor=6C5CE7" alt="GPL-3.0"></a>
&nbsp;
<a href="README.md"><img src="https://img.shields.io/badge/Language-中文-F3F1FF?style=flat-square" alt="中文"></a>

</div>

---

Sometimes, I just want my phone to feel a little more alive.

Not another tool asking for my attention, and not another task waiting to be finished. Just a small companion resting quietly in a corner of the screen. It blinks when you have a moment to look, responds when you tap it, and is there when you suddenly feel like saying a few words.

That is where Dollhouse began.

Along the way, I started to feel that a desktop pet should not be stuck with one face and a handful of fixed lines. It should be able to grow into something you genuinely like: a character of your own, with movements you choose and memories of the things you have talked about.

So Dollhouse is not just an animated picture, nor is it simply a chat window with an AI attached. I would rather think of it as a little house you can make your own. **The house is the platform; the character living inside is where the story begins.**

Its current resident is a whale girl called “Little Fatty Fish.” She blinks, sways while idle, and peeks out from the edges of the screen.

## Inside the house

### A little companion who responds

Your character can float above other apps, so you do not have to open Dollhouse every time you want to see them.

- Dragging, edge snapping, position saving, and size adjustment
- Idle behavior, animation, and screen-edge interactions
- A tap for a speech bubble, a triple-tap for the mini input box
- Foreground service, boot restoration, and background survival settings

### There when you feel like talking

The mini input box and the full chat screen share the same conversation. Drop in for a quick word, then come back later and pick up where you left off.

Dollhouse supports multi-turn conversations, streaming replies, and chat history. You can manage multiple model services, model lists, and favorites, as well as your own avatar, nickname, and signature.

AI chat requires an online service that you configure yourself; no offline model is bundled. Actual capabilities depend on the service and model you choose.

### Some things are worth remembering

Automatic session summaries, long-term memory saving, and memory trimming are three independent switches. You decide what the app should do.

The memory library is not just another list. It is a force-directed graph that you can drag and zoom. Tap a node to revisit a memory, tap an edge to see how two memories relate, or create a connection yourself.

### Make the place feel like yours

Choose from system-following, light, dark, and pure black themes. Wallpaper colors, background images, and frosted glass are supported too.

Backgrounds fall back from the per-doll layer to the global layer, and each character can have its own chat background and bubble colors. Character and action support will continue to grow, so a different resident can feel like more than a different picture.

### When something goes wrong, do not just guess

The last entry on the About page opens the Diagnostics Center. Here you can inspect error and crash groups, stack traces, and related logs; search and filter records; run a log self-check; or export an integrity-checked ZIP diagnostic package.

Severe errors always keep a basic record. Detailed runtime logging has its own switch.

## Getting started

1. Download an APK from [Releases](https://github.com/Farewell-coder/Dollhouse/releases), preferably from a stable release.
2. Install and open the app, grant the overlay permission, and start the desktop pet. Notification permission is used for the running notification.
3. To chat, enter your own model service configuration in Settings.
4. If Android keeps stopping the app in the background, follow the in-app guidance for battery optimization and vendor autostart options.

Dollhouse requires **Android 7.0 (API 24)** or newer and targets **Android 14 (API 34)**. It has been built and installed on OPPO / ColorOS 14, but not every device vendor or system version has been verified.

Device control is optional. It requires you to install, start, and authorize [Shizuku](https://github.com/RikkaApps/Shizuku). [lamda](https://github.com/firerpa/lamda) is another optional component and is not distributed with the APK. The desktop pet and configured chat work without them.

## Permissions and your data

Some permissions help your companion stay with you; others are only needed for specific features:

- Overlay access is used for the doll and chat windows. The foreground service and notifications support background operation, and the boot broadcast restores the doll.
- Network access is used for your configured chat service, online tools, and optional component downloads.
- Conversations, memories, and settings are stored in the app's private directory on your device. During online chat, relevant content is sent to the service you configured as required by the feature.
- Credentials are used for request authentication. Check for sensitive information before exporting or sharing a configuration.
- OCR uses Google ML Kit. Its dependencies follow their own terms and data handling rules.
- Authorized device commands may read the screen, files, and settings, and may change device state. Model-generated instructions can be wrong; verify important operations yourself.

## Release notes

What each version adds, changes, and fixes is kept in the changelog instead of piling up here.

You are welcome to browse the [changelog](CHANGELOG.md).

## Build from source

You will need JDK 17 and Android SDK 36. The project uses Gradle 8.11.1, AGP 8.9.1, and Kotlin 2.2.10. All project source code is Kotlin.

```bash
git clone https://github.com/Farewell-coder/Dollhouse.git
cd Dollhouse

# Configure your local SDK path in local.properties.
# gradle.properties contains a local aapt2 path for on-device arm64 builds;
# remove that override on a desktop, or point it at a valid local path.
./gradlew assembleDebug
```

For release signing, configure your own key following `keystore.properties.example`, then run `./gradlew assembleRelease`. Output is written to `app/build/outputs/apk/`.

Signing credentials and build outputs are not committed. A package signed with your own key cannot overwrite the officially signed package.

## Help make the house a home

Dollhouse is an open-source project and an idea that is still growing.

If you enjoy this direction, you are welcome to read the source, report problems, suggest improvements, or contribute code. Making a movement feel more natural, a back action more reliable, or memory usage a little lower—these small, concrete improvements all make the house nicer to live in.

- [GitHub repository](https://github.com/Farewell-coder/Dollhouse)
- [Releases and APKs](https://github.com/Farewell-coder/Dollhouse/releases)
- [Issues and suggestions](https://github.com/Farewell-coder/Dollhouse/issues)

## Third-party notices and license

| Source | Purpose | License or terms |
| --- | --- | --- |
| [Lucide](https://github.com/lucide-icons/lucide) | Android adaptation of icon paths | ISC, with MIT for Feather-derived parts |
| [Shizuku API](https://github.com/RikkaApps/Shizuku-API) | Client for authorization and system APIs | MIT; not equivalent to the license of a standalone manager |
| Kotlin, coroutines, Hilt / Dagger, AndroidX, and others | Runtime and dependencies | Apache-2.0 and others; see the itemized notices |
| Google ML Kit / Play services | Text recognition and dependencies | Google ML Kit / Android SDK terms |

Full copyright, license, and build dependency listings are available in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and [licenses](licenses/).

Dollhouse's own code is licensed under [GPL-3.0](LICENSE), Copyright (C) 2026 Farewell-coder. Third-party content follows its respective licenses.

This project is provided “as is” and is not guaranteed to work on every device. AI replies and device operations can be wrong; verify important information and high-privilege operations yourself. Nothing here reduces the rights granted by the applicable open-source license or excludes liability that cannot be excluded by law.

---

<div align="center">

The house is still being built, and the story is far from finished.

**Welcome to Dollhouse.**

</div>