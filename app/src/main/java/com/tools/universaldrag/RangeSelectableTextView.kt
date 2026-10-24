package com.tools.universaldrag

import android.content.Context
import android.graphics.*
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.MotionEvent
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class RangeSelectableTextView(
    context: Context,
    private val rawText: String,
    private val onSelectionChanged: (String) -> Unit,
    private val onStartGlobalDrag: (String) -> Unit
) : TextView(context) {

    private var selStart = 0
    private var selEnd = rawText.length
    private var anchorOffset = -1
    private var isDraggingSelection = false
    private var isHoldingHighlighted = false
    private var downX = 0f
    private var downY = 0f

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#818CF8")
        style = Paint.Style.FILL
    }

    init {
        setTextColor(Color.parseColor("#E4E4E7"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setLineSpacing(dp(4f).toFloat(), 1.15f)
        setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
        updateHighlight()
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    fun getSelectedText(): String {
        val s = min(selStart, selEnd).coerceIn(0, rawText.length)
        val e = max(selStart, selEnd).coerceIn(0, rawText.length)
        return if (e > s) rawText.substring(s, e) else rawText
    }

    private fun updateHighlight() {
        val s = min(selStart, selEnd).coerceIn(0, rawText.length)
        val e = max(selStart, selEnd).coerceIn(0, rawText.length)
        val span = SpannableString(rawText)
        if (e > s) {
            span.setSpan(BackgroundColorSpan(Color.parseColor("#6366F1")), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            span.setSpan(ForegroundColorSpan(Color.WHITE), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        text = span
        onSelectionChanged(getSelectedText())
        invalidate()
    }

    private fun getCharIndexAt(x: Float, y: Float): Int {
        val layout = layout ?: return 0
        val tx = (x - totalPaddingLeft).coerceAtLeast(0f)
        val ty = (y - totalPaddingTop + scrollY).coerceAtLeast(0f)
        val line = layout.getLineForVertical(ty.toInt())
        return layout.getOffsetForHorizontal(line, tx).coerceIn(0, rawText.length)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val offset = getCharIndexAt(event.x, event.y)
        val currentMin = min(selStart, selEnd)
        val currentMax = max(selStart, selEnd)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                parent?.requestDisallowInterceptTouchEvent(true)
                val isPartial = (currentMax > currentMin) && !(currentMin == 0 && currentMax == rawText.length)
                if (isPartial && offset in currentMin..currentMax) {
                    isHoldingHighlighted = true
                    isDraggingSelection = false
                } else {
                    isHoldingHighlighted = false
                    isDraggingSelection = true
                    anchorOffset = offset
                    selStart = offset
                    selEnd = (offset + 1).coerceAtMost(rawText.length)
                    updateHighlight()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val moveDist = hypot((event.rawX - downX).toDouble(), (event.rawY - downY).toDouble())
                if (isHoldingHighlighted && moveDist > dp(10f)) {
                    isHoldingHighlighted = false
                    onStartGlobalDrag(getSelectedText())
                    return true
                }
                if (isDraggingSelection && anchorOffset >= 0) {
                    selStart = min(anchorOffset, offset)
                    selEnd = max(anchorOffset, offset)
                    if (selStart == selEnd && selEnd < rawText.length) selEnd += 1
                    updateHighlight()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isHoldingHighlighted) {
                    anchorOffset = offset
                    selStart = offset
                    selEnd = (offset + 1).coerceAtMost(rawText.length)
                    updateHighlight()
                }
                isDraggingSelection = false
                isHoldingHighlighted = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val l = layout ?: return
        val s = min(selStart, selEnd).coerceIn(0, rawText.length)
        val e = max(selStart, selEnd).coerceIn(0, rawText.length)
        if (e <= s) return

        val startLine = l.getLineForOffset(s)
        val startX = l.getPrimaryHorizontal(s) + totalPaddingLeft
        val startBottom = l.getLineBottom(startLine) + totalPaddingTop - scrollY

        val endLine = l.getLineForOffset(e)
        val endX = l.getPrimaryHorizontal(e) + totalPaddingLeft
        val endBottom = l.getLineBottom(endLine) + totalPaddingTop - scrollY

        val r = dp(5.5f).toFloat()
        canvas.drawCircle(startX, startBottom + r, r, handlePaint)
        canvas.drawCircle(endX, endBottom + r, r, handlePaint)
    }
}
