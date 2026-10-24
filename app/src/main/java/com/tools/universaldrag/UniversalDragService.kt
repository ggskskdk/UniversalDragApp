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
import android.util.TypedValue
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.MimeTypeMap
import android.widget.*
import java.io.File
import java.io.InputStream
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

    /**
     * [수정 3] 바탕화면(One UI 홈 등 모든 런처), 시스템 UI, 키보드, 설정 앱에서는
     * 아이콘을 꾹 눌러도 절대 팝업이 뜨지 않도록 패키지 블랙리스트 적용
     */
    private val ignoredPackages = mutableSetOf(
        "com.sec.android.app.launcher",         // 삼성 One UI 홈 화면
        "com.google.android.apps.nexuslauncher",// 픽셀 런처
        "com.teslacoilsw.launcher",             // 노바 런처
        "com.microsoft.launcher",               // MS 런처
        "com.android.systemui",                 // 상단바 / 네비게이션바 / 시스템 UI
        "com.android.settings",                 // 안드로이드 설정
        "com.samsung.android.honeyboard",       // 삼성 키보드
        "com.google.android.inputmethod.latin", // Gboard
        "com.samsung.android.app.cocktailbarservice", // 엣지 패널
        "com.sec.android.app.quicktool",
        "com.samsung.android.app.galaxyfinder",
        "com.sec.android.daemonapp"
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

    /**
     * 사용자가 명시적으로 '이미지 복사'를 눌렀을 때만 클립보드의 이미지 URI를 감지하여 띄움
     */
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (internalClipChange) {
            internalClipChange = false
            return@OnPrimaryClipChangedListener
        }
        val currentPkg = rootInActiveWindow?.packageName?.toString() ?: ""
        if (isIgnoredPackage(currentPkg)) return@OnPrimaryClipChangedListener

        val clip = clipboardManager.primaryClip ?: return@OnPrimaryClipChangedListener
        if (clip.itemCount > 0) {
            val item = clip.getItemAt(0)
            val clipUri = item.uri
            if (clipUri != null) {
                val mime = contentResolver.getType(clipUri) ?: clip.description.getMimeType(0) ?: ""
                if (mime.startsWith("image/") || mime.startsWith("video/")) {
                    handleSharedUri(clipUri, mime)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(clipListener)
        refreshLauncherBlacklist()
        setupDragLocationTracker()
    }

    /**
     * 기기에 설치된 모든 홈 런처 패키지를 동적으로 조회하여 블랙리스트에 추가
     */
    private fun refreshLauncherBlacklist() {
        runCatching {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveInfos = packageManager.queryIntentActivities(homeIntent, PackageManager.MATCH_ALL)
            for (info in resolveInfos) {
                info.activityInfo?.packageName?.let { ignoredPackages.add(it) }
            }
        }
    }

    private fun isIgnoredPackage(pkg: String): Boolean {
        if (pkg.isEmpty() || pkg == packageName) return true
        if (ignoredPackages.contains(pkg)) return true
        if (pkg.contains("launcher", ignoreCase = true) || pkg.contains("systemui", ignoreCase = true) || pkg.contains("inputmethod", ignoreCase = true)) {
            return true
        }
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || isGlobalDragging) return
        val pkgName = event.packageName?.toString() ?: ""

        // 홈 화면(바탕화면), 시스템 UI, 키보드 등에서는 모든 이벤트 즉시 무시!
        if (isIgnoredPackage(pkgName)) return

        val now = System.currentTimeMillis()

        when (event.eventType) {
            // 1. 일반 텍스트 드래그 블록 선택 시
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val source = event.source ?: return
                val fullText = source.text?.toString() ?: return
                val start = event.fromIndex
                val end = event.toIndex
                if (start >= 0 && end > start && end <= fullText.length) {
                    val selected = fullText.substring(start, end).trim()
                    if (selected.length >= 2) {
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

            // 2. 롱프레스(꾹 누르기) 시
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                if (now - lastEventTimestamp < 400) return
                val source = event.source ?: return
                handleLongPressedNode(source, pkgName, now)
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
     * [v5.0 정밀 롱프레스 필터]
     * - 버튼, 아이콘, 탭 바, 빈 배경에서는 절대 발동하지 않음
     * - 갤러리와 무관한 엉뚱한 최근 이미지(1523x491 등)를 불러오는 폴백 코드 100% 제거 완료
     */
    private fun handleLongPressedNode(node: AccessibilityNodeInfo, pkgName: String, timestamp: Long) {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val clsName = node.className?.toString() ?: ""

        // 1. 버튼, 아이콘, 탭, 스위치, 상하단 바 등 UI 컨트롤이면 무조건 무시!
        if (isUiControlOrIcon(node, clsName, rect)) return

        // 2. 내 파일 / 문서 앱에서 실제 파일 항목(.mp4, .jpg, .pdf, .zip 등)을 꾹 누른 경우에만 원본 파일 매칭
        val directOrChildText = extractGenuineContentText(node)
        if (!directOrChildText.isNullOrBlank()) {
            if (pkgName.contains("myfiles", true) || pkgName.contains("documentsui", true) || pkgName.contains("file", true)) {
                val matchedFile = tryResolveFileFromText(directOrChildText)
                if (matchedFile != null) {
                    lastEventTimestamp = timestamp
                    prepareOriginalFilePayload(matchedFile, rect.centerX(), rect.centerY())
                    return
                }
            }

            // 3. 본문 텍스트인 경우 (최소 2글자 이상 & 단순 아이콘 라벨 제외) -> 텍스트 드래그 영역 지정 창 오픈
            if (directOrChildText.length >= 2 && !isSingleWordIconLabel(node, directOrChildText, rect)) {
                lastEventTimestamp = timestamp
                showTextSelectionAndDragOverlay(directOrChildText, rect)
            }
            return
        }

        // 4. 텍스트가 없는 경우: 반드시 실제 대형 콘텐츠 ImageView(가로·세로 130dp 이상)일 때만 해당 이미지 영역 캡처
        //    (실패하더라도 절대 갤러리의 이전 스크린샷을 불러오지 않음!)
        if (isGenuineLargeContentImage(node, clsName, rect)) {
            lastEventTimestamp = timestamp
            captureLosslessTrimmedImage(rect)
        }
    }

    /**
     * 버튼, 툴바 아이콘, 앱 아이콘, 아바타 등 일반 UI 요소인지 판별
     */
    private fun isUiControlOrIcon(node: AccessibilityNodeInfo, clsName: String, rect: Rect): Boolean {
        val lowerCls = clsName.lowercase()
        if (lowerCls.contains("button") ||
            lowerCls.contains("checkbox") ||
            lowerCls.contains("switch") ||
            lowerCls.contains("tab") ||
            lowerCls.contains("toolbar") ||
            lowerCls.contains("actionmenu") ||
            lowerCls.contains("navigation")
        ) {
            return true
        }
        // 상태바 또는 하단 네비게이션 제스처 영역 근처 무시
        val screenH = resources.displayMetrics.heightPixels
        if (rect.top < dp(36f) || rect.bottom > screenH - dp(42f)) {
            return true
        }
        return false
    }

    /**
     * 앱 아이콘이나 하단 탭처럼 작은 영역에 짧은 단어 하나만 있는 라벨인지 판별
     */
    private fun isSingleWordIconLabel(node: AccessibilityNodeInfo, text: String, rect: Rect): Boolean {
        if (text.length <= 10 && !text.contains(" ") && rect.width() < dp(95f) && rect.height() < dp(95f)) {
            return true
        }
        return false
    }

    /**
     * 화면 전체 컨테이너나 작은 아이콘이 아닌, 실제 본문 내 대형 이미지(ImageView / PhotoView)인지 엄격히 검증
     */
    private fun isGenuineLargeContentImage(node: AccessibilityNodeInfo, clsName: String, rect: Rect): Boolean {
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels

        // 너무 작은 아이콘(130dp 미만)이거나 화면 전체 배경(화면 높이의 88% 이상)이면 이미지가 아님
        if (rect.width() < dp(130f) || rect.height() < dp(130f)) return false
        if (rect.height() > (screenH * 0.88f).toInt() && rect.width() >= (screenW * 0.98f).toInt()) return false

        // 자식 노드가 여러 개 들어있는 레이아웃 컨테이너(FrameLayout, LinearLayout, RecyclerView 등)는 이미지가 아님
        if (node.childCount > 1) return false

        val lowerCls = clsName.lowercase()
        return lowerCls.contains("imageview") ||
               lowerCls.contains("photoview") ||
               lowerCls.contains("subsamplingscaleimageview") ||
               lowerCls.contains("draweeview") ||
               lowerCls.contains("image")
    }

    private fun extractGenuineContentText(node: AccessibilityNodeInfo?): String? {
        if (node == null) return null
        val screenH = resources.displayMetrics.heightPixels
        val r = Rect()
        node.getBoundsInScreen(r)

        // 만약 이벤트 소스가 화면 전체를 덮는 최상위 루트 레이아웃이면 전체 화면 텍스트를 긁어오지 않도록 차단
        if (r.height() > (screenH * 0.85f).toInt() && node.childCount > 3) {
            return null
        }

        val direct = node.text?.toString()?.trim()
        if (!direct.isNullOrEmpty()) return direct

        val builder = StringBuilder()
        var textNodeCount = 0
        fun traverse(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 5 || textNodeCount > 8) return
            val cls = n.className?.toString()?.lowercase() ?: ""
            if (cls.contains("button") || cls.contains("toolbar")) return

            val t = n.text?.toString()?.trim()
            if (!t.isNullOrEmpty()) {
                if (builder.isNotEmpty()) builder.append("\n")
                builder.append(t)
                textNodeCount++
            }
            for (i in 0 until n.childCount) {
                traverse(n.getChild(i), depth + 1)
            }
        }
        traverse(node, 0)
        return if (builder.isNotEmpty()) builder.toString() else null
    }

    /**
     * 실제 대형 ImageView를 꾹 눌렀을 때만 해당 뷰 영역을 크롭하며,
     * 실패 시 절대 갤러리의 엉뚱한 이전 이미지(queryLatestMediaStoreItem)를 띄우지 않음!
     */
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

                            val fileName = "DragImage_${System.currentTimeMillis()}.png"
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
                // 아무것도 띄우지 않음 (엉뚱한 이전 캡처 이미지가 뜨던 현상 완전 제거)
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

    /**
     * 사용자가 명시적으로 공유(⚡ 원본 드래그)하거나 복사한 실제 원본 미디어 URI 처리
     */
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
            fileExtensions.any { ext -> line.lowercase().endsWith(ext) }
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
                    f.isFile && f.name.equals(candidateName, ignoreCase = true)
                }
                if (found != null) return found
            }
        }
        return null
    }

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
                    mainHandler.postDelayed(autoDismissRunnable, 8000)
                },
                onStartGlobalDrag = { sel ->
                    val payload = DragPayload(PayloadType.TEXT, text = sel, displayName = sel)
                    startGlobalDragSession(rootCard, params, payload)
                }
            )

            val scrollWrapper = ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(155f))
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
            mainHandler.postDelayed(autoDismissRunnable, 8000)
        }
    }

    /**
     * [수정 1 완료]
     * 불필요한 '방금 본 원본 영상으로 전환' 버튼 제거 및 우측 닫기(✕) 버튼 추가
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
                val maxSide = dp(68f).toFloat()
                val scale = minOf(maxSide / bw, maxSide / bh)
                val targetW = (bw * scale).toInt().coerceAtLeast(dp(34f))
                val targetH = (bh * scale).toInt().coerceAtLeast(dp(34f))

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
                "${payload.origWidth}×${payload.origHeight}"
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
                    payload.displayName.replace("\n", " ").take(20)
                } else {
                    "잡고 다른 앱으로 끌어다 놓으세요"
                }
                setTextColor(Color.parseColor("#A5B4FC"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
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
                x = (posX - dp(120f)).coerceIn(dp(16f), screenW - dp(250f))
                y = (posY - dp(68f)).coerceIn(dp(50f), screenH - dp(150f))
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
                            mainHandler.postDelayed(autoDismissRunnable, 4000)
                        }
                        true
                    }
                    else -> false
                }
            }

            attachDragEndListener(container)

            activeOverlayView = container
            windowManager.addView(container, params)
            mainHandler.postDelayed(autoDismissRunnable, 4500)
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
        if (isIgnoredPackage(targetPkg)) return

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
