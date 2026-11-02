/**
 * 文本解码与文件容器识别。
 *
 * 背景（客户实测问题）：
 *  - 中文 Windows 上 Excel「另存为 CSV」默认是 GB18030/GBK 编码，按 UTF-8 读会得到一片乱码；
 *  - 把 xlsx 直接改名成 .csv（Excel 里另存为时选错）时，内容是 ZIP 二进制，
 *    按文本读会出现「PK…xl/_rels/workbook.xml.rels」这种乱码。
 * 因此读文本前先按字节判断真实类型，再按 BOM / UTF-8 合法性选择编码。
 */

export type ContainerKind = 'pdf' | 'zip' | 'ole' | 'text'
export type TextEncoding = 'utf-8' | 'utf-16le' | 'utf-16be' | 'gb18030'

export interface DecodedText {
  text: string
  encoding: TextEncoding
  /** 解不出来（大量控制字符 / 替换字符）时置 true，调用方应提示“不是文本文件” */
  binary: boolean
}

/** 读取文件头部（只读前 64KB，避免大文件整体入内存） */
export async function readHead(file: Blob, bytes = 65536): Promise<Uint8Array> {
  return new Uint8Array(await file.slice(0, bytes).arrayBuffer())
}

function startsWith(head: Uint8Array, bytes: number[]): boolean {
  if (head.length < bytes.length) return false
  return bytes.every((value, index) => head[index] === value)
}

const utf8 = new TextDecoder('utf-8')
const utf8Strict = new TextDecoder('utf-8', { fatal: true })
const gb18030 = new TextDecoder('gb18030')
const utf16le = new TextDecoder('utf-16le')
const utf16be = new TextDecoder('utf-16be')

/** 按文件头判断真实容器类型（与扩展名无关） */
export function sniffContainer(head: Uint8Array): ContainerKind {
  if (startsWith(head, [0x25, 0x50, 0x44, 0x46])) return 'pdf' // %PDF
  if (head.length >= 4 && head[0] === 0x50 && head[1] === 0x4b
    && ((head[2] === 0x03 && head[3] === 0x04) || (head[2] === 0x05 && head[3] === 0x06) || (head[2] === 0x07 && head[3] === 0x08))) {
    return 'zip'
  }
  if (startsWith(head, [0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1])) return 'ole' // 旧版 Office 复合文档
  return 'text'
}

/** ZIP 容器里再判断是 Excel / Word / PowerPoint（扫描本地文件头即可，无需解压） */
export function sniffOfficeZip(head: Uint8Array): 'xlsx' | 'docx' | 'pptx' | null {
  let text = ''
  for (let index = 0; index < head.length; index += 1) text += String.fromCharCode(head[index])
  if (text.includes('xl/workbook.xml') || text.includes('xl/worksheets/')) return 'xlsx'
  if (text.includes('word/document.xml')) return 'docx'
  if (text.includes('ppt/presentation.xml')) return 'pptx'
  return null
}

/** BOM 优先，其次 UTF-8 严格解码，失败后回退 GB18030（覆盖 GBK/GB2312）；无 BOM 的 UTF-16 按零字节密度识别。 */
export function detectEncoding(buffer: ArrayBuffer): TextEncoding {
  const bytes = new Uint8Array(buffer)
  if (bytes.length >= 3 && bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf) return 'utf-8'
  if (bytes.length >= 2 && bytes[0] === 0xff && bytes[1] === 0xfe) return 'utf-16le'
  if (bytes.length >= 2 && bytes[0] === 0xfe && bytes[1] === 0xff) return 'utf-16be'
  try {
    utf8Strict.decode(bytes)
    return 'utf-8'
  } catch {
    /* 不是合法 UTF-8，继续判断 */
  }
  const sample = bytes.subarray(0, Math.min(bytes.length, 4096))
  let oddZero = 0
  let evenZero = 0
  for (let index = 0; index < sample.length; index += 1) {
    if (sample[index] === 0) {
      if (index % 2 === 0) evenZero += 1
      else oddZero += 1
    }
  }
  const half = Math.max(1, Math.floor(sample.length / 2))
  if (oddZero > half * 0.4 && evenZero < half * 0.1) return 'utf-16le'
  if (evenZero > half * 0.4 && oddZero < half * 0.1) return 'utf-16be'
  return 'gb18030'
}

/** 清掉 BOM、NUL 与控制字符，统一换行 */
export function cleanText(raw: string): string {
  return raw
    .replace(/^\uFEFF/, '')
    .replace(/\u0000/g, '')
    .replace(/\r\n?/g, '\n')
    .replace(/[\u0001-\u0008\u000B\u000C\u000E-\u001F\u007F]/g, '')
    .trim()
}

/** 解码文本文件；binary=true 表示内容并不是文本（例如压缩包、可执行文件） */
export function decodeTextBuffer(buffer: ArrayBuffer): DecodedText {
  const bytes = new Uint8Array(buffer)
  const encoding = detectEncoding(buffer)
  const text = cleanText(decodeBytes(bytes, encoding))
  /* 判定用原始字节：文本文件几乎没有控制字节，二进制文件占比很高（压缩包、图片、可执行文件） */
  const sample = bytes.subarray(0, Math.min(bytes.length, 65536))
  let control = 0
  for (const byte of sample) {
    if (byte === 0x09 || byte === 0x0a || byte === 0x0d) continue
    if (byte < 0x20) control += 1
  }
  const replacement = (text.match(/\uFFFD/g) || []).length / Math.max(1, text.length)
  return {
    text,
    encoding,
    binary: control / Math.max(1, sample.length) > 0.02 || replacement > 0.02,
  }
}

function decodeBytes(bytes: Uint8Array, encoding: TextEncoding): string {
  const decoder = encoding === 'utf-8' ? utf8 : encoding === 'utf-16le' ? utf16le : encoding === 'utf-16be' ? utf16be : gb18030
  return decoder.decode(bytes)
}
