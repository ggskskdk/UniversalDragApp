package com.tools.universaldrag

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.widget.Toast

class ProcessTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim()
        if (!text.isNullOrEmpty()) {
            val service = UniversalDragService.instance
            if (service != null) {
                val cx = resources.displayMetrics.widthPixels / 2
                val cy = resources.displayMetrics.heightPixels / 2
                service.showOrUpdateRealtimeTextPill(
                    selectedText = text,
                    anchorRect = Rect(cx - 50, cy - 20, cx + 50, cy + 20)
                )
            } else {
                Toast.makeText(this, "Universal Drag 접근성 권한을 먼저 켜주세요.", Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }
}
