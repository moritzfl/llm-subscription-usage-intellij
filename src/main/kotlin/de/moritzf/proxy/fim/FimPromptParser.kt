package de.moritzf.proxy.fim

object FimPromptParser {
    private const val QWEN_PREFIX = "<|fim_prefix|>"
    private const val QWEN_SUFFIX = "<|fim_suffix|>"
    private const val QWEN_MIDDLE = "<|fim_middle|>"
    private const val DEEPSEEK_PREFIX = "<fim_prefix>"
    private const val DEEPSEEK_SUFFIX = "<fim_suffix>"
    private const val DEEPSEEK_MIDDLE = "<fim_middle>"
    private const val CJK_BEGIN = "<｜fim▁begin｜>"
    private const val CJK_HOLE = "<｜fim▁hole｜>"
    private const val CJK_END = "<｜fim▁end｜>"
    private const val CODESTRAL_PREFIX = "[PREFIX]"
    private const val CODESTRAL_SUFFIX = "[SUFFIX]"
    private const val CODESTRAL_MIDDLE = "[MIDDLE]"
    private const val GENERIC_PRE = "<PRE>"
    private const val GENERIC_SUF = "<SUF>"
    private const val GENERIC_MID = "<MID>"
    private const val FILE_SEP = "<|file_sep|>"
    private const val REPO_NAME = "<|repo_name|>"

    private val KNOWN_TOKENS = listOf(
        QWEN_PREFIX, QWEN_SUFFIX, QWEN_MIDDLE,
        DEEPSEEK_PREFIX, DEEPSEEK_SUFFIX, DEEPSEEK_MIDDLE,
        CJK_BEGIN, CJK_HOLE, CJK_END,
        CODESTRAL_PREFIX, CODESTRAL_SUFFIX, CODESTRAL_MIDDLE,
        GENERIC_PRE, GENERIC_SUF, GENERIC_MID,
        FILE_SEP, REPO_NAME,
    )

    fun parse(prompt: String, suffix: String? = null): FimContext {
        val fieldSuffix = suffix?.takeIf { it.isNotBlank() }
        if (fieldSuffix != null && !containsFimTokens(prompt)) {
            return context(
                schema = FimSchema.UNKNOWN_CHAT,
                prefix = prompt,
                suffix = fieldSuffix,
                originalPrompt = prompt,
            )
        }
        triple(prompt, QWEN_PREFIX, QWEN_SUFFIX, QWEN_MIDDLE)?.let { (prefix, parsedSuffix) ->
            return context(FimSchema.QWEN, prefix, parsedSuffix, prompt)
        }
        triple(prompt, DEEPSEEK_PREFIX, DEEPSEEK_SUFFIX, DEEPSEEK_MIDDLE)?.let { (prefix, parsedSuffix) ->
            return context(FimSchema.DEEPSEEK, prefix, parsedSuffix, prompt)
        }
        triple(prompt, CJK_BEGIN, CJK_HOLE, CJK_END)?.let { (prefix, parsedSuffix) ->
            return context(FimSchema.DEEPSEEK_CJK, prefix, parsedSuffix, prompt)
        }
        codestral(prompt, fieldSuffix)?.let { return it }
        triple(prompt, GENERIC_PRE, GENERIC_SUF, GENERIC_MID)?.let { (prefix, parsedSuffix) ->
            return context(FimSchema.GENERIC_PRE, prefix, parsedSuffix, prompt)
        }
        return context(
            schema = FimSchema.UNKNOWN_CHAT,
            prefix = prompt,
            suffix = fieldSuffix.orEmpty(),
            originalPrompt = prompt,
        )
    }

    private fun containsFimTokens(prompt: String): Boolean = KNOWN_TOKENS.any { prompt.contains(it) }

    private fun triple(prompt: String, prefixTok: String, suffixTok: String, middleTok: String): Pair<String, String>? {
        val prefixAt = prompt.indexOf(prefixTok)
        val suffixAt = prompt.indexOf(suffixTok)
        val middleAt = prompt.indexOf(middleTok)
        if (prefixAt < 0 || suffixAt < 0 || middleAt < 0) return null
        if (prefixAt > suffixAt || suffixAt > middleAt) return null
        val prefix = prompt.substring(prefixAt + prefixTok.length, suffixAt)
        val suffix = prompt.substring(suffixAt + suffixTok.length, middleAt)
        return prefix to suffix
    }

