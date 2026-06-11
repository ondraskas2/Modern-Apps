package com.vayunmathur.launcher.search

data class CalendarDocument(
    val id: String,
    val title: String,
    val date: String = "",
    val location: String = "",
    val eventId: Long = 0
)
