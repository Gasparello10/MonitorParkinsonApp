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

class DataLayerListenerService : WearableListenerService() {

    // CORREÇÃO: Usamos onDataChanged para ouvir DataItems, não onMessageReceived.
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        super.onDataChanged(dataEvents)

        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == DataLayerConstants.SENSOR_DATA_PATH) {
                try {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val timestamp = dataMap.getLong(DataLayerConstants.KEY_TIMESTAMP)
                    val values = dataMap.getFloatArray(DataLayerConstants.KEY_VALUES)

                    if (values != null) {
                        val dataPoint = SensorDataPoint(timestamp, values)
                        Log.d("DataLayerListener", "SensorDataPoint recebido: $dataPoint")

                        // Envia o objeto SensorDataPoint completo para o ViewModel
                        val intent = Intent(ACTION_RECEIVE_SENSOR_DATA).apply {
                            putExtra(EXTRA_SENSOR_DATA, dataPoint) // dataPoint é Serializable
                        }
                        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
                    }
                } catch (e: Exception) {
                    Log.e("DataLayerListener", "Erro ao processar DataItem", e)
                }
            }
        }
    }

    companion object {
        const val ACTION_RECEIVE_SENSOR_DATA = "com.gabriel.mobile.RECEIVE_SENSOR_DATA"
        const val EXTRA_SENSOR_DATA = "extra_sensor_data"
    }
}
