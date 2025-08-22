package com.example.remote

import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.util.Base64
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

class RcFragment : Fragment() {

    // ==== STREAM TUNING =======================================================
    private var STREAM_INTERVAL_MS   = 33L
    private val STREAM_TARGET_RES    = Size(1920, 1080)
    private var STREAM_SCALE_FACTOR  = 0.5f
    private var STREAM_JPEG_QUALITY  = 100
    private var STREAM_MAX_BYTES     = 400_000
    // ==========================================================================

    // ▼▼▼ 1. WebSocket 변수 선언 정리 ▼▼▼
    private val ws = WebSocketManager.getInstance()
    private lateinit var previewView: PreviewView
    private var imageCapture: ImageCapture? = null
    private lateinit var captureBtn: Button
    private lateinit var sprayBtn: Button
    private var seq = 1
    private var lastSentAt = 0L

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var currentLat: Double = 37.4563 // 기본값 (인천)
    private var currentLng: Double = 126.7052 // 기본값 (인천)

    // ▼▼▼ 2. 프래그먼트 전용 리스너를 멤버 변수로 분리 ▼▼▼
    private val fragmentWsListener: (type: String, content: String) -> Unit = { type, content ->
        Log.d("RcFragment", "event: $type / $content")
        when (type) {
            "capture_request", "CaptureRequest" -> { // 이전/이후 형식 모두 수신
                takePhotoAndSend()
            }
            "water" -> if (content.equals("fire", ignoreCase = true)) {
                ws.sendText(JsonFactory.createJetMessage("Launch"))
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, s: Bundle?
    ): View = inflater.inflate(R.layout.fragment_rc, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        previewView = view.findViewById(R.id.rc_cameraPreview)
        captureBtn  = view.findViewById(R.id.rc_captureButton)
        sprayBtn    = view.findViewById(R.id.rc_sprayButton)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())

        // ▼▼▼ 3. 리스너 등록 및 연결/권한 처리 로직 수정 ▼▼▼
        ws.addEventListener(fragmentWsListener)

        PermissionUtils.checkAndRequestPermissions(
            activity = requireActivity(),
            onGranted = {
                // ws.connect() // 삭제! (MainActivity에서 관리)
                startCamera()
                updateLastLocation()
            },
            onDenied = {
                Log.e("Permission", "❌ 권한 거부됨")
            }
        )
        // ▲▲▲ 로직 수정 ▲▲▲

        captureBtn.setOnClickListener { takePhotoAndSend() }
        setRepeatCommand(sprayBtn, "hose_spray", 150L)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // ▼▼▼ 4. 리스너 해제 및 연결 종료 로직 수정 ▼▼▼
        ws.removeEventListener(fragmentWsListener)
        // ws.disconnect() // 삭제! (MainActivity에서 관리)
        // ▲▲▲ 로직 수정 ▲▲▲
    }

    // ... (updateLastLocation, setRepeatCommand, startCamera, takePhotoAndSend, toBitmapNV21, rotate 함수는 그대로 유지) ...

    private fun updateLastLocation() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    currentLat = location.latitude
                    currentLng = location.longitude
                    Log.d("Location", "📍 위치 정보 갱신: Lat=$currentLat, Lng=$currentLng")
                } else {
                    Log.w("Location", "⚠️ 마지막 위치 정보를 가져올 수 없습니다 (null).")
                }
            }.addOnFailureListener {
                Log.e("Location", "❌ 위치 정보 가져오기 실패", it)
            }
        } else {
            Log.w("Location", "📍 위치 권한이 없어 위치를 갱신할 수 없습니다.")
        }
    }

    private fun setRepeatCommand(button: Button, command: String, intervalMs: Long) {
        val handler = Handler(Looper.getMainLooper())
        val repeatRunnable = object : Runnable {
            override fun run() {
                ws.sendText(JsonFactory.createJetMessage(command))
                handler.postDelayed(this, intervalMs)
            }
        }
        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    ws.sendText(JsonFactory.createJetMessage(command))
                    handler.postDelayed(repeatRunnable, intervalMs)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(repeatRunnable)
                    ws.sendText(JsonFactory.createJetMessage("Stop"))
                    true
                }
                else -> false
            }
        }
    }

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
                .build().also { ia ->
                    ia.setAnalyzer(ContextCompat.getMainExecutor(requireContext())) { image ->
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
                            if (bytes.size <= STREAM_MAX_BYTES) {
                                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                                val json = JSONObject().apply {
                                    put("type", "image_frame")
                                    put("content", b64)
                                }
                                ws.sendText(json.toString())
                                lastSentAt = now
                            } else {
                                Log.d("RcFragment", "frame skipped (size=${bytes.size})")
                            }
                        } catch (e: Exception) {
                            Log.e("RcFragment", "stream fail", e)
                        } finally {
                            image.close()
                        }
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
                Log.d("RcFragment", "Camera started (with streaming)")
            } catch (e: Exception) {
                Log.e("RcFragment", "bind failed", e)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
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
                            Log.w("RcFragment", "image > 5MB, skip"); image.close(); return
                        }
                        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

                        // ▼▼▼ JsonFactory 사용 ▼▼▼
                        val jsonString = JsonFactory.createCapMessage(currentLat, currentLng, base64)
                        ws.sendText(jsonString)
                        Log.d("RcFragment", "📤 image sent: (${bytes.size / 1024f} KB)")

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

/** ImageProxy → Bitmap (JPEG 처리 포함) */
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

/** Bitmap 회전 헬퍼 */
private fun Bitmap.rotate(degrees: Int): Bitmap {
    if (degrees % 360 == 0) return this
    val m = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(this, 0, 0, width, height, m, true)
}