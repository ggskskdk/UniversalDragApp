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
            text = "Universal Drag 12.2"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
        }

        val guide = TextView(this).apply {
            text = "\n✅ v12.0 갤러리 앨범 선택 모드 완벽 수정:\n" +
                   "1. [One UI 갤러리 선택 바 감지]: 좌상단 숫자 배지(1, 2...), 우상단 '취소' 버튼, 하단 '만들기/공유/삭제/더보기' 바가 뜨는 즉시 팝업이 표시됩니다.\n" +
                   "2. [앨범 폴더 직결 매칭]: 현재 열린 앨범명(예: 느노어어)과 동일한 실제 폴더 및 MediaStore 버킷에서 원본 이미지를 즉시 연결합니다.\n" +
                   "3. [조기 return 버그 수정]: 앨범 헤더('이미지 5개') 때문에 하위 썸네일 스캔이 중단되던 문제를 해결했습니다.\n"
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

        // 갤러리/내 파일에서 팝업이 뜨면 이전 앱으로 자동 전환
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val autoSwitch = android.widget.Switch(this).apply {
            text = "갤러리·내 파일에서 팝업이 뜨면 이전 앱으로 자동 전환"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(16f), 0, dp(16f))
            isChecked = prefs.getBoolean("auto_switch_back", true)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("auto_switch_back", on).apply() }
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
        root.addView(autoSwitch)
        root.addView(testInput)
        setContentView(root)
    }
}
