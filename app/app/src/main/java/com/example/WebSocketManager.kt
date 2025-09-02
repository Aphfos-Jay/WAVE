package com.example.remote

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// 클라우드와 WebSocket 연결을 관리하는 싱글톤
// connect(): 서버 연결
// sendText(): 메시지 전송
// add/removeEventListener(): 수신 콜백 등록
// disconnect(): 연결 종료

class WebSocketManager private constructor() {

    private var webSocket: WebSocket? = null
    private val listeners = mutableListOf<(type: String, content: String) -> Unit>()
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()
    private val handler = Handler(Looper.getMainLooper())



    // 싱글톤 인스턴스 보관
    // CLIENT_ID: 기기/모드 구분용 → 쿼리 파라미터로 붙여 전송
    companion object {
        @Volatile private var instance: WebSocketManager? = null
        fun getInstance(): WebSocketManager =
            instance ?: synchronized(this) {
                instance ?: WebSocketManager().also { instance = it }
            }

        // 서버 WebSocket 엔드포인트 (direction 기준)
        private val SERVER_BASE = BuildConfig.SERVER_URL

        private var CLIENT_ID = "android_client_1"

        fun setClientId(id: String) { CLIENT_ID = id }

        private fun buildWsUrls(): List<String> {
            return listOf(
                "$SERVER_BASE?id=$CLIENT_ID"   // clientId 쿼리 붙여 서버 세션 구분
            )
        }
    }

    fun isConnected(): Boolean = webSocket != null


    // 서버 WebSocket 연결 시작
    // 이미 연결된 상태면 무시
    // 여러 URL 후보를 순서대로 시도 (향후 확장 가능)
    fun connect() {
        if (isConnected()) {
            Log.w("WebSocket", "⚠️ 이미 연결됨")
            return
        }

        val urls = buildWsUrls()
        tryConnect(urls, 0)
    }

    // 지정된 URL로 연결 시도
    // onOpen: 연결 성공
    // onMessage: JSON 파싱 후 type/content로 콜백 전달
    // onFailure: 실패 시 다음 URL로 재시도
    // onClosed: 정상 종료 → webSocket=null 처리
    private fun tryConnect(urls: List<String>, index: Int) {
        if (index >= urls.size) {
            Log.e("WebSocket", "모든 WebSocket URL 연결 실패")
            return
        }
        val url = urls[index]
        val request = Request.Builder().url(url).build()
        Log.i("WebSocket", "연결 시도: $url")

        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, r: Response) {
                webSocket = ws
                Log.i("WebSocket", "✅ 연결 성공: $url (code=${r.code})")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                Log.d("WebSocket", "📩 수신: $text")
                runCatching {
                    val j = JSONObject(text)   // JSON 파싱 시도
                    val type = j.optString("Type", j.optString("type"))
                    val content = if (type == "Tts") {
                        j.optString("Text")
                    } else {
                        j.optString("Content", j.optString("content", j.toString()))
                    }
                    handler.post { listeners.forEach { it(type, content) } }
                }.onFailure {
                    // JSON 파싱 실패 → 원문을 Raw 로 전달
                    Log.w("WebSocket", "⚠JSON 아님, 원문 전달")
                    handler.post { listeners.forEach { it("Raw", text) } }
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) {
                webSocket = null
                Log.e("WebSocket", "❌ 연결 실패 (${url}): ${t.message}")
                // 다음 URL로 재시도
                tryConnect(urls, index + 1)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                webSocket = null
                Log.w("WebSocket", "연결 종료: code=$code reason=$reason")
            }
        })
    }

    // 서버로 문자열 전송 (연결 안 되어 있으면 경고 로그)
    fun sendText(text: String) {
        if (!isConnected()) {
            Log.w("WebSocket", "⚠️ 전송 실패 (연결 안 됨)")
            return
        }
        Log.d("WebSocket", "📤 전송: $text")
        webSocket?.send(text)
    }

    fun addEventListener(l: (String, String) -> Unit) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeEventListener(l: (String, String) -> Unit) {
        listeners.remove(l)
    }

    fun disconnect() {
        if (isConnected()) {
            Log.i("WebSocket", "연결 종료 요청")
            webSocket?.close(1000, "bye")
            webSocket = null
        } else {
            Log.w("WebSocket", "이미 연결 해제 상태")
        }
    }
}
