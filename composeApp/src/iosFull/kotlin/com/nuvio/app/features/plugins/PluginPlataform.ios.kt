package com.nuvio.app.features.plugins

import platform.Foundation.NSDate

actual fun currentPluginPlatform(): String = "ios"
actual fun currentEpochMillis(): Long = NSDate().timeIntervalSince1970.toLong()