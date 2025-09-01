package com.gabriel.wear.service // Ou o pacote correspondente

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log

class DeviceStateLogger : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        // isDeviceIdleMode() é a verificação principal para o modo Soneca (Doze)
        val isIdle = powerManager.isDeviceIdleMode

        // isPowerSaveMode() verifica se o modo "Economia de Bateria" geral está ativo
        val isPowerSaving = powerManager.isPowerSaveMode

        Log.d("DeviceStateLogger", "--- VERIFICAÇÃO DE ESTADO DE ENERGIA ---")
        Log.d("DeviceStateLogger", "Modo Soneca (Doze) ativo: $isIdle")
        Log.d("DeviceStateLogger", "Modo Economia de Bateria ativo: $isPowerSaving")
        Log.d("DeviceStateLogger", "------------------------------------")
    }
}