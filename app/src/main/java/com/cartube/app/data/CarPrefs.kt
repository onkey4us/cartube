package com.cartube.app.data

import android.content.Context

class CarPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var maxVideoHeight: Int
        get() = prefs.getInt(KEY_MAX_VIDEO_HEIGHT, 1080)
        set(value) = prefs.edit().putInt(KEY_MAX_VIDEO_HEIGHT, value).apply()

    var blockAv1: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_AV1, true)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK_AV1, value).apply()

    var backgroundAudioOnly: Boolean
        get() = prefs.getBoolean(KEY_BG_AUDIO_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_BG_AUDIO_ONLY, value).apply()

    var sponsorBlockEnabled: Boolean
        get() = prefs.getBoolean(KEY_SPONSOR_BLOCK, true)
        set(value) = prefs.edit().putBoolean(KEY_SPONSOR_BLOCK, value).apply()

    var preferredClientName: String?
        get() = prefs.getString(KEY_PREFERRED_CLIENT, null)
        set(value) = prefs.edit().putString(KEY_PREFERRED_CLIENT, value).apply()

    companion object {
        private const val PREFS_NAME = "cartube_prefs"
        private const val KEY_MAX_VIDEO_HEIGHT = "max_video_height"
        private const val KEY_BLOCK_AV1 = "block_av1"
        private const val KEY_BG_AUDIO_ONLY = "bg_audio_only"
        private const val KEY_SPONSOR_BLOCK = "sponsor_block"
        private const val KEY_PREFERRED_CLIENT = "preferred_client_name"
    }
}
