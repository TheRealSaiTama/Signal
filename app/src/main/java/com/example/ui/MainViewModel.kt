package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.workDataOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface WorkbenchState {
    object Idle : WorkbenchState
    data class SourceLoaded(
        val uri: Uri,
        val name: String,
        val size: String,
        val telemetry: AudioTelemetry? = null
    ) : WorkbenchState
    data class Processing(val progress: Float) : WorkbenchState
    data class Completed(
        val name: String,
        val size: String,
        val anomalyPurged: String,
        val file: java.io.File,
        val wavFile: java.io.File? = null
    ) : WorkbenchState
    data class Error(val message: String) : WorkbenchState
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow<WorkbenchState>(WorkbenchState.Idle)
    val uiState: StateFlow<WorkbenchState> = _uiState.asStateFlow()

    private val audioRepository = AudioProcessingRepository(application)
    private var processingJob: Job? = null

    fun selectSource(uri: Uri, name: String, size: String) {
        _uiState.value = WorkbenchState.SourceLoaded(uri, name, size, telemetry = null)
        viewModelScope.launch {
            audioRepository.analyzeAudio(uri).fold(
                onSuccess = { telemetry ->
                    val currentState = _uiState.value
                    if (currentState is WorkbenchState.SourceLoaded && currentState.uri == uri) {
                        _uiState.value = currentState.copy(telemetry = telemetry)
                    }
                },
                onFailure = { exception ->
                    _uiState.value = WorkbenchState.Error(exception.message ?: "NO AUDIO TRACK DETECTED IN SOURCE")
                }
            )
        }
    }

    fun initiateSequence() {
        val currentState = _uiState.value
        if (currentState is WorkbenchState.SourceLoaded) {
            val anomaly = currentState.telemetry?.anomaly ?: "STATIC"
            processingJob?.cancel()
            processingJob = viewModelScope.launch {
                val workManager = WorkManager.getInstance(getApplication())
                val workRequest = OneTimeWorkRequestBuilder<AudioCleanWorker>()
                    .setInputData(workDataOf("inputUri" to currentState.uri.toString()))
                    .build()

                _uiState.value = WorkbenchState.Processing(0f)
                workManager.enqueue(workRequest)

                workManager.getWorkInfoByIdFlow(workRequest.id).collect { workInfo ->
                    if (workInfo != null) {
                        when (workInfo.state) {
                            WorkInfo.State.RUNNING -> {
                                val progressPercent = workInfo.progress.getInt("progress", 0)
                                _uiState.value = WorkbenchState.Processing(progressPercent.toFloat() / 100f)
                            }
                            WorkInfo.State.SUCCEEDED -> {
                                val outputUriString = workInfo.outputData.getString("outputUri")
                                val wavUriString = workInfo.outputData.getString("wavUri")
                                if (outputUriString != null) {
                                    val file = java.io.File(Uri.parse(outputUriString).path ?: "")
                                    val wavFile = wavUriString?.let { java.io.File(Uri.parse(it).path ?: "") }
                                    _uiState.value = WorkbenchState.Completed(
                                        name = "CLEANED_" + file.name.substringAfterLast("DEEPFILTERNET_CLEANED_"),
                                        size = formatFileSize(file.length()),
                                        anomalyPurged = anomaly,
                                        file = file,
                                        wavFile = wavFile
                                    )
                                } else {
                                    _uiState.value = WorkbenchState.Error("NO OUTPUT PAYLOAD REGISTERED")
                                }
                            }
                            WorkInfo.State.FAILED -> {
                                val errorMsg = workInfo.outputData.getString("error") ?: "SPECTRAL FILTERING FAILED"
                                _uiState.value = WorkbenchState.Error(errorMsg)
                            }
                            else -> {}
                        }
                    }
                }
            }
        }
    }

    suspend fun exportFile(file: java.io.File, displayName: String, wavFile: java.io.File? = null): Uri? {
        val m4aUri = audioRepository.exportToPublicDirectory(file, displayName)
        if (wavFile != null && wavFile.exists()) {
            audioRepository.exportToPublicDirectory(wavFile, "Signal_Clean_Fallback.wav")
        }
        return m4aUri
    }

    private fun formatFileSize(sizeBytes: Long): String {
        if (sizeBytes <= 0) return "0.00 KB"
        val kb = sizeBytes / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1.0) {
            String.format("%.2f MB", mb)
        } else {
            String.format("%.2f KB", kb)
        }
    }

    fun ejectMedium() {
        processingJob?.cancel()
        _uiState.value = WorkbenchState.Idle
    }
}

