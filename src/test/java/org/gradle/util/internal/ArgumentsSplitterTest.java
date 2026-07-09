/*
 * Copyright 2009 the original author or authors.
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
package org.gradle.util.internal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArgumentsSplitterTest {
    @Test
    void breaksUpEmptyCommandLineIntoEmptyList() {
        assertEquals(List.of(), ArgumentsSplitter.split(""));
    }

    @Test
    void breaksUpWhitespaceOnlyCommandLineIntoEmptyList() {
        assertEquals(List.of(), ArgumentsSplitter.split(" \t "));
    }

    @Test
    void breaksUpCommandLineIntoSpaceSeparatedArgument() {
        assertEquals(List.of("a"), ArgumentsSplitter.split("a"));
        assertEquals(List.of("a", "b", "c"), ArgumentsSplitter.split("a b\tc"));
    }

    @Test
    void ignoresExtraWhiteSpaceBetweenArguments() {
        assertEquals(List.of("a"), ArgumentsSplitter.split("  a \t"));
        assertEquals(List.of("a", "b"), ArgumentsSplitter.split("a  \t\t b "));
    }

    @Test
    void breaksUpCommandLineIntoDoubleQuotedArguments() {
        assertEquals(List.of("a b c"), ArgumentsSplitter.split("\"a b c\""));
        assertEquals(List.of("a", "b c d", "e"), ArgumentsSplitter.split("a \"b c d\" e"));
        assertEquals(List.of("a", "  b c d  "), ArgumentsSplitter.split("a \"  b c d  \""));
    }

    @Test
    void breaksUpCommandLineIntoSingleQuotedArguments() {
        assertEquals(List.of("a b c"), ArgumentsSplitter.split("'a b c'"));
        assertEquals(List.of("a", "b c d", "e"), ArgumentsSplitter.split("a 'b c d' e"));
        assertEquals(List.of("a", "  b c d  "), ArgumentsSplitter.split("a '  b c d  '"));
    }

    @Test
    void canHaveEmptyQuotedArgument() {
        assertEquals(List.of(""), ArgumentsSplitter.split("\"\""));
        assertEquals(List.of(""), ArgumentsSplitter.split("''"));
    }

    @Test
    void canHaveQuoteInsideQuotedArgument() {
        assertEquals(List.of("'quoted'"), ArgumentsSplitter.split("\"'quoted'\""));
        assertEquals(List.of("\"quoted\""), ArgumentsSplitter.split("'\"quoted\"'"));
    }

    @Test
    void argumentCanHaveQuotedAndUnquotedParts() {
        assertEquals(List.of("ab c"), ArgumentsSplitter.split("a\"b \"c"));
        assertEquals(List.of("ab c"), ArgumentsSplitter.split("a'b 'c"));
    }

    @Test
    void canHaveMissingEndQuote() {
        assertEquals(List.of("a b c"), ArgumentsSplitter.split("\"a b c"));
    }
}
