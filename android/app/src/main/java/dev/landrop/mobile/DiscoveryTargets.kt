package dev.landrop.mobile

import java.net.InetAddress

internal fun subnetProbeTargets(ip: String?, prefixLength: Short): List<InetAddress> {
    if (ip == null || !ip.contains('.') || prefixLength !in 1..30) return emptyList()
    val octets = ip.split('.').mapNotNull { it.toIntOrNull() }
    if (octets.size != 4 || octets.any { it !in 0..255 }) return emptyList()
    val own = octets.fold(0) { acc, octet -> (acc shl 8) or octet }
    val mask = (-1 shl (32 - prefixLength)).toInt()
    val network = own and mask
    val broadcast = network or mask.inv()
    val candidates = mutableListOf<Int>()
    // Hotspots assign clients anywhere in the pool, not just .2 through .20.
    // Bound larger networks to the local /24 to keep discovery traffic small.
    val probeNetwork = if (prefixLength < 24) own and -256 else network
    val probeEnd = minOf(broadcast.toLong() and 0xffffffffL,
        (probeNetwork.toLong() and 0xffffffffL) + 255)
    for (host in 1..254) {
        val candidate = probeNetwork + host
        if (candidate != own && (candidate.toLong() and 0xffffffffL) < probeEnd) {
            candidates += candidate
        }
    }
    val gatewayLike = network + 254
    if (gatewayLike != own && gatewayLike < broadcast) {
        candidates += gatewayLike
    }
    return candidates.mapNotNull { candidate ->
        val host = listOf(
            (candidate ushr 24) and 0xff,
            (candidate ushr 16) and 0xff,
            (candidate ushr 8) and 0xff,
            candidate and 0xff
        ).joinToString(".")
        runCatching { InetAddress.getByName(host) }.getOrNull()
    }
}
