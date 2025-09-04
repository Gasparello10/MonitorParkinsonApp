package com.gabriel.mobile.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gabriel.mobile.BuildConfig
import com.gabriel.mobile.data.local.AppDatabase
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class UploadWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val batchDao = AppDatabase.getDatabase(appContext).sensorDataBatchDao()
    private val networkClient = OkHttpClient()

    override suspend fun doWork(): Result {

        // <<< MUDANÇA 1: Adicionado um loop para processar todos os lotes em sequência >>>
        while (true) {
            // Pega o lote mais antigo que ainda não foi enviado
            val pendingBatch = batchDao.getOldestPendingBatch()
                ?: return Result.success().also {
                    // Se não há mais lotes, o trabalho termina com sucesso.
                    Log.d("UploadWorker", "Nenhum lote pendente encontrado. Trabalho concluído.")
                }

            Log.d("UploadWorker", "Encontrado lote pendente (id: ${pendingBatch.id}). Tentando enviar...")

            try {
                val serverUrl = "${BuildConfig.SERVER_URL}/data"

                val rootJsonObject = JSONObject().apply {
                    put("patientId", pendingBatch.patientId)
                    put("sessao_id", pendingBatch.sessionId)
                    put("data", org.json.JSONArray(pendingBatch.jsonData))
                }

                val requestBody = rootJsonObject.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder().url(serverUrl).post(requestBody).build()

                networkClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.i("UploadWorker", "Lote ${pendingBatch.id} enviado com sucesso! Deletando do banco local.")
                        batchDao.deleteBatch(pendingBatch)

                        // <<< MUDANÇA 2: A linha "Result.success()" foi REMOVIDA daqui >>>
                        // Em vez de terminar, o loop continua para o próximo lote.

                    } else {
                        Log.w("UploadWorker", "Falha no envio do lote ${pendingBatch.id}: ${response.code}. Tentando novamente mais tarde.")
                        // Se o servidor deu erro, paramos e pedimos para o WorkManager tentar de novo mais tarde.
                        return Result.retry()
                    }
                }
            } catch (e: Exception) {
                Log.e("UploadWorker", "Erro de rede/exceção ao enviar o lote ${pendingBatch.id}.", e)
                // Se a rede caiu ou deu outro erro, paramos e pedimos para tentar de novo.
                return Result.retry()
            }
        } // Fim do loop
    }
}

