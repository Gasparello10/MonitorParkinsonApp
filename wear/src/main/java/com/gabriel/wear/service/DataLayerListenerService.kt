package com.gabriel.wear.service

import android.content.Intent
import android.util.Log
import com.gabriel.shared.DataLayerConstants
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import java.nio.charset.StandardCharsets

class DataLayerListenerService : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)

        // O serviço do relógio agora só ouve os comandos de controlo do telemóvel
        when (messageEvent.path) {
            DataLayerConstants.CONTROL_PATH -> {
                val command = String(messageEvent.data, StandardCharsets.UTF_8)
                Log.d("DataLayerListener", "Comando de controlo recebido: $command")
                when (command) {
                    // Usa as constantes do ficheiro partilhado
                    DataLayerConstants.START_COMMAND -> {
                        Log.d("DataLayerListener", "A iniciar o SensorService...")
                        val intent = Intent(this, SensorService::class.java)
                        startForegroundService(intent)
                    }
                    // Usa as constantes do ficheiro partilhado
                    DataLayerConstants.STOP_COMMAND -> {
                        Log.d("DataLayerListener", "A parar o SensorService...")
                        val intent = Intent(this, SensorService::class.java)
                        stopService(intent)
                    }
                }
            }
        }
    }
}
