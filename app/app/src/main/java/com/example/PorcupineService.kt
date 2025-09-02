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
import java.io.File
import java.io.FileOutputStream
import java.util.*
import java.util.regex.Pattern
import org.json.JSONObject

// 백그라운드 음성 관련 서비스
// Picovoice Porcupine으로 호출어 감지
// Controller 모드: STT → 서버 전송
// RC 모드: 서버에서 온 TTS 출력 전담
// 음성 기반 제어 명령/대화 처리 + WebSocket 통신 + TTS 실행

class PorcupineService : Service() {

    private var porcupineManager: PorcupineManager? = null
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var recognizerIntent: Intent
    private lateinit var wsManager: WebSocketManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var voiceMacroJob: Job? = null // 음성 매크로 실행을 제어하기 위한 Job

    private val KOR_COMMAND = mapOf(
        "Forward" to "앞으로",
        "Back" to "뒤로",
        "Forward-Left" to "좌회전",
        "Forward-Right" to "우회전"
    )

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
        const val ACTION_SEND_CONTROL_COMMAND = "com.example.remote.SEND_CONTROL"
        const val EXTRA_CONTROL_COMMAND_JSON = "control_json"

        // 제어 락(Lock) 상태를 전달하기 위한 Action 추가
        const val ACTION_SET_CONTROL_LOCK = "com.example.remote.SET_LOCK"
        const val EXTRA_IS_LOCKED = "is_locked"
    }


    private fun getAppMode(): String {
        val prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
        return prefs.getString(MainActivity.KEY_MODE, "controller") ?: "controller"
    }
    private fun isRc(): Boolean = getAppMode() == "rc"
    private fun isController(): Boolean = !isRc()
    private fun shouldSpeak(): Boolean = isRc()

    private val serviceWsListener: (type: String, content: String) -> Unit = { type, content ->
        when (type) {
            "Tts", "response" -> {
                if (isController()) {
                    sendTtsCommandOverTcp(content)
                } else if (isRc()) {
                    speakOut(content)
                }
            }

            // CapAnalysis 처리 추가
            "CapAnalysis" -> {
                try {
                    val json = JSONObject(content)
                    val id = json.optString("ID", "")
                    val resultText = json.optString("result", "")
                    val summary = extractSummary(resultText)
                    val finalSummary = if (summary == "분석 결과 요약 불가")
                        "분석 결과를 확인해 주세요."
                    else summary

                    // 로컬 SharedPreferences에 저장
                    saveAnalysisToPrefs(id, resultText)

                    // 1) RC로 요약본 TTS 전송
                    val ttsJson = JsonFactory.createTtsRequestMessage(finalSummary)
                    RpiWebSocketManager.sendText(ttsJson)

                    // 2) 전체 원문은 로그/저장용으로 남기기
                    sendStatus("🧠 AI 분석 결과 수신")
                    sendStatus("🧠 CapAnalysis 요약: $finalSummary")
                    logConversation("CapAnalysis", resultText)
                    logConversation("CapAnalysis_Summary", finalSummary)

                } catch (e: Exception) {
                    sendStatus("❌ CapAnalysis 처리 실패: ${e.message}")
                }
            }


            // Agent응답 처리 추가
            "SttResult" -> {
                try {
                    val json = JSONObject(content)
                    val reply = json.optString("Text", content)

                    // RC가 읽을 수 있도록 Tts 형식으로 변환
                    val msg = JsonFactory.createTtsRequestMessage(reply)

                    RpiWebSocketManager.sendText(msg)

                    sendStatus("🤖 GPT 응답 전달(Tts): $reply")
                    logConversation("SttResult", reply)
                } catch (e: Exception) {
                    sendStatus("❌ SttResult 처리 실패: ${e.message}")
                }
            }


        }
    }

    private fun saveAnalysisToPrefs(id: String, analysis: String) {
        val prefs = getSharedPreferences("analysis_store", MODE_PRIVATE)
        prefs.edit().putString(id, analysis).apply()
    }


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
                Log.d("PorcupineService", "TTS 브로드캐스트 수신: $text")
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
            .setContentTitle("SurfBoard")
            .setContentText(msg)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pending)
            .build()
        startForeground(1, notification)
    }



    // TTS 기본 설정 - 현재는 남성 목소리로 강제 설정되었습니다
    private fun initTextToSpeech() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.KOREAN

                // Voice를 이름으로 직접 지정
                val maleVoice = tts?.voices?.find { it.name == "ko-kr-x-koc-local" }
                if (maleVoice != null) {
                    tts?.voice = maleVoice
                } else {
                }
            } else {
                log("TTS 초기화 실패")
            }
        }
    }

    private fun sendTtsCommandToFragment(text: String) {
        val ttsJson = JsonFactory.createTtsRequestMessage(text)
        val intent = Intent(PorcupineService.ACTION_SEND_CONTROL_COMMAND).apply {
            putExtra(PorcupineService.EXTRA_CONTROL_COMMAND_JSON, ttsJson)
        }
        sendBroadcast(intent)
    }

    private fun sendTtsCommandOverTcp(text: String) {
        val ttsJson = JsonFactory.createTtsRequestMessage(text)
        val intent = Intent(ACTION_SEND_CONTROL_COMMAND).apply {
            putExtra(EXTRA_CONTROL_COMMAND_JSON, ttsJson)
        }
        sendBroadcast(intent) // ← ControllerFragment 가 받아서 TCP 전송
    }



    private fun speakOut(text: String, utteranceId: String? = "utterance_tts") {
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        logConversation("TTS", text)
        sendStatus("🔊 $text")
    }

    private fun initPorcupine() {
        try {
            val keywordPath = getAssetFilePath("surf.ppn")
            val modelPath = getAssetFilePath("porcupine_params_ko.pv")

            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(BuildConfig.PORCUPINE_KEY)
                .setKeywordPath(keywordPath)
                .setModelPath(modelPath)
                .setSensitivity(0.7f)
                .build(applicationContext) {
                    sendStatus("🟢 호출어 인식됨")
                    logConversation("Hotword", "호출어가 감지되었습니다")

                    // RC에서 바로 "네, 말씀하세요" 발화
                    val ttsJson = JsonFactory.createTtsRequestMessage("네, 말씀하세요")
                    RpiWebSocketManager.sendText(ttsJson)

                    porcupineManager?.stop()

                    if (isController()) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            startSTT()
                        }, 1600)
                    }
                }

            porcupineManager?.start()
        } catch (e: Exception) {
            sendStatus("Hotword 초기화 실패: ${e.message}")
        }
    }

    private fun initSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "명령어를 말씀해주세요")
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { sendStatus("🎤 음성 인식 준비됨") }

            override fun onResults(results: Bundle?) {
                val recognizedText = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: "인식 실패"
                logConversation("STT 결과", recognizedText)
                parseAndRouteStt(recognizedText) // << 로직은 이 함수로 통합
                // 후처리 코드는 parseAndRouteStt 내부에서 호출됨
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
                sendStatus("STT 오류: $errorMessage")
                finishSttSession()
            }

            override fun onBeginningOfSpeech() { sendStatus("🗣️ 말하기 시작 감지됨") }
            override fun onRmsChanged(rmsdB: Float) { sendRms(rmsdB) }
            override fun onBufferReceived(buffer: ByteArray?) { }
            override fun onEndOfSpeech() { sendStatus("🤐 말하기 종료 감지됨") }
            override fun onPartialResults(partialResults: Bundle?) { }
            override fun onEvent(eventType: Int, params: Bundle?) { }
        })
    }

    //STT 세션을 종료하고 Porcupine을 재시작하는 공통 함수
    private fun finishSttSession() {
        sendBroadcast(Intent("com.example.remote.STT_ENDED"))
        Handler(Looper.getMainLooper()).postDelayed({
            try { porcupineManager?.start() } catch (e: Exception) { /* ... */ }
        }, 400)
    }

    private fun extractSummary(result: String): String {
        val clean = result.replace("\\n", "\n")
        val dangerSection = Regex("위험[ ]*요소[\\s\\S]+?(즉시|이러한 조치)").find(clean)?.value
        val dangers = mutableListOf<String>()
        dangerSection?.lines()?.forEach { line ->
            val t = line.trim()
            when {
                t.startsWith("위험") -> {}
                t.contains(":") -> dangers.add(t.substringBefore(":").trim())
                t.contains(",") -> dangers.addAll(t.split(",").map { it.trim() })
                t.startsWith("-") -> dangers.add(t.removePrefix("-").substringBefore(":").trim())
                t.matches(Regex("^[0-9]+\\..*")) -> {
                    val part = t.substringAfter(".").substringBefore(":").trim()
                    if (part.isNotEmpty()) dangers.add(part)
                }
            }
        }

        val actionSection = Regex("즉시[\\s\\S]+").find(clean)?.value
        var action: String? = null
        actionSection?.lines()
            ?.firstOrNull { it.trim().startsWith("1.") || it.trim().startsWith("-") }
            ?.let { line ->
                action = line.replace("1.", "")
                    .replace("-", "")
                    .substringBefore(":")
                    .trim()
            }

        val sb = StringBuilder()
        if (dangers.isNotEmpty()) {
            sb.append("위험요소: ").append(dangers.joinToString(", ")).append(". ")
        }
        if (!action.isNullOrBlank()) {
            sb.append("조치: ").append(action).append(" 필요.")
        }
        return if (sb.isNotEmpty()) sb.toString() else "분석 결과 요약 불가"
    }






    // 음성 텍스트 → 제어 명령 변환
    // 한국어 키워드 매핑 (앞/뒤/좌/우/정지/발사/캡처)
    // "n초" 패턴 감지해서 duration 추출 (없으면 기본값 사용)
    private fun parseVoiceCommand(text: String): Triple<String, String, Float> {
        // 한국어 키워드 → 제어 명령 매핑
        val KOR_DIR = mapOf(
            "전진" to "Forward", "앞" to "Forward", "앞으로" to "Forward", "직진" to "Forward",
            "후진" to "Back", "뒤" to "Back", "뒤로" to "Back",
            "좌" to "Forward-Left", "좌회전" to "Forward-Left", "왼쪽" to "Forward-Left",
            "우" to "Forward-Right", "우회전" to "Forward-Right", "오른쪽" to "Forward-Right",
            "정지" to "Stop", "멈춰" to "Stop", "멈춤" to "Stop", "스톱" to "Stop",
            "전쟁" to "Forward"
        )

        var command: String? = null
        var korCmd: String? = null

        for ((k, v) in KOR_DIR) {
            if (text.contains(k)) {
                command = v
                korCmd = when (v) {
                    "Forward" -> "앞으로"
                    "Back" -> "뒤로"
                    "Forward-Left" -> "좌회전"
                    "Forward-Right" -> "우회전"
                    "Stop" -> "정지"
                    else -> k
                }
                break
            }
        }

        if (command == null) {
            if (text.contains("물") || text.contains("분사")) command = "Launch"
            if (text.contains("캡처") || text.contains("사진") || text.contains("찍어")) command = "Capture"
        }

        if (command == null) return Triple("Unknown", text, 0f)
        if (command == "Stop") return Triple("Stop", "정지", 0f)

        // "5초" 같이 숫자+초 패턴 감지
        val matcher = Pattern.compile("(\\d+)\\s*초").matcher(text)
        val duration = when {
            matcher.find() -> matcher.group(1)?.toFloatOrNull() ?: 5.0f
            command == "Launch" -> 5.0f   // ✅ 발사는 기본 5초
            (command == "Forward-Left" || command == "Forward-Right") -> 3.0f
            else -> 1.0f
        }



        return Triple(command!!, korCmd ?: command!!, duration)
    }


    // STT 결과를 실제 동작으로 분기
    // Stop → 즉시 정지
    // Launch → n초 동안 물 분사
    // Capture → 사진 촬영 요청
    // 방향(Forward/Back/Left/Right) → runVoiceMacro()로 일정 시간 이동
    // 그 외 → 일반 대화로 서버 전송
    private fun parseAndRouteStt(text: String) {
        voiceMacroJob?.cancel()
        val (command, korCmd, duration) = parseVoiceCommand(text)

        when (command) {
            "Stop" -> {
                RpiWebSocketManager.sendText(JsonFactory.createConMessage("Stop"))
                RpiWebSocketManager.sendText(JsonFactory.createTtsRequestMessage("정지합니다"))
                finishSttSession()
            }

            "Launch" -> {
                // 음성에서 "n초 발사" 감지 → duration 활용
                val durMs = (duration * 1000).toLong().coerceAtLeast(5000L)  // 최소 0.5초
                RpiWebSocketManager.sendText(JsonFactory.createJetMessage("Launch"))

                Handler(Looper.getMainLooper()).postDelayed({
                    RpiWebSocketManager.sendText(JsonFactory.createJetMessage("Stop"))
                    RpiWebSocketManager.sendText(JsonFactory.createTtsRequestMessage("분사를 완료했습니다"))
                }, durMs)

                RpiWebSocketManager.sendText(JsonFactory.createTtsRequestMessage("물을 ${duration.toInt()}초 동안 분사합니다"))
                finishSttSession()
            }

            "Capture" -> {
                val intent = Intent(ControllerFragment.ACTION_TRIGGER_CAPTURE)
                sendBroadcast(intent)
                RpiWebSocketManager.sendText(JsonFactory.createTtsRequestMessage("사진을 촬영합니다"))
                finishSttSession()
            }

            // 방향 이동 명령 처리
            "Forward", "Back", "Forward-Left", "Forward-Right" -> {
                RpiWebSocketManager.sendText(
                    JsonFactory.createTtsRequestMessage("$korCmd ${duration.toInt()}초 이동합니다")
                )
                runVoiceMacro(command, duration) // → 여기서 끝나면 Stop + 완료 TTS + finishSttSession()
            }

            else -> {
                // 일반 대화
                val sttJson = JsonFactory.createSttMessage(text)
                sendWhenReady(sttJson)
                sendStatus("⬆서버로 전송 (대화): $text")
                finishSttSession()
            }
        }
    }


    //시간 제어 매크로 실행
    private fun runVoiceMacro(command: String, duration: Float) {
        voiceMacroJob = serviceScope.launch {
            setControlLock(true)
            val startTime = System.currentTimeMillis()
            try {
                while (isActive && (System.currentTimeMillis() - startTime) < (duration * 1000)) {
                    RpiWebSocketManager.sendText(JsonFactory.createConMessage(command))
                    delay(200)
                }
            } finally {
                RpiWebSocketManager.sendText(JsonFactory.createConMessage("Stop"))
                setControlLock(false)
                RpiWebSocketManager.sendText(JsonFactory.createTtsRequestMessage("명령을 완료했습니다"))
                finishSttSession() //이동이 끝난 뒤에만 호출
            }
        }
    }

    private fun sendControlCommandToFragment(commandJson: String) {
        val intent = Intent(ACTION_SEND_CONTROL_COMMAND).apply {
            putExtra(EXTRA_CONTROL_COMMAND_JSON, commandJson)
        }
        sendBroadcast(intent)
    }

    //조이스틱 제어 잠금/해제 상태를 Broadcast로 ControllerFragment에 전달
    private fun setControlLock(isLocked: Boolean) {
        val intent = Intent(ACTION_SET_CONTROL_LOCK).apply {
            putExtra(EXTRA_IS_LOCKED, isLocked)
        }
        sendBroadcast(intent)
    }


    private fun startSTT() {
        if (!isController()) {
            sendStatus("RC 모드에서는 STT가 비활성입니다.")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            sendStatus("마이크 권한이 없습니다.")
            return
        }
        speechRecognizer?.startListening(recognizerIntent)
        sendStatus("🎙 음성 인식 시작")
    }

    private fun stopSTT() {
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {}
        sendStatus("⛔ 음성 인식 중지")
        sendBroadcast(Intent("com.example.remote.STT_ENDED"))

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
        // Broadcast만 유지
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

    private fun sendWhenReady(json: String, retries: Int = 6) {
        serviceScope.launch {
            repeat(retries) {
                if (wsManager.isConnected()) {
                    wsManager.sendText(json)
                    return@launch
                }
                delay(500)
            }
            sendStatus("⚠️ 서버에 전송 실패(연결 지연)")
        }
    }

    private fun log(msg: String) = Log.d("PorcupineService", msg)
}
