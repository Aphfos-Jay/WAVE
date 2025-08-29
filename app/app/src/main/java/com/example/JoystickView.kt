package com.example.remote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sqrt

interface JoystickListener {
    fun onJoystickMoved(xPos: Float, yPos: Float)
}

class JoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val joystickBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ffffff")
        style = Paint.Style.FILL
    }
    private val joystickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6200EE")
        style = Paint.Style.FILL
    }
    private val joystickBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#999999")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private var centerX = 0f
    private var centerY = 0f
    private var baseRadius = 0f
    private var joystickRadius = 0f

    private var joystickPositionX = 0f
    private var joystickPositionY = 0f

    var listener: JoystickListener? = null

    init {
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.JoystickView)
        joystickPaint.color = typedArray.getColor(R.styleable.JoystickView_joystick_color, Color.parseColor("#6200EE"))
        joystickBackgroundPaint.color = typedArray.getColor(R.styleable.JoystickView_joystick_background_color, Color.parseColor("#ffffff"))
        typedArray.recycle()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        baseRadius = minOf(w, h) / 3f
        joystickRadius = baseRadius / 3f
        joystickPositionX = centerX
        joystickPositionY = centerY
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawCircle(centerX, centerY, baseRadius, joystickBackgroundPaint)
        canvas.drawCircle(centerX, centerY, baseRadius, joystickBorderPaint)
        canvas.drawCircle(joystickPositionX, joystickPositionY, joystickRadius, joystickPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val dx = event.x - centerX
                val dy = event.y - centerY
                val distance = sqrt(dx.pow(2) + dy.pow(2))

                if (distance < baseRadius) {
                    joystickPositionX = event.x
                    joystickPositionY = event.y
                } else {
                    val ratio = baseRadius / distance
                    joystickPositionX = centerX + dx * ratio
                    joystickPositionY = centerY + dy * ratio
                }
                invalidate()

                val normalizedX = (joystickPositionX - centerX) / baseRadius
                val normalizedY = (joystickPositionY - centerY) / baseRadius
                listener?.onJoystickMoved(normalizedX, -normalizedY)
            }
            MotionEvent.ACTION_UP -> {
                joystickPositionX = centerX
                joystickPositionY = centerY
                invalidate()
                listener?.onJoystickMoved(0.0f, 0.0f)
            }
        }
        return true
    }
}