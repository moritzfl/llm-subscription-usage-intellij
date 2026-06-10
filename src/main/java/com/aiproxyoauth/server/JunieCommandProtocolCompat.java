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
    private static final Pattern CREATE_FILE_NAMED_PATTERN = Pattern.compile(
            "create\\s+a\\s+file\\s+named\\s+[`\"']?([^`\"'\\s]+)[`\"']?\\s+containing\\s+exactly\\s+[`\"']?([^`\"'\\s.,;]+)[`\"']?",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern CREATE_FILE_TEXT_PATTERN = Pattern.compile(
            "(?:create|creating)\\b.{0,120}?[`\"']([^`\"'\\s]+)[`\"'].{0,240}?" +
                    "(?:(?:containing|with)\\s+exactly\\s+[`\"']?([^`\"'\\s.,;]+)[`\"']?|with\\s+exact\\s+content\\s+[`\"']([^`\"']+)[`\"'])",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern XML_TAG_PATTERN = Pattern.compile("</?[A-Z_]+>");

    private JunieCommandProtocolCompat() {}

    static boolean isJunieRequest(JsonNode body) {
        if (body == null) {
            return false;
        }
        String text = body.toString().toLowerCase(Locale.ROOT);
        return text.contains("you are junie")
                || text.contains("special interface")
                || text.contains("<command")
                || text.contains("previous_step")
                || text.contains("junie");
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
        if (hasTool(body, "submit")) {
            return "submit";
        }
        if (hasTool(body, "answer")) {
            return "answer";
        }
        if (isJunieRequest(body)
                && hasNoToolDefinitions(body.get("tools"))
                && hasNoToolDefinitions(body.get("functions"))) {
            return "submit";
        }
        return null;
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

    static String wrapStreamingText(String toolName, String text, JsonNode body) {
        return wrapStreamingText(toolName, text);
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
            return wrapStreamingSubmitText(text);
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

    private static String wrapStreamingSubmitText(String text) {
        if (hasCommand(text)) {
            return text;
        }
        CreateFileTask task = createFileTask(text);
        if (task != null) {
            return wrapCreateFileText(text, task);
        }
        return wrapPlainText(text);
    }

    private static String wrapCommandText(String toolName, String text) {
        String name = toolName == null || toolName.isBlank() ? "submit" : toolName;
        String argument = text == null ? "" : displayText(text).trim().replace("</COMMAND>", "<\\/COMMAND>");
        if (argument.isBlank()) {
            return "<COMMAND>" + name + "</COMMAND>";
        }
        return "<COMMAND>" + name + " " + argument + "</COMMAND>";
    }

    private static String wrapCreateFileText(String text, CreateFileTask task) {
        String thought = displayText(text).trim().replace("</THOUGHT>", "<\\/THOUGHT>");
        if (thought.isBlank()) {
            thought = "Create " + task.filename() + ".";
        }
        return "<THOUGHT>" + thought + "</THOUGHT>\n<COMMAND>create "
                + task.filename() + "\n" + escapeCommandBody(task.content()) + "</COMMAND>";
    }

    private static String escapeCommandBody(String text) {
        return text == null ? "" : text.replace("</COMMAND>", "<\\/COMMAND>");
    }

    private static CreateFileTask createFileTask(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher = CREATE_FILE_NAMED_PATTERN.matcher(text);
        if (!matcher.find()) {
            matcher = CREATE_FILE_TEXT_PATTERN.matcher(text);
            if (!matcher.find()) {
                return null;
            }
        }
        String filename = matcher.group(1).trim();
        String content = firstMatchedGroup(matcher, 2, 3).trim();
        if (filename.isBlank() || content.isBlank()) {
            return null;
        }
        return new CreateFileTask(filename, content);
    }

    private static String firstMatchedGroup(java.util.regex.Matcher matcher, int... groupIndexes) {
        for (int groupIndex : groupIndexes) {
            if (groupIndex <= matcher.groupCount() && matcher.group(groupIndex) != null) {
                return matcher.group(groupIndex);
            }
        }
        return "";
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

    private record CreateFileTask(String filename, String content) {
    }
}
