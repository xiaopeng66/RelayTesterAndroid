package com.relaytester.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast

fun copyToClipboard(context: Context, text: String, confirmation: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
    Toast.makeText(context, confirmation, Toast.LENGTH_SHORT).show()
}

/** The platform requires a label for every clip; it is not shown as part of the content. */
private const val CLIP_LABEL = "relay-tester"
