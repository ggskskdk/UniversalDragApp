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
import java.net.HttpURLConnection
import java.net.URL
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
     * 브라우저/앱에서 '이미지 복사' 시 클립보드에 들어오는 원본 이미지 URI를 즉시 감지하여 무손실 드래그 캡슐로 표시
     */
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
        setupDragLocationTracker()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || isGlobalDragging) return
        if (event.packageName?.toString() == packageName) return

        val now = System.currentTimeMillis()

        when (event.eventType) {
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

            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                if (now - lastEventTimestamp < 350) return
                lastEventTimestamp = now
                val source = event.source ?: rootInActiveWindow ?: return
                handleLongPressedNode(source, event.packageName?.toString() ?: "")
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
     * [v4.0 롱프레스 지능형 분기]
     * 1. 비디오 플레이어 / 갤러리 / 최근 미디어인지 확인 -> 원본 비디오/이미지 MediaStore URI 직결
     * 2. 이미지/영상 원본 URL(http/https) 포함 여부 확인 -> 원본 고해상도 스트림 다운로드
     * 3. 텍스트 노드인 경우 -> 드래그 영역 지정 창 표시
     * 4. 순수 이미지 뷰인 경우 -> 최근 원본 MediaStore 매칭 또는 여백 자동 제거(Auto-Trim) 무손실 추출
     */
    private fun handleLongPressedNode(node: AccessibilityNodeInfo, pkgName: String) {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val clsName = node.className?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""

        // A. 비디오 뷰이거나 설명에 '동영상/Video'가 포함된 경우 -> 즉시 최신 원본 동영상 MediaStore URI 매칭!
        val looksLikeVideo = clsName.contains("Video", true) ||
                clsName.contains("Player", true) ||
                clsName.contains("SurfaceView", true) ||
                clsName.contains("TextureView", true) ||
                contentDesc.contains("동영상") ||
                contentDesc.contains("Video", true)

        if (looksLikeVideo) {
            val latestVideo = queryLatestMediaStoreItem(preferVideo = true)
            if (latestVideo != null) {
                showQuickDragPill(latestVideo, rect.centerX(), rect.centerY())
                return
            }
        }

        // B. 갤러리 / 내파일 / 포토 앱에서 롱프레스한 경우 -> MediaStore에서 원본 해상도 파일 직결!
        if (pkgName.contains("gallery", true) || pkgName.contains("photos", true) || pkgName.contains("documentsui", true) || pkgName.contains("myfiles", true)) {
            val matched = findMediaByNodeMetadata(node) ?: queryLatestMediaStoreItem(preferVideo = false)
            if (matched != null) {
                showQuickDragPill(matched, rect.centerX(), rect.centerY())
                return
            }
        }

        // C. 텍스트 및 파일명 추출 검사
        val extractedText = extractNodeTextDeep(node)
        if (!extractedText.isNullOrBlank()) {
            // 파일명이 포함된 경우 실제 원본 파일 매칭
            val matchedFile = tryResolveFileFromText(extractedText)
            if (matchedFile != null) {
                prepareOriginalFilePayload(matchedFile, rect.centerX(), rect.centerY())
                return
            }
            // 이미지/미디어 직접 URL인 경우 원본 다운로드
            if (extractedText.startsWith("http") && (extractedText.contains("twimg.com") || fileExtensions.any { extractedText.lowercase().contains(it) })) {
                downloadOriginalUrlToMediaStore(extractedText, rect.centerX(), rect.centerY())
                return
            }
            // 일반 텍스트(챗GPT, 클로드, X 본문 등) -> 드래그 영역 지정 오버레이 오픈
            showTextSelectionAndDragOverlay(extractedText, rect)
            return
        }

        // D. 텍스트가 없는 이미지/미디어 뷰 롱프레스 -> 무손실 원본 비율 추출 + [원본 파일 전환] 버튼 제공
        val mediaRect = findBestMediaBounds(node, rect)
        if (mediaRect.width() > dp(32f) && mediaRect.height() > dp(32f)) {
            captureLosslessTrimmedImage(mediaRect)
        }
    }

    /**
     * [원본 해상도/비율 보장 1]
     * MediaStore에서 가장 최근 저장/열람된 원본 영상 또는 원본 이미지의 시스템 URI(content://media/external/...)와 원본 해상도를 그대로 가져옴
     */
    private fun queryLatestMediaStoreItem(preferVideo: Boolean): DragPayload? {
        runCatching {
            val collection = if (preferVideo) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Files.getContentUri("external")
            }

            val selection = if (preferVideo) {
                null
            } else {
                "${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}"
            }

            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.WIDTH,
                MediaStore.MediaColumns.HEIGHT,
                MediaStore.MediaColumns.DATA
            )

            contentResolver.query(
                collection,
                projection,
                selection,
                null,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    val name = cursor.getString(1) ?: "media"
                    val mime = cursor.getString(2) ?: if (preferVideo) "video/mp4" else "image/jpeg"
                    var w = cursor.getInt(3)
                    var h = cursor.getInt(4)
                    val isVideo = mime.startsWith("video/")

                    val baseUri = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    val contentUri = ContentUris.withAppendedId(baseUri, id)

                    val preview = if (isVideo) {
                        extractVideoFrameAndSize(contentUri)?.also {
                            if (w == 0 || h == 0) {
                                w = it.second
                                h = it.third
                            }
                        }?.first
                    } else {
                        decodeSampledPreview(contentUri)?.also {
                            if (w == 0 || h == 0) {
                                w = it.second
                                h = it.third
                            }
                        }?.first
                    }

                    return DragPayload(
                        type = if (isVideo) PayloadType.VIDEO else PayloadType.IMAGE,
                        uri = contentUri,
                        mimeType = mime,
                        displayName = name,
                        origWidth = w,
                        origHeight = h,
                        previewBitmap = preview
                    )
                }
            }
        }
        return null
    }

    private fun findMediaByNodeMetadata(node: AccessibilityNodeInfo): DragPayload? {
        val desc = (node.contentDescription?.toString() ?: "") + " " + (node.text?.toString() ?: "")
        val matchedFile = tryResolveFileFromText(desc)
        if (matchedFile != null) {
            return buildPayloadFromFile(matchedFile)
        }
        return null
    }

    /**
     * [원본 해상도/비율 보장 2]
     * 화면에서 직접 추출해야 하는 경우:
     * 1. 절대 임의로 축소/리사이즈하지 않고 원본 1:1 픽셀 유지
     * 2. 상하좌우 레터박스(검은 여백/흰 여백/배경색)를 자동으로 감지해 도려내는 Auto-Trim 알고리즘 적용
     * 3. 모든 채팅앱이 즉시 수용하는 시스템 MediaStore URI(content://media/external/images/media/...)로 등록
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
                            // 상하좌우 불필요한 네모 여백(레터박스) 자동 제거 -> 순수 원본 이미지 비율 복원!
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
                // 캡처 쿨타임 시 즉시 최신 MediaStore 원본으로 폴백
                val latest = queryLatestMediaStoreItem(preferVideo = false)
                if (latest != null) {
                    showQuickDragPill(latest, cropRect.centerX(), cropRect.centerY())
                }
            }
        })
    }

    /**
     * [핵심 알고리즘]
     * 네모난 ImageView 컨테이너 안에 상하(또는 좌우) 여백이 포함된 경우,
     * 단색 배경/레터박스 라인을 스캔하여 잘라내고 실제 이미지의 원본 비율과 크롭 영역만 정확히 추출
     */
    private fun trimUniformBorders(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        if (w < 60 || h < 60) return src

        fun colorDist(c1: Int, c2: Int): Int {
            val dr = abs(Color.red(c1) - Color.red(c2))
            val dg = abs(Color.green(c1) - Color.green(c2))
            val db = abs(Color.blue(c1) - Color.blue(c2))
            return dr + dg + db
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
        while (top < h * 0.42f && isRowUniform(top, topColor)) {
            top += 2
        }

        val bottomColor = src.getPixel(w / 2, h - 3)
        var bottom = h - 1
        while (bottom > h * 0.58f && isRowUniform(bottom, bottomColor)) {
            bottom -= 2
        }

        val leftColor = src.getPixel(2, (top + bottom) / 2)
        var left = 0
        while (left < w * 0.42f && isColUniform(left, leftColor, top, bottom)) {
            left += 2
        }

        val rightColor = src.getPixel(w - 3, (top + bottom) / 2)
        var right = w - 1
        while (right > w * 0.58f && isColUniform(right, rightColor, top, bottom)) {
            right -= 2
        }

        val newW = (right - left + 1).coerceAtLeast(1)
        val newH = (bottom - top + 1).coerceAtLeast(1)

        return if (newW >= w * 0.25f && newH >= h * 0.25f && (newW < w - 6 || newH < h - 6)) {
            Bitmap.createBitmap(src, left, top, newW, newH)
        } else {
            src
        }
    }

    /**
     * X(트위터)나 웹 이미지 URL 감지 시 최고 화질(?name=orig) 원본 스트림을 다운로드하여 MediaStore에 등록
     */
    private fun downloadOriginalUrlToMediaStore(rawUrl: String, cx: Int, cy: Int) {
        ioExecutor.execute {
            runCatching {
                val highResUrl = if (rawUrl.contains("pbs.twimg.com/media/")) {
                    rawUrl.replace(Regex("name=[a-zA-Z0-9_]+"), "name=orig")
                } else rawUrl

                val conn = (URL(highResUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 7000
                    doInput = true
                    connect()
                }
                val contentType = conn.contentType ?: "image/jpeg"
                val bytes = conn.inputStream.use { it.readBytes() }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    val ext = if (contentType.contains("png")) "png" else "jpg"
                    val name = "OrigWeb_${System.currentTimeMillis()}.$ext"
                    val uri = insertStreamOrBytesIntoMediaStore(bytes, name, contentType, bmp.width, bmp.height, isVideo = false)
                    if (uri != null) {
                        val payload = DragPayload(
                            type = PayloadType.IMAGE,
                            uri = uri,
                            mimeType = contentType,
                            displayName = name,
                            origWidth = bmp.width,
                            origHeight = bmp.height,
                            previewBitmap = createAspectRatioPreview(bmp, 480)
                        )
                        mainHandler.post { showQuickDragPill(payload, cx, cy) }
                    }
                }
            }
        }
    }

    /**
     * 공유 인텐트(ShareReceiveActivity) 또는 클립보드에서 들어온 원본 이미지/영상/파일 URI를
     * 리사이즈나 재압축 없이 100% 원본 바이트 그대로 MediaStore URI로 바인딩
     */
    fun handleSharedUri(sourceUri: Uri, mimeType: String?) {
        ioExecutor.execute {
            runCatching {
                val resolvedMime = mimeType ?: contentResolver.getType(sourceUri) ?: "*/*"
                val isVideo = resolvedMime.startsWith("video/")
                val isImage = resolvedMime.startsWith("image/")

                // 이미 MediaStore URI인 경우 복사조차 하지 않고 원본 URI 직결!
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

                // 외부 앱의 임시 URI인 경우 원본 바이트 스트림을 그대로(무손실 1:1) MediaStore에 등록
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

    /**
     * [핵심] 타겟 채팅앱이 100% 신뢰하고 즉시 첨부하는 시스템 MediaStore URI 생성기 (무손실 원본 저장)
     */
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
            // PNG 무손실 100% 원본 저장
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        return uri
    }

    private fun insertStreamOrBytesIntoMediaStore(
        bytes: ByteArray,
        displayName: String,
        mimeType: String,
        w: Int,
        h: Int,
        isVideo: Boolean
    ): Uri? {
        val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val relPath = if (isVideo) Environment.DIRECTORY_MOVIES + "/UniversalDrag" else Environment.DIRECTORY_PICTURES + "/UniversalDrag"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (w > 0) put(MediaStore.MediaColumns.WIDTH, w)
            if (h > 0) put(MediaStore.MediaColumns.HEIGHT, h)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(collection, values) ?: return null
        contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
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
        contentResolver.openOutputStream(uri)?.use { out ->
            input.copyTo(out)
        }
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
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }

    private fun decodeSampledPreview(uri: Uri): Triple<Bitmap, Int, Int>? {
        return runCatching {
            val boundsOpt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOpt) }
            val origW = boundsOpt.outWidth
            val origH = boundsOpt.outHeight
            if (origW <= 0 || origH <= 0) return null

            var sampleSize = 1
            while (origW / sampleSize > 600 || origH / sampleSize > 600) {
                sampleSize *= 2
            }
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
            if (frame != null) {
                Triple(createAspectRatioPreview(frame, 480), w, h)
            } else null
        }.getOrNull()
    }

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
     * 텍스트 드래그 영역 지정 오버레이
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
     * [v4.0 원본 비율 프리뷰 캡슐]
     * - 이미지/영상의 원본 가로·세로 비율 그대로 썸네일을 표시하고 원본 해상도(예: 1920×1080) 표기
     * - 영상 플레이어 위에서 롱프레스했을 때를 위해 우측에 [🎬 최근 원본 영상] 원터치 전환 버튼 탑재
     */
    fun showQuickDragPill(payload: DragPayload, posX: Int, posY: Int) {
        mainHandler.post {
            removeActiveOverlay()
            val screenW = resources.displayMetrics.widthPixels
            val screenH = resources.displayMetrics.heightPixels

            val container = FrameLayout(this)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(18f).toFloat()
                    setColor(Color.parseColor("#F218181B"))
                    setStroke(dp(1.8f), Color.parseColor("#818CF8"))
                }
                elevation = dp(12f).toFloat()
            }

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            // 원본 비율을 정확히 유지하는 프리뷰 이미지뷰
            if (payload.previewBitmap != null && !payload.previewBitmap.isRecycled) {
                val bw = payload.previewBitmap.width.toFloat()
                val bh = payload.previewBitmap.height.toFloat()
                val maxSide = dp(84f).toFloat()
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
                topRow.addView(imgView)
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
                PayloadType.FILE -> "📁 파일 (${payload.displayName})"
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
                    "잡고 다른 앱 채팅창으로 끌어다 놓으세요"
                }
                setTextColor(Color.parseColor("#A5B4FC"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            }

            infoCol.addView(titleTv)
            infoCol.addView(subTv)
            topRow.addView(infoCol)
            card.addView(topRow)

            // 이미지 캡슐인 경우 하단에 [🎬 최근 원본 영상으로 전환] 버튼 제공
            if (payload.type == PayloadType.IMAGE) {
                val switchVideoBtn = TextView(this).apply {
                    text = "🎬 방금 본 원본 영상(.mp4)으로 전환"
                    setTextColor(Color.parseColor("#E0E7FF"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    gravity = Gravity.CENTER
                    setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(6f)
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(8f).toFloat()
                        setColor(Color.parseColor("#336366F1"))
                    }
                    setOnClickListener {
                        val latestVid = queryLatestMediaStoreItem(preferVideo = true)
                        if (latestVid != null) {
                            showQuickDragPill(latestVid, posX, posY)
                        } else {
                            Toast.makeText(this@UniversalDragService, "최근 저장된 동영상 파일이 없습니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                card.addView(switchVideoBtn)
            }

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

            topRow.setOnTouchListener { _, ev ->
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

    /**
     * [v4.0 글로벌 드래그 세션]
     * - ClipDescription에 실제 MIME 타입(image/png, video/mp4 등)을 명시적으로 세팅하여 모든 채팅앱이 미디어 드롭을 즉시 인식하도록 보장
     */
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

            // 핵심: 타겟 앱의 OnDragListener / OnReceiveContentListener가 요구하는 구체적 MIME 배열 명시!
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
                        // 타겟 앱이 기본 드롭을 받지 않은 경우 -> 즉시 강제 드롭(Force-Drop) 실행
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

    /**
     * [v4.0 미디어 & 텍스트 강제 드롭 엔진]
     */
    private fun executeForceDrop(payload: DragPayload, dropX: Int, dropY: Int) {
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
            val targetPkg = rootInActiveWindow?.packageName?.toString()

            // 1. 드롭 지점의 채팅창 입력 노드에 리치 클립보드 붙여넣기(ACTION_PASTE) 먼저 시도
            val editable = findEditableNodeNear(dropX, dropY) ?: findBestTargetEditable(dropX, dropY)
            if (payload.type == PayloadType.IMAGE && editable != null) {
                editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                if (editable.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                    return
                }
            }

            // 2. 드롭을 자체 지원하지 않는 채팅앱(카카오톡·X·디스코드 등)에 현재 열린 앱 패키지로 원본 미디어 직결 전송
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
