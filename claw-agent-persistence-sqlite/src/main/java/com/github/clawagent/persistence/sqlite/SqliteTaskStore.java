package com.github.clawagent.persistence.sqlite;

import com.github.clawagent.core.AgentRequest;
import com.github.clawagent.core.AgentEvent;
import com.github.clawagent.core.AgentMessage;
import com.github.clawagent.core.AgentSession;
import com.github.clawagent.core.AgentStep;
import com.github.clawagent.core.AgentTask;
import com.github.clawagent.core.AutomationDefinition;
import com.github.clawagent.core.AutomationRun;
import com.github.clawagent.core.AutomationRunStatus;
import com.github.clawagent.core.AutomationScheduleType;
import com.github.clawagent.core.AutomationStatus;
import com.github.clawagent.core.StepStatus;
import com.github.clawagent.core.StepType;
import com.github.clawagent.core.TaskStatus;
import com.github.clawagent.core.TodoItem;
import com.github.clawagent.spi.AgentDataCleaner;
import com.github.clawagent.spi.AgentEventStore;
import com.github.clawagent.spi.AutomationStore;
import com.github.clawagent.spi.SessionMessageStore;
import com.github.clawagent.spi.SessionStore;
import com.github.clawagent.spi.TaskStore;
import com.github.clawagent.spi.TodoStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.StringReader;
import java.io.StringWriter;
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
import java.util.Properties;

/**
 * SQLite 任务存储用于 ClawAgent 单机默认模式。
 * 这里直接用 JDBC，避免把 core/runtime 绑定到 Spring JDBC 或 JPA。
 */
