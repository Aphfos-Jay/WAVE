package com.example.remote

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WebSocketManager private constructor() {

    private var webSocket: WebSocket? = null
    private val listeners = mutableListOf<(type: String, content: String) -> Unit>()
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS).build()
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        @Volatile private var instance: WebSocketManager? = null
        fun getInstance(): WebSocketManager =
            instance ?: synchronized(this) { instance ?: WebSocketManager().also { instance = it } }

        private var SERVER_IP = "192.168.137.1"
        private const val SERVER_PORT = 8080
        private var CLIENT_ID = "android_client_1"

        fun setClientId(id: String) { CLIENT_ID = id }
        fun setServerIp(ip: String) { SERVER_IP = ip }

        private fun buildWsUrl() = "ws://$SERVER_IP:$SERVER_PORT/ws/agent/$CLIENT_ID"
    }

    fun isConnected(): Boolean = webSocket != null

    fun connect() {
        if (isConnected()) { Log.w("WebSocket","already connected"); return }
        val request = Request.Builder().url(buildWsUrl()).build()
        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, r: Response) { webSocket = ws; Log.i("WebSocket","open") }

            // ▼▼▼ [수정] 메시지 파싱 로직 변경 ▼▼▼
            override fun onMessage(ws: WebSocket, text: String) {
                runCatching {
                    val j = JSONObject(text)
                    // "Type" 키를 먼저 찾고, 없으면 옛날 "type" 키를 찾도록 수정
                    val type = j.optString("Type", j.optString("type"))
                    var content = ""

                    // [핵심 수정] Tts 타입일 경우 "Text" 키에서 내용을 가져오도록 처리
                    if (type == "Tts") {
                        content = j.optString("Text")
                    } else {
                        // 그 외의 경우에는 기존 로직 유지
                        content = j.optString("Content", j.optString("content", j.toString()))
                    }

                    handler.post { listeners.forEach { it(type, content) } }
                }.onFailure { Log.e("WebSocket","bad json", it) }
            }


            override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) { webSocket = null; Log.e("WebSocket","fail", t) }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) { webSocket = null; Log.w("WebSocket","closed $code $reason") }
        })
    }

    fun sendText(text: String) {
        if (!isConnected()) { Log.w("WebSocket","not connected"); return }
        webSocket?.send(text)
    }

    // sendCommand는 이제 사용하지 않으므로 삭제하거나 주석 처리해도 좋습니다.
    fun sendCommand(command: String) {
        // 이 함수는 옛날 형식을 보냅니다. JsonFactory 사용을 권장합니다.
        val json = JSONObject().apply { put("type","button"); put("content", command) }
        sendText(json.toString())
    }

    fun addEventListener(l: (String,String)->Unit) { if (!listeners.contains(l)) listeners.add(l) }
    fun removeEventListener(l: (String,String)->Unit) { listeners.remove(l) }

    fun disconnect() { webSocket?.close(1000, "bye"); webSocket = null }
}