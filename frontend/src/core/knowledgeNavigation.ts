export function validKnowledgeText(value: unknown): string {
  const text = String(value || '').trim()
  if (!text || /^(未返回|调用失败|识别失败|服务不可用|HTTP\s*\d{3}|讯飞.*不可用)/i.test(text)) return ''
  return text
}
