package com.wifivpn.app.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.wireguard.config.Config
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Android 17 local network protection (apps targeting API 37+).
 *
 * WireGuard's UDP socket lives in this app's UID, so a peer endpoint on a "local network"
 * address is blocked with EPERM unless [Manifest.permission.ACCESS_LOCAL_NETWORK] is held.
 * The symptom is otherwise indistinguishable from a dead server (no handshake).
 */
object LocalNetwork {

    /** True on API 37+ when the permission exists and has not been granted. */
    fun isPermissionMissing(context: Context): Boolean = permissionToRequest(context) != null

    /** The permission to add to a runtime request, or null when not applicable / granted. */
    fun permissionToRequest(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) return null
        val permission = Manifest.permission.ACCESS_LOCAL_NETWORK
        val granted = ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
        return if (granted) null else permission
    }

    /** Any peer endpoint in [config] is an IP literal the platform treats as local. */
    fun hasLocalEndpoint(config: Config): Boolean =
        config.peers.any { peer ->
            peer.endpoint.isPresent && isLocalAddressLiteral(peer.endpoint.get().host)
        }

    /** Local network blocked for this config: permission missing *and* a LAN endpoint. */
    fun blocksConfig(context: Context, config: Config?): Boolean =
        config != null && isPermissionMissing(context) && hasLocalEndpoint(config)

    /**
     * Whether [host] is an IP literal in a range Android 17 classifies as local network.
     * Hostnames return false: resolving them here would mean DNS on the caller's thread.
     */
    fun isLocalAddressLiteral(host: String): Boolean {
        val h = host.trim().removePrefix("[").removeSuffix("]")
        if (h.isEmpty()) return false
        ipv4Octets(h)?.let { return isLocalIpv4(it) }
        if (!h.contains(':')) return false
        // IPv6 literal: InetAddress parses it without a DNS lookup.
        val addr = runCatching { InetAddress.getByName(h) }.getOrNull() as? Inet6Address
            ?: return false
        return addr.isLinkLocalAddress || addr.isMulticastAddress
    }

    private fun ipv4Octets(h: String): IntArray? {
        val parts = h.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in 0..3) {
            val p = parts[i]
            if (p.isEmpty() || p.length > 3 || !p.all { it.isDigit() }) return null
            val v = p.toInt()
            if (v > 255) return null
            octets[i] = v
        }
        return octets
    }

    // Ranges from developer.android.com/privacy-and-security/local-network-definition
    private fun isLocalIpv4(o: IntArray): Boolean {
        val (a, b) = o[0] to o[1]
        return a == 10 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254) ||
            (a == 100 && b in 64..127) ||
            a in 224..239 ||
            o.all { it == 255 }
    }
}
