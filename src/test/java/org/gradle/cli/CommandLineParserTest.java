/*
 * Copyright 2010 the original author or authors.
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
package org.gradle.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandLineParserTest {
    private CommandLineParser parser;

    @BeforeEach
    void setup() {
        parser = new CommandLineParser();
    }

    @Test
    void parsesEmptyCommandLine() {
        parser.option("a");
        parser.option("long-value");

        ParsedCommandLine result = parser.parse(List.of());

        assertFalse(result.hasOption("a"));
        assertFalse(result.hasOption("long-value"));
        assertEquals(List.of(), result.getExtraArguments());
    }

    @Test
    void parsesShortOption() {
        parser.option("a");
        parser.option("b");

        ParsedCommandLine result = parser.parse(List.of("-a"));

        assertTrue(result.hasOption("a"));
        assertFalse(result.hasOption("b"));
    }

    @Test
    void canUseDoubleDashesForShortOptions() {
        parser.option("a");

        ParsedCommandLine result = parser.parse(List.of("--a"));

        assertTrue(result.hasOption("a"));
    }

    @Test
    void parsesShortOptionWithArgument() {
        parser.option("a").hasArgument();

        ParsedCommandLine result = parser.parse(List.of("-a", "arg"));

        assertTrue(result.hasOption("a"));
        assertEquals("arg", result.option("a").getValue());
        assertEquals(List.of("arg"), result.option("a").getValues());
    }

    @Test
    void parsesShortOptionWithAttachedArgument() {
        parser.option("a").hasArgument();

        ParsedCommandLine result = parser.parse(List.of("-aarg"));

        assertTrue(result.hasOption("a"));
        assertEquals("arg", result.option("a").getValue());
        assertEquals(List.of("arg"), result.option("a").getValues());
    }

    @Test
    void attachedArgumentTakesPrecedenceOverCombinedOption() {
        parser.option("a").hasArgument();
        parser.option("b");

        ParsedCommandLine result = parser.parse(List.of("-ab"));

        assertTrue(result.hasOption("a"));
        assertEquals("b", result.option("a").getValue());
        assertFalse(result.hasOption("b"));
    }

    @Test
    void parsesShortOptionWithEqualArgument() {
        parser.option("a").hasArgument();

        ParsedCommandLine result = parser.parse(List.of("-a=arg"));

        assertTrue(result.hasOption("a"));
        assertEquals("arg", result.option("a").getValue());
        assertEquals(List.of("arg"), result.option("a").getValues());
    }

    @Test
    void parsesShortOptionWithEqualsCharacterInAttachedArgument() {
        parser.option("a").hasArgument();

        ParsedCommandLine result = parser.parse(List.of("-avalue=arg"));

        assertTrue(result.hasOption("a"));
        assertEquals("value=arg", result.option("a").getValue());
        assertEquals(List.of("value=arg"), result.option("a").getValues());
    }

    @Test
    void parsesCombinedShortOptions() {
        parser.option("a");
        parser.option("b");

        ParsedCommandLine result = parser.parse(List.of("-ab"));

        assertTrue(result.hasOption("a"));
        assertTrue(result.hasOption("b"));
    }

    @Test
    void parsesLongOption() {
        parser.option("long-option-a");
        parser.option("long-option-b");

        ParsedCommandLine result = parser.parse(List.of("--long-option-a"));

        assertTrue(result.hasOption("long-option-a"));
        assertFalse(result.hasOption("long-option-b"));
    }

    @Test
    void canUseSingleDashForLongOptions() {
        parser.option("long");
        parser.option("other").hasArgument();

        ParsedCommandLine result = parser.parse(List.of("-long", "-other", "arg"));

        assertTrue(result.hasOption("long"));
        assertTrue(result.hasOption("other"));
        assertEquals("arg", result.option("other").getValue());
    }

    @Test
    void parsesLongOptionWithArgument() {
        parser.option("long-option-a").hasArgument();
        parser.option("long-option-b");

        ParsedCommandLine result = parser.parse(List.of("--long-option-a", "arg"));

        assertTrue(result.hasOption("long-option-a"));
        assertEquals("arg", result.option("long-option-a").getValue());
        assertEquals(List.of("arg"), result.option("long-option-a").getValues());
    }

    @Test
    void parsesLongOptionWithEqualsArgument() {
        parser.option("long-option-a").hasArgument();

        ParsedCommandLine result = parser.parse(List.of("--long-option-a=arg"));

        assertTrue(result.hasOption("long-option-a"));
        assertEquals("arg", result.option("long-option-a").getValue());
        assertEquals(List.of("arg"), result.option("long-option-a").getValues());
    }

    @Test
    void parseFailsForInvalidOptionNames() {
        assertThrows(IllegalArgumentException.class, () -> parser.option("weird\nmulti\nline\noption"));
        assertThrows(IllegalArgumentException.class, () -> parser.option("!@#$"));
        assertThrows(IllegalArgumentException.class, () -> parser.option("with space"));
        assertThrows(IllegalArgumentException.class, () -> parser.option("="));
        assertThrows(IllegalArgumentException.class, () -> parser.option("-"));
    }

    @Test
    void parsesOptionWithMultipleAliases() {
        parser.option("a", "b", "long-option-a");

        ParsedCommandLine longOptionResult = parser.parse(List.of("--long-option-a"));

        assertTrue(longOptionResult.hasOption("a"));
        assertTrue(longOptionResult.hasOption("b"));
        assertTrue(longOptionResult.hasOption("long-option-a"));
        assertSame(longOptionResult.option("a"), longOptionResult.option("long-option-a"));
        assertSame(longOptionResult.option("a"), longOptionResult.option("b"));

        ParsedCommandLine shortOptionResult = parser.parse(List.of("-a"));
        assertTrue(shortOptionResult.hasOption("a"));
        assertTrue(shortOptionResult.hasOption("b"));
        assertTrue(shortOptionResult.hasOption("long-option-a"));
    }

    @Test
    void parsesOptionWithMultipleArguments() {
        parser.option("a", "long").hasArguments();

        ParsedCommandLine result = parser.parse(List.of("-a", "arg1", "--long", "arg2", "-aarg3", "--long=arg4"));

        assertTrue(result.hasOption("a"));
        assertTrue(result.hasOption("long"));
        assertEquals(List.of("arg1", "arg2", "arg3", "arg4"), result.option("a").getValues());
    }

    @Test
    void parsesHelpOption() {
        parser.option("h", "?", "help");

        ParsedCommandLine result = parser.parse(List.of("-?"));

        assertTrue(result.hasOption("?"));
    }

    @Test
    void parsesCommandLineWithSubcommand() {
        parser.option("a");

        ParsedCommandLine singleArgResult = parser.parse(List.of("a"));
        assertEquals(List.of("a"), singleArgResult.getExtraArguments());
        assertFalse(singleArgResult.hasOption("a"));

        ParsedCommandLine multipleArgsResult = parser.parse(List.of("a", "b"));
        assertEquals(List.of("a", "b"), multipleArgsResult.getExtraArguments());
        assertFalse(multipleArgsResult.hasOption("a"));
    }
}
