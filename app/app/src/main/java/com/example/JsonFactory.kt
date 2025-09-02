package com.example.remote

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


// 서버/RPi와 주고받는 메시지를 JSON 문자열로 만들어주는 헬퍼
// Type / Datetime / Value 같은 공통 키를 맞추는 용도
object JsonFactory {

    private fun nowString(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date())
    }


    // ====== RC/조종 공통 ======

    // RC 제어 명령 (방향, 전진/후진 등)
    fun createConMessage(value: String): String =
        JSONObject().apply {
            put("Type", "Con")
            put("Datetime", nowString())
            put("Value", value)
        }.toString()

    // 물 분사(Launch/Stop) 제어 명령
    fun createJetMessage(value: String): String =
        JSONObject().apply {
            put("Type", "Jet")
            put("Datetime", nowString())
            put("Value", value)
        }.toString()

    // 음성 인식 결과를 서버로 보낼 때 사용
    fun createSttMessage(text: String): String =
        JSONObject().apply {
            put("Type", "Stt")
            put("Datetime", nowString())
            put("Text", text)
        }.toString()

    // 서버→RC TTS 출력 요청
    fun createTtsRequestMessage(text: String): String =
        JSONObject().apply {
            put("Type", "Tts")
            put("Datetime", nowString())
            put("Text", text)
        }.toString()


    // ====== 캡처 관련 ======

    // (1) Controller → 서버: RC에 사진 찍어달라는 명령
    fun createCaptureRequestMessage(): String =
        JSONObject().apply {
            put("Type", "CapUploadInit")
            put("Datetime", nowString())
            put("확장자","jpg",)

        }.toString()

    // (2) RC → 서버: 업로드 주소 요청
    fun createCapUploadInit(ext: String): String =
        JSONObject().apply {
            put("Type", "CapUploadInit")
            put("Datetime", nowString())

        }.toString()

    // (3) RC → 서버: 업로드 성공 후 메타데이터 저장
    fun createCapMeta(datetime: String, lat: Double, lng: Double, ext: String, gcsUri: String): String =
        JSONObject().apply {
            put("Type", "Cap")
            put("Datetime", datetime)
            put("Lang", lat)
            put("Long", lng)
            put("확장자","jpg",)
            put("GcsUri", gcsUri)
        }.toString()

    fun createCapGet(id: String, ttl: Int = 600): String =
        JSONObject().apply {
            put("Type", "CapGet")
            put("Id", id)
            put("TtlSec", ttl)
        }.toString()

    fun createFindCaps(from: String, to: String, limit: Int = 100, ttl: Int = 600): String =
        JSONObject().apply {
            put("Type", "FindCaps")
            put("From", from)
            put("To", to)
            put("Limit", limit)
            put("TtlSec", ttl)
        }.toString()

    // ====== 기타 ======

    fun createIpMessage(ip: String): String =
        JSONObject().apply {
            put("Type", "Ip")
            put("Ip", ip)
        }.toString()

    fun createIpRequestMessage(): String =
        JSONObject().apply {
            put("Type", "IpRequest")
        }.toString()
}
