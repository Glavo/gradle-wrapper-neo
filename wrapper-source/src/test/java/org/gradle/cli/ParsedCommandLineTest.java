/*
 * Copyright 2012 the original author or authors.
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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParsedCommandLineTest {
    @Test
    void knowsIfContainsAnOption() {
        ParsedCommandLine line = new ParsedCommandLine(List.of(cmdOption("a"), cmdOption("b"), cmdOption("c")));
        line.addOption("b", cmdOption("b"));

        assertFalse(line.hasOption("a"));
        assertTrue(line.hasOption("b"));
        assertFalse(line.hasAnyOption(List.of("a", "c")));
        assertTrue(line.hasAnyOption(List.of("b", "c")));
    }

    @Test
    void keepsTrackOfRemovedOptions() {
        ParsedCommandLine line = new ParsedCommandLine(List.of(cmdOption("a"), cmdOption("b"), cmdOption("c")));
        line.addOption("a", cmdOption("a"));
        line.addOption("b", cmdOption("b"));
        line.removeOption(cmdOption("a"));

        assertFalse(line.hasOption("a"));
        assertTrue(line.hasOption("b"));
        assertTrue(line.hadOptionRemoved("a"));
        assertFalse(line.hasAnyOption(List.of("a", "c")));
        assertTrue(line.hasAnyOption(List.of("b", "c")));
    }

    private static CommandLineOption cmdOption(String option) {
        return new CommandLineOption(List.of(option));
    }
}
