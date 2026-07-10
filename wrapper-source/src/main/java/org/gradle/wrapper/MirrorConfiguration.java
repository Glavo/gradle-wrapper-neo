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

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

final class MirrorConfiguration {
    static final String PROPERTY_PREFIX = "gradle.wrapper.neo.mirrors.";
    private static final Pattern INDEX_PATTERN = Pattern.compile("0|[1-9][0-9]*");
    private static final Set<String> MIRROR_FIELDS = fields("enabled", "pattern", "replacement", "requireChecksum");

    private final List<Mirror> mirrors;

    private MirrorConfiguration(List<Mirror> mirrors) {
        this.mirrors = Collections.unmodifiableList(new ArrayList<>(mirrors));
    }

    static MirrorConfiguration empty() {
        return new MirrorConfiguration(Collections.emptyList());
    }

    static MirrorConfiguration fromSystemProperties(Map<?, ?> systemProperties) {
        Map<Integer, Map<String, String>> configuredMirrors = new TreeMap<>();
        for (Map.Entry<?, ?> entry : systemProperties.entrySet()) {
            String propertyName = String.valueOf(entry.getKey());
            if (!propertyName.startsWith(PROPERTY_PREFIX)) {
                continue;
            }

            String suffix = propertyName.substring(PROPERTY_PREFIX.length());
            int separator = suffix.indexOf('.');
            if (separator <= 0 || separator == suffix.length() - 1) {
                throw invalidPropertyName(propertyName);
            }

            String indexText = suffix.substring(0, separator);
            if (!INDEX_PATTERN.matcher(indexText).matches()) {
                throw invalidPropertyName(propertyName);
            }

            int index;
            try {
                index = Integer.parseInt(indexText);
            } catch (NumberFormatException e) {
                throw invalidPropertyName(propertyName);
            }

            String field = suffix.substring(separator + 1);
            if (!MIRROR_FIELDS.contains(field)) {
                throw new IllegalArgumentException("Unknown mirror property: " + propertyName);
            }
            if (!(entry.getValue() instanceof String)) {
                throw new IllegalArgumentException("Mirror property " + propertyName + " must be a string.");
            }

            Map<String, String> mirror = configuredMirrors.computeIfAbsent(index, ignored -> new TreeMap<>());
            mirror.put(field, (String) entry.getValue());
        }

        List<Mirror> mirrors = new ArrayList<>();
        for (Map.Entry<Integer, Map<String, String>> entry : configuredMirrors.entrySet()) {
            Mirror mirror = parseMirror(entry.getKey(), entry.getValue());
            if (mirror != null) {
                mirrors.add(mirror);
            }
        }
        return new MirrorConfiguration(mirrors);
    }

    List<URI> resolve(URI source, boolean checksumProvided) {
        LinkedHashSet<URI> result = new LinkedHashSet<>();
        for (Mirror mirror : mirrors) {
            URI resolved = mirror.resolve(source, checksumProvided);
            if (resolved != null && !resolved.equals(source)) {
                result.add(resolved);
            }
        }
        result.add(source);
        return new ArrayList<>(result);
    }

    private static Mirror parseMirror(int index, Map<String, String> properties) {
        String path = PROPERTY_PREFIX + index;
        if (!optionalBoolean(properties, "enabled", true, path)) {
            return null;
        }

        String patternText = required(properties, "pattern", path);
        Pattern pattern;
        try {
            pattern = Pattern.compile(patternText);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid regular expression in " + path + ".pattern: " + e.getDescription(), e);
        }

        String replacement = required(properties, "replacement", path);
        boolean requireChecksum = optionalBoolean(properties, "requireChecksum", true, path);
        return new Mirror(pattern, replacement, requireChecksum);
    }

    private static String required(Map<String, String> properties, String field, String path) {
        String value = properties.get(field);
        if (value == null) {
            throw new IllegalArgumentException("Missing required property " + path + "." + field + ".");
        }
        return value;
    }

    private static boolean optionalBoolean(Map<String, String> properties, String field, boolean defaultValue, String path) {
        String value = properties.get(field);
        if (value == null) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException(path + "." + field + " must be true or false.");
    }

    private static IllegalArgumentException invalidPropertyName(String propertyName) {
        return new IllegalArgumentException(
            "Invalid mirror property name '" + propertyName + "'. Expected " + PROPERTY_PREFIX + "<index>.<field>."
        );
    }

    private static Set<String> fields(String... values) {
        Set<String> result = new HashSet<>();
        Collections.addAll(result, values);
        return Collections.unmodifiableSet(result);
    }

    private static final class Mirror {
        private final Pattern pattern;
        private final String replacement;
        private final boolean requireChecksum;

        private Mirror(Pattern pattern, String replacement, boolean requireChecksum) {
            this.pattern = pattern;
            this.replacement = replacement;
            this.requireChecksum = requireChecksum;
        }

        private URI resolve(URI source, boolean checksumProvided) {
            if (requireChecksum && !checksumProvided) {
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
