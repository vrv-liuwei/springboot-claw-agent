package com.github.clawagent.toolkit.webfetch;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * WebFetch 工具配置。
 * 该配置由 ToolkitRegistry 从 tools.web-fetch.env 传入，避免 starter 感知具体工具参数。
 */
public class WebFetchToolkitProperties {
    private boolean allowPrivateAddresses = false;
    private Set<String> allowedHosts = new LinkedHashSet<>();
    private int maxOutputChars = 12000;

    public boolean isAllowPrivateAddresses() {
        return allowPrivateAddresses;
    }

    public void setAllowPrivateAddresses(boolean allowPrivateAddresses) {
        this.allowPrivateAddresses = allowPrivateAddresses;
    }

    public Set<String> getAllowedHosts() {
        return allowedHosts;
    }

    public void setAllowedHosts(Set<String> allowedHosts) {
        this.allowedHosts = allowedHosts == null ? new LinkedHashSet<>() : new LinkedHashSet<>(allowedHosts);
    }

    public int getMaxOutputChars() {
        return maxOutputChars;
    }

    public void setMaxOutputChars(int maxOutputChars) {
        this.maxOutputChars = maxOutputChars;
    }

    public boolean isAllowedHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        return allowedHosts.contains(host.trim().toLowerCase(Locale.ROOT));
    }

    public static WebFetchToolkitProperties fromEnv(Map<String, String> env) {
        WebFetchToolkitProperties properties = new WebFetchToolkitProperties();
        if (env == null || env.isEmpty()) {
            return properties;
        }
        Map<String, String> normalized = env.entrySet().stream()
                .filter(entry -> entry.getKey() != null)
                .collect(Collectors.toMap(
                        entry -> entry.getKey().trim().toUpperCase(Locale.ROOT).replace('-', '_'),
                        Map.Entry::getValue,
                        (left, right) -> right
                ));
        properties.setAllowPrivateAddresses(booleanValue(normalized.get("ALLOW_PRIVATE_ADDRESSES"), false));
        properties.setAllowedHosts(hostSet(normalized.get("ALLOWED_HOSTS")));
        properties.setMaxOutputChars(intValue(normalized.get("MAX_OUTPUT_CHARS"), 12000));
        return properties;
    }

    private static boolean booleanValue(String value, boolean defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private static Set<String> hostSet(String value) {
        if (value == null || value.isBlank()) {
            return new LinkedHashSet<>();
        }
        return Arrays.stream(value.split("[;,]"))
                .map(item -> item.trim().toLowerCase(Locale.ROOT))
                .filter(item -> !item.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static int intValue(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(value.trim());
    }
}
