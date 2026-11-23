package com.cartube.app

import androidx.multidex.MultiDexApplication
import com.cartube.app.data.CarPrefs
import com.cartube.app.data.ClientSourceHelper
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager

class CarTubeApp : MultiDexApplication() {
    override fun onCreate() {
        super.onCreate()
        // Initialize MediaServiceCore global preferences before any YouTubeServiceManager call
        GlobalPreferences.instance(this)
        // Restore last known working playback client before warming up cache so first video starts fast
        val savedClient = CarPrefs(this).preferredClientName
        if (!savedClient.isNullOrBlank()) {
            ClientSourceHelper.restoreClient(savedClient)
        }
        YouTubeServiceManager.instance().warmUpCacheIfNeeded()
    }
}
