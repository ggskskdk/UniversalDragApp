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
            text = "Universal Drag 5.0"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
        }

        val guide = TextView(this).apply {
            text = "\n✅ 수정 완료 내역:\n" +
                   "1. 선택하지 않은 이전 캡처 이미지가 멋대로 뜨던 버그 완전 제거\n" +
                   "2. 고정 서명키(universal_drag.p12) 및 자동 VersionCode 적용 완료 (향후 빌드 시 삭제 없이 바로 '업데이트' 설치 가능)\n" +
                   "3. 홈 화면(바탕화면) 아이콘, 버튼, 상하단 바 롱프레스 시 팝업 완벽 차단\n"
            setTextColor(Color.parseColor("#A1A1AA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        }

        val btnAcc = Button(this).apply {
            text = "1. 접근성 권한 설정 열기 (필수)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val btnStorage = Button(this).apply {
            text = "2. 모든 파일(원본 영상/이미지) 접근 권한 허용"
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
            hint = "드롭 테스트 입력창"
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
