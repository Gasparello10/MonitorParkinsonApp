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
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.gabriel.shared.DataLayerConstants
import com.gabriel.shared.SensorDataPoint
import com.gabriel.wear.R
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.ObjectOutputStream

class SensorService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private val messageClient by lazy { Wearable.getMessageClient(this) }
    private val nodeClient by lazy { Wearable.getNodeClient(this) }
    private var wakeLock: PowerManager.WakeLock? = null
    private var fakeDataJob: Job? = null

    companion object {
        private const val USE_FAKE_DATA = true
    }

    private val NOTIFICATION_ID = 1
    private val NOTIFICATION_CHANNEL_ID = "SensorServiceChannel"

    override fun onCreate() {
        super.onCreate()
        if (!USE_FAKE_DATA) {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        }
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MonitorParkinson::SensorWakelockTag"
        ).apply { acquire() }
        Log.i("SensorService", "WakeLock adquirido.")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("SensorService", "onStartCommand chamado. Modo Falso: $USE_FAKE_DATA")

        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        if (USE_FAKE_DATA) {
            startFakeDataPlayback()
        } else {
            startRealSensor()
        }

        return START_STICKY
    }

    private fun startRealSensor() {
        accelerometer?.also { sensor ->
            val SENSOR_FREQUENCY_HZ = 50
            val SENSOR_DELAY_US = 1_000_000 / SENSOR_FREQUENCY_HZ
            val success = sensorManager.registerListener(this, sensor, SENSOR_DELAY_US)
            Log.i("SensorService", if (success) "Listener do sensor real registado" else "FALHA ao registar listener real")
        }
    }

    // <<< FUNÇÃO MODIFICADA PARA LER DOS RECURSOS 'RAW' >>>
    private fun startFakeDataPlayback() {
        fakeDataJob?.cancel()

        fakeDataJob = GlobalScope.launch(Dispatchers.IO) {
            try {
                // Abre o arquivo a partir da pasta res/raw
                val inputStream = resources.openRawResource(R.raw.simulacao_20min_6hz)
                val reader = BufferedReader(InputStreamReader(inputStream))

                Log.i("SensorService", "Iniciando playback de dados do recurso raw...")

                // Pula a primeira linha (cabeçalho)
                reader.readLine()

                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (!isActive) break

                    val parts = line!!.split(',')
                    if (parts.size == 4) {
                        val dataPoint = SensorDataPoint(
                            timestamp = parts[0].toLong(),
                            values = floatArrayOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
                        )
                        sendDataToPhone(dataPoint)
                        delay(20L) // Simula 50Hz
                    }
                }

                reader.close()
                Log.i("SensorService", "Playback do arquivo CSV concluído.")

            } catch (e: Exception) {
                Log.e("SensorService", "Erro ao ler ou processar o arquivo CSV do recurso raw", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (USE_FAKE_DATA) {
            fakeDataJob?.cancel()
            Log.i("SensorService", "Playback de dados falsos interrompido.")
        } else {
            sensorManager.unregisterListener(this)
            Log.i("SensorService", "Listener do sensor real desregistrado.")
        }

        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.i("SensorService", "WakeLock liberado.")
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.d("SensorService", "Serviço destruído.")
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent?) {
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
            nodes.forEach { node ->
                val stream = ByteArrayOutputStream()
                ObjectOutputStream(stream).use { oos -> oos.writeObject(dataPoint) }
                val data = stream.toByteArray()
                messageClient.sendMessage(node.id, DataLayerConstants.SENSOR_DATA_PATH, data)
                    .addOnSuccessListener { Log.d("SensorService", "Dado enviado para o nó ${node.displayName}") }
                    .addOnFailureListener { Log.e("SensorService", "Falha ao enviar dado para o nó ${node.displayName}") }
            }
        }
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID, "Canal do Serviço de Sensores",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Monitorização Ativa")
            .setContentText(if (USE_FAKE_DATA) "Enviando dados simulados..." else "Recolhendo dados do acelerómetro.")
            .setSmallIcon(R.drawable.ic_notification_icon) // Lembre-se de ter este ícone
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}