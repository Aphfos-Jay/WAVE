package com.example.remote

import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.Button
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
import androidx.camera.core.ImageProxy
import android.util.Size
import android.os.Handler
import android.os.Looper
import java.net.ServerSocket
import java.net.Socket
import java.io.OutputStream
import kotlin.concurrent.thread
import java.io.DataOutputStream
import android.util.Base64
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import android.content.Intent
import android.speech.tts.TextToSpeech
import java.util.Locale

// RC 모드 화면 (로봇 쪽 앱)

class RcFragment : Fragment() {

    // ==== STREAM TUNING =======================================================
    private var STREAM_INTERVAL_MS   = 33L
    private val STREAM_TARGET_RES    = Size(1920, 1080)
    private var STREAM_SCALE_FACTOR  = 0.5f
    private var STREAM_JPEG_QUALITY  = 80 // 품질을 약간 낮춰 전송량 감소
    private var STREAM_MAX_BYTES     = 400_000
    // ==========================================================================

    // --- 통신 및 스레딩 ---
    private val ws = WebSocketManager.getInstance()
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var dataOut: DataOutputStream? = null
    private var restartScheduled = false
    private var rpiSocket: Socket? = null
    private var rpiWriter: PrintWriter? = null
    private val rpiExecutor = Executors.newSingleThreadExecutor()
    // << 1. 카메라 분석 전용 스레드 Executor 추가
    private lateinit var cameraExecutor: ExecutorService

    // --- RPi 정보 ---
    private val RPI_HOST = BuildConfig.RPI_IP
    private val RPI_PORT = BuildConfig.RPI_PORT.toInt()

    // --- NSD, 카메라, 위치 등 기타 ---
    private lateinit var nsdManager: NsdManager
    private val registrationListener by lazy { createRegistrationListener() }
    private var serviceName = "RcStreamService"
    private lateinit var previewView: PreviewView
    private var imageCapture: ImageCapture? = null
    private lateinit var captureBtn: Button
    private lateinit var sprayBtn: Button
    private var lastSentAt = 0L
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private var currentLat: Double = 37.4563
    private var currentLng: Double = 126.7052

    private var tts: TextToSpeech? = null

    private lateinit var rpiListener: (String, String) -> Unit




    private var pendingCaptureBytes: ByteArray? = null
    private var pendingCaptureDatetime: String? = null


    // presigned URL 로 캡처 이미지를 PUT 업로드
    // 성공 여부를 callback 으로 반환
    private fun uploadImageToUrl(url: String, bytes: ByteArray, callback: (Boolean) -> Unit) {
        thread {
            try {
                val client = OkHttpClient()
                val body = bytes.toRequestBody("image/jpeg".toMediaType())
                val request = Request.Builder()
                    .url(url)
                    .put(body)
                    .build()
                val response = client.newCall(request).execute()
                callback(response.isSuccessful)
            } catch (e: Exception) {
                Log.e("RcFragment", "PUT 업로드 실패", e)
                callback(false)
            }
        }
    }

