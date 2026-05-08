package com.nuvio.app.features.plugins

import android.os.SystemClock

actual fun currentPluginPlatform(): String = "android"
actual fun currentEpochMillis(): Long = System.currentTimeMillis()