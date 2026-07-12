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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GradleDistributionVersionResolverTest {
    @Test
    void completeVersionsDoNotUseTheVersionsService() throws Exception {
        try (VersionServer server = new VersionServer()) {
            GradleDistributionVersionResolver resolver = server.resolver();

            assertEquals("8.14", resolver.resolve("8.14"));
            assertEquals("8.12.1", resolver.resolve("8.12.1"));
            assertEquals("9.0.0", resolver.resolve("9.0.0"));
            assertEquals("9.0.0-rc-1", resolver.resolve("9.0.0-rc-1"));
            assertTrue(server.requestPaths().isEmpty());
        }
    }

    @Test
    void rejectsInvalidCompleteVersion() throws Exception {
        try (VersionServer server = new VersionServer()) {
            GradleException failure = assertThrows(
                GradleException.class,
                () -> server.resolver().resolve("invalid")
            );

            assertEquals(
                "Invalid version specified for argument '--gradle-version'",
                failure.getMessage()
            );
            assertTrue(server.requestPaths().isEmpty());
        }
    }

    @Test
    void resolvesAllStandardDynamicVersionSelectors() throws Exception {
        Map<String, String> selectors = new LinkedHashMap<>();
        selectors.put("latest", "current");
        selectors.put("release-candidate", "release-candidate");
        selectors.put("release-milestone", "milestone");
        selectors.put("release-nightly", "release-nightly");
        selectors.put("nightly", "nightly");

        try (VersionServer server = new VersionServer()) {
            for (String endpoint : selectors.values()) {
                server.respond("/versions/" + endpoint, "{\"version\":\"9.6.1\"}");
            }

            GradleDistributionVersionResolver resolver = server.resolver();
            for (Map.Entry<String, String> selector : selectors.entrySet()) {
                assertEquals("9.6.1", resolver.resolve(selector.getKey()));
            }

            List<String> expectedPaths = new ArrayList<>();
            for (String endpoint : selectors.values()) {
                expectedPaths.add("/versions/" + endpoint);
            }
            assertEquals(expectedPaths, server.requestPaths());
        }
    }

    @Test
    void resolvesGradleNineMajorAndMinorSelectorsToLatestFinalVersion() throws Exception {
        String versions = "["
                + "{\"version\":\"9.0.0\"},"
                + "{\"version\":\"9.1.1\"},"
                + "{\"version\":\"9.1.2\"},"
                + "{\"version\":\"9.2.1-rc-1\"},"
                + "{\"version\":\"9.3.3-milestone-2\"},"
                + "{\"version\":\"9.4.4-branch-main-20260101000000+0000\"},"
                + "{\"version\":\"10.0.0\"},"
                + "{\"version\":\"invalid versions are ignored\"}"
                + "]";

        try (VersionServer server = new VersionServer()) {
            server.respond("/versions/9", versions);
            GradleDistributionVersionResolver resolver = server.resolver();

            assertEquals("9.1.2", resolver.resolve("9"));
            assertEquals("9.1.2", resolver.resolve("9.1"));
            assertEquals(
                    java.util.Arrays.asList("/versions/9", "/versions/9"),
                    server.requestPaths()
            );
        }
    }

    @Test
    void reportsWhenSemanticSelectorHasNoFinalVersion() throws Exception {
        try (VersionServer server = new VersionServer()) {
            server.respond("/versions/9", "[{\"version\":\"9.1.2-rc-1\"}]");

            GradleException failure = assertThrows(
                    GradleException.class,
                    () -> server.resolver().resolve("9.1")
            );

            assertEquals(
                    "Invalid version specified for argument '--gradle-version': "
                            + "no final version found for version 9.1",
                    failure.getMessage()
            );
        }
    }

    @Test
    void reportsUnavailableAndIncompleteDynamicVersionResponses() throws Exception {
        try (VersionServer server = new VersionServer()) {
            server.respond("/versions/current", "{}");
            GradleDistributionVersionResolver resolver = server.resolver();

            GradleException incomplete = assertThrows(
                    GradleException.class,
                    () -> resolver.resolve("latest")
            );
            assertEquals(
                    "There is currently no version information available for 'latest'.",
                    incomplete.getMessage()
            );

            GradleException unavailable = assertThrows(
                    GradleException.class,
                    () -> resolver.resolve("release-candidate")
            );
            assertEquals(
                    "Unable to resolve Gradle version for 'release-candidate'.",
                    unavailable.getMessage()
            );
        }
    }

    private static final class VersionServer implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "wrapper-neo-version-server");
            thread.setDaemon(true);
            return thread;
        });
        private final Map<String, byte[]> responses = Collections.synchronizedMap(new LinkedHashMap<>());
        private final List<String> requestPaths = Collections.synchronizedList(new ArrayList<>());

        private VersionServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();
        }

        private GradleDistributionVersionResolver resolver() {
            return new GradleDistributionVersionResolver(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/",
                    5000
            );
        }

        private void respond(String path, String body) {
            responses.put(path, body.getBytes(StandardCharsets.UTF_8));
        }

        private List<String> requestPaths() {
            synchronized (requestPaths) {
                return new ArrayList<>(requestPaths);
            }
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getRawPath();
            requestPaths.add(path);
            byte[] response = responses.get(path);
            if (response == null) {
                response = new byte[0];
                exchange.sendResponseHeaders(404, response.length);
            } else {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
            }
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(response);
            }
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
