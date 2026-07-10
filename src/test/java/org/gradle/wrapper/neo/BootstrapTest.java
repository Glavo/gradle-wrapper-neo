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
    void readsExplicitPathsFromSystemProperties() {
        Path wrapperRoot = temporaryDirectory.resolve("project");
        Path sourceFile = temporaryDirectory.resolve("launcher/GradleWrapperNeo.java");
        Path jarFile = wrapperRoot.resolve(".gradle/wrapper-neo/launcher/gradle-wrapper-neo.jar");
        String originalWrapperRoot = System.getProperty(Bootstrap.WRAPPER_ROOT_PROPERTY);
        String originalSourceFile = System.getProperty(Bootstrap.SOURCE_FILE_PROPERTY);
        String originalJarFile = System.getProperty(Bootstrap.JAR_FILE_PROPERTY);
        try {
            System.setProperty(Bootstrap.WRAPPER_ROOT_PROPERTY, wrapperRoot.toString());
            System.setProperty(Bootstrap.SOURCE_FILE_PROPERTY, sourceFile.toString());
            System.setProperty(Bootstrap.JAR_FILE_PROPERTY, jarFile.toString());

            assertEquals(wrapperRoot, Bootstrap.wrapperRoot());
            assertEquals(sourceFile, Bootstrap.sourceFile());
            assertEquals(jarFile, Bootstrap.jarFile());
        } finally {
            restoreProperty(Bootstrap.WRAPPER_ROOT_PROPERTY, originalWrapperRoot);
            restoreProperty(Bootstrap.SOURCE_FILE_PROPERTY, originalSourceFile);
            restoreProperty(Bootstrap.JAR_FILE_PROPERTY, originalJarFile);
        }
    }

    @Test
    void rejectsMissingRequiredPath() {
        String originalValue = System.getProperty(Bootstrap.WRAPPER_ROOT_PROPERTY);
        try {
            System.clearProperty(Bootstrap.WRAPPER_ROOT_PROPERTY);

            RuntimeException failure = assertThrows(RuntimeException.class, Bootstrap::wrapperRoot);

            assertEquals("Missing required system property: " + Bootstrap.WRAPPER_ROOT_PROPERTY, failure.getMessage());
        } finally {
            restoreProperty(Bootstrap.WRAPPER_ROOT_PROPERTY, originalValue);
        }
    }

    @Test
    void rejectsRelativePath() {
        String originalValue = System.getProperty(Bootstrap.SOURCE_FILE_PROPERTY);
        try {
            System.setProperty(Bootstrap.SOURCE_FILE_PROPERTY, "GradleWrapperNeo.java");

            RuntimeException failure = assertThrows(RuntimeException.class, Bootstrap::sourceFile);

            assertEquals(
                "System property " + Bootstrap.SOURCE_FILE_PROPERTY + " must be an absolute path: GradleWrapperNeo.java",
                failure.getMessage()
            );
        } finally {
            restoreProperty(Bootstrap.SOURCE_FILE_PROPERTY, originalValue);
        }
    }

    @Test
    void forwardsExplicitPathsAndRemovesInternalProperties() {
        Path wrapperRoot = temporaryDirectory.resolve("project");
        Path sourceFile = temporaryDirectory.resolve("launcher/GradleWrapperNeo.java");
        Path jarFile = wrapperRoot.resolve(".gradle/wrapper-neo/launcher/gradle-wrapper-neo.jar");

        List<String> result = Bootstrap.forwardedJvmArguments(
            Arrays.asList(
                "-Xmx256m",
                "-Duser.language=en",
                "-Dgradle.wrapper.neo.mirrors.0.enabled=false",
                "-Dgradle.wrapper.neo.bootstrap=true",
                "-Dorg.gradle.wrapper.neo.wrapper-root=/wrong",
                "-Dorg.gradle.wrapper.neo.source-file=/wrong",
                "-Dorg.gradle.wrapper.neo.jar-file=/wrong"
            ),
            wrapperRoot,
            sourceFile,
            jarFile
        );

        assertEquals(
            Arrays.asList(
                "-Xmx256m",
                "-Duser.language=en",
                "-Dgradle.wrapper.neo.mirrors.0.enabled=false",
                "-D" + Bootstrap.WRAPPER_ROOT_PROPERTY + "=" + wrapperRoot,
                "-D" + Bootstrap.SOURCE_FILE_PROPERTY + "=" + sourceFile,
                "-D" + Bootstrap.JAR_FILE_PROPERTY + "=" + jarFile
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
