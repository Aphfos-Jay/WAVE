package com.example.remote

import android.content.*
import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.TextView
import android.widget.Button
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import org.json.JSONObject
import kotlin.math.*
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
import android.os.Handler
import android.os.Looper
import androidx.camera.core.ImageCaptureException
import android.Manifest

class RemoteFragment : Fragment(), JoystickListener {

    private lateinit var joystickView: JoystickView
    private lateinit var joystickStatusTextView: TextView
    private lateinit var wsManager: WebSocketManager

    // CameraX
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var previewView: PreviewView? = null

    // 버튼들
    private lateinit var upButton: Button
    private lateinit var downButton: Button
    private lateinit var leftButton: Button
    private lateinit var rightButton: Button
    private lateinit var centerButton: Button
    private lateinit var captureButton: Button

    // 파일명 번호 카운트
    private var captureCount = 1

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_remote, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // UI 연결
        joystickView = view.findViewById(R.id.joystickView)
        joystickStatusTextView = view.findViewById(R.id.joystickStatusTextView)
        joystickView.listener = this

        // 버튼 연결
        upButton = view.findViewById(R.id.upButton)
        downButton = view.findViewById(R.id.downButton)
        leftButton = view.findViewById(R.id.leftButton)
        rightButton = view.findViewById(R.id.rightButton)
        centerButton = view.findViewById(R.id.centerButton)
        captureButton = view.findViewById(R.id.captureButton)

        // CameraX 프리뷰 View
        previewView = view.findViewById(R.id.cameraPreview)

        wsManager = WebSocketManager()

        // 권한 확인 (카메라 + 오디오)
        PermissionUtils.checkAndRequestPermissions(requireActivity()) {
            wsManager.connect()
            startPorcupineServiceIfNotRunning()
            startCamera()
        }

        // WebSocket 이벤트 처리
        wsManager.setOnEventListener { type, content ->
            activity?.runOnUiThread {
                val message = when (type) {
                    "car_status" -> "📡 현재 상태: $content"
                    "response" -> "📡 서버 응답: $content"
                    else -> null
                }
                message?.let { joystickStatusTextView.text = it }
            }
        }

        // 버튼 반복 전송
        setRepeatCommand(upButton, "hose_up")
        setRepeatCommand(downButton, "hose_down")
        setRepeatCommand(leftButton, "hose_left")
        setRepeatCommand(rightButton, "hose_right")
        setRepeatCommand(centerButton, "hose_spray")

        // 캡처 버튼 → 사진 촬영 후 전송
        captureButton.setOnClickListener { takePhotoAndSend() }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                previewView?.let { pv -> it.setSurfaceProvider(pv.surfaceProvider) }
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                )
                Log.d("CameraX", "카메라 시작 완료")
            } catch (exc: Exception) {
                Log.e("CameraX", "카메라 바인딩 실패", exc)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun takePhotoAndSend() {
        val imageCapture = imageCapture ?: return
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val bitmap = image.toBitmap()
                        image.close()

                        val outputStream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                        val bytes = outputStream.toByteArray()

                        // 5MB 제한
                        if (bytes.size > 5 * 1024 * 1024) {
                            Log.e("CameraX", "❌ 이미지 크기 초과 (5MB)")
                            return
                        }

                        val base64Image = Base64.encodeToString(bytes, Base64.NO_WRAP)

                        // 파일명: yyyyMMdd_001
                        val datePart = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
                        val fileName = "%s_%03d".format(datePart, captureCount)
                        captureCount++

                        val json = JSONObject().apply {
                            put("type", "image")
                            put("filename", "$fileName.jpg")
                            put("content", base64Image)
                        }

                        wsManager.sendText(json.toString())
                        Log.d("CameraX", "📷 캡처 전송 완료: $fileName.jpg")

                    } catch (e: Exception) {
                        Log.e("CameraX", "이미지 처리 오류", e)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("CameraX", "캡처 실패", exception)
                }
            }
        )
    }

    private fun setRepeatCommand(button: Button, command: String, intervalMs: Long = 100L) {
        val handler = Handler(Looper.getMainLooper())
        val repeatRunnable = object : Runnable {
            override fun run() {
                wsManager.sendCommand(command)
                handler.postDelayed(this, intervalMs)
            }
        }

        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    wsManager.sendCommand(command)
                    handler.postDelayed(repeatRunnable, intervalMs)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(repeatRunnable)
                    true
                }
                else -> false
            }
        }
    }

    override fun onJoystickMoved(xPos: Float, yPos: Float) {
        val statusText = "Joystick Value: (%.2f, %.2f)".format(xPos, yPos)
        joystickStatusTextView.text = statusText
        Log.d("Joystick", statusText)

        val distance = sqrt(xPos.pow(2) + yPos.pow(2))
        val deadzoneRadius = 0.3f
        val command: String = if (distance < deadzoneRadius) {
            "stop"
        } else {
            var angle = Math.toDegrees(atan2(yPos.toDouble(), xPos.toDouble()))
            angle -= 90.0
            if (angle < 0) angle += 360.0
            when {
                angle in 337.5..360.0 || angle < 22.5 -> "up"
                angle in 22.5..67.5 -> "up_left"
                angle in 67.5..112.5 -> "right"
                angle in 112.5..157.5 -> "down_left"
                angle in 157.5..202.5 -> "down"
                angle in 202.5..247.5 -> "down_right"
                angle in 247.5..292.5 -> "left"
                angle in 292.5..337.5 -> "up_right"
                else -> "stop"
            }
        }

        val joystickData = JSONObject().apply {
            put("type", "rc")
            put("command", command)
        }
        wsManager.sendText(joystickData.toString())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        wsManager.disconnect()
        cameraExecutor.shutdown()
        if (isAdded) joystickStatusTextView.text = "📡 연결 종료"
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        PermissionUtils.handlePermissionsResult(
            requestCode,
            grantResults,
            {
                wsManager.connect()
                startPorcupineServiceIfNotRunning()
                startCamera()
            },
            {
                Log.e("Permission", "❌ 권한 거부됨")
                joystickStatusTextView.text = "📡 권한 없음, 연결 실패"
            }
        )
    }

    private fun startPorcupineServiceIfNotRunning() {
        val intent = Intent(requireContext(), PorcupineService::class.java).apply {
            action = PorcupineService.ACTION_START_FOREGROUND_SERVICE
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }
}

/** ImageProxy → Bitmap 변환 */
fun ImageProxy.toBitmap(): Bitmap {
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
    val imageBytes = out.toByteArray()
    return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
}