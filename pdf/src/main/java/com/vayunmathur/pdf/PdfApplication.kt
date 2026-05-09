package com.vayunmathur.pdf

import android.app.Application
import com.google.android.material.color.DynamicColors

class PdfApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
