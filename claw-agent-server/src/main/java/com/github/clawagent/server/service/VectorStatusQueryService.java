package com.github.clawagent.server.service;

import com.github.clawagent.server.dto.VectorStatusView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * 查询本地 SQLite 中知识库和记忆的向量化覆盖情况。
 */
@Service
public class VectorStatusQueryService {
    private static final Logger log = LoggerFactory.getLogger(VectorStatusQueryService.class);

    /**
     * 查询知识库文档的 chunk/vector 覆盖情况。
     */
    public List<VectorStatusView> knowledgeVectorStatus(String userId) {
        return queryVectorStatus(
                "knowledge_document",
                "knowledge_chunk",
                "knowledge_vector",
                "document_id",
                "id",
                "name",
                "status",
                userId);
    }

    /**
     * 查询长期记忆条目的 chunk/vector 覆盖情况。
     */
    public List<VectorStatusView> memoryVectorStatus(String userId) {
        return queryVectorStatus(
                "memory_item",
                "memory_chunk",
                "memory_vector",
                "item_id",
                "id",
                "summary",
                "status",
                userId);
    }

    private List<VectorStatusView> queryVectorStatus(
            String ownerTable,
            String chunkTable,
            String vectorTable,
            String ownerForeignKey,
            String ownerIdColumn,
            String ownerNameColumn,
            String ownerStatusColumn,
            String userId) {
        Path databasePath = runtimeConfigRoot().resolve("clawagent.db");
        if (!Files.exists(databasePath)) {
            return List.of();
        }
        String sql = "select o." + ownerIdColumn + " as id, o." + ownerNameColumn + " as name, o." + ownerStatusColumn + " as status, " +
                "count(distinct c.id) as chunk_count, count(distinct v.chunk_id) as vector_count " +
                "from " + ownerTable + " o " +
                "left join " + chunkTable + " c on c." + ownerForeignKey + " = o." + ownerIdColumn + " and c.user_id = o.user_id " +
                "left join " + vectorTable + " v on v.chunk_id = c.id and v.user_id = o.user_id " +
                "where o.user_id = ? " +
                "group by o." + ownerIdColumn + ", o." + ownerNameColumn + ", o." + ownerStatusColumn + " " +
                "order by o." + ownerIdColumn + " desc";
        List<VectorStatusView> rows = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, firstNonBlank(userId, "console"));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    int chunkCount = rs.getInt("chunk_count");
                    int vectorCount = rs.getInt("vector_count");
                    // chunk 为 0 说明尚未解析成功，不能算已向量化；vector 必须覆盖所有 chunk。
                    boolean vectorized = chunkCount > 0 && vectorCount >= chunkCount;
                    rows.add(new VectorStatusView(
                            rs.getString("id"),
                            rs.getString("name"),
                            rs.getString("status"),
                            chunkCount,
                            vectorCount,
                            vectorized
                    ));
                }
            }
        } catch (Exception ex) {
            log.warn("vector status query skipped table={} reason={}", ownerTable, ex.getMessage());
        }
        return rows;
    }

    private Path runtimeConfigRoot() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path current = cwd; current != null; current = current.getParent()) {
            Path candidate = current.resolve(".clawagent");
            // 优先复用仓库级 .clawagent，避免从子模块启动时误写 claw-agent-server/.clawagent。
            if (Files.isDirectory(candidate)
                    && (Files.exists(candidate.resolve("clawagent.db")) || Files.isDirectory(candidate.resolve("skills")))) {
                return candidate;
            }
        }
        return cwd.resolve(".clawagent");
    }

    private String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first.trim();
    }
}
