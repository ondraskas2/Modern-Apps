package com.vayunmathur.passwords.cable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the assigned tunnel-domain table (the verifiable part of [TunnelDomains]). */
class TunnelDomainsTest {

    @Test fun assignedDomains() {
        assertEquals("cable.ua5v.com", TunnelDomains.decode(0))
        assertEquals("cable.auth.com", TunnelDomains.decode(1))
        assertEquals(0, TunnelDomains.DEFAULT_ID)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownAssignedIdThrows() {
        TunnelDomains.decode(2)
    }

    @Test fun hexRoundTrip() {
        val bytes = byteArrayOf(0x00, 0x1f, 0x7a.toByte(), 0xff.toByte())
        assertEquals("001f7aff", CableTunnel.hex(bytes))
    }

    @Test fun algorithmicDomainIsDeterministic() {
        val a = TunnelDomains.decode(300)
        val b = TunnelDomains.decode(300)
        assertEquals(a, b)
        assertTrue(a.startsWith("cable."))
    }
}
