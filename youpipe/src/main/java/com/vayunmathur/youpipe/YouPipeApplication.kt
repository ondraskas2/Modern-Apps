package com.vayunmathur.youpipe

import android.app.Application
import com.vayunmathur.youpipe.util.MyDownloader
import org.schabi.newpipe.extractor.NewPipe

class YouPipeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NewPipe.init(MyDownloader())
    }
}
