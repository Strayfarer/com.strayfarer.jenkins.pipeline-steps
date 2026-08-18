package com.strayfarer.jenkins.pipelinesteps;

import java.util.LinkedHashMap;
import java.util.Map;

/** Parses the de facto dotenv file format without performing variable expansion. */
final class DotEnvParser {

    private DotEnvParser() {}

    static Map<String, String> parse(String source) {
        String text = source.replace("\r\n", "\n").replace('\r', '\n');
        Map<String, String> values = new LinkedHashMap<>();
        int length = text.length();
        int offset = 0;

        while (offset < length) {
            offset = skipWhitespace(text, offset);
            if (offset >= length) {
                break;
            }
            if (text.charAt(offset) == '#') {
                offset = skipLine(text, offset);
                continue;
            }

            int lineStart = offset;
            if (text.startsWith("export", offset)
                    && offset + 6 < length
                    && isHorizontalWhitespace(text.charAt(offset + 6))) {
                offset = skipHorizontalWhitespace(text, offset + 7);
            }

            int keyStart = offset;
            while (offset < length && isKeyCharacter(text.charAt(offset))) {
                offset++;
            }
            if (offset == keyStart) {
                offset = skipLine(text, lineStart);
                continue;
            }
            String key = text.substring(keyStart, offset);
            offset = skipHorizontalWhitespace(text, offset);

            if (offset < length && text.charAt(offset) == '=') {
                offset++;
            } else if (offset + 1 < length
                    && text.charAt(offset) == ':'
                    && isHorizontalWhitespace(text.charAt(offset + 1))) {
                offset++;
            } else {
                offset = skipLine(text, lineStart);
                continue;
            }
            offset = skipHorizontalWhitespace(text, offset);

            ParsedValue parsed = parseValue(text, offset);
            values.put(key, parsed.value());
            offset = parsed.nextOffset();
        }
        return values;
    }

    private static ParsedValue parseValue(String text, int offset) {
        if (offset >= text.length() || text.charAt(offset) == '\n' || text.charAt(offset) == '#') {
            return new ParsedValue("", skipLine(text, offset));
        }

        char first = text.charAt(offset);
        if (first == '\'' || first == '"' || first == '`') {
            return parseQuotedValue(text, offset, first);
        }
        return parseUnquotedValue(text, offset);
    }

    private static ParsedValue parseQuotedValue(String text, int offset, char quote) {
        int valueStart = offset + 1;
        int cursor = valueStart;
        while (cursor < text.length()) {
            char current = text.charAt(cursor);
            if (current == '\\' && cursor + 1 < text.length()) {
                char following = text.charAt(cursor + 1);
                if (following == quote || following == '\\') {
                    cursor += 2;
                    continue;
                }
            }
            if (current == quote) {
                String value = text.substring(valueStart, cursor);
                if (quote == '"') {
                    value = value.replace("\\n", "\n").replace("\\r", "\r");
                }
                return new ParsedValue(value, skipLine(text, cursor + 1));
            }
            cursor++;
        }
        return parseUnquotedValue(text, offset);
    }

    private static ParsedValue parseUnquotedValue(String text, int offset) {
        int end = offset;
        while (end < text.length() && text.charAt(end) != '\n' && text.charAt(end) != '#') {
            end++;
        }
        int trimmedEnd = end;
        while (trimmedEnd > offset && isHorizontalWhitespace(text.charAt(trimmedEnd - 1))) {
            trimmedEnd--;
        }
        return new ParsedValue(text.substring(offset, trimmedEnd), skipLine(text, end));
    }

    private static int skipWhitespace(String text, int offset) {
        while (offset < text.length()) {
            char current = text.charAt(offset);
            if (current != ' ' && current != '\t' && current != '\n' && current != '\uFEFF') {
                break;
            }
            offset++;
        }
        return offset;
    }

    private static int skipHorizontalWhitespace(String text, int offset) {
        while (offset < text.length() && isHorizontalWhitespace(text.charAt(offset))) {
            offset++;
        }
        return offset;
    }

    private static int skipLine(String text, int offset) {
        while (offset < text.length() && text.charAt(offset) != '\n') {
            offset++;
        }
        return offset < text.length() ? offset + 1 : offset;
    }

    private static boolean isHorizontalWhitespace(char value) {
        return value == ' ' || value == '\t';
    }

    private static boolean isKeyCharacter(char value) {
        return value >= '0' && value <= '9'
                || value >= 'A' && value <= 'Z'
                || value >= 'a' && value <= 'z'
                || value == '_'
                || value == '-'
                || value == '.';
    }

    private record ParsedValue(String value, int nextOffset) {}
}
