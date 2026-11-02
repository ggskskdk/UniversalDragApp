package com.tools.universaldrag

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO),
                100
            )
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121214"))
            setPadding(dp(24f), dp(48f), dp(24f), dp(24f))
        }

        val title = TextView(this).apply {
            text = "Universal Drag 11.0"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
        }

        val guide = TextView(this).apply {
            text = "\n✅ v11.0 갤러리 컨텍스트 메뉴 & 썸네일 대응:\n" +
                   "1. [갤러리 흰색 팝업 메뉴 감지]: 사진을 꾹 눌러 '만들기/공유/삭제/클립보드에 복사' 메뉴가 뜰 때도 뒤쪽 갤러리 창을 함께 스캔해 검은색 드래그 팝업을 띄웁니다.\n" +
                   "2. [하단 필름스트립 & 큰 사진 뷰 지원]: 화면 맨 아래쪽 썸네일과 큰 사진 보기 화면에서도 동작합니다.\n" +
                   "3. [빠른 팝업]: 날짜로 원본이 확정되면 스크린샷 없이 바로 띄우고, 애매한 사진만 화면과 비교해 확실할 때만 띄웁니다.\n"
            setTextColor(Color.parseColor("#A1A1AA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        }

        val btnAcc = Button(this).apply {
            text = "1. 접근성 권한 설정 열기"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val btnStorage = Button(this).apply {
            text = "2. 모든 파일(내 파일/갤러리 원본) 권한 허용"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            }
        }

        val testInput = EditText(this).apply {
            hint = "드롭 및 키보드 입력 테스트창"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
        }

        root.addView(title)
        root.addView(guide)
        root.addView(btnAcc)
        root.addView(btnStorage)
        root.addView(testInput)
        setContentView(root)
    }
}
