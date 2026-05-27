package com.github.clawagent.toolkit.content;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentArtifactStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void saveAndReadSummaryAndQueryChunk() {
        ContentArtifactProperties properties = new ContentArtifactProperties();
        properties.setPath(tempDir);
        properties.setChunkChars(80);
        properties.setSummaryChars(800);
        ContentArtifactStore store = new ContentArtifactStore(properties);

        ContentArtifact artifact = store.save(
                "web",
                "https://example.test/readme",
                "text/markdown",
                "# Demo\n\n## Installation\nRun npm install.\n\n## Usage\nUse API_KEY to configure search.\n",
                "# Demo\n\n## Installation\nRun npm install.\n\n## Usage\nUse API_KEY to configure search.\n");

        assertTrue(Files.exists(tempDir.resolve("web").resolve(artifact.artifactId()).resolve("metadata.json")));
        assertTrue(store.read(artifact.artifactId(), null, null, 2000).contains("Installation"));
        assertTrue(store.read(artifact.artifactId(), null, "API_KEY", 2000).contains("API_KEY"));
    }
}
