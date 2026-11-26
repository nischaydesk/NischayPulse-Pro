package com.prince.aiassistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

class ProAiOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class State { IDLE, LISTENING, THINKING }

    private var state = State.IDLE
    private var rotationAngle = 0f
    private var pulsePhase = 0f
    private var voiceAmplitude = 0f

    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val shaderMatrix = Matrix()

    private val loopAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 2800L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            rotationAngle = it.animatedValue as Float
            pulsePhase += 0.08f
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!loopAnimator.isStarted) loopAnimator.start()
    }

    override fun onDetachedFromWindow() {
        loopAnimator.cancel()
        super.onDetachedFromWindow()
    }

    fun setOrbState(newState: State) {
        if (state != newState) {
            state = newState
            if (newState != State.LISTENING) voiceAmplitude = 0f
            invalidate()
        }
    }

    fun setVoiceLevel(rmsdB: Float) {
        voiceAmplitude = (rmsdB.coerceIn(0f, 12f) / 12f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val maxR = (minOf(width, height) / 2f) * 0.88f
        if (maxR <= 10f) return

        val breathe = 1f + 0.05f * sin(pulsePhase) + (voiceAmplitude * 0.12f)
        val coreR = maxR * 0.68f * breathe

        if (state == State.LISTENING || state == State.THINKING) {
            val waveAlpha = ((0.45f - (sin(pulsePhase) * 0.2f)) * 255).toInt().coerceIn(30, 140)
            wavePaint.color = if (state == State.LISTENING) {
                Color.argb(waveAlpha, 236, 72, 153)
            } else {
                Color.argb(waveAlpha, 0, 229, 255)
            }
            wavePaint.strokeWidth = maxR * 0.035f
            canvas.drawCircle(cx, cy, maxR * (0.86f + voiceAmplitude * 0.12f), wavePaint)
        }

        val ringColors = when (state) {
            State.LISTENING -> intArrayOf(
                Color.parseColor("#FF0080"),
                Color.parseColor("#7928CA"),
                Color.parseColor("#00DFD8"),
                Color.parseColor("#FF0080")
            )
            State.THINKING -> intArrayOf(
                Color.parseColor("#00F5D4"),
                Color.parseColor("#00BBF9"),
                Color.parseColor("#F15BB5"),
                Color.parseColor("#FEE440"),
                Color.parseColor("#00F5D4")
            )
            State.IDLE -> intArrayOf(
                Color.parseColor("#0EA5E9"),
                Color.parseColor("#6366F1"),
                Color.parseColor("#EC4899"),
                Color.parseColor("#0EA5E9")
            )
        }

        val sweep = SweepGradient(cx, cy, ringColors, null)
        shaderMatrix.setRotate(rotationAngle, cx, cy)
        sweep.setLocalMatrix(shaderMatrix)
        outerRingPaint.shader = sweep
        outerRingPaint.strokeWidth = maxR * 0.09f
        canvas.drawCircle(cx, cy, maxR * 0.78f, outerRingPaint)

        val coreColors = when (state) {
            State.LISTENING -> intArrayOf(Color.parseColor("#3B0764"), Color.parseColor("#090D16"))
            State.THINKING -> intArrayOf(Color.parseColor("#083344"), Color.parseColor("#090D16"))
            State.IDLE -> intArrayOf(Color.parseColor("#1E1B4B"), Color.parseColor("#090D16"))
        }
        corePaint.shader = RadialGradient(
            cx - coreR * 0.25f,
            cy - coreR * 0.25f,
            coreR * 1.2f,
            coreColors,
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, coreR, corePaint)

        textPaint.textSize = coreR * 0.85f
        val fontMetrics = textPaint.fontMetrics
        val textY = cy - (fontMetrics.ascent + fontMetrics.descent) / 2f
        canvas.drawText("N", cx, textY, textPaint)
    }
}
