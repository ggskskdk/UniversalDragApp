package com.tools.universaldrag

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

class ProcessTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim()
        if (!text.isNullOrEmpty()) {
            val service = UniversalDragService.instance
            if (service != null) {
                service.showOrUpdateRealtimeTextPill(
                    text,
                    resources.displayMetrics.widthPixels / 2,
                    resources.displayMetrics.heightPixels / 2
                )
            } else {
                Toast.makeText(this, "Universal Drag 접근성 권한을 먼저 켜주세요.", Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }
}
