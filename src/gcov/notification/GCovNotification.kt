package gcov.notification

import com.intellij.notification.NotificationDisplayType
import com.intellij.notification.NotificationGroup

object GCovNotification {
    val GROUP_DISPLAY_ID_INFO = NotificationGroup("GCoverage", NotificationDisplayType.BALLOON, true)
}
