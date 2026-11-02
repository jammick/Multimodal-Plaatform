/** 从图片 EXIF 读取真实拍摄时间（JPEG/TIFF）；读不到返回空值，不编造。 */
export interface ExifInfo {
  capturedAt: string
}

const EMPTY: ExifInfo = { capturedAt: '' }

interface Entry {
  tag: number
  count: number
  valueField: number
}

function readEntries(view: DataView, ifdOffset: number, little: boolean): Entry[] {
  if (ifdOffset + 2 > view.byteLength) return []
  const count = view.getUint16(ifdOffset, little)
  const list: Entry[] = []
  for (let index = 0; index < count; index += 1) {
    const offset = ifdOffset + 2 + index * 12
    if (offset + 12 > view.byteLength) break
    list.push({
      tag: view.getUint16(offset, little),
      count: view.getUint32(offset + 4, little),
      valueField: offset + 8,
    })
  }
  return list
}

function ascii(view: DataView, base: number, entry: Entry, little: boolean): string {
  const size = entry.count
  if (size <= 0 || size > 512) return ''
  const start = size <= 4 ? entry.valueField : base + view.getUint32(entry.valueField, little)
  let text = ''
  for (let index = 0; index < size && start + index < view.byteLength; index += 1) {
    const code = view.getUint8(start + index)
    if (code === 0) break
    text += String.fromCharCode(code)
  }
  return text.trim()
}

function normalizeTime(raw: string): string {
  const match = /^(\d{4}):(\d{2}):(\d{2})[ T](\d{2}):(\d{2}):(\d{2})/.exec(raw)
  return match ? `${match[1]}-${match[2]}-${match[3]} ${match[4]}:${match[5]}:${match[6]}` : ''
}

function parseTiff(view: DataView, base: number): ExifInfo {
  if (base + 8 > view.byteLength) return EMPTY
  const little = view.getUint16(base) === 0x4949
  if (view.getUint16(base + 2, little) !== 42) return EMPTY
  const ifd0 = base + view.getUint32(base + 4, little)
  let capturedAt = ''
  let exifOffset = 0
  for (const entry of readEntries(view, ifd0, little)) {
    if (entry.tag === 0x8769) exifOffset = base + view.getUint32(entry.valueField, little)
  }
  if (exifOffset) {
    for (const entry of readEntries(view, exifOffset, little)) {
      if (entry.tag === 0x9003 || entry.tag === 0x9004) capturedAt = normalizeTime(ascii(view, base, entry, little))
    }
  }
  return { capturedAt }
}

/** 解析 JPEG APP1 中的 EXIF；非 JPEG 或没有 EXIF 时返回空。 */
export function readExif(buffer: ArrayBuffer): ExifInfo {
  try {
    const view = new DataView(buffer)
    if (view.byteLength < 4 || view.getUint8(0) !== 0xff || view.getUint8(1) !== 0xd8) return EMPTY
    let offset = 2
    while (offset + 4 <= view.byteLength) {
      if (view.getUint8(offset) !== 0xff) {
        offset += 1
        continue
      }
      const marker = view.getUint8(offset + 1)
      if (marker === 0xd9 || marker === 0xda || marker === 0x00) break
      const length = view.getUint16(offset + 2)
      if (length < 2) break
      if (marker === 0xe1 && offset + 10 <= view.byteLength
        && view.getUint32(offset + 4) === 0x45786966 && view.getUint16(offset + 8) === 0) {
        return parseTiff(view, offset + 10)
      }
      offset += 2 + length
    }
  } catch {
    // 损坏或非标准 EXIF 时按“未采集”处理
  }
  return EMPTY
}
