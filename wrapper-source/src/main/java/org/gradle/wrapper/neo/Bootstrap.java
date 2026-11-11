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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Packages the compiled Wrapper into a source-specific cached JAR and launches it.
 *
 * <p>The launcher supplies the project home, source file, and target JAR as absolute system
 * properties. A file lock serializes cache updates across concurrent Wrapper processes.</p>
 */
public final class Bootstrap {
    private static final String MAIN_CLASS_NAME = "GradleWrapperNeo";
    private static final String LOCK_FILE_NAME = "lock";
    private static final String BOOTSTRAP_PROPERTY = "gradle.wrapper.neo.bootstrap";
    /** System property containing the absolute project directory used as the Wrapper application home. */
    public static final String APP_HOME_PROPERTY = "org.gradle.wrapper.neo.app-home";
    /** System property containing the absolute path to {@code GradleWrapperNeo.java}. */
    public static final String SOURCE_FILE_PROPERTY = "org.gradle.wrapper.neo.source-file";
    /** System property containing the absolute path to the cached Wrapper JAR. */
    public static final String JAR_FILE_PROPERTY = "org.gradle.wrapper.neo.jar-file";
    private static final String MANIFEST_SOURCE_SHA256 = "Gradle-Wrapper-Neo-Source-SHA256";

    private Bootstrap() {
    }

    /**
     * Creates the cached Wrapper JAR on first launch and validates it on subsequent launches.
     *
     * @param args the command-line arguments forwarded to the Wrapper
     * @param mainClass the class used to locate the currently running code
     * @return {@code true} when the caller must stop because another Wrapper process was launched
     * @throws Exception if the cache cannot be validated, created, or launched
     */
    public static boolean handle(String[] args, Class<?> mainClass) throws Exception {
        Path appHome = appHome();
        Path sourceFile = sourceFile();
        Path targetJar = jarFile();
        if (!Files.isDirectory(appHome)) {
            throw new RuntimeException("Application home directory '" + appHome + "' does not exist.");
        }
        if (!Files.isRegularFile(sourceFile)) {
            throw new RuntimeException("Wrapper source file '" + sourceFile + "' does not exist.");
        }

        String sourceHash = sha256(sourceFile);
        if (!sourceHash.equals(targetJar.getParent().getFileName().toString())) {
            throw new RuntimeException("Wrapper source changed during startup. Please run the Wrapper again.");
        }

        if (Boolean.getBoolean(BOOTSTRAP_PROPERTY)) {
            Path stagingClassesDir = codeSource(mainClass);
            if (!Files.isDirectory(stagingClassesDir)) {
                throw new RuntimeException("Bootstrap classes directory '" + stagingClassesDir + "' does not exist.");
            }
            int exitCode;
            try {
                withLock(targetJar.getParent(), () -> {
                    if (!isCurrent(targetJar, sourceHash)) {
                        writeJar(sourceHash, stagingClassesDir, targetJar);
                    }
                    return null;
                });
                exitCode = launchJar(targetJar, appHome, sourceFile, args);
            } finally {
                deleteRecursively(stagingClassesDir);
                deleteIfEmpty(stagingClassesDir.getParent());
            }
            System.exit(exitCode);
            return true;
        }

        Path currentJar = codeSource(mainClass);
        if (!Files.isRegularFile(currentJar)) {
            throw new RuntimeException("Cached wrapper JAR '" + currentJar + "' does not exist.");
        }
        if (!Files.isSameFile(currentJar, targetJar)) {
            throw new RuntimeException("Running wrapper JAR '" + currentJar + "' does not match configured JAR '" + targetJar + "'.");
        }
        if (!isCurrent(currentJar, sourceHash)) {
            throw new RuntimeException("Invalid wrapper cache. Delete '" + targetJar + "' and run the Wrapper again.");
        }
        return false;
    }

    private static Path codeSource(Class<?> mainClass) {
        URI location;
        try {
            location = mainClass.getProtectionDomain().getCodeSource().getLocation().toURI();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
        if (!location.getScheme().equals("file")) {
            throw new RuntimeException(String.format("Cannot determine classpath for wrapper Jar from codebase '%s'.", location));
        }
        try {
            return Paths.get(location);
        } catch (NoClassDefFoundError e) {
            return new File(location.getPath()).toPath();
        }
    }

    private static Path requireAbsolutePath(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isEmpty()) {
            throw new RuntimeException("Missing required system property: " + name);
        }
        Path path = Paths.get(value);
        if (!path.isAbsolute()) {
            throw new RuntimeException("System property " + name + " must be an absolute path: " + value);
        }
        return path.normalize();
    }

    /**
     * Returns the project directory containing the Wrapper configuration.
     *
     * @return the normalized absolute application home
     */
    public static Path appHome() {
        return requireAbsolutePath(APP_HOME_PROPERTY);
    }

