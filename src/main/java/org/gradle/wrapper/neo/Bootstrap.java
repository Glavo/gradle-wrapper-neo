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

import javax.lang.model.SourceVersion;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

public final class Bootstrap {
    private static final String MAIN_CLASS_NAME = "GradleWrapperNeo";
    private static final String JAR_FILE_NAME = "gradle-wrapper-neo.jar";
    private static final String WORK_DIR_NAME = ".gradle-wrapper-neo";
    private static final String CLASSES_DIR_NAME = "classes";
    private static final String LOCK_FILE_NAME = "lock";
    private static final String SOURCE_FILE_NAME = MAIN_CLASS_NAME + ".java";
    private static final String BOOTSTRAP_PROPERTY = "gradle.wrapper.neo.bootstrap";
    private static final String SOURCE_PROPERTY = "gradle.wrapper.neo.source";
    private static final String JAR_PROPERTY = "gradle.wrapper.neo.jar";
    private static final String MANIFEST_SOURCE_SHA256 = "Gradle-Wrapper-Neo-Source-SHA256";

    private Bootstrap() {
    }

    public static boolean handle(String[] args, Class<?> mainClass) throws Exception {
        if (Boolean.getBoolean(BOOTSTRAP_PROPERTY)) {
            Path sourceFile = Paths.get(requireProperty(SOURCE_PROPERTY));
            Path targetJar = Paths.get(requireProperty(JAR_PROPERTY));
            Path stagingClassesDir = codeSource(mainClass);
            Path wrapperDir = targetJar.getParent();
            int exitCode;
            try {
                withLock(wrapperDir, () -> {
                    if (!isCurrent(targetJar, sourceFile)) {
                        Path classesDir = classesDir(wrapperDir);
                        recreateDirectory(classesDir);
                        copyDirectory(stagingClassesDir, classesDir);
                        writeJar(sourceFile, classesDir, targetJar);
                    }
                    return null;
                });
                exitCode = launchJar(targetJar, args);
            } finally {
                deleteRecursively(stagingClassesDir);
                deleteIfEmpty(stagingClassesDir.getParent());
            }
            System.exit(exitCode);
            return true;
        }

        Path currentJar = codeSource(mainClass);
        if (!Files.isRegularFile(currentJar) || !currentJar.getFileName().toString().equals(JAR_FILE_NAME)) {
            return false;
        }

        Path wrapperDir = currentJar.getParent();
        Path sourceFile = wrapperDir.resolve(SOURCE_FILE_NAME);
        if (!Files.isRegularFile(sourceFile) || isCurrent(currentJar, sourceFile)) {
            return false;
        }

            Path classesDir = classesDir(wrapperDir);
            Path workDir = workDir(wrapperDir);
            Files.createDirectories(workDir);
            Path nextJar = Files.createTempFile(workDir, "gradle-wrapper-neo-", ".jar");
            Path launchJar;
            try {
                launchJar = withLock(wrapperDir, () -> {
                    if (isCurrent(currentJar, sourceFile)) {
                        return currentJar;
                    }
                    compileSource(sourceFile, classesDir);
                    writeJar(sourceFile, classesDir, nextJar);
                    return installReplacement(nextJar, currentJar);
                });
            } catch (Exception e) {
                Files.deleteIfExists(nextJar);
                throw e;
            }
            int exitCode;
            try {
                exitCode = launchJar(launchJar, args);
            } finally {
                Files.deleteIfExists(nextJar);
            }
            System.exit(exitCode);
            return true;
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

    private static String requireProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isEmpty()) {
            throw new RuntimeException("Missing required system property: " + name);
        }
        return value;
    }

    private static Path classesDir(Path wrapperDir) {
        return wrapperDir.resolve(WORK_DIR_NAME).resolve(CLASSES_DIR_NAME);
    }

    private static Path workDir(Path wrapperDir) {
        return wrapperDir.resolve(WORK_DIR_NAME);
    }

    private static <T> T withLock(Path wrapperDir, LockedAction<T> action) throws Exception {
        Path workDir = workDir(wrapperDir);
        Files.createDirectories(workDir);
        try (
            FileChannel channel = FileChannel.open(workDir.resolve(LOCK_FILE_NAME), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock ignored = channel.lock()
        ) {
            return action.execute();
        }
    }

    private static boolean isCurrent(Path jarFile, Path sourceFile) throws Exception {
        try (JarFile jar = new JarFile(jarFile.toFile())) {
            Manifest manifest = jar.getManifest();
            if (manifest == null) {
                return false;
            }
            Attributes attributes = manifest.getMainAttributes();
            return sha256(sourceFile).equals(attributes.getValue(MANIFEST_SOURCE_SHA256));
        } catch (IOException e) {
            return false;
        }
    }

    private static void compileSource(Path sourceFile, Path classesDir) throws Exception {
        recreateDirectory(classesDir);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new RuntimeException("Could not compile " + sourceFile + ". A JDK with the system Java compiler is required.");
        }

        List<String> options = new ArrayList<>();
        if (SourceVersion.latestSupported().compareTo(SourceVersion.RELEASE_8) > 0) {
            options.add("--release");
            options.add("8");
        } else {
            options.add("-source");
            options.add("8");
            options.add("-target");
            options.add("8");
        }
        options.add("-encoding");
        options.add("UTF-8");
        options.add("-d");
        options.add(classesDir.toString());
        options.add(sourceFile.toString());

        int result = compiler.run(null, null, null, options.toArray(new String[0]));
        if (result != 0) {
            throw new RuntimeException("Could not compile " + sourceFile + " with the system Java compiler.");
        }
    }

    private static void recreateDirectory(Path directory) throws IOException {
        deleteRecursively(directory);
        Files.createDirectories(directory);
    }

    private static void writeJar(Path sourceFile, Path classesDir, Path targetJar) throws Exception {
        Files.createDirectories(targetJar.getParent());
        Path tempJar = Files.createTempFile(targetJar.getParent(), targetJar.getFileName().toString() + ".", ".tmp");
        try {
            Manifest manifest = new Manifest();
            Attributes attributes = manifest.getMainAttributes();
            attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
            attributes.put(Attributes.Name.MAIN_CLASS, MAIN_CLASS_NAME);
            attributes.putValue(MANIFEST_SOURCE_SHA256, sha256(sourceFile));

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

    private static void copyDirectory(Path sourceDirectory, Path targetDirectory) throws IOException {
        Files.walkFileTree(sourceDirectory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(targetDirectory.resolve(sourceDirectory.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, targetDirectory.resolve(sourceDirectory.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static Path installReplacement(Path sourceJar, Path targetJar) {
        try {
            moveReplacing(sourceJar, targetJar);
            return targetJar;
        } catch (IOException e) {
            return sourceJar;
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static int launchJar(Path jarFile, String[] args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        for (String inputArgument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (!inputArgument.startsWith("-Dgradle.wrapper.neo.")) {
                command.add(inputArgument);
            }
        }
        command.add("-jar");
        command.add(jarFile.toString());
        for (String arg : args) {
            command.add(arg);
        }
        return run(command);
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
