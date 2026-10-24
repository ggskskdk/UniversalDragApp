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
            text = "Universal Drag 4.0 (원본 무손실)"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
        }

        val guide = TextView(this).apply {
            text = "\n• [원본 비율 & 원본 해상도 100% 유지]: 고정 네모 캡처를 폐기하고, 원본 이미지/영상의 실제 해상도(예: 3024×4032)와 가로·세로 비율 그대로 드래그 섀도우가 생성됩니다.\n" +
                   "• [시스템 MediaStore URI 직결]: 카카오톡·X·디스코드·메시지 등 모든 채팅창에 드롭 시 즉시 인식됩니다.\n" +
                   "• [영상 드래그]: 영상을 길게 누르거나 캡슐 하단의 [🎬 최근 원본 영상(.mp4)으로 전환]을 누르면 원본 영상 파일 그대로 드래그됩니다.\n"
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
