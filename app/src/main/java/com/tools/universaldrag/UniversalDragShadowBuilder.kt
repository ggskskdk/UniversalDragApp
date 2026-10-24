package com.tools.universaldrag

import android.content.Context
import android.graphics.*
import android.text.TextPaint
import android.view.View

/**
 * [v4.0 핵심]
 * - 이미지/영상 드래그 시 네모 박스에 강제로 구겨 넣지 않고, 원본 가로·세로 비율(Aspect Ratio) 100% 그대로 렌더링
 * - 하단 캡슐에 원본 실제 해상도(예: 3024×4032 • 원본) 표시
 */
class UniversalDragShadowBuilder(
    context: Context,
    private val titleBadge: String,
    private val subtitleText: String,
    private val previewBitmap: Bitmap? = null
) : View.DragShadowBuilder() {

    private val density = context.resources.displayMetrics.density
    private val maxPreviewSide = 210f * density
    private val minPreviewSide = 72f * density

    private val shadowW: Int
    private val shadowH: Int

    init {
        if (previewBitmap != null && !previewBitmap.isRecycled && previewBitmap.width > 0 && previewBitmap.height > 0) {
            val bw = previewBitmap.width.toFloat()
            val bh = previewBitmap.height.toFloat()
            val scale = minOf(maxPreviewSide / bw, maxPreviewSide / bh)
            shadowW = (bw * scale).toInt().coerceAtLeast(minPreviewSide.toInt())
            shadowH = (bh * scale).toInt().coerceAtLeast(minPreviewSide.toInt())
        } else {
            shadowW = (236 * density).toInt()
            shadowH = (92 * density).toInt()
        }
    }

    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F218181B")
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#818CF8")
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
    }
    private val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC141417")
        style = Paint.Style.FILL
    }
    private val badgePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E7FF")
        textSize = 10.5f * density
        typeface = Typeface.DEFAULT_BOLD
    }
    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * density
    }

    override fun onProvideShadowMetrics(outShadowSize: Point, outShadowTouchPoint: Point) {
        outShadowSize.set(shadowW, shadowH)
        outShadowTouchPoint.set(shadowW / 2, (shadowH * 0.72f).toInt())
    }

    override fun onDrawShadow(canvas: Canvas) {
        val pad = 4f * density
        val rect = RectF(pad, pad, shadowW - pad, shadowH - pad)
        val radius = 14f * density

        if (previewBitmap != null && !previewBitmap.isRecycled) {
            // 원본 비율 그대로 둥근 모서리 클리핑 렌더링 (여백/찌그러짐 제로)
            val clipPath = Path().apply {
                addRoundRect(rect, radius, radius, Path.Direction.CW)
            }
            canvas.save()
            canvas.clipPath(clipPath)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(previewBitmap, null, rect, paint)
            canvas.restore()

            canvas.drawRoundRect(rect, radius, radius, borderPaint)

            // 하단에 원본 해상도 배지 오버레이
            val badgeH = 22f * density
            val badgeRect = RectF(
                rect.left + 8f * density,
                rect.bottom - badgeH - 6f * density,
                rect.right - 8f * density,
                rect.bottom - 6f * density
            )
            canvas.drawRoundRect(badgeRect, 8f * density, 8f * density, pillBgPaint)
            canvas.drawText(
                titleBadge,
                badgeRect.left + 8f * density,
                badgeRect.centerY() + 3.8f * density,
                badgePaint
            )
        } else {
            canvas.drawRoundRect(rect, radius, radius, cardPaint)
            canvas.drawRoundRect(rect, radius, radius, borderPaint)

            val textPad = 14f * density
            canvas.drawText(titleBadge, textPad, textPad + 10f * density, badgePaint)

            val clean = subtitleText.replace("\n", " ").trim()
            val line1 = if (clean.length > 22) clean.substring(0, 22) else clean
            val line2 = if (clean.length > 22) clean.substring(22).take(20) + "…" else ""
            canvas.drawText(line1, textPad, textPad + 34f * density, bodyPaint)
            if (line2.isNotEmpty()) {
                canvas.drawText(line2, textPad, textPad + 54f * density, bodyPaint)
            }
        }
    }
}
