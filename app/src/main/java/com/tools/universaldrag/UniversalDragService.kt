package com.tools.universaldrag

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

class UniversalDragService : AccessibilityService() {

    companion object {
        var instance: UniversalDragService? = null
    }

    private lateinit var windowManager: WindowManager
    private lateinit var clipboardManager: ClipboardManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var sniperView: View? = null
    private var dragCapsuleView: View? = null
    private var dragTrackerView: View? = null
    private var trackerParams: WindowManager.LayoutParams? = null

    private var lastGrabbedText: String = ""
    private var internalClipChange = false

    // 글로벌 드래그 중 실시간 좌표 추적 변수
    private var isGlobalDragging = false
    private var lastDropX: Float = 0f
    private var lastDropY: Float = 0f
    private var forceDropHandledByTracker = false

    private val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt() }

    private val hideCapsuleRunnable = Runnable { removeDragCapsule() }

    /**
     * 120ms 주기로 12ms 동안만 투명 트래커의 터치를 열어 현재 손가락의 드래그 좌표(X, Y)를 샘플링
     * (두 번째 손의 멀티터치와 타겟 앱의 순정 드롭을 방해하지 않음)
     */
    private val sampleDragLocationRunnable = object : Runnable {
        override fun run() {
            if (!isGlobalDragging) return
            setTrackerTouchable(true)
            mainHandler.postDelayed({
                if (isGlobalDragging) setTrackerTouchable(false)
            }, 12)
            mainHandler.postDelayed(this, 120)
        }
    }

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (internalClipChange) {
            internalClipChange = false
            return@OnPrimaryClipChangedListener
        }
        val clip = clipboardManager.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).coerceToText(this)?.toString()?.trim()
            if (!text.isNullOrEmpty()) {
                showDragCapsule(text)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(clipListener)
        setupDragLocationTracker()
        setupSniperHandle()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            val source = event.source ?: return
            val fullText = source.text?.toString() ?: return
            val start = event.fromIndex
            val end = event.toIndex
            if (start >= 0 && end > start && end <= fullText.length) {
                val selected = fullText.substring(start, end).trim()
                if (selected.isNotEmpty()) {
                    showDragCapsule(selected)
                }
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        mainHandler.removeCallbacksAndMessages(null)
        clipboardManager.removePrimaryClipChangedListener(clipListener)
        removeDragCapsule()
        sniperView?.let { runCatching { windowManager.removeView(it) } }
        dragTrackerView?.let { runCatching { windowManager.removeView(it) } }
        super.onDestroy()
    }

    /**
     * 전체 화면 투명 드래그 좌표 샘플러 세팅
     */
    private fun setupDragLocationTracker() {
        val tracker = View(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        tracker.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DRAG_LOCATION, DragEvent.ACTION_DRAG_ENTERED -> {
                    if (event.x > 0f || event.y > 0f) {
                        lastDropX = event.x
                        lastDropY = event.y
                    }
                    // 좌표 획득 즉시 터치 투과 상태로 복귀
                    setTrackerTouchable(false)
                    true
                }
                DragEvent.ACTION_DROP -> {
                    // 샘플링 찰나에 손을 뗐다면 즉시 좌표를 기록하고 강제 드롭 엔진 실행
                    if (event.x > 0f || event.y > 0f) {
                        lastDropX = event.x
                        lastDropY = event.y
                    }
                    forceDropHandledByTracker = true
                    setTrackerTouchable(false)
                    executeForceDrop(lastGrabbedText, lastDropX.toInt(), lastDropY.toInt())
                    true
                }
                else -> false
            }
        }

        dragTrackerView = tracker
        trackerParams = params
        windowManager.addView(tracker, params)
    }

    private fun setTrackerTouchable(touchable: Boolean) {
        val view = dragTrackerView ?: return
        val lp = trackerParams ?: return
        val newFlags = if (touchable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (lp.flags != newFlags) {
            lp.flags = newFlags
            runCatching { windowManager.updateViewLayout(view, lp) }
        }
    }

    /**
     * 우측 플로팅 스나이퍼(🎯) 핸들: 텍스트 위로 끌어다 놓으면 그 위치의 텍스트를 캡슐로 추출
     */
    private fun setupSniperHandle() {
        val badge = TextView(this).apply {
            text = "🎯"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            alpha = 0.7f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC1E1E24"))
                setStroke(dp(1.5f), Color.parseColor("#55FFFFFF"))
            }
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
        }

        val params = WindowManager.LayoutParams(
            dp(44f), dp(44f),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - dp(56f)
            y = resources.displayMetrics.heightPixels / 3
        }

        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        badge.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = ev.rawX
                    touchY = ev.rawY
                    moved = false
                    badge.alpha = 1.0f
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - touchX).toInt()
                    val dy = (ev.rawY - touchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager.updateViewLayout(badge, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val targetX = ev.rawX.toInt()
                    val targetY = ev.rawY.toInt()
                    params.x = resources.displayMetrics.widthPixels - dp(56f)
                    params.y = initialY
                    windowManager.updateViewLayout(badge, params)
                    badge.alpha = 0.7f
                    if (moved) {
                        val text = findTextAtScreen(targetX, targetY)
                        if (text != null) showDragCapsule(text) else toast("이 위치에서 텍스트를 찾지 못했어요")
                    } else if (lastGrabbedText.isNotEmpty()) {
                        showDragCapsule(lastGrabbedText)
                    }
                    true
                }
                else -> false
            }
        }

        sniperView = badge
        windowManager.addView(badge, params)
    }

    /**
     * 화면 좌표 아래에서 가장 깊은(구체적인) 텍스트 노드를 찾음. 오버레이 창은 제외.
     */
    private fun findTextAtScreen(x: Int, y: Int): String? {
        for (w in windows) {
            if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
            findTextAt(w.root, x, y)?.let { return it }
        }
        return null
    }

    private fun findTextAt(node: AccessibilityNodeInfo?, x: Int, y: Int): String? {
        if (node == null) return null
        val r = Rect()
        node.getBoundsInScreen(r)
        if (!r.contains(x, y)) return null
        for (i in node.childCount - 1 downTo 0) {
            findTextAt(node.getChild(i), x, y)?.let { return it }
        }
        return (node.text ?: node.contentDescription)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * 하단 드래그 캡슐: 길게 눌러 글로벌 드래그 시작 (다른 손으로 앱 전환 후 드롭)
     */
    fun showDragCapsule(text: String) {
        if (isGlobalDragging) return
        lastGrabbedText = text
        removeDragCapsule()

        val capsule = TextView(this).apply {
            this.text = "✊ $text"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 2
            maxWidth = (resources.displayMetrics.widthPixels * 0.8f).toInt()
            ellipsize = TextUtils.TruncateAt.END
            background = GradientDrawable().apply {
                cornerRadius = dp(24f).toFloat()
                setColor(Color.parseColor("#E61E1E24"))
                setStroke(dp(1f), Color.parseColor("#66FFFFFF"))
            }
            setPadding(dp(18f), dp(12f), dp(18f), dp(12f))
            setOnLongClickListener { v -> startGlobalDrag(v, text); true }
            setOnClickListener { removeDragCapsule() }
            setOnDragListener { _, e ->
                when (e.action) {
                    DragEvent.ACTION_DRAG_STARTED -> true
                    // 캡슐 위에 다시 놓으면 취소로 간주
                    DragEvent.ACTION_DROP -> { forceDropHandledByTracker = true; true }
                    DragEvent.ACTION_DRAG_ENDED -> { onGlobalDragEnded(e.result); true }
                    else -> true
                }
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(120f)
        }

        dragCapsuleView = capsule
        windowManager.addView(capsule, params)
        mainHandler.postDelayed(hideCapsuleRunnable, 6000)
    }

    private fun removeDragCapsule() {
        mainHandler.removeCallbacks(hideCapsuleRunnable)
        dragCapsuleView?.let { runCatching { windowManager.removeView(it) } }
        dragCapsuleView = null
    }

    private fun startGlobalDrag(v: View, text: String) {
        mainHandler.removeCallbacks(hideCapsuleRunnable)
        isGlobalDragging = true
        forceDropHandledByTracker = false
        lastDropX = 0f
        lastDropY = 0f
        val started = v.startDragAndDrop(
            ClipData.newPlainText("UniversalDrag", text),
            View.DragShadowBuilder(v),
            null,
            View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ
        )
        if (!started) {
            isGlobalDragging = false
            toast("드래그를 시작하지 못했어요")
            return
        }
        mainHandler.post(sampleDragLocationRunnable)
    }

    private fun onGlobalDragEnded(accepted: Boolean) {
        if (!isGlobalDragging) return
        isGlobalDragging = false
        mainHandler.removeCallbacks(sampleDragLocationRunnable)
        setTrackerTouchable(false)
        // 타겟 앱이 드롭을 거부했다면(X 등) 마지막 샘플 좌표에 강제 드롭
        if (!accepted && !forceDropHandledByTracker && (lastDropX > 0f || lastDropY > 0f)) {
            executeForceDrop(lastGrabbedText, lastDropX.toInt(), lastDropY.toInt())
        }
        mainHandler.postDelayed(hideCapsuleRunnable, 1500)
    }

    /**
     * Force-Drop: 클립보드 적재 → 좌표 탭으로 입력창 포커스 → PASTE (실패 시 SET_TEXT 이어붙이기)
     */
    private fun executeForceDrop(text: String, x: Int, y: Int) {
        if (text.isEmpty()) return
        internalClipChange = true
        clipboardManager.setPrimaryClip(ClipData.newPlainText("UniversalDrag", text))
        // ponytail: 리스너가 안 불리는 기기 대비 플래그 자동 해제
        mainHandler.postDelayed({ internalClipChange = false }, 500)

        tapAt(x, y) {
            val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val ok = node != null && node.isEditable && (
                node.performAction(AccessibilityNodeInfo.ACTION_PASTE) ||
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    val existing = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, existing + text)
                })
            )
            toast(if (ok) "강제 드롭 완료" else "입력창을 못 찾았어요 · 클립보드에 복사됨")
        }
    }

    private fun tapAt(x: Int, y: Int, then: () -> Unit) {
        val path = Path().apply { moveTo(x.coerceAtLeast(0).toFloat(), y.coerceAtLeast(0).toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { mainHandler.postDelayed(then, 300) }
            override fun onCancelled(gestureDescription: GestureDescription?) { mainHandler.post(then) }
        }, null)
        if (!dispatched) mainHandler.post(then)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
