package com.gabriel.mobile.service

import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.gabriel.shared.DataLayerConstants
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import java.io.ByteArrayInputStream
import java.io.ObjectInputStream

class DataLayerListenerService : WearableListenerService() {

    companion object {
        const val ACTION_RECEIVE_SENSOR_DATA = "com.gabriel.mobile.RECEIVE_SENSOR_DATA"
        const val EXTRA_SENSOR_DATA = "extra_sensor_data"
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)

        when (messageEvent.path) {
            // Este serviço ouve os dados do sensor enviados pelo relógio
            DataLayerConstants.SENSOR_DATA_PATH -> {
                try {
                    val byteArrayInputStream = ByteArrayInputStream(messageEvent.data)
                    val objectInputStream = ObjectInputStream(byteArrayInputStream)
                    val dataPoint = objectInputStream.readObject()

                    Log.d("DataLayerListener", "Dados do sensor recebidos do relógio!")

                    // Envia os dados recebidos para o MainViewModel através de um broadcast local
                    val intent = Intent(ACTION_RECEIVE_SENSOR_DATA).apply {
                        putExtra(EXTRA_SENSOR_DATA, dataPoint as java.io.Serializable)
                    }
                    LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

                } catch (e: Exception) {
                    Log.e("DataLayerListener", "Erro ao desserializar os dados do sensor", e)
                }
            }
        }
    }
}
