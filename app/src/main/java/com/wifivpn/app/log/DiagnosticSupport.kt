package com.wifivpn.app.log

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.wifivpn.app.WifiVpnApp
import com.wifivpn.app.network.WifiConnectivityMonitor
import com.wifivpn.app.service.WifiMonitorService
import com.wireguard.config.Config
import java.io.BufferedReader
import java.io.StringReader

/**
 * Compact diagnostic snapshots for the support log (no secrets).
 */
object DiagnosticSupport {

    /**
     * One-line permission / battery state for logs:
     * `loc=ok nearby=ok notif=ok vpn=ok battery=exempt`
     */
    fun permissionSnapshot(context: Context): String {
        val appCtx = context.applicationContext
        val loc = if (
            ContextCompat.checkSelfPermission(appCtx, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            "ok"
        } else {
            "no"
        }
        val nearby = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> "n/a"
            ContextCompat.checkSelfPermission(appCtx, Manifest.permission.NEARBY_WIFI_DEVICES)
                == PackageManager.PERMISSION_GRANTED -> "ok"
            else -> "no"
        }
        val notif = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> "n/a"
            ContextCompat.checkSelfPermission(appCtx, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED -> "ok"
            else -> "no"
        }
        val vpn = if (VpnService.prepare(appCtx) == null) "ok" else "no"
        val battery = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M -> "n/a"
            else -> {
                val pm = appCtx.getSystemService(PowerManager::class.java)
                if (pm?.isIgnoringBatteryOptimizations(appCtx.packageName) == true) {
                    "exempt"
                } else {
                    "restricted"
                }
            }
        }
        return "loc=$loc nearby=$nearby notif=$notif vpn=$vpn battery=$battery"
    }

    fun isScreenInteractive(context: Context): Boolean {
        val pm = context.applicationContext.getSystemService(PowerManager::class.java)
            ?: return true
        return pm.isInteractive
    }

    /**
     * WireGuard config summary without private keys or PSKs.
     * Example: `peers=1 addrs=1 dns=1 listen=- mtu=- p0{ep=vpn.example.com:51820 allowed=2}`
     */
    fun configFingerprint(rawConfig: String): String {
        if (rawConfig.isBlank()) return "empty"
        return try {
            val config = Config.parse(BufferedReader(StringReader(rawConfig)))
            val iface = config.`interface`
            val peers = config.peers
            val peerSummary = peers.mapIndexed { idx, peer ->
                val ep = if (peer.endpoint.isPresent) {
                    peer.endpoint.get().toString()
                } else {
                    "none"
                }
                "p$idx{ep=$ep allowed=${peer.allowedIps.size}}"
            }.joinToString(",")
            "peers=${peers.size} addrs=${iface.addresses.size} " +
                "dns=${iface.dnsServers.size} " +
                "listen=${iface.listenPort.map { it.toString() }.orElse("-")} " +
                "mtu=${iface.mtu.map { it.toString() }.orElse("-")}" +
                if (peerSummary.isNotEmpty()) " $peerSummary" else ""
        } catch (e: Exception) {
            "parse_error=${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * Writes a single SUPPORT line with app/device/config/permission state.
     * Call when monitoring starts or when the user sends the log by email.
     */
    suspend fun logSupportSummary(app: WifiVpnApp, reason: String) {
        if (!app.diagnosticLogger.isEnabled()) return
        val repo = app.configRepository
        val mon = WifiMonitorService.uiState.value
        val trusted = repo.getTrustedWifiSsids()
        val excluded = repo.getExcludedApps()
        val raw = repo.getWireGuardConfig()
        val snap = WifiConnectivityMonitor(app).snapshot(trusted)
        val version = appVersionLabel(app)
        val line = buildString {
            append("support summary ($reason): ")
            append("app=$version ")
            append("android=${Build.VERSION.RELEASE}(API${Build.VERSION.SDK_INT}) ")
            append("device=${Build.MANUFACTURER} ${Build.MODEL} ")
            append("monitoring=${if (mon.monitoring) "on" else "off"} ")
            append("tunnel=${if (app.wireGuardManager.isUp) "UP" else "DOWN"} ")
            append("trusted=${trusted.size} excluded=${excluded.size} ")
            append("auto_start=${if (repo.isAutoStartEnabled()) "on" else "off"} ")
            append("logging=${if (app.diagnosticLogger.isEnabled()) "on" else "off"} ")
            append("perms: ${permissionSnapshot(app)} ")
            append("ssid=${snap.ssid?.let { "\"$it\"" } ?: "none"} ")
            append("ssid_redacted=${snap.ssidRedacted} ")
            append("ssid_from_cache=${snap.ssidFromCache} ")
            append("screen=${if (snap.screenInteractive) "on" else "off"} ")
            append("transports=${snap.transports.ifEmpty { "none" }} ")
            append("config ${configFingerprint(raw)}")
        }
        app.diagnosticLogger.i("SUPPORT", line)
    }

    private fun appVersionLabel(context: Context): String {
        return try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0)
            }
            val name = info.versionName?.takeIf { it.isNotBlank() } ?: "?"
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "$name/$code"
        } catch (_: Exception) {
            "?"
        }
    }
}
