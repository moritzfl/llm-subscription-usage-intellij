package de.moritzf.quota.idea.common

import de.moritzf.quota.cursor.CursorQuota
import de.moritzf.quota.github.GitHubQuota
import de.moritzf.quota.kimi.KimiQuota
import de.moritzf.quota.minimax.MiniMaxQuota
import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.ollama.OllamaQuota
import de.moritzf.quota.zai.ZaiQuota

data class QuotaUsageSnapshot(
    val openAiQuota: OpenAiCodexQuota?,
    val openAiError: String?,
    val openCodeQuota: OpenCodeQuota?,
    val openCodeError: String?,
    val ollamaQuota: OllamaQuota?,
    val ollamaError: String?,
    val zaiQuota: ZaiQuota?,
    val zaiError: String?,
    val miniMaxQuota: MiniMaxQuota?,
    val miniMaxError: String?,
    val kimiQuota: KimiQuota?,
    val kimiError: String?,
    val gitHubQuota: GitHubQuota?,
    val gitHubError: String?,
    val cursorQuota: CursorQuota?,
    val cursorError: String?,
)
