package net.atlasauth.atlas

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigInteger

class DeviceKeyTest {
    @Test
    fun messageMatchesServerFormat() {
        assertEquals(
            "atlas-device-key:v1:sign_in:ins_1:dvk_1:n",
            DeviceKeyCodec.message("sign_in", "ins_1", "dvk_1", "n"),
        )
    }

    @Test
    fun p1363PadsAndTrimsDerIntegers() {
        // r = 00 80 (leading zero for sign), s = 01
        val der = byteArrayOf(0x30, 0x07, 0x02, 0x02, 0x00, 0x80.toByte(), 0x02, 0x01, 0x01)
        val raw = DeviceKeyCodec.p1363(der)!!
        assertEquals(64, raw.size)
        assertEquals(0x80.toByte(), raw[31])
        assertEquals(0x01.toByte(), raw[63])
    }

    @Test
    fun p1363RejectsMalformed() {
        assertNull(DeviceKeyCodec.p1363(byteArrayOf()))
        assertNull(DeviceKeyCodec.p1363(byteArrayOf(0x31, 0x00)))
        assertNull(DeviceKeyCodec.p1363(byteArrayOf(0x30, 0x03, 0x02, 0x01)))
    }

    @Test
    fun jwkPadsCoordinatesTo32Bytes() {
        val jwk = DeviceKeyCodec.jwk(BigInteger.ONE, BigInteger.TEN)
        val x = java.util.Base64.getUrlDecoder().decode(jwk.x)
        assertEquals(32, x.size)
        assertArrayEquals(ByteArray(31) + byteArrayOf(1), x)
    }
}
