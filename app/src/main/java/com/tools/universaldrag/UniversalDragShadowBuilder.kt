package com.tools.universaldrag

import android.content.Context
import android.graphics.*
import android.text.TextPaint
import android.view.View

class UniversalDragShadowBuilder(
    context: Context,
    private val titleBadge: String,
    private val subtitleText: String,
    private val previewBitmap: Bitmap? = null,
    private val itemCount: Int = 1
) : View.DragShadowBuilder() {

    private val density = context.resources.displayMetrics.density
    private val maxPreviewSide = 210f * density
    private val minPreviewSide = 80f * density
    private val stackOffset = if (itemCount > 1) 12f * density else 0f

    private val shadowW: Int
    private val shadowH: Int

    init {
        if (previewBitmap != null && !previewBitmap.isRecycled && previewBitmap.width > 0 && previewBitmap.height > 0) {
            val bw = previewBitmap.width.toFloat()
            val bh = previewBitmap.height.toFloat()
            val scale = minOf(maxPreviewSide / bw, maxPreviewSide / bh)
            shadowW = ((bw * scale).toInt().coerceAtLeast(minPreviewSide.toInt())) + stackOffset.toInt()
            shadowH = ((bh * scale).toInt().coerceAtLeast(minPreviewSide.toInt())) + stackOffset.toInt()
        } else {
            shadowW = (248 * density).toInt() + stackOffset.toInt()
            shadowH = (98 * density).toInt() + stackOffset.toInt()
        }
    }

    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F218181B")
        style = Paint.Style.FILL
    }
    private val backStackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B327272A")
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#818CF8")
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
    }
    private val backBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#666366F1")
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
    }
    private val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC141417")
        style = Paint.Style.FILL
    }
    private val countCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6366F1")
        style = Paint.Style.FILL
    }
    private val countTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * density
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val badgePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A5B4FC")
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
        val pad = 6f * density
        val radius = 14f * density

        val mainRect = RectF(
            pad,
            pad + stackOffset * 0.5f,
            shadowW - pad - stackOffset,
            shadowH - pad - stackOffset * 0.5f
        )

        if (itemCount > 1) {
            if (itemCount >= 3) {
                val backRect2 = RectF(mainRect).apply { offset(stackOffset * 0.9f, -stackOffset * 0.45f) }
                canvas.drawRoundRect(backRect2, radius, radius, backStackPaint)
                canvas.drawRoundRect(backRect2, radius, radius, backBorderPaint)
            }
            val backRect1 = RectF(mainRect).apply { offset(stackOffset * 0.5f, -stackOffset * 0.25f) }
            canvas.drawRoundRect(backRect1, radius, radius, backStackPaint)
            canvas.drawRoundRect(backRect1, radius, radius, backBorderPaint)
        }

        if (previewBitmap != null && !previewBitmap.isRecycled) {
            val clipPath = Path().apply {
                addRoundRect(mainRect, radius, radius, Path.Direction.CW)
            }
            canvas.save()
            canvas.clipPath(clipPath)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(previewBitmap, null, mainRect, paint)
            canvas.restore()

            canvas.drawRoundRect(mainRect, radius, radius, borderPaint)

            val badgeH = 22f * density
            val badgeRect = RectF(
                mainRect.left + 8f * density,
                mainRect.bottom - badgeH - 6f * density,
                mainRect.right - 8f * density,
                mainRect.bottom - 6f * density
            )
            canvas.drawRoundRect(badgeRect, 8f * density, 8f * density, pillBgPaint)
            canvas.drawText(
                titleBadge,
                badgeRect.left + 8f * density,
                badgeRect.centerY() + 3.8f * density,
                badgePaint
            )
        } else {
            canvas.drawRoundRect(mainRect, radius, radius, cardPaint)
            canvas.drawRoundRect(mainRect, radius, radius, borderPaint)

            val textPad = mainRect.left + 14f * density
            val topBase = mainRect.top + 14f * density
            canvas.drawText(titleBadge, textPad, topBase + 10f * density, badgePaint)

            val clean = subtitleText.replace("\n", " ").trim()
            val line1 = if (clean.length > 22) clean.substring(0, 22) else clean
            val line2 = if (clean.length > 22) clean.substring(22).take(20) + "…" else ""
            canvas.drawText(line1, textPad, topBase + 33f * density, bodyPaint)
            if (line2.isNotEmpty()) {
                canvas.drawText(line2, textPad, topBase + 53f * density, bodyPaint)
            }
        }

        if (itemCount > 1) {
            val badgeRadius = 13f * density
            val cx = mainRect.right - 4f * density
            val cy = mainRect.top + 6f * density
            canvas.drawCircle(cx, cy, badgeRadius, countCirclePaint)
            canvas.drawCircle(cx, cy, badgeRadius, borderPaint)
            canvas.drawText(
                itemCount.toString(),
                cx,
                cy + 4.3f * density,
                countTextPaint
            )
        }
    }
}
