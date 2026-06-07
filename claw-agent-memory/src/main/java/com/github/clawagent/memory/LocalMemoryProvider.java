package com.github.clawagent.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.clawagent.core.MemoryHitLog;
import com.github.clawagent.core.MemoryItem;
import com.github.clawagent.core.MemorySearchHit;
import com.github.clawagent.core.MemorySearchRequest;
import com.github.clawagent.spi.EmbeddingClient;
import com.github.clawagent.spi.EmbeddingOptions;
import com.github.clawagent.spi.EmbeddingResult;
import com.github.clawagent.spi.MemoryProvider;
import io.github.jbellis.jvector.disk.SimpleReader;
import io.github.jbellis.jvector.graph.GraphIndexBuilder;
import io.github.jbellis.jvector.graph.GraphSearcher;
import io.github.jbellis.jvector.graph.ImmutableGraphIndex;
import io.github.jbellis.jvector.graph.ListRandomAccessVectorValues;
import io.github.jbellis.jvector.graph.OnHeapGraphIndex;
import io.github.jbellis.jvector.graph.SearchResult;
import io.github.jbellis.jvector.graph.diversity.VamanaDiversityProvider;
import io.github.jbellis.jvector.graph.similarity.BuildScoreProvider;
import io.github.jbellis.jvector.util.Bits;
import io.github.jbellis.jvector.vector.VectorSimilarityFunction;
import io.github.jbellis.jvector.vector.VectorizationProvider;
import io.github.jbellis.jvector.vector.types.VectorFloat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 本地记忆 Provider。
 * <p>
 * metadata/chunk/FTS 与现有 clawagent.db 共库，Markdown 兼容文件和 JVector 图索引放在 .clawagent/memory 下。
 * </p>
 */
public class LocalMemoryProvider implements MemoryProvider {
    private static final Logger log = LoggerFactory.getLogger(LocalMemoryProvider.class);
    private static final int CHUNK_CHARS = 1_200;
    private static final int CHUNK_OVERLAP_CHARS = 120;
    private static final double RRF_K = 60.0;
    private static final double MIN_VECTOR_SCORE = 0.70;
    private static final String JVECTOR_GRAPH_FILE = "jvector.graph";
    private static final String JVECTOR_MAPPING_FILE = "jvector-memory-chunks.json";
    private static final int JVECTOR_MAX_CONNECTIONS = 16;
    private static final int JVECTOR_BEAM_WIDTH = 64;
    private static final float JVECTOR_OVERFLOW_RATIO = 1.2f;
    private static final float JVECTOR_ALPHA = 1.2f;

    /** 复用的 clawagent.db 路径。 */
    private final Path databasePath;
    /** Markdown 兼容文件根目录。 */
    private final Path filesRoot;
    /** JVector 图索引根目录。 */
    private final Path vectorsRoot;
    /** 项目统一 embedding 客户端。 */
    private final EmbeddingClient embeddingClient;
    /** embedding 调用参数。 */
    private final EmbeddingOptions embeddingOptions;
    /** JSON 序列化工具。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param databasePath 复用的 clawagent.db 路径。
     * @param filesRoot Markdown 兼容文件根目录。
     * @param vectorsRoot JVector 图索引根目录。
     * @param embeddingClient 项目统一 embedding 客户端。
     * @param embeddingOptions embedding 调用参数。
     */
    public LocalMemoryProvider(Path databasePath,
                               Path filesRoot,
                               Path vectorsRoot,
                               EmbeddingClient embeddingClient,
                               EmbeddingOptions embeddingOptions) {
        this.databasePath = databasePath.toAbsolutePath().normalize();
        this.filesRoot = filesRoot.toAbsolutePath().normalize();
        this.vectorsRoot = vectorsRoot.toAbsolutePath().normalize();
        this.embeddingClient = embeddingClient;
        this.embeddingOptions = embeddingOptions;
        initialize();
    }

    @Override
    public String id() {
        return "local";
    }

    @Override
    public Map<String, Object> capabilities() {
        return Map.of(
                "markdownFile", true,
                "bm25", true,
                "vectorSearch", true,
                "hybridSearch", true,
                "workspaceScope", false);
    }

