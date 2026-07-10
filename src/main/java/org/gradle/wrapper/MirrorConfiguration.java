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

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

final class MirrorConfiguration {
    static final String FILE_NAME = "gradle-wrapper-neo.json";
    private static final int MAX_FILE_SIZE = 1024 * 1024;
    private static final Set<String> ROOT_FIELDS = fields("version", "mirrors");
    private static final Set<String> MIRROR_FIELDS = fields("regions", "pattern", "replacement", "requireChecksum");

    private final String region;
    private final List<Mirror> mirrors;

    private MirrorConfiguration(String region, List<Mirror> mirrors) {
        this.region = region;
        this.mirrors = Collections.unmodifiableList(new ArrayList<>(mirrors));
    }

    static MirrorConfiguration empty() {
        return new MirrorConfiguration("", Collections.emptyList());
    }

    static MirrorConfiguration load(File gradleUserHome) {
        return load(gradleUserHome, Locale.getDefault().getCountry());
    }

    static MirrorConfiguration load(File gradleUserHome, String region) {
        File file = new File(gradleUserHome, FILE_NAME);
        if (!file.exists()) {
            return new MirrorConfiguration(normalizeRegion(region), Collections.emptyList());
        }
        if (!file.isFile()) {
            throw new RuntimeException("Mirror configuration is not a file: " + file);
        }

        try {
            if (file.length() > MAX_FILE_SIZE) {
                throw new IllegalArgumentException("Mirror configuration exceeds " + MAX_FILE_SIZE + " bytes.");
            }
            byte[] content = Files.readAllBytes(file.toPath());
            if (content.length > MAX_FILE_SIZE) {
                throw new IllegalArgumentException("Mirror configuration exceeds " + MAX_FILE_SIZE + " bytes.");
            }
            String json = new String(content, StandardCharsets.UTF_8);
            return parse(json, region);
        } catch (IOException | RuntimeException e) {
            throw new RuntimeException("Could not load mirror configuration from '" + file + "'.", e);
        }
    }

    static MirrorConfiguration parse(String json, String region) {
        Map<String, Object> root = object(MinimalJsonParser.parse(json), "root");
        rejectUnknownFields(root, ROOT_FIELDS, "root");

        BigDecimal version = number(required(root, "version", "root"), "root.version");
        if (version.compareTo(BigDecimal.ONE) != 0) {
            throw new IllegalArgumentException("Unsupported mirror configuration version: " + version);
        }

        List<Mirror> mirrors = new ArrayList<>();
        if (root.containsKey("mirrors")) {
            Object configuredMirrors = root.get("mirrors");
            List<Object> entries = array(configuredMirrors, "root.mirrors");
            for (int index = 0; index < entries.size(); index++) {
                mirrors.add(parseMirror(entries.get(index), "root.mirrors[" + index + "]"));
            }
        }
        return new MirrorConfiguration(normalizeRegion(region), mirrors);
    }

    List<URI> resolve(URI source, boolean checksumProvided) {
        LinkedHashSet<URI> result = new LinkedHashSet<>();
        for (Mirror mirror : mirrors) {
            URI resolved = mirror.resolve(source, region, checksumProvided);
            if (resolved != null && !resolved.equals(source)) {
                result.add(resolved);
            }
        }
        result.add(source);
        return new ArrayList<>(result);
    }

