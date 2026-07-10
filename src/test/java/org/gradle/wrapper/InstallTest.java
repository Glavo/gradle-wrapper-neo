/*
 * Copyright 2007 the original author or authors.
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallTest {
    private static final String ZIP_FILE_NAME = "gradle-0.9.zip";
    private static final byte[] BAD_ARCHIVE_CONTENT = "bad archive content".getBytes(StandardCharsets.UTF_8);
    private static final URI OFFICIAL_DISTRIBUTION_URL = URI.create("https://services.gradle.org/distributions/" + ZIP_FILE_NAME);
    private static final URI MIRROR_DISTRIBUTION_URL = URI.create("https://mirror.example/gradle/" + ZIP_FILE_NAME);
    private static final String MIRROR_CONFIGURATION = "{" +
        "\"version\":1," +
        "\"mirrors\":[{" +
        "\"pattern\":\"^https://services\\\\.gradle\\\\.org/distributions/(.+)$\"," +
        "\"replacement\":\"https://mirror.example/gradle/$1\"," +
        "\"requireChecksum\":true" +
        "}]}";

    @TempDir
    Path temporaryDirectory;

    private WrapperConfiguration configuration;
    private File distributionDir;
    private File gradleHomeDir;
    private File zipDestination;

    @BeforeEach
    void setup() throws Exception {
        configuration = new WrapperConfiguration();
        configuration.setZipBase(PathAssembler.PROJECT_STRING);
        configuration.setZipPath("someZipPath");
        configuration.setDistributionBase(PathAssembler.GRADLE_USER_HOME_STRING);
        configuration.setDistributionPath("someDistPath");
        configuration.setDistribution(new URI("http://server/" + ZIP_FILE_NAME));

        distributionDir = temporaryDirectory.resolve("someDistPath").toFile();
        gradleHomeDir = new File(distributionDir, "gradle-0.9");
        zipDestination = temporaryDirectory.resolve("zips/" + ZIP_FILE_NAME).toFile();
    }

    @Test
    void installsDistributionAndReusesOnSubsequentAccess() throws Exception {
        File templateZip = temporaryDirectory.resolve("template-gradle.zip").toFile();
        createTestZip(templateZip);
        RecordingDownload download = RecordingDownload.copying(templateZip);
        Install install = newInstall(download);

        File homeDir = install.createDist(configuration);

        assertEquals(gradleHomeDir, homeDir);
        assertTrue(gradleHomeDir.isDirectory());
        assertTrue(new File(gradleHomeDir, "bin/gradle").isFile());
        assertEquals(1, download.downloadCountFor(configuration.getDistribution()));

        homeDir = install.createDist(configuration);

        assertEquals(gradleHomeDir, homeDir);
        assertEquals(1, download.downloadCountFor(configuration.getDistribution()));
    }

    @Test
    void downloadsDistributionFromConfiguredMirror() throws Exception {
        File templateZip = temporaryDirectory.resolve("template-gradle.zip").toFile();
        createTestZip(templateZip);
        configureMirror(templateZip);
        RecordingDownload download = new RecordingDownload((address, destination, attempt) -> {
            assertEquals(MIRROR_DISTRIBUTION_URL, address);
            copy(templateZip, destination);
        });

        File homeDir = newInstall(download).createDist(configuration);

        assertEquals(gradleHomeDir, homeDir);
        assertEquals(1, download.downloadCountFor(MIRROR_DISTRIBUTION_URL));
        assertEquals(0, download.downloadCountFor(OFFICIAL_DISTRIBUTION_URL));
    }

    @Test
    void fallsBackToOriginalUrlWhenMirrorDownloadFails() throws Exception {
        File templateZip = temporaryDirectory.resolve("template-gradle.zip").toFile();
        createTestZip(templateZip);
        configureMirror(templateZip);
        RecordingDownload download = new RecordingDownload((address, destination, attempt) -> {
            if (address.equals(MIRROR_DISTRIBUTION_URL)) {
                throw new IOException("mirror unavailable");
            }
            copy(templateZip, destination);
        });

        File homeDir = newInstall(download).createDist(configuration);

        assertEquals(gradleHomeDir, homeDir);
        assertEquals(1, download.downloadCountFor(MIRROR_DISTRIBUTION_URL));
        assertEquals(1, download.downloadCountFor(OFFICIAL_DISTRIBUTION_URL));
    }

    @Test
    void fallsBackToOriginalUrlWhenMirrorChecksumDoesNotMatch() throws Exception {
        File templateZip = temporaryDirectory.resolve("template-gradle.zip").toFile();
        createTestZip(templateZip);
        configureMirror(templateZip);
        RecordingDownload download = new RecordingDownload((address, destination, attempt) -> {
            if (address.equals(MIRROR_DISTRIBUTION_URL)) {
                writeBytes(destination, BAD_ARCHIVE_CONTENT);
            } else {
                copy(templateZip, destination);
            }
        });

        File homeDir = newInstall(download).createDist(configuration);

        assertEquals(gradleHomeDir, homeDir);
        assertEquals(1, download.downloadCountFor(MIRROR_DISTRIBUTION_URL));
        assertEquals(1, download.downloadCountFor(OFFICIAL_DISTRIBUTION_URL));
    }

    @Test
    void recoversFromDownloadFailure() throws Exception {
        RuntimeException failure = new RuntimeException("broken");
        File templateZip = temporaryDirectory.resolve("template-gradle.zip").toFile();
        createTestZip(templateZip);
        RecordingDownload download = new RecordingDownload((address, destination, attempt) -> {
            if (attempt == 1) {
                Files.writeString(destination.toPath(), "broken!");
                throw failure;
            }
            copy(templateZip, destination);
        });
        Install install = newInstall(download);

        RuntimeException actualFailure = assertThrows(RuntimeException.class, () -> install.createDist(configuration));
        assertSame(failure, actualFailure);

        File homeDir = install.createDist(configuration);

        assertEquals(gradleHomeDir, homeDir);
        assertEquals(2, download.downloadCountFor(configuration.getDistribution()));
    }

    @Test
    void refusesToInstallDistributionWithUnsafeZipEntryName() {
        RecordingDownload download = new RecordingDownload((address, destination, attempt) -> createEvilZip(destination));
        Install install = newInstall(download);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> install.createDist(configuration));

        assertEquals("'../../tmp/evil.sh' is not a safe archive entry or path name.", failure.getMessage());
        assertEquals(1, download.downloadCountFor(configuration.getDistribution()));
    }

    @Test
    void refusesToInstallBadArchiveAndRetries() {
        URI distributionHashUri = URI.create(configuration.getDistribution() + Install.SHA_256);
        RecordingDownload download = new RecordingDownload((address, destination, attempt) -> {
            if (address.equals(distributionHashUri)) {
                Files.writeString(destination.toPath(), sha256(BAD_ARCHIVE_CONTENT));
            } else {
                writeBytes(destination, BAD_ARCHIVE_CONTENT);
            }
        });
        Install install = newInstall(download);

        RuntimeException failure = assertThrows(RuntimeException.class, () -> install.createDist(configuration));

        assertTrue(failure.getMessage().matches("[\\S\\s]*Downloaded distribution file .* is no valid zip file\\.[\\S\\s]*"));
        assertEquals(3, download.downloadCountFor(configuration.getDistribution()));
        assertEquals(1, download.downloadCountFor(distributionHashUri));
    }

    private Install newInstall(IDownload download) {
        return new Install(new Logger(true), download, new FixedPathAssembler(distributionDir, zipDestination));
    }

    private void configureMirror(File templateZip) throws Exception {
        configuration.setDistribution(OFFICIAL_DISTRIBUTION_URL);
        configuration.setDistributionSha256Sum(Install.calculateSha256Sum(templateZip));
        configuration.setMirrorConfiguration(MirrorConfiguration.parse(MIRROR_CONFIGURATION, "CN"));
    }

    private static void createTestZip(File zipDestination) throws Exception {
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(zipDestination))) {
            zipOutputStream.putNextEntry(new ZipEntry("gradle-0.9/"));
            zipOutputStream.closeEntry();
            zipOutputStream.putNextEntry(new ZipEntry("gradle-0.9/bin/"));
            zipOutputStream.closeEntry();
            zipOutputStream.putNextEntry(new ZipEntry("gradle-0.9/bin/gradle"));
            zipOutputStream.write("something".getBytes(StandardCharsets.UTF_8));
            zipOutputStream.closeEntry();
            zipOutputStream.putNextEntry(new ZipEntry("gradle-0.9/lib/"));
            zipOutputStream.closeEntry();
            zipOutputStream.putNextEntry(new ZipEntry("gradle-0.9/lib/gradle-launcher-0.9.jar"));
            zipOutputStream.write("something".getBytes(StandardCharsets.UTF_8));
            zipOutputStream.closeEntry();
        }
    }

    private static void createEvilZip(File zipDestination) throws Exception {
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(zipDestination))) {
            zipOutputStream.putNextEntry(new ZipEntry("../../tmp/evil.sh"));
            zipOutputStream.write("evil".getBytes(StandardCharsets.UTF_8));
            zipOutputStream.closeEntry();
        }
    }

    private static void copy(File source, File destination) throws Exception {
        File parent = destination.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("Could not create " + parent);
        }
        Files.copy(source.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static void writeBytes(File destination, byte[] bytes) throws Exception {
        File parent = destination.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("Could not create " + parent);
        }
        Files.write(destination.toPath(), bytes);
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder result = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
            String hex = Integer.toHexString(value & 0xff);
            if (hex.length() == 1) {
                result.append('0');
            }
            result.append(hex);
        }
        return result.toString();
    }

    private static class FixedPathAssembler extends PathAssembler {
        private final File distributionDir;
        private final File zipFile;

        FixedPathAssembler(File distributionDir, File zipFile) {
            super(new File("userHome"), new File("projectDir"));
            this.distributionDir = distributionDir;
            this.zipFile = zipFile;
        }

        @Override
        public LocalDistribution getDistribution(WrapperConfiguration configuration) {
            return new LocalDistribution(distributionDir, zipFile);
        }
    }

    private static class RecordingDownload implements IDownload {
        private final DownloadAction action;
        private final java.util.Map<URI, Integer> downloadCounts = new java.util.HashMap<>();

        RecordingDownload(DownloadAction action) {
            this.action = action;
        }

        static RecordingDownload copying(File source) {
            return new RecordingDownload((address, destination, attempt) -> copy(source, destination));
        }

        @Override
        public void download(URI address, File destination) throws Exception {
            int attempt = downloadCounts.merge(address, 1, Integer::sum);
            action.download(address, destination, attempt);
        }

        int downloadCountFor(URI address) {
            return downloadCounts.getOrDefault(address, 0);
        }
    }

    @FunctionalInterface
    private interface DownloadAction {
        void download(URI address, File destination, int attempt) throws Exception;
    }
}
