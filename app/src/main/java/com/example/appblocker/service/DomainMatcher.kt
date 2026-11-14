package com.example.appblocker.service

object DomainMatcher {

    /** Turns user input such as "https://www.Example.com/path" into "example.com". */
    fun normalize(input: String): String {
        var value = input.trim().lowercase()
        value = value.substringAfter("://", value)
        value = value.substringBefore('/').substringBefore('?').substringBefore('#')
        value = value.substringBefore(':')
        if (value.startsWith("www.")) value = value.removePrefix("www.")
        return value.trim('.')
    }

    /** Extracts a host from text shown in a browser URL bar. */
    fun hostFromUrl(text: String): String? {
        val host = normalize(text)
        if (host.isBlank() || host.contains(' ')) return null
        return host
    }

    /** True when [host] equals [blocked] or is a subdomain of it. */
    fun matches(host: String, blocked: String): Boolean {
        val h = host.lowercase().trim('.')
        val b = blocked.lowercase().trim('.')
        if (h.isEmpty() || b.isEmpty()) return false
        return h == b || h.endsWith(".$b")
    }

}
