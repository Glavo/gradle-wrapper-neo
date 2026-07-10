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

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinimalJsonParserTest {

    @Test
    @SuppressWarnings("unchecked")
    void parsesJsonValuesAndEscapes() {
        Object parsed = MinimalJsonParser.parse(
            "\ufeff{\"version\":1,\"enabled\":true,\"missing\":null," +
                "\"text\":\"line\\n\\u0041\",\"values\":[-12.5e2,false]}"
        );

        Map<String, Object> root = (Map<String, Object>) parsed;
        assertEquals(new BigDecimal("1"), root.get("version"));
        assertEquals(Boolean.TRUE, root.get("enabled"));
        assertTrue(root.containsKey("missing"));
        assertNull(root.get("missing"));
        assertEquals("line\nA", root.get("text"));
        assertEquals(new BigDecimal("-12.5e2"), ((List<Object>) root.get("values")).get(0));
        assertEquals(Boolean.FALSE, ((List<Object>) root.get("values")).get(1));
    }

    @Test
    void rejectsDuplicateObjectKeys() {
        IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            () -> MinimalJsonParser.parse("{\"key\":1,\"key\":2}")
        );

        assertTrue(failure.getMessage().contains("Duplicate object key 'key'"));
    }

    @Test
    void rejectsMalformedNumbersAndTrailingContent() {
        assertThrows(IllegalArgumentException.class, () -> MinimalJsonParser.parse("1e+-2"));
        assertThrows(IllegalArgumentException.class, () -> MinimalJsonParser.parse("{} true"));
        assertThrows(IllegalArgumentException.class, () -> MinimalJsonParser.parse("[1,]"));
    }
}
