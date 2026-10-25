package com.tools.universaldrag

import android.content.Context
import android.graphics.*
import android.text.TextPaint
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * [v7.0 핵심 컴포넌트]
 * 별도의 팝업창을 띄우지 않고, 사용자가 꾹 누른 실제 화면 텍스트 노드들의 위치(Rect) 위에
 * 반투명 파란색 하이라이트 레이어를 직접 얹어 손가락이 지나가는 구간을 실시간 선택하고
 * 손을 떼지 않고 끌면 바로 글로벌 드래그 세션으로 전환함.
 */
class InlineTextSweepOverlayView(
    context: Context,
    private val textNodes: List<TextNodeItem>,
    initialTouchX: Float,
    initialTouchY: Float,
    private val onStartGlobalDrag: (selectedText: String) -> Unit,
    private val onDismiss: () -> Unit
) : View(context) {

    data class TextNodeItem(
        val rect: Rect,
        val text: String
    )

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float) = (v * density).toInt()

    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4D6366F1") // 반투명 인디고
        style = Paint.Style.FILL
    }
    private val highlightBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#818CF8")
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EE18181B")
        style = Paint.Style.FILL
    }
    private val pillTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * density
        typeface = Typeface.DEFAULT_BOLD
    }

    private var startY = initialTouchY
    private var currentTouchX = initialTouchX
    private var currentTouchY = initialTouchY
    private var isDraggingSession = false

    private val selectedNodeIndices = sortedSetOf<Int>()

    init {
        updateSelectionRange(initialTouchY)
    }

    private fun updateSelectionRange(fingerY: Float) {
        val topBound = min(startY, fingerY) - dp(24f)
        val bottomBound = max(startY, fingerY) + dp(24f)

        selectedNodeIndices.clear()
        for (i in textNodes.indices) {
            val r = textNodes[i].rect
            if (r.bottom >= topBound && r.top <= bottomBound) {
                selectedNodeIndices.add(i)
            }
        }
        if (selectedNodeIndices.isEmpty()) {
            val closest = textNodes.indices.minByOrNull {
                abs(textNodes[it].rect.centerY() - fingerY)
            }
            if (closest != null) selectedNodeIndices.add(closest)
        }
        invalidate()
    }

    fun getSelectedText(): String {
        if (selectedNodeIndices.isEmpty()) return ""
        return selectedNodeIndices.map { textNodes[it].text }.joinToString("\n")
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        currentTouchX = event.rawX
        currentTouchY = event.rawY

        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(event.rawX - currentTouchX)
                val dy = abs(event.rawY - currentTouchY)

                // 위아래로 문지르는 중에는 영역 확장/축소
                updateSelectionRange(event.rawY)

                // 손가락이 하이라이트된 영역 밖으로 15dp 이상 쭉 끌려나가면 즉시 글로벌 드래그 발동!
                if (!isDraggingSession) {
                    val combinedRect = getSelectedBounds()
                    val isFarEnough = event.rawX < combinedRect.left - dp(20f) ||
                                      event.rawX > combinedRect.right + dp(20f) ||
                                      event.rawY < combinedRect.top - dp(35f) ||
                                      event.rawY > combinedRect.bottom + dp(35f)

                    if (isFarEnough || hypot(dx.toDouble(), dy.toDouble()) > dp(28f)) {
                        val text = getSelectedText()
                        if (text.isNotBlank()) {
                            isDraggingSession = true
                            onStartGlobalDrag(text)
                            return true
                        }
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isDraggingSession) {
                    val text = getSelectedText()
                    if (text.length >= 2) {
                        // 손을 뗐을 때 바로 드래그할 수 있도록 컴팩트 캡슐을 그 자리에 띄워줌
                        onStartGlobalDrag(text)
                    } else {
                        onDismiss()
                    }
                }
                return true
            }
        }
        return true
    }

    private fun getSelectedBounds(): Rect {
        if (selectedNodeIndices.isEmpty()) return Rect()
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        for (idx in selectedNodeIndices) {
            val r = textNodes[idx].rect
            left = min(left, r.left)
            top = min(top, r.top)
            right = max(right, r.right)
            bottom = max(bottom, r.bottom)
        }
        return Rect(left, top, right, bottom)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (selectedNodeIndices.isEmpty()) return

        // 1. 선택된 실제 화면 텍스트 라인마다 반투명 하이라이트 박스 렌더링
        for (idx in selectedNodeIndices) {
            val r = textNodes[idx].rect
            val rectF = RectF(
                (r.left - dp(4f)).toFloat(),
                (r.top - dp(2f)).toFloat(),
                (r.right + dp(4f)).toFloat(),
                (r.bottom + dp(2f)).toFloat()
            )
            canvas.drawRoundRect(rectF, dp(6f).toFloat(), dp(6f).toFloat(), highlightPaint)
            canvas.drawRoundRect(rectF, dp(6f).toFloat(), dp(6f).toFloat(), highlightBorderPaint)
        }

        // 2. 손가락 바로 위에 실시간 선택 글자수 및 안내 배지 표시
        val totalChars = getSelectedText().length
        val badge = "⠿ $totalChars 자 선택됨 (잡고 다른 앱으로 이동)"
        val textW = pillTextPaint.measureText(badge)
        val pillW = textW + dp(24f)
        val pillH = dp(32f).toFloat()

        val pillX = (currentTouchX - pillW / 2).coerceIn(dp(12f).toFloat(), (width - pillW - dp(12f)).toFloat())
        val pillY = (currentTouchY - pillH - dp(36f)).coerceIn(dp(48f).toFloat(), (height - pillH - dp(48f)).toFloat())

        val pillRect = RectF(pillX, pillY, pillX + pillW, pillY + pillH)
        canvas.drawRoundRect(pillRect, dp(16f).toFloat(), dp(16f).toFloat(), pillBgPaint)
        canvas.drawRoundRect(pillRect, dp(16f).toFloat(), dp(16f).toFloat(), highlightBorderPaint)

        canvas.drawText(
            badge,
            pillX + dp(12f),
            pillY + dp(20f),
            pillTextPaint
        )
    }
}
