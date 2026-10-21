package com.leandro.f1clashcoach

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.leandro.f1clashcoach.data.CoachStore
import com.leandro.f1clashcoach.data.Settings

class CoachApp : Application() {
    lateinit var store: CoachStore
        private set
    lateinit var settings: Settings
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = CoachStore(this)
        settings = Settings(this)
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
