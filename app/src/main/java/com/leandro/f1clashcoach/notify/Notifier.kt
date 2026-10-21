package com.leandro.f1clashcoach.notify

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.leandro.f1clashcoach.data.CoachData
import com.leandro.f1clashcoach.ui.MainActivity

/** Avisos no celular (mesmo com o jogo fechado): caixote, eventos acabando, objetivos, liga do clube. */
object Notifier {
    const val CHANNEL = "coach_alerts"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Avisos do Coach", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Caixote grátis, eventos acabando, objetivos, clube e análises"
        })
    }

    fun allowed(ctx: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
        ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun show(ctx: Context, id: Int, title: String, text: String) {
        if (!allowed(ctx)) return
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(ctx, id, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.star_on)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(id, n) }
    }
}

/** Dispara o aviso agendado. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Notifier.show(context, intent.getIntExtra("id", 1), intent.getStringExtra("title") ?: "F1 Clash Coach", intent.getStringExtra("text") ?: "")
    }
}

/** Agenda os avisos a partir do que o Coach leu no jogo. Refeito sempre que os dados mudam. */
object Reminders {
    data class R(val key: String, val at: Long, val title: String, val text: String)

    private const val H = 3_600_000L

    fun compute(d: CoachData, now: Long): List<R> {
        val out = ArrayList<R>()
        d.freeCrateAt?.takeIf { it > now }?.let { out += R("crate", it, "🎁 Caixote grátis pronto", "Abra o F1 Clash e pegue o caixote grátis.") }
        for (e in d.events) {
            val end = e.endsAt ?: continue
            if (end - 3 * H > now) out += R("ev:${e.id}", end - 3 * H, "⏱ ${e.mode.label} acaba em 3 h", "${e.name}: jogue o que falta antes de terminar.")
        }
        d.gp?.let { gp -> gp.roundEndsAt?.let { end -> if (end - 3 * H > now) out += R("gp", end - 3 * H, "🏆 Grand Prix: rodada acaba em 3 h", "Complete as corridas do GP ${gp.name} para não perder pontos.") } }
        val daily = d.objectives.filter { !it.weekly && !it.done && it.endsAt != null }
        daily.minOfOrNull { it.endsAt!! }?.let { end -> if (end - 2 * H > now) out += R("obj", end - 2 * H, "📋 Objetivos diários acabam em 2 h", "Faltam ${daily.size}: ${daily.take(2).joinToString { it.text.take(40) }}") }
        d.club.leagueEndsAt?.let { end -> if (end - 24 * H > now) out += R("club", end - 24 * H, "⭐ Liga do clube termina em 24 h", "Hora de revisar quem excluir do clube e cobrar quem não correu.") }
        return out.sortedBy { it.at }.take(12)
    }

    fun schedule(ctx: Context, d: CoachData, now: Long = System.currentTimeMillis()) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val prefs = ctx.getSharedPreferences("reminders", Context.MODE_PRIVATE)
        val old = prefs.getStringSet("keys", emptySet()) ?: emptySet()
        val list = compute(d, now)
        val keys = list.map { it.key }.toSet()
        for (k in old - keys) pending(ctx, k, null)?.let { am.cancel(it) }
        for (r in list) {
            val pi = pending(ctx, r.key, r) ?: continue
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi) }
        }
        prefs.edit().putStringSet("keys", keys).apply()
    }

    private fun pending(ctx: Context, key: String, r: R?): PendingIntent? {
        val id = 5000 + (key.hashCode() and 0x7fff)
        val i = Intent(ctx, ReminderReceiver::class.java).putExtra("id", id)
        if (r != null) i.putExtra("title", r.title).putExtra("text", r.text)
        return PendingIntent.getBroadcast(ctx, id, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
