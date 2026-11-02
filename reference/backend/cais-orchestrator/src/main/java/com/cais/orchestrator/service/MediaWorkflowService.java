package com.cais.orchestrator.service;

import com.cais.common.api.ErrorCode;
import com.cais.common.exception.BizException;
import com.cais.orchestrator.domain.MediaStepLabels;
import com.cais.orchestrator.domain.TaskCreateRequest;
import com.cais.orchestrator.domain.TaskRecord;
import com.cais.orchestrator.domain.TaskStep;
import com.cais.orchestrator.dto.TaskAcceptedView;
import com.cais.orchestrator.dto.TaskDetailView;
import com.cais.orchestrator.model.ModelInferenceClient;
import com.cais.orchestrator.store.TaskStore;
import com.cais.orchestrator.support.TaskLockRegistry;
import com.cais.common.statemachine.TaskStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Media workflow contract built on the persisted orchestrator task/step store.
 * Each source becomes an independent task; all 33 existing product pages stay intact. */
@Service
public class MediaWorkflowService {
    private static final Logger LOG = LoggerFactory.getLogger(MediaWorkflowService.class);
    private static final String VERSION = "media-v1";
    private static final Map<String, String> RULE_DATASETS = Map.of(
            "IMAGE_PREPROCESS", "tf.imagePreRule", "IMAGE_DETECT", "tf.imageDetectRule",
            "IMAGE_SCENE", "tf.imgCapTpl",
            "VIDEO_STANDARD", "tf.videoStdRule", "VIDEO_AUDIO", "tf.audioRule",
            "VIDEO_KEYFRAME", "tf.frameScheme", "VIDEO_EVENT", "tf.behRule",
            "VIDEO_SEMANTIC", "tf.semRule", "VIDEO_SUMMARY", "tf.sumRule");
    /** 前端自动勾选上游依赖；后端只认完整依赖链，避免下游节点单独运行。
     *  仅“事件分析 / 语义摘要 → 抽帧”：它们必须消费逐帧画面；预处理、标准化、抽帧可直接处理原始文件。 */
    private static final Map<String, List<String>> STEP_DEPS = Map.of(
            "VIDEO_EVENT", List.of("VIDEO_KEYFRAME"),
            "VIDEO_SEMANTIC", List.of("VIDEO_KEYFRAME"),
            // 内容总结不做硬依赖：只要启用了音轨转写、事件分析、语义解析之一即可，逐个校验见 resolvePlan。
            "VIDEO_SUMMARY", List.of());
    /** 语义解析规则的事件触发条件：前端只给固定取值，这里转成模型提示词用的中文口径。 */
    private static final Map<String, String> SEMANTIC_TRIGGERS = Map.of(
            "ANY", "任一目标出现", "CONSECUTIVE", "同类目标连续出现",
            "DWELL_5", "目标停留达到 5 秒", "DWELL_15", "目标停留达到 15 秒");
    /** 语义解析规则的因果判断逻辑：主语与宾语的时序关系，固定取值。 */
    private static final Map<String, String> SEMANTIC_CAUSAL = Map.of(
            "NONE", "不判断因果", "SEQUENCE", "主语先于宾语发生",
            "SIMULTANEOUS", "主语与宾语同时出现", "CAUSE", "主语导致宾语");
    /**
     * 可回收的加工产物文件字段白名单。
     * 只认这些由引擎登记过的产物键，绝不按 `*_file_id` 通配——`file_id` / `source_file_id`
     * 指向用户上传的原始素材，误删后无法恢复。
     */
    private static final Set<String> ARTIFACT_FILE_KEYS = Set.of(
            "artifact_file_id", "standard_file_id", "audio_file_id", "storage_file_id",
            "events_json_file_id", "events_csv_file_id", "summary_txt_file_id",
            "semantic_json_file_id", "summary_json_file_id", "transcript_txt_file_id",
            /* 兼容历史任务快照里的 `xxx_ref_file_id` 命名，仍只覆盖产物，不包含源素材。 */
            "artifact_ref_file_id", "standard_ref_file_id", "audio_ref_file_id", "storage_ref_file_id",
            "events_json_ref_file_id", "events_csv_ref_file_id", "summary_txt_ref_file_id",
            "semantic_json_ref_file_id", "summary_json_ref_file_id", "transcript_txt_ref_file_id");
    private final TaskOrchestrationService tasks;
    private final TaskStore store;
    private final ModelInferenceClient models;
    private final MediaPlanClient plans;
    private final Map<String, Object> taskLocks = new ConcurrentHashMap<>();

    /** 与编排回调、重试、删除共用同一把任务锁，避免复核写回覆盖并发推进。 */
    private TaskLockRegistry lockRegistry;

    @Autowired(required = false)
    public void setLockRegistry(TaskLockRegistry lockRegistry) {
        this.lockRegistry = lockRegistry;
    }

    private Object taskLock(String taskId) {
        return lockRegistry == null
                ? taskLocks.computeIfAbsent(taskId, ignored -> new Object())
                : lockRegistry.lock(taskId);
    }

    /** 事件落库实现（仅 dm8 模式装配）；非 dm8 或单测环境为 null，删除任务时跳过事件清理。 */
    @Autowired(required = false)
    private B2Persistence persistence;

    @Autowired
    public MediaWorkflowService(TaskOrchestrationService tasks, TaskStore store, ModelInferenceClient models,
                                MediaPlanClient plans) {
        this.tasks = tasks;
        this.store = store;
        this.models = models;
        this.plans = plans;
    }

    /** Existing tests and older internal callers continue to use the built-in plan. */
    public MediaWorkflowService(TaskOrchestrationService tasks, TaskStore store, ModelInferenceClient models) {
        this(tasks, store, models, id -> {
            throw new BizException(ErrorCode.DEPENDENCY_UNAVAILABLE, "流程方案服务未配置");
        });
    }

    /** 单测注入事件落库实现（生产由 Spring 按类型注入）。 */
    void usePersistence(B2Persistence value) {
        this.persistence = value;
    }

    public Map<String, Object> videoMetadata(String fileId) {
        String id = fileId == null ? "" : fileId.trim();
        if (id.isEmpty()) throw new BizException(ErrorCode.PARAM_ERROR, "缺少视频文件编号");
        Map<String, Object> metadata = models.videoMetadata(id);
        return metadata == null ? Map.of() : metadata;
    }

    public Map<String, Object> preflight(MediaRequest request) {
        Kind kind = validate(request);
        PlanSpec plan = resolvePlan(request, kind);
        if (!models.enabled()) throw new BizException(ErrorCode.DEPENDENCY_UNAVAILABLE, "模型适配服务未启用");
        Map<String, Map<String, Object>> bindings = new LinkedHashMap<>();
        for (Map<String, Object> model : models.runtimeModels()) {
            String capability = String.valueOf(model.getOrDefault("capability", "")).toUpperCase(Locale.ROOT);
            if (Boolean.TRUE.equals(model.get("configured"))) bindings.putIfAbsent(capability, model);
        }
        for (Map.Entry<String, RuleSnapshot> reference : plan.rules().entrySet()) {
            String capability = switch (reference.getKey()) {
                case "IMAGE_DETECT", "VIDEO_EVENT" -> "IMAGE_DETECT";
                case "IMAGE_SCENE", "VIDEO_SEMANTIC" -> "SEMANTIC";
                default -> "";
            };
            String requestedModel = reference.getValue().text("model", "");
            if (capability.isBlank() || requestedModel.isBlank()) continue;
            Map<String, Object> selected = models.runtimeModels().stream()
                    .filter(model -> Boolean.TRUE.equals(model.get("configured")))
                    .filter(model -> capability.equalsIgnoreCase(String.valueOf(model.get("capability"))))
                    .filter(model -> requestedModel.equals(String.valueOf(model.get("id")))
                            || requestedModel.equals(String.valueOf(model.get("model"))))
                    .findFirst().orElseThrow(() -> new BizException(ErrorCode.DEPENDENCY_UNAVAILABLE,
                            reference.getKey() + " 子规则绑定的模型已停用或不可用：" + requestedModel));
            bindings.put(capability, selected);
        }
        requireDetectorBinding(plan, bindings);
        Set<String> required = new LinkedHashSet<>();
        if (plan.steps().contains("IMAGE_DETECT") || plan.steps().contains("VIDEO_EVENT")
                || plan.steps().contains("VIDEO_SEMANTIC")) required.add("IMAGE_DETECT");
        if (plan.steps().contains("IMAGE_SCENE") || plan.steps().contains("VIDEO_SEMANTIC")
                || plan.steps().contains("VIDEO_SUMMARY")) required.add("SEMANTIC");
        // 抽帧的语义筛选（语义关注点 + 匹配阈值）也要调语义模型：未配置时应在预检就拦下，而不是执行到一半失败。
        RuleSnapshot frameRule = plan.rules().get("VIDEO_KEYFRAME");
        if (plan.steps().contains("VIDEO_KEYFRAME") && frameRule != null
                && frameRule.decimal("semanticThreshold", 0) > 0) required.add("SEMANTIC");
        for (String capability : required) {
            if (!bindings.containsKey(capability))
                throw new BizException(ErrorCode.DEPENDENCY_UNAVAILABLE, capability + " 未配置可用模型，不能启动" + kind.label + "流程");
        }
        List<Map<String, Object>> sources = new ArrayList<>();
        boolean audioRequired = false;
        for (String fileId : request.fileIds()) {
            Map<String, Object> source = models.sourceMetadata(fileId);
            String mime = String.valueOf(source.getOrDefault("mimeType", "")).toLowerCase(Locale.ROOT);
            String fileName = String.valueOf(source.getOrDefault("fileName", fileId));
            boolean typed = mime.startsWith(kind == Kind.IMAGE ? "image/" : "video/");
            boolean unknownMime = mime.isBlank() || "application/octet-stream".equals(mime);
            boolean knownExtension = kind == Kind.IMAGE
                    ? fileName.toLowerCase(Locale.ROOT).matches(".*\\.(?:jpe?g|png|bmp|tiff?|webp)")
                    : fileName.toLowerCase(Locale.ROOT).matches(".*\\.(?:mp4|mov|avi|mkv|webm)");
            if (!typed && !(unknownMime && knownExtension))
                throw new BizException(ErrorCode.PARAM_ERROR, fileId + " 的文件类型与" + kind.label + "流程不符");
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("fileId", fileId);
            detail.put("fileName", fileName);
            detail.put("mimeType", mime);
            if (kind == Kind.VIDEO) {
                boolean hasAudio = models.videoHasAudio(fileId);
                detail.put("hasAudio", hasAudio);
                audioRequired |= hasAudio;
            }
            sources.add(detail);
        }
        if (audioRequired && plan.steps().contains("VIDEO_AUDIO") && !bindings.containsKey("ASR"))
            throw new BizException(ErrorCode.DEPENDENCY_UNAVAILABLE, "视频包含音轨但 ASR 未配置可用模型");
        requireStepDependencies(plan);
        String batchId = batchId(request, kind);
        return Map.of("batchId", batchId, "workflowVersion", VERSION, "kind", kind.name(),
                "sources", sources, "bindings", bindings, "plan", plan.publicView(),
                "snapshotToken", snapshotToken(plan, bindings));
    }

    /**
     * 语义解析规则可选绑定「目标识别模型名称」：只能绑定平台当前生效的检测模型。
     * 绑定别的模型却按当前模型执行属于“配置看着生效、实际不生效”，因此这里直接拦下并给出下一步。
     */
    private static void requireDetectorBinding(PlanSpec plan, Map<String, Map<String, Object>> bindings) {
        RuleSnapshot rule = plan.rules().get("VIDEO_SEMANTIC");
        if (rule == null) return;
        String wanted = rule.text("detectorModel", "");
        if (wanted.isBlank()) return;
        resolveDetectorId(wanted, bindings);
    }

