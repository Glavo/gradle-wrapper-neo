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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParsedCommandLineOptionTest {
    private final ParsedCommandLineOption option = new ParsedCommandLineOption();

    @Test
    void reportsNoValue() {
        assertThrows(IllegalStateException.class, option::getValue);
        assertFalse(option.hasValue());
    }

    @Test
    void reportsSingleValue() {
        option.addArgument("foo");

        assertTrue(option.hasValue());
        assertEquals("foo", option.getValue());
        assertEquals(List.of("foo"), option.getValues());
    }

    @Test
    void reportsMultipleValues() {
        option.addArgument("foo");
        option.addArgument("bar");

        assertTrue(option.hasValue());
        assertEquals(List.of("foo", "bar"), option.getValues());
        assertThrows(IllegalStateException.class, option::getValue);
    }
}
