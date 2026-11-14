package com.example.appblocker.ui.web

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Shared setup for the screens that show the app's UI. The UI is one HTML page
 * (assets/app.html) that talks to the device through the [WebBridge] object.
 */
object WebHost {

    const val PAGE = "file:///android_asset/app.html"
    const val OVERLAY_PAGE = "$PAGE?overlay=1"

    private const val ASSET_PREFIX = "file:///android_asset/"
    private const val ICON_HOST = "appicon.local"

    /** Same test the page uses to pick its dark or light palette. */
    fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) !=
            Configuration.UI_MODE_NIGHT_NO

    /** The page's --bg colour, so the system bar areas match the page. */
    fun pageBackground(context: Context): Int =
        if (isNight(context)) 0xFF050505.toInt() else 0xFFF6F3F4.toInt()

    /** The page's --surface colour, used behind the block screen. */
    fun surfaceBackground(context: Context): Int =
        if (isNight(context)) 0xFF0D0D0D.toInt() else 0xFFFFFFFF.toInt()

    /**
     * A container that keeps the page clear of the system bars and the keyboard. Required
     * because the activities draw edge to edge (forced on Android 15).
     */
    fun createRoot(activity: Activity, webView: WebView, background: Int): FrameLayout {
        val root = FrameLayout(activity)
        root.setBackgroundColor(background)
        root.addView(
            webView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        return root
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(
        activity: Activity,
        webView: WebView,
        bridge: WebBridge,
        background: Int,
        whenLoaded: () -> Unit,
        whenRenderGone: () -> Unit,
        chrome: WebChromeClient? = null
    ) {
        val appContext = activity.applicationContext
        val debuggable = (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        WebView.setWebContentsDebuggingEnabled(debuggable)

        webView.setBackgroundColor(background)
        webView.overScrollMode = WebView.OVER_SCROLL_NEVER
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Only the bundled page is ever loaded; asset URLs work without file access.
            allowFileAccess = false
            allowContentAccess = false
            // Keep sizes exactly as designed, whatever the system font scale is.
            textZoom = 100
            setSupportZoom(false)
            builtInZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        webView.addJavascriptInterface(bridge, "AndroidBridge")
        if (chrome != null) webView.webChromeClient = chrome
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !request.url.toString().startsWith(ASSET_PREFIX)

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (request.url.host == ICON_HOST) iconResponse(appContext, request.url.lastPathSegment.orEmpty())
                else null

            override fun onPageFinished(view: WebView, url: String) = whenLoaded()

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                whenRenderGone()
                return true
            }
        }
    }

    /** Rendered launcher icons, kept in memory so each icon is drawn and encoded only once. */
    private val iconCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    private fun iconBytes(context: Context, pkg: String): ByteArray? {
        iconCache[pkg]?.let { return it }
        val bytes = runCatching {
            val bitmap = context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96)
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }.getOrNull()
        if (bytes != null) iconCache[pkg] = bytes
        return bytes
    }

    /** Renders icons ahead of time on a background thread so the app list opens instantly. */
    fun warmIcons(context: Context, pkgs: List<String>) {
        pkgs.forEach { iconBytes(context.applicationContext, it) }
    }

    /** Serves https://appicon.local/<package> as that app's launcher icon (PNG). */
    private fun iconResponse(context: Context, pkg: String): WebResourceResponse {
        val bytes = iconBytes(context, pkg)
        return if (bytes != null) {
            WebResourceResponse(
                "image/png", null, 200, "OK",
                mapOf("Cache-Control" to "max-age=86400"),
                ByteArrayInputStream(bytes)
            )
        } else {
            WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
    }
}
