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
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
            "",
            "space value",
            "bang!value"
        );
        processBuilder.directory(temporaryDirectory.toFile());
        processBuilder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        processBuilder.environment().put("PATH", "");
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
                "",
                "space value",
                "bang!value"
            ),
            arguments
        );
    }

    @Test
    void rebuildsEmptyAndLeavesNonEmptyCorruptCachedJars() throws Exception {
        assumeTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows"));

        copyResource("/gradlew.bat", temporaryDirectory.resolve("gradlew.bat"));
        copyResource("/gradlew.ps1", temporaryDirectory.resolve("gradlew.ps1"));

        Path wrapperDirectory = temporaryDirectory.resolve("gradle/wrapper");
        Files.createDirectories(wrapperDirectory);
        Path sourceFile = wrapperDirectory.resolve("GradleWrapperNeo.java");
        Files.copy(Paths.get(System.getProperty("gradle.wrapper.neo.test-source")), sourceFile);
        Files.write(
            wrapperDirectory.resolve("gradle-wrapper.properties"),
            ("distributionUrl=" + temporaryDirectory.resolve("missing.zip").toUri() + "\n")
                .getBytes(StandardCharsets.ISO_8859_1)
        );

        String firstOutput = runBatchLauncher("--version");
        Path jarFile = temporaryDirectory.resolve(".gradle/wrapper-neo/gradle-wrapper-neo.jar");
        assertTrue(Files.isRegularFile(jarFile), firstOutput);

        Files.write(jarFile, new byte[0]);
        String recoveryOutput = runBatchLauncher("--version");
        try (JarFile recoveredJar = new JarFile(jarFile.toFile())) {
            assertNotNull(recoveredJar.getManifest(), recoveryOutput);
            assertNotNull(recoveredJar.getEntry("GradleWrapperNeo.class"), recoveryOutput);
        }

        Files.write(jarFile, new byte[] { 1, 2, 3, 4 });
        String corruptOutput = runBatchLauncher("--version");
        assertEquals(4L, Files.size(jarFile), corruptOutput);
    }

    private String runBatchLauncher(String... arguments) throws Exception {
        List<String> command = new java.util.ArrayList<>();
        command.add("cmd.exe");
        command.add("/d");
        command.add("/c");
        command.add("gradlew.bat");
        command.addAll(Arrays.asList(arguments));

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(temporaryDirectory.toFile());
        processBuilder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        boolean completed = process.waitFor(30, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
        }
        assertTrue(completed, "The batch launcher did not exit within 30 seconds.");
        return readText(process.getInputStream());
    }

    @Test
    void preservesComplexArgumentsWhenUsingWindowsPowerShell() throws Exception {
        assumeTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows"));

        prepareLauncher();

        Path windowsPowerShell = Paths.get(
            System.getenv("SystemRoot"),
            "System32",
            "WindowsPowerShell",
            "v1.0",
            "powershell.exe"
        );
        assumeTrue(Files.isRegularFile(windowsPowerShell));

        List<String> expected = Arrays.asList(
            "alpha",
            "",
            "space value",
            "quote\"inside",
            "C:\\Program Files\\Test\\",
            "backslash\\\"quote",
            "bang!value"
        );
        ProcessBuilder processBuilder = new ProcessBuilder(
            windowsPowerShell.toString(),
            "-NoLogo",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            temporaryDirectory.resolve("gradlew.ps1").toString()
        );
        processBuilder.directory(temporaryDirectory.toFile());
        processBuilder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        processBuilder.environment().put("GRADLE_WRAPPER_NEO_BATCH_LAUNCH", "1");
        processBuilder.environment().put("GRADLE_WRAPPER_NEO_BATCH_ARGUMENTS", toWindowsCommandLine(expected));

        Path argumentsFile = temporaryDirectory.resolve("arguments.txt");
        processBuilder.environment().put("GRADLE_WRAPPER_NEO_TEST_ARGUMENTS", argumentsFile.toString());
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();
        boolean completed = process.waitFor(30, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
        }
        assertTrue(completed, "The PowerShell launcher did not exit within 30 seconds.");

        String output = readText(process.getInputStream());
        assertEquals(0, process.exitValue(), output);
        assertEquals(expected, Files.readAllLines(argumentsFile, StandardCharsets.UTF_8));
    }

    @Test
    void usesPowerShellLocationAsJavaWorkingDirectory() throws Exception {
        assumeTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows"));

        prepareLauncher();

        Path windowsPowerShell = Paths.get(
            System.getenv("SystemRoot"),
            "System32",
            "WindowsPowerShell",
            "v1.0",
            "powershell.exe"
        );
        assumeTrue(Files.isRegularFile(windowsPowerShell));

        Path workingDirectory = Files.createDirectory(temporaryDirectory.resolve("nested"));
        Path userDirectoryFile = temporaryDirectory.resolve("user-dir.txt");

        ProcessBuilder processBuilder = new ProcessBuilder(
            windowsPowerShell.toString(),
            "-NoLogo",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-Command",
            "Set-Location -LiteralPath $env:GRADLE_WRAPPER_NEO_TEST_WORKING_DIRECTORY; " +
                "& $env:GRADLE_WRAPPER_NEO_TEST_SCRIPT"
        );
        processBuilder.directory(temporaryDirectory.toFile());
        processBuilder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        processBuilder.environment().put("GRADLE_WRAPPER_NEO_TEST_WORKING_DIRECTORY", workingDirectory.toString());
        processBuilder.environment().put("GRADLE_WRAPPER_NEO_TEST_SCRIPT", temporaryDirectory.resolve("gradlew.ps1").toString());
        processBuilder.environment().put("GRADLE_WRAPPER_NEO_TEST_USER_DIR", userDirectoryFile.toString());
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();
        boolean completed = process.waitFor(30, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
        }
        assertTrue(completed, "The PowerShell launcher did not exit within 30 seconds.");

        String output = readText(process.getInputStream());
        assertEquals(0, process.exitValue(), output);
        Path actualWorkingDirectory = Paths.get(
            Files.readAllLines(userDirectoryFile, StandardCharsets.UTF_8).get(0)
        );
        assertTrue(
            Files.isSameFile(workingDirectory, actualWorkingDirectory),
            () -> "Expected working directory " + workingDirectory + ", but was " + actualWorkingDirectory
        );
    }

    private void prepareLauncher() throws IOException {
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
                "        String argumentsFile = System.getenv(\"GRADLE_WRAPPER_NEO_TEST_ARGUMENTS\");\n" +
                "        if (argumentsFile != null) {\n" +
                "            Files.write(Paths.get(argumentsFile), Arrays.asList(args), StandardCharsets.UTF_8);\n" +
                "        }\n" +
                "        String userDirectoryFile = System.getenv(\"GRADLE_WRAPPER_NEO_TEST_USER_DIR\");\n" +
                "        if (userDirectoryFile != null) {\n" +
                "            Files.write(Paths.get(userDirectoryFile), " +
                "Arrays.asList(System.getProperty(\"user.dir\")), StandardCharsets.UTF_8);\n" +
                "        }\n" +
                "    }\n" +
                "}\n").getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String toWindowsCommandLine(List<String> arguments) {
        StringBuilder result = new StringBuilder();
        for (String argument : arguments) {
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append('"');
            int backslashes = 0;
            for (int index = 0; index < argument.length(); index++) {
                char character = argument.charAt(index);
                if (character == '\\') {
                    backslashes++;
                } else {
                    append(result, '\\', character == '"' ? backslashes * 2 + 1 : backslashes);
                    backslashes = 0;
                    result.append(character);
                }
            }
            append(result, '\\', backslashes * 2);
            result.append('"');
        }
        return result.toString();
    }

    private static void append(StringBuilder result, char character, int count) {
        for (int index = 0; index < count; index++) {
            result.append(character);
        }
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
