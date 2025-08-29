package com.example.remote

import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import androidx.fragment.app.Fragment
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sqrt
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ControllerFragment : Fragment(), JoystickListener {

    private lateinit var joystick: JoystickView
    private lateinit var captureBtn: Button
    private lateinit var sprayBtn: Button
    private lateinit var remoteFeed: ImageView
    private val ws = WebSocketManager.getInstance()

    private val fragmentWsListener: (type: String, content: String) -> Unit = { type, content ->
        if (type == "image_frame") {
            runCatching {
                val bytes = Base64.decode(content, Base64.DEFAULT)
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                remoteFeed.post { remoteFeed.setImageBitmap(bmp) }
            }.onFailure { Log.e("ControllerFragment", "frame decode fail", it) }
        } else {
            Log.d("ControllerFragment", "event: $type / $content")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_controller, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        remoteFeed = view.findViewById(R.id.ctrl_remoteFeed)
        joystick   = view.findViewById(R.id.ctrl_joystick)
        captureBtn = view.findViewById(R.id.ctrl_captureRequestButton)
        sprayBtn   = view.findViewById(R.id.ctrl_sprayButton)

        ws.addEventListener(fragmentWsListener)
        joystick.listener = this

        captureBtn.setOnClickListener {
            ws.sendText(JsonFactory.createCaptureRequestMessage())
        }

        // ▼▼▼ [수정] "Launch" 값을 전달하도록 변경 ▼▼▼
        setRepeatCommand(sprayBtn, "Launch", 150L)
    }

    // ▼▼▼ [수정] setRepeatCommand 함수 전체를 올바른 로직으로 교체 ▼▼▼
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
                    handler.removeCallbacks(repeatRunnable) // 멈춤 코드 추가!
                    ws.sendText(JsonFactory.createJetMessage("Stop"))
                    true
                }
                else -> false
            }
        }
    }
    // ▲▲▲ 수정 완료 ▲▲▲

    override fun onJoystickMoved(xPos: Float, yPos: Float) {
        val distance = sqrt(xPos.pow(2) + yPos.pow(2))
        val deadzone = 0.3f
        val command = if (distance < deadzone) {
            "Stop"
        } else {
            var angle = Math.toDegrees(atan2(yPos.toDouble(), xPos.toDouble()))
            angle -= 90.0
            if (angle < 0) angle += 360.0
            when {
                angle >= 337.5 || angle < 22.5  -> "Forward"
                angle < 67.5                    -> "Forward-Left"
                angle < 112.5                   -> "Left"
                angle < 157.5                   -> "Back-Left"
                angle < 202.5                   -> "Back"
                angle < 247.5                   -> "Back-Right"
                angle < 292.5                   -> "Right"
                angle < 337.5                   -> "Forward-Right"
                else                            -> "Stop"
            }
        }
        ws.sendText(JsonFactory.createConMessage(command))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        ws.removeEventListener(fragmentWsListener)
    }
}