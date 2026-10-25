package com.tools.universaldrag

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.TextUtils
import android.util.Size
import android.util.TypedValue
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.MimeTypeMap
import android.widget.*
import java.io.File
import java.io.InputStream
import java.util.Calendar
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot

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

    private var activeOverlayView: View? = null
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

    private val autoDismissRunnable = Runnable { removeActiveOverlay() }

    // 오직 바탕화면(홈 런처)과 시스템 상단바에서만 팝업을 차단하고 나머지 모든 앱(챗GPT, 클로드, X, 갤러리 등)은 100% 허용
    private val launcherPackages = mutableSetOf(
        "com.sec.android.app.launcher",
        "com.google.android.apps.nexuslauncher",
        "com.teslacoilsw.launcher",
        "com.microsoft.launcher",
        "com.android.systemui"
    )

    private val fileExtensions = listOf(
        ".jpg", ".jpeg", ".png", ".webp", ".gif", ".heic",
        ".mp4", ".mov", ".mkv", ".webm", ".avi",
        ".pdf", ".zip", ".rar", ".7z", ".apk", ".psd", ".ai",
        ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".txt", ".csv", ".json", ".mp3", ".wav"
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

    /**
     * 이전에 임시 생성되어 MediaStore에 남아있던 1523x491 등 임시 캡처 파일을 자동 정리하여 갤러리 오염 방지
     */
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || isGlobalDragging) return
        val pkgName = event.packageName?.toString() ?: ""

        // 바탕화면(홈 화면 아이콘) 롱프레스만 차단, 나머지 모든 앱은 v4.0 그대로 작동!
        if (isHomeLauncher(pkgName)) return

        val now = System.currentTimeMillis()

        when (event.eventType) {
            // 1. 기본 텍스트 블록 드래그 선택 감지 (v4.0 동일)
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val source = event.source ?: return
                val fullText = source.text?.toString() ?: return
                val start = event.fromIndex
                val end = event.toIndex
                if (start >= 0 && end > start && end <= fullText.length) {
                    val selected = fullText.substring(start, end).trim()
                    if (selected.isNotEmpty()) {
                        val rect = Rect()
                        source.getBoundsInScreen(rect)
                        lastEventTimestamp = now
                        showQuickDragPill(
                            DragPayload(PayloadType.TEXT, text = selected, displayName = selected),
                            rect.centerX(),
                            rect.top
                        )
                    }
                }
            }

            // 2. 롱프레스 감지 (v4.0 텍스트 추출 + 갤러리 전용 원본 매칭)
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                if (now - lastEventTimestamp < 350) return
                lastEventTimestamp = now
                val source = event.source ?: rootInActiveWindow ?: return
                handleLongPressedNode(source, pkgName)
            }

            // 3. 삼성 갤러리에서 썸네일을 길게 눌러 '선택 체크(Selection Mode)'가 켜졌을 때 감지
            AccessibilityEvent.TYPE_VIEW_SELECTED -> {
                if (!isGalleryApp(pkgName)) return
                if (now - lastEventTimestamp < 450) return
                val source = event.source ?: return
                lastEventTimestamp = now
                handleGalleryNodeSelection(source)
            }
        }
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

    /**
     * [v6.0 핵심 핸들러]
     * - 갤러리 앱(com.sec.android.gallery3d / Google Photos)인 경우: 갤러리 전용 원본 이미지/영상 매칭 엔진 실행!
     * - 그 외 모든 앱(챗GPT, 클로드, X, 제미나이, 내 파일 등): v4.0의 텍스트 플로팅바 & 파일 매칭 로직 100% 그대로 실행!
     */
    private fun handleLongPressedNode(node: AccessibilityNodeInfo, pkgName: String) {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        // [A] 삼성 갤러리 / 포토 앱에서 사진·영상을 꾹 누른 경우 -> 갤러리 전용 원본 추출!
        if (isGalleryApp(pkgName)) {
            handleGalleryNodeSelection(node)
            return
        }

        // [B] v4.0과 100% 동일한 텍스트 추출 (챗GPT, 클로드, X, 제미나이 등 모든 앱에서 플로팅바 즉시 작동)
        val extractedText = extractNodeTextDeep(node)
        if (!extractedText.isNullOrBlank()) {
            val matchedFile = tryResolveFileFromText(extractedText)
            if (matchedFile != null) {
                prepareOriginalFilePayload(matchedFile, rect.centerX(), rect.centerY())
                return
            }
            // 챗GPT, 클로드, X, 제미나이 본문 텍스트 -> v4.0 영역 지정 플로팅바 표시!
            showTextSelectionAndDragOverlay(extractedText, rect)
            return
        }

        // [C] 일반 앱 내 이미지 뷰 롱프레스 시 해당 이미지 영역만 크롭 (엉뚱한 이전 캡처 폴백 없음)
        val mediaRect = findBestMediaBounds(node, rect)
        if (mediaRect.width() > dp(45f) && mediaRect.height() > dp(45f)) {
            captureLosslessTrimmedImage(mediaRect)
        }
    }

    /**
     * [갤러리 전용 원본 이미지·영상 매칭 엔진]
     * 삼성 갤러리의 목록(썸네일) 또는 상세 보기 화면에서 길게 누른 사진/영상을
     * 1) 화면 내 파일명/상세정보 텍스트 탐색
     * 2) 갤러리 접근성 설명(contentDescription: "사진, 2026년 10월 25일..." / "동영상...")의 날짜·시간 매칭
     * 3) 터치한 썸네일 영역과 MediaStore 원본 썸네일 간의 시각적 픽셀 매칭(Visual Fingerprint)
     * 을 통해 100% 실제 원본 MediaStore URI(content://media/external/...)로 가져옵니다.
     */
    private fun handleGalleryNodeSelection(node: AccessibilityNodeInfo) {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val cx = if (rect.width() > 0) rect.centerX() else resources.displayMetrics.widthPixels / 2
        val cy = if (rect.height() > 0) rect.centerY() else resources.displayMetrics.heightPixels / 2

        val desc = collectGalleryMetadataFromNode(node)
        val windowFileText = findFilenameInActiveWindow()

        // 화면 캡처를 동시에 수행하여 썸네일 시각적 매칭(Visual Hash)에 사용
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
                            targetCrop = trimUniformBorders(Bitmap.createBitmap(swBitmap, left, top, width, height))
                        }
                    }

                    val matchedPayload = resolveExactGalleryMedia(desc, windowFileText, targetCrop)
                    if (matchedPayload != null) {
                        mainHandler.post { showQuickDragPill(matchedPayload, cx, cy) }
                    } else if (targetCrop != null) {
                        // 만약 기기에 없는 클라우드 전용 이미지인 경우 크롭된 원본 비율 비트맵으로 제공
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
                    val matchedPayload = resolveExactGalleryMedia(desc, windowFileText, null)
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

    private fun findFilenameInActiveWindow(): String? {
        val root = rootInActiveWindow ?: return null
        var foundName: String? = null

        fun traverse(n: AccessibilityNodeInfo?) {
            if (n == null || foundName != null) return
            val t = n.text?.toString()?.trim() ?: ""
            if (t.isNotEmpty() && fileExtensions.any { ext -> t.lowercase().endsWith(ext) }) {
                foundName = t
                return
            }
            for (i in 0 until n.childCount) traverse(n.getChild(i))
        }
        traverse(root)
        return foundName
    }

    /**
     * 갤러리에서 선택한 항목의 메타데이터(파일명, 날짜/시간, 동영상 여부) 및 시각적 썸네일 유사도를 비교해
     * MediaStore에서 정확히 일치하는 실제 원본 사진/영상을 반환 (UniversalDrag 임시 폴더는 100% 제외!)
     */
    private fun resolveExactGalleryMedia(
        nodeDesc: String,
        windowFilename: String?,
        screenCrop: Bitmap?
    ): DragPayload? {
        val isVideoHint = nodeDesc.contains("동영상") || nodeDesc.contains("video", true) ||
                (windowFilename != null && listOf(".mp4", ".mov", ".mkv", ".webm", ".avi").any { windowFilename.lowercase().endsWith(it) })

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

        // 핵심: UniversalDrag가 생성한 임시 이미지들은 쿼리에서 영구 제외하여 1523x491 캡처가 절대 뜨지 않게 함!
        val baseSelection = "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR " +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}) AND " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} NOT LIKE 'OrigDrag_%' AND " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} NOT LIKE 'DragImage_%' AND " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} NOT LIKE 'GalleryDrag_%'"

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

        val candidates = mutableListOf<MediaCandidate>()

        runCatching {
            contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection,
                baseSelection,
                null,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                var count = 0
                while (cursor.moveToNext() && count < 100) {
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
                    candidates.add(MediaCandidate(id, name, mime, w, h, isVid, dateTaken, contentUri))
                    count++
                }
            }
        }

        if (candidates.isEmpty()) return null

        // 1순위: 화면에 파일명이 표시되어 있다면 파일명 정확히 일치하는 원본 반환
        if (!windowFilename.isNullOrBlank()) {
            val byName = candidates.firstOrNull { it.name.equals(windowFilename, ignoreCase = true) }
            if (byName != null) return candidateToPayload(byName.uri, byName.name, byName.mime, byName.width, byName.height, byName.isVideo)
        }

        // 2순위: 삼성 갤러리의 contentDescription("2026년 10월 25일 오전 9시 03분" 등) 날짜·시간 파싱 매칭
        val parsedTimeRange = parseKoreanOrEnglishDateRange(nodeDesc)
        val filteredByType = if (isVideoHint) {
            candidates.filter { it.isVideo }.ifEmpty { candidates }
        } else {
            candidates
        }

        val timeMatched = if (parsedTimeRange != null) {
            filteredByType.filter { it.dateTakenMs in parsedTimeRange.first..parsedTimeRange.second }
        } else {
            emptyList()
        }

        if (timeMatched.size == 1) {
            val c = timeMatched.first()
            return candidateToPayload(c.uri, c.name, c.mime, c.width, c.height, c.isVideo)
        }

        // 3순위: 터치한 영역의 화면 이미지(screenCrop)와 MediaStore 썸네일 간의 픽셀 유사도(Visual Fingerprint) 비교!
        val pool = if (timeMatched.isNotEmpty()) timeMatched else filteredByType.take(40)
        if (screenCrop != null) {
            val targetSig = computeVisualSignature(screenCrop)
            var bestCandidate: MediaCandidate? = null
            var bestDist = Long.MAX_VALUE

            for (cand in pool) {
                runCatching {
                    val thumb = contentResolver.loadThumbnail(cand.uri, Size(64, 64), null)
                    val trimmedThumb = trimUniformBorders(thumb)
                    val sig = computeVisualSignature(trimmedThumb)
                    val dist = signatureDistance(targetSig, sig)
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

        // 4순위: 날짜 매칭된 첫 항목 또는 최신 원본 항목 반환
        val fallbackCand = timeMatched.firstOrNull() ?: filteredByType.first()
        return candidateToPayload(
            fallbackCand.uri,
            fallbackCand.name,
            fallbackCand.mime,
            fallbackCand.width,
            fallbackCand.height,
            fallbackCand.isVideo
        )
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
     * 삼성 갤러리의 한국어 날짜 설명(예: "2026년 10월 25일 오전 9시 3분" 또는 "2026년 10월 25일")을 파싱하여 타임스탬프 범위 반환
     */
    private fun parseKoreanOrEnglishDateRange(desc: String): Pair<Long, Long>? {
        val dateRegex = Regex("(\\d{4})년\\s*(\\d{1,2})월\\s*(\\d{1,2})일")
        val match = dateRegex.find(desc) ?: return null
        val year = match.groupValues[1].toIntOrNull() ?: return null
        val month = (match.groupValues[2].toIntOrNull() ?: return null) - 1
        val day = match.groupValues[3].toIntOrNull() ?: return null

        val timeRegex = Regex("(오전|오후)?\\s*(\\d{1,2})(?:시|:)\\s*(\\d{1,2})분?")
        val timeMatch = timeRegex.find(desc.substring(match.range.last))

        val cal = Calendar.getInstance()
        if (timeMatch != null) {
            val ampm = timeMatch.groupValues[1]
            var hour = timeMatch.groupValues[2].toIntOrNull() ?: 0
            val minute = timeMatch.groupValues[3].toIntOrNull() ?: 0
            if (ampm == "오후" && hour < 12) hour += 12
            if (ampm == "오전" && hour == 12) hour = 0

            cal.set(year, month, day, hour, minute, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis - 90_000L
            val end = cal.timeInMillis + 150_000L
            return start to end
        } else {
            cal.set(year, month, day, 0, 0, 0)
            val start = cal.timeInMillis - 60_000L
            cal.set(year, month, day, 23, 59, 59)
            val end = cal.timeInMillis + 60_000L
            return start to end
        }
    }

    /**
     * 12x12 RGB 퍼셉추얼 시그니처 계산 (터치한 갤러리 사진과 MediaStore 원본 매칭용)
     */
    private fun computeVisualSignature(bmp: Bitmap): IntArray {
        val scaled = Bitmap.createScaledBitmap(bmp, 12, 12, true)
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

    /**
     * [v4.0 원본 그대로 복원]
     * 챗GPT, 클로드, X(트위터), 제미나이 등 모든 앱의 텍스트를 제한 없이 깊숙이 추출
     */
    private fun extractNodeTextDeep(node: AccessibilityNodeInfo?): String? {
        if (node == null) return null
        val direct = node.text?.toString()?.trim()
        if (!direct.isNullOrEmpty()) return direct

        val builder = StringBuilder()
        fun traverse(n: AccessibilityNodeInfo?) {
            if (n == null) return
            val t = n.text?.toString()?.trim()
            if (!t.isNullOrEmpty()) {
                if (builder.isNotEmpty()) builder.append("\n")
                builder.append(t)
            }
            for (i in 0 until n.childCount) traverse(n.getChild(i))
        }
        traverse(node)
        return if (builder.isNotEmpty()) builder.toString() else null
    }

    private fun findBestMediaBounds(node: AccessibilityNodeInfo, fallbackRect: Rect): Rect {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            val r = Rect()
            current.getBoundsInScreen(r)
            val cls = current.className?.toString() ?: ""
            if (cls.contains("Image", true) || cls.contains("Photo", true) || cls.contains("Video", true)) {
                if (r.width() > dp(40f) && r.height() > dp(40f)) return r
            }
            current = current.parent
        }
        return fallbackRect
    }

    private fun captureLosslessTrimmedImage(cropRect: Rect) {
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                ioExecutor.execute {
                    try {
                        val hwBuffer = screenshot.hardwareBuffer
                        val hwBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, screenshot.colorSpace)
                        val swBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                        hwBuffer.close()

                        if (swBitmap != null) {
                            val left = cropRect.left.coerceIn(0, swBitmap.width - 1)
                            val top = cropRect.top.coerceIn(0, swBitmap.height - 1)
                            val width = cropRect.width().coerceIn(1, swBitmap.width - left)
                            val height = cropRect.height().coerceIn(1, swBitmap.height - top)

                            val rawCrop = Bitmap.createBitmap(swBitmap, left, top, width, height)
                            val trimmed = trimUniformBorders(rawCrop)

                            val fileName = "OrigDrag_${System.currentTimeMillis()}.png"
                            val mediaUri = insertBitmapIntoMediaStore(trimmed, fileName, "image/png")

                            if (mediaUri != null) {
                                val payload = DragPayload(
                                    type = PayloadType.IMAGE,
                                    uri = mediaUri,
                                    mimeType = "image/png",
                                    displayName = fileName,
                                    origWidth = trimmed.width,
                                    origHeight = trimmed.height,
                                    previewBitmap = createAspectRatioPreview(trimmed, 480)
                                )
                                mainHandler.post {
                                    showQuickDragPill(payload, cropRect.centerX(), cropRect.centerY())
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            override fun onFailure(errorCode: Int) {
                // 캡처 실패 시 엉뚱한 이전 이미지를 불러오지 않음
            }
        })
    }

    private fun trimUniformBorders(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        if (w < 60 || h < 60) return src

        fun colorDist(c1: Int, c2: Int): Int {
            return abs(Color.red(c1) - Color.red(c2)) +
                   abs(Color.green(c1) - Color.green(c2)) +
                   abs(Color.blue(c1) - Color.blue(c2))
        }

        fun isRowUniform(y: Int, refColor: Int): Boolean {
            val step = (w / 24).coerceAtLeast(2)
            var uniformCount = 0
            var total = 0
            var x = step
            while (x < w - step) {
                total++
                if (colorDist(src.getPixel(x, y), refColor) <= 18) uniformCount++
                x += step
            }
            return total > 0 && (uniformCount.toFloat() / total) >= 0.90f
        }

        fun isColUniform(x: Int, refColor: Int, topY: Int, bottomY: Int): Boolean {
            val span = bottomY - topY
            if (span <= 20) return false
            val step = (span / 24).coerceAtLeast(2)
            var uniformCount = 0
            var total = 0
            var y = topY + step
            while (y < bottomY - step) {
                total++
                if (colorDist(src.getPixel(x, y), refColor) <= 18) uniformCount++
                y += step
            }
            return total > 0 && (uniformCount.toFloat() / total) >= 0.90f
        }

        val topColor = src.getPixel(w / 2, 2)
        var top = 0
        while (top < h * 0.42f && isRowUniform(top, topColor)) top += 2

        val bottomColor = src.getPixel(w / 2, h - 3)
        var bottom = h - 1
        while (bottom > h * 0.58f && isRowUniform(bottom, bottomColor)) bottom -= 2

        val leftColor = src.getPixel(2, (top + bottom) / 2)
        var left = 0
        while (left < w * 0.42f && isColUniform(left, leftColor, top, bottom)) left += 2

        val rightColor = src.getPixel(w - 3, (top + bottom) / 2)
        var right = w - 1
        while (right > w * 0.58f && isColUniform(right, rightColor, top, bottom)) right -= 2

        val newW = (right - left + 1).coerceAtLeast(1)
        val newH = (bottom - top + 1).coerceAtLeast(1)

        return if (newW >= w * 0.25f && newH >= h * 0.25f && (newW < w - 6 || newH < h - 6)) {
            Bitmap.createBitmap(src, left, top, newW, newH)
        } else {
            src
        }
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

    private fun prepareOriginalFilePayload(file: File, cx: Int, cy: Int) {
        ioExecutor.execute {
            val payload = buildPayloadFromFile(file)
            if (payload != null) {
                mainHandler.post { showQuickDragPill(payload, cx, cy) }
            }
        }
    }

    private fun buildPayloadFromFile(file: File): DragPayload? {
        return runCatching {
            val ext = file.extension.lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
            val isVideo = mime.startsWith("video/")
            val isImage = mime.startsWith("image/")

            var origW = 0
            var origH = 0
            var preview: Bitmap? = null

            if (isImage) {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, opts)
                origW = opts.outWidth
                origH = opts.outHeight
                val full = BitmapFactory.decodeFile(file.absolutePath)
                if (full != null) preview = createAspectRatioPreview(full, 480)
            } else if (isVideo) {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(file.absolutePath)
                origW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                origH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val frame = retriever.frameAtTime
                if (frame != null) preview = createAspectRatioPreview(frame, 480)
                retriever.release()
            }

            val mediaUri = file.inputStream().use { input ->
                insertInputStreamIntoMediaStore(input, file.name, mime, origW, origH, isVideo, isImage)
            } ?: return null

            DragPayload(
                type = when {
                    isImage -> PayloadType.IMAGE
                    isVideo -> PayloadType.VIDEO
                    else -> PayloadType.FILE
                },
                uri = mediaUri,
                mimeType = mime,
                displayName = file.name,
                origWidth = origW,
                origHeight = origH,
                previewBitmap = preview
            )
        }.getOrNull()
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

    private fun tryResolveFileFromText(text: String): File? {
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val candidateName = lines.firstOrNull { line ->
            fileExtensions.any { ext -> line.lowercase().contains(ext) }
        } ?: return null

        val searchDirs = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        )
        for (dir in searchDirs) {
            if (dir != null && dir.exists()) {
                val found = dir.walkTopDown().maxDepth(3).firstOrNull { f ->
                    f.isFile && (f.name.equals(candidateName, ignoreCase = true) || candidateName.contains(f.name, ignoreCase = true))
                }
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * [v4.0 그대로 유지] 텍스트 드래그 영역 지정 플로팅바 오버레이
     */
    fun showTextSelectionAndDragOverlay(fullText: String, sourceRect: Rect? = null) {
        mainHandler.post {
            removeActiveOverlay()

            val screenW = resources.displayMetrics.widthPixels
            val screenH = resources.displayMetrics.heightPixels

            val rootCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(18f).toFloat()
                    setColor(Color.parseColor("#F518181B"))
                    setStroke(dp(1.8f), Color.parseColor("#6366F1"))
                }
                elevation = dp(12f).toFloat()
                setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
            }

            var currentSelected = fullText

            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6f), dp(2f), dp(6f), dp(6f))
            }

            val guideLabel = TextView(this).apply {
                text = "① 드래그해 영역 지정  →  ② 파란 영역 잡고 이동"
                setTextColor(Color.parseColor("#A5B4FC"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val closeBtn = TextView(this).apply {
                text = "✕"
                setTextColor(Color.parseColor("#A1A1AA"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(dp(10f), dp(2f), dp(6f), dp(2f))
                setOnClickListener { removeActiveOverlay() }
            }
            header.addView(guideLabel)
            header.addView(closeBtn)

            val dragBar = TextView(this).apply {
                text = "⠿ 선택 영역 바로 끌기 (${fullText.length}자)"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(12f).toFloat()
                    setColor(Color.parseColor("#4F46E5"))
                }
            }

            val params = WindowManager.LayoutParams(
                (screenW * 0.90f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (screenW * 0.05f).toInt()
                val targetY = sourceRect?.top ?: (screenH / 3)
                y = targetY.coerceIn(dp(60f), screenH - dp(280f))
            }

            val selectableView = RangeSelectableTextView(
                context = this,
                rawText = fullText,
                onSelectionChanged = { sel ->
                    currentSelected = sel
                    val preview = if (sel.length > 18) sel.take(18).replace("\n", " ") + "…" else sel.replace("\n", " ")
                    dragBar.text = "⠿  \"$preview\" 끌어서 이동 (${sel.length}자)"
                    mainHandler.removeCallbacks(autoDismissRunnable)
                    mainHandler.postDelayed(autoDismissRunnable, 9000)
                },
                onStartGlobalDrag = { sel ->
                    val payload = DragPayload(PayloadType.TEXT, text = sel, displayName = sel)
                    startGlobalDragSession(rootCard, params, payload)
                }
            )

            val scrollWrapper = ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(160f))
                addView(selectableView)
            }

            var barStartX = 0f
            var barStartY = 0f
            dragBar.setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        barStartX = ev.rawX
                        barStartY = ev.rawY
                        lastDropX = ev.rawX
                        lastDropY = ev.rawY
                        mainHandler.removeCallbacks(autoDismissRunnable)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!isGlobalDragging && hypot((ev.rawX - barStartX).toDouble(), (ev.rawY - barStartY).toDouble()) > dp(6f)) {
                            val payload = DragPayload(PayloadType.TEXT, text = currentSelected, displayName = currentSelected)
                            startGlobalDragSession(rootCard, params, payload)
                        }
                        true
                    }
                    else -> false
                }
            }

            rootCard.addView(header)
            rootCard.addView(scrollWrapper)
            rootCard.addView(dragBar)

            attachDragEndListener(rootCard)

            activeOverlayView = rootCard
            windowManager.addView(rootCard, params)
            mainHandler.postDelayed(autoDismissRunnable, 9000)
        }
    }

    /**
     * 원본 비율 썸네일 & 파일 드래그 캡슐
     */
    fun showQuickDragPill(payload: DragPayload, posX: Int, posY: Int) {
        mainHandler.post {
            removeActiveOverlay()
            val screenW = resources.displayMetrics.widthPixels
            val screenH = resources.displayMetrics.heightPixels

            val container = FrameLayout(this)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(18f).toFloat()
                    setColor(Color.parseColor("#F218181B"))
                    setStroke(dp(1.8f), Color.parseColor("#818CF8"))
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
                PayloadType.TEXT -> "⠿ 텍스트 드래그"
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
                    payload.displayName.replace("\n", " ").take(22)
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
                setOnClickListener { removeActiveOverlay() }
            }
            card.addView(closeBtn)

            container.addView(card)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (posX - dp(125f)).coerceIn(dp(16f), screenW - dp(260f))
                y = (posY - dp(70f)).coerceIn(dp(50f), screenH - dp(160f))
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
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!isGlobalDragging && hypot((ev.rawX - startX).toDouble(), (ev.rawY - startY).toDouble()) > dp(6f)) {
                            startGlobalDragSession(container, params, payload)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!isGlobalDragging) {
                            mainHandler.postDelayed(autoDismissRunnable, 4500)
                        }
                        true
                    }
                    else -> false
                }
            }

            attachDragEndListener(container)

            activeOverlayView = container
            windowManager.addView(container, params)
            mainHandler.postDelayed(autoDismissRunnable, 5500)
        }
    }

    private fun startGlobalDragSession(
        hostView: View,
        hostParams: WindowManager.LayoutParams,
        payload: DragPayload
    ) {
        if (isGlobalDragging) return
        isGlobalDragging = true
        currentPayload = payload
        forceDropHandledByTracker = false
        preDropFocusedText = getFocusedEditableText()

        val clipData: ClipData
        var flags = View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_OPAQUE

        if (payload.type == PayloadType.TEXT) {
            val txt = payload.text ?: ""
            internalClipChange = true
            clipboardManager.setPrimaryClip(ClipData.newPlainText("UniversalDragText", txt))
            clipData = ClipData.newPlainText("UniversalDragText", txt)
        } else {
            val uri = payload.uri ?: return
            grantReadPermissionToAllApps(uri)

            val mimeArray = arrayOf(payload.mimeType, ClipDescription.MIMETYPE_TEXT_URILIST)
            val description = ClipDescription(payload.displayName, mimeArray)
            clipData = ClipData(description, ClipData.Item(uri))

            internalClipChange = true
            runCatching { clipboardManager.setPrimaryClip(clipData) }
            flags = flags or View.DRAG_FLAG_GLOBAL_URI_READ
        }

        val resTag = if (payload.origWidth > 0 && payload.origHeight > 0) {
            "${payload.origWidth}×${payload.origHeight} • 원본"
        } else {
            "원본 유지"
        }

        val badgeTitle = when (payload.type) {
            PayloadType.TEXT -> "TEXT (${payload.text?.length ?: 0}자)"
            PayloadType.IMAGE -> "🖼️ $resTag"
            PayloadType.VIDEO -> "🎬 VIDEO ($resTag)"
            PayloadType.FILE -> "📁 ${payload.displayName}"
        }

        val shadowBuilder = UniversalDragShadowBuilder(
            context = this,
            titleBadge = badgeTitle,
            subtitleText = payload.text ?: payload.displayName,
            previewBitmap = payload.previewBitmap
        )

        val started = hostView.startDragAndDrop(clipData, shadowBuilder, null, flags)
        if (started) {
            hostView.alpha = 0f
            hostParams.flags = hostParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            runCatching { windowManager.updateViewLayout(hostView, hostParams) }
            mainHandler.postDelayed(sampleDragLocationRunnable, 60)
        } else {
            isGlobalDragging = false
        }
    }

    private fun attachDragEndListener(container: View) {
        container.setOnDragListener { _, event ->
            if (event.action == DragEvent.ACTION_DRAG_ENDED) {
                isGlobalDragging = false
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

    private fun grantReadPermissionToAllApps(uri: Uri) {
        runCatching {
            val packages = packageManager.getInstalledPackages(PackageManager.GET_ACTIVITIES)
            for (pkg in packages) {
                grantUriPermission(pkg.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
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
                node.refresh()
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
    }
}
