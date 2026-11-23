package com.cartube.app

import androidx.multidex.MultiDexApplication
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager

class CarTubeApp : MultiDexApplication() {
    override fun onCreate() {
        super.onCreate()
        // Initialize MediaServiceCore global preferences before any YouTubeServiceManager call
        GlobalPreferences.instance(this)
        YouTubeServiceManager.instance().warmUpCacheIfNeeded()
    }
}
