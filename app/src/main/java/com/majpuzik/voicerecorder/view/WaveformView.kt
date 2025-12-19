package com.majpuzik.voicerecorder.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val amplitudes = mutableListOf<Float>()
    private val maxAmplitudes = 100

    private val barPaint = Paint().apply {
        color = Color.parseColor("#4CAF50")
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val backgroundPaint = Paint().apply {
        color = Color.parseColor("#1E1E1E")
    }

    var isActive = false
        set(value) {
            field = value
            if (!value) {
                amplitudes.clear()
                invalidate()
            }
        }

    fun addAmplitude(amplitude: Float) {
        if (!isActive) return

        amplitudes.add(min(1f, max(0f, amplitude)))
        if (amplitudes.size > maxAmplitudes) {
            amplitudes.removeAt(0)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        if (amplitudes.isEmpty()) return

        val centerY = height / 2f
        val barWidth = width.toFloat() / maxAmplitudes
        val maxBarHeight = height * 0.8f

        amplitudes.forEachIndexed { index, amplitude ->
            val x = index * barWidth + barWidth / 2
            val barHeight = amplitude * maxBarHeight

            // Draw bar from center
            canvas.drawLine(
                x, centerY - barHeight / 2,
                x, centerY + barHeight / 2,
                barPaint
            )
        }
    }
}
