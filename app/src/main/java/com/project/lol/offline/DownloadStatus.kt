package com.project.lol.offline

data class DownloadStatus(
    val active: Boolean = false,
    val batch: Boolean = false,
    val collection: String = "",
    val title: String = "",
    val artist: String = "",
    val stage: String = "",
    val index: Int = 0,
    val total: Int = 0,
    val saved: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val percent: Int = 0,
    val error: Boolean = false,
) {
    val processed: Int get() = saved + failed + skipped
}
