package com.nuvio.app.core.diagnostics

internal const val REDACTED = "[redacted]"

internal class ReportRedactor(knownSecrets: Collection<String> = emptyList()) {
    private val secrets = knownSecrets
        .map(String::trim)
        .filter { it.length >= MIN_KNOWN_SECRET_LENGTH }
        .distinct()
        .sortedByDescending(String::length)

    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var result = text
        secrets.forEach { secret -> result = result.replace(secret, REDACTED) }
        result = urlPattern.replace(result) { redactUrl(it.value) }
        result = authHeaderPattern.replace(result) { "${it.groupValues[1]}${it.groupValues[2]}$REDACTED" }
        result = bearerPattern.replace(result) { "${it.groupValues[1]} $REDACTED" }
        result = jwtPattern.replace(result, REDACTED)
        result = secretFieldPattern.replace(result) { "${it.groupValues[1]}${it.groupValues[2]}$REDACTED" }
        result = apiKeyLiteralPattern.replace(result, REDACTED)
        result = hexKeyPattern.replace(result, REDACTED)
        return result
    }

    fun redactUrl(url: String): String {
        val fragmentStart = url.indexOf('#').takeIf { it >= 0 } ?: url.length
        val queryStart = url.indexOf('?').takeIf { it in 0 until fragmentStart } ?: fragmentStart
        val schemeEnd = url.indexOf("://")
        val authorityStart = if (schemeEnd >= 0) schemeEnd + 3 else 0
        val pathStart = url.indexOf('/', authorityStart).takeIf { it in 0 until queryStart } ?: queryStart
        val authority = redactUserInfo(url.substring(authorityStart, pathStart))
        val path = url.substring(pathStart, queryStart)
            .split('/')
            .joinToString("/") { redactPathSegment(it) }
        val query = if (queryStart < fragmentStart) {
            "?" + url.substring(queryStart + 1, fragmentStart)
                .split('&')
                .joinToString("&") { redactQueryParameter(it) }
        } else {
            ""
        }
        val fragment = if (fragmentStart < url.length - 1) "#$REDACTED" else url.substring(fragmentStart)
        return url.substring(0, authorityStart) + authority + path + query + fragment
    }

    private fun redactUserInfo(authority: String): String {
        val at = authority.lastIndexOf('@')
        return if (at < 0) authority else "$REDACTED@" + authority.substring(at + 1)
    }

    private fun redactPathSegment(segment: String): String {
        if (segment.isEmpty() || segment == REDACTED) return segment
        if (segment.contains('=')) {
            return segment.split('|', ',', ';', '&').fold(segment) { acc, pair ->
                val name = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                when {
                    looksLikeToken(name) -> acc.replace(pair, REDACTED)
                    value.isNotEmpty() && (isSecretName(name) || looksLikeToken(value)) -> acc.replace(pair, "$name=$REDACTED")
                    else -> acc
                }
            }
        }
        val extension = segment.substringAfterLast('.', "")
        val stem = if (extension.length in 1..5 && extension.all(Char::isLetter)) {
            segment.dropLast(extension.length + 1)
        } else {
            segment
        }
        return if (looksLikeToken(stem)) REDACTED + segment.removePrefix(stem) else segment
    }

    private fun redactQueryParameter(parameter: String): String {
        val name = parameter.substringBefore('=')
        val value = parameter.substringAfter('=', "")
        if (value.isEmpty() || value == REDACTED) return parameter
        return if (isSecretName(name) || looksLikeToken(value)) "$name=$REDACTED" else parameter
    }

    companion object {
        private const val MIN_KNOWN_SECRET_LENGTH = 6
        private const val MIN_TOKEN_LENGTH = 20
        private const val NOT_REDACTED = """(?!\[redacted\])"""

        private val urlPattern = Regex("""\b(?:https?|stremio)://[^\s"'<>\[\](){}]+""", RegexOption.IGNORE_CASE)
        private val authHeaderPattern = Regex(
            """((?:proxy-)?authorization|x-api-key|api-key|apikey|cookie|set-cookie)(["']?\s*[:=]\s*["']?)$NOT_REDACTED[^"'\r\n,}]+""",
            RegexOption.IGNORE_CASE,
        )
        private val bearerPattern = Regex("""\b(bearer|basic)\s+$NOT_REDACTED[A-Za-z0-9._~+/=-]{8,}""", RegexOption.IGNORE_CASE)
        private val jwtPattern = Regex("""\beyJ[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]*""")
        private val secretFieldPattern = Regex(
            """(["']?[A-Za-z0-9_-]*(?:api[_-]?key|apikey|access[_-]?token|refresh[_-]?token|id[_-]?token|auth[_-]?token|client[_-]?secret|secret|password|passwd|token|anon[_-]?key|private[_-]?key)["']?)(\s*[:=]\s*["']?)$NOT_REDACTED[^"'\s&,;}\]]+""",
            RegexOption.IGNORE_CASE,
        )
        private val apiKeyLiteralPattern = Regex("""\b(?:sk|pk|rk)_(?:live|test)_[A-Za-z0-9]{8,}\b""")
        private val hexKeyPattern = Regex("""\b[a-fA-F0-9]{32}\b""")
        private val secretNamePattern = Regex(
            """(?i)(key|token|secret|password|passwd|auth|sig|signature|credential|session|cookie|client[_-]?id|debrid|premiumize|torbox|offcloud|putio)""",
        )

        fun isSecretName(name: String): Boolean = secretNamePattern.containsMatchIn(name)

        fun looksLikeToken(value: String): Boolean {
            if (value.length < MIN_TOKEN_LENGTH) return false
            if (!value.all { it.isLetterOrDigit() || it in "-_+/=%~." }) return false
            return value.any(Char::isDigit) && value.any(Char::isLetter)
        }
    }
}
