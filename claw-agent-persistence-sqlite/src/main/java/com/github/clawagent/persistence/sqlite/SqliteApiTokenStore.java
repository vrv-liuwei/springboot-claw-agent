package com.github.clawagent.persistence.sqlite;

import com.github.clawagent.spi.ApiTokenRecord;
import com.github.clawagent.spi.ApiTokenStore;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * API Token SQLite DAO。
 * 本地管理台需要重新复制 Token；数据库保存明文 token，但鉴权仍只使用 hash，审计禁止记录明文。
 */
public class SqliteApiTokenStore extends SqliteIdentitySupport implements ApiTokenStore {
    public SqliteApiTokenStore(Path databasePath) {
        super(databasePath);
        execute("create table if not exists auth_api_token (" +
                "id text primary key, name text, token_prefix text, token_hash text, token text, status text, " +
                "owner_user_id text, owner_username text, permission_mode text, approved_tool_ids text, scopes text, " +
                "created_at text, expires_at text, revoked_at text, last_used_at text, usage_count integer, " +
                "last_used_method text, last_used_path text, metadata text)");
        ensureTokenColumn();
    }

    @Override
    public List<ApiTokenRecord> read() {
        try (Connection connection = connect();
             PreparedStatement ps = connection.prepareStatement("select * from auth_api_token order by created_at asc");
             ResultSet rs = ps.executeQuery()) {
            List<ApiTokenRecord> records = new ArrayList<>();
            while (rs.next()) {
                records.add(readRecord(rs));
            }
            return records;
        } catch (SQLException e) {
            throw new IllegalStateException("读取 SQLite API Token 失败", e);
        }
    }

    @Override
    public void write(List<ApiTokenRecord> records) {
        try (Connection connection = connect();
             PreparedStatement delete = connection.prepareStatement("delete from auth_api_token");
             PreparedStatement insert = connection.prepareStatement("insert into auth_api_token " +
                     "(id, name, token_prefix, token_hash, token, status, owner_user_id, owner_username, permission_mode, " +
                     "approved_tool_ids, scopes, created_at, expires_at, revoked_at, last_used_at, usage_count, " +
                     "last_used_method, last_used_path, metadata) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            connection.setAutoCommit(false);
            delete.executeUpdate();
            for (ApiTokenRecord record : records == null ? List.<ApiTokenRecord>of() : records) {
                bind(insert, record);
                insert.addBatch();
            }
            insert.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("保存 SQLite API Token 失败", e);
        }
    }

    private void bind(PreparedStatement ps, ApiTokenRecord record) throws SQLException {
        ps.setString(1, record.id());
        ps.setString(2, record.name());
        ps.setString(3, record.tokenPrefix());
        ps.setString(4, record.tokenHash());
        ps.setString(5, record.token());
        ps.setString(6, record.status());
        ps.setString(7, record.ownerUserId());
        ps.setString(8, record.ownerUsername());
        ps.setString(9, record.permissionMode());
        ps.setString(10, serializeList(record.approvedToolIds()));
        ps.setString(11, serializeList(record.scopes()));
        ps.setString(12, instant(record.createdAt()));
        ps.setString(13, instant(record.expiresAt()));
        ps.setString(14, instant(record.revokedAt()));
        ps.setString(15, instant(record.lastUsedAt()));
        ps.setLong(16, record.usageCount());
        ps.setString(17, record.lastUsedMethod());
        ps.setString(18, record.lastUsedPath());
        ps.setString(19, serializeMap(record.metadata()));
    }

    private ApiTokenRecord readRecord(ResultSet rs) throws SQLException {
        return new ApiTokenRecord(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("token_prefix"),
                rs.getString("token_hash"),
                rs.getString("token"),
                rs.getString("status"),
                rs.getString("owner_user_id"),
                rs.getString("owner_username"),
                rs.getString("permission_mode"),
                parseList(rs.getString("approved_tool_ids")),
                parseList(rs.getString("scopes")),
                parseInstant(rs.getString("created_at")),
                parseInstant(rs.getString("expires_at")),
                parseInstant(rs.getString("revoked_at")),
                parseInstant(rs.getString("last_used_at")),
                rs.getLong("usage_count"),
                rs.getString("last_used_method"),
                rs.getString("last_used_path"),
                parseMap(rs.getString("metadata")));
    }

    private void ensureTokenColumn() {
        try (Connection connection = connect();
             PreparedStatement ps = connection.prepareStatement("pragma table_info(auth_api_token)");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                if ("token".equalsIgnoreCase(rs.getString("name"))) {
                    return;
                }
            }
            try (Statement statement = connection.createStatement()) {
                // 兼容旧 SQLite 数据库：旧 token 无法反推出明文，只能从新增 token 开始支持列表复制完整值。
                statement.executeUpdate("alter table auth_api_token add column token text");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("迁移 SQLite API Token 表失败", e);
        }
    }
}
