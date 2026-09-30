package dev.landrop.mobile

import org.junit.Assert.*
import org.junit.Test

class DiscoveryTargetsTest {
    @Test fun hotspotClientsOutsideTheOldPoolAreProbed() {
        val targets = subnetProbeTargets("192.168.43.87", 24).map { it.hostAddress }.toSet()
        assertTrue("192.168.43.210" in targets)
        assertTrue("192.168.43.1" in targets)
        assertFalse("192.168.43.87" in targets)
        assertFalse("192.168.43.255" in targets)
        assertEquals(253, targets.size)
    }

    @Test fun smallSubnetsExcludeNetworkBroadcastAndSelf() {
        assertEquals(setOf("172.20.10.1", "172.20.10.3", "172.20.10.4",
            "172.20.10.5", "172.20.10.6"),
            subnetProbeTargets("172.20.10.2", 29).map { it.hostAddress }.toSet())
    }

    @Test fun largeSubnetsProbeTheLocalSlice() {
        val targets = subnetProbeTargets("10.42.7.80", 16).map { it.hostAddress }
        assertTrue("10.42.7.200" in targets)
        assertTrue(targets.size <= 254)
    }

    @Test fun invalidAddressesAreIgnored() {
        assertTrue(subnetProbeTargets("::1", 24).isEmpty())
        assertTrue(subnetProbeTargets("300.1.2.3", 24).isEmpty())
        assertTrue(subnetProbeTargets("192.168.1.1", 32).isEmpty())
    }
}
