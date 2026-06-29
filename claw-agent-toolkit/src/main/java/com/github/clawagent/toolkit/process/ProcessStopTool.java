package com.github.clawagent.toolkit.process;

import com.github.clawagent.core.AgentContext;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolDefinition;
import com.github.clawagent.core.ToolResult;
import com.github.clawagent.spi.AgentTool;
import com.github.clawagent.toolkit.execute.ExecuteToolkitProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class ProcessStopTool extends ProcessToolSupport implements AgentTool {
    public ProcessStopTool(ManagedProcessStore store, ExecuteToolkitProperties properties) {
        super(store, properties);
    }

    @Override
    public ToolDefinition definition() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("pid", ToolDefinition.integerProperty("后台进程 PID"));
        schema.put("force", ToolDefinition.stringProperty("是否强制终止，true/false"));
        return ToolDefinition.high(
                "builtin.process.stop",
                "Stop Process",
                "停止由 process.start 托管的后台进程。",
                ToolDefinition.objectSchema(schema, false, List.of("pid")));
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        try {
            long pid = Long.parseLong(required(call, "pid"));
            boolean force = Boolean.parseBoolean(call.arguments().getOrDefault("force", "false"));
            ManagedProcess process = store.get(pid).orElse(null);
            if (process == null) {
                return ToolResult.error("未找到托管进程：" + pid);
            }
            ProcessHandle handle = process.process() == null ? ProcessHandle.of(pid).orElse(null) : process.process().toHandle();
            int descendantCount = 0;
            boolean aliveAfterStop = false;
            if (handle != null) {
                descendantCount = stopProcessTree(handle, force);
                aliveAfterStop = handle.isAlive();
            }
            if (!aliveAfterStop) {
                store.remove(pid);
            }
            return ToolResult.success("pid: " + pid + "\n"
                    + "status: " + (aliveAfterStop ? "stopping" : "stopped") + "\n"
                    + "force: " + force + "\n"
                    + "descendants: " + descendantCount);
        } catch (Exception e) {
            return ToolResult.error(e.getMessage());
        }
    }

    private int stopProcessTree(ProcessHandle root, boolean force) throws Exception {
        List<ProcessHandle> descendants = root.descendants().toList();
        // 先停子进程再停父进程，避免 shell/mvn/npm 等父进程退出后留下真实服务进程。
        Stream<ProcessHandle> handles = Stream.concat(descendants.stream(), Stream.of(root));
        handles.filter(ProcessHandle::isAlive).forEach(handle -> {
            if (force) {
                handle.destroyForcibly();
            } else {
                handle.destroy();
            }
        });
        waitForExit(descendants, root, 5);
        if (!force && (root.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive))) {
            Stream.concat(descendants.stream(), Stream.of(root))
                    .filter(ProcessHandle::isAlive)
                    .forEach(ProcessHandle::destroyForcibly);
            waitForExit(descendants, root, 3);
        }
        return descendants.size();
    }

    private void waitForExit(List<ProcessHandle> descendants, ProcessHandle root, long seconds) {
        Stream.concat(descendants.stream(), Stream.of(root))
                .filter(ProcessHandle::isAlive)
                .forEach(handle -> {
                    try {
                        handle.onExit().get(seconds, TimeUnit.SECONDS);
                    } catch (Exception ignored) {
                        // 单个子进程退出超时不应阻断后续强停和状态返回。
                    }
                });
    }
}
