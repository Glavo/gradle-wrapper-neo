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
package org.gradle.wrapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.gradle.wrapper.neo.Bootstrap;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GradleWrapperMainTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesWrapperContextFromExplicitAppHome() {
        File appHome = temporaryDirectory.resolve("project").toFile();
        String originalValue = System.getProperty(Bootstrap.APP_HOME_PROPERTY);
        try {
            System.setProperty(Bootstrap.APP_HOME_PROPERTY, appHome.getPath());

            assertEquals(appHome, GradleWrapperMain.appHome());
            assertEquals(new File(appHome, "gradle/wrapper/gradle-wrapper.properties"), GradleWrapperMain.wrapperProperties());
        } finally {
            restoreProperty(Bootstrap.APP_HOME_PROPERTY, originalValue);
        }
    }

    private static void restoreProperty(String property, String value) {
        if (value == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, value);
        }
    }
}
