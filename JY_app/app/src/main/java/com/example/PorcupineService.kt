package com.example.remote

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import ai.picovoice.porcupine.PorcupineManager
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.*
import java.io.File
import java.io.FileOutputStream

class PorcupineService : Service() {

    private var porcupineManager: PorcupineManager? = null
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var recognizerIntent: Intent
    private lateinit var wsManager: WebSocketManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val ACTION_START_FOREGROUND_SERVICE = "com.example.remote.START_SERVICE"
        const val ACTION_STOP_FOREGROUND_SERVICE = "com.example.remote.STOP_SERVICE"
        const val ACTION_START_STT = "com.example.remote.START_STT"
        const val ACTION_STOP_STT = "com.example.remote.STOP_STT"
        const val ACTION_SPEAK_OUT = "com.example.remote.SPEAK"
        const val EXTRA_TEXT_TO_SPEAK = "text"
        const val ACTION_UPDATE_STATUS = "com.example.remote.STATUS"
        const val EXTRA_STATUS_MESSAGE = "status"
        const val ACTION_UPDATE_RMS = "com.example.remote.RMS"
        const val EXTRA_RMS_VALUE = "rms"
        const val ACTION_LOG_CONVERSATION = "com.example.remote.LOG"
        const val EXTRA_LOG_TYPE = "log_type"
        const val EXTRA_LOG_CONTENT = "log_content"
    }

    // ---------------------------
    // 모드 유틸
    // ---------------------------
    private fun getAppMode(): String {
        val prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
        return prefs.getString(MainActivity.KEY_MODE, "controller") ?: "controller"
    }
    private fun isRc(): Boolean = getAppMode() == "rc"
    private fun isController(): Boolean = !isRc()
    private fun shouldSpeak(): Boolean = isRc()

    private val serviceWsListener: (type: String, content: String) -> Unit = { type, content ->
        when (type) {
            "Tts", "response" -> { // 이전/이후 형식 모두 수신
                if (shouldSpeak()) speakOut(content)
                sendStatus("⬅️ 서버 응답: $content")
                logConversation("서버 응답", content)
            }
            "error" -> {
                sendStatus("❗️ 서버 오류: $content")
                logConversation("서버 오류", content)
            }
        }
    }


    // ---------------------------

    override fun onCreate() {
        super.onCreate()
        initNotification()
        initTextToSpeech()

        wsManager = WebSocketManager.getInstance()
        wsManager.addEventListener(serviceWsListener) // 서비스의 리스너 등록

        if (isController()) {
            initSpeechRecognizer()
            initPorcupine()
            sendStatus("🎮 조종기 모드: 호출어/음성 인식 활성")
        } else {
            sendStatus("🤖 RC 모드: TTS 전담, 호출어/음성 인식 비활성")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_FOREGROUND_SERVICE -> startForegroundNotification("호출어 인식 준비 중...")
            ACTION_STOP_FOREGROUND_SERVICE -> {
                stopSTT()
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_START_STT -> {
                if (isController()) {
                    porcupineManager?.stop()
                    startSTT()
                } else {
                    sendStatus("RC 모드에서는 STT가 비활성입니다.")
                }
            }
            ACTION_STOP_STT -> stopSTT()
            ACTION_SPEAK_OUT -> {
                val text = intent.getStringExtra(EXTRA_TEXT_TO_SPEAK)
                if (text != null && shouldSpeak()) speakOut(text)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        wsManager.removeEventListener(serviceWsListener) // 리스너 해제
        stopSTT()
        porcupineManager?.delete()
        porcupineManager = null
        tts?.shutdown()
        tts = null
        speechRecognizer?.destroy()
        speechRecognizer = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------
    // Foreground Notification
    // ---------------------------
    private fun initNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("voice_channel", "Voice Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification(msg: String) {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "voice_channel")
            .setContentTitle("Voice Assistant")
            .setContentText(msg)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pending)
            .build()
        startForeground(1, notification)
    }

    // ---------------------------
    // TTS
    // ---------------------------
    private fun initTextToSpeech() {
        tts = TextToSpeech(this) {
            if (it != TextToSpeech.SUCCESS) log("TTS 초기화 실패")
        }.apply {
            this?.language = Locale.KOREAN
            this?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == "utterance_tts" && isController()) {
                        porcupineManager?.start()
                    }
                }
                override fun onError(utteranceId: String?) { log("TTS 오류 발생: $utteranceId") }
                override fun onStart(utteranceId: String?) { log("TTS 시작됨: $utteranceId") }
            })
        }
    }

    private fun speakOut(text: String, utteranceId: String? = "utterance_tts") {
        if (shouldSpeak()) {
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            logConversation("TTS", text)
            sendStatus("🔊 $text")
        } else if (isController()) {
            // ▼▼▼ JsonFactory 사용 ▼▼▼
            wsManager.sendText(JsonFactory.createTtsRequestMessage(text))
        }
    }

    // ---------------------------
    // Porcupine (조종기 전용)
    // ---------------------------
    private fun initPorcupine() {
        try {
            val keywordPath = getAssetFilePath("hi_porsche_android.ppn")
            val modelPath = getAssetFilePath("porcupine_params_ko.pv")

            porcupineManager = PorcupineManager.Builder()
                .setAccessKey("V0pKjHMbDKu1TxJheS8q2z35JhkVxICj25WhsfBKT2i8hr4BI3Ne7A==")
                .setKeywordPath(keywordPath)
                .setModelPath(modelPath)
                .setSensitivity(0.7f)
                .build(applicationContext) {
                    sendStatus("🟢 호출어 인식됨")
                    logConversation("Hotword", "호출어가 감지되었습니다")

                    porcupineManager?.stop()

                    if (isController()) {
                        speakOut("네, 말씀하세요") // RC가 말하도록 서버에 요청
                        Handler(Looper.getMainLooper()).postDelayed({
                            startSTT() // 0.5초 후 STT 시작
                        }, 500)
                    }
                }

            porcupineManager?.start()
        } catch (e: Exception) {
            sendStatus("Hotword 초기화 실패: ${e.message}")
        }
    }

    // ---------------------------
    // STT (조종기 전용)
    // ---------------------------
    private fun initSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "명령어를 말씀해주세요")
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                sendStatus("🎤 음성 인식 준비됨")
                Log.d("PorcupineService", "STT: onReadyForSpeech 호출됨")
            }

            override fun onResults(results: Bundle?) {
                Log.d("PorcupineService", "STT: onResults 호출됨. results Bundle: $results")
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                Log.d("PorcupineService", "STT: 인식된 텍스트 후보들 (ArrayList): $texts")

                val recognizedText = texts?.firstOrNull() ?: "인식 실패"
                logConversation("STT 결과", recognizedText)

                // ▶ 서버로 전송 (연결 준비될 때까지 재시도)
                val payload = JsonFactory.createSttMessage(recognizedText)
                sendWhenReady(payload)

                sendStatus("⬆️ 서버로 전송: $recognizedText")
                sendBroadcast(Intent("com.example.remote.STT_ENDED"))
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        porcupineManager?.start()
                    } catch (e: Exception) { /* ... */ }
                }, 400)
            }

            override fun onError(error: Int) {
                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "오디오 녹음 오류 (ERROR_AUDIO)"
                    SpeechRecognizer.ERROR_CLIENT -> "클라이언트 오류 (ERROR_CLIENT)"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "권한 부족 (ERROR_INSUFFICIENT_PERMISSIONS)"
                    SpeechRecognizer.ERROR_NETWORK -> "네트워크 오류 (ERROR_NETWORK)"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "네트워크 시간 초과 (ERROR_NETWORK_TIMEOUT)"
                    SpeechRecognizer.ERROR_NO_MATCH -> "일치하는 음성 없음 (ERROR_NO_MATCH)"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "인식기 사용 중 (ERROR_RECOGNIZER_BUSY)"
                    SpeechRecognizer.ERROR_SERVER -> "서버 오류 (ERROR_SERVER)"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "음성 입력 시간 초과 (ERROR_SPEECH_TIMEOUT)"
                    else -> "알 수 없는 오류: $error"
                }
                sendStatus("❌ STT 오류: $errorMessage")
                Log.e("PorcupineService", "STT: onError 호출됨. 오류 코드: $error ($errorMessage)")
                // 조종기에서만 음성 피드백이 필요하면 아래 한 줄 유지/제거 선택 가능
                // if (shouldSpeak()) speakOut("음성을 인식하지 못했어요.")

                sendBroadcast(Intent("com.example.remote.STT_ENDED"))

                // ✅ 오류로 STT가 끝난 경우에도 Porcupine 재시작
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        porcupineManager?.start()
                        sendStatus("🟢 호출어 대기 재개")
                    } catch (e: Exception) {
                        sendStatus("❌ 호출어 재시작 실패: ${e.message}")
                    }
                }, 400)
            }

            override fun onBeginningOfSpeech() {
                sendStatus("🗣️ 말하기 시작 감지됨")
                Log.d("PorcupineService", "STT: onBeginningOfSpeech 호출됨")
            }
            override fun onRmsChanged(rmsdB: Float) { sendRms(rmsdB) }
            override fun onBufferReceived(buffer: ByteArray?) { }
            override fun onEndOfSpeech() {
                sendStatus("🤐 말하기 종료 감지됨")
                Log.d("PorcupineService", "STT: onEndOfSpeech 호출됨")
            }
            override fun onPartialResults(partialResults: Bundle?) { }
            override fun onEvent(eventType: Int, params: Bundle?) { }
        })
    }

    private fun startSTT() {
        if (!isController()) {
            sendStatus("RC 모드에서는 STT가 비활성입니다.")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            sendStatus("🎤 마이크 권한이 없습니다.")
            return
        }
        sendStatus("🎙 STT 요청됨 (startSTT 진입)")
        speechRecognizer?.startListening(recognizerIntent)
        sendStatus("🎙 음성 인식 시작")
        Log.d("PorcupineService", "STT: startListening 호출 완료")
    }

    private fun stopSTT() {
        try {
            speechRecognizer?.stopListening()
            Log.d("PorcupineService", "STT: stopListening 호출됨")
        } catch (_: Exception) {
            Log.e("PorcupineService", "STT: stopListening 중 예외 발생")
        }
        sendStatus("⛔ 음성 인식 중지")
        sendBroadcast(Intent("com.example.remote.STT_ENDED"))

        // 조종기 모드일 때만 호출어 대기 재개
        if (isController()) {
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    porcupineManager?.start()
                    sendStatus("🟢 호출어 대기 재개")
                } catch (e: Exception) {
                    sendStatus("❌ 호출어 재시작 실패: ${e.message}")
                }
            }, 400)
        }
    }

    // ---------------------------
    // 공용 브로드캐스트/유틸
    // ---------------------------
    private fun sendStatus(msg: String) {
        sendBroadcast(Intent(ACTION_UPDATE_STATUS).apply {
            putExtra(EXTRA_STATUS_MESSAGE, msg)
        })
    }

    private fun sendRms(rms: Float) {
        sendBroadcast(Intent(ACTION_UPDATE_RMS).apply {
            putExtra(EXTRA_RMS_VALUE, rms)
        })
    }

    private fun logConversation(type: String, content: String) {
        sendBroadcast(Intent(ACTION_LOG_CONVERSATION).apply {
            putExtra(EXTRA_LOG_TYPE, type)
            putExtra(EXTRA_LOG_CONTENT, content)
        })
    }

    private fun getAssetFilePath(file: String): String {
        val outFile = File(filesDir, file)
        if (!outFile.exists()) {
            assets.open(file).use { input -> FileOutputStream(outFile).use { output -> input.copyTo(output) } }
        }
        return outFile.absolutePath
    }

    // ▶ 연결 준비될 때까지 최대 3초(500ms*6) 재시도
    private fun sendWhenReady(json: String, retries: Int = 6) {
        serviceScope.launch {
            repeat(retries) {
                if (wsManager.isConnected()) {
                    wsManager.sendText(json)   // ✅ 연결됐을 때 딱 1번만 전송
                    return@launch              // ✅ 바로 종료
                }
                delay(500)                     // 아직이면 0.5초 후 재시도
            }
            sendStatus("⚠️ 서버에 전송 실패(연결 지연)")
        }
    }

    private fun log(msg: String) = Log.d("PorcupineService", msg)
}
