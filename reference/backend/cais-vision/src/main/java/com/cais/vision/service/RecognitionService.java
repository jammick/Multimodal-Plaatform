package com.cais.vision.service;

import com.cais.vision.domain.ObjectAttribute;
import com.cais.vision.domain.PreprocessTask;
import com.cais.vision.domain.RecognitionTask;
import com.cais.vision.domain.RecognitionTask.ImageInfo;
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
 * 图像目标与属性精细识别服务（R7，12 条排期条目）。
 * ----------------------------------------------------------------------------
 * 能力清单：
 *   1. 识别任务信息管理：新增/修改/删除/查询/展示 —— {@link #create} {@link #delete} {@link #list}
 *   2. 目标属性识别与标签生成：颜色/形状/尺寸/纹理/位置坐标 + 可检索特征向量标签 ——
 *      {@link #execute}（确定性模拟识别，输出结构与生产 PaddleOCR/检测模型一致）
 *   3. 人工复核、修正与确认：内容总结文本与属性标签文本更新 —— {@link #review}
 *   4. 版本号与操作时间记录 —— {@link #bumpVersion}
 *   5. 识别结果查询、展示、导出 —— {@link #exportResult}
 *   6. 与预处理服务的数据对接：接收高质量图像作为输入 —— {@link #create} 中
 *      inputSource=PREPROCESSED 时校验预处理任务存在且处理成功
 *
 * 置信度分档（需求 4.4）：≥0.90 通过 / 0.85~0.90 关注 / <0.85 转复核。
 */
@Service
public class RecognitionService {

    /** 置信度转复核阈值 */
    public static final double REVIEW_THRESHOLD = 0.85;

    private final VisionStore store;
    private final PreprocessService preprocessService;

    public RecognitionService(VisionStore store, PreprocessService preprocessService) {
        this.store = store;
        this.preprocessService = preprocessService;
    }

    // ------------------------------------------------------------------
    // 1. 任务信息管理
    // ------------------------------------------------------------------

    /**
     * 新增识别任务。
     *
     * 数据对接（R7 ↔ R6）：inputSource=PREPROCESSED 时校验关联预处理任务
     *   存在且状态为处理成功（「接收高质量图像作为输入」），否则拒绝创建。
     */
    public RecognitionTask create(String taskName, String imageFileId, String inputSource,
                                  String preprocessTaskId, int width, int height, String format,
                                  String operator) {
        if (taskName == null || taskName.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "任务名称不能为空");
        }
        if (imageFileId == null || imageFileId.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "输入图像文件 ID 不能为空");
        }
        String source = inputSource == null || inputSource.isBlank() ? "ORIGINAL" : inputSource;
        if (!"ORIGINAL".equals(source) && !"PREPROCESSED".equals(source)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "输入来源须为 ORIGINAL/PREPROCESSED");
        }
        String ppTaskId = null;
        if ("PREPROCESSED".equals(source)) {
            if (preprocessTaskId == null || preprocessTaskId.isBlank()) {
                throw new BizException(ErrorCode.PARAM_ERROR, "输入来源为预处理输出时必须关联预处理任务");
            }
            PreprocessTask pp = preprocessService.detail(preprocessTaskId);
            if (pp.getStatus() != VisionTaskStatus.SUCCESS) {
                throw new BizException(ErrorCode.PARAM_ERROR,
                        "关联预处理任务未处理成功，不能作为识别输入：" + preprocessTaskId);
            }
            ppTaskId = preprocessTaskId;
        }

        Instant now = Instant.now();
        RecognitionTask task = RecognitionTask.builder()
                .taskId(genId("TASK-RC"))
                .taskName(taskName.trim())
                .imageFileId(imageFileId)
                .inputSource(source)
                .preprocessTaskId(ppTaskId)
                .image(ImageInfo.builder()
                        .width(width)
                        .height(height)
                        .format(format)
                        .path("/vision/recognize/input/" + imageFileId)
                        .build())
                .objects(new ArrayList<>())
                .vectorTags(new ArrayList<>())
                .status(VisionTaskStatus.PENDING)
                .statusLabel(VisionTaskStatus.PENDING.label())
                .version(1)
                .versionHistory(new ArrayList<>(List.of(versionEntry(1, operator, "任务创建"))))
                .reviewStatus("PENDING")
                .createdBy(operator)
                .createdAt(now)
                .updatedAt(now)
                .build();
        store.saveRecognition(task);
        return task;
    }

    /** 删除任务（仅非处理中可删）。 */
    public void delete(String taskId) {
        RecognitionTask task = requireTask(taskId);
        if (task.getStatus() == VisionTaskStatus.PROCESSING) {
            throw new BizException(ErrorCode.PARAM_ERROR, "处理中任务不可删除");
        }
        store.removeRecognition(taskId);
    }

    /** 查询与展示（分页 + 筛选：状态 / 复核状态 / 关键词）。 */
    public PageResult<RecognitionTask> list(String status, String reviewStatus, String keyword,
                                            long pageNum, long pageSize) {
        long pn = pageNum <= 0 ? 1 : pageNum;
        long ps = pageSize <= 0 ? 20 : Math.min(pageSize, 200);
        List<RecognitionTask> all = store.listRecognition().stream()
                .filter(t -> status == null || status.isBlank()
                        || t.getStatus().name().equalsIgnoreCase(status))
                .filter(t -> reviewStatus == null || reviewStatus.isBlank()
                        || reviewStatus.equals(t.getReviewStatus()))
                .filter(t -> keyword == null || keyword.isBlank()
                        || t.getTaskName().toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))
                        || t.getTaskId().toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))
                        || (t.getImageFileId() != null && t.getImageFileId().contains(keyword)))
                .sorted(Comparator.comparing(RecognitionTask::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
        long total = all.size();
        int from = (int) Math.min((pn - 1) * ps, total);
        int to = (int) Math.min(from + ps, total);
        return PageResult.of(total, pn, ps, all.subList(from, to));
    }

    public RecognitionTask detail(String taskId) {
        return requireTask(taskId);
    }

    // ------------------------------------------------------------------
    // 2. 目标属性识别与标签生成（确定性模拟）
    // ------------------------------------------------------------------

    /**
     * 执行识别：生成目标属性结构与可检索特征向量标签。
     *
     * 模拟策略（确定性、结构同生产）：
     *   * 按图像分辨率推导目标数（每 30 万 px 一个目标，上限 8 个）；
     *   * 属性从电网设备目标库（变压器本体/避雷器/断路器/铭牌/绝缘子）轮转取值，
     *     颜色/形状/尺寸/纹理按目标类型派生，位置按序均布；
     *   * 置信度确定性生成（0.72 + (index * 37 % 27) / 100），覆盖三档分档场景；
     *   * 特征向量标签 = objectType#color#shape 组合，供检索召回。
     */
    public RecognitionTask execute(String taskId) {
        RecognitionTask task = requireTask(taskId);
        transit(task, VisionTaskStatus.PROCESSING);

        int width = task.getImage() != null && task.getImage().getWidth() > 0 ? task.getImage().getWidth() : 1280;
        int height = task.getImage() != null && task.getImage().getHeight() > 0 ? task.getImage().getHeight() : 960;
        int count = Math.min(8, Math.max(1, width * height / 300_000));

        String[] types = {"变压器本体", "避雷器", "断路器", "铭牌文字", "绝缘子"};
        String[][] colorShape = {
                {"深灰", "长方体"}, {"灰白", "圆柱"}, {"军绿", "框架"}, {"银白", "薄板"}, {"瓷白", "伞裙"}
        };
        String[] textures = {"金属拉丝", "釉面光滑", "喷塑粗糙", "金属蚀刻", "瓷质釉面"};

        List<ObjectAttribute> objects = new ArrayList<>();
        List<String> vectorTags = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int t = i % types.length;
            double conf = 0.72 + ((i * 37) % 27) / 100.0; // 0.72 ~ 0.98
            conf = Math.min(conf, 0.98);
            ObjectAttribute obj = ObjectAttribute.builder()
                    .index(i + 1)
                    .objectType(types[t])
                    .color(colorShape[t][0])
                    .shape(colorShape[t][1])
                    .size(t == 0 ? "大型" : t == 1 ? "中型" : "小型")
                    .texture(textures[t])
                    .position(List.of(
                            (i % 4) * width / 4 + 8,
                            (i / 4) * height / 2 + 8,
                            (i % 4) * width / 4 + width / 4 - 8,
                            (i / 4) * height / 2 + height / 2 - 8))
                    .confidence(conf)
                    .confidenceLevel(ObjectAttribute.levelOf(conf))
                    .needReview(conf < REVIEW_THRESHOLD)
                    .build();
            objects.add(obj);
            vectorTags.add(types[t] + "#" + colorShape[t][0] + "#" + colorShape[t][1]);
        }

        // 低置信目标进入复核队列
        boolean hasLow = objects.stream().anyMatch(ObjectAttribute::isNeedReview);

        task.setObjects(objects);
        task.setVectorTags(vectorTags);
        task.setSummaryText("图像 " + task.getImageFileId() + " 共识别 " + count
                + " 个目标，含 " + objects.stream().filter(ObjectAttribute::isNeedReview).count()
                + " 个低置信目标待人工复核。");
        task.setReviewStatus(hasLow ? "PENDING" : "REVIEWED");
        task.setConfirmed(!hasLow);
        task.setUpdatedAt(Instant.now());
        transit(task, VisionTaskStatus.SUCCESS);
        return task;
    }

    // ------------------------------------------------------------------
    // 3. 人工复核、修正与确认
    // ------------------------------------------------------------------

    /**
     * 人工复核修正：更新内容总结文本与属性标签文本，版本 +1 并记录操作时间。
     *
     * @param corrections 目标序号 → 修正后属性文本（仅修正命中的目标）
     * @param confirm     是否同时完成人工确认（全部目标无待复核项时才允许）
     */
    public RecognitionTask review(String taskId, String summaryText, List<String> vectorTags,
                                  Map<Integer, String> corrections, boolean confirm, String operator) {
        RecognitionTask task = requireTask(taskId);
        if (task.getStatus() != VisionTaskStatus.SUCCESS) {
            throw new BizException(ErrorCode.PARAM_ERROR, "仅处理成功的任务可复核，当前：" + task.getStatusLabel());
        }
        StringBuilder note = new StringBuilder();
        if (summaryText != null) {
            task.setSummaryText(summaryText);
            note.append("总结文本更新；");
        }
        if (vectorTags != null) {
            task.setVectorTags(new ArrayList<>(vectorTags));
            note.append("标签文本更新；");
        }
        if (corrections != null && !corrections.isEmpty()) {
            for (ObjectAttribute obj : task.getObjects()) {
                String text = corrections.get(obj.getIndex());
                if (text != null) {
                    obj.setCorrectedText(text);
                    obj.setCorrectedBy(operator);
                    note.append("#").append(obj.getIndex()).append(" 修正；");
                }
            }
        }
        if (confirm) {
            boolean pendingReview = task.getObjects() != null
                    && task.getObjects().stream().anyMatch(ObjectAttribute::isNeedReview)
                    && task.getObjects().stream()
                            .filter(ObjectAttribute::isNeedReview)
                            .anyMatch(o -> o.getCorrectedText() == null);
            if (pendingReview) {
                throw new BizException(ErrorCode.PARAM_ERROR,
                        "存在未修正的低置信目标，不能确认（请先修正或逐项确认）");
            }
            task.setConfirmed(true);
            task.setReviewStatus("REVIEWED");
            note.append("人工确认完成；");
        }
        if (!note.isEmpty()) {
            bumpVersion(task, operator, note.toString());
        }
        task.setUpdatedAt(Instant.now());
        store.saveRecognition(task);
        return task;
    }

    // ------------------------------------------------------------------
    // 4. 版本记录 + 5. 导出
    // ------------------------------------------------------------------

    /** 版本 +1 并追加变更记录（版本号 / 操作时间 / 操作人 / 摘要）。 */
    private void bumpVersion(RecognitionTask task, String operator, String note) {
        task.setVersion(task.getVersion() + 1);
        task.getVersionHistory().add(versionEntry(task.getVersion(), operator, note));
    }

    private static Map<String, Object> versionEntry(int version, String operator, String note) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("version", version);
        entry.put("at", Instant.now().toString());
        entry.put("operator", operator);
        entry.put("note", note);
        return entry;
    }

    /** 识别结果导出（含任务、对象属性、向量标签与版本历史）。 */
    public Map<String, Object> exportResult(String taskId) {
        RecognitionTask task = requireTask(taskId);
        if (task.getStatus() != VisionTaskStatus.SUCCESS) {
            throw new BizException(ErrorCode.PARAM_ERROR, "仅处理成功的任务可导出识别结果");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("task", task);
        out.put("exportedAt", Instant.now().toString());
        out.put("format", "cais-recognition-result/v1");
        return out;
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    public RecognitionTask transit(RecognitionTask task, VisionTaskStatus target) {
        try {
            task.getStatus().transitTo(target);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.PARAM_ERROR, e.getMessage());
        }
        task.setStatus(target);
        task.setStatusLabel(target.label());
        task.setUpdatedAt(Instant.now());
        store.saveRecognition(task);
        return task;
    }

    private RecognitionTask requireTask(String taskId) {
        return store.findRecognition(taskId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "识别任务不存在：" + taskId));
    }

    private static String genId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
