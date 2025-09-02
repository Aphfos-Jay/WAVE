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
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import java.text.SimpleDateFormat
import java.util.*

class HomeFragment : Fragment() {

    private lateinit var sttButton: ImageButton
    private lateinit var ttsButton: Button
    private lateinit var ttsInputEditText: EditText
    private lateinit var clearLogButton: Button

    private lateinit var tts: TextToSpeech
    private lateinit var sharedPreferences: SharedPreferences

    // BottomSheet
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>

    // ✅ ViewModel & Adapter
    private val viewModel: ConversationViewModel by activityViewModels()
    private lateinit var logAdapter: LogAdapter

    companion object {
        private const val PREFS_NAME = "TTS_PREFS"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // UI 바인딩
        sttButton = view.findViewById(R.id.sttButton)
        ttsButton = view.findViewById(R.id.ttsButton)
        ttsInputEditText = view.findViewById(R.id.ttsInputEditText)
        clearLogButton = view.findViewById(R.id.clearLogButton)

        sharedPreferences = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // ✅ 로그 RecyclerView & ViewModel 연결
        val recyclerView: androidx.recyclerview.widget.RecyclerView =
            view.findViewById(R.id.logRecyclerView)
        logAdapter = LogAdapter()
        recyclerView.adapter = logAdapter
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        viewModel.logs.observe(viewLifecycleOwner) { logs ->
            logAdapter.submitList(logs)
            if (logs.isNotEmpty()) recyclerView.scrollToPosition(logs.size - 1)
        }

        // BottomSheetBehavior 설정
        val logLayout: View = view.findViewById(R.id.logLayout)
        bottomSheetBehavior = BottomSheetBehavior.from(logLayout).apply {
            state = BottomSheetBehavior.STATE_COLLAPSED
            peekHeight = 350
        }

        initTTS()
        registerBroadcastReceiver()

        // ✅ TTS 버튼
        ttsButton.setOnClickListener {
            val text = ttsInputEditText.text.toString()
            if (text.isNotEmpty()) {
                val rate = sharedPreferences.getFloat("tts_rate", 1.0f)
                val pitch = sharedPreferences.getFloat("tts_pitch", 1.0f)
                tts.setSpeechRate(rate)
                tts.setPitch(pitch)
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_speak")
                viewModel.appendLog("🗣️ 수동 TTS: $text")
            }
        }

        // ✅ STT 버튼
        sttButton.setOnClickListener {
            it.startAnimation(AnimationUtils.loadAnimation(requireContext(), R.anim.mic_pulse))

            PermissionUtils.ensureMicPermissionOrRequest(
                activity = requireActivity(),
                onGranted = {
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
                    viewModel.appendLog("🎤 STT 시작")
                },
                onDenied = { viewModel.appendLog("❌ 마이크 권한이 필요합니다.") }
            )
        }

        // ✅ 로그 초기화 버튼
        clearLogButton.setOnClickListener {
            viewModel.clearLogs()
            viewModel.appendLog("🗑 로그가 초기화되었습니다.")
        }

        // ✅ WebSocket 이벤트 → 로그 기록
        WebSocketManager.getInstance().addEventListener { type, content ->
            when (type) {
                "Tts" -> viewModel.appendLog("🔊 서버 TTS: $content")
                "SttResult" -> viewModel.appendLog("🤖 Agent 응답: $content")
            }
        }
    }

    private fun mapRmsToScale(rms: Float): Float {
        val minInput = 0f
        val maxInput = 10f
        val minScale = 1.0f
        val maxScale = 1.3f
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
                viewModel.appendLog("✅ TTS 초기화 완료")
            } else {
                viewModel.appendLog("❌ TTS 초기화 실패")
            }
        }
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
                    viewModel.appendLog(msg)
                }

                PorcupineService.ACTION_UPDATE_RMS -> {
                    val rms = intent.getFloatExtra(PorcupineService.EXTRA_RMS_VALUE, -60f)
                    val scale = mapRmsToScale(rms)
                    sttButton.animate()
                        .scaleX(scale)
                        .scaleY(scale)
                        .setDuration(100)
                        .start()
                }

                PorcupineService.ACTION_LOG_CONVERSATION -> {
                    val type = intent.getStringExtra(PorcupineService.EXTRA_LOG_TYPE)
                    val content = intent.getStringExtra(PorcupineService.EXTRA_LOG_CONTENT)
                    if (type != null && content != null) {
                        viewModel.appendLog("[$type] $content")
                    }
                }

                "com.example.remote.STT_ENDED" -> {
                    sttButton.contentDescription = "🎙 수동 음성 명령"
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
        requireContext().unregisterReceiver(serviceReceiver)
        tts.shutdown()
    }
}
