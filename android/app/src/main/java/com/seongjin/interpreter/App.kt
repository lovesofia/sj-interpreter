package com.seongjin.interpreter

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class App : Application() {
    lateinit var repo: Repo; private set
    lateinit var engine: Engine; private set

    override fun onCreate() {
        super.onCreate()
        repo = Repo(this)
        engine = Engine(this, repo)
        val ch = NotificationChannel(CHANNEL, "실시간 통역", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    companion object { const val CHANNEL = "live" }
}

/** 통역 중 상단 알림을 띄워, 화면이 꺼지거나 주머니에 넣어도 마이크가 계속 동작하게 한다 */
class InterpreterService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, App.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("성진 현장통역 · 대화 중")
            .setContentText("눌러서 앱으로 돌아가기")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        runCatching { ServiceCompat.startForeground(this, 1, n, type) }
        return START_NOT_STICKY
    }
}
