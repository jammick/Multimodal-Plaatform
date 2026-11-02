/* =============================================================================
   core/videoMetaRules.ts — 视频文件元数据的纯规则（可在 Node 单测里直接跑）
   -----------------------------------------------------------------------------
   与 core/videoMeta.ts 的分工：这里只放不依赖网络/浏览器的最小纯函数
   （字段定义、归一化、缺失判定、时间格式校验），读写接口在 videoMeta.ts。
   口径：缺字段就是缺，不补默认值；缺失项以中文标签返回。
   ========================================================================== */

export interface VideoMetaInput {
  /** 视频标题 */
  title: string
  /** 拍摄时间（YYYY-MM-DD HH:mm，选取自日期时间选择器，保留用户选择的本地时间，不做时区换算） */
  shotAt: string
  /** 设备编号 */
  deviceNo: string
}

/** 校验项顺序即提示顺序，与流程配置里的「必填元数据」选项一致。 */
export const VIDEO_META_FIELDS: Array<{ key: keyof VideoMetaInput; label: string }> = [
  { key: 'title', label: '视频标题' },
  { key: 'shotAt', label: '拍摄时间' },
  { key: 'deviceNo', label: '设备编号' },
]

const text = (value: unknown): string => (value === null || value === undefined ? '' : String(value).trim())

/**
 * 只保留三个字段并去空白。
 * 空字符串保持为空——「未填」不能变成「已填」，否则元数据完整性校验会变成摆设。
 */
export function normalizeVideoMeta(
  input: Record<string, unknown> | Partial<VideoMetaInput> | null | undefined,
): VideoMetaInput {
  const raw = (input || {}) as Record<string, unknown>
  return { title: text(raw.title), shotAt: text(raw.shotAt), deviceNo: text(raw.deviceNo) }
}

/** 缺失字段的中文标签（如 ['视频标题', '设备编号']），全部齐全时为空数组。 */
export function missingVideoMeta(
  input: Record<string, unknown> | Partial<VideoMetaInput> | null | undefined,
): string[] {
  const meta = normalizeVideoMeta(input)
  return VIDEO_META_FIELDS.filter((field) => !meta[field.key]).map((field) => field.label)
}

/** 三项是否齐全。 */
export function hasVideoMeta(
  input: Record<string, unknown> | Partial<VideoMetaInput> | null | undefined,
): boolean {
  return missingVideoMeta(input).length === 0
}

/** 拍摄时间格式：新录入统一为 YYYY-MM-DD HH:mm；兼容早期含秒（:ss）与仅日期的历史记录。空值视为未填。 */
export function validShotAtFormat(value: unknown): boolean {
  const raw = String(value || '').trim()
  if (!raw) return true
  if (!/^\d{4}-\d{2}-\d{2}([ T]\d{2}:\d{2}(:\d{2})?)?$/.test(raw)) return false
  return !Number.isNaN(Date.parse(raw.replace(' ', 'T')))
}
