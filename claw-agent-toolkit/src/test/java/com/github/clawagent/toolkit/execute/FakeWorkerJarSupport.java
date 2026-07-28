package com.github.clawagent.toolkit.execute;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

public final class FakeWorkerJarSupport {
    private FakeWorkerJarSupport() {
    }

    public static Path createJar(Path dir, Class<?> mainClass) throws Exception {
        Files.createDirectories(dir);
        Path jar = dir.resolve(mainClass.getSimpleName() + ".jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Main-Class", mainClass.getName());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            String classEntry = mainClass.getName().replace('.', '/') + ".class";
            output.putNextEntry(new JarEntry(classEntry));
            try (InputStream input = mainClass.getClassLoader().getResourceAsStream(classEntry)) {
                if (input == null) {
                    throw new IllegalStateException("测试 fake worker class 不存在：" + classEntry);
                }
                input.transferTo(output);
            }
            output.closeEntry();
        }
        return jar;
    }
}
