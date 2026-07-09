/*
 * Copyright 2015 the original author or authors.
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class GradleUserHomeLookupTest {
    @AfterEach
    void clearGradleUserHomeSystemProperty() {
        System.clearProperty(GradleUserHomeLookup.GRADLE_USER_HOME_PROPERTY_KEY);
    }

    @Test
    void returnsDefaultGradleUserHomeIfEnvironmentVariableOrSystemPropertyIsNotDefined() {
        assumeFalse(System.getenv().containsKey(GradleUserHomeLookup.GRADLE_USER_HOME_ENV_KEY));
        System.clearProperty(GradleUserHomeLookup.GRADLE_USER_HOME_PROPERTY_KEY);

        assertEquals(new File(GradleUserHomeLookup.DEFAULT_GRADLE_USER_HOME), GradleUserHomeLookup.gradleUserHome());
    }

    @Test
    void returnsGradleUserHomeSetBySystemProperty() {
        String userDefinedDirName = "some/dir";
        System.setProperty(GradleUserHomeLookup.GRADLE_USER_HOME_PROPERTY_KEY, userDefinedDirName);

        assertEquals(new File(userDefinedDirName), GradleUserHomeLookup.gradleUserHome());
    }
}
