package com.example.remote

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object JsonFactory {

    private fun nowString(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date())
    }


    // ====== RC/조종 공통 ======

    fun createConMessage(value: String): String =
        JSONObject().apply {
            put("Type", "Con")
            put("Datetime", nowString())
            put("Value", value)
        }.toString()

    fun createJetMessage(value: String): String =
        JSONObject().apply {
            put("Type", "Jet")
            put("Datetime", nowString())
            put("Value", value)
        }.toString()

    fun createSttMessage(text: String): String =
        JSONObject().apply {
            put("Type", "Stt")
            put("Datetime", nowString())
            put("Voice", text)
        }.toString()

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
