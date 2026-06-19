package de.moritzf.proxy.server

import java.util.Locale
import java.util.UUID
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

object JunieCommandProtocolCompat {
    private val COMMAND_PATTERN: Pattern = Pattern.compile(
        "<COMMAND(\\d{0,2})>.*?</COMMAND\\1>",
        Pattern.DOTALL,
    )
    private val XML_TAG_PATTERN: Pattern = Pattern.compile("</?[A-Z_]+>")
    private val UPDATE_MARKUP_PATTERN: Pattern = Pattern.compile(
        "</?(UPDATE|PREVIOUS_STEP|PLAN|NEXT_STEP)>",
        Pattern.CASE_INSENSITIVE,
    )

    /**
     * Detects Junie's matterhorn command protocol via the "You are Junie" system
     * prompt. The `stop: ["</COMMAND>"]` parameter is deliberately NOT a
     * signal: Junie 1892.26 attaches it to every LLM call, including plain-text
     * utility prompts (task-name summarizer, step summarizer, allowlist
     * generator) whose output it displays verbatim; wrapping those in
     * THOUGHT/COMMAND leaks raw tags into the UI. Scanning the whole body would
     * likewise misfire on any conversation that merely mentions Junie.
     */
    fun isJunieRequest(body: JsonObject?): Boolean {
        if (body == null) {
            return false
        }
        val systemText = systemText(body).lowercase(Locale.ROOT)
        return systemText.contains("you are junie")
    }

    /** Collects instructions plus all system/developer message text from chat and responses bodies. */
    private fun systemText(body: JsonObject): String {
        val text = StringBuilder()
        val instructions = body["instructions"]
        if (instructions.isTextual()) {
            text.append(instructions.text).append('\n')
        }
        appendSystemMessages(text, body["messages"])
        appendSystemMessages(text, body["input"])
        return text.toString()
    }

    private fun appendSystemMessages(target: StringBuilder, messages: JsonElement?) {
        if (messages !is JsonArray) {
            return
        }
        for (messageElement in messages) {
            val message = messageElement as? JsonObject ?: continue
            val role = message.stringPath("role", "")
            if (role != "system" && role != "developer") {
                continue
            }
            target.append(messageText(message["content"])).append('\n')
        }
    }

    fun messageText(content: JsonElement?): String {
        if (content == null) {
            return ""
        }
        if (content.isTextual()) {
            return content.text
        }
        if (content !is JsonArray) {
            return ""
        }
        val text = StringBuilder()
        for (partElement in content) {
            val part = partElement as? JsonObject ?: continue
            val type = part.stringPath("type", "")
            if (type == "text" || type == "input_text" || type == "output_text") {
                text.append(part.stringPath("text", ""))
            }
        }
        return text.toString()
    }

    fun wrapCompletedResponse(completedResponse: JsonObject?): JsonObject? {
        if (completedResponse == null) {
            return completedResponse
        }
        val textParts = outputTextPartTexts(completedResponse["output"])
        if (textParts.isEmpty()) {
            return completedResponse
        }
        val text = textParts.joinToString("")
        if (hasCommand(text)) {
            return completedResponse
        }
        return mapOutputTextParts(completedResponse) { _, index ->
            if (index == 0) wrapPlainText(text) else ""
        }
    }

    fun hasFunctionCallOutput(completedResponse: JsonObject?): Boolean {
        val output = completedResponse?.get("output")
        if (output !is JsonArray) {
            return false
        }
        for (itemElement in output) {
            val item = itemElement as? JsonObject ?: continue
            if (item.stringPath("type", "") == "function_call") {
                return true
            }
        }
        return false
    }

    fun toToolResponse(completedResponse: JsonObject?, toolName: String): JsonObject? {
        if (completedResponse == null) {
            return completedResponse
        }
        val copy = MutableJsonObject(completedResponse)
        val text = plainOutputText(completedResponse)
        val output = createArrayNode()
        output.add(responsesToolCall(toolName, text))
        copy.set("output", output)
        return copy.build()
    }

    fun fallbackToolName(body: JsonObject?): String? {
        val declared = declaredFallbackToolName(body)
        if (declared != null) {
            return declared
        }
        if (isJunieRequest(body) && !hasToolDefinitions(body)) {
            return "submit"
        }
        return null
    }

    /** Returns submit/answer only when the request actually declares that tool. */
    fun declaredFallbackToolName(body: JsonObject?): String? {
        if (hasTool(body, "submit")) {
            return "submit"
        }
        if (hasTool(body, "answer")) {
            return "answer"
        }
        return null
    }

    fun hasToolDefinitions(body: JsonObject?): Boolean {
        if (body == null) {
            return false
        }
        return !hasNoToolDefinitions(body["tools"]) || !hasNoToolDefinitions(body["functions"])
    }

    private fun hasNoToolDefinitions(tools: JsonElement?): Boolean {
        return tools !is JsonArray || tools.isEmpty()
    }

