package com.gabriel.mobile.service

import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.gabriel.shared.DataLayerConstants
import com.gabriel.shared.SensorDataPoint
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.nio.charset.StandardCharsets

class DataLayerListenerService : WearableListenerService() {

    // <<< ADIÇÃO 2: Instância do Gson para desserializar o JSON >>>
    private val gson = Gson()

    companion object {
        const val ACTION_RECEIVE_SENSOR_DATA = "com.gabriel.mobile.RECEIVE_SENSOR_DATA"
        const val EXTRA_SENSOR_DATA = "extra_sensor_data"
        private const val TAG = "DataLayerListener" // Adicionado para logs
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)

        when (messageEvent.path) {
            DataLayerConstants.SENSOR_DATA_PATH -> {
                try {
                    // <<< ALTERAÇÃO 3: Lógica de desserialização substituída >>>

                    // 1. Converte os bytes recebidos para uma string JSON
                    val jsonString = String(messageEvent.data, StandardCharsets.UTF_8)

                    // 2. Define o tipo de dado esperado: uma Lista de SensorDataPoint
                    val typeToken = object : TypeToken<List<SensorDataPoint>>() {}.type

                    // 3. Usa Gson para desserializar a string JSON na lista de objetos
                    val dataPoints = gson.fromJson<List<SensorDataPoint>>(jsonString, typeToken)

                    Log.d(TAG, "Lote de ${dataPoints.size} amostras recebido do relógio!")

                    // 4. Itera sobre a lista recebida e envia cada ponto individualmente
                    // para o MonitoringService. Desta forma, o resto do seu app não precisa mudar.
                    dataPoints.forEach { dataPoint ->
                        val intent = Intent(ACTION_RECEIVE_SENSOR_DATA).apply {
                            putExtra(EXTRA_SENSOR_DATA, dataPoint)
                        }
                        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao desserializar o lote de dados do sensor", e)
                }
            }
        }
    }
}