    static Path sourceFile() {
        return requireAbsolutePath(SOURCE_FILE_PROPERTY);
    }

    static Path jarFile() {
        return requireAbsolutePath(JAR_FILE_PROPERTY);
    }

    private static <T> T withLock(Path cacheDirectory, LockedAction<T> action) throws Exception {
        Files.createDirectories(cacheDirectory);
        try (
            FileChannel channel = FileChannel.open(cacheDirectory.resolve(LOCK_FILE_NAME), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock ignored = channel.lock()
        ) {
            return action.execute();
        }
    }

    private static boolean isCurrent(Path jarFile, String sourceHash) {
        try (JarFile jar = new JarFile(jarFile.toFile())) {
            Manifest manifest = jar.getManifest();
            if (manifest == null) {
                return false;
            }
            Attributes attributes = manifest.getMainAttributes();
            return sourceHash.equals(attributes.getValue(MANIFEST_SOURCE_SHA256))
                && "ALL-UNNAMED".equals(attributes.getValue("Enable-Native-Access"));
        } catch (IOException e) {
            return false;
        }
    }

    private static void writeJar(String sourceHash, Path classesDir, Path targetJar) throws Exception {
        Files.createDirectories(targetJar.getParent());
        Path tempJar = Files.createTempFile(targetJar.getParent(), targetJar.getFileName().toString() + ".", ".tmp");
        try {
            Manifest manifest = new Manifest();
            Attributes attributes = manifest.getMainAttributes();
            attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
            attributes.put(Attributes.Name.MAIN_CLASS, MAIN_CLASS_NAME);
            attributes.putValue("Enable-Native-Access", "ALL-UNNAMED");
            attributes.putValue(MANIFEST_SOURCE_SHA256, sourceHash);

            try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(tempJar), manifest)) {
                addClasses(output, classesDir);
            }

            moveReplacing(tempJar, targetJar);
        } finally {
            Files.deleteIfExists(tempJar);
        }
    }

    private static void addClasses(JarOutputStream output, Path classesDir) throws IOException {
        Files.walkFileTree(classesDir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String entryName = classesDir.relativize(file).toString().replace(File.separatorChar, '/');
                output.putNextEntry(new JarEntry(entryName));
                Files.copy(file, output);
                output.closeEntry();
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static int launchJar(
        Path targetJar,
        Path appHome,
        Path sourceFile,
        String[] args
    ) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.addAll(forwardedJvmArguments(
            ManagementFactory.getRuntimeMXBean().getInputArguments(),
            appHome,
            sourceFile,
            targetJar
        ));
        command.add("-jar");
        command.add(targetJar.toString());
        for (String arg : args) {
            command.add(arg);
        }
        return run(command);
    }

    static List<String> forwardedJvmArguments(
        List<String> inputArguments,
        Path appHome,
        Path sourceFile,
        Path jarFile
    ) {
        List<String> result = new ArrayList<>();
        for (String inputArgument : inputArguments) {
            if (!isSystemPropertyArgument(inputArgument, BOOTSTRAP_PROPERTY)
                && !inputArgument.startsWith("-Dorg.gradle.wrapper.neo.")) {
                result.add(inputArgument);
            }
        }
        result.add("-D" + APP_HOME_PROPERTY + "=" + appHome);
        result.add("-D" + SOURCE_FILE_PROPERTY + "=" + sourceFile);
        result.add("-D" + JAR_FILE_PROPERTY + "=" + jarFile);
        return result;
    }

    private static boolean isSystemPropertyArgument(String inputArgument, String property) {
        String option = "-D" + property;
        return inputArgument.equals(option) || inputArgument.startsWith(option + "=");
    }

    private static String javaExecutable() {
        Path executable = Paths.get(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
        if (Files.isRegularFile(executable)) {
            return executable.toString();
        }
        return isWindows() ? "java.exe" : "java";
    }

    private static int run(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).inheritIO().start();
        return process.waitFor();
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
            byte[] buffer = new byte[8192];
            while (input.read(buffer) >= 0) {
                // Drain the source stream into the digest.
            }
        }

        byte[] hash = digest.digest();
        StringBuilder result = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            result.append(Character.forDigit((b >>> 4) & 0xf, 16));
            result.append(Character.forDigit(b & 0xf, 16));
        }
        return result.toString();
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteIfEmpty(Path directory) throws IOException {
        if (directory == null || !Files.isDirectory(directory)) {
            return;
        }
        try {
            Files.delete(directory);
        } catch (IOException ignored) {
            // Directory is not empty or cannot be removed; leaving it behind is harmless.
        }
    }

    private static boolean isWindows() {
        return File.separatorChar == '\\';
    }

    @FunctionalInterface
    private interface LockedAction<T> {
        T execute() throws Exception;
    }
}
