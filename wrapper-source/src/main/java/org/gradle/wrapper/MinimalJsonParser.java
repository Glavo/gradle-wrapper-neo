/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.wrapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class MinimalJsonParser {
    private static final int MAX_DEPTH = 64;

    private MinimalJsonParser() {
    }

    static Object parse(String input) {
        return new Parser(input).parse();
    }

    private static final class Parser {
        private final String input;
        private int offset;

        private Parser(String input) {
            this.input = input;
            if (!input.isEmpty() && input.charAt(0) == '\ufeff') {
                offset = 1;
            }
        }

        private Object parse() {
            skipWhitespace();
            Object value = parseValue(0);
            skipWhitespace();
            if (offset != input.length()) {
                throw error("Unexpected trailing content");
            }
            return value;
        }

        private Object parseValue(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("Maximum nesting depth exceeded");
            }
            if (offset >= input.length()) {
                throw error("Expected a value");
            }

            char current = input.charAt(offset);
            switch (current) {
                case '{':
                    return parseObject(depth + 1);
                case '[':
                    return parseArray(depth + 1);
                case '"':
                    return parseString();
                case 't':
                    expectLiteral("true");
                    return Boolean.TRUE;
                case 'f':
                    expectLiteral("false");
                    return Boolean.FALSE;
                case 'n':
                    expectLiteral("null");
                    return null;
                default:
                    if (current == '-' || isDigit(current)) {
                        return parseNumber();
                    }
                    throw error("Unexpected character '" + current + "'");
            }
        }

        private Map<String, Object> parseObject(int depth) {
            offset++;
            skipWhitespace();
            Map<String, Object> result = new LinkedHashMap<>();
            if (consume('}')) {
                return result;
            }

            while (true) {
                if (offset >= input.length() || input.charAt(offset) != '"') {
                    throw error("Expected an object key");
                }
                String key = parseString();
                if (result.containsKey(key)) {
                    throw error("Duplicate object key '" + key + "'");
                }
                skipWhitespace();
                expect(':');
                skipWhitespace();
                result.put(key, parseValue(depth));
                skipWhitespace();
                if (consume('}')) {
                    return result;
                }
                expect(',');
                skipWhitespace();
            }
        }

        private List<Object> parseArray(int depth) {
            offset++;
            skipWhitespace();
            List<Object> result = new ArrayList<>();
            if (consume(']')) {
                return result;
            }

            while (true) {
                result.add(parseValue(depth));
                skipWhitespace();
                if (consume(']')) {
                    return result;
                }
                expect(',');
                skipWhitespace();
            }
        }

        private String parseString() {
            offset++;
            StringBuilder result = new StringBuilder();
            while (offset < input.length()) {
                char current = input.charAt(offset++);
                if (current == '"') {
                    return result.toString();
                }
                if (current == '\\') {
                    if (offset >= input.length()) {
                        throw error("Unterminated escape sequence");
                    }
                    char escaped = input.charAt(offset++);
                    switch (escaped) {
                        case '"':
                        case '\\':
                        case '/':
                            result.append(escaped);
                            break;
                        case 'b':
                            result.append('\b');
                            break;
                        case 'f':
                            result.append('\f');
                            break;
                        case 'n':
                            result.append('\n');
                            break;
                        case 'r':
                            result.append('\r');
                            break;
                        case 't':
                            result.append('\t');
                            break;
                        case 'u':
                            result.append(parseUnicodeEscape());
                            break;
                        default:
                            throw error("Invalid escape sequence '\\" + escaped + "'");
                    }
                } else {
                    if (current < 0x20) {
                        throw error("Unescaped control character in string");
                    }
                    result.append(current);
                }
            }
            throw error("Unterminated string");
        }

        private char parseUnicodeEscape() {
            if (offset + 4 > input.length()) {
                throw error("Incomplete Unicode escape");
            }
            int value = 0;
            for (int i = 0; i < 4; i++) {
                char current = input.charAt(offset++);
                int digit = Character.digit(current, 16);
                if (digit < 0) {
                    throw error("Invalid Unicode escape");
                }
                value = (value << 4) | digit;
            }
            return (char) value;
        }

        private BigDecimal parseNumber() {
            int start = offset;
            consume('-');
            if (consume('0')) {
                if (offset < input.length() && isDigit(input.charAt(offset))) {
                    throw error("Leading zero in number");
                }
            } else {
                requireDigit();
                consumeDigits();
            }

            if (consume('.')) {
                requireDigit();
                consumeDigits();
            }

            if (consume('e') || consume('E')) {
                if (!consume('+')) {
                    consume('-');
                }
                requireDigit();
                consumeDigits();
            }

            try {
                return new BigDecimal(input.substring(start, offset));
            } catch (NumberFormatException e) {
                throw error("Invalid number");
            }
        }

        private void consumeDigits() {
            while (offset < input.length() && isDigit(input.charAt(offset))) {
                offset++;
            }
        }

        private void requireDigit() {
            if (offset >= input.length() || !isDigit(input.charAt(offset))) {
                throw error("Expected a digit");
            }
        }

        private void expectLiteral(String literal) {
            if (!input.regionMatches(offset, literal, 0, literal.length())) {
                throw error("Expected '" + literal + "'");
            }
            offset += literal.length();
        }

        private void expect(char expected) {
            if (!consume(expected)) {
                throw error("Expected '" + expected + "'");
            }
        }

        private boolean consume(char expected) {
            if (offset < input.length() && input.charAt(offset) == expected) {
                offset++;
                return true;
            }
            return false;
        }

        private void skipWhitespace() {
            while (offset < input.length()) {
                char current = input.charAt(offset);
                if (current != ' ' && current != '\t' && current != '\r' && current != '\n') {
                    return;
                }
                offset++;
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException("Invalid JSON at offset " + offset + ": " + message);
        }

        private static boolean isDigit(char value) {
            return value >= '0' && value <= '9';
        }
    }
}
