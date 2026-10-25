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
            text = "Universal Drag 8.0"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
        }

        val guide = TextView(this).apply {
            text = "\n✅ v8.0 모든 앱 호환 업데이트:\n" +
                   "1. [챗GPT / 클로드 / 브라우저 완벽 지원]: 텍스트를 드래그해 선택 메뉴(복사 | 모두 선택...)가 뜨면 그 바로 위에 [⠿ 선택 영역 바로 드래그] 캡슐이 자동으로 나타납니다.\n" +
                   "2. [선택 핸들(●) 자유 조절]: 캡슐이 떠 있는 상태에서도 검정색 물방울 핸들(●)을 늘리거나 줄여 여러 문단을 자유롭게 선택할 수 있습니다.\n" +
                   "3. [원터치 자동 복사 & 드래그]: 캡슐을 잡고 끄는 순간 툴바의 '복사'를 자동 실행하여 선택한 전체 문장을 끊김 없이 다른 앱으로 드래그합니다.\n"
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
            text = "2. 모든 파일(갤러리 원본) 권한 허용"
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
