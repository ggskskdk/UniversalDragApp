package com.tools.universaldrag

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
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
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
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
        val thumbnail: Bitmap? = null
    )

    private lateinit var windowManager: WindowManager
    private lateinit var clipboardManager: ClipboardManager
    private val mainHandler = Handler(Looper.getMainLooper())

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

    /**
     * 드래그 중 120ms 주기로 10ms 동안만 드롭 좌표(X, Y)를 샘플링 (멀티터치 방해 제로)
     */
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        setupDragLocationTracker()
        // 상시 떠 있는 아이콘(🎯) 없음: 100% 클린 화면 유지
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || isGlobalDragging) return
        if (event.packageName?.toString() == packageName) return

        val now = System.currentTimeMillis()

        when (event.eventType) {
            // 1. 앱에서 기본 텍스트 블록 지정(드래그 선택)이 발생한 경우
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

            // 2. 텍스트, 이미지, 영상, 파일을 꾹 눌렀을 때(Long-Press) 감지
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                if (now - lastEventTimestamp < 350) return
                lastEventTimestamp = now
                val source = event.source ?: rootInActiveWindow ?: return
                handleLongPressedNode(source)
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        removeActiveOverlay()
        dragTrackerView?.let { runCatching { windowManager.removeView(it) } }
        super.onDestroy()
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    /**
     * 꾹 누른(Long-Pressed) 노드가 [파일] / [텍스트] / [이미지·영상] 중 무엇인지 판별하여 처리
     */
    private fun handleLongPressedNode(node: AccessibilityNodeInfo) {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        // 1순위: 노드 내부 또는 자식 노드에 파일명(.mp4, .pdf, .zip 등)이 있는지 탐색
        val extractedText = extractNodeTextDeep(node)
        if (!extractedText.isNullOrBlank()) {
            val matchedFile = tryResolveFileFromText(extractedText)
            if (matchedFile != null) {
                prepareFileDragPayload(matchedFile, rect.centerX(), rect.centerY())
                return
            }
            // 일반 텍스트(챗GPT, 클로드, X, 브라우저 본문 등) -> 드래그 영역 지정 & 드래그 오버레이 표시!
            showTextSelectionAndDragOverlay(extractedText, rect)
            return
        }

        // 2순위: 텍스트가 없는 뷰(ImageView, 영상 플레이어, 썸네일 등) -> 즉시 해당 영역 화면 캡처 후 이미지 드래그 활성화!
        val mediaRect = findBestMediaBounds(node, rect)
        if (mediaRect.width() > dp(32f) && mediaRect.height() > dp(32f)) {
            captureRegionAndShowImageDrag(mediaRect)
        }
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
            for (i in 0 until n.childCount) {
                traverse(n.getChild(i))
            }
        }
        traverse(node)
        return if (builder.isNotEmpty()) builder.toString() else null
    }

    private fun findBestMediaBounds(node: AccessibilityNodeInfo, fallbackRect: Rect): Rect {
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        if (fallbackRect.width() in dp(40f)..screenW && fallbackRect.height() in dp(40f)..(screenH - dp(80f))) {
            return fallbackRect
        }
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

    /**
     * [기능 3 - 이미지/영상 화면 캡처 드래그]
     * Android 11+ AccessibilityService.takeScreenshot()으로 롱프레스한 미디어 영역을 크롭하여 FileProvider URI 생성
     */
    private fun captureRegionAndShowImageDrag(cropRect: Rect) {
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
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

                        val cropped = Bitmap.createBitmap(swBitmap, left, top, width, height)
                        val sharedDir = File(cacheDir, "shared_media").apply { mkdirs() }
                        val outFile = File(sharedDir, "drag_img_${System.currentTimeMillis()}.png")
                        FileOutputStream(outFile).use { out ->
                            cropped.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }

                        val uri = FileProvider.getUriForFile(
                            this@UniversalDragService,
                            "$packageName.fileprovider",
                            outFile
                        )
                        val thumb = Bitmap.createScaledBitmap(
                            cropped,
                            dp(160f),
                            (dp(160f) * height / width.toFloat()).toInt().coerceIn(dp(60f), dp(140f)),
                            true
                        )

                        val payload = DragPayload(
                            type = PayloadType.IMAGE,
                            uri = uri,
                            mimeType = "image/png",
                            displayName = outFile.name,
                            thumbnail = thumb
                        )
                        showQuickDragPill(payload, cropRect.centerX(), cropRect.centerY())
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onFailure(errorCode: Int) {
                Toast.makeText(this@UniversalDragService, "이미지 캡처 대기 중입니다. 다시 길게 눌러주세요.", Toast.LENGTH_SHORT).show()
            }
        })
    }

    /**
     * [기능 3 - 영상 및 모든 파일(모든 MIME 타입) 매칭]
     * 롱프레스한 텍스트에 파일 확장자가 포함된 경우 Download / MediaStore에서 실제 파일을 찾아 FileProvider URI로 변환
     */
    private fun tryResolveFileFromText(text: String): File? {
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val candidateName = lines.firstOrNull { line ->
            fileExtensions.any { ext -> line.lowercase().contains(ext) }
        } ?: return null

        // 공개 디렉토리 우선 탐색 (Download, DCIM, Pictures, Movies, Documents)
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

        // MediaStore 전체 쿼리 폴백
        runCatching {
            val projection = arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DISPLAY_NAME)
            contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection,
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                arrayOf("%${candidateName.take(30)}%"),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val pathIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (pathIdx >= 0) {
                        val path = cursor.getString(pathIdx)
                        if (!path.isNullOrEmpty()) {
                            val file = File(path)
                            if (file.exists()) return file
                        }
                    }
                }
            }
        }
        return null
    }

    private fun prepareFileDragPayload(file: File, cx: Int, cy: Int) {
        runCatching {
            val sharedDir = File(cacheDir, "shared_media").apply { mkdirs() }
            val copiedFile = File(sharedDir, file.name)
            file.copyTo(copiedFile, overwrite = true)

            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", copiedFile)
            val ext = file.extension.lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
            val pType = when {
                mime.startsWith("image/") -> PayloadType.IMAGE
                mime.startsWith("video/") -> PayloadType.VIDEO
                else -> PayloadType.FILE
            }
            val thumb = if (pType == PayloadType.IMAGE) {
                BitmapFactory.decodeFile(copiedFile.absolutePath)
            } else null

            val payload = DragPayload(
                type = pType,
                uri = uri,
                mimeType = mime,
                displayName = file.name,
                thumbnail = thumb
            )
            showQuickDragPill(payload, cx, cy)
        }
    }

    /**
     * 외부 공유 인텐트(ShareReceiveActivity)를 통해 전달받은 원본 영상/파일/이미지 URI를 즉시 드래그 캡슐로 표시
     */
    fun handleSharedUri(sourceUri: Uri, mimeType: String?) {
        runCatching {
            val resolvedMime = mimeType ?: contentResolver.getType(sourceUri) ?: "*/*"
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(resolvedMime) ?: "bin"
            val sharedDir = File(cacheDir, "shared_media").apply { mkdirs() }
            val destFile = File(sharedDir, "shared_${System.currentTimeMillis()}.$ext")

            contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }

            val fileUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", destFile)
            val pType = when {
                resolvedMime.startsWith("image/") -> PayloadType.IMAGE
                resolvedMime.startsWith("video/") -> PayloadType.VIDEO
                else -> PayloadType.FILE
            }
            val thumb = if (pType == PayloadType.IMAGE) {
                BitmapFactory.decodeFile(destFile.absolutePath)
            } else null

            val payload = DragPayload(
                type = pType,
                uri = fileUri,
                mimeType = resolvedMime,
                displayName = destFile.name,
                thumbnail = thumb
            )
            showQuickDragPill(payload, resources.displayMetrics.widthPixels / 2, resources.displayMetrics.heightPixels / 2)
        }
    }

    /**
     * [기능 1 & 2 - 롱프레스 시 열리는 인플레이스 텍스트 드래그 영역 지정 오버레이]
     * - 상시 아이콘 없음
     * - 글자 위를 드래그하면 원하는 구간이 지정됨
     * - 지정된 하이라이트 영역(또는 하단 드래그 바)을 잡고 끌면 즉시 글로벌 드래그 섀도우로 전환!
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

            // 상단 안내 및 닫기 헤더
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6f), dp(2f), dp(6f), dp(6f))
            }

            val guideLabel = TextView(this).apply {
                text = "① 드래그해 영역 지정  →  ② 파란 영역 잡고 다른 앱으로 이동"
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

            // 하단 즉시 드래그 핸들 바
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

            // 커스텀 드래그 영역 선택 텍스트뷰
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
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(160f)
                )
                addView(selectableView)
            }

            // 하단 드래그 바를 잡고 끌어도 즉시 글로벌 드래그 발동
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
     * 이미지·영상·파일 또는 이미 블록 지정된 텍스트를 잡고 바로 끌 수 있는 컴팩트 드래그 캡슐
     */
    fun showQuickDragPill(payload: DragPayload, posX: Int, posY: Int) {
        mainHandler.post {
            removeActiveOverlay()
            val screenW = resources.displayMetrics.widthPixels
            val screenH = resources.displayMetrics.heightPixels

            val container = FrameLayout(this)
            val pillLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(22f).toFloat()
                    setColor(Color.parseColor("#F218181B"))
                    setStroke(dp(1.8f), Color.parseColor("#818CF8"))
                }
                elevation = dp(10f).toFloat()
            }

            if (payload.thumbnail != null) {
                val imgView = ImageView(this).apply {
                    setImageBitmap(payload.thumbnail)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = LinearLayout.LayoutParams(dp(38f), dp(38f)).apply {
                        rightMargin = dp(10f)
                    }
                }
                pillLayout.addView(imgView)
            }

            val iconPrefix = when (payload.type) {
                PayloadType.TEXT -> "⠿ 텍스트"
                PayloadType.IMAGE -> "🖼️ 이미지"
                PayloadType.VIDEO -> "🎬 영상"
                PayloadType.FILE -> "📁 파일"
            }

            val label = TextView(this).apply {
                val shortName = if (payload.displayName.length > 20) payload.displayName.take(20).replace("\n", " ") + "…" else payload.displayName.replace("\n", " ")
                text = "$iconPrefix 드래그: $shortName"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            pillLayout.addView(label)
            container.addView(pillLayout)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (posX - dp(110f)).coerceIn(dp(16f), screenW - dp(240f))
                y = (posY - dp(64f)).coerceIn(dp(50f), screenH - dp(140f))
            }

            var startX = 0f
            var startY = 0f

            pillLayout.setOnTouchListener { _, ev ->
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

    /**
     * [핵심] 텍스트 및 미디어/파일 URI 글로벌 드래그 세션 시작
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
            // 모든 설치된 채팅/타겟 앱에 URI 읽기 권한 부여 (Cross-App SecurityException 완벽 방지)
            grantReadPermissionToAllApps(uri)
            clipData = ClipData.newUri(contentResolver, payload.displayName, uri)
            internalClipChange = true
            runCatching { clipboardManager.setPrimaryClip(clipData) }
            flags = flags or View.DRAG_FLAG_GLOBAL_URI_READ
        }

        val badgeTitle = when (payload.type) {
            PayloadType.TEXT -> "TEXT DRAG (${payload.text?.length ?: 0}자)"
            PayloadType.IMAGE -> "IMAGE DRAG & DROP"
            PayloadType.VIDEO -> "VIDEO DRAG (${payload.displayName})"
            PayloadType.FILE -> "FILE DRAG (${payload.displayName})"
        }

        val shadowBuilder = UniversalDragShadowBuilder(
            context = this,
            titleBadge = badgeTitle,
            subtitleText = payload.text ?: payload.displayName,
            thumbnail = payload.thumbnail
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
                        // 타겟 앱이 기본 드롭을 거부한 경우 -> 즉시 강제 드롭(Force-Drop) 실행!
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

    /**
     * 전체 화면 투명 드래그 좌표 샘플러
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
     * [강제 드롭 엔진 v3.0]
     * - 텍스트: 드롭 지점 탭 포커스 -> ACTION_PASTE / ACTION_SET_TEXT 주입
     * - 이미지/영상/파일: 드롭된 채팅창에 ACTION_PASTE(리치 클립보드) 시도 후, 미지원 채팅앱에는 해당 앱 패키지로 ACTION_SEND 파일 첨부 즉시 실행
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
            // 이미지 / 영상 / 일반 파일 강제 드롭 (채팅창 적용)
            val uri = payload.uri ?: return
            val targetPkg = rootInActiveWindow?.packageName?.toString()

            // 1단계: 이미지인 경우 채팅 입력창 포커스 후 리치 클립보드 붙여넣기(ACTION_PASTE) 시도
            val editable = findEditableNodeNear(dropX, dropY) ?: findBestTargetEditable(dropX, dropY)
            if (payload.type == PayloadType.IMAGE && editable != null) {
                editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val pasted = editable.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                if (pasted) return
            }

            // 2단계: 영상/파일이거나 ACTION_PASTE를 받지 않는 채팅앱(카카오톡 등)인 경우 해당 앱으로 즉시 미디어/파일 전송 인텐트 호출
            sendMediaDirectlyToTargetApp(uri, payload.mimeType, targetPkg)
        }
    }

    private fun sendMediaDirectlyToTargetApp(uri: Uri, mimeType: String, targetPackage: String?) {
        runCatching {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                if (!targetPackage.isNullOrEmpty() && targetPackage != packageName) {
                    setPackage(targetPackage)
                }
            }
            if (sendIntent.resolveActivity(packageManager) != null) {
                startActivity(sendIntent)
            } else {
                val chooser = Intent.createChooser(sendIntent.apply { setPackage(null) }, "드롭한 파일 전송").apply {
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
