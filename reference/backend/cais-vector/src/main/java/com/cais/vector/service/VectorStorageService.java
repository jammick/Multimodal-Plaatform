package com.cais.vector.service;

import com.cais.common.api.ErrorCode;
import com.cais.common.exception.BizException;
import com.cais.vector.domain.CollectionRequest;
import com.cais.vector.domain.SearchRequest;
import com.cais.vector.domain.VectorEntryRequest;
import com.cais.vector.dto.VectorCollectionView;
import com.cais.vector.model.EmbeddingClient;
import com.cais.vector.model.EmbeddingBridgeProperties;
import com.cais.vector.model.EmbeddingRuntimeResolver;
import com.cais.vector.persistence.VectorTracePersistence;
import com.cais.vector.persistence.VectorCollectionMetadataStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import com.cais.vector.store.SemanticVectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 向量集合、文件分块关联和一致性校验服务。
 * 控制面元数据在 DM8 部署模式下持久化，语义写入与检索通过 SemanticVectorStore 委派。
 */
@Service
public class VectorStorageService {
    private static final Logger LOG = LoggerFactory.getLogger(VectorStorageService.class);
    private final Map<String, Collection> collections = new ConcurrentHashMap<>();
    private final Map<String, List<VectorEntry>> entries = new ConcurrentHashMap<>();
    private final Map<String, String> idsByName = new ConcurrentHashMap<>();
    private final SemanticVectorStore vectorStore;
    private final EmbeddingClient embeddingClient;
    private final EmbeddingBridgeProperties embeddingProperties;
    private final EmbeddingRuntimeResolver embeddingResolver;
    private final VectorTracePersistence tracePersistence;
    private final VectorCollectionMetadataStore metadataStore;
    private final java.util.concurrent.atomic.LongAdder searchCount = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder searchLatencyNanos = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder indexBuildCount = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder indexBuildSuccess = new java.util.concurrent.atomic.LongAdder();
    private QueryRateLimiter rateLimiter;
    /** 片段可见性：归档或撤回的文档不参与检索（默认全部可见）。 */
    private volatile java.util.function.Predicate<String> chunkVisible = chunkId -> true;

