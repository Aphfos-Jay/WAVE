package com.example.remote

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object JsonFactory {

    private fun getCurrentTimestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
    }

    /**
     * Type: Con
     * 조이스틱 제어 메시지 생성
     */
    fun createConMessage(value: String): String {
        return JSONObject().apply {
            put("Type", "Con")
            put("Datetime", getCurrentTimestamp())
            put("Value", value)
        }.toString()
    }

    /**
     * Type: Jet
     * 호스 분사 제어 메시지 생성
     */
    fun createJetMessage(value: String): String {
        return JSONObject().apply {
            put("Type", "Jet")
            put("Datetime", getCurrentTimestamp())
            put("Value", value)
        }.toString()
    }

    /**
     * Type: Stt
     * 음성 인식(STT) 결과 메시지 생성
     */
    fun createSttMessage(voiceText: String): String {
        return JSONObject().apply {
            put("Type", "Stt")
            put("Datetime", getCurrentTimestamp())
            put("Voice", voiceText)
        }.toString()
    }

    /**
     * Type: Cap
     * 캡처된 이미지 정보 메시지 생성
     */
    fun createCapMessage(lat: Double, lng: Double, photoBase64: String): String {
        return JSONObject().apply {
            put("Type", "Cap")
            put("Datetime", getCurrentTimestamp())
            put("Lang", lat)
            put("Long", lng)
            put("Extension", "jpg")
            put("Photo", photoBase64)
        }.toString()
    }

    /**
     * Type: CaptureRequest
     * RC카에 캡처를 요청하는 메시지 생성
     */
    fun createCaptureRequestMessage(): String {
        return JSONObject().apply {
            put("Type", "CaptureRequest")
            put("Datetime", getCurrentTimestamp())
        }.toString()
    }

    /**
     * Type: TtsRequest
     * RC카에 TTS를 요청하는 메시지 생성
     */
    fun createTtsRequestMessage(text: String): String {
        return JSONObject().apply {
            put("Type", "TtsRequest")
            put("Datetime", getCurrentTimestamp())
            put("Text", text)
        }.toString()
    }
}