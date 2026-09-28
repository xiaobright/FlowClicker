package com.flowclicker.app

internal enum class CaptureNotificationAction { REQUEST, WARN, START }

internal fun captureNotificationAction(
    sdk: Int,
    granted: Boolean,
    askedBefore: Boolean,
    notificationsEnabled: Boolean,
): CaptureNotificationAction = when {
    sdk >= 33 && !granted && !askedBefore -> CaptureNotificationAction.REQUEST
    (sdk >= 33 && !granted) || !notificationsEnabled -> CaptureNotificationAction.WARN
    else -> CaptureNotificationAction.START
}
