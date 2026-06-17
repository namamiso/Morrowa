package app.morrowa

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.lawnchair.LawnchairLauncher

object NotificationHelper {
    private const val CHANNEL_ALARM = "morrowa_alarm"
    private const val CHANNEL_HABIT_REMINDER = "morrowa_habit_reminder"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALARM, "Morrowa アラーム", NotificationManager.IMPORTANCE_HIGH),
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_HABIT_REMINDER,
                "未達習慣リマインダー",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    fun showAlarmNotification(
        context: Context,
        notificationId: Int,
        title: String,
        targetPage: String,
    ) {
        val intent = Intent(context, LawnchairLauncher::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            putExtra(EXTRA_MORROWA_PAGE, targetPage.toMorrowaStoredPage())
        }
        val pi = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    fun showIncompleteHabitNotification(context: Context, count: Int) {
        val intent = Intent(context, LawnchairLauncher::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            putExtra(EXTRA_MORROWA_PAGE, MorrowaPage.HABIT.storedValue)
        }
        val pi = PendingIntent.getActivity(
            context,
            NOTIF_ID_INCOMPLETE_HABIT,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_HABIT_REMINDER)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("未達習慣があります")
            .setContentText("${count}件の習慣が今日未完了です")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIF_ID_INCOMPLETE_HABIT, notification)
    }

    private fun String.toMorrowaStoredPage(): String = when (this) {
        MorrowaPage.HABIT.name, MorrowaPage.HABIT.storedValue -> MorrowaPage.HABIT.storedValue
        MorrowaPage.TODO.name, MorrowaPage.TODO.storedValue -> MorrowaPage.TODO.storedValue
        else -> MorrowaPage.HOME.storedValue
    }

    const val EXTRA_MORROWA_PAGE = "morrowa_target_page"
    const val NOTIF_ID_INCOMPLETE_HABIT = 1_000_000
}
