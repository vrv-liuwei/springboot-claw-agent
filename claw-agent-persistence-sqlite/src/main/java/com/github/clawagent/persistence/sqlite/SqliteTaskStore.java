package com.github.clawagent.persistence.sqlite;

import com.github.clawagent.core.AgentRequest;
import com.github.clawagent.core.AgentEvent;
import com.github.clawagent.core.AgentMessage;
import com.github.clawagent.core.AgentSession;
import com.github.clawagent.core.AgentStep;
import com.github.clawagent.core.AgentTask;
import com.github.clawagent.core.StepStatus;
import com.github.clawagent.core.StepType;
import com.github.clawagent.core.TaskStatus;
import com.github.clawagent.spi.AgentEventStore;
import com.github.clawagent.spi.SessionMessageStore;
import com.github.clawagent.spi.SessionStore;
import com.github.clawagent.spi.TaskStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SQLite 任务存储用于 ClawAgent 单机默认模式。
 * 这里直接用 JDBC，避免把 core/runtime 绑定到 Spring JDBC 或 JPA。
 */
public class SqliteTaskStore implements TaskStore, SessionStore, SessionMessageStore, AgentEventStore {
    private final String jdbcUrl;

    public SqliteTaskStore(Path databasePath) {
        try {
            Path parent = databasePath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            throw new IllegalStateException("创建 SQLite 数据目录失败：" + databasePath, e);
        }
        this.jdbcUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath();
        initialize();
    }

