package com.example.remote

import android.content.*
import android.os.*
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomsheet.BottomSheetBehavior
import java.text.SimpleDateFormat
import java.util.*

class HomeFragment : Fragment() {

    private lateinit var statusText: TextView
    private lateinit var sttButton: ImageButton
    private lateinit var ttsButton: Button
    private lateinit var ttsInputEditText: EditText

    private lateinit var clearLogButton: Button

    private lateinit var tts: TextToSpeech
    private lateinit var sharedPreferences: SharedPreferences

    // BottomSheet
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>


    companion object {
        private const val PREFS_NAME = "TTS_PREFS"
        private const val CONVERSATION_LOG_FILE = "conversation_log.json"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        statusText = view.findViewById(R.id.statusText)
        sttButton = view.findViewById(R.id.sttButton)
        ttsButton = view.findViewById(R.id.ttsButton)
        ttsInputEditText = view.findViewById(R.id.ttsInputEditText)

        clearLogButton = view.findViewById(R.id.clearLogButton)

        sharedPreferences = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // BottomSheetBehavior 설정
        val logLayout: View = view.findViewById(R.id.logLayout)
        bottomSheetBehavior = BottomSheetBehavior.from(logLayout)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        bottomSheetBehavior.peekHeight = 350 // 최소 높이 (dp 단위)

        initTTS()
        registerBroadcastReceiver()

        ttsButton.setOnClickListener {
            val text = ttsInputEditText.text.toString()
            if (text.isNotEmpty()) {
                val rate = sharedPreferences.getFloat("tts_rate", 1.0f)
                val pitch = sharedPreferences.getFloat("tts_pitch", 1.0f)
                tts.setSpeechRate(rate)
                tts.setPitch(pitch)
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_speak")
            }
        }



        sttButton.setOnClickListener {
            // pulse 애니메이션 (mic_pulse.xml)
            it.startAnimation(AnimationUtils.loadAnimation(requireContext(), R.anim.mic_pulse))

            PermissionUtils.ensureMicPermissionOrRequest(
                activity = requireActivity(),
                onGranted = {
                    // 서비스 실행 + STT 시작
                    ContextCompat.startForegroundService(
                        requireContext(),
                        Intent(requireContext(), PorcupineService::class.java).apply {
                            action = PorcupineService.ACTION_START_FOREGROUND_SERVICE
                        }
                    )
                    ContextCompat.startForegroundService(
                        requireContext(),
                        Intent(requireContext(), PorcupineService::class.java).apply {
                            action = PorcupineService.ACTION_START_STT
                        }
                    )
                },
                onDenied = {
                    appendStatus("🎤 마이크 권한이 필요합니다.")
                }
            )
        }

        clearLogButton.setOnClickListener {
            statusText.text = ""
            appendStatus("로그가 초기화되었습니다.")
        }


        /*

        WebSocketManager.getInstance().addEventListener { type, content ->
            when (type) {
                "Tts" -> {
                    appendStatus("🔊 서버 TTS: $content")
                    // ❌ tts.speak(...) 제거
                }
                "SttResult" -> {
                    appendStatus("🤖 Agent 응답: $content")
                    // ❌ tts.speak(...) 제거
                }
            }
        }

         */

    }

    private fun mapRmsToScale(rms: Float): Float {
        val minInput = 0f     // 말 안 할 때
        val maxInput = 10f    // 크게 말할 때

        val minScale = 1.0f   // 기본 크기
        val maxScale = 1.3f   // 최대 커지는 크기

        val clamped = rms.coerceIn(minInput, maxInput)
        val scale = minScale + ((clamped - minInput) / (maxInput - minInput)) * (maxScale - minScale)

        return if (scale.isNaN()) 1.0f else scale
    }


    private fun initTTS() {
        tts = TextToSpeech(requireContext()) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val rate = sharedPreferences.getFloat("tts_rate", 1.0f)
                val pitch = sharedPreferences.getFloat("tts_pitch", 1.0f)
                tts.setSpeechRate(rate)
                tts.setPitch(pitch)
                tts.language = Locale.KOREAN
                appendStatus("TTS 초기화 완료")
            } else {
                appendStatus("TTS 초기화 실패")
            }
        }
    }

    private fun appendStatus(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        statusText.append("[$time] $msg\n")
    }

    private fun registerBroadcastReceiver() {
        val filter = IntentFilter().apply {
            addAction(PorcupineService.ACTION_UPDATE_STATUS)
            addAction(PorcupineService.ACTION_UPDATE_RMS)
            addAction(PorcupineService.ACTION_LOG_CONVERSATION)
            addAction("com.example.remote.STT_ENDED")
        }

        ContextCompat.registerReceiver(
            requireContext(),
            serviceReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                PorcupineService.ACTION_UPDATE_STATUS -> {
                    val msg = intent.getStringExtra(PorcupineService.EXTRA_STATUS_MESSAGE) ?: return
                    appendStatus(msg)
                }

                PorcupineService.ACTION_UPDATE_RMS -> {
                    val rms = intent.getFloatExtra(PorcupineService.EXTRA_RMS_VALUE, -60f)


                    val scale = mapRmsToScale(rms)


                    sttButton.animate()
                        .scaleX(scale)
                        .scaleY(scale)
                        .setDuration(100) // 빠른 반응
                        .start()
                }

                PorcupineService.ACTION_LOG_CONVERSATION -> {
                    val type = intent.getStringExtra(PorcupineService.EXTRA_LOG_TYPE)
                    val content = intent.getStringExtra(PorcupineService.EXTRA_LOG_CONTENT)
                    appendStatus("[$type] $content")
                }

                "com.example.remote.STT_ENDED" -> {
                    sttButton.contentDescription = "🎙 수동 음성 명령"

                    // ✅ STT 끝나면 원래 크기로 복귀
                    sttButton.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(200)
                        .start()
                }
            }
        }
    }


    override fun onDestroyView() {
        super.onDestroyView()
        WebSocketManager.getInstance().removeEventListener { type, content -> } // TODO: 같은 리스너 참조로 제거
        requireContext().unregisterReceiver(serviceReceiver)
        tts.shutdown()
    }
}
