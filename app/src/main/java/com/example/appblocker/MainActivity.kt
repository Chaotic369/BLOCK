package com.example.appblocker

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.appblocker.di.AppModule
import com.example.appblocker.service.BlockDnsVpnService
import com.example.appblocker.ui.web.WebBridge
import com.example.appblocker.ui.web.WebHost
import com.example.appblocker.util.PermissionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Hosts the app's UI: the HTML page in assets/app.html. All data and device access goes
 * through [WebBridge]; this class owns the parts that need an Activity (permission screens,
 * the image picker, the back button, live updates).
 */
class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var bridge: WebBridge
    private var night = false
    private var pageReady = false
    private var destroyed = false
    private val queued = ArrayList<Pair<String, String?>>()
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooser = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        fileCallback?.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        )
        fileCallback = null
    }

    // Export: the page hands over a file name and JSON text; the user picks where to save it.
    private var pendingExport: String? = null
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val text = pendingExport
        pendingExport = null
        if (uri == null || text == null) return@registerForActivityResult
        val ok = runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
        }.getOrDefault(false)
        emit("toast", if (ok) "Config exported" else "Could not save the file")
    }

    // The DNS filter only runs after the user approves Android's VPN consent dialog.
    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) startFilterIfNeeded()
        emit("perms", null)
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { emit("perms", null) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        night = WebHost.isNight(this)
        val background = WebHost.pageBackground(this)

        webView = WebView(this)
        bridge = WebBridge(
            activity = this,
            emit = { kind, payload -> emit(kind, payload) },
            requestPermission = { id -> openPermission(id) },
            onExport = { name, text ->
                pendingExport = text
                runCatching { exportLauncher.launch(name) }.onFailure {
                    pendingExport = null
                    emit("toast", "Could not open the file picker")
                }
            }
        )
        WebHost.configure(
            activity = this,
            webView = webView,
            bridge = bridge,
            background = background,
            whenLoaded = { onPageFinished() },
            whenRenderGone = { recreate() },
            chrome = object : WebChromeClient() {
                override fun onShowFileChooser(
                    view: WebView,
                    callback: ValueCallback<Array<Uri>>,
                    params: WebChromeClient.FileChooserParams
                ): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = callback
                    return try {
                        fileChooser.launch(params.createIntent())
                        true
                    } catch (e: Exception) {
                        fileCallback = null
                        callback.onReceiveValue(null)
                        false
                    }
                }
            }
        )
        bridge.warm()
        setContentView(WebHost.createRoot(this, webView, background))
        webView.loadUrl(WebHost.PAGE)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // The page closes dialogs and steps back through its own screens first.
                webView.evaluateJavascript(
                    "window.nativeBack ? window.nativeBack() : false"
                ) { handled -> if (handled != "true") finish() }
            }
        })

        // Tell the page when locks or domains change (expiry, removals, new locks).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val repository = AppModule.blockRepository
                combine(repository.apps, repository.domains) { _, _ -> }
                    .drop(1)
                    .collect { emit("data", null) }
            }
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Coming back from a system settings screen: re-read the permission states.
        if (pageReady) emit("perms", null)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Light/dark switched: reload so the page picks its other palette.
        if (WebHost.isNight(this) != night) recreate()
    }

    override fun onDestroy() {
        destroyed = true
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        bridge.destroy()
        webView.removeJavascriptInterface("AndroidBridge")
        webView.destroy()
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra(EXTRA_TOAST)?.let { emit("toast", it) }
        intent?.removeExtra(EXTRA_TOAST)
    }

    private fun onPageFinished() {
        pageReady = true
        queued.forEach { (kind, payload) -> deliver(kind, payload) }
        queued.clear()
    }

    /** Pushes an event to the page. Safe from any thread; held back until the page has loaded. */
    private fun emit(kind: String, payload: String?) {
        runOnUiThread {
            if (destroyed) return@runOnUiThread
            if (pageReady) deliver(kind, payload) else queued.add(kind to payload)
        }
    }

    private fun deliver(kind: String, payload: String?) {
        val arg = if (payload == null) "null" else JSONObject.quote(payload)
        webView.evaluateJavascript(
            "window.onNativeEvent && window.onNativeEvent(${JSONObject.quote(kind)}, $arg)",
            null
        )
    }

    private fun startFilterIfNeeded() {
        lifecycleScope.launch(Dispatchers.IO) {
            val active = AppModule.blockRepository.hasActiveDomains(System.currentTimeMillis())
            if (active) BlockDnsVpnService.start(applicationContext)
        }
    }

    /** Opens the system screen or prompt for one of the permission ids the page uses. */
    private fun openPermission(id: String) {
        runCatching {
            when (id) {
                "usage" -> startActivity(PermissionUtils.usageAccessIntent())
                "a11y" -> startActivity(PermissionUtils.accessibilitySettingsIntent())
                "overlay" -> startActivity(PermissionUtils.overlayIntent(this))
                "admin" -> startActivity(PermissionUtils.deviceAdminIntent(this))
                "vpn" -> PermissionUtils.vpnConsentIntent(this)?.let { vpnLauncher.launch(it) }
                "notif" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    companion object {
        /** Optional: a message the page shows as a toast when it opens. */
        const val EXTRA_TOAST = "extra_toast"
    }
}
