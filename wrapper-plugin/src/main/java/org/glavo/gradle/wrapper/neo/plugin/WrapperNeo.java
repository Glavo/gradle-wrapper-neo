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
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.util.GradleVersion;
import org.gradle.work.DisableCachingByDefault;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Generates the Gradle Wrapper Neo launchers, Java source, and standard Wrapper properties.
 *
 * <p>The generated files use the conventional project layout. Distribution and archive
 * settings are exposed as Gradle properties so builds can configure the generated Wrapper.</p>
 */
@DisableCachingByDefault(because = "Generates project wrapper files and may resolve checksums from the network.")
public abstract class WrapperNeo extends DefaultTask {
    private static final String BUNDLE_RESOURCE_PREFIX = "/org/glavo/gradle/wrapper/neo/plugin/bundle/";
    private static final int MAX_CHECKSUM_RESPONSE_SIZE = 1024;

    private final File scriptFile;
    private final File batchScriptFile;
    private final File powerShellScriptFile;
    private final File sourceFile;
    private final File propertiesFile;

    /** The Gradle distribution variant used when the distribution URL is derived from a version. */
    public enum DistributionType {
        /** The binary-only Gradle distribution. */
        BIN,
        /** The complete Gradle distribution including documentation and sources. */
        ALL
    }

    /** The base directory used for distribution and archive paths. */
    public enum PathBase {
        /** Resolve the path relative to the Gradle user home. */
        GRADLE_USER_HOME,
        /** Resolve the path relative to the project directory. */
        PROJECT
    }

    /**
     * Creates the task and configures its conventional output locations and Wrapper defaults.
     *
     * @param layout the target project layout
     */
    @Inject
    public WrapperNeo(ProjectLayout layout) {
        scriptFile = layout.getProjectDirectory().file("gradlew").getAsFile();
        batchScriptFile = layout.getProjectDirectory().file("gradlew.bat").getAsFile();
        powerShellScriptFile = layout.getProjectDirectory().file("gradlew.ps1").getAsFile();
        sourceFile = layout.getProjectDirectory().file("gradle/wrapper/GradleWrapperNeo.java").getAsFile();
        propertiesFile = layout.getProjectDirectory().file("gradle/wrapper/gradle-wrapper.properties").getAsFile();

        getOutputs().upToDateWhen(ignored -> false);
        getOutputs().file(scriptFile);
        getOutputs().file(batchScriptFile);
        getOutputs().file(powerShellScriptFile);
        getOutputs().file(sourceFile);
        getOutputs().file(propertiesFile);
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
        getDownloadDistributionSha256Sum().convention(true);
        getRemoveLegacyWrapperJar().convention(true);

        getLegacyWrapperJar().convention(layout.getProjectDirectory().file("gradle/wrapper/gradle-wrapper.jar"));
    }

    /**
     * Returns the Gradle version used to derive the distribution URL. Defaults to the current Gradle version.
     *
     * @return the configurable Gradle version
     */
    @Input
    public abstract Property<String> getGradleVersion();

    /**
     * Returns the distribution variant. Defaults to {@link DistributionType#BIN}.
     *
     * @return the configurable distribution variant
     */
    @Input
    public abstract Property<DistributionType> getDistributionType();

    /**
     * Returns the optional complete distribution URL, which overrides the version and distribution type.
     *
     * @return the configurable distribution URL
     */
    @Optional
    @Input
    public abstract Property<String> getDistributionUrl();

    /**
     * Returns the optional SHA-256 checksum written to the generated Wrapper properties.
     *
     * @return the configurable distribution checksum
     */
    @Optional
    @Input
    public abstract Property<String> getDistributionSha256Sum();

    /**
     * Returns whether a missing checksum is downloaded from the distribution URL with a {@code .sha256} suffix.
     *
     * @return the configurable checksum download flag
     */
    @Input
    public abstract Property<Boolean> getDownloadDistributionSha256Sum();

