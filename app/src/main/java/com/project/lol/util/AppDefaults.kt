package com.project.lol.util

import android.content.SharedPreferences

object AppDefaults {

    fun enforce(prefs: SharedPreferences) {
        prefs.edit()
            .putBoolean("HideTopBar", false)
            .putBoolean("LandscapeMode", true)
            .putBoolean("ShowScrollbar", true)
            .putString("APlayMode", "disabled")
            .putString("PlayerMode", "spotilol")
            .putBoolean("HideEmptyPlayer", false)
            .putBoolean("PlaylistSortEnabled", true)
            .putBoolean("TakeControl", true)
            .putBoolean("AndAuto", true)
            .putBoolean("CloseNowPlay", true)
            .putBoolean("SwipeStop", true)
            .putBoolean("BlockServiceWorker", true)
            .putBoolean("DlTags", true)
            .putString("DlFormat", "MP3")
            .putBoolean("BtAutoPause", true)
            .apply()
    }
}
