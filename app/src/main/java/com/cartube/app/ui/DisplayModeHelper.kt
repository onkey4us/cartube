package com.cartube.app.ui

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Build
import android.view.Display

object DisplayModeHelper {

    enum class LayoutMode {
        FULLSCREEN,
        PIP_LARGE,
        PIP_COMPACT
    }

    @Suppress("DEPRECATION")
    fun getDisplayId(activity: Activity): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                activity.display?.displayId ?: Display.DEFAULT_DISPLAY
            } else {
                activity.windowManager?.defaultDisplay?.displayId ?: Display.DEFAULT_DISPLAY
            }
        } catch (_: Exception) {
            Display.DEFAULT_DISPLAY
        }
    }

    fun isEmbeddedOrPip(activity: Activity): Boolean {
        val displayId = getDisplayId(activity)
        val widthDp = activity.resources.configuration.screenWidthDp
        val inMultiWindow = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInMultiWindowMode
        val inPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity.isInPictureInPictureMode
        return displayId != Display.DEFAULT_DISPLAY || widthDp < 1400 || inMultiWindow || inPip
    }

    /**
     * Only real picture-in-picture needs a TextureView. In the launcher's embedded windows
     * (ActivityView virtual displays) a TextureView ties every video frame to a full UI redraw:
     * measured on the C3005H_MX8Q that is ~36 ms per frame, so 30 fps video showed at ~21 fps.
     * A SurfaceView hands frames straight to the compositor. See docs/DEVICE_PROFILE.md.
     */
    fun needsTextureView(activity: Activity): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity.isInPictureInPictureMode
    }

    fun getLayoutMode(activity: Activity): LayoutMode {
        val widthDp = activity.resources.configuration.screenWidthDp
        return when {
            widthDp < 600 -> LayoutMode.PIP_COMPACT
            widthDp < 1400 -> LayoutMode.PIP_LARGE
            else -> LayoutMode.FULLSCREEN
        }
    }

    fun launchOnMainDisplay(activity: Activity, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val options = ActivityOptions.makeBasic()
                .setLaunchDisplayId(Display.DEFAULT_DISPLAY)
                .toBundle()
            activity.startActivity(intent, options)
        } else {
            activity.startActivity(intent)
        }
    }
}