    /**
     * Returns the base directory for the installed Gradle distribution.
     *
     * @return the configurable distribution base
     */
    @Input
    public abstract Property<PathBase> getDistributionBase();

    /**
     * Returns the path below {@link #getDistributionBase()} used for installed distributions.
     *
     * @return the configurable distribution path
     */
    @Input
    public abstract Property<String> getDistributionPath();

    /**
     * Returns the base directory for downloaded distribution archives.
     *
     * @return the configurable archive base
     */
    @Input
    public abstract Property<PathBase> getArchiveBase();

    /**
     * Returns the path below {@link #getArchiveBase()} used for downloaded archives.
     *
     * @return the configurable archive path
     */
    @Input
    public abstract Property<String> getArchivePath();

    /**
     * Returns the connection and read timeout in milliseconds. Defaults to {@code 10000}.
     *
     * @return the configurable network timeout
     */
    @Input
    public abstract Property<Integer> getNetworkTimeout();

    /**
     * Returns the number of additional distribution download attempts made by the generated Wrapper.
     *
     * @return the configurable retry count
     */
    @Input
    public abstract Property<Integer> getRetries();

    /**
     * Returns the initial delay in milliseconds between distribution download attempts.
     *
     * @return the configurable retry backoff
     */
    @Input
    public abstract Property<Integer> getRetryBackOffMs();

    /**
     * Returns the value written to the generated {@code validateDistributionUrl} Wrapper property.
     *
     * @return the configurable URL validation flag
     */
    @Input
    public abstract Property<Boolean> getValidateDistributionUrl();

    /**
     * Returns whether generation removes the legacy {@code gradle-wrapper.jar}. Defaults to {@code true}.
     *
     * @return the configurable legacy JAR removal flag
     */
    @Input
    public abstract Property<Boolean> getRemoveLegacyWrapperJar();

    /**
     * Returns the legacy Wrapper JAR removed when {@link #getRemoveLegacyWrapperJar()} is enabled.
     *
     * @return the internal legacy Wrapper JAR location
     */
    @Internal
    public abstract RegularFileProperty getLegacyWrapperJar();

    /**
     * Sets {@link #getGradleVersion()} from the {@code --gradle-version} task option.
     *
     * @param value the Gradle version
     */
    @Option(option = "gradle-version", description = "The Gradle version used by the generated wrapper.")
    public void configureGradleVersion(String value) {
        getGradleVersion().set(value);
    }

    /**
     * Sets {@link #getDistributionType()} from the {@code --distribution-type} task option.
     *
     * @param value {@code bin} or {@code all}
     */
    @Option(option = "distribution-type", description = "The Gradle distribution type: bin or all.")
    public void configureDistributionType(String value) {
        getDistributionType().set(parseDistributionType(value));
    }

    /**
     * Sets {@link #getDistributionUrl()} from the {@code --gradle-distribution-url} task option.
     *
     * @param value the complete distribution URL
     */
    @Option(option = "gradle-distribution-url", description = "The complete URL of the Gradle distribution.")
    public void configureDistributionUrl(String value) {
        getDistributionUrl().set(value);
    }

    /**
     * Sets {@link #getDistributionSha256Sum()} from the checksum task option.
     *
     * @param value the 64-character hexadecimal SHA-256 checksum
     */
    @Option(option = "gradle-distribution-sha256-sum", description = "The SHA-256 checksum of the Gradle distribution.")
    public void configureDistributionSha256Sum(String value) {
        getDistributionSha256Sum().set(value);
    }

    /**
     * Sets {@link #getNetworkTimeout()} from the {@code --network-timeout} task option.
     *
     * @param value the timeout in milliseconds
     */
    @Option(option = "network-timeout", description = "The network timeout used by the generated wrapper, in milliseconds.")
    public void configureNetworkTimeout(String value) {
        try {
            getNetworkTimeout().set(Integer.parseInt(value));
        } catch (NumberFormatException e) {
            throw new GradleException("Network timeout must be an integer: " + value, e);
        }
    }

