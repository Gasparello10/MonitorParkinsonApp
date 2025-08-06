package com.gabriel.wear.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.util.Log
import com.gabriel.shared.DataLayerConstants
import com.gabriel.shared.SensorDataPoint
import com.google.android.gms.wearable.Wearable
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream

class SensorService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private val messageClient by lazy { Wearable.getMessageClient(this) }
    private val nodeClient by lazy { Wearable.getNodeClient(this) }

    private val NOTIFICATION_ID = 1
    private val NOTIFICATION_CHANNEL_ID = "SensorServiceChannel"

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // --- NOVO LOG DE VERIFICAÇÃO ---
        if (accelerometer == null) {
            Log.e("SensorService", "ERRO CRÍTICO: Acelerómetro não foi encontrado no dispositivo.")
        } else {
            Log.d("SensorService", "Acelerómetro encontrado com sucesso no onCreate.")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("SensorService", "onStartCommand chamado.")
        createNotificationChannel()
        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Monitorização Ativa")
            .setContentText("A recolher dados do acelerómetro.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        accelerometer?.also { sensor ->
            val SENSOR_FREQUENCY_HZ = 50
            val SENSOR_DELAY_US = 1_000_000 / SENSOR_FREQUENCY_HZ

            // --- NOVO LOG DE VERIFICAÇÃO ---
            val success = sensorManager.registerListener(this, sensor, SENSOR_DELAY_US)
            if (success) {
                Log.i("SensorService", "Listener do sensor registado com SUCESSO.")
            } else {
                Log.e("SensorService", "FALHA ao registar o listener do sensor.")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("SensorService", "Serviço destruído, a desregistar o listener do sensor.")
        sensorManager.unregisterListener(this)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Não é necessário para este caso
    }

    override fun onSensorChanged(event: SensorEvent?) {
        // --- NOVO LOG DE VERIFICAÇÃO ---
        // Este log é o mais importante. Se ele aparecer, significa que estamos a receber dados.
        Log.d("SensorService", "onSensorChanged chamado! A enviar dados...")

        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val dataPoint = SensorDataPoint(
                timestamp = System.currentTimeMillis(),
                values = floatArrayOf(event.values[0], event.values[1], event.values[2])
            )
            sendDataToPhone(dataPoint)
        }
    }

    private fun sendDataToPhone(dataPoint: SensorDataPoint) {
        nodeClient.connectedNodes.addOnSuccessListener { nodes ->
            if (nodes.isEmpty()) {
                Log.w("SensorService", "A tentar enviar dados, mas nenhum telemóvel foi encontrado.")
            }
            nodes.forEach { node ->
                val stream = ByteArrayOutputStream()
                val oos = ObjectOutputStream(stream)
                oos.writeObject(dataPoint)
                val data = stream.toByteArray()
                messageClient.sendMessage(node.id, DataLayerConstants.SENSOR_DATA_PATH, data)
            }
        }
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Canal do Serviço de Sensores",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
