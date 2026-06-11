package com.vayunmathur.launcher.search

data class ContactDocument(
    val id: String,
    val name: String,
    val phones: String = "",
    val lookupKey: String = ""
)
