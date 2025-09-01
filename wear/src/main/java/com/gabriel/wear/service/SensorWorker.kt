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

    // Buffer para os dados coletados
    private val dataBuffer = mutableListOf<SensorDataPoint>()
    private val bufferMutex = Mutex()

    // Controle de envio de bateria
    private var lastBatterySendTime = 0L
    private val batterySendInterval = 30000L // 30 segundos

    companion object {
        const val WORK_NAME = "SensorCollectionWork"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "SensorServiceChannel"
        private const val BATCH_SIZE = DataLayerConstants.BATCH_SIZE
        private const val TAG = "SensorWorkerHardware"
        private const val DATA_KEY_SENSOR_BATCH = "sensor_batch_data"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Worker com batching de hardware iniciado.")
        setForeground(createForegroundInfo())

        try {
            // Usamos coroutineScope para garantir que as tarefas filhas sejam canceladas
            // junto com o worker.
            coroutineScope {
                startRealSensorCollection(this)
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "Worker cancelado, finalizando a coleta.")
        } catch (e: Exception) {
            Log.e(TAG, "Erro crítico no Worker", e)
            return Result.failure()
        } finally {
            // Garante que o listener seja desregistrado
            sensorManager.unregisterListener(this)
            Log.d(TAG, "Worker finalizado e listener do sensor desregistrado.")
        }
        return Result.success()
    }

    private suspend fun startRealSensorCollection(scope: CoroutineScope) {
        Log.d(TAG, "Registrando listener do sensor com batching de hardware.")

        // Frequência de amostragem: 25Hz (40.000 microssegundos)
        val samplingPeriodUs = 40_000

        // Latência máxima de relatório: 5 segundos (5.000.000 microssegundos)
        // O sistema entregará os dados em lotes a cada 5 segundos, no máximo.
        // A entrega pode ocorrer antes se o buffer FIFO do sensor encher.
        val maxReportLatencyUs = 5_000_000

        val registered = sensorManager.registerListener(
            this,
            accelerometer,
            samplingPeriodUs,
            maxReportLatencyUs
        )

        if (!registered) {
            Log.e(TAG, "Não foi possível registrar o listener do acelerômetro.")
            throw IllegalStateException("Falha ao registrar o sensor")
        }

        // Mantém a corrotina viva enquanto o worker estiver ativo
        // awaitCancellation() suspende a corrotina até que ela seja cancelada.
        awaitCancellation()
    }

    // <<< MUDANÇA CRÍTICA 1: onSensorChanged agora é o ponto central >>>
    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return

        // Criamos o dataPoint com o timestamp correto do evento
        val dataPoint = SensorDataPoint(
            event.timestamp, // <<< USA O TIMESTAMP DO EVENTO (nanossegundos desde o boot)
            floatArrayOf(event.values[0], event.values[1], event.values[2])
        )

        // Precisamos lançar uma nova corrotina para não bloquear a thread do sensor
        // e para poder usar funções 'suspend' como o Mutex e o envio de dados.
        GlobalScope.launch(Dispatchers.Default) {
            addDataToBufferAndSend(dataPoint)
        }
    }

    // <<< MUDANÇA CRÍTICA 2: onAccuracyChanged é necessário ao implementar a interface >>>
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d(TAG, "Acurácia do sensor ${sensor?.name} mudou para: $accuracy")
    }

    private suspend fun addDataToBufferAndSend(dataPoint: SensorDataPoint) {
        var batchToSend: List<SensorDataPoint>? = null
        bufferMutex.withLock {
            dataBuffer.add(dataPoint)
            if (dataBuffer.size >= BATCH_SIZE) {
                batchToSend = ArrayList(dataBuffer) // Cria uma cópia para enviar
                dataBuffer.clear()
                Log.d(TAG, "Buffer de software atingiu o tamanho ${batchToSend?.size}. Preparando para envio.")
            }
        }
        // Envia o lote fora do lock do mutex
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
            Log.d(TAG, "Lote de ${batch.size} amostras enviado para o celular com sucesso.")
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao enviar lote de dados para o Data Layer", e)
        }

        // Verificação para enviar o nível da bateria
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBatterySendTime > batterySendInterval) {
            sendBatteryLevel()
            lastBatterySendTime = currentTime
        }
    }

    private suspend fun sendBatteryLevel() {
        // (O corpo desta função permanece o mesmo do seu código original)
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
        // (O corpo desta função permanece o mesmo do seu código original)
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