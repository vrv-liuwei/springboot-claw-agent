package com.github.clawagent.server.support;

import com.github.clawagent.persistence.sqlite.SqliteApiTokenStore;
import com.github.clawagent.persistence.sqlite.SqliteChannelUserBindingStore;
import com.github.clawagent.persistence.sqlite.SqliteDeviceStore;
import com.github.clawagent.persistence.sqlite.SqliteLocalUserSessionStore;
import com.github.clawagent.persistence.sqlite.SqliteLocalUserStore;
import com.github.clawagent.server.service.ApiTokenService;
import com.github.clawagent.server.service.ChannelUserBindingService;
import com.github.clawagent.server.service.DeviceRegistryService;
import com.github.clawagent.server.service.LocalUserService;
import com.github.clawagent.server.service.LocalUserSessionService;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 身份相关测试统一走 SQLite Store，避免测试继续依赖旧 JSON 文件构造入口。
 */
public final class TestIdentityStores {
    private TestIdentityStores() {
    }

    public static Path databasePath(Path tempDir) {
        return tempDir.resolve("identity.db");
    }

    public static ApiTokenService apiTokenService(Path tempDir) {
        return new ApiTokenService(new SqliteApiTokenStore(databasePath(tempDir)));
    }

    public static LocalUserService localUserService(Path tempDir) {
        return new LocalUserService(new SqliteLocalUserStore(databasePath(tempDir)));
    }

    public static LocalUserSessionService localUserSessionService(Path tempDir, LocalUserService userService) {
        return localUserSessionService(tempDir, userService, null);
    }

    public static LocalUserSessionService localUserSessionService(Path tempDir, LocalUserService userService, Duration ttl) {
        return new LocalUserSessionService(new SqliteLocalUserSessionStore(databasePath(tempDir)), userService, ttl);
    }

    public static DeviceRegistryService deviceRegistryService(Path tempDir) {
        return new DeviceRegistryService(new SqliteDeviceStore(databasePath(tempDir)));
    }

    public static ChannelUserBindingService channelUserBindingService(Path tempDir) {
        return new ChannelUserBindingService(new SqliteChannelUserBindingStore(databasePath(tempDir)));
    }
}
