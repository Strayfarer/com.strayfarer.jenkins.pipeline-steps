package com.strayfarer.jenkins.pipelinesteps;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DotEnvParserTest {

    @Test
    void parsesTheDotenvGrammarAndNormalizesCrLf() {
        Map<String, String> values = DotEnvParser.parse("""
                \uFEFF# comment\r
                BASIC=basic\r
                EMPTY=\r
                export EXPORTED = value\r
                SINGLE='  single # value  '\r
                DOUBLE="double\\nline\\rreturn # value" # comment\r
                BACKTICK=`backtick # value`\r
                UNQUOTED = some value   # comment\r
                COLON: colon value\r
                JSON={"key": "value"}\r
                MULTILINE="first\r
                second"\r
                """);

        assertEquals(
                Map.ofEntries(
                        Map.entry("BASIC", "basic"),
                        Map.entry("EMPTY", ""),
                        Map.entry("EXPORTED", "value"),
                        Map.entry("SINGLE", "  single # value  "),
                        Map.entry("DOUBLE", "double\nline\rreturn # value"),
                        Map.entry("BACKTICK", "backtick # value"),
                        Map.entry("UNQUOTED", "some value"),
                        Map.entry("COLON", "colon value"),
                        Map.entry("JSON", "{\"key\": \"value\"}"),
                        Map.entry("MULTILINE", "first\nsecond")),
                values);
    }

    @Test
    void ignoresMalformedLinesAndUsesTheLastDuplicateValue() {
        assertEquals(Map.of("VALID", "last"), DotEnvParser.parse("""
                        not an assignment
                        =missing-key
                        # ignored
                        VALID=first
                        VALID=last
                        """));
    }

    @Test
    void acceptsEmptyFilesAndPreservesDotenvEscaping() {
        assertEquals(Map.of(), DotEnvParser.parse(""));
        assertEquals(Map.of(), DotEnvParser.parse("\r\n\t# comment\r\n"));
        assertEquals(
                Map.of(
                        "SINGLE", "one\\ntwo",
                        "DOUBLE", "one\ntwo\\\"quoted\\\"",
                        "UNTERMINATED", "\"kept"),
                DotEnvParser.parse("SINGLE='one\\ntwo'\nDOUBLE=\"one\\ntwo\\\"quoted\\\"\"\nUNTERMINATED=\"kept\n"));
    }
}
