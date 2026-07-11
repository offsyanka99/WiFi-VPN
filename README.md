# WiFi VPN

Android app that monitors **trusted Wi‑Fi networks** in the background and automatically controls a **WireGuard** tunnel:

| Network state | VPN action |
|---------------|------------|
| Connected to a **trusted** SSID | VPN **off** |
| Other Wi‑Fi, or no Wi‑Fi | VPN **on** |

Built with **Kotlin + Jetpack** (Foreground Service, ConnectivityManager, DataStore, Material 3) and the official WireGuard tunnel library (`com.wireguard.android:tunnel`).

## Features

- **Trusted Wi‑Fi list** — add SSIDs manually or from the current network; VPN turns off only on those networks
- **Foreground service** with a persistent status notification while monitoring
- **WireGuard** tunnel via the userspace Go backend (`GoBackend$VpnService`)
- Load config from a `.conf` file (system file picker); stored in DataStore
- **Exclude apps** from the VPN (e.g. Android Auto); multi-select list with search
- VPN connect **retries** (up to 5 attempts, 5s apart) with progress in the notification
- **Auto-start after reboot** (optional switch; requires config + at least one trusted SSID)
- **Quick Settings tile** to start/stop monitoring
- Grant VPN permission, location / nearby Wi‑Fi permission (needed to read SSIDs), and notification permission

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
# APK: app/build/outputs/apk/debug/wifi-vpn-debug.apk
adb install -r app/build/outputs/apk/debug/wifi-vpn-debug.apk
```

Release builds use signing from `keystore.properties` (see `app/build.gradle.kts`). Keystore files and that properties file are gitignored.

```bash
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/wifi-vpn-release.apk
```

## Setup

1. Open this folder in Android Studio (optional) **or** use Gradle CLI above.
2. Sync Gradle and build the `app` module.
3. Install on a device with USB debugging (VPN APIs required; physical device recommended).
4. In the app, tap **Load config file…** and pick a WireGuard `.conf`, for example:

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

5. Add at least one **trusted Wi‑Fi** (type the SSID or use **Add current network**). Grant location / nearby Wi‑Fi permission if prompted.
6. Optionally choose **Exclude applications from VPN**.
7. Tap **Grant VPN permission** once (system dialog).
8. Tap **Start monitoring**. Optionally enable **Auto-start after reboot**, or add the **WiFi VPN** Quick Settings tile.

## How it works

```
MainActivity / Quick Settings tile / BootReceiver
    │  config, trusted SSIDs, start-stop
    ▼
WifiMonitorService  (foreground + persistent notification)
    │  ConnectivityManager + trusted SSID list (DataStore)
    ▼
WifiConnectivityMonitor ──► connected? SSID? trusted?
    │
    ├── On trusted Wi‑Fi  → WireGuardManager.setTunnelDown()
    └── Other / no Wi‑Fi  → WireGuardManager.setTunnelUp(config, excludedApps)
                              (retries on failure)
```

Policy (see `WifiMonitorService`):

- Connected to a **trusted** SSID → VPN off  
- Any other Wi‑Fi, or no Wi‑Fi → VPN on  

WireGuard uses the official userspace Go backend (`GoBackend$VpnService`). Preferences (config, trusted SSIDs, exclusions, auto-start, monitoring flag) live in **DataStore**.

## Permissions

| Permission | Why |
|------------|-----|
| `INTERNET` | Tunnel traffic |
| `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` | Detect Wi‑Fi |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Read current SSID (Android requirement) |
| `NEARBY_WIFI_DEVICES` | Read SSID on Android 13+ without full location use |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` | Keep monitor alive |
| `POST_NOTIFICATIONS` | Status notification (Android 13+) |
| `RECEIVE_BOOT_COMPLETED` | Resume monitoring after reboot when auto-start is on |
| `WAKE_LOCK` | Reliable service work around sleep |
| `QUERY_ALL_PACKAGES` | List installed apps for VPN exclusion |
| `BIND_VPN_SERVICE` (system) | Create the VPN interface |
| `BIND_QUICK_SETTINGS_TILE` (system) | Quick Settings tile |

## Notes

- Battery optimizers can still kill background work; exclude the app if needed.
- Only one VPN can be active on Android; another VPN app will conflict.
- `AllowedIPs = 0.0.0.0/0` routes all traffic through the tunnel when VPN is on.
- SSID reading often needs **location services enabled** on the device, not only the runtime permission.
- Test by leaving a trusted home Wi‑Fi (or turning Wi‑Fi off on mobile data) and watching the notification switch to “Other Wi‑Fi — VPN active” or “No Wi‑Fi — VPN active”.
- Changes to excluded apps apply the **next** time the tunnel starts.

## License

Use freely for personal projects. WireGuard is a registered trademark of Jason A. Donenfeld.
