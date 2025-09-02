package com.example.remote

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import java.text.SimpleDateFormat
import java.util.*

// 대화 로그를 관리하는 ViewModel
// - UI(Fragment)에서 관찰(LiveData)하여 실시간으로 로그 표시

class ConversationViewModel : ViewModel() {

    private val _logs = MutableLiveData<List<LogItem>>(emptyList())
    val logs: LiveData<List<LogItem>> = _logs


    // 새로운 로그를 시간과 함께 추가
    fun appendLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val newLog = LogItem(timestamp, message)
        _logs.value = _logs.value?.plus(newLog)
    }

    // 모든 로그 삭제 (초기화 버튼 등에서 사용)
    fun clearLogs() {
        _logs.value = emptyList()
    }
}
