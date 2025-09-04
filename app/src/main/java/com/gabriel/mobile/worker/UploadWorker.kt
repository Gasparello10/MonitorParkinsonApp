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
import org.json.JSONArray
import org.json.JSONObject

class UploadWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val batchDao = AppDatabase.getDatabase(appContext).sensorDataBatchDao()
    private val networkClient = OkHttpClient()

    // Define quantos lotes do banco de dados tentaremos enviar de uma só vez
    private val BATCH_UPLOAD_SIZE = 20

    override suspend fun doWork(): Result {
        while (true) {
            // Pega um conjunto de lotes pendentes, até o limite definido
            val pendingBatches = batchDao.getOldestPendingBatches(BATCH_UPLOAD_SIZE)

            if (pendingBatches.isEmpty()) {
                Log.d("UploadWorker", "Nenhum lote pendente encontrado. Trabalho concluído.")
                return Result.success()
            }

            // Agrupa os dados por sessão para enviar uma requisição por sessão
            val batchesBySession = pendingBatches.groupBy { it.sessionId }

            // Itera sobre cada sessão que tem dados pendentes
            for ((sessionId, batchesForSession) in batchesBySession) {

                val patientId = batchesForSession.firstOrNull()?.patientId ?: continue

                Log.d("UploadWorker", "Agrupando ${batchesForSession.size} lotes para a sessão $sessionId. Tentando enviar...")

                try {
                    val serverUrl = "${BuildConfig.SERVER_URL}/data"

                    // Combina todos os dados JSON de múltiplos lotes em um único JSONArray
                    val combinedJsonData = JSONArray()
                    batchesForSession.forEach { batch ->
                        try {
                            val batchJsonArray = JSONArray(batch.jsonData)
                            for (i in 0 until batchJsonArray.length()) {
                                combinedJsonData.put(batchJsonArray.getJSONObject(i))
                            }
                        } catch (e: Exception) {
                            Log.e("UploadWorker", "Erro ao parsear o JSON do lote ${batch.id}, pulando este lote.", e)
                        }
                    }

                    // Se todos os lotes de um grupo estiverem corrompidos, deleta e continua
                    if (combinedJsonData.length() == 0) {
                        Log.w("UploadWorker", "Nenhum dado válido para enviar para a sessão $sessionId após parse. Deletando lotes corrompidos.")
                        batchDao.deleteBatches(batchesForSession)
                        continue // Pula para o próximo grupo de sessão
                    }

                    val rootJsonObject = JSONObject().apply {
                        put("patientId", patientId)
                        put("sessao_id", sessionId)
                        put("data", combinedJsonData)
                    }

                    val requestBody = rootJsonObject.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                    val request = Request.Builder().url(serverUrl).post(requestBody).build()

                    networkClient.newCall(request).execute().use { response ->
                        when {
                            response.isSuccessful -> {
                                Log.i("UploadWorker", "${batchesForSession.size} lotes da sessão $sessionId enviados com sucesso! Deletando do banco local.")
                                batchDao.deleteBatches(batchesForSession)
                            }
                            response.code in 400..499 -> {
                                Log.e("UploadWorker", "Erro do cliente (${response.code}) ao enviar o grupo de lotes para a sessão $sessionId. Os dados são inválidos ou a sessão não existe mais. Deletando lotes locais.")
                                batchDao.deleteBatches(batchesForSession)
                            }
                            else -> {
                                Log.w("UploadWorker", "Falha no envio do grupo de lotes para a sessão $sessionId: ${response.code}. Tentando novamente mais tarde.")
                                return Result.retry()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("UploadWorker", "Erro de rede/exceção ao enviar o grupo de lotes para a sessão $sessionId.", e)
                    return Result.retry()
                }
            } // Fim do loop de sessões
        } // Fim do loop principal (while true)
    }
}

