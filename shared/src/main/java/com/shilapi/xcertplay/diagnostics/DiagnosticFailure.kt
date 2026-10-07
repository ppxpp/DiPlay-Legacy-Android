package com.shilapi.xcertplay.diagnostics

/** Keep an operation's reason, never keys, protocol payloads, tokens or peer identifiers. */
object DiagnosticFailure {
    private val sensitive = Regex("(?i)(pass(word|phrase)?|token|private.?key|certificate|pair.?record|body[=:]|payload[=:]|[0-9a-f]{40,})")
    private val mac = Regex("(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}")
    private val ipv4 = Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")
    private val ipv6 = Regex("(?i)(?:[0-9a-f]{0,4}:){2,}[0-9a-f:]*")
    fun safeReason(message: String?): String {
        val line = message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        if (line.isBlank() || sensitive.containsMatchIn(message.orEmpty())) return "Operation failed; sensitive details omitted from diagnostics"
        return line.replace(mac, "[address]").replace(ipv4, "[ip]").replace(ipv6, "[ip]").take(240)
    }
    fun describe(error: Throwable): String = "${error.javaClass.simpleName}: ${safeReason(error.message)}"
}
