package com.gabriel.wear.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.gabriel.shared.DataLayerConstants
import com.gabriel.shared.SensorDataPoint
import com.gabriel.wear.R
import com.gabriel.wear.presentation.MainActivity
// <<< MUDANÇA 1: Importações adicionadas para o DataClient >>>
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.channels.awaitClose

class SensorWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    // <<< MUDANÇA 2: Trocamos MessageClient por DataClient >>>
    private val dataClient by lazy { Wearable.getDataClient(context) }
    private val gson = Gson()

    // Buffers e controle de concorrência
    private val dataBuffer = mutableListOf<SensorDataPoint>()
    private val bufferMutex = Mutex()
    private val sensorDataChannel = Channel<SensorDataPoint>(capacity = Channel.UNLIMITED)

    companion object {
        const val WORK_NAME = "SensorCollectionWork"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "SensorServiceChannel"
        private const val BATCH_SIZE = DataLayerConstants.BATCH_SIZE
        private const val TAG = "SensorWorker"
        // <<< MUDANÇA 3: Adicionada uma chave para o DataMap >>>
        private const val DATA_KEY_SENSOR_BATCH = "sensor_batch_data"
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
                        Log.d(TAG, "Registrando listener do sensor.")
                        sensorManager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_GAME)
                        awaitClose {
                            Log.d(TAG, "Cancelando listener do sensor.")
                            sensorManager.unregisterListener(listener)
                        }
                    }.collect { dataPoint ->
                        sensorDataChannel.send(dataPoint)
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

    // <<< MUDANÇA 4: Função de envio totalmente reescrita para usar DataClient >>>
    private suspend fun sendDataToPhone(batch: List<SensorDataPoint>) {
        val serializedBatch = gson.toJson(batch)
        try {
            // Para garantir que cada envio de lote dispare um evento onDataChanged,
            // criamos um caminho único adicionando o timestamp atual.
            val uniquePath = "${DataLayerConstants.SENSOR_DATA_PATH}/${System.currentTimeMillis()}"

            val putDataMapRequest = PutDataMapRequest.create(uniquePath)
            putDataMapRequest.dataMap.putString(DATA_KEY_SENSOR_BATCH, serializedBatch)

            val putDataRequest = putDataMapRequest.asPutDataRequest().setUrgent()

            dataClient.putDataItem(putDataRequest).await()
            Log.d(TAG, "Lote de ${batch.size} amostras adicionado ao Data Layer com sucesso.")

        } catch (e: Exception) {
            Log.e(TAG, "Falha ao enviar lote de dados para o Data Layer", e)
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