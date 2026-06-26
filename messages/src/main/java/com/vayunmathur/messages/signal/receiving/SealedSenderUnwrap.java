package com.vayunmathur.messages.signal.receiving;

import org.signal.libsignal.internal.Native;
import org.signal.libsignal.protocol.state.IdentityKeyStore;

/**
 * Bridges to libsignal's {@code SealedSessionCipher_DecryptToUsmc} native binding.
 *
 * <p>libsignal 0.86.5 has no public {@code SealedSessionCipher.decryptToUsmc()} method; the only
 * way to recover the {@code UnidentifiedSenderMessageContent} (and therefore the inner ciphertext
 * type and content hint) is the {@code org.signal.libsignal.internal.Native} binding, which is
 * marked Kotlin-{@code internal} and so cannot be referenced from Kotlin. Java interop does not
 * enforce Kotlin's {@code internal} visibility, so this thin Java shim exposes it to
 * {@code EnvelopeDecryptor}. // UNVERIFIED: relies on an internal libsignal binding.
 */
final class SealedSenderUnwrap {
    private SealedSenderUnwrap() {}

    static long decryptToUsmc(byte[] ciphertext, IdentityKeyStore identityStore) throws Exception {
        return Native.SealedSessionCipher_DecryptToUsmc(ciphertext, identityStore);
    }
}
