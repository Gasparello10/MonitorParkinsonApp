package com.gabriel.mobile.service

import android.util.Log
import com.gabriel.shared.DataLayerConstants
import com.gabriel.shared.SensorDataPoint
import com.gabriel.mobile.data.SensorDataRepository
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DataLayerListenerService : WearableListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    companion object {
        private const val TAG = "DataLayerListener"
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        super.onDataChanged(dataEvents)
        Log.d(TAG, "Novos dados recebidos.")

        dataEvents.forEach { event ->
            if (event.type == com.google.android.gms.wearable.DataEvent.TYPE_CHANGED) {
                val dataItem = event.dataItem
                if (dataItem.uri.path == DataLayerConstants.SENSOR_DATA_PATH) {
                    val dataMap = DataMapItem.fromDataItem(dataItem).dataMap
                    val timestamp = dataMap.getLong(DataLayerConstants.KEY_TIMESTAMP)
                    val values = dataMap.getFloatArray(DataLayerConstants.KEY_VALUES)

                    if (values != null) {
                        val dataPoint = SensorDataPoint(timestamp, values)
                        Log.d(TAG, "Ponto de dado processado: $dataPoint")
                        // Lança uma coroutine para enviar os dados ao repositório
                        serviceScope.launch {
                            SensorDataRepository.addDataPoint(dataPoint)
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
