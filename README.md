A BLE remote for the DJI Osmo 360 that, I guess, should work on any Android device with a BLE module, optimized for wearable/watch form factors.

```
Huawei Watch 4 Pro ──BLE──► DJI R SDK protocol ──► DJI Osmo 360
```

No phone, DJI Mimo, DJI Mobile SDK, Wear Engine, Wear OS API or Google Play Services required.

> **Disclaimer:** this is an unofficial, independent project, not affiliated with or endorsed by
> DJI. Protocol constants and algorithms are implemented from the MIT-licensed code of
> [`dji-sdk/Osmo-GPS-Controller-Demo`](https://github.com/dji-sdk/Osmo-GPS-Controller-Demo);
> DJI's protocol documentation itself is distributed under DJI's EULA and is **not** redistributed
> in this repository (the `reference/` checkout is local-only, see `.gitignore`).

---

## Supported devices

| Role | Device |
|---|---|
| Controller (this app) | Huawei Watch 4 Pro (HarmonyOS 4.x, Android-compat layer) |
| Camera (target) | DJI Osmo 360 — `device_id = 0xFF66` |
| Cameras allowed by protocol | Osmo Action 6 (0xFF55) / 5 Pro (0xFF44) / 4 (0xFF33); Osmo Nano — no |

Protocol reference: the official [`dji-sdk/Osmo-GPS-Controller-Demo`](https://github.com/dji-sdk/Osmo-GPS-Controller-Demo).
For local development: `git clone --depth 1 https://github.com/dji-sdk/Osmo-GPS-Controller-Demo reference/Osmo-GPS-Controller-Demo`
(the `reference/` directory is in `.gitignore` and is not committed). ESP32-specific parts are not ported; only the protocol/algorithms.

## Build and test

`./gradlew :app:assembleDebug :app:testDebugUnitTest`

## Build requirements

* macOS/Linux, **JDK 17** (`brew install openjdk@17`) — AGP 8.10.1 requires JDK 17
  (if the default JDK is newer, the script exports JAVA_HOME itself);
* Android SDK: platform android-35, build-tools 35.x (installed by Android Studio or
  sdkmanager); the path is resolved via `ANDROID_HOME`;
* Gradle wrapper 8.14 — downloaded automatically.

```bash
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease        # release APK (shrunk, debug-signed)
```

### App versioning

The version lives in a single source of truth, [`app/version.properties`](app/version.properties)
(`versionCode` + `versionName`). Plugged straight into `defaultConfig`.

* Release builds (`assembleRelease` / `bundleRelease` / `installRelease` — any task whose
  name contains `Release`) **auto-bump the version**: `versionCode` +1 and the patch digit of
  `versionName` +1 (`0.1.0` → `0.1.1`), then persist it back to `version.properties`.
* Debug builds leave the version untouched, so reinstalling a debug APK doesn't increment it.

If you need to jump a major/minor milestone, edit `app/version.properties` by hand before the
next release build (the auto-bump then only advances on top of it).

The app currently has NO external dependencies (Kotlin stdlib + framework APIs) —
a deliberate choice for battery life and HarmonyOS compat-layer compatibility.

## Docs index

* [`docs/watch-compatibility.md`](docs/watch-compatibility.md) — STEP 1 table (filled in on device)
* [`docs/ble.md`](docs/ble.md) — GATT profile from the official demo (FFF0/FFF4/FFF5), verification plan
* `docs/dji-protocol.md` — will appear in STEP 5–7 together with the command implementations

## License

[MIT](LICENSE) © 2026
