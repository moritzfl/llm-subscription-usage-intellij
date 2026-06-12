package com.aiproxyoauth.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.aiproxyoauth.server.JsonHelper.MAPPER;

final class JunieCommandProtocolCompat {
    private static final Pattern COMMAND_PATTERN = Pattern.compile(
            "<COMMAND(\\d{0,2})>.*?</COMMAND\\1>",
            Pattern.DOTALL
    );
    private static final Pattern XML_TAG_PATTERN = Pattern.compile("</?[A-Z_]+>");
    private static final Pattern UPDATE_MARKUP_PATTERN = Pattern.compile(
            "</?(UPDATE|PREVIOUS_STEP|PLAN|NEXT_STEP)>",
            Pattern.CASE_INSENSITIVE
    );

    private JunieCommandProtocolCompat() {}

    /**
     * Detects Junie's matterhorn command protocol via the "You are Junie" system
     * prompt. The {@code stop: ["</COMMAND>"]} parameter is deliberately NOT a
     * signal: Junie 1892.26 attaches it to every LLM call, including plain-text
     * utility prompts (task-name summarizer, step summarizer, allowlist
     * generator) whose output it displays verbatim — wrapping those in
     * THOUGHT/COMMAND leaks raw tags into the UI. Scanning the whole body would
     * likewise misfire on any conversation that merely mentions Junie.
     */
    static boolean isJunieRequest(JsonNode body) {
        if (body == null) {
            return false;
        }
        String systemText = systemText(body).toLowerCase(Locale.ROOT);
        return systemText.contains("you are junie");
    }

    /** Collects instructions plus all system/developer message text from chat and responses bodies. */
    private static String systemText(JsonNode body) {
        StringBuilder text = new StringBuilder();
        JsonNode instructions = body.get("instructions");
        if (instructions != null && instructions.isTextual()) {
            text.append(instructions.asText()).append('\n');
        }
        appendSystemMessages(text, body.get("messages"));
        appendSystemMessages(text, body.get("input"));
        return text.toString();
    }

    private static void appendSystemMessages(StringBuilder target, JsonNode messages) {
        if (messages == null || !messages.isArray()) {
            return;
        }
        for (JsonNode message : messages) {
            if (!message.isObject()) {
                continue;
            }
            String role = message.path("role").asText("");
            if (!"system".equals(role) && !"developer".equals(role)) {
                continue;
            }
            target.append(messageText(message.get("content"))).append('\n');
        }
    }

    static String messageText(JsonNode content) {
        if (content == null) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (!content.isArray()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode part : content) {
            String type = part.path("type").asText("");
            if ("text".equals(type) || "input_text".equals(type) || "output_text".equals(type)) {
                text.append(part.path("text").asText(""));
            }
        }
        return text.toString();
    }

    static JsonNode wrapCompletedResponse(JsonNode completedResponse) {
        if (completedResponse == null || !completedResponse.isObject()) {
            return completedResponse;
        }
        ObjectNode copy = completedResponse.deepCopy();
        List<ObjectNode> textParts = outputTextParts(copy.get("output"));
        if (textParts.isEmpty()) {
            return copy;
        }

        StringBuilder combined = new StringBuilder();
        for (ObjectNode part : textParts) {
            combined.append(part.path("text").asText(""));
        }
        String text = combined.toString();
        if (hasCommand(text)) {
            return copy;
        }

        textParts.getFirst().put("text", wrapPlainText(text));
        textParts.stream().skip(1).forEach(part -> part.put("text", ""));
        return copy;
    }

    static boolean hasFunctionCallOutput(JsonNode completedResponse) {
        JsonNode output = completedResponse != null ? completedResponse.get("output") : null;
        if (output == null || !output.isArray()) {
            return false;
        }
        for (JsonNode item : output) {
            if ("function_call".equals(item.path("type").asText(""))) {
                return true;
            }
        }
        return false;
    }

    static JsonNode toToolResponse(JsonNode completedResponse, String toolName) {
        if (completedResponse == null || !completedResponse.isObject()) {
            return completedResponse;
        }
        ObjectNode copy = completedResponse.deepCopy();
        String text = plainOutputText(copy);
        ArrayNode output = MAPPER.createArrayNode();
        output.add(responsesToolCall(toolName, text));
        copy.set("output", output);
        return copy;
    }

