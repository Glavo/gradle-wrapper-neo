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
    void resolvesProjectPathsFromWrapperDirectorySystemProperty() {
        File projectDir = temporaryDirectory.toFile();
        File wrapperDir = new File(projectDir, "gradle/wrapper");
        File jarFile = temporaryDirectory.resolve("unrelated/cache/gradle-wrapper-neo.jar").toFile();
        String originalValue = System.getProperty(Bootstrap.WRAPPER_DIR_PROPERTY);
        try {
            System.setProperty(Bootstrap.WRAPPER_DIR_PROPERTY, wrapperDir.getPath());

            assertEquals(wrapperDir, GradleWrapperMain.wrapperDir(jarFile));
            assertEquals(new File(wrapperDir, "gradle-wrapper.properties"), GradleWrapperMain.wrapperProperties(jarFile));
            assertEquals(projectDir, GradleWrapperMain.rootDir(jarFile));
        } finally {
            restoreProperty(Bootstrap.WRAPPER_DIR_PROPERTY, originalValue);
        }
    }

    @Test
    void fallsBackToJarParentWhenWrapperDirectorySystemPropertyIsMissing() {
        File jarFile = temporaryDirectory.resolve("wrapper/gradle-wrapper.jar").toFile();
        String originalValue = System.getProperty(Bootstrap.WRAPPER_DIR_PROPERTY);
        try {
            System.clearProperty(Bootstrap.WRAPPER_DIR_PROPERTY);

            assertEquals(jarFile.getParentFile(), GradleWrapperMain.wrapperDir(jarFile));
        } finally {
            restoreProperty(Bootstrap.WRAPPER_DIR_PROPERTY, originalValue);
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
