package com.github.clawagent.persistence.sqlite;

import com.github.clawagent.spi.LocalUserSessionRecord;
import com.github.clawagent.spi.LocalUserSessionStore;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地登录会话 SQLite DAO。
 * 会话明文 token 不落库，只保存 hash 和前缀。
 */
public class SqliteLocalUserSessionStore extends SqliteIdentitySupport implements LocalUserSessionStore {
    public SqliteLocalUserSessionStore(Path databasePath) {
        super(databasePath);
        execute("create table if not exists auth_local_user_session (" +
                "id text primary key, user_id text, username text, display_name text, role text, " +
                "token_prefix text, token_hash text, created_at text, expires_at text, revoked_at text, last_used_at text)");
    }

    @Override
    public List<LocalUserSessionRecord> read() {
        try (Connection connection = connect();
             PreparedStatement ps = connection.prepareStatement("select * from auth_local_user_session order by created_at asc");
             ResultSet rs = ps.executeQuery()) {
            List<LocalUserSessionRecord> records = new ArrayList<>();
            while (rs.next()) {
                records.add(readRecord(rs));
            }
            return records;
        } catch (SQLException e) {
            throw new IllegalStateException("读取 SQLite 本地用户会话失败", e);
        }
    }

    @Override
    public void write(List<LocalUserSessionRecord> records) {
        try (Connection connection = connect();
             PreparedStatement delete = connection.prepareStatement("delete from auth_local_user_session");
             PreparedStatement insert = connection.prepareStatement("insert into auth_local_user_session " +
                     "(id, user_id, username, display_name, role, token_prefix, token_hash, created_at, expires_at, revoked_at, last_used_at) " +
                     "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            connection.setAutoCommit(false);
            delete.executeUpdate();
            for (LocalUserSessionRecord record : records == null ? List.<LocalUserSessionRecord>of() : records) {
                bind(insert, record);
                insert.addBatch();
            }
            insert.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("保存 SQLite 本地用户会话失败", e);
        }
    }

    private void bind(PreparedStatement ps, LocalUserSessionRecord record) throws SQLException {
        ps.setString(1, record.id());
        ps.setString(2, record.userId());
        ps.setString(3, record.username());
        ps.setString(4, record.displayName());
        ps.setString(5, record.role());
        ps.setString(6, record.tokenPrefix());
        ps.setString(7, record.tokenHash());
        ps.setString(8, instant(record.createdAt()));
        ps.setString(9, instant(record.expiresAt()));
        ps.setString(10, instant(record.revokedAt()));
        ps.setString(11, instant(record.lastUsedAt()));
    }

    private LocalUserSessionRecord readRecord(ResultSet rs) throws SQLException {
        return new LocalUserSessionRecord(
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getString("username"),
                rs.getString("display_name"),
                rs.getString("role"),
                rs.getString("token_prefix"),
                rs.getString("token_hash"),
                parseInstant(rs.getString("created_at")),
                parseInstant(rs.getString("expires_at")),
                parseInstant(rs.getString("revoked_at")),
                parseInstant(rs.getString("last_used_at")));
    }
}
