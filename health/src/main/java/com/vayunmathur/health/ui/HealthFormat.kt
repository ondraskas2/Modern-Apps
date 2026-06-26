package com.vayunmathur.health.ui

import android.content.Context
import com.vayunmathur.health.R
import kotlin.time.Duration.Companion.minutes

/** Formats a minute count as "Xh Ym" or "Ym". */
fun hoursMinutesString(context: Context, minutes: Long): String =
    minutes.minutes.toComponents { h, m, _, _ ->
        if (h > 0) context.getString(R.string.hours_minutes_format, h, m)
        else context.getString(R.string.minutes_format, m)
    }
