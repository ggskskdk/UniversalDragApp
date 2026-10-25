package com.tools.universaldrag

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast

class ShareReceiveActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val service = UniversalDragService.instance
        if (service == null) {
            Toast.makeText(this, "Universal Drag 접근성 권한을 먼저 켜주세요.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (intent?.action == Intent.ACTION_SEND) {
            val streamUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (streamUri != null) {
                service.handleSharedUri(streamUri, intent.type)
            }
        }
        finish()
    }
}
