package com.vayunmathur.e2ee

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignTest {
    @Test
    fun sign_verify_roundtrip_reusing_identity_key() = runBlocking {
        val kp = E2ee.generateKeyPair() // OAEP-generated key, reused for PSS signatures
        val data = "the quick brown fox".encodeToByteArray()
        val sig = E2ee.sign(kp.privateKeyPem, data)
        assertTrue("valid signature verifies", E2ee.verify(kp.publicKeyPem, data, sig))
        assertFalse("tampered data fails", E2ee.verify(kp.publicKeyPem, "different".encodeToByteArray(), sig))

        val other = E2ee.generateKeyPair()
        assertFalse("wrong key fails", E2ee.verify(other.publicKeyPem, data, sig))
    }
}
