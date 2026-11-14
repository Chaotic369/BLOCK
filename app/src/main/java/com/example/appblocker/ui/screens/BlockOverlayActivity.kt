package com.example.appblocker.ui.screens

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import com.example.appblocker.MainActivity
import com.example.appblocker.di.AppModule
import com.example.appblocker.ui.web.OverlayInfo
import com.example.appblocker.ui.web.WebBridge
import com.example.appblocker.ui.web.WebHost

/**
 * The block screen. When "Show locked screen" is on it shows the page's block screen
 * (assets/app.html?overlay=1); otherwise opening a locked app lands on the app's home page.
 */
class BlockOverlayActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var bridge: WebBridge? = null
    private var night = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                goHome()
            }
        })

        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        val until = intent.getLongExtra(EXTRA_UNTIL, 0L)
        val isDomain = intent.getBooleanExtra(EXTRA_IS_DOMAIN, false)

        if (!AppModule.settingsRepository.lockScreenEnabled) {
            openAppHome(label)
            return
        }

        enableEdgeToEdge()
        night = WebHost.isNight(this)
        val background = WebHost.surfaceBackground(this)

        val view = WebView(this)
        val overlayBridge = WebBridge(
            activity = this,
            emit = { _, _ -> },
            overlay = OverlayInfo(label, until, isDomain)
        )
        WebHost.configure(
            activity = this,
            webView = view,
            bridge = overlayBridge,
            background = background,
            whenLoaded = {},
            whenRenderGone = { recreate() }
        )
        webView = view
        bridge = overlayBridge
        setContentView(WebHost.createRoot(this, view, background))
        view.loadUrl(WebHost.OVERLAY_PAGE)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (webView != null && WebHost.isNight(this) != night) recreate()
    }

    override fun onDestroy() {
        bridge?.destroy()
        webView?.let {
            it.removeJavascriptInterface("AndroidBridge")
            it.destroy()
        }
        webView = null
        bridge = null
        super.onDestroy()
    }

    /** Lock screen is off: bring the app's home page forward with a short notice. */
    private fun openAppHome(label: String) {
        val home = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (label.isNotBlank()) putExtra(MainActivity.EXTRA_TOAST, "$label is locked")
        }
        startActivity(home)
        finish()
    }

    private fun goHome() {
        val home = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(home)
        finish()
    }

    companion object {
        const val EXTRA_LABEL = "extra_label"
        const val EXTRA_UNTIL = "extra_until"
        const val EXTRA_IS_DOMAIN = "extra_is_domain"

        fun intent(
            context: Context,
            label: String,
            until: Long,
            isDomain: Boolean = false
        ): Intent = Intent(context, BlockOverlayActivity::class.java).apply {
            putExtra(EXTRA_LABEL, label)
            putExtra(EXTRA_UNTIL, until)
            putExtra(EXTRA_IS_DOMAIN, isDomain)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }

}
