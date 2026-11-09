package com.example

import com.example.core.AuthToken
import com.example.core.NetUtils
import com.example.pairing.PairingInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun authToken_generatesValidTokenAndVerifies() {
        val token = AuthToken.newToken()
        assertNotNull(token)
        assertTrue(token.length >= 32)
        assertTrue(AuthToken.verifyToken(token, token))
        assertFalse(AuthToken.verifyToken("wrong_token", token))
        assertFalse(AuthToken.verifyToken("", token))
        assertFalse(AuthToken.verifyToken(null, token))
    }

    @Test
    fun netUtils_allowsTailscaleAndPrivateLan() {
        // Tailscale CGNAT 100.64.0.0/10
        assertTrue(NetUtils.isAllowedSource("100.64.0.1"))
        assertTrue(NetUtils.isAllowedSource("100.126.131.35"))
        assertTrue(NetUtils.isTailscaleIp("100.126.131.35"))

        // Standard private subnets
        assertTrue(NetUtils.isAllowedSource("192.168.1.100"))
        assertTrue(NetUtils.isAllowedSource("10.0.0.5"))
        assertTrue(NetUtils.isAllowedSource("172.16.0.2"))
        assertTrue(NetUtils.isAllowedSource("127.0.0.1"))

        // Rejects public IPv4
        assertFalse(NetUtils.isAllowedSource("8.8.8.8"))
        assertFalse(NetUtils.isAllowedSource("1.1.1.1"))

        // Rejects IPv6 to protect against public IPv6 exposure
        assertFalse(NetUtils.isAllowedSource("2409:4080:1234:5678::1"))
        assertFalse(NetUtils.isAllowedSource("fe80::1"))
    }

    @Test
    fun netUtils_tailscaleOnlyMode() {
        // When tailscaleOnly is true, LAN IPs must be rejected
        assertFalse(NetUtils.isAllowedSource("192.168.1.100", tailscaleOnly = true))
        assertFalse(NetUtils.isAllowedSource("10.0.0.5", tailscaleOnly = true))

        // Tailscale and localhost must still be accepted
        assertTrue(NetUtils.isAllowedSource("100.126.131.35", tailscaleOnly = true))
        assertTrue(NetUtils.isAllowedSource("127.0.0.1", tailscaleOnly = true))
    }

    @Test
    fun pairingInfo_serializesAndParsesUri() {
        val info = PairingInfo(
            name = "Front Gate",
            host = "100.126.131.35",
            port = 8080,
            token = "secretToken123"
        )
        val uri = info.toUriString()
        assertTrue(uri.startsWith("homecam://pair"))

        val parsed = PairingInfo.fromUriString(uri)
        assertNotNull(parsed)
        assertEquals("Front Gate", parsed?.name)
        assertEquals("100.126.131.35", parsed?.host)
        assertEquals(8080, parsed?.port)
        assertEquals("secretToken123", parsed?.token)
    }

    @Test
    fun pairingInfo_parsesHttpFallback() {
        val httpUrl = "http://100.126.131.35:8080/stream.mjpg?t=myToken"
        val parsed = PairingInfo.fromUriString(httpUrl)
        assertNotNull(parsed)
        assertEquals("100.126.131.35", parsed?.host)
        assertEquals(8080, parsed?.port)
        assertEquals("myToken", parsed?.token)
    }
}
