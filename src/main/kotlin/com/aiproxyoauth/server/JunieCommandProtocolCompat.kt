package com.aiproxyoauth.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.util.Locale
import java.util.UUID
import java.util.regex.Matcher
import java.util.regex.Pattern

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
    @JvmStatic
    fun isJunieRequest(body: JsonNode?): Boolean {
        if (body == null) {
            return false
        }
        val systemText = systemText(body).lowercase(Locale.ROOT)
        return systemText.contains("you are junie")
    }

    /** Collects instructions plus all system/developer message text from chat and responses bodies. */
    private fun systemText(body: JsonNode): String {
        val text = StringBuilder()
        val instructions = body.get("instructions")
        if (instructions != null && instructions.isTextual) {
            text.append(instructions.asText()).append('\n')
        }
        appendSystemMessages(text, body.get("messages"))
        appendSystemMessages(text, body.get("input"))
        return text.toString()
    }

    private fun appendSystemMessages(target: StringBuilder, messages: JsonNode?) {
        if (messages == null || !messages.isArray) {
            return
        }
        for (message in messages) {
            if (!message.isObject) {
                continue
            }
            val role = message.path("role").asText("")
            if (role != "system" && role != "developer") {
                continue
            }
            target.append(messageText(message.get("content"))).append('\n')
        }
    }

    @JvmStatic
    fun messageText(content: JsonNode?): String {
        if (content == null) {
            return ""
        }
        if (content.isTextual) {
            return content.asText()
        }
        if (!content.isArray) {
            return ""
        }
        val text = StringBuilder()
        for (part in content) {
            val type = part.path("type").asText("")
            if (type == "text" || type == "input_text" || type == "output_text") {
                text.append(part.path("text").asText(""))
            }
        }
        return text.toString()
    }

    @JvmStatic
    fun wrapCompletedResponse(completedResponse: JsonNode?): JsonNode? {
        if (completedResponse == null || !completedResponse.isObject) {
            return completedResponse
        }
        val copy: ObjectNode = completedResponse.deepCopy()
        val textParts = outputTextParts(copy.get("output"))
        if (textParts.isEmpty()) {
            return copy
        }

        val combined = StringBuilder()
        for (part in textParts) {
            combined.append(part.path("text").asText(""))
        }
        val text = combined.toString()
        if (hasCommand(text)) {
            return copy
        }

        textParts.first().put("text", wrapPlainText(text))
        textParts.drop(1).forEach { part -> part.put("text", "") }
        return copy
    }

    @JvmStatic
    fun hasFunctionCallOutput(completedResponse: JsonNode?): Boolean {
        val output = completedResponse?.get("output")
        if (output == null || !output.isArray) {
            return false
        }
        for (item in output) {
            if (item.path("type").asText("") == "function_call") {
                return true
            }
        }
        return false
    }

    @JvmStatic
    fun toToolResponse(completedResponse: JsonNode?, toolName: String): JsonNode? {
        if (completedResponse == null || !completedResponse.isObject) {
            return completedResponse
        }
        val copy: ObjectNode = completedResponse.deepCopy()
        val text = plainOutputText(copy)
        val output = JsonHelper.MAPPER.createArrayNode()
        output.add(responsesToolCall(toolName, text))
        copy.set<ArrayNode>("output", output)
        return copy
    }

    @JvmStatic
    fun fallbackToolName(body: JsonNode?): String? {
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
    @JvmStatic
    fun declaredFallbackToolName(body: JsonNode?): String? {
        if (hasTool(body, "submit")) {
            return "submit"
        }
        if (hasTool(body, "answer")) {
            return "answer"
        }
        return null
    }

    @JvmStatic
    fun hasToolDefinitions(body: JsonNode?): Boolean {
        if (body == null) {
            return false
        }
        return !hasNoToolDefinitions(body.get("tools")) || !hasNoToolDefinitions(body.get("functions"))
    }

    private fun hasNoToolDefinitions(tools: JsonNode?): Boolean {
        return tools == null || !tools.isArray || tools.isEmpty
    }

    private fun hasTool(body: JsonNode?, toolName: String): Boolean {
        if (body == null) {
            return false
        }
        return hasToolDefinition(body.get("tools"), toolName) || hasToolDefinition(body.get("functions"), toolName)
    }

    private fun hasToolDefinition(tools: JsonNode?, toolName: String): Boolean {
        if (tools == null || !tools.isArray) {
            return false
        }
        for (tool in tools) {
            val name = tool.path("name").asText(tool.path("function").path("name").asText(""))
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
    @JvmStatic
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
    @JvmStatic
    fun formatUpdateMarkupInResponse(completedResponse: JsonNode?): JsonNode? {
        if (completedResponse == null || !completedResponse.isObject) {
            return completedResponse
        }
        val copy: ObjectNode = completedResponse.deepCopy()
        var changed = false
        for (part in outputTextParts(copy.get("output"))) {
            val text = part.path("text").asText("")
            val formatted = formatUpdateMarkup(text)
            if (text != formatted) {
                part.put("text", formatted)
                changed = true
            }
        }
        return if (changed) copy else completedResponse
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

    @JvmStatic
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

    @JvmStatic
    fun wrapStreamingText(toolName: String?, text: String?): String {
        if (toolName == "submit") {
            return wrapPlainText(text)
        }
        return wrapCommandText(toolName, text)
    }

    @JvmStatic
    fun textFromToolArguments(toolName: String?, argumentsJson: String?): String {
        if (argumentsJson.isNullOrBlank()) {
            return ""
        }
        return try {
            val arguments = JsonHelper.MAPPER.readTree(argumentsJson)
            if (toolName == "answer") {
                arguments.path("full_answer").asText("")
            } else {
                arguments.path("solution_summary").asText("")
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

    private fun responsesToolCall(toolName: String, text: String): ObjectNode {
        val callId = newToolCallId()
        val item = JsonHelper.MAPPER.createObjectNode()
        item.put("type", "function_call")
        item.put("id", "fc_" + callId.substring("call_".length))
        item.put("call_id", callId)
        item.put("name", toolName)
        item.put("arguments", toolArguments(toolName, text))
        item.put("status", "completed")
        return item
    }

    @JvmStatic
    fun newToolCallId(): String {
        return "call_quota_submit_" + UUID.randomUUID().toString().replace("-", "")
    }

    /** Builds a chat-completions-format tool call carrying plain model text as tool arguments. */
    @JvmStatic
    fun chatToolCall(toolName: String, text: String?): ObjectNode {
        val toolCall = JsonHelper.MAPPER.createObjectNode()
        toolCall.put("id", newToolCallId())
        toolCall.put("type", "function")
        val function = JsonHelper.MAPPER.createObjectNode()
        function.put("name", toolName)
        function.put("arguments", toolArguments(toolName, text))
        toolCall.set<ObjectNode>("function", function)
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
        val arguments = JsonHelper.MAPPER.createObjectNode()
        arguments.put(fieldName, if (text.isNullOrBlank()) "Done." else text.trim())
        return try {
            JsonHelper.MAPPER.writeValueAsString(arguments)
        } catch (_: Exception) {
            "{\"$fieldName\":\"Done.\"}"
        }
    }

    private fun plainOutputText(completedResponse: JsonNode): String {
        val combined = StringBuilder()
        for (part in outputTextParts(completedResponse.get("output"))) {
            combined.append(part.path("text").asText(""))
        }
        return combined.toString()
    }

    private fun outputTextParts(output: JsonNode?): List<ObjectNode> {
        val parts = ArrayList<ObjectNode>()
        if (output == null || !output.isArray) {
            return parts
        }
        for (item in output) {
            if (!item.isObject || item.path("type").asText("") != "message") {
                continue
            }
            val content = item.get("content")
            if (content == null || !content.isArray) {
                continue
            }
            for (part in content) {
                if (part.isObject && part.path("type").asText("") == "output_text") {
                    parts.add(part as ObjectNode)
                }
            }
        }
        return parts
    }
}
