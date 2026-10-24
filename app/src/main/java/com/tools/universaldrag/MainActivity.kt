package com.tools.universaldrag

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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121214"))
            setPadding(dp(24f), dp(48f), dp(24f), dp(24f))
        }

        val title = TextView(this).apply {
            text = "Universal Drag 3.0"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        }

        val guide = TextView(this).apply {
            text = "\n• 상시 화면 아이콘 제거 완료 (평소 화면 100% 클린)\n" +
                   "• [텍스트 영역 지정 드래그]: 챗GPT·클로드·X 등에서 텍스트를 꾹 누르면 영역 선택창이 열립니다. 원하는 구간을 드래그해 지정한 뒤, 파란 영역을 잡고 다른 앱으로 끌어다 놓으세요.\n" +
                   "• [이미지·영상·모든 파일 드래그]: 이미지나 파일명을 꾹 누르면(또는 공유 -> ⚡ 드래그 앤 드롭) 즉시 미디어 드래그 카드가 뜨며, 다른 앱 채팅창에 끌어다 놓으면 첨부됩니다.\n"
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
            text = "2. 모든 파일(영상/문서) 접근 권한 허용"
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
            hint = "드롭 테스트 입력창 (텍스트를 여기에 놓아보세요)"
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