    private static Mirror parseMirror(Object value, String path) {
        Map<String, Object> mirror = object(value, path);
        rejectUnknownFields(mirror, MIRROR_FIELDS, path);

        Set<String> regions = new HashSet<>();
        if (mirror.containsKey("regions")) {
            Object configuredRegions = mirror.get("regions");
            List<Object> entries = array(configuredRegions, path + ".regions");
            for (int index = 0; index < entries.size(); index++) {
                String configuredRegion = string(entries.get(index), path + ".regions[" + index + "]");
                String normalizedRegion = normalizeRegion(configuredRegion);
                if (normalizedRegion.isEmpty()) {
                    throw new IllegalArgumentException(path + ".regions must not contain empty values.");
                }
                regions.add(normalizedRegion);
            }
        }

        String patternText = string(required(mirror, "pattern", path), path + ".pattern");
        Pattern pattern;
        try {
            pattern = Pattern.compile(patternText);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid regular expression at " + path + ".pattern: " + e.getDescription(), e);
        }

        String replacement = string(required(mirror, "replacement", path), path + ".replacement");
        boolean requireChecksum = optionalBoolean(mirror, "requireChecksum", true, path);
        return new Mirror(regions, pattern, replacement, requireChecksum);
    }

    private static Object required(Map<String, Object> object, String field, String path) {
        if (!object.containsKey(field)) {
            throw new IllegalArgumentException("Missing required field " + path + "." + field + ".");
        }
        return object.get(field);
    }

    private static boolean optionalBoolean(Map<String, Object> object, String field, boolean defaultValue, String path) {
        if (!object.containsKey(field)) {
            return defaultValue;
        }
        Object value = object.get(field);
        if (!(value instanceof Boolean)) {
            throw new IllegalArgumentException(path + "." + field + " must be a boolean.");
        }
        return (Boolean) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String path) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException(path + " must be an object.");
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value, String path) {
        if (!(value instanceof List)) {
            throw new IllegalArgumentException(path + " must be an array.");
        }
        return (List<Object>) value;
    }

    private static String string(Object value, String path) {
        if (!(value instanceof String)) {
            throw new IllegalArgumentException(path + " must be a string.");
        }
        return (String) value;
    }

    private static BigDecimal number(Object value, String path) {
        if (!(value instanceof BigDecimal)) {
            throw new IllegalArgumentException(path + " must be a number.");
        }
        return (BigDecimal) value;
    }

    private static void rejectUnknownFields(Map<String, Object> object, Set<String> allowedFields, String path) {
        for (String field : object.keySet()) {
            if (!allowedFields.contains(field)) {
                throw new IllegalArgumentException("Unknown field " + path + "." + field + ".");
            }
        }
    }

    private static String normalizeRegion(String region) {
        return region == null ? "" : region.trim().toUpperCase(Locale.US);
    }

    private static Set<String> fields(String... values) {
        Set<String> result = new HashSet<>();
        Collections.addAll(result, values);
        return Collections.unmodifiableSet(result);
    }

    private static final class Mirror {
        private final Set<String> regions;
        private final Pattern pattern;
        private final String replacement;
        private final boolean requireChecksum;

        private Mirror(Set<String> regions, Pattern pattern, String replacement, boolean requireChecksum) {
            this.regions = Collections.unmodifiableSet(new HashSet<>(regions));
            this.pattern = pattern;
            this.replacement = replacement;
            this.requireChecksum = requireChecksum;
        }

        private URI resolve(URI source, String region, boolean checksumProvided) {
            if ((!regions.isEmpty() && !regions.contains(region)) || (requireChecksum && !checksumProvided)) {
                return null;
            }

            Matcher matcher = pattern.matcher(source.toASCIIString());
            if (!matcher.matches()) {
                return null;
            }

            String resolvedText;
            try {
                resolvedText = matcher.replaceFirst(replacement);
            } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
                throw new IllegalArgumentException("Invalid mirror replacement '" + replacement + "'.", e);
            }

            URI resolved;
            try {
                resolved = URI.create(resolvedText);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Mirror replacement produced an invalid URI: " + resolvedText, e);
            }
            if (!resolved.isAbsolute() || !"https".equalsIgnoreCase(resolved.getScheme()) || resolved.getHost() == null || resolved.getFragment() != null) {
                throw new IllegalArgumentException("Mirror replacement must produce an absolute HTTPS URI without a fragment: " + resolvedText);
            }
            return resolved;
        }
    }
}
