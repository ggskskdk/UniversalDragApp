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
import android.view.accessibility.AccessibilityWindowInfo
import android.webkit.MimeTypeMap
import android.widget.*
import java.io.InputStream
import java.util.Calendar
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

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
        val previewBitmap: Bitmap? = null
    )

    private lateinit var windowManager: WindowManager
    private lateinit var clipboardManager: ClipboardManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newCachedThreadPool()

    // 평상시에는 어떠한 오버레이 뷰도 띄우지 않아 카톡/키보드 입력을 절대 방해하지 않음
    private var activeOverlayView: View? = null
    private var activeOverlayParams: WindowManager.LayoutParams? = null
    private var activePillTitleView: TextView? = null
    private var activePillBodyView: TextView? = null

    // 드래그 진행 중에만 일시적으로 생성되고 드롭 즉시 제거되는 좌표 추적기
    private var dragTrackerView: View? = null
    private var trackerParams: WindowManager.LayoutParams? = null

    private var currentPayload: DragPayload? = null
    private var isGlobalDragging = false
    private var lastDropX: Float = 0f
    private var lastDropY: Float = 0f
    private var forceDropHandledByTracker = false
    private var preDropFocusedText: String? = null

    private var lastRealtimeSelectedText: String = ""
    private var lastRealtimeTextTimestamp: Long = 0L
    private var lastGalleryTriggerTimestamp: Long = 0L

    private val autoDismissRunnable = Runnable { removeActiveOverlay() }

    private val launcherPackages = mutableSetOf(
        "com.sec.android.app.launcher",
        "com.google.android.apps.nexuslauncher",
        "com.teslacoilsw.launcher",
        "com.microsoft.launcher",
        "com.android.systemui"
    )

    private val sampleDragLocationRunnable = object : Runnable {
        override fun run() {
            if (!isGlobalDragging) return
            setTrackerTouchable(true)
            mainHandler.postDelayed({
                if (isGlobalDragging) setTrackerTouchable(false)
            }, 10)
            mainHandler.postDelayed(this, 110)
        }
    }

    /**
     * 화면 콘텐츠 변경 시 실시간 선택 텍스트 스캔.
     * ponytail: content-changed fires many times a second while typing/scrolling/playing video; a full tree walk
     * (plus per-character location queries) on every event makes the foreground app lag. Debounced to run once
     * the screen has been still for 150ms - selection handles stop moving, so the pill still updates.
     */
    private val selectionScanRunnable = Runnable {
        if (isGlobalDragging) return@Runnable
        val sel = findSelectedTextInTree()?.takeIf { it.first.isNotEmpty() }
            ?: extractComposeHandleSelectedText()?.takeIf { it.first.isNotEmpty() }
            ?: return@Runnable
        lastRealtimeSelectedText = sel.first
        lastRealtimeTextTimestamp = System.currentTimeMillis()
        showOrUpdateRealtimeTextPill(sel.first, sel.second.centerX(), sel.second.top)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        refreshLauncherPackages()
        cleanupOldTempMediaStoreEntries()
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
                    "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                    arrayOf("OrigDrag_%", "DragImage_%", "GalleryDrag_%")
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || isGlobalDragging) return
        val pkgName = event.packageName?.toString() ?: ""
        if (isHomeLauncher(pkgName)) return

        val now = System.currentTimeMillis()

        when (event.eventType) {
            // [1] 실시간 텍스트 선택 이벤트 (제미나이, 삼성 인터넷, 크롬, 카톡, 메모장 등)
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                if (isGalleryApp(pkgName)) return
                val extracted = extractSelectedTextFromSelectionEvent(event)
                if (extracted != null && extracted.first.isNotEmpty()) {
                    val (selText, bounds) = extracted
                    lastRealtimeSelectedText = selText
                    lastRealtimeTextTimestamp = now
                    showOrUpdateRealtimeTextPill(selText, bounds.centerX(), bounds.top)
                } else {
                    // 선택이 해제된 경우(start == end)
                    val src = event.source
                    if (src != null && src.textSelectionStart >= 0 && src.textSelectionStart == src.textSelectionEnd) {
                        if (currentPayload?.type == PayloadType.TEXT) {
                            removeActiveOverlay()
                        }
                    }
                }
            }

            // [2] 화면 콘텐츠 변경 시: 챗GPT(Compose) 물방울 핸들 실시간 텍스트 추출 및 실시간 선택 범위 갱신
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (isGalleryApp(pkgName)) return
                mainHandler.removeCallbacks(selectionScanRunnable)
                mainHandler.postDelayed(selectionScanRunnable, 150)
            }

            // [3] 갤러리 앱에서 사용자가 직접 썸네일이나 이미지를 꾹 눌렀을 때만 동작 (자동 팝업 완전 차단)
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                if (isGalleryApp(pkgName)) {
                    if (now - lastGalleryTriggerTimestamp < 500) return
                    lastGalleryTriggerTimestamp = now

                    val source = event.source
                    val initialRect = Rect()
                    source?.getBoundsInScreen(initialRect)

                    // 삼성 갤러리가 꾹 누른 썸네일을 '선택됨(Checked)' 상태로 전환할 시간을 85ms 부여한 뒤 정확히 추출
                    mainHandler.postDelayed({
                        handleGalleryExactLongPress(initialRect)
                    }, 85)
                }
            }
        }
    }

    override fun onInterrupt() {
        removeActiveOverlay()
        removeDragTracker()
    }

    override fun onDestroy() {
        instance = null
        mainHandler.removeCallbacks(selectionScanRunnable)
        removeActiveOverlay()
        removeDragTracker()
        super.onDestroy()
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun toast(msg: String) = mainHandler.post {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /**
     * [실시간 텍스트 추출 1단계]
     * TYPE_VIEW_TEXT_SELECTION_CHANGED 이벤트에서 실제 드래그된 문자열과 화면 좌표를 즉시 추출
     */
    private fun extractSelectedTextFromSelectionEvent(event: AccessibilityEvent): Pair<String, Rect>? {
        val source = event.source
        if (source != null) {
            // 입력창(EditText)에서 단순 커서 이동인 경우는 제외하고 실제 블록 지정(start != end)만 추출
            val rawText = source.text?.toString() ?: ""
            val sStart = if (source.textSelectionStart >= 0) source.textSelectionStart else event.fromIndex
            val sEnd = if (source.textSelectionEnd >= 0) source.textSelectionEnd else event.toIndex
            val start = min(sStart, sEnd)
            val end = max(sStart, sEnd)

            if (rawText.isNotEmpty() && start >= 0 && end > start && end <= rawText.length) {
                val sub = rawText.substring(start, end).trim()
                if (sub.isNotEmpty()) {
                    val rect = Rect()
                    source.getBoundsInScreen(rect)
                    return sub to rect
                }
            }
        }

        val eventJoined = event.text.joinToString("")
        val eStart = min(event.fromIndex, event.toIndex)
        val eEnd = max(event.fromIndex, event.toIndex)
        if (eventJoined.isNotEmpty() && eStart >= 0 && eEnd > eStart && eEnd <= eventJoined.length) {
            val sub = eventJoined.substring(eStart, eEnd).trim()
            if (sub.isNotEmpty()) {
                val rect = Rect()
                source?.getBoundsInScreen(rect)
                if (rect.isEmpty) {
                    rect.set(
                        resources.displayMetrics.widthPixels / 2 - dp(60f),
                        resources.displayMetrics.heightPixels / 3,
                        resources.displayMetrics.widthPixels / 2 + dp(60f),
                        resources.displayMetrics.heightPixels / 3 + dp(40f)
                    )
                }
                return sub to rect
            }
        }

        return findSelectedTextInTree()
    }

    /**
     * [실시간 텍스트 추출 2단계]
     * 현재 활성 윈도우 트리에서 textSelectionStart != textSelectionEnd 인 모든 노드의 선택 텍스트를 조합
     */
    private fun findSelectedTextInTree(): Pair<String, Rect>? {
        val root = rootInActiveWindow ?: return null
        data class SelectedPiece(val rect: Rect, val text: String)
        val pieces = mutableListOf<SelectedPiece>()

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null || !node.isVisibleToUser) return
            val raw = node.text?.toString()
            val s = node.textSelectionStart
            val e = node.textSelectionEnd
            if (!raw.isNullOrEmpty() && s >= 0 && e >= 0 && s != e) {
                val start = min(s, e).coerceAtLeast(0)
                val end = max(s, e).coerceAtMost(raw.length)
                if (end > start) {
                    val sub = raw.substring(start, end).trim()
                    if (sub.isNotEmpty()) {
                        val r = Rect()
                        node.getBoundsInScreen(r)
                        pieces.add(SelectedPiece(r, sub))
                    }
                }
            }
            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }

        traverse(root)
        if (pieces.isEmpty()) return null

        val sorted = pieces.sortedWith(compareBy<SelectedPiece> { it.rect.top }.thenBy { it.rect.left })
        val combinedText = sorted.joinToString("\n") { it.text }.trim()
        val combinedRect = Rect(sorted.first().rect)
        for (p in sorted) combinedRect.union(p.rect)
        return combinedText to combinedRect
    }

    /**
     * [실시간 텍스트 추출 3단계 - 챗GPT(Jetpack Compose) 및 커스텀 뷰 지원]
     * 화면에 텍스트 선택 툴바(복사/모두 선택)와 두 개의 물방울 선택 핸들(● ... ●)이 떠 있을 때,
     * 핸들 좌표와 글자별 바운딩 박스(EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)를 이용해
     * 클립보드를 전혀 건드리지 않고 사용자가 실시간 드래그 중인 구간만 정확히 잘라냄.
     */
    private fun extractComposeHandleSelectedText(): Pair<String, Rect>? {
        val allWindows = runCatching { windows }.getOrNull() ?: return null
        val screenH = resources.displayMetrics.heightPixels

        // 1. 선택 툴바(복사 / 모두 선택) 존재 여부 확인
        var toolbarRect: Rect? = null
        var hasCopyAction = false

        fun findToolbar(node: AccessibilityNodeInfo?) {
            if (node == null || !node.isVisibleToUser) return
            val t = (node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: "")
            if (t.contains("복사") || t.equals("Copy", ignoreCase = true)) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (r.width() in 1..dp(180f) && r.height() in 1..dp(90f)) {
                    hasCopyAction = true
                    toolbarRect = r
                }
            }
            for (i in 0 until node.childCount) findToolbar(node.getChild(i))
        }

        for (w in allWindows) {
            if (w.root?.packageName?.toString() == packageName) continue
            findToolbar(w.root)
        }
        if (!hasCopyAction) {
            findToolbar(rootInActiveWindow)
        }
        if (!hasCopyAction) return null

        // 2. 물방울 선택 핸들(●) 팝업 윈도우 또는 핸들 뷰 2개의 화면 좌표 수집
        val handleRects = mutableListOf<Rect>()
        for (w in allWindows) {
            if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                val wr = Rect()
                w.getBoundsInScreen(wr)
                if (wr.width() in dp(12f)..dp(88f) && wr.height() in dp(12f)..dp(88f)) {
                    handleRects.add(wr)
                }
            }
        }

        if (handleRects.size < 2) {
            fun findHandleNodes(node: AccessibilityNodeInfo?) {
                if (node == null || !node.isVisibleToUser) return
                val viewId = node.viewIdResourceName ?: ""
                val cls = node.className?.toString() ?: ""
                val r = Rect()
                node.getBoundsInScreen(r)
                if ((viewId.contains("handle", true) || cls.contains("Handle", true)) &&
                    r.width() in dp(10f)..dp(88f) && r.height() in dp(10f)..dp(88f)
                ) {
                    handleRects.add(r)
                }
                for (i in 0 until node.childCount) findHandleNodes(node.getChild(i))
            }
            for (w in allWindows) findHandleNodes(w.root)
        }

        if (handleRects.size < 2) return null

        // 두 물방울 핸들을 위->아래, 좌->우 순서로 정렬
        val sortedHandles = handleRects
            .distinctBy { "${it.centerX() / 6}_${it.centerY() / 6}" }
            .sortedWith(compareBy<Rect> { it.top / dp(14f) }.thenBy { it.centerX() })

        if (sortedHandles.size < 2) return null

        val startHandle = sortedHandles.first()
        val endHandle = sortedHandles.last()

        // 물방울 핸들 꼭짓점(상단 중앙) 바로 위가 실제 선택된 텍스트 베이스라인
        val startX = startHandle.centerX().toFloat()
        val startY = (startHandle.top - dp(8f)).toFloat()
        val endX = endHandle.centerX().toFloat()
        val endY = (endHandle.top - dp(8f)).toFloat()

        val minY = min(startY, endY) - dp(24f)
        val maxY = max(startY, endY) + dp(20f)

        data class TextSegment(val rect: Rect, val text: String)
        val segments = mutableListOf<TextSegment>()

        val activeRoot = rootInActiveWindow ?: return null

        fun collectTextBetweenHandles(node: AccessibilityNodeInfo?) {
            if (node == null || !node.isVisibleToUser) return
            val raw = node.text?.toString()
            if (!raw.isNullOrEmpty() && raw !in listOf("복사", "모두 선택", "공유", "번역", "웹 검색", "소리 내 읽기", "Gemini에게 물어보기", "삼성 패스에 저장")) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (r.width() > dp(20f) && r.bottom >= minY && r.top <= maxY && r.height() < screenH * 0.85f) {
                    val sliced = sliceNodeTextByHandles(node, raw, r, startX, startY, endX, endY)
                    if (sliced.isNotBlank()) {
                        segments.add(TextSegment(r, sliced))
                    }
                }
            }
            for (i in 0 until node.childCount) {
                collectTextBetweenHandles(node.getChild(i))
            }
        }

        collectTextBetweenHandles(activeRoot)
        if (segments.isEmpty()) return null

        val ordered = segments.sortedWith(compareBy<TextSegment> { it.rect.top }.thenBy { it.rect.left })
        val finalString = ordered.joinToString("\n") { it.text }.trim()
        if (finalString.isEmpty()) return null

        val anchorRect = toolbarRect ?: Rect(
            min(startHandle.left, endHandle.left),
            min(startHandle.top, endHandle.top) - dp(40f),
            max(startHandle.right, endHandle.right),
            max(startHandle.bottom, endHandle.bottom)
        )
        return finalString to anchorRect
    }

    /**
     * Compose Text 노드의 글자별 실제 좌표(EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)를 조회해
     * 시작 핸들(startX, startY)과 끝 핸들(endX, endY) 사이의 글자만 정확히 슬라이싱
     */
    private fun sliceNodeTextByHandles(
        node: AccessibilityNodeInfo,
        rawText: String,
        nodeRect: Rect,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float
    ): String {
        val len = rawText.length.coerceAtMost(1500)
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0)
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, len)
        }

        val refreshed = runCatching {
            node.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, args)
        }.getOrDefault(false)

        if (refreshed) {
            @Suppress("DEPRECATION")
            val parcelables = node.extras?.getParcelableArray(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)
            if (parcelables != null && parcelables.isNotEmpty()) {
                val lineTol = dp(14f).toFloat()
                val sb = StringBuilder()
                for (i in 0 until min(len, parcelables.size)) {
                    val cr = parcelables[i] as? RectF ?: continue
                    val cy = cr.centerY()
                    val cx = cr.centerX()

                    if (cy < startY - lineTol || cy > endY + lineTol) continue

                    val onStartLine = abs(cy - startY) <= lineTol
                    val onEndLine = abs(cy - endY) <= lineTol

                    val keep = when {
                        onStartLine && onEndLine -> cx >= min(startX, endX) - dp(4f) && cx <= max(startX, endX) + dp(4f)
                        onStartLine -> cx >= startX - dp(4f)
                        onEndLine -> cx <= endX + dp(4f)
                        else -> cy > startY && cy < endY
                    }
                    if (keep) {
                        sb.append(rawText[i])
                    }
                }
                val exact = sb.toString().trim()
                if (exact.isNotEmpty()) return exact
            }
        }

        // 캐릭터 좌표 미지원 뷰에 대한 비례 슬라이싱 폴백
        val lineH = dp(22f).toFloat().coerceAtLeast(1f)
        val estLines = max(1, (nodeRect.height() / lineH).toInt())
        if (estLines == 1) {
            val w = nodeRect.width().toFloat().coerceAtLeast(1f)
            val sRatio = if (abs(nodeRect.centerY() - startY) <= lineH) ((startX - nodeRect.left) / w).coerceIn(0f, 1f) else 0f
            val eRatio = if (abs(nodeRect.centerY() - endY) <= lineH) ((endX - nodeRect.left) / w).coerceIn(0f, 1f) else 1f
            val sIdx = (sRatio * rawText.length).toInt().coerceIn(0, rawText.length)
            val eIdx = (eRatio * rawText.length).toInt().coerceIn(sIdx, rawText.length)
            return rawText.substring(sIdx, eIdx).trim()
        }
        return rawText
    }

    /**
     * [검은색 팝업창 실시간 텍스트 표시 및 업데이트]
     * 오직 사용자가 실시간 드래그로 선택한 텍스트만 표시하며, 이미 창이 떠 있으면 깜빡임 없이 내부 텍스트만 즉시 갱신
     */
    fun showOrUpdateRealtimeTextPill(selectedText: String, posX: Int, posY: Int) {
        val cleanText = selectedText.trim()
        if (cleanText.isEmpty()) return

        mainHandler.post {
            val payload = DragPayload(
                type = PayloadType.TEXT,
                text = cleanText,
                displayName = cleanText
            )
            currentPayload = payload

            val previewSingleLine = cleanText.replace(Regex("\\s+"), " ")
            val badgeText = "⠿ 실시간 선택 (${cleanText.length}자) • 잡고 이동"

            // 이미 텍스트용 검은색 팝업이 떠 있다면 텍스트만 실시간 업데이트
            if (activeOverlayView != null && activePillTitleView != null && activePillBodyView != null) {
                activePillTitleView?.text = badgeText
                activePillBodyView?.text = previewSingleLine
                mainHandler.removeCallbacks(autoDismissRunnable)
                mainHandler.postDelayed(autoDismissRunnable, 6500)
                return@post
            }

            showQuickDragPill(payload, posX, posY)
        }
    }

    /**
     * [삼성 갤러리 꾹 누른 정확한 단일 미디어 추출]
     * 갤러리 진입 시 자동 팝업을 완전히 배제하고, 사용자가 꾹 눌러서 '선택됨(Checked)'이 된 바로 그 썸네일만 추출
     */
    private fun handleGalleryExactLongPress(initialLongPressRect: Rect) {
        val root = rootInActiveWindow ?: return
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels

        data class GalleryCell(
            val node: AccessibilityNodeInfo,
            val rect: Rect,
            val desc: String,
            val isChecked: Boolean
        )

        val cells = mutableListOf<GalleryCell>()

        fun collectGalleryCells(node: AccessibilityNodeInfo?) {
            if (node == null || !node.isVisibleToUser) return
            val r = Rect()
            node.getBoundsInScreen(r)

            val isThumbSize = r.width() in dp(45f)..(screenW * 0.65f).toInt() &&
                              r.height() in dp(45f)..(screenH * 0.55f).toInt()

            val ownDesc = buildString {
                node.contentDescription?.let { append(it).append(" ") }
                node.stateDescription?.let { append(it).append(" ") }
                node.text?.let { append(it).append(" ") }
            }.trim()

            var childChecked = false
            val childDescBuilder = StringBuilder(ownDesc)
            for (i in 0 until node.childCount) {
                val c = node.getChild(i) ?: continue
                if (c.isChecked || c.isSelected) childChecked = true
                c.contentDescription?.let { childDescBuilder.append(" ").append(it) }
                c.stateDescription?.let { childDescBuilder.append(" ").append(it) }
            }
            val fullCellDesc = childDescBuilder.toString().trim()

            val hasMediaDateOrHint = fullCellDesc.contains("년") ||
                    fullCellDesc.contains("월") ||
                    fullCellDesc.contains("사진") ||
                    fullCellDesc.contains("동영상") ||
                    fullCellDesc.contains("GIF", true) ||
                    fullCellDesc.contains("photo", true) ||
                    fullCellDesc.contains("video", true)

            val checked = node.isChecked || node.isSelected || childChecked ||
                    fullCellDesc.contains("선택됨") || fullCellDesc.contains("Selected", true)

            if (isThumbSize && (hasMediaDateOrHint || checked)) {
                cells.add(GalleryCell(node, r, fullCellDesc, checked))
                return
            }

            for (i in 0 until node.childCount) {
                collectGalleryCells(node.getChild(i))
            }
        }

        collectGalleryCells(root)

        val initialWasSingleThumb = initialLongPressRect.width() in dp(45f)..(screenW * 0.65f).toInt() &&
                                    initialLongPressRect.height() in dp(45f)..(screenH * 0.55f).toInt()

        // 1순위: 방금 꾹 눌러서 '선택됨(isChecked)' 상태가 된 썸네일 셀
        val targetCell: GalleryCell? = when {
            cells.any { it.isChecked } -> {
                val checkedList = cells.filter { it.isChecked }
                if (initialWasSingleThumb) {
                    checkedList.minByOrNull {
                        hypot(
                            (it.rect.centerX() - initialLongPressRect.centerX()).toDouble(),
                            (it.rect.centerY() - initialLongPressRect.centerY()).toDouble()
                        )
                    }
                } else {
                    checkedList.first()
                }
            }
            initialWasSingleThumb -> {
                cells.minByOrNull {
                    hypot(
                        (it.rect.centerX() - initialLongPressRect.centerX()).toDouble(),
                        (it.rect.centerY() - initialLongPressRect.centerY()).toDouble()
                    )
                }
            }
            else -> null
        }

        val targetRect = targetCell?.rect ?: initialLongPressRect
        val targetDesc = targetCell?.desc ?: ""

        if (targetRect.width() < dp(40f) || targetRect.height() < dp(40f)) return

        // 화면 내 동일한 날짜/분 텍스트를 가진 썸네일들 중 현재 선택한 셀이 몇 번째인지(그리드 순서) 계산
        val orderedCells = cells.sortedWith(compareBy<GalleryCell> { it.rect.top / dp(24f) }.thenBy { it.rect.left })
        val targetDateRange = parseGalleryDateRange(targetDesc)
        val sameTimestampCells = if (targetDateRange != null) {
            orderedCells.filter {
                val r = parseGalleryDateRange(it.desc)
                r != null && r.first == targetDateRange.first && r.second == targetDateRange.second
            }
        } else {
            emptyList()
        }
        val indexInSameTimestamp = sameTimestampCells.indexOfFirst { it.rect == targetRect }.coerceAtLeast(0)

        // 그리드가 최신순(내림차순)인지 과거순(오름차순)인지 판별
        val firstCellRange = orderedCells.firstOrNull()?.let { parseGalleryDateRange(it.desc) }
        val lastCellRange = orderedCells.lastOrNull()?.let { parseGalleryDateRange(it.desc) }
        val isGridAscendingOrder = if (firstCellRange != null && lastCellRange != null && firstCellRange.first != lastCellRange.first) {
            firstCellRange.first < lastCellRange.first
        } else {
            false
        }

        val cx = targetRect.centerX()
        val cy = targetRect.centerY()

        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                ioExecutor.execute {
                    var targetCrop: Bitmap? = null
                    runCatching {
                        val hwBuffer = screenshot.hardwareBuffer
                        val hwBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, screenshot.colorSpace)
                        val swBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                        hwBuffer.close()

                        if (swBitmap != null && targetRect.width() > dp(32f) && targetRect.height() > dp(32f)) {
                            val left = targetRect.left.coerceIn(0, swBitmap.width - 1)
                            val top = targetRect.top.coerceIn(0, swBitmap.height - 1)
                            val width = targetRect.width().coerceIn(1, swBitmap.width - left)
                            val height = targetRect.height().coerceIn(1, swBitmap.height - top)
                            targetCrop = Bitmap.createBitmap(swBitmap, left, top, width, height)
                        }
                    }

                    val matched = resolveExactGalleryMedia(
                        nodeDesc = targetDesc,
                        screenCrop = targetCrop,
                        sameTimeIndex = indexInSameTimestamp,
                        sameTimeTotal = sameTimestampCells.size,
                        isGridAscending = isGridAscendingOrder
                    )

                    if (matched != null) {
                        mainHandler.post { showQuickDragPill(matched, cx, cy) }
                    } else {
                        toast("원본을 확실히 찾지 못했어요. 공유 → ⚡ 원본 드래그를 써주세요")
                    }
                }
            }

            override fun onFailure(errorCode: Int) {
                ioExecutor.execute {
                    val matched = resolveExactGalleryMedia(
                        nodeDesc = targetDesc,
                        screenCrop = null,
                        sameTimeIndex = indexInSameTimestamp,
                        sameTimeTotal = sameTimestampCells.size,
                        isGridAscending = isGridAscendingOrder
                    )
                    if (matched != null) {
                        mainHandler.post { showQuickDragPill(matched, cx, cy) }
                    } else {
                        toast("원본을 확실히 찾지 못했어요. 공유 → ⚡ 원본 드래그를 써주세요")
                    }
                }
            }
        })
    }

    /**
     * [갤러리 원본 미디어 정밀 매칭 엔진]
     * 1) 사진 vs 동영상 엄격 분리
     * 2) 갤러리 선택 음영(Dimming)에 불변하는 ZNCC(영평균 정규화 교차상관) 시각 비교
     * 3) 동일 시간대(분) 촬영본의 그리드 순서 매칭
     * 확신이 없으면 null을 반환해 엉뚱한 사진을 띄우지 않음
     */
    private fun resolveExactGalleryMedia(
        nodeDesc: String,
        screenCrop: Bitmap?,
        sameTimeIndex: Int,
        sameTimeTotal: Int,
        isGridAscending: Boolean
    ): DragPayload? {
        val isVideoStrict = nodeDesc.contains("동영상") ||
                nodeDesc.contains("video", ignoreCase = true) ||
                Regex("\\d+분\\s*\\d+초|\\d+초").containsMatchIn(nodeDesc)

        val isImageStrict = !isVideoStrict && (
                nodeDesc.contains("사진") ||
                nodeDesc.contains("이미지") ||
                nodeDesc.contains("GIF", ignoreCase = true) ||
                nodeDesc.contains("photo", ignoreCase = true) ||
                nodeDesc.contains("image", ignoreCase = true)
        )

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
            val timestampMs: Long,
            val uri: Uri
        )

        fun queryCandidates(extraWhere: String?, extraArgs: Array<String>?, limit: Int): List<MediaCandidate> {
            val list = mutableListOf<MediaCandidate>()
            val mediaFilter = when {
                isVideoStrict -> "${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}"
                isImageStrict -> "${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}"
                else -> "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
            }

            var sel = "$mediaFilter AND " +
                    "${MediaStore.MediaColumns.DISPLAY_NAME} NOT LIKE 'OrigDrag_%' AND " +
                    "${MediaStore.MediaColumns.DISPLAY_NAME} NOT LIKE 'DragImage_%' AND " +
                    "${MediaStore.MediaColumns.DISPLAY_NAME} NOT LIKE 'GalleryDrag_%' AND " +
                    "${MediaStore.MediaColumns.DISPLAY_NAME} NOT LIKE 'Original_%'"

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
                        if (relPath.contains("UniversalDrag", ignoreCase = true)) continue

                        val id = cursor.getLong(0)
                        val name = cursor.getString(1) ?: "media"
                        val mime = cursor.getString(2) ?: "image/jpeg"
                        val w = cursor.getInt(3)
                        val h = cursor.getInt(4)
                        val mediaType = cursor.getInt(5)
                        val isVid = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO || mime.startsWith("video/")
                        val dateTaken = cursor.getLong(6)
                        val dateModMs = cursor.getLong(7) * 1000L
                        val effectiveTs = if (dateTaken > 0L) dateTaken else dateModMs

                        val contentUri = ContentUris.withAppendedId(
                            if (isVid) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            id
                        )
                        list.add(MediaCandidate(id, name, mime, w, h, isVid, effectiveTs, contentUri))
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
                45
            )
        } else {
            emptyList()
        }

        if (dateCandidates.size == 1) {
            val c = dateCandidates.first()
            return candidateToPayload(c.uri, c.name, c.mime, c.width, c.height, c.isVideo)
        }

        val pool = if (dateCandidates.isNotEmpty()) {
            if (isGridAscending) dateCandidates.sortedBy { it.timestampMs } else dateCandidates.sortedByDescending { it.timestampMs }
        } else {
            queryCandidates(null, null, 60)
        }

        if (pool.isEmpty()) return null

        // 음영 변화에 강건한 영평균 정규화 교차상관(ZNCC) 시각 매칭
        if (screenCrop != null) {
            val targetVec = extractNormalizedVisualVector(screenCrop)
            if (targetVec != null) {
                var bestCand: MediaCandidate? = null
                var bestScore = -2.0

                for ((idx, cand) in pool.withIndex()) {
                    runCatching {
                        val thumb = loadSquareCandidateThumbnail(cand.uri, cand.isVideo)
                        if (thumb != null) {
                            val candVec = extractNormalizedVisualVector(thumb)
                            if (candVec != null) {
                                var score = computeCosineSimilarity(targetVec, candVec)
                                // 동일 시간대 그리드 순서가 일치하는 후보에 가산점 부여
                                if (sameTimeTotal > 1 && idx == sameTimeIndex) {
                                    score += 0.08
                                }
                                if (score > bestScore) {
                                    bestScore = score
                                    bestCand = cand
                                }
                            }
                        }
                    }
                }

                // ponytail: calibration knob - ZNCC below 0.80 means "looks different"; show nothing rather than a wrong photo
                if (bestCand != null && bestScore >= 0.80) {
                    val c = bestCand!!
                    return candidateToPayload(c.uri, c.name, c.mime, c.width, c.height, c.isVideo)
                }
                return null
            }
        }

        // 스크린샷을 사용할 수 없는 경우: 같은 시각 촬영본 목록이 화면 그리드와 정확히 대응할 때만 순서로 선택
        if (dateCandidates.isNotEmpty() && sameTimeTotal == pool.size) {
            val chosen = pool[sameTimeIndex.coerceIn(0, pool.size - 1)]
            return candidateToPayload(chosen.uri, chosen.name, chosen.mime, chosen.width, chosen.height, chosen.isVideo)
        }
        return null
    }

    /**
     * 원본 미디어에서 갤러리 그리드와 동일한 1:1 중앙 정방형 크롭(Center-Crop) 썸네일을 생성
     */
    private fun loadSquareCandidateThumbnail(uri: Uri, isVideo: Boolean): Bitmap? {
        val raw = runCatching {
            if (!isVideo) {
                decodeSampledPreview(uri)?.first
            } else {
                contentResolver.loadThumbnail(uri, Size(160, 160), null)
            }
        }.getOrNull() ?: runCatching {
            contentResolver.loadThumbnail(uri, Size(160, 160), null)
        }.getOrNull() ?: return null

        val side = min(raw.width, raw.height)
        if (side <= 4) return null
        val x = (raw.width - side) / 2
        val y = (raw.height - side) / 2
        return Bitmap.createBitmap(raw, x, y, side, side)
    }

    /**
     * 삼성 갤러리의 좌상단 체크박스 아이콘과 외곽 라운딩을 피하기 위해
     * 정방형 이미지의 안쪽 [26%..82%] 영역만 추출한 뒤 영평균·단위분산 정규화(Zero-Mean Unit-Variance) 벡터를 반환.
     * 선택 시 썸네일이 어두워지는(Dimming) 효과가 완전히 상쇄됨.
     */
    private fun extractNormalizedVisualVector(bmp: Bitmap): DoubleArray? {
        val side = min(bmp.width, bmp.height)
        if (side < 16) return null
        val sqX = (bmp.width - side) / 2
        val sqY = (bmp.height - side) / 2

        val innerStart = (side * 0.26f).toInt()
        val innerSize = (side * 0.56f).toInt().coerceAtLeast(8)

        val cropX = (sqX + innerStart).coerceIn(0, bmp.width - innerSize)
        val cropY = (sqY + innerStart).coerceIn(0, bmp.height - innerSize)

        val sub = Bitmap.createBitmap(bmp, cropX, cropY, innerSize, innerSize)
        val grid = 14
        val scaled = Bitmap.createScaledBitmap(sub, grid, grid, true)
        val pixels = IntArray(grid * grid)
        scaled.getPixels(pixels, 0, grid, 0, 0, grid, grid)

        val vec = DoubleArray(grid * grid * 3)
        var sum = 0.0
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = Color.red(c).toDouble()
            val g = Color.green(c).toDouble()
            val b = Color.blue(c).toDouble()
            vec[i * 3] = r
            vec[i * 3 + 1] = g
            vec[i * 3 + 2] = b
            sum += r + g + b
        }

        val mean = sum / vec.size
        var sqSum = 0.0
        for (i in vec.indices) {
            vec[i] -= mean
            sqSum += vec[i] * vec[i]
        }
        val norm = sqrt(sqSum)
        if (norm < 1e-5) return null
        for (i in vec.indices) {
            vec[i] /= norm
        }
        return vec
    }

    private fun computeCosineSimilarity(v1: DoubleArray, v2: DoubleArray): Double {
        var dot = 0.0
        for (i in v1.indices) {
            dot += v1[i] * v2[i]
        }
        return dot
    }

    private fun parseGalleryDateRange(desc: String): Pair<Long, Long>? {
        val dateRegex = Regex("(\\d{4})(?:년|\\.|-)\\s*(\\d{1,2})(?:월|\\.|-)\\s*(\\d{1,2})일?")
        val match = dateRegex.find(desc) ?: return null
        val year = match.groupValues[1].toIntOrNull() ?: return null
        val month = (match.groupValues[2].toIntOrNull() ?: return null) - 1
        val day = match.groupValues[3].toIntOrNull() ?: return null

        val afterDate = desc.substring(match.range.last)
        val timeRegex = Regex("(오전|오후|AM|PM)?\\s*(\\d{1,2})(?:시|:)\\s*(\\d{1,2})분?")
        val timeMatch = timeRegex.find(afterDate)

        val cal = Calendar.getInstance()
        if (timeMatch != null) {
            val ampm = timeMatch.groupValues[1].uppercase()
            var hour = timeMatch.groupValues[2].toIntOrNull() ?: 0
            val minute = timeMatch.groupValues[3].toIntOrNull() ?: 0
            if ((ampm == "오후" || ampm == "PM") && hour < 12) hour += 12
            if ((ampm == "오전" || ampm == "AM") && hour == 12) hour = 0

            cal.set(year, month, day, hour, minute, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return (cal.timeInMillis - 90_000L) to (cal.timeInMillis + 120_000L)
        } else {
            cal.set(year, month, day, 0, 0, 0)
            cal.set(Calendar.MILLISECOND, 0)
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

    /**
     * [검은색 팝업창 UI]
     * - 텍스트일 때: 실시간 드래그한 실제 텍스트 본문을 그대로 표시
     * - 이미지/영상일 때: 선택한 원본 썸네일과 해상도 표시
     * - FLAG_NOT_FOCUSABLE을 유지하여 카톡 등 채팅창 키보드 입력을 절대 방해하지 않음
     */
    fun showQuickDragPill(payload: DragPayload, posX: Int, posY: Int) {
        mainHandler.post {
            removeActiveOverlay()
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
                    cornerRadius = dp(18f).toFloat()
                    setColor(Color.parseColor("#F418181B"))
                    setStroke(dp(1.8f), Color.parseColor("#818CF8"))
                }
                elevation = dp(12f).toFloat()
            }

            if (payload.previewBitmap != null && !payload.previewBitmap.isRecycled) {
                val bw = payload.previewBitmap.width.toFloat()
                val bh = payload.previewBitmap.height.toFloat()
                val maxSide = dp(68f).toFloat()
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

            val isText = payload.type == PayloadType.TEXT
            val actualText = payload.text?.trim() ?: ""

            val typeTitle = when (payload.type) {
                PayloadType.TEXT -> "⠿ 실시간 선택 (${actualText.length}자) • 잡고 이동"
                PayloadType.IMAGE -> "🖼️ 이미지 ($resInfo)"
                PayloadType.VIDEO -> "🎬 동영상 ($resInfo)"
                PayloadType.FILE -> "📁 ${payload.displayName}"
            }

            val infoCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    dp(205f),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val titleTv = TextView(this).apply {
                text = typeTitle
                setTextColor(Color.parseColor("#A5B4FC"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }

            val bodyTv = TextView(this).apply {
                text = if (isText) {
                    actualText.replace(Regex("\\s+"), " ")
                } else {
                    "${payload.displayName} • 잡고 다른 앱으로 이동"
                }
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }

            if (isText) {
                activePillTitleView = titleTv
                activePillBodyView = bodyTv
            }

            infoCol.addView(titleTv)
            infoCol.addView(bodyTv)
            card.addView(infoCol)

            val closeBtn = TextView(this).apply {
                text = "✕"
                setTextColor(Color.parseColor("#A1A1AA"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(10f), dp(4f), dp(4f), dp(4f))
                setOnClickListener { removeActiveOverlay() }
            }
            card.addView(closeBtn)

            container.addView(card)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (posX - dp(130f)).coerceIn(dp(12f), screenW - dp(270f))
                y = (posY - dp(88f)).coerceIn(dp(48f), screenH - dp(180f))
            }

            var downX = 0f
            var downY = 0f

            card.setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.rawX
                        downY = ev.rawY
                        lastDropX = ev.rawX
                        lastDropY = ev.rawY
                        mainHandler.removeCallbacks(autoDismissRunnable)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val activePayload = currentPayload ?: payload
                        if (!isGlobalDragging && hypot((ev.rawX - downX).toDouble(), (ev.rawY - downY).toDouble()) > dp(6f)) {
                            startGlobalDragSession(container, activePayload)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!isGlobalDragging) {
                            mainHandler.postDelayed(autoDismissRunnable, 5000)
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
            mainHandler.postDelayed(autoDismissRunnable, 6500)
        }
    }

    private fun startGlobalDragSession(container: View, payload: DragPayload) {
        if (isGlobalDragging) return
        isGlobalDragging = true
        currentPayload = payload
        forceDropHandledByTracker = false
        preDropFocusedText = getFocusedEditableText()

        createDragTrackerIfNeeded()

        val clipData: ClipData
        var dragFlags = View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_OPAQUE

        if (payload.type == PayloadType.TEXT) {
            val txt = payload.text ?: ""
            clipData = ClipData.newPlainText("UniversalDragText", txt)
        } else {
            val uri = payload.uri ?: run {
                isGlobalDragging = false
                removeDragTracker()
                return
            }
            val desc = ClipDescription(payload.displayName, arrayOf(payload.mimeType))
            clipData = ClipData(desc, ClipData.Item(uri))
            dragFlags = dragFlags or View.DRAG_FLAG_GLOBAL_URI_READ
        }

        val badge = when (payload.type) {
            PayloadType.TEXT -> "TEXT (${(payload.text ?: "").length}자)"
            PayloadType.IMAGE -> "IMAGE ${payload.origWidth}×${payload.origHeight}"
            PayloadType.VIDEO -> "VIDEO ${payload.origWidth}×${payload.origHeight}"
            PayloadType.FILE -> "FILE"
        }

        val shadowBuilder = UniversalDragShadowBuilder(
            context = this,
            titleBadge = badge,
            subtitleText = payload.text ?: payload.displayName,
            previewBitmap = payload.previewBitmap
        )

        val started = container.startDragAndDrop(clipData, shadowBuilder, null, dragFlags)
        if (started) {
            container.alpha = 0f
            val lp = container.layoutParams as WindowManager.LayoutParams
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            runCatching { windowManager.updateViewLayout(container, lp) }
            mainHandler.postDelayed(sampleDragLocationRunnable, 60)
        } else {
            isGlobalDragging = false
            removeDragTracker()
        }
    }

    private fun attachDragEndListener(container: View) {
        container.setOnDragListener { _, event ->
            if (event.action == DragEvent.ACTION_DRAG_ENDED) {
                isGlobalDragging = false
                mainHandler.removeCallbacks(sampleDragLocationRunnable)
                removeDragTracker()

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

    /**
     * 드래그 중에만 일시적으로 생성되는 좌표 추적기 (평상시에는 윈도우 자체가 없음)
     */
    private fun createDragTrackerIfNeeded() {
        if (dragTrackerView != null) return
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
        runCatching { windowManager.addView(tracker, params) }
    }

    private fun removeDragTracker() {
        dragTrackerView?.let {
            runCatching { windowManager.removeView(it) }
        }
        dragTrackerView = null
        trackerParams = null
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
        activeOverlayView?.let {
            runCatching { windowManager.removeView(it) }
        }
        activeOverlayView = null
        activeOverlayParams = null
        activePillTitleView = null
        activePillBodyView = null
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
