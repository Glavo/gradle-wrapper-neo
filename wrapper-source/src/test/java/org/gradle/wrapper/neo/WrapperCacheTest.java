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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WrapperCacheTest {
    @TempDir
    Path project;
    private Path sourceFile;

    @BeforeEach
    void prepareProject() throws Exception {
        for (String name : new String[] { "gradlew", "gradlew.bat", "gradlew.ps1" }) {
            try (InputStream input = getClass().getResourceAsStream("/" + name)) {
                assertNotNull(input);
                String script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                script = script.replace("        Compile-WrapperSource -SourceFile",
                    "        [Console]::WriteLine('WRAPPER_COMPILE')\n        Compile-WrapperSource -SourceFile");
                script = script.replace("    if ! \"$JAVACMD_COMPILER\" $JAVAC_TARGET_ARGS",
                    "    echo WRAPPER_COMPILE\n    if ! \"$JAVACMD_COMPILER\" $JAVAC_TARGET_ARGS");
                Files.writeString(project.resolve(name), script);
            }
        }
        Path wrapper = Files.createDirectories(project.resolve("gradle/wrapper"));
        Files.writeString(wrapper.resolve("gradle-wrapper.properties"), "");
        sourceFile = wrapper.resolve("GradleWrapperNeo.java");
        String source = Files.readString(Paths.get(System.getProperty("gradle.wrapper.neo.test-source")));
        String main = "    public static void main(String[] args) throws Exception {";
        String execute = "        prepareWrapper(args).execute();";
        assertTrue(source.contains(main));
        assertTrue(source.contains(execute));
        Files.writeString(sourceFile, source.replace(main, main + "\n        System.out.println(\"WRAPPER_JVM\");")
            .replace(execute, "        System.out.println(\"WRAPPER_READY \" + " +
                "GradleWrapperNeo.class.getProtectionDomain().getCodeSource().getLocation().toURI());"));
    }

    @Test
    void launchesThroughProjectDirectoryAlias() throws Exception {
        Path originalProject = project;
        Path originalSource = sourceFile;
        Path alias = project.resolveSibling(project.getFileName() + "-alias");
        try {
            Files.createSymbolicLink(alias, project);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "Symbolic links are unavailable: " + e);
        }
        try {
            project = alias;
            sourceFile = alias.resolve("gradle/wrapper/GradleWrapperNeo.java");
            assertLaunch(finish(start()), 2, 1);
            assertLaunch(finish(start()), 1, 0);
            assertCache();
        } finally {
            project = originalProject;
            sourceFile = originalSource;
            Files.deleteIfExists(alias);
        }
    }

    @Test
    void bypassesLegacyCacheAndReusesPersistentCache() throws Exception {
        Path legacy = project.resolve(".gradle/wrapper-neo/gradle-wrapper-neo.jar");
        Files.createDirectories(legacy.getParent());
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(legacy), manifest)) {
            // A legacy cache has no native-access attribute and must never be launched.
        }
        byte[] legacyBytes = Files.readAllBytes(legacy);
        try (JarFile lockedLegacy = new JarFile(legacy.toFile())) {
            assertNull(lockedLegacy.getManifest().getMainAttributes().getValue("Enable-Native-Access"));
            assertLaunch(finish(start()), 2, 1);
            assertCache();
            byte[] cachedBytes = Files.readAllBytes(cacheJar());
            assertLaunch(finish(start()), 1, 0);
            assertArrayEquals(cachedBytes, Files.readAllBytes(cacheJar()));
            assertArrayEquals(legacyBytes, Files.readAllBytes(legacy));
        }
    }

    @Test
    void sourceUpdateCreatesNewCacheWhilePreviousJarIsOpen() throws Exception {
        assertLaunch(finish(start()), 2, 1);
        Path previous = cacheJar();
        byte[] previousBytes = Files.readAllBytes(previous);
        try (JarFile lockedPrevious = new JarFile(previous.toFile())) {
            assertNotNull(lockedPrevious.getManifest());
            Files.writeString(sourceFile, Files.readString(sourceFile) + "\n// Updated source.\n");
            assertNotEquals(previous, cacheJar());
            assertLaunch(finish(start()), 2, 1);
            assertCache();
            assertLaunch(finish(start()), 1, 0);
            assertArrayEquals(previousBytes, Files.readAllBytes(previous));
        }
    }

    @Test
    void concurrentColdStartsSharePersistentCache() throws Exception {
        Process first = start();
        Process second = start();
        String firstOutput = finish(first);
        String secondOutput = finish(second);
        for (String output : new String[] { firstOutput, secondOutput }) {
            boolean compiled = output.contains("WRAPPER_COMPILE");
            assertLaunch(output, compiled ? 2 : 1, compiled ? 1 : 0);
        }
        assertTrue(firstOutput.contains("WRAPPER_COMPILE") || secondOutput.contains("WRAPPER_COMPILE"));
        assertCache();
        assertLaunch(finish(start()), 1, 0);
    }

    @Test
    void rebuildsEmptyCacheAndReportsNonEmptyCorruptCache() throws Exception {
        Path jar = cacheJar();
        Files.createDirectories(jar.getParent());
        Files.write(jar, new byte[0]);
        assertLaunch(finish(start()), 2, 1);
        assertCache();
        byte[] corrupt = { 1, 2, 3, 4 };
        Files.write(jar, corrupt);
        Process process = start();
        String output = output(process);
        assertNotEquals(0, process.exitValue(), output);
        assertFalse(output.contains("WRAPPER_COMPILE"), output);
        assertArrayEquals(corrupt, Files.readAllBytes(jar));
    }

    private Path cacheJar() throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(sourceFile)));
        return project.resolve(".gradle/wrapper-neo").resolve(hash).resolve("gradle-wrapper-neo.jar");
    }

    private void assertCache() throws Exception {
        Path jar = cacheJar();
        try (JarFile cache = new JarFile(jar.toFile())) {
            Attributes attributes = cache.getManifest().getMainAttributes();
            assertEquals("ALL-UNNAMED", attributes.getValue("Enable-Native-Access"));
            assertEquals(jar.getParent().getFileName().toString(), attributes.getValue("Gradle-Wrapper-Neo-Source-SHA256"));
            assertNotNull(cache.getEntry("GradleWrapperNeo.class"));
        }
    }

    private void assertLaunch(String output, int jvms, int compilations) throws Exception {
        assertEquals(jvms, output.lines().filter("WRAPPER_JVM"::equals).count(), output);
        assertEquals(compilations, output.lines().filter("WRAPPER_COMPILE"::equals).count(), output);
        assertRunningCache(output);
    }

    private void assertRunningCache(String output) throws Exception {
        String location = output.lines().filter(line -> line.startsWith("WRAPPER_READY "))
            .findFirst().orElseThrow(() -> new AssertionError(output)).substring("WRAPPER_READY ".length());
        assertTrue(Files.isSameFile(cacheJar(), Paths.get(URI.create(location))), output);
    }

    private Process start() throws Exception {
        ProcessBuilder builder = File.separatorChar == '\\'
            ? new ProcessBuilder("cmd.exe", "/d", "/c", "gradlew.bat")
            : new ProcessBuilder("sh", "gradlew");
        builder.directory(project.toFile());
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        builder.environment().remove("JAVA_OPTS");
        builder.environment().remove("GRADLE_OPTS");
        builder.redirectErrorStream(true);
        return builder.start();
    }

    private String finish(Process process) throws Exception {
        String output = output(process);
        assertEquals(0, process.exitValue(), output);
        return output;
    }

    private String output(Process process) throws Exception {
        boolean completed = process.waitFor(60, TimeUnit.SECONDS);
        if (!completed) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
        assertTrue(completed, "Wrapper launch timed out.");
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