    private fun codestral(prompt: String, fieldSuffix: String?): FimContext? {
        val prefixAt = prompt.indexOf(CODESTRAL_PREFIX)
        if (prefixAt < 0) return null
        val suffixAt = prompt.indexOf(CODESTRAL_SUFFIX)
        val middleAt = prompt.indexOf(CODESTRAL_MIDDLE)
        val prefixStart = prefixAt + CODESTRAL_PREFIX.length
        val prefix: String
        val suffix: String
        when {
            suffixAt >= prefixStart && middleAt > suffixAt -> {
                prefix = prompt.substring(prefixStart, suffixAt)
                suffix = prompt.substring(suffixAt + CODESTRAL_SUFFIX.length, middleAt)
            }
            suffixAt >= prefixStart -> {
                prefix = prompt.substring(prefixStart, suffixAt)
                suffix = prompt.substring(suffixAt + CODESTRAL_SUFFIX.length)
            }
            fieldSuffix != null -> {
                prefix = prompt.substring(prefixStart)
                suffix = fieldSuffix
            }
            else -> {
                prefix = prompt.substring(prefixStart)
                suffix = ""
            }
        }
        return context(FimSchema.CODESTRAL, prefix, suffix, prompt)
    }

    private fun context(
        schema: FimSchema,
        prefix: String,
        suffix: String,
        originalPrompt: String,
    ): FimContext {
        val repoName = repoName(originalPrompt)
        val files = fileSlices(originalPrompt)
        val currentIndex = files.indexOfFirst { slice ->
            slice.content.contains("<|fim_prefix|>") ||
                slice.content.contains("<fim_prefix") ||
                slice.content.contains(CJK_BEGIN) ||
                slice.content.contains(CODESTRAL_PREFIX) ||
                slice.content.contains(GENERIC_PRE)
        }
        val current = currentIndex.takeIf { it >= 0 }?.let { files[it] }
        val extra = if (currentIndex >= 0) {
            files.filterIndexed { index, _ -> index != currentIndex }
        } else {
            files
        }
        val filePath = current?.path
        return FimContext(
            schema = schema,
            prefix = prefix,
            suffix = suffix,
            filePath = filePath,
            repoName = repoName,
            languageHint = languageHint(filePath),
            extraFiles = extra.filter { it.content.isNotBlank() || it.path.isNotBlank() },
        )
    }

    private fun repoName(prompt: String): String? {
        val start = prompt.indexOf(REPO_NAME)
        if (start < 0) return null
        val from = start + REPO_NAME.length
        val endCandidates = listOf(
            prompt.indexOf('\n', from),
            prompt.indexOf(FILE_SEP, from),
            prompt.indexOf(QWEN_PREFIX, from),
        ).filter { it >= 0 }
        val end = endCandidates.minOrNull() ?: prompt.length
        return prompt.substring(from, end).trim().takeIf { it.isNotEmpty() }
    }

    private fun fileSlices(prompt: String): List<FimFileSlice> {
        if (!prompt.contains(FILE_SEP)) return emptyList()
        val parts = prompt.split(FILE_SEP)
        if (parts.size < 2) return emptyList()
        return parts.drop(1).map { hunk ->
            val newline = hunk.indexOf('\n')
            if (newline < 0) {
                FimFileSlice(path = hunk.trim(), content = "")
            } else {
                FimFileSlice(
                    path = hunk.substring(0, newline).trim(),
                    content = hunk.substring(newline + 1),
                )
            }
        }
    }

    internal fun languageHint(filePath: String?): String? {
        val name = filePath?.substringAfterLast('/')?.substringAfterLast('\\') ?: return null
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.lastIndex) return null
        val ext = name.substring(dot + 1)
        if (ext.any { !it.isLetterOrDigit() }) return null
        return ext.lowercase()
    }
}
