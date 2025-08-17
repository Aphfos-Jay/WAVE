package com.example.remote

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WebSocketManager {

    private var webSocket: WebSocket? = null
    private var eventCallback: ((type: String, content: String) -> Unit)? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.SECONDS)
        .build()
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private var SERVER_IP = "192.168.137.1"
        private const val SERVER_PORT = 8080
        private const val CLIENT_ID = "android_client_1"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1000L

        // 🔧 WS_URL을 고정값 대신 함수로 만듦
        private fun buildWsUrl(): String {
            return "ws://$SERVER_IP:$SERVER_PORT/ws/agent/$CLIENT_ID"
        }
    }

    fun setServerIp(ip: String) {
        SERVER_IP = ip
    }

    fun isConnected(): Boolean = webSocket != null

    fun connect() {
        Thread {
            var attempt = 0
            while (attempt < MAX_RETRIES && webSocket == null) {
                attempt++
                try {
                    val request = Request.Builder().url(buildWsUrl()).build()
                    val listener = object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            Log.i("WebSocket", "✅ Connection successful: ${buildWsUrl()}")
                            this@WebSocketManager.webSocket = webSocket
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            Log.i("WebSocket", "📥 Message received: $text")
                            try {
                                val json = JSONObject(text)
                                val type = json.optString("type")
                                val content = json.optString("content")
                                handler.post { eventCallback?.invoke(type, content) }
                            } catch (e: Exception) {
                                Log.e("WebSocket", "❌ JSON parsing failed: ${e.message}")
                            }
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            Log.e("WebSocket", "❌ Connection failed: ${t.message}")
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            Log.w("WebSocket", "🔌 Connection closed: $reason ($code)")
                            this@WebSocketManager.webSocket = null
                        }
                    }
                    client.newWebSocket(request, listener)
                    if (webSocket != null) break
                    Log.w("WebSocket", "🔄 Retry attempt $attempt of $MAX_RETRIES")
                    Thread.sleep(RETRY_DELAY_MS)
                } catch (e: Exception) {
                    Log.e("WebSocket", "❌ Connection attempt $attempt failed: ${e.message}")
                    if (attempt == MAX_RETRIES) handler.post { throw e }
                }
            }
        }.start()
    }
    fun sendText(text: String) {
        if (webSocket == null) {
            Log.w("WebSocket", "⚠️ No active connection, data not sent: $text")
            return
        }
        webSocket?.send(text)
        Log.d("WebSocket", "📤 Joystick data sent: $text")
    }

    fun sendCommand(command: String) {
        if (webSocket == null) {
            Log.w("WebSocket", "⚠️ No active connection, command not sent: $command")
            return
        }
        val json = JSONObject().apply {
            put("type", "button")
            put("content", command)
        }
        Log.d("WebSocket", "📤 [sendCommand] 실제 전송: $json")
        webSocket?.send(json.toString())
    }
    fun setOnEventListener(callback: (type: String, content: String) -> Unit) {
        this.eventCallback = callback
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        client.dispatcher.executorService.shutdown()
        webSocket = null
    }
}