    /**
     * 规则里可能记录模型编号、模型标识或显示名称，统一下发实际生效的检测模型编号，
     * 否则执行阶段的模型快照校验会认为与提交时不一致。
     */
    private static String resolveDetectorId(String configured, Map<String, Map<String, Object>> bindings) {
        Map<String, Object> detect = bindings.get("IMAGE_DETECT");
        String currentId = detect == null ? "" : String.valueOf(detect.getOrDefault("id", ""));
        String currentModel = detect == null ? "" : String.valueOf(detect.getOrDefault("model", ""));
        String currentName = detect == null ? "" : String.valueOf(detect.getOrDefault("name", ""));
        if (configured.equals(currentId) || configured.equals(currentModel) || configured.equals(currentName))
            return currentId;
        throw new BizException(ErrorCode.PARAM_ERROR,
                "语义解析规则绑定的目标识别模型不是平台当前生效的检测模型，请在模型管理中切换生效模型后重新提交");
    }

    /**
     * 功能节点有明确上游依赖：下游节点不能脱离上游单独运行。
     * 前端会自动勾选依赖，这里只做服务端兜底（接口直调也拦得住）。
     */
    private static void requireStepDependencies(PlanSpec plan) {
        for (String step : plan.steps()) {
            List<String> missing = STEP_DEPS.getOrDefault(step, List.of()).stream()
                    .filter(dep -> !plan.steps().contains(dep)).toList();
            if (missing.isEmpty()) continue;
            String names = missing.stream().map(MediaStepLabels::label)
                    .collect(java.util.stream.Collectors.joining("、"));
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "「" + MediaStepLabels.label(step) + "」依赖「" + names
                            + "」，不能单独运行；请同时选择依赖节点");
        }
    }

    public Map<String, Object> submit(MediaRequest request) {
        Kind requestedKind = validate(request);
        String replayBatchId = batchId(request, requestedKind);
        Map<String, TaskRecord> existing = new LinkedHashMap<>();
        for (TaskRecord task : store.findAll()) {
            Object extra = task.getInput() == null ? null : task.getInput().get("extra");
            if (extra instanceof Map<?, ?> map && replayBatchId.equals(map.get("batchId"))) {
                existing.put(String.valueOf(task.getInput().get("fileId")), task);
            }
        }
        if (existing.keySet().containsAll(request.fileIds())) {
            List<Map<String, Object>> repeated = request.fileIds().stream().map(fileId -> Map.<String, Object>of(
                    "fileId", fileId, "accepted", true, "taskId", existing.get(fileId).getTaskId(),
                    "repeated", true)).toList();
            return Map.of("batchId", replayBatchId, "workflowVersion", VERSION, "items", repeated);
        }
        List<String> missing = request.fileIds().stream().filter(id -> !existing.containsKey(id)).toList();
        Map<String, Object> checked = preflight(existing.isEmpty() ? request
                : new MediaRequest(request.kind(), missing, request.requestId(), request.planId(), request.snapshotToken(), request.steps(), request.rules(), request.note(), request.output()));
        if (request.snapshotToken() != null && !request.snapshotToken().isBlank()
                && !request.snapshotToken().equals(checked.get("snapshotToken")))
            throw new BizException(ErrorCode.TASK_STATE_CONFLICT,
                    "预检后方案、子规则或模型绑定发生变化，请重新预检后提交");
        String batchId = replayBatchId;
        @SuppressWarnings("unchecked") Map<String, Map<String, Object>> bindings =
                (Map<String, Map<String, Object>>) checked.get("bindings");
        Kind kind = Kind.valueOf(String.valueOf(checked.get("kind")));
        PlanSpec plan = resolvePlan(request, kind);
        /* 预检已取到原文件名，直接写进任务输入：产物命名要用它，避免执行期再查一次文件服务。 */
        Map<String, String> sourceNames = sourceFileNames(checked.get("sources"));
        if (request.snapshotToken() != null && !request.snapshotToken().isBlank()
                && !request.snapshotToken().equals(snapshotToken(plan, bindings)))
            throw new BizException(ErrorCode.TASK_STATE_CONFLICT, "提交时子规则发生变化，请重新预检");
        if (!existing.isEmpty()) {
            TaskRecord acceptedBefore = existing.values().iterator().next();
            String earlierFileId = String.valueOf(acceptedBefore.getInput().get("fileId"));
            TaskCreateRequest proposed = taskRequest(kind, earlierFileId, batchId, request.requestId(), bindings, plan,
                    request.note(), request.output(), sourceNames.getOrDefault(earlierFileId, ""),
                    taskNameFor(request.taskName(), request.fileIds(), earlierFileId));
            if (!samePlan(acceptedBefore, proposed))
                throw new BizException(ErrorCode.TASK_STATE_CONFLICT,
                        "本批次已部分受理，但模型或流程配置已变更；不能混用配置续交，请先检查原批次");
        }
        List<Map<String, Object>> accepted = new ArrayList<>();
        for (String fileId : request.fileIds()) {
            TaskRecord earlier = existing.get(fileId);
            if (earlier != null) {
                accepted.add(Map.of("fileId", fileId, "accepted", true,
                        "taskId", earlier.getTaskId(), "repeated", true));
                continue;
            }
            try {
                String taskName = taskNameFor(request.taskName(), request.fileIds(), fileId);
                TaskAcceptedView task = tasks.createTask(taskRequest(kind, fileId, batchId, request.requestId(), bindings,
                        plan, request.note(), request.output(), sourceNames.getOrDefault(fileId, ""), taskName));
                accepted.add(Map.of("fileId", fileId, "accepted", true, "taskId", task.getTaskId(),
                        "repeated", task.isRepeated(), "taskName", taskName));
            } catch (RuntimeException failure) {
                accepted.add(Map.of("fileId", fileId, "accepted", false,
                        "error", failure instanceof BizException ? failure.getMessage() : "任务受理失败，请查看服务日志"));
            }
        }
        return Map.of("batchId", batchId, "workflowVersion", VERSION, "items", accepted);
    }

    private static boolean samePlan(TaskRecord existing, TaskCreateRequest proposed) {
        if (existing.getSteps() == null || existing.getSteps().size() != proposed.getSteps().size()) return false;
        for (int index = 0; index < existing.getSteps().size(); index++) {
            TaskStep old = existing.getSteps().get(index);
            TaskCreateRequest.Step next = proposed.getSteps().get(index);
            if (!old.getStepType().equals(next.getStepType())
                    || !old.getEngineType().equals(next.getEngineType())
                    || !old.getParams().equals(next.getParams())) return false;
        }
        return true;
    }

    public Map<String, Object> batch(String batchId) { return batch(batchId, false); }

    private Map<String, Object> batch(String batchId, boolean archived) {
        if (batchId == null || !batchId.matches("MB-[0-9a-f]{24}"))
            throw new BizException(ErrorCode.PARAM_ERROR, "批次编号不合法");
        List<Map<String, Object>> items = store.findAll().stream()
                .filter(task -> (task.getArchivedAt() != null) == archived)
                .filter(task -> batchId.equals(batchIdOf(task)))
                .sorted(BATCH_ITEM_ORDER).map(MediaWorkflowService::batchItem).toList();
        return Map.of("batchId", batchId, "total", items.size(), "items", items);
    }

    /** Persisted batch discovery lets a user return after closing the browser. */
    public List<Map<String, Object>> batches() { return batches(false); }

    /**
     * 单次扫描任务表按 batchId 归并。原实现先找出所有批次、再对每个批次调用 {@link #batch}，
     * 而 batch 内部又完整 store.findAll() 一次，批次多时为 O(批次数×任务数)，列表接口会延迟数秒。
     */
    public List<Map<String, Object>> batches(boolean archived) {
        Map<String, List<TaskRecord>> grouped = new LinkedHashMap<>();
        store.findAll().stream()
                .filter(task -> "IMAGE_PIPELINE".equals(task.getBizType()) || "VIDEO_PIPELINE".equals(task.getBizType()))
                .filter(task -> (task.getArchivedAt() != null) == archived)
                .sorted(BATCH_ITEM_ORDER)
                .forEach(task -> {
                    String batchId = batchIdOf(task);
                    if (batchId != null) grouped.computeIfAbsent(batchId, ignored -> new ArrayList<>()).add(task);
                });
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, List<TaskRecord>> entry : grouped.entrySet()) {
            if (result.size() >= 100) break;
            List<Map<String, Object>> items = entry.getValue().stream().map(MediaWorkflowService::batchItem).toList();
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("batchId", entry.getKey());
            view.put("total", items.size());
            view.put("items", items);
            view.put("kind", "IMAGE_PIPELINE".equals(entry.getValue().get(0).getBizType()) ? "IMAGE" : "VIDEO");
            result.add(view);
        }
        return result;
    }

    private static final Comparator<TaskRecord> BATCH_ITEM_ORDER = Comparator
            .comparing(TaskRecord::getCreateTime, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(TaskRecord::getTaskId);

    private static String batchIdOf(TaskRecord task) {
        Object extra = task.getInput() == null ? null : task.getInput().get("extra");
        return extra instanceof Map<?, ?> map && map.get("batchId") instanceof String batchId ? batchId : null;
    }

    private static Map<String, Object> batchItem(TaskRecord task) {
        TaskStep step = task.getSteps().get(task.getCurrentStepIndex());
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("taskId", task.getTaskId());
        item.put("fileId", task.getInput().get("fileId"));
        Object extra = task.getInput().get("extra");
        if (extra instanceof Map<?, ?> values) {
            Object sourceName = values.get("sourceFileName");
            Object taskName = values.get("taskName");
            item.put("fileName", sourceName instanceof String name ? name : "");
            item.put("taskName", taskName instanceof String name ? name : "");
        }
        item.put("kind", "IMAGE_PIPELINE".equals(task.getBizType()) ? "IMAGE" : "VIDEO");
        item.put("status", task.getStatus());
        item.put("currentStep", step.getStepType());
        item.put("failReason", task.getFailReason());
        item.put("createdAt", task.getCreateTime());
        item.put("stepStartedAt", step.getStartedAt());
        item.put("finishedAt", task.getFinishedAt());
        item.put("archivedAt", task.getArchivedAt());
        item.put("archivedBy", task.getArchivedBy());
        return item;
    }

    /** Archive only terminal media tasks; the source and knowledge vectors are not removed. */
    public Map<String, Object> archive(String taskId, String operator) {
        requireOperator(operator);
        synchronized (taskLock(taskId)) {
            TaskRecord task = requireMediaTask(taskId);
            if (task.getArchivedAt() != null) return archiveView(task);
            if (task.getStatus() != TaskStatus.SUCCESS && task.getStatus() != TaskStatus.FAILED_TERMINAL)
                throw new BizException(ErrorCode.TASK_STATE_CONFLICT, "任务仍在运行或等待重试，请先取消处理");
            task.setArchivedAt(Instant.now());
            task.setArchivedBy(operator);
            store.save(task);
            return archiveView(task);
        }
    }

    public Map<String, Object> restore(String taskId, String operator) {        requireOperator(operator);
        synchronized (taskLock(taskId)) {
            TaskRecord task = requireMediaTask(taskId);
            task.setArchivedAt(null);
            task.setArchivedBy(null);
            store.save(task);
            return Map.of("taskId", taskId, "archived", false);
        }
    }

    /**
     * 彻底删除任务（自动加工列表的「删除」）：只允许终态任务；删除后任务与其步骤不再可查、不可恢复。
     * 默认不处理对象存储产物（兼容旧行为）；调用方传 deleteArtifacts=true 时按输出引用逻辑删除已登记的产物文件。
     */
    public Map<String, Object> deleteTask(String taskId, String operator) {
        return deleteTask(taskId, operator, false);
    }

    public Map<String, Object> deleteTask(String taskId, String operator, boolean deleteArtifacts) {
        requireOperator(operator);
        synchronized (taskLock(taskId)) {
            TaskRecord task = requireMediaTask(taskId);
            if (task.getStatus() != TaskStatus.SUCCESS && task.getStatus() != TaskStatus.FAILED_TERMINAL)
                throw new BizException(ErrorCode.TASK_STATE_CONFLICT, "任务仍在运行或等待重试，请先取消处理再删除");
            Map<String, Object> artifactResult = deleteArtifacts
                    ? deleteArtifacts(task, operator)
                    : Map.of("artifactsTotal", 0, "artifactsDeleted", 0, "artifactsFailed", 0);
            if (!store.delete(taskId))
                throw new BizException(ErrorCode.TASK_NOT_FOUND, "任务不存在或已删除：" + taskId);
            int eventsDeleted = 0;
            try {
                if (persistence != null) eventsDeleted = Math.max(0, persistence.deleteVideoEvents(taskId));
            } catch (RuntimeException failure) {
                LOG.warn("删除任务事件行失败（任务已删除，事件检索可能残留）：{}", failure.toString());
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("taskId", taskId);
            result.put("deleted", true);
            result.put("eventsDeleted", eventsDeleted);
            result.putAll(artifactResult);
            return result;
        }
    }

    /** 删除某个任务已登记的产物文件（逻辑删除），任务记录保留；供保留期清理与手动清理复用。 */
    public Map<String, Object> deleteArtifacts(String taskId, String operator) {
        requireOperator(operator);
        synchronized (taskLock(taskId)) {
            return deleteArtifacts(requireMediaTask(taskId), operator);
        }
    }

    private Map<String, Object> deleteArtifacts(TaskRecord task, String operator) {
        List<String> artifactFileIds = artifactFileIds(task);
        int deletedArtifacts = 0;
        int failedArtifacts = 0;
        for (String fileId : artifactFileIds) {
            if (models.deleteRegisteredArtifact(fileId, operator)) deletedArtifacts++;
            else failedArtifacts++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", task.getTaskId());
        result.put("artifactsTotal", artifactFileIds.size());
        result.put("artifactsDeleted", deletedArtifacts);
        result.put("artifactsFailed", failedArtifacts);
        return result;
    }

    /** 收集任务所有节点已登记到数据空间的产物文件 id（artifact/standard/audio/storage 等 *_file_id）。 */
    public static List<String> artifactFileIds(TaskRecord task) {
        Set<String> ids = new LinkedHashSet<>();
        if (task.getSteps() != null) {
            for (TaskStep step : task.getSteps()) {
                collectFileIds(step.getOutputRef(), ids, 0);
            }
        }
        return new ArrayList<>(ids);
    }

    private static void collectFileIds(Object value, Set<String> ids, int depth) {
        if (value == null || depth > 8) return;
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object item = entry.getValue();
                if (ARTIFACT_FILE_KEYS.contains(key) && item instanceof String fileId && !fileId.isBlank()) {
                    ids.add(fileId);
                } else if (item instanceof Map<?, ?> || item instanceof List<?>) {
                    collectFileIds(item, ids, depth + 1);
                }
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) collectFileIds(item, ids, depth + 1);
        }
    }

    public List<Map<String, Object>> archiveBatch(List<String> taskIds, String operator) {
        requireOperator(operator);
        if (taskIds == null || taskIds.isEmpty() || taskIds.size() > 100
                || taskIds.stream().anyMatch(id -> id == null || id.isBlank())
                || new LinkedHashSet<>(taskIds).size() != taskIds.size())
            throw new BizException(ErrorCode.PARAM_ERROR, "请选择 1 至 100 条不重复的任务记录");
        List<Map<String, Object>> results = new ArrayList<>();
        for (String id : taskIds) {
            try { results.add(archive(id, operator)); }
            catch (BizException failure) {
                results.add(Map.of("taskId", id, "archived", false, "error", failure.getMessage()));
            }
        }
        return results;
    }

    private static Map<String, Object> archiveView(TaskRecord task) {
        return Map.of("taskId", task.getTaskId(), "archived", true,
                "archivedAt", task.getArchivedAt().toString(), "knowledgeVectorsUnchanged", true);
    }

    private static void requireOperator(String operator) {
        if (operator == null || !operator.matches("[A-Za-z0-9._@-]{2,128}"))
            throw new BizException(ErrorCode.FORBIDDEN, "缺少经网关验证的操作人身份");
    }

    public TaskDetailView task(String taskId) {
        requireMediaTask(taskId);
        return tasks.getTask(taskId);
    }

    /** One authoritative step feed powers both the automatic-task detail and existing capability pages. */
    public List<Map<String, Object>> stepRecords() {
        return stepRecords(null, null);
    }

    /**
     * 步骤记录（可选按节点类型或文件过滤）。
     *
     * <p>全量返回带着每步的 params / outputRef（事件、语义数组都在里面），任务攒多后单次可达 MB 级；
     * 列表页只要某一类节点、标注页只要某个文件，按需过滤能把响应和解析量都降下来。</p>
     */
    public List<Map<String, Object>> stepRecords(String stepType, String fileId) {
        String wantedStep = stepType == null ? "" : stepType.trim();
        String wantedFile = fileId == null ? "" : fileId.trim();
        List<Map<String, Object>> records = new ArrayList<>();
        for (TaskRecord task : store.findAll()) {
            if (!List.of("IMAGE_PIPELINE", "VIDEO_PIPELINE").contains(task.getBizType())
                    || task.getSteps() == null || task.getArchivedAt() != null) continue;
            Object taskFile = task.getInput() == null ? null : task.getInput().get("fileId");
            if (!wantedFile.isEmpty() && !wantedFile.equals(String.valueOf(taskFile))) continue;
            Object extra = task.getInput() == null ? null : task.getInput().get("extra");
            Object plan = extra instanceof Map<?, ?> values ? values.get("planSnapshot") : null;
            for (TaskStep step : task.getSteps()) {
                if (!wantedStep.isEmpty() && !wantedStep.equals(step.getStepType())) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", task.getTaskId() + "-" + step.getStepIndex());
                row.put("taskId", task.getTaskId());
                row.put("taskStatus", task.getStatus() == null ? "PENDING" : task.getStatus().name());
                row.put("fileId", task.getInput().get("fileId"));
                row.put("stepType", step.getStepType());
                row.put("stepIndex", step.getStepIndex());
                row.put("status", step.getStatus() == null ? "PENDING" : step.getStatus().name());
                row.put("createdAt", task.getCreateTime());
                /* 步骤开始/结束都要给：执行方案要按真实起止算耗时，不能只给任务创建时间。 */
                row.put("startedAt", step.getStartedAt());
                row.put("finishedAt", step.getFinishedAt());
                row.put("failReason", step.getFailReason());
                row.put("params", step.getParams());
                row.put("outputRef", step.getOutputRef());
                row.put("plan", plan);
                row.put("origin", "自动流程");
                records.add(row);
            }
        }
        records.sort(Comparator.comparing((Map<String, Object> row) ->
                (Instant) row.get("createdAt"), Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(row -> String.valueOf(row.get("id")), Comparator.reverseOrder()));
        return records;
    }

    /** Frame references are paged independently, never one row per frame in a batch list. */
    public Map<String, Object> frames(String taskId, int pageNum, int pageSize) {
        TaskRecord task = requireMediaTask(taskId);
        if (pageNum < 1 || pageSize < 1 || pageSize > 100)
            throw new BizException(ErrorCode.PARAM_ERROR, "帧图分页参数不合法");
        List<Map<String, Object>> all = new ArrayList<>();
        for (TaskStep step : task.getSteps()) {
            if (!"VIDEO_KEYFRAME".equals(step.getStepType()) || step.getOutputRef() == null) continue;
            Object rawOutputs = step.getOutputRef().get("outputs");
            if (!(rawOutputs instanceof List<?> outputs)) continue;
            for (Object output : outputs) {
                if (!(output instanceof Map<?, ?> item) || !(item.get("keyframes") instanceof List<?> frames)) continue;
                for (Object frame : frames) if (frame instanceof Map<?, ?> row) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    row.forEach((key, value) -> copy.put(String.valueOf(key), value));
                    all.add(copy);
                }
            }
        }
        int from = (int) Math.min((long) (pageNum - 1) * pageSize, all.size());
        int to = Math.min(from + pageSize, all.size());
        return Map.of("taskId", taskId, "total", all.size(), "pageNum", pageNum,
                "pageSize", pageSize, "items", all.subList(from, to));
    }

    /**
     * 复杂场景语义解析的事件链查询：把每个视频任务的语义步骤摊平成可筛选、可导出的三元组。
     * 只读取已落库的真实产物，不重新分析、不补造事件。
     */
    public Map<String, Object> semanticEvents(String keyword, String from, String to, int limit) {
        String text = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        int cap = Math.max(1, Math.min(limit <= 0 ? 200 : limit, 1000));
        Instant begin = parseInstant(from);
        Instant end = parseInstant(to);
        List<Map<String, Object>> matched = new ArrayList<>();
        Set<String> tasks = new LinkedHashSet<>();
        for (TaskRecord task : store.findAll()) {
            if (!"VIDEO_PIPELINE".equals(task.getBizType()) || task.getSteps() == null) continue;
            for (TaskStep step : task.getSteps()) {
                if (!"VIDEO_SEMANTIC".equals(step.getStepType()) || step.getOutputRef() == null) continue;
                Object rawOutputs = step.getOutputRef().get("outputs");
                if (!(rawOutputs instanceof List<?> outputs) || outputs.isEmpty()) continue;
                if (!(outputs.get(0) instanceof Map<?, ?> first)) continue;
                if (!(first.get("event_chain") instanceof List<?> chain)) continue;
                Instant at = step.getFinishedAt() != null ? step.getFinishedAt() : task.getCreateTime();
                if (begin != null && (at == null || at.isBefore(begin))) continue;
                if (end != null && (at == null || at.isAfter(end))) continue;
                Map<String, Object> params = step.getParams() == null ? Map.of() : step.getParams();
                for (Object raw : chain) {
                    if (!(raw instanceof Map<?, ?> row)) continue;
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("taskId", task.getTaskId());
                    item.put("fileId", task.getInput() == null ? "" : task.getInput().get("fileId"));
                    item.put("ruleId", params.getOrDefault("ruleId", ""));
                    item.put("ruleVersion", params.getOrDefault("ruleVersion", ""));
                    item.put("seq", row.get("seq"));
                    item.put("startMs", row.get("start_ms"));
                    item.put("endMs", row.get("end_ms"));
                    item.put("subjectType", row.get("subject_type"));
                    item.put("subjectId", row.get("subject_id"));
                    item.put("subject", row.get("subject"));
                    item.put("predicate", row.get("predicate"));
                    item.put("objectType", row.get("object_type"));
                    item.put("objectId", row.get("object_id"));
                    item.put("object", row.get("object"));
                    item.put("chainTag", row.get("chain_tag"));
                    item.put("source", row.get("source"));
                    item.put("cause", row.get("cause"));
                    item.put("finishedAt", at);
                    if (!text.isEmpty()) {
                        String haystack = String.join(" ",
                                String.valueOf(row.get("subject")), String.valueOf(row.get("predicate")),
                                String.valueOf(row.get("object")), String.valueOf(row.get("chain_tag")),
                                task.getTaskId(), String.valueOf(item.get("fileId"))).toLowerCase(Locale.ROOT);
                        if (!haystack.contains(text)) continue;
                    }
                    matched.add(item);
                    tasks.add(task.getTaskId());
                }
            }
        }
        matched.sort(Comparator.comparing((Map<String, Object> row) -> (Instant) row.get("finishedAt"),
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(row -> String.valueOf(row.get("taskId")), Comparator.reverseOrder())
                .thenComparing(row -> Integer.parseInt(String.valueOf(row.getOrDefault("seq", 0)))));
        List<Map<String, Object>> items = matched.size() > cap ? matched.subList(0, cap) : matched;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", matched.size());
        result.put("returned", items.size());
        result.put("matchedTasks", tasks.size());
        result.put("truncated", matched.size() > cap);
        result.put("limit", cap);
        result.put("note", matched.size() > cap
                ? "只返回最近 " + cap + " 条语义三元组，请缩小时间范围或补充关键词" : "");
        return result;
    }

    /** 时间过滤参数：支持 ISO 时间戳、yyyy-MM-dd 与 yyyy-MM-ddTHH:mm:ss；非法值直接报错，不静默当成不过滤。 */
    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        String text = value.trim();
        String normalized = text.replace(' ', 'T');
        try {
            return Instant.parse(normalized);
        } catch (java.time.format.DateTimeParseException ignored) {
            try {
                return java.time.LocalDateTime.parse(normalized)
                        .atZone(java.time.ZoneId.systemDefault()).toInstant();
            } catch (java.time.format.DateTimeParseException alsoIgnored) {
                try {
                    return java.time.LocalDate.parse(text)
                            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant();
                } catch (java.time.format.DateTimeParseException failure) {
                    throw new BizException(ErrorCode.PARAM_ERROR, "时间参数不是合法时间：" + text);
                }
            }
        }
    }

    private TaskRecord requireMediaTask(String taskId) {
        TaskRecord task = store.findById(taskId)
                .orElseThrow(() -> new BizException(ErrorCode.TASK_NOT_FOUND, "媒体任务不存在：" + taskId));
        if (!List.of("IMAGE_PIPELINE", "VIDEO_PIPELINE").contains(task.getBizType()))
            throw new BizException(ErrorCode.PARAM_ERROR, "仅图片和视频任务可使用本入口");
        return task;
    }

    /**
     * 复核并修改生成结果（场景描述 / 语义摘要）：写回任务步骤产物并落库，重新打开即为修改后的内容。
     */
    public Map<String, Object> reviewStepOutput(String taskId, String stepType, Map<String, Object> body) {
        String type = stepType == null ? "" : stepType.trim().toUpperCase(Locale.ROOT);
        synchronized (taskLock(taskId)) {
            return reviewStepOutputLocked(taskId, type, body);
        }
    }

    private Map<String, Object> reviewStepOutputLocked(String taskId, String type, Map<String, Object> body) {
        TaskRecord task = store.findById(taskId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "任务不存在：" + taskId));
        TaskStep target = task.getSteps().stream()
                .filter(step -> type.equals(String.valueOf(step.getStepType())))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "该任务没有加工节点：" + type));
        if (target.getStatus() != TaskStatus.SUCCESS)
            throw new BizException(ErrorCode.TASK_STATE_CONFLICT, "该节点尚未成功完成，暂不能复核");
        Map<String, Object> outputRef = target.getOutputRef();
        if (outputRef == null || !(outputRef.get("outputs") instanceof List<?> rawOutputs) || rawOutputs.isEmpty())
            throw new BizException(ErrorCode.TASK_STATE_CONFLICT, "该节点尚无加工结果，无法复核");
        String summary = body.get("summary") == null ? "" : String.valueOf(body.get("summary")).trim();
        boolean textNode = "IMAGE_SCENE".equals(type) || "VIDEO_SEMANTIC".equals(type)
                || "VIDEO_AUDIO".equals(type) || "VIDEO_SUMMARY".equals(type);
        if (textNode && summary.isEmpty() && !"VIDEO_AUDIO".equals(type))
            throw new BizException(ErrorCode.PARAM_ERROR, "复核内容不能为空");
        List<Object> outputs = new ArrayList<>(rawOutputs);
        Map<String, Object> first = new LinkedHashMap<>();
        if (outputs.get(0) instanceof Map<?, ?> map) map.forEach((key, value) -> first.put(String.valueOf(key), value));
        if (textNode) {
            // 内容总结按版本保存：编辑前的内容先归档，再写入新版本，历史版本可追溯。
            if ("VIDEO_SUMMARY".equals(type)) {
                int summaryVersion = first.get("summaryVersion") instanceof Number number ? number.intValue() : 1;
                List<Object> versions = new ArrayList<>();
                if (first.get("summaryVersions") instanceof List<?> priorVersions) versions.addAll(priorVersions);
                Object previous = first.get("summary");
                if (previous instanceof String previousText && !previousText.isBlank()) {
                    Map<String, Object> archived = new LinkedHashMap<>();
                    archived.put("version", Math.max(1, summaryVersion));
                    archived.put("summary", previousText);
                    archived.put("operator", String.valueOf(first.getOrDefault("reviewedBy", "")));
                    archived.put("at", String.valueOf(first.getOrDefault("reviewedAt", "")));
                    archived.put("txtFileId", String.valueOf(first.getOrDefault("summary_txt_file_id", "")));
                    versions.add(archived);
                }
                first.put("summaryVersions", versions);
                first.put("summaryVersion", Math.max(1, summaryVersion) + 1);
            }
            first.put("summary", summary);
            // 复核后的内容单独存成 txt 并登记，形成「更新版本」的真实文件；失败不阻断复核记录。
            if ("VIDEO_SUMMARY".equals(type)) {
                try {
                    String reference = models.storeTextArtifact(summary);
                    if (reference != null && !reference.isBlank()) {
                        String fileId = models.registerArtifact(reference, taskId, first);
                        if (fileId != null && !fileId.isBlank()) {
                            first.put("summary_txt_ref", reference);
                            first.put("summary_txt_file_id", fileId);
                            first.remove("summaryTxtError");
                        }
                    }
                } catch (RuntimeException failure) {
                    first.put("summaryTxtError", failure.getMessage() == null ? "复核版本文件保存失败" : failure.getMessage());
                }
            }
            // 语义标签的结构化信息：名称、分类、描述。描述完整保存（至少支持 20 个汉字），
            // tags 仍保留为名称数组，兼容既有检索与展示逻辑。
            List<Map<String, Object>> tagDetails = new ArrayList<>();
            if (body.get("tagDetails") instanceof List<?> rawDetails) {
                for (Object rawDetail : rawDetails) {
                    if (!(rawDetail instanceof Map<?, ?> source)) continue;
                    String name = source.get("name") == null ? "" : String.valueOf(source.get("name")).trim();
                    if (name.isEmpty()) continue;
                    if (tagDetails.stream().anyMatch(tag -> name.equals(tag.get("name")))) continue;
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("name", name);
                    detail.put("category", source.get("category") == null ? "" : String.valueOf(source.get("category")).trim());
                    detail.put("description", source.get("description") == null ? "" : String.valueOf(source.get("description")).trim());
                    tagDetails.add(detail);
                }
            }
            List<String> tags = new ArrayList<>();
            if (!tagDetails.isEmpty()) {
                tagDetails.forEach(detail -> tags.add(String.valueOf(detail.get("name"))));
            } else if (body.get("tags") instanceof List<?> rawTags) {
                for (Object rawTag : rawTags) {
                    String value = rawTag == null ? "" : String.valueOf(rawTag).trim();
                    if (!value.isEmpty() && !tags.contains(value)) tags.add(value);
                }
            }
            if (!tagDetails.isEmpty()) first.put("tagDetails", tagDetails);
            else first.remove("tagDetails");
            first.put("tags", tags);
        }
        if ("VIDEO_AUDIO".equals(type)) {
            Object rawTracks = first.get("tracks");
            if (rawTracks instanceof List<?> tracks && !tracks.isEmpty() && tracks.get(0) instanceof Map<?, ?> rawTrack) {
                Map<String, Object> track = new LinkedHashMap<>();
                rawTrack.forEach((key, value) -> track.put(String.valueOf(key), value));
                Map<String, Object> transcript = new LinkedHashMap<>();
                if (track.get("transcript") instanceof Map<?, ?> rawTranscript)
                    rawTranscript.forEach((key, value) -> transcript.put(String.valueOf(key), value));
                String transcriptText = body.get("transcriptText") == null ? summary : String.valueOf(body.get("transcriptText")).trim();
                transcript.put("text", transcriptText);
                transcript.put("reviewedBy", String.valueOf(body.getOrDefault("operator", "CURRENT_USER")));
                transcript.put("reviewedAt", Instant.now().toString());
                track.put("transcript", transcript);
                /* 音轨文件名 / 存储地址 / 转写文本名称：空值表示用户删除该字段，不删除物理对象。 */
                if (body.containsKey("audioName")) {
                    String name = body.get("audioName") == null ? "" : String.valueOf(body.get("audioName")).trim();
                    if (name.isEmpty()) track.remove("audio_name"); else track.put("audio_name", name);
                }
                if (body.containsKey("audioRef")) {
                    String ref = body.get("audioRef") == null ? "" : String.valueOf(body.get("audioRef")).trim();
                    if (ref.isEmpty()) track.remove("audio_ref"); else track.put("audio_ref", ref);
                }
                if (body.containsKey("transcriptName")) {
                    String name = body.get("transcriptName") == null ? "" : String.valueOf(body.get("transcriptName")).trim();
                    if (name.isEmpty()) track.remove("transcript_name"); else track.put("transcript_name", name);
                }
                List<Object> updatedTracks = new ArrayList<>(tracks);
                updatedTracks.set(0, track);
                first.put("tracks", updatedTracks);
            }
        }
        if ("IMAGE_DETECT".equals(type) && body.get("detections") instanceof List<?> rawDetections) {
            List<Map<String, Object>> detections = new ArrayList<>();
            for (Object rawDetection : rawDetections) {
                if (!(rawDetection instanceof Map<?, ?> source)) continue;
                String label = source.get("label") == null ? "" : String.valueOf(source.get("label")).trim();
                if (label.isEmpty()) continue;
                Map<String, Object> detection = new LinkedHashMap<>();
                detection.put("label", label);
                Object confidence = source.get("confidence");
                if (confidence instanceof Number number) detection.put("confidence", number.doubleValue());
                Object box = source.get("box");
                if (box instanceof List<?>) detection.put("box", box);
                Map<String, Object> attributes = attributeMap(source.get("attributes"));
                if (!attributes.isEmpty()) {
                    detection.put("attributes", attributes);
                    detection.put("attribute_tags", attributeTags(label, attributes));
                }
                detections.add(detection);
            }
            first.put("detections", detections);
            // 复核只改「保留下来的目标列表」，不重写模型的检出数与规则过滤数。
            // 原实现把 filtered_count 固定写成 0，复核后任务会显示「没有目标被规则过滤」，属伪造统计。
            first.putIfAbsent("detected_count", detections.size());
            first.putIfAbsent("filtered_count", 0);
        }
        int reviewVersion = 1;
        if (first.get("reviewVersion") instanceof Number number) reviewVersion = number.intValue() + 1;
        first.put("reviewVersion", reviewVersion);
        first.put("reviewStatus", "REVIEWED");
        first.put("reviewedBy", String.valueOf(body.getOrDefault("operator", "CURRENT_USER")));
        first.put("reviewedAt", Instant.now().toString());
        outputs.set(0, first);
        outputRef.put("outputs", outputs);
        target.setOutputRef(outputRef);
        store.save(task);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId);
        result.put("stepType", type);
        result.put("outputs", outputs);
        return result;
    }

    /** 按源文件（视频/图片）聚合最近一次语义摘要与音轨转写，供“源视频总结与标签”使用。 */
    public Map<String, Object> fileDigest(String fileId) {
        String target = fileId == null ? "" : fileId.trim();
        if (target.isEmpty()) throw new BizException(ErrorCode.PARAM_ERROR, "缺少源文件标识");
        String summary = "";
        String transcript = "";
        String semanticAt = "";
        int taskCount = 0;
        List<String> tags = new ArrayList<>();
        for (TaskRecord task : store.findAll()) {
            Map<String, Object> input = task.getInput();
            if (input == null || !target.equals(String.valueOf(input.get("fileId")))) continue;
            taskCount++;
            for (TaskStep step : task.getSteps()) {
                String type = String.valueOf(step.getStepType());
                Map<String, Object> outputRef = step.getOutputRef();
                if (outputRef == null || !(outputRef.get("outputs") instanceof List<?> outputs) || outputs.isEmpty()) continue;
                if (!(outputs.get(0) instanceof Map<?, ?> first)) continue;
                if ("VIDEO_SEMANTIC".equals(type)) {
                    Object text = first.get("summary");
                    String finished = step.getFinishedAt() == null ? "" : step.getFinishedAt().toString();
                    if (text instanceof String value && !value.isBlank() && (semanticAt.isEmpty() || finished.compareTo(semanticAt) >= 0)) {
                        summary = value.trim();
                        semanticAt = finished;
                        tags = new ArrayList<>();
                        if (first.get("tags") instanceof List<?> rawTags)
                            for (Object tag : rawTags) {
                                String value2 = tag == null ? "" : String.valueOf(tag).trim();
                                if (!value2.isEmpty() && !tags.contains(value2)) tags.add(value2);
                            }
                    }
                }
                if ("VIDEO_AUDIO".equals(type) && first.get("tracks") instanceof List<?> tracks && !tracks.isEmpty()
                        && tracks.get(0) instanceof Map<?, ?> track && track.get("transcript") instanceof Map<?, ?> tr
                        && tr.get("text") instanceof String text && !text.isBlank()) {
                    transcript = text.trim();
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fileId", target);
        result.put("taskCount", taskCount);
        result.put("summary", summary);
        result.put("tags", tags);
        result.put("transcriptText", transcript);
        result.put("transcriptLength", transcript.length());
        result.put("semanticAt", semanticAt);
        return result;
    }

    private PlanSpec resolvePlan(MediaRequest request, Kind kind) {
        PlanSpec plan = resolvePlanBase(request, kind);
        List<String> ordered = plan.steps();
        Map<String, RuleSnapshot> rules = new LinkedHashMap<>(plan.rules());
        List<String> selected = request.steps();
        if (selected != null && !selected.isEmpty()) {
            java.util.Set<String> canonical = kind == Kind.IMAGE
                    ? java.util.Set.of("IMAGE_PREPROCESS", "IMAGE_DETECT", "IMAGE_SCENE")
                    : java.util.Set.of("VIDEO_STANDARD", "VIDEO_KEYFRAME", "VIDEO_AUDIO", "VIDEO_EVENT", "VIDEO_SEMANTIC", "VIDEO_SUMMARY");
            java.util.Set<String> wanted = new LinkedHashSet<>();
            for (String step : selected) {
                String value = step == null ? "" : step.trim().toUpperCase(Locale.ROOT);
                if (canonical.contains(value)) wanted.add(value);
            }
            if (wanted.isEmpty()) throw new BizException(ErrorCode.PARAM_ERROR, "请至少选择一个加工节点");
            ordered = ordered.stream().filter(wanted::contains).toList();
            // 内容总结是可选的末端节点：不在推荐流程里，用户显式勾选时追加到最后。
            if (wanted.contains("VIDEO_SUMMARY") && !ordered.contains("VIDEO_SUMMARY")) {
                List<String> withSummary = new ArrayList<>(ordered);
                withSummary.add("VIDEO_SUMMARY");
                ordered = List.copyOf(withSummary);
                rules.putIfAbsent("VIDEO_SUMMARY", builtInRule("VIDEO_SUMMARY"));
            }
            if (ordered.isEmpty()) throw new BizException(ErrorCode.PARAM_ERROR, "所选加工节点不在该流程方案中");
            java.util.Set<String> keep = wanted;
            rules.keySet().retainAll(keep);
        }
        Map<String, String> overrides = request.rules();
        if (overrides != null && !overrides.isEmpty()) {
            for (Map.Entry<String, String> entry : overrides.entrySet()) {
                String step = entry.getKey() == null ? "" : entry.getKey().trim().toUpperCase(Locale.ROOT);
                String ruleId = entry.getValue() == null ? "" : entry.getValue().trim();
                if (ruleId.isEmpty() || "system-default".equals(ruleId) || !ordered.contains(step)) continue;
                String dataset = RULE_DATASETS.get(step);
                if (dataset == null) continue;
                // 兼容位：仅用于已经保存了 builtin-general / builtin-defect 的历史方案。
                // 新建任务的提示词模板统一在「场景描述规则」里维护（前端 SCENE_PROMPTS），此处不再作为可选项暴露。
                if ("IMAGE_SCENE".equals(step) && ruleId.startsWith("builtin-")) {
                    boolean defect = "builtin-defect".equals(ruleId);
                    String content = defect
                            ? "请重点描述图像中的电力设备缺陷：缺陷类型、缺陷部位、严重程度，并给出可检索的缺陷标签；同时说明画面主体、客体及其相互关系。"
                            : "请用中文描述图像中的主体、客体及其相互关系，并给出可检索的场景标签。";
                    Map<String, Object> preset = new LinkedHashMap<>();
                    preset.put("content", content);
                    preset.put("status", "启用");
                    rules.put(step, new RuleSnapshot(dataset, ruleId, 1, defect ? "缺陷描述" : "通用描述",
                            java.util.Collections.unmodifiableMap(preset)));
                    continue;
                }
                Map<String, Object> row;
                try {
                    row = new LinkedHashMap<>(plans.findRuleLatest(dataset, ruleId));
                } catch (BizException missing) {
                    /*
                     * 内容总结是可选末端节点：历史流程/复制任务可能引用已删除的总结规则。
                     * 这种情况退回内置总结口径，不阻断整个视频任务；其它节点仍严格报错。
                     */
                    if ("VIDEO_SUMMARY".equals(step) && missing.getCode() == ErrorCode.NOT_FOUND.getCode()) {
                        LOG.warn("内容总结规则 {} 已不存在，本次使用内置总结口径", ruleId);
                        rules.put(step, builtInRule(step));
                        continue;
                    }
                    throw missing;
                }
                row.remove("revisions");
                if (!"启用".equals(row.get("status")))
                    throw new BizException(ErrorCode.TASK_STATE_CONFLICT, step + " 加工规则已停用：" + ruleId);
                Object rawRevision = row.get("ruleRevision");
                int revision = rawRevision instanceof Number number ? number.intValue() : 1;
                rules.put(step, new RuleSnapshot(dataset, ruleId, revision,
                        String.valueOf(row.getOrDefault("name", ruleId)),
                        java.util.Collections.unmodifiableMap(new LinkedHashMap<>(row))));
            }
        }
        // 内容总结消费的是上游文本：没有音轨转写、事件分析或语义解析中的任何一个，就没有可总结的内容。
        if (ordered.contains("VIDEO_SUMMARY")
                && ordered.stream().noneMatch(step -> List.of("VIDEO_AUDIO", "VIDEO_EVENT", "VIDEO_SEMANTIC").contains(step)))
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "内容总结需要同时启用音轨与转写、事件分析或复杂场景语义解析中的至少一个节点");
        validateRules(rules);
        return new PlanSpec(plan.id(), plan.name(), plan.version(), ordered, plan.settings(), rules);
    }

    private PlanSpec resolvePlanBase(MediaRequest request, Kind kind) {
        String id = request.planId();
        if (id == null || id.isBlank() || "system-default".equals(id)) {
            return new PlanSpec("system-default", "推荐流程", 1,
                    kind == Kind.IMAGE
                            ? List.of("IMAGE_PREPROCESS", "IMAGE_DETECT", "IMAGE_SCENE")
                            // 与需求「按顺序执行：抽帧处理 → 音轨提取 → 语义特征分析」一致：抽帧在音轨之前。
                            : List.of("VIDEO_STANDARD", "VIDEO_KEYFRAME", "VIDEO_AUDIO", "VIDEO_EVENT", "VIDEO_SEMANTIC"),
                    Map.of(), builtInRules(kind));
        }
        Map<String, Object> saved = plans.find(id);
        if (Boolean.TRUE.equals(saved.get("deleted")) || !"启用".equals(saved.get("status")))
            throw new BizException(ErrorCode.TASK_STATE_CONFLICT, "所选流程方案已停用或删除");
        if (!kind.name().equals(saved.get("kind")))
            throw new BizException(ErrorCode.PARAM_ERROR, "流程方案与文件类型不匹配");
        Object rawVersion = saved.get("version");
        int version = rawVersion instanceof Number number ? number.intValue() : -1;
        if (version < 1) throw new BizException(ErrorCode.PARAM_ERROR, "流程方案版本不合法");
        Object rawSteps = saved.get("steps");
        if (!(rawSteps instanceof List<?> list) || list.isEmpty())
            throw new BizException(ErrorCode.PARAM_ERROR, "流程方案没有启用节点");
        List<String> canonical = kind == Kind.IMAGE
                ? List.of("IMAGE_PREPROCESS", "IMAGE_DETECT", "IMAGE_SCENE")
                : List.of("VIDEO_STANDARD", "VIDEO_KEYFRAME", "VIDEO_AUDIO", "VIDEO_EVENT", "VIDEO_SEMANTIC", "VIDEO_SUMMARY");
        Set<String> selected = new LinkedHashSet<>();
        for (Object raw : list) {
            if (!(raw instanceof String step) || !canonical.contains(step) || !selected.add(step))
                throw new BizException(ErrorCode.PARAM_ERROR, "流程方案包含重复或不支持的节点");
        }
        // 单节点方案：允许只启用一个节点（例如只做目标识别 / 只做抽帧 / 只做事件分析）。
        // 未启用上游节点时，执行引擎会让该节点直接处理原始文件，不再强制依赖预处理/标准化/抽帧。
        Map<String, Object> settings = new LinkedHashMap<>();
        if (saved.get("settings") instanceof Map<?, ?> raw) raw.forEach((key, value) -> settings.put(String.valueOf(key), value));
        Map<String, RuleSnapshot> resolvedRules = new LinkedHashMap<>();
        Map<?, ?> ruleRefs = saved.get("rules") instanceof Map<?, ?> existing ? existing : Map.of();
        for (String step : selected) {
            Object reference = ruleRefs.get(step);
            if (!(reference instanceof Map<?, ?> value)) {
                // 未单独配置子规则的节点（例如场景描述使用平台固定提示词）回退到系统内置参数，
                // 不能因为节点不设规则而阻断整个方案提交。
                resolvedRules.put(step, builtInRule(step));
                continue;
            }
            String dataset = String.valueOf(value.get("dataset"));
            String ruleId = String.valueOf(value.get("id"));
            Object rawRevision = value.get("version");
            int revision = rawRevision instanceof Number number ? number.intValue() : -1;
            if (!RULE_DATASETS.get(step).equals(dataset) || revision < 1)
                throw new BizException(ErrorCode.PARAM_ERROR, step + " 子规则引用不合法");
            if ("system-default".equals(ruleId)) {
                if (revision != 1) throw new BizException(ErrorCode.PARAM_ERROR, "系统默认规则版本不合法");
                resolvedRules.put(step, builtInRule(step));
                continue;
            }
            Map<String, Object> rule = new LinkedHashMap<>(plans.findRule(dataset, ruleId, revision));
            rule.remove("revisions");
            if (!"启用".equals(rule.get("status")))
                throw new BizException(ErrorCode.TASK_STATE_CONFLICT, step + " 子规则版本未发布或已停用");
            resolvedRules.put(step, new RuleSnapshot(dataset, ruleId, revision,
                    String.valueOf(rule.getOrDefault("name", ruleId)),
                    java.util.Collections.unmodifiableMap(new LinkedHashMap<>(rule))));
        }
        PlanSpec plan = new PlanSpec(id, String.valueOf(saved.getOrDefault("name", id)), version,
                canonical.stream().filter(selected::contains).toList(), settings, resolvedRules);
        validateRules(resolvedRules);
        return plan;
    }

    /**
     * 子规则取值校验：保存的方案与手工指定的规则走同一套口径，
     * 避免界面拦住了、接口直调却能绕过（例如给了不存在的固定取值）。
     */
    /**
     * 抽帧方案里「抽帧间隔 / 提取算法 / 每分钟抽帧数」的执行顺序是固定的：
     * 按间隔抽候选帧 → 按算法筛选 → 每分钟上限取前 N 帧（有变化度时按变化度优先）。
     * 这里只拒绝**永远无法生效**的组合，并给出可执行的改法，避免用户以为设了却在空转。
     */
    private static void rejectKeyframeConflicts(RuleSnapshot rule) {
        String name = rule.text("name", "");
        String label = name.isBlank() ? "抽帧方案" : "抽帧方案「" + name + "」";
        int interval = intervalOf(rule.text("freq", "每 5 秒一帧"));
        int perMinute = rule.integer("framesPerMinute", 0);
        if (interval > 0 && perMinute > 0 && perMinute > 60 / interval) {
            throw new BizException(ErrorCode.PARAM_ERROR, label + "的每分钟抽取帧数 " + perMinute
                    + " 超过抽帧间隔的理论上限：每 " + interval + " 秒一帧最多 " + (60 / interval)
                    + " 帧/分钟，请把每分钟帧数降到 " + (60 / interval) + " 以内或把间隔改小");
        }
        if (rule.decimal("semanticThreshold", 0) > 0 && rule.text("prompt", "").isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, label
                    + "填了语义匹配阈值但没填语义关注点，阈值不会生效：请填写语义关注点，或把阈值设为 0");
        }
    }

    private static void validateRules(Map<String, RuleSnapshot> rules) {
        for (Map.Entry<String, RuleSnapshot> entry : rules.entrySet()) {
            String step = entry.getKey();
            RuleSnapshot rule = entry.getValue();
            if ("VIDEO_KEYFRAME".equals(step)) rejectKeyframeConflicts(rule);
            boolean invalid = switch (step) {
                case "IMAGE_PREPROCESS" -> !List.of("800 × 600", "1024 × 768", "1920 × 1080")
                        .contains(rule.text("targetSize", "1920 × 1080"))
                        || !List.of("JPEG", "PNG").contains(rule.text("outputFormat", "JPEG"))
                        || !within(rule.decimal("brightness", 0), -50, 50)
                        || !within(rule.decimal("contrast", 0), -20, 20)
                        || !within(rule.decimal("grayscale", 0), 0, 1)
                        || !within(rule.decimal("denoise", 0), 0, 100)
                        || !within(rule.decimal("sharpen", 0), 0, 100)
                        || !within(rule.decimal("edge", 0), 0, 100)
                        || !validOutputDirectory(rule.text("outputDirectory", ""));
                case "IMAGE_DETECT" -> !unitInterval(rule.decimal("minConfidence", 0))
                        || rule.text("targetLabels", "").length() > 500;
                case "IMAGE_SCENE" -> rule.text("content", "").length() > 500;
                case "VIDEO_STANDARD" -> !List.of("source", "1920x1080", "1280x720", "640x360")
                        .contains(rule.text("resolution", "source"))
                        || !List.of("mp4", "mkv", "mov", "avi").contains(rule.text("container", "mp4"))
                        || rule.integer("crf", 23) < 18 || rule.integer("crf", 23) > 35;
                case "VIDEO_AUDIO" -> rule.integer("trackNo", 1) < 1 || rule.integer("trackNo", 1) > 32;
                case "VIDEO_KEYFRAME" -> !List.of(1, 5, 10, 15, 30).contains(intervalOf(rule.text("freq", "每 5 秒一帧")))
                        || !List.of("关键帧提取", "均匀时间间隔", "运动检测触发")
                                .contains(rule.text("algo", "均匀时间间隔"))
                        || !unitInterval(rule.decimal("similarityThreshold", 0.95))
                        || !List.of("JPEG", "PNG").contains(rule.text("outputFormat", "JPEG"))
                        || !List.of("source", "1080p", "720p", "480p").contains(rule.text("resolution", "source"))
                        || !List.of(80, 90, 100).contains(rule.integer("quality", 90))
                        || !validEnhancements(rule.text("enhance", ""))
                        || !validEnhancements(rule.text("deblur", ""))
                        || rule.text("prompt", "").length() > 500
                        || !unitInterval(rule.decimal("semanticThreshold", 0))
                        /* 三者在执行时是「间隔抽候选 → 算法筛选 → 每分钟上限取前 N」的串联关系，
                           这里只拒绝「永远无法满足」的组合，避免用户以为设了却在空转。 */
                        || !validFramesPerMinute(intervalOf(rule.text("freq", "每 5 秒一帧")),
                                rule.integer("framesPerMinute", 0))
                        || (rule.decimal("semanticThreshold", 0) > 0 && rule.text("prompt", "").isBlank());
                case "VIDEO_EVENT" -> !unitInterval(confidence(rule.text("conf", "0")))
                        || rule.text("type", "").length() > 40
                        || !validSeconds(rule.text("dur", "0s"), 0, 600, true)
                        || !List.of("全画面", "左半区", "右半区", "上半区", "下半区")
                                .contains(rule.text("region", "全画面"))
                        || !List.of("任一目标出现", "同类目标连续出现", "至少2次")
                                .contains(rule.text("logic", "任一目标出现"))
                        || !List.of("", "无要求", "先后发生", "同时发生", "间隔不超过 5 秒", "间隔不超过 30 秒")
                                .contains(rule.text("temporal", ""))
                        || !unitInterval(rule.decimal("weight", 1))
                        || rule.text("judge", "").length() > 500;
                case "VIDEO_SEMANTIC" -> !validWindowSeconds(rule.settings().get("window"))
                        || !unitInterval(rule.decimal("crossThreshold", 0.8))
                        || !validLabels(splitTexts(rule.settings().get("targets")))
                        || !validLabels(splitTexts(rule.settings().get("actions")))
                        || !validTrigger(rule)
                        || !validCausal(rule)
                        || rule.text("detectorModel", "").length() > 128;
                case "VIDEO_SUMMARY" -> rule.text("prompt", "").length() > 2000
                        || rule.integer("maxChars", 800) < 100 || rule.integer("maxChars", 800) > 2000;
                default -> false;
            };
            if (invalid) throw new BizException(ErrorCode.PARAM_ERROR, step + " 子规则参数超出允许范围");
        }
    }

    /** 从「每 N 秒一帧」里取 N，非法值返回 0。 */
    private static int intervalOf(String frequency) {
        if (frequency == null) return 0;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)").matcher(frequency);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private static boolean unitInterval(double value) {
        return Double.isFinite(value) && value >= 0 && value <= 1;
    }

    /** 每分钟抽帧数是「配额」，不能超过该抽帧间隔下的理论最大帧数，否则这个配置永远不起作用。 */
    private static boolean validFramesPerMinute(int interval, int framesPerMinute) {
        if (framesPerMinute < 0) return false;
        if (framesPerMinute == 0) return true;
        if (interval <= 0) return false;
        return framesPerMinute <= 60 / interval;
    }

    /** 输出目录：空表示不指定（落未归档）；否则为数据目录编号（字母数字点下划线连字符）。 */
    private static boolean validOutputDirectory(String value) {
        return value == null || value.isBlank() || value.matches("[A-Za-z0-9._-]{1,128}");
    }

    /** 抽帧增强方式白名单：锐化 / 对比度 / 亮度 / 高斯去模糊 / 维纳去模糊。 */
    private static boolean validEnhancements(String value) {
        if (value == null || value.isBlank()) return true;
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .map(item -> item.toLowerCase(java.util.Locale.ROOT))
                .filter(item -> !item.isEmpty())
                .allMatch(item -> List.of("sharpen", "contrast", "brightness", "deblur",
                        "deblur_gaussian", "deblur_wiener").contains(item));
    }

    /** 把「图像增强」与「去模糊算法」两个字段合并成适配器需要的逗号串（去重保序）。 */
    private static String combineEnhancements(String enhance, String deblur) {
        StringBuilder builder = new StringBuilder();
        for (String value : List.of(enhance == null ? "" : enhance, deblur == null ? "" : deblur)) {
            for (String item : value.split(",")) {
                String trimmed = item.trim();
                if (trimmed.isEmpty() || builder.indexOf(trimmed) >= 0) continue;
                if (builder.length() > 0) builder.append(',');
                builder.append(trimmed);
            }
        }
        return builder.toString();
    }

    /** 只保留属性识别约定字段，避免复核提交把任意结构写进结果。 */
    private static Map<String, Object> attributeMap(Object raw) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> source)) return attributes;
        for (String key : List.of("color", "shape", "size", "texture")) {
            if (source.get(key) instanceof Map<?, ?> part) {
                Map<String, Object> copy = new LinkedHashMap<>();
                part.forEach((name, value) -> copy.put(String.valueOf(name), value));
                attributes.put(key, copy);
            }
        }
        if (source.get("source") != null) attributes.put("source", String.valueOf(source.get("source")));
        return attributes;
    }

    /** 属性标签：目标类型#颜色#形状 与 目标类型#纹理，随复核修正重新生成。 */
    private static List<String> attributeTags(String label, Map<String, Object> attributes) {
        List<String> tags = new ArrayList<>();
        if (label.isBlank()) return tags;
        String color = attributeName(attributes.get("color"));
        String shape = attributeName(attributes.get("shape"));
        String texture = attributeName(attributes.get("texture"));
        if (!color.isBlank() && !shape.isBlank()) tags.add(label + "#" + color + "#" + shape);
        if (!texture.isBlank()) tags.add(label + "#" + texture);
        return tags;
    }

    private static String attributeName(Object part) {
        if (part instanceof Map<?, ?> map && map.get("name") != null) return String.valueOf(map.get("name")).trim();
        return "";
    }

    private static boolean validSeconds(String value, double minimum, double maximum, boolean allowPrefix) {
        String normalized = allowPrefix ? value.replaceFirst("^≥\\s*", "") : value;
        if (!allowPrefix && normalized.contains(".")) return false;
        if (!normalized.matches("\\d+(?:\\.\\d+)?\\s*s?")) return false;
        try {
            double seconds = Double.parseDouble(normalized.replaceAll("(?i)\\s*s$", "").trim());
            return within(seconds, minimum, maximum);
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private static boolean textList(Object value) {
        return value == null || value instanceof List<?> list && list.stream().allMatch(String.class::isInstance);
    }

    /**
     * 规则的「列表」字段可能存成顿号 / 逗号 / 换行分隔的文本，也可能是数组；
     * 统一拆成字符串列表，既用于校验也用于下发给适配器，避免两种形状各判一套。
     */
    private static List<String> splitTexts(Object value) {
        List<String> items = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                String text = item == null ? "" : String.valueOf(item).trim();
                if (!text.isEmpty() && !items.contains(text)) items.add(text);
            }
            return items;
        }
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.isEmpty()) return items;
        for (String part : text.split("[,，、;；\\n\\r]+")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty() && !items.contains(trimmed)) items.add(trimmed);
        }
        return items;
    }

    /** 目标类型 / 动作类型：最多 20 项，单项不超过 40 字。空列表表示不限制。 */
    private static boolean validLabels(List<String> items) {
        return items.size() <= 20 && items.stream().allMatch(item -> item.length() <= 40);
    }

    /** 语义解析规则里「时序窗口」存的是秒或 Ns；取不到数字时回退默认 600 秒。 */
    private static int secondsOf(Object value, int fallback) {
        if (value instanceof Number number) {
            double seconds = number.doubleValue();
            return Double.isFinite(seconds) && seconds > 0 ? (int) Math.round(seconds) : fallback;
        }
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("(\\d+)").matcher(value == null ? "" : String.valueOf(value));
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : fallback;
    }

    /** 时序窗口：1～3600 秒的整数；数字与 Ns 文本两种历史形状都要认。 */
    private static boolean validWindowSeconds(Object value) {
        if (value instanceof Number number) {
            double seconds = number.doubleValue();
            return Double.isFinite(seconds) && seconds >= 1 && seconds <= 3600
                    && seconds == Math.rint(seconds);
        }
        return validSeconds(value == null ? "600s" : String.valueOf(value), 1, 3600, false);
    }

    /** 触发条件：固定取值，或历史自由文本（≤500 字）。 */
    private static boolean validTrigger(RuleSnapshot rule) {
        String value = rule.text("trigger", "");
        return value.isBlank() || SEMANTIC_TRIGGERS.containsKey(value) || value.length() <= 500;
    }

    /** 因果逻辑：固定取值 + 两个目标类型；旧规则的历史自由文本仍可用。 */
    private static boolean validCausal(RuleSnapshot rule) {
        String mode = rule.text("causalMode", "");
        if (!mode.isBlank() && !SEMANTIC_CAUSAL.containsKey(mode)) return false;
        if (rule.text("causal", "").length() > 500) return false;
        return rule.text("causalSubject", "").length() <= 40 && rule.text("causalObject", "").length() <= 40;
    }

    /** 下发给适配器的语义规则：列表统一成数组，窗口统一成秒数，未配置项不伪造。 */
    private static Map<String, Object> semanticSettings(RuleSnapshot rule) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("window", secondsOf(rule.settings().get("window"), 600));
        double cross = rule.decimal("crossThreshold", 0.8);
        settings.put("crossThreshold", Double.isFinite(cross) ? cross : 0.8);
        settings.put("targets", splitTexts(rule.settings().get("targets")));
        settings.put("actions", splitTexts(rule.settings().get("actions")));
        settings.put("trigger", semanticTriggerText(rule));
        settings.put("causal", semanticCausalText(rule));
        settings.put("detectorModel", rule.text("detectorModel", ""));
        return settings;
    }

    /** 事件触发条件：规则里存固定取值，这里转成模型提示词用的一句话；历史自由文本原样透传。 */
    private static String semanticTriggerText(RuleSnapshot rule) {
        String value = rule.text("trigger", "");
        return SEMANTIC_TRIGGERS.getOrDefault(value, value);
    }

    /** 因果判断逻辑：固定取值 + 主宾语目标，拼成一句可读表达式；历史自由文本原样透传。 */
    private static String semanticCausalText(RuleSnapshot rule) {
        String legacy = rule.text("causal", "");
        String mode = rule.text("causalMode", "");
        if (mode.isBlank()) return legacy;
        String text = SEMANTIC_CAUSAL.getOrDefault(mode, mode);
        // 「不判断因果」不进入提示词，避免给模型多余的约束。
        if ("NONE".equals(mode)) return "";
        // 规则里填写的表达式优先：与固定取值拼在一起，两者都进入模型提示词。
        if (!legacy.isBlank()) return text + "：" + legacy;
        String subject = rule.text("causalSubject", "");
        String object = rule.text("causalObject", "");
        if (subject.isBlank() && object.isBlank()) return text;
        return text + "：" + (subject.isBlank() ? "任意目标" : subject) + " → " + (object.isBlank() ? "任意目标" : object);
    }

    private static boolean within(double value, double minimum, double maximum) {
        return Double.isFinite(value) && value >= minimum && value <= maximum;
    }

    private static Map<String, RuleSnapshot> builtInRules(Kind kind) {
        List<String> steps = kind == Kind.IMAGE
                ? List.of("IMAGE_PREPROCESS", "IMAGE_DETECT", "IMAGE_SCENE")
                : List.of("VIDEO_STANDARD", "VIDEO_KEYFRAME", "VIDEO_AUDIO", "VIDEO_EVENT", "VIDEO_SEMANTIC", "VIDEO_SUMMARY");
        Map<String, RuleSnapshot> result = new LinkedHashMap<>();
        steps.forEach(step -> result.put(step, builtInRule(step)));
        return result;
    }

    private static RuleSnapshot builtInRule(String step) {
        Map<String, Object> settings = switch (step) {
            case "IMAGE_PREPROCESS" -> Map.of("targetSize", "1920 × 1080", "outputFormat", "JPEG",
                    "brightness", 0, "contrast", 0, "grayscale", 0, "denoise", 0, "sharpen", 0,
                    "edge", 0, "outputDirectory", "");
            case "IMAGE_SCENE" -> Map.of("content", "请描述图像中的主体、客体及其相互关系，并给出可检索的场景标签；同时返回 subject、object、relations 结构化信息。");
            case "VIDEO_STANDARD" -> Map.of("resolution", "source", "crf", 23);
            case "VIDEO_AUDIO" -> Map.of("trackNo", 1);
            case "VIDEO_KEYFRAME" -> Map.of("freq", "每 5 秒一帧", "similarityThreshold", 0.95);
            default -> Map.of();
        };
        return new RuleSnapshot(RULE_DATASETS.get(step), "system-default", 1, "系统默认规则", settings);
    }

    private record RuleSnapshot(String dataset, String id, int version, String name, Map<String, Object> settings) {
        String text(String key, String fallback) {
            Object value = settings.get(key);
            if (value instanceof String text && !text.isBlank()) return text.trim();
            // 多选字段在注册表里是数组：统一按逗号拼接，和只支持字符串的历史数据保持同一口径。
            if (value instanceof java.util.Collection<?> list) {
                String joined = list.stream().filter(java.util.Objects::nonNull)
                        .map(String::valueOf).map(String::trim).filter(item -> !item.isEmpty())
                        .collect(java.util.stream.Collectors.joining(","));
                return joined.isBlank() ? fallback : joined;
            }
            return fallback;
        }
        int integer(String key, int fallback) {
            Object value = settings.get(key);
            if (value instanceof Number number) return number.intValue();
            try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); }
            catch (NumberFormatException error) { return Integer.MIN_VALUE; }
        }
        double decimal(String key, double fallback) {
            Object value = settings.get(key);
            if (value instanceof Number number) return number.doubleValue();
            try { return value == null ? fallback : Double.parseDouble(String.valueOf(value)); }
            catch (NumberFormatException error) { return Double.NaN; }
        }
        Map<String, Object> publicView() {
            return Map.of("dataset", dataset, "id", id, "version", version,
                    "name", name, "settings", settings);
        }
    }

    private record PlanSpec(String id, String name, int version, List<String> steps,
                            Map<String, Object> settings, Map<String, RuleSnapshot> rules) {
        String text(String key, String fallback) {
            Object value = settings.get(key);
            if (value instanceof String text && !text.isBlank()) return text.trim();
            // 多选字段在注册表里是数组：统一按逗号拼接，和只支持字符串的历史数据保持同一口径。
            if (value instanceof java.util.Collection<?> list) {
                String joined = list.stream().filter(java.util.Objects::nonNull)
                        .map(String::valueOf).map(String::trim).filter(item -> !item.isEmpty())
                        .collect(java.util.stream.Collectors.joining(","));
                return joined.isBlank() ? fallback : joined;
            }
            return fallback;
        }
        int integer(String key, int fallback) {
            Object value = settings.get(key);
            if (value == null) return fallback;
            if (value instanceof Number number) return number.intValue();
            try { return Integer.parseInt(String.valueOf(value)); }
            catch (NumberFormatException failure) { return Integer.MIN_VALUE; }
        }
        double decimal(String key, double fallback) {
            Object value = settings.get(key);
            if (value == null) return fallback;
            if (value instanceof Number number) return number.doubleValue();
            try { return Double.parseDouble(String.valueOf(value)); }
            catch (NumberFormatException failure) { return Double.NaN; }
        }
        Map<String, Object> publicView() {
            Map<String, Object> snapshots = new LinkedHashMap<>();
            rules.forEach((step, rule) -> snapshots.put(step, rule.publicView()));
            return Map.of("id", id, "name", name, "version", version, "steps", steps,
                    "settings", settings, "rules", snapshots);
        }
    }

    /** 预检返回的 sources：fileId → 原文件名，供任务输入与产物命名使用。 */
    private static Map<String, String> sourceFileNames(Object sources) {
        Map<String, String> names = new LinkedHashMap<>();
        if (!(sources instanceof List<?> list)) return names;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> source)) continue;
            Object fileId = source.get("fileId");
            Object fileName = source.get("fileName");
            if (fileId instanceof String id && !id.isBlank()
                    && fileName instanceof String name && !name.isBlank()) names.put(id, name);
        }
        return names;
    }

    /** 任务名称：用户填什么就是什么，多文件也不加序号（需求：名称不跟后缀）。 */
    private static String taskNameFor(String base, List<String> fileIds, String fileId) {
        if (base == null || base.isBlank()) return "";
        return base.trim();
    }

    private static TaskCreateRequest taskRequest(Kind kind, String fileId, String batchId, String requestId,
                                                  Map<String, Map<String, Object>> bindings, PlanSpec plan, String note,
                                                  Map<String, Object> output, String sourceFileName, String taskName) {
        TaskCreateRequest request = new TaskCreateRequest();
        request.setBizType(kind == Kind.IMAGE ? "IMAGE_PIPELINE" : "VIDEO_PIPELINE");
        TaskCreateRequest.Input input = new TaskCreateRequest.Input();
        input.setFileId(fileId);
        input.setExtra(Map.of("batchId", batchId, "workflowVersion", VERSION, "note", note == null ? "" : note.trim(),
                "output", output == null ? Map.of() : output, "sourceFileName", sourceFileName == null ? "" : sourceFileName,
                "taskName", taskName == null ? "" : taskName,
                "planId", plan.id(), "planVersion", plan.version(), "planSnapshot", plan.publicView()));
        request.setInput(input);
        List<TaskCreateRequest.Step> steps = new ArrayList<>();
        if (kind == Kind.IMAGE) {
            if (plan.steps().contains("IMAGE_PREPROCESS")) {
                RuleSnapshot rule = plan.rules().get("IMAGE_PREPROCESS");
                Map<String, Object> preprocess = new LinkedHashMap<>();
                preprocess.put("target_size", rule.text("targetSize", "1920 × 1080"));
                preprocess.put("output_format", rule.text("outputFormat", "JPEG"));
                preprocess.put("brightness", rule.decimal("brightness", 0));
                preprocess.put("contrast", rule.decimal("contrast", 0));
                preprocess.put("grayscale", rule.decimal("grayscale", 0));
                preprocess.put("denoise", rule.decimal("denoise", 0));
                preprocess.put("sharpen", rule.decimal("sharpen", 0));
                preprocess.put("edge", rule.decimal("edge", 0));
                preprocess.put("outputDirectory", rule.text("outputDirectory", ""));
                preprocess.put("ruleId", rule.id());
                preprocess.put("ruleVersion", rule.version());
                steps.add(step("IMAGE_PREPROCESS", "VISION", preprocess, requestId));
            }
            if (plan.steps().contains("IMAGE_DETECT")) {
                RuleSnapshot rule = plan.rules().get("IMAGE_DETECT");
                Map<String, Object> detect = new LinkedHashMap<>(binding(bindings, "IMAGE_DETECT"));
                detect.put("minConfidence", rule.decimal("minConfidence", 0));
                detect.put("targetLabels", rule.text("targetLabels", ""));
                detect.put("ruleId", rule.id());
                detect.put("ruleVersion", rule.version());
                steps.add(step("IMAGE_DETECT", "VISION", detect, requestId));
            }
            if (plan.steps().contains("IMAGE_SCENE")) {
                Map<String, Object> scene = new LinkedHashMap<>(binding(bindings, "SEMANTIC"));
                RuleSnapshot rule = plan.rules().get("IMAGE_SCENE");
                scene.put("prompt", rule.text("content", "请用中文描述图像中的主体、场景和可检索的关键事实。"));
                scene.put("ruleId", rule.id());
                scene.put("ruleVersion", rule.version());
                steps.add(step("IMAGE_SCENE", "VISION", scene, requestId));
            }
        } else {
            if (plan.steps().contains("VIDEO_STANDARD")) {
                RuleSnapshot standard = plan.rules().get("VIDEO_STANDARD");
                steps.add(step("VIDEO_STANDARD", "TRANSCODE", Map.of(
                        "templateId", standard.id(), "resolution", standard.text("resolution", "source"),
                        "crf", standard.integer("crf", 23),
                        "container", standard.text("container", "mp4"),
                        "ruleId", standard.id(),
                        "ruleVersion", standard.version()), requestId));
            }
            Map<String, Object> audio = bindings.containsKey("ASR")
                    ? new LinkedHashMap<>(binding(bindings, "ASR")) : new LinkedHashMap<>();
            RuleSnapshot audioRule = plan.rules().get("VIDEO_AUDIO");
            if (audioRule != null) {
                audio.put("trackNo", audioRule.integer("trackNo", 1));
                audio.put("ruleId", audioRule.id());
                audio.put("ruleVersion", audioRule.version());
            }
            RuleSnapshot frameRule = plan.rules().get("VIDEO_KEYFRAME");
            String frequency = frameRule == null ? "每 5 秒一帧" : frameRule.text("freq", "每 5 秒一帧");
            java.util.regex.Matcher intervalMatcher = java.util.regex.Pattern.compile("(\\d+)").matcher(frequency);
            int interval = intervalMatcher.find() ? Integer.parseInt(intervalMatcher.group(1)) : 5;
            if (plan.steps().contains("VIDEO_KEYFRAME")) {
                Map<String, Object> frameParams = new LinkedHashMap<>();
                frameParams.put("interval", interval);
                frameParams.put("algorithm", frameRule.text("algo", "均匀时间间隔"));
                frameParams.put("similarityThreshold", frameRule.decimal("similarityThreshold", 0.95));
                frameParams.put("outputFormat", frameRule.text("outputFormat", "JPEG"));
                frameParams.put("resolution", frameRule.text("resolution", "source"));
                frameParams.put("quality", frameRule.integer("quality", 90));
                frameParams.put("maxFrames", frameRule.integer("framesPerMinute", 0));
                frameParams.put("enhance", combineEnhancements(frameRule.text("enhance", ""),
                        frameRule.text("deblur", "")));
                frameParams.put("prompt", frameRule.text("prompt", ""));
                frameParams.put("semanticThreshold", frameRule.decimal("semanticThreshold", 0));
                frameParams.put("ruleId", frameRule.id());
                frameParams.put("ruleVersion", frameRule.version());
                steps.add(step("VIDEO_KEYFRAME", "TRANSCODE", frameParams, requestId));
            }
            /* 需求 4.3.2.1.1.6.1：按顺序执行「抽帧处理 → 音轨提取 → 语义特征分析」，
               因此音轨步骤排在抽帧之后；两者都只依赖标准化产物，互不影响。 */
            if (plan.steps().contains("VIDEO_AUDIO")) steps.add(step("VIDEO_AUDIO", "TRANSCODE", audio, requestId));
            if (plan.steps().contains("VIDEO_EVENT")) {
                RuleSnapshot eventRule = plan.rules().get("VIDEO_EVENT");
                Map<String, Object> event = new LinkedHashMap<>(binding(bindings, "IMAGE_DETECT"));
                event.put("interval", interval);
                event.put("ruleId", eventRule.id());
                event.put("ruleVersion", eventRule.version());
                event.put("minConfidence", confidence(eventRule.text("conf", "0")));
                event.put("eventRule", eventRule.settings());
                steps.add(step("VIDEO_EVENT", "TRANSCODE", event, requestId));
            }
            if (plan.steps().contains("VIDEO_SEMANTIC")) {
                RuleSnapshot semanticRule = plan.rules().get("VIDEO_SEMANTIC");
                Map<String, Object> semantic = new LinkedHashMap<>(binding(bindings, "SEMANTIC"));
                /* 规则里可选地绑定目标识别模型；未绑定时跟随平台当前生效的检测模型。
                   预检已校验二者一致，这里只如实记录本次实际使用的模型编号。 */
                String configuredDetector = semanticRule.text("detectorModel", "");
                String effectiveDetector =
                        String.valueOf(bindings.getOrDefault("IMAGE_DETECT", Map.of()).getOrDefault("id", ""));
                semantic.put("detectorModelId", configuredDetector.isBlank()
                        ? effectiveDetector : resolveDetectorId(configuredDetector, bindings));
                semantic.put("detectorModel", configuredDetector);
                semantic.put("interval", interval);
                semantic.put("ruleId", semanticRule.id());
                semantic.put("ruleVersion", semanticRule.version());
                /* 历史规则的因果逻辑是自由文本，作为附加分析要求下发；新规则用固定取值，见 semanticRule.causal。 */
                semantic.put("rulePrompt", semanticRule.text("causalMode", "").isBlank()
                        ? semanticRule.text("causal", "") : "");
                semantic.put("semanticRule", semanticSettings(semanticRule));
                steps.add(step("VIDEO_SEMANTIC", "TRANSCODE", semantic, requestId));
            }
            if (plan.steps().contains("VIDEO_SUMMARY")) {
                RuleSnapshot summaryRule = plan.rules().get("VIDEO_SUMMARY");
                Map<String, Object> summary = new LinkedHashMap<>(binding(bindings, "SEMANTIC"));
                summary.put("ruleId", summaryRule.id());
                summary.put("ruleVersion", summaryRule.version());
                summary.put("prompt", summaryRule.text("prompt", ""));
                summary.put("maxChars", summaryRule.integer("maxChars", 800));
                summary.put("summaryRule", summaryRule.settings());
                steps.add(step("VIDEO_SUMMARY", "TRANSCODE", summary, requestId));
            }
        }
        for (TaskCreateRequest.Step item : steps) {
            item.getParams().put("batchId", batchId);
            item.getParams().put("planId", plan.id());
            item.getParams().put("planVersion", plan.version());
        }
        request.setSteps(steps);
        return request;
    }

    private static double confidence(String raw) {
        try { return Double.parseDouble(raw.trim().replaceFirst("^≥\\s*", "")); }
        catch (NumberFormatException failure) { return Double.NaN; }
    }

    private static Map<String, Object> binding(Map<String, Map<String, Object>> bindings, String capability) {
        Map<String, Object> selected = bindings.get(capability);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("model", String.valueOf(selected.getOrDefault("model", "")));
        params.put("capability", capability);
        params.put("modelId", String.valueOf(selected.getOrDefault("id", "")));
        params.put("provider", String.valueOf(selected.getOrDefault("provider", "")));
        params.put("version", String.valueOf(selected.getOrDefault("version", "")));
        return params;
    }

    private static TaskCreateRequest.Step step(String type, String engine, Map<String, Object> params, String requestId) {
        TaskCreateRequest.Step step = new TaskCreateRequest.Step();
        step.setStepType(type);
        step.setEngineType(engine);
        Map<String, Object> values = new LinkedHashMap<>(params);
        values.put("workflowVersion", VERSION);
        values.put("requestId", requestId);
        step.setParams(values);
        return step;
    }

    private static Kind validate(MediaRequest request) {
        if (request == null || request.kind() == null || request.requestId() == null
                || !request.requestId().matches("[A-Za-z0-9-]{8,64}") || request.fileIds() == null
                || request.fileIds().isEmpty() || request.fileIds().size() > 20)
            throw new BizException(ErrorCode.PARAM_ERROR, "请选择 1 至 20 个文件并提供本次请求编号");
        Kind kind;
        try { kind = Kind.valueOf(request.kind().trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException failure) { throw new BizException(ErrorCode.PARAM_ERROR, "媒体类型仅支持 IMAGE/VIDEO"); }
        Set<String> unique = new LinkedHashSet<>(request.fileIds());
        if (unique.size() != request.fileIds().size() || unique.stream().anyMatch(id -> id == null || !id.matches("[A-Za-z0-9._-]{1,128}")))
            throw new BizException(ErrorCode.PARAM_ERROR, "文件编号重复或不合法");
        return kind;
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format("%02x", b & 0xff));
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String batchId(MediaRequest request, Kind kind) {
        return "MB-" + digest(request.requestId() + ":" + kind + ":" +
                (request.planId() == null ? "system-default" : request.planId()) + ":"
                + request.fileIds().stream().sorted().toList()).substring(0, 24);
    }

    private static String snapshotToken(PlanSpec plan, Map<String, Map<String, Object>> bindings) {
        StringBuilder value = new StringBuilder(plan.id()).append('|').append(plan.version());
        plan.steps().forEach(step -> {
            RuleSnapshot rule = plan.rules().get(step);
            value.append('|').append(step).append(':').append(rule.dataset()).append(':')
                    .append(rule.id()).append(':').append(rule.version()).append(':')
                    .append(new java.util.TreeMap<>(rule.settings()));
        });
        new java.util.TreeMap<>(bindings).forEach((capability, model) -> value.append('|')
                .append(capability).append(':').append(model.get("id")).append(':')
                .append(model.get("version")));
        return digest(value.toString());
    }

    public record MediaRequest(String kind, List<String> fileIds, String requestId, String planId,
                               String snapshotToken, List<String> steps, Map<String, String> rules, String note,
                               Map<String, Object> output, String taskName) {
        /** 既有调用方未传任务名时保持原签名。 */
        public MediaRequest(String kind, List<String> fileIds, String requestId, String planId,
                            String snapshotToken, List<String> steps, Map<String, String> rules, String note,
                            Map<String, Object> output) {
            this(kind, fileIds, requestId, planId, snapshotToken, steps, rules, note, output, null);
        }
        public MediaRequest(String kind, List<String> fileIds, String requestId, String planId,
                            String snapshotToken, List<String> steps) {
            this(kind, fileIds, requestId, planId, snapshotToken, steps, null, null, null, null);
        }
        public MediaRequest(String kind, List<String> fileIds, String requestId, String planId, String snapshotToken) {
            this(kind, fileIds, requestId, planId, snapshotToken, null, null, null, null, null);
        }
        public MediaRequest(String kind, List<String> fileIds, String requestId, String planId) {
            this(kind, fileIds, requestId, planId, null, null, null, null, null, null);
        }
        public MediaRequest(String kind, List<String> fileIds, String requestId) {
            this(kind, fileIds, requestId, null, null, null, null, null, null, null);
        }
    }
    private enum Kind { IMAGE("图片"), VIDEO("视频"); final String label; Kind(String label) { this.label = label; } }
}