    @Override
    public MemoryItem save(MemoryItem item) {
        MemoryItem safeItem = normalizeItem(item);
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            deleteIndexes(connection, safeItem.id());
            try (PreparedStatement ps = connection.prepareStatement(
                    "insert or replace into memory_item(" +
                            "id,user_id,scope_type,scope_id,type,status,content,summary,source_session_id,source_task_id," +
                            "importance,confidence,metadata,content_hash,created_at,updated_at) " +
                            "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, safeItem.id());
                ps.setString(2, safeItem.userId());
                ps.setString(3, safeItem.scopeType());
                ps.setString(4, nullToEmpty(safeItem.scopeId()));
                ps.setString(5, safeItem.type());
                ps.setString(6, safeItem.status());
                ps.setString(7, safeItem.content());
                ps.setString(8, safeItem.summary());
                ps.setString(9, safeItem.sourceSessionId());
                ps.setString(10, safeItem.sourceTaskId());
                ps.setDouble(11, safeItem.importance());
                ps.setDouble(12, safeItem.confidence());
                ps.setString(13, objectMapper.writeValueAsString(safeItem.metadata()));
                ps.setString(14, sha256(safeItem.content()));
                ps.setString(15, safeItem.createdAt().toString());
                ps.setString(16, safeItem.updatedAt().toString());
                ps.executeUpdate();
            }
            insertChunks(connection, safeItem);
            connection.commit();
            writeMarkdownFile(safeItem);
            // JVector 按 userId 独立重建，避免不同用户记忆进入同一 ANN 空间。
            rebuildJVectorIndex(safeItem.userId());
            return find(safeItem.userId(), safeItem.id()).orElse(safeItem);
        } catch (Exception e) {
            throw new IllegalStateException("保存记忆失败：" + safeItem.id(), e);
        }
    }

    @Override
    public Optional<MemoryItem> find(String userId, String itemId) {
        try (Connection connection = connect();
             PreparedStatement ps = connection.prepareStatement("select * from memory_item where id = ? and user_id = ?")) {
            ps.setString(1, itemId);
            ps.setString(2, normalizeUserId(userId));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readItem(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询记忆失败：" + itemId, e);
        }
    }

    @Override
    public List<MemoryItem> list(String userId, String scopeType, String status, int limit) {
        String sql = "select * from memory_item where user_id = ? "
                + (isBlank(scopeType) ? "" : "and scope_type = ? ")
                + (isBlank(status) ? "" : "and status = ? ")
                + "order by updated_at desc limit ?";
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, normalizeUserId(userId));
            int index = 2;
            if (!isBlank(scopeType)) {
                ps.setString(index++, normalizeScopeType(scopeType));
            }
            if (!isBlank(status)) {
                ps.setString(index++, normalizeStatus(status));
            }
            ps.setInt(index, Math.max(1, Math.min(limit, 500)));
            try (ResultSet rs = ps.executeQuery()) {
                List<MemoryItem> items = new ArrayList<>();
                while (rs.next()) {
                    items.add(readItem(rs));
                }
                return items;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询记忆列表失败", e);
        }
    }

    @Override
    public List<MemorySearchHit> search(MemorySearchRequest request) {
        SearchSpec spec = SearchSpec.from(request);
        Map<String, MemorySearchHit> hits = new LinkedHashMap<>();
        Map<String, Double> scores = new HashMap<>();
        if (!"vector".equalsIgnoreCase(spec.mode())) {
            mergeHits(hits, scores, keywordSearch(spec), 0.55);
        }
        if (!"keyword".equalsIgnoreCase(spec.mode())) {
            mergeHits(hits, scores, vectorSearch(spec), 0.45);
        }
        return hits.values().stream()
                .map(hit -> copyWithScore(hit, scores.getOrDefault(hit.chunkId(), hit.score())))
                .sorted(Comparator.comparingDouble(MemorySearchHit::score).reversed())
                .limit(spec.topK())
                .toList();
    }

    @Override
    public MemoryItem updateStatus(String userId, String itemId, String status) {
        MemoryItem existing = find(userId, itemId)
                .orElseThrow(() -> new IllegalArgumentException("记忆不存在或无权访问：" + itemId));
        MemoryItem updated = new MemoryItem(existing.id(), existing.userId(), existing.scopeType(), existing.scopeId(),
                existing.type(), normalizeStatus(status), existing.content(), existing.summary(),
                existing.sourceSessionId(), existing.sourceTaskId(), existing.importance(), existing.confidence(),
                existing.metadata(), existing.createdAt(), Instant.now());
        return save(updated);
    }

