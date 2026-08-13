package com.mediacenter.app.ui.image

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs

class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatImageView(context, attrs) {

    private val imageMatrixValues = Matrix()
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val last = PointF()
    private var mode = NONE
    private var currentScale = 1f

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
        super.setImageDrawable(drawable)
        post { resetMatrix() }
    }

    override fun setImageBitmap(bm: android.graphics.Bitmap?) {
        super.setImageBitmap(bm)
        post { resetMatrix() }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        val point = PointF(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                last.set(point)
                mode = DRAG
                parent?.requestDisallowInterceptTouchEvent(currentScale > 1.01f)
            }
            MotionEvent.ACTION_MOVE -> if (mode == DRAG && currentScale > 1.01f) {
                imageMatrixValues.postTranslate(point.x - last.x, point.y - last.y)
                imageMatrix = imageMatrixValues
                last.set(point)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> mode = ZOOM
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                mode = NONE
                if (currentScale <= 1.01f) {
                    resetMatrix()
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
        }
        return true
    }

    private fun resetMatrix() {
        val d = drawable ?: return
        if (width == 0 || height == 0) return
        val scale = minOf(width.toFloat() / d.intrinsicWidth, height.toFloat() / d.intrinsicHeight)
        val dx = (width - d.intrinsicWidth * scale) / 2f
        val dy = (height - d.intrinsicHeight * scale) / 2f
        imageMatrixValues.reset()
        imageMatrixValues.postScale(scale, scale)
        imageMatrixValues.postTranslate(dx, dy)
        imageMatrix = imageMatrixValues
        currentScale = 1f
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            var factor = detector.scaleFactor
            val next = currentScale * factor
            if (next < 1f) {
                factor = 1f / currentScale
                currentScale = 1f
            } else if (next > 5f) {
                factor = 5f / currentScale
                currentScale = 5f
            } else {
                currentScale = next
            }
            if (abs(factor - 1f) > 0.001f) {
                imageMatrixValues.postScale(factor, factor, detector.focusX, detector.focusY)
                imageMatrix = imageMatrixValues
            }
            parent?.requestDisallowInterceptTouchEvent(currentScale > 1.01f)
            return true
        }
    }

    companion object {
        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
    }
}
