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

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.util.GradleVersion;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

public abstract class WrapperNeo extends DefaultTask {
    private static final String BUNDLE_RESOURCE_PREFIX = "/org/glavo/gradle/wrapper/neo/plugin/bundle/";

    private final File scriptFile;
    private final File batchScriptFile;
    private final File powerShellScriptFile;

    public enum DistributionType {
        BIN,
        ALL
    }

    public enum PathBase {
        GRADLE_USER_HOME,
        PROJECT
    }

    @Inject
    public WrapperNeo(ProjectLayout layout) {
        scriptFile = layout.getProjectDirectory().file("gradlew").getAsFile();
        batchScriptFile = layout.getProjectDirectory().file("gradlew.bat").getAsFile();
        powerShellScriptFile = layout.getProjectDirectory().file("gradlew.ps1").getAsFile();

        getOutputs().upToDateWhen(ignored -> false);
        getOutputs().file(scriptFile);
        getOutputs().file(batchScriptFile);
        getOutputs().file(powerShellScriptFile);
        getGradleVersion().convention(GradleVersion.current().getVersion());
        getDistributionType().convention(DistributionType.BIN);
        getDistributionBase().convention(PathBase.GRADLE_USER_HOME);
        getDistributionPath().convention("wrapper/dists");
        getArchiveBase().convention(PathBase.GRADLE_USER_HOME);
        getArchivePath().convention("wrapper/dists");
        getNetworkTimeout().convention(10000);
        getRetries().convention(0);
        getRetryBackOffMs().convention(500);
        getValidateDistributionUrl().convention(true);
        getRemoveLegacyWrapperJar().convention(true);

        getSourceFile().convention(layout.getProjectDirectory().file("gradle/wrapper/GradleWrapperNeo.java"));
        getPropertiesFile().convention(layout.getProjectDirectory().file("gradle/wrapper/gradle-wrapper.properties"));
        getLegacyWrapperJar().convention(layout.getProjectDirectory().file("gradle/wrapper/gradle-wrapper.jar"));
    }

    @Input
    public abstract Property<String> getGradleVersion();

    @Input
    public abstract Property<DistributionType> getDistributionType();

    @Optional
    @Input
    public abstract Property<String> getDistributionUrl();

    @Optional
    @Input
    public abstract Property<String> getDistributionSha256Sum();

    @Input
    public abstract Property<PathBase> getDistributionBase();

    @Input
    public abstract Property<String> getDistributionPath();

    @Input
    public abstract Property<PathBase> getArchiveBase();

    @Input
    public abstract Property<String> getArchivePath();

    @Input
    public abstract Property<Integer> getNetworkTimeout();

    @Input
    public abstract Property<Integer> getRetries();

    @Input
    public abstract Property<Integer> getRetryBackOffMs();

    @Input
    public abstract Property<Boolean> getValidateDistributionUrl();

    @Input
    public abstract Property<Boolean> getRemoveLegacyWrapperJar();

    @OutputFile
    public abstract RegularFileProperty getSourceFile();

    @OutputFile
    public abstract RegularFileProperty getPropertiesFile();

    @Internal
    public abstract RegularFileProperty getLegacyWrapperJar();

    @Option(option = "gradle-version", description = "The Gradle version used by the generated wrapper.")
    public void configureGradleVersion(String value) {
        getGradleVersion().set(value);
    }

    @Option(option = "distribution-type", description = "The Gradle distribution type: bin or all.")
    public void configureDistributionType(String value) {
        getDistributionType().set(parseDistributionType(value));
    }

    @Option(option = "gradle-distribution-url", description = "The complete URL of the Gradle distribution.")
    public void configureDistributionUrl(String value) {
        getDistributionUrl().set(value);
    }

    @Option(option = "gradle-distribution-sha256-sum", description = "The SHA-256 checksum of the Gradle distribution.")
    public void configureDistributionSha256Sum(String value) {
        getDistributionSha256Sum().set(value);
    }

    @Option(option = "network-timeout", description = "The network timeout used by the generated wrapper, in milliseconds.")
    public void configureNetworkTimeout(String value) {
        try {
            getNetworkTimeout().set(Integer.parseInt(value));
        } catch (NumberFormatException e) {
            throw new GradleException("Network timeout must be an integer: " + value, e);
        }
    }

    @TaskAction
    public void generate() throws IOException {
        validateConfiguration();

        writeBundledFile("gradlew", scriptFile.toPath(), LineEndings.LF);
        setExecutable(scriptFile.toPath());
        writeBundledFile("gradlew.bat", batchScriptFile.toPath(), LineEndings.CRLF);
        writeBundledFile("gradlew.ps1", powerShellScriptFile.toPath(), LineEndings.LF);
        writeBundledFile("GradleWrapperNeo.java", getSourceFile().get().getAsFile().toPath(), LineEndings.LF);
        writeIfChanged(getPropertiesFile().get().getAsFile().toPath(), propertiesFileContent().getBytes(StandardCharsets.ISO_8859_1));

        if (getRemoveLegacyWrapperJar().get()) {
            Path legacyJar = getLegacyWrapperJar().get().getAsFile().toPath();
            if (Files.deleteIfExists(legacyJar)) {
                getLogger().lifecycle("Removed legacy Gradle wrapper JAR: {}", legacyJar);
            }
        }
    }

