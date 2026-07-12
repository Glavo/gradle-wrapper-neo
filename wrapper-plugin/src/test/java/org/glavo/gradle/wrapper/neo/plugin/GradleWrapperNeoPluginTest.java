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

import com.sun.net.httpserver.HttpServer;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GradleWrapperNeoPluginTest {
    private static final String BUNDLE_RESOURCE_PREFIX = "/org/glavo/gradle/wrapper/neo/plugin/bundle/";
    private static final String TEST_KIT_DIR_PROPERTY = "gradle.wrapper.neo.test-kit-dir";

    @TempDir
    Path projectDirectory;

    @Test
    void launcherFileLocationsAreNotConfigurable() {
        assertThrows(NoSuchMethodException.class, () -> WrapperNeo.class.getMethod("getScriptFile"));
        assertThrows(NoSuchMethodException.class, () -> WrapperNeo.class.getMethod("getBatchScriptFile"));
        assertThrows(NoSuchMethodException.class, () -> WrapperNeo.class.getMethod("getPowerShellScriptFile"));
        assertThrows(NoSuchMethodException.class, () -> WrapperNeo.class.getMethod("getSourceFile"));
        assertThrows(NoSuchMethodException.class, () -> WrapperNeo.class.getMethod("getPropertiesFile"));
    }

    @Test
    void validateDistributionUrlIsNotExposed() {
        assertThrows(NoSuchMethodException.class, () -> WrapperNeo.class.getMethod("getValidateDistributionUrl"));
    }

    @Test
    void generatesSourceBasedWrapperFilesAndReusesConfigurationCache() throws IOException {
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
                "}\n").getBytes(StandardCharsets.UTF_8)
        );

        Path legacyJar = projectDirectory.resolve("gradle/wrapper/gradle-wrapper.jar");
        Files.createDirectories(legacyJar.getParent());
        Files.write(legacyJar, new byte[]{1, 2, 3});

        String checksum = repeat('a', 64);
        GradleRunner runner = gradleRunner(
            "wrapperNeo",
            "--gradle-version", "8.14.3",
            "--distribution-type", "all",
            "--gradle-distribution-sha256-sum", checksum,
            "--network-timeout", "15000",
            "--configuration-cache"
        );
        BuildResult result = runner.build();

        assertSuccessfulTask(result);
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

        assertArrayEquals(bundledResource("gradlew"), Files.readAllBytes(script));
        assertArrayEquals(bundledResource("gradlew.bat"), Files.readAllBytes(batchScript));
        assertArrayEquals(bundledResource("gradlew.ps1"), Files.readAllBytes(powerShellScript));
        assertArrayEquals(bundledResource("GradleWrapperNeo.java"), Files.readAllBytes(sourceFile));
        assertLfOnly(script);
        assertCrlfOnly(batchScript);
        assertLfOnly(powerShellScript);
        assertLfOnly(sourceFile);

        String sourceContent = new String(Files.readAllBytes(sourceFile), StandardCharsets.UTF_8);
        assertTrue(sourceContent.contains("Gradle Wrapper Neo single-file source distribution."));
        assertTrue(sourceContent.contains("This source file is part of Gradle Wrapper Neo"));
        assertTrue(sourceContent.contains("Documentation and updates: https://github.com/Glavo/gradle-wrapper-neo"));
        assertTrue(sourceContent.contains("Place this file at gradle/wrapper/GradleWrapperNeo.java"));
        assertTrue(sourceContent.contains("public class GradleWrapperNeo"));

        Properties properties = loadGeneratedProperties();
        assertEquals("PROJECT", properties.getProperty("distributionBase"));
        assertEquals("custom/dists", properties.getProperty("distributionPath"));
        assertEquals("https://services.gradle.org/distributions/gradle-8.14.3-all.zip", properties.getProperty("distributionUrl"));
        assertEquals(checksum, properties.getProperty("distributionSha256Sum"));
        assertEquals("15000", properties.getProperty("networkTimeout"));
        assertEquals("2", properties.getProperty("retries"));
        assertEquals("750", properties.getProperty("retryBackOffMs"));
        assertNull(properties.getProperty("validateDistributionUrl"));
        assertEquals("GRADLE_USER_HOME", properties.getProperty("zipStoreBase"));
        assertEquals("wrapper/dists", properties.getProperty("zipStorePath"));

        BuildResult reusedResult = runner.build();
        assertSuccessfulTask(reusedResult);
        assertTrue(reusedResult.getOutput().contains("Reusing configuration cache."));
    }

    @Test
    void downloadsDistributionSha256SumWhenNotConfigured() throws Exception {
        String checksum = repeat('b', 64);
        try (ChecksumServer server = new ChecksumServer(checksum + "\n")) {
            writeMinimalProject(
                "wrapperNeo {\n" +
                    "    distributionUrl = '" + server.distributionUrl() + "?token=test#fragment'\n" +
                    "}\n"
            );

            BuildResult result = runWrapperNeo();

            assertSuccessfulTask(result);
            assertEquals(checksum, loadGeneratedProperties().getProperty("distributionSha256Sum"));
            assertEquals(1, server.requestCount.get());
            assertEquals("GET", server.requestMethod.get());
            assertEquals("/gradle.zip.sha256", server.requestPath.get());
            assertEquals("token=test", server.requestQuery.get());
        }
    }

    @Test
    void configuredDistributionSha256SumTakesPriority() throws Exception {
        String checksum = repeat('C', 64);
        try (ChecksumServer server = new ChecksumServer(repeat('d', 64))) {
            writeMinimalProject(
                "wrapperNeo {\n" +
                    "    distributionUrl = '" + server.distributionUrl() + "'\n" +
                    "    distributionSha256Sum = '" + checksum + "'\n" +
                    "}\n"
            );

            BuildResult result = runWrapperNeo();

            assertSuccessfulTask(result);
            assertEquals(checksum.toLowerCase(Locale.ROOT), loadGeneratedProperties().getProperty("distributionSha256Sum"));
            assertEquals(0, server.requestCount.get());
        }
    }

    @Test
    void canDisableDistributionSha256SumDownload() throws Exception {
        try (ChecksumServer server = new ChecksumServer(repeat('d', 64))) {
            writeMinimalProject(
                "wrapperNeo {\n" +
                    "    distributionUrl = '" + server.distributionUrl() + "'\n" +
                    "    downloadDistributionSha256Sum = false\n" +
                    "}\n"
            );

            BuildResult result = runWrapperNeo();

            assertSuccessfulTask(result);
            assertNull(loadGeneratedProperties().getProperty("distributionSha256Sum"));
            assertEquals(0, server.requestCount.get());
        }
    }

    @Test
    void supportsGradleEight() throws IOException {
        String checksum = repeat('e', 64);
        writeMinimalProject("");

        BuildResult result = gradleRunner(
            "wrapperNeo",
            "--gradle-distribution-sha256-sum", checksum
        ).withGradleVersion("8.0.2").build();

        assertSuccessfulTask(result);
        assertEquals(checksum, loadGeneratedProperties().getProperty("distributionSha256Sum"));
    }

    private void writeMinimalProject(String configuration) throws IOException {
        Files.write(
            projectDirectory.resolve("settings.gradle"),
            "rootProject.name = 'checksum-test'\n".getBytes(StandardCharsets.UTF_8)
        );
        Files.write(
            projectDirectory.resolve("build.gradle"),
            ("plugins { id 'org.glavo.gradle-wrapper-neo' }\n" + configuration).getBytes(StandardCharsets.UTF_8)
        );
    }

    private BuildResult runWrapperNeo() {
        return gradleRunner("wrapperNeo").build();
    }

    private GradleRunner gradleRunner(String... arguments) {
        List<String> allArguments = new ArrayList<>();
        allArguments.add("-Dorg.gradle.daemon.idletimeout=1000");
        allArguments.addAll(Arrays.asList(arguments));
        allArguments.add("--stacktrace");
        return GradleRunner.create()
            .withProjectDir(projectDirectory.toFile())
            .withTestKitDir(testKitDirectory())
            .withPluginClasspath()
            .withArguments(allArguments);
    }

    private static java.io.File testKitDirectory() {
        String configuredDirectory = System.getProperty(TEST_KIT_DIR_PROPERTY);
        if (configuredDirectory != null && !configuredDirectory.trim().isEmpty()) {
            return new java.io.File(configuredDirectory);
        }
        return new java.io.File(System.getProperty("user.home"), ".gradle/gradle-wrapper-neo/test-kit");
    }

    private Properties loadGeneratedProperties() throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(
            projectDirectory.resolve("gradle/wrapper/gradle-wrapper.properties")
        )) {
            properties.load(input);
        }
        return properties;
    }

    private static void assertSuccessfulTask(BuildResult result) {
        assertNotNull(result.task(":wrapperNeo"));
        assertEquals(TaskOutcome.SUCCESS, result.task(":wrapperNeo").getOutcome());
    }

    private static byte[] bundledResource(String name) throws IOException {
        try (InputStream input = GradleWrapperNeoPluginTest.class.getResourceAsStream(BUNDLE_RESOURCE_PREFIX + name)) {
            if (input == null) {
                throw new AssertionError("Missing bundled test resource: " + name);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) >= 0) {
                output.write(buffer, 0, length);
            }
            return output.toByteArray();
        }
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

    private static final class ChecksumServer implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "wrapper-neo-checksum-server");
            thread.setDaemon(true);
            return thread;
        });
        private final AtomicInteger requestCount = new AtomicInteger();
        private final AtomicReference<String> requestMethod = new AtomicReference<>();
        private final AtomicReference<String> requestPath = new AtomicReference<>();
        private final AtomicReference<String> requestQuery = new AtomicReference<>();

        private ChecksumServer(String responseText) throws IOException {
            byte[] response = responseText.getBytes(StandardCharsets.US_ASCII);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                requestCount.incrementAndGet();
                requestMethod.set(exchange.getRequestMethod());
                requestPath.set(exchange.getRequestURI().getRawPath());
                requestQuery.set(exchange.getRequestURI().getRawQuery());
                exchange.sendResponseHeaders(200, response.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(response);
                }
            });
            server.start();
        }

        private String distributionUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/gradle.zip";
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
