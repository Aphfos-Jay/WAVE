package com.example.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import android.Manifest
import android.content.pm.PackageManager
import java.io.DataInputStream
import java.io.PrintWriter
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sqrt
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

class ControllerFragment : Fragment(), JoystickListener {

    companion object {
        const val ACTION_TRIGGER_CAPTURE = "com.example.remote.ACTION_TRIGGER_CAPTURE"
    }

    private val captureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_TRIGGER_CAPTURE) {
                triggerCapture()
            }
        }
    }

    private lateinit var connTxt: TextView
    private lateinit var joystick: JoystickView
    private lateinit var captureBtn: Button
    private lateinit var sprayBtn: Button
    private lateinit var remoteFeed: ImageView
    private val ws = WebSocketManager.getInstance()
    private var socket: Socket? = null
    private var dis: DataInputStream? = null
    private var writer: PrintWriter? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private lateinit var nsdManager: NsdManager
    private val discoveryListener by lazy { createDiscoveryListener() }
    private var isResolving = false
    private var lastCaptureBytes: ByteArray? = null
    private var lastGcsUri: String? = null

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var currentLat: Double = 37.4563
    private var currentLng: Double = 126.7052

    @Volatile private var isControlLocked = false

    private val fragmentWsListener: (type: String, content: String) -> Unit = { type, content ->
        Log.d("ControllerFragment", "event: $type / $content")
        if (type == "CapUploadInitResult") {
            try {
                val json = JSONObject(content)
                val uploadUrl = json.getString("UploadUrl")
                val gcsUri = json.getString("GcsUri")
                lastGcsUri = gcsUri
                lastCaptureBytes?.let { bytes ->
                    uploadImageToUrl(uploadUrl, bytes) { success ->
                        if (success) {
                            lastCaptureBytes = null
                            Log.i("ControllerFragment", "✅ 캡처 업로드 완료 (GcsUri=$lastGcsUri)")
                        } else {
                            Log.w("ControllerFragment", "⚠️ 업로드 실패 → lastCaptureBytes 유지 (재시도 가능)")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ControllerFragment", "❌ CapUploadInitResult 처리 실패", e)
            }
        }
    }

    private val serviceCommandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                PorcupineService.ACTION_SEND_CONTROL_COMMAND -> {
                    val commandJson = intent.getStringExtra(PorcupineService.EXTRA_CONTROL_COMMAND_JSON)
                    if (commandJson != null) {
                        sendControlCommand(commandJson)
                    }
                }
                PorcupineService.ACTION_SET_CONTROL_LOCK -> {
                    isControlLocked = intent.getBooleanExtra(PorcupineService.EXTRA_IS_LOCKED, false)
                    Log.i("ControllerFragment", "🕹️ 제어 잠금 상태 변경: $isControlLocked")
                    joystick.alpha = if (isControlLocked) 0.5f else 1.0f
                }
            }
        }
    }

    // ✅ [1단계] 화면 회전 시 프래그먼트 객체를 유지하도록 설정
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        retainInstance = true
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, s: Bundle?
    ): View = inflater.inflate(R.layout.fragment_controller, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
        updateLastLocation()

        connTxt = view.findViewById(R.id.txt_connection)
        remoteFeed = view.findViewById(R.id.ctrl_remoteFeed)
        joystick = view.findViewById(R.id.ctrl_joystick)
        captureBtn = view.findViewById(R.id.btn_capture)
        sprayBtn = view.findViewById(R.id.btn_spray)

        ws.addEventListener(fragmentWsListener)
        joystick.listener = this

        captureBtn.setOnClickListener {
            triggerCapture()
        }

        setRepeatCommand(sprayBtn, "Launch", 150L)

        // ✅ [2단계] 최초 실행 시에만 서비스 탐색을 시작하도록 변경
        if (socket == null) {
            discoverRcService()
        } else {
            connTxt.text = "연결됨"
        }

        val filter = IntentFilter().apply {
            addAction(PorcupineService.ACTION_SEND_CONTROL_COMMAND)
            addAction(PorcupineService.ACTION_SET_CONTROL_LOCK)
            addAction(ACTION_TRIGGER_CAPTURE)
        }
        ContextCompat.registerReceiver(requireContext(), serviceCommandReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(requireContext(), captureReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun createDiscoveryListener(): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) { Log.i("ControllerFragment", "🔍 서비스 검색 시작: $regType") }
            override fun onServiceFound(service: NsdServiceInfo) {
                Log.i("ControllerFragment", "✅ 서비스 발견: ${service.serviceName}")
                if (service.serviceType == "_rcstream._tcp." && service.serviceName.contains("RcStreamService")) {
                    if (!isResolving) {
                        isResolving = true
                        nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                            override fun onServiceResolved(resolved: NsdServiceInfo) {
                                isResolving = false
                                Log.i("ControllerFragment", "🎯 서비스 해결됨: ${resolved.host.hostAddress}:${resolved.port}")
                                uiHandler.post { connTxt.text = "연결됨" }
                                startTcpClient(resolved.host.hostAddress!!, resolved.port)
                            }
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                                Log.e("ControllerFragment", "❌ 서비스 해결 실패: $errorCode")
                                isResolving = false
                            }
                        })
                    }
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                Log.w("ControllerFragment", "⚠️ 서비스 사라짐: ${service.serviceName}")
                uiHandler.post { connTxt.text = "연결 끊김" }
            }
            override fun onDiscoveryStopped(serviceType: String) { Log.i("ControllerFragment", "🛑 서비스 검색 중지: $serviceType") }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { Log.e("ControllerFragment", "❌ 검색 시작 실패: $errorCode") }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { Log.e("ControllerFragment", "❌ 검색 중지 실패: $errorCode") }
        }
    }

    private fun discoverRcService() {
        nsdManager = requireContext().getSystemService(Context.NSD_SERVICE) as NsdManager
        Log.i("ControllerFragment", "📡 RC 서비스 검색 시작")
        nsdManager.discoverServices("_rcstream._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    private fun startTcpClient(host: String, port: Int) {
        try { writer?.close(); dis?.close(); socket?.close() } catch (_: Exception) {}

        thread {
            try {
                socket = Socket(host, port)
                dis = DataInputStream(socket!!.getInputStream())
                writer = PrintWriter(socket!!.getOutputStream(), true)
                Log.i("ControllerFragment", "✅ TCP 연결 성공 ($host:$port), 송/수신 준비 완료")

                while (socket?.isConnected == true) {
                    val len = dis!!.readInt()
                    if (len == -1) {
                        val imgSize = dis!!.readInt()
                        val buf = ByteArray(imgSize)
                        dis!!.readFully(buf)
                        Log.i("ControllerFragment", "📸 RC 캡처 이미지 수신 (size=${imgSize / 1024f} KB)")
                        uploadCaptureToCloud(buf)
                    } else {
                        val buf = ByteArray(len)
                        dis!!.readFully(buf)
                        val bmp = BitmapFactory.decodeByteArray(buf, 0, len)
                        if (bmp != null) {
                            uiHandler.post { remoteFeed.setImageBitmap(bmp) }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ControllerFragment", "❌ TCP 오류 발생", e)
            } finally {
                Log.w("ControllerFragment", "TCP 연결 종료됨.")
            }
        }
    }

    private fun sendControlCommand(commandJson: String) {
        networkExecutor.execute {
            if (writer == null) {
                Log.w("ControllerFragment", "TCP writer가 초기화되지 않아 명령을 보낼 수 없습니다.")
                return@execute
            }
            try {
                writer?.println(commandJson)
                Log.d("ControllerFragment", "⬆️ TCP 전송: $commandJson")
            } catch (e: Exception) {
                Log.e("ControllerFragment", "❌ TCP 전송 오류", e)
            }
        }
    }

    private fun uploadCaptureToCloud(bytes: ByteArray) {
        lastCaptureBytes = bytes
        val initMsg = JsonFactory.createCaptureRequestMessage()
        ws.sendText(initMsg)
    }

    private fun uploadImageToUrl(url: String, bytes: ByteArray, callback: (Boolean) -> Unit) {
        thread {
            try {
                val client = OkHttpClient()
                val body = bytes.toRequestBody("image/jpeg".toMediaType())
                val request = Request.Builder().url(url).put(body).build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    Log.i("ControllerFragment", "✅ 캡처 업로드 성공")
                    val msg = JsonFactory.createCapMeta(
                        datetime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
                        lat = currentLat,
                        lng = currentLng,
                        ext = "jpg",
                        gcsUri = lastGcsUri ?: ""
                    )
                    ws.sendText(msg)
                    Log.i("ControllerFragment", "✅ Cap 메타데이터 전송 완료")
                    callback(true)
                } else {
                    Log.e("ControllerFragment", "❌ 캡처 업로드 실패: ${response.code}")
                    callback(false)
                }
            } catch (e: Exception) {
                Log.e("ControllerFragment", "❌ 업로드 오류", e)
                callback(false)
            }
        }
    }

    private fun setRepeatCommand(button: Button, command: String, intervalMs: Long) {
        val handler = Handler(Looper.getMainLooper())
        val repeatRunnable = object : Runnable {
            override fun run() {
                if (!isControlLocked) {
                    RpiWebSocketManager.sendText(JsonFactory.createJetMessage(command))
                }
                handler.postDelayed(this, intervalMs)
            }
        }
        button.setOnTouchListener { _, event ->
            if (isControlLocked) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    RpiWebSocketManager.sendText(JsonFactory.createJetMessage(command))
                    handler.postDelayed(repeatRunnable, intervalMs)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(repeatRunnable)
                    RpiWebSocketManager.sendText(JsonFactory.createJetMessage("Stop"))
                    true
                }
                else -> false
            }
        }
    }

    fun triggerCapture() {
        if (!isControlLocked) {
            sendControlCommand(JsonFactory.createCaptureRequestMessage())
            Log.d("ControllerFragment", "📤 RC에 CaptureRequest 전송 (공통 메소드)")
        }
    }

    override fun onJoystickMoved(xPos: Float, yPos: Float) {
        if (isControlLocked) return
        val distance = sqrt(xPos.pow(2) + yPos.pow(2))
        val deadzone = 0.3f
        val command = if (distance < deadzone) {
            "Stop"
        } else {
            var angle = Math.toDegrees(atan2(yPos.toDouble(), xPos.toDouble()))
            angle -= 90.0
            if (angle < 0) angle += 360.0
            when {
                angle >= 337.5 || angle < 22.5 -> "Forward"
                angle < 67.5 -> "Forward-Left"
                angle < 112.5 -> "Left"
                angle < 157.5 -> "Back-Left"
                angle < 202.5 -> "Back"
                angle < 247.5 -> "Back-Right"
                angle < 292.5 -> "Right"
                angle < 337.5 -> "Forward-Right"
                else -> "Stop"
            }
        }
        val msg = JsonFactory.createConMessage(command)
        RpiWebSocketManager.sendText(msg)
    }

    private fun updateLastLocation() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    currentLat = location.latitude
                    currentLng = location.longitude
                    Log.i("ControllerFragment", "📍 현재 위치 업데이트: ($currentLat, $currentLng)")
                }
            }
        } else {
            Log.w("ControllerFragment", "⚠️ 위치 권한 없음, 기본 좌표 사용")
        }
    }

    // ✅ [3단계] onDestroyView()에서는 뷰와 관련된 리스너만 정리
    override fun onDestroyView() {
        super.onDestroyView()
        ws.removeEventListener(fragmentWsListener)
        requireContext().unregisterReceiver(serviceCommandReceiver)
        requireContext().unregisterReceiver(captureReceiver)
        // ❌ 네트워크 연결을 끊는 코드는 여기서 모두 제거
    }

    // ✅ [3단계] onDestroy()에서 실제 네트워크 연결 자원을 정리
    override fun onDestroy() {
        super.onDestroy()
        try {
            if (::nsdManager.isInitialized) {
                nsdManager.stopServiceDiscovery(discoveryListener)
            }
            networkExecutor.shutdown()
            writer?.close()
            dis?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.w("ControllerFragment", "onDestroy 정리 중 오류 발생", e)
        }
    }
}