# Security policy

WiFi VPN decides when a WireGuard tunnel is up based on which Wi‑Fi network the device is on, and it stores the WireGuard configuration on the device. Please report vulnerabilities privately so they can be fixed before details are public.

## Supported versions

Security fixes go into the current release line published on [GitHub Releases](https://github.com/offsyanka99/WiFi-VPN/releases). Older APKs are not patched in place.

| Version | Supported |
|---------|-----------|
| 1.4.12 (latest) | Yes |
| Older than 1.4.12 | No |

## Reporting a vulnerability

Email [wifi-vpn@mailbox.org](mailto:wifi-vpn@mailbox.org). Do not open a public GitHub issue for an unfixed vulnerability.

Include:

- A short description of the impact (for example: tunnel stays down on an untrusted network, config or diagnostic data leaves the device, or another app can start or stop the tunnel).
- App version (About dialog or the APK file name) and Android version.
- Steps to reproduce, or a proof of concept that stays on your own device.
- Whether you want credit in the release notes.

You should receive an acknowledgement within 7 days. A fix or a clear “not a vulnerability” decision is the goal within 90 days when the report is valid and reproducible. Please give the maintainer a chance to ship a release before publishing details.

## In scope

- Trusted-network detection that fails open (VPN stays off on a network that is not trusted).
- WireGuard configuration storage, import, and decryption.
- Start, stop, or toggle of the tunnel from the notification, tile, or widgets without the app’s own authorization.
- Diagnostic log contents that include secrets, SSIDs, access-point addresses, or the VPN server host after redaction is supposed to apply.
- Release signing, dependency, or CI changes that would let an untrusted build be published as this project.

## Out of scope

- The WireGuard protocol itself, or a VPN server you do not control.
- A device that is already rooted, or an attacker who can run code as the app.
- Denial of service that only drains battery or shows a wrong label, with no trust or confidentiality impact.
- Issues in dependencies that do not affect this app’s build or runtime behavior. Report those upstream.

This project is maintained by one person. There is no bug-bounty program.
