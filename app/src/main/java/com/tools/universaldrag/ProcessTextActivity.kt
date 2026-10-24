package com.tools.universaldrag

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast

class ProcessTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim()
        val service = UniversalDragService.instance
        when {
            text.isNullOrEmpty() -> Unit
            service == null -> {
                Toast.makeText(this, "접근성에서 Universal Two-Hand Drag를 켜주세요", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            else -> service.showDragCapsule(text)
        }
        finish()
    }
}
