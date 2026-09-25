package de.moritzf.quota.shared

internal fun rewriteMarkdownImageLinks(markdown: String, links: Map<String, String?>): String {
    if (links.isEmpty()) return markdown
    var text = markdown
    // Long data URLs are literal tokens, not huge regular-expression alternatives.
    val tokens = links.entries.mapIndexed { index, (id, target) ->
        if (id.length <= 2048) id to target else {
            val token = "ocr-image-${java.util.UUID.randomUUID()}-$index"
            text = text.replace(id, token)
            token to target
        }
    }.toMap()
    for ((id, target) in tokens) {
        if (target == null) {
            text = Regex("!\\[([^]]*)]\\(\\s*<?${Regex.escape(id)}>?(?:\\s+[\"'][^\\r\\n]*[\"'])?\\s*\\)")
                .replace(text) { it.groupValues[1] }
        }
    }
    val valid = tokens.filterValues { it != null }
    if (valid.isEmpty()) return text
    val ids = valid.keys.sortedByDescending(String::length).joinToString("|", transform = Regex::escape)
    val patterns = listOf(
        Regex("(]\\(\\s*<?)($ids)(?=>?\\s*(?:[\"'][^\\r\\n]*[\"']\\s*)?\\))"),
        Regex("(?m)^(\\s{0,3}\\[[^]\\r\\n]+]:\\s*<?)($ids)(?=>?(?:\\s|$))"),
        Regex("(\\bsrc\\s*=\\s*[\"'])($ids)(?=[\"'])"),
    )
    return patterns.fold(text) { value, pattern ->
        pattern.replace(value) { match -> match.groupValues[1] + valid.getValue(match.groupValues[2]) }
    }
}
