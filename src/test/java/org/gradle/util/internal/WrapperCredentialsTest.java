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
package org.gradle.util.internal;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WrapperCredentialsTest {
    @Test
    void findsCredentials() throws Exception {
        assertFindsCredentials("my.company1.com", wrapperUserAndPassKeys(1), testBasicUserInfo(1));
        assertFindsCredentials("my.company1.com", wrapperTokenKeys(1), testTokenInfo(1));
        assertFindsCredentials("my.company1.com", wrapperUserAndPassKeys(1, "my_company1_com"), testBasicUserInfo(1));
        assertFindsCredentials("my.company1.com", wrapperTokenKeys(1, "my_company1_com"), testTokenInfo(1));
        assertFindsCredentials("MY.Company1.com", wrapperUserAndPassKeys(1, "my_company1_com"), testBasicUserInfo(1));
        assertFindsCredentials("MY.Company1.com", wrapperTokenKeys(1, "my_company1_com"), testTokenInfo(1));
        assertFindsCredentials("my.company1.com", merge(wrapperUserAndPassKeys(1), wrapperUserAndPassKeys(2, "my_company1_com")), testBasicUserInfo(2));
        assertFindsCredentials("my.company1.com", merge(wrapperTokenKeys(1), wrapperTokenKeys(2, "my_company1_com")), testTokenInfo(2));
        assertFindsCredentials("my.company1.com", merge(wrapperUserAndPassKeys(1), wrapperTokenKeys(2)), testTokenInfo(2));
        assertFindsCredentials("my.company1.com", merge(wrapperUserAndPassKeys(1, "my_company1_com"), wrapperTokenKeys(2)), testTokenInfo(2));
        assertFindsCredentials("my.company1.com", merge(wrapperUserAndPassKeys(1), wrapperTokenKeys(2, "my_company1_com")), testTokenInfo(2));
        assertFindsCredentials("my.company1.com", merge(wrapperUserAndPassKeys(1, "my_company1_com"), wrapperTokenKeys(2, "my_company1_com")), testTokenInfo(2));
        assertFindsCredentials("TestUser1:TestPassword1@my.company1.com", Map.of(), testBasicUserInfo(1));
        assertFindsCredentials("TestUser1:TestPassword1@my.company1.com", wrapperTokenKeys(2), testTokenInfo(2));
    }

    private static void assertFindsCredentials(String host, Map<String, String> properties, WrapperCredentials expected) throws Exception {
        WrapperCredentials actual = WrapperCredentials.findCredentials(new URI("https://" + host + "/dists/gradle-x.zip"), properties::get);
        assertEquals(expected, actual);
    }

    private static WrapperCredentials testBasicUserInfo(int index) {
        return WrapperCredentials.fromBasicUserInfo("TestUser" + index + ":TestPassword" + index);
    }

    private static WrapperCredentials testTokenInfo(int index) {
        return WrapperCredentials.fromToken("TestToken" + index);
    }

    private static Map<String, String> wrapperUserAndPassKeys(int index) {
        return wrapperUserAndPassKeys(index, null);
    }

    private static Map<String, String> wrapperUserAndPassKeys(int index, String host) {
        Map<String, String> values = new HashMap<>();
        values.put(sysPropertyKey(host, "wrapperUser"), "TestUser" + index);
        values.put(sysPropertyKey(host, "wrapperPassword"), "TestPassword" + index);
        return values;
    }

    private static Map<String, String> wrapperTokenKeys(int index) {
        return wrapperTokenKeys(index, null);
    }

    private static Map<String, String> wrapperTokenKeys(int index, String host) {
        return Map.of(sysPropertyKey(host, "wrapperToken"), "TestToken" + index);
    }

    private static String sysPropertyKey(String host, String key) {
        return host != null ? "gradle." + host + "." + key : "gradle." + key;
    }

    private static Map<String, String> merge(Map<String, String> first, Map<String, String> second) {
        Map<String, String> result = new HashMap<>(first);
        result.putAll(second);
        return result;
    }
}
