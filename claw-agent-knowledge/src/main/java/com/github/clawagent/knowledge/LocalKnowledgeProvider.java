package com.github.clawagent.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.clawagent.core.KnowledgeDocument;
import com.github.clawagent.core.KnowledgeSearchResult;
import com.github.clawagent.core.StoredFile;
import com.github.clawagent.spi.EmbeddingClient;
import com.github.clawagent.spi.EmbeddingOptions;
import com.github.clawagent.spi.EmbeddingResult;
import com.github.clawagent.spi.KnowledgeProvider;
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
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 本地知识库 provider。
 * metadata/chunk/FTS 与现有 clawagent.db 共库，文件和向量索引文件放在 .clawagent/knowledge 下。
 */
public class LocalKnowledgeProvider implements KnowledgeProvider {
    private static final Logger log = LoggerFactory.getLogger(LocalKnowledgeProvider.class);
    private static final int CHUNK_CHARS = 3_000;
    private static final int CHUNK_OVERLAP_CHARS = 250;
    private static final double MIN_VECTOR_SCORE = 0.88;
    private static final String JVECTOR_GRAPH_FILE = "jvector.graph";
    private static final String JVECTOR_MAPPING_FILE = "jvector-chunks.json";
    private static final int JVECTOR_MAX_CONNECTIONS = 16;
    private static final int JVECTOR_BEAM_WIDTH = 64;
    private static final float JVECTOR_OVERFLOW_RATIO = 1.2f;
    private static final float JVECTOR_ALPHA = 1.2f;
    private static final double RRF_K = 60.0;

    private final Path databasePath;
    private final Path filesRoot;
    private final Path vectorsRoot;
    private final EmbeddingClient embeddingClient;
    private final EmbeddingOptions embeddingOptions;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Tika tika = new Tika();

