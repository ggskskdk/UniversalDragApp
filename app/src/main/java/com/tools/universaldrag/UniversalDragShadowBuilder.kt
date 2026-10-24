package com.tools.universaldrag

import android.content.Context
import android.graphics.*
import android.text.TextPaint
import android.view.View

/**
 * 텍스트, 이미지(썸네일 프리뷰), 영상, 일반 파일을 모두 지원하는 통합 DragShadowBuilder
 */
class UniversalDragShadowBuilder(
    context: Context,
    private val titleBadge: String,
    private val subtitleText: String,
    private val thumbnail: Bitmap? = null
) : View.DragShadowBuilder() {

    private val density = context.resources.displayMetrics.density
    private val widthPx = if (thumbnail != null) (220 * density).toInt() else (240 * density).toInt()
    private val heightPx = if (thumbnail != null) (160 * density).toInt() else (96 * density).toInt()
    private val cornerRadius = 16f * density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F218181B")
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#818CF8")
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
    }
    private val badgePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A5B4FC")
        textSize = 11f * density
        typeface = Typeface.DEFAULT_BOLD
    }
    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * density
    }

    override fun onProvideShadowMetrics(outShadowSize: Point, outShadowTouchPoint: Point) {
        outShadowSize.set(widthPx, heightPx)
        outShadowTouchPoint.set(widthPx / 2, (heightPx * 0.78f).toInt())
    }

    override fun onDrawShadow(canvas: Canvas) {
        val rect = RectF(4f, 4f, widthPx - 4f, heightPx - 4f)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)

        val pad = 12f * density
        canvas.drawText(titleBadge, pad, pad + 12f * density, badgePaint)

        if (thumbnail != null && !thumbnail.isRecycled) {
            val imgTop = pad + 20f * density
            val imgRect = RectF(pad, imgTop, widthPx - pad, heightPx - pad)
            val path = Path().apply {
                addRoundRect(imgRect, 10f * density, 10f * density, Path.Direction.CW)
            }
            canvas.save()
            canvas.clipPath(path)
            canvas.drawBitmap(thumbnail, null, imgRect, null)
            canvas.restore()
        } else {
            val clean = subtitleText.replace("\n", " ").trim()
            val line1 = if (clean.length > 22) clean.substring(0, 22) else clean
            val line2 = if (clean.length > 22) clean.substring(22).take(20) + "…" else ""
            canvas.drawText(line1, pad, pad + 36f * density, bodyPaint)
            if (line2.isNotEmpty()) {
                canvas.drawText(line2, pad, pad + 56f * density, bodyPaint)
            }
        }

        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, strokePaint)
    }
}
