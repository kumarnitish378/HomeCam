package com.example.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Generates and validates access tokens for HomeCam streams and API endpoints.
 * Implements constant-time token comparison and brute-force rate limiting.
 */
object AuthToken {

    /**
     * Generates a random 32-byte URL-safe token.
     * Uses standard java.util.Base64 (available in Android minSdk 26+).
     */
    fun newToken(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Constant-time comparison to prevent timing attacks.
     */
    fun verifyToken(given: String?, expected: String): Boolean {
        if (given.isNullOrBlank() || expected.isBlank()) return false
        val givenBytes = given.trim().toByteArray(Charsets.UTF_8)
        val expectedBytes = expected.trim().toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(givenBytes, expectedBytes)
    }

    // Rate limiter: after 10 failures within 60s from one address, block for 10 minutes.
    private data class IpFailureRecord(
        var count: Int = 0,
        var firstFailTime: Long = 0L,
        var blockedUntil: Long = 0L
    )

    private val failureMap = ConcurrentHashMap<String, IpFailureRecord>()

    fun isIpBlocked(ip: String): Boolean {
        val now = System.currentTimeMillis()
        val record = failureMap[ip] ?: return false
        if (record.blockedUntil > now) {
            return true
        }
        if (now - record.firstFailTime > 60_000) {
            failureMap.remove(ip)
        }
        return false
    }

    fun recordAuthFailure(ip: String) {
        val now = System.currentTimeMillis()
        val record = failureMap.computeIfAbsent(ip) {
            IpFailureRecord(count = 0, firstFailTime = now)
        }
        if (now - record.firstFailTime > 60_000) {
            record.count = 1
            record.firstFailTime = now
            record.blockedUntil = 0L
        } else {
            record.count++
            if (record.count >= 10) {
                record.blockedUntil = now + (10 * 60 * 1000L) // 10 minutes block
            }
        }
    }

    fun recordAuthSuccess(ip: String) {
        failureMap.remove(ip)
    }
}
