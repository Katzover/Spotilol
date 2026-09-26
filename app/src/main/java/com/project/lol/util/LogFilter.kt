package com.project.lol.util

private class LogSpec(
    val minPriority: Int,
    val all: Boolean,
    val packageMine: Boolean,
    val packageBlocked: Boolean,
    val tags: List<String>,
    val messages: List<String>,
    val terms: List<String>,
    val excludes: List<String>
) {
    fun matches(entry: LogEntry): Boolean {
        if (entry.level.priority < minPriority) return false
        if (packageBlocked) return false
        if (all) return true
        val tag = entry.tag.lowercase()
        val message = entry.message.lowercase()
        val haystack = tag + " " + message
        for (value in tags) if (!tag.contains(value)) return false
        for (value in messages) if (!message.contains(value)) return false
        for (value in terms) if (!haystack.contains(value)) return false
        for (value in excludes) if (haystack.contains(value)) return false
        return true
    }
}

object LogFilter {

    private fun parse(query: String, minLevel: LogLevel = LogLevel.VERBOSE): LogSpec {
        var min = minLevel.priority
        val tags = ArrayList<String>()
        val messages = ArrayList<String>()
        val terms = ArrayList<String>()
        val excludes = ArrayList<String>()
        var packageMine = false
        var packageBlocked = false
        var all = true

        for (raw in query.trim().split(' ', '\t', '\n')) {
            if (raw.isEmpty()) continue
            var token = raw
            var negated = false
            if (token.length > 1 && token[0] == '-') {
                negated = true
                token = token.substring(1)
            }
            val split = token.indexOf(':')
            val key = if (split > 0) token.substring(0, split).lowercase() else ""
            val value = if (split > 0) token.substring(split + 1).lowercase() else token.lowercase()
            if (value.isEmpty()) continue
            all = false
            when (key) {
                "tag" -> if (negated) excludes.add(value) else tags.add(value)
                "msg", "message" -> if (negated) excludes.add(value) else messages.add(value)
                "level", "lvl", "l", "priority", "p" -> {
                    LogLevel.parse(value)?.let { min = maxOf(min, it.priority) }
                }
                "package", "pkg" -> when (value) {
                    "mine" -> packageMine = true
                    else -> packageBlocked = true
                }
                "" -> if (negated) excludes.add(value) else terms.add(value)
                else -> {
                    if (negated) excludes.add(value) else terms.add(token.lowercase())
                }
            }
        }
        return LogSpec(min, all, packageMine, packageBlocked, tags, messages, terms, excludes)
    }

    fun apply(
        entries: List<LogEntry>,
        query: String,
        minLevel: LogLevel = LogLevel.VERBOSE
    ): List<LogEntry> {
        val spec = parse(query, minLevel)
        if (spec.all && spec.minPriority <= LogLevel.VERBOSE.priority) return entries
        return entries.filter { spec.matches(it) }
    }

    fun text(entries: List<LogEntry>): String = entries.joinToString("\n") { it.line() }
}
