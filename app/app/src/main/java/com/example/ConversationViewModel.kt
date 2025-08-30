package com.example.remote

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import java.text.SimpleDateFormat
import java.util.*

class ConversationViewModel : ViewModel() {

    private val _logs = MutableLiveData<List<LogItem>>(emptyList())
    val logs: LiveData<List<LogItem>> = _logs

    fun appendLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val newLog = LogItem(timestamp, message)
        _logs.value = _logs.value?.plus(newLog)
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
