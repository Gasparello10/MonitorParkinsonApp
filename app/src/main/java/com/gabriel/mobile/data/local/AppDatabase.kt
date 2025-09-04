package com.gabriel.mobile.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// <<< MUDANÇA 1: Adicionar a nova entidade e aumentar a versão >>>
@Database(entities = [SensorDataBatch::class, BatteryReading::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sensorDataBatchDao(): SensorDataBatchDao
    // <<< MUDANÇA 2: Adicionar a referência ao novo DAO >>>
    abstract fun batteryReadingDao(): BatteryReadingDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "parkinson_monitor_db"
                )
                    // <<< MUDANÇA 3: Adicionar estratégia de migração >>>
                    // Como mudamos a estrutura (versão 1 -> 2), o Room precisa saber o que fazer.
                    // fallbackToDestructiveMigration irá apagar o banco antigo e criar um novo.
                    // Isso é seguro para desenvolvimento.
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

