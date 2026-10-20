<div align="center">
  <h1>SMS Forwarder</h1>
  <p>
    <strong>Forward incoming SMS to your Telegram — reliably, in the background</strong>
  </p>
  <p>
    Reads the text messages your phone receives and uploads each one to the SMS Forwarder
    backend, which relays it to your Telegram. Built for a phone left plugged in at home so
    your SMS follow you while you are away.
  </p>
  <p>
    🌐 <a href="https://smsforwarder.shuttlelab.org">Forward SMS to Telegram on Android — SMS Forwarder</a>
    &nbsp;·&nbsp;
    🔌 Backend: <a href="https://github.com/ShuttleLab/smsforwarder-api">smsforwarder-api</a>
  </p>
  <img src="docs/screenshot.jpg" alt="SMS Forwarder — home screen" width="300" />
  <img src="docs/screenshot1.jpg" alt="SMS Forwarder — app screen" width="300" />
</div>

<div align="center">

![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84?style=flat-square&logo=android)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9-7F52FF?style=flat-square&logo=kotlin)
![Material](https://img.shields.io/badge/Material_Components-1.10-757575?style=flat-square&logo=materialdesign)
![License](https://img.shields.io/badge/License-AGPL--3.0-blue?style=flat-square)

</div>

## About

SMS Forwarder is a small Android app that captures incoming SMS and **POSTs each one as
JSON to the SMS Forwarder backend**, which relays it to your Telegram. It is a deliberately
**thin client**: it holds no channel tokens and talks to no messaging service directly —
adding or changing channels is done on the server and never requires rebuilding the app.

The design goal is reliability on a **plugged-in, unattended phone** — including on
aggressive Chinese ROMs (Huawei / HarmonyOS) where background restrictions routinely break
naive SMS apps. It is **not** published to any app store; it is for personal use.

## Key Features

- **Real-time capture that survives aggressive ROMs** — an incoming-SMS broadcast receiver *plus* a foreground-service poll of the SMS inbox (every few seconds, configurable). Capture uses a monotonic `_id` cursor, so nothing is missed even where the system withholds SMS broadcasts or the message timestamp lags the phone clock.
- **Thin client** — no channel tokens in the app; it only uploads to the backend. Channel routing lives server-side.
- **Reliable delivery** — each SMS is uploaded directly from the live foreground service (not via WorkManager, which Huawei throttles), retried until it succeeds; the server deduplicates with an idempotency key so nothing is delivered twice.
- **Long-term survival** — persistent foreground service, auto-restart on boot, battery-optimization exemption, an OEM auto-start whitelist guide, and an optional "silent-audio" keep-alive for extra resistance to being killed.
- **Guided setup** — a clean three-step home screen: ① sign in / register → ② bind Telegram → ③ start the service, each with a ✓ when done.
- **Account-scoped** — sign in once with username + password; the device stays signed in via a long-lived, revocable token. Your bindings are tied to your account, so messages never reach the wrong user.
- **Built-in diagnostics** — an activity log (bounded, self-pruning) and an "inspect SMS inbox" tool for verifying capture on a headless phone.
- **Bilingual** — English and 中文, with an in-app language switch.

## Tech Stack

- **Language:** Kotlin
- **Platform:** Android SDK (minSdk 24 / targetSdk 36)
- **UI:** Material Components, View-based layouts
- **Concurrency:** Kotlin Coroutines; WorkManager (periodic reconcile backstop only)
- **Networking:** raw `HttpURLConnection` (no networking library)
- **CI/CD:** GitHub Actions

## Getting Started

### Backend — already running

The released APK is preconfigured to talk to the **ShuttleLab-operated backend** at
`smsforwarder-api.shuttlelab.org`, so it works out of the box — just install, sign in, and
bind Telegram. If you build from source you can point it at another instance in the app's
Settings (the companion server is
[smsforwarder-api](https://github.com/ShuttleLab/smsforwarder-api)).

### Install

Grab the debug APK from the latest **Build APK** run under [Actions](../../actions)
(artifact `SMSForwarder-debug`), or a signed APK from [Releases](../../releases). You may
need to allow installation from unknown sources; on Huawei, disable "Pure Mode" first.

After installing: grant SMS + notification permissions, sign in, bind Telegram, tap
**Start service**, then allow **battery-optimization exemption** and **auto-start** (the
decisive step on Chinese ROMs).

### Build from Source

#### Prerequisites

- JDK 17
- Android SDK (or Android Studio)

#### Build

```bash
git clone https://github.com/ShuttleLab/SMSForwarder.git
cd SMSForwarder
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

## Releases

Pushing a `v*` tag triggers a GitHub Actions workflow that builds a **signed release APK**
and publishes it to GitHub Releases. Signing secrets (`KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) are configured in the repository settings;
when unset, the release build falls back to debug signing so it never fails.

```bash
git tag v1.0.0
git push origin v1.0.0
```

## License

Licensed under the **GNU Affero General Public License v3.0** (AGPL-3.0) — see
[LICENSE](LICENSE). You may use, study, modify and redistribute it, but any distributed
or network-served derivative must also be released under AGPL-3.0. A personal project;
not published to any app store.

<div align="center">
  Built by <a href="https://github.com/ShuttleLab">ShuttleLab</a>
</div>
