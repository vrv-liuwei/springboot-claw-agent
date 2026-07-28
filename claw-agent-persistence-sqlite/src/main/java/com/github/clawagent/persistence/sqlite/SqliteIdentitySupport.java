package com.github.clawagent.persistence.sqlite;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Auth/Device 轻量 SQLite DAO 的公共能力。
 * 这里不引入 Spring JDBC/JPA，保持和现有 SqliteTaskStore 一样的单机 JDBC 风格。
 */
abstract class SqliteIdentitySupport {
    private final String jdbcUrl;

    SqliteIdentitySupport(Path databasePath) {
        try {
            Path parent = databasePath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            throw new IllegalStateException("创建 SQLite 数据目录失败：" + databasePath, e);
        }
        this.jdbcUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath();
    }

    Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    void execute(String sql) {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("初始化 SQLite Auth 表失败", e);
        }
    }

    String instant(Instant value) {
        return value == null ? null : value.toString();
    }

    Instant parseInstant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    String serializeList(List<String> values) {
        Properties properties = new Properties();
        List<String> safeValues = values == null ? List.of() : values;
        properties.setProperty("count", String.valueOf(safeValues.size()));
        for (int i = 0; i < safeValues.size(); i++) {
            properties.setProperty("item." + i, safeValues.get(i) == null ? "" : safeValues.get(i));
        }
        return storeProperties(properties);
    }

    List<String> parseList(String value) {
        Properties properties = loadProperties(value);
        int count = parseInt(properties.getProperty("count"), 0);
        ArrayList<String> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String item = properties.getProperty("item." + i, "");
            if (!item.isBlank()) {
                result.add(item);
            }
        }
        return result;
    }

    String serializeMap(Map<String, String> input) {
        Properties properties = new Properties();
        Map<String, String> safeInput = input == null ? Map.of() : input;
        safeInput.forEach((key, value) -> properties.setProperty(key, value == null ? "" : value));
        return storeProperties(properties);
    }

    Map<String, String> parseMap(String value) {
        Map<String, String> result = new LinkedHashMap<>();
        Properties properties = loadProperties(value);
        properties.forEach((key, entryValue) -> result.put(String.valueOf(key), String.valueOf(entryValue)));
        return result;
    }

    private String storeProperties(Properties properties) {
        try (StringWriter writer = new StringWriter()) {
            properties.store(writer, null);
            return writer.toString();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("序列化 SQLite Auth 属性失败", e);
        }
    }

    private Properties loadProperties(String value) {
        Properties properties = new Properties();
        if (value == null || value.isBlank()) {
            return properties;
        }
        try (StringReader reader = new StringReader(value)) {
            properties.load(reader);
        } catch (java.io.IOException | IllegalArgumentException ignored) {
            // Auth 新表没有历史明文格式；遇到坏数据返回空集合，避免管理页整体不可用。
        }
        return properties;
    }

    private int parseInt(String value, int fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
