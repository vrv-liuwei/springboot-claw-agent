package com.github.clawagent.server.service;

import com.github.clawagent.spring.ClawAgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppWorkspaceServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void enrichWorkspaceMetadataUsesCurrentWorkspaceWhenNoWorkspaceIdProvided() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);

        ClawAgentProperties properties = new ClawAgentProperties();
        properties.getPersistence().getSqlite().setPath(tempDir.resolve("data").resolve("clawagent.db").toString());
        AppWorkspaceService service = new AppWorkspaceService(properties);
        service.openWorkspace(workspace.toString());

        Map<String, String> metadata = service.enrichWorkspaceMetadata("", Map.of("approvalMode", "ask"));

        // 计划创建接口通常不会显式传 workspaceId，后端必须回落到 App 当前工作区。
        assertEquals(workspace.toAbsolutePath().normalize().toString(), metadata.get("projectPath"));
        assertEquals(workspace.toAbsolutePath().normalize().toString(), metadata.get("activeProjectPath"));
        assertEquals("ask", metadata.get("approvalMode"));
    }
}
