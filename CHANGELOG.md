# Changelog

All notable changes to WiFi VPN are recorded here. The app version is `versionName` in `app/build.gradle.kts`. Release APKs are on [GitHub Releases](https://github.com/offsyanka99/WiFi-VPN/releases).

## [Unreleased]

- **Toolchain:** Gradle **9.8.1** (security fixes for 9.8.0). Distribution checksum is pinned in the wrapper.

## [1.4.13] — 2026-10-07

- **Contact:** project email is [hummersoft@mailbox.org](mailto:hummersoft@mailbox.org) (About dialog, diagnostic-log share, and security reports)

## [1.4.12] — 2026-09-25

Includes all changes from the unpublished 1.4.11 test build.

- **Security — fail closed on unknown Wi‑Fi:** a hidden SSID is treated as trusted only when the access point's BSSID / network id positively matches a remembered trusted network; the "sole remembered SSID" fallback is removed
- **Security — bounded SSID wait:** after reboot / app update, the VPN comes up after at most **90 s** if the network name stays hidden (previously waited indefinitely with VPN off); it still turns off as soon as a trusted name appears
- **Trusted Wi‑Fi after reboot:** with auto-start, Android gives a monitor started at boot no location access unless location is allowed **"All the time"**, so the Wi‑Fi name stayed hidden until the app was opened. New **Configuration → Location all the time** switch (with **(i)** help) shows and changes this setting; the app also offers it when auto-start is enabled and after the problem occurs. Without it, opening the app once restores location access for the rest of the session, and the notification / widget say why the name is hidden
- **Diagnostic log privacy:** SSIDs, access point addresses and the VPN server host are replaced with per-device codes; excluded apps logged as a count only; one-time confirmation before the first share; sending no longer re-enables logging. The log now records whether the monitor actually has location access
- **Config:** a saved config that can no longer be decrypted (device security keys changed) is reported as such instead of looking like "no config loaded"
- **Android 17:** `targetSdk` **37**; requests **Nearby devices → local network** access so a VPN server on a private / CGNAT address keeps working, with a clear message if it is denied
- **Reliability:** cancelled VPN connects are no longer logged as failures; retry decisions use typed WireGuard errors instead of message text
- **Performance:** encrypted-config and log-file work moved off the main thread
- **Toolchain:** AGP **9.4.1**, Gradle **9.8**, `compileSdk` **37**; core-ktx 1.19, AppCompat 1.8, WorkManager 2.12, Robolectric 4.17

## [1.4.10] — 2026-08-18

- **Status labels:** monitoring and VPN show **ON** / **OFF** (main screen, 2×2 widget, 4×1 widget)
- **4×1 widget:** first line is tunnel name + **Monitoring: ON/OFF**; second line is **VPN: ON/OFF**; transfer / handshake unchanged

## [1.4.9] — 2026-07-30

- **Peer health:** if handshake is older than ~4 minutes, tear down and reconnect with configured retries; wait for a live handshake before treating VPN as up; status on UI / notification / widgets
- **Boot / SSID:** register location-info network callbacks before policy; location bridge for background SSID; persist trusted association (`networkId`/`BSSID` → SSID); sole-SSID memory only when association key is unavailable (not when key is known but untrusted)
- **Security:** internal auth token for tile/widget/notification start-stop and widget toggle; serialized VPN policy decisions; backup excludes Keystore prefs v2; config import size cap (512 KiB); remove unused `CHANGE_NETWORK_STATE`
- Fix: peer-dead detection no longer resets session clock on stats poll restart

## [1.4.8] — 2026-07-29

- **Toolchain:** AGP **9.3**, Gradle **9.6**, built-in Kotlin, `compileSdk`/`targetSdk` **36**, Gradle version catalog
- Dependency bumps (WireGuard tunnel **1.0.20260102**, Material 1.14, Lifecycle, WorkManager, DataStore, etc.)
- **Security:** WireGuard secrets use **Android Keystore AES-GCM** storage (one-time migration from EncryptedSharedPreferences)
- After **phone reboot** (auto-start only): wait **5 s** before the first Wi‑Fi / cellular policy check; no delay when monitoring starts from UI / tile / widget
- When stopping monitoring **off trusted Wi‑Fi** while the **VPN is on**, show a warning that the connection will not be secure
- Configuration: **Wait between attempts** −/+ steps by **5 s**

## [1.4.7] — 2026-07-17

- **VPN transfer stats** on the main screen while the tunnel is on: download/upload speed, session totals, and last handshake age
- **Home-screen widgets**: show total received / sent and last handshake age when VPN is on
- Stats polled only while the main UI is visible or the monitor notification (tunnel) is active

## [1.4.6] — 2026-07-17

- **Battery**: ignore pure Wi‑Fi RSSI / non-policy `onCapabilitiesChanged` updates (fewer overnight snapshots)
- **Battery**: quieter screen-off SSID-cache logcat (DEBUG, once per association)
- **Battery**: diagnostic log periodic flush **7s → 45s**, still skips disk I/O when the buffer is empty

## [1.4.5] — 2026-07-13

- **4×1 widget**: summary no longer shows Mon on/off (Wi‑Fi · VPN only)
- **2×2 widget**: untrusted Wi‑Fi line no longer truncates with trailing “…” (`other network…`)
- **Configuration**: section **(i)** info icons (next to title) for trusted Wi‑Fi, excluded apps, VPN retries, and diagnostic log; help text moved into the info dialog

## [1.4.4] — 2026-07-12

- **Home-screen widgets**: **2×2** status card and **4×1** compact bar (monitoring, Wi‑Fi, VPN; tunnel name; Start/Stop)
- Widget **Start** uses a translucent trampoline Activity (Android 14+ FGS) without flashing the main UI
- Fix: widget button/state no longer stuck on Start after monitoring starts (wait for service state + reinforced widget updates)
- Widget Stop waits for monitoring to clear before refreshing the surface

## [1.4.3] — 2026-07-12

- Performance / reliability: debounced network decisions, buffered diagnostic log with periodic flush, R8 release builds
- Security: WireGuard config in encrypted storage; backup disabled for secrets
- Fix: after app update on trusted Wi‑Fi, VPN no longer turns on incorrectly (restore monitoring only if it was on; wait for SSID / trusted list)
- APKs published via **GitHub Releases** (not stored in git)
- UI: localized monitoring status strings; richer diagnostics from earlier 1.4.x work

## [1.4.2] — 2026-07-12

- Richer diagnostic log: **permission/battery snapshot**, **SSID redacted / screen-off** flags, **config fingerprint** (no secrets), monitor **start source** (UI / tile / boot)
- **Support summary** line when monitoring starts and when sending the log by email
- Uncaught crashes and key exceptions recorded in the diagnostic log (crashes even if logging is off)

## [1.4.1] — 2026-07-12

- **Enable logging** toggle on the Diagnostic log section (opt-in); Send log / Clear log only active when logging is on
- Turning logging off asks whether to **delete** the log file or **keep** it on the device
- Diagnostic log also records **configuration changes** (WireGuard load/clear, trusted Wi‑Fi, exclusions, retries, auto-start, permissions, etc.)
- Log file soft cap **3 MiB** with streamed rotation (keeps recent half)

## [1.4.0] — 2026-07-12

- **Diagnostic log** for multi-device troubleshooting: Wi‑Fi and cellular changes, SSID, VPN on/off, tunnel connect success/failure, and retry attempts
- Log file can be **sent via email** from Configuration (attachment + device info); optional clear log
- Release/debug APK filenames include the version: `wifi-vpn-<version>-{debug,release}.apk`
- About dialog: removed author line; year shown after the email

## [1.3.1] — 2026-07-11

- VPN retries UI: **−** / **+** text controls (not circular buttons), left-aligned with spacing around the value
- Default VPN retry **attempts = 10**

## [1.3] — 2026-07-11

- **VPN connection retries** configurable on the Configuration page (attempts 1–20, delay 1–120s) before the permission section
- **Weekly permission check** (WorkManager): notifies if location/nearby Wi‑Fi, notifications, VPN permission, or battery exemption is missing or disabled

## [1.2.2] — 2026-07-11

- **Quick Settings tile** subtitle shows **VPN on** / **VPN off** (instead of monitoring text)
- Fix crash when **enabling monitoring from the tile** on Android 14+ (location FGS must start from a foreground Activity; tile now starts via MainActivity)
- Safer FGS start fallback if the location type is rejected

## [1.2.1] — 2026-07-11

- Fix main-screen title overlapping the status bar on Pixel / Android 15+ (edge-to-edge system bar insets)

## [1.2] — 2026-07-11

- **Configuration** extras: **Grant VPN permission**, **Battery optimization** (allow background usage), and **Manage app if unused** (matches system toggle; leave off for reliable monitoring)
- Main screen simplified to status + start/stop monitoring
- **Quick Settings tile**: monochrome white-on-transparent icon; **label** shows tunnel name from the loaded config file; subtitle shows monitoring status
- Tile updates whenever QS is open (non-active `TileService`); note: **wide** tile size is set by the user in system QS edit (apps cannot force default width)
- VPN permission dialogs and messaging remain on the Configuration path

## [1.1.1] — 2026-07-11

- **Quick Settings tile** icon fixed as monochrome white-on-transparent (avoids solid white square)
- Note: tile **width** (small vs wide) is controlled by the system / user

## [1.1] — 2026-07-11

- **Configuration** screen — WireGuard config, trusted Wi‑Fi, excluded apps, and auto-start live on a dedicated page (footer: Configuration · About)
- Clearer **VPN permission** feedback: full dialogs instead of truncated toasts; logcat diagnostics for grant results
- New **app icon** and **Quick Settings tile** icon
- Configuration toolbar spacing so the title clears the system status bar

## [1.0] — 2026-07-10

- Initial release: trusted Wi‑Fi monitoring, WireGuard userspace tunnel, exclusions, auto-start, Quick Settings tile, screen-off SSID handling
