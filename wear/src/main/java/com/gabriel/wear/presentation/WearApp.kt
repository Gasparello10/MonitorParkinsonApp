package com.gabriel.wear.presentation

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class WearApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        // A criação de canais só é necessária a partir do Android 8.0 (API 26)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Monitoramento de Sensor" // Nome que o usuário verá nas configurações
            val descriptionText = "Canal para a notificação do serviço de coleta de dados"
            val importance = NotificationManager.IMPORTANCE_LOW // Use LOW para não ter som

            // O ID do canal DEVE ser exatamente o mesmo usado no seu Worker
            val channelId = "SensorServiceChannel"

            val channel = NotificationChannel(channelId, name, importance).apply {
                description = descriptionText
            }

            // Registra o canal com o sistema
            val notificationManager: NotificationManager =
                getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}