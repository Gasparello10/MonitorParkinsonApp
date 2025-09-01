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

        const val ACTION_RECEIVE_BATTERY_DATA = "com.gabriel.mobile.RECEIVE_BATTERY_DATA"
        const val EXTRA_BATTERY_LEVEL = "extra_battery_level"
        const val ACTION_RECEIVE_SENSOR_DATA = "com.gabriel.mobile.RECEIVE_SENSOR_DATA"
        const val EXTRA_SENSOR_DATA = "extra_sensor_data"
        private const val TAG = "DataLayerListener"

        // <<< MUDANÇA 2: Chave correspondente à do relógio >>>
        private const val DATA_KEY_SENSOR_BATCH = "sensor_batch_data"
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        super.onDataChanged(dataEvents)

        dataEvents.forEach { event ->

            if (event.type == DataEvent.TYPE_CHANGED) {
                when {
                    event.dataItem.uri.path?.startsWith(DataLayerConstants.SENSOR_DATA_PATH) == true -> {
                        try {
                            val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                            val jsonString = dataMap.getString(DATA_KEY_SENSOR_BATCH)

                            jsonString?.let { nonNullJsonString ->
                                val typeToken = object : TypeToken<List<SensorDataPoint>>() {}.type
                                val dataPoints = gson.fromJson<List<SensorDataPoint>>(
                                    nonNullJsonString,
                                    typeToken
                                )
                                Log.d(
                                    TAG,
                                    "Lote de ${dataPoints.size} amostras recebido do relógio!"
                                )
                                dataPoints.forEach { dataPoint ->
                                    val intent = Intent(ACTION_RECEIVE_SENSOR_DATA).apply {
                                        putExtra(EXTRA_SENSOR_DATA, dataPoint)
                                    }
                                    LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Erro ao desserializar o lote de dados do sensor", e)
                        }
                    }

                    // <<< ALTERAÇÃO AQUI: De '.equals' para '.startsWith' >>>
                    event.dataItem.uri.path?.startsWith(DataLayerConstants.BATTERY_PATH) == true -> {
                        try {
                            val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                            val batteryLevel = dataMap.getInt(DataLayerConstants.BATTERY_KEY, -1)

                            if (batteryLevel != -1) {
                                Log.d(TAG, "Nível da bateria recebido do relógio: $batteryLevel%")
                                val intent = Intent(ACTION_RECEIVE_BATTERY_DATA).apply {
                                    putExtra(EXTRA_BATTERY_LEVEL, batteryLevel)
                                }
                                LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Erro ao receber dados da bateria", e)
                        }
                    }
                }
            }
        }
    }
}