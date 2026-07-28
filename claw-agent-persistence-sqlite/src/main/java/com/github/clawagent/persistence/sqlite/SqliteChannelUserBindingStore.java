package com.github.clawagent.persistence.sqlite;

import com.github.clawagent.spi.ChannelUserBindingRecord;
import com.github.clawagent.spi.ChannelUserBindingStore;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Channel 外部用户绑定 SQLite DAO。
 * 用于把飞书、钉钉、DDIO 等外部 userId 映射到本地用户和本地权限策略。
 */
public class SqliteChannelUserBindingStore extends SqliteIdentitySupport implements ChannelUserBindingStore {
    public SqliteChannelUserBindingStore(Path databasePath) {
        super(databasePath);
        execute("create table if not exists auth_channel_user_binding (" +
                "id text primary key, channel_id text, external_user_id text, external_username text, " +
                "local_user_id text, local_username text, status text, created_at text, updated_at text, metadata text)");
    }

    @Override
    public List<ChannelUserBindingRecord> read() {
        try (Connection connection = connect();
             PreparedStatement ps = connection.prepareStatement("select * from auth_channel_user_binding order by updated_at desc");
             ResultSet rs = ps.executeQuery()) {
            List<ChannelUserBindingRecord> records = new ArrayList<>();
            while (rs.next()) {
                records.add(readRecord(rs));
            }
            return records;
        } catch (SQLException e) {
            throw new IllegalStateException("读取 SQLite Channel 用户绑定失败", e);
        }
    }

    @Override
    public void write(List<ChannelUserBindingRecord> records) {
        try (Connection connection = connect();
             PreparedStatement delete = connection.prepareStatement("delete from auth_channel_user_binding");
             PreparedStatement insert = connection.prepareStatement("insert into auth_channel_user_binding " +
                     "(id, channel_id, external_user_id, external_username, local_user_id, local_username, status, created_at, updated_at, metadata) " +
                     "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            connection.setAutoCommit(false);
            delete.executeUpdate();
            for (ChannelUserBindingRecord record : records == null ? List.<ChannelUserBindingRecord>of() : records) {
                bind(insert, record);
                insert.addBatch();
            }
            insert.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("保存 SQLite Channel 用户绑定失败", e);
        }
    }

    private void bind(PreparedStatement ps, ChannelUserBindingRecord record) throws SQLException {
        ps.setString(1, record.id());
        ps.setString(2, record.channelId());
        ps.setString(3, record.externalUserId());
        ps.setString(4, record.externalUsername());
        ps.setString(5, record.localUserId());
        ps.setString(6, record.localUsername());
        ps.setString(7, record.status());
        ps.setString(8, instant(record.createdAt()));
        ps.setString(9, instant(record.updatedAt()));
        ps.setString(10, serializeMap(record.metadata()));
    }

    private ChannelUserBindingRecord readRecord(ResultSet rs) throws SQLException {
        return new ChannelUserBindingRecord(
                rs.getString("id"),
                rs.getString("channel_id"),
                rs.getString("external_user_id"),
                rs.getString("external_username"),
                rs.getString("local_user_id"),
                rs.getString("local_username"),
                rs.getString("status"),
                parseInstant(rs.getString("created_at")),
                parseInstant(rs.getString("updated_at")),
                parseMap(rs.getString("metadata")));
    }
}
