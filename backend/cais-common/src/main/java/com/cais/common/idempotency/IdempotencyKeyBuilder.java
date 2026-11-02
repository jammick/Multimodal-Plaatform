package com.cais.common.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 幂等键生成器 IdempotencyKeyBuilder
 * ----------------------------------------------------------------------------
 * 与《技术栈与架构规范》第五章约定严格一致：
 *
 *   所有引擎接口幂等：以"文件哈希 + 引擎类型 + 参数摘要"为幂等键，
 *   重复投递直接返回既有成果。
 *
 * 幂等键 = SHA-256( 文件哈希 | 引擎类型 | 参数摘要 )
 *   其中：
 *   - 文件哈希  ：cais-collect 入库时计算的文件 SHA-256（同时承担"秒传去重"职责）；
 *   - 引擎类型  ：TRANSCODE（视频转码）/ VISION（图片识别）/ DOCPARSE（文档解析）/
 *                AUDIO（音频提取）/ VECTOR（向量化），全平台统一大写；
 *   - 参数摘要  ：SHA-256( 规范化参数串 )。规范化规则（保证"参数等价 → 摘要等价"）：
 *       1) Map 按 key 字典序升序排列后拼接 "k=v"；
 *       2) 嵌套 Map/List 递归规范化（List 内元素保序，逐个规范化后以逗号连接）；
 *       3) 标量值一律 String.valueOf；
 *       4) 参数为空时摘要取空串的 SHA-256（固定值），保证公式仍成立。
 *
 * 使用场景：
 * 1. cais-orchestrator 创建任务时生成幂等键，写入任务实例；重复提交（同一文件同一
 *    引擎同一参数）返回 40901 IDEMPOTENT_CONFLICT 或直接挂接既有成果；
 * 2. 四个引擎回调时携带幂等键，orchestrator 据此将结果与任务实例精确关联，
 *    抵御 MQ 重复投递（at-least-once 语义）；
 * 3. 引擎服务自身入队前也可用同键做二级去重（harmless）。
 */
public final class IdempotencyKeyBuilder {

    /** 键组分隔符：文件哈希/引擎类型均为十六进制或字母，竖线不冲突 */
    private static final String SEPARATOR = "|";

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private IdempotencyKeyBuilder() {
        // 工具类禁止实例化
    }

    /**
     * 生成引擎任务幂等键。
     *
     * @param fileSha256 文件 SHA-256（十六进制小写，建议 cais-collect 统一归一化后再传入）
     * @param engineType 引擎类型（大写，如 TRANSCODE / VISION / DOCPARSE / AUDIO / VECTOR）
     * @param params     引擎参数（允许 null / 空 Map，等价于"无参数"）
     * @return 64 位十六进制幂等键
     */
    public static String build(String fileSha256, String engineType, Map<String, Object> params) {
        String paramsDigest = sha256Hex(normalizeParams(params));
        String raw = String.join(SEPARATOR,
                nullToEmpty(fileSha256), nullToEmpty(engineType), paramsDigest);
        return sha256Hex(raw);
    }

    /**
     * 参数规范化：Map 字典序 + 递归展开。
     * 输出形如：pageSize=20&sort=asc&topK=10（key 升序）。
     */
    public static String normalizeParams(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        // TreeMap 保证 key 字典序，序列化结果与传入顺序无关
        Map<String, Object> sorted = new TreeMap<>(params);
        StringBuilder sb = new StringBuilder(64);
        sorted.forEach((k, v) -> {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(k).append('=').append(normalizeValue(v));
        });
        return sb.toString();
    }

    /** 值的规范化：Map/List 递归，标量转字符串 */
    private static String normalizeValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), v));
            StringBuilder sb = new StringBuilder("{");
            sorted.forEach((k, v) -> sb.append(k).append(':').append(normalizeValue(v)).append(','));
            if (sb.charAt(sb.length() - 1) == ',') {
                sb.setLength(sb.length() - 1);
            }
            return sb.append('}').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (Object item : list) {
                sb.append(normalizeValue(item)).append(',');
            }
            if (sb.charAt(sb.length() - 1) == ',') {
                sb.setLength(sb.length() - 1);
            }
            return sb.append(']').toString();
        }
        return String.valueOf(value);
    }

    /** SHA-256 摘要（小写十六进制，64 字符） */
    public static String sha256Hex(String input) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // JDK 8+ 强制提供 SHA-256，理论不可达；防御性包装
            throw new IllegalStateException("当前 JRE 不支持 SHA-256", e);
        }
        byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(HEX[(b >> 4) & 0x0f]).append(HEX[b & 0x0f]);
        }
        return sb.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }
}
