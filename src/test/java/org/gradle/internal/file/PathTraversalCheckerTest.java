/*
 * Copyright 2022 the original author or authors.
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
package org.gradle.internal.file;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PathTraversalCheckerTest {
    @Test
    void identifiesPotentiallyUnsafeZipEntryNames() {
        assertUnsafeAndSafe("/", "foo/");
        assertUnsafeAndSafe("\\", "foo\\");
        assertUnsafeAndSafe("/foo", "foo");
        assertUnsafeAndSafe("\\foo", "foo");
        assertUnsafeAndSafe("foo/..", "foo/bar");
        assertUnsafeAndSafe("foo\\..", "foo\\bar");
        assertUnsafeAndSafe("../foo", "..foo");
        assertUnsafeAndSafe("..\\foo", "..foo");
        assertUnsafeAndSafe("foo/../bar", "foo/..bar");
        assertUnsafeAndSafe("foo\\..\\bar", "foo\\..bar");
        assertUnsafeAndSafe("foo\\../bar", "foo\\..bar");
    }

    @Test
    void identifiesPotentiallyUnsafeZipEntryNamesOnWindows() {
        assumeTrue(isWindows());

        assertUnsafeAndSafe("C:/foo", "foo");
        assertUnsafeAndSafe("foo.", "foo.txt");
        assertUnsafeAndSafe("foo.\\bar.txt", "foo\\bar.txt");
        assertUnsafeAndSafe("foo./bar.txt", "foo/bar.txt");
        assertUnsafeAndSafe("foo..\\bar", "..foo\\bar");
    }

    @Test
    void doesNotRejectSafeZipEntryNamesWithSimilarPatterns() {
        assertFalse(PathTraversalChecker.isUnsafePathName(".hidden"));
        assertFalse(PathTraversalChecker.isUnsafePathName("foo/..bar"));
        assertFalse(PathTraversalChecker.isUnsafePathName("foo\\..bar"));
        assertFalse(PathTraversalChecker.isUnsafePathName("./..foo"));
        assertFalse(PathTraversalChecker.isUnsafePathName(".\\..foo"));
    }

    @Test
    void identifiesPotentiallyUnsafeZipEntryNamesOnNonWindows() {
        assumeFalse(isWindows());

        assertFalse(PathTraversalChecker.isUnsafePathName("foo../bar"));
        assertFalse(PathTraversalChecker.isUnsafePathName("foo..\\bar"));
        assertFalse(PathTraversalChecker.isUnsafePathName(".../bar"));
        assertFalse(PathTraversalChecker.isUnsafePathName("...\\bar"));
        assertFalse(PathTraversalChecker.isUnsafePathName("foo...//"));
        assertFalse(PathTraversalChecker.isUnsafePathName("foo...\\\\"));
        assertFalse(PathTraversalChecker.isUnsafePathName("foo...//bar"));
        assertFalse(PathTraversalChecker.isUnsafePathName("foo...\\\\bar"));
    }

    private static void assertUnsafeAndSafe(String unsafePath, String safePath) {
        assertTrue(PathTraversalChecker.isUnsafePathName(unsafePath), unsafePath);
        assertFalse(PathTraversalChecker.isUnsafePathName(safePath), safePath);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.US).contains("windows");
    }
}