    /**
     * Writes the launchers, source file, and Wrapper properties using the configured values.
     *
     * @throws IOException if a generated file or checksum response cannot be read or written
     */
    @TaskAction
    public void generate() throws IOException {
        validateConfiguration();

        String distributionUrl = distributionUrl();
        String distributionSha256Sum = resolveDistributionSha256Sum(distributionUrl);

        writeBundledFile("gradlew", scriptFile.toPath());
        setExecutable(scriptFile.toPath());
        writeBundledFile("gradlew.bat", batchScriptFile.toPath());
        writeBundledFile("gradlew.ps1", powerShellScriptFile.toPath());
        writeBundledFile("GradleWrapperNeo.java", sourceFile.toPath());
        writeIfChanged(
            propertiesFile.toPath(),
            propertiesFileContent(distributionUrl, distributionSha256Sum).getBytes(StandardCharsets.ISO_8859_1)
        );

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

    private String propertiesFileContent(String distributionUrl, String distributionSha256Sum) {
        StringBuilder result = new StringBuilder();
        appendProperty(result, "distributionBase", getDistributionBase().get().name());
        appendProperty(result, "distributionPath", getDistributionPath().get());
        appendProperty(result, "distributionUrl", distributionUrl);
        if (distributionSha256Sum != null) {
            appendProperty(result, "distributionSha256Sum", distributionSha256Sum);
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

    private String resolveDistributionSha256Sum(String distributionUrl) {
        String configuredChecksum = getDistributionSha256Sum().getOrNull();
        if (configuredChecksum != null) {
            return configuredChecksum;
        }
        if (!getDownloadDistributionSha256Sum().get()) {
            return null;
        }

        String checksumUrl = checksumUrl(distributionUrl);
        try {
            URLConnection connection = new URL(checksumUrl).openConnection();
            int timeout = getNetworkTimeout().get();
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);

            byte[] content;
            try (InputStream input = connection.getInputStream()) {
                content = readLimitedBytes(input, MAX_CHECKSUM_RESPONSE_SIZE);
            }

            String checksum = new String(content, StandardCharsets.US_ASCII).trim();
            if (!isSha256(checksum)) {
                throw new GradleException(
                    "The SHA-256 checksum downloaded from " + checksumUrl +
                        " must contain exactly 64 hexadecimal characters."
                );
            }
            return checksum.toLowerCase(Locale.ROOT);
        } catch (IOException e) {
            throw new GradleException("Could not download the Gradle distribution SHA-256 checksum from " + checksumUrl + ".", e);
        }
    }

    private static String checksumUrl(String distributionUrl) {
        int suffixIndex = distributionUrl.length();
        int queryIndex = distributionUrl.indexOf('?');
        if (queryIndex >= 0) {
            suffixIndex = queryIndex;
        }
        int fragmentIndex = distributionUrl.indexOf('#');
        if (fragmentIndex >= 0 && fragmentIndex < suffixIndex) {
            suffixIndex = fragmentIndex;
        }
        return distributionUrl.substring(0, suffixIndex) + ".sha256" + distributionUrl.substring(suffixIndex);
    }

    private static byte[] readLimitedBytes(InputStream input, int maximumSize) throws IOException {
        byte[] buffer = new byte[256];
        int length;
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        while ((length = input.read(buffer)) >= 0) {
            if (output.size() + length > maximumSize) {
                throw new GradleException("The SHA-256 checksum response exceeds " + maximumSize + " bytes.");
            }
            output.write(buffer, 0, length);
        }
        return output.toByteArray();
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

    private static void writeBundledFile(String name, Path target) throws IOException {
        byte[] content;
        try (InputStream input = WrapperNeo.class.getResourceAsStream(BUNDLE_RESOURCE_PREFIX + name)) {
            if (input == null) {
                throw new GradleException("Missing bundled wrapper resource: " + name);
            }
            content = readAllBytes(input);
        }
        writeIfChanged(target, content);
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

}
