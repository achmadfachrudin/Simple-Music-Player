package com.simplemobiletools.musicplayer

import android.app.Application
import com.google.android.gms.ads.MobileAds
import com.simplemobiletools.commons.extensions.checkUseEnglish

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        checkUseEnglish()
        MobileAds.initialize(this)
    }
}
