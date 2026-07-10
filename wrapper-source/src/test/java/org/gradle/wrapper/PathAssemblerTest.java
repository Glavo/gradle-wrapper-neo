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

import java.io.File;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PathAssemblerTest {
    private static final String TEST_GRADLE_USER_HOME = "someUserHome";
    private static final String TEST_PROJECT_DIR = "someProjectDir";

    private final PathAssembler pathAssembler = new PathAssembler(new File(TEST_GRADLE_USER_HOME), new File(TEST_PROJECT_DIR));
    private final WrapperConfiguration configuration = new WrapperConfiguration();

    @BeforeEach
    void setup() {
        configuration.setDistributionBase(PathAssembler.GRADLE_USER_HOME_STRING);
        configuration.setDistributionPath("somePath");
        configuration.setZipBase(PathAssembler.GRADLE_USER_HOME_STRING);
        configuration.setZipPath("somePath");
    }

    @Test
    void distributionDirWithGradleUserHomeBase() throws Exception {
        configuration.setDistribution(new URI("http://server/dist/gradle-0.9-bin.zip"));

        File distributionDir = pathAssembler.getDistribution(configuration).getDistributionDir();
        assertEquals("emn8ua2x0re2y4jlewhnxhasz", distributionDir.getName());
        assertEquals(file(TEST_GRADLE_USER_HOME + "/somePath/gradle-0.9-bin"), distributionDir.getParentFile());
    }

    @Test
    void distributionDirWithProjectBase() throws Exception {
        configuration.setDistributionBase(PathAssembler.PROJECT_STRING);
        configuration.setDistribution(new URI("http://server/dist/gradle-0.9-bin.zip"));

        File distributionDir = pathAssembler.getDistribution(configuration).getDistributionDir();
        assertEquals("emn8ua2x0re2y4jlewhnxhasz", distributionDir.getName());
        assertEquals(file(TEST_PROJECT_DIR + "/somePath/gradle-0.9-bin"), distributionDir.getParentFile());
    }

    @Test
    void distributionDirWithUnknownBase() throws Exception {
        configuration.setDistribution(new URI("http://server/dist/gradle-1.0.zip"));
        configuration.setDistributionBase("unknownBase");

        RuntimeException failure = assertThrows(RuntimeException.class, () -> pathAssembler.getDistribution(configuration));
        assertEquals("Base: unknownBase is unknown", failure.getMessage());
    }

    @Test
    void distributionDirWithUserInfo() throws Exception {
        configuration.setDistributionBase(PathAssembler.PROJECT_STRING);

        configuration.setDistribution(new URI("http://username1:password1@server/dist/gradle-0.9-bin.zip"));
        File distributionDir1 = pathAssembler.getDistribution(configuration).getDistributionDir();

        configuration.setDistribution(new URI("http://username2:password2@server/dist/gradle-0.9-bin.zip"));
        File distributionDir2 = pathAssembler.getDistribution(configuration).getDistributionDir();

        assertEquals(distributionDir1.getName(), distributionDir2.getName());
    }

    @Test
    void distZipWithGradleUserHomeBase() throws Exception {
        configuration.setDistribution(new URI("http://server/dist/gradle-1.0.zip"));

        File dist = pathAssembler.getDistribution(configuration).getZipFile();
        assertEquals("gradle-1.0.zip", dist.getName());
        assertEquals("98xa9n94mamfu7vl4mzwomw11", dist.getParentFile().getName());
        assertEquals(file(TEST_GRADLE_USER_HOME + "/somePath/gradle-1.0"), dist.getParentFile().getParentFile());
    }

    @Test
    void distZipWithProjectBase() throws Exception {
        configuration.setZipBase(PathAssembler.PROJECT_STRING);
        configuration.setDistribution(new URI("http://server/dist/gradle-1.0.zip"));

        File dist = pathAssembler.getDistribution(configuration).getZipFile();
        assertEquals("gradle-1.0.zip", dist.getName());
        assertEquals("98xa9n94mamfu7vl4mzwomw11", dist.getParentFile().getName());
        assertEquals(file(TEST_PROJECT_DIR + "/somePath/gradle-1.0"), dist.getParentFile().getParentFile());
    }

    private static File file(String path) {
        return new File(path);
    }
}
