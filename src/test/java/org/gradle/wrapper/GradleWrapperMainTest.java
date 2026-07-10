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

import org.gradle.cli.CommandLineParser;
import org.gradle.cli.ParsedCommandLine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.gradle.wrapper.neo.Bootstrap;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GradleWrapperMainTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesLocalProjectPathsFromSourceWrapperDirectory() throws IOException {
        File projectDir = temporaryDirectory.toFile();
        File wrapperDir = new File(projectDir, "gradle/wrapper");
        File jarFile = temporaryDirectory.resolve("unrelated/cache/gradle-wrapper-neo.jar").toFile();
        assertTrue(wrapperDir.mkdirs());
        assertTrue(new File(wrapperDir, "gradle-wrapper.properties").createNewFile());
        String originalValue = System.getProperty(Bootstrap.WRAPPER_DIR_PROPERTY);
        try {
            System.setProperty(Bootstrap.WRAPPER_DIR_PROPERTY, wrapperDir.getPath());

            assertEquals(wrapperDir, GradleWrapperMain.sourceWrapperDir(jarFile));
            assertEquals(wrapperDir, GradleWrapperMain.wrapperDir(jarFile));
            assertEquals(new File(wrapperDir, "gradle-wrapper.properties"), GradleWrapperMain.wrapperProperties(jarFile));
            assertEquals(projectDir, GradleWrapperMain.rootDir(jarFile));
        } finally {
            restoreProperty(Bootstrap.WRAPPER_DIR_PROPERTY, originalValue);
        }
    }

    @Test
    void findsNearestProjectWrapperInGlobalMode() throws IOException {
        File sourceWrapperDir = temporaryDirectory.resolve("global/gradle/wrapper").toFile();
        File outerProject = temporaryDirectory.resolve("projects/outer").toFile();
        File innerProject = temporaryDirectory.resolve("projects/outer/nested/inner").toFile();
        File currentDirectory = temporaryDirectory.resolve("projects/outer/nested/inner/subproject").toFile();
        File outerWrapperDir = createWrapperProperties(outerProject);
        File innerWrapperDir = createWrapperProperties(innerProject);
        File jarFile = temporaryDirectory.resolve("project/gradle/wrapper/.gradle-wrapper-neo/gradle-wrapper-neo-global.jar").toFile();
        String originalValue = System.getProperty(Bootstrap.WRAPPER_DIR_PROPERTY);
        try {
            System.setProperty(Bootstrap.WRAPPER_DIR_PROPERTY, sourceWrapperDir.getPath());

            assertEquals(innerWrapperDir, GradleWrapperMain.wrapperDir(jarFile, currentDirectory));
            assertNotEquals(outerWrapperDir, innerWrapperDir);
        } finally {
            restoreProperty(Bootstrap.WRAPPER_DIR_PROPERTY, originalValue);
        }
    }

    @Test
    void rejectsGlobalModeOutsideAWrapperProject() {
        File sourceWrapperDir = temporaryDirectory.resolve("global/gradle/wrapper").toFile();
        File currentDirectory = temporaryDirectory.resolve("projects/without-wrapper/subproject").toFile();
        File jarFile = temporaryDirectory.resolve("project/gradle/wrapper/.gradle-wrapper-neo/gradle-wrapper-neo-global.jar").toFile();
        String originalValue = System.getProperty(Bootstrap.WRAPPER_DIR_PROPERTY);
        try {
            System.setProperty(Bootstrap.WRAPPER_DIR_PROPERTY, sourceWrapperDir.getPath());

            RuntimeException failure = assertThrows(RuntimeException.class, () -> GradleWrapperMain.wrapperDir(jarFile, currentDirectory));

            assertEquals(
                "Could not find gradle/wrapper/gradle-wrapper.properties searching from " + currentDirectory.getAbsolutePath() + ".",
                failure.getMessage()
            );
        } finally {
            restoreProperty(Bootstrap.WRAPPER_DIR_PROPERTY, originalValue);
        }
    }

    @Test
    void sourceWrapperDirectoryFallsBackToJarParentWhenSystemPropertyIsMissing() {
        File jarFile = temporaryDirectory.resolve("wrapper/gradle-wrapper.jar").toFile();
        String originalValue = System.getProperty(Bootstrap.WRAPPER_DIR_PROPERTY);
        try {
            System.clearProperty(Bootstrap.WRAPPER_DIR_PROPERTY);

            assertEquals(jarFile.getParentFile(), GradleWrapperMain.sourceWrapperDir(jarFile));
        } finally {
            restoreProperty(Bootstrap.WRAPPER_DIR_PROPERTY, originalValue);
        }
    }

    @Test
    void usesExplicitGradleUserHomeOptionForMirrorConfiguration() {
        File optionHome = temporaryDirectory.resolve("option-home").toFile();
        Map<String, String> commandLineProperties = new HashMap<>();
        commandLineProperties.put(GradleUserHomeLookup.GRADLE_USER_HOME_PROPERTY_KEY, temporaryDirectory.resolve("property-home").toString());

        File result = GradleWrapperMain.mirrorConfigurationHome(parse("-g", optionHome.getPath()), commandLineProperties);

        assertEquals(optionHome, result);
    }

    @Test
    void usesCommandLineSystemPropertyForMirrorConfiguration() {
        File propertyHome = temporaryDirectory.resolve("property-home").toFile();
        Map<String, String> commandLineProperties = new HashMap<>();
        commandLineProperties.put(GradleUserHomeLookup.GRADLE_USER_HOME_PROPERTY_KEY, propertyHome.getPath());

        File result = GradleWrapperMain.mirrorConfigurationHome(parse(), commandLineProperties);

        assertEquals(propertyHome, result);
    }

    private static ParsedCommandLine parse(String... args) {
        CommandLineParser parser = new CommandLineParser();
        parser.allowUnknownOptions();
        parser.option(GradleWrapperMain.GRADLE_USER_HOME_OPTION, GradleWrapperMain.GRADLE_USER_HOME_DETAILED_OPTION).hasArgument();
        return parser.parse(args);
    }

    private static File createWrapperProperties(File projectDir) throws IOException {
        File wrapperDir = new File(projectDir, "gradle/wrapper");
        assertTrue(wrapperDir.mkdirs());
        assertTrue(new File(wrapperDir, "gradle-wrapper.properties").createNewFile());
        return wrapperDir;
    }

    private static void restoreProperty(String property, String value) {
        if (value == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, value);
        }
    }
}
