package com.example.appblocker.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo

object BrowserUrlExtractor {

    private fun ids(pkg: String, vararg names: String) = names.map { "$pkg:id/$it" }

    /** Known address-bar view ids per browser. Browsers rename these, so [extractGeneric] backs them up. */
    private val urlBarIds: Map<String, List<String>> = mapOf(
        "com.android.chrome" to ids("com.android.chrome", "url_bar"),
        "com.chrome.beta" to ids("com.chrome.beta", "url_bar"),
        "com.chrome.dev" to ids("com.chrome.dev", "url_bar"),
        "com.chrome.canary" to ids("com.chrome.canary", "url_bar"),
        "org.mozilla.firefox" to ids(
            "org.mozilla.firefox",
            "mozac_browser_toolbar_url_view",
            "mozac_browser_toolbar_edit_url_view",
            "url_bar_title"
        ),
        "org.mozilla.firefox_beta" to ids(
            "org.mozilla.firefox_beta",
            "mozac_browser_toolbar_url_view",
            "mozac_browser_toolbar_edit_url_view",
            "url_bar_title"
        ),
        "org.mozilla.fenix" to ids(
            "org.mozilla.fenix",
            "mozac_browser_toolbar_url_view",
            "mozac_browser_toolbar_edit_url_view"
        ),
        "org.mozilla.focus" to ids("org.mozilla.focus", "display_url", "mozac_browser_toolbar_url_view"),
        "com.brave.browser" to ids("com.brave.browser", "url_bar"),
        "com.brave.browser_beta" to ids("com.brave.browser_beta", "url_bar"),
        "com.brave.browser_nightly" to ids("com.brave.browser_nightly", "url_bar"),
        "com.microsoft.emmx" to ids("com.microsoft.emmx", "url_bar"),
        "com.opera.browser" to ids("com.opera.browser", "url_field"),
        "com.sec.android.app.sbrowser" to ids("com.sec.android.app.sbrowser", "location_bar_edit_text"),
        "com.sec.android.app.sbrowser.beta" to ids("com.sec.android.app.sbrowser.beta", "location_bar_edit_text"),
        "com.duckduckgo.mobile.android" to ids("com.duckduckgo.mobile.android", "omnibarTextInput"),
        "com.kiwibrowser.browser" to ids("com.kiwibrowser.browser", "url_bar"),
        "com.vivaldi.browser" to ids("com.vivaldi.browser", "url_bar")
    )

    private val addressBarHints = listOf("url", "address", "omnibox", "location_bar", "search_box")
    private const val MAX_NODES = 250

    fun isKnownBrowser(packageName: String): Boolean = packageName in urlBarIds

    /**
     * Every installed app that can open an https link, so browsers that are not in [urlBarIds]
     * are still covered through [extractGeneric].
     */
    fun installedBrowsers(context: Context): Set<String> = runCatching {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        context.packageManager
            .queryIntentActivities(probe, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }
            .filter { it != context.packageName }
            .toSet()
    }.getOrDefault(emptySet())

    /** Text of the browser's address bar, using known ids first and a bounded tree scan second. */
    fun extract(root: AccessibilityNodeInfo, packageName: String): String? {
        urlBarIds[packageName]?.forEach { id ->
            val text = root.findAccessibilityNodeInfosByViewId(id)
                ?.firstNotNullOfOrNull { it.text?.toString()?.takeIf { value -> value.isNotBlank() } }
            if (text != null) return text
        }
        return extractGeneric(root)
    }

    /**
     * Breadth-first scan for something that looks like an address bar: an editable field, or a
     * node whose view id mentions url/address/omnibox, holding text that looks like a host name.
     * Capped at [MAX_NODES] nodes so a huge page cannot stall the accessibility thread.
     */
    fun extractGeneric(root: AccessibilityNodeInfo): String? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            val text = node.text?.toString()
            if (!text.isNullOrBlank() && looksLikeAddressBar(node) && looksLikeHost(text)) return text
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun looksLikeAddressBar(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) return true
        val id = node.viewIdResourceName?.lowercase() ?: return false
        return addressBarHints.any { id.contains(it) }
    }

    private fun looksLikeHost(text: String): Boolean {
        val host = DomainMatcher.hostFromUrl(text) ?: return false
        return host.contains('.') && host.length <= 253
    }

}
