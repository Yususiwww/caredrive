package ai.caredrive.car.car

import ai.caredrive.car.MainActivity
import ai.caredrive.car.R
import ai.caredrive.car.model.CareState
import ai.caredrive.car.model.Level
import ai.caredrive.car.ui.Messages
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build

/**
 * L2/L3 as a system notification, so the driver sees it while navigation (or another app) is in front.
 * Same shape as the Pleos system popup: title, one line, confirm action first, at most two actions.
 */
class AlertNotifier(private val context: Context, private val onAction: (String) -> Unit) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            cancel()
            intent.getStringExtra(EXTRA)?.let(onAction)
        }
    }

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "케어드라이브 경고", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "경고와 정차 후 안내를 다른 앱 위에 띄워요"
                setSound(null, null) // the app plays its own level sound and voice
            }
        )
        context.registerReceiver(receiver, IntentFilter(ACTION), Context.RECEIVER_NOT_EXPORTED)
    }

    fun show(state: CareState) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val msg = Messages.forState(state)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_alert)
            .setContentTitle(msg.title)
            .setContentText(msg.body)
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
        if (state.level == Level.L2_WARNING && !state.guidingToRest) {
            n.addAction(action("휴게소 안내", ACCEPT, 1))
            n.addAction(action("괜찮아", DISMISS, 2))
        }
        manager.notify(ID, n.build())
    }

    fun cancel() = manager.cancel(ID)

    fun release() {
        cancel()
        context.unregisterReceiver(receiver)
    }

    private fun action(label: String, id: String, code: Int): Notification.Action {
        val intent = Intent(ACTION).setPackage(context.packageName).putExtra(EXTRA, id)
        val pi = PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(null, label, pi).build()
    }

    companion object {
        const val ACCEPT = "accept"
        const val DISMISS = "dismiss"
        private const val CHANNEL = "alerts"
        private const val ID = 1
        private const val ACTION = "ai.caredrive.car.ALERT_ACTION"
        private const val EXTRA = "id"
    }
}
