package com.github.clawagent.toolkit.filesystem;

import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FilesystemSearchTextToolTest {
    @TempDir
    Path tempDir;

    @Test
    void javaEngineReturnsMatchingLineNumbers() throws Exception {
        Files.writeString(tempDir.resolve("Demo.java"), String.join(System.lineSeparator(),
                "class Demo {",
                "    void run() {",
                "        System.out.println(\"needle\");",
                "    }",
                "}"));
        FilesystemSearchTextTool tool = new FilesystemSearchTextTool(access());

        ToolResult result = tool.execute(call(Map.of(
                "path", tempDir.toString(),
                "query", "needle",
                "glob", "*.java",
                "engine", "java"
        )), null);

        assertTrue(result.success(), result.content());
        assertTrue(result.content().contains("engine: java"));
        assertTrue(result.content().contains("Demo.java:3:"));
    }

    @Test
    void unsupportedEngineReturnsClearError() throws Exception {
        Files.writeString(tempDir.resolve("Demo.java"), "class Demo { String value = \"needle\"; }");
        FilesystemSearchTextTool tool = new FilesystemSearchTextTool(access());

        ToolResult result = tool.execute(call(Map.of(
                "path", tempDir.toString(),
                "query", "needle",
                "engine", "unknown"
        )), null);

        assertTrue(!result.success());
        assertTrue(result.content().contains("不支持的搜索引擎"));
    }

    @Test
    void javaEngineSkipsIgnoredDirectories() throws Exception {
        Files.createDirectories(tempDir.resolve("node_modules/pkg"));
        Files.writeString(tempDir.resolve("node_modules/pkg/index.js"), "const value = 'needle';");
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/App.js"), "const value = 'needle';");
        FilesystemSearchTextTool tool = new FilesystemSearchTextTool(access());

        ToolResult result = tool.execute(call(Map.of(
                "path", tempDir.toString(),
                "query", "needle",
                "glob", "**/*.js",
                "engine", "java"
        )), null);

        assertTrue(result.success(), result.content());
        assertTrue(result.content().contains("src\\App.js:1:") || result.content().contains("src/App.js:1:"));
        assertTrue(!result.content().contains("node_modules"));
    }

    @Test
    void readTextFileCanReturnLineRangeWithLineNumbers() throws Exception {
        Files.writeString(tempDir.resolve("Demo.java"), String.join(System.lineSeparator(),
                "line 1",
                "line 2",
                "line 3",
                "line 4"));
        FilesystemReadTextTool tool = new FilesystemReadTextTool(access());

        ToolResult result = tool.execute(new ToolCall("builtin.filesystem.read_text_file", new LinkedHashMap<>(Map.of(
                "path", tempDir.resolve("Demo.java").toString(),
                "startLine", "2",
                "limit", "2",
                "showLineNumbers", "1"
        ))), null);

        assertTrue(result.success(), result.content());
        assertTrue(result.content().contains("2: line 2"));
        assertTrue(result.content().contains("3: line 3"));
    }

    private FilesystemAccess access() {
        FilesystemToolkitProperties properties = new FilesystemToolkitProperties();
        properties.setAllowedRoots(List.of(tempDir.toString()));
        properties.setDefaultCwd(tempDir.toString());
        return new FilesystemAccess(properties);
    }

    private ToolCall call(Map<String, String> arguments) {
        return new ToolCall("builtin.filesystem.search_text", new LinkedHashMap<>(arguments));
    }
}
