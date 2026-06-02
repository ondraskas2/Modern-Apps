package com.vayunmathur.messages.signal.store

import org.signal.libsignal.protocol.groups.state.SenderKeyStore
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyStore
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SessionStore
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyStore

class SignalProtocolStoreImpl(
    private val sessionStore: SessionStore,
    private val identityKeyStore: IdentityKeyStore,
    private val preKeyStore: PreKeyStore,
    private val signedPreKeyStore: SignedPreKeyStore,
    private val kyberPreKeyStore: KyberPreKeyStore,
    private val senderKeyStore: SenderKeyStore,
) : SignalProtocolStore,
    SessionStore by sessionStore,
    IdentityKeyStore by identityKeyStore,
    PreKeyStore by preKeyStore,
    SignedPreKeyStore by signedPreKeyStore,
    KyberPreKeyStore by kyberPreKeyStore,
    SenderKeyStore by senderKeyStore
