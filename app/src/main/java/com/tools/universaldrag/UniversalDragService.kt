package com.tools.universaldrag

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.text.TextUtils
import android.util.Size
import android.util.TypedValue
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.MimeTypeMap
import android.widget.*
import java.io.InputStream
import java.util.Calendar
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class UniversalDragService : AccessibilityService() {

    companion object {
        var instance: UniversalDragService? = null
    }

    enum class PayloadType { TEXT, IMAGE, VIDEO, FILE }

    data class DragPayload(
        val type: PayloadType,
        val text: String? = null,
        val uri: Uri? = null,
        val mimeType: String = "text/plain",
        val displayName: String = "",
        val origWidth: Int = 0,
        val origHeight: Int = 0,
        val previewBitmap: Bitmap? = null,
        val requiresToolbarCopyOnDrag: Boolean = false
    )

    data class SelectionToolbarInfo(
        val copyClickableNode: AccessibilityNodeInfo,
        val copyRawNode: AccessibilityNodeInfo,
        val copyRect: Rect,
        val toolbarRect: Rect,
        val preExtractedText: String?
    )

    private lateinit var windowManager: WindowManager
    private lateinit var clipboardManager: ClipboardManager
    private lateinit var vibrator: Vibrator
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newCachedThreadPool()

    private var activeOverlayView: View? = null
    private var activeOverlayParams: WindowManager.LayoutParams? = null
    private var isShowingToolbarCapsule = false
    private var isPreparingToolbarCopyDrag = false

    private var dragTrackerView: View? = null
    private var trackerParams: WindowManager.LayoutParams? = null

    private var currentPayload: DragPayload? = null
    private var internalClipChange = false

    private var isGlobalDragging = false
    private var lastDropX: Float = 0f
    private var lastDropY: Float = 0f
    private var forceDropHandledByTracker = false
    private var preDropFocusedText: String? = null
    private var lastEventTimestamp: Long = 0L
    private var lastToolbarScanTime: Long = 0L

    private val autoDismissRunnable = Runnable { removeActiveOverlay() }

    private val launcherPackages = mutableSetOf(
        "com.sec.android.app.launcher",
        "com.google.android.apps.nexuslauncher",
        "com.teslacoilsw.launcher",
        "com.microsoft.launcher",
        "com.android.systemui"
    )

    private val copyLabels = setOf("복사", "Copy", "텍스트 복사", "Copy text")

    private val siblingToolbarKeywords = listOf(
        "모두 선택", "Select all",
        "잘라내기", "Cut",
        "붙여넣기", "Paste",
        "공유", "Share",
        "번역", "Translate",
        "소리 내 읽기", "Read aloud",
        "삼성 패스에 저장", "Gemini에게 물어보기",
        "드래그 앤 드롭", "웹 검색", "Web search",
        "사전", "Dictionary"
    )

    private val sampleDragLocationRunnable = object : Runnable {
        override fun run() {
            if (!isGlobalDragging) return
            setTrackerTouchable(true)
            mainHandler.postDelayed({
                if (isGlobalDragging) setTrackerTouchable(false)
            }, 10)
            mainHandler.postDelayed(this, 120)
        }
    }

    private val toolbarScanRunnable = Runnable {
        scanAndSyncFloatingSelectionToolbar()
    }

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (internalClipChange) {
            internalClipChange = false
            return@OnPrimaryClipChangedListener
        }
        val clip = clipboardManager.primaryClip ?: return@OnPrimaryClipChangedListener
        if (clip.itemCount > 0) {
            val item = clip.getItemAt(0)
            val clipUri = item.uri
            if (clipUri != null) {
                val mime = contentResolver.getType(clipUri) ?: clip.description.getMimeType(0) ?: "image/*"
                handleSharedUri(clipUri, mime)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        clipboardManager.addPrimaryClipChangedListener(clipListener)
        refreshLauncherPackages()
        cleanupOldTempMediaStoreEntries()
        setupDragLocationTracker()
    }

    private fun refreshLauncherPackages() {
        runCatching {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val infos = packageManager.queryIntentActivities(homeIntent, PackageManager.MATCH_ALL)
            for (info in infos) {
                info.activityInfo?.packageName?.let { launcherPackages.add(it) }
            }
        }
    }

    private fun cleanupOldTempMediaStoreEntries() {
        ioExecutor.execute {
            runCatching {
                contentResolver.delete(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                    arrayOf("OrigDrag_%", "DragImage_%")
                )
            }
        }
    }

    private fun isHomeLauncher(pkg: String): Boolean {
        if (pkg.isEmpty() || pkg == packageName) return true
        return launcherPackages.contains(pkg) || pkg.contains("launcher", ignoreCase = true)
    }

    private fun isGalleryApp(pkg: String): Boolean {
        val lower = pkg.lowercase()
        return lower.contains("gallery") || lower.contains("photos") || lower == "com.sec.android.gallery3d"
    }

    private fun triggerHapticFeedback() {
        runCatching {
            vibrator.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || isGlobalDragging || isPreparingToolbarCopyDrag) return
        val pkgName = event.packageName?.toString() ?: ""
        if (pkgName == packageName) return

        val now = System.currentTimeMillis()

        when (event.eventType) {
            // 1. 제미나이, 삼성노트, 일반 EditText 등 표준 텍스트 선택 이벤트 지원
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val source = event.source ?: return
                val fullText = source.text?.toString() ?: ""
                val start = event.fromIndex
                val end = event.toIndex
                if (start >= 0 && end > start && end <= fullText.length) {
                    val selected = fullText.substring(start, end).trim()
                    if (selected.isNotEmpty()) {
                        val rect = Rect()
                        source.getBoundsInScreen(rect)
                        lastEventTimestamp = now
                        isShowingToolbarCapsule = false
                        showQuickDragPill(
                            DragPayload(PayloadType.TEXT, text = selected, displayName = selected),
                            rect.centerX(),
                            rect.top
                        )
                        return
                    }
                }
                scheduleFloatingToolbarScan()
            }

            // 2. [v8.0 핵심] 챗GPT, 클로드, 삼성 인터넷, 크롬 등에서 텍스트 블록 선택 시 뜨는 플로팅 툴바("복사 | 모두 선택...") 실시간 감지
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (!isHomeLauncher(pkgName)) {
                    scheduleFloatingToolbarScan()
                }
            }

            // 3. [v8.0 핵심] 챗GPT/클로드 메시지 하단 [복사] 아이콘 또는 툴바의 [복사] 버튼을 직접 눌렀을 때도 즉시 드래그 캡슐 팝업
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val source = event.source ?: return
                val label = (source.text?.toString() ?: source.contentDescription?.toString() ?: "").trim()
                val viewId = source.viewIdResourceName ?: ""
                if (copyLabels.contains(label) || label.contains("복사") || viewId.contains("copy", ignoreCase = true)) {
                    val rect = Rect()
                    source.getBoundsInScreen(rect)
                    val cx = if (rect.width() > 0) rect.centerX() else resources.displayMetrics.widthPixels / 2
                    val cy = if (rect.height() > 0) rect.top else resources.displayMetrics.heightPixels / 2
                    readClipboardWithTempFocus(delayMs = 65L) { copiedText ->
                        if (!copiedText.isNullOrBlank()) {
                            isShowingToolbarCapsule = false
                            showQuickDragPill(
                                DragPayload(PayloadType.TEXT, text = copiedText, displayName = copiedText),
                                cx,
                                cy
                            )
                        }
                    }
                }
            }

            // 4. 갤러리 및 일반 앱 롱프레스 지원
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                if (isHomeLauncher(pkgName)) return
                if (now - lastEventTimestamp < 350) return
                val source = event.source ?: rootInActiveWindow ?: return
                lastEventTimestamp = now
                if (isGalleryApp(pkgName)) {
                    handleGalleryNodeSelection(source)
                } else {
                    // 먼저 플로팅 텍스트 선택 툴바가 떴는지 확인 후, 없으면 인라인 스위프 실행
                    mainHandler.postDelayed({
                        val toolbar = findFloatingSelectionToolbar()
                        if (toolbar != null) {
                            showOrUpdateToolbarSelectionCapsule(toolbar)
                        } else {
                            val rect = Rect()
                            source.getBoundsInScreen(rect)
                            val textNodes = findTextNodesAround(rect.centerX(), rect.centerY())
                            if (textNodes.isNotEmpty()) {
                                triggerHapticFeedback()
                                startInlineTextSweepSession(textNodes, rect.centerX().toFloat(), rect.centerY().toFloat())
                            }
                        }
                    }, 180)
                }
            }

            // 5. 삼성 갤러리 썸네일 선택 지원
            AccessibilityEvent.TYPE_VIEW_SELECTED -> {
                if (!isGalleryApp(pkgName)) return
                if (now - lastEventTimestamp < 350) return
                val source = event.source ?: return
                lastEventTimestamp = now
                handleGalleryNodeSelection(source)
            }
        }
    }

    private fun scheduleFloatingToolbarScan() {
        val now = System.currentTimeMillis()
        if (now - lastToolbarScanTime < 110L) {
            mainHandler.removeCallbacks(toolbarScanRunnable)
            mainHandler.postDelayed(toolbarScanRunnable, 110L)
        } else {
            lastToolbarScanTime = now
            mainHandler.removeCallbacks(toolbarScanRunnable)
            mainHandler.postDelayed(toolbarScanRunnable, 40L)
        }
    }

    /**
     * [v8.0 핵심 엔진]
     * 챗GPT(Jetpack Compose), 클로드, 브라우저(WebView) 등에서 글자를 블록 지정하면 나타나는
     * 안드로이드 시스템 플로팅 툴바("복사 | 모두 선택 | Gemini에게 물어보기 | 번역...")를 찾아내고,
     * 그 바로 위에 드래그 캡슐을 띄워 핸들(●) 조절을 방해하지 않으면서도 잡는 즉시 드래그되게 함.
     */
    private fun scanAndSyncFloatingSelectionToolbar() {
        if (isGlobalDragging || isPreparingToolbarCopyDrag) return
        val toolbar = findFloatingSelectionToolbar()
        if (toolbar != null) {
            showOrUpdateToolbarSelectionCapsule(toolbar)
        } else if (isShowingToolbarCapsule) {
            // 사용자가 선택 영역을 해제하여 툴바가 사라진 경우 캡슐도 닫음
            removeActiveOverlay()
        }
    }

    private fun findFloatingSelectionToolbar(): SelectionToolbarInfo? {
        val allRoots = mutableListOf<AccessibilityNodeInfo>()
        runCatching {
            for (w in windows) {
                w.root?.let { allRoots.add(it) }
            }
        }
        rootInActiveWindow?.let { root ->
            if (allRoots.none { it == root }) allRoots.add(root)
        }
        if (allRoots.isEmpty()) return null

        var copyRaw: AccessibilityNodeInfo? = null
        var copyClickable: AccessibilityNodeInfo? = null
        val copyRect = Rect()
        val siblingRects = mutableListOf<Rect>()
        var selectedTextFromNode: String? = null

        val maxButtonW = dp(170f)
        val maxButtonH = dp(80f)

        fun inspectTree(node: AccessibilityNodeInfo?) {
            if (node == null || !node.isVisibleToUser) return

            // 만약 노드에 직접 선택된 텍스트 인덱스가 노출되어 있다면 함께 추출
            val nodeText = node.text?.toString()
            if (!nodeText.isNullOrEmpty() && node.textSelectionStart >= 0 && node.textSelectionEnd > node.textSelectionStart) {
                val s = min(node.textSelectionStart, node.textSelectionEnd).coerceAtLeast(0)
                val e = max(node.textSelectionStart, node.textSelectionEnd).coerceAtMost(nodeText.length)
                if (e > s) {
                    selectedTextFromNode = nodeText.substring(s, e).trim()
                }
            }

            val label = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").trim()
            val viewId = node.viewIdResourceName ?: ""

            if (label.isNotEmpty() && label.length <= 18) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (r.width() in dp(20f)..maxButtonW && r.height() in dp(16f)..maxButtonH) {
                    if (copyLabels.contains(label) || viewId.endsWith("select_action_menu_copy") || viewId.endsWith("floating_toolbar_menu_item_copy")) {
                        val clickable = findClickableSelfOrParent(node)
                        if (clickable != null) {
                            copyRaw = node
                            copyClickable = clickable
                            clickable.getBoundsInScreen(copyRect)
                            if (copyRect.width() <= 0 || copyRect.width() > maxButtonW * 2) {
                                copyRect.set(r)
                            }
                        }
                    } else if (siblingToolbarKeywords.any { label.equals(it, ignoreCase = true) || label.contains(it) }) {
                        siblingRects.add(r)
                    }
                }
            }

            for (i in 0 until node.childCount) {
                inspectTree(node.getChild(i))
            }
        }

        for (root in allRoots) {
            inspectTree(root)
        }

        val finalCopyClickable = copyClickable ?: return null
        val finalCopyRaw = copyRaw ?: return null

        // "복사" 버튼과 같은 가로줄(Y축 ±48dp 이내)에 "모두 선택", "번역", "소리 내 읽기" 등 동료 툴바 버튼이 최소 1개 이상 있는지 검증
        val alignedSiblings = siblingRects.filter {
            abs(it.centerY() - copyRect.centerY()) <= dp(48f)
        }
        val isFloatingToolbarId = (finalCopyRaw.viewIdResourceName ?: "").contains("floating_toolbar") ||
                (finalCopyClickable.viewIdResourceName ?: "").contains("floating_toolbar")

        if (alignedSiblings.isEmpty() && !isFloatingToolbarId) {
            return null
        }

        val combinedToolbarRect = Rect(copyRect)
        for (sr in alignedSiblings) {
            combinedToolbarRect.union(sr)
        }

        return SelectionToolbarInfo(
            copyClickableNode = finalCopyClickable,
            copyRawNode = finalCopyRaw,
            copyRect = copyRect,
            toolbarRect = combinedToolbarRect,
            preExtractedText = selectedTextFromNode
        )
    }

    private fun findClickableSelfOrParent(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var cur = node
        var depth = 0
        while (cur != null && depth < 4) {
            if (cur.isClickable || cur.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
                return cur
            }
            cur = cur.parent
            depth++
        }
        return node
    }

    /**
     * 플로팅 툴바("복사 | 모두 선택...") 바로 위(또는 아래)에 드래그 캡슐을 표시하고,
     * 사용자가 핸들(●)을 움직여 툴바 위치가 바뀌면 캡슐 위치만 부드럽게 동기화함.
     */
    private fun showOrUpdateToolbarSelectionCapsule(toolbar: SelectionToolbarInfo) {
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels

        val targetX = (toolbar.toolbarRect.centerX() - dp(125f)).coerceIn(dp(16f), screenW - dp(265f))
        val aboveY = toolbar.toolbarRect.top - dp(62f)
        val targetY = if (aboveY >= dp(48f)) {
            aboveY
        } else {
            (toolbar.toolbarRect.bottom + dp(68f)).coerceAtMost(screenH - dp(140f))
        }

        val payload = DragPayload(
            type = PayloadType.TEXT,
            text = toolbar.preExtractedText,
            displayName = toolbar.preExtractedText ?: "선택한 텍스트 블록",
            requiresToolbarCopyOnDrag = toolbar.preExtractedText.isNullOrBlank()
        )
        currentPayload = payload

        val overlay = activeOverlayView
        val lp = activeOverlayParams
        if (isShowingToolbarCapsule && overlay != null && lp != null) {
            if (abs(lp.x - targetX) > dp(6f) || abs(lp.y - targetY) > dp(6f)) {
                lp.x = targetX
                lp.y = targetY
                runCatching { windowManager.updateViewLayout(overlay, lp) }
            }
            mainHandler.removeCallbacks(autoDismissRunnable)
            mainHandler.postDelayed(autoDismissRunnable, 9000)
            return
        }

        isShowingToolbarCapsule = true
        showQuickDragPill(payload, toolbar.toolbarRect.centerX(), toolbar.toolbarRect.top, isFromToolbar = true)
    }

    /**
     * [v8.0 핵심]
     * 안드로이드 10~15의 백그라운드 클립보드 읽기 차단을 우회하기 위해
     * 오버레이에 약 30ms 동안만 포커스를 부여해 클립보드 텍스트를 읽어낸 뒤 즉시 원래 플래그로 복원함.
     */
    private fun readClipboardWithTempFocus(delayMs: Long = 45L, onResult: (String?) -> Unit) {
        mainHandler.postDelayed({
            val tempView = View(this)
            val lp = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }

            runCatching {
                windowManager.addView(tempView, lp)
                tempView.requestFocus()
            }

            mainHandler.postDelayed({
                var text: String? = null
                runCatching {
                    val clip = clipboardManager.primaryClip
                    if (clip != null && clip.itemCount > 0) {
                        text = clip.getItemAt(0).coerceToText(this)?.toString()?.trim()
                    }
                }
                runCatching { windowManager.removeView(tempView) }
                onResult(text)
            }, 35L)
        }, delayMs)
    }

    /**
     * 사용자가 [⠿ 선택 텍스트 드래그] 캡슐을 잡고 끄는 순간:
     * 1) 화면에 떠 있는 플로팅 툴바의 "복사" 버튼을 자동으로 클릭(performAction)하여
     *    챗GPT/클로드의 다중 문단 선택 영역 전체를 클립보드에 복사시키고,
     * 2) 오버레이에 순간 포커스를 부여해 복사된 텍스트를 읽어온 뒤
     * 3) 손을 떼지 않은 상태 그대로 글로벌 드래그(startDragAndDrop)를 즉시 발동함!
     */
    private fun triggerToolbarCopyAndStartGlobalDrag(overlayContainer: View) {
        if (isGlobalDragging || isPreparingToolbarCopyDrag) return
        isPreparingToolbarCopyDrag = true

        // 1단계: 현재 화면의 플로팅 툴바 "복사" 버튼 즉시 클릭
        val liveToolbar = findFloatingSelectionToolbar()
        var copyClicked = false
        if (liveToolbar != null) {
            copyClicked = liveToolbar.copyClickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!copyClicked) {
                copyClicked = liveToolbar.copyRawNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            if (!copyClicked) {
                copyClicked = liveToolbar.copyRawNode.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
        }

        // 2단계: 25ms 후 오버레이 윈도우에 포커스를 활성화하여 안드로이드 10~15 클립보드 읽기 권한 획득
        mainHandler.postDelayed({
            val lp = activeOverlayParams
            if (lp != null && activeOverlayView == overlayContainer) {
                lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                runCatching {
                    windowManager.updateViewLayout(overlayContainer, lp)
                    overlayContainer.requestFocus()
                }
            }

            // 3단계: 클립보드에서 방금 복사된 챗GPT/클로드/브라우저 텍스트 읽기 및 글로벌 드래그 시작
            mainHandler.postDelayed({
                var copiedText: String? = null
                runCatching {
                    val clip = clipboardManager.primaryClip
                    if (clip != null && clip.itemCount > 0) {
                        copiedText = clip.getItemAt(0).coerceToText(this)?.toString()?.trim()
                    }
                }

                // 플래그 원복
                if (lp != null && activeOverlayView == overlayContainer) {
                    lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    runCatching { windowManager.updateViewLayout(overlayContainer, lp) }
                }

                isPreparingToolbarCopyDrag = false
                val finalText = if (!copiedText.isNullOrBlank()) {
                    copiedText!!
                } else {
                    currentPayload?.text ?: ""
                }

                if (finalText.isNotBlank()) {
                    triggerHapticFeedback()
                    startGlobalDragDirectly(
                        DragPayload(
                            type = PayloadType.TEXT,
                            text = finalText,
                            displayName = finalText,
                            requiresToolbarCopyOnDrag = false
                        )
                    )
                }
            }, 35L)
        }, 25L)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        clipboardManager.removePrimaryClipChangedListener(clipListener)
        removeActiveOverlay()
        dragTrackerView?.let { runCatching { windowManager.removeView(it) } }
        super.onDestroy()
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun findTextNodesAround(touchX: Int, touchY: Int): List<InlineTextSweepOverlayView.TextNodeItem> {
        val root = rootInActiveWindow ?: return emptyList()
        val collected = mutableListOf<InlineTextSweepOverlayView.TextNodeItem>()

        fun traverse(n: AccessibilityNodeInfo?) {
            if (n == null || !n.isVisibleToUser) return
            val t = n.text?.toString()?.trim() ?: n.contentDescription?.toString()?.trim()
            if (!t.isNullOrEmpty() && t.length >= 2) {
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.width() > dp(30f) && r.height() in dp(14f)..dp(500f)) {
                    collected.add(InlineTextSweepOverlayView.TextNodeItem(r, t))
                }
            }
            for (i in 0 until n.childCount) traverse(n.getChild(i))
        }

        for (w in windows) traverse(w.root)
        if (collected.isEmpty()) traverse(root)

        val rangeY = dp(400f)
        return collected.filter {
            abs(it.rect.centerY() - touchY) <= rangeY
        }.sortedBy { it.rect.top }
    }

    private fun startInlineTextSweepSession(
        nodes: List<InlineTextSweepOverlayView.TextNodeItem>,
        touchX: Float,
        touchY: Float
    ) {
        mainHandler.post {
            removeActiveOverlay()

            val sweepView = InlineTextSweepOverlayView(
                context = this,
                textNodes = nodes,
                initialTouchX = touchX,
                initialTouchY = touchY,
                onStartGlobalDrag = { selectedText ->
                    val payload = DragPayload(
                        type = PayloadType.TEXT,
                        text = selectedText,
                        displayName = selectedText
                    )
                    startGlobalDragDirectly(payload)
                },
                onDismiss = {
                    removeActiveOverlay()
                }
            )

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )

            attachDragEndListener(sweepView)
            activeOverlayView = sweepView
            activeOverlayParams = params
            windowManager.addView(sweepView, params)
            mainHandler.postDelayed(autoDismissRunnable, 10000)
        }
    }

    private fun startGlobalDragDirectly(payload: DragPayload) {
        val overlay = activeOverlayView ?: return
        if (isGlobalDragging) return
        isGlobalDragging = true
        currentPayload = payload
        forceDropHandledByTracker = false
        preDropFocusedText = getFocusedEditableText()

        val clipData: ClipData
        val badge: String
        val sub: String

        if (payload.type == PayloadType.TEXT) {
            val txt = payload.text ?: ""
            internalClipChange = true
            clipboardManager.setPrimaryClip(ClipData.newPlainText("UniversalDragText", txt))
            clipData = ClipData.newPlainText("UniversalDragText", txt)
            badge = "TEXT (${txt.length}자)"
            sub = txt
        } else {
            val uri = payload.uri ?: run {
                isGlobalDragging = false
                return
            }
            if (payload.type == PayloadType.IMAGE) {
                internalClipChange = true
                runCatching {
                    clipboardManager.setPrimaryClip(ClipData.newUri(contentResolver, "UniversalDragImage", uri))
                }
            }
            val clipDesc = ClipDescription(payload.displayName, arrayOf(payload.mimeType))
            clipData = ClipData(clipDesc, ClipData.Item(uri))
            GrantPermissionsToAllApps(uri)
            val res = if (payload.origWidth > 0 && payload.origHeight > 0) "${payload.origWidth}×${payload.origHeight}" else "ORIGINAL"
            badge = "${payload.type.name} • $res"
            sub = payload.displayName
        }

        val shadowBuilder = UniversalDragShadowBuilder(
            context = this,
            titleBadge = badge,
            subtitleText = sub,
            previewBitmap = payload.previewBitmap
        )

        var flags = View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_OPAQUE
        if (payload.uri != null) {
            flags = flags or View.DRAG_FLAG_GLOBAL_URI_READ
        }

        val started = overlay.startDragAndDrop(clipData, shadowBuilder, null, flags)
        if (started) {
            overlay.alpha = 0f
            val lp = (activeOverlayParams ?: overlay.layoutParams) as WindowManager.LayoutParams
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            runCatching { windowManager.updateViewLayout(overlay, lp) }
            mainHandler.postDelayed(sampleDragLocationRunnable, 60)
        } else {
            isGlobalDragging = false
            removeActiveOverlay()
        }
    }

    private fun GrantPermissionsToAllApps(uri: Uri) {
        runCatching {
            val intent = Intent(Intent.ACTION_SEND).apply { type = "*/*" }
            val resolves = packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            for (info in resolves) {
                val pkg = info.activityInfo?.packageName ?: continue
                runCatching {
                    grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }
    }

    private fun handleGalleryNodeSelection(node: AccessibilityNodeInfo) {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val cx = if (rect.width() > 0) rect.centerX() else resources.displayMetrics.widthPixels / 2
        val cy = if (rect.height() > 0) rect.centerY() else resources.displayMetrics.heightPixels / 2

        val desc = collectGalleryMetadataFromNode(node)
        val isGridThumb = rect.width() < (resources.displayMetrics.widthPixels * 0.65f)

        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                ioExecutor.execute {
                    var targetCrop: Bitmap? = null
                    runCatching {
                        val hwBuffer = screenshot.hardwareBuffer
                        val hwBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, screenshot.colorSpace)
                        val swBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                        hwBuffer.close()

                        if (swBitmap != null && rect.width() > dp(32f) && rect.height() > dp(32f)) {
                            val left = rect.left.coerceIn(0, swBitmap.width - 1)
                            val top = rect.top.coerceIn(0, swBitmap.height - 1)
                            val width = rect.width().coerceIn(1, swBitmap.width - left)
                            val height = rect.height().coerceIn(1, swBitmap.height - top)
                            targetCrop = Bitmap.createBitmap(swBitmap, left, top, width, height)
                        }
                    }

                    val matchedPayload = resolveExactGalleryMedia(desc, targetCrop, isGridThumb)
                    if (matchedPayload != null) {
                        mainHandler.post { showQuickDragPill(matchedPayload, cx, cy) }
                    } else if (targetCrop != null) {
                        val bmp = targetCrop!!
                        val fileName = "GalleryDrag_${System.currentTimeMillis()}.png"
                        val uri = insertBitmapIntoMediaStore(bmp, fileName, "image/png")
                        if (uri != null) {
                            val fallback = DragPayload(
                                type = PayloadType.IMAGE,
                                uri = uri,
                                mimeType = "image/png",
                                displayName = fileName,
                                origWidth = bmp.width,
                                origHeight = bmp.height,
                                previewBitmap = createAspectRatioPreview(bmp, 480)
                            )
                            mainHandler.post { showQuickDragPill(fallback, cx, cy) }
                        }
                    }
                }
            }

            override fun onFailure(errorCode: Int) {
                ioExecutor.execute {
                    val matchedPayload = resolveExactGalleryMedia(desc, null, isGridThumb)
                    if (matchedPayload != null) {
                        mainHandler.post { showQuickDragPill(matchedPayload, cx, cy) }
                    }
                }
            }
        })
    }

    private fun collectGalleryMetadataFromNode(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        node.contentDescription?.let { sb.append(it).append(" ") }
        node.text?.let { sb.append(it).append(" ") }
        node.parent?.contentDescription?.let { sb.append(it).append(" ") }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            child.contentDescription?.let { sb.append(it).append(" ") }
            child.text?.let { sb.append(it).append(" ") }
        }
        return sb.toString().trim()
    }

    private fun resolveExactGalleryMedia(
        nodeDesc: String,
        screenCrop: Bitmap?,
        isGridSquareThumb: Boolean
    ): DragPayload? {
        val isVideoHint = nodeDesc.contains("동영상") || nodeDesc.contains("video", true)

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.WIDTH,
            MediaStore.Files.FileColumns.HEIGHT,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.RELATIVE_PATH
        )

        data class MediaCandidate(
            val id: Long,
            val name: String,
            val mime: String,
            val width: Int,
            val height: Int,
            val isVideo: Boolean,
            val dateTakenMs: Long,
            val uri: Uri
        )

        fun queryCandidates(extraWhere: String?, extraArgs: Array<String>?, limit: Int): List<MediaCandidate> {
            val list = mutableListOf<MediaCandidate>()
            var sel = "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR " +
                    "${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}) AND " +
                    "${MediaStore.MediaColumns.DISPLAY_NAME} NOT LIKE 'OrigDrag_%' AND " +
                    "${MediaStore.MediaColumns.DISPLAY_NAME} NOT LIKE 'DragImage_%' AND " +
                    "${MediaStore.MediaColumns.DISPLAY_NAME} NOT LIKE 'GalleryDrag_%'"
            if (!extraWhere.isNullOrEmpty()) {
                sel = "$sel AND ($extraWhere)"
            }

            runCatching {
                contentResolver.query(
                    MediaStore.Files.getContentUri("external"),
                    projection,
                    sel,
                    extraArgs,
                    "${MediaStore.MediaColumns.DATE_TAKEN} DESC, ${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    var count = 0
                    while (cursor.moveToNext() && count < limit) {
                        val relPath = cursor.getString(8) ?: ""
                        if (relPath.contains("UniversalDrag", true)) continue

                        val id = cursor.getLong(0)
                        val name = cursor.getString(1) ?: "media"
                        val mime = cursor.getString(2) ?: "image/jpeg"
                        val w = cursor.getInt(3)
                        val h = cursor.getInt(4)
                        val mediaType = cursor.getInt(5)
                        val isVid = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO || mime.startsWith("video/")
                        val dateTaken = cursor.getLong(6).let { if (it > 0) it else cursor.getLong(7) * 1000L }

                        val contentUri = ContentUris.withAppendedId(
                            if (isVid) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            id
                        )
                        list.add(MediaCandidate(id, name, mime, w, h, isVid, dateTaken, contentUri))
                        count++
                    }
                }
            }
            return list
        }

        val parsedRange = parseGalleryDateRange(nodeDesc)
        val dateCandidates = if (parsedRange != null) {
            val startMs = parsedRange.first
            val endMs = parsedRange.second
            val startSec = startMs / 1000L
            val endSec = endMs / 1000L
            queryCandidates(
                "(${MediaStore.MediaColumns.DATE_TAKEN} BETWEEN ? AND ?) OR (${MediaStore.MediaColumns.DATE_MODIFIED} BETWEEN ? AND ?)",
                arrayOf(startMs.toString(), endMs.toString(), startSec.toString(), endSec.toString()),
                40
            )
        } else {
            emptyList()
        }

        if (dateCandidates.size == 1) {
            val c = dateCandidates.first()
            return candidateToPayload(c.uri, c.name, c.mime, c.width, c.height, c.isVideo)
        }

        val pool = if (dateCandidates.isNotEmpty()) dateCandidates else queryCandidates(null, null, 80).let {
            if (isVideoHint) it.filter { c -> c.isVideo }.ifEmpty { it } else it
        }

        if (screenCrop != null && pool.isNotEmpty()) {
            val targetSig = computeCenterVisualSignature(screenCrop, isGridSquareThumb)
            var bestCandidate: MediaCandidate? = null
            var bestDist = Long.MAX_VALUE

            for (cand in pool) {
                runCatching {
                    val thumb = contentResolver.loadThumbnail(cand.uri, Size(96, 96), null)
                    val candSig = computeCenterVisualSignature(thumb, isGridSquareThumb)
                    val dist = signatureDistance(targetSig, candSig)
                    if (dist < bestDist) {
                        bestDist = dist
                        bestCandidate = cand
                    }
                }
            }

            if (bestCandidate != null) {
                val c = bestCandidate!!
                return candidateToPayload(c.uri, c.name, c.mime, c.width, c.height, c.isVideo)
            }
        }

        if (dateCandidates.isNotEmpty()) {
            val c = dateCandidates.first()
            return candidateToPayload(c.uri, c.name, c.mime, c.width, c.height, c.isVideo)
        }
        return null
    }

    private fun computeCenterVisualSignature(bmp: Bitmap, squareCropFirst: Boolean): IntArray {
        val base = if (squareCropFirst && bmp.width != bmp.height) {
            val side = min(bmp.width, bmp.height)
            val x = (bmp.width - side) / 2
            val y = (bmp.height - side) / 2
            Bitmap.createBitmap(bmp, x, y, side, side)
        } else {
            bmp
        }

        val cw = (base.width * 0.65f).toInt().coerceAtLeast(1)
        val ch = (base.height * 0.65f).toInt().coerceAtLeast(1)
        val cx = ((base.width - cw) / 2).coerceAtLeast(0)
        val cy = ((base.height - ch) / 2).coerceAtLeast(0)

        val centerBmp = Bitmap.createBitmap(base, cx, cy, cw, ch)
        val scaled = Bitmap.createScaledBitmap(centerBmp, 12, 12, true)
        val pixels = IntArray(144)
        scaled.getPixels(pixels, 0, 12, 0, 0, 12, 12)
        return pixels
    }

    private fun signatureDistance(sig1: IntArray, sig2: IntArray): Long {
        var sum = 0L
        for (i in sig1.indices) {
            val c1 = sig1[i]
            val c2 = sig2[i]
            sum += abs(Color.red(c1) - Color.red(c2)) +
                   abs(Color.green(c1) - Color.green(c2)) +
                   abs(Color.blue(c1) - Color.blue(c2))
        }
        return sum
    }

    private fun parseGalleryDateRange(desc: String): Pair<Long, Long>? {
        val dateRegex = Regex("(\\d{4})(?:년|\\.|-)\\s*(\\d{1,2})(?:월|\\.|-)\\s*(\\d{1,2})일?")
        val match = dateRegex.find(desc) ?: return null
        val year = match.groupValues[1].toIntOrNull() ?: return null
        val month = (match.groupValues[2].toIntOrNull() ?: return null) - 1
        val day = match.groupValues[3].toIntOrNull() ?: return null

        val timeRegex = Regex("(오전|오후|AM|PM)?\\s*(\\d{1,2})(?:시|:)\\s*(\\d{1,2})분?")
        val timeMatch = timeRegex.find(desc.substring(match.range.last))

        val cal = Calendar.getInstance()
        if (timeMatch != null) {
            val ampm = timeMatch.groupValues[1].uppercase()
            var hour = timeMatch.groupValues[2].toIntOrNull() ?: 0
            val minute = timeMatch.groupValues[3].toIntOrNull() ?: 0
            if ((ampm == "오후" || ampm == "PM") && hour < 12) hour += 12
            if ((ampm == "오전" || ampm == "AM") && hour == 12) hour = 0

            cal.set(year, month, day, hour, minute, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return (cal.timeInMillis - 120_000L) to (cal.timeInMillis + 180_000L)
        } else {
            cal.set(year, month, day, 0, 0, 0)
            val start = cal.timeInMillis - 60_000L
            cal.set(year, month, day, 23, 59, 59)
            val end = cal.timeInMillis + 60_000L
            return start to end
        }
    }

    private fun candidateToPayload(
        uri: Uri,
        name: String,
        mime: String,
        dbW: Int,
        dbH: Int,
        isVideo: Boolean
    ): DragPayload {
        var w = dbW
        var h = dbH
        val preview = if (isVideo) {
            extractVideoFrameAndSize(uri)?.also {
                if (w == 0 || h == 0) {
                    w = it.second
                    h = it.third
                }
            }?.first
        } else {
            decodeSampledPreview(uri)?.also {
                if (w == 0 || h == 0) {
                    w = it.second
                    h = it.third
                }
            }?.first
        }
        return DragPayload(
            type = if (isVideo) PayloadType.VIDEO else PayloadType.IMAGE,
            uri = uri,
            mimeType = mime,
            displayName = name,
            origWidth = w,
            origHeight = h,
            previewBitmap = preview
        )
    }

    fun showQuickDragPill(payload: DragPayload, posX: Int, posY: Int, isFromToolbar: Boolean = false) {
        mainHandler.post {
            removeActiveOverlay()
            isShowingToolbarCapsule = isFromToolbar
            currentPayload = payload

            val screenW = resources.displayMetrics.widthPixels
            val screenH = resources.displayMetrics.heightPixels

            val container = FrameLayout(this)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14f), dp(10f), dp(12f), dp(10f))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(20f).toFloat()
                    setColor(Color.parseColor("#F218181B"))
                    setStroke(dp(2f), Color.parseColor("#818CF8"))
                }
                elevation = dp(12f).toFloat()
            }

            if (payload.previewBitmap != null && !payload.previewBitmap.isRecycled) {
                val bw = payload.previewBitmap.width.toFloat()
                val bh = payload.previewBitmap.height.toFloat()
                val maxSide = dp(72f).toFloat()
                val scale = minOf(maxSide / bw, maxSide / bh)
                val targetW = (bw * scale).toInt().coerceAtLeast(dp(36f))
                val targetH = (bh * scale).toInt().coerceAtLeast(dp(36f))

                val imgView = ImageView(this).apply {
                    setImageBitmap(payload.previewBitmap)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = LinearLayout.LayoutParams(targetW, targetH).apply {
                        rightMargin = dp(10f)
                    }
                }
                card.addView(imgView)
            }

            val resInfo = if (payload.origWidth > 0 && payload.origHeight > 0) {
                "${payload.origWidth}×${payload.origHeight} 원본"
            } else {
                "원본"
            }

            val typeTitle = when (payload.type) {
                PayloadType.TEXT -> if (payload.requiresToolbarCopyOnDrag) "⠿ 선택 영역 바로 드래그" else "⠿ 텍스트 드래그"
                PayloadType.IMAGE -> "🖼️ 이미지 ($resInfo)"
                PayloadType.VIDEO -> "🎬 영상 ($resInfo)"
                PayloadType.FILE -> "📁 ${payload.displayName}"
            }

            val infoCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }

            val titleTv = TextView(this).apply {
                text = typeTitle
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }

            val subTv = TextView(this).apply {
                text = if (payload.type == PayloadType.TEXT) {
                    if (payload.requiresToolbarCopyOnDrag) {
                        "이 캡슐을 잡고 끌면 선택 문장이 바로 이동됩니다"
                    } else {
                        payload.displayName.replace("\n", " ").take(24)
                    }
                } else {
                    "${payload.displayName} • 잡고 다른 앱으로 이동"
                }
                setTextColor(Color.parseColor("#A5B4FC"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }

            infoCol.addView(titleTv)
            infoCol.addView(subTv)
            card.addView(infoCol)

            val closeBtn = TextView(this).apply {
                text = "✕"
                setTextColor(Color.parseColor("#A1A1AA"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(12f), dp(4f), dp(4f), dp(4f))
                setOnClickListener {
                    isShowingToolbarCapsule = false
                    removeActiveOverlay()
                }
            }
            card.addView(closeBtn)

            container.addView(card)

            val aboveY = posY - dp(64f)
            val finalY = if (aboveY >= dp(48f)) aboveY else (posY + dp(68f)).coerceAtMost(screenH - dp(140f))

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (posX - dp(125f)).coerceIn(dp(16f), screenW - dp(265f))
                y = finalY
            }

            var startX = 0f
            var startY = 0f

            card.setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = ev.rawX
                        startY = ev.rawY
                        lastDropX = ev.rawX
                        lastDropY = ev.rawY
                        mainHandler.removeCallbacks(autoDismissRunnable)

                        // 챗GPT/클로드/브라우저 툴바 연동 모드일 경우 터치 즉시 자동 복사+글로벌 드래그 시퀀스 가동
                        val activePayload = currentPayload ?: payload
                        if (activePayload.requiresToolbarCopyOnDrag && activePayload.text.isNullOrBlank()) {
                            triggerToolbarCopyAndStartGlobalDrag(container)
                        }
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val activePayload = currentPayload ?: payload
                        if (!isGlobalDragging && !isPreparingToolbarCopyDrag &&
                            hypot((ev.rawX - startX).toDouble(), (ev.rawY - startY).toDouble()) > dp(5f)
                        ) {
                            if (activePayload.requiresToolbarCopyOnDrag && activePayload.text.isNullOrBlank()) {
                                triggerToolbarCopyAndStartGlobalDrag(container)
                            } else {
                                startGlobalDragDirectly(activePayload)
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!isGlobalDragging && !isPreparingToolbarCopyDrag) {
                            mainHandler.postDelayed(autoDismissRunnable, 5500)
                        }
                        true
                    }
                    else -> false
                }
            }

            attachDragEndListener(container)

            activeOverlayView = container
            activeOverlayParams = params
            windowManager.addView(container, params)
            mainHandler.postDelayed(autoDismissRunnable, if (isFromToolbar) 9000L else 6000L)
        }
    }

    private fun attachDragEndListener(container: View) {
        container.setOnDragListener { _, event ->
            if (event.action == DragEvent.ACTION_DRAG_ENDED) {
                isGlobalDragging = false
                isShowingToolbarCapsule = false
                mainHandler.removeCallbacks(sampleDragLocationRunnable)
                setTrackerTouchable(false)

                val payload = currentPayload
                val dropX = lastDropX.toInt()
                val dropY = lastDropY.toInt()

                if (payload != null && !forceDropHandledByTracker) {
                    if (!event.result) {
                        executeForceDrop(payload, dropX, dropY)
                    } else if (payload.type == PayloadType.TEXT) {
                        val txt = payload.text ?: ""
                        mainHandler.postDelayed({
                            val afterText = getFocusedEditableText()
                            if (afterText != null && afterText == preDropFocusedText && !afterText.contains(txt.take(10))) {
                                executeForceDrop(payload, dropX, dropY)
                            }
                        }, 80)
                    }
                }
                mainHandler.post { removeActiveOverlay() }
            }
            true
        }
    }

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
                    setTrackerTouchable(false)
                    true
                }
                DragEvent.ACTION_DROP -> {
                    if (event.x > 0f || event.y > 0f) {
                        lastDropX = event.x
                        lastDropY = event.y
                    }
                    forceDropHandledByTracker = true
                    setTrackerTouchable(false)
                    currentPayload?.let { executeForceDrop(it, lastDropX.toInt(), lastDropY.toInt()) }
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

    private fun executeForceDrop(payload: DragPayload, dropX: Int, dropY: Int) {
        val targetPkg = rootInActiveWindow?.packageName?.toString() ?: ""
        if (isHomeLauncher(targetPkg)) return

        showDropRipple(dropX, dropY)

        if (payload.type == PayloadType.TEXT) {
            val text = payload.text ?: return
            val directEditable = findEditableNodeNear(dropX, dropY)
            if (directEditable != null) {
                injectTextIntoNode(directEditable, text)
                return
            }
            val clickable = findClickableNodeAt(dropX, dropY)
            clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            dispatchTapGesture(dropX.toFloat(), dropY.toFloat()) {
                mainHandler.postDelayed({
                    val newlyFocused = findBestTargetEditable(dropX, dropY)
                    if (newlyFocused != null) {
                        injectTextIntoNode(newlyFocused, text)
                    }
                }, 220)
            }
        } else {
            val uri = payload.uri ?: return
            val editable = findEditableNodeNear(dropX, dropY) ?: findBestTargetEditable(dropX, dropY)
            if (payload.type == PayloadType.IMAGE && editable != null) {
                editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                if (editable.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                    return
                }
            }
            sendOriginalMediaToTargetApp(uri, payload.mimeType, targetPkg)
        }
    }

    private fun sendOriginalMediaToTargetApp(uri: Uri, mimeType: String, targetPackage: String?) {
        runCatching {
            val clipDesc = ClipDescription("OriginalMedia", arrayOf(mimeType))
            val clip = ClipData(clipDesc, ClipData.Item(uri))

            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = clip
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                if (!targetPackage.isNullOrEmpty() && targetPackage != packageName) {
                    setPackage(targetPackage)
                }
            }
            if (sendIntent.resolveActivity(packageManager) != null) {
                startActivity(sendIntent)
            } else {
                val chooser = Intent.createChooser(sendIntent.apply { setPackage(null) }, "원본 미디어 전송").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(chooser)
            }
        }
    }

    private fun injectTextIntoNode(node: AccessibilityNodeInfo, text: String) {
        internalClipChange = true
        clipboardManager.setPrimaryClip(ClipData.newPlainText("UniversalDragText", text))

        if (!node.isFocused) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        mainHandler.postDelayed({
            node.refresh()
            val before = getActualNodeText(node)
            val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)

            mainHandler.postDelayed({
                val after = getActualNodeText(node)
                if (!pasted || after == before) {
                    val combined = if (before.isEmpty()) text else "$before $text"
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, combined)
                    }
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                }
            }, 60)
        }, 50)
    }

    private fun getActualNodeText(node: AccessibilityNodeInfo): String {
        if (node.isShowingHintText) return ""
        val raw = node.text?.toString() ?: ""
        val hint = node.hintText?.toString() ?: ""
        return if (hint.isNotEmpty() && raw == hint) "" else raw
    }

    private fun getFocusedEditableText(): String? {
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        return getActualNodeText(focused)
    }

    private fun findEditableNodeNear(x: Int, y: Int): AccessibilityNodeInfo? {
        val tolerance = dp(95f).toDouble()
        val candidates = mutableListOf<Pair<Double, AccessibilityNodeInfo>>()

        fun isNodeEditable(n: AccessibilityNodeInfo): Boolean {
            if (n.isEditable) return true
            val cls = n.className?.toString() ?: ""
            if (cls.contains("EditText", true) || cls.contains("BasicTextField", true)) return true
            return n.actionList.any {
                it.id == AccessibilityNodeInfo.ACTION_SET_TEXT || it.id == AccessibilityNodeInfo.ACTION_PASTE
            }
        }

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null || !node.isVisibleToUser) return
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (isNodeEditable(node)) {
                val dist = if (rect.contains(x, y)) 0.0 else hypot((rect.centerX() - x).toDouble(), (rect.centerY() - y).toDouble())
                if (dist <= tolerance) candidates.add(dist to node)
            }
            for (i in 0 until node.childCount) traverse(node.getChild(i))
        }

        for (w in windows) traverse(w.root)
        if (candidates.isEmpty()) traverse(rootInActiveWindow)
        return candidates.minByOrNull { it.first }?.second
    }

    private fun findBestTargetEditable(x: Int, y: Int): AccessibilityNodeInfo? {
        for (w in windows) {
            val focused = w.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && (focused.isEditable || focused.actionList.any {
                    it.id == AccessibilityNodeInfo.ACTION_PASTE || it.id == AccessibilityNodeInfo.ACTION_SET_TEXT
                })) {
                return focused
            }
        }
        return rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditableNodeNear(x, y)
    }

    private fun findClickableNodeAt(x: Int, y: Int): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var minArea = Int.MAX_VALUE

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null || !node.isVisibleToUser) return
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.contains(x, y)) {
                val area = rect.width() * rect.height()
                if ((node.isClickable || node.isFocusable) && area in 1 until minArea) {
                    minArea = area
                    best = node
                }
                for (i in 0 until node.childCount) traverse(node.getChild(i))
            }
        }
        for (w in windows) traverse(w.root)
        if (best == null) traverse(rootInActiveWindow)
        return best
    }

    private fun dispatchTapGesture(x: Float, y: Float, onComplete: () -> Unit) {
        if (x <= 0f || y <= 0f) {
            onComplete()
            return
        }
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 35)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = onComplete()
            override fun onCancelled(gestureDescription: GestureDescription?) = onComplete()
        }, mainHandler)
    }

    private fun showDropRipple(x: Int, y: Int) {
        if (x <= 0 || y <= 0) return
        mainHandler.post {
            val size = dp(54f)
            val ripple = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#336366F1"))
                    setStroke(dp(2f), Color.parseColor("#818CF8"))
                }
            }
            val lp = WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = x - size / 2
                this.y = y - size / 2
            }
            runCatching {
                windowManager.addView(ripple, lp)
                ripple.animate().scaleX(1.5f).scaleY(1.5f).alpha(0f).setDuration(320).withEndAction {
                    runCatching { windowManager.removeView(ripple) }
                }.start()
            }
        }
    }

    private fun removeActiveOverlay() {
        mainHandler.removeCallbacks(autoDismissRunnable)
        isShowingToolbarCapsule = false
        isPreparingToolbarCopyDrag = false
        activeOverlayView?.let {
            runCatching { windowManager.removeView(it) }
        }
        activeOverlayView = null
        activeOverlayParams = null
    }

    fun handleSharedUri(sourceUri: Uri, mimeType: String?) {
        ioExecutor.execute {
            runCatching {
                val resolvedMime = mimeType ?: contentResolver.getType(sourceUri) ?: "*/*"
                val isVideo = resolvedMime.startsWith("video/")
                val isImage = resolvedMime.startsWith("image/")

                if (sourceUri.authority == MediaStore.AUTHORITY) {
                    val meta = if (isVideo) extractVideoFrameAndSize(sourceUri) else decodeSampledPreview(sourceUri)
                    val payload = DragPayload(
                        type = when {
                            isImage -> PayloadType.IMAGE
                            isVideo -> PayloadType.VIDEO
                            else -> PayloadType.FILE
                        },
                        uri = sourceUri,
                        mimeType = resolvedMime,
                        displayName = "Original_${System.currentTimeMillis()}",
                        origWidth = meta?.second ?: 0,
                        origHeight = meta?.third ?: 0,
                        previewBitmap = meta?.first
                    )
                    mainHandler.post {
                        showQuickDragPill(payload, resources.displayMetrics.widthPixels / 2, resources.displayMetrics.heightPixels / 2)
                    }
                    return@runCatching
                }

                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(resolvedMime)
                    ?: if (isVideo) "mp4" else if (isImage) "png" else "bin"
                val fileName = "Original_${System.currentTimeMillis()}.$ext"

                val meta = if (isVideo) extractVideoFrameAndSize(sourceUri) else if (isImage) decodeSampledPreview(sourceUri) else null
                val origW = meta?.second ?: 0
                val origH = meta?.third ?: 0

                val destUri = contentResolver.openInputStream(sourceUri)?.use { input ->
                    insertInputStreamIntoMediaStore(input, fileName, resolvedMime, origW, origH, isVideo, isImage)
                } ?: return@runCatching

                val payload = DragPayload(
                    type = when {
                        isImage -> PayloadType.IMAGE
                        isVideo -> PayloadType.VIDEO
                        else -> PayloadType.FILE
                    },
                    uri = destUri,
                    mimeType = resolvedMime,
                    displayName = fileName,
                    origWidth = origW,
                    origHeight = origH,
                    previewBitmap = meta?.first
                )
                mainHandler.post {
                    showQuickDragPill(payload, resources.displayMetrics.widthPixels / 2, resources.displayMetrics.heightPixels / 2)
                }
            }
        }
    }

    private fun insertBitmapIntoMediaStore(bitmap: Bitmap, displayName: String, mimeType: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.WIDTH, bitmap.width)
            put(MediaStore.MediaColumns.HEIGHT, bitmap.height)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/UniversalDrag")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        contentResolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        return uri
    }

    private fun insertInputStreamIntoMediaStore(
        input: InputStream,
        displayName: String,
        mimeType: String,
        w: Int,
        h: Int,
        isVideo: Boolean,
        isImage: Boolean
    ): Uri? {
        val collection = when {
            isVideo -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            isImage -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
        val relPath = when {
            isVideo -> Environment.DIRECTORY_MOVIES + "/UniversalDrag"
            isImage -> Environment.DIRECTORY_PICTURES + "/UniversalDrag"
            else -> Environment.DIRECTORY_DOWNLOADS + "/UniversalDrag"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (w > 0) put(MediaStore.MediaColumns.WIDTH, w)
            if (h > 0) put(MediaStore.MediaColumns.HEIGHT, h)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(collection, values) ?: return null
        contentResolver.openOutputStream(uri)?.use { out -> input.copyTo(out) }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        return uri
    }

    private fun createAspectRatioPreview(src: Bitmap, maxEdge: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src
        if (w <= maxEdge && h <= maxEdge) return src
        val scale = minOf(maxEdge.toFloat() / w, maxEdge.toFloat() / h)
        return Bitmap.createScaledBitmap(src, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), true)
    }

    private fun decodeSampledPreview(uri: Uri): Triple<Bitmap, Int, Int>? {
        return runCatching {
            val boundsOpt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOpt) }
            val origW = boundsOpt.outWidth
            val origH = boundsOpt.outHeight
            if (origW <= 0 || origH <= 0) return null

            var sampleSize = 1
            while (origW / sampleSize > 600 || origH / sampleSize > 600) sampleSize *= 2
            val decodeOpt = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpt) } ?: return null
            Triple(createAspectRatioPreview(bmp, 480), origW, origH)
        }.getOrNull()
    }

    private fun extractVideoFrameAndSize(uri: Uri): Triple<Bitmap, Int, Int>? {
        return runCatching {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(this, uri)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val frame = retriever.frameAtTime
            retriever.release()
            if (frame != null) Triple(createAspectRatioPreview(frame, 480), w, h) else null
        }.getOrNull()
    }
}
