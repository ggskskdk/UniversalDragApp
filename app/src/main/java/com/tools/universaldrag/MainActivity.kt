package com.tools.universaldrag

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(TextView(this@MainActivity).apply {
                textSize = 16f
                text = "1. 아래 버튼 → 접근성 → Universal Two-Hand Drag 켜기\n" +
                    "2. 텍스트 선택/복사, 또는 🎯 핸들을 텍스트 위로 끌어다 놓기\n" +
                    "3. 하단 캡슐을 길게 눌러 잡고, 다른 손으로 앱 전환 후 드롭\n" +
                    "   (드롭 미지원 앱은 자동 강제 드롭)\n"
            })
            addView(Button(this@MainActivity).apply {
                text = "접근성 설정 열기"
                setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            })
        })
    }
}