    // 서버 WebSocket 이벤트 처리
    // - CapUploadInitResult : presigned URL 받아 이미지 업로드 → 메타데이터 전송
    private val fragmentWsListener: (type: String, content: String) -> Unit = { type, content ->
        Log.d("RcFragment", "WebSocket event: $type / $content")
        if (type == "CapUploadInitResult") {
            try {
                val json = JSONObject(content)
                val uploadUrl = json.getString("UploadUrl")
                val gcsUri = json.getString("GcsUri")

                // UploadUrl 로 PUT 업로드
                pendingCaptureBytes?.let { bytes ->
                    uploadImageToUrl(uploadUrl, bytes) {
                        if (it) {
                            // 업로드 성공 시 Cap 메타데이터 전송
                            val msg = JsonFactory.createCapMeta(
                                datetime = pendingCaptureDatetime
                                    ?: SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
                                lat = currentLat,
                                lng = currentLng,
                                ext = "jpg",
                                gcsUri = gcsUri
                            )
                            ws.sendText(msg)
                            Log.i("RcFragment", "Cap 메타데이터 전송 완료")
                        } else {
                            Log.e("RcFragment", "이미지 업로드 실패")
                        }
                        // cleanup
                        pendingCaptureBytes = null
                        pendingCaptureDatetime = null
                    }
                }
            } catch (e: Exception) {
                Log.e("RcFragment", "CapUploadInitResult 처리 실패", e)
            }
        }
    }


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, s: Bundle?
    ): View = inflater.inflate(R.layout.fragment_rc, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initTextToSpeech()

        previewView = view.findViewById(R.id.rc_cameraPreview)
        captureBtn  = view.findViewById(R.id.rc_captureButton)
        sprayBtn    = view.findViewById(R.id.rc_sprayButton)

        // 카메라 Executor 초기화
        cameraExecutor = Executors.newSingleThreadExecutor()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
        updateLastLocation()
        ws.addEventListener(fragmentWsListener)

        // RPi WebSocket 리스너 등록
        rpiListener = { _, content ->
            try {
                val json = JSONObject(content)
                when (json.optString("Type")) {
                    "Tts" -> {
                        val ttsText = json.optString("Text")
                        Log.i("RcFragment", "RPi TTS 수신: $ttsText")
                        Handler(Looper.getMainLooper()).post {
                            tts?.speak(ttsText, TextToSpeech.QUEUE_FLUSH, null, "rpi_tts")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("RcFragment", "❌ RPi 메시지 처리 오류", e)
            }
        }
        RpiWebSocketManager.addEventListener(rpiListener)

        PermissionUtils.checkAndRequestPermissions(
            activity = requireActivity(),
            onGranted = {
                startCamera()
                updateLastLocation()
                startTcpServer()
                connectToRpi()
                registerNsdService()
            },
            onDenied = { Log.e("Permission", "❌ 권한 거부됨") }
        )

        captureBtn.setOnClickListener {
            Log.d("RcFragment", "로컬 캡처 버튼 클릭됨 (테스트용)")
            takePhotoAndSend()
        }
    }

    // RC 모드에서만 사용되는 TTS (구글 엔진 고정)
    private fun initTextToSpeech() {
        tts = TextToSpeech(requireContext(), { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.KOREAN)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("RcFragment", "한국어 미지원")
                } else {
                    Log.i("RcFragment", "TTS 초기화 완료 (구글 엔진)")
                }
            } else {
                Log.e("RcFragment", "TTS 초기화 실패")
            }
        }, "com.google.android.tts")   // 엔진을 구글로 고정
    }


    override fun onDestroyView() {
        super.onDestroyView()
        ws.removeEventListener(fragmentWsListener)

        if (::rpiListener.isInitialized) {
            RpiWebSocketManager.removeEventListener(rpiListener)
        }

        stopTcpServer()
        disconnectFromRpi()
        cameraExecutor.shutdown()
        rpiExecutor.shutdownNow()

        try {
            if (::nsdManager.isInitialized) {
                nsdManager.unregisterService(registrationListener)
            }
        } catch (e: Exception) {
            Log.w("RcFragment", "⚠NSD 해제 중 오류: ${e.message}")
        }

        // 여기서는 shutdown() 하지 않음
        tts?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 앱 완전 종료 시에만 TTS 자원 해제
        tts?.shutdown()
        Log.i("RcFragment", "🛑 TTS 완전 해제됨")
    }

    // CameraX 초기화
    // Preview: 로컬 화면에 출력
    // ImageAnalysis: 주기적으로 프레임 추출 후 TCP 전송
    // Analyzer 는 cameraExecutor (백그라운드 스레드)에서 동작
    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(requireContext())
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(STREAM_TARGET_RES)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            // Analyzer를 백그라운드 스레드에서 실행하도록 변경
            analysis.setAnalyzer(cameraExecutor) { image ->
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastSentAt < STREAM_INTERVAL_MS) {
                        image.close()
                        return@setAnalyzer
                    }

                    val rotation = image.imageInfo.rotationDegrees
                    val bmpRaw = image.toBitmapNV21()
                    val bmp = if (rotation != 0) bmpRaw.rotate(rotation) else bmpRaw

                    val scaledW = (bmp.width * STREAM_SCALE_FACTOR).toInt().coerceAtLeast(1)
                    val scaledH = (bmp.height * STREAM_SCALE_FACTOR).toInt().coerceAtLeast(1)
                    val scaled = if (STREAM_SCALE_FACTOR < 0.999f)
                        Bitmap.createScaledBitmap(bmp, scaledW, scaledH, true)
                    else bmp

                    val baos = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, STREAM_JPEG_QUALITY, baos)
                    val bytes = baos.toByteArray()

                    // 같은 백그라운드 스레드에서 바로 TCP 전송 (별도 Executor 불필요)
                    if (bytes.size <= STREAM_MAX_BYTES && dataOut != null) {
                        try {
                            // synchronized를 사용하여 여러 스레드에서 dataOut에 동시 접근하는 것을 방지
                            synchronized(dataOut!!) {
                                dataOut?.writeInt(bytes.size)
                                dataOut?.write(bytes)
                                dataOut?.flush()
                            }
                            lastSentAt = now
                        } catch (e: Exception) {
                            Log.e("RcFragment", "frame send fail", e)
                            stopTcpServer()
                            scheduleRestart()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("RcFragment", "stream fail", e)
                } finally {
                    image.close()
                }
            }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    viewLifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture,
                    analysis
                )
                Log.d("RcFragment", "Camera started (with Background streaming)")
            } catch (e: Exception) {
                Log.e("RcFragment", "bind failed", e)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }


    private fun connectToRpi() {
        rpiExecutor.execute {
            while (isAdded) {
                try {
                    Log.i("RpiConnection", "RPi에 연결 시도 중... ($RPI_HOST:$RPI_PORT)")
                    rpiSocket = Socket(RPI_HOST, RPI_PORT)
                    rpiWriter = PrintWriter(rpiSocket!!.getOutputStream(), true)
                    Log.i("RpiConnection", "✅ RPi 연결 성공!")
                    break
                } catch (ie: InterruptedException) {
                    Log.w("RpiConnection", "⏹️ 연결 재시도 중단됨 (인터럽트)")
                    break   // 스레드 종료
                } catch (e: Exception) {
                    Log.e("RpiConnection", "❌ RPi 연결 실패: ${e.message}")
                    try {
                        Thread.sleep(2000)
                    } catch (ie: InterruptedException) {
                        Log.w("RpiConnection", "sleep 중단됨 (인터럽트)")
                        break
                    }
                }
            }
        }
    }

    private fun sendToRpi(commandJson: String) {
        if (rpiWriter == null || rpiSocket?.isConnected != true) {
            Log.w("RpiConnection", "RPi가 연결되지 않아 명령을 보낼 수 없습니다.")
            return
        }
        rpiExecutor.execute {
            try {
                rpiWriter?.println(commandJson)
                Log.d("RpiConnection", "➡️ RPi로 전송: $commandJson")
            } catch (e: Exception) {
                Log.e("RpiConnection", "❌ RPi 전송 오류", e)
                disconnectFromRpi()
                connectToRpi()
            }
        }
    }

    private fun disconnectFromRpi() {
        try {
            rpiWriter?.close()
            rpiSocket?.close()
        } catch (_: Exception) {}
        rpiWriter = null
        rpiSocket = null
        Log.i("RpiConnection", "RPi 연결 정리 완료")
    }

    private fun handleControlCommand(commandJson: String) {
        try {
            val json = JSONObject(commandJson.trim())
            Log.d("RcFragment", "⬇리모컨 명령 수신: ${json.toString(2)}")
            when (json.optString("Type")) {
                "Con", "Jet" -> {
                    sendToRpi(commandJson)
                }
                "CapUploadInit", "CaptureRequest" -> {
                    Log.i("RcFragment", "📸 캡처 요청 수신")
                    Handler(Looper.getMainLooper()).post { takePhotoAndSend() }
                }
                "Tts" -> {
                    val ttsText = json.getString("Text")
                    Log.i("RcFragment", "🔊 TTS 요청 수신: $ttsText")
                    // TTS 객체를 사용해 음성 출력
                    Handler(Looper.getMainLooper()).post {
                        tts?.speak(ttsText, TextToSpeech.QUEUE_FLUSH, null, "tts_speak")
                    }
                }
                else -> Log.w("RcFragment", "⚠️ 알 수 없는 타입: ${json.optString("Type")}")
            }
        } catch (e: Exception) {
            Log.e("RcFragment", "❌ 명령 파싱 오류: $commandJson", e)
        }
    }

    private fun startTcpServer() {
        if (serverSocket != null && !serverSocket!!.isClosed) {
            Log.w("RcFragment", "TCP 서버가 이미 실행 중입니다.")
            return
        }
        thread {
            try {
                serverSocket = ServerSocket(8888)
                Log.i("RcFragment", "TCP 서버 대기중")
                clientSocket = serverSocket!!.accept()
                // clientOut = clientSocket!!.getOutputStream() // DataOutputStream만 사용하므로 중복
                dataOut = DataOutputStream(clientSocket!!.getOutputStream())
                Log.i("RcFragment", "TCP 클라이언트 연결됨: ${clientSocket!!.inetAddress.hostAddress}")
                val reader = BufferedReader(InputStreamReader(clientSocket!!.getInputStream()))
                while (clientSocket?.isConnected == true) {
                    val commandJson = reader.readLine() ?: break
                    handleControlCommand(commandJson)
                }
            } catch (e: Exception) {
                Log.e("RcFragment", "TCP 서버 오류", e)
            } finally {
                Log.w("RcFragment", "TCP 연결 종료됨. 1초 후 재시작 예약")
                stopTcpServer()
                scheduleRestart()
            }
        }
    }

    private fun scheduleRestart() {
        if (!restartScheduled && isAdded) {
            restartScheduled = true
            Handler(Looper.getMainLooper()).postDelayed({
                restartScheduled = false
                if (isAdded) startTcpServer()
            }, 1000)
        }
    }

    private fun stopTcpServer() {
        try {
            dataOut?.close()
            // clientOut?.close()
            clientSocket?.close()
            serverSocket?.close()
        } catch (_: Exception) {}
        dataOut = null
        // clientOut = null
        clientSocket = null
        serverSocket = null
        Log.i("RcFragment", "TCP 서버 소켓 정리 완료")
    }

    // 같은 인터넷에 연결되어 있다면, 자동 탐색
    private fun registerNsdService() {
        nsdManager = requireContext().getSystemService(Context.NSD_SERVICE) as NsdManager
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = this@RcFragment.serviceName
            serviceType = "_rcstream._tcp."
            port = 8888
        }
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }


    private fun createRegistrationListener(): NsdManager.RegistrationListener {
        return object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                serviceName = NsdServiceInfo.serviceName
                Log.i("RcFragment", "NSD 서비스 등록 성공: $serviceName")
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { Log.e("RcFragment", "❌ NSD 서비스 등록 실패: $errorCode") }
            override fun onServiceUnregistered(arg0: NsdServiceInfo) { Log.i("RcFragment", "NSD 서비스 등록 해제됨: ${arg0.serviceName}") }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { Log.e("RcFragment", "❌ NSD 서비스 해제 실패: $errorCode") }
        }
    }

    private fun updateLastLocation() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    currentLat = location.latitude
                    currentLng = location.longitude
                }
            }
        }
    }

    private fun takePhotoAndSend() {
        updateLastLocation()
        val cap = imageCapture ?: return
        cap.takePicture(
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val rotation = image.imageInfo.rotationDegrees
                        val bmpRaw = image.toBitmapNV21()
                        val bmp = if (rotation != 0) bmpRaw.rotate(rotation) else bmpRaw
                        val baos = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, 90, baos)
                        val bytes = baos.toByteArray()

                        if (bytes.size > 5 * 1024 * 1024) {
                            Log.w("RcFragment", "image > 5MB, skip")
                            image.close()
                            return
                        }

                        // TCP 통신 작업을 별도 스레드로 분리
                        thread { // 새로운 백그라운드 스레드를 시작합니다.
                            if (dataOut != null) {
                                try {
                                    synchronized(dataOut!!) {
                                        dataOut?.writeInt(-1)             // 캡처 신호
                                        dataOut?.writeInt(bytes.size)     // 실제 이미지 크기
                                        dataOut?.write(bytes)             // 실제 데이터
                                        dataOut?.flush()
                                    }
                                    Log.i("RcFragment", "📸 캡처 이미지 전송 완료 (size=${bytes.size / 1024f} KB)")

                                } catch (e: Exception) {
                                    Log.e("RcFragment", "capture process fail", e)
                                    // 여기에 오류 처리 로직 (예: 소켓 재시작)을 추가할 수 있습니다.
                                }
                            } else {
                                Log.w("RcFragment", "❌ dataOut 이 초기화되지 않음 (TCP 연결 없음)")
                            }
                        }

                    } catch (e: Exception) {
                        Log.e("RcFragment", "capture process fail", e)
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("RcFragment", "capture error", exception)
                }
            }
        )
    }

}

//ImageProxy → Bitmap (JPEG 처리 포함)
fun ImageProxy.toBitmapNV21(): Bitmap {
    if (format == ImageFormat.JPEG) {
        val buffer = planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
    val yBuffer = planes[0].buffer
    val uBuffer = planes[1].buffer
    val vBuffer = planes[2].buffer
    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()
    val nv21 = ByteArray(ySize + uSize + vSize)
    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)
    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, out)
    val bytes = out.toByteArray()
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

// Bitmap 회전 헬퍼
private fun Bitmap.rotate(degrees: Int): Bitmap {
    if (degrees % 360 == 0) return this
    val m = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(this, 0, 0, width, height, m, true)
}