    @Override
    public void delete(String userId, String itemId) {
        String normalizedUserId = normalizeUserId(userId);
        MemoryItem existing = find(normalizedUserId, itemId)
                .orElseThrow(() -> new IllegalArgumentException("记忆不存在或无权访问：" + itemId));
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            // 删除顺序保持 DB、FTS、向量和主表一致，避免页面看不到但检索还能召回。
            deleteIndexes(connection, existing.id());
            try (PreparedStatement ps = connection.prepareStatement("delete from memory_item where id = ? and user_id = ?")) {
                ps.setString(1, existing.id());
                ps.setString(2, normalizedUserId);
                ps.executeUpdate();
            }
            connection.commit();
            Files.deleteIfExists(markdownPath(existing));
            rebuildJVectorIndex(normalizedUserId);
        } catch (Exception e) {
            throw new IllegalStateException("删除记忆失败：" + itemId, e);
        }
    }

    @Override
    public void recordHit(MemoryHitLog log) {
        try (Connection connection = connect();
             PreparedStatement ps = connection.prepareStatement(
                     "insert into memory_hit_log(id,user_id,session_id,task_id,item_id,chunk_id,score,metadata,created_at) values (?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, isBlank(log.id()) ? UUID.randomUUID().toString() : log.id());
            ps.setString(2, normalizeUserId(log.userId()));
            ps.setString(3, nullToEmpty(log.sessionId()));
            ps.setString(4, nullToEmpty(log.taskId()));
            ps.setString(5, nullToEmpty(log.itemId()));
            ps.setString(6, nullToEmpty(log.chunkId()));
            ps.setDouble(7, log.score());
            ps.setString(8, objectMapper.writeValueAsString(log.metadata() == null ? Map.of() : log.metadata()));
            ps.setString(9, (log.createdAt() == null ? Instant.now() : log.createdAt()).toString());
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("记录记忆命中失败", e);
        }
    }

    @Override
    public List<MemoryHitLog> hits(String userId, String sessionId, String taskId, int limit) {
        String sql = "select * from memory_hit_log where user_id = ? "
                + (isBlank(sessionId) ? "" : "and session_id = ? ")
                + (isBlank(taskId) ? "" : "and task_id = ? ")
                + "order by created_at desc limit ?";
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, normalizeUserId(userId));
            int index = 2;
            if (!isBlank(sessionId)) {
                ps.setString(index++, sessionId);
            }
            if (!isBlank(taskId)) {
                ps.setString(index++, taskId);
            }
            ps.setInt(index, Math.max(1, Math.min(limit, 500)));
            try (ResultSet rs = ps.executeQuery()) {
                List<MemoryHitLog> logs = new ArrayList<>();
                while (rs.next()) {
                    logs.add(new MemoryHitLog(
                            rs.getString("id"),
                            rs.getString("user_id"),
                            rs.getString("session_id"),
                            rs.getString("task_id"),
                            rs.getString("item_id"),
                            rs.getString("chunk_id"),
                            rs.getDouble("score"),
                            Instant.parse(rs.getString("created_at")),
                            parseMap(rs.getString("metadata"))));
                }
                return logs;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询记忆命中记录失败", e);
        }
    }

    private void initialize() {
        try {
            Files.createDirectories(databasePath.getParent());
            Files.createDirectories(filesRoot);
            Files.createDirectories(vectorsRoot);
            try (Connection connection = connect(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("create table if not exists memory_item (" +
                        "id text primary key, user_id text, scope_type text, scope_id text, type text, status text, " +
                        "content text, summary text, source_session_id text, source_task_id text, importance real, confidence real, " +
                        "metadata text, content_hash text, created_at text, updated_at text)");
                statement.executeUpdate("create table if not exists memory_chunk (" +
                        "id text primary key, item_id text, user_id text, scope_type text, scope_id text, chunk_no integer, " +
                        "text text, content_hash text, token_estimate integer, metadata text)");
                statement.executeUpdate("create virtual table if not exists memory_chunk_fts using fts5(" +
                        "chunk_id unindexed, item_id unindexed, user_id unindexed, scope_type unindexed, scope_id unindexed, text)");
                statement.executeUpdate("create table if not exists memory_vector (" +
                        "chunk_id text primary key, item_id text, user_id text, scope_type text, scope_id text, vector text)");
                statement.executeUpdate("create table if not exists memory_hit_log (" +
                        "id text primary key, user_id text, session_id text, task_id text, item_id text, chunk_id text, score real, metadata text, created_at text)");
                statement.executeUpdate("create index if not exists idx_memory_item_user_scope on memory_item(user_id, scope_type, status, updated_at)");
                statement.executeUpdate("create index if not exists idx_memory_item_hash on memory_item(user_id, scope_type, content_hash)");
                statement.executeUpdate("create index if not exists idx_memory_chunk_user_scope on memory_chunk(user_id, scope_type, scope_id)");
                statement.executeUpdate("create index if not exists idx_memory_hit_user_task on memory_hit_log(user_id, session_id, task_id)");
            }
        } catch (Exception e) {
            throw new IllegalStateException("初始化本地记忆失败", e);
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + databasePath);
    }

    private void insertChunks(Connection connection, MemoryItem item) throws Exception {
        List<String> chunks = splitChunks(item.content());
        try (PreparedStatement chunk = connection.prepareStatement("insert into memory_chunk values (?,?,?,?,?,?,?,?,?,?)");
             PreparedStatement fts = connection.prepareStatement("insert into memory_chunk_fts(chunk_id,item_id,user_id,scope_type,scope_id,text) values (?,?,?,?,?,?)");
             PreparedStatement vector = connection.prepareStatement("insert or replace into memory_vector values (?,?,?,?,?,?)")) {
            for (int i = 0; i < chunks.size(); i++) {
                String text = chunks.get(i);
                String chunkId = item.id() + "-" + String.format("%04d", i + 1);
                chunk.setString(1, chunkId);
                chunk.setString(2, item.id());
                chunk.setString(3, item.userId());
                chunk.setString(4, item.scopeType());
                chunk.setString(5, nullToEmpty(item.scopeId()));
                chunk.setInt(6, i + 1);
                chunk.setString(7, text);
                chunk.setString(8, sha256(text));
                chunk.setInt(9, estimateTokens(text));
                chunk.setString(10, "{}");
                chunk.addBatch();

                fts.setString(1, chunkId);
                fts.setString(2, item.id());
                fts.setString(3, item.userId());
                fts.setString(4, item.scopeType());
                fts.setString(5, nullToEmpty(item.scopeId()));
                fts.setString(6, text);
                fts.addBatch();

                List<Double> embedding = embed(text);
                if (!embedding.isEmpty()) {
                    vector.setString(1, chunkId);
                    vector.setString(2, item.id());
                    vector.setString(3, item.userId());
                    vector.setString(4, item.scopeType());
                    vector.setString(5, nullToEmpty(item.scopeId()));
                    vector.setString(6, objectMapper.writeValueAsString(embedding));
                    vector.addBatch();
                }
            }
            chunk.executeBatch();
            fts.executeBatch();
            vector.executeBatch();
        }
    }

    private List<MemorySearchHit> keywordSearch(SearchSpec spec) {
        List<MemorySearchHit> fts = ftsSearch(spec);
        return fts.isEmpty() ? likeSearch(spec) : fts;
    }

    private List<MemorySearchHit> ftsSearch(SearchSpec spec) {
        String query = ftsQuery(spec.query());
        if (query.isBlank()) {
            return List.of();
        }
        String sql = "select c.*, i.type, i.status, i.metadata, bm25(memory_chunk_fts) as rank " +
                "from memory_chunk_fts join memory_chunk c on c.id = memory_chunk_fts.chunk_id " +
                "join memory_item i on i.id = c.item_id " +
                "where memory_chunk_fts match ? and c.user_id = ? " + filterSql(spec) +
                " order by rank limit ?";
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, query);
            ps.setString(2, spec.userId());
            int index = bindFilters(ps, 3, spec);
            ps.setInt(index, spec.topK() * 4);
            try (ResultSet rs = ps.executeQuery()) {
                List<MemorySearchHit> hits = new ArrayList<>();
                while (rs.next()) {
                    double rank = rs.getDouble("rank");
                    hits.add(readHit(rs, 1.0 / (1.0 + Math.abs(rank))));
                }
                return hits;
            }
        } catch (SQLException e) {
            log.debug("memory fts search fallback reason={}", e.getMessage());
            return List.of();
        }
    }

    private List<MemorySearchHit> likeSearch(SearchSpec spec) {
        if (spec.query().isBlank()) {
            return List.of();
        }
        String sql = "select c.*, i.type, i.status, i.metadata from memory_chunk c join memory_item i on i.id = c.item_id " +
                "where c.user_id = ? and c.text like ? " + filterSql(spec) + " order by c.chunk_no limit ?";
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, spec.userId());
            ps.setString(2, "%" + spec.query() + "%");
            int index = bindFilters(ps, 3, spec);
            ps.setInt(index, spec.topK() * 4);
            try (ResultSet rs = ps.executeQuery()) {
                List<MemorySearchHit> hits = new ArrayList<>();
                while (rs.next()) {
                    hits.add(readHit(rs, 0.75));
                }
                return hits;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("记忆关键词检索失败", e);
        }
    }

    private List<MemorySearchHit> vectorSearch(SearchSpec spec) {
        List<Double> queryVector = embed(spec.query());
        if (queryVector.isEmpty()) {
            return List.of();
        }
        try (JVectorIndex index = loadJVectorIndex(spec.userId())) {
            if (index == null || index.records().isEmpty() || queryVector.size() != index.dimension()) {
                return List.of();
            }
            VectorFloat<?> vector = toJVectorVector(queryVector);
            Bits acceptOrds = new MemoryBits(index.records(), spec);
            int limit = Math.min(index.records().size(), Math.max(1, spec.topK() * 4));
            SearchResult result = GraphSearcher.search(vector, limit, index.vectorValues(), VectorSimilarityFunction.COSINE, index.graph(), acceptOrds);
            Map<String, Double> scores = new LinkedHashMap<>();
            for (SearchResult.NodeScore nodeScore : result.getNodes()) {
                double cosineScore = Math.max(0, nodeScore.score * 2.0 - 1.0);
                if (cosineScore >= MIN_VECTOR_SCORE) {
                    scores.put(index.records().get(nodeScore.node).chunkId(), cosineScore);
                }
            }
            return readHitsByChunkIds(scores).stream()
                    .sorted(Comparator.comparingDouble(MemorySearchHit::score).reversed())
                    .limit(spec.topK() * 4L)
                    .toList();
        } catch (Exception e) {
            log.debug("memory vector search skipped reason={}", e.getMessage());
            return List.of();
        }
    }

    private JVectorIndex loadJVectorIndex(String userId) throws Exception {
        Path userDir = userVectorDir(userId);
        Path graphPath = userDir.resolve(JVECTOR_GRAPH_FILE);
        Path mappingPath = userDir.resolve(JVECTOR_MAPPING_FILE);
        if (!Files.exists(graphPath) || !Files.exists(mappingPath)) {
            rebuildJVectorIndex(userId);
        }
        if (!Files.exists(graphPath) || !Files.exists(mappingPath)) {
            return null;
        }
        List<IndexedVector> records = objectMapper.readValue(mappingPath.toFile(), new TypeReference<List<IndexedVector>>() {});
        if (records.isEmpty()) {
            return null;
        }
        ListRandomAccessVectorValues vectorValues = vectorValues(records);
        BuildScoreProvider scoreProvider = BuildScoreProvider.randomAccessScoreProvider(vectorValues, VectorSimilarityFunction.COSINE);
        try (SimpleReader.Supplier supplier = new SimpleReader.Supplier(graphPath);
             var reader = supplier.get()) {
            ImmutableGraphIndex graph = OnHeapGraphIndex.load(reader, vectorValues.dimension(),
                    JVECTOR_OVERFLOW_RATIO, new VamanaDiversityProvider(scoreProvider, JVECTOR_ALPHA));
            return new JVectorIndex(records, vectorValues, graph, vectorValues.dimension());
        }
    }

    private void rebuildJVectorIndex(String userId) throws Exception {
        String normalizedUserId = normalizeUserId(userId);
        Path userDir = userVectorDir(normalizedUserId);
        Files.createDirectories(userDir);
        Path graphPath = userDir.resolve(JVECTOR_GRAPH_FILE);
        Path mappingPath = userDir.resolve(JVECTOR_MAPPING_FILE);
        List<IndexedVector> records = readIndexedVectors(normalizedUserId);
        if (records.isEmpty()) {
            Files.deleteIfExists(graphPath);
            objectMapper.writeValue(mappingPath.toFile(), List.of());
            return;
        }
        ListRandomAccessVectorValues vectorValues = vectorValues(records);
        int maxConnections = Math.max(1, Math.min(JVECTOR_MAX_CONNECTIONS, Math.max(1, records.size() - 1)));
        // 真实构建 JVector 图索引；检索时再按 scope/status 做 ordinal 过滤。
        try (GraphIndexBuilder builder = new GraphIndexBuilder(vectorValues, VectorSimilarityFunction.COSINE,
                maxConnections, JVECTOR_BEAM_WIDTH, JVECTOR_OVERFLOW_RATIO, JVECTOR_ALPHA, false)) {
            ImmutableGraphIndex graph = builder.build(vectorValues);
            if (graph instanceof OnHeapGraphIndex onHeapGraph) {
                try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(graphPath))) {
                    onHeapGraph.save(output);
                }
            }
            graph.close();
        }
        objectMapper.writeValue(mappingPath.toFile(), records);
    }

    private List<IndexedVector> readIndexedVectors(String userId) throws Exception {
        String sql = "select v.chunk_id, v.item_id, v.user_id, v.scope_type, v.scope_id, v.vector, i.status " +
                "from memory_vector v join memory_item i on i.id = v.item_id where v.user_id = ? order by v.chunk_id";
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, normalizeUserId(userId));
            try (ResultSet rs = ps.executeQuery()) {
                List<IndexedVector> records = new ArrayList<>();
                while (rs.next()) {
                    List<Double> vector = objectMapper.readValue(rs.getString("vector"), new TypeReference<List<Double>>() {});
                    if (!vector.isEmpty()) {
                        records.add(new IndexedVector(
                                rs.getString("chunk_id"),
                                rs.getString("item_id"),
                                rs.getString("user_id"),
                                rs.getString("scope_type"),
                                rs.getString("scope_id"),
                                rs.getString("status"),
                                vector));
                    }
                }
                return records;
            }
        }
    }

    private ListRandomAccessVectorValues vectorValues(List<IndexedVector> records) {
        List<VectorFloat<?>> vectors = new ArrayList<>();
        for (IndexedVector record : records) {
            vectors.add(toJVectorVector(record.vector()));
        }
        return new ListRandomAccessVectorValues(vectors, vectors.get(0).length());
    }

    private VectorFloat<?> toJVectorVector(List<Double> vector) {
        float[] values = new float[vector.size()];
        for (int i = 0; i < vector.size(); i++) {
            values[i] = vector.get(i).floatValue();
        }
        return VectorizationProvider.getInstance().getVectorTypeSupport().createFloatVector(values);
    }

    private List<MemorySearchHit> readHitsByChunkIds(Map<String, Double> scores) {
        if (scores == null || scores.isEmpty()) {
            return List.of();
        }
        String sql = "select c.*, i.type, i.status, i.metadata from memory_chunk c join memory_item i on i.id = c.item_id " +
                "where c.id in (" + "?,".repeat(scores.size()).replaceFirst(",$", "") + ")";
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(sql)) {
            int index = 1;
            for (String chunkId : scores.keySet()) {
                ps.setString(index++, chunkId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                Map<String, MemorySearchHit> hits = new LinkedHashMap<>();
                while (rs.next()) {
                    String chunkId = rs.getString("id");
                    hits.put(chunkId, readHit(rs, scores.getOrDefault(chunkId, 0.0)));
                }
                return scores.keySet().stream().map(hits::get).filter(hit -> hit != null).toList();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("记忆向量结果回查失败", e);
        }
    }

    private void mergeHits(Map<String, MemorySearchHit> hits, Map<String, Double> scores, List<MemorySearchHit> next, double weight) {
        for (int i = 0; i < next.size(); i++) {
            MemorySearchHit hit = next.get(i);
            hits.putIfAbsent(hit.chunkId(), hit);
            // RRF 只看各路召回排名，不直接混入 BM25 或向量原始分数，保持不同检索源可比。
            scores.merge(hit.chunkId(), weight / (RRF_K + i + 1), Double::sum);
        }
    }

    private MemorySearchHit readHit(ResultSet rs, double score) throws SQLException {
        return new MemorySearchHit(
                rs.getString("item_id"),
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getString("scope_type"),
                rs.getString("scope_id"),
                rs.getString("type"),
                rs.getString("status"),
                rs.getString("text"),
                score,
                parseMap(rs.getString("metadata")));
    }

    private MemorySearchHit copyWithScore(MemorySearchHit hit, double score) {
        return new MemorySearchHit(hit.itemId(), hit.chunkId(), hit.userId(), hit.scopeType(), hit.scopeId(),
                hit.type(), hit.status(), hit.content(), score, hit.metadata());
    }

    private MemoryItem readItem(ResultSet rs) throws SQLException {
        return new MemoryItem(
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getString("scope_type"),
                rs.getString("scope_id"),
                rs.getString("type"),
                rs.getString("status"),
                rs.getString("content"),
                rs.getString("summary"),
                rs.getString("source_session_id"),
                rs.getString("source_task_id"),
                rs.getDouble("importance"),
                rs.getDouble("confidence"),
                parseMap(rs.getString("metadata")),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")));
    }

    private void deleteIndexes(Connection connection, String itemId) throws SQLException {
        deleteByItem(connection, "memory_chunk_fts", itemId);
        deleteByItem(connection, "memory_vector", itemId);
        deleteByItem(connection, "memory_chunk", itemId);
    }

    private void deleteByItem(Connection connection, String table, String itemId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("delete from " + table + " where item_id = ?")) {
            ps.setString(1, itemId);
            ps.executeUpdate();
        }
    }

    private String filterSql(SearchSpec spec) {
        StringBuilder sql = new StringBuilder();
        if (!spec.scopeTypes().isEmpty()) {
            sql.append(" and c.scope_type in (").append("?,".repeat(spec.scopeTypes().size()).replaceFirst(",$", "")).append(")");
        }
        if (!isBlank(spec.scopeId())) {
            sql.append(" and c.scope_id = ?");
        }
        if (!spec.statuses().isEmpty()) {
            sql.append(" and i.status in (").append("?,".repeat(spec.statuses().size()).replaceFirst(",$", "")).append(")");
        }
        return sql.toString();
    }

    private int bindFilters(PreparedStatement ps, int startIndex, SearchSpec spec) throws SQLException {
        int index = startIndex;
        for (String scopeType : spec.scopeTypes()) {
            ps.setString(index++, scopeType);
        }
        if (!isBlank(spec.scopeId())) {
            ps.setString(index++, spec.scopeId());
        }
        for (String status : spec.statuses()) {
            ps.setString(index++, status);
        }
        return index;
    }

    private MemoryItem normalizeItem(MemoryItem item) {
        if (item == null) {
            throw new IllegalArgumentException("记忆不能为空");
        }
        String scopeType = normalizeScopeType(item.scopeType());
        if ("task".equals(scopeType)) {
            throw new IllegalArgumentException("task 不是长期记忆 scope，只能作为运行时上下文来源");
        }
        if ("workspace".equals(scopeType)) {
            throw new IllegalArgumentException("workspace scope 仅预留，当前版本不启用");
        }
        Instant now = Instant.now();
        String id = isBlank(item.id()) ? UUID.randomUUID().toString() : item.id();
        String content = nullToEmpty(item.content()).trim();
        if (content.isBlank()) {
            throw new IllegalArgumentException("记忆正文不能为空");
        }
        return new MemoryItem(id, normalizeUserId(item.userId()), scopeType, nullToEmpty(item.scopeId()),
                isBlank(item.type()) ? "fact" : item.type().trim(),
                normalizeStatus(item.status()),
                content,
                isBlank(item.summary()) ? preview(content, 160) : item.summary(),
                item.sourceSessionId(),
                item.sourceTaskId(),
                item.importance(),
                item.confidence(),
                item.metadata(),
                item.createdAt() == null ? now : item.createdAt(),
                now);
    }

    private void writeMarkdownFile(MemoryItem item) throws Exception {
        Path path = markdownPath(item);
        Files.createDirectories(path.getParent());
        StringBuilder builder = new StringBuilder();
        builder.append("---\n");
        builder.append("id: ").append(item.id()).append('\n');
        builder.append("userId: ").append(item.userId()).append('\n');
        builder.append("scopeType: ").append(item.scopeType()).append('\n');
        builder.append("scopeId: ").append(item.scopeId()).append('\n');
        builder.append("type: ").append(item.type()).append('\n');
        builder.append("status: ").append(item.status()).append('\n');
        builder.append("updatedAt: ").append(item.updatedAt()).append('\n');
        builder.append("---\n\n");
        builder.append(item.content()).append('\n');
        Files.writeString(path, builder.toString(), StandardCharsets.UTF_8);
    }

    private Path markdownPath(MemoryItem item) {
        return filesRoot.resolve(safePathPart(item.userId())).resolve(item.id() + ".md").normalize();
    }

    private List<String> splitChunks(String text) {
        String normalized = normalizeText(text);
        if (normalized.isBlank()) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(normalized.length(), start + CHUNK_CHARS);
            chunks.add(normalized.substring(start, end).trim());
            if (end == normalized.length()) {
                break;
            }
            start = Math.max(end - CHUNK_OVERLAP_CHARS, start + 1);
        }
        return chunks;
    }

    private List<Double> embed(String text) {
        if (embeddingClient == null || isBlank(text)) {
            return List.of();
        }
        try {
            EmbeddingResult result = embeddingClient.embed(text, embeddingOptions);
            return result.vector();
        } catch (RuntimeException e) {
            log.debug("memory embedding skipped reason={}", e.getMessage());
            return List.of();
        }
    }

    private Map<String, String> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String ftsQuery(String query) {
        return isBlank(query) ? "" : "\"" + query.replace("\"", "\"\"").trim() + "\"";
    }

    private String normalizeText(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').replaceAll("[\\t ]+\\n", "\n").trim();
    }

    private String normalizeUserId(String userId) {
        return isBlank(userId) ? "anonymous" : userId.trim();
    }

    private String normalizeScopeType(String scopeType) {
        String value = isBlank(scopeType) ? "session" : scopeType.trim().toLowerCase();
        return switch (value) {
            case "global", "channel", "session", "workspace", "task" -> value;
            default -> throw new IllegalArgumentException("不支持的记忆 scope：" + scopeType);
        };
    }

    private String normalizeStatus(String status) {
        String value = isBlank(status) ? "pending" : status.trim().toLowerCase();
        return switch (value) {
            case "pending", "active", "disabled", "conflict", "archived" -> value;
            default -> throw new IllegalArgumentException("不支持的记忆状态：" + status);
        };
    }

    private Path userVectorDir(String userId) {
        return vectorsRoot.resolve(safePathPart(normalizeUserId(userId))).normalize();
    }

    private String safePathPart(String value) {
        String safe = isBlank(value) ? "unknown" : value;
        return safe.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}\\s]+", "_");
    }

    private int estimateTokens(String text) {
        return text == null ? 0 : Math.max(1, text.length() / 4);
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(nullToEmpty(text).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }

    private String preview(String text, int limit) {
        String normalized = normalizeText(text).replaceAll("\\s+", " ");
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "...";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * @param chunkId chunk 主键。
     * @param itemId 记忆条目 ID。
     * @param userId 用户 ID。
     * @param scopeType scope 类型。
     * @param scopeId scope ID。
     * @param status 记忆状态。
     * @param vector embedding 向量。
     */
    private record IndexedVector(
            String chunkId,
            String itemId,
            String userId,
            String scopeType,
            String scopeId,
            String status,
            List<Double> vector
    ) {
    }

    /**
     * @param records ordinal 到 chunk 的映射。
     * @param vectorValues JVector 检索使用的向量源。
     * @param graph JVector 图索引。
     * @param dimension 向量维度。
     */
    private record JVectorIndex(
            List<IndexedVector> records,
            ListRandomAccessVectorValues vectorValues,
            ImmutableGraphIndex graph,
            int dimension
    ) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            graph.close();
        }
    }

    /**
     * JVector ordinal 过滤器，用于状态和 scope 隔离。
     */
    private static class MemoryBits implements Bits {
        private final List<IndexedVector> records;
        private final SearchSpec spec;

        private MemoryBits(List<IndexedVector> records, SearchSpec spec) {
            this.records = records;
            this.spec = spec;
        }

        @Override
        public boolean get(int index) {
            IndexedVector record = records.get(index);
            if (!spec.userId().equals(record.userId())) {
                return false;
            }
            if (!spec.scopeTypes().isEmpty() && !spec.scopeTypes().contains(record.scopeType())) {
                return false;
            }
            if (!spec.statuses().isEmpty() && !spec.statuses().contains(record.status())) {
                return false;
            }
            return spec.scopeId().isBlank() || spec.scopeId().equals(record.scopeId());
        }
    }

    /**
     * @param userId 用户 ID。
     * @param query 检索 query。
     * @param scopeTypes scope 过滤。
     * @param scopeId scope ID 过滤。
     * @param statuses 状态过滤。
     * @param mode 检索模式。
     * @param topK 返回条数。
     */
    private record SearchSpec(
            String userId,
            String query,
            List<String> scopeTypes,
            String scopeId,
            List<String> statuses,
            String mode,
            int topK
    ) {
        private static SearchSpec from(MemorySearchRequest request) {
            MemorySearchRequest safe = request == null
                    ? new MemorySearchRequest("anonymous", "", List.of(), "", List.of("active"), "hybrid", 8)
                    : request;
            List<String> scopes = safe.scopeTypes() == null ? List.of() : safe.scopeTypes().stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> value.trim().toLowerCase())
                    .filter(value -> !"workspace".equals(value) && !"task".equals(value))
                    .distinct()
                    .toList();
            List<String> statuses = safe.statuses() == null || safe.statuses().isEmpty()
                    ? List.of("active")
                    : safe.statuses().stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> value.trim().toLowerCase())
                    .distinct()
                    .toList();
            return new SearchSpec(
                    safe.userId() == null || safe.userId().isBlank() ? "anonymous" : safe.userId().trim(),
                    safe.query() == null ? "" : safe.query(),
                    scopes,
                    safe.scopeId() == null ? "" : safe.scopeId().trim(),
                    statuses,
                    safe.mode() == null || safe.mode().isBlank() ? "hybrid" : safe.mode(),
                    Math.max(1, Math.min(safe.topK(), 50)));
        }
    }
}
