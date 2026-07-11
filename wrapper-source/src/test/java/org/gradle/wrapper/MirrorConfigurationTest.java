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
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MirrorConfigurationTest {
    private static final URI DISTRIBUTION_URL = URI.create("https://services.gradle.org/distributions/gradle-9.6.1-bin.zip");
    private static final URI MIRROR_URL = URI.create("https://mirror.example/gradle/gradle-9.6.1-bin.zip");
    private static final String CONFIGURATION = "{" +
        "\"version\":1," +
        "\"mirrors\":[{" +
        "\"pattern\":\"^https://services\\\\.gradle\\\\.org/distributions/(.+)$\"," +
        "\"replacement\":\"https://mirror.example/gradle/$1\"" +
        "}]}";

    @TempDir
    Path temporaryDirectory;

    @Test
    void missingConfigurationDoesNotAddMirrors() {
        MirrorConfiguration configuration = MirrorConfiguration.load(temporaryDirectory.toFile());

        assertEquals(Collections.singletonList(DISTRIBUTION_URL), configuration.resolve(DISTRIBUTION_URL, true));
    }

    @Test
    void loadsMatchingMirrorFromGradleUserHome() throws Exception {
        Files.write(
            temporaryDirectory.resolve(MirrorConfiguration.FILE_NAME),
            CONFIGURATION.getBytes(StandardCharsets.UTF_8)
        );

        MirrorConfiguration configuration = MirrorConfiguration.load(temporaryDirectory.toFile());

        assertEquals(Arrays.asList(MIRROR_URL, DISTRIBUTION_URL), configuration.resolve(DISTRIBUTION_URL, true));
    }

    @Test
    void resolvesConfigurationFileLocation() {
        File gradleUserHome = temporaryDirectory.resolve("gradle-home").toFile();
        File configuredFile = temporaryDirectory.resolve("custom/config.json").toAbsolutePath().toFile();

        assertEquals(
            new File(gradleUserHome, MirrorConfiguration.FILE_NAME),
            MirrorConfiguration.configurationFile(gradleUserHome, null)
        );
        assertEquals(configuredFile, MirrorConfiguration.configurationFile(gradleUserHome, configuredFile.getPath()));
        assertThrows(
            IllegalArgumentException.class,
            () -> MirrorConfiguration.configurationFile(gradleUserHome, "")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> MirrorConfiguration.configurationFile(gradleUserHome, "relative/config.json")
        );
    }

    @Test
    void requiresChecksumByDefault() {
        MirrorConfiguration configuration = MirrorConfiguration.parse(CONFIGURATION);

        assertEquals(Collections.singletonList(DISTRIBUTION_URL), configuration.resolve(DISTRIBUTION_URL, false));
    }

    @Test
    void supportsExplicitMirrorWithoutChecksumRequirement() {
        MirrorConfiguration configuration = MirrorConfiguration.parse(
            "{\"version\":1,\"mirrors\":[{" +
                "\"pattern\":\"^https://services\\\\.gradle\\\\.org/distributions/(.+)$\"," +
                "\"replacement\":\"https://mirror.example/gradle/$1\"," +
                "\"requireChecksum\":false}]}"
        );

        assertEquals(Arrays.asList(MIRROR_URL, DISTRIBUTION_URL), configuration.resolve(DISTRIBUTION_URL, false));
    }

    @Test
    void supportsDisabledMirrors() {
        MirrorConfiguration configuration = MirrorConfiguration.parse(
            "{\"version\":1,\"mirrors\":[{\"enabled\":false}]}"
        );

        assertEquals(Collections.singletonList(DISTRIBUTION_URL), configuration.resolve(DISTRIBUTION_URL, true));
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThrows(
            IllegalArgumentException.class,
            () -> MirrorConfiguration.parse("{\"version\":1,\"unknown\":true}")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> MirrorConfiguration.parse("{\"version\":2}")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> MirrorConfiguration.parse("{\"version\":1,\"mirrors\":[{}]}")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> MirrorConfiguration.parse(
                "{\"version\":1,\"mirrors\":[{" +
                    "\"pattern\":\"[\"," +
                    "\"replacement\":\"https://mirror.example/gradle/$1\"}]}"
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> MirrorConfiguration.parse(
                "{\"version\":1,\"mirrors\":[{" +
                    "\"pattern\":\".*\"," +
                    "\"replacement\":\"https://mirror.example/gradle/$1\"," +
                    "\"requireChecksum\":\"true\"}]}"
            )
        );
    }

    @Test
    void rejectsNonHttpsResults() {
        MirrorConfiguration configuration = MirrorConfiguration.parse(
            "{\"version\":1,\"mirrors\":[{" +
                "\"pattern\":\"(.*)\",\"replacement\":\"http://mirror.example/$1\"}]}"
        );

        IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            () -> configuration.resolve(DISTRIBUTION_URL, true)
        );
        assertTrue(failure.getMessage().contains("absolute HTTPS URI"));
    }
}
