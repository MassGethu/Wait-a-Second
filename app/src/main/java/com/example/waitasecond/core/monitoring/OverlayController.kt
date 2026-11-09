package com.example.waitasecond.core.monitoring

data class OverlayData(
    val token: String, val topic: String, val context: AppContext, val remainingMillis: Long,
    val isDmCheckin: Boolean = false
)
interface OverlayController {
    fun showOverlay(data: OverlayData)
    fun dismissOverlay()
}
