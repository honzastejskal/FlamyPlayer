package io.github.flamy69.flamyplayer.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubnetBlockTest {

    private fun ip(a: Int, b: Int, c: Int, d: Int): Int = (a shl 24) or (b shl 16) or (c shl 8) or d

    @Test
    fun `a block is the 254 addresses around the address, itself included`() {
        val block = Subnet.block(ip(10, 10, 1, 1))
        assertEquals(254, block.size)
        assertEquals(ip(10, 10, 1, 1), block.first())
        assertEquals(ip(10, 10, 1, 254), block.last())
    }

    @Test
    fun `contains tells whether an address is in the same network`() {
        val tv = ip(10, 10, 10, 3)
        assertTrue(Subnet.contains(tv, 16, ip(10, 10, 1, 1)))
        assertFalse(Subnet.contains(tv, 16, ip(10, 11, 1, 1)))
        assertFalse(Subnet.contains(tv, 24, ip(10, 10, 1, 1)))
    }
}
