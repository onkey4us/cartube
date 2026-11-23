package com.cartube.app.data

import android.util.Log
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager

/**
 * Persists and restores the last working YouTube InnerTube client profile inside
 * MediaServiceCore's FormatInfoWrapper without modifying upstream MediaServiceCore files.
 */
object ClientSourceHelper {
    private const val TAG = "ClientSourceHelper"
    private const val WRAPPER_CLASS = "com.liskovsoft.youtubeapi.service.internal.FormatInfoWrapper"

    fun getCurrentClientKey(): String? {
        return runCatching {
            val clazz = Class.forName(WRAPPER_CLASS)
            val actualField = clazz.getDeclaredField("mActualClient").apply { isAccessible = true }
            val nextField = clazz.getDeclaredField("mNextClient").apply { isAccessible = true }
            val pair = (actualField.get(null) ?: nextField.get(null)) as? Pair<*, *> ?: return null
            (pair.first as? Enum<*>)?.name
        }.onFailure {
            Log.w(TAG, "Unable to read current client key: ${it.message}")
        }.getOrNull()
    }

    fun restoreClient(clientKey: String?): Boolean {
        if (clientKey.isNullOrBlank()) return false
        return runCatching {
            val clazz = Class.forName(WRAPPER_CLASS)
            val listField = clazz.getDeclaredField("CLIENT_LIST").apply { isAccessible = true }
            val clients = listField.get(null) as? Array<*> ?: return false
            val targetPair = clients.firstOrNull { entry ->
                val pair = entry as? Pair<*, *>
                (pair?.first as? Enum<*>)?.name.equals(clientKey, ignoreCase = true)
            } ?: return false

            val nextField = clazz.getDeclaredField("mNextClient").apply { isAccessible = true }
            val actualField = clazz.getDeclaredField("mActualClient").apply { isAccessible = true }
            nextField.set(null, targetPair)
            actualField.set(null, targetPair)
            YouTubeServiceManager.instance().invalidateFormatCache()
            Log.i(TAG, "Restored remembered YouTube client: $clientKey")
            true
        }.onFailure {
            Log.w(TAG, "Unable to restore client '$clientKey': ${it.message}")
        }.getOrDefault(false)
    }

    fun resetClient() {
        runCatching {
            val clazz = Class.forName(WRAPPER_CLASS)
            val nextField = clazz.getDeclaredField("mNextClient").apply { isAccessible = true }
            val actualField = clazz.getDeclaredField("mActualClient").apply { isAccessible = true }
            nextField.set(null, null)
            actualField.set(null, null)
            YouTubeServiceManager.instance().invalidateFormatCache()
            Log.i(TAG, "Reset YouTube client selection to default")
        }.onFailure {
            Log.w(TAG, "Unable to reset client: ${it.message}")
        }
    }
}
