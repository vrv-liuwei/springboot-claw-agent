package com.github.clawagent.toolkit.github;

import java.net.URI;

/**
 * GitHub 仓库坐标。
 */
record GithubRepo(String owner, String name, String branch) {
    static GithubRepo parse(String urlOrRepo, String branch) {
        if (urlOrRepo == null || urlOrRepo.isBlank()) {
            throw new IllegalArgumentException("repoUrl 不能为空");
        }
        String value = urlOrRepo.trim();
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            String[] parts = value.replace(".git", "").split("/");
            if (parts.length < 2) {
                throw new IllegalArgumentException("repoUrl 需要是 owner/repo 或 GitHub 仓库 URL");
            }
            return new GithubRepo(parts[0], parts[1], defaultBranch(branch));
        }
        String[] parts = URI.create(value.replace(".git", "")).getPath().replaceAll("^/+", "").split("/");
        if (parts.length < 2) {
            throw new IllegalArgumentException("不是有效 GitHub 仓库 URL：" + urlOrRepo);
        }
        return new GithubRepo(parts[0], parts[1], defaultBranch(branch));
    }

    private static String defaultBranch(String branch) {
        return branch == null || branch.isBlank() ? "main" : branch.trim();
    }
}
