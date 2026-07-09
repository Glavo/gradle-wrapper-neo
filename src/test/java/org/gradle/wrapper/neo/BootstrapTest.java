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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BootstrapTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void readsWrapperDirectoryFromSystemProperty() {
        Path wrapperDir = temporaryDirectory.resolve("custom-wrapper");
        String originalValue = System.getProperty(Bootstrap.WRAPPER_DIR_PROPERTY);
        try {
            System.setProperty(Bootstrap.WRAPPER_DIR_PROPERTY, wrapperDir.toString());
            assertEquals(wrapperDir, Bootstrap.wrapperDir());
        } finally {
            restoreProperty(Bootstrap.WRAPPER_DIR_PROPERTY, originalValue);
        }
    }

    @Test
    void rejectsMissingWrapperDirectorySystemProperty() {
        String originalValue = System.getProperty(Bootstrap.WRAPPER_DIR_PROPERTY);
        try {
            System.clearProperty(Bootstrap.WRAPPER_DIR_PROPERTY);

            RuntimeException failure = assertThrows(RuntimeException.class, Bootstrap::wrapperDir);

            assertEquals("Missing required system property: " + Bootstrap.WRAPPER_DIR_PROPERTY, failure.getMessage());
        } finally {
            restoreProperty(Bootstrap.WRAPPER_DIR_PROPERTY, originalValue);
        }
    }

    @Test
    void forwardsWrapperDirectoryAndRemovesBootstrapOnlyProperties() {
        Path wrapperDir = temporaryDirectory.resolve("custom-wrapper");

        List<String> result = Bootstrap.forwardedJvmArguments(
            Arrays.asList(
                "-Xmx256m",
                "-Duser.language=en",
                "-Dgradle.wrapper.neo.bootstrap=true",
                "-Dgradle.wrapper.neo.source=/source",
                "-Dgradle.wrapper.neo.jar=/jar",
                "-Dorg.gradle.wrapper.neo.wrapper-dir=/wrong"
            ),
            wrapperDir
        );

        assertEquals(
            Arrays.asList(
                "-Xmx256m",
                "-Duser.language=en",
                "-D" + Bootstrap.WRAPPER_DIR_PROPERTY + "=" + wrapperDir
            ),
            result
        );
    }

    private static void restoreProperty(String property, String value) {
        if (value == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, value);
        }
    }
}