    private fun hasTool(body: JsonObject?, toolName: String): Boolean {
        if (body == null) {
            return false
        }
        return hasToolDefinition(body["tools"], toolName) || hasToolDefinition(body["functions"], toolName)
    }

    private fun hasToolDefinition(tools: JsonElement?, toolName: String): Boolean {
        if (tools !is JsonArray) {
            return false
        }
        for (toolElement in tools) {
            val tool = toolElement as? JsonObject ?: continue
            val name = tool.stringPath("name", tool.pathOrNull("function").stringPath("name", ""))
            if (toolName == name) {
                return true
            }
        }
        return false
    }

    /**
     * Junie's native tool-call protocol displays assistant text verbatim as the step
     * thought; unlike the <THOUGHT>/<COMMAND> text protocol it never routes that text
     * through its update_status tag parser, so <UPDATE>/<PLAN> plan markup would reach
     * the UI raw. Reformat the markup into plain readable text. Every section's content
     * is kept: Junie echoes this text back as assistant history, and the model relies
     * on the previous plan to track progress across steps.
     */
    fun formatUpdateMarkup(text: String?): String? {
        if (text == null || !UPDATE_MARKUP_PATTERN.matcher(text).find()) {
            return text
        }
        var formatted = text
        formatted = replaceTagSection(formatted, "PREVIOUS_STEP", "")
        formatted = replaceTagSection(formatted, "PLAN", "Plan:\n")
        formatted = replaceTagSection(formatted, "NEXT_STEP", "Next: ")
        formatted = XML_TAG_PATTERN.matcher(formatted).replaceAll("")
        formatted = formatted.replace(Regex("\n{3,}"), "\n\n").trim()
        return formatted.ifBlank { text }
    }

    /** Applies [formatUpdateMarkup] to all output_text parts of a completed Responses payload. */
    fun formatUpdateMarkupInResponse(completedResponse: JsonObject?): JsonObject? {
        if (completedResponse == null) {
            return completedResponse
        }
        var changed = false
        val result = mapOutputTextParts(completedResponse) { text, _ ->
            val formatted = formatUpdateMarkup(text)
            if (text != formatted) {
                changed = true
            }
            formatted ?: text
        }
        return if (changed) result else completedResponse
    }

