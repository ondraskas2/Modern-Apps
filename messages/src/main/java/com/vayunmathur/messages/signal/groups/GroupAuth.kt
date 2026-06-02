package com.vayunmathur.messages.signal.groups

import android.util.Base64

object GroupAuth {
    fun authHeader(aci: String, password: String): String {
        val creds = "$aci:$password"
        return "Basic " + Base64.encodeToString(creds.toByteArray(), Base64.NO_WRAP)
    }
}
