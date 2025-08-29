package com.gabriel.wear.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.gabriel.shared.DataLayerConstants
import com.gabriel.shared.SensorDataPoint
import com.gabriel.wear.R
import com.gabriel.wear.presentation.MainActivity
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

class SensorWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private var lastBatterySendTime = 0L
    private val batterySendInterval = 30000L // 30 segundos em milissegundos
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val dataClient by lazy { Wearable.getDataClient(context) }
    private val gson = Gson()

    private val dataBuffer = mutableListOf<SensorDataPoint>()
    private val bufferMutex = Mutex()
    private val sensorDataChannel = Channel<SensorDataPoint>(capacity = Channel.UNLIMITED)

    companion object {
        const val WORK_NAME = "SensorCollectionWork"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "SensorServiceChannel"
        private const val BATCH_SIZE = DataLayerConstants.BATCH_SIZE
        private const val TAG = "SensorWorker"
        private const val DATA_KEY_SENSOR_BATCH = "sensor_batch_data"

        private const val USE_FAKE_DATA = false

        private val PLAYBACK_FILE_ID = R.raw.simulacao_20min_6hz
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Worker iniciado.")
        setForeground(createForegroundInfo())
        val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp::SensorWakeLock")

        try {
            wakeLock.acquire()
            Log.d(TAG, "WakeLock adquirido.")

            coroutineScope {
                launch {
                    if (USE_FAKE_DATA) {
                        Log.d(TAG, "Usando modo de PLAYBACK com dados do arquivo.")
                        startFakeDataPlayback()
                    } else {
                        Log.d(TAG, "Usando dados REAIS do acelerômetro.")
                        startRealSensorCollection()
                    }
                }

                launch {
                    for (dataPoint in sensorDataChannel) {
                        addDataToBuffer(dataPoint)
                    }
                }
            }

        } catch (e: CancellationException) {
            Log.d(TAG, "Worker cancelado, finalizando a coleta.")
        } catch (e: Exception) {
            Log.e(TAG, "Erro crítico no Worker", e)
            return Result.failure()
        } finally {
            if (wakeLock.isHeld) {
                wakeLock.release()
                Log.d(TAG, "WakeLock liberado.")
            }
            sensorDataChannel.close()
            Log.d(TAG, "Worker finalizado e recursos liberados.")
        }
        return Result.success()
    }

    private suspend fun startRealSensorCollection() {
        callbackFlow {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent?) {
                    event?.let {
                        val dataPoint = SensorDataPoint(
                            System.currentTimeMillis(),
                            floatArrayOf(it.values[0], it.values[1], it.values[2])
                        )
                        trySend(dataPoint)
                    }
                }
                override fun onAccuracyChanged(s: Sensor?, a: Int) {}
            }
            Log.d(TAG, "Registrando listener do sensor real.")
            sensorManager.registerListener(listener, accelerometer, 40000)
            awaitClose {
                Log.d(TAG, "Cancelando listener do sensor real.")
                sensorManager.unregisterListener(listener)
            }
        }.collect { dataPoint ->
            sensorDataChannel.send(dataPoint)
        }
    }

    private suspend fun startFakeDataPlayback() = withContext(Dispatchers.IO) {
        try {
            // <<< ALTERAÇÃO: Usa a variável PLAYBACK_FILE_ID definida acima >>>
            val inputStream = context.resources.openRawResource(PLAYBACK_FILE_ID)

            inputStream.bufferedReader().use { reader ->
                var lastTimestamp: Long? = null
                reader.readLine() // Pula o cabeçalho do CSV

                var line: String? = reader.readLine()
                while (line != null) {
                    ensureActive()

                    val parts = line.split(',')
                    if (parts.size == 4) {
                        val timestamp = parts[0].toLong()
                        val x = parts[1].toFloat()
                        val y = parts[2].toFloat()
                        val z = parts[3].toFloat()

                        if (lastTimestamp != null) {
                            val delayMs = timestamp - lastTimestamp!!
                            if (delayMs > 0) {
                                delay(delayMs)
                            }
                        }

                        val dataPoint = SensorDataPoint(timestamp, floatArrayOf(x, y, z))

                        withContext(Dispatchers.Default) {
                            sensorDataChannel.send(dataPoint)
                        }

                        lastTimestamp = timestamp
                    }
                    line = reader.readLine()
                }
            }
            Log.d(TAG, "Playback do arquivo concluído.")
        } catch (e: CancellationException) {
            Log.d(TAG, "Playback de dados falsos cancelado.")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao ler o arquivo de playback de res/raw", e)
        }
    }

    private suspend fun addDataToBuffer(dataPoint: SensorDataPoint) {
        var batchToSend: List<SensorDataPoint>? = null
        bufferMutex.withLock {
            dataBuffer.add(dataPoint)
            if (dataBuffer.size >= BATCH_SIZE) {
                batchToSend = ArrayList(dataBuffer)
                dataBuffer.clear()
            }
        }
        batchToSend?.let {
            sendDataToPhone(it)
        }
    }

    private suspend fun sendDataToPhone(batch: List<SensorDataPoint>) {
        val serializedBatch = gson.toJson(batch)
        try {
            val uniquePath = "${DataLayerConstants.SENSOR_DATA_PATH}/${System.currentTimeMillis()}"
            val putDataMapRequest = PutDataMapRequest.create(uniquePath)
            putDataMapRequest.dataMap.putString(DATA_KEY_SENSOR_BATCH, serializedBatch)
            val putDataRequest = putDataMapRequest.asPutDataRequest().setUrgent()
            dataClient.putDataItem(putDataRequest).await()
            Log.d(TAG, "Lote de ${batch.size} amostras adicionado ao Data Layer com sucesso.")
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao enviar lote de dados para o Data Layer", e)
        }
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBatterySendTime > batterySendInterval) {
            sendBatteryLevel()
            lastBatterySendTime = currentTime
        }
    }

    private suspend fun sendBatteryLevel() {
        val batteryIntent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level: Int = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level == -1 || scale == -1) {
            Log.e(TAG, "Não foi possível obter o nível da bateria.")
            return
        }
        val batteryPct = (level / scale.toFloat() * 100).toInt()
        try {
            val uniquePath = "${DataLayerConstants.BATTERY_PATH}/${System.currentTimeMillis()}"
            val putDataMapRequest = PutDataMapRequest.create(uniquePath)
            putDataMapRequest.dataMap.putInt(DataLayerConstants.BATTERY_KEY, batteryPct)
            val putDataRequest = putDataMapRequest.asPutDataRequest().setUrgent()
            dataClient.putDataItem(putDataRequest).await()
            Log.d(TAG, "Nível da bateria ($batteryPct%) enviado para o celular.")
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao enviar o nível da bateria", e)
        }
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val touchIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, touchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notificationBuilder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentTitle("Monitorização Ativa")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setSilent(true)
        val ongoingActivity = OngoingActivity.Builder(context, NOTIFICATION_ID, notificationBuilder)
            .setTouchIntent(pendingIntent)
            .setStatus(Status.Builder().addTemplate("Coletando dados...").build())
            .build()
        ongoingActivity.apply(context)
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        } else {
            0
        }
        return ForegroundInfo(NOTIFICATION_ID, notificationBuilder.build(), serviceType)
    }
}