    private fun replaceTagSection(text: String, tagName: String, prefix: String): String {
        val pattern = Pattern.compile(
            "<$tagName>\\s*(.*?)\\s*</$tagName>",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL,
        )
        val matcher = pattern.matcher(text)
        val result = StringBuilder()
        while (matcher.find()) {
            val content = matcher.group(1).trim()
            val replacement = if (content.isBlank()) "" else "\n$prefix$content\n"
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement))
        }
        matcher.appendTail(result)
        return result.toString()
    }

    fun wrapPlainText(text: String?): String {
        if (text.isNullOrBlank()) {
            return "<THOUGHT>Ready to submit.</THOUGHT>\n<COMMAND>submit</COMMAND>"
        }
        if (hasCommand(text)) {
            return text
        }
        var thought = displayText(text).trim().replace("</THOUGHT>", "<\\/THOUGHT>")
        if (thought.isBlank()) {
            thought = "Ready to submit."
        }
        return "<THOUGHT>$thought</THOUGHT>\n<COMMAND>submit</COMMAND>"
    }

    fun wrapStreamingText(toolName: String?, text: String?): String {
        if (toolName == "submit") {
            return wrapPlainText(text)
        }
        return wrapCommandText(toolName, text)
    }

    fun textFromToolArguments(toolName: String?, argumentsJson: String?): String {
        if (argumentsJson.isNullOrBlank()) {
            return ""
        }
        return try {
            val arguments = JsonHelper.parseToJsonElement(argumentsJson) as? JsonObject ?: return argumentsJson
            if (toolName == "answer") {
                arguments.stringPath("full_answer", "")
            } else {
                arguments.stringPath("solution_summary", "")
            }
        } catch (_: Exception) {
            argumentsJson
        }
    }

    private fun wrapCommandText(toolName: String?, text: String?): String {
        val name = if (toolName.isNullOrBlank()) "submit" else toolName
        val argument = if (text == null) "" else displayText(text).trim().replace("</COMMAND>", "<\\/COMMAND>")
        if (argument.isBlank()) {
            return "<COMMAND>$name</COMMAND>"
        }
        return "<COMMAND>$name $argument</COMMAND>"
    }

    private fun hasCommand(text: String?): Boolean {
        return text != null && COMMAND_PATTERN.matcher(text).find()
    }

    private fun responsesToolCall(toolName: String, text: String): MutableJsonObject {
        val callId = newToolCallId()
        val item = createObjectNode()
        item.put("type", "function_call")
        item.put("id", "fc_" + callId.substring("call_".length))
        item.put("call_id", callId)
        item.put("name", toolName)
        item.put("arguments", toolArguments(toolName, text))
        item.put("status", "completed")
        return item
    }

    fun newToolCallId(): String {
        return "call_quota_submit_" + UUID.randomUUID().toString().replace("-", "")
    }

    /** Builds a chat-completions-format tool call carrying plain model text as tool arguments. */
    fun chatToolCall(toolName: String, text: String?): MutableJsonObject {
        val toolCall = createObjectNode()
        toolCall.put("id", newToolCallId())
        toolCall.put("type", "function")
        val function = createObjectNode()
        function.put("name", toolName)
        function.put("arguments", toolArguments(toolName, text))
        toolCall.set("function", function)
        return toolCall
    }

    private fun submitArguments(text: String?): String = arguments("solution_summary", text)

    private fun toolArguments(toolName: String, text: String?): String {
        val displayText = displayText(text)
        if (toolName == "answer") {
            return arguments("full_answer", displayText)
        }
        return submitArguments(displayText)
    }

    private fun displayText(text: String?): String {
        if (text.isNullOrBlank()) {
            return ""
        }
        val parts = ArrayList<String>()
        addIfNotBlank(parts, tagText(text, "PREVIOUS_STEP"))
        addIfNotBlank(parts, tagText(text, "NEXT_STEP"))
        if (parts.isEmpty()) {
            addIfNotBlank(parts, tagText(text, "THOUGHT"))
        }
        if (parts.isNotEmpty()) {
            return parts.joinToString("\n\n")
        }
        val withoutCommand = COMMAND_PATTERN.matcher(text).replaceAll("")
        return XML_TAG_PATTERN.matcher(withoutCommand).replaceAll("").trim()
    }

    private fun addIfNotBlank(parts: MutableList<String>, text: String) {
        if (text.isNotBlank() && !parts.contains(text)) {
            parts.add(text)
        }
    }

    private fun tagText(text: String, tagName: String): String {
        val pattern = Pattern.compile(
            "<$tagName>(.*?)</$tagName>",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL,
        )
        val matcher = pattern.matcher(text)
        if (!matcher.find()) {
            return ""
        }
        return XML_TAG_PATTERN.matcher(matcher.group(1)).replaceAll("").trim()
    }

    private fun arguments(fieldName: String, text: String?): String {
        val arguments = createObjectNode()
        arguments.put(fieldName, if (text.isNullOrBlank()) "Done." else text.trim())
        return try {
            JsonHelper.encodeToString(arguments.build())
        } catch (_: Exception) {
            "{\"$fieldName\":\"Done.\"}"
        }
    }

    private fun plainOutputText(completedResponse: JsonObject): String {
        return outputTextPartTexts(completedResponse["output"]).joinToString("")
    }

    private fun outputTextPartTexts(output: JsonElement?): List<String> {
        val parts = ArrayList<String>()
        if (output !is JsonArray) {
            return parts
        }
        for (itemElement in output) {
            val item = itemElement as? JsonObject ?: continue
            if (item.stringPath("type", "") != "message") {
                continue
            }
            val content = item["content"] as? JsonArray ?: continue
            for (partElement in content) {
                val part = partElement as? JsonObject ?: continue
                if (part.stringPath("type", "") == "output_text") {
                    parts.add(part.stringPath("text", ""))
                }
            }
        }
        return parts
    }

    private fun mapOutputTextParts(response: JsonObject, mapper: (String, Int) -> String): JsonObject {
        val output = response["output"] as? JsonArray ?: return response
        val mappedOutput = createArrayNode()
        var changed = false
        var textPartIndex = 0
        for (itemElement in output) {
            val item = itemElement as? JsonObject
            if (item == null || item.stringPath("type", "") != "message") {
                mappedOutput.add(itemElement)
                continue
            }
            val content = item["content"] as? JsonArray
            if (content == null) {
                mappedOutput.add(item)
                continue
            }
            val mappedContent = createArrayNode()
            var itemChanged = false
            for (partElement in content) {
                val part = partElement as? JsonObject
                if (part != null && part.stringPath("type", "") == "output_text") {
                    val oldText = part.stringPath("text", "")
                    val newText = mapper(oldText, textPartIndex)
                    textPartIndex++
                    if (oldText != newText) {
                        val partCopy = MutableJsonObject(part)
                        partCopy.put("text", newText)
                        mappedContent.add(partCopy)
                        itemChanged = true
                        changed = true
                    } else {
                        mappedContent.add(part)
                    }
                } else {
                    mappedContent.add(partElement)
                }
            }
            if (itemChanged) {
                val itemCopy = MutableJsonObject(item)
                itemCopy.set("content", mappedContent)
                mappedOutput.add(itemCopy)
            } else {
                mappedOutput.add(item)
            }
        }
        if (!changed) {
            return response
        }
        val responseCopy = MutableJsonObject(response)
        responseCopy.set("output", mappedOutput)
        return responseCopy.build()
    }
}
