package com.vayunmathur.messages.telegram.mtproto.crypto

import java.math.BigInteger
import java.security.SecureRandom

object PqMath {
    private val random = SecureRandom()

    fun decompose(pq: BigInteger): Pair<BigInteger, BigInteger> {
        val zero = BigInteger.ZERO
        val one = BigInteger.ONE
        val fifteen = BigInteger.valueOf(15)
        val seventeen = BigInteger.valueOf(17)
        val rndMax = one.shiftLeft(64)

        var what = pq
        var g = BigInteger.ZERO
        var i = 0

        while (!(g > one && g < what)) {
            var v = BigInteger(64, random).and(fifteen).add(seventeen).mod(what)
            var x = BigInteger(64, random).mod(what.subtract(one)).add(one)
            var y = x
            val lim = 1 shl (i + 18)
            var j = 1
            var flag = true

            while (j < lim && flag) {
                var a = x
                var b = x
                var c = v

                while (b > zero) {
                    if (b.testBit(0)) {
                        c = c.add(a)
                        if (c >= what) c = c.subtract(what)
                    }
                    a = a.add(a)
                    if (a >= what) a = a.subtract(what)
                    b = b.shiftRight(1)
                }
                x = c

                val z = if (x < y) what.add(x).subtract(y) else x.subtract(y)
                g = z.gcd(what)

                if ((j and (j - 1)) == 0) y = x
                j++
                if (g != one) flag = false
            }
            i++
        }

        var p = g
        var q = what.divide(g)
        if (p > q) {
            val tmp = p; p = q; q = tmp
        }
        return p to q
    }
}
