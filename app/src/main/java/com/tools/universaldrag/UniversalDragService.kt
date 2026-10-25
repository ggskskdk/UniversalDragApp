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
        val previewBitmap: Bitmap? = null
    )

    private lateinit var windowManager: WindowManager
    private lateinit var clipboardManager: ClipboardManager
    private lateinit var vibrator: Vibrator
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
        // ponytail: no full-screen touch interceptor. A touchable overlay window swallows every touch on the
        // phone (Android never forwards a window's touches to the app below), so long-press detection relies
        // on TYPE_VIEW_LONG_CLICKED. Apps that suppress that event still get text via the selection toolbar.
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
            vibrator.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || isGlobalDragging) return
        val pkgName = event.packageName?.toString() ?: ""
        if (isHomeLauncher(pkgName)) return

        val now = System.currentTimeMillis()

        when (event.eventType) {
            // 텍스트 블록 선택 시 즉시 캡슐 지원
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

            // 시스템 표준 롱프레스 이벤트가 오는 일반 앱 및 갤러리 지원
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                if (now - lastEventTimestamp < 350) return
                val source = event.source ?: rootInActiveWindow ?: return
                lastEventTimestamp = now
                if (isGalleryApp(pkgName)) {
                    handleGalleryNodeSelection(source)
                } else {
                    val rect = Rect()
                    source.getBoundsInScreen(rect)
                    val textNodes = findTextNodesAround(rect.centerX(), rect.centerY())
                    if (textNodes.isNotEmpty()) {
                        triggerHapticFeedback()
                        startInlineTextSweepSession(textNodes, rect.centerX().toFloat(), rect.centerY().toFloat())
                    }
                }
            }

            // 삼성 갤러리 썸네일 체크 선택 시
            AccessibilityEvent.TYPE_VIEW_SELECTED -> {
                if (!isGalleryApp(pkgName)) return
                if (now - lastEventTimestamp < 350) return
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
     * 손가락이 누른 화면 좌표 (x, y) 주변의 모든 문장/문단 노드들의 화면 좌표(Rect)와 텍스트를 추출
     */
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

        // 터치한 Y 좌표 기준으로 상하 400dp 범위 내의 문단들을 세로 순서대로 정렬
        val rangeY = dp(400f)
        return collected.filter {
            abs(it.rect.centerY() - touchY) <= rangeY
        }.sortedBy { it.rect.top }
    }

    /**
     * [v7.0 핵심 - 원래 화면 글자 위에서 바로 드래그해 영역 지정]
     * 별도의 팝업창을 띄우지 않고, 현재 화면 위에 직접 반투명 파란색 선택 영역 레이어를 얹음.
     */
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
                    // 손가락을 떼지 않고 바로 글로벌 드래그로 변환!
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
            windowManager.addView(sweepView, params)
            mainHandler.postDelayed(autoDismissRunnable, 10000)
        }
    }

    /**
     * 화면 위에서 지정된 텍스트 영역 또는 미디어 캡슐을 잡고 끄는 순간 즉시 발동되는 원스톱 글로벌 드래그
     */
    private fun startGlobalDragDirectly(payload: DragPayload) {
        val overlay = activeOverlayView ?: return
        if (isGlobalDragging) return
        isGlobalDragging = true
        currentPayload = payload
        forceDropHandledByTracker = false
        preDropFocusedText = getFocusedEditableText()

        val clipData: ClipData
        var flags = View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_OPAQUE
        val uri = payload.uri
        if (payload.type == PayloadType.TEXT || uri == null) {
            val txt = payload.text ?: ""
            clipData = ClipData.newPlainText("UniversalDragText", txt)
        } else {
            clipData = ClipData(ClipDescription(payload.displayName, arrayOf(payload.mimeType, ClipDescription.MIMETYPE_TEXT_URILIST)), ClipData.Item(uri))
            flags = flags or View.DRAG_FLAG_GLOBAL_URI_READ
        }
        internalClipChange = true
        runCatching { clipboardManager.setPrimaryClip(clipData) }

        val resTag = if (payload.origWidth > 0 && payload.origHeight > 0) "${payload.origWidth}×${payload.origHeight} • 원본" else "원본 유지"
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

        val started = overlay.startDragAndDrop(clipData, shadowBuilder, null, flags)
        if (started) {
            overlay.alpha = 0f
            val lp = overlay.layoutParams as WindowManager.LayoutParams
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            runCatching { windowManager.updateViewLayout(overlay, lp) }
            mainHandler.postDelayed(sampleDragLocationRunnable, 60)
        } else {
            isGlobalDragging = false
            removeActiveOverlay()
        }
    }

    /**
     * 삼성 갤러리 정밀 매칭
     */
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
                            startGlobalDragDirectly(payload)
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
        activeOverlayView?.let {
            runCatching { windowManager.removeView(it) }
        }
        activeOverlayView = null
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
