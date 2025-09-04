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
import android.os.SystemClock
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.cancellation.CancellationException

class SensorWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val dataClient by lazy { Wearable.getDataClient(context) }
    private val gson = Gson()
    private val dataBuffer = mutableListOf<SensorDataPoint>()
    private val bufferMutex = Mutex()
    private var lastBatterySendTime = 0L
    private val batterySendInterval = 30000L

    // Armazena a diferença entre o tempo de boot e o tempo real (wall-clock)
    private var timestampOffset: Long = 0L

    companion object {
        const val WORK_NAME = "SensorCollectionWork"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "SensorServiceChannel"
        private const val BATCH_SIZE = DataLayerConstants.BATCH_SIZE
        private const val TAG = "SensorWorker" // TAG unificada
        private const val DATA_KEY_SENSOR_BATCH = "sensor_batch_data"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Worker iniciado.")
        setForeground(createForegroundInfo())
        try {
            coroutineScope {
                // Calcula o offset de tempo antes de iniciar a coleta
                calculateTimestampOffset()
                startRealSensorCollection()
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "Worker cancelado, enviando dados restantes.")
            // Garante que o buffer final seja enviado mesmo se o worker for cancelado
            withContext(NonCancellable) {
                flushDataBuffer()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro crítico no Worker", e)
            return Result.failure()
        } finally {
            sensorManager.unregisterListener(this)
            Log.d(TAG, "Worker finalizado e listener do sensor desregistrado.")
        }
        return Result.success()
    }

    private fun calculateTimestampOffset() {
        val currentTimeMillis = System.currentTimeMillis()
        val elapsedRealtimeMillis = SystemClock.elapsedRealtime()
        // O offset é a diferença que converte o "tempo desde o boot" para "tempo real"
        timestampOffset = currentTimeMillis - elapsedRealtimeMillis
        Log.d(TAG, "Offset de tempo calculado: $timestampOffset")
    }

    private suspend fun startRealSensorCollection() {
        Log.d(TAG, "Registrando listener do sensor com batching de hardware.")
        val samplingPeriodUs = 40_000
        val maxReportLatencyUs = 5_000_000 // 5 segundos
        val registered = sensorManager.registerListener(this, accelerometer, samplingPeriodUs, maxReportLatencyUs)
        if (!registered) {
            Log.e(TAG, "Não foi possível registrar o listener do acelerômetro.")
            throw IllegalStateException("Falha ao registrar o sensor")
        }
        awaitCancellation()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return

        // Converte o timestamp do evento (nanossegundos desde o boot) para milissegundos
        val eventTimeMillis = event.timestamp / 1_000_000
        // Aplica o offset para obter o timestamp Unix (tempo real UTC)
        val correctedTimestamp = eventTimeMillis + timestampOffset

        val dataPoint = SensorDataPoint(
            correctedTimestamp, // Usa o timestamp absoluto e corrigido
            floatArrayOf(event.values[0], event.values[1], event.values[2])
        )

        CoroutineScope(Dispatchers.Default).launch {
            addDataToBufferAndSend(dataPoint)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d(TAG, "Acurácia do sensor ${sensor?.name} mudou para: $accuracy")
    }

    private suspend fun addDataToBufferAndSend(dataPoint: SensorDataPoint) {
        var batchToSend: List<SensorDataPoint>? = null
        bufferMutex.withLock {
            dataBuffer.add(dataPoint)
            if (dataBuffer.size >= BATCH_SIZE) {
                batchToSend = ArrayList(dataBuffer)
                dataBuffer.clear()
                Log.d(TAG, "Buffer de software atingiu o tamanho ${batchToSend?.size}. Preparando para envio.")
            }
        }
        batchToSend?.let {
            sendDataToPhone(it)
        }
    }

    private suspend fun flushDataBuffer() {
        var finalBatch: List<SensorDataPoint>? = null
        bufferMutex.withLock {
            if (dataBuffer.isNotEmpty()) {
                finalBatch = ArrayList(dataBuffer)
                dataBuffer.clear()
            }
        }
        finalBatch?.let {
            Log.d(TAG, "Enviando lote final de ${it.size} amostras.")
            sendDataToPhone(it)
        }
    }

    private suspend fun sendDataToPhone(batch: List<SensorDataPoint>) {
        withWakeLock {
            val serializedBatch = gson.toJson(batch)
            try {
                val uniquePath = "${DataLayerConstants.SENSOR_DATA_PATH}/${System.currentTimeMillis()}"
                val putDataMapRequest = PutDataMapRequest.create(uniquePath)
                putDataMapRequest.dataMap.putString(DATA_KEY_SENSOR_BATCH, serializedBatch)
                val putDataRequest = putDataMapRequest.asPutDataRequest().setUrgent()
                dataClient.putDataItem(putDataRequest).await()
                Log.d(TAG, "Lote de ${batch.size} amostras enviado para o celular.")
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao enviar lote de dados para o Data Layer", e)
            }

            val currentTime = System.currentTimeMillis()
            if (currentTime - lastBatterySendTime > batterySendInterval) {
                sendBatteryLevel()
                lastBatterySendTime = currentTime
            }
        }
    }

    private suspend fun sendBatteryLevel() {
        val batteryIntent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level: Int = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level == -1 || scale == -1) return

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

    private suspend fun <T> withWakeLock(block: suspend () -> T): T {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SensorWorker::DataSendWakeLock")
        try {
            wakeLock.acquire(1 * 60 * 1000L)
            Log.d(TAG, "WakeLock adquirido para envio de dados.")
            return block()
        } finally {
            if (wakeLock.isHeld) {
                wakeLock.release()
                Log.d(TAG, "WakeLock liberado.")
            }
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
            .setContentText("Coletando dados do sensor.")
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

