package com.github.clawagent.persistence.sqlite;

import com.github.clawagent.spi.LocalUserRecord;
import com.github.clawagent.spi.LocalUserStore;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地用户 SQLite DAO。
 * 只落密码 salt/hash，不保存明文密码。
 */
public class SqliteLocalUserStore extends SqliteIdentitySupport implements LocalUserStore {
    public SqliteLocalUserStore(Path databasePath) {
        super(databasePath);
        execute("create table if not exists auth_local_user (" +
                "id text primary key, username text unique, display_name text, role text, status text, " +
                "created_at text, disabled_at text, last_password_changed_at text, " +
                "password_salt text, password_hash text, metadata text)");
    }

    @Override
    public List<LocalUserRecord> read() {
        try (Connection connection = connect();
             PreparedStatement ps = connection.prepareStatement("select * from auth_local_user order by created_at asc");
             ResultSet rs = ps.executeQuery()) {
            List<LocalUserRecord> records = new ArrayList<>();
            while (rs.next()) {
                records.add(readRecord(rs));
            }
            return records;
        } catch (SQLException e) {
            throw new IllegalStateException("读取 SQLite 本地用户失败", e);
        }
    }

    @Override
    public void write(List<LocalUserRecord> records) {
        try (Connection connection = connect();
             PreparedStatement delete = connection.prepareStatement("delete from auth_local_user");
             PreparedStatement insert = connection.prepareStatement("insert into auth_local_user " +
                     "(id, username, display_name, role, status, created_at, disabled_at, last_password_changed_at, " +
                     "password_salt, password_hash, metadata) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            connection.setAutoCommit(false);
            delete.executeUpdate();
            for (LocalUserRecord record : records == null ? List.<LocalUserRecord>of() : records) {
                bind(insert, record);
                insert.addBatch();
            }
            insert.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("保存 SQLite 本地用户失败", e);
        }
    }

    private void bind(PreparedStatement ps, LocalUserRecord record) throws SQLException {
        ps.setString(1, record.id());
        ps.setString(2, record.username());
        ps.setString(3, record.displayName());
        ps.setString(4, record.role());
        ps.setString(5, record.status());
        ps.setString(6, instant(record.createdAt()));
        ps.setString(7, instant(record.disabledAt()));
        ps.setString(8, instant(record.lastPasswordChangedAt()));
        ps.setString(9, record.passwordSalt());
        ps.setString(10, record.passwordHash());
        ps.setString(11, serializeMap(record.metadata()));
    }

    private LocalUserRecord readRecord(ResultSet rs) throws SQLException {
        return new LocalUserRecord(
                rs.getString("id"),
                rs.getString("username"),
                rs.getString("display_name"),
                rs.getString("role"),
                rs.getString("status"),
                parseInstant(rs.getString("created_at")),
                parseInstant(rs.getString("disabled_at")),
                parseInstant(rs.getString("last_password_changed_at")),
                rs.getString("password_salt"),
                rs.getString("password_hash"),
                parseMap(rs.getString("metadata")));
    }
}
