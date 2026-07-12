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
package org.gradle.wrapper.neo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsBatchLauncherTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void forwardsArgumentsToPowerShellWithoutReparsing() throws Exception {
        assumeTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows"));

        copyResource("/gradlew.bat", temporaryDirectory.resolve("gradlew.bat"));
        copyResource("/gradlew.ps1", temporaryDirectory.resolve("gradlew.ps1"));

        Path wrapperDirectory = temporaryDirectory.resolve("gradle/wrapper");
        Files.createDirectories(wrapperDirectory);
        Files.write(wrapperDirectory.resolve("gradle-wrapper.properties"), new byte[0]);
        Files.write(
            wrapperDirectory.resolve("GradleWrapperNeo.java"),
            ("import java.nio.charset.StandardCharsets;\n" +
                "import java.nio.file.Files;\n" +
                "import java.nio.file.Paths;\n" +
                "import java.util.Arrays;\n" +
                "public class GradleWrapperNeo {\n" +
                "    public static void main(String[] args) throws Exception {\n" +
                "        Files.write(Paths.get(System.getenv(\"GRADLE_WRAPPER_NEO_TEST_ARGUMENTS\")), " +
                "Arrays.asList(args), StandardCharsets.UTF_8);\n" +
                "    }\n" +
                "}\n").getBytes(StandardCharsets.UTF_8)
        );

        Path argumentsFile = temporaryDirectory.resolve("arguments.txt");
        ProcessBuilder processBuilder = new ProcessBuilder(
            "cmd.exe",
            "/d",
            "/c",
            "gradlew.bat",
            "-Pprobe=alpha.beta",
            "-Purl=https://example.com/a.b",
            "space value"
        );
        processBuilder.directory(temporaryDirectory.toFile());
        processBuilder.environment().put("GRADLE_WRAPPER_NEO_TEST_ARGUMENTS", argumentsFile.toString());
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();
        boolean completed = process.waitFor(30, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
        }
        assertTrue(completed, "The batch launcher did not exit within 30 seconds.");

        String output = readText(process.getInputStream());
        assertEquals(0, process.exitValue(), output);
        List<String> arguments = Files.readAllLines(argumentsFile, StandardCharsets.UTF_8);
        assertEquals(
            Arrays.asList(
                "-Pprobe=alpha.beta",
                "-Purl=https://example.com/a.b",
                "space value"
            ),
            arguments
        );
    }

    private static void copyResource(String name, Path target) throws IOException {
        try (InputStream input = WindowsBatchLauncherTest.class.getResourceAsStream(name)) {
            assertTrue(input != null, "Missing test resource: " + name);
            Files.copy(input, target);
        }
    }

    private static String readText(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = input.read(buffer)) >= 0) {
            output.write(buffer, 0, length);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}
