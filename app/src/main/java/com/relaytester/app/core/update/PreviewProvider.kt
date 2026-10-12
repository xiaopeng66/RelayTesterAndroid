package com.relaytester.app.core.update

import androidx.core.content.FileProvider

/**
 * The previews FileProvider, as its own class.
 *
 * Two `<provider>` entries may not share one class name: the system's provider
 * resolution conflates same-class providers of a package and serves the *first*
 * one's configuration for both authorities — measured on the API 35 emulator, where
 * acquiring `${applicationId}.previews` returned the updates provider
 * ("The authority ... does not match the one of the contentProvider"), so every
 * consumer saw the updates path config, found no `previews` root, and both Chrome
 * and HTML Viewer answered 浏览器打开 with ERR_FILE_NOT_FOUND. A dedicated subclass
 * makes the two components distinct and the authorities resolve individually.
 */
class PreviewProvider : FileProvider()
