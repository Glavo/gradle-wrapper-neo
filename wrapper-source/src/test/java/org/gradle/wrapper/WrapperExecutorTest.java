/*
 * Copyright 2011 the original author or authors.
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
import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WrapperExecutorTest {
    private static final String SHA_256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @TempDir
    Path temporaryDirectory;

    private File projectDir;
    private File propertiesFile;
    private Properties properties;

    @BeforeEach
    void setup() throws Exception {
        projectDir = temporaryDirectory.toFile();
        propertiesFile = temporaryDirectory.resolve("gradle/wrapper/gradle-wrapper.properties").toFile();

        properties = new Properties();
        properties.setProperty("distributionUrl", "http://server/test/gradle.zip");
        properties.setProperty("distributionBase", "testDistBase");
        properties.setProperty("distributionPath", "testDistPath");
        properties.setProperty("zipStoreBase", "testZipBase");
        properties.setProperty("zipStorePath", "testZipPath");
        properties.setProperty("distributionSha256Sum", SHA_256);
        properties.setProperty("networkTimeout", "11000");
        properties.setProperty("retries", "5");
        properties.setProperty("retryBackOffMs", "1000");
        properties.setProperty("validateDistributionUrl", "true");
        storeProperties();
    }

    @Test
    void loadsWrapperMetadataFromSpecifiedPropertiesFile() throws Exception {
        WrapperExecutor wrapper = WrapperExecutor.forWrapperPropertiesFile(propertiesFile);

        assertFullConfiguration(wrapper);
    }

    @Test
    void loadsWrapperMetadataFromSpecifiedProjectDirectory() throws Exception {
        WrapperExecutor wrapper = WrapperExecutor.forProjectDirectory(projectDir);

        assertFullConfiguration(wrapper);
    }

    @Test
    void usesDefaultMetadataWhenPropertiesFileDoesNotExistInProjectDirectory() {
        WrapperExecutor wrapper = WrapperExecutor.forProjectDirectory(temporaryDirectory.resolve("unknown").toFile());

        assertNull(wrapper.getDistribution());
        WrapperConfiguration configuration = wrapper.getConfiguration();
        assertNull(configuration.getDistribution());
        assertEquals(PathAssembler.GRADLE_USER_HOME_STRING, configuration.getDistributionBase());
        assertEquals(Install.DEFAULT_DISTRIBUTION_PATH, configuration.getDistributionPath());
        assertEquals(PathAssembler.GRADLE_USER_HOME_STRING, configuration.getZipBase());
        assertEquals(Install.DEFAULT_DISTRIBUTION_PATH, configuration.getZipPath());
        assertNull(configuration.getDistributionSha256Sum());
        assertEquals(Download.DEFAULT_NETWORK_TIMEOUT_MILLISECONDS, configuration.getNetworkTimeout());
        assertEquals(Install.DEFAULT_NETWORK_RETRIES, configuration.getRetries());
        assertEquals(Install.DEFAULT_NETWORK_RETRY_BACK_OFF_MS, configuration.getRetryBackOffMs());
        assertTrue(configuration.getValidateDistributionUrl());
    }

    @Test
    void propertiesFileNeedContainOnlyTheDistributionUrl() throws Exception {
        properties = new Properties();
        properties.setProperty("distributionUrl", "http://server/test/gradle.zip");
        storeProperties();

        WrapperExecutor wrapper = WrapperExecutor.forWrapperPropertiesFile(propertiesFile);

        assertEquals(new URI("http://server/test/gradle.zip"), wrapper.getDistribution());
        WrapperConfiguration configuration = wrapper.getConfiguration();
        assertEquals(new URI("http://server/test/gradle.zip"), configuration.getDistribution());
        assertEquals(PathAssembler.GRADLE_USER_HOME_STRING, configuration.getDistributionBase());
        assertEquals(Install.DEFAULT_DISTRIBUTION_PATH, configuration.getDistributionPath());
        assertEquals(PathAssembler.GRADLE_USER_HOME_STRING, configuration.getZipBase());
        assertEquals(Install.DEFAULT_DISTRIBUTION_PATH, configuration.getZipPath());
        assertEquals(Download.DEFAULT_NETWORK_TIMEOUT_MILLISECONDS, configuration.getNetworkTimeout());
        assertEquals(Install.DEFAULT_NETWORK_RETRIES, configuration.getRetries());
        assertEquals(Install.DEFAULT_NETWORK_RETRY_BACK_OFF_MS, configuration.getRetryBackOffMs());
        assertTrue(configuration.getValidateDistributionUrl());
    }

    @Test
    void executeInstallsDistributionAndLaunchesApplication() throws Exception {
        WrapperExecutor wrapper = WrapperExecutor.forWrapperPropertiesFile(propertiesFile);
        File installDir = temporaryDirectory.resolve("install").toFile();
        RecordingInstall install = new RecordingInstall(installDir);
        RecordingBootstrapMainStarter starter = new RecordingBootstrapMainStarter();

        wrapper.execute(new String[]{"arg"}, install, starter);

        assertSame(wrapper.getConfiguration(), install.configuration);
        assertArrayEquals(new String[]{"arg"}, starter.args);
        assertEquals(installDir, starter.gradleHome);
    }

    @Test
    void failsWhenDistributionNotSpecifiedInPropertiesFile() throws Exception {
        properties = new Properties();
        storeProperties();

        RuntimeException failure = assertThrows(RuntimeException.class, () -> WrapperExecutor.forWrapperPropertiesFile(propertiesFile));

        assertEquals("Could not load wrapper properties from '" + propertiesFile + "'.", failure.getMessage());
        assertEquals("No value with key 'distributionUrl' specified in wrapper properties file '" + propertiesFile + "'.", failure.getCause().getMessage());
    }

    @Test
    void forWrapperPropertiesFileFailsWhenPropertiesFileDoesNotExist() {
        File missingPropertiesFile = temporaryDirectory.resolve("unknown.properties").toFile();

        RuntimeException failure = assertThrows(RuntimeException.class, () -> WrapperExecutor.forWrapperPropertiesFile(missingPropertiesFile));

        assertEquals("Wrapper properties file '" + missingPropertiesFile + "' does not exist.", failure.getMessage());
    }

    @Test
    void reportsErrorWhenNoneOfTheValidFormatsAreMet() throws Exception {
        properties = new Properties();
        properties.setProperty("distributionBase", "testDistBase");
        properties.setProperty("distributionPath", "testDistPath");
        properties.setProperty("zipStoreBase", "testZipBase");
        properties.setProperty("zipStorePath", "testZipPath");
        storeProperties();

        RuntimeException failure = assertThrows(RuntimeException.class, () -> WrapperExecutor.forWrapperPropertiesFile(propertiesFile));

        assertEquals("No value with key 'distributionUrl' specified in wrapper properties file '" + propertiesFile + "'.", failure.getCause().getMessage());
    }

    @Test
    void supportsRelativeDistributionUrl() throws Exception {
        properties.setProperty("distributionUrl", "some/relative/url/to/bin.zip");
        storeProperties();

        WrapperExecutor wrapper = WrapperExecutor.forWrapperPropertiesFile(propertiesFile);

        assertFalse(wrapper.getDistribution().getSchemeSpecificPart().equals("some/relative/url/to/bin.zip"));
        assertTrue(wrapper.getDistribution().getSchemeSpecificPart().endsWith("some/relative/url/to/bin.zip"));
    }

    private void assertFullConfiguration(WrapperExecutor wrapper) throws Exception {
        assertEquals(new URI("http://server/test/gradle.zip"), wrapper.getDistribution());
        WrapperConfiguration configuration = wrapper.getConfiguration();
        assertEquals(new URI("http://server/test/gradle.zip"), configuration.getDistribution());
        assertEquals("testDistBase", configuration.getDistributionBase());
        assertEquals("testDistPath", configuration.getDistributionPath());
        assertEquals("testZipBase", configuration.getZipBase());
        assertEquals("testZipPath", configuration.getZipPath());
        assertEquals(SHA_256, configuration.getDistributionSha256Sum());
        assertEquals(11000, configuration.getNetworkTimeout());
        assertEquals(5, configuration.getRetries());
        assertEquals(1000, configuration.getRetryBackOffMs());
        assertTrue(configuration.getValidateDistributionUrl());
    }

    private void storeProperties() throws Exception {
        File parent = propertiesFile.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("Could not create " + parent);
        }
        try (FileOutputStream outputStream = new FileOutputStream(propertiesFile)) {
            properties.store(outputStream, "header");
        }
    }

    private static class RecordingInstall extends Install {
        private final File installDir;
        private WrapperConfiguration configuration;

        RecordingInstall(File installDir) {
            super(new Logger(true), (address, destination) -> { }, new PathAssembler(new File("userHome"), new File("projectDir")));
            this.installDir = installDir;
        }

        @Override
        public File createDist(WrapperConfiguration configuration) {
            this.configuration = configuration;
            return installDir;
        }
    }

    private static class RecordingBootstrapMainStarter extends BootstrapMainStarter {
        private String[] args;
        private File gradleHome;

        @Override
        public void start(String[] args, File gradleHome) {
            this.args = Arrays.copyOf(args, args.length);
            this.gradleHome = gradleHome;
        }
    }
}
