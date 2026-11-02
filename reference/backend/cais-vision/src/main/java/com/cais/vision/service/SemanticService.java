package com.cais.vision.service;

import com.cais.vision.domain.PromptTemplate;
import com.cais.vision.domain.RecognitionTask;
import com.cais.vision.domain.SemanticTask;
import com.cais.vision.domain.SemanticTask.SceneRelation;
import com.cais.vision.domain.VisionTaskStatus;
import com.cais.vision.store.VisionStore;
import com.cais.common.api.ErrorCode;
import com.cais.common.api.PageResult;
import com.cais.common.exception.BizException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 复杂场景语义解析与描述服务（R8，12 条排期条目）。
 * ----------------------------------------------------------------------------
 * 能力清单：
 *   1. 解析任务管理：新增/修改/删除/查询/展示 —— {@link #create} {@link #delete} {@link #list}
 *   2. 语义描述与标签生成：自然语言描述 + 场景结构化信息 + 总结文本 + 标签 —— {@link #execute}
 *   3. 提示词模板管理（通过模板调整解析策略）—— {@link PromptTemplateService}
 *   4. 复核修正与版本更新：描述/总结/标签查询展示与人工修正 —— {@link #review}
 *   5. 结果查询、展示、导出 —— {@link #exportResult}
 *   6. 与外部检索系统的语义信息对接 —— {@link #syncToRetrieval}（对接回执落库，可查同步状态）
 *
 * 异常场景（R8 自测项）：
 *   * 多目标重叠：重叠目标强制显式标注「位于/包含」关系（不隐式省略）；
 *   * 关系歧义：SceneRelation.ambiguity=true 且保留 candidates，不静默择一；
 *   * 长文本描述：描述超过 2000 字拒绝（提示分段）。
 */
@Service
public class SemanticService {

    /** 自然语言描述长度上限（长文本保护） */
    private static final int MAX_DESC_LENGTH = 2000;

    private final VisionStore store;
    private final RecognitionService recognitionService;
    private final PromptTemplateService templateService;

    public SemanticService(VisionStore store, RecognitionService recognitionService,
                           PromptTemplateService templateService) {
        this.store = store;
        this.recognitionService = recognitionService;
        this.templateService = templateService;
    }

    // ------------------------------------------------------------------
    // 1. 任务管理
    // ------------------------------------------------------------------

    /**
     * 新增解析任务。
     *
     * 依赖链：R8 基于 R7 的对象结构解析场景（recognitionTaskId 必填，
     * 且该识别任务须处理成功），体现「图片识别类」三服务的数据对接。
     */
    public SemanticTask create(String taskName, String imageFileId, String recognitionTaskId,
                               String templateId, String operator) {
        if (taskName == null || taskName.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "任务名称不能为空");
        }
        if (imageFileId == null || imageFileId.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "输入图像文件 ID 不能为空");
        }
        RecognitionTask rc = recognitionService.detail(recognitionTaskId);
        if (rc.getStatus() != VisionTaskStatus.SUCCESS) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "关联识别任务未完成，不能作为语义解析输入：" + recognitionTaskId);
        }
        PromptTemplate template = null;
        if (templateId != null && !templateId.isBlank()) {
            template = templateService.detail(templateId);
            if (!template.isEnabled()) {
                throw new BizException(ErrorCode.PARAM_ERROR, "提示词模板已停用：" + templateId);
            }
        }

        Instant now = Instant.now();
        SemanticTask task = SemanticTask.builder()
                .taskId(genId("TASK-SM"))
                .taskName(taskName.trim())
                .imageFileId(imageFileId)
                .recognitionTaskId(recognitionTaskId)
                .promptTemplateId(template == null ? null : template.getTemplateId())
                .promptTemplateVersion(template == null ? null : template.getVersion())
                .relations(new ArrayList<>())
                .tags(new ArrayList<>())
                .status(VisionTaskStatus.PENDING)
                .statusLabel(VisionTaskStatus.PENDING.label())
                .version(1)
                .versionHistory(new ArrayList<>(List.of(Map.of(
                        "version", 1, "at", now.toString(), "operator", operator, "note", "任务创建"))))
                .createdBy(operator)
                .createdAt(now)
                .updatedAt(now)
                .build();
        store.saveSemantic(task);
        if (template != null) {
            templateService.increaseRefCount(template.getTemplateId());
        }
        return task;
    }

    public void delete(String taskId) {
        SemanticTask task = requireTask(taskId);
        if (task.getStatus() == VisionTaskStatus.PROCESSING) {
            throw new BizException(ErrorCode.PARAM_ERROR, "处理中任务不可删除");
        }
        store.removeSemantic(taskId);
    }

    /** 查询与展示（分页 + 筛选：状态/关键词）。 */
    public PageResult<SemanticTask> list(String status, String keyword, long pageNum, long pageSize) {
        long pn = pageNum <= 0 ? 1 : pageNum;
        long ps = pageSize <= 0 ? 20 : Math.min(pageSize, 200);
        List<SemanticTask> all = store.listSemantic().stream()
                .filter(t -> status == null || status.isBlank()
                        || t.getStatus().name().equalsIgnoreCase(status))
                .filter(t -> keyword == null || keyword.isBlank()
                        || t.getTaskName().toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))
                        || t.getTaskId().toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))
                        || (t.getImageFileId() != null && t.getImageFileId().contains(keyword)))
                .sorted(Comparator.comparing(SemanticTask::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
        long total = all.size();
        int from = (int) Math.min((pn - 1) * ps, total);
        int to = (int) Math.min(from + ps, total);
        return PageResult.of(total, pn, ps, all.subList(from, to));
    }

    public SemanticTask detail(String taskId) {
        return requireTask(taskId);
    }

    // ------------------------------------------------------------------
    // 2. 语义描述与标签生成（确定性模拟）
    // ------------------------------------------------------------------

    /**
     * 执行解析：基于 R7 识别结果生成三元组关系、自然语言描述、总结与标签。
     *
     * 模拟策略（确定性、结构同生产多模态大模型输出）：
     *   * 关系：相邻目标间生成「位于/附着于」关系；相邻且同源目标标注包含关系
     *     （对应「多目标重叠」显式标注）；
     *   * 歧义：偶数序目标标记 ambiguity=true 并给出候选（对应「关系歧义」）；
     *   * 描述/总结/标签：由对象属性拼装确定性文本。
     */
    public SemanticTask execute(String taskId) {
        SemanticTask task = requireTask(taskId);
        transit(task, VisionTaskStatus.PROCESSING);

        RecognitionTask rc = recognitionService.detail(task.getRecognitionTaskId());
        if (rc.getObjects() == null || rc.getObjects().isEmpty()) {
            task.setFailureReason("关联识别任务无目标属性结果，无法解析场景");
            task.setUpdatedAt(Instant.now());
            transit(task, VisionTaskStatus.FAILED);
            return task;
        }

        // 三元组关系（含重叠包含显式标注与关系歧义标记）
        List<SceneRelation> relations = new ArrayList<>();
        List<com.cais.vision.domain.ObjectAttribute> objs = rc.getObjects();
        for (int i = 0; i < objs.size(); i++) {
            com.cais.vision.domain.ObjectAttribute cur = objs.get(i);
            if (i + 1 < objs.size()) {
                com.cais.vision.domain.ObjectAttribute next = objs.get(i + 1);
                boolean contained = next.getPosition() != null && cur.getPosition() != null
                        && next.getPosition().get(0) >= cur.getPosition().get(0)
                        && next.getPosition().get(2) <= cur.getPosition().get(2)
                        && next.getPosition().get(1) >= cur.getPosition().get(1)
                        && next.getPosition().get(3) <= cur.getPosition().get(3);
                relations.add(SceneRelation.builder()
                        .subject(contained ? next.getObjectType() : cur.getObjectType())
                        .relation(contained ? "包含于" : (i % 2 == 0 ? "位于" : "邻近"))
                        .object(contained ? cur.getObjectType() : next.getObjectType())
                        .confidence(0.82 + (i * 13 % 15) / 100.0)
                        .ambiguity(i % 2 == 0)
                        .candidates(i % 2 == 0 ? List.of("位于", "邻近", "附着于") : null)
                        .build());
            }
        }

        // 自然语言描述（确定性拼装）
        StringBuilder desc = new StringBuilder();
        desc.append("画面共包含 ").append(objs.size()).append(" 个目标：");
        for (int i = 0; i < objs.size(); i++) {
            com.cais.vision.domain.ObjectAttribute o = objs.get(i);
            if (i > 0) {
                desc.append("；");
            }
            desc.append(o.getObjectType())
                    .append("（").append(o.getColor()).append("/").append(o.getShape())
                    .append("/").append(o.getSize()).append("）位于图")
                    .append(o.getPosition() == null ? "-" : quadrantOf(o.getPosition()));
        }
        desc.append("。整体光照均匀，无明显遮挡。");
        if (desc.length() > MAX_DESC_LENGTH) {
            task.setFailureReason("长文本描述超限：" + desc.length() + " 字（上限 " + MAX_DESC_LENGTH + "），请分段解析");
            task.setUpdatedAt(Instant.now());
            transit(task, VisionTaskStatus.FAILED);
            return task;
        }

        // 区域摘要
        Map<String, String> regionSummary = new LinkedHashMap<>();
        regionSummary.put("左上区", objs.size() > 0 ? objs.get(0).getObjectType() + "本体" : "—");
        regionSummary.put("中右区", objs.size() > 1 ? objs.get(1).getObjectType() + "组" : "—");
        regionSummary.put("下沿区", "地面与围栏");

        // 质量校验（语义完整性/描述准确性/逻辑一致性）
        Map<String, String> quality = new LinkedHashMap<>();
        quality.put("语义完整性", "通过");
        quality.put("描述准确性", "通过");
        quality.put("逻辑一致性", relations.stream().anyMatch(SceneRelation::isAmbiguity) ? "存在待确认关系" : "通过");

        // 标签 = 对象类型去重 + 场景通用标签
        List<String> tags = objs.stream()
                .map(com.cais.vision.domain.ObjectAttribute::getObjectType)
                .distinct()
                .collect(Collectors.toCollection(ArrayList::new));
        tags.add("设备巡检");
        tags.add("状态正常");

        task.setRelations(relations);
        task.setDescription(desc.toString());
        task.setSummaryText("该图像为设备区场景，识别 " + objs.size() + " 类目标，"
                + relations.size() + " 组关系（含 " + relations.stream().filter(SceneRelation::isAmbiguity).count()
                + " 组歧义待确认）。");
        task.setTags(tags);
        task.setRegionSummary(regionSummary);
        task.setQualityCheck(quality);
        task.setUpdatedAt(Instant.now());
        transit(task, VisionTaskStatus.SUCCESS);
        return task;
    }

    // ------------------------------------------------------------------
    // 4. 复核修正与版本更新
    // ------------------------------------------------------------------

    /** 人工修正描述 / 总结 / 标签（版本 +1，操作时间与操作人留痕）。 */
    public SemanticTask review(String taskId, String description, String summaryText,
                               List<String> tags, List<SceneRelation> relations, String operator) {
        SemanticTask task = requireTask(taskId);
        if (task.getStatus() != VisionTaskStatus.SUCCESS) {
            throw new BizException(ErrorCode.PARAM_ERROR, "仅解析成功的任务可复核，当前：" + task.getStatusLabel());
        }
        StringBuilder note = new StringBuilder();
        if (description != null) {
            if (description.length() > MAX_DESC_LENGTH) {
                throw new BizException(ErrorCode.PARAM_ERROR,
                        "描述超过 " + MAX_DESC_LENGTH + " 字上限，请分段");
            }
            task.setDescription(description);
            note.append("描述修正；");
        }
        if (summaryText != null) {
            task.setSummaryText(summaryText);
            note.append("总结修正；");
        }
        if (tags != null) {
            task.setTags(new ArrayList<>(tags));
            note.append("标签修正；");
        }
        if (relations != null) {
            // 人工选定关系即消除歧义
            relations.forEach(r -> r.setAmbiguity(false));
            task.setRelations(new ArrayList<>(relations));
            note.append("关系确认（歧义消除）；");
        }
        if (!note.isEmpty()) {
            task.setVersion(task.getVersion() + 1);
            task.getVersionHistory().add(Map.of(
                    "version", task.getVersion(),
                    "at", Instant.now().toString(),
                    "operator", operator,
                    "note", note.toString()));
        }
        task.setUpdatedAt(Instant.now());
        store.saveSemantic(task);
        return task;
    }

    // ------------------------------------------------------------------
    // 5. 导出 + 6. 外部检索对接
    // ------------------------------------------------------------------

    /** 解析结果导出。 */
    public Map<String, Object> exportResult(String taskId) {
        SemanticTask task = requireTask(taskId);
        if (task.getStatus() != VisionTaskStatus.SUCCESS) {
            throw new BizException(ErrorCode.PARAM_ERROR, "仅解析成功的任务可导出");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("task", task);
        out.put("exportedAt", Instant.now().toString());
        out.put("format", "cais-semantic-result/v1");
        return out;
    }

    /**
     * 与外部检索系统的语义信息对接：将解析结果（结构化信息 + 标签）推送至检索系统索引。
     *
     * M1 模拟对接：校验存在歧义关系时拒绝推送（歧义须先人工确认），
     *   推送成功后记录 syncedAt，状态可在列表中筛选。
     */
    public SemanticTask syncToRetrieval(String taskId, String operator) {
        SemanticTask task = requireTask(taskId);
        if (task.getStatus() != VisionTaskStatus.SUCCESS) {
            throw new BizException(ErrorCode.PARAM_ERROR, "仅解析成功的任务可同步至检索系统");
        }
        boolean hasAmbiguity = task.getRelations() != null
                && task.getRelations().stream().anyMatch(SceneRelation::isAmbiguity);
        if (hasAmbiguity) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "存在未确认的歧义关系，请先人工复核再同步（外部检索系统拒绝歧义语义）");
        }
        task.setSyncedToRetrieval(true);
        task.setSyncedAt(Instant.now());
        task.setUpdatedAt(Instant.now());
        store.saveSemantic(task);
        return task;
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    public SemanticTask transit(SemanticTask task, VisionTaskStatus target) {
        try {
            task.getStatus().transitTo(target);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.PARAM_ERROR, e.getMessage());
        }
        task.setStatus(target);
        task.setStatusLabel(target.label());
        task.setUpdatedAt(Instant.now());
        store.saveSemantic(task);
        return task;
    }

    private SemanticTask requireTask(String taskId) {
        return store.findSemantic(taskId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "语义解析任务不存在：" + taskId));
    }

    /** 位置坐标 → 象限描述（左上/右上/左下/右下）。 */
    private static String quadrantOf(List<Integer> pos) {
        int cx = (pos.get(0) + pos.get(2)) / 2;
        int cy = (pos.get(1) + pos.get(3)) / 2;
        String h = cx < 640 ? "左" : "右";
        String v = cy < 480 ? "上" : "下";
        return h + v + "区";
    }

    private static String genId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
