package com.example.remote

import android.content.SharedPreferences
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.slider.Slider

class SettingsFragment : Fragment() {

    private lateinit var prefs: SharedPreferences
    private var tts: TextToSpeech? = null

    private lateinit var voiceSpinner: AutoCompleteTextView
    private lateinit var rateSeek: Slider
    private lateinit var pitchSeek: Slider
    private lateinit var rateLabel: TextView
    private lateinit var pitchLabel: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        prefs = requireContext().getSharedPreferences("TTS_PREFS", 0)

        voiceSpinner = view.findViewById(R.id.voiceSpinner)
        rateSeek = view.findViewById(R.id.rateSeek)
        pitchSeek = view.findViewById(R.id.pitchSeek)
        rateLabel = view.findViewById(R.id.rateLabel)
        pitchLabel = view.findViewById(R.id.pitchLabel)

        tts = TextToSpeech(requireContext()) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val voices = tts?.voices?.toList()?.filter { it.locale.language == "ko" } ?: emptyList()
                val names = voices.map { it.name }

                val adapter = ArrayAdapter(requireContext(),
                    android.R.layout.simple_list_item_1, names)
                voiceSpinner.setAdapter(adapter)
                voiceSpinner.setDropDownBackgroundResource(android.R.color.white)

                // 저장된 voice 선택
                val saved = prefs.getString("tts_voice", null)
                val idx = names.indexOf(saved)
                if (idx != -1) {
                    voiceSpinner.setText(names[idx], false)
                }

                voiceSpinner.setOnItemClickListener { _, _, position, _ ->
                    prefs.edit().putString("tts_voice", names[position]).apply()
                    tts?.voice = voices[position]
                    tts?.speak("음성이 변경되었습니다.",
                        TextToSpeech.QUEUE_FLUSH, null, "preview")
                }
            }
        }

        // 속도 조절 슬라이더
        val savedRate = prefs.getFloat("tts_rate", 1.0f)
        rateSeek.value = savedRate
        rateLabel.text = "속도: $savedRate"
        rateSeek.addOnChangeListener { _, value, _ ->
            prefs.edit().putFloat("tts_rate", value).apply()
            rateLabel.text = "속도: $value"
        }

        // 피치 조절 슬라이더
        val savedPitch = prefs.getFloat("tts_pitch", 1.0f)
        pitchSeek.value = savedPitch
        pitchLabel.text = "피치: $savedPitch"
        pitchSeek.addOnChangeListener { _, value, _ ->
            prefs.edit().putFloat("tts_pitch", value).apply()
            pitchLabel.text = "피치: $value"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.shutdown()
    }
}