    static String fallbackToolName(JsonNode body) {
        String declared = declaredFallbackToolName(body);
        if (declared != null) {
            return declared;
        }
        if (isJunieRequest(body) && !hasToolDefinitions(body)) {
            return "submit";
        }
        return null;
    }

    /** Returns submit/answer only when the request actually declares that tool. */
    static String declaredFallbackToolName(JsonNode body) {
        if (hasTool(body, "submit")) {
            return "submit";
        }
        if (hasTool(body, "answer")) {
            return "answer";
        }
        return null;
    }

    static boolean hasToolDefinitions(JsonNode body) {
        if (body == null) {
            return false;
        }
        return !hasNoToolDefinitions(body.get("tools")) || !hasNoToolDefinitions(body.get("functions"));
    }

    private static boolean hasNoToolDefinitions(JsonNode tools) {
        return tools == null || !tools.isArray() || tools.isEmpty();
    }

    private static boolean hasTool(JsonNode body, String toolName) {
        if (body == null) {
            return false;
        }
        return hasToolDefinition(body.get("tools"), toolName) || hasToolDefinition(body.get("functions"), toolName);
    }

    private static boolean hasToolDefinition(JsonNode tools, String toolName) {
        if (tools == null || !tools.isArray()) {
            return false;
        }
        for (JsonNode tool : tools) {
            String name = tool.path("name").asText(tool.path("function").path("name").asText(""));
            if (toolName.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Junie's native tool-call protocol displays assistant text verbatim as the step
     * thought; unlike the <THOUGHT>/<COMMAND> text protocol it never routes that text
     * through its update_status tag parser, so <UPDATE>/<PLAN> plan markup would reach
     * the UI raw. Reformat the markup into plain readable text. Every section's content
     * is kept: Junie echoes this text back as assistant history, and the model relies
     * on the previous plan to track progress across steps.
     */
    static String formatUpdateMarkup(String text) {
        if (text == null || !UPDATE_MARKUP_PATTERN.matcher(text).find()) {
            return text;
        }
        String formatted = text;
        formatted = replaceTagSection(formatted, "PREVIOUS_STEP", "");
        formatted = replaceTagSection(formatted, "PLAN", "Plan:\n");
        formatted = replaceTagSection(formatted, "NEXT_STEP", "Next: ");
        formatted = XML_TAG_PATTERN.matcher(formatted).replaceAll("");
        formatted = formatted.replaceAll("\\n{3,}", "\n\n").trim();
        return formatted.isBlank() ? text : formatted;
    }

    /** Applies {@link #formatUpdateMarkup} to all output_text parts of a completed Responses payload. */
    static JsonNode formatUpdateMarkupInResponse(JsonNode completedResponse) {
        if (completedResponse == null || !completedResponse.isObject()) {
            return completedResponse;
        }
        ObjectNode copy = completedResponse.deepCopy();
        boolean changed = false;
        for (ObjectNode part : outputTextParts(copy.get("output"))) {
            String text = part.path("text").asText("");
            String formatted = formatUpdateMarkup(text);
            if (!text.equals(formatted)) {
                part.put("text", formatted);
                changed = true;
            }
        }
        return changed ? copy : completedResponse;
    }

    private static String replaceTagSection(String text, String tagName, String prefix) {
        Pattern pattern = Pattern.compile(
                "<" + tagName + ">\\s*(.*?)\\s*</" + tagName + ">",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );
        java.util.regex.Matcher matcher = pattern.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String content = matcher.group(1).trim();
            String replacement = content.isBlank() ? "" : "\n" + prefix + content + "\n";
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    static String wrapPlainText(String text) {
        if (text == null || text.isBlank()) {
            return "<THOUGHT>Ready to submit.</THOUGHT>\n<COMMAND>submit</COMMAND>";
        }
        if (hasCommand(text)) {
            return text;
        }
        String thought = displayText(text).trim().replace("</THOUGHT>", "<\\/THOUGHT>");
        if (thought.isBlank()) {
            thought = "Ready to submit.";
        }
        return "<THOUGHT>" + thought + "</THOUGHT>\n<COMMAND>submit</COMMAND>";
    }

    static String wrapStreamingText(String toolName, String text) {
        if ("submit".equals(toolName)) {
            return wrapPlainText(text);
        }
        return wrapCommandText(toolName, text);
    }

    static String textFromToolArguments(String toolName, String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return "";
        }
        try {
            JsonNode arguments = MAPPER.readTree(argumentsJson);
            if ("answer".equals(toolName)) {
                return arguments.path("full_answer").asText("");
            }
            return arguments.path("solution_summary").asText("");
        } catch (Exception ignored) {
            return argumentsJson;
        }
    }

    private static String wrapCommandText(String toolName, String text) {
        String name = toolName == null || toolName.isBlank() ? "submit" : toolName;
        String argument = text == null ? "" : displayText(text).trim().replace("</COMMAND>", "<\\/COMMAND>");
        if (argument.isBlank()) {
            return "<COMMAND>" + name + "</COMMAND>";
        }
        return "<COMMAND>" + name + " " + argument + "</COMMAND>";
    }

    private static boolean hasCommand(String text) {
        return text != null && COMMAND_PATTERN.matcher(text).find();
    }

    private static ObjectNode responsesToolCall(String toolName, String text) {
        String callId = newToolCallId();
        ObjectNode item = MAPPER.createObjectNode();
        item.put("type", "function_call");
        item.put("id", "fc_" + callId.substring("call_".length()));
        item.put("call_id", callId);
        item.put("name", toolName);
        item.put("arguments", toolArguments(toolName, text));
        item.put("status", "completed");
        return item;
    }

    static String newToolCallId() {
        return "call_quota_submit_" + UUID.randomUUID().toString().replace("-", "");
    }

    /** Builds a chat-completions-format tool call carrying plain model text as tool arguments. */
    static ObjectNode chatToolCall(String toolName, String text) {
        ObjectNode toolCall = MAPPER.createObjectNode();
        toolCall.put("id", newToolCallId());
        toolCall.put("type", "function");
        ObjectNode function = MAPPER.createObjectNode();
        function.put("name", toolName);
        function.put("arguments", toolArguments(toolName, text));
        toolCall.set("function", function);
        return toolCall;
    }

    private static String submitArguments(String text) {
        return arguments("solution_summary", text);
    }

    private static String toolArguments(String toolName, String text) {
        String displayText = displayText(text);
        if ("answer".equals(toolName)) {
            return arguments("full_answer", displayText);
        }
        return submitArguments(displayText);
    }

    private static String displayText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        addIfNotBlank(parts, tagText(text, "PREVIOUS_STEP"));
        addIfNotBlank(parts, tagText(text, "NEXT_STEP"));
        if (parts.isEmpty()) {
            addIfNotBlank(parts, tagText(text, "THOUGHT"));
        }
        if (!parts.isEmpty()) {
            return String.join("\n\n", parts);
        }
        String withoutCommand = COMMAND_PATTERN.matcher(text).replaceAll("");
        return XML_TAG_PATTERN.matcher(withoutCommand).replaceAll("").trim();
    }

    private static void addIfNotBlank(List<String> parts, String text) {
        if (!text.isBlank() && !parts.contains(text)) {
            parts.add(text);
        }
    }

    private static String tagText(String text, String tagName) {
        Pattern pattern = Pattern.compile(
                "<" + tagName + ">(.*?)</" + tagName + ">",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );
        java.util.regex.Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return "";
        }
        return XML_TAG_PATTERN.matcher(matcher.group(1)).replaceAll("").trim();
    }

    private static String arguments(String fieldName, String text) {
        ObjectNode arguments = MAPPER.createObjectNode();
        arguments.put(fieldName, text == null || text.isBlank() ? "Done." : text.trim());
        try {
            return MAPPER.writeValueAsString(arguments);
        } catch (Exception e) {
            return "{\"" + fieldName + "\":\"Done.\"}";
        }
    }

    private static String plainOutputText(JsonNode completedResponse) {
        StringBuilder combined = new StringBuilder();
        for (ObjectNode part : outputTextParts(completedResponse.get("output"))) {
            combined.append(part.path("text").asText(""));
        }
        return combined.toString();
    }

    private static List<ObjectNode> outputTextParts(JsonNode output) {
        List<ObjectNode> parts = new ArrayList<>();
        if (output == null || !output.isArray()) {
            return parts;
        }
        for (JsonNode item : output) {
            if (!item.isObject() || !"message".equals(item.path("type").asText(""))) {
                continue;
            }
            JsonNode content = item.get("content");
            if (content == null || !content.isArray()) {
                continue;
            }
            for (JsonNode part : content) {
                if (part.isObject() && "output_text".equals(part.path("type").asText(""))) {
                    parts.add((ObjectNode) part);
                }
            }
        }
        return parts;
    }

}