    private void initialize() {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table if not exists agent_task (" +
                    "id text primary key, input text, session_id text, channel_id text, user_id text, " +
                    "status text, final_answer text, created_at text, updated_at text)");
            statement.executeUpdate("create table if not exists agent_session (" +
                    "id text primary key, title text, channel_id text, user_id text, metadata text, summary text, " +
                    "created_at text, updated_at text, last_active_at text)");
            statement.executeUpdate("create table if not exists agent_message (" +
                    "id text primary key, session_id text, task_id text, role text, content text, metadata text, created_at text)");
            statement.executeUpdate("create table if not exists agent_step (" +
                    "id text primary key, task_id text, type text, name text, input text, output text, error text, " +
                    "status text, started_at text, finished_at text)");
            statement.executeUpdate("create table if not exists agent_event (" +
                    "id text primary key, session_id text, task_id text, level text, type text, message text, details text, created_at text)");
        } catch (SQLException e) {
            throw new IllegalStateException("初始化 SQLite 表结构失败", e);
        }
    }

    @Override
    public void saveTask(AgentTask task) {
        try (Connection connection = connect();
             PreparedStatement ps = connection.prepareStatement("insert or replace into agent_task values (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            bindTask(ps, task);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("保存任务失败：" + task.id(), e);
        }
    }

    @Override
    public void updateTask(AgentTask task) {
        saveTask(task);
    }

    @Override
    public void saveSession(AgentSession session) {
        try (Connection connection = connect();
             PreparedStatement ps = connection.prepareStatement("insert or replace into agent_session values (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            bindSession(ps, session);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("保存会话失败：" + session.id(), e);
        }
    }

    @Override
    public void updateSession(AgentSession session) {
        saveSession(session);
    }

    @Override
    public Optional<AgentSession> findSession(String sessionId) {
        try (Connection connection = connect();
             PreparedStatement ps = connection.prepareStatement("select * from agent_session where id = ?")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(readSession(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询会话失败：" + sessionId, e);
        }
    }

    @Override
    public List<AgentSession> listSessions(int limit) {
        try (Connection connection = connect();
             PreparedStatement ps = connection.prepareStatement("select * from agent_session order by last_active_at desc limit ?")) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                java.util.ArrayList<AgentSession> sessions = new java.util.ArrayList<>();
                while (rs.next()) {
                    sessions.add(readSession(rs));
                }
                return sessions;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询会话列表失败", e);
        }
    }

    @Override
    public void saveMessage(AgentMessage message) {
        try (Connection connection = connect();
             PreparedStatement ps = connection.prepareStatement("insert or replace into agent_message values (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, message.id());
            ps.setString(2, message.sessionId());
            ps.setString(3, message.taskId());
            ps.setString(4, message.role());
            ps.setString(5, message.content());
            ps.setString(6, serializeMap(message.metadata()));
            ps.setString(7, message.createdAt().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("保存会话消息失败：" + message.id(), e);
        }
    }

    @Override
    public List<AgentMessage> findMessages(String sessionId, int limit) {
        try (Connection connection = connect();
             PreparedStatement ps = connection.prepareStatement("select * from agent_message where session_id = ? order by created_at asc limit ?")) {
            ps.setString(1, sessionId);
            ps.setInt(2, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                java.util.ArrayList<AgentMessage> messages = new java.util.ArrayList<>();
                while (rs.next()) {
                    messages.add(new AgentMessage(
                            rs.getString("id"),
                            rs.getString("session_id"),
                            rs.getString("task_id"),
                            rs.getString("role"),
                            rs.getString("content"),
                            parseMap(rs.getString("metadata"))));
                }
                return messages;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询会话消息失败：" + sessionId, e);
        }
    }

    @Override
    public void saveEvent(AgentEvent event) {
        try (Connection connection = connect();
             PreparedStatement ps = connection.prepareStatement("insert or replace into agent_event values (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, event.id());
            ps.setString(2, event.sessionId());
            ps.setString(3, event.taskId());
            ps.setString(4, event.level());
            ps.setString(5, event.type());
            ps.setString(6, event.message());
            ps.setString(7, serializeMap(event.details()));
            ps.setString(8, event.createdAt().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("保存运行事件失败：" + event.id(), e);
        }
    }

    @Override
    public List<AgentEvent> findEventsBySession(String sessionId, int limit) {
        try (Connection connection = connect();
             PreparedStatement ps = connection.prepareStatement("select * from agent_event where session_id = ? order by created_at asc limit ?")) {
            ps.setString(1, sessionId);
            ps.setInt(2, Math.max(1, limit));
            return readEvents(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("查询会话运行事件失败：" + sessionId, e);
        }
    }

    @Override
    public List<AgentEvent> findEventsByTask(String taskId, int limit) {
        try (Connection connection = connect();
             PreparedStatement ps = connection.prepareStatement("select * from agent_event where task_id = ? order by created_at asc limit ?")) {
            ps.setString(1, taskId);
            ps.setInt(2, Math.max(1, limit));
            return readEvents(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("查询任务运行事件失败：" + taskId, e);
        }
    }

    @Override
    public Optional<AgentTask> findTask(String taskId) {
        try (Connection connection = connect();
             PreparedStatement ps = connection.prepareStatement("select * from agent_task where id = ?")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(readTask(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询任务失败：" + taskId, e);
        }
    }

    @Override
    public List<AgentTask> findTasksBySession(String sessionId, int limit) {
        try (Connection connection = connect();
             PreparedStatement ps = connection.prepareStatement("select * from agent_task where session_id = ? order by created_at desc limit ?")) {
            ps.setString(1, sessionId);
            ps.setInt(2, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                java.util.ArrayList<AgentTask> tasks = new java.util.ArrayList<>();
                while (rs.next()) {
                    tasks.add(readTask(rs));
                }
                return tasks;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询会话任务失败：" + sessionId, e);
        }
    }

    @Override
    public void saveStep(AgentStep step) {
        try (Connection connection = connect();
             PreparedStatement ps = connection.prepareStatement("insert or replace into agent_step values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, step.id());
            ps.setString(2, step.taskId());
            ps.setString(3, step.type().name());
            ps.setString(4, step.name());
            ps.setString(5, serializeMap(step.input()));
            ps.setString(6, step.output());
            ps.setString(7, step.error());
            ps.setString(8, step.status().name());
            ps.setString(9, step.startedAt().toString());
            ps.setString(10, step.finishedAt() == null ? null : step.finishedAt().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("保存步骤失败：" + step.id(), e);
        }
    }

    @Override
    public List<AgentStep> findSteps(String taskId) {
        try (Connection connection = connect();
             PreparedStatement ps = connection.prepareStatement("select * from agent_step where task_id = ? order by started_at")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                java.util.ArrayList<AgentStep> steps = new java.util.ArrayList<>();
                while (rs.next()) {
                    AgentStep step = new AgentStep(
                            rs.getString("id"),
                            rs.getString("task_id"),
                            StepType.valueOf(rs.getString("type")),
                            rs.getString("name"),
                            Map.of("raw", rs.getString("input")));
                    if (StepStatus.valueOf(rs.getString("status")) == StepStatus.SUCCEEDED) {
                        step.succeed(rs.getString("output"));
                    } else {
                        step.fail(rs.getString("error"));
                    }
                    steps.add(step);
                }
                return steps;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询步骤失败：" + taskId, e);
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    private List<AgentEvent> readEvents(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            java.util.ArrayList<AgentEvent> events = new java.util.ArrayList<>();
            while (rs.next()) {
                events.add(new AgentEvent(
                        rs.getString("id"),
                        rs.getString("session_id"),
                        rs.getString("task_id"),
                        rs.getString("level"),
                        rs.getString("type"),
                        rs.getString("message"),
                        parseMap(rs.getString("details")),
                        Instant.parse(rs.getString("created_at"))));
            }
            return events;
        }
    }

    private void bindTask(PreparedStatement ps, AgentTask task) throws SQLException {
        ps.setString(1, task.id());
        ps.setString(2, task.input());
        ps.setString(3, task.sessionId());
        ps.setString(4, task.channelId());
        ps.setString(5, task.userId());
        ps.setString(6, task.status().name());
        ps.setString(7, task.finalAnswer());
        ps.setString(8, task.createdAt().toString());
        ps.setString(9, Instant.now().toString());
    }

    private void bindSession(PreparedStatement ps, AgentSession session) throws SQLException {
        ps.setString(1, session.id());
        ps.setString(2, session.title());
        ps.setString(3, session.channelId());
        ps.setString(4, session.userId());
        ps.setString(5, serializeMap(session.metadata()));
        ps.setString(6, session.summary());
        ps.setString(7, session.createdAt().toString());
        ps.setString(8, session.updatedAt().toString());
        ps.setString(9, session.lastActiveAt().toString());
    }

    private AgentTask readTask(ResultSet rs) throws SQLException {
        AgentRequest request = new AgentRequest(
                rs.getString("input"),
                rs.getString("session_id"),
                rs.getString("channel_id"),
                rs.getString("user_id"),
                new LinkedHashMap<>());
        AgentTask task = new AgentTask(rs.getString("id"), request);
        task.markStatus(TaskStatus.valueOf(rs.getString("status")));
        if (rs.getString("final_answer") != null && task.status() == TaskStatus.COMPLETED) {
            task.complete(rs.getString("final_answer"));
        }
        return task;
    }

    private AgentSession readSession(ResultSet rs) throws SQLException {
        AgentSession session = new AgentSession(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("channel_id"),
                rs.getString("user_id"),
                parseMap(rs.getString("metadata")));
        if (rs.getString("summary") != null) {
            session.updateSummary(rs.getString("summary"));
        }
        return session;
    }

    private String serializeMap(Map<String, String> input) {
        StringBuilder builder = new StringBuilder();
        input.forEach((key, value) -> builder.append(key).append('=').append(value).append('\n'));
        return builder.toString();
    }

    private Map<String, String> parseMap(String value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value == null || value.isBlank()) {
            return result;
        }
        for (String line : value.split("\\R")) {
            int separator = line.indexOf('=');
            if (separator > 0) {
                result.put(line.substring(0, separator), line.substring(separator + 1));
            }
        }
        return result;
    }
}
