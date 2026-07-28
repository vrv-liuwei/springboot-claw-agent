package com.github.clawagent.persistence.sqlite;

import com.github.clawagent.spi.DeviceRecord;
import com.github.clawagent.spi.DeviceStore;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Device Pairing SQLite DAO。
 * 配对码和设备密钥只保存 hash/前缀，明文只在创建或配对响应中返回。
 */
public class SqliteDeviceStore extends SqliteIdentitySupport implements DeviceStore {
    public SqliteDeviceStore(Path databasePath) {
        super(databasePath);
        execute("create table if not exists auth_device (" +
                "id text primary key, name text, type text, status text, first_seen_at text, last_seen_at text, " +
                "revoked_at text, paired_at text, pairing_code_hash text, pairing_code_expires_at text, " +
                "device_secret_hash text, device_secret_prefix text, permission_mode text, approved_tool_ids text, " +
                "metadata text, bound_user_id text, bound_username text)");
    }

    @Override
    public List<DeviceRecord> read() {
        try (Connection connection = connect();
             PreparedStatement ps = connection.prepareStatement("select * from auth_device order by first_seen_at asc");
             ResultSet rs = ps.executeQuery()) {
            List<DeviceRecord> records = new ArrayList<>();
            while (rs.next()) {
                records.add(readRecord(rs));
            }
            return records;
        } catch (SQLException e) {
            throw new IllegalStateException("读取 SQLite 设备失败", e);
        }
    }

    @Override
    public void write(List<DeviceRecord> records) {
        try (Connection connection = connect();
             PreparedStatement delete = connection.prepareStatement("delete from auth_device");
             PreparedStatement insert = connection.prepareStatement("insert into auth_device " +
                     "(id, name, type, status, first_seen_at, last_seen_at, revoked_at, paired_at, pairing_code_hash, " +
                     "pairing_code_expires_at, device_secret_hash, device_secret_prefix, permission_mode, approved_tool_ids, " +
                     "metadata, bound_user_id, bound_username) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            connection.setAutoCommit(false);
            delete.executeUpdate();
            for (DeviceRecord record : records == null ? List.<DeviceRecord>of() : records) {
                bind(insert, record);
                insert.addBatch();
            }
            insert.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("保存 SQLite 设备失败", e);
        }
    }

    private void bind(PreparedStatement ps, DeviceRecord record) throws SQLException {
        ps.setString(1, record.id());
        ps.setString(2, record.name());
        ps.setString(3, record.type());
        ps.setString(4, record.status());
        ps.setString(5, instant(record.firstSeenAt()));
        ps.setString(6, instant(record.lastSeenAt()));
        ps.setString(7, instant(record.revokedAt()));
        ps.setString(8, instant(record.pairedAt()));
        ps.setString(9, record.pairingCodeHash());
        ps.setString(10, instant(record.pairingCodeExpiresAt()));
        ps.setString(11, record.deviceSecretHash());
        ps.setString(12, record.deviceSecretPrefix());
        ps.setString(13, record.permissionMode());
        ps.setString(14, serializeList(record.approvedToolIds()));
        ps.setString(15, serializeMap(record.metadata()));
        ps.setString(16, record.boundUserId());
        ps.setString(17, record.boundUsername());
    }

    private DeviceRecord readRecord(ResultSet rs) throws SQLException {
        return new DeviceRecord(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("type"),
                rs.getString("status"),
                parseInstant(rs.getString("first_seen_at")),
                parseInstant(rs.getString("last_seen_at")),
                parseInstant(rs.getString("revoked_at")),
                parseInstant(rs.getString("paired_at")),
                rs.getString("pairing_code_hash"),
                parseInstant(rs.getString("pairing_code_expires_at")),
                rs.getString("device_secret_hash"),
                rs.getString("device_secret_prefix"),
                rs.getString("permission_mode"),
                parseList(rs.getString("approved_tool_ids")),
                parseMap(rs.getString("metadata")),
                rs.getString("bound_user_id"),
                rs.getString("bound_username"));
    }
}
