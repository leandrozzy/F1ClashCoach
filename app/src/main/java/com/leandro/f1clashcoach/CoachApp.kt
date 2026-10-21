package com.leandro.f1clashcoach

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.leandro.f1clashcoach.data.CoachStore
import com.leandro.f1clashcoach.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@OptIn(kotlinx.coroutines.FlowPreview::class)
class CoachApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var store: CoachStore
        private set
    lateinit var settings: Settings
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        com.leandro.f1clashcoach.ai.Health.install(this)
        com.leandro.f1clashcoach.ai.AiKeys.migrateFromOld(this)
        store = CoachStore(this)
        settings = Settings(this)
        com.leandro.f1clashcoach.notify.Notifier.ensureChannel(this)
        // Reagenda os avisos sempre que o que o Coach leu muda (no máx. a cada 20 s).
        appScope.launch {
            store.data.map { Triple(it.events, it.objectives, Triple(it.gp, it.freeCrateAt, it.club.leagueEndsAt)) }
                .distinctUntilChanged().debounce(20_000).collect {
                    runCatching { com.leandro.f1clashcoach.notify.Reminders.schedule(this@CoachApp, store.data.value) }
                }
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_COACH, "Coach ao vivo", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Mantém o Coach lendo a tela durante o jogo"
            }
        )
    }

    companion object {
        const val CHANNEL_COACH = "coach_live"
        const val F1_CLASH_PACKAGE = "com.hutchgames.formularacing"
        lateinit var instance: CoachApp
            private set
    }
}
