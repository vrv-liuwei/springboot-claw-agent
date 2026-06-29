package com.github.clawagent.toolkit.execute;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectWorkingDirectoryResolverTest {
    @TempDir
    Path tempDir;

    @Test
    void returnsStructuredConfirmationWhenMultipleProjectsFound() throws Exception {
        createNpmProject("admin-api");
        createNpmProject("admin-web");
        ProjectWorkingDirectoryResolver resolver = new ProjectWorkingDirectoryResolver(properties());

        ProjectDirectoryResolutionException error = assertThrows(ProjectDirectoryResolutionException.class,
                () -> resolver.resolve(tempDir.toString(), "npm", List.of("run", "dev"), null));

        assertEquals("multiple-project-candidates", error.code());
        assertEquals(2, error.candidateProjects().size());
        assertTrue(error.getMessage().contains("requiresProjectConfirmation: true"));
        assertTrue(error.getMessage().contains("candidateProjects:"));
    }

    @Test
    void returnsStructuredConfirmationWhenProjectCannotBeFound() {
        ProjectWorkingDirectoryResolver resolver = new ProjectWorkingDirectoryResolver(properties());

        ProjectDirectoryResolutionException error = assertThrows(ProjectDirectoryResolutionException.class,
                () -> resolver.resolve(tempDir.toString(), "npm", List.of("run", "dev"), null));

        assertEquals("project-directory-not-runnable", error.code());
        assertTrue(error.getMessage().contains("nextAction: 请向用户确认"));
    }

    private void createNpmProject(String name) throws Exception {
        Path project = tempDir.resolve(name);
        Files.createDirectories(project);
        Files.writeString(project.resolve("package.json"), "{\"scripts\":{\"dev\":\"vite\"}}");
    }

    private ExecuteToolkitProperties properties() {
        ExecuteToolkitProperties properties = new ExecuteToolkitProperties();
        properties.setAllowedRoots(List.of(tempDir.toString()));
        properties.setDefaultCwd(tempDir.toString());
        return properties;
    }
}
