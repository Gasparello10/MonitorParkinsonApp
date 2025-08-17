package com.gabriel.mobile.service

import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.gabriel.shared.DataLayerConstants
import com.gabriel.shared.SensorDataPoint
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.google.gson.Gson
import java.io.ByteArrayInputStream
import java.io.ObjectInputStream

class DataLayerListenerService : WearableListenerService() {

    // <<< ADIÇÃO 1: Instância do Gson para desserializar o JSON >>>
    private val gson = Gson()

    companion object {
        const val ACTION_RECEIVE_SENSOR_DATA = "com.gabriel.mobile.RECEIVE_SENSOR_DATA"
        const val EXTRA_SENSOR_DATA = "extra_sensor_data"
        private const val TAG = "DataLayerListener" // Adicionado para logs
    }

    // O método antigo pode ser mantido por compatibilidade ou removido se não for mais usado.
    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)
        // Se você ainda usa MessageClient para outros comandos (como START/STOP), mantenha a lógica aqui.
        // Se não, este método pode ser removido.
    }

    // <<< ADIÇÃO 2: Novo método para receber dados sincronizados via DataClient >>>
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        super.onDataChanged(dataEvents)
        //Log.d(TAG, "onDataChanged chamado com ${dataEvents.count} eventos.")

        dataEvents.forEach { event ->
            // Verifica se o dado foi alterado (TYPE_CHANGED) e se o caminho é o que nos interessa
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == DataLayerConstants.SENSOR_DATA_PATH) {
                try {
                    // Extrai o mapa de dados do item
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    // Pega a string JSON que salvamos no relógio
                    val dataPointJson = dataMap.getString(DataLayerConstants.DATA_KEY)

                    if (dataPointJson != null) {
                        // Desserializa o JSON de volta para o nosso objeto SensorDataPoint
                        val dataPoint = gson.fromJson(dataPointJson, SensorDataPoint::class.java)

                        //Log.d(TAG, "DataItem de sensor recebido e desserializado com sucesso!")

                        // Envia os dados para o MonitoringService, exatamente como o messageClient fazia
                        val intent = Intent(ACTION_RECEIVE_SENSOR_DATA).apply {
                            putExtra(EXTRA_SENSOR_DATA, dataPoint)
                        }
                        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Falha ao processar DataItem.", e)
                }
            }
        }
    }
}