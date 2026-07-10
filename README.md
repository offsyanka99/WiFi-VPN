# WiFi VPN

Android app that runs in the background, watches WiFi, and automatically controls a **WireGuard** tunnel:

| WiFi state | VPN action |
|------------|------------|
| Connected  | VPN **off** |
| Lost       | VPN **on**  |

Built with **Kotlin + Jetpack** (Foreground Service, ConnectivityManager, DataStore, Material 3).

## Features

- Foreground service so monitoring survives when the app is in the background
- `ConnectivityManager` callbacks for reliable WiFi up/down detection
- Embedded WireGuard tunnel via `com.wireguard.android:tunnel`
- Persistent WireGuard config (DataStore)
- Optional auto-restart after reboot if monitoring was left on
- Load WireGuard config from a `.conf` file (system file picker)
- Exclude apps from VPN (e.g. Android Auto) via multi-select app list
- Grant VPN permission, start/stop monitoring

## Requirements

- Android 8.0+ (API 26)
- A WireGuard server and a client config (`[Interface]` / `[Peer]`)
- JDK 17, Android SDK 35, Gradle 8.9 / AGP 8.7

## Toolchain (this machine)

Installed under the user home (no root required):

| Component | Location |
|-----------|----------|
| JDK 17 (Temurin) | `~/.local/jdk/jdk-17` |
| Android SDK | `~/Android/Sdk` |
| Platform 35 / Build-Tools 35 / platform-tools / NDK 26.1 | under SDK |
| `local.properties` | `sdk.dir=/home/yurik/Android/Sdk` |

Environment is appended to `~/.bashrc` (`JAVA_HOME`, `ANDROID_HOME`, `PATH`). Open a new terminal or `source ~/.bashrc`.

### Build from CLI

```bash
source ~/.bashrc
cd /path/to/WiFi-VPN
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Setup

1. Open this folder in Android Studio (optional) **or** use Gradle CLI above.
2. Sync Gradle and build the `app` module.
3. Install on a device with USB debugging (VPN APIs required; physical device recommended).
4. Paste your WireGuard config, e.g.:

```ini
[Interface]
PrivateKey = <your-client-private-key>
Address = 10.0.0.2/32
DNS = 1.1.1.1

[Peer]
PublicKey = <server-public-key>
AllowedIPs = 0.0.0.0/0, ::/0
Endpoint = vpn.example.com:51820
PersistentKeepalive = 25
```

5. Tap **Grant VPN permission** once (system dialog).
6. Tap **Start monitoring**.

## How it works

```
MainActivity
    │  save config / start-stop
    ▼
WifiMonitorService  (foreground + persistent notification)
    │  ConnectivityManager.NetworkCallback
    ▼
WifiConnectivityMonitor ──► WiFi yes/no
    │
    ├── WiFi lost  → WireGuardManager.setTunnelUp(config)
    └── WiFi back  → WireGuardManager.setTunnelDown()
```

WireGuard uses the official userspace Go backend (`GoBackend$VpnService`).

## Permissions

| Permission | Why |
|------------|-----|
| `INTERNET` | Tunnel traffic |
| `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` | Detect WiFi |
| `FOREGROUND_SERVICE` / `SPECIAL_USE` | Keep monitor alive |
| `POST_NOTIFICATIONS` | Status notification (Android 13+) |
| `RECEIVE_BOOT_COMPLETED` | Resume monitoring after reboot |
| `BIND_VPN_SERVICE` (system) | Create the VPN interface |

## Notes

- Battery optimizers can still kill background work; exclude the app if needed.
- Only one VPN can be active on Android; another VPN app will conflict.
- `AllowedIPs = 0.0.0.0/0` routes all traffic through the tunnel when VPN is on.
- Test by disabling WiFi on the phone (use mobile data) and watching the notification switch to “No WiFi — VPN active”.

## License

Use freely for personal projects. WireGuard is a registered trademark of Jason A. Donenfeld.