    @Autowired(required = false)
    public void setRateLimiter(QueryRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    public VectorStorageService(SemanticVectorStore vectorStore, EmbeddingClient embeddingClient) {
        this(vectorStore, embeddingClient, VectorTracePersistence.disabled(), VectorCollectionMetadataStore.disabled(), null);
    }

    @Autowired
    public VectorStorageService(SemanticVectorStore vectorStore, EmbeddingClient embeddingClient,
                                ObjectProvider<VectorTracePersistence> tracePersistenceProvider,
                                ObjectProvider<VectorCollectionMetadataStore> metadataStoreProvider,
                                EmbeddingBridgeProperties embeddingProperties,
                                EmbeddingRuntimeResolver embeddingResolver) {
        this(vectorStore, embeddingClient, tracePersistenceProvider.getIfAvailable(VectorTracePersistence::disabled),
                metadataStoreProvider.getIfAvailable(VectorCollectionMetadataStore::disabled), embeddingProperties,
                embeddingResolver);
    }

    public VectorStorageService(SemanticVectorStore vectorStore, EmbeddingClient embeddingClient,
                                VectorTracePersistence tracePersistence) {
        this(vectorStore, embeddingClient, tracePersistence, VectorCollectionMetadataStore.disabled(), null);
    }

    public VectorStorageService(SemanticVectorStore vectorStore, EmbeddingClient embeddingClient,
                                VectorTracePersistence tracePersistence, VectorCollectionMetadataStore metadataStore) {
        this(vectorStore, embeddingClient, tracePersistence, metadataStore, null);
    }

    public VectorStorageService(SemanticVectorStore vectorStore, EmbeddingClient embeddingClient,
                                VectorTracePersistence tracePersistence, VectorCollectionMetadataStore metadataStore,
                                EmbeddingBridgeProperties embeddingProperties) {
        this(vectorStore, embeddingClient, tracePersistence, metadataStore, embeddingProperties, null);
    }

    public VectorStorageService(SemanticVectorStore vectorStore, EmbeddingClient embeddingClient,
                                VectorTracePersistence tracePersistence, VectorCollectionMetadataStore metadataStore,
                                EmbeddingBridgeProperties embeddingProperties, EmbeddingRuntimeResolver embeddingResolver) {
        this.vectorStore = vectorStore;
        this.embeddingClient = embeddingClient;
        this.tracePersistence = tracePersistence;
        this.metadataStore = metadataStore;
        this.embeddingProperties = embeddingProperties;
        this.embeddingResolver = embeddingResolver;
    }

    /** The model actually wired to this vector service, not a console registry entry. */
    public Map<String, Object> activeEmbeddingModel() {
        if (embeddingProperties == null) return Map.of("enabled", false);
        EmbeddingRuntimeResolver.Current current = currentEmbedding();
        return Map.of("modelId", current.modelName(), "version", current.version(),
                "dimension", current.dimension(),
                "enabled", current.enabled() && embeddingClient.enabled() && vectorStore.requiresEmbedding(),
                "provider", current.provider());
    }

    private EmbeddingRuntimeResolver.Current currentEmbedding() {
        if (embeddingResolver != null) return embeddingResolver.current();
        return new EmbeddingRuntimeResolver.Current(embeddingProperties.getModelName(),
                embeddingProperties.getModelVersion(), embeddingProperties.getDimension(),
                embeddingProperties.getProvider(), embeddingProperties.isEnabled());
    }

    private void requireBoundModel(String selectedModel) {
        if (!vectorStore.requiresEmbedding() || embeddingProperties == null) return;
        EmbeddingRuntimeResolver.Current current = currentEmbedding();
        if (!current.enabled() || !embeddingClient.enabled()) {
            throw new BizException(ErrorCode.DEPENDENCY_UNAVAILABLE, "向量模型服务未启用");
        }
        if (!current.modelName().equals(selectedModel)) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "知识库绑定的向量模型与当前运行模型不一致；请使用当前可用模型或联系管理员完成模型迁移");
        }
    }

    public VectorCollectionView create(CollectionRequest request) {
        requireBoundModel(request.embeddingModel().trim());
        if (vectorStore.requiresEmbedding() && embeddingProperties != null
                && request.dimension() != currentEmbedding().dimension()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "新知识库维度必须与当前生效向量模型一致");
        }
        String normalized = request.name().trim().toLowerCase(Locale.ROOT);
        // vec_collection.collection_id is frozen at VARCHAR(24); 80 random bits retain ample collision margin.
        String collectionId = "vec-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        if (!metadataStore.enabled() && idsByName.putIfAbsent(normalized, collectionId) != null) {
            throw new BizException(ErrorCode.IDEMPOTENT_CONFLICT, "向量集合名称已存在：" + request.name().trim());
        }
        int quota = request.quota() == null ? 100_000 : request.quota();
        // 维度只保留一处事实源（上文按“当前生效的向量模型”校验）；不再用静态配置做第二次校验，
        // 否则运行模型维度与配置不一致时会拦截建库，并报出配置里的旧维度。
        Collection collection = new Collection(collectionId, request.name().trim(), request.dimension(),
                request.embeddingModel().trim(), quota, "NOT_BUILT", Instant.now());
        boolean physicalCreated = false;
        try {
            vectorStore.createCollection(collectionId, request.dimension(), "COSINE");
            physicalCreated = true;
            if (metadataStore.enabled()) return toView(metadataStore.create(request, collectionId));
            collections.put(collectionId, collection);
            return toView(collection);
        } catch (RuntimeException exception) {
            if (physicalCreated) {
                try { vectorStore.dropCollection(collectionId); }
                catch (RuntimeException cleanupFailure) { exception.addSuppressed(cleanupFailure); }
            }
            if (!metadataStore.enabled()) idsByName.remove(normalized, collectionId);
            throw exception;
        }
    }

    public List<VectorCollectionView> list() {
        if (metadataStore.enabled()) return metadataStore.list().stream().map(this::toView).toList();
        return collections.values().stream().sorted(Comparator.comparing(Collection::updatedAt).reversed()).map(this::toView).toList();
    }

    public void validateIngestCollection(String collectionId) {
        Collection collection = requireCollection(collectionId);
        requireBoundModel(collection.embeddingModel());
        requireBoundVersion(collectionId);
        if (vectorStore.requiresEmbedding() && embeddingProperties != null
                && collection.dimension() != currentEmbedding().dimension()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "知识库维度与当前运行向量模型不一致；请重建索引到兼容集合");
        }
    }

    /**
     * 按知识库绑定的向量模型生成单段文本的向量。
     * 语义切分需要逐句比对相似度，必须用与该知识库相同的模型，不能借用别的模型。
     */
    public List<Float> embedForCollection(String collectionId, String text) {
        Collection collection = requireCollection(collectionId);
        requireBoundModel(collection.embeddingModel());
        return embeddingClient.embed(text, "para", collection.embeddingModel());
    }

    private void requireBoundVersion(String collectionId) {
        if (!metadataStore.enabled() || embeddingResolver == null) return;
        String pinned = metadataStore.modelVersion(collectionId);
        if (!pinned.isBlank() && !pinned.equals(currentEmbedding().version())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "知识库绑定的向量模型版本已变化，请新建兼容知识库并重建索引");
        }
    }

    /** 该向量条目对应的入库原文（DM8 分块追溯表）；用于「修正」时展示当前内容。 */
    public java.util.Optional<String> ingestedText(String vectorId) {
        return tracePersistence.ingestedText(vectorId);
    }

    public boolean hasStoredEntry(String collectionId, String vectorId) {
        Boolean store = existsInVectorStore(collectionId, vectorId);
        return store == null ? hasTraceEntry(collectionId, vectorId) : store;
    }

    /** 是否在 DM8 追溯表（或内存台账）中存在；用于区分“向量存在但追溯缺失”。 */
    public boolean hasTraceEntry(String collectionId, String vectorId) {
        requireCollection(collectionId);
        if (tracePersistence.enabled() && tracePersistence.existsInCollection(collectionId, vectorId)) return true;
        return entries.getOrDefault(collectionId, List.of()).stream()
                .anyMatch(entry -> entry.vectorId().equals(vectorId));
    }

    /** true/false = 向量库明确存在/不存在；null = 当前后端不支持检查。 */
    public Boolean existsInVectorStore(String collectionId, String vectorId) {
        requireCollection(collectionId);
        if (!vectorStore.supportsExistenceCheck()) return null;
        return vectorStore.exists(collectionId, vectorId);
    }

    /** Read-only physical ID scan; null means this backend cannot provide a complete reverse audit. */
    public List<String> physicalVectorIds(String collectionId) {
        requireCollection(collectionId);
        return vectorStore.supportsVectorIdScan() ? vectorStore.listVectorIds(collectionId) : null;
    }

    /**
     * 数据验证修复：按来源台账的文本重建缺失的向量或追溯（真实重新嵌入/写入）。
     * writeStore 只在向量库确认缺失时设为 true；writeTrace 只在追溯缺失时设为 true。
     */
    public void repairEntry(String collectionId, VectorEntryRequest request, boolean writeStore, boolean writeTrace) {
        Collection collection = requireCollection(collectionId);
        requireBoundModel(collection.embeddingModel());
        requireBoundVersion(collectionId);
        String text = request.textPreview() == null ? "" : request.textPreview().trim();
        if (text.isBlank()) throw new BizException(ErrorCode.PARAM_ERROR, "修复向量需要非空文本");
        List<Float> embedding = vectorStore.requiresEmbedding()
                ? embeddingClient.embed(text, "para", collection.embeddingModel()) : List.of();
        String preview = utf8Prefix(text, 512);
        if (writeStore) {
            vectorStore.upsert(collectionId, new SemanticVectorStore.VectorRecord(request.vectorId(),
                    request.fileId(), request.chunkId(), preview), embedding);
        }
        if (writeTrace) {
            tracePersistence.persist(new VectorTracePersistence.VectorTrace(collectionId, collection.name(),
                    collection.dimension(), collection.embeddingModel(), request, embedding));
        }
        List<VectorEntry> collectionEntries = entries.computeIfAbsent(collectionId, ignored -> new ArrayList<>());
        synchronized (collectionEntries) {
            if (collectionEntries.stream().noneMatch(entry -> entry.vectorId().equals(request.vectorId()))) {
                collectionEntries.add(new VectorEntry(request.fileId().trim(), request.chunkId().trim(),
                        request.vectorId().trim(), preview));
            }
        }
        touch(collectionId);
    }

    public void markKnowledgeTask(String taskId, boolean success) {
        tracePersistence.markKnowledgeTask(taskId, success);
    }

    /** Explicitly withdraw one knowledge job's vectors from retrieval, then its DM8 trace. */
    public int withdrawKnowledgeTask(String collectionId, String taskId) {
        requireCollection(collectionId);
        List<VectorEntry> local = entries.computeIfAbsent(collectionId, ignored -> new ArrayList<>());
        synchronized (local) {
            List<String> ids = tracePersistence.enabled()
                    ? tracePersistence.vectorIdsForTask(collectionId, taskId)
                    : local.stream().filter(item -> item.chunkId().startsWith(taskId + "-"))
                            .map(VectorEntry::vectorId).toList();
            if (ids.isEmpty()) return 0;
            vectorStore.delete(collectionId, ids);
            int deleted = tracePersistence.enabled()
                    ? tracePersistence.deleteTaskVectors(collectionId, taskId, ids) : ids.size();
            local.removeIf(item -> ids.contains(item.vectorId()));
            /*
             * 条目数是账务数据：向量与追溯已经删掉了，扣减失败不能再让整次撤回报错（否则会留下"向量已删、任务未标记撤回"的中间态）。
             * 追溯表删除时已经在自己的事务里扣过 vec_count，这里只在没有追溯的模式下扣，
             * 否则同一次撤回会扣两次、把条目数越扣越小。
             */
            if (metadataStore.enabled() && !tracePersistence.enabled()) {
                try {
                    metadataStore.decrementEntryCount(collectionId, ids.size());
                } catch (RuntimeException failure) {
                    LOG.warn("知识库 {} 撤回 {} 条向量后条目数扣减失败，需人工核对：{}",
                            collectionId, ids.size(), failure.getMessage());
                }
            }
            if (!metadataStore.enabled()) touch(collectionId);
            return deleted;
        }
    }

    /** 删除知识库：先删向量集合，再删 DM8 元数据与内存缓存。 */
    public void deleteCollection(String collectionId) {
        requireCollection(collectionId);
        vectorStore.dropCollection(collectionId);
        metadataStore.delete(collectionId);
        entries.remove(collectionId);
        Collection removed = collections.remove(collectionId);
        if (removed != null) idsByName.remove(removed.name());
    }

    public VectorCollectionView buildIndex(String collectionId) {
        Collection collection = requireCollection(collectionId);
        indexBuildCount.increment();
        try {
            vectorStore.buildIndex(collectionId);
        } catch (RuntimeException failure) {
            markIndexFailed(collectionId);
            throw failure;
        }
        indexBuildSuccess.increment();
        if (metadataStore.enabled()) return toView(metadataStore.markIndexReady(collectionId));
        Collection updated = collection.withIndexStatus("READY", Instant.now());
        collections.put(collectionId, updated);
        return toView(updated);
    }

    /**
     * 真实探测索引状态（列表页「检测」）：去向量库确认真实集合是否存在，
     * 存在→READY、不存在→FAILED，并把结果回写元数据，避免状态长期是台账值。
     */
    public VectorCollectionView verifyIndex(String collectionId) {
        Collection current = requireCollection(collectionId);
        boolean exists;
        try {
            exists = vectorStore.collectionExists(collectionId);
        } catch (RuntimeException failure) {
            markIndexFailed(collectionId);
            throw failure;
        }
        // 从未建过索引的知识库不因为"向量库里还没有它"就报异常，保持"未建索引"语义。
        String status = "NOT_BUILT".equalsIgnoreCase(current.indexStatus()) ? "NOT_BUILT"
                : (exists ? "READY" : "FAILED");
        if (exists && !"NOT_BUILT".equalsIgnoreCase(current.indexStatus())) {
            try {
                List<Float> probe = new ArrayList<>(java.util.Collections.nCopies(Math.max(1, current.dimension()), 0.0f));
                probe.set(0, 1.0f);
                List<SemanticVectorStore.SearchHit> hits = vectorStore.search(collectionId, "", probe, 1);
                int expected = metadataStore.enabled()
                        ? metadataStore.find(collectionId).map(VectorCollectionMetadataStore.Metadata::entryCount).orElse(0)
                        : entries.getOrDefault(collectionId, List.of()).size();
                if (expected > 0 && hits.isEmpty()) status = "FAILED";
            } catch (RuntimeException failure) {
                markIndexFailed(collectionId);
                throw failure;
            }
        }
        if (metadataStore.enabled()) return toView(metadataStore.updateIndexStatus(collectionId, status));
        Collection updated = current.withIndexStatus(status, Instant.now());
        collections.put(collectionId, updated);
        return toView(updated);
    }

    /** 建索引失败时把集合置为异常（否则列表会长期显示"正常"）。 */
    public void markIndexFailed(String collectionId) {
        if (metadataStore.enabled()) {
            metadataStore.markIndexFailed(collectionId);
            return;
        }
        Collection current = collections.get(collectionId);
        if (current != null) collections.put(collectionId, current.withIndexStatus("FAILED", Instant.now()));
    }

    public Map<String, Object> registerEntry(String collectionId, VectorEntryRequest request) {
        Collection collection = requireCollection(collectionId);
        requireBoundModel(collection.embeddingModel());
        requireBoundVersion(collectionId);
        List<VectorEntry> collectionEntries = entries.computeIfAbsent(collectionId, ignored -> new ArrayList<>());
        synchronized (collectionEntries) {
            boolean duplicate = collectionEntries.stream().anyMatch(item -> item.vectorId().equals(request.vectorId()));
            if (duplicate || (tracePersistence.enabled() && request.hasDocumentLineage()
                    && tracePersistence.exists(request.vectorId()))) {
                throw new BizException(ErrorCode.IDEMPOTENT_CONFLICT, "vectorId 已登记：" + request.vectorId());
            }
            int entryCount = persistedEntryCount(collectionId, collectionEntries);
            if (entryCount >= collection.quota()) {
                throw new BizException(ErrorCode.QUOTA_EXCEEDED, "向量集合配额已满");
            }
            String textPreview = request.textPreview() == null ? "" : request.textPreview().trim();
            if (vectorStore.requiresEmbedding() && textPreview.isBlank()) {
                throw new BizException(ErrorCode.PARAM_ERROR, "真实向量写入时 textPreview 不能为空");
            }
            List<Float> embedding = vectorStore.requiresEmbedding() ? embeddingClient.embed(textPreview, "para", collection.embeddingModel()) : List.of();
            String storedPreview = utf8Prefix(textPreview, 512);
            VectorEntry entry = new VectorEntry(request.fileId().trim(), request.chunkId().trim(), request.vectorId().trim(), storedPreview);
            /* 记录写入前是否已存在：追溯失败时只回滚本次新建的向量，避免把历史遗留、仍被引用的同主键条目删掉。 */
            boolean existedBefore = vectorStore.supportsExistenceCheck()
                    && vectorStore.exists(collectionId, entry.vectorId());
            vectorStore.upsert(collectionId, new SemanticVectorStore.VectorRecord(entry.vectorId(), entry.fileId(), entry.chunkId(), entry.textPreview()), embedding);
            // Only persist the trace after Milvus accepts the exact vector. A retry is safe because vec_item_id is unique.
            try {
                tracePersistence.persist(new VectorTracePersistence.VectorTrace(collectionId, collection.name(),
                        collection.dimension(), collection.embeddingModel(), request, embedding));
            } catch (RuntimeException failure) {
                /*
                 * 向量已写入、追溯没写成：回滚这条向量，避免库里有向量而条目数没加、
                 * 检索还能命中一条没有来源记录的片段。写入前已存在的同主键向量不回滚，留给失败任务的按任务撤回处理。
                 */
                if (!existedBefore) {
                    try {
                        vectorStore.delete(collectionId, List.of(entry.vectorId()));
                    } catch (RuntimeException cleanupFailure) {
                        LOG.warn("知识库 {} 追溯写入失败后回滚向量 {} 未完成，需条目数校准：{}",
                                collectionId, entry.vectorId(), cleanupFailure.getMessage());
                    }
                } else {
                    LOG.warn("知识库 {} 追溯写入失败，向量 {} 写入前已存在，保留待任务撤回处理",
                            collectionId, entry.vectorId());
                }
                throw failure;
            }
            if (metadataStore.enabled() && (!tracePersistence.enabled() || !request.hasDocumentLineage())) {
                metadataStore.incrementEntryCount(collectionId);
            }
            collectionEntries.add(entry);
            entryCount = persistedEntryCount(collectionId, collectionEntries);
            touch(collectionId);
            return Map.of("collectionId", collectionId, "vectorId", request.vectorId(), "entryCount", entryCount);
        }
    }

    public List<Map<String, Object>> search(String collectionId, SearchRequest request) {
        if (rateLimiter != null && !rateLimiter.tryAcquire()) {
            throw new BizException(ErrorCode.RATE_LIMITED,
                    "查询已触发限流（上限 " + rateLimiter.limit() + " 次/秒），请稍后重试");
        }
        if (request.filters() != null && !request.filters().isEmpty()) {
            // 条件过滤尚未实现：明确拒绝，避免调用方以为过滤生效（此前被静默忽略）。
            throw new BizException(ErrorCode.PARAM_ERROR, "检索暂不支持按条件过滤");
        }
        long started = System.nanoTime();
        Collection collection = requireCollection(collectionId);
        requireBoundModel(collection.embeddingModel());
        requireBoundVersion(collectionId);
        if (!"READY".equalsIgnoreCase(collection.indexStatus())) {
            throw new BizException(ErrorCode.TASK_STATE_CONFLICT, "索引尚未构建完成");
        }
        List<Float> embedding = vectorStore.requiresEmbedding() ? embeddingClient.embed(request.query().trim(), "query", collection.embeddingModel()) : List.of();
        int topK = Math.max(1, request.topK());
        /*
         * 归档/撤回只移出列表、保留向量，因此过滤必须发生在截断之前：
         * 先多取一批再按可见性过滤，最后截到 topK，避免归档文档占满名额导致结果为空。
         */
        int fetch = chunkVisible == null ? topK : Math.min(1000, Math.max(topK, topK * 10));
        List<Map<String, Object>> hits = vectorStore.search(collectionId, request.query().trim(), embedding, fetch).stream()
                // 归档或已撤回的文档不参与检索：归档只移出列表、向量保留，恢复后重新可见。
                .filter(hit -> chunkVisible == null || chunkVisible.test(hit.chunkId()))
                .limit(topK)
                .map(hit -> Map.<String, Object>of("vectorId", hit.vectorId(), "fileId", hit.fileId(),
                        "chunkId", hit.chunkId(), "score", hit.score(), "textPreview", hit.textPreview()))
                .toList();
        searchCount.increment();
        searchLatencyNanos.add(System.nanoTime() - started);
        return hits;
    }

    /** 由知识库任务台账注入的可见性判断：归档/撤回的片段不参与检索。 */
    public void setChunkVisibility(java.util.function.Predicate<String> predicate) {
        this.chunkVisible = predicate;
    }

    /** 删除指定向量（数据校验修复清理无来源、无追溯的孤儿向量时使用）。 */
    public int deleteVectors(String collectionId, List<String> vectorIds) {
        requireCollection(collectionId);
        if (vectorIds == null || vectorIds.isEmpty()) return 0;
        vectorStore.delete(collectionId, vectorIds);
        List<VectorEntry> local = entries.computeIfAbsent(collectionId, ignored -> new ArrayList<>());
        synchronized (local) {
            local.removeIf(item -> vectorIds.contains(item.vectorId()));
        }
        touch(collectionId);
        return vectorIds.size();
    }

    /** 台账里记录的向量条目数（知识库列表展示的数量）。 */
    public int recordedEntryCount(String collectionId) {
        requireCollection(collectionId);
        return persistedEntryCount(collectionId, entries.getOrDefault(collectionId, List.of()));
    }

    /** 追溯表是否启用：未启用时不能靠「无追溯」判定孤儿向量，避免误删。 */
    public boolean traceEnabled() {
        return tracePersistence.enabled();
    }

    /**
     * 向量库里的实际向量数。后端不支持计数/遍历时返回 -1；向量库不可达时抛业务异常，
     * 调用方要按「本次未核验」处理，不能当成 0 条。
     */
    public int physicalEntryCount(String collectionId) {
        requireCollection(collectionId);
        if (!vectorStore.supportsVectorIdScan()) return -1;
        long counted = vectorStore.countVectors(collectionId);
        return (int) Math.min(counted, Integer.MAX_VALUE);
    }

    /**
     * 把台账条目数校准为向量库实际条数（任务结束、撤回后与数据校验修复时调用）。
     *
     * <p>先读台账快照再回写，中间可能有并发写入；台账已被改动时放弃本次校准，
     * 返回读取到的实际条数，下一轮校准会再对齐。</p>
     *
     * @return 校准后的实际条数；不可校准（无台账或后端不支持）返回 -1
     */
    public int reconcileEntryCount(String collectionId) {
        requireCollection(collectionId);
        if (!metadataStore.enabled()) return -1;
        /* 先读台账、再数向量：CAS 的 expected 必须早于快照，之后的任何写入都会让本次校准放弃而不是覆盖。 */
        for (int attempt = 0; attempt < 2; attempt++) {
            int recorded = recordedEntryCount(collectionId);
            int actual = physicalEntryCount(collectionId);
            if (actual < 0) return -1;
            if (recorded == actual) return actual;
            if (metadataStore.setEntryCountIfUnchanged(collectionId, recorded, actual)) {
                LOG.info("知识库 {} 条目数校准：{} -> {}", collectionId, recorded, actual);
                return actual;
            }
            LOG.info("知识库 {} 条目数校准跳过：期间台账已被其它写入更新，重试一次", collectionId);
        }
        return recordedEntryCount(collectionId);
    }

    public RuntimeSnapshot drainRuntimeMetrics(long intervalSeconds) {
        long searches = searchCount.sumThenReset();
        long nanos = searchLatencyNanos.sumThenReset();
        long indexAttempts = indexBuildCount.sumThenReset();
        long indexSuccesses = indexBuildSuccess.sumThenReset();
        return new RuntimeSnapshot(searches / (double) Math.max(1, intervalSeconds),
                searches == 0 ? null : nanos / (double) searches / 1_000_000.0,
                indexAttempts == 0 ? null : 100.0 * indexSuccesses / indexAttempts);
    }

    public record RuntimeSnapshot(double qps, Double queryLatencyMs, Double indexBuildSuccessPercent) { }

    private Collection requireCollection(String collectionId) {
        if (metadataStore.enabled()) {
            VectorCollectionMetadataStore.Metadata metadata = metadataStore.find(collectionId)
                    .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "向量集合不存在：" + collectionId));
            return new Collection(metadata.collectionId(), metadata.name(), metadata.dimension(), metadata.embeddingModel(),
                    metadata.quota(), metadata.indexStatus(), metadata.updatedAt());
        }
        Collection collection = collections.get(collectionId);
        if (collection == null) throw new BizException(ErrorCode.NOT_FOUND, "向量集合不存在：" + collectionId);
        return collection;
    }
    private int persistedEntryCount(String collectionId, List<VectorEntry> collectionEntries) {
        if (metadataStore.enabled()) {
            return metadataStore.find(collectionId)
                    .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "向量集合不存在：" + collectionId))
                    .entryCount();
        }
        return collectionEntries.size();
    }
    private VectorCollectionView toView(Collection value) {
        return new VectorCollectionView(value.collectionId(), value.name(), value.dimension(), value.embeddingModel(),
                value.quota(), entries.getOrDefault(value.collectionId(), List.of()).size(), value.indexStatus(), value.updatedAt());
    }
    private VectorCollectionView toView(VectorCollectionMetadataStore.Metadata value) {
        return new VectorCollectionView(value.collectionId(), value.name(), value.dimension(), value.embeddingModel(),
                value.quota(), value.entryCount(), value.indexStatus(), value.updatedAt());
    }
    private void touch(String collectionId) {
        Collection value = requireCollection(collectionId);
        collections.put(collectionId, value.withIndexStatus(value.indexStatus(), Instant.now()));
    }

    static String utf8Prefix(String value, int maxBytes) {
        if (value == null || value.isEmpty() || maxBytes <= 0) return "";
        if (value.getBytes(StandardCharsets.UTF_8).length <= maxBytes) return value;
        StringBuilder result = new StringBuilder(Math.min(value.length(), maxBytes));
        int used = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            int bytes = character.getBytes(StandardCharsets.UTF_8).length;
            if (used + bytes > maxBytes) break;
            result.append(character);
            used += bytes;
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }
    private record Collection(String collectionId, String name, int dimension, String embeddingModel, int quota,
                              String indexStatus, Instant updatedAt) {
        private Collection withIndexStatus(String indexStatus, Instant updatedAt) {
            return new Collection(collectionId, name, dimension, embeddingModel, quota, indexStatus, updatedAt);
        }
    }
    private record VectorEntry(String fileId, String chunkId, String vectorId, String textPreview) { }
}
