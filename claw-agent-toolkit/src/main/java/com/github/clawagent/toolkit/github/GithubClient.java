package com.github.clawagent.toolkit.github;

import com.github.clawagent.core.http.AgentHttpClient;
import com.github.clawagent.core.http.AgentHttpClient.AgentHttpResponse;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GitHub 专用 HTTP 客户端。
 */
final class GithubClient {
    private static final int DEFAULT_TIMEOUT_MS = 60_000;

    private GithubClient() {
    }

    static AgentHttpResponse tree(GithubRepo repo, int timeoutMs) {
        String url = "https://api.github.com/repos/" + repo.owner() + "/" + repo.name()
                + "/git/trees/" + repo.branch() + "?recursive=1";
        return AgentHttpClient.get(url, githubHeaders(), timeout(timeoutMs));
    }

    static AgentHttpResponse rawFile(GithubRepo repo, String path, int timeoutMs) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path 不能为空");
        }
        String normalizedPath = path.replace('\\', '/').replaceAll("^/+", "");
        String url = "https://raw.githubusercontent.com/" + repo.owner() + "/" + repo.name()
                + "/" + repo.branch() + "/" + normalizedPath;
        return AgentHttpClient.get(url, Map.of(), timeout(timeoutMs));
    }

    private static Map<String, String> githubHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/vnd.github+json");
        headers.put("X-GitHub-Api-Version", "2022-11-28");
        return headers;
    }

    private static int timeout(int timeoutMs) {
        return timeoutMs <= 0 ? DEFAULT_TIMEOUT_MS : timeoutMs;
    }
}
