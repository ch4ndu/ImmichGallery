package com.udnahc.immichgallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.udnahc.immichgallery.di.bindLocalNetworkAccessController
import com.udnahc.immichgallery.di.initPlatformContext
import com.udnahc.immichgallery.ui.util.registerActivity

class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        AppLifecycle.setForeground(true)
    }

    override fun onStop() {
        AppLifecycle.setForeground(false)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initPlatformContext(this)
        bindLocalNetworkAccessController(this)
        registerActivity(this)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        setContent {
            App()
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
