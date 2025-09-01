package com.gabriel.wear.service

import android.util.Log
import androidx.work.*
import com.gabriel.shared.DataLayerConstants
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import java.nio.charset.StandardCharsets

class DataLayerListenerService : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)

        when (messageEvent.path) {
            DataLayerConstants.CONTROL_PATH -> {
                val command = String(messageEvent.data, StandardCharsets.UTF_8)
                Log.d("DataLayerListener", "Comando recebido: $command")

                val workManager = WorkManager.getInstance(this)

                when (command) {
                    DataLayerConstants.START_COMMAND -> {
                        Log.d("DataLayerListener", "Enfileirando o SensorWorker...")

                        // Cria uma requisição para o nosso worker
                        val startRequest = OneTimeWorkRequestBuilder<SensorWorker>().build()

                        // Enfileira o trabalho com um nome único, substituindo qualquer trabalho anterior com o mesmo nome.
                        // Isso evita que múltiplos workers rodem ao mesmo tempo.
                        workManager.enqueueUniqueWork(
                            SensorWorker.WORK_NAME,
                            ExistingWorkPolicy.REPLACE,
                            startRequest
                        )
                    }
                    DataLayerConstants.STOP_COMMAND -> {
                        Log.d("DataLayerListener", "Cancelando o SensorWorker...")

                        // Cancela o trabalho que tem o nosso nome único.
                        workManager.cancelUniqueWork(SensorWorker.WORK_NAME)
                    }
                }
            }
        }
    }
}