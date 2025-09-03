package com.gabriel.wear.service

import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.gabriel.shared.DataLayerConstants
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService


class DataLayerListenerService : WearableListenerService() {

    // O método antigo onMessageReceived foi completamente removido.

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        super.onDataChanged(dataEvents)
        Log.d(TAG, "onDataChanged acionado no relógio.")

        dataEvents.forEach { event ->
            // Verificamos se o evento é uma mudança e se o caminho é o de controle da sessão
            if (event.type == DataEvent.TYPE_CHANGED &&
                event.dataItem.uri.path == DataLayerConstants.SESSION_STATE_PATH) {

                // Correct way to get DataMap from DataItem
                val dataMapItem = DataMapItem.fromDataItem(event.dataItem)
                val dataMap = dataMapItem.dataMap // Now you have the DataMap
                val isSessionActive = dataMap.getBoolean(DataLayerConstants.SESSION_STATE_KEY_ACTIVE)

                Log.d(TAG, "Comando de sessão via DataItem recebido: ativo = $isSessionActive")
                if (isSessionActive) {
                    startSensorCollection()
                } else {
                    stopSensorCollection()
                }
            }
        }
    }

    private fun startSensorCollection() {
        Log.d(TAG, "Iniciando o SensorWorker a partir do comando do celular.")
        val workRequest = OneTimeWorkRequestBuilder<SensorWorker>().build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            SensorWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    private fun stopSensorCollection() {
        Log.d(TAG, "Parando o SensorWorker a partir do comando do celular.")
        WorkManager.getInstance(applicationContext).cancelUniqueWork(SensorWorker.WORK_NAME)
    }

    companion object {
        private const val TAG = "DataLayerListenerWear"
    }
}