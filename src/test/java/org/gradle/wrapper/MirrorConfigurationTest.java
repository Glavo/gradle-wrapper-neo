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

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MirrorConfigurationTest {
    private static final URI DISTRIBUTION_URL = URI.create("https://services.gradle.org/distributions/gradle-9.6.1-bin.zip");
    private static final URI MIRROR_URL = URI.create("https://mirror.example/gradle/gradle-9.6.1-bin.zip");
    private static final String MIRROR_0 = MirrorConfiguration.PROPERTY_PREFIX + "0.";

    @Test
    void missingConfigurationDoesNotAddMirrors() {
        MirrorConfiguration configuration = MirrorConfiguration.fromSystemProperties(Collections.emptyMap());

        assertEquals(Collections.singletonList(DISTRIBUTION_URL), configuration.resolve(DISTRIBUTION_URL, true));
    }

    @Test
    void loadsMatchingMirrorFromSystemProperties() {
        MirrorConfiguration configuration = MirrorConfiguration.fromSystemProperties(
            mirrorProperties("0", "https://mirror.example/gradle/$1")
        );

        assertEquals(Arrays.asList(MIRROR_URL, DISTRIBUTION_URL), configuration.resolve(DISTRIBUTION_URL, true));
    }

    @Test
    void requiresChecksumByDefault() {
        MirrorConfiguration configuration = MirrorConfiguration.fromSystemProperties(
            mirrorProperties("0", "https://mirror.example/gradle/$1")
        );

        assertEquals(Collections.singletonList(DISTRIBUTION_URL), configuration.resolve(DISTRIBUTION_URL, false));
    }

    @Test
    void supportsExplicitMirrorWithoutChecksumRequirement() {
        Map<String, String> properties = mirrorProperties("0", "https://mirror.example/gradle/$1");
        properties.put(MIRROR_0 + "requireChecksum", "false");

        MirrorConfiguration configuration = MirrorConfiguration.fromSystemProperties(properties);

        assertEquals(Arrays.asList(MIRROR_URL, DISTRIBUTION_URL), configuration.resolve(DISTRIBUTION_URL, false));
    }

    @Test
    void sortsSparseMirrorIndicesNumerically() {
        Map<String, String> properties = mirrorProperties("10", "https://ten.example/gradle/$1");
        properties.putAll(mirrorProperties("2", "https://two.example/gradle/$1"));

        MirrorConfiguration configuration = MirrorConfiguration.fromSystemProperties(properties);

        assertEquals(
            Arrays.asList(
                URI.create("https://two.example/gradle/gradle-9.6.1-bin.zip"),
                URI.create("https://ten.example/gradle/gradle-9.6.1-bin.zip"),
                DISTRIBUTION_URL
            ),
            configuration.resolve(DISTRIBUTION_URL, true)
        );
    }

    @Test
    void supportsDisablingAnInheritedMirror() {
        MirrorConfiguration configuration = MirrorConfiguration.fromSystemProperties(
            Collections.singletonMap(MIRROR_0 + "enabled", "false")
        );

        assertEquals(Collections.singletonList(DISTRIBUTION_URL), configuration.resolve(DISTRIBUTION_URL, true));
    }

    @Test
    void rejectsInvalidPropertyNamesAndValues() {
        assertThrows(
            IllegalArgumentException.class,
            () -> MirrorConfiguration.fromSystemProperties(
                Collections.singletonMap(MirrorConfiguration.PROPERTY_PREFIX + "01.pattern", ".*")
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> MirrorConfiguration.fromSystemProperties(
                Collections.singletonMap(MIRROR_0 + "unknown", "value")
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> MirrorConfiguration.fromSystemProperties(
                Collections.singletonMap(MIRROR_0 + "pattern", ".*")
            )
        );

        Map<String, String> properties = mirrorProperties("0", "https://mirror.example/gradle/$1");
        properties.put(MIRROR_0 + "enabled", "yes");
        assertThrows(IllegalArgumentException.class, () -> MirrorConfiguration.fromSystemProperties(properties));
    }

    @Test
    void rejectsNonHttpsResults() {
        MirrorConfiguration configuration = MirrorConfiguration.fromSystemProperties(
            mirrorProperties("0", "http://mirror.example/gradle/$1")
        );

        IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            () -> configuration.resolve(DISTRIBUTION_URL, true)
        );
        assertTrue(failure.getMessage().contains("absolute HTTPS URI"));
    }

    private static Map<String, String> mirrorProperties(String index, String replacement) {
        String prefix = MirrorConfiguration.PROPERTY_PREFIX + index + ".";
        Map<String, String> properties = new HashMap<>();
        properties.put(prefix + "pattern", "^https://services[.]gradle[.]org/distributions/(.+)$");
        properties.put(prefix + "replacement", replacement);
        return properties;
    }
}
