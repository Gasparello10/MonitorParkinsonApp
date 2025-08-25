package com.gabriel.mobile.presentation

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.gabriel.mobile.service.DataLayerListenerService
import com.gabriel.mobile.service.MonitoringService
import com.gabriel.shared.DataLayerConstants
import com.gabriel.shared.SensorDataPoint
import com.google.android.gms.wearable.Wearable
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class Patient(val id: String = UUID.randomUUID().toString(), val name: String)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val gson = Gson()
    private val sharedPreferences = application.getSharedPreferences("patient_prefs", Context.MODE_PRIVATE)
    private val nodeClient by lazy { Wearable.getNodeClient(application) }

    private val _patients = MutableStateFlow<List<Patient>>(emptyList())
    val patients = _patients.asStateFlow()

    private val _selectedPatient = MutableStateFlow<Patient?>(null)
    val selectedPatient = _selectedPatient.asStateFlow()

    private val _isSessionActive = MutableStateFlow(false)
    val isSessionActive = _isSessionActive.asStateFlow()

    private val _isInSelectionMode = MutableStateFlow(false)
    val isInSelectionMode = _isInSelectionMode.asStateFlow()

    private val _selectedForDeletion = MutableStateFlow<Set<String>>(emptySet())
    val selectedForDeletion = _selectedForDeletion.asStateFlow()

    private val _status = MutableStateFlow("Aguardando Início")
    val status = _status.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _sensorDataPoints = MutableStateFlow<List<SensorDataPoint>>(emptyList())
    val sensorDataPoints = _sensorDataPoints.asStateFlow()

    private val serviceUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MonitoringService.ACTION_STATUS_UPDATE -> {
                    _status.value = intent.getStringExtra(MonitoringService.EXTRA_STATUS_MESSAGE) ?: "Status desconhecido"
                }
                MonitoringService.ACTION_SESSION_STATE_UPDATE -> {
                    _isSessionActive.value = intent.getBooleanExtra(MonitoringService.EXTRA_IS_SESSION_ACTIVE, false)
                }
                MonitoringService.ACTION_NEW_DATA_UPDATE -> {
                    val dataPointsJson = intent.getStringExtra(MonitoringService.EXTRA_DATA_POINTS)
                    if (dataPointsJson != null) {
                        val type = object : TypeToken<List<SensorDataPoint>>() {}.type
                        _sensorDataPoints.value = gson.fromJson(dataPointsJson, type)
                    }
                }
            }
        }
    }

    init {
        val intentFilter = IntentFilter().apply {
            addAction(MonitoringService.ACTION_STATUS_UPDATE)
            addAction(MonitoringService.ACTION_SESSION_STATE_UPDATE)
            addAction(MonitoringService.ACTION_NEW_DATA_UPDATE)
        }
        LocalBroadcastManager.getInstance(application).registerReceiver(serviceUpdateReceiver, intentFilter)

        checkConnection()
        loadPatients()
    }

    fun addPatient(name: String) {
        val newPatient = Patient(name = name)
        val updatedList = _patients.value + newPatient
        _patients.value = updatedList
        savePatients(updatedList)
    }

    fun selectPatient(patient: Patient) {
        _selectedPatient.value = patient
        val serviceIntent = Intent(getApplication(), MonitoringService::class.java).apply {
            action = MonitoringService.ACTION_CONNECT
            putExtra(MonitoringService.EXTRA_PATIENT_NAME, patient.name)
        }
        getApplication<Application>().startService(serviceIntent)
        Log.d("ViewModel_DEBUG", "Comando ACTION_CONNECT enviado para o serviço para o paciente ${patient.name}")
    }

    fun deleteSelectedPatients() {
        val selection = _selectedForDeletion.value
        if (selection.isEmpty()) {
            exitSelectionMode()
            return
        }
        if (_selectedPatient.value != null && selection.contains(_selectedPatient.value!!.id)) {
            if (_isSessionActive.value) {
                stopSession()
            }
            _selectedPatient.value = null
        }
        val updatedList = _patients.value.filter { !selection.contains(it.id) }
        _patients.value = updatedList
        savePatients(updatedList)
        exitSelectionMode()
    }

    fun enterSelectionMode(initialPatientId: String) {
        _isInSelectionMode.value = true
        _selectedForDeletion.value = setOf(initialPatientId)
    }

    fun toggleSelection(patientId: String) {
        _selectedForDeletion.update { currentSelection ->
            if (currentSelection.contains(patientId)) {
                currentSelection - patientId
            } else {
                currentSelection + patientId
            }
        }
    }

    fun exitSelectionMode() {
        _isInSelectionMode.value = false
        _selectedForDeletion.value = emptySet()
    }

    // <<< FUNÇÃO REMOVIDA >>>
    // A função startSession(sessionId: Int) foi removida pois o serviço agora lida com o início internamente.

    // <<< FUNÇÃO ADICIONADA >>>
    /**
     * Pede ao MonitoringService para iniciar o processo de começo de uma nova sessão.
     * O serviço irá contatar o servidor, que por sua vez criará a sessão e responderá
     * com um comando para iniciar o monitoramento de fato.
     */
    fun requestStartSession() {
        val patient = _selectedPatient.value
        if (patient == null) {
            Log.w("MainViewModel", "Tentativa de iniciar sessão sem paciente selecionado.")
            return
        }
        Log.d("MainViewModel", "Requisitando início de sessão para ${patient.name}")

        val serviceIntent = Intent(getApplication(), MonitoringService::class.java).apply {
            action = MonitoringService.ACTION_REQUEST_START_SESSION
            putExtra(MonitoringService.EXTRA_PATIENT_NAME, patient.name)
        }
        getApplication<Application>().startService(serviceIntent)
    }

    fun stopSession() {
        Log.d("MainViewModel", "Enviando comando para parar o MonitoringService")
        val serviceIntent = Intent(getApplication(), MonitoringService::class.java).apply {
            action = MonitoringService.ACTION_STOP
        }
        getApplication<Application>().startService(serviceIntent)
    }

    private fun savePatients(patients: List<Patient>) {
        val json = gson.toJson(patients)
        sharedPreferences.edit().putString("patient_list", json).apply()
    }

    private fun loadPatients() {
        val json = sharedPreferences.getString("patient_list", null)
        if (json != null) {
            val type = object : TypeToken<List<Patient>>() {}.type
            _patients.value = gson.fromJson(json, type)
        }
    }

    fun checkConnection() {
        nodeClient.connectedNodes.addOnSuccessListener { nodes ->
            _isConnected.value = nodes.any { it.isNearby }
        }
    }

    override fun onCleared() {
        super.onCleared()
        LocalBroadcastManager.getInstance(getApplication()).unregisterReceiver(serviceUpdateReceiver)
    }
}