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

    private val selectedMyFilesSessionMap = LinkedHashMap<String, ResolvedEntry>()
    private val visibleCheckedMyFilesKeys = mutableSetOf<String>()

    private val selectedGallerySessionMap = LinkedHashMap<String, ResolvedEntry>()
    private val visibleCheckedGalleryKeys = mutableSetOf<String>()
    private val touchedGalleryCellRects = mutableListOf<Rect>()
    private var lastObservedGalleryCount = 0
    private var wasGalleryContextMenuVisible = false
    private var lastGalleryInteractedRect = Rect()
    private var lastGalleryInteractedDesc = ""
    private var lastActiveAlbumTitle = ""

    private val isVisualSliceRunning = AtomicBoolean(false)
    private var lastVisualSliceTimestamp: Long = 0L

    private val autoDismissRunnable = Runnable { removeActiveOverlay() }

    private val launcherPackages = mutableSetOf(
        "com.sec.android.app.launcher",
        "com.google.android.apps.nexuslauncher",
        "com.teslacoilsw.launcher",
        "com.microsoft.launcher",
        "com.android.systemui"
    )

    // 연속 content-changed 이벤트를 150ms로 묶어 한 번만 스캔 (키보드/입력 끊김 방지)
    private val SCAN_DEBOUNCE_MS = 150L
    private var pendingScan: Runnable? = null
    private fun scheduleScan(block: () -> Unit) {
        pendingScan?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable { block() }
        pendingScan = r
        mainHandler.postDelayed(r, SCAN_DEBOUNCE_MS)
    }

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
        val now = SystemClock.uptimeMillis()
        if (zone != edgeZone) {
            edgeZone = zone; edgeSince = now; edgeFired = false
            return
        }
        if (zone == 0 || edgeFired || now - edgeSince < EDGE_DWELL_MS) return
        edgeFired = true
        val ok = performGlobalAction(if (zone == 3) GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN else GLOBAL_ACTION_RECENTS)
        if (ok) mainHandler.post { Toast.makeText(this, if (zone == 3) "분할 화면" else "최근 앱", Toast.LENGTH_SHORT).show() }
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

    private fun collectAppRoots(predicate: (String) -> Boolean): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        runCatching {
            for (w in windows) {
                val r = w.root ?: continue
                val pkg = r.packageName?.toString() ?: ""
                if (predicate(pkg)) {
                    roots.add(r)
                }
            }
        }
        val active = rootInActiveWindow
        if (active != null) {
            val pkg = active.packageName?.toString() ?: ""
            if (predicate(pkg) && roots.none { it == active }) {
                roots.add(active)
            }
        }
        return roots
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || isGlobalDragging) return
        val eventPkg = event.packageName?.toString() ?: ""
        val activePkg = rootInActiveWindow?.packageName?.toString() ?: ""
        val pkgName = if (eventPkg.isNotEmpty()) eventPkg else activePkg
        if (isHomeLauncher(pkgName)) return

        val now = System.currentTimeMillis()

        // 갤러리/내 파일/홈이 아닌 마지막 앱 기억 (자동 복귀 대상)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val activeApp = rootInActiveWindow?.packageName?.toString() ?: ""
            if (activeApp.isNotEmpty() && !isGalleryApp(activeApp) && !isMyFilesApp(activeApp) &&
                !isHomeLauncher(activeApp) && activeApp != "com.android.systemui"
            ) {
                lastForegroundAppPkg = activeApp
            }
        }

        // [A] 내 파일 앱 (com.sec.android.app.myfiles)
        if (isMyFilesApp(pkgName) || (collectAppRoots(::isMyFilesApp).isNotEmpty() && isMyFilesApp(eventPkg))) {
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_VIEW_SELECTED -> {
                    val forceFromLongClick = event.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
                    val srcRect = Rect()
                    event.source?.getBoundsInScreen(srcRect)
                    if (forceFromLongClick) {
                        mainHandler.postDelayed({ syncMyFilesMultiSelection(srcRect, true) }, 50L)
                    } else {
                        scheduleScan { syncMyFilesMultiSelection(srcRect, false) }
                    }
                }
            }
            return
        }

        // [B] 갤러리 앱 (com.sec.android.gallery3d)
        if (isGalleryApp(pkgName)) {
            if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                galleryEventLog.addLast(AccessibilityEvent.eventTypeToString(event.eventType).removePrefix("TYPE_"))
                while (galleryEventLog.size > 30) galleryEventLog.removeFirst()
            }
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
                mainHandler.postDelayed({ dumpGalleryDebug(collectAppRoots(::isGalleryApp), "long-click") }, 400L)
            }
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_VIEW_SELECTED,
                AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                    val isExplicitLong = event.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
                    val isDirectInteraction = isExplicitLong ||
                            event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                            event.eventType == AccessibilityEvent.TYPE_VIEW_SELECTED
                    val src = event.source
                    if (src != null) {
                        val r = Rect()
                        src.getBoundsInScreen(r)
                        val d = buildString {
                            src.contentDescription?.let { append(it).append(" ") }
                            src.text?.let { append(it).append(" ") }
                            src.stateDescription?.let { append(it) }
                        }.trim()
                        val screenW = resources.displayMetrics.widthPixels
                        val screenH = resources.displayMetrics.heightPixels
                        if (r.width() in dp(36f)..(screenW * 0.75f).toInt() &&
                            r.height() in dp(36f)..(screenH * 0.65f).toInt() &&
                            r.top > dp(80f)
                        ) {
                            lastGalleryInteractedRect.set(r)
                            if (isDirectInteraction) {
                                touchedGalleryCellRects.removeAll {
                                    hypot((it.centerX() - r.centerX()).toDouble(), (it.centerY() - r.centerY()).toDouble()) < dp(24f)
                                }
                                touchedGalleryCellRects.add(Rect(r))
                                if (touchedGalleryCellRects.size > 20) touchedGalleryCellRects.removeAt(0)
                            }
                            if (d.isNotEmpty() && !d.contains("복사") && !d.contains("삭제") && !d.contains("공유") && !d.contains("취소")) {
                                lastGalleryInteractedDesc = d
                            }
                        }
                    }

                    if (isExplicitLong) {
                        mainHandler.postDelayed({ syncGalleryMultiSelection(Rect(lastGalleryInteractedRect), true) }, 35L)
                    } else {
                        scheduleScan { syncGalleryMultiSelection(Rect(lastGalleryInteractedRect), false) }
                    }
                }
            }
            return
        }

        // [C] 브라우저, 챗GPT, 제미나이 등 실시간 텍스트 선택
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val extracted = extractSelectedTextFromSelectionEvent(event)
                if (extracted != null && extracted.first.isNotEmpty()) {
                    val (selText, bounds) = extracted
                    lastRealtimeSelectedText = selText
                    lastRealtimeTextTimestamp = now
                    lastSelectionBounds = bounds

                    // 사용자가 실제로 선택했을 때만 뜨는 복사/전체선택 툴바가 있어야 표시 (앱이 보내는 가짜 선택 이벤트 무시)
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

    override fun onInterrupt() {
        removeActiveOverlay()
        removeDragTracker()
    }

    override fun onDestroy() {
        instance = null
        removeActiveOverlay()
        removeDragTracker()
        super.onDestroy()
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    /**
     * [내 파일 다중 선택 실시간 동기화 엔진]
     */
    // 갤러리 목록 보기(파일명 행)도 같은 로직으로 처리. 파일명 행이 없으면 false (갤러리 썸네일 로직으로 넘어감)
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
            if (selectedMyFilesSessionMap.isNotEmpty() || visibleCheckedMyFilesKeys.isNotEmpty()) {
                selectedMyFilesSessionMap.clear()
                visibleCheckedMyFilesKeys.clear()
                if (currentPayload?.type != PayloadType.TEXT) removeActiveOverlay()
            }
            return false
        }
        val currentlyCheckedVisible = distinctVisible.filter { it.isChecked }
        val currentlyUncheckedVisibleNames = distinctVisible.filter { !it.isChecked }.map { it.fileName }.toSet()

        for (uncheckedName in currentlyUncheckedVisibleNames) {
            selectedMyFilesSessionMap.remove(uncheckedName)
        }

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
        // 보이는 체크 항목이 전체 선택이거나 새 꾹 누르기(1개)면 이전 선택 잔여 항목 제거
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
                mainHandler.post { Toast.makeText(this, "선택한 파일의 원본 위치를 찾지 못했어요. 모든 파일 접근 권한을 확인해주세요", Toast.LENGTH_SHORT).show() }
                return@execute
            }

            val payload = buildMultiEntryPayload(allEntries, hc)
            mainHandler.post {
                showOrUpdateMediaOrFilePill(payload, anchorRow.rect.centerX(), anchorRow.rect.top)
                scheduleSwitchBack(AUTO_SWITCH_SETTLE_MS)
            }
        }
        return true
    }

    /**
     * [v12.0 핵심 - 삼성 갤러리 One UI 앨범/사진 선택 모드 & 컨텍스트 메뉴 100% 감지 엔진]
     * - 조기 return 버그 완전 제거 (모든 자식 노드를 끝까지 스캔)
     * - 우상단 '취소' 버튼, 좌상단 숫자 배지('1', '2'...), 하단 '만들기/공유/삭제/더보기' 바 감지
     * - 흰색 컨텍스트 메뉴('클립보드에 복사', '앨범으로 복사', '앨범으로 이동', '공유 앨범에 추가', '즐겨찾기에 추가') 감지
     * - 현재 앨범명(예: '느노어어') 자동 추출 후 실제 폴더/MediaStore 버킷과 즉시 매칭
     */
    private fun syncGalleryMultiSelection(initialLongPressRect: Rect, isExplicitLongPress: Boolean) {
        if (isGlobalDragging) return
        if (pendingGalleryClipboard && activeOverlayView != null) return
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
        var explicitHeaderCount: Int? = null
        var topLeftPureNumberBadge: Int? = null
        var hasTopCancelButton = false
        val bottomSelectionBarHits = mutableSetOf<String>()

        val headerCountRegex = Regex("(\\d+)\\s*개\\s*선택")
        val albumSummaryCountRegex = Regex("^(이미지|사진|동영상|항목)\\s*\\d+\\s*(개|장)?$")
        val pureNumberRegex = Regex("^(\\d{1,3})$")

        var menuHasClipboardCopy = false
        var menuHasMake = false
        val contextMenuStrongKeywords = setOf(
            "클립보드에 복사", "앨범으로 복사", "앨범으로 이동", "공유 앨범에 추가", "즐겨찾기에 추가", "휴지통으로 이동", "보안 폴더로 이동"
        )
        val contextMenuAllKeywords = contextMenuStrongKeywords + setOf("만들기", "공유", "삭제")
        val matchedContextMenuRects = mutableListOf<Rect>()
        var hasStrongContextMenuKeyword = false

        val bottomBarKeywords = setOf("만들기", "공유", "삭제", "더보기")
        val bottomNavTabNames = setOf("사진", "앨범", "스토리", "메뉴", "검색", "Pictures", "Albums", "Stories", "Menu")
        val potentialAlbumTitleNodes = mutableListOf<Pair<Rect, String>>()

        fun scanGalleryTree(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 42) return
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.width() <= 0 || r.height() <= 0) return

            val ownText = node.text?.toString()?.trim() ?: ""
            val ownDesc = node.contentDescription?.toString()?.trim() ?: ""
            val ownState = node.stateDescription?.toString()?.trim() ?: ""
            val clsName = node.className?.toString() ?: ""

            // 1) "N개 선택됨" 텍스트 감지
            val countMatch = headerCountRegex.find(ownText) ?: headerCountRegex.find(ownDesc) ?: headerCountRegex.find(ownState)
            if (countMatch != null) {
                explicitHeaderCount = countMatch.groupValues[1].toIntOrNull()
            }

            // 2) One UI 갤러리 좌상단 숫자 배지 ('1', '2', '3'...) 감지
            if (r.top in 0..dp(150f) && r.centerX() < (screenW * 0.42f)) {
                val numStr = if (pureNumberRegex.matches(ownText)) ownText
                             else if (pureNumberRegex.matches(ownDesc)) ownDesc
                             else ""
                val numVal = numStr.toIntOrNull()
                if (numVal != null && numVal in 1..500) {
                    topLeftPureNumberBadge = numVal
                }
            }

            // 3) One UI 갤러리 우상단 '취소' 버튼 감지
            val label = if (ownText.isNotEmpty()) ownText else ownDesc
            if ((label == "취소" || label.equals("Cancel", true)) &&
                r.top in 0..dp(160f) && r.centerX() > (screenW * 0.55f)
            ) {
                hasTopCancelButton = true
            }

            // 4) 하단 선택 액션바 ('만들기', '공유', '삭제', '더보기') 감지
            if (label in bottomBarKeywords && r.top > (screenH * 0.68f)) {
                bottomSelectionBarHits.add(label)
            }

            // 5) 흰색 롱프레스 컨텍스트 메뉴 감지
            // [v12.3] 사진 보기 화면의 하트(즐겨찾기에 추가)·공유·삭제 아이콘 설명글에 속지 않도록, 메뉴 글자(text)만 봄
            if (ownText == "클립보드에 복사") menuHasClipboardCopy = true
            if (ownText == "만들기") menuHasMake = true
            if (label.isNotEmpty() && label.length <= 22) {
                if (ownText.isNotEmpty() && contextMenuStrongKeywords.any { ownText.contains(it) }) {
                    hasStrongContextMenuKeyword = true
                    matchedContextMenuRects.add(Rect(r))
                } else if (contextMenuAllKeywords.any { label == it } && r.top < (screenH * 0.85f)) {
                    matchedContextMenuRects.add(Rect(r))
                }
            }

            // 6) 현재 앨범 제목 후보 수집 (예: '느노어어')
            if (clsName.contains("TextView", true) &&
                ownText.length in 1..30 &&
                r.top in dp(40f)..(screenH * 0.45f).toInt() &&
                !pureNumberRegex.matches(ownText) &&
                !albumSummaryCountRegex.matches(ownText) &&
                ownText !in bottomNavTabNames &&
                ownText !in contextMenuAllKeywords &&
                ownText != "취소" && ownText != "더보기" &&
                !ownText.contains("선택")
            ) {
                potentialAlbumTitleNodes.add(Rect(r) to ownText)
            }

            // 7) 실제 사진/동영상 썸네일 셀 판별 (조기 return 없이 하위 노드도 계속 탐색!)
            val isSummaryCountLabel = albumSummaryCountRegex.matches(ownText) || albumSummaryCountRegex.matches(ownDesc)
            val isNavOrActionLabel = label in bottomNavTabNames || label in bottomBarKeywords ||
                    label == "취소" || contextMenuStrongKeywords.any { label.contains(it) }

            val isSingleThumbBounds = r.width() in dp(42f)..(screenW * 0.65f).toInt() &&
                                      r.height() in dp(42f)..(screenH * 0.52f).toInt() &&
                                      r.top > dp(80f)

            if (isSingleThumbBounds && !isSummaryCountLabel && !isNavOrActionLabel && !clsName.contains("TextView", true)) {
                var subChecked = node.isChecked || node.isSelected ||
                        ownState.contains("선택됨") || ownState.equals("Selected", true)
                val descBuilder = StringBuilder()
                if (ownDesc.isNotEmpty()) descBuilder.append(ownDesc).append(" ")
                if (ownState.isNotEmpty()) descBuilder.append(ownState).append(" ")
                if (ownText.isNotEmpty()) descBuilder.append(ownText).append(" ")

                fun inspectCellDescendants(c: AccessibilityNodeInfo?, d: Int) {
                    if (c == null || d > 4) return
                    val cst = c.stateDescription?.toString()?.trim() ?: ""
                    val ccd = c.contentDescription?.toString()?.trim() ?: ""
                    val ctx = c.text?.toString()?.trim() ?: ""
                    val notSel = cst.contains("선택 안 됨") || ccd.contains("선택 안 됨") ||
                                 cst.contains("Not selected", true) || ccd.contains("Not selected", true)
                    if (!notSel && (c.isChecked || c.isSelected ||
                                cst.contains("선택됨") || ccd.contains("선택됨") ||
                                cst.equals("Selected", true) || ccd.equals("Selected", true))
                    ) {
                        subChecked = true
                    }
                    if (ccd.isNotEmpty()) descBuilder.append(ccd).append(" ")
                    if (cst.isNotEmpty()) descBuilder.append(cst).append(" ")
                    if (ctx.isNotEmpty() && !albumSummaryCountRegex.matches(ctx)) descBuilder.append(ctx).append(" ")
                    for (k in 0 until c.childCount) inspectCellDescendants(c.getChild(k), d + 1)
                }

                for (i in 0 until node.childCount) {
                    inspectCellDescendants(node.getChild(i), 1)
                }

                val fullCellDesc = descBuilder.toString().trim()
                val hasNotSelectedFlag = fullCellDesc.contains("선택 안 됨") || fullCellDesc.contains("Not selected", true)
                if (!hasNotSelectedFlag && (fullCellDesc.contains("선택됨") || fullCellDesc.contains("Selected", true))) {
                    subChecked = true
                }

                val hasMediaMetadata = fullCellDesc.contains("년") ||
                        fullCellDesc.contains("월") ||
                        fullCellDesc.contains("사진") ||
                        fullCellDesc.contains("동영상") ||
                        fullCellDesc.contains("GIF", true) ||
                        fullCellDesc.contains("photo", true) ||
                        fullCellDesc.contains("video", true) ||
                        Regex("\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}").containsMatchIn(fullCellDesc)

                val isImageOrClickableCell = clsName.contains("ImageView", true) ||
                        (node.isClickable && node.isLongClickable) ||
                        hasMediaMetadata || subChecked

                if (isImageOrClickableCell) {
                    val normalizedDesc = fullCellDesc
                        .replace("선택됨", "")
                        .replace("선택 안 됨", "")
                        .replace("Selected", "", true)
                        .replace("Not selected", "", true)
                        .trim()
                    val cellKey = "cell_${r.left / dp(28f)}_${r.top / dp(28f)}_${normalizedDesc.take(24)}"
                    // 이미 동일한 좌표(중심점 거리 20dp 이내)에 등록된 부모/자식 셀이 있으면 더 구체적인 것으로 병합
                    val existingIdx = cells.indexOfFirst {
                        hypot((it.rect.centerX() - r.centerX()).toDouble(), (it.rect.centerY() - r.centerY()).toDouble()) < dp(22f)
                    }
                    if (existingIdx >= 0) {
                        val prev = cells[existingIdx]
                        val mergedDesc = if (fullCellDesc.length > prev.desc.length) fullCellDesc else prev.desc
                        cells[existingIdx] = GalleryCell(
                            key = prev.key,
                            rect = prev.rect,
                            desc = mergedDesc,
                            isExplicitlyChecked = prev.isExplicitlyChecked || subChecked
                        )
                    } else {
                        cells.add(GalleryCell(cellKey, Rect(r), fullCellDesc, subChecked))
                    }
                }
            }

            for (i in 0 until node.childCount) {
                scanGalleryTree(node.getChild(i), depth + 1)
            }
        }

        for (root in galleryRoots) {
            scanGalleryTree(root, 0)
        }

        // 앨범 제목 추출 (예: '느노어어')
        potentialAlbumTitleNodes.firstOrNull()?.second?.let { title ->
            if (title.isNotEmpty()) lastActiveAlbumTitle = title
        }

        // 꾹 누르기 메뉴에는 "만들기"와 "클립보드에 복사"가 함께 있음 (사진 보기 화면의 ⋮ 메뉴와 구분)
        val isGalleryContextMenuOpen = menuHasClipboardCopy && menuHasMake && hasStrongContextMenuKeyword && matchedContextMenuRects.isNotEmpty()
        val contextMenuUnionRect = if (isGalleryContextMenuOpen) {
            Rect(matchedContextMenuRects.first()).apply {
                for (mr in matchedContextMenuRects) union(mr)
            }
        } else null

        // 사진 보기 화면에도 하단 공유/삭제 바가 있으므로 선택 모드는 상단 "취소" 버튼으로만 판단
        val isSelectionActionBarVisible = hasTopCancelButton
        val effectiveSelectedCount = explicitHeaderCount
            ?: if (isSelectionActionBarVisible || isGalleryContextMenuOpen) (topLeftPureNumberBadge ?: 1) else null

        val orderedCells = cells
            .sortedWith(compareBy<GalleryCell> { it.rect.top / dp(28f) }.thenBy { it.rect.left })

        val checkedCells = orderedCells.filter { it.isExplicitlyChecked }

        val contextMenuJustOpened = isGalleryContextMenuOpen && !wasGalleryContextMenuVisible
        wasGalleryContextMenuVisible = isGalleryContextMenuOpen

        val isGalleryInSelectionState = checkedCells.isNotEmpty() ||
                isSelectionActionBarVisible ||
                isGalleryContextMenuOpen ||
                (effectiveSelectedCount != null && effectiveSelectedCount > 0)

        // 선택 모드가 완전히 해제된 경우 세션 초기화 및 팝업 닫기
        if (!isGalleryInSelectionState) {
            val hadGallerySelection = selectedGallerySessionMap.isNotEmpty() ||
                    visibleCheckedGalleryKeys.isNotEmpty() ||
                    lastObservedGalleryCount > 0
            selectedGallerySessionMap.clear()
            visibleCheckedGalleryKeys.clear()
            touchedGalleryCellRects.clear()
            lastObservedGalleryCount = 0
            if (hadGallerySelection && currentPayload?.type != PayloadType.TEXT) {
                removeActiveOverlay()
            }
            return
        }

        val targetCount = effectiveSelectedCount ?: max(1, checkedCells.size)
        val currentCheckedKeySet = checkedCells.map { it.key }.toSet()
        val hasCountChanged = targetCount != lastObservedGalleryCount
        val hasKeysChanged = currentCheckedKeySet != visibleCheckedGalleryKeys

        if (!isExplicitLongPress && !contextMenuJustOpened && !hasCountChanged && !hasKeysChanged && activeOverlayView != null) {
            return
        }

        lastObservedGalleryCount = targetCount
        visibleCheckedGalleryKeys.clear()
        visibleCheckedGalleryKeys.addAll(currentCheckedKeySet)

        // 팝업을 표시할 위치 계산 (흰색 컨텍스트 메뉴가 있으면 겹치지 않도록 상단/중앙에 배치)
        val anchorRect = when {
            contextMenuUnionRect != null -> contextMenuUnionRect
            checkedCells.isNotEmpty() -> checkedCells.last().rect
            !initialLongPressRect.isEmpty -> initialLongPressRect
            orderedCells.isNotEmpty() -> orderedCells.first().rect
            else -> Rect(screenW / 4, screenH / 3, screenW * 3 / 4, screenH / 2)
        }

        val popupX = (screenW / 2)
        val popupY = if (contextMenuUnionRect != null) {
            if (contextMenuUnionRect.top > dp(125f)) {
                contextMenuUnionRect.top - dp(10f)
            } else {
                dp(135f)
            }
        } else {
            anchorRect.top.coerceIn(dp(130f), screenH - dp(190f))
        }

        dumpGalleryDebug(galleryRoots, "selection state: cells=${cells.size} checked=${checkedCells.size} count=$targetCount menu=$isGalleryContextMenuOpen bar=$isSelectionActionBarVisible album=$lastActiveAlbumTitle")

        // [v12.1] 흰색 메뉴가 떠 있으면 추측하지 않고, 팝업을 잡는 순간 갤러리의 "클립보드에 복사"로 정확한 원본을 받아옴
        if (isGalleryContextMenuOpen) {
            mainHandler.post { showPendingGalleryClipboardPill(targetCount, popupX, popupY) }
            return
        }

        // 선택된 썸네일들의 그리드 인덱스 (0..N-1) 파악
        val selectedGridIndices = mutableListOf<Int>()
        if (checkedCells.isNotEmpty()) {
            for (cc in checkedCells) {
                val idx = orderedCells.indexOfFirst { it.key == cc.key }
                if (idx >= 0) selectedGridIndices.add(idx)
            }
        }
        if (selectedGridIndices.size < targetCount && orderedCells.isNotEmpty()) {
            for (tr in touchedGalleryCellRects.asReversed()) {
                val idx = orderedCells.indices.minByOrNull { i ->
                    hypot(
                        (orderedCells[i].rect.centerX() - tr.centerX()).toDouble(),
                        (orderedCells[i].rect.centerY() - tr.centerY()).toDouble()
                    )
                }
                if (idx != null && idx !in selectedGridIndices) {
                    selectedGridIndices.add(idx)
                    if (selectedGridIndices.size >= targetCount) break
                }
            }
        }
        // 어떤 셀을 골랐는지 모르면 첫 칸을 추측하지 않음 (엉뚱한 사진 방지)
        if (selectedGridIndices.isEmpty()) return
        val albumTitleSnapshot = lastActiveAlbumTitle
        val targetDescs = selectedGridIndices.mapNotNull { idx ->
            orderedCells.getOrNull(idx)?.desc
        }.ifEmpty { listOf(lastGalleryInteractedDesc) }

        ioExecutor.execute {
            val resolvedEntries = resolveGallerySelectionEntries(
                albumTitle = albumTitleSnapshot,
                targetCount = targetCount,
                selectedGridIndices = selectedGridIndices,
                cellDescs = targetDescs
            )

            if (resolvedEntries.isNotEmpty()) {
                selectedGallerySessionMap.clear()
                for (entry in resolvedEntries) {
                    selectedGallerySessionMap[entry.key] = entry
                }
                val payload = buildMultiEntryPayload(resolvedEntries, targetCount)
                mainHandler.post {
                    showOrUpdateMediaOrFilePill(payload, popupX, popupY)
                    scheduleSwitchBack(AUTO_SWITCH_SETTLE_MS)
                }
            }
        }
    }

    /**
     * [v12.0 핵심 - 갤러리 선택 미디어 3중 해결 엔진]
     * 1순위: 현재 앨범명(예: '느노어어')과 일치하는 폴더(File) 또는 MediaStore BUCKET_DISPLAY_NAME 내 파일들에서 그리드 인덱스로 정확히 추출
     * 2순위: MediaStore.Images / Video 날짜 및 최근 항목 매칭
     * 3순위: 외부 저장소(/storage/emulated/0/Pictures, DCIM, Download 등) 직접 파일 스캔 (FileProvider)
     */
    private fun resolveGallerySelectionEntries(
        albumTitle: String,
        targetCount: Int,
        selectedGridIndices: List<Int>,
        cellDescs: List<String>
    ): List<ResolvedEntry> {
        val results = mutableListOf<ResolvedEntry>()
        val usedUris = mutableSetOf<Uri>()

        // 1. 현재 앨범 이름(예: '느노어어')으로 폴더 및 MediaStore 버킷에서 파일 목록 조회
        val albumFiles = if (albumTitle.isNotEmpty()) {
            findFilesInAlbumBucketOrFolder(albumTitle)
        } else {
            emptyList()
        }

        if (albumFiles.isNotEmpty()) {
            for (gridIdx in selectedGridIndices) {
                val chosen = albumFiles.getOrNull(gridIdx) ?: albumFiles.firstOrNull { it.uri !in usedUris } ?: albumFiles.first()
                usedUris.add(chosen.uri)
                results.add(chosen.copy(key = "album_${albumTitle}_${gridIdx}_${chosen.displayName}"))
                if (results.size >= targetCount) break
            }
            while (results.size < targetCount) {
                val next = albumFiles.firstOrNull { it.uri !in usedUris } ?: break
                usedUris.add(next.uri)
                results.add(next.copy(key = "album_${albumTitle}_extra_${results.size}"))
            }
            if (results.isNotEmpty()) return results
        }

        // 2. 셀 설명(날짜/시간) 또는 전체 MediaStore + 파일시스템 최근 미디어에서 매칭
        for ((i, desc) in cellDescs.withIndex()) {
            val gridIdx = selectedGridIndices.getOrElse(i) { i }
            val entry = resolveExactGalleryMediaEntry(
                cellKey = "gal_sel_$i",
                nodeDesc = desc,
                screenCrop = null,
                sameTimeIndex = gridIdx,
                sameTimeTotal = max(targetCount, selectedGridIndices.size),
                isGridAscending = false,
                excludeUris = usedUris
            )
            if (entry != null) {
                usedUris.add(entry.uri)
                results.add(entry)
            }
        }

        // ponytail: no "recent files" filler - a guessed photo is worse than no popup; share -> ⚡ 원본 드래그 covers misses
        if (results.isEmpty()) {
            mainHandler.post { Toast.makeText(this, "원본을 확실히 찾지 못했어요. 공유 → ⚡ 원본 드래그를 써주세요", Toast.LENGTH_SHORT).show() }
        }
        return results
    }

    /**
     * 앨범 이름(예: '느노어어')에 해당하는 폴더 또는 MediaStore BUCKET_DISPLAY_NAME의 미디어 목록 반환
     */
    private fun findFilesInAlbumBucketOrFolder(albumName: String): List<ResolvedEntry> {
        val entries = mutableListOf<ResolvedEntry>()
        val mediaExts = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "mp4", "mov", "mkv", "webm")

        // A. 파일시스템에서 albumName과 이름이 같은 폴더 직접 탐색 (가장 빠르고 권한 제한 없음)
        val storageRoot = Environment.getExternalStorageDirectory()
        val candidateFolders = mutableListOf<File>()

        fun searchFolderByName(dir: File, depth: Int) {
            if (depth > 3 || !dir.exists() || !dir.isDirectory) return
            if (dir.name.equals(albumName, ignoreCase = true)) {
                candidateFolders.add(dir)
            }
            val children = dir.listFiles() ?: return
            for (c in children) {
                if (c.isDirectory && !c.name.startsWith(".") && c.name != "Android") {
                    searchFolderByName(c, depth + 1)
                }
            }
        }

        searchFolderByName(storageRoot, 0)

        for (folder in candidateFolders) {
            val files = folder.listFiles()?.filter { f ->
                f.isFile && f.extension.lowercase() in mediaExts &&
                !f.name.startsWith("OrigDrag_") && !f.name.startsWith("GalleryDrag_")
            }?.sortedByDescending { it.lastModified() } ?: continue

            for (f in files) {
                val uri = runCatching {
                    FileShareProvider.uriFor(this, f)
                }.getOrNull() ?: continue
                val ext = f.extension.lowercase()
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                    ?: if (ext in setOf("mp4", "mov", "mkv", "webm")) "video/mp4" else "image/jpeg"
                val isVid = mime.startsWith("video/")
                val meta = if (entries.size < 4) {
                    if (isVid) extractVideoFrameAndSize(uri) else decodeSampledPreview(uri)
                } else null

                entries.add(
                    ResolvedEntry(
                        key = f.absolutePath,
                        uri = uri,
                        mimeType = mime,
                        displayName = f.name,
                        width = meta?.second ?: 0,
                        height = meta?.third ?: 0,
                        previewBitmap = meta?.first,
                        isVideo = isVid,
                        isImage = !isVid
                    )
                )
            }
            if (entries.isNotEmpty()) return entries
        }

        // B. MediaStore에서 BUCKET_DISPLAY_NAME = albumName 조회
        val proj = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DATA
        )
        for ((colUri, isVidCol) in listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI to false,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to true
        )) {
            runCatching {
                contentResolver.query(
                    colUri,
                    proj,
                    "${MediaStore.MediaColumns.BUCKET_DISPLAY_NAME} = ?",
                    arrayOf(albumName),
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    while (cursor.moveToNext() && entries.size < 30) {
                        val id = cursor.getLong(0)
                        val name = cursor.getString(1) ?: "media"
                        val mime = cursor.getString(2) ?: if (isVidCol) "video/mp4" else "image/jpeg"
                        val w = cursor.getInt(3)
                        val h = cursor.getInt(4)
                        val dataPath = cursor.getString(5)
                        val uri = if (!dataPath.isNullOrEmpty() && File(dataPath).exists()) {
                            FileShareProvider.uriFor(this, File(dataPath))
                        } else {
                            ContentUris.withAppendedId(colUri, id)
                        }
                        val meta = if (entries.size < 4) {
                            if (isVidCol) extractVideoFrameAndSize(uri) else decodeSampledPreview(uri)
                        } else null
                        entries.add(
                            ResolvedEntry(
                                key = "${albumName}_$id",
                                uri = uri,
                                mimeType = mime,
                                displayName = name,
                                width = if (w > 0) w else (meta?.second ?: 0),
                                height = if (h > 0) h else (meta?.third ?: 0),
                                previewBitmap = meta?.first,
                                isVideo = isVidCol,
                                isImage = !isVidCol
                            )
                        )
                    }
                }
            }
        }

        return entries
    }

    /**
     * MediaStore가 비어있을 때 외부 저장소의 최근 이미지/영상 파일들을 직접 스캔하여 반환
     */
    private fun scanRecentMediaFilesDirectly(limit: Int): List<ResolvedEntry> {
        val mediaExts = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "mp4", "mov", "mkv", "webm")
        val collectedFiles = mutableListOf<File>()
        val root = Environment.getExternalStorageDirectory()

        val priorityDirs = listOf(
            File(root, "Pictures"),
            File(root, "DCIM"),
            File(root, "Download"),
            File(root, "Movies")
        )

        fun collectFromDir(dir: File, depth: Int) {
            if (depth > 3 || !dir.exists() || !dir.isDirectory) return
            if (dir.name.equals("UniversalDrag", ignoreCase = true) || dir.name.startsWith(".")) return
            val files = dir.listFiles() ?: return
            for (f in files) {
                if (f.isFile && f.extension.lowercase() in mediaExts &&
                    !f.name.startsWith("OrigDrag_") && !f.name.startsWith("GalleryDrag_")
                ) {
                    collectedFiles.add(f)
                } else if (f.isDirectory && depth < 3) {
                    collectFromDir(f, depth + 1)
                }
            }
        }

        for (d in priorityDirs) collectFromDir(d, 0)

        val sorted = collectedFiles.distinctBy { it.absolutePath }
            .sortedByDescending { it.lastModified() }
            .take(limit)

        return sorted.mapIndexedNotNull { idx, f ->
            val uri = runCatching {
                FileShareProvider.uriFor(this, f)
            }.getOrNull() ?: return@mapIndexedNotNull null
            val ext = f.extension.lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: if (ext in setOf("mp4", "mov", "mkv", "webm")) "video/mp4" else "image/jpeg"
            val isVid = mime.startsWith("video/")
            val meta = if (idx < 3) {
                if (isVid) extractVideoFrameAndSize(uri) else decodeSampledPreview(uri)
            } else null

            ResolvedEntry(
                key = f.absolutePath,
                uri = uri,
                mimeType = mime,
                displayName = f.name,
                width = meta?.second ?: 0,
                height = meta?.third ?: 0,
                previewBitmap = meta?.first,
                isVideo = isVid,
                isImage = !isVid
            )
        }
    }

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
        val anyMedia = entries.any { it.isImage || it.isVideo }

        val overallType = when {
            allImages -> PayloadType.IMAGE
            allVideos -> PayloadType.VIDEO
            !anyMedia -> PayloadType.FILE
            else -> PayloadType.FILE
        }

        val combinedMime = when {
            distinctMimes.size == 1 -> distinctMimes.first()
            allImages -> "image/*"
            allVideos -> "video/*"
            else -> "*/*"
        }

        val latestPreviewEntry = entries.lastOrNull { it.previewBitmap != null } ?: primary
        val summaryNames = if (entries.size == 1 && count == 1) {
            if (primary.sizeText.isNotEmpty()) "${primary.displayName} (${primary.sizeText})" else primary.displayName
        } else {
            val firstTwo = entries.take(2).joinToString(", ") { it.displayName }
            if (count > 2) "$firstTwo 외 ${count - 2}개" else firstTwo
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
            mainHandler.postDelayed(autoDismissRunnable, 9000)
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
                                    return@execute
                                }
                            }
                        }

                        // 하이라이트를 못 찾으면 주변 문단이나 "선택한 텍스트" 같은 가짜 내용을 띄우지 않음
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

        val overlayRect = Rect()
        activeOverlayView?.let { v ->
            val loc = IntArray(2); v.getLocationOnScreen(loc)
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

    private fun findNearestWebViewTextAroundToolbar(toolbarRect: Rect): String {
        val targetY = toolbarRect.bottom + dp(38f)
        var bestText = ""
        var bestDist = Int.MAX_VALUE

        val ignoreLabels = setOf(
            "복사", "모두 선택", "전체 선택", "공유", "번역", "웹 검색",
            "소리 내 읽기", "Gemini에게 물어보기", "삼성 패스에 저장", "앱 관리"
        )

        fun scan(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 45) return
            val t = node.text?.toString()?.trim()
            if (!t.isNullOrEmpty() && t.length >= 2 && t !in ignoreLabels) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (r.width() > dp(30f) && r.height() in dp(12f)..dp(350f)) {
                    val d = abs(r.centerY() - targetY)
                    if (d < bestDist) {
                        bestDist = d
                        bestText = t
                    }
                }
            }
            for (i in 0 until node.childCount) scan(node.getChild(i), depth + 1)
        }

        scan(rootInActiveWindow, 0)
        return bestText
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

            if (activeOverlayView != null && activePillTitleView != null && activePillBodyView != null) {
                activePillTitleView?.text = badgeText
                activePillBodyView?.text = previewSingleLine
                activePillBadgeView?.visibility = View.GONE
                activeOverlayParams?.let { lp ->
                    if (abs(lp.x - targetX) > dp(10f) || abs(lp.y - targetY) > dp(10f)) {
                        lp.x = targetX
                        lp.y = targetY
                        val overlay = activeOverlayView
                        if (overlay != null) runCatching { windowManager.updateViewLayout(overlay, lp) }
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
                return runCatching {
                    FileShareProvider.uriFor(this, direct)
                }.getOrNull()
            }
            val subFiles = dir.listFiles() ?: continue
            for (sub in subFiles) {
                if (sub.isDirectory && sub.name != "Android") {
                    val nested = File(sub, fileName)
                    if (nested.exists() && nested.isFile) {
                        return runCatching {
                            FileShareProvider.uriFor(this, nested)
                        }.getOrNull()
                    }
                }
            }
        }
        return null
    }

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
                MediaStore.MediaColumns.DATA
            )
            var sel = "${MediaStore.MediaColumns.DISPLAY_NAME} NOT LIKE 'OrigDrag_%' AND " +
                      "${MediaStore.MediaColumns.DISPLAY_NAME} NOT LIKE 'DragImage_%' AND " +
                      "${MediaStore.MediaColumns.DISPLAY_NAME} NOT LIKE 'GalleryDrag_%'"
            if (!extraWhere.isNullOrEmpty()) {
                sel = "$sel AND ($extraWhere)"
            }

            runCatching {
                contentResolver.query(
                    collectionUri,
                    proj,
                    sel,
                    extraArgs,
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    var c = 0
                    while (cursor.moveToNext() && c < limit) {
                        val id = cursor.getLong(0)
                        val name = cursor.getString(1) ?: "media"
                        val mime = cursor.getString(2) ?: if (isVidCollection) "video/mp4" else "image/jpeg"
                        val w = cursor.getInt(3)
                        val h = cursor.getInt(4)
                        val dt = cursor.getLong(5)
                        val dm = cursor.getLong(6) * 1000L
                        val dataPath = cursor.getString(7)
                        val ts = if (dt > 0L) dt else dm
                        val itemUri = if (!dataPath.isNullOrEmpty() && File(dataPath).exists()) {
                            FileShareProvider.uriFor(this, File(dataPath))
                        } else {
                            ContentUris.withAppendedId(collectionUri, id)
                        }
                        out.add(MediaCandidate(id, name, mime, w, h, isVidCollection, ts, itemUri))
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

        if (dateCandidates.size == 1) {
            val c = dateCandidates.first()
            return candidateToResolvedEntry(cellKey, c.uri, c.name, c.mime, c.width, c.height, c.isVideo)
        }

        // 날짜가 맞는 후보가 없으면 최근 사진 중 아무거나 고르지 않음
        if (dateCandidates.isEmpty()) return null
        val rawPool = if (isGridAscending) dateCandidates.sortedBy { it.timestampMs } else dateCandidates.sortedByDescending { it.timestampMs }

        val pool = rawPool.filter { it.uri !in excludeUris }.ifEmpty { rawPool }
        if (pool.isEmpty()) return null

        val fallbackIdx = sameTimeIndex.coerceIn(0, pool.size - 1)
        val chosen = pool[fallbackIdx]
        return candidateToResolvedEntry(cellKey, chosen.uri, chosen.name, chosen.mime, chosen.width, chosen.height, chosen.isVideo)
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
                        if (pendingGalleryClipboard && currentPayload?.uri == null) fetchGallerySelectionViaClipboard()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val activePayload = currentPayload ?: payload
                        // 원본을 받아오는 중이면 손가락을 유지한 채 기다렸다가 받아온 뒤 드래그 시작
                        if (activePayload.type != PayloadType.TEXT && activePayload.uri == null) return@setOnTouchListener true
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
            mainHandler.postDelayed(autoDismissRunnable, 9000)
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

    // ---------------- [v12.1] 갤러리 "클립보드에 복사"로 정확한 원본 받기 ----------------
    private var pendingGalleryClipboard = false
    private var galleryClipFetchRunning = false

    private fun showPendingGalleryClipboardPill(count: Int, x: Int, y: Int) {
        if (activeOverlayView != null && pendingGalleryClipboard) return
        pendingGalleryClipboard = true
        val payload = DragPayload(
            type = PayloadType.IMAGE,
            uri = null,
            allMimeTypes = listOf("image/*"),
            mimeType = "image/*",
            displayName = "갤러리 선택 항목",
            subtitleSummary = "잡으면 갤러리에서 원본을 가져와요",
            totalCount = max(1, count)
        )
        showQuickDragPill(payload, x, y)
        mainHandler.post { pendingGalleryClipboard = true }
    }

    private fun findGalleryNodeByLabel(label: String): AccessibilityNodeInfo? {
        fun walk(n: AccessibilityNodeInfo?, d: Int): AccessibilityNodeInfo? {
            if (n == null || d > 40) return null
            val t = n.text?.toString()?.trim() ?: n.contentDescription?.toString()?.trim() ?: ""
            if (t == label) return n
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)?.let { return it }
            return null
        }
        for (r in collectAppRoots(::isGalleryApp)) walk(r, 0)?.let { return it }
        return null
    }

    // ponytail: Android 10+ only lets the focused window read the clipboard, so the pill briefly takes focus
    private fun readClipboardWithFocus(cb: (ClipData?) -> Unit) {
        val v = activeOverlayView
        val lp = activeOverlayParams
        if (v == null || lp == null) { cb(runCatching { clipboardManager.primaryClip }.getOrNull()); return }
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        runCatching { windowManager.updateViewLayout(v, lp) }
        mainHandler.postDelayed({
            val clip = runCatching { clipboardManager.primaryClip }.getOrNull()
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            if (activeOverlayView === v) runCatching { windowManager.updateViewLayout(v, lp) }
            cb(clip)
        }, 70L)
    }

    private fun fetchGallerySelectionViaClipboard() {
        if (galleryClipFetchRunning) return
        var node = findGalleryNodeByLabel("클립보드에 복사")
        while (node != null && !node.isClickable) node = node.parent
        if (node == null) {
            Toast.makeText(this, "갤러리 메뉴의 '클립보드에 복사'를 찾지 못했어요", Toast.LENGTH_SHORT).show()
            return
        }
        galleryClipFetchRunning = true
        wasGalleryContextMenuVisible = false // 메뉴는 복사 클릭으로 닫히므로 드래그 시작 때 BACK을 보내지 않음
        val clickedAt = System.currentTimeMillis()
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        var tries = 0
        fun poll() {
            readClipboardWithFocus { clip ->
                val fresh = clip != null && clip.itemCount > 0 && clip.getItemAt(0).uri != null &&
                        clip.description.timestamp >= clickedAt - 300L
                if (fresh) {
                    val uris = (0 until clip!!.itemCount).mapNotNull { clip.getItemAt(it).uri }
                    ioExecutor.execute {
                        val entries = uris.mapIndexedNotNull { i, u -> clipUriToEntry(u, i) }
                        mainHandler.post {
                            galleryClipFetchRunning = false
                            if (entries.isEmpty()) {
                                Toast.makeText(this, "갤러리 원본을 읽지 못했어요", Toast.LENGTH_SHORT).show()
                                return@post
                            }
                            val p = buildMultiEntryPayload(entries, entries.size)
                            showOrUpdateMediaOrFilePill(p, 0, 0)
                            scheduleSwitchBack(0L)
                        }
                    }
                } else if (++tries < 12) {
                    mainHandler.postDelayed({ poll() }, 120L)
                } else {
                    galleryClipFetchRunning = false
                    Toast.makeText(this, "갤러리에서 복사된 원본을 받지 못했어요. 공유 → ⚡ 원본 드래그를 써주세요", Toast.LENGTH_SHORT).show()
                }
            }
        }
        mainHandler.postDelayed({ poll() }, 150L)
    }

    private fun clipUriToEntry(src: Uri, idx: Int): ResolvedEntry? = runCatching {
        val mime = contentResolver.getType(src) ?: "image/jpeg"
        var name = "gallery_$idx"
        contentResolver.query(src, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0)?.let { n -> name = n }
        }
        // MediaStore URI는 그대로, 갤러리 자체 URI는 우리 권한으로 넘길 수 없어 캐시에 복사 후 공유
        val uri = if (src.authority == MediaStore.AUTHORITY) src else {
            val dir = File(cacheDir, "gallery_clip").apply { mkdirs() }
            val out = File(dir, name.replace('/', '_'))
            contentResolver.openInputStream(src)?.use { input -> out.outputStream().use { input.copyTo(it) } } ?: return null
            FileShareProvider.uriFor(this, out)
        }
        val isVid = mime.startsWith("video/")
        val meta = if (idx < 3) (if (isVid) extractVideoFrameAndSize(uri) else decodeSampledPreview(uri)) else null
        ResolvedEntry(
            key = "clip_${idx}_$name", uri = uri, mimeType = mime, displayName = name,
            width = meta?.second ?: 0, height = meta?.third ?: 0, previewBitmap = meta?.first,
            isVideo = isVid, isImage = mime.startsWith("image/")
        )
    }.getOrNull()

    // ponytail: debug dump so a failing gallery screen can be diagnosed from one file; remove once gallery is stable
    private val galleryEventLog = ArrayDeque<String>()
    private var lastGalleryDumpAt = 0L
    private var galleryDumpToastShown = false
    private fun dumpGalleryDebug(roots: List<AccessibilityNodeInfo>, reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastGalleryDumpAt < 5000L || roots.isEmpty()) return
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
                .append(if (n.isSelected) " SELECTED" else "").append(if (n.isClickable) " click" else "")
                .append(if (n.isLongClickable) " long" else "")
                .append(" id=").append(n.viewIdResourceName?.substringAfterLast('/')).append('\n')
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        roots.forEach { walk(it, 0); sb.append("\n----\n") }
        ioExecutor.execute {
            runCatching {
                val f = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "UniversalDrag_gallery_debug.txt")
                f.writeText(sb.toString())
                if (!galleryDumpToastShown) {
                    galleryDumpToastShown = true
                    mainHandler.post { Toast.makeText(this, "갤러리 화면 구조 기록: Download/UniversalDrag_gallery_debug.txt", Toast.LENGTH_SHORT).show() }
                }
            }
        }
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

    // ---------------- [v12.2] 팝업이 뜨면 이전 앱으로 자동 전환 (설정에서 on/off) ----------------
    private var lastForegroundAppPkg = ""
    // ponytail: calibration knob - time to keep multi-selecting before switching away
    private val AUTO_SWITCH_SETTLE_MS = 1500L
    private val switchBackRunnable = Runnable {
        val target = lastForegroundAppPkg
        if (target.isEmpty() || activeOverlayView == null) return@Runnable
        val launch = packageManager.getLaunchIntentForPackage(target) ?: return@Runnable
        // 이미 떠 있는 작업을 그대로 앞으로 가져옴 (새로 시작하지 않음)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { startActivity(launch) }
        // 다른 앱으로 넘어가도 팝업은 잡을 수 있게 유지
        mainHandler.removeCallbacks(autoDismissRunnable)
        mainHandler.postDelayed(autoDismissRunnable, 12000)
    }

    private fun scheduleSwitchBack(delayMs: Long) {
        mainHandler.removeCallbacks(switchBackRunnable)
        if (!getSharedPreferences("settings", MODE_PRIVATE).getBoolean("auto_switch_back", true)) return
        mainHandler.postDelayed(switchBackRunnable, delayMs)
    }

    private fun removeActiveOverlay() {
        mainHandler.removeCallbacks(switchBackRunnable)
        pendingGalleryClipboard = false
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
