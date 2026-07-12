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
package org.glavo.gradle.wrapper.neo.plugin;

import org.gradle.api.GradleException;
import org.gradle.util.GradleVersion;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the version selectors accepted by the standard Gradle {@code Wrapper} task.
 *
 * <p>Complete version strings are validated locally. Dynamic labels and Gradle 9 or later
 * major/minor selectors are resolved through the public Gradle versions service.</p>
 */
final class GradleDistributionVersionResolver {
    private static final String DEFAULT_SERVICES_BASE_URL = "https://services.gradle.org";
    private static final int MAX_RESPONSE_SIZE = 1024 * 1024;
    private static final Pattern SEMANTIC_VERSION_REQUEST = Pattern.compile("([0-9]+)(?:\\.([0-9]+))?");
    private static final Pattern VERSION_FIELD = Pattern.compile(
            "\"version\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\""
    );

    private final String servicesBaseUrl;
    private final int networkTimeout;

    /**
     * Creates a resolver using the public Gradle services endpoint.
     *
     * @param networkTimeout the connection and read timeout in milliseconds
     */
    GradleDistributionVersionResolver(int networkTimeout) {
        this(DEFAULT_SERVICES_BASE_URL, networkTimeout);
    }

    /**
     * Creates a resolver using a specified Gradle services endpoint.
     *
     * @param servicesBaseUrl the endpoint base URL
     * @param networkTimeout  the connection and read timeout in milliseconds
     */
    GradleDistributionVersionResolver(String servicesBaseUrl, int networkTimeout) {
        if (servicesBaseUrl == null || servicesBaseUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("servicesBaseUrl must not be empty.");
        }
        if (networkTimeout < 0) {
            throw new IllegalArgumentException("networkTimeout must not be negative.");
        }

        String normalizedBaseUrl = servicesBaseUrl;
        while (normalizedBaseUrl.endsWith("/")) {
            normalizedBaseUrl = normalizedBaseUrl.substring(0, normalizedBaseUrl.length() - 1);
        }
        this.servicesBaseUrl = normalizedBaseUrl;
        this.networkTimeout = networkTimeout;
    }

    /**
     * Resolves a complete version, dynamic label, or supported semantic selector.
     *
     * @param request the value supplied for {@code --gradle-version}
     * @return the concrete Gradle version
     */
    String resolve(String request) {
        DynamicVersion dynamicVersion = DynamicVersion.find(request);
        if (dynamicVersion != null) {
            return resolveDynamicVersion(dynamicVersion);
        }

        Matcher semanticVersion = SEMANTIC_VERSION_REQUEST.matcher(request);
        if (semanticVersion.matches()) {
            int majorVersion;
            Integer minorVersion;
            try {
                majorVersion = Integer.parseInt(semanticVersion.group(1));
                minorVersion = semanticVersion.group(2) == null
                        ? null
                        : Integer.valueOf(semanticVersion.group(2));
            } catch (NumberFormatException e) {
                throw invalidVersion(e);
            }

            if (majorVersion >= 9) {
                return resolveSemanticVersion(majorVersion, minorVersion);
            }
        }

        return parseVersion(request).getVersion();
    }

    private String resolveDynamicVersion(DynamicVersion dynamicVersion) {
        String json = readEndpoint(
                "versions/" + dynamicVersion.endpoint,
                "Unable to resolve Gradle version for '" + dynamicVersion.selector + "'."
        );
        List<String> versions = versionFields(json, false);
        if (versions.isEmpty()) {
            throw new GradleException(
                    "There is currently no version information available for '" + dynamicVersion.selector + "'."
            );
        }
        return parseVersion(versions.get(0)).getVersion();
    }

    private String resolveSemanticVersion(int majorVersion, Integer minorVersion) {
        String json = readEndpoint(
                "versions/" + majorVersion,
                "Unable to resolve list of Gradle versions for '" + majorVersion + "'."
        );

        GradleVersion selectedVersion = null;
        for (String versionText : versionFields(json, true)) {
            GradleVersion candidate;
            try {
                candidate = GradleVersion.version(versionText);
            } catch (RuntimeException ignored) {
                continue;
            }

            int[] semanticParts = semanticParts(candidate);
            if (!isFinal(candidate)
                    || semanticParts[0] != majorVersion
                    || (minorVersion != null && semanticParts[1] != minorVersion)) {
                continue;
            }
            if (selectedVersion == null || candidate.compareTo(selectedVersion) > 0) {
                selectedVersion = candidate;
            }
        }

        if (selectedVersion == null) {
            String description = minorVersion == null
                    ? "major version " + majorVersion
                    : "version " + majorVersion + "." + minorVersion;
            throw new GradleException(
                    "Invalid version specified for argument '--gradle-version': no final version found for " + description
            );
        }
        return selectedVersion.getVersion();
    }

