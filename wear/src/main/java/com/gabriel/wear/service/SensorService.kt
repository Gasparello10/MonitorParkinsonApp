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
// <<< ADIÇÃO 1: Novas importações para a DataClient API >>>
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.gson.Gson // Adicionado para serialização em JSON
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

class SensorService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    // <<< ALTERAÇÃO 2: A messageClient não é mais necessária para o envio de dados >>>
    // private val messageClient by lazy { Wearable.getMessageClient(this) }
    private val nodeClient by lazy { Wearable.getNodeClient(this) }

    // <<< ADIÇÃO 3: Cliente para a Data API e Gson para serialização >>>
    private val dataClient by lazy { Wearable.getDataClient(this) }
    private val gson = Gson()

    private var wakeLock: PowerManager.WakeLock? = null
    private var fakeDataJob: Job? = null

    companion object {
        private const val USE_FAKE_DATA = true
        private const val TAG = "SensorService" // Adicionado para logs consistentes
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
        Log.i(TAG, "WakeLock adquirido.")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand chamado. Modo Falso: $USE_FAKE_DATA")

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
            Log.i(TAG, if (success) "Listener do sensor real registado" else "FALHA ao registar listener real")
        }
    }

    private fun startFakeDataPlayback() {
        fakeDataJob?.cancel()

        fakeDataJob = GlobalScope.launch(Dispatchers.IO) {
            try {
                val inputStream = resources.openRawResource(R.raw.simulacao_20min_6hz)
                val reader = BufferedReader(InputStreamReader(inputStream))
                Log.i(TAG, "Iniciando playback de dados do recurso raw...")
                reader.readLine() // Pula cabeçalho

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
                Log.i(TAG, "Playback do arquivo CSV concluído.")

            } catch (e: Exception) {
                Log.e(TAG, "Erro ao ler ou processar o arquivo CSV do recurso raw", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (USE_FAKE_DATA) {
            fakeDataJob?.cancel()
            Log.i(TAG, "Playback de dados falsos interrompido.")
        } else {
            sensorManager.unregisterListener(this)
            Log.i(TAG, "Listener do sensor real desregistrado.")
        }

        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.i(TAG, "WakeLock liberado.")
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.d(TAG, "Serviço destruído.")
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

    // <<< ALTERAÇÃO 4: Função de envio completamente substituída para usar DataClient >>>
    private fun sendDataToPhone(dataPoint: SensorDataPoint) {
        // Cria um "mapa de dados" num caminho específico. Pense nisto como um ficheiro sincronizado.
        val putDataMapRequest = PutDataMapRequest.create(DataLayerConstants.SENSOR_DATA_PATH).apply {
            // Serializa o objeto dataPoint para uma string JSON e coloca no mapa
            dataMap.putString(DataLayerConstants.DATA_KEY, gson.toJson(dataPoint))
            // Adiciona um timestamp para garantir que cada DataItem seja único e acione uma atualização
            dataMap.putLong("timestamp", System.currentTimeMillis())
        }

        // Converte o mapa para uma requisição de "salvamento"
        val request = putDataMapRequest.asPutDataRequest()

        // Pede ao sistema para sincronizar este item. A entrega agora é garantida.
        dataClient.putDataItem(request)
            .addOnSuccessListener { Log.d(TAG, "DataItem salvo para sincronização bem-sucedido.") }
            .addOnFailureListener { e -> Log.e(TAG, "Falha ao salvar DataItem para sincronização.", e) }
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
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}