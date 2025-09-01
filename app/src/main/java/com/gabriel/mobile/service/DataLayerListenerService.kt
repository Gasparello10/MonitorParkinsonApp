package com.gabriel.mobile.service

import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.gabriel.shared.DataLayerConstants
import com.gabriel.shared.SensorDataPoint
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class DataLayerListenerService : WearableListenerService() {

    private val gson = Gson()

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        super.onDataChanged(dataEvents)
        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED) {
                val path = event.dataItem.uri.path ?: ""
                when {
                    path.startsWith(DataLayerConstants.SENSOR_DATA_PATH) -> {
                        handleSensorData(event)
                    }
                    path.startsWith(DataLayerConstants.BATTERY_PATH) -> {
                        handleBatteryData(event)
                    }
                }
            }
        }
    }

    private fun handleSensorData(event: DataEvent) {
        try {
            val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
            val serializedBatch = dataMap.getString("sensor_batch_data")
            if (serializedBatch != null) {
                val type = object : TypeToken<List<SensorDataPoint>>() {}.type
                val batch: List<SensorDataPoint> = gson.fromJson(serializedBatch, type)

                if (batch.isNotEmpty()) {
                    Log.d(TAG, "Lote de ${batch.size} amostras recebido do relógio!")

                    // <<< MUDANÇA CRÍTICA: Enviando o lote inteiro de uma vez >>>
                    // Em vez de iterar e enviar um por um, enviamos a lista completa
                    // em um único broadcast. Isso elimina a necessidade de re-buffering
                    // no MonitoringService, reduzindo drasticamente a latência.
                    val intent = Intent(ACTION_RECEIVE_SENSOR_DATA).apply {
                        // Usamos putSerializable para enviar a lista inteira.
                        // O Android requer que a lista seja convertida para ArrayList para serialização.
                        putExtra(EXTRA_SENSOR_BATCH, ArrayList(batch))
                    }
                    LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao processar dados do sensor", e)
        }
    }


    private fun handleBatteryData(event: DataEvent) {
        try {
            val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
            val batteryLevel = dataMap.getInt(DataLayerConstants.BATTERY_KEY, -1)
            if (batteryLevel != -1) {
                Log.d(TAG, "Nível de bateria recebido: $batteryLevel%")
                val intent = Intent(ACTION_RECEIVE_BATTERY_DATA).apply {
                    putExtra(EXTRA_BATTERY_LEVEL, batteryLevel)
                }
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao processar dados da bateria", e)
        }
    }

    companion object {
        private const val TAG = "DataLayerListener"

        const val ACTION_RECEIVE_SENSOR_DATA = "com.gabriel.mobile.RECEIVE_SENSOR_DATA"
        // <<< MUDANÇA: Novo nome para o extra para clareza e consistência >>>
        const val EXTRA_SENSOR_BATCH = "extra_sensor_batch"

        const val ACTION_RECEIVE_BATTERY_DATA = "com.gabriel.mobile.RECEIVE_BATTERY_DATA"
        const val EXTRA_BATTERY_LEVEL = "extra_battery_level"
    }
}

