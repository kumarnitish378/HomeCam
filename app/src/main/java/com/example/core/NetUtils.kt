package com.example.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface

/**
 * Network utilities for finding LAN and Tailscale addresses, and verifying IP source allow-lists.
 */
object NetUtils {

    data class NetworkAddress(
        val ip: String,
        val interfaceName: String,
        val isTailscale: Boolean
    )

    /**
     * Checks if the incoming IP is within allowed private or Tailscale networks.
     * Rejects IPv6 addresses to prevent exposure to public IPv6 addresses.
     */
    fun isAllowedSource(ip: String, tailscaleOnly: Boolean = false): Boolean {
        // Strip any port or IPv6 brackets
        val cleanIp = ip.removeSurrounding("[", "]").substringBefore(':')
        val parts = cleanIp.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false // Rejects IPv6

        val isTailscale = parts[0] == 100 && parts[1] in 64..127
        val isLocalhost = parts[0] == 127

        if (tailscaleOnly) {
            return isTailscale || isLocalhost
        }

        val isLan10 = parts[0] == 10
        val isLan192 = parts[0] == 192 && parts[1] == 168
        val isLan172 = parts[0] == 172 && parts[1] in 16..31

        return isTailscale || isLocalhost || isLan10 || isLan192 || isLan172
    }

    /**
     * Returns true if the IP belongs to Tailscale CGNAT subnet (100.64.0.0/10).
     */
    fun isTailscaleIp(ip: String): Boolean {
        val parts = ip.split('.').mapNotNull { it.toIntOrNull() }
        return parts.size == 4 && parts[0] == 100 && parts[1] in 64..127
    }

    /**
     * Discovers all available IPv4 addresses on the device (LAN and Tailscale).
     */
    fun getLocalIpv4Addresses(): List<NetworkAddress> {
        val result = mutableListOf<NetworkAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val addresses = intf.inetAddresses
                for (addr in addresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val hostAddress = addr.hostAddress ?: continue
                        val isTailscale = isTailscaleIp(hostAddress) ||
                                intf.name.contains("tailscale", ignoreCase = true) ||
                                intf.name.contains("tun", ignoreCase = true)
                        result.add(
                            NetworkAddress(
                                ip = hostAddress,
                                interfaceName = intf.displayName ?: intf.name,
                                isTailscale = isTailscale
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore network discovery errors
        }
        return result
    }

    /**
     * Returns primary Tailscale IP if available.
     */
    fun findTailscaleIp(): String? {
        val addresses = getLocalIpv4Addresses()
        return addresses.firstOrNull { it.isTailscale }?.ip
    }

    /**
     * Returns primary LAN IP (192.168.x.x, 10.x.x.x, 172.x.x.x).
     */
    fun findLanIp(): String? {
        val addresses = getLocalIpv4Addresses()
        return addresses.firstOrNull { !it.isTailscale }?.ip
    }

    // Linux address flags (from if_addr.h), exposed by LinkAddress.getFlags()
    private const val IFA_F_TEMPORARY = 0x01
    private const val IFA_F_DEPRECATED = 0x20

    /** True for a global unicast IPv6 address (2000::/3). */
    fun isGlobalIpv6(addr: Inet6Address): Boolean =
        (addr.address[0].toInt() and 0xE0) == 0x20

    /**
     * The phone's stable public IPv6 address on Wi-Fi/Ethernet, or null if it has none.
     * Temporary (privacy) addresses are skipped because they change every few hours.
     * The Tailscale VPN network is ignored.
     */
    @Suppress("DEPRECATION")
    fun findGlobalIpv6(context: Context): String? {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            var fallback: String? = null
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                ) continue
                val lp = cm.getLinkProperties(network) ?: continue
                for (la in lp.linkAddresses) {
                    val addr = la.address as? Inet6Address ?: continue
                    if (!isGlobalIpv6(addr)) continue
                    val text = addr.hostAddress?.substringBefore('%') ?: continue
                    if (la.flags and (IFA_F_TEMPORARY or IFA_F_DEPRECATED) == 0) return text
                    if (fallback == null) fallback = text
                }
            }
            return fallback
        } catch (_: Exception) {
            return null
        }
    }

    /** Puts IPv6 literals in brackets for use in a URL: 2409::1 -> [2409::1]. */
    fun hostForUrl(host: String): String =
        if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
}
