package com.gabriel.mobile.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Representa um lote de dados de sensor salvo localmente,
 * aguardando para ser enviado ao servidor.
 */
@Entity(tableName = "pending_batches")
data class SensorDataBatch(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val sessionId: Int,
    val patientId: String,
    val jsonData: String, // O lote de dados já serializado como uma string JSON
    val createdAt: Long = System.currentTimeMillis()
)