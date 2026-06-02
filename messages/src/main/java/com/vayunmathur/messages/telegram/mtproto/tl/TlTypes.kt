package com.vayunmathur.messages.telegram.mtproto.tl

import java.math.BigInteger

const val TYPE_VECTOR = 0x1cb5c415.toInt()
const val TYPE_TRUE = 0x997275b5.toInt()
const val TYPE_FALSE = 0xbc799737.toInt()

@JvmInline
value class Int128(val data: ByteArray = ByteArray(16)) {
    init { require(data.size == 16) }
    fun toBigInteger(): BigInteger = BigInteger(1, data)
}

@JvmInline
value class Int256(val data: ByteArray = ByteArray(32)) {
    init { require(data.size == 32) }
    fun toBigInteger(): BigInteger = BigInteger(1, data)
}

@JvmInline
value class Fields(val value: Int = 0) {
    fun has(bit: Int): Boolean = (value and (1 shl bit)) != 0
    fun set(bit: Int): Fields = Fields(value or (1 shl bit))
    fun unset(bit: Int): Fields = Fields(value and (1 shl bit).inv())

    fun encode(buf: TlBuffer) = buf.putInt32(value)

    companion object {
        fun decode(buf: TlBuffer): Fields = Fields(buf.int32())
    }
}

interface TlObject {
    val typeId: Int
    fun encode(buf: TlBuffer)

    companion object {
        fun decode(buf: TlBuffer): TlObject {
            throw UnsupportedOperationException("Use TlRegistry.decode()")
        }
    }
}

interface TlMethod<R : TlObject> : TlObject
