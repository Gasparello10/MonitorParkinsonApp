package com.gabriel.mobile.service

import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.gabriel.shared.DataLayerConstants
import com.gabriel.shared.SensorDataPoint
// <<< MUDANÇA 1: Importações adicionadas/alteradas para o DataClient >>>
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class DataLayerListenerService : WearableListenerService() {

    private val gson = Gson()

    companion object {
        const val ACTION_RECEIVE_SENSOR_DATA = "com.gabriel.mobile.RECEIVE_SENSOR_DATA"
        const val EXTRA_SENSOR_DATA = "extra_sensor_data"
        private const val TAG = "DataLayerListener"
        // <<< MUDANÇA 2: Chave correspondente à do relógio >>>
        private const val DATA_KEY_SENSOR_BATCH = "sensor_batch_data"
    }

    // <<< MUDANÇA 3: A lógica foi movida de onMessageReceived para onDataChanged >>>
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        super.onDataChanged(dataEvents)
        Log.d(TAG, "onDataChanged acionado. Eventos recebidos: ${dataEvents.count}")

        dataEvents.forEach { event ->
            // Verificamos se o evento é uma alteração de dados e se o caminho corresponde ao que esperamos
            // <<< SUA CORREÇÃO - EXCELENTE! >>>
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path?.startsWith(DataLayerConstants.SENSOR_DATA_PATH) == true) {
                try {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val jsonString = dataMap.getString(DATA_KEY_SENSOR_BATCH)

                    // <<< NOVA CORREÇÃO AQUI >>>
                    // Só executa o código se jsonString não for nulo.
                    jsonString?.let { nonNullJsonString ->
                        val typeToken = object : TypeToken<List<SensorDataPoint>>() {}.type
                        val dataPoints = gson.fromJson<List<SensorDataPoint>>(nonNullJsonString, typeToken)

                        Log.d(TAG, "Lote de ${dataPoints.size} amostras recebido do relógio via DataClient!")

                        dataPoints.forEach { dataPoint ->
                            val intent = Intent(ACTION_RECEIVE_SENSOR_DATA).apply {
                                putExtra(EXTRA_SENSOR_DATA, dataPoint)
                            }
                            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao desserializar o lote de dados do sensor via DataClient", e)
                }
            }
        }
    }
}