    private void validateConfiguration() {
        requireNonEmpty("gradleVersion", getGradleVersion().get());
        requireNonEmpty("distributionPath", getDistributionPath().get());
        requireNonEmpty("archivePath", getArchivePath().get());
        requireNonNegative("networkTimeout", getNetworkTimeout().get());
        requireNonNegative("retries", getRetries().get());
        requireNonNegative("retryBackOffMs", getRetryBackOffMs().get());

        String checksum = getDistributionSha256Sum().getOrNull();
        if (checksum != null && !isSha256(checksum)) {
            throw new GradleException("distributionSha256Sum must contain exactly 64 hexadecimal characters.");
        }
    }

    private String propertiesFileContent() {
        StringBuilder result = new StringBuilder();
        appendProperty(result, "distributionBase", getDistributionBase().get().name());
        appendProperty(result, "distributionPath", getDistributionPath().get());
        appendProperty(result, "distributionUrl", distributionUrl());
        if (getDistributionSha256Sum().isPresent()) {
            appendProperty(result, "distributionSha256Sum", getDistributionSha256Sum().get());
        }
        appendProperty(result, "networkTimeout", Integer.toString(getNetworkTimeout().get()));
        appendProperty(result, "retries", Integer.toString(getRetries().get()));
        appendProperty(result, "retryBackOffMs", Integer.toString(getRetryBackOffMs().get()));
        appendProperty(result, "validateDistributionUrl", Boolean.toString(getValidateDistributionUrl().get()));
        appendProperty(result, "zipStoreBase", getArchiveBase().get().name());
        appendProperty(result, "zipStorePath", getArchivePath().get());
        return result.toString();
    }

    private String distributionUrl() {
        String configuredUrl = getDistributionUrl().getOrNull();
        if (configuredUrl != null) {
            requireNonEmpty("distributionUrl", configuredUrl);
            return configuredUrl;
        }
        return "https://services.gradle.org/distributions/gradle-"
            + getGradleVersion().get()
            + "-"
            + getDistributionType().get().name().toLowerCase(Locale.ROOT)
            + ".zip";
    }

    private static void appendProperty(StringBuilder result, String key, String value) {
        result.append(key).append('=').append(escapePropertyValue(value)).append('\n');
    }

    private static String escapePropertyValue(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\':
                    result.append("\\\\");
                    break;
                case '\t':
                    result.append("\\t");
                    break;
                case '\n':
                    result.append("\\n");
                    break;
                case '\r':
                    result.append("\\r");
                    break;
                case '\f':
                    result.append("\\f");
                    break;
                case ':':
                    result.append("\\:");
                    break;
                case ' ':
                    if (index == 0) {
                        result.append('\\');
                    }
                    result.append(character);
                    break;
                default:
                    if (character < 0x20 || character > 0x7e) {
                        result.append("\\u");
                        String hex = Integer.toHexString(character).toUpperCase(Locale.ROOT);
                        for (int padding = hex.length(); padding < 4; padding++) {
                            result.append('0');
                        }
                        result.append(hex);
                    } else {
                        result.append(character);
                    }
            }
        }
        return result.toString();
    }

    private static DistributionType parseDistributionType(String value) {
        try {
            return DistributionType.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new GradleException("Unknown distribution type '" + value + "'. Expected 'bin' or 'all'.", e);
        }
    }

    private static void requireNonEmpty(String name, String value) {
        if (value.trim().isEmpty()) {
            throw new GradleException(name + " must not be empty.");
        }
    }

    private static void requireNonNegative(String name, int value) {
        if (value < 0) {
            throw new GradleException(name + " must not be negative.");
        }
    }

    private static boolean isSha256(String value) {
        if (value.length() != 64) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!((character >= '0' && character <= '9') || (character >= 'a' && character <= 'f') || (character >= 'A' && character <= 'F'))) {
                return false;
            }
        }
        return true;
    }

    private static void writeBundledFile(String name, Path target, LineEndings lineEndings) throws IOException {
        byte[] sourceBytes;
        try (InputStream input = WrapperNeo.class.getResourceAsStream(BUNDLE_RESOURCE_PREFIX + name)) {
            if (input == null) {
                throw new GradleException("Missing bundled wrapper resource: " + name);
            }
            sourceBytes = readAllBytes(input);
        }

        String content = new String(sourceBytes, StandardCharsets.UTF_8)
            .replace("\r\n", "\n")
            .replace('\r', '\n');
        if (lineEndings == LineEndings.CRLF) {
            content = content.replace("\n", "\r\n");
        }
        writeIfChanged(target, content.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] readAllBytes(InputStream input) throws IOException {
        byte[] buffer = new byte[8192];
        int length;
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        while ((length = input.read(buffer)) >= 0) {
            output.write(buffer, 0, length);
        }
        return output.toByteArray();
    }

    private static void writeIfChanged(Path target, byte[] content) throws IOException {
        if (Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), content)) {
            return;
        }
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(target, content);
    }

    private static void setExecutable(Path script) throws IOException {
        Set<PosixFilePermission> permissions = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_EXECUTE
        );
        try {
            Files.setPosixFilePermissions(script, permissions);
        } catch (UnsupportedOperationException ignored) {
            // POSIX permissions are not available on this file system.
        }
    }

    private enum LineEndings {
        LF,
        CRLF
    }
}
