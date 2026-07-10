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
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GradleWrapperMainTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesWrapperContextFromExplicitRoot() {
        File wrapperRoot = temporaryDirectory.resolve("project").toFile();
        String originalValue = System.getProperty(Bootstrap.WRAPPER_ROOT_PROPERTY);
        try {
            System.setProperty(Bootstrap.WRAPPER_ROOT_PROPERTY, wrapperRoot.getPath());

            assertEquals(wrapperRoot, GradleWrapperMain.wrapperRoot());
            assertEquals(new File(wrapperRoot, "gradle/wrapper/gradle-wrapper.properties"), GradleWrapperMain.wrapperProperties());
        } finally {
            restoreProperty(Bootstrap.WRAPPER_ROOT_PROPERTY, originalValue);
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

    private static void restoreProperty(String property, String value) {
        if (value == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, value);
        }
    }
}
