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
package org.glavo.gradle.wrapper.neo.plugin;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GradleWrapperNeoPluginTest {
    @TempDir
    Path projectDirectory;

    @Test
    void generatesSourceBasedWrapperFiles() throws IOException {
        Files.write(projectDirectory.resolve("settings.gradle"), "rootProject.name = 'test-project'\n".getBytes(StandardCharsets.UTF_8));
        Files.write(
            projectDirectory.resolve("build.gradle"),
            ("import org.glavo.gradle.wrapper.neo.plugin.WrapperNeo\n" +
                "plugins { id 'org.glavo.gradle-wrapper-neo' }\n" +
                "wrapperNeo {\n" +
                "    distributionBase = WrapperNeo.PathBase.PROJECT\n" +
                "    distributionPath = 'custom/dists'\n" +
                "    retries = 2\n" +
                "    retryBackOffMs = 750\n" +
                "    validateDistributionUrl = false\n" +
                "}\n").getBytes(StandardCharsets.UTF_8)
        );

        Path legacyJar = projectDirectory.resolve("gradle/wrapper/gradle-wrapper.jar");
        Files.createDirectories(legacyJar.getParent());
        Files.write(legacyJar, new byte[]{1, 2, 3});

        String checksum = repeat('a', 64);
        BuildResult result = GradleRunner.create()
            .withProjectDir(projectDirectory.toFile())
            .withPluginClasspath()
            .withArguments(
                "wrapperNeo",
                "--gradle-version", "8.14.3",
                "--distribution-type", "all",
                "--gradle-distribution-sha256-sum", checksum,
                "--network-timeout", "15000",
                "--stacktrace",
                "--configuration-cache"
            )
            .build();

        assertNotNull(result.task(":wrapperNeo"));
        assertEquals(TaskOutcome.SUCCESS, result.task(":wrapperNeo").getOutcome());
        assertFalse(Files.exists(legacyJar));

        Path script = projectDirectory.resolve("gradlew");
        Path batchScript = projectDirectory.resolve("gradlew.bat");
        Path powerShellScript = projectDirectory.resolve("gradlew.ps1");
        Path sourceFile = projectDirectory.resolve("gradle/wrapper/GradleWrapperNeo.java");
        Path propertiesFile = projectDirectory.resolve("gradle/wrapper/gradle-wrapper.properties");
        assertTrue(Files.isRegularFile(script));
        assertTrue(Files.isRegularFile(batchScript));
        assertTrue(Files.isRegularFile(powerShellScript));
        assertTrue(Files.isRegularFile(sourceFile));
        assertTrue(Files.isRegularFile(propertiesFile));

        assertLfOnly(script);
        assertCrlfOnly(batchScript);
        assertLfOnly(powerShellScript);
        assertLfOnly(sourceFile);
        String sourceContent = new String(Files.readAllBytes(sourceFile), StandardCharsets.UTF_8);
        assertTrue(sourceContent.contains("Gradle Wrapper Neo single-file source distribution."));
        assertTrue(sourceContent.contains("File version: 1.0-SNAPSHOT"));
        assertTrue(sourceContent.contains("Project: https://github.com/Glavo/gradle-wrapper-neo"));
        assertTrue(sourceContent.contains("Place this file at gradle/wrapper/GradleWrapperNeo.java"));
        assertTrue(sourceContent.contains("public class GradleWrapperNeo"));

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(propertiesFile)) {
            properties.load(input);
        }
        assertEquals("PROJECT", properties.getProperty("distributionBase"));
        assertEquals("custom/dists", properties.getProperty("distributionPath"));
        assertEquals("https://services.gradle.org/distributions/gradle-8.14.3-all.zip", properties.getProperty("distributionUrl"));
        assertEquals(checksum, properties.getProperty("distributionSha256Sum"));
        assertEquals("15000", properties.getProperty("networkTimeout"));
        assertEquals("2", properties.getProperty("retries"));
        assertEquals("750", properties.getProperty("retryBackOffMs"));
        assertEquals("false", properties.getProperty("validateDistributionUrl"));
        assertEquals("GRADLE_USER_HOME", properties.getProperty("zipStoreBase"));
        assertEquals("wrapper/dists", properties.getProperty("zipStorePath"));
    }

    private static void assertLfOnly(Path file) throws IOException {
        byte[] content = Files.readAllBytes(file);
        assertTrue(contains(content, (byte) '\n'));
        assertFalse(contains(content, (byte) '\r'));
    }

    private static void assertCrlfOnly(Path file) throws IOException {
        byte[] content = Files.readAllBytes(file);
        boolean foundLineFeed = false;
        for (int index = 0; index < content.length; index++) {
            if (content[index] == '\n') {
                foundLineFeed = true;
                assertTrue(index > 0 && content[index - 1] == '\r');
            }
            if (content[index] == '\r') {
                assertTrue(index + 1 < content.length && content[index + 1] == '\n');
            }
        }
        assertTrue(foundLineFeed);
    }

    private static boolean contains(byte[] content, byte value) {
        for (byte current : content) {
            if (current == value) {
                return true;
            }
        }
        return false;
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }
}
