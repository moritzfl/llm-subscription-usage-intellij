package de.moritzf.proxy.fim

internal data class FimCommentContext(
    val marker: String,
    val markerOffset: Int,
    val closer: String?,
) {
    companion object {
        fun detect(prefix: String, languageHint: String? = null): FimCommentContext? {
            val language = languageHint?.trim()?.lowercase()?.removePrefix(".")?.takeIf { it.isNotEmpty() }
            val hashComments = language in HASH_LANGUAGES
            val dashComments = language == "sql" || language == "lua"
            val markup = language in MARKUP_LANGUAGES
            val slashComments = !hashComments && !dashComments && !markup
            val blockComments = !hashComments && language != "lua" && !markup
            val nestedBlocks = language in NESTED_BLOCK_LANGUAGES
            val comments = ArrayDeque<FimCommentContext>()
            var quote: String? = null
            var index = 0
            while (index < prefix.length) {
                val character = prefix[index]
                val comment = comments.lastOrNull()
                if (comment != null) {
                    val closer = comment.closer
                    when {
                        closer == null -> {
                            if (character == '\n' || character == '\r') comments.removeLast()
                            index++
                        }
                        prefix.startsWith(closer, index) -> {
                            comments.removeLast()
                            index += closer.length
                        }
                        nestedBlocks && closer == "*/" && prefix.startsWith("/*", index) -> {
                            val marker = if (prefix.startsWith("/**", index)) "/**" else "/*"
                            comments.addLast(FimCommentContext(marker, index, "*/"))
                            index += 2
                        }
                        else -> index++
                    }
                    continue
                }
                val delimiter = quote
                if (delimiter != null) {
                    when {
                        character == '\\' -> index += 2
                        prefix.startsWith(delimiter, index) -> {
                            quote = null
                            index += delimiter.length
                        }
                        else -> index++
                    }
                    continue
                }
                if (character == '\\') {
                    index += 2
                    continue
                }
                if (character == '\'' || character == '"' || character == '`') {
                    val triple = character.toString().repeat(3)
                    quote = if (prefix.startsWith(triple, index)) triple else character.toString()
                    index += quote.length
                    continue
                }

                val separatedBefore = index == 0 || prefix[index - 1].isWhitespace()
                val marker = when {
                    prefix.startsWith("<!--", index) -> "<!--"
                    blockComments && prefix.startsWith("/**", index) -> "/**"
                    blockComments && prefix.startsWith("/*", index) -> "/*"
                    slashComments && prefix.startsWith("//", index) && prefix.getOrNull(index - 1) != ':' -> when {
                        prefix.startsWith("///", index) -> "///"
                        prefix.startsWith("//!", index) -> "//!"
                        else -> "//"
                    }
                    character == '#' && (
                        hashComments && (language in INLINE_HASH_LANGUAGES || separatedBefore) ||
                            language == null && separatedBefore && (prefix.getOrNull(index + 1)?.isWhitespace() != false)
                        ) -> "#"
                    prefix.startsWith("--", index) && (
                        dashComments || language == null && separatedBefore &&
                            (prefix.getOrNull(index + 2)?.isWhitespace() != false)
                        ) -> "--"
                    else -> null
                }
                if (marker == null) {
                    index++
                    continue
                }
                val closer = when (marker) {
                    "/*", "/**" -> "*/"
                    "<!--" -> "-->"
                    else -> null
                }
                comments.addLast(FimCommentContext(marker, index, closer))
                index += if (closer == "*/") 2 else marker.length
            }

            val comment = comments.lastOrNull() ?: return null
            if (comment.closer == "*/") {
                val lineStart = maxOf(prefix.lastIndexOf('\n'), prefix.lastIndexOf('\r')) + 1
                val firstText = (lineStart until prefix.length).firstOrNull { !prefix[it].isWhitespace() }
                if (lineStart > comment.markerOffset && firstText != null && prefix[firstText] == '*') {
                    return comment.copy(marker = "*", markerOffset = firstText)
                }
            }
            return comment
        }

        private val HASH_LANGUAGES = setOf(
            "py", "pyw", "python", "sh", "shell", "bash", "zsh", "fish",
            "yaml", "yml", "rb", "ruby", "toml", "ini", "conf", "properties", "ps1", "r", "jl",
        )
        private val INLINE_HASH_LANGUAGES = setOf("py", "pyw", "python", "rb", "ruby")
        private val MARKUP_LANGUAGES = setOf("md", "markdown", "mdx", "html", "htm", "xml", "svg")
        private val NESTED_BLOCK_LANGUAGES = setOf("kt", "kts", "kotlin", "rs", "rust", "scala", "sc", "swift", "sql")
    }
}
