package com.nuvio.app

import androidx.compose.ui.window.ComposeUIViewController
import com.nuvio.app.features.plugins.engine.IosQuickJsEngine
import com.nuvio.app.features.plugins.engine.PluginEngineProvider
import platform.UIKit.UIColor

private val nuvioBackgroundColor = UIColor(red = 0.008, green = 0.016, blue = 0.016, alpha = 1.0)

fun MainViewController() = ComposeUIViewController {

    if (!PluginEngineProvider.isInitialized()) {
        PluginEngineProvider.initialize(IosQuickJsEngine())
    }

    App()
}.apply {
    view.backgroundColor = nuvioBackgroundColor
}