public class SqliteTaskStore implements TaskStore, SessionStore, SessionMessageStore, AgentEventStore, TodoStore, AutomationStore, AgentDataCleaner {
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
            statement.executeUpdate("create table if not exists agent_todo_item (" +
                    "id text primary key, session_id text, task_id text, item_order integer, title text, description text, " +
                    "status text, metadata text, created_at text, updated_at text)");
            statement.executeUpdate("create table if not exists agent_automation (" +
                    "id text primary key, name text, prompt text, session_id text, channel_id text, user_id text, " +
                    "schedule_type text, cron_expression text, interval_seconds integer, timezone text, " +
                    "next_run_at text, last_run_at text, status text, metadata text, created_at text, updated_at text)");
            statement.executeUpdate("create table if not exists agent_automation_run (" +
                    "id text primary key, automation_id text, task_id text, status text, started_at text, finished_at text, error text)");
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
    public void clearAllAgentData() {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            // 删除顺序从明细到会话，避免未来增加外键后出现约束问题。
            statement.executeUpdate("delete from agent_todo_item");
            statement.executeUpdate("delete from agent_event");
            statement.executeUpdate("delete from agent_step");
            statement.executeUpdate("delete from agent_message");
            statement.executeUpdate("delete from agent_task");
            statement.executeUpdate("delete from agent_session");
        } catch (SQLException e) {
            throw new IllegalStateException("清空 SQLite 会话数据失败", e);
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
                            parseMap(rs.getString("metadata")),
                            Instant.parse(rs.getString("created_at"))));
                }
                return messages;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询会话消息失败：" + sessionId, e);
        }
    }

    @Override
    public List<AgentMessage> findMessagesByTask(String taskId, int limit) {
        try (Connection connection = connect();
             PreparedStatement ps = connection.prepareStatement("select * from agent_message where task_id = ? order by created_at asc limit ?")) {
            ps.setString(1, taskId);
            ps.setInt(2, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                java.util.ArrayList<AgentMessage> messages = new java.util.ArrayList<>();
                while (rs.next()) {
                    // 任务详情弹窗按 task_id 精确查询，避免展示同一会话下其它轮次消息。
                    messages.add(new AgentMessage(
                            rs.getString("id"),
                            rs.getString("session_id"),
                            rs.getString("task_id"),
                            rs.getString("role"),
                            rs.getString("content"),
                            parseMap(rs.getString("metadata")),
                            Instant.parse(rs.getString("created_at"))));
                }
                return messages;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询任务消息失败：" + taskId, e);
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
                            parseMap(rs.getString("input")),
                            parseInstant(rs.getString("started_at")),
                            parseInstant(rs.getString("finished_at")),
                            StepStatus.valueOf(rs.getString("status")),
                            rs.getString("output"),
                            rs.getString("error"));
                    steps.add(step);
                }
                return steps;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询步骤失败：" + taskId, e);
        }
    }

    @Override
    public void saveTodoItems(List<TodoItem> items) {
        try (Connection connection = connect();
             PreparedStatement ps = connection.prepareStatement("insert or replace into agent_todo_item values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (TodoItem item : items) {
                ps.setString(1, item.id());
                ps.setString(2, item.sessionId());
                ps.setString(3, item.taskId());
                ps.setInt(4, item.itemOrder());
                ps.setString(5, item.title());
                ps.setString(6, item.description());
                ps.setString(7, item.status());
                ps.setString(8, serializeMap(item.metadata()));
                ps.setString(9, item.createdAt().toString());
                ps.setString(10, item.updatedAt().toString());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException("保存 Todo 失败", e);
        }
    }

    @Override
    public Optional<TodoItem> findTodoItem(String id) {
        try (Connection connection = connect();
             PreparedStatement ps = connection.prepareStatement("select * from agent_todo_item where id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(readTodoItem(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询 Todo 失败：" + id, e);
        }
    }

    @Override
    public List<TodoItem> listTodoItems(String sessionId, String taskId, int limit) {
        StringBuilder sql = new StringBuilder("select * from agent_todo_item where 1=1");
        java.util.ArrayList<String> args = new java.util.ArrayList<>();
        if (sessionId != null && !sessionId.isBlank()) {
            sql.append(" and session_id = ?");
            args.add(sessionId);
        }
        if (taskId != null && !taskId.isBlank()) {
            sql.append(" and task_id = ?");
            args.add(taskId);
        }
        sql.append(" order by created_at asc, item_order asc limit ?");
        try (Connection connection = connect();
             PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) {
                ps.setString(i + 1, args.get(i));
            }
            ps.setInt(args.size() + 1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                java.util.ArrayList<TodoItem> items = new java.util.ArrayList<>();
                while (rs.next()) {
                    items.add(readTodoItem(rs));
                }
                return items;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询 Todo 列表失败", e);
        }
    }

    @Override
    public TodoItem updateTodoStatus(String id, String status) {
        TodoItem old = findTodoItem(id).orElseThrow(() -> new IllegalArgumentException("Todo 不存在：" + id));
        TodoItem updated = new TodoItem(old.id(), old.sessionId(), old.taskId(), old.itemOrder(), old.title(),
                old.description(), status, old.metadata(), old.createdAt(), Instant.now());
        saveTodoItems(List.of(updated));
        return updated;
    }

    @Override
    public void saveAutomation(AutomationDefinition automation) {
        try (Connection connection = connect();
             PreparedStatement ps = connection.prepareStatement("insert or replace into agent_automation values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            bindAutomation(ps, automation);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("保存自动化任务失败：" + automation.id(), e);
        }
    }

    @Override
    public Optional<AutomationDefinition> findAutomation(String id) {
        try (Connection connection = connect();
             PreparedStatement ps = connection.prepareStatement("select * from agent_automation where id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(readAutomation(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询自动化任务失败：" + id, e);
        }
    }

    @Override
    public List<AutomationDefinition> listAutomations(int limit) {
        try (Connection connection = connect();
             PreparedStatement ps = connection.prepareStatement("select * from agent_automation order by updated_at desc limit ?")) {
            ps.setInt(1, Math.max(1, limit));
            return readAutomations(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("查询自动化任务列表失败", e);
        }
    }

    @Override
    public List<AutomationDefinition> listDueAutomations(Instant now, int limit) {
        try (Connection connection = connect();
             PreparedStatement ps = connection.prepareStatement("select * from agent_automation where status = ? and next_run_at is not null and next_run_at <= ? order by next_run_at asc limit ?")) {
            ps.setString(1, AutomationStatus.ENABLED.name());
            ps.setString(2, now.toString());
            ps.setInt(3, Math.max(1, limit));
            return readAutomations(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("查询到期自动化任务失败", e);
        }
    }

    @Override
    public void deleteAutomation(String id) {
        try (Connection connection = connect()) {
            try (PreparedStatement ps = connection.prepareStatement("delete from agent_automation_run where automation_id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement("delete from agent_automation where id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("删除自动化任务失败：" + id, e);
        }
    }

    @Override
    public void saveAutomationRun(AutomationRun run) {
        try (Connection connection = connect();
             PreparedStatement ps = connection.prepareStatement("insert or replace into agent_automation_run values (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, run.id());
            ps.setString(2, run.automationId());
            ps.setString(3, run.taskId());
            ps.setString(4, run.status().name());
            ps.setString(5, run.startedAt() == null ? null : run.startedAt().toString());
            ps.setString(6, run.finishedAt() == null ? null : run.finishedAt().toString());
            ps.setString(7, run.error());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("保存自动化运行记录失败：" + run.id(), e);
        }
    }

    @Override
    public List<AutomationRun> listAutomationRuns(String automationId, int limit) {
        try (Connection connection = connect();
             PreparedStatement ps = connection.prepareStatement("select * from agent_automation_run where automation_id = ? order by started_at desc limit ?")) {
            ps.setString(1, automationId);
            ps.setInt(2, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                java.util.ArrayList<AutomationRun> result = new java.util.ArrayList<>();
                while (rs.next()) {
                    result.add(readAutomationRun(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询自动化运行记录失败：" + automationId, e);
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

    private List<AutomationDefinition> readAutomations(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            java.util.ArrayList<AutomationDefinition> result = new java.util.ArrayList<>();
            while (rs.next()) {
                result.add(readAutomation(rs));
            }
            return result;
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
        ps.setString(9, task.updatedAt().toString());
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

    private void bindAutomation(PreparedStatement ps, AutomationDefinition automation) throws SQLException {
        ps.setString(1, automation.id());
        ps.setString(2, automation.name());
        ps.setString(3, automation.prompt());
        ps.setString(4, automation.sessionId());
        ps.setString(5, automation.channelId());
        ps.setString(6, automation.userId());
        ps.setString(7, automation.scheduleType().name());
        ps.setString(8, automation.cronExpression());
        if (automation.intervalSeconds() == null) {
            ps.setObject(9, null);
        } else {
            ps.setLong(9, automation.intervalSeconds());
        }
        ps.setString(10, automation.timezone());
        ps.setString(11, automation.nextRunAt() == null ? null : automation.nextRunAt().toString());
        ps.setString(12, automation.lastRunAt() == null ? null : automation.lastRunAt().toString());
        ps.setString(13, automation.status().name());
        ps.setString(14, serializeMap(automation.metadata()));
        ps.setString(15, automation.createdAt().toString());
        ps.setString(16, automation.updatedAt().toString());
    }

    private AgentTask readTask(ResultSet rs) throws SQLException {
        return new AgentTask(
                rs.getString("id"),
                rs.getString("input"),
                rs.getString("session_id"),
                rs.getString("channel_id"),
                rs.getString("user_id"),
                new LinkedHashMap<>(),
                parseInstant(rs.getString("created_at")),
                parseInstant(rs.getString("updated_at")),
                TaskStatus.valueOf(rs.getString("status")),
                rs.getString("final_answer"));
    }

    private AgentSession readSession(ResultSet rs) throws SQLException {
        return new AgentSession(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("channel_id"),
                rs.getString("user_id"),
                parseMap(rs.getString("metadata")),
                parseInstant(rs.getString("created_at")),
                parseInstant(rs.getString("updated_at")),
                parseInstant(rs.getString("last_active_at")),
                rs.getString("summary"));
    }

    private TodoItem readTodoItem(ResultSet rs) throws SQLException {
        return new TodoItem(
                rs.getString("id"),
                rs.getString("session_id"),
                rs.getString("task_id"),
                rs.getInt("item_order"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("status"),
                parseMap(rs.getString("metadata")),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")));
    }

    private AutomationDefinition readAutomation(ResultSet rs) throws SQLException {
        long interval = rs.getLong("interval_seconds");
        return new AutomationDefinition(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("prompt"),
                rs.getString("session_id"),
                rs.getString("channel_id"),
                rs.getString("user_id"),
                AutomationScheduleType.valueOf(rs.getString("schedule_type")),
                rs.getString("cron_expression"),
                rs.wasNull() ? null : interval,
                rs.getString("timezone"),
                parseInstant(rs.getString("next_run_at")),
                parseInstant(rs.getString("last_run_at")),
                AutomationStatus.valueOf(rs.getString("status")),
                parseMap(rs.getString("metadata")),
                parseInstant(rs.getString("created_at")),
                parseInstant(rs.getString("updated_at")));
    }

    private AutomationRun readAutomationRun(ResultSet rs) throws SQLException {
        return new AutomationRun(
                rs.getString("id"),
                rs.getString("automation_id"),
                rs.getString("task_id"),
                AutomationRunStatus.valueOf(rs.getString("status")),
                parseInstant(rs.getString("started_at")),
                parseInstant(rs.getString("finished_at")),
                rs.getString("error"));
    }

    private String serializeMap(Map<String, String> input) {
        Properties properties = new Properties();
        input.forEach((key, value) -> properties.setProperty(key, value == null ? "" : value));
        try (StringWriter writer = new StringWriter()) {
            // Properties 会自动转义换行和等号，避免工具输出里包含多行文本时读取后只剩第一行。
            properties.store(writer, null);
            return writer.toString();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("序列化 Map 失败", e);
        }
    }

    private Map<String, String> parseMap(String value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value == null || value.isBlank()) {
            return result;
        }
        Properties properties = new Properties();
        try (StringReader reader = new StringReader(value)) {
            properties.load(reader);
            properties.forEach((key, entryValue) -> result.put(String.valueOf(key), String.valueOf(entryValue)));
            return result;
        } catch (java.io.IOException | IllegalArgumentException e) {
            // 兼容历史 key=value 明文格式，旧数据不能因为反序列化策略升级而无法读取。
            // Properties 遇到 Windows 路径这类非法反斜杠转义会抛 IllegalArgumentException，这里降级为逐行解析。
            for (String line : value.split("\\R")) {
                int separator = line.indexOf('=');
                if (separator > 0) {
                    result.put(line.substring(0, separator), line.substring(separator + 1));
                }
            }
        }
        return result;
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Instant.parse(value);
    }
}
