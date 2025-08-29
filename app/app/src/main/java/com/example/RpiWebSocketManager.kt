package com.example.remote

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.*
import java.util.concurrent.TimeUnit

object RpiWebSocketManager {

    private var webSocket: WebSocket? = null
    private val listeners = mutableListOf<(String, String) -> Unit>()
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val handler = Handler(Looper.getMainLooper())

    private var rpiUrl: String = ""

    /** 라즈베리파이 서버 연결 */
    fun connect(ip: String, port: Int = 9000, clientId: String = "android_client") {
        // ✅ clientId를 URL에 쿼리로 붙임
        val type = clientId
        rpiUrl = "ws://$ip:$port/ws?type=$type&id=$clientId"
        val request = Request.Builder().url(rpiUrl).build()
        Log.i("RpiWS", "📡 RPi WebSocket 연결 시도: $rpiUrl")

        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                webSocket = ws
                Log.i("RpiWS", "✅ 연결 성공 (code=${response.code})")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                Log.d("RpiWS", "📩 수신: $text")
                handler.post { listeners.forEach { it("Msg", text) } }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) {
                webSocket = null
                Log.e("RpiWS", "❌ 연결 실패: ${t.message}")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                webSocket = null
                Log.w("RpiWS", "⚠️ 연결 종료: $reason")
            }
        })
    }

    /** JSON 전송 */
    fun sendText(msg: String) {
        if (webSocket == null) {
            Log.w("RpiWS", "⚠️ 전송 실패 (연결 없음)")
            return
        }
        Log.d("RpiWS", "📤 전송: $msg")
        webSocket?.send(msg)
    }

    /** 수신 리스너 등록 */
    fun addEventListener(l: (String, String) -> Unit) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeEventListener(l: (String, String) -> Unit) {
        listeners.remove(l)
    }

    fun disconnect() {
        webSocket?.close(1000, "bye")
        webSocket = null
        Log.i("RpiWS", "🛑 수동 연결 종료")
    }
}
