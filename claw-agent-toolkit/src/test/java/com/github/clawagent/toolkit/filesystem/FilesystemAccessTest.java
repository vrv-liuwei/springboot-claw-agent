package com.github.clawagent.toolkit.filesystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilesystemAccessTest {
    @TempDir
    Path tempDir;

    @Test
    void resolvesRelativePathsFromConfiguredDefaultCwd() {
        Path workspace = tempDir.resolve("workspace");
        FilesystemToolkitProperties properties = new FilesystemToolkitProperties();
        properties.setReadonly(false);
        properties.setAllowedRoots(List.of(tempDir.toString()));
        properties.setDefaultCwd(workspace.toString());
        FilesystemAccess access = new FilesystemAccess(properties);

        Path resolved = access.resolveWritable("demo/pom.xml");

        assertEquals(workspace.resolve("demo/pom.xml").toAbsolutePath().normalize(), resolved);
        assertTrue(workspace.toFile().isDirectory());
    }
}
