package com.gabriel.mobile.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Representa uma única leitura de bateria salva localmente,
 * que pode ser usada para reconstruir o histórico no servidor.
 */
@Entity(tableName = "pending_battery_readings")
data class BatteryReading(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val sessionId: Int,
    val batteryLevel: Int,
    val timestamp: Long = System.currentTimeMillis()
)
