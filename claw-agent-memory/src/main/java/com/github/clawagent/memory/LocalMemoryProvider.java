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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private static final double DUPLICATE_SIMILARITY = 0.88;
    private static final double CONFLICT_SIMILARITY = 0.76;
    private static final ZoneId MARKDOWN_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String JVECTOR_GRAPH_FILE = "jvector.graph";
    private static final String JVECTOR_MAPPING_FILE = "jvector-memory-chunks.json";
    private static final String USER_MEMORY_FILE = "MEMORY.md";
    private static final String DAILY_DIR = "daily";
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
    /** 长期记忆自动降权和归档配置。 */
    private final GovernanceOptions governanceOptions;
    /** JSON 序列化工具。 */
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 防止自动治理在保存 Markdown 索引时递归触发。 */
    private final ThreadLocal<Boolean> suppressAutomaticGovernance = ThreadLocal.withInitial(() -> false);

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
        this(databasePath, filesRoot, vectorsRoot, embeddingClient, embeddingOptions, GovernanceOptions.defaults());
    }

    /**
     * @param databasePath 复用的 clawagent.db 路径。
     * @param filesRoot Markdown 兼容文件根目录。
     * @param vectorsRoot JVector 图索引根目录。
     * @param embeddingClient 项目统一 embedding 客户端。
     * @param embeddingOptions embedding 调用参数。
     * @param governanceOptions 长期记忆自动治理配置。
     */
    public LocalMemoryProvider(Path databasePath,
                               Path filesRoot,
                               Path vectorsRoot,
                               EmbeddingClient embeddingClient,
                               EmbeddingOptions embeddingOptions,
                               GovernanceOptions governanceOptions) {
        this.databasePath = databasePath.toAbsolutePath().normalize();
        this.filesRoot = filesRoot.toAbsolutePath().normalize();
        this.vectorsRoot = vectorsRoot.toAbsolutePath().normalize();
        this.embeddingClient = embeddingClient;
        this.embeddingOptions = embeddingOptions;
        this.governanceOptions = governanceOptions == null ? GovernanceOptions.defaults() : governanceOptions;
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
        MemoryItem safeItem = applyQualityScoring(applyGovernance(normalizeItem(item)));
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
            suppressAutomaticGovernance.set(true);
            try {
                writeMarkdownFile(safeItem);
                syncMarkdownIndexes(safeItem.userId());
                // JVector 按 userId 独立重建，避免不同用户记忆进入同一 ANN 空间。
                rebuildJVectorIndex(safeItem.userId());
            } finally {
                suppressAutomaticGovernance.set(false);
            }
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
        applyAutomaticGovernance(userId);
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
        applyAutomaticGovernance(request.userId());
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
                withMetadata(existing.metadata(), Map.of("statusReason", "manual-" + normalizeStatus(status))),
                existing.createdAt(), Instant.now());
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
            syncMarkdownIndexes(normalizedUserId);
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
            // 命中时间和次数用于后续降权/过期治理，当前只更新主表 metadata，不影响检索结果。
            updateHitStats(connection, normalizeUserId(log.userId()), log.itemId());
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
        String sql = "select c.*, i.type, i.status, i.metadata as item_metadata, bm25(memory_chunk_fts) as rank " +
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
        String sql = "select c.*, i.type, i.status, i.metadata as item_metadata from memory_chunk c join memory_item i on i.id = c.item_id " +
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
        String sql = "select c.*, i.type, i.status, i.metadata as item_metadata from memory_chunk c join memory_item i on i.id = c.item_id " +
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
            hits.putIfAbsent(hit.chunkId(), copyWithMetadata(hit, Map.of(
                    "retrievalSource", retrievalSource(weight),
                    "retrievalRank", String.valueOf(i + 1))));
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
                parseMap(columnOrNull(rs, "item_metadata", "metadata")));
    }

    private MemorySearchHit copyWithScore(MemorySearchHit hit, double score) {
        return new MemorySearchHit(hit.itemId(), hit.chunkId(), hit.userId(), hit.scopeType(), hit.scopeId(),
                hit.type(), hit.status(), hit.content(), score, withMetadata(hit.metadata(), Map.of(
                "rrfScore", String.format("%.6f", score))));
    }

    private MemorySearchHit copyWithMetadata(MemorySearchHit hit, Map<String, String> extra) {
        return new MemorySearchHit(hit.itemId(), hit.chunkId(), hit.userId(), hit.scopeType(), hit.scopeId(),
                hit.type(), hit.status(), hit.content(), hit.score(), withMetadata(hit.metadata(), extra));
    }

    private String retrievalSource(double weight) {
        if (Math.abs(weight - 0.55) < 0.001) {
            return "keyword";
        }
        if (Math.abs(weight - 0.45) < 0.001) {
            return "vector";
        }
        return "unknown";
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

    private void updateHitStats(Connection connection, String userId, String itemId) throws Exception {
        if (isBlank(itemId)) {
            return;
        }
        MemoryItem existing = find(userId, itemId).orElse(null);
        if (existing == null) {
            return;
        }
        Map<String, String> metadata = new LinkedHashMap<>(existing.metadata());
        int count = parseInt(metadata.get("hitCount"), 0) + 1;
        metadata.put("hitCount", String.valueOf(count));
        metadata.put("lastHitAt", Instant.now().toString());
        // 命中后只重算治理指标，不重写 chunk/vector，避免每次命中都触发索引重建。
        metadata = applyQualityMetadata(existing, metadata);
        try (PreparedStatement ps = connection.prepareStatement(
                "update memory_item set metadata = ?, updated_at = ? where id = ? and user_id = ?")) {
            ps.setString(1, objectMapper.writeValueAsString(metadata));
            ps.setString(2, existing.updatedAt().toString());
            ps.setString(3, existing.id());
            ps.setString(4, existing.userId());
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

    /**
     * 给记忆补充透明治理指标。
     * <p>
     * 质量分只写入 metadata，不直接改变状态，避免没有配置阈值时误归档有效记忆。
     * </p>
     */
    private MemoryItem applyQualityScoring(MemoryItem item) {
        Map<String, String> metadata = applyQualityMetadata(item, item.metadata());
        return new MemoryItem(item.id(), item.userId(), item.scopeType(), item.scopeId(), item.type(),
                item.status(), item.content(), item.summary(), item.sourceSessionId(), item.sourceTaskId(),
                item.importance(), item.confidence(), metadata, item.createdAt(), item.updatedAt());
    }

    /**
     * 计算长期记忆质量指标。
     * <p>
     * 评分来源包括重要性、置信度、来源可追溯性、命中次数、重复确认次数和最近使用时间。
     * </p>
     */
    private Map<String, String> applyQualityMetadata(MemoryItem item, Map<String, String> sourceMetadata) {
        Map<String, String> metadata = new LinkedHashMap<>(sourceMetadata == null ? Map.of() : sourceMetadata);
        int hitCount = parseInt(metadata.get("hitCount"), 0);
        int duplicateCount = parseInt(metadata.get("duplicateCount"), 0);
        int staleDays = staleDays(item, metadata);
        double stalenessPenalty = staleDays <= governanceOptions.staleAfterDays()
                ? 0.0
                : Math.min(0.35, (staleDays - governanceOptions.staleAfterDays()) / (double) governanceOptions.veryStaleAfterDays());
        double sourceScore = sourceScore(item);
        double reuseScore = Math.min(0.20, Math.log1p(hitCount) / 12.0) + Math.min(0.10, Math.log1p(duplicateCount) / 18.0);
        double baseScore = clamp01(item.importance()) * 0.30
                + clamp01(item.confidence()) * 0.35
                + sourceScore * 0.15
                + reuseScore;
        double qualityScore = clamp01(baseScore - stalenessPenalty);
        // 质量分只作为透明治理指标，当前版本不自动归档，避免阈值未配置时误处理有效记忆。
        metadata.put("qualityScore", formatScore(qualityScore));
        metadata.put("stalenessPenalty", formatScore(stalenessPenalty));
        metadata.put("staleDays", String.valueOf(staleDays));
        metadata.put("qualityReason", "importance/confidence/source/hit/duplicate/recency");
        metadata.put("qualityUpdatedAt", Instant.now().toString());
        return metadata;
    }

    /**
     * 按配置自动治理 active 记忆。
     * <p>
     * 这里不缓存全量记忆，只按当前 userId 扫描 active 记录；命中归档条件后复用 save 保持 DB、FTS、JVector、Markdown 一致。
     * </p>
     */
    private void applyAutomaticGovernance(String userId) {
        if (suppressAutomaticGovernance.get() || !governanceOptions.autoArchiveEnabled()) {
            return;
        }
        String normalizedUserId = normalizeUserId(userId);
        List<MemoryItem> archiveItems = new ArrayList<>();
        suppressAutomaticGovernance.set(true);
        try (Connection connection = connect();
             PreparedStatement ps = connection.prepareStatement(
                     "select * from memory_item where user_id = ? and status = 'active' order by updated_at asc")) {
            ps.setString(1, normalizedUserId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    MemoryItem scored = applyQualityScoring(readItem(rs));
                    int staleDays = parseInt(scored.metadata().get("staleDays"), 0);
                    double qualityScore = parseDouble(scored.metadata().get("qualityScore"), 1.0);
                    String reason = automaticArchiveReason(staleDays, qualityScore);
                    if (reason != null) {
                        // 自动归档只改状态和治理 metadata，不删除原文，管理台仍可回溯和手动恢复。
                        archiveItems.add(copyWithStatusAndMetadata(scored, "archived", Map.of(
                                "governance", "auto-archived",
                                "archiveReason", reason,
                                "archivedAt", Instant.now().toString())));
                    } else {
                        persistQualityMetadata(connection, scored);
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("自动治理记忆失败", e);
        } finally {
            suppressAutomaticGovernance.set(false);
        }
        for (MemoryItem archiveItem : archiveItems) {
            save(archiveItem);
        }
    }

    /** 判断记忆是否满足自动归档条件。 */
    private String automaticArchiveReason(int staleDays, double qualityScore) {
        if (governanceOptions.archiveAfterDays() > 0 && staleDays >= governanceOptions.archiveAfterDays()) {
            return "stale-days-" + staleDays;
        }
        if (governanceOptions.archiveBelowQuality() > 0 && qualityScore <= governanceOptions.archiveBelowQuality()) {
            return "quality-score-" + formatScore(qualityScore);
        }
        return null;
    }

    /** 只刷新质量 metadata，不重建 chunk，避免列表查询产生昂贵副作用。 */
    private void persistQualityMetadata(Connection connection, MemoryItem item) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "update memory_item set metadata = ?, updated_at = ? where id = ? and user_id = ?")) {
            ps.setString(1, writeJson(item.metadata()));
            ps.setString(2, item.updatedAt().toString());
            ps.setString(3, item.id());
            ps.setString(4, item.userId());
            ps.executeUpdate();
        }
    }

    /** 根据最后命中时间或更新时间计算未使用天数。 */
    private int staleDays(MemoryItem item, Map<String, String> metadata) {
        Instant anchor = parseInstant(metadata.get("lastHitAt"))
                .orElse(item.updatedAt() == null ? item.createdAt() : item.updatedAt());
        if (anchor == null) {
            return 0;
        }
        return (int) Math.max(0, ChronoUnit.DAYS.between(anchor, Instant.now()));
    }

    /** 来源越可追溯，质量基础分越高。 */
    private double sourceScore(MemoryItem item) {
        double score = 0.0;
        if (!isBlank(item.sourceSessionId())) {
            score += 0.45;
        }
        if (!isBlank(item.sourceTaskId())) {
            score += 0.35;
        }
        if (!isBlank(item.metadata().get("governance")) && !"new".equals(item.metadata().get("governance"))) {
            score += 0.20;
        }
        return Math.min(1.0, score);
    }

    private MemoryItem applyGovernance(MemoryItem item) {
        if (item.metadata().getOrDefault("statusReason", "").startsWith("manual-")) {
            // 用户在管理台手动启停/归档/通过冲突时，尊重人工决策，不再自动打回 conflict。
            return copyWithMetadata(item, Map.of(
                    "governance", "manual",
                    "governedAt", Instant.now().toString()));
        }
        Optional<MemoryItem> exact = findByContentHash(item);
        if (exact.isPresent() && !exact.get().id().equals(item.id())) {
            // 完全重复时不再新增长期记忆，只补充旧记忆的来源和重复计数。
            MemoryItem merged = mergeDuplicate(exact.get(), item, "content-hash");
            log.info("memory duplicate merged userId={} existing={} incoming={}", item.userId(), merged.id(), item.id());
            return merged;
        }
        SimilarMemory similar = findSimilarMemory(item);
        if (similar != null && !similar.item().id().equals(item.id())) {
            if (similar.score() >= DUPLICATE_SIMILARITY) {
                MemoryItem merged = mergeDuplicate(similar.item(), item, "similarity-" + String.format("%.2f", similar.score()));
                log.info("memory similar duplicate merged userId={} existing={} incoming={} score={}",
                        item.userId(), merged.id(), item.id(), similar.score());
                return merged;
            }
            if (shouldMarkConflict(item, similar.item(), similar.score())) {
                // 冲突候选不能直接覆盖 active，必须让用户在管理台确认。
                return copyWithStatusAndMetadata(item, "conflict", Map.of(
                        "governance", "conflict",
                        "conflictWith", similar.item().id(),
                        "conflictScore", String.format("%.4f", similar.score()),
                        "conflictReason", "same-scope-similar-content"));
            }
        }
        return copyWithMetadata(item, Map.of(
                "governance", item.metadata().getOrDefault("governance", "new"),
                "governedAt", Instant.now().toString()));
    }

    private Optional<MemoryItem> findByContentHash(MemoryItem item) {
        String sql = "select * from memory_item where user_id = ? and scope_type = ? and scope_id = ? " +
                "and type = ? and content_hash = ? order by updated_at desc limit 1";
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, item.userId());
            ps.setString(2, item.scopeType());
            ps.setString(3, nullToEmpty(item.scopeId()));
            ps.setString(4, item.type());
            ps.setString(5, sha256(item.content()));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readItem(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询重复记忆失败", e);
        }
    }

    private SimilarMemory findSimilarMemory(MemoryItem item) {
        List<MemorySearchHit> hits = search(new MemorySearchRequest(
                item.userId(),
                item.summary() + "\n" + item.content(),
                List.of(item.scopeType()),
                item.scopeId(),
                List.of("active", "pending", "conflict"),
                "hybrid",
                8));
        SimilarMemory best = null;
        for (MemorySearchHit hit : hits) {
            if (hit.itemId().equals(item.id())) {
                continue;
            }
            MemoryItem existing = find(item.userId(), hit.itemId()).orElse(null);
            if (existing == null || !existing.type().equals(item.type())) {
                continue;
            }
            double score = contentSimilarity(item.content(), existing.content());
            if (best == null || score > best.score()) {
                best = new SimilarMemory(existing, score);
            }
        }
        return best;
    }

    private MemoryItem mergeDuplicate(MemoryItem existing, MemoryItem incoming, String reason) {
        Map<String, String> metadata = new LinkedHashMap<>(existing.metadata());
        int duplicateCount = parseInt(metadata.get("duplicateCount"), 0) + 1;
        metadata.put("governance", "duplicate-merged");
        metadata.put("duplicateReason", reason);
        metadata.put("duplicateCount", String.valueOf(duplicateCount));
        metadata.put("lastDuplicateAt", Instant.now().toString());
        metadata.put("lastDuplicateSourceTaskId", nullToEmpty(incoming.sourceTaskId()));
        metadata.put("lastDuplicateSourceSessionId", nullToEmpty(incoming.sourceSessionId()));
        String summary = existing.summary().length() >= incoming.summary().length() ? existing.summary() : incoming.summary();
        return new MemoryItem(
                existing.id(),
                existing.userId(),
                existing.scopeType(),
                existing.scopeId(),
                existing.type(),
                existing.status(),
                existing.content(),
                summary,
                firstNonBlank(existing.sourceSessionId(), incoming.sourceSessionId()),
                firstNonBlank(existing.sourceTaskId(), incoming.sourceTaskId()),
                Math.max(existing.importance(), incoming.importance()),
                Math.max(existing.confidence(), incoming.confidence()),
                metadata,
                existing.createdAt(),
                Instant.now());
    }

    private boolean shouldMarkConflict(MemoryItem incoming, MemoryItem existing, double similarity) {
        if (!"active".equals(existing.status()) || similarity < CONFLICT_SIMILARITY) {
            return false;
        }
        if (!incoming.scopeType().equals(existing.scopeType()) || !nullToEmpty(incoming.scopeId()).equals(nullToEmpty(existing.scopeId()))) {
            return false;
        }
        return hasOppositePolarity(incoming.content(), existing.content());
    }

    private boolean hasOppositePolarity(String left, String right) {
        String a = normalizeText(left);
        String b = normalizeText(right);
        return (hasNegativeMarker(a) && !hasNegativeMarker(b)) || (!hasNegativeMarker(a) && hasNegativeMarker(b));
    }

    private boolean hasNegativeMarker(String text) {
        String value = text == null ? "" : text.toLowerCase();
        return value.contains("不要") || value.contains("不需要") || value.contains("禁止")
                || value.contains("不能") || value.contains("别") || value.contains("avoid")
                || value.contains("never") || value.contains("do not");
    }

    private double contentSimilarity(String left, String right) {
        Set<String> a = tokenSet(left);
        Set<String> b = tokenSet(right);
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        int same = 0;
        for (String token : a) {
            if (b.contains(token)) {
                same++;
            }
        }
        return same / (double) Math.max(a.size(), b.size());
    }

    private Set<String> tokenSet(String text) {
        String normalized = normalizeText(text).toLowerCase().replaceAll("[\\p{Punct}\\s]+", " ").trim();
        Set<String> tokens = new LinkedHashSet<>();
        if (normalized.isBlank()) {
            return tokens;
        }
        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }
        // 中文文本经常没有空格，用连续字符片段做保守相似度判断。
        String compact = normalized.replace(" ", "");
        for (int i = 0; i + 2 <= compact.length(); i += 2) {
            tokens.add(compact.substring(i, Math.min(i + 4, compact.length())));
        }
        return tokens;
    }

    private MemoryItem copyWithStatusAndMetadata(MemoryItem item, String status, Map<String, String> extra) {
        return new MemoryItem(item.id(), item.userId(), item.scopeType(), item.scopeId(), item.type(),
                normalizeStatus(status), item.content(), item.summary(), item.sourceSessionId(), item.sourceTaskId(),
                item.importance(), item.confidence(), withMetadata(item.metadata(), extra), item.createdAt(), Instant.now());
    }

    private MemoryItem copyWithMetadata(MemoryItem item, Map<String, String> extra) {
        return new MemoryItem(item.id(), item.userId(), item.scopeType(), item.scopeId(), item.type(),
                item.status(), item.content(), item.summary(), item.sourceSessionId(), item.sourceTaskId(),
                item.importance(), item.confidence(), withMetadata(item.metadata(), extra), item.createdAt(), item.updatedAt());
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
        builder.append("createdAt: ").append(item.createdAt()).append('\n');
        builder.append("updatedAt: ").append(item.updatedAt()).append('\n');
        if (!item.metadata().isEmpty()) {
            builder.append("metadata: ").append(objectMapper.writeValueAsString(item.metadata()).replace("\n", "")).append('\n');
        }
        builder.append("---\n\n");
        builder.append("# ").append(item.summary()).append("\n\n");
        builder.append(item.content()).append('\n');
        Files.writeString(path, builder.toString(), StandardCharsets.UTF_8);
    }

    private void syncMarkdownIndexes(String userId) throws Exception {
        String normalizedUserId = normalizeUserId(userId);
        Files.createDirectories(userRoot(normalizedUserId));
        writeUserMemoryFile(normalizedUserId);
        writeDailyMemoryFile(normalizedUserId, LocalDate.now(MARKDOWN_ZONE));
    }

    private void writeUserMemoryFile(String userId) throws Exception {
        List<MemoryItem> activeItems = list(userId, null, "active", 500);
        Path path = userRoot(userId).resolve(USER_MEMORY_FILE);
        StringBuilder builder = new StringBuilder();
        builder.append("# ClawAgent Memory\n\n");
        builder.append("> 只包含 active 长期记忆；pending/conflict/disabled/archived 不会进入模型上下文。\n\n");
        for (String scope : List.of("global", "channel", "session")) {
            List<MemoryItem> scopeItems = activeItems.stream()
                    .filter(item -> scope.equals(item.scopeType()))
                    .sorted(Comparator.comparing(MemoryItem::updatedAt).reversed())
                    .toList();
            if (scopeItems.isEmpty()) {
                continue;
            }
            builder.append("## ").append(scope).append("\n\n");
            for (MemoryItem item : scopeItems) {
                appendMarkdownItem(builder, item, true);
            }
        }
        Files.writeString(path, builder.toString(), StandardCharsets.UTF_8);
    }

    private void writeDailyMemoryFile(String userId, LocalDate date) throws Exception {
        List<MemoryItem> items = list(userId, null, null, 500).stream()
                .filter(item -> LocalDate.ofInstant(item.createdAt(), MARKDOWN_ZONE).equals(date))
                .sorted(Comparator.comparing(MemoryItem::createdAt))
                .toList();
        Path dailyDir = userRoot(userId).resolve(DAILY_DIR);
        Files.createDirectories(dailyDir);
        Path path = dailyDir.resolve(date + ".md");
        StringBuilder builder = new StringBuilder();
        builder.append("# ").append(date).append(" 记忆过程\n\n");
        builder.append("> 记录当天候选、冲突、归档和 active 记忆，供人工回溯；模型上下文不会直接注入整份 daily 文件。\n\n");
        if (items.isEmpty()) {
            builder.append("暂无记忆变更。\n");
        } else {
            for (MemoryItem item : items) {
                appendMarkdownItem(builder, item, false);
            }
        }
        Files.writeString(path, builder.toString(), StandardCharsets.UTF_8);
    }

    private void appendMarkdownItem(StringBuilder builder, MemoryItem item, boolean concise) {
        builder.append("- ").append(item.summary()).append('\n');
        builder.append("  - id: `").append(item.id()).append("`\n");
        builder.append("  - scope: `").append(item.scopeType()).append('`');
        if (!isBlank(item.scopeId())) {
            builder.append(" / `").append(item.scopeId()).append('`');
        }
        builder.append("\n");
        builder.append("  - type/status: `").append(item.type()).append("` / `").append(item.status()).append("`\n");
        builder.append("  - updatedAt: ").append(item.updatedAt()).append('\n');
        if (!concise) {
            builder.append("  - content: ").append(preview(item.content(), 260)).append('\n');
        }
        if (!item.metadata().isEmpty()) {
            builder.append("  - metadata: `").append(preview(item.metadata().toString(), 260)).append("`\n");
        }
        builder.append('\n');
    }

    private Path markdownPath(MemoryItem item) {
        return filesRoot.resolve(safePathPart(item.userId())).resolve(item.id() + ".md").normalize();
    }

    private Path userRoot(String userId) {
        return filesRoot.resolve(safePathPart(normalizeUserId(userId))).normalize();
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

    private Map<String, String> withMetadata(Map<String, String> base, Map<String, String> extra) {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (base != null) {
            metadata.putAll(base);
        }
        if (extra != null) {
            extra.forEach((key, value) -> {
                if (!isBlank(key) && value != null) {
                    metadata.put(key, value);
                }
            });
        }
        return metadata;
    }

    private int parseInt(String value, int fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private double parseDouble(String value, double fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String writeJson(Map<String, String> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (Exception e) {
            throw new IllegalStateException("序列化记忆 metadata 失败", e);
        }
    }

    private Optional<Instant> parseInstant(String value) {
        try {
            return isBlank(value) ? Optional.empty() : Optional.of(Instant.parse(value));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private String formatScore(double value) {
        return String.format("%.4f", clamp01(value));
    }

    private String columnOrNull(ResultSet rs, String preferred, String fallback) throws SQLException {
        try {
            return rs.getString(preferred);
        } catch (SQLException ignored) {
            return rs.getString(fallback);
        }
    }

    private String firstNonBlank(String first, String second) {
        return isBlank(first) ? nullToEmpty(second) : first;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * @param staleAfterDays 超过该天数未命中后开始降权。
     * @param veryStaleAfterDays 超过该天数未命中后降权趋近上限。
     * @param autoArchiveEnabled 是否启用自动归档。
     * @param archiveAfterDays 超过该天数未命中时归档，0 表示关闭该条件。
     * @param archiveBelowQuality 质量分低于该阈值时归档，0 表示关闭该条件。
     */
    public record GovernanceOptions(
            int staleAfterDays,
            int veryStaleAfterDays,
            boolean autoArchiveEnabled,
            int archiveAfterDays,
            double archiveBelowQuality
    ) {
        public GovernanceOptions {
            staleAfterDays = Math.max(0, staleAfterDays);
            veryStaleAfterDays = Math.max(1, veryStaleAfterDays);
            archiveAfterDays = Math.max(0, archiveAfterDays);
            archiveBelowQuality = Math.max(0.0, Math.min(1.0, archiveBelowQuality));
        }

        public static GovernanceOptions defaults() {
            return new GovernanceOptions(30, 180, false, 365, 0.15);
        }
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
     * @param item 相似记忆条目。
     * @param score 轻量文本相似度分数。
     */
    private record SimilarMemory(
            MemoryItem item,
            double score
    ) {
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
