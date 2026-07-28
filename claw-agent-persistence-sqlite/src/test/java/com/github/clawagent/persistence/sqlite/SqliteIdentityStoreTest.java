package com.github.clawagent.persistence.sqlite;

import com.github.clawagent.spi.ApiTokenRecord;
import com.github.clawagent.spi.ChannelUserBindingRecord;
import com.github.clawagent.spi.DeviceRecord;
import com.github.clawagent.spi.LocalUserRecord;
import com.github.clawagent.spi.LocalUserSessionRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqliteIdentityStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsIdentityRecordsAcrossStoreInstances() {
        Path database = tempDir.resolve("identity.db");
        Instant now = Instant.parse("2026-07-08T02:00:00Z");

        LocalUserRecord user = new LocalUserRecord("user-1", "admin", "管理员", "owner", "active",
                now, null, now, "salt-1", "hash-1", Map.of("permissionMode", "auto"));
        LocalUserSessionRecord session = new LocalUserSessionRecord("session-1", "user-1", "admin", "管理员",
                "owner", "sess_", "session-hash", now, now.plusSeconds(3600), null, now);
        ApiTokenRecord token = new ApiTokenRecord("token-1", "Default", "ca_", "token-hash", "cla_plain", "active",
                "user-1", "admin", "custom", List.of("builtin.time"), List.of("tasks:write"),
                now, now.plusSeconds(86400), null, now, 3, "POST", "/api/v1/tasks", Map.of("source", "test"));
        DeviceRecord device = new DeviceRecord("device-1", "Windows Dev", "desktop", "active",
                now, now, null, now, "pair-hash", now.plusSeconds(300), "secret-hash", "dev_",
                "ask", List.of("builtin.filesystem.read_text_file"), Map.of("os", "windows"), "user-1", "admin");
        ChannelUserBindingRecord binding = new ChannelUserBindingRecord("binding-1", "feishu", "ou-user",
                "张三", "user-1", "admin", "active", now, now, Map.of("team", "dev"));

        // 先写入，再重新创建 Store 读取，验证真实 SQLite 表结构和序列化字段都能跨实例恢复。
        new SqliteLocalUserStore(database).write(List.of(user));
        new SqliteLocalUserSessionStore(database).write(List.of(session));
        new SqliteApiTokenStore(database).write(List.of(token));
        new SqliteDeviceStore(database).write(List.of(device));
        new SqliteChannelUserBindingStore(database).write(List.of(binding));

        assertEquals(List.of(user), new SqliteLocalUserStore(database).read());
        assertEquals(List.of(session), new SqliteLocalUserSessionStore(database).read());
        assertEquals(List.of(token), new SqliteApiTokenStore(database).read());
        assertEquals(List.of(device), new SqliteDeviceStore(database).read());
        assertEquals(List.of(binding), new SqliteChannelUserBindingStore(database).read());
    }
}
