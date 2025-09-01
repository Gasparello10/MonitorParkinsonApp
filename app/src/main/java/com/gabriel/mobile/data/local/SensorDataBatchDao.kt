package com.gabriel.mobile.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query

/**
 * DAO (Data Access Object) para interagir com a tabela de lotes pendentes.
 */
@Dao
interface SensorDataBatchDao {

    @Insert
    suspend fun insertBatch(batch: SensorDataBatch)

    @Query("SELECT * FROM pending_batches ORDER BY createdAt ASC LIMIT 1")
    suspend fun getOldestPendingBatch(): SensorDataBatch?

    @Delete
    suspend fun deleteBatch(batch: SensorDataBatch)

    /**
     * <<< NOVO: Deleta todos os lotes pendentes associados a uma sessão específica. >>>
     * Isso é crucial para limpar o backlog quando uma sessão é interrompida.
     */
    @Query("DELETE FROM pending_batches WHERE sessionId = :sessionId")
    suspend fun deleteBatchesBySessionId(sessionId: Int)
}
