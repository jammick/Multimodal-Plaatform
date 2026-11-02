package com.cais.vector.service;

import com.cais.common.api.ErrorCode;
import com.cais.common.exception.BizException;
import com.cais.vector.domain.KnowledgeIngestRequest;
import com.cais.vector.domain.VectorEntryRequest;
import com.cais.vector.dto.VectorCollectionView;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Persists actual knowledge-ingest progress; the source text must come from a processed result. */
@Service
public class KnowledgeIngestService {
    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeIngestService.class);
    private static final String DATASET = "kb.task";
    private final VectorStorageService vectors;
    private final ConsoleRegistryService registry;
    private final VectorOpsService sourceLedger;
    /** 停机标志：启动后的条目数校准要能及时退出，不再对着已关闭的数据源跑。 */
    private volatile boolean stopped;
    /** 正在修复的知识库：同一库只允许一次修复，避免并发覆盖台账 */
    private final java.util.Set<String> repairing = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(INGEST_THREADS, INGEST_THREADS, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(40), task -> {
                Thread thread = new Thread(task, "cais-knowledge-ingest");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    /** 单次入库可接受的文本上限（字符）：PDF/长文档常见数万字，上限过小会表现为"写入失败"。 */
    static final int MAX_CONTENT_CHARS = 1000000;

    /** 并行入库线程数，默认 4，可用 CAIS_VECTOR_INGEST_THREADS 调整（1~16）。 */
    private static final int INGEST_THREADS = ingestThreads();

    /**
     * 语义切分的相邻句相似度阈值：低于它视为语义边界。
     * 默认 0.96 —— 现场讯飞 Embedding 实测同主题 0.97~0.98、跨主题 0.94~0.95，
     * 换模型时基线会变，可用 CAIS_SEMANTIC_SPLIT_THRESHOLD 调整（0.1~0.99）。
     */
    static final double SEMANTIC_THRESHOLD = semanticThreshold();

    /** 语义切分逐句取向量的句数上限：超过时明确报错，避免一次预览/入库把模型调用拖到不可用。 */
    static final int SEMANTIC_MAX_SENTENCES = 300;

    /** 句子向量缓存：同一篇文本的预览与入库、重复句都不重复调用模型。 */
    private final Map<String, List<Float>> sentenceVectors = new ConcurrentHashMap<>();
    private static final int SENTENCE_CACHE_LIMIT = 2000;

    /**
     * Embedding 调用节流：讯飞 Embedding 对**并发**很敏感（并发 2 路就会返回 500，适配器转 502），
     * 所以全服务串行调用，并保持最小间隔。默认 250ms，可用 CAIS_EMBED_MIN_INTERVAL_MS 调整。
     */
    private static final long EMBED_MIN_INTERVAL_MS = embedInterval();
    private final Object embeddingLock = new Object();
    private long nextEmbeddingAt = 0L;
    /** 自适应间隔：被上游拒绝时放慢，连续成功后逐步回到基准值。 */
    private long currentEmbedInterval = EMBED_MIN_INTERVAL_MS;
    private int embedSuccessStreak = 0;

    private static long embedInterval() {
        try {
            long value = Long.parseLong(System.getenv().getOrDefault("CAIS_EMBED_MIN_INTERVAL_MS", "250").trim());
            return Math.max(0, Math.min(5000, value));
        } catch (NumberFormatException ignored) {
            return 250;
        }
    }

    private interface EmbeddingCall<T> {
        T run();
    }

    /** 在串行槽位内执行一次模型调用，避免并发把上游打到 502；intervalMs 为基准间隔（0 表示不限速）。 */
    private <T> T withEmbeddingSlot(long intervalMs, EmbeddingCall<T> call) {
        synchronized (embeddingLock) {
            long wait = nextEmbeddingAt - System.currentTimeMillis();
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            try {
                T result = call.run();
                if (intervalMs > 0) {
                    embedSuccessStreak++;
                    if (embedSuccessStreak >= 5 && currentEmbedInterval > EMBED_MIN_INTERVAL_MS) {
                        currentEmbedInterval = Math.max(EMBED_MIN_INTERVAL_MS, currentEmbedInterval / 2);
                        embedSuccessStreak = 0;
                    }
                }
                return result;
            } catch (RuntimeException failure) {
                if (intervalMs > 0) {
                    embedSuccessStreak = 0;
                    currentEmbedInterval = Math.min(2000, Math.max(800, currentEmbedInterval * 2));
                }
                throw failure;
            } finally {
                nextEmbeddingAt = System.currentTimeMillis()
                        + (intervalMs > 0 ? Math.max(intervalMs, currentEmbedInterval) : 0);
            }
        }
    }

    private static double semanticThreshold() {
        try {
            double value = Double.parseDouble(System.getenv().getOrDefault("CAIS_SEMANTIC_SPLIT_THRESHOLD", "0.96").trim());
            return value > 0.1 && value < 0.99 ? value : 0.96;
        } catch (NumberFormatException ignored) {
            return 0.96;
        }
    }

    private static int ingestThreads() {
        try {
            int value = Integer.parseInt(System.getenv().getOrDefault("CAIS_VECTOR_INGEST_THREADS", "4").trim());
            return Math.max(1, Math.min(16, value));
        } catch (NumberFormatException ignored) {
            return 4;
        }
    }

    public KnowledgeIngestService(VectorStorageService vectors, ConsoleRegistryService registry) {
        this(vectors, registry, null);
    }

    @Autowired
    public KnowledgeIngestService(VectorStorageService vectors, ConsoleRegistryService registry,
                                  VectorOpsService sourceLedger) {
        this.vectors = vectors;
        this.registry = registry;
        this.sourceLedger = sourceLedger;
        // 归档或撤回的文档不参与检索：检索在向量服务里完成，这里把任务台账的可见性注入进去。
        vectors.setChunkVisibility(this::visibleChunk);
    }

    /** 片段编号格式为「任务编号-序号」；对应任务已归档或已撤回时不可检索。 */
    private boolean visibleChunk(String chunkId) {
        String value = chunkId == null ? "" : chunkId;
        int dash = value.lastIndexOf('-');
        if (dash <= 0) return true;
        String jobId = value.substring(0, dash);
        for (Map<String, Object> job : registry.list(DATASET)) {
            if (!jobId.equals(String.valueOf(job.get("id")))) continue;
            return !Boolean.TRUE.equals(job.get("archived")) && !Boolean.TRUE.equals(job.get("withdrawn"));
        }
        // 没有台账的历史片段保持可检索，避免误伤。
        return true;
    }

    private ProcessedSourceClient sourceClient;

    /** 失败任务重试需要重新取该文件的加工结果，单机/单测环境可能没有该客户端。 */
    @Autowired(required = false)
    public void setSourceClient(ProcessedSourceClient sourceClient) {
        this.sourceClient = sourceClient;
    }

    /**
     * 失败任务重试：按原任务的文件与切分参数，重新读取该文件的加工后文本再提交一次。
     * 已成功的切片按 fingerprint 幂等，不会重复写入。
     */
    public Map<String, Object> retry(String id) {
        Map<String, Object> job = job(id);
        if (!"失败".equals(String.valueOf(job.get("status")))) {
            throw new BizException(ErrorCode.TASK_STATE_CONFLICT,
                    "只有「失败」的任务可以重试，当前状态：" + job.get("status"));
        }
        String fileId = String.valueOf(job.get("fileId"));
        if (sourceClient == null) {
            throw new BizException(ErrorCode.DEPENDENCY_UNAVAILABLE, "当前环境未启用加工结果来源服务，无法自动重试，请重新导入该文件");
        }
        ProcessedSourceClient.SourceText source;
        try {
            source = sourceClient.sourceText(fileId);
        } catch (RuntimeException failure) {
            throw new BizException(ErrorCode.DEPENDENCY_UNAVAILABLE, "读取文件加工结果失败：" + failure.getMessage());
        }
        if (source == null || source.text() == null || source.text().isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "该文件的加工结果已不可用（可能已被清理），请重新导入该文件");
        }
        Number chunkSize = job.get("chunkSize") instanceof Number number ? number : 512;
        Number overlap = job.get("overlap") instanceof Number number ? number : 0;
        KnowledgeIngestRequest request = new KnowledgeIngestRequest(
                String.valueOf(job.get("collectionId")),
                fileId,
                source.text(),
                String.valueOf(job.getOrDefault("strategy", "按句切分")),
                Math.max(2, chunkSize.intValue()),
                Math.max(0, overlap.intValue()),
                source.sourceTaskId() == null ? "" : source.sourceTaskId());
        String origin = String.valueOf(job.getOrDefault("origin", "")).trim();
        Map<String, Object> submitted = submit(request, origin.isBlank() ? "MANUAL" : origin);
        // 重新抽取的文本可能与首次入库略有差异，指纹不同 → 会产生新任务；把旧的失败任务归档，
        // 避免界面上同时留一条"失败"和一条新任务（用户会以为重试没生效）。
        String newId = String.valueOf(submitted.get("id"));
        if (!newId.isBlank() && !newId.equals(id)) {
            try { archive(id, "已重试，由新任务 " + newId + " 替代"); }
            catch (RuntimeException ignored) { /* 归档失败不影响重试结果 */ }
        }
        return submitted;
    }

    @PostConstruct
    void recoverInterruptedJobs() {
        for (Map<String, Object> job : registry.list(DATASET)) {
            String status = String.valueOf(job.getOrDefault("status", ""));
            String id = String.valueOf(job.get("id"));
            if (List.of("待处理", "解析中", "向量化中").contains(status)) {
                registry.update(DATASET, id,
                        Map.of("status", "失败", "error", "服务重启中断任务，请重新提交相同来源以继续", "at", Instant.now().toString()));
                status = "失败";
            }
            if (sourceLedger != null && List.of("成功", "失败").contains(status)) {
                sourceLedger.finishRealUpdate(id, "成功".equals(status),
                        ((Number) job.getOrDefault("chunks", 0)).intValue(),
                        String.valueOf(job.getOrDefault("error", "")), this);
            }
        }
        /*
         * 历史数据可能因为「向量写入成功、台账加一失败」而条目数偏小，页面会一直显示旧数字。
         * 启动后延迟校准：等 Milvus 把集合加载完，且不阻塞启动；失败集合隔 30 秒只重试它们，最多 3 轮。
         */
        java.util.concurrent.CompletableFuture
                .delayedExecutor(60, java.util.concurrent.TimeUnit.SECONDS)
                .execute(() -> reconcileAllEntryCounts(null, 1, 3));
    }

    /**
     * 按向量库实际条数校准知识库条目数。
     *
     * @param targets 只校准这些集合（null 表示全部）；重试轮只带上失败集合，避免整轮重算
     * @param round   当前轮次，从 1 开始
     * @param rounds  最多轮次
     */
    void reconcileAllEntryCounts(java.util.Set<String> targets, int round, int rounds) {
        if (stopped) return;
        List<String> failed = new java.util.ArrayList<>();
        try {
            for (VectorCollectionView collection : vectors.list()) {
                if (stopped) return;
                String id = collection.collectionId();
                if (targets != null && !targets.contains(id)) continue;
                if (!reconcileEntryCountOnce(id)) failed.add(id);
            }
        } catch (RuntimeException failure) {
            LOG.warn("启动时条目数校准失败（第 {} 轮）：{}", round, failure.getMessage());
            return;
        }
        if (failed.isEmpty() || round >= rounds) return;
        /* 用再次延时调度代替线程睡眠：共享延时线程不能被一次校准占住。 */
        java.util.Set<String> next = new java.util.HashSet<>(failed);
        java.util.concurrent.CompletableFuture
                .delayedExecutor(30, java.util.concurrent.TimeUnit.SECONDS)
                .execute(() -> reconcileAllEntryCounts(next, round + 1, rounds));
    }

    /** 单个知识库校准：返回 true 表示成功，false 表示本轮失败（下一轮重试）。 */
    private boolean reconcileEntryCountOnce(String collectionId) {
        try {
            vectors.reconcileEntryCount(collectionId);
            return true;
        } catch (RuntimeException failure) {
            LOG.warn("知识库 {} 条目数校准失败，待下一轮重试或数据校验：{}", collectionId, failure.getMessage());
            return false;
        }
    }

    @PreDestroy
    void stop() {
        stopped = true;
        workers.shutdownNow();
    }

    public List<Map<String, Object>> list() {
        return registry.list(DATASET).stream()
                .filter(job -> !Boolean.TRUE.equals(job.get("deleted")) && !Boolean.TRUE.equals(job.get("archived")))
                .sorted(Comparator.comparing((Map<String, Object> job) -> String.valueOf(job.getOrDefault("at", ""))).reversed())
                .toList();
    }

    public List<Map<String, Object>> listArchived() {
        return registry.list(DATASET).stream()
                .filter(job -> Boolean.TRUE.equals(job.get("archived")))
                .sorted(Comparator.comparing((Map<String, Object> job) -> String.valueOf(job.getOrDefault("archivedAt", ""))).reversed())
                .toList();
    }

    /**
     * 数据验证（VEC-F3 ②）：按知识库核对「入库任务 → 来源台账 → DM8 向量追溯表」的一致性。
     *
     * <p>核对成功任务、来源台账、DM8 追溯和可查询的物理向量 ID。
     */
    public Map<String, Object> validate(String collectionId) {
        if (collectionId == null || collectionId.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "请指定知识库集合");
        }
        List<Map<String, Object>> jobs = list().stream()
                .filter(job -> collectionId.equals(String.valueOf(job.get("collectionId"))))
                .toList();
        List<com.cais.vector.domain.VectorModels.VectorSource> sources = sourceLedger == null
                ? List.of() : sourceLedger.listSources(null, collectionId);
        Map<String, List<com.cais.vector.domain.VectorModels.VectorSource>> byTask = new LinkedHashMap<>();
        for (var source : sources) {
            if (source.isWithdrawn()) continue;
            String taskId = source.getSourceTaskId();
            if (taskId == null || taskId.isBlank()) continue;
            byTask.computeIfAbsent(taskId, ignored -> new ArrayList<>()).add(source);
        }
        List<String> violations = new ArrayList<>();
        int checkedVectors = 0;
        int missingVectors = 0;
        int missingTrace = 0;
        int emptyContents = 0;
        int mismatchedTasks = 0;
        int duplicateChunks = 0;
        for (Map<String, Object> job : jobs) {
            String id = String.valueOf(job.get("id"));
            String status = String.valueOf(job.getOrDefault("status", ""));
            int chunks = ((Number) job.getOrDefault("chunks", 0)).intValue();
            int totalChunks = ((Number) job.getOrDefault("totalChunks", 0)).intValue();
            if (!"成功".equals(status)) continue;
            if (chunks <= 0 || chunks != totalChunks) {
                mismatchedTasks++;
                if (violations.size() < 100) {
                    violations.add("任务 " + id + " 状态成功，但写入片段 " + chunks + " / 计划 " + totalChunks);
                }
            }
            List<com.cais.vector.domain.VectorModels.VectorSource> records = byTask.getOrDefault(id, List.of());
            if (records.size() < totalChunks && violations.size() < 100) {
                violations.add("任务 " + id + " 计划 " + totalChunks + " 片，来源台账仅 " + records.size() + " 条");
            }
            java.util.Set<String> chunkIds = new java.util.HashSet<>();
            for (var record : records) {
                checkedVectors++;
                if (record.getChunkId() == null || record.getChunkId().isBlank()) {
                    violations.add("向量条目 " + record.getVectorId() + " 缺少片段编号");
                } else if (!chunkIds.add(record.getChunkId())) {
                    duplicateChunks++;
                    if (violations.size() < 100) {
                        violations.add("任务 " + id + " 存在重复片段编号：" + record.getChunkId());
                    }
                }
                String text = record.getContentText();
                if (text == null || text.isBlank()) {
                    // 老数据可能未保存文本快照：回退到 DM8 分块追溯表，两者都没有才算真的空
                    text = vectors.ingestedText(record.getVectorId()).orElse("");
                }
                if (text == null || text.isBlank()) {
                    emptyContents++;
                    if (violations.size() < 100) violations.add("向量条目 " + record.getVectorId() + " 的入库文本为空");
                }
                Boolean inStore = vectors.existsInVectorStore(collectionId, record.getVectorId());
                boolean traceExists = vectors.hasTraceEntry(collectionId, record.getVectorId());
                if (Boolean.FALSE.equals(inStore)) {
                    missingVectors++;
                    if (violations.size() < 100) {
                        violations.add("向量条目 " + record.getVectorId() + " 在向量库中不存在");
                    }
                } else if (inStore == null && !traceExists) {
                    missingVectors++;
                    if (violations.size() < 100) {
                        violations.add("向量条目 " + record.getVectorId() + " 无法确认存在（向量后端不支持逐个校验）");
                    }
                } else if (!traceExists) {
                    missingTrace++;
                    if (violations.size() < 100) {
                        violations.add("向量条目 " + record.getVectorId() + " 在向量库存在，但 DM8 追溯缺失");
                    }
                }
            }
        }
        /* 物理向量反查：向量库不可用时不阻断整次校验，降级为"本次未核验"并写明原因。 */
        List<String> physicalIds = null;
        try {
            physicalIds = vectors.physicalVectorIds(collectionId);
        } catch (RuntimeException failure) {
            if (violations.size() < 100) {
                violations.add("本次无法核验物理向量库：" + (failure.getMessage() == null ? "向量库不可用" : failure.getMessage()));
            }
        }
        boolean physicalAuditComplete = physicalIds != null;
        int orphanVectors = 0;
        int withdrawnVectors = 0;
        if (physicalAuditComplete) {
            java.util.Set<String> activeSourceIds = new java.util.HashSet<>();
            java.util.Set<String> withdrawnSourceIds = new java.util.HashSet<>();
            for (var source : sources) {
                if (source.isWithdrawn()) withdrawnSourceIds.add(source.getVectorId());
                else activeSourceIds.add(source.getVectorId());
            }
            for (String vectorId : physicalIds) {
                if (activeSourceIds.contains(vectorId)) continue;
                if (withdrawnSourceIds.contains(vectorId)) {
                    withdrawnVectors++;
                    if (violations.size() < 100) violations.add("已撤回向量 " + vectorId + " 仍在向量库中");
                } else if (!vectors.hasTraceEntry(collectionId, vectorId)) {
                    orphanVectors++;
                    if (violations.size() < 100) violations.add("物理向量 " + vectorId + " 缺少来源台账和 DM8 追溯");
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("collectionId", collectionId);
        /* 向量条目数必须与向量库里的实际条数一致：不一致时给出可修复项，而不是显示一个台账数字。 */
        int recordedCount = vectors.recordedEntryCount(collectionId);
        Integer physicalCount = physicalAuditComplete ? physicalIds.size() : null;
        boolean entryCountMatches = physicalCount == null || recordedCount == physicalCount;
        if (!entryCountMatches && violations.size() < 100) {
            violations.add("向量条目数 " + recordedCount + " 与向量库实际 " + physicalCount + " 不一致");
        }
        result.put("tasks", jobs.size());
        result.put("checkedVectors", checkedVectors);
        result.put("missingVectors", missingVectors);
        result.put("missingTrace", missingTrace);
        result.put("emptyContents", emptyContents);
        result.put("mismatchedTasks", mismatchedTasks);
        result.put("duplicateChunks", duplicateChunks);
        result.put("physicalAuditComplete", physicalAuditComplete);
        result.put("checkedPhysicalVectors", physicalAuditComplete ? physicalIds.size() : 0);
        result.put("orphanVectors", orphanVectors);
        result.put("withdrawnVectors", withdrawnVectors);
        result.put("entryCount", recordedCount);
        result.put("physicalVectors", physicalCount);
        result.put("entryCountMatches", entryCountMatches);
        result.put("consistent", physicalAuditComplete && violations.isEmpty());
        result.put("violations", violations);
        result.put("checkedAt", Instant.now().toString());
        return result;
    }

    /**
     * 数据验证修复（VEC-F3②）：把可修复的问题真正修掉，而不是只出报告。
     *
     * <p>可修复：
     * <ul>
     *   <li>任务显示成功但片段数不符：来源台账齐全时按台账修正片段数，否则标记任务失败；</li>
     *   <li>来源台账文本为空但 DM8 追溯表有文本：按「系统校验修复」生成一次版本修正并回填。</li>
     * </ul>
     * 缺失的向量追溯、重复片段编号无法安全自动重建，只保留在校验结果里，需人工核查。
     */
    public Map<String, Object> repair(String collectionId) {
        if (collectionId == null || collectionId.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "请指定知识库集合");
        }
        /* 修复是写操作：同一知识库同时只允许一次，避免超时重试时两次修复互相覆盖台账 */
        if (!repairing.add(collectionId)) {
            throw new BizException(ErrorCode.TASK_STATE_CONFLICT, "该知识库正在修复，请稍后查看结果");
        }
        try {
            return repairInternal(collectionId);
        } finally {
            repairing.remove(collectionId);
        }
    }

    private Map<String, Object> repairInternal(String collectionId) {
        List<Map<String, Object>> jobs = list().stream()
                .filter(job -> collectionId.equals(String.valueOf(job.get("collectionId"))))
                .toList();
        List<com.cais.vector.domain.VectorModels.VectorSource> sources = sourceLedger == null
                ? List.of() : sourceLedger.listSources(null, collectionId);
        Map<String, List<com.cais.vector.domain.VectorModels.VectorSource>> byTask = new LinkedHashMap<>();
        for (var source : sources) {
            if (source.isWithdrawn()) continue;
            String taskId = source.getSourceTaskId();
            if (taskId == null || taskId.isBlank()) continue;
            byTask.computeIfAbsent(taskId, ignored -> new ArrayList<>()).add(source);
        }
        /* 已撤回片段的台账记录：它们在库里若还在，就是本次要清掉的残留 */
        java.util.Set<String> withdrawnIds = new java.util.HashSet<>();
        for (var source : sources) {
            if (source.isWithdrawn() && source.getVectorId() != null) withdrawnIds.add(source.getVectorId());
        }
        int fixedJobs = 0;
        int fixedContents = 0;
        int fixedVectors = 0;
        int manual = 0;
        List<String> notes = new ArrayList<>();
        for (Map<String, Object> job : jobs) {
            String id = String.valueOf(job.get("id"));
            if (!"成功".equals(String.valueOf(job.getOrDefault("status", "")))) continue;
            int chunks = ((Number) job.getOrDefault("chunks", 0)).intValue();
            int totalChunks = ((Number) job.getOrDefault("totalChunks", 0)).intValue();
            List<com.cais.vector.domain.VectorModels.VectorSource> records = byTask.getOrDefault(id, List.of());
            if (chunks <= 0 || chunks != totalChunks) {
                if (totalChunks > 0 && records.size() == totalChunks) {
                    registry.update(DATASET, id, Map.of("chunks", totalChunks,
                            "error", "", "at", Instant.now().toString()));
                    fixedJobs++;
                    notes.add("任务 " + id + " 片段数已按来源台账修正为 " + totalChunks);
                } else {
                    registry.update(DATASET, id, Map.of("status", "失败",
                            "error", "数据验证修复：计划 " + totalChunks + " 片，来源台账 " + records.size()
                                    + " 条，片段不齐，需人工核查",
                            "at", Instant.now().toString()));
                    fixedJobs++;
                    notes.add("任务 " + id + " 已标记为失败（片段不齐）");
                }
            } else if (totalChunks > 0 && records.isEmpty()) {
                /* 片段数看着对得上、但来源台账一条都没有：没有可重建的片段文本，只能人工处置，不能静默跳过 */
                manual++;
                notes.add("任务 " + id + " 计划 " + totalChunks + " 片但来源台账 0 条，缺少可追溯的片段文本，需人工处置");
            }
            for (var record : records) {
                String text = record.getContentText();
                if (text == null || text.isBlank()) {
                    text = vectors.ingestedText(record.getVectorId()).orElse("");
                    if (!text.isBlank() && sourceLedger != null) {
                        try {
                            sourceLedger.updateSourceText(record.getVectorId(), text);
                            fixedContents++;
                            notes.add("向量条目 " + record.getVectorId() + " 已回填入库文本");
                        } catch (RuntimeException failure) {
                            notes.add("向量条目 " + record.getVectorId() + " 回填文本失败：" + failure.getMessage());
                        }
                    }
                }
                if (text == null || text.isBlank()) {
                    manual++;
                    notes.add("向量条目 " + record.getVectorId() + " 无可用文本，无法重建向量");
                    continue;
                }
                boolean traceExists = vectors.hasTraceEntry(collectionId, record.getVectorId());
                Boolean inStore = vectors.existsInVectorStore(collectionId, record.getVectorId());
                boolean storeMissing = Boolean.FALSE.equals(inStore);
                if (!storeMissing && traceExists) continue;
                try {
                    Map<String, Object> jobInfo = null;
                    try { jobInfo = job(record.getSourceTaskId()); } catch (RuntimeException ignored) { }
                    String strategy = jobInfo == null ? "按句切分"
                            : String.valueOf(jobInfo.getOrDefault("strategy", "按句切分"));
                    int chunkSize = jobInfo == null ? 512
                            : ((Number) jobInfo.getOrDefault("chunkSize", 512)).intValue();
                    int overlap = jobInfo == null ? 0
                            : ((Number) jobInfo.getOrDefault("overlap", 0)).intValue();
                    VectorEntryRequest request = new VectorEntryRequest(
                            record.getSrcFileId() == null ? "" : record.getSrcFileId(),
                            record.getChunkId(), record.getVectorId(),
                            // 预览字段同时用于重新生成向量：必须传完整片段文本，截断会让修复后的向量与原向量不一致。
                            text, record.getSourceTaskId(), chunkIndex(record.getChunkId()),
                            strategy, Math.max(2, chunkSize), Math.max(0, overlap), text);
                    vectors.repairEntry(collectionId, request, storeMissing, !traceExists);
                    fixedVectors++;
                    notes.add("向量条目 " + record.getVectorId() + " 已重建："
                            + (storeMissing ? "向量库" : "") + (!traceExists ? (storeMissing ? " + " : "") + "DM8 追溯" : ""));
                } catch (RuntimeException failure) {
                    manual++;
                    notes.add("向量条目 " + record.getVectorId() + " 重建失败：" + failure.getMessage());
                }
            }
        }
        if (sourceLedger != null) sourceLedger.flushState();
        /* 物理向量里既没有来源台账、也没有 DM8 追溯的条目无法被引用与追溯：修复时按孤儿向量清理。 */
        int removedOrphans = 0;
        int removedWithdrawn = 0;
        List<String> physical = null;
        if (!vectors.traceEnabled()) {
            notes.add("追溯表未启用，跳过孤儿向量清理");
        } else {
            try {
                physical = vectors.physicalVectorIds(collectionId);
            } catch (RuntimeException failure) {
                notes.add("物理向量反查失败，未清理孤儿向量：" + failure.getMessage());
            }
        }
        if (physical != null) {
            java.util.Set<String> known = new java.util.HashSet<>();
            for (var source : sources) known.add(source.getVectorId());
            List<String> orphans = new ArrayList<>();
            for (String vectorId : physical) {
                if (known.contains(vectorId)) continue;
                if (vectors.hasTraceEntry(collectionId, vectorId)) continue;
                orphans.add(vectorId);
            }
            if (!orphans.isEmpty()) {
                try {
                    removedOrphans = vectors.deleteVectors(collectionId, orphans);
                    notes.add("已删除 " + removedOrphans + " 条无来源台账、无追溯的孤儿向量：" + String.join("、", orphans));
                } catch (RuntimeException failure) {
                    notes.add("孤儿向量删除失败：" + failure.getMessage());
                }
            }
            /* 已撤回的片段：台账标记了撤回但向量还在库里，按台账删除，避免撤回内容继续占库并让条数虚高 */
            List<String> withdrawnInStore = new ArrayList<>();
            for (String vectorId : physical) {
                if (withdrawnIds.contains(vectorId)) withdrawnInStore.add(vectorId);
            }
            if (!withdrawnInStore.isEmpty()) {
                try {
                    removedWithdrawn = vectors.deleteVectors(collectionId, withdrawnInStore);
                    notes.add("已删除 " + removedWithdrawn + " 条已撤回但仍留在向量库的向量：" + String.join("、", withdrawnInStore));
                } catch (RuntimeException failure) {
                    notes.add("已撤回向量删除失败：" + failure.getMessage());
                }
            }
        }
        /* 条目数是列表展示的数量：按向量库实际条数校准，避免台账与实际不一致。
         * 校准失败不能让整次修复报错——前面的修复已经生效，用户需要看到结果。 */
        int recordedBefore = vectors.recordedEntryCount(collectionId);
        int reconciledCount;
        try {
            reconciledCount = vectors.reconcileEntryCount(collectionId);
        } catch (RuntimeException failure) {
            reconciledCount = -1;
            notes.add("条目数校准失败，台账保持 " + recordedBefore + "：" + failure.getMessage());
        }
        /* 校准可能因并发写入被跳过，文案按回读到的真实台账值写，不能按「以为写回去的值」写。 */
        int recordedAfter = reconciledCount >= 0 ? vectors.recordedEntryCount(collectionId) : recordedBefore;
        if (recordedAfter != recordedBefore) {
            notes.add("向量条目数由 " + recordedBefore + " 校准为 " + recordedAfter);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("collectionId", collectionId);
        out.put("fixedJobs", fixedJobs);
        out.put("fixedContents", fixedContents);
        out.put("fixedVectors", fixedVectors);
        out.put("removedOrphans", removedOrphans);
        out.put("removedWithdrawn", removedWithdrawn);
        out.put("entryCount", recordedAfter);
        out.put("manual", manual);
        out.put("notes", notes);
        out.put("validation", validate(collectionId));
        return out;
    }

    /**
     * 删除知识库时清理任务台账、采编参数与来源台账，避免留下无法访问的孤儿记录。
     *
     * <p>清理失败不能让删除接口报错：集合已经删掉了，再抛错会让页面提示「删除失败」，
     * 实际刷新后又消失。这里逐步兜住异常，把失败原因放进 warnings 如实返回。</p>
     */
    public synchronized Map<String, Object> purgeCollection(String collectionId) {
        List<String> warnings = new ArrayList<>();
        int jobs = 0;
        try {
            for (Map<String, Object> job : new ArrayList<>(registry.list(DATASET))) {
                if (!collectionId.equals(String.valueOf(job.get("collectionId")))) continue;
                registry.remove(DATASET, String.valueOf(job.get("id")));
                jobs++;
            }
        } catch (RuntimeException failure) {
            warnings.add("任务台账未清理：" + failure.getMessage());
        }
        int configs = 0;
        try {
            for (Map<String, Object> row : new ArrayList<>(registry.list("tf.kbConfig"))) {
                if (!collectionId.equals(String.valueOf(row.get("collectionId")))) continue;
                registry.remove("tf.kbConfig", String.valueOf(row.get("id")));
                configs++;
            }
        } catch (RuntimeException failure) {
            /* 业务身份下控制台台账只开放 kb.task，采编参数在业务台账里，这里读不到就如实记录 */
            warnings.add("采编参数未清理：" + failure.getMessage());
        }
        int sources = 0;
        try {
            sources = sourceLedger == null ? 0 : sourceLedger.removeCollection(collectionId);
        } catch (RuntimeException failure) {
            warnings.add("来源台账未清理：" + failure.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobs", jobs);
        out.put("configs", configs);
        out.put("sources", sources);
        out.put("warnings", warnings);
        return out;
    }

    private static int chunkIndex(String chunkId) {
        if (chunkId == null) return 0;
        int dash = chunkId.lastIndexOf('-');
        if (dash < 0) return 0;
        try {
            return Integer.parseInt(chunkId.substring(dash + 1));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    public Map<String, Object> job(String id) {
        return registry.list(DATASET).stream()
                .filter(item -> id.equals(item.get("id")) && !Boolean.TRUE.equals(item.get("deleted")))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "向量化任务不存在：" + id));
    }

    /** Archive only a terminal task. This never withdraws vectors or erases its audit trail. */
    public synchronized Map<String, Object> archive(String id, String reason) {
        Map<String, Object> job = job(id);
        if (!List.of("成功", "失败", "已取消").contains(String.valueOf(job.get("status")))) {
            throw new BizException(ErrorCode.TASK_STATE_CONFLICT, "运行中的任务不能归档，请等待完成或先取消");
        }
        if (Boolean.TRUE.equals(job.get("archived"))) return job;
        return registry.update(DATASET, id, Map.of("archived", true,
                "archivedAt", Instant.now().toString(), "archiveReason", reason == null ? "" : reason.trim()));
    }

    public synchronized Map<String, Object> restore(String id) {
        Map<String, Object> job = job(id);
        if (!Boolean.TRUE.equals(job.get("archived"))) return job;
        if (Boolean.TRUE.equals(job.get("withdrawn"))) {
            throw new BizException(ErrorCode.TASK_STATE_CONFLICT, "知识内容已撤回，不能恢复原向量化任务；如需重新入库请重新提交来源");
        }
        return registry.update(DATASET, id, Map.of("archived", false, "restoredAt", Instant.now().toString()));
    }

    /**
     * 版本保留清理：只清理「已撤回且超过保留期」的任务台账。
     *
     * <p>未撤回的归档任务不在清理范围（向量仍在库中，删台账会留下孤儿向量）。
     * 删除时保留同编号墓碑，审计仍能查到这个任务曾经存在。
     */
    public synchronized int purgeArchived(String collectionId, Instant before) {
        int purged = 0;
        for (Map<String, Object> job : new ArrayList<>(registry.list(DATASET))) {
            if (!collectionId.equals(String.valueOf(job.get("collectionId")))) continue;
            if (!Boolean.TRUE.equals(job.get("archived"))) continue;
            if (!Boolean.TRUE.equals(job.get("withdrawn"))) continue;
            if (Boolean.TRUE.equals(job.get("deleted"))) continue;
            String stamp = String.valueOf(job.getOrDefault("withdrawnAt",
                    job.getOrDefault("archivedAt", job.getOrDefault("at", ""))));
            try {
                if (!stamp.isBlank() && Instant.parse(stamp).isAfter(before)) continue;
            } catch (java.time.format.DateTimeParseException ignored) {
                // 时间不可解析时按超期处理，避免保留期永远不生效
            }
            registry.remove(DATASET, String.valueOf(job.get("id")));
            purged++;
        }
        return purged;
    }

    /** Irreversible retrieval withdrawal is intentionally separate from reversible archive. */
    public Map<String, Object> withdraw(String id, String fileId) {
        String collectionId;
        Map<String, Object> updated;
        /* 只有台账读写与向量删除在锁内；条目数校准要访问向量库，放到锁外，避免拖住提交与归档。 */
        synchronized (this) {
            Map<String, Object> job = job(id);
            if (!String.valueOf(job.get("fileId")).equals(fileId)) {
                throw new BizException(ErrorCode.PARAM_ERROR, "确认文件 ID 与任务不匹配");
            }
            if (!List.of("成功", "失败", "已取消").contains(String.valueOf(job.get("status")))) {
                throw new BizException(ErrorCode.TASK_STATE_CONFLICT, "运行中的任务不能撤回知识内容");
            }
            if (Boolean.TRUE.equals(job.get("withdrawn"))) return job;
            collectionId = String.valueOf(job.get("collectionId"));
            int count = vectors.withdrawKnowledgeTask(collectionId, id);
            if (sourceLedger != null) sourceLedger.markKnowledgeSourceWithdrawn(id, collectionId);
            updated = registry.update(DATASET, id, Map.of("withdrawn", true, "withdrawnAt", Instant.now().toString(),
                    "withdrawnVectors", count, "archived", true, "archivedAt", Instant.now().toString(),
                    "archiveReason", "知识内容已撤回"));
        }
        /* 撤回是删向量 + 台账减法两步，完成后按实际条数校准，避免条目数停在旧值。 */
        reconcileEntryCountQuietly(collectionId);
        return updated;
    }

    /**
     * 条目数校准：累计值来自「向量库写入 + 台账加一」两步，任一步失败（追溯写入超时、重复命中、
     * 撤回扣减截到 0、索引重建先减后加）都会让页面数字与向量库实际条数不一致。
     * 每次入库任务结束、每次撤回后按实际条数回写一次；校准失败只记日志，不影响任务结果。
     */
    private void reconcileEntryCountQuietly(String collectionId) {
        if (collectionId == null || collectionId.isBlank()) return;
        try {
            vectors.reconcileEntryCount(collectionId);
        } catch (RuntimeException failure) {
            LOG.warn("知识库 {} 条目数校准失败，待数据校验重试：{}", collectionId, failure.getMessage());
        }
    }

    public Map<String, Object> queueSnapshot() {
        return Map.of("queuedTasks", workers.getQueue().size(), "runningTasks", workers.getActiveCount(),
                "maxConcurrency", workers.getMaximumPoolSize());
    }

    public List<String> preview(KnowledgeIngestRequest request) {
        if (request.chunkSize() < 2 || request.chunkSize() > 8000 || request.overlap() < 0
                || request.overlap() >= request.chunkSize()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "分块长度或重叠长度无效");
        }
        if (!List.of("按句切分", "按语义切分", "固定长度切分", "不切分").contains(request.strategy())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "切分策略无效");
        }
        requireContentLength(request.content());
        if ("按语义切分".equals(request.strategy())) vectors.validateIngestCollection(request.collectionId());
        return splitChunks(request);
    }

    public synchronized Map<String, Object> submit(KnowledgeIngestRequest request) {
        return submit(request, "MANUAL");
    }

    /**
     * 提交入库任务；origin 记录来源（MANUAL 人工 / SCAN 定时扫描 / EVENT 事件推送），
     * 定时扫描据此避免覆盖事件或人工更新。
     */
    public synchronized Map<String, Object> submit(KnowledgeIngestRequest request, String origin) {
        if (request.chunkSize() < 2 || request.chunkSize() > 8000 || request.overlap() < 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "分块长度或重叠长度超出允许范围");
        }
        if (request.overlap() >= request.chunkSize()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "重叠长度必须小于分块长度");
        }
        if (!List.of("按句切分", "按语义切分", "固定长度切分", "不切分").contains(request.strategy())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "切分策略无效");
        }
        requireContentLength(request.content());
        VectorCollectionView collection = vectors.list().stream()
                .filter(item -> item.collectionId().equals(request.collectionId()))
                .findFirst().orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "目标知识库不存在"));
        String fingerprint = fingerprintFor(request);
        String id = jobIdFor(request);
        Map<String, Object> existing = registry.list(DATASET).stream()
                .filter(job -> id.equals(job.get("id"))).findFirst().orElse(null);
        // 已清理（只剩编号墓碑）的任务按不存在处理，重新入库。
        if (existing != null && Boolean.TRUE.equals(existing.get("deleted"))) existing = null;
        if (existing != null && !"失败".equals(existing.get("status"))
                && !Boolean.TRUE.equals(existing.get("withdrawn"))) {
            return Boolean.TRUE.equals(existing.get("archived")) ? restore(id) : existing;
        }
        vectors.validateIngestCollection(request.collectionId());
        /*
         * 语义切分要逐句调用 Embedding，不能在 HTTP 提交线程里做（一篇几十句的文档就会超过前端 15s 超时，
         * 表现为「导入超时」）。这里只对确定性策略预先切分；语义策略交给后台 worker 切分并回填 totalChunks。
         */
        boolean semantic = "按语义切分".equals(request.strategy());
        List<String> plannedChunks = semantic ? List.of() : splitChunks(request);
        if (!semantic) ensureQuota(collection, request.collectionId(), fingerprint, plannedChunks);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("fileId", request.fileId());
        row.put("collectionId", request.collectionId());
        row.put("collection", collection.name());
        row.put("model", collection.embeddingModel());
        row.put("sourceTaskId", request.sourceTaskId() == null ? "" : request.sourceTaskId());
        row.put("chunks", 0);
        row.put("strategy", request.strategy());
        row.put("chunkSize", request.chunkSize());
        row.put("overlap", "固定长度切分".equals(request.strategy()) ? request.overlap() : 0);
        row.put("totalChunks", plannedChunks.size());
        row.put("status", "待处理");
        row.put("archived", false);
        row.put("withdrawn", false);
        row.put("error", "");
        row.put("origin", origin);
        row.put("submittedAt", Instant.now().toString());
        row.put("at", Instant.now().toString());
        Map<String, Object> saved = existing == null ? registry.create(DATASET, row) : registry.update(DATASET, id, row);
        try {
            workers.execute(() -> process(id, request, fingerprint, plannedChunks));
        } catch (RuntimeException rejected) {
            registry.update(DATASET, id, Map.of("status", "失败", "error", "任务队列已满，请稍后重试"));
            throw new BizException(ErrorCode.DEPENDENCY_UNAVAILABLE, "知识库任务队列已满，请稍后重试");
        }
        return saved;
    }

    /** One independent, idempotent job per source/collection pair. A rejected pair does not
     *  hide tasks already accepted for the same user batch. */
    public List<Map<String, Object>> submitBatch(List<KnowledgeIngestRequest> requests) {
        if (requests == null || requests.isEmpty() || requests.size() > 40) {
            throw new BizException(ErrorCode.PARAM_ERROR, "每批必须提交 1 至 40 个来源—知识库任务");
        }
        List<Map<String, Object>> outcomes = new ArrayList<>();
        for (KnowledgeIngestRequest request : requests) {
            try {
                Map<String, Object> task = submit(request);
                outcomes.add(Map.of("fileId", request.fileId(), "collectionId", request.collectionId(),
                        "accepted", true, "taskId", task.get("id")));
            } catch (BizException failure) {
                outcomes.add(Map.of("fileId", request.fileId(), "collectionId", request.collectionId(),
                        "accepted", false, "error", failure.getMessage()));
            }
        }
        return List.copyOf(outcomes);
    }

    /** 配额预检：只统计尚未写入的切片，重试时不会把已存在的切片重复算进去。 */
    private void ensureQuota(VectorCollectionView collection, String collectionId, String fingerprint, List<String> chunks) {
        long missingChunks = 0;
        for (int index = 0; index < chunks.size(); index++) {
            String vectorId = "V" + digest(fingerprint + ":" + index).substring(0, 30);
            if (!vectors.hasStoredEntry(collectionId, vectorId)) missingChunks++;
        }
        if (collection.entryCount() + missingChunks > collection.quota()) {
            throw new BizException(ErrorCode.QUOTA_EXCEEDED, "目标知识库剩余条目配额不足，本次尚需 " + missingChunks + " 条");
        }
    }

    /**
     * 写单个切片：Embedding 偶发 5xx 时重试（失败发生在写入之前，可安全重试；
     * 已写入的切片按 vectorId 幂等，直接视为成功继续）。
     */
    private void registerChunkWithRetry(KnowledgeIngestRequest request, String vectorId, String chunkId,
                                        String text, int index, String jobId) {
        BizException lastFailure = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                withEmbeddingSlot(0, () -> {
                    vectors.registerEntry(request.collectionId(), new VectorEntryRequest(request.fileId(), chunkId,
                            vectorId, text, jobId, index, request.strategy(), request.chunkSize(), request.overlap(), text));
                    return null;
                });
                return;
            } catch (BizException failure) {
                BizException effective = failure;
                if (failure.getCode() == ErrorCode.IDEMPOTENT_CONFLICT.getCode()) {
                    Boolean stored = vectors.existsInVectorStore(request.collectionId(), vectorId);
                    if (stored == null || stored) return;
                    try {
                        withEmbeddingSlot(0, () -> {
                            vectors.repairEntry(request.collectionId(), new VectorEntryRequest(request.fileId(),
                                    chunkId, vectorId, text, jobId, index, request.strategy(),
                                    request.chunkSize(), request.overlap(), text), true, false);
                            return null;
                        });
                        return;
                    } catch (BizException repairFailure) {
                        effective = repairFailure;
                    }
                }
                if (!transientVectorFailure(effective)) throw effective;
                lastFailure = effective;
            }
            try {
                Thread.sleep(400L * (attempt + 1));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw lastFailure == null ? new BizException(ErrorCode.ENGINE_VECTOR_FAILED, "向量生成阶段失败") : lastFailure;
    }

    private static boolean transientVectorFailure(BizException failure) {
        String message = failure.getMessage() == null ? "" : failure.getMessage();
        return failure.getCode() == ErrorCode.ENGINE_VECTOR_FAILED.getCode() || message.contains("HTTP 50");
    }

    private void process(String id, KnowledgeIngestRequest request, String fingerprint, List<String> plannedChunks) {
        String stage = "文本切分";
        try {
            registry.update(DATASET, id, Map.of("status", "解析中"));
            /* 语义策略在后台切分（要逐句取向量），其余策略用提交时算好的分块。 */
            List<String> chunks = plannedChunks.isEmpty()
                    ? splitChunks(request, (done, total) -> registry.update(DATASET, id,
                            Map.of("splitDone", done, "splitTotal", total)))
                    : plannedChunks;
            VectorCollectionView quotaCollection = vectors.list().stream()
                    .filter(item -> item.collectionId().equals(request.collectionId())).findFirst()
                    .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "目标知识库不存在"));
            ensureQuota(quotaCollection, request.collectionId(), fingerprint, chunks);
            registry.update(DATASET, id, Map.of("status", "向量化中", "totalChunks", chunks.size()));
            long lastProgressAt = System.nanoTime();
            for (int index = 0; index < chunks.size(); index++) {
                String vectorId = "V" + digest(fingerprint + ":" + index).substring(0, 30);
                String chunkId = id + "-" + index;
                String text = chunks.get(index);
                stage = "第 " + (index + 1) + " 片段：向量生成/写入/追溯";
                registerChunkWithRetry(request, vectorId, chunkId, text, index, id);
                if (sourceLedger != null) {
                    stage = "第 " + (index + 1) + " 片段：来源台账";
                    sourceLedger.recordStoredChunk(vectorId, request.fileId(), request.collectionId(),
                            quotaCollection.dimension(), quotaCollection.embeddingModel(), chunkId, text, id);
                }
                /*
                 * 进度每 2 秒最多落一次：registry.json 是整份文件重写，逐片更新会让导入成本随切片数线性放大。
                 * 最后一片必须落，保证任务完成时片段数与实际一致。
                 */
                long now = System.nanoTime();
                if (index == chunks.size() - 1 || now - lastProgressAt >= 2_000_000_000L) {
                    registry.update(DATASET, id, Map.of("chunks", index + 1));
                    lastProgressAt = now;
                }
            }
            if (sourceLedger != null) sourceLedger.flushState();
            stage = "索引构建";
            vectors.buildIndex(request.collectionId());
            vectors.markKnowledgeTask(id, true);
            // 同一文件在同一知识库只保留最新版本：撤回旧的、已成功的任务向量，避免检索命中重复内容。
            int cleanupFailures;
            try {
                cleanupFailures = withdrawSupersededVersions(request.collectionId(), request.fileId(), id);
            } catch (RuntimeException failure) {
                cleanupFailures = 1;
                LOG.warn("知识库任务 {} 的旧版本清理待重试：{}", id, failure.getMessage());
            }
            registry.update(DATASET, id, Map.of("status", "成功", "chunks", chunks.size(),
                    "at", Instant.now().toString(), "versionCleanupPending", cleanupFailures > 0,
                    /* error 只用于真正的失败；版本清理是成功任务的后续动作，单独记在 cleanupNote，避免日志把成功显示成失败。 */
                    "cleanupNote", cleanupFailures > 0 ? "旧版本撤回待重试" : "", "error", ""));
            // 新版本可能先于旧任务完成；旧任务完成后须撤回自身，不能把后来提交的版本撤回。
            try {
                Map<String, Object> current = refreshJobs().stream()
                        .filter(job -> id.equals(job.get("id"))).findFirst().orElseThrow();
                if (hasNewerSuccessfulVersion(refreshJobs(), current)) {
                    withdraw(id, request.fileId());
                    registry.update(DATASET, id, Map.of("versionCleanupPending", false, "cleanupNote", ""));
                }
            } catch (RuntimeException failure) {
                registry.update(DATASET, id, Map.of("versionCleanupPending", true,
                        "cleanupNote", "版本清理待重试"));
                LOG.warn("知识库任务 {} 的版本清理待重试：{}", id, failure.getMessage());
            }
            if (sourceLedger != null) sourceLedger.finishRealUpdate(id, true, chunks.size(), "", this);
        } catch (Exception failure) {
            LOG.error("知识库任务 {} 在 {} 失败", id, stage, failure);
            String message = stage + "失败：" + (failure instanceof BizException ? failure.getMessage() : "服务端异常，请按任务编号查询服务日志");
            try { vectors.markKnowledgeTask(id, false); }
            catch (Exception markFailure) { LOG.warn("知识库任务 {} 状态回写失败", id, markFailure); }
            /*
             * 失败任务已写入的切片必须撤回：否则界面显示“失败”，这些半成品仍会被检索命中，
             * 而数据校验只检查成功任务，看不出来。撤回失败只记日志，不影响失败状态落库。
             */
            try {
                int removed = vectors.withdrawKnowledgeTask(request.collectionId(), id);
                if (removed > 0) LOG.warn("知识库任务 {} 失败，已撤回 {} 条半成品向量", id, removed);
            } catch (Exception withdrawFailure) {
                LOG.warn("知识库任务 {} 失败后撤回半成品向量未完成，需人工核对：{}", id, withdrawFailure.getMessage());
            }
            /*
             * 追溯写入失败的那一片已由 registerEntry 内部回滚向量，这里不再按计划 ID 兜底删除：
             * 同一文件重复入库时 vectorId 相同，误删会把上一版仍然有效的向量一起删掉。
             */
            if (sourceLedger != null) sourceLedger.flushState();
            // 索引构建失败时把集合标为异常，避免知识库列表长期显示"正常"（状态是真实结果，不该是台账）。
            if (stage.contains("索引")) {
                try { vectors.markIndexFailed(request.collectionId()); }
                catch (Exception markFailure) { LOG.warn("知识库集合 {} 索引状态回写失败：{}", request.collectionId(), markFailure.getMessage()); }
            }
            registry.update(DATASET, id, Map.of("status", "失败", "error", message.length() > 240 ? message.substring(0, 240) : message,
                    "at", Instant.now().toString()));
            if (sourceLedger != null) sourceLedger.finishRealUpdate(id, false, 0, message, this);
        } finally {
            /* 成功/失败都要校准：条目数是页面直接展示的数量，必须与向量库实际条数一致。 */
            reconcileEntryCountQuietly(request.collectionId());
        }
    }

    /**
     * 同一「文件 + 知识库」只保留最新版本：新任务成功后撤回旧的、已成功且未撤回的任务向量。
     * 失败/已撤回的任务不处理；撤回失败记录待重试，不把版本清理误报为已完成。
     */
    private int withdrawSupersededVersions(String collectionId, String fileId, String keepJobId) {
        List<Map<String, Object>> jobs = refreshJobs();
        Map<String, Object> keep = jobs.stream()
                .filter(job -> keepJobId.equals(String.valueOf(job.get("id")))).findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "当前知识库任务不存在"));
        Instant keepSubmittedAt = versionTime(keep);
        int failures = 0;
        for (Map<String, Object> other : jobs) {
            String otherId = String.valueOf(other.getOrDefault("id", ""));
            if (otherId.isBlank() || otherId.equals(keepJobId)) continue;
            if (!collectionId.equals(String.valueOf(other.getOrDefault("collectionId", "")))) continue;
            if (!fileId.equals(String.valueOf(other.getOrDefault("fileId", "")))) continue;
            if (!"成功".equals(String.valueOf(other.getOrDefault("status", "")))) continue;
            if (Boolean.TRUE.equals(other.get("withdrawn"))) continue;
            if (!versionTime(other).isBefore(keepSubmittedAt)) continue;
            try {
                withdraw(otherId, fileId);
                LOG.info("知识库任务 {} 已被新版本 {} 替代，旧向量已撤回", otherId, keepJobId);
            } catch (RuntimeException failure) {
                failures++;
                LOG.warn("撤回旧版本任务 {} 失败：{}", otherId, failure.getMessage());
            }
        }
        return failures;
    }

    @Scheduled(fixedDelay = 300_000)
    public void retryPendingVersionCleanup() {
        List<Map<String, Object>> jobs = refreshJobs();
        for (Map<String, Object> job : jobs) {
            if (!Boolean.TRUE.equals(job.get("versionCleanupPending"))
                    || !"成功".equals(String.valueOf(job.get("status")))
                    || Boolean.TRUE.equals(job.get("withdrawn"))) continue;
            String id = String.valueOf(job.get("id"));
            try {
                if (hasNewerSuccessfulVersion(jobs, job)) {
                    withdraw(id, String.valueOf(job.get("fileId")));
                    registry.update(DATASET, id, Map.of("versionCleanupPending", false, "cleanupNote", ""));
                    continue;
                }
                if (withdrawSupersededVersions(String.valueOf(job.get("collectionId")),
                        String.valueOf(job.get("fileId")), id) == 0) {
                    registry.update(DATASET, id, Map.of("versionCleanupPending", false, "cleanupNote", ""));
                }
            } catch (RuntimeException failure) {
                LOG.warn("知识库任务 {} 的旧版本清理仍未完成：{}", id, failure.getMessage());
            }
        }
    }

    /** 读取任务列表（registry 可能返回不可变视图，这里统一复制一份）。 */
    private List<Map<String, Object>> refreshJobs() {
        return new ArrayList<>(registry.list(DATASET));
    }

    private static boolean hasNewerSuccessfulVersion(List<Map<String, Object>> jobs, Map<String, Object> job) {
        String id = String.valueOf(job.get("id"));
        return jobs.stream().anyMatch(other -> !id.equals(String.valueOf(other.get("id")))
                && String.valueOf(job.get("collectionId")).equals(String.valueOf(other.get("collectionId")))
                && String.valueOf(job.get("fileId")).equals(String.valueOf(other.get("fileId")))
                && "成功".equals(String.valueOf(other.get("status")))
                && !Boolean.TRUE.equals(other.get("withdrawn"))
                && versionTime(other).isAfter(versionTime(job)));
    }

    private static Instant versionTime(Map<String, Object> job) {
        for (String field : List.of("submittedAt", "at")) {
            Object value = job.get(field);
            if (value == null) continue;
            try { return Instant.parse(String.valueOf(value)); }
            catch (java.time.format.DateTimeParseException ignored) { /* 旧台账可能缺少时间 */ }
        }
        return Instant.EPOCH;
    }

    /** 文本长度守卫：超过单次上限时给出可读提示，而不是笼统的"写入失败"。 */
    private static void requireContentLength(String content) {        int length = content == null ? 0 : content.codePointCount(0, content.length());
        if (length > MAX_CONTENT_CHARS) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "文本长度 " + length + " 字，超过单次入库上限 " + MAX_CONTENT_CHARS + " 字，请拆分后再导入");
        }
    }

    /** 切分进度回调（语义切分逐句取向量时用于让界面看到还在推进）。 */
    interface SplitProgress {
        void update(int done, int total);
    }

    /** 选择切分方式：语义切分需要逐句取向量；其余策略保持原有确定性切分。 */
    private List<String> splitChunks(KnowledgeIngestRequest request) {
        return splitChunks(request, (done, total) -> { });
    }

    private List<String> splitChunks(KnowledgeIngestRequest request, SplitProgress progress) {
        String content = request.content().trim();
        if ("按语义切分".equals(request.strategy())) {
            return semanticSplit(content, request.chunkSize(), request.collectionId(),
                    kbSemanticThreshold(request.collectionId()), progress);
        }
        return split(content, request.strategy(), request.chunkSize(), request.overlap());
    }

    /** 知识库采编参数里的语义相似度阈值（tf.kbConfig），未设置时用平台默认值。 */
    private double kbSemanticThreshold(String collectionId) {
        try {
            for (Map<String, Object> row : registry.list("tf.kbConfig")) {
                if (Boolean.TRUE.equals(row.get("deleted"))) continue;
                if (!collectionId.equals(String.valueOf(row.getOrDefault("collectionId", "")))) continue;
                Object raw = row.get("semanticThreshold");
                if (raw instanceof Number number && number.doubleValue() > 0.3 && number.doubleValue() < 0.999) {
                    return number.doubleValue();
                }
                break;
            }
        } catch (RuntimeException ignored) { /* 读配置失败按平台默认值处理 */ }
        return SEMANTIC_THRESHOLD;
    }

    /**
     * 按语义切分：先按句号/问号/分号/换行断句，取每句向量，相邻句余弦相似度低于阈值处断开，
     * 再按分块长度上限合并；超长单句仍按长度硬切（与按句切分一致）。
     * 相似度模型不可用时明确报错，不静默降级为按句切分。
     */
    List<String> semanticSplit(String content, int size, String collectionId, double threshold) {
        return semanticSplit(content, size, collectionId, threshold, (done, total) -> { });
    }

    List<String> semanticSplit(String content, int size, String collectionId, double threshold, SplitProgress progress) {
        List<String> sentences = new ArrayList<>();
        for (String raw : content.split("(?<=[。！？；\\n])")) {
            String sentence = raw.trim();
            if (!sentence.isEmpty()) sentences.add(sentence);
        }
        if (sentences.size() <= 1) return split(content, "按句切分", size, 0);
        List<List<Float>> vectors = embedSentences(sentences, collectionId, progress);
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentLength = 0;
        for (int index = 0; index < sentences.size(); index++) {
            String sentence = sentences.get(index);
            int sentenceLength = sentence.codePointCount(0, sentence.length());
            boolean boundary = currentLength > 0 && (currentLength + sentenceLength > size
                    || cosine(vectors.get(index - 1), vectors.get(index)) < threshold);
            if (boundary) {
                chunks.add(current.toString());
                current.setLength(0);
                currentLength = 0;
            }
            if (sentenceLength > size) {
                int[] points = sentence.codePoints().toArray();
                for (int start = 0; start < points.length; start += size) {
                    int end = Math.min(points.length, start + size);
                    chunks.add(new String(points, start, end - start));
                }
                continue;
            }
            current.append(sentence);
            currentLength += sentenceLength;
        }
        if (current.length() > 0) chunks.add(current.toString());
        return chunks;
    }

    /**
     * 逐句取向量：串行调用（讯飞 Embedding 并发会被限流返回 500），相同句子只算一次，
     * 单句失败重试一次；仍失败则明确报错，不静默降级。
     */
    private List<List<Float>> embedSentences(List<String> sentences, String collectionId, SplitProgress progress) {
        if (sentences.size() > SEMANTIC_MAX_SENTENCES) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "按语义切分最多处理 " + SEMANTIC_MAX_SENTENCES + " 句，当前 " + sentences.size()
                            + " 句，请改用按句切分或先拆分文档");
        }
        List<String> unique = new ArrayList<>(new LinkedHashSet<>(sentences));
        Map<String, List<Float>> computed = new LinkedHashMap<>();
        progress.update(0, unique.size());
        for (int index = 0; index < unique.size(); index++) {
            String sentence = unique.get(index);
            computed.put(sentence, embedWithRetry(collectionId, sentence));
            if ((index + 1) % 10 == 0 || index + 1 == unique.size()) progress.update(index + 1, unique.size());
        }
        List<List<Float>> result = new ArrayList<>(sentences.size());
        for (String sentence : sentences) result.add(computed.getOrDefault(sentence, List.of()));
        return result;
    }

    private List<Float> embedWithRetry(String collectionId, String sentence) {
        String key = collectionId + "\n" + sentence;
        List<Float> cached = sentenceVectors.get(key);
        if (cached != null) return cached;
        BizException lastFailure = null;
        long[] backoff = {400, 900, 1800};
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                List<Float> vector = withEmbeddingSlot(EMBED_MIN_INTERVAL_MS, () -> vectors.embedForCollection(collectionId, sentence));
                if (sentenceVectors.size() >= SENTENCE_CACHE_LIMIT) sentenceVectors.clear();
                sentenceVectors.put(key, vector);
                return vector;
            } catch (BizException failure) {
                lastFailure = failure;
                if (!transientVectorFailure(failure)) break;
                if (attempt < backoff.length) {
                    try { Thread.sleep(backoff[attempt]); } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        String detail = lastFailure == null || lastFailure.getMessage() == null ? "Embedding 模型不可用" : lastFailure.getMessage();
        throw new BizException(ErrorCode.DEPENDENCY_UNAVAILABLE, "语义切分需要调用 Embedding 模型：" + detail);
    }

    /** 余弦相似度；向量缺失或维度不一致视为不相似（在边界处断开，避免把无关内容并成一块）。 */
    private static double cosine(List<Float> left, List<Float> right) {
        if (left == null || right == null || left.isEmpty() || left.size() != right.size()) return 0;
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int index = 0; index < left.size(); index++) {
            double a = left.get(index);
            double b = right.get(index);
            dot += a * b;
            leftNorm += a * a;
            rightNorm += b * b;
        }
        if (leftNorm == 0 || rightNorm == 0) return 0;
        return dot / Math.sqrt(leftNorm * rightNorm);
    }

    static List<String> split(String content, String strategy, int size, int overlap) {
        if (content.isBlank()) throw new BizException(ErrorCode.PARAM_ERROR, "待入库内容为空");
        int[] points = content.codePoints().toArray();
        if ("不切分".equals(strategy)) {
            if (points.length > 500) throw new BizException(ErrorCode.PARAM_ERROR, "不切分内容最多 500 字，请选择切分策略");
            return List.of(content);
        }
        if ("按句切分".equals(strategy) || "按语义切分".equals(strategy)) {
            List<String> result = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            for (String sentence : content.split("(?<=[。！？；\\n])")) {
                int[] sentencePoints = sentence.codePoints().toArray();
                if (current.codePointCount(0, current.length()) + sentencePoints.length > size && !current.isEmpty()) {
                    result.add(current.toString());
                    current.setLength(0);
                }
                if (sentencePoints.length > size) {
                    for (int start = 0; start < sentencePoints.length; start += size) {
                        int end = Math.min(sentencePoints.length, start + size);
                        result.add(new String(sentencePoints, start, end - start));
                    }
                } else current.append(sentence);
            }
            if (!current.isEmpty()) result.add(current.toString());
            return result;
        }
        List<String> result = new ArrayList<>();
        for (int start = 0; start < points.length; start += size - overlap) {
            int end = Math.min(points.length, start + size);
            result.add(new String(points, start, end - start));
            if (end == points.length) break;
        }
        return result;
    }

    static String jobIdFor(KnowledgeIngestRequest request) {
        return "KB-" + fingerprintFor(request).substring(0, 20);
    }

    private static String fingerprintFor(KnowledgeIngestRequest request) {
        return digest(request.collectionId() + "\n" + request.fileId() + "\n" + request.content().trim()
                + "\n" + request.strategy() + "\n" + request.chunkSize() + "\n" + request.overlap());
    }

    private static String digest(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }
}
