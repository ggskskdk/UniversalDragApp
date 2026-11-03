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
import java.io.File
import java.io.InputStream
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
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

    data class ResolvedEntry(
        val key: String,
        val uri: Uri,
        val mimeType: String,
        val displayName: String,
        val sizeText: String = "",
        val width: Int = 0,
        val height: Int = 0,
        val previewBitmap: Bitmap? = null,
        val isVideo: Boolean = false,
        val isImage: Boolean = false
    )

    data class DragPayload(
        val type: PayloadType,
        val text: String? = null,
        val uri: Uri? = null,
        val additionalUris: List<Uri> = emptyList(),
        val allMimeTypes: List<String> = listOf("text/plain"),
        val mimeType: String = "text/plain",
        val displayName: String = "",
        val subtitleSummary: String = "",
        val origWidth: Int = 0,
        val origHeight: Int = 0,
        val previewBitmap: Bitmap? = null,
        val totalCount: Int = 1,
        val copyButtonBounds: Rect? = null
    )

    private lateinit var windowManager: WindowManager
    private lateinit var clipboardManager: ClipboardManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newCachedThreadPool()

    private var activeOverlayView: View? = null
    private var activeOverlayParams: WindowManager.LayoutParams? = null
    private var activePillTitleView: TextView? = null
    private var activePillBodyView: TextView? = null
    private var activePillThumbView: ImageView? = null
    private var activePillBadgeView: TextView? = null

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
    private var lastSelectionBounds: Rect? = null

    // [v10.0 핵심] 내 파일 & 갤러리 다중 선택 세션 누적 캐시 (스크롤 및 체크 추가/해제 실시간 동기화)
    private val selectedMyFilesSessionMap = LinkedHashMap<String, ResolvedEntry>()
    private val visibleCheckedMyFilesKeys = mutableSetOf<String>()

    private val selectedGallerySessionMap = LinkedHashMap<String, ResolvedEntry>()
    private val visibleCheckedGalleryKeys = mutableSetOf<String>()

    private var wasGalleryContextMenuVisible = false
    private var lastGalleryInteractedRect = Rect()
    private var lastGalleryInteractedDesc = ""

    private val isVisualSliceRunning = AtomicBoolean(false)
    private var lastVisualSliceTimestamp: Long = 0L

    // ponytail: content-changed fires many times a second (typing, scrolling, video). Every scan walks whole
    // window trees (and may screenshot), which makes the foreground app lag, so non-long-press scans run once
    // the screen has been still for SCAN_DEBOUNCE_MS. Long-presses are handled immediately.
    private val SCAN_DEBOUNCE_MS = 150L
    private var pendingScan: Runnable? = null

    private val autoDismissRunnable = Runnable { removeActiveOverlay() }

    private val launcherPackages = mutableSetOf(
        "com.sec.android.app.launcher",
        "com.google.android.apps.nexuslauncher",
        "com.teslacoilsw.launcher",
        "com.microsoft.launcher",
        "com.android.systemui"
    )

    // 드래그 중 캡슐이 가장자리에 머물면: 좌/우 = 최근 앱, 위 = 분할 화면
    private var edgeZone = 0
    private var edgeSince = 0L
    private var edgeFired = false
    private val EDGE_DWELL_MS = 650L // ponytail: calibration knob - shorter fires by accident, longer feels laggy

    private fun checkDragEdgeGesture() {
        val w = resources.displayMetrics.widthPixels
        val x = lastDropX
        val y = lastDropY
        val zone = when {
            x <= 0f && y <= 0f -> 0
            y < dp(36f) -> 3
            x < dp(22f) -> 1
            x > w - dp(22f) -> 2
            else -> 0
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (zone != edgeZone) {
            edgeZone = zone; edgeSince = now; edgeFired = false
            return
        }
        if (zone == 0 || edgeFired || now - edgeSince < EDGE_DWELL_MS) return
        edgeFired = true
        val ok = performGlobalAction(
            if (zone == 3) GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN else GLOBAL_ACTION_RECENTS
        )
        if (ok) toast(if (zone == 3) "분할 화면" else "최근 앱")
    }

    private val sampleDragLocationRunnable = object : Runnable {
        override fun run() {
            if (!isGlobalDragging) return
            checkDragEdgeGesture()
            setTrackerTouchable(true)
            mainHandler.postDelayed({
                if (isGlobalDragging) setTrackerTouchable(false)
            }, 10)
            mainHandler.postDelayed(this, 110)
        }
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

    private fun isMyFilesApp(pkg: String): Boolean {
        val lower = pkg.lowercase()
        return lower == "com.sec.android.app.myfiles" ||
               lower.contains("myfiles") ||
               lower.contains("documentsui") ||
               lower.contains("filemanager")
    }

    /**
     * [v11.0] 분할 화면이나 팝업 메뉴가 뜬 상태에서도 해당 앱의 모든 윈도우 루트를 빠짐없이 수집
     */
    private fun collectAppRoots(predicate: (String) -> Boolean): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        runCatching {
            for (w in windows) {
                val r = w.root ?: continue
                if (predicate(r.packageName?.toString() ?: "")) roots.add(r)
            }
        }
        val active = rootInActiveWindow
        if (active != null && predicate(active.packageName?.toString() ?: "") && roots.none { it == active }) {
            roots.add(active)
        }
        return roots
    }

    private fun toast(msg: String) = mainHandler.post {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun scheduleScan(block: () -> Unit) {
        pendingScan?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable { block() }
        pendingScan = r
        mainHandler.postDelayed(r, SCAN_DEBOUNCE_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || isGlobalDragging) return
        val pkgName = event.packageName?.toString() ?: rootInActiveWindow?.packageName?.toString() ?: ""
        if (isHomeLauncher(pkgName)) return

        // [A] 내 파일 앱 (com.sec.android.app.myfiles) 다중 선택 실시간 동기화
        if (isMyFilesApp(pkgName)) {
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                    val srcRect = Rect()
                    event.source?.getBoundsInScreen(srcRect)
                    mainHandler.postDelayed({ syncMyFilesMultiSelection(srcRect, true) }, 65L)
                }
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_VIEW_SELECTED -> {
                    scheduleScan { syncMyFilesMultiSelection(Rect(), false) }
                }
            }
            return
        }

        // [B] 갤러리 앱 (com.sec.android.gallery3d) 다중 선택 실시간 동기화
        if (isGalleryApp(pkgName)) {
            if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                galleryEventLog.addLast(AccessibilityEvent.eventTypeToString(event.eventType).removePrefix("TYPE_"))
                while (galleryEventLog.size > 30) galleryEventLog.removeFirst()
            }
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                    rememberGalleryInteraction(event.source)
                    val srcRect = Rect(lastGalleryInteractedRect)
                    mainHandler.postDelayed({ syncGalleryMultiSelection(srcRect, true) }, 45L)
                }
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    rememberGalleryInteraction(event.source)
                    scheduleScan { syncGalleryMultiSelection(Rect(lastGalleryInteractedRect), false) }
                }
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_SELECTED,
                AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                    scheduleScan { syncGalleryMultiSelection(Rect(lastGalleryInteractedRect), false) }
                }
            }
            return
        }

        // [C] 브라우저(삼성 인터넷, Browser.apk), 챗GPT, 제미나이 등 텍스트 선택 처리
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val extracted = extractSelectedTextFromSelectionEvent(event)
                if (extracted != null && extracted.first.isNotEmpty()) {
                    val (selText, bounds) = extracted
                    lastRealtimeSelectedText = selText
                    lastRealtimeTextTimestamp = System.currentTimeMillis()
                    lastSelectionBounds = bounds

                    // [v11.4] 사용자가 실제로 선택했을 때만 뜨는 복사/전체선택 툴바가 있어야 표시 (앱이 내부적으로 보내는 가짜 선택 이벤트 무시)
                    val toolbarInfo = findFloatingSelectionToolbar()
                    if (toolbarInfo != null) {
                        showOrUpdateRealtimeTextPill(selText, toolbarInfo.first, toolbarInfo.second)
                    } else {
                        scheduleScan { handleWindowContentChangedForTextSelection(System.currentTimeMillis()) }
                    }
                } else {
                    val src = event.source
                    if (src != null && src.textSelectionStart > 0 && src.textSelectionStart == src.textSelectionEnd) {
                        if (currentPayload?.type == PayloadType.TEXT && findFloatingSelectionToolbar() == null) {
                            removeActiveOverlay()
                        }
                    }
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                scheduleScan { handleWindowContentChangedForTextSelection(System.currentTimeMillis()) }
            }
        }
    }

    // ponytail: only long-press/tap sources are remembered; content-change sources are whole grids and would
    // point the "closest cell" lookup at the middle of the screen
    // ponytail: debug dump so a failing gallery screen can be diagnosed from one file; remove once gallery is stable
    private val galleryEventLog = ArrayDeque<String>()
    private var lastGalleryDumpAt = 0L
    private fun dumpGalleryDebug(roots: List<AccessibilityNodeInfo>, reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastGalleryDumpAt < 8000L) return
        lastGalleryDumpAt = now
        val sb = StringBuilder("reason: $reason\nevents: ${galleryEventLog.joinToString()}\n\n")
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 40) return
            val r = Rect(); n.getBoundsInScreen(r)
            sb.append("  ".repeat(d)).append(n.className?.toString()?.substringAfterLast('.'))
                .append(" ").append(r.toShortString())
                .append(" t=").append(n.text?.toString()?.take(40))
                .append(" d=").append(n.contentDescription?.toString()?.take(80))
                .append(" s=").append(n.stateDescription)
                .append(if (n.isCheckable) " checkable" else "").append(if (n.isChecked) " CHECKED" else "")
                .append(if (n.isSelected) " SELECTED" else "").append(if (n.isLongClickable) " long" else "")
                .append(" id=").append(n.viewIdResourceName?.substringAfterLast('/')).append('\n')
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        roots.forEach { walk(it, 0); sb.append("\n----\n") }
        ioExecutor.execute {
            runCatching {
                val f = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "UniversalDrag_gallery_debug.txt")
                f.writeText(sb.toString())
                toast("갤러리 인식 실패 기록: Download/UniversalDrag_gallery_debug.txt 를 보내주세요")
            }
        }
    }

    private fun rememberGalleryInteraction(src: AccessibilityNodeInfo?) {
        if (src == null) return
        val r = Rect()
        src.getBoundsInScreen(r)
        if (r.width() < dp(32f) || r.height() < dp(32f)) return
        lastGalleryInteractedRect.set(r)
        val d = buildString {
            src.contentDescription?.let { append(it).append(" ") }
            src.text?.let { append(it).append(" ") }
            src.stateDescription?.let { append(it) }
        }.trim()
        if (d.isNotEmpty() && !d.contains("복사") && !d.contains("삭제") && !d.contains("공유")) {
            lastGalleryInteractedDesc = d
        }
    }

    override fun onInterrupt() {
        removeActiveOverlay()
        removeDragTracker()
    }

    override fun onDestroy() {
        instance = null
        pendingScan?.let { mainHandler.removeCallbacks(it) }
        removeActiveOverlay()
        removeDragTracker()
        super.onDestroy()
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    /**
     * [내 파일 다중 선택 실시간 동기화 엔진]
     * - 1개 체크 -> 2개 체크 -> 3개 체크 또는 체크 해제 시 개수를 즉시 반영하고 무깜빡임으로 팝업 갱신
     * - 상단 "N개 선택됨" 모드가 유지되는 동안 스크롤되어 지나간 파일도 세션 캐시에 보존
     * - 모든 체크가 해제되면 팝업 자동 닫힘
     */
    // [v11.1] 갤러리 목록 보기(파일명 행)도 같은 로직으로 처리. 파일명 행이 없으면 false (갤러리 썸네일 로직으로 넘어감)
    private fun syncMyFilesMultiSelection(
        initialRect: Rect,
        isExplicitLongPress: Boolean,
        appPredicate: (String) -> Boolean = ::isMyFilesApp,
        fileNameRowsOnly: Boolean = false
    ): Boolean {
        if (isGlobalDragging) return true
        val roots = collectAppRoots(appPredicate)
        if (roots.isEmpty()) return false
        val screenH = resources.displayMetrics.heightPixels

        data class VisibleFileRow(
            val fileName: String,
            val fileSizeText: String,
            val rect: Rect,
            val isChecked: Boolean
        )

        val visibleRows = mutableListOf<VisibleFileRow>()
        var headerSelectedCount: Int? = null
        val headerCountRegex = Regex("(\\d+)\\s*개\\s*선택")
        val fileNameRegex = Regex(".+\\.[a-zA-Z0-9]{1,7}$")

        fun scanMyFiles(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 35) return
            val nodeText = node.text?.toString()?.trim() ?: ""
            val nodeDesc = node.contentDescription?.toString()?.trim() ?: ""

            val hr = Rect()
            node.getBoundsInScreen(hr)
            if (hr.bottom < dp(220f)) {
                val match = headerCountRegex.find(nodeText) ?: headerCountRegex.find(nodeDesc)
                if (match != null) {
                    headerSelectedCount = match.groupValues[1].toIntOrNull()
                }
            }

            val isRowBounds = hr.width() > dp(180f) && hr.height() in dp(40f)..dp(170f) &&
                              hr.top in (if (fileNameRowsOnly) 0 else dp(45f))..(screenH - dp(20f))

            if (isRowBounds && node.childCount >= 1) {
                var rowChecked = node.isChecked || node.isSelected ||
                        (node.stateDescription?.toString()?.contains("선택됨") == true)
                var hasCheckboxChild = false
                val rowTexts = mutableListOf<String>()

                fun inspectRowChildren(c: AccessibilityNodeInfo?, d: Int) {
                    if (c == null || d > 8) return
                    val cls = c.className?.toString() ?: ""
                    val st = c.stateDescription?.toString() ?: ""
                    val cd = c.contentDescription?.toString() ?: ""
                    if (cls.contains("CheckBox", true) || c.isCheckable ||
                        st.contains("선택") || cd.contains("선택")
                    ) {
                        hasCheckboxChild = true
                    }
                    if (c.isChecked || st.contains("선택됨") || cd.contains("선택됨") ||
                        st.equals("Selected", true)
                    ) {
                        rowChecked = true
                    }
                    val t = c.text?.toString()?.trim()
                    if (!t.isNullOrEmpty()) rowTexts.add(t)
                    for (i in 0 until c.childCount) inspectRowChildren(c.getChild(i), d + 1)
                }

                inspectRowChildren(node, 0)

                val candidateName = rowTexts.firstOrNull { fileNameRegex.matches(it) }
                    ?: if (fileNameRowsOnly) null else rowTexts.firstOrNull {
                        it.length >= 3 && !it.contains("월") && !it.endsWith("B") &&
                        !it.contains("선택") && !it.contains("오전") && !it.contains("오후")
                    }

                if (!candidateName.isNullOrEmpty() && (rowChecked || hasCheckboxChild)) {
                    val sizeTxt = rowTexts.firstOrNull {
                        it.endsWith("KB", true) || it.endsWith("MB", true) ||
                        it.endsWith("GB", true) || it.endsWith("B", true)
                    } ?: ""
                    visibleRows.add(VisibleFileRow(candidateName, sizeTxt, hr, rowChecked))
                    return
                }
            }

            for (i in 0 until node.childCount) {
                scanMyFiles(node.getChild(i), depth + 1)
            }
        }

        for (r in roots) scanMyFiles(r, 0)

        val distinctVisible = visibleRows.distinctBy { it.fileName }
        if (fileNameRowsOnly && distinctVisible.isEmpty()) {
            // 선택 모드가 끝나 체크박스가 사라진 경우: 이전 선택이 다음 꾹 누르기에 남지 않게 초기화
            if (selectedMyFilesSessionMap.isNotEmpty() || visibleCheckedMyFilesKeys.isNotEmpty()) {
                selectedMyFilesSessionMap.clear()
                visibleCheckedMyFilesKeys.clear()
                if (currentPayload?.type != PayloadType.TEXT) removeActiveOverlay()
            }
            return false
        }
        val currentlyCheckedVisible = distinctVisible.filter { it.isChecked }
        val currentlyUncheckedVisibleNames = distinctVisible.filter { !it.isChecked }.map { it.fileName }.toSet()

        // 화면에서 체크 해제된 항목은 세션 캐시에서 즉시 제거
        for (uncheckedName in currentlyUncheckedVisibleNames) {
            selectedMyFilesSessionMap.remove(uncheckedName)
        }

        // 선택 모드가 종료되었거나 체크된 항목이 0개면 세션 초기화 및 팝업 닫기
        if (currentlyCheckedVisible.isEmpty() && (headerSelectedCount == null || headerSelectedCount == 0)) {
            val hadSelection = selectedMyFilesSessionMap.isNotEmpty() || visibleCheckedMyFilesKeys.isNotEmpty()
            selectedMyFilesSessionMap.clear()
            visibleCheckedMyFilesKeys.clear()
            if (hadSelection && currentPayload?.type != PayloadType.TEXT) {
                removeActiveOverlay()
            }
            return true
        }

        val currentVisibleCheckedSet = currentlyCheckedVisible.map { it.fileName }.toSet()
        // 화면에 보이는 체크 항목이 헤더 개수만큼(또는 새 꾹 누르기로 1개) 이면 그게 전체 선택: 남은 이전 항목 제거
        val hc = headerSelectedCount
        if ((hc != null && currentVisibleCheckedSet.size >= hc) || (isExplicitLongPress && (hc ?: 0) <= 1)) {
            selectedMyFilesSessionMap.keys.retainAll(currentVisibleCheckedSet)
        }
        val hasChange = currentVisibleCheckedSet != visibleCheckedMyFilesKeys

        if (!isExplicitLongPress && !hasChange && activeOverlayView != null) {
            return true
        }
        visibleCheckedMyFilesKeys.clear()
        visibleCheckedMyFilesKeys.addAll(currentVisibleCheckedSet)

        val anchorRow = currentlyCheckedVisible.lastOrNull() ?: return true
        val headerCount = headerSelectedCount

        ioExecutor.execute {
            for (row in currentlyCheckedVisible) {
                if (!selectedMyFilesSessionMap.containsKey(row.fileName)) {
                    val uri = resolveMyFilesDocumentUri(row.fileName) ?: continue
                    val ext = row.fileName.substringAfterLast('.', "").lowercase()
                    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
                        "apk" -> "application/vnd.android.package-archive"
                        "zip" -> "application/zip"
                        "rar" -> "application/vnd.rar"
                        "7z" -> "application/x-7z-compressed"
                        "pdf" -> "application/pdf"
                        else -> "*/*"
                    }
                    val isImg = mime.startsWith("image/")
                    val isVid = mime.startsWith("video/")
                    val meta = if (isImg) decodeSampledPreview(uri) else if (isVid) extractVideoFrameAndSize(uri) else null

                    selectedMyFilesSessionMap[row.fileName] = ResolvedEntry(
                        key = row.fileName,
                        uri = uri,
                        mimeType = mime,
                        displayName = row.fileName,
                        sizeText = row.fileSizeText,
                        width = meta?.second ?: 0,
                        height = meta?.third ?: 0,
                        previewBitmap = meta?.first,
                        isVideo = isVid,
                        isImage = isImg
                    )
                }
            }

            val allEntries = selectedMyFilesSessionMap.values.toList()
            if (allEntries.isEmpty()) {
                toast("선택한 파일의 원본 위치를 찾지 못했어요. 파일 접근 권한(모든 파일)을 확인해주세요")
                return@execute
            }

            val payload = buildMultiEntryPayload(allEntries, headerCount)
            mainHandler.post {
                showOrUpdateMediaOrFilePill(payload, anchorRow.rect.centerX(), anchorRow.rect.top)
            }
        }
        return true
    }

    /**
     * [v11.0 삼성 갤러리 다중 선택 / 컨텍스트 메뉴 동기화]
     * - 갤러리의 모든 창(흰색 컨텍스트 메뉴 + 본 화면 + 분할 화면)을 함께 스캔
     * - 컨텍스트 메뉴(클립보드에 복사 / 앨범으로 복사 / 앨범으로 이동)가 뜨면 꾹 누른 선택으로 판정
     * - 하단 필름스트립 썸네일과 큰 사진 화면도 감지
     * - 날짜로 원본이 하나로 확정되면 스크린샷 없이 즉시 표시하고, 나머지만 스크린샷 시각 매칭(확신할 때만)
     */
    private fun syncGalleryMultiSelection(initialLongPressRect: Rect, isExplicitLongPress: Boolean) {
        if (isGlobalDragging) return
        // 목록 보기에서 파일명이 보이면 이름으로 정확히 찾음 (zip/apk 등 비미디어 포함)
        if (syncMyFilesMultiSelection(initialLongPressRect, isExplicitLongPress, ::isGalleryApp, fileNameRowsOnly = true)) return
        val galleryRoots = collectAppRoots(::isGalleryApp)
        if (galleryRoots.isEmpty()) return

        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels

        data class GalleryCell(
            val key: String,
            val rect: Rect,
            val desc: String,
            val isExplicitlyChecked: Boolean
        )

        val cells = mutableListOf<GalleryCell>()
        var headerSelectedCount: Int? = null
        val headerCountRegex = Regex("(\\d+)\\s*개\\s*선택")

        // 삼성 갤러리 롱프레스 흰색 컨텍스트 메뉴(만들기 / 공유 / 삭제 / 클립보드에 복사 / 앨범으로 복사 / 앨범으로 이동) 감지용
        val contextMenuItemKeywords = setOf(
            "클립보드에 복사", "앨범으로 복사", "앨범으로 이동", "만들기", "휴지통으로 이동", "보안 폴더로 이동"
        )
        val contextMenuStrongKeywords = setOf("클립보드에 복사", "앨범으로 복사", "앨범으로 이동")
        val matchedContextMenuRects = mutableListOf<Rect>()
        var hasStrongContextMenuKeyword = false

        val bottomNavTabNames = setOf("사진", "앨범", "스토리", "메뉴", "검색", "Pictures", "Albums", "Stories", "Menu")

        // [v11.4] One UI 8 갤러리: 체크 원이 셀의 손자/형제 뷰로 따로 그려짐 -> 체크 표시와 셀 박스를 따로 모아 위치로 짝지음
        val checkMarks = mutableListOf<Pair<Rect, Boolean>>()
        val cellBoxes = mutableListOf<Pair<Rect, String>>()
        var hasCancelButton = false
        var bareDigitHeader: Int? = null
        fun isCheckedNode(n: AccessibilityNodeInfo): Boolean {
            val st = n.stateDescription?.toString() ?: ""
            val cd = n.contentDescription?.toString() ?: ""
            return n.isChecked || st.contains("선택됨") || cd.contains("선택됨") || st.equals("Selected", true)
        }
        fun hasCheckedDescendant(n: AccessibilityNodeInfo?, d: Int): Boolean {
            if (n == null || d > 4) return false
            if (d > 0 && isCheckedNode(n)) return true
            for (i in 0 until n.childCount) if (hasCheckedDescendant(n.getChild(i), d + 1)) return true
            return false
        }

        fun scanGalleryTree(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 40) return
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.width() <= 0 || r.height() <= 0) return

            val ownText = node.text?.toString()?.trim() ?: ""
            val ownDesc = node.contentDescription?.toString()?.trim() ?: ""
            val ownState = node.stateDescription?.toString()?.trim() ?: ""

            // 1) 상단 "N개 선택됨" 헤더 감지
            val countMatch = headerCountRegex.find(ownText) ?: headerCountRegex.find(ownDesc) ?: headerCountRegex.find(ownState)
            if (countMatch != null) {
                headerSelectedCount = countMatch.groupValues[1].toIntOrNull()
            }

            // 2) 흰색 롱프레스 컨텍스트 메뉴 감지
            val combinedLabel = if (ownText.isNotEmpty()) ownText else ownDesc
            if (combinedLabel.isNotEmpty() && combinedLabel.length <= 20) {
                if (contextMenuStrongKeywords.any { combinedLabel.contains(it) }) {
                    hasStrongContextMenuKeyword = true
                    matchedContextMenuRects.add(Rect(r))
                } else if (contextMenuItemKeywords.any { combinedLabel.contains(it) }) {
                    matchedContextMenuRects.add(Rect(r))
                }
            }

            // 2-1) 선택 모드 헤더: "취소" 버튼 + 숫자만 있는 선택 개수
            if (r.top < dp(140f)) {
                if (combinedLabel == "취소" || combinedLabel.equals("Cancel", true)) hasCancelButton = true
                if (ownText.matches(Regex("\\d{1,4}"))) bareDigitHeader = ownText.toIntOrNull()
            }
            // 2-2) 작은 체크 원 수집
            val clsName = node.className?.toString() ?: ""
            if ((node.isCheckable || clsName.contains("CheckBox", true) || clsName.contains("RadioButton", true)) &&
                r.width() < dp(56f) && r.height() < dp(56f)
            ) {
                checkMarks.add(Rect(r) to isCheckedNode(node))
            }

            // 3) 하단 네비게이션 탭 버튼(사진/앨범/스토리/메뉴) 자체만 스킵 (하단 썸네일은 스킵하지 않음)
            if (combinedLabel in bottomNavTabNames && r.height() < dp(68f)) {
                return
            }

            // 4) 썸네일 셀 및 사진 뷰 감지 (하단 작은 필름스트립 32dp부터 큰 사진 뷰까지)
            val isMediaCellSize = r.width() in dp(32f)..screenW &&
                                  r.height() in dp(32f)..(screenH * 0.88f).toInt()
            if (r.width() >= dp(56f) && r.height() >= dp(56f) && r.width() < screenW * 0.95f &&
                r.height() < screenH * 0.6f && (node.isClickable || node.isLongClickable || clsName.contains("Image"))
            ) {
                cellBoxes.add(Rect(r) to (if (ownDesc.isNotEmpty()) ownDesc else ownText))
            }

            val descBuilder = StringBuilder()
            if (ownDesc.isNotEmpty()) descBuilder.append(ownDesc).append(" ")
            if (ownState.isNotEmpty()) descBuilder.append(ownState).append(" ")
            if (ownText.isNotEmpty() && ownText !in bottomNavTabNames) descBuilder.append(ownText).append(" ")

            var childChecked = false
            for (i in 0 until node.childCount) {
                val c = node.getChild(i) ?: continue
                val cst = c.stateDescription?.toString() ?: ""
                val ccd = c.contentDescription?.toString() ?: ""
                if (c.isChecked || cst.contains("선택됨") || ccd.contains("선택됨") || cst.equals("Selected", true)) {
                    childChecked = true
                }
                c.contentDescription?.let { descBuilder.append(it).append(" ") }
                c.stateDescription?.let { descBuilder.append(it).append(" ") }
            }
            val fullCellDesc = descBuilder.toString().trim()

            // 컨텍스트 메뉴 항목 자체는 미디어 셀에서 제외
            val isContextMenuRow = contextMenuStrongKeywords.any { fullCellDesc.contains(it) } ||
                    fullCellDesc in setOf("만들기", "공유", "삭제", "클립보드에 복사", "앨범으로 복사", "앨범으로 이동")

            if (!isContextMenuRow) {
                val hasMediaMetadata = fullCellDesc.contains("년") ||
                        fullCellDesc.contains("월") ||
                        fullCellDesc.contains("사진") ||
                        fullCellDesc.contains("이미지") ||
                        fullCellDesc.contains("동영상") ||
                        fullCellDesc.contains("GIF", true) ||
                        fullCellDesc.contains("photo", true) ||
                        fullCellDesc.contains("video", true) ||
                        Regex("\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}").containsMatchIn(fullCellDesc)

                val explicitlyChecked = node.isChecked || childChecked || hasCheckedDescendant(node, 0) ||
                        ownState.contains("선택됨") ||
                        fullCellDesc.contains("선택됨") ||
                        fullCellDesc.contains("Selected", true)

                if (isMediaCellSize && (hasMediaMetadata || explicitlyChecked)) {
                    val normalizedDesc = fullCellDesc
                        .replace("선택됨", "")
                        .replace("선택 안 됨", "")
                        .replace("Selected", "", true)
                        .replace("Not selected", "", true)
                        .trim()
                    val cellKey = "${normalizedDesc}_${r.left / dp(24f)}_${r.top / dp(24f)}"
                    cells.add(GalleryCell(cellKey, r, fullCellDesc, explicitlyChecked))
                    return
                }
            }

            for (i in 0 until node.childCount) {
                scanGalleryTree(node.getChild(i), depth + 1)
            }
        }

        for (root in galleryRoots) {
            scanGalleryTree(root, 0)
        }
        // 체크 원을 담고 있는 가장 작은 셀 박스에 체크 상태를 반영
        for ((mr, checked) in checkMarks) {
            val idx = cells.indexOfFirst { it.rect.contains(mr.centerX(), mr.centerY()) }
            if (idx >= 0) {
                if (checked && !cells[idx].isExplicitlyChecked) cells[idx] = cells[idx].copy(isExplicitlyChecked = true)
                continue
            }
            val box = cellBoxes.filter { it.first.contains(mr.centerX(), mr.centerY()) }
                .minByOrNull { it.first.width().toLong() * it.first.height() } ?: continue
            cells.add(GalleryCell("box_${box.first.left / dp(24f)}_${box.first.top / dp(24f)}_${box.second}", box.first, box.second, checked))
        }
        if (headerSelectedCount == null && hasCancelButton) headerSelectedCount = bareDigitHeader
        val headerCount = headerSelectedCount

        val isGalleryContextMenuOpen = hasStrongContextMenuKeyword && matchedContextMenuRects.isNotEmpty()
        val contextMenuUnionRect = if (isGalleryContextMenuOpen) {
            Rect(matchedContextMenuRects.first()).apply {
                for (mr in matchedContextMenuRects) union(mr)
            }
        } else null

        val orderedCells = cells
            .distinctBy { it.key }
            .sortedWith(compareBy<GalleryCell> { it.rect.top / dp(24f) }.thenBy { it.rect.left })

        val checkedCells = orderedCells.filter { it.isExplicitlyChecked }
        val uncheckedKeys = orderedCells.filter { !it.isExplicitlyChecked }.map { it.key }.toSet()

        // 컨텍스트 메뉴가 닫혀 있을 때만 체크 해제 항목 제거
        if (!isGalleryContextMenuOpen) {
            for (uk in uncheckedKeys) {
                selectedGallerySessionMap.remove(uk)
            }
        }

        val contextMenuJustOpened = isGalleryContextMenuOpen && !wasGalleryContextMenuVisible
        wasGalleryContextMenuVisible = isGalleryContextMenuOpen

        // 체크된 셀도 없고, 컨텍스트 메뉴도 없고, 명시적 롱프레스도 아니면 종료
        if (checkedCells.isEmpty() && !isGalleryContextMenuOpen && !isExplicitLongPress &&
            (headerCount == null || headerCount == 0)
        ) {
            val hadGallerySelection = selectedGallerySessionMap.isNotEmpty() || visibleCheckedGalleryKeys.isNotEmpty()
            selectedGallerySessionMap.clear()
            visibleCheckedGalleryKeys.clear()
            if (hadGallerySelection && currentPayload?.type != PayloadType.TEXT) {
                removeActiveOverlay()
            }
            return
        }

        val currentCheckedKeySet = checkedCells.map { it.key }.toSet()
        // 새 꾹 누르기(선택 1개 이하) 또는 보이는 체크 셀이 전체 선택이면 이전 선택 제거
        if (isExplicitLongPress && (headerCount ?: 0) <= 1) {
            selectedGallerySessionMap.clear()
            visibleCheckedGalleryKeys.clear()
        } else if (headerCount != null && checkedCells.isNotEmpty() && checkedCells.size >= headerCount) {
            selectedGallerySessionMap.keys.retainAll(currentCheckedKeySet)
        }
        val hasSelectionChanged = currentCheckedKeySet != visibleCheckedGalleryKeys

        if (!isExplicitLongPress && !contextMenuJustOpened && !hasSelectionChanged && activeOverlayView != null) {
            return
        }

        val newlyCheckedCells = checkedCells.filter { it.key !in visibleCheckedGalleryKeys }
        visibleCheckedGalleryKeys.clear()
        visibleCheckedGalleryKeys.addAll(currentCheckedKeySet)

        // 해석할 대상 셀: 새로 체크된 셀 -> 체크된 셀 -> (메뉴/롱프레스 시) 꾹 누른 위치에 가장 가까운 셀 -> 가려진 경우 합성 셀
        val targetCellsToResolve: List<GalleryCell> = when {
            newlyCheckedCells.isNotEmpty() -> newlyCheckedCells
            checkedCells.isNotEmpty() -> checkedCells
            orderedCells.isNotEmpty() && (isGalleryContextMenuOpen || isExplicitLongPress) -> {
                val refX = if (!initialLongPressRect.isEmpty) initialLongPressRect.centerX()
                           else contextMenuUnionRect?.centerX() ?: (screenW / 2)
                val refY = if (!initialLongPressRect.isEmpty) initialLongPressRect.centerY()
                           else contextMenuUnionRect?.centerY() ?: (screenH / 2)
                val closest = orderedCells.minByOrNull {
                    hypot((it.rect.centerX() - refX).toDouble(), (it.rect.centerY() - refY).toDouble())
                }
                listOfNotNull(closest)
            }
            (isGalleryContextMenuOpen || isExplicitLongPress) && lastGalleryInteractedDesc.isNotEmpty() -> {
                // 흰색 메뉴가 화면을 덮어 셀 정보가 가려진 경우: 마지막으로 꾹 누른 항목의 설명(날짜 등)으로 합성 셀 생성
                val fallbackRect = if (!initialLongPressRect.isEmpty) initialLongPressRect
                                   else contextMenuUnionRect ?: Rect(screenW / 4, screenH / 3, screenW * 3 / 4, screenH * 2 / 3)
                listOf(
                    GalleryCell(
                        key = "gallery_active_${lastGalleryInteractedDesc.hashCode()}",
                        rect = fallbackRect,
                        desc = lastGalleryInteractedDesc,
                        isExplicitlyChecked = true
                    )
                )
            }
            else -> emptyList()
        }

        if (targetCellsToResolve.isEmpty() && selectedGallerySessionMap.isEmpty()) {
            dumpGalleryDebug(galleryRoots, "no target cells (cells=${cells.size}, marks=${checkMarks.size}, boxes=${cellBoxes.size}, header=$headerCount, menu=$isGalleryContextMenuOpen)")
            return
        }

        val firstCellRange = orderedCells.firstOrNull()?.let { parseGalleryDateRange(it.desc) }
        val lastCellRange = orderedCells.lastOrNull()?.let { parseGalleryDateRange(it.desc) }
        val isGridAscendingOrder = if (firstCellRange != null && lastCellRange != null && firstCellRange.first != lastCellRange.first) {
            firstCellRange.first < lastCellRange.first
        } else {
            false
        }

        // 팝업 위치: 흰색 컨텍스트 메뉴가 떠 있으면 메뉴 바로 위(또는 아래)에 배치해 가리지 않게 함
        val anchorRect = when {
            contextMenuUnionRect != null -> contextMenuUnionRect
            targetCellsToResolve.isNotEmpty() -> targetCellsToResolve.last().rect
            checkedCells.isNotEmpty() -> checkedCells.last().rect
            else -> initialLongPressRect
        }
        val popupX = anchorRect.centerX().coerceIn(dp(80f), screenW - dp(80f))
        val popupY = if (contextMenuUnionRect != null) {
            if (contextMenuUnionRect.top > dp(120f)) {
                contextMenuUnionRect.top - dp(12f)
            } else {
                (contextMenuUnionRect.bottom + dp(96f)).coerceAtMost(screenH - dp(80f))
            }
        } else {
            anchorRect.top
        }

        fun resolveCell(cell: GalleryCell, crop: Bitmap?): ResolvedEntry? {
            val cellDateRange = parseGalleryDateRange(cell.desc)
            val sameTimeCells = if (cellDateRange != null) {
                orderedCells.filter {
                    val r = parseGalleryDateRange(it.desc)
                    r != null && r.first == cellDateRange.first && r.second == cellDateRange.second
                }
            } else emptyList()
            val idxInSameTime = sameTimeCells.indexOfFirst { it.key == cell.key }.coerceAtLeast(0)
            return resolveExactGalleryMediaEntry(
                cellKey = cell.key,
                nodeDesc = cell.desc,
                screenCrop = crop,
                sameTimeIndex = idxInSameTime,
                sameTimeTotal = sameTimeCells.size,
                isGridAscending = isGridAscendingOrder,
                excludeUris = selectedGallerySessionMap.values.map { it.uri }.toSet()
            )
        }

        fun showResolvedEntries() {
            val all = selectedGallerySessionMap.values.toList()
            if (all.isEmpty()) return
            val payload = buildMultiEntryPayload(all, headerCount)
            mainHandler.post { showOrUpdateMediaOrFilePill(payload, popupX, popupY) }
        }

        ioExecutor.execute {
            // [v11.3] 날짜가 하나뿐이어도 바로 믿지 않고 항상 화면 썸네일과 시각 비교로 확인 (엉뚱한 사진 방지)
            val unresolved = targetCellsToResolve.filter { !selectedGallerySessionMap.containsKey(it.key) }
            showResolvedEntries()
            if (unresolved.isEmpty()) return@execute

            // [2단계] 날짜만으로 확정되지 않은 항목은 화면 썸네일과 원본을 시각 비교 (확신할 때만 추가)
            mainHandler.post {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        ioExecutor.execute {
                            var fullBitmap: Bitmap? = null
                            runCatching {
                                val hwBuffer = screenshot.hardwareBuffer
                                fullBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, screenshot.colorSpace)
                                    ?.copy(Bitmap.Config.ARGB_8888, false)
                                hwBuffer.close()
                            }
                            val bmp = fullBitmap
                            var missed = 0
                            for (cell in unresolved) {
                                var crop: Bitmap? = null
                                if (bmp != null && cell.rect.width() > dp(32f) && cell.rect.height() > dp(32f)) {
                                    runCatching {
                                        val left = cell.rect.left.coerceIn(0, bmp.width - 1)
                                        val top = cell.rect.top.coerceIn(0, bmp.height - 1)
                                        val width = cell.rect.width().coerceIn(1, bmp.width - left)
                                        val height = cell.rect.height().coerceIn(1, bmp.height - top)
                                        crop = Bitmap.createBitmap(bmp, left, top, width, height)
                                    }
                                }
                                val resolved = resolveCell(cell, crop)
                                if (resolved != null) selectedGallerySessionMap[cell.key] = resolved else missed++
                            }
                            if (missed > 0) dumpGalleryDebug(galleryRoots, "visual match failed for $missed cell(s): " + unresolved.joinToString { it.desc })
                            if (missed > 0) toast("원본을 확실히 찾지 못한 항목이 있어요. 공유 → ⚡ 원본 드래그를 써주세요")
                            showResolvedEntries()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        toast("원본을 확실히 찾지 못했어요. 공유 → ⚡ 원본 드래그를 써주세요")
                    }
                })
            }
        }
    }

    /**
     * 선택된 1개 이상의 파일/미디어 엔트리 목록을 다중 드래그용 DragPayload로 조합
     */
    private fun buildMultiEntryPayload(
        entries: List<ResolvedEntry>,
        headerCountHint: Int? = null
    ): DragPayload {
        val primary = entries.first()
        val extraUris = entries.drop(1).map { it.uri }
        val count = max(entries.size, headerCountHint ?: entries.size)
        val distinctMimes = entries.map { it.mimeType }.distinct()

        val allImages = entries.all { it.isImage }
        val allVideos = entries.all { it.isVideo }

        val overallType = when {
            allImages -> PayloadType.IMAGE
            allVideos -> PayloadType.VIDEO
            else -> PayloadType.FILE
        }

        val combinedMime = when {
            distinctMimes.size == 1 -> distinctMimes.first()
            allImages -> "image/*"
            allVideos -> "video/*"
            else -> "*/*"
        }

        val latestPreviewEntry = entries.lastOrNull { it.previewBitmap != null } ?: primary
        val summaryNames = if (entries.size == 1) {
            if (primary.sizeText.isNotEmpty()) "${primary.displayName} (${primary.sizeText})" else primary.displayName
        } else {
            val firstTwo = entries.take(2).joinToString(", ") { it.displayName }
            if (entries.size > 2) "$firstTwo 외 ${entries.size - 2}개" else firstTwo
        }

        return DragPayload(
            type = overallType,
            uri = primary.uri,
            additionalUris = extraUris,
            allMimeTypes = distinctMimes.ifEmpty { listOf("*/*") },
            mimeType = combinedMime,
            displayName = primary.displayName,
            subtitleSummary = summaryNames,
            origWidth = latestPreviewEntry.width,
            origHeight = latestPreviewEntry.height,
            previewBitmap = latestPreviewEntry.previewBitmap,
            totalCount = count
        )
    }

    /**
     * [다중 파일/미디어 팝업 무깜빡임 업데이트]
     * 이미 팝업이 떠 있는 상태에서 체크 개수가 바뀌면 창을 재생성하지 않고 배지·제목·썸네일만 즉시 갱신
     */
    private fun showOrUpdateMediaOrFilePill(payload: DragPayload, posX: Int, posY: Int) {
        currentPayload = payload

        val resInfo = if (payload.origWidth > 0 && payload.origHeight > 0) {
            "${payload.origWidth}×${payload.origHeight} 원본"
        } else {
            "원본"
        }

        val titleText = if (payload.totalCount > 1) {
            when (payload.type) {
                PayloadType.IMAGE -> "🖼️ 사진 ${payload.totalCount}장 선택됨 (원본 묶음)"
                PayloadType.VIDEO -> "🎬 동영상 ${payload.totalCount}개 선택됨 (원본 묶음)"
                else -> "📁 파일/미디어 ${payload.totalCount}개 선택됨 • 잡고 이동"
            }
        } else {
            when (payload.type) {
                PayloadType.IMAGE -> "🖼️ 이미지 ($resInfo)"
                PayloadType.VIDEO -> "🎬 동영상 ($resInfo)"
                else -> "📁 파일 원본 드래그"
            }
        }

        val subText = if (payload.subtitleSummary.isNotEmpty()) {
            "${payload.subtitleSummary} • 잡고 다른 앱으로 이동"
        } else {
            "${payload.displayName} • 잡고 다른 앱으로 이동"
        }

        if (activeOverlayView != null && activePillTitleView != null && activePillBodyView != null) {
            activePillTitleView?.text = titleText
            activePillBodyView?.text = subText

            activePillBadgeView?.let { badge ->
                if (payload.totalCount > 1) {
                    badge.visibility = View.VISIBLE
                    badge.text = payload.totalCount.toString()
                } else {
                    badge.visibility = View.GONE
                }
            }

            if (payload.previewBitmap != null && !payload.previewBitmap.isRecycled) {
                activePillThumbView?.visibility = View.VISIBLE
                activePillThumbView?.setImageBitmap(payload.previewBitmap)
            }

            mainHandler.removeCallbacks(autoDismissRunnable)
            mainHandler.postDelayed(autoDismissRunnable, 8000)
            return
        }

        showQuickDragPill(payload, posX, posY)
    }

    private fun handleWindowContentChangedForTextSelection(now: Long) {
        if (isGlobalDragging) return
        val toolbarInfo = findFloatingSelectionToolbar() ?: return

        val treeSelection = findSelectedTextInAllWindows()
        if (treeSelection != null && treeSelection.first.isNotEmpty()) {
            val (selText, bounds) = treeSelection
            lastRealtimeSelectedText = selText
            lastRealtimeTextTimestamp = now
            lastSelectionBounds = bounds
            val anchor = toolbarInfo?.first ?: bounds
            showOrUpdateRealtimeTextPill(selText, anchor, toolbarInfo?.second)
            return
        }

        val handleSelection = extractComposeHandleSelectedText(toolbarInfo?.first)
        if (handleSelection != null && handleSelection.first.isNotEmpty()) {
            val (selText, bounds) = handleSelection
            lastRealtimeSelectedText = selText
            lastRealtimeTextTimestamp = now
            lastSelectionBounds = bounds
            val anchor = toolbarInfo?.first ?: bounds
            showOrUpdateRealtimeTextPill(selText, anchor, toolbarInfo?.second)
            return
        }

        if (toolbarInfo != null) {
            val (toolbarRect, copyBtnRect) = toolbarInfo
            if (lastRealtimeSelectedText.isNotEmpty() && (now - lastRealtimeTextTimestamp) < 3500L) {
                showOrUpdateRealtimeTextPill(lastRealtimeSelectedText, toolbarRect, copyBtnRect)
            }
            if (!isVisualSliceRunning.get() && (now - lastVisualSliceTimestamp) > 220L) {
                lastVisualSliceTimestamp = now
                extractWebViewHighlightedTextViaScreenshot(toolbarRect, copyBtnRect)
            }
        }
    }

    /**
     * 텍스트 선택 플로팅 툴바("복사 | 모두 선택 | 웹 검색 ...") 탐색.
     * ponytail: the system selection toolbar is its own small popup window; full-size app windows (ChatGPT/Claude
     * message action rows with Copy|Share) and the keyboard are skipped so they can't fake a selection toolbar.
     */
    private fun findFloatingSelectionToolbar(): Pair<Rect, Rect?>? {
        val allWindows = runCatching { windows }.getOrNull() ?: emptyList()
        val screenH = resources.displayMetrics.heightPixels

        val selectionActionKeywords = listOf(
            "복사", "Copy",
            "모두 선택", "전체 선택", "Select all",
            "웹 검색", "Web search",
            "소리 내 읽기", "번역", "Translate",
            "삼성 패스에 저장", "Gemini에게 물어보기", "잘라내기", "Cut", "공유", "Share"
        )

        // 갤러리 컨텍스트 메뉴(앨범으로 복사 등)는 텍스트 선택 툴바에서 제외
        val galleryMenuExclusions = listOf("클립보드에 복사", "앨범으로 복사", "앨범으로 이동")

        data class ActionNodeItem(val label: String, val rect: Rect)
        val foundItems = mutableListOf<ActionNodeItem>()

        fun scanNode(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val txt = (node.text?.toString()?.trim() ?: "").ifEmpty {
                node.contentDescription?.toString()?.trim() ?: ""
            }
            if (txt.isNotEmpty() && txt.length <= 18 && galleryMenuExclusions.none { txt.contains(it) }) {
                val matchedKeyword = selectionActionKeywords.any { kw ->
                    txt.equals(kw, ignoreCase = true) ||
                    txt.startsWith("$kw,", ignoreCase = true) ||
                    txt.contains("모두 선택") ||
                    txt.contains("전체 선택") ||
                    txt.contains("웹 검색") ||
                    txt.contains("소리 내 읽기") ||
                    txt.contains("삼성 패스에 저장")
                }
                if (matchedKeyword) {
                    val r = Rect()
                    node.getBoundsInScreen(r)
                    if (r.width() in dp(16f)..dp(220f) && r.height() in dp(16f)..dp(100f) && r.top in dp(20f)..(screenH - dp(40f))) {
                        foundItems.add(ActionNodeItem(txt, r))
                    }
                }
            }
            for (i in 0 until node.childCount) {
                scanNode(node.getChild(i))
            }
        }

        for (w in allWindows) {
            if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            val root = w.root ?: continue
            val rootPkg = root.packageName?.toString() ?: ""
            if (rootPkg == packageName || isGalleryApp(rootPkg)) continue
            val wb = Rect()
            w.getBoundsInScreen(wb)
            if (wb.height() !in 1..(screenH * 0.4f).toInt()) continue
            scanNode(root)
        }

        if (foundItems.isEmpty()) return null

        for (anchor in foundItems) {
            val rowGroup = foundItems.filter { abs(it.rect.centerY() - anchor.rect.centerY()) <= dp(36f) }
            val distinctLabels = rowGroup.map { it.label }.distinct()
            val hasCopy = distinctLabels.any { it.contains("복사") || it.equals("Copy", true) }
            val hasCompanion = distinctLabels.any {
                it.contains("모두 선택") || it.contains("전체 선택") || it.equals("Select all", true) ||
                it.contains("웹 검색") || it.equals("Web search", true) ||
                it.contains("번역") || it.equals("Translate", true) ||
                it.contains("소리 내 읽기") || it.contains("삼성 패스에 저장") ||
                it.contains("Gemini") || it.contains("잘라내기") || it.contains("공유") || it.equals("Share", true)
            }

            if (hasCopy && (hasCompanion || distinctLabels.size >= 2)) {
                val unionRect = Rect(rowGroup.first().rect)
                for (item in rowGroup) unionRect.union(item.rect)
                val copyRect = rowGroup.firstOrNull { it.label.contains("복사") || it.label.equals("Copy", true) }?.rect
                return unionRect to copyRect
            }
        }

        return null
    }

    private fun extractSelectedTextFromSelectionEvent(event: AccessibilityEvent): Pair<String, Rect>? {
        val source = event.source
        val rect = Rect()
        source?.getBoundsInScreen(rect)
        if (rect.isEmpty) {
            rect.set(
                resources.displayMetrics.widthPixels / 2 - dp(80f),
                resources.displayMetrics.heightPixels / 3,
                resources.displayMetrics.widthPixels / 2 + dp(80f),
                resources.displayMetrics.heightPixels / 3 + dp(45f)
            )
        }

        if (source != null) {
            val rawText = source.text?.toString() ?: ""
            val hasValidNodeSel = source.textSelectionStart >= 0 &&
                                  source.textSelectionEnd >= 0 &&
                                  source.textSelectionStart != source.textSelectionEnd
            val sStart = if (hasValidNodeSel) source.textSelectionStart else event.fromIndex
            val sEnd = if (hasValidNodeSel) source.textSelectionEnd else event.toIndex
            val start = min(sStart, sEnd)
            val end = max(sStart, sEnd)

            if (rawText.isNotEmpty() && start >= 0 && end > start) {
                if (end <= rawText.length) {
                    val sub = rawText.substring(start, end).trim()
                    if (sub.isNotEmpty()) return sub to rect
                } else if ((end - start) == rawText.length && !source.isEditable) {
                    val sub = rawText.trim()
                    if (sub.isNotEmpty()) return sub to rect
                }
            }
        }

        val eventJoined = event.text.joinToString("").trim()
        val eStart = min(event.fromIndex, event.toIndex)
        val eEnd = max(event.fromIndex, event.toIndex)
        if (eventJoined.isNotEmpty()) {
            if (eStart >= 0 && eEnd > eStart && eEnd <= eventJoined.length) {
                val sub = eventJoined.substring(eStart, eEnd).trim()
                if (sub.isNotEmpty()) return sub to rect
            }
            if (eEnd > eStart && (eEnd - eStart) == eventJoined.length && source?.isEditable != true) {
                return eventJoined to rect
            }
            if (source?.isEditable != true && eEnd > eStart && eventJoined.length in 1..600) {
                return eventJoined to rect
            }
        }

        return findSelectedTextInAllWindows()
    }

    private fun findSelectedTextInAllWindows(): Pair<String, Rect>? {
        data class SelectedPiece(val rect: Rect, val text: String)
        val pieces = mutableListOf<SelectedPiece>()

        fun traverse(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 45) return
            val raw = node.text?.toString()
            val s = node.textSelectionStart
            val e = node.textSelectionEnd
            if (!raw.isNullOrEmpty() && s >= 0 && e >= 0 && s != e && !node.isEditable) {
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
                traverse(node.getChild(i), depth + 1)
            }
        }

        val allWin = runCatching { windows }.getOrNull() ?: emptyList()
        for (w in allWin) {
            if (w.root?.packageName?.toString() == packageName) continue
            traverse(w.root, 0)
        }
        if (pieces.isEmpty()) {
            traverse(rootInActiveWindow, 0)
        }
        if (pieces.isEmpty()) return null

        val sorted = pieces.distinctBy { "${it.rect.top}_${it.rect.left}_${it.text}" }
            .sortedWith(compareBy<SelectedPiece> { it.rect.top }.thenBy { it.rect.left })
        val combinedText = sorted.joinToString("\n") { it.text }.trim()
        val combinedRect = Rect(sorted.first().rect)
        for (p in sorted) combinedRect.union(p.rect)
        return combinedText to combinedRect
    }

    private fun extractComposeHandleSelectedText(toolbarRect: Rect?): Pair<String, Rect>? {
        val allWindows = runCatching { windows }.getOrNull() ?: return null
        val screenH = resources.displayMetrics.heightPixels

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
            fun findHandleNodes(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || depth > 35) return
                val viewId = node.viewIdResourceName ?: ""
                val cls = node.className?.toString() ?: ""
                val r = Rect()
                node.getBoundsInScreen(r)
                if ((viewId.contains("handle", true) || cls.contains("Handle", true)) &&
                    r.width() in dp(10f)..dp(88f) && r.height() in dp(10f)..dp(88f)
                ) {
                    handleRects.add(r)
                }
                for (i in 0 until node.childCount) findHandleNodes(node.getChild(i), depth + 1)
            }
            for (w in allWindows) findHandleNodes(w.root, 0)
        }

        if (handleRects.size < 2) return null

        val sortedHandles = handleRects
            .distinctBy { "${it.centerX() / 6}_${it.centerY() / 6}" }
            .sortedWith(compareBy<Rect> { it.top / dp(14f) }.thenBy { it.centerX() })

        if (sortedHandles.size < 2) return null

        val startHandle = sortedHandles.first()
        val endHandle = sortedHandles.last()

        val startX = startHandle.centerX().toFloat()
        val startY = (startHandle.top - dp(8f)).toFloat()
        val endX = endHandle.centerX().toFloat()
        val endY = (endHandle.top - dp(8f)).toFloat()

        val sliced = slicePageTextBetweenCoordinates(startX, startY, endX, endY, screenH)
        if (sliced.isBlank()) return null

        val anchorRect = toolbarRect ?: Rect(
            min(startHandle.left, endHandle.left),
            min(startHandle.top, endHandle.top) - dp(36f),
            max(startHandle.right, endHandle.right),
            max(startHandle.bottom, endHandle.bottom)
        )
        return sliced to anchorRect
    }

    /**
     * 화면 하이라이트(파란 선택 영역) 위치를 스크린샷으로 찾아 그 영역의 글자만 잘라냄.
     * 하이라이트를 못 찾으면 아무것도 띄우지 않음 (주변 문단이나 "선택한 텍스트" 같은 가짜 내용을 드래그하지 않도록).
     */
    private fun extractWebViewHighlightedTextViaScreenshot(toolbarRect: Rect, copyBtnRect: Rect?) {
        if (!isVisualSliceRunning.compareAndSet(false, true)) return

        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                ioExecutor.execute {
                    try {
                        val hwBuffer = screenshot.hardwareBuffer
                        val hwBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, screenshot.colorSpace)
                        val swBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                        hwBuffer.close()

                        if (swBitmap != null) {
                            val highlightBox = detectSelectionHighlightBoundsInBitmap(swBitmap, toolbarRect)
                            if (highlightBox != null) {
                                val sliced = slicePageTextBetweenCoordinates(
                                    startX = highlightBox.left.toFloat(),
                                    startY = (highlightBox.top + dp(6f)).toFloat(),
                                    endX = highlightBox.right.toFloat(),
                                    endY = (highlightBox.bottom - dp(6f)).toFloat(),
                                    screenH = swBitmap.height
                                )
                                if (sliced.isNotBlank()) {
                                    lastRealtimeSelectedText = sliced
                                    lastRealtimeTextTimestamp = System.currentTimeMillis()
                                    mainHandler.post {
                                        showOrUpdateRealtimeTextPill(sliced, toolbarRect, copyBtnRect)
                                    }
                                }
                            }
                        }
                    } finally {
                        isVisualSliceRunning.set(false)
                    }
                }
            }

            override fun onFailure(errorCode: Int) {
                isVisualSliceRunning.set(false)
            }
        })
    }

    private fun detectSelectionHighlightBoundsInBitmap(bmp: Bitmap, toolbarRect: Rect): Rect? {
        val w = bmp.width
        val h = bmp.height
        val searchTop = (toolbarRect.top - dp(260f)).coerceAtLeast(dp(60f))
        val searchBottom = (toolbarRect.bottom + dp(320f)).coerceAtMost(h - dp(60f))
        val step = dp(3f).coerceAtLeast(2)

        // 우리 팝업이 하이라이트로 오인되지 않도록 팝업 영역은 제외
        val overlayRect = Rect()
        activeOverlayView?.let { v ->
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            overlayRect.set(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
        }
        val paddedToolbar = Rect(
            toolbarRect.left - dp(12f),
            toolbarRect.top - dp(12f),
            toolbarRect.right + dp(12f),
            toolbarRect.bottom + dp(12f)
        )

        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        var hitCount = 0

        for (y in searchTop until searchBottom step step) {
            for (x in dp(16f) until (w - dp(16f)) step step) {
                if (paddedToolbar.contains(x, y) || overlayRect.contains(x, y)) continue
                val c = bmp.getPixel(x, y)
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)

                val isLightBlueHighlight = b in 185..255 && g in 165..248 && r in 110..215 &&
                        (b - r) >= 24 && (g - r) >= 12
                val isDeepBlueOrTealHighlight = (b in 140..255 && r < 100 && (b - r) >= 45) ||
                        (g in 115..195 && b in 105..190 && r in 0..55 && (g - r) >= 70)

                if (isLightBlueHighlight || isDeepBlueOrTealHighlight) {
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                    hitCount++
                }
            }
        }

        if (hitCount < 8 || minX >= maxX || minY >= maxY) return null
        return Rect(minX, minY, maxX, maxY)
    }

    private fun slicePageTextBetweenCoordinates(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        screenH: Int
    ): String {
        val minY = min(startY, endY) - dp(18f)
        val maxY = max(startY, endY) + dp(18f)

        val ignoreLabels = setOf(
            "복사", "모두 선택", "전체 선택", "공유", "번역", "웹 검색",
            "소리 내 읽기", "Gemini에게 물어보기", "삼성 패스에 저장", "앱 관리", "잘라내기"
        )

        data class TextSegment(val rect: Rect, val text: String)
        val segments = mutableListOf<TextSegment>()

        fun collectNodes(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 45) return
            val raw = node.text?.toString()?.trim()
            if (!raw.isNullOrEmpty() && raw !in ignoreLabels) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (r.width() > dp(16f) && r.height() in dp(10f)..(screenH * 0.85f).toInt() &&
                    r.bottom >= minY && r.top <= maxY
                ) {
                    var childHasText = false
                    for (i in 0 until node.childCount) {
                        val ct = node.getChild(i)?.text?.toString()?.trim()
                        if (!ct.isNullOrEmpty() && raw.contains(ct)) {
                            childHasText = true
                            break
                        }
                    }
                    if (!childHasText) {
                        val sliced = sliceNodeTextByHandles(node, raw, r, startX, startY, endX, endY)
                        if (sliced.isNotBlank()) {
                            segments.add(TextSegment(r, sliced))
                        }
                    }
                }
            }
            for (i in 0 until node.childCount) {
                collectNodes(node.getChild(i), depth + 1)
            }
        }

        val allWin = runCatching { windows }.getOrNull() ?: emptyList()
        for (w in allWin) {
            if (w.root?.packageName?.toString() == packageName) continue
            collectNodes(w.root, 0)
        }
        if (segments.isEmpty()) {
            collectNodes(rootInActiveWindow, 0)
        }
        if (segments.isEmpty()) return ""

        val ordered = segments
            .distinctBy { "${it.rect.top / 8}_${it.rect.left / 8}_${it.text}" }
            .sortedWith(compareBy<TextSegment> { it.rect.top / dp(10f) }.thenBy { it.rect.left })
        return ordered.joinToString(" ") { it.text }.replace(Regex("\\s+"), " ").trim()
    }

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

        val lineH = dp(22f).toFloat().coerceAtLeast(1f)
        val estLines = max(1, (nodeRect.height() / lineH).toInt())
        val w = nodeRect.width().toFloat().coerceAtLeast(1f)

        if (estLines == 1) {
            val sRatio = if (abs(nodeRect.centerY() - startY) <= lineH * 1.3f) {
                ((min(startX, endX) - nodeRect.left) / w).coerceIn(0f, 0.95f)
            } else 0f
            val eRatio = if (abs(nodeRect.centerY() - endY) <= lineH * 1.3f) {
                ((max(startX, endX) - nodeRect.left) / w).coerceIn(sRatio + 0.05f, 1f)
            } else 1f
            val sIdx = (sRatio * rawText.length).toInt().coerceIn(0, rawText.length - 1)
            val eIdx = (eRatio * rawText.length).toInt().coerceIn(sIdx + 1, rawText.length)
            return rawText.substring(sIdx, eIdx).trim()
        } else {
            val topLineRatio = ((startY - nodeRect.top) / nodeRect.height().toFloat()).coerceIn(0f, 0.9f)
            val bottomLineRatio = ((endY - nodeRect.top) / nodeRect.height().toFloat()).coerceIn(topLineRatio + 0.08f, 1f)
            val sIdx = (topLineRatio * rawText.length).toInt().coerceIn(0, rawText.length - 1)
            val eIdx = (bottomLineRatio * rawText.length).toInt().coerceIn(sIdx + 1, rawText.length)
            return rawText.substring(sIdx, eIdx).trim()
        }
    }

    fun showOrUpdateRealtimeTextPill(
        selectedText: String,
        anchorRect: Rect,
        copyBtnRect: Rect? = null
    ) {
        val cleanText = selectedText.trim()
        if (cleanText.isEmpty()) return

        mainHandler.post {
            val payload = DragPayload(
                type = PayloadType.TEXT,
                text = cleanText,
                displayName = cleanText,
                copyButtonBounds = copyBtnRect
            )
            currentPayload = payload

            val previewSingleLine = cleanText.replace(Regex("\\s+"), " ")
            val badgeText = "⠿ 실시간 선택 (${cleanText.length}자) • 잡고 이동"

            val screenW = resources.displayMetrics.widthPixels
            val screenH = resources.displayMetrics.heightPixels
            val targetX = (anchorRect.centerX() - dp(135f)).coerceIn(dp(12f), screenW - dp(275f))
            val targetY = if (anchorRect.top > dp(135f)) {
                anchorRect.top - dp(82f)
            } else {
                (anchorRect.bottom + dp(24f)).coerceAtMost(screenH - dp(160f))
            }

            val overlay = activeOverlayView
            if (overlay != null && activePillTitleView != null && activePillBodyView != null) {
                activePillTitleView?.text = badgeText
                activePillBodyView?.text = previewSingleLine
                activePillBadgeView?.visibility = View.GONE
                activeOverlayParams?.let { lp ->
                    if (abs(lp.x - targetX) > dp(10f) || abs(lp.y - targetY) > dp(10f)) {
                        lp.x = targetX
                        lp.y = targetY
                        runCatching { windowManager.updateViewLayout(overlay, lp) }
                    }
                }
                mainHandler.removeCallbacks(autoDismissRunnable)
                mainHandler.postDelayed(autoDismissRunnable, 6500)
                return@post
            }

            showQuickDragPill(payload, anchorRect.centerX(), targetY + dp(82f))
        }
    }

    private fun resolveMyFilesDocumentUri(fileName: String): Uri? {
        runCatching {
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.MEDIA_TYPE,
                MediaStore.MediaColumns.DATA
            )
            contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection,
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(fileName),
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    val mediaType = cursor.getInt(1)
                    val dataPath = cursor.getString(2)
                    if (!dataPath.isNullOrEmpty()) {
                        val f = File(dataPath)
                        if (f.exists()) {
                            return FileShareProvider.uriFor(this, f)
                        }
                    }
                    return when (mediaType) {
                        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE ->
                            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO ->
                            ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                        else ->
                            ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id)
                    }
                }
            }
        }

        val storageRoot = Environment.getExternalStorageDirectory()
        val searchDirs = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            File(storageRoot, "Download"),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            File(storageRoot, "DCIM/Screenshots"),
            File(storageRoot, "DCIM/Camera"),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            storageRoot
        )

        for (dir in searchDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            val direct = File(dir, fileName)
            if (direct.exists() && direct.isFile) {
                return FileShareProvider.uriFor(this, direct)
            }
            val subFiles = dir.listFiles() ?: continue
            for (sub in subFiles) {
                if (sub.isDirectory && sub.name != "Android") {
                    val nested = File(sub, fileName)
                    if (nested.exists() && nested.isFile) {
                        return FileShareProvider.uriFor(this, nested)
                    }
                }
            }
        }
        return null
    }

    /**
     * 갤러리 썸네일 -> 원본 매칭. 확신이 없으면 null (엉뚱한 사진을 띄우지 않음).
     */
    private fun resolveExactGalleryMediaEntry(
        cellKey: String,
        nodeDesc: String,
        screenCrop: Bitmap?,
        sameTimeIndex: Int,
        sameTimeTotal: Int,
        isGridAscending: Boolean,
        excludeUris: Set<Uri>
    ): ResolvedEntry? {
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

        // [v11.0] Android 13~15에서 Files 컬렉션이 권한에 따라 비어 있을 수 있어 Images / Video 컬렉션을 직접 조회
        fun queryCollection(collectionUri: Uri, isVidCollection: Boolean, extraWhere: String?, extraArgs: Array<String>?, limit: Int): List<MediaCandidate> {
            val out = mutableListOf<MediaCandidate>()
            val proj = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.WIDTH,
                MediaStore.MediaColumns.HEIGHT,
                MediaStore.MediaColumns.DATE_TAKEN,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.RELATIVE_PATH
            )
            var sel = "${MediaStore.MediaColumns.DISPLAY_NAME} NOT LIKE 'OrigDrag_%' AND " +
                      "${MediaStore.MediaColumns.DISPLAY_NAME} NOT LIKE 'DragImage_%' AND " +
                      "${MediaStore.MediaColumns.DISPLAY_NAME} NOT LIKE 'GalleryDrag_%' AND " +
                      "${MediaStore.MediaColumns.DISPLAY_NAME} NOT LIKE 'Original_%'"
            if (!extraWhere.isNullOrEmpty()) {
                sel = "$sel AND ($extraWhere)"
            }

            runCatching {
                contentResolver.query(
                    collectionUri,
                    proj,
                    sel,
                    extraArgs,
                    "${MediaStore.MediaColumns.DATE_TAKEN} DESC, ${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    var c = 0
                    while (cursor.moveToNext() && c < limit) {
                        val relPath = cursor.getString(7) ?: ""
                        if (relPath.contains("UniversalDrag", ignoreCase = true)) continue
                        val id = cursor.getLong(0)
                        val name = cursor.getString(1) ?: "media"
                        val mime = cursor.getString(2) ?: if (isVidCollection) "video/mp4" else "image/jpeg"
                        val w = cursor.getInt(3)
                        val h = cursor.getInt(4)
                        val dt = cursor.getLong(5)
                        val dm = cursor.getLong(6) * 1000L
                        val ts = if (dt > 0L) dt else dm
                        out.add(MediaCandidate(id, name, mime, w, h, isVidCollection, ts, ContentUris.withAppendedId(collectionUri, id)))
                        c++
                    }
                }
            }
            return out
        }

        fun queryCandidates(extraWhere: String?, extraArgs: Array<String>?, limit: Int): List<MediaCandidate> {
            val combined = mutableListOf<MediaCandidate>()
            if (!isVideoStrict) {
                combined.addAll(queryCollection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, extraWhere, extraArgs, limit))
            }
            if (!isImageStrict) {
                combined.addAll(queryCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, extraWhere, extraArgs, limit))
            }
            return combined.sortedByDescending { it.timestampMs }.take(limit)
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

        // 스크린샷이 없을 때만 날짜 유일 후보를 사용 (있으면 아래에서 시각 확인)
        if (screenCrop == null && dateCandidates.size == 1) {
            val c = dateCandidates.first()
            return candidateToResolvedEntry(cellKey, c.uri, c.name, c.mime, c.width, c.height, c.isVideo)
        }

        val rawPool = if (dateCandidates.isNotEmpty()) {
            if (isGridAscending) dateCandidates.sortedBy { it.timestampMs } else dateCandidates.sortedByDescending { it.timestampMs }
        } else {
            queryCandidates(null, null, 60)
        }

        val pool = rawPool.filter { it.uri !in excludeUris }.ifEmpty { rawPool }
        if (pool.isEmpty()) return null

        if (screenCrop != null) {
            val targetVec = extractNormalizedVisualVector(screenCrop)
            if (targetVec != null) {
                var bestCand: MediaCandidate? = null
                var bestScore = -2.0
                // 날짜 후보에서 못 찾으면(갤러리 표시 날짜와 DB 날짜가 다른 경우) 최근 항목 전체에서 다시 비교
                val widePool = if (dateCandidates.isNotEmpty()) {
                    val seen = pool.map { it.uri }.toSet()
                    queryCandidates(null, null, 150).filter { it.uri !in seen && it.uri !in excludeUris }
                } else emptyList()
                var bestFromWide = false
                for ((idx, cand) in (pool + widePool).withIndex()) {
                    if (idx == pool.size && bestScore >= 0.80) break
                    runCatching {
                        val thumb = loadSquareCandidateThumbnail(cand.uri, cand.isVideo)
                        if (thumb != null) {
                            val candVec = extractNormalizedVisualVector(thumb)
                            if (candVec != null) {
                                var score = computeCosineSimilarity(targetVec, candVec)
                                if (sameTimeTotal > 1 && idx == sameTimeIndex) {
                                    score += 0.08
                                }
                                if (score > bestScore) {
                                    bestScore = score
                                    bestCand = cand
                                    bestFromWide = idx >= pool.size
                                }
                            }
                        }
                    }
                }

                // ponytail: calibration knob - ZNCC below 0.80 means "looks different"; show nothing rather than a wrong photo
                // 날짜가 안 맞는 넓은 후보에서 고른 경우엔 더 엄격하게
                if (bestCand != null && bestScore >= (if (bestFromWide) 0.86 else 0.80)) {
                    val c = bestCand!!
                    return candidateToResolvedEntry(cellKey, c.uri, c.name, c.mime, c.width, c.height, c.isVideo)
                }
                return null
            }
        }

        // 스크린샷이 없을 때: 같은 시각 촬영본 목록이 화면 그리드와 정확히 대응할 때만 순서로 선택
        if (dateCandidates.isNotEmpty() && sameTimeTotal == pool.size) {
            val chosen = pool[sameTimeIndex.coerceIn(0, pool.size - 1)]
            return candidateToResolvedEntry(cellKey, chosen.uri, chosen.name, chosen.mime, chosen.width, chosen.height, chosen.isVideo)
        }
        return null
    }

    private fun candidateToResolvedEntry(
        key: String,
        uri: Uri,
        name: String,
        mime: String,
        dbW: Int,
        dbH: Int,
        isVideo: Boolean
    ): ResolvedEntry {
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
        return ResolvedEntry(
            key = key,
            uri = uri,
            mimeType = mime,
            displayName = name,
            width = w,
            height = h,
            previewBitmap = preview,
            isVideo = isVideo,
            isImage = !isVideo
        )
    }

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
        // 올해 사진은 "11월 2일"처럼 연도 없이 표시됨
        val full = dateRegex.find(desc)
        val match = full ?: Regex("(\\d{1,2})월\\s*(\\d{1,2})일").find(desc) ?: return null
        val g = if (full != null) match.groupValues
                else listOf("", Calendar.getInstance().get(Calendar.YEAR).toString()) + match.groupValues.drop(1)
        val year = g[1].toIntOrNull() ?: return null
        val month = (g[2].toIntOrNull() ?: return null) - 1
        val day = g[3].toIntOrNull() ?: return null

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

            // 썸네일 + 다중 선택 원형 카운트 배지 영역
            val thumbContainer = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    rightMargin = dp(10f)
                }
            }

            val imgView = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(dp(56f), dp(56f))
                if (payload.previewBitmap != null && !payload.previewBitmap.isRecycled) {
                    setImageBitmap(payload.previewBitmap)
                    visibility = View.VISIBLE
                } else {
                    visibility = View.GONE
                }
            }
            activePillThumbView = imgView
            thumbContainer.addView(imgView)

            val countBadgeTv = TextView(this).apply {
                text = payload.totalCount.toString()
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                gravity = Gravity.CENTER
                setPadding(dp(6f), dp(2f), dp(6f), dp(2f))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#6366F1"))
                    setStroke(dp(1.5f), Color.WHITE)
                }
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                }
                visibility = if (payload.totalCount > 1) View.VISIBLE else View.GONE
            }
            activePillBadgeView = countBadgeTv
            thumbContainer.addView(countBadgeTv)
            card.addView(thumbContainer)

            val resInfo = if (payload.origWidth > 0 && payload.origHeight > 0) {
                "${payload.origWidth}×${payload.origHeight} 원본"
            } else {
                "원본"
            }

            val isText = payload.type == PayloadType.TEXT
            val actualText = payload.text?.trim() ?: ""

            val typeTitle = if (payload.totalCount > 1) {
                when (payload.type) {
                    PayloadType.IMAGE -> "🖼️ 사진 ${payload.totalCount}장 선택됨 (원본 묶음)"
                    PayloadType.VIDEO -> "🎬 동영상 ${payload.totalCount}개 선택됨 (원본 묶음)"
                    else -> "📁 파일/미디어 ${payload.totalCount}개 선택됨 • 잡고 이동"
                }
            } else {
                when (payload.type) {
                    PayloadType.TEXT -> "⠿ 실시간 선택 (${actualText.length}자) • 잡고 이동"
                    PayloadType.IMAGE -> "🖼️ 이미지 ($resInfo)"
                    PayloadType.VIDEO -> "🎬 동영상 ($resInfo)"
                    PayloadType.FILE -> "📁 파일 원본 드래그"
                }
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
                text = when {
                    isText -> actualText.replace(Regex("\\s+"), " ")
                    payload.subtitleSummary.isNotEmpty() -> "${payload.subtitleSummary} • 잡고 다른 앱으로 이동"
                    else -> "${payload.displayName} • 잡고 다른 앱으로 이동"
                }
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }

            activePillTitleView = titleTv
            activePillBodyView = bodyTv

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
                x = (posX - dp(135f)).coerceIn(dp(12f), screenW - dp(275f))
                y = (posY - dp(86f)).coerceIn(dp(44f), screenH - dp(175f))
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
                            mainHandler.postDelayed(autoDismissRunnable, 6000)
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
            mainHandler.postDelayed(autoDismissRunnable, 7500)
        }
    }

    /**
     * [다중 파일/미디어 글로벌 드래그 시작]
     * - 선택된 모든 파일의 MIME 타입 배열을 ClipDescription에 등록
     * - 모든 Uri를 ClipData.Item으로 추가하고 DRAG_FLAG_GLOBAL_URI_READ 부여
     * - 다중 항목일 때 UniversalDragShadowBuilder에 itemCount를 전달하여 스택 카드 + 배지 렌더링
     */
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
            grantUriPermissionsToActiveApp(uri)
            payload.additionalUris.forEach { grantUriPermissionsToActiveApp(it) }

            val mimeArray = payload.allMimeTypes.ifEmpty { listOf(payload.mimeType) }.distinct().toTypedArray()
            val desc = ClipDescription(payload.displayName, mimeArray)
            clipData = ClipData(desc, ClipData.Item(uri))
            for (extraUri in payload.additionalUris) {
                clipData.addItem(ClipData.Item(extraUri))
            }
            dragFlags = dragFlags or View.DRAG_FLAG_GLOBAL_URI_READ
        }

        val itemCount = max(payload.totalCount, 1 + payload.additionalUris.size)
        val badge = when {
            payload.type == PayloadType.TEXT -> "TEXT (${(payload.text ?: "").length}자)"
            itemCount > 1 -> "MULTI (${itemCount}개 항목)"
            payload.type == PayloadType.IMAGE -> "IMAGE ${payload.origWidth}×${payload.origHeight}"
            payload.type == PayloadType.VIDEO -> "VIDEO ${payload.origWidth}×${payload.origHeight}"
            else -> "FILE"
        }

        val subtitle = when {
            payload.type == PayloadType.TEXT -> payload.text ?: ""
            payload.subtitleSummary.isNotEmpty() -> payload.subtitleSummary
            else -> payload.displayName
        }

        val shadowBuilder = UniversalDragShadowBuilder(
            context = this,
            titleBadge = badge,
            subtitleText = subtitle,
            previewBitmap = payload.previewBitmap,
            itemCount = itemCount
        )

        val started = container.startDragAndDrop(clipData, shadowBuilder, null, dragFlags)
        if (started) {
            container.alpha = 0f
            val lp = container.layoutParams as WindowManager.LayoutParams
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            runCatching { windowManager.updateViewLayout(container, lp) }
            edgeZone = 0; edgeFired = false
            // 갤러리 흰색 컨텍스트 메뉴가 떠 있으면 닫아서 드롭 대상이 가려지지 않게 함
            if (wasGalleryContextMenuVisible) {
                wasGalleryContextMenuVisible = false
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            mainHandler.postDelayed(sampleDragLocationRunnable, 60)
        } else {
            isGlobalDragging = false
            removeDragTracker()
        }
    }

    private fun grantUriPermissionsToActiveApp(uri: Uri) {
        runCatching {
            val allWin = windows
            for (w in allWin) {
                val pkg = w.root?.packageName?.toString()
                if (!pkg.isNullOrEmpty() && pkg != packageName) {
                    grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            val curPkg = rootInActiveWindow?.packageName?.toString()
            if (!curPkg.isNullOrEmpty() && curPkg != packageName) {
                grantUriPermission(curPkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
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

    /**
     * 드롭된 좌표(dropX, dropY)에 위치한 실제 타겟 윈도우 패키지명 조회 (분할화면/멀티윈도우 완벽 지원)
     */
    private fun resolveTargetPackageAtDropPoint(dropX: Int, dropY: Int): String {
        val allWin = runCatching { windows }.getOrNull() ?: emptyList()
        for (w in allWin) {
            if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                val r = Rect()
                w.getBoundsInScreen(r)
                val pkg = w.root?.packageName?.toString() ?: ""
                if (pkg.isNotEmpty() && pkg != packageName && r.contains(dropX, dropY)) {
                    return pkg
                }
            }
        }
        return rootInActiveWindow?.packageName?.toString() ?: ""
    }

    private fun executeForceDrop(payload: DragPayload, dropX: Int, dropY: Int) {
        val targetPkg = resolveTargetPackageAtDropPoint(dropX, dropY)
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
            if (isGalleryApp(targetPkg) || isMyFilesApp(targetPkg)) return
            sendOriginalMediaToTargetApp(
                uri = uri,
                additionalUris = payload.additionalUris,
                mimeType = payload.mimeType,
                allMimeTypes = payload.allMimeTypes,
                targetPackage = targetPkg
            )
        }
    }

    /**
     * [단일 및 다중 파일/미디어 타겟 앱 즉시 전송]
     * - 다중 항목일 때 ClipData에 모든 Uri를 담고 ACTION_SEND_MULTIPLE 인텐트와 URI 읽기 권한을 함께 부여
     */
    private fun sendOriginalMediaToTargetApp(
        uri: Uri,
        additionalUris: List<Uri>,
        mimeType: String,
        allMimeTypes: List<String>,
        targetPackage: String?
    ) {
        runCatching {
            val allUris = ArrayList<Uri>().apply {
                add(uri)
                addAll(additionalUris)
            }

            if (!targetPackage.isNullOrEmpty()) {
                for (u in allUris) {
                    runCatching {
                        grantUriPermission(targetPackage, u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
            }

            val mimeArray = allMimeTypes.ifEmpty { listOf(mimeType) }.distinct().toTypedArray()
            val clipDesc = ClipDescription("OriginalMultiMedia", mimeArray)
            val multiClip = ClipData(clipDesc, ClipData.Item(uri)).apply {
                for (extra in additionalUris) {
                    addItem(ClipData.Item(extra))
                }
            }

            val sendIntent = if (additionalUris.isEmpty()) {
                Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = multiClip
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (!targetPackage.isNullOrEmpty() && targetPackage != packageName) {
                        setPackage(targetPackage)
                    }
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = mimeType
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, allUris)
                    clipData = multiClip
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (!targetPackage.isNullOrEmpty() && targetPackage != packageName) {
                        setPackage(targetPackage)
                    }
                }
            }

            if (sendIntent.resolveActivity(packageManager) != null) {
                startActivity(sendIntent)
            } else {
                val chooserTitle = if (allUris.size > 1) {
                    "원본 ${allUris.size}개 항목 전송"
                } else {
                    "원본 파일/미디어 전송"
                }
                val chooser = Intent.createChooser(sendIntent.apply { setPackage(null) }, chooserTitle).apply {
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
        activePillThumbView = null
        activePillBadgeView = null
    }

    fun handleSharedUris(sourceUris: List<Uri>, mimeType: String?) {
        if (sourceUris.isEmpty()) return
        ioExecutor.execute {
            runCatching {
                val resolvedEntries = mutableListOf<ResolvedEntry>()
                for ((idx, srcUri) in sourceUris.withIndex()) {
                    val resolvedMime = contentResolver.getType(srcUri) ?: mimeType ?: "*/*"
                    val isVideo = resolvedMime.startsWith("video/")
                    val isImage = resolvedMime.startsWith("image/")
                    val meta = if (isVideo) extractVideoFrameAndSize(srcUri) else if (isImage) decodeSampledPreview(srcUri) else null

                    val finalUri = if (srcUri.authority == MediaStore.AUTHORITY || srcUri.authority == "$packageName.files") {
                        srcUri
                    } else {
                        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(resolvedMime)
                            ?: if (isVideo) "mp4" else if (isImage) "png" else "bin"
                        val fileName = "Original_${System.currentTimeMillis()}_$idx.$ext"
                        contentResolver.openInputStream(srcUri)?.use { input ->
                            insertInputStreamIntoMediaStore(
                                input, fileName, resolvedMime,
                                meta?.second ?: 0, meta?.third ?: 0, isVideo, isImage
                            )
                        } ?: srcUri
                    }

                    resolvedEntries.add(
                        ResolvedEntry(
                            key = "shared_$idx",
                            uri = finalUri,
                            mimeType = resolvedMime,
                            displayName = "공유항목_${idx + 1}",
                            width = meta?.second ?: 0,
                            height = meta?.third ?: 0,
                            previewBitmap = meta?.first,
                            isVideo = isVideo,
                            isImage = isImage
                        )
                    )
                }

                if (resolvedEntries.isNotEmpty()) {
                    val payload = buildMultiEntryPayload(resolvedEntries)
                    mainHandler.post {
                        showOrUpdateMediaOrFilePill(
                            payload,
                            resources.displayMetrics.widthPixels / 2,
                            resources.displayMetrics.heightPixels / 2
                        )
                    }
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
