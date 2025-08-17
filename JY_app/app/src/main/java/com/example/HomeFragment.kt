package com.example.remote

import android.content.*
import android.os.*
import android.speech.tts.TextToSpeech
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.text.SimpleDateFormat
import java.util.*

class HomeFragment : Fragment() {

    private lateinit var statusText: TextView
    private lateinit var sttButton: Button
    private lateinit var ttsButton: Button
    private lateinit var ttsInputEditText: EditText
    private lateinit var rmsIndicator: ImageView
    private lateinit var clearLogButton: Button

    private lateinit var tts: TextToSpeech
    private lateinit var sharedPreferences: SharedPreferences

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
        rmsIndicator = view.findViewById(R.id.rmsIndicator)
        clearLogButton = view.findViewById(R.id.clearLogButton)

        sharedPreferences = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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
            val intent = Intent(requireContext(), PorcupineService::class.java).apply {
                action = PorcupineService.ACTION_START_STT
            }
            ContextCompat.startForegroundService(requireContext(), intent)
        }

        clearLogButton.setOnClickListener {
            statusText.text = ""
            appendStatus("로그가 초기화되었습니다.")
        }
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
                    val rms = intent.getFloatExtra(PorcupineService.EXTRA_RMS_VALUE, 0f)
                    rmsIndicator.visibility = if (rms > -60) View.VISIBLE else View.GONE
                }
                PorcupineService.ACTION_LOG_CONVERSATION -> {
                    val type = intent.getStringExtra(PorcupineService.EXTRA_LOG_TYPE)
                    val content = intent.getStringExtra(PorcupineService.EXTRA_LOG_CONTENT)
                    appendStatus("[$type] $content")
                }
                "com.example.remote.STT_ENDED" -> {
                    sttButton.text = "🎙 수동 음성 명령"
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