    /**
     * @param databasePath 复用的 clawagent.db 路径。
     * @param filesRoot 本地原文件保存目录。
     * @param vectorsRoot JVector 图索引落盘目录；向量原始值会镜像存入 SQLite，便于重建索引。
     * @param embeddingClient 复用项目已有 embedding 客户端。
     * @param embeddingOptions embedding 调用参数。
     */
    public LocalKnowledgeProvider(Path databasePath,
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
                "fileStorage", true,
                "vectorSearch", true,
                "bm25", true,
                "hybridSearch", true,
                "asyncParsing", false);
    }

    @Override
    public KnowledgeDocument ingest(String userId,
                                    String name,
                                    String contentType,
                                    String kind,
                                    byte[] content,
                                    Map<String, String> metadata) {
        String normalizedUserId = normalizeUserId(userId);
        byte[] safeContent = content == null ? new byte[0] : content;
        try {
            String contentHash = sha256Bytes(safeContent);
            Optional<KnowledgeDocument> existing = findDocumentByContentHash(normalizedUserId, contentHash);
            if (existing.isPresent()) {
                // 同一用户重复上传同一份文件时复用已有知识库文档，避免重复 chunk、FTS 和 JVector 索引。
                return existing.get();
            }
            String documentId = UUID.randomUUID().toString();
            Instant now = Instant.now();
            Path storedPath = storeFile(documentId, name, safeContent);
            String readable = extractReadableText(contentType, kind, safeContent);
            List<String> chunks = splitChunks(readable);
            Map<String, String> documentMetadata = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
            documentMetadata.put("contentType", safeContentType(contentType));
            documentMetadata.put("contentHash", contentHash);
            documentMetadata.put("chunkCount", String.valueOf(chunks.size()));

            try (Connection connection = connect()) {
                connection.setAutoCommit(false);
                insertDocument(connection, new KnowledgeDocument(
                        documentId,
                        normalizedUserId,
                        id(),
                        documentId,
                        safeName(name),
                        safeKind(kind),
                        safeContent.length,
                        filesRoot.relativize(storedPath).toString(),
                        "READY",
                        documentMetadata,
                        now,
                        now));
                insertChunks(connection, documentId, normalizedUserId, chunks);
                connection.commit();
            }
            // userId 是向量隔离边界，JVector 图索引也按用户目录分开重建和落盘。
            rebuildJVectorIndex(normalizedUserId);
            return findDocument(normalizedUserId, documentId).orElseThrow();
        } catch (Exception e) {
            throw new IllegalStateException("知识库文档入库失败：" + safeName(name), e);
        }
    }

    @Override
    public List<KnowledgeDocument> list(String userId, int limit) {
        try (Connection connection = connect();
             PreparedStatement ps = connection.prepareStatement(
                     "select * from knowledge_document where user_id = ? order by created_at desc limit ?")) {
            ps.setString(1, normalizeUserId(userId));
            ps.setInt(2, Math.max(1, Math.min(limit, 200)));
            try (ResultSet rs = ps.executeQuery()) {
                List<KnowledgeDocument> documents = new ArrayList<>();
                while (rs.next()) {
                    documents.add(readDocument(rs));
                }
                return documents;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询知识库文档失败", e);
        }
    }

    @Override
    public List<KnowledgeSearchResult> search(String userId, String query, List<String> documentIds, String mode, int topK) {
        SearchSpec spec = new SearchSpec(normalizeUserId(userId), query == null ? "" : query,
                documentIds == null ? List.of() : documentIds.stream().filter(id -> id != null && !id.isBlank()).toList(),
                mode == null || mode.isBlank() ? "hybrid" : mode,
                Math.max(1, topK));
        Map<String, KnowledgeSearchResult> hits = new LinkedHashMap<>();
        Map<String, Double> scores = new HashMap<>();
        if (!"vector".equalsIgnoreCase(spec.mode())) {
            mergeHits(hits, scores, keywordSearch(spec), 0.55);
        }
        if (!"keyword".equalsIgnoreCase(spec.mode())) {
            mergeHits(hits, scores, vectorSearch(spec), 0.45);
        }
        return hits.values().stream()
                .map(hit -> copyWithScore(hit, scores.getOrDefault(hit.chunkId(), hit.score())))
                .sorted(Comparator.comparingDouble(KnowledgeSearchResult::score).reversed())
                .limit(spec.topK())
                .toList();
    }

    @Override
    public List<KnowledgeSearchResult> readDocumentChunks(String userId, List<String> documentIds, int maxChunks) {
        String normalizedUserId = normalizeUserId(userId);
        List<String> safeDocumentIds = safeDocumentIds(documentIds);
        String sql = "select c.*, d.provider, d.name, d.created_at from knowledge_chunk c " +
                "join knowledge_document d on d.id = c.document_id " +
                "where c.user_id = ? " + directReadDocumentFilterSql(safeDocumentIds) +
                " order by d.created_at desc, c.chunk_no limit ?";
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, normalizedUserId);
            int bindIndex = 2;
            for (String documentId : safeDocumentIds) {
                ps.setString(bindIndex++, documentId);
            }
            ps.setInt(bindIndex, Math.max(1, maxChunks));
            try (ResultSet rs = ps.executeQuery()) {
                List<KnowledgeSearchResult> chunks = new ArrayList<>();
                while (rs.next()) {
                    chunks.add(readHit(rs, 1.0));
                }
                if (safeDocumentIds.isEmpty()) {
                    return chunks;
                }
                Map<String, Integer> order = new LinkedHashMap<>();
                for (int i = 0; i < safeDocumentIds.size(); i++) {
                    order.put(safeDocumentIds.get(i), i);
                }
                // 直接读取用于总结/概览，必须尊重用户选择文档的顺序和文档内 chunk 顺序。
                return chunks.stream()
                        .sorted(Comparator
                                .comparingInt((KnowledgeSearchResult hit) -> order.getOrDefault(hit.documentId(), Integer.MAX_VALUE))
                                .thenComparingInt(KnowledgeSearchResult::chunkNo))
                        .toList();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取知识库文档内容失败", e);
        }
    }

    @Override
    public StoredFile download(String userId, String documentId) {
        KnowledgeDocument document = findDocument(userId, documentId)
                .orElseThrow(() -> new IllegalArgumentException("知识库文档不存在或无权访问：" + documentId));
        Path file = filesRoot.resolve(document.storagePath()).normalize();
        if (!file.startsWith(filesRoot) || !Files.exists(file)) {
            throw new IllegalArgumentException("知识库文件不存在：" + documentId);
        }
        String contentType = document.metadata().getOrDefault("contentType", "application/octet-stream");
        return new StoredFile(document.id(), document.name(), contentType, document.size(), id(),
                document.storagePath(), file, document.metadata());
    }

    @Override
    public void delete(String userId, String documentId) {
        KnowledgeDocument document = findDocument(userId, documentId)
                .orElseThrow(() -> new IllegalArgumentException("知识库文档不存在或无权访问：" + documentId));
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            // 删除顺序保持 SQLite、FTS、向量表一致，避免后台列表看不到但检索仍能召回。
            deleteByDocument(connection, "knowledge_chunk_fts", document.id());
            deleteByDocument(connection, "knowledge_vector", document.id());
            deleteByDocument(connection, "knowledge_chunk", document.id());
            try (PreparedStatement ps = connection.prepareStatement("delete from knowledge_document where id = ? and user_id = ?")) {
                ps.setString(1, document.id());
                ps.setString(2, normalizeUserId(userId));
                ps.executeUpdate();
            }
            connection.commit();
            Path file = filesRoot.resolve(document.storagePath()).normalize();
            if (file.startsWith(filesRoot)) {
                Files.deleteIfExists(file);
            }
            // 文档删除后重建当前用户索引，避免 JVector 图里保留已删除 chunk。
            rebuildJVectorIndex(normalizeUserId(userId));
        } catch (Exception e) {
            throw new IllegalStateException("删除知识库文档失败：" + documentId, e);
        }
    }

    private void initialize() {
        try {
            Files.createDirectories(databasePath.getParent());
            Files.createDirectories(filesRoot);
            Files.createDirectories(vectorsRoot);
            try (Connection connection = connect(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("create table if not exists knowledge_document (" +
                        "id text primary key, user_id text, provider text, provider_document_id text, name text, " +
                        "kind text, size integer, storage_path text, status text, metadata text, created_at text, updated_at text)");
                ensureDocumentContentHashColumn(connection);
                statement.executeUpdate("create table if not exists knowledge_chunk (" +
                        "id text primary key, document_id text, user_id text, chunk_no integer, text text, " +
                        "content_hash text, token_estimate integer, metadata text)");
                statement.executeUpdate("create virtual table if not exists knowledge_chunk_fts using fts5(" +
                        "chunk_id unindexed, document_id unindexed, user_id unindexed, text)");
                statement.executeUpdate("create table if not exists knowledge_vector (" +
                        "chunk_id text primary key, document_id text, user_id text, vector text)");
                statement.executeUpdate("create index if not exists idx_knowledge_document_user on knowledge_document(user_id, created_at)");
                statement.executeUpdate("create index if not exists idx_knowledge_document_hash on knowledge_document(user_id, content_hash)");
                statement.executeUpdate("create index if not exists idx_knowledge_chunk_user_doc on knowledge_chunk(user_id, document_id)");
            }
            backfillDocumentContentHash();
            deduplicateDocumentsByContentHash();
        } catch (Exception e) {
            throw new IllegalStateException("初始化本地知识库失败", e);
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + databasePath);
    }

    private void insertDocument(Connection connection, KnowledgeDocument document) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "insert into knowledge_document(id, user_id, provider, provider_document_id, name, kind, size, storage_path, status, metadata, created_at, updated_at, content_hash) " +
                        "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, document.id());
            ps.setString(2, document.userId());
            ps.setString(3, document.provider());
            ps.setString(4, document.providerDocumentId());
            ps.setString(5, document.name());
            ps.setString(6, document.kind());
            ps.setLong(7, document.size());
            ps.setString(8, document.storagePath());
            ps.setString(9, document.status());
            ps.setString(10, objectMapper.writeValueAsString(document.metadata()));
            ps.setString(11, document.createdAt().toString());
            ps.setString(12, document.updatedAt().toString());
            ps.setString(13, document.metadata().getOrDefault("contentHash", ""));
            ps.executeUpdate();
        }
    }

    private void insertChunks(Connection connection, String documentId, String userId, List<String> chunks) throws Exception {
        try (PreparedStatement chunk = connection.prepareStatement("insert into knowledge_chunk values (?, ?, ?, ?, ?, ?, ?, ?)");
             PreparedStatement fts = connection.prepareStatement("insert into knowledge_chunk_fts(chunk_id, document_id, user_id, text) values (?, ?, ?, ?)");
             PreparedStatement vector = connection.prepareStatement("insert or replace into knowledge_vector values (?, ?, ?, ?)")) {
            for (int i = 0; i < chunks.size(); i++) {
                String text = chunks.get(i);
                String chunkId = documentId + "-" + String.format("%04d", i + 1);
                chunk.setString(1, chunkId);
                chunk.setString(2, documentId);
                chunk.setString(3, userId);
                chunk.setInt(4, i + 1);
                chunk.setString(5, text);
                chunk.setString(6, sha256(text));
                chunk.setInt(7, estimateTokens(text));
                chunk.setString(8, "{}");
                chunk.addBatch();

                fts.setString(1, chunkId);
                fts.setString(2, documentId);
                fts.setString(3, userId);
                fts.setString(4, text);
                fts.addBatch();

                List<Double> embedding = embed(text);
                if (!embedding.isEmpty()) {
                    vector.setString(1, chunkId);
                    vector.setString(2, documentId);
                    vector.setString(3, userId);
                    vector.setString(4, objectMapper.writeValueAsString(embedding));
                    vector.addBatch();
                }
            }
            chunk.executeBatch();
            fts.executeBatch();
            vector.executeBatch();
        }
    }

    private List<KnowledgeSearchResult> keywordSearch(SearchSpec spec) {
        List<KnowledgeSearchResult> fts = ftsSearch(spec);
        if (!fts.isEmpty()) {
            return fts;
        }
        return likeSearch(spec);
    }

    private List<KnowledgeSearchResult> ftsSearch(SearchSpec spec) {
        String query = ftsQuery(spec.query());
        if (query.isBlank()) {
            return List.of();
        }
        String sql = "select c.*, d.provider, d.name, bm25(knowledge_chunk_fts) as rank " +
                "from knowledge_chunk_fts join knowledge_chunk c on c.id = knowledge_chunk_fts.chunk_id " +
                "join knowledge_document d on d.id = c.document_id " +
                "where knowledge_chunk_fts match ? and c.user_id = ? " + documentFilterSql(spec) +
                " order by rank limit ?";
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, query);
            ps.setString(2, spec.userId());
            bindDocumentFilter(ps, 3, spec);
            ps.setInt(3 + spec.documentIds().size(), spec.topK() * 4);
            try (ResultSet rs = ps.executeQuery()) {
                List<KnowledgeSearchResult> hits = new ArrayList<>();
                while (rs.next()) {
                    double rank = rs.getDouble("rank");
                    hits.add(readHit(rs, 1.0 / (1.0 + Math.abs(rank))));
                }
                return hits;
            }
        } catch (SQLException e) {
            log.debug("knowledge fts search fallback reason={}", e.getMessage());
            return List.of();
        }
    }

    private List<KnowledgeSearchResult> likeSearch(SearchSpec spec) {
        if (spec.query().isBlank()) {
            return List.of();
        }
        String sql = "select c.*, d.provider, d.name from knowledge_chunk c join knowledge_document d on d.id = c.document_id " +
                "where c.user_id = ? and c.text like ? " + documentFilterSql(spec) + " order by c.chunk_no limit ?";
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, spec.userId());
            ps.setString(2, "%" + spec.query() + "%");
            bindDocumentFilter(ps, 3, spec);
            ps.setInt(3 + spec.documentIds().size(), spec.topK() * 4);
            try (ResultSet rs = ps.executeQuery()) {
                List<KnowledgeSearchResult> hits = new ArrayList<>();
                while (rs.next()) {
                    hits.add(readHit(rs, 0.75));
                }
                return hits;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("知识库关键词检索失败", e);
        }
    }

    private List<KnowledgeSearchResult> vectorSearch(SearchSpec spec) {
        List<Double> queryVector = embed(spec.query());
        if (queryVector.isEmpty()) {
            return List.of();
        }
        try (JVectorIndex index = loadJVectorIndex(spec.userId())) {
            if (index == null || index.records().isEmpty() || queryVector.size() != index.dimension()) {
                return List.of();
            }
            VectorFloat<?> vector = toJVectorVector(queryVector);
            Bits acceptOrds = acceptOrds(index.records(), spec.documentIds());
            int limit = Math.min(index.records().size(), Math.max(1, spec.topK() * 4));
            SearchResult result = GraphSearcher.search(vector, limit, index.vectorValues(), VectorSimilarityFunction.COSINE, index.graph(), acceptOrds);
            Map<String, Double> scores = new LinkedHashMap<>();
            for (SearchResult.NodeScore nodeScore : result.getNodes()) {
                // JVector 的 COSINE 分数是 (1 + cosine) / 2，这里转回原始 cosine，保持原有阈值语义。
                double cosineScore = Math.max(0, nodeScore.score * 2.0 - 1.0);
                if (cosineScore >= MIN_VECTOR_SCORE) {
                    scores.put(index.records().get(nodeScore.node).chunkId(), cosineScore);
                }
            }
            return readHitsByChunkIds(scores).stream()
                    .sorted(Comparator.comparingDouble(KnowledgeSearchResult::score).reversed())
                    .limit(spec.topK() * 4L)
                    .toList();
        } catch (Exception e) {
            log.debug("knowledge vector search skipped reason={}", e.getMessage());
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
        // JVector 图按 userId 独立构建，避免不同用户的 chunk 进入同一个 ANN 搜索空间。
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
        String sql = "select chunk_id, document_id, user_id, vector from knowledge_vector where user_id = ? order by chunk_id";
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, normalizeUserId(userId));
            try (ResultSet rs = ps.executeQuery()) {
                List<IndexedVector> records = new ArrayList<>();
                while (rs.next()) {
                    List<Double> vector = objectMapper.readValue(rs.getString("vector"), new TypeReference<List<Double>>() {});
                    if (!vector.isEmpty()) {
                        records.add(new IndexedVector(
                                rs.getString("chunk_id"),
                                rs.getString("document_id"),
                                rs.getString("user_id"),
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

    private Bits acceptOrds(List<IndexedVector> records, List<String> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return Bits.ALL;
        }
        return new DocumentBits(records, documentIds);
    }

    private List<KnowledgeSearchResult> readHitsByChunkIds(Map<String, Double> scores) {
        if (scores == null || scores.isEmpty()) {
            return List.of();
        }
        String sql = "select c.*, d.provider, d.name from knowledge_chunk c join knowledge_document d on d.id = c.document_id " +
                "where c.id in (" + "?,".repeat(scores.size()).replaceFirst(",$", "") + ")";
        try (Connection connection = connect(); PreparedStatement ps = connection.prepareStatement(sql)) {
            int index = 1;
            for (String chunkId : scores.keySet()) {
                ps.setString(index++, chunkId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                Map<String, KnowledgeSearchResult> hits = new LinkedHashMap<>();
                while (rs.next()) {
                    String chunkId = rs.getString("id");
                    hits.put(chunkId, readHit(rs, scores.getOrDefault(chunkId, 0.0)));
                }
                return scores.keySet().stream()
                        .map(hits::get)
                        .filter(hit -> hit != null)
                        .toList();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("知识库向量结果回查失败", e);
        }
    }

    private void mergeHits(Map<String, KnowledgeSearchResult> hits,
                           Map<String, Double> scores,
                           List<KnowledgeSearchResult> next,
                           double weight) {
        for (int i = 0; i < next.size(); i++) {
            KnowledgeSearchResult hit = next.get(i);
            hits.putIfAbsent(hit.chunkId(), hit);
            // OpenClaw 口径的 RRF：只使用各路召回排名，不混入 BM25 或向量原始分数。
            double rankScore = weight / (RRF_K + i + 1);
            scores.merge(hit.chunkId(), rankScore, Double::sum);
        }
    }

    private KnowledgeSearchResult readHit(ResultSet rs, double score) throws SQLException {
        return new KnowledgeSearchResult(
                rs.getString("document_id"),
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getString("name"),
                rs.getInt("chunk_no"),
                rs.getString("text"),
                score,
                rs.getString("provider"),
                parseMap(rs.getString("metadata")));
    }

    private KnowledgeSearchResult copyWithScore(KnowledgeSearchResult hit, double score) {
        return new KnowledgeSearchResult(hit.documentId(), hit.chunkId(), hit.userId(), hit.documentName(),
                hit.chunkNo(), hit.text(), score, hit.provider(), hit.metadata());
    }

    private Optional<KnowledgeDocument> findDocument(String userId, String documentId) {
        try (Connection connection = connect();
             PreparedStatement ps = connection.prepareStatement("select * from knowledge_document where id = ? and user_id = ?")) {
            ps.setString(1, documentId);
            ps.setString(2, normalizeUserId(userId));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readDocument(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询知识库文档失败：" + documentId, e);
        }
    }

    private Optional<KnowledgeDocument> findDocumentByContentHash(String userId, String contentHash) {
        if (contentHash == null || contentHash.isBlank()) {
            return Optional.empty();
        }
        try (Connection connection = connect();
             PreparedStatement ps = connection.prepareStatement(
                     "select * from knowledge_document where user_id = ? and content_hash = ? order by created_at asc limit 1")) {
            ps.setString(1, normalizeUserId(userId));
            ps.setString(2, contentHash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readDocument(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询重复知识库文档失败", e);
        }
    }

    private void ensureDocumentContentHashColumn(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("pragma table_info(knowledge_document)")) {
            while (rs.next()) {
                if ("content_hash".equalsIgnoreCase(rs.getString("name"))) {
                    return;
                }
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("alter table knowledge_document add column content_hash text");
        }
    }

    private void backfillDocumentContentHash() {
        List<DocumentHashUpdate> updates = new ArrayList<>();
        try (Connection connection = connect()) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "select id, storage_path, metadata from knowledge_document where content_hash is null or content_hash = ''");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String hash = parseMap(rs.getString("metadata")).getOrDefault("contentHash", "");
                    if (hash.isBlank()) {
                        Path file = filesRoot.resolve(rs.getString("storage_path")).normalize();
                        if (file.startsWith(filesRoot) && Files.exists(file)) {
                            hash = sha256Bytes(Files.readAllBytes(file));
                        }
                    }
                    if (!hash.isBlank()) {
                        updates.add(new DocumentHashUpdate(rs.getString("id"), hash));
                    }
                }
            }
            try (PreparedStatement update = connection.prepareStatement("update knowledge_document set content_hash = ? where id = ?")) {
                for (DocumentHashUpdate item : updates) {
                    update.setString(1, item.contentHash());
                    update.setString(2, item.documentId());
                    update.addBatch();
                }
                update.executeBatch();
            }
        } catch (Exception e) {
            log.debug("knowledge content hash backfill skipped reason={}", e.getMessage());
        }
    }

    private void deduplicateDocumentsByContentHash() {
        Map<String, List<KnowledgeDocument>> groups = new LinkedHashMap<>();
        try (Connection connection = connect();
             PreparedStatement ps = connection.prepareStatement(
                     "select * from knowledge_document where content_hash is not null and content_hash <> '' order by user_id, content_hash, created_at asc");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                KnowledgeDocument document = readDocument(rs);
                String key = document.userId() + "\n" + rs.getString("content_hash");
                groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(document);
            }
        } catch (SQLException e) {
            log.debug("knowledge duplicate scan skipped reason={}", e.getMessage());
            return;
        }

        List<String> affectedUsers = new ArrayList<>();
        for (List<KnowledgeDocument> documents : groups.values()) {
            if (documents.size() <= 1) {
                continue;
            }
            KnowledgeDocument kept = documents.get(0);
            for (int i = 1; i < documents.size(); i++) {
                // 去重只删除重复副本，保留最早入库文档，避免知识库列表被同一文件刷屏。
                removeDuplicateDocument(documents.get(i));
            }
            affectedUsers.add(kept.userId());
        }
        affectedUsers.stream().distinct().forEach(userId -> {
            try {
                rebuildJVectorIndex(userId);
            } catch (Exception e) {
                log.debug("knowledge duplicate rebuild skipped userId={} reason={}", userId, e.getMessage());
            }
        });
    }

    private void removeDuplicateDocument(KnowledgeDocument document) {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            deleteByDocument(connection, "knowledge_chunk_fts", document.id());
            deleteByDocument(connection, "knowledge_vector", document.id());
            deleteByDocument(connection, "knowledge_chunk", document.id());
            try (PreparedStatement ps = connection.prepareStatement("delete from knowledge_document where id = ? and user_id = ?")) {
                ps.setString(1, document.id());
                ps.setString(2, document.userId());
                ps.executeUpdate();
            }
            connection.commit();
            Path file = filesRoot.resolve(document.storagePath()).normalize();
            if (file.startsWith(filesRoot)) {
                Files.deleteIfExists(file);
            }
        } catch (Exception e) {
            log.debug("knowledge duplicate remove skipped documentId={} reason={}", document.id(), e.getMessage());
        }
    }

    private KnowledgeDocument readDocument(ResultSet rs) throws SQLException {
        return new KnowledgeDocument(
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getString("provider"),
                rs.getString("provider_document_id"),
                rs.getString("name"),
                rs.getString("kind"),
                rs.getLong("size"),
                rs.getString("storage_path"),
                rs.getString("status"),
                parseMap(rs.getString("metadata")),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")));
    }

    private Path storeFile(String documentId, String name, byte[] content) throws Exception {
        Path dayDir = filesRoot.resolve(DateTimeFormatter.BASIC_ISO_DATE.format(LocalDate.now())).normalize();
        Files.createDirectories(dayDir);
        Path target = dayDir.resolve(documentId + "-" + sanitizeName(name)).normalize();
        if (!target.startsWith(dayDir)) {
            throw new IllegalArgumentException("非法知识库文件路径");
        }
        Files.write(target, content);
        return target;
    }

    private String extractReadableText(String contentType, String kind, byte[] content) throws Exception {
        if (content.length == 0) {
            return "";
        }
        if (safeContentType(contentType).startsWith("text/") || "text".equalsIgnoreCase(kind)) {
            return normalizeText(new String(content, StandardCharsets.UTF_8));
        }
        try (ByteArrayInputStream input = new ByteArrayInputStream(content)) {
            return normalizeText(tika.parseToString(input));
        }
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
        if (embeddingClient == null || text == null || text.isBlank()) {
            return List.of();
        }
        try {
            EmbeddingResult result = embeddingClient.embed(text, embeddingOptions);
            return result.vector();
        } catch (RuntimeException e) {
            log.debug("knowledge embedding skipped reason={}", e.getMessage());
            return List.of();
        }
    }

    private double cosine(List<Double> left, List<Double> right) {
        int size = Math.min(left.size(), right.size());
        if (size == 0) {
            return 0;
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < size; i++) {
            double a = left.get(i);
            double b = right.get(i);
            dot += a * b;
            leftNorm += a * a;
            rightNorm += b * b;
        }
        return leftNorm == 0 || rightNorm == 0 ? 0 : dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private String documentFilterSql(SearchSpec spec) {
        if (spec.documentIds().isEmpty()) {
            return "";
        }
        return " and c.document_id in (" + "?,".repeat(spec.documentIds().size()).replaceFirst(",$", "") + ")";
    }

    private String directReadDocumentFilterSql(List<String> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return "";
        }
        return " and c.document_id in (" + "?,".repeat(documentIds.size()).replaceFirst(",$", "") + ")";
    }

    private void bindDocumentFilter(PreparedStatement ps, int startIndex, SearchSpec spec) throws SQLException {
        for (int i = 0; i < spec.documentIds().size(); i++) {
            ps.setString(startIndex + i, spec.documentIds().get(i));
        }
    }

    private List<String> safeDocumentIds(List<String> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return List.of();
        }
        return documentIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private void deleteByDocument(Connection connection, String table, String documentId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("delete from " + table + " where document_id = ?")) {
            ps.setString(1, documentId);
            ps.executeUpdate();
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
        if (query == null || query.isBlank()) {
            return "";
        }
        return "\"" + query.replace("\"", "\"\"").trim() + "\"";
    }

    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t ]+\\n", "\n")
                .trim();
    }

    private String sanitizeName(String name) {
        String value = name == null || name.isBlank() ? "document" : name;
        return value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
    }

    private String safePathPart(String value) {
        return sanitizeName(value).replace(' ', '_');
    }

    private Path userVectorDir(String userId) {
        return vectorsRoot.resolve(safePathPart(normalizeUserId(userId))).normalize();
    }

    private String normalizeUserId(String userId) {
        return userId == null || userId.isBlank() ? "anonymous" : userId.trim();
    }

    private String safeName(String name) {
        return name == null || name.isBlank() ? "document" : name;
    }

    private String safeKind(String kind) {
        return kind == null || kind.isBlank() ? "text" : kind;
    }

    private String safeContentType(String contentType) {
        return contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
    }

    private int estimateTokens(String text) {
        return text == null ? 0 : Math.max(1, text.length() / 4);
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }

    private String sha256Bytes(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(content == null ? new byte[0] : content);
            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }

    /**
     * @param documentId 待回填 content_hash 的知识库文档 ID。
     * @param contentHash 原文件内容 SHA-256。
     */
    private record DocumentHashUpdate(
            String documentId,
            String contentHash
    ) {
    }

    /**
     * JVector 图里的 ordinal 到知识库 chunk 的映射。
     *
     * @param chunkId chunk 主键。
     * @param documentId 文档主键，用于检索时按选择文档过滤。
     * @param userId 用户 ID，用于校验索引隔离。
     * @param vector embedding 向量值，加载图索引时作为 JVector 的向量源。
     */
    private record IndexedVector(
            String chunkId,
            String documentId,
            String userId,
            List<Double> vector
    ) {
    }

    /**
     * 已加载的 JVector 图索引和 ordinal 映射。
     *
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
     * JVector 搜索过滤器，用于“只检索选中文档/附件文档”的场景。
     */
    private static class DocumentBits implements Bits {
        private final List<IndexedVector> records;
        private final List<String> documentIds;

        private DocumentBits(List<IndexedVector> records, List<String> documentIds) {
            this.records = records;
            this.documentIds = documentIds == null ? List.of() : documentIds;
        }

        @Override
        public boolean get(int index) {
            if (index < 0 || index >= records.size()) {
                return false;
            }
            return documentIds.contains(records.get(index).documentId());
        }
    }

    /**
     * 本地检索参数，作为 provider 内部实现细节，避免向 SPI 暴露过细 DTO。
     */
    private record SearchSpec(
            String userId,
            String query,
            List<String> documentIds,
            String mode,
            int topK
    ) {
    }
}
