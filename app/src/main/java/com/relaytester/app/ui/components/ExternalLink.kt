package com.relaytester.app.ui.components

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri

fun openUriSafely(context: Context, url: String) {
    val opened = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess
    if (!opened) {
        Toast.makeText(context, "没有可以打开这个链接的应用", Toast.LENGTH_SHORT).show()
    }
}