    private String readEndpoint(String path, String failureMessage) {
        try {
            URLConnection connection = new URL(servicesBaseUrl + "/" + path).openConnection();
            connection.setConnectTimeout(networkTimeout);
            connection.setReadTimeout(networkTimeout);
            connection.setRequestProperty("User-Agent", "gradle-wrapper-neo");
            try (InputStream input = connection.getInputStream()) {
                return new String(readLimitedBytes(input), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new GradleException(failureMessage, e);
        }
    }

    private static byte[] readLimitedBytes(InputStream input) throws IOException {
        byte[] buffer = new byte[8192];
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int length;
        while ((length = input.read(buffer)) != -1) {
            if (output.size() + length > MAX_RESPONSE_SIZE) {
                throw new IOException("Gradle versions response exceeds " + MAX_RESPONSE_SIZE + " bytes.");
            }
            output.write(buffer, 0, length);
        }
        return output.toByteArray();
    }

    private static List<String> versionFields(String json, boolean arrayExpected) {
        String trimmed = json.trim();
        char opening = arrayExpected ? '[' : '{';
        char closing = arrayExpected ? ']' : '}';
        if (trimmed.length() < 2
                || trimmed.charAt(0) != opening
                || trimmed.charAt(trimmed.length() - 1) != closing) {
            throw new GradleException("Gradle versions service returned malformed JSON.");
        }

        List<String> versions = new ArrayList<>();
        Matcher matcher = VERSION_FIELD.matcher(trimmed);
        while (matcher.find()) {
            versions.add(unescapeJsonString(matcher.group(1)));
        }
        return versions;
    }

    private static String unescapeJsonString(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != '\\') {
                if (character < 0x20) {
                    throw new GradleException("Gradle versions service returned malformed JSON.");
                }
                result.append(character);
                continue;
            }

            if (++index >= value.length()) {
                throw new GradleException("Gradle versions service returned malformed JSON.");
            }
            char escaped = value.charAt(index);
            switch (escaped) {
                case '"':
                case '\\':
                case '/':
                    result.append(escaped);
                    break;
                case 'b':
                    result.append('\b');
                    break;
                case 'f':
                    result.append('\f');
                    break;
                case 'n':
                    result.append('\n');
                    break;
                case 'r':
                    result.append('\r');
                    break;
                case 't':
                    result.append('\t');
                    break;
                case 'u':
                    if (index + 4 >= value.length()) {
                        throw new GradleException("Gradle versions service returned malformed JSON.");
                    }
                    int codePoint = 0;
                    for (int digitIndex = 0; digitIndex < 4; digitIndex++) {
                        int digit = Character.digit(value.charAt(++index), 16);
                        if (digit < 0) {
                            throw new GradleException("Gradle versions service returned malformed JSON.");
                        }
                        codePoint = (codePoint << 4) | digit;
                    }
                    result.append((char) codePoint);
                    break;
                default:
                    throw new GradleException("Gradle versions service returned malformed JSON.");
            }
        }
        return result.toString();
    }

    private static GradleVersion parseVersion(String version) {
        try {
            return GradleVersion.version(version);
        } catch (RuntimeException e) {
            throw invalidVersion(e);
        }
    }

    private static GradleException invalidVersion(RuntimeException cause) {
        return new GradleException("Invalid version specified for argument '--gradle-version'", cause);
    }

    private static boolean isFinal(GradleVersion version) {
        return version.getVersion().equals(version.getBaseVersion().getVersion());
    }

    private static int[] semanticParts(GradleVersion version) {
        String[] parts = version.getBaseVersion().getVersion().split("\\.");
        try {
            int major = parts.length > 0 ? Integer.parseInt(parts[0]) : -1;
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return new int[]{major, minor};
        } catch (NumberFormatException e) {
            return new int[]{-1, -1};
        }
    }

    private enum DynamicVersion {
        LATEST("latest", "current"),
        RELEASE_CANDIDATE("release-candidate", "release-candidate"),
        RELEASE_MILESTONE("release-milestone", "milestone"),
        RELEASE_NIGHTLY("release-nightly", "release-nightly"),
        NIGHTLY("nightly", "nightly");

        private final String selector;
        private final String endpoint;

        DynamicVersion(String selector, String endpoint) {
            this.selector = selector;
            this.endpoint = endpoint;
        }

        private static DynamicVersion find(String selector) {
            for (DynamicVersion value : values()) {
                if (value.selector.equals(selector)) {
                    return value;
                }
            }
            return null;
        }
    }
}
