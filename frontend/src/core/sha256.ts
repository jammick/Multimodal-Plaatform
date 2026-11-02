/* =============================================================================
   core/sha256.ts — 增量 SHA-256（支持分块流式计算）
   -----------------------------------------------------------------------------
   为什么不用 crypto.subtle.digest：Web Crypto 只接受整块 ArrayBuffer，超大
   文件（视频 GB 级）会把整个文件读进内存导致页面崩溃。本实现按 512 位分块
   增量压缩，配合 Blob.slice 分块读取，内存峰值 = 单块大小。
   输出与标准 SHA-256 一致（十六进制小写 64 位，契约统一口径）。
   ========================================================================== */

const K = new Uint32Array([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
])

const rotr = (x: number, n: number): number => ((x >>> n) | (x << (32 - n))) >>> 0

export class Sha256 {
  private h = new Uint32Array([
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
  ])
  private buf = new Uint8Array(64)
  private bufLen = 0
  private total = 0

  update(data: Uint8Array): void {
    this.total += data.length
    let off = 0
    if (this.bufLen > 0) {
      const take = Math.min(64 - this.bufLen, data.length)
      this.buf.set(data.subarray(0, take), this.bufLen)
      this.bufLen += take
      off = take
      if (this.bufLen === 64) {
        this.compress(this.buf)
        this.bufLen = 0
      }
    }
    while (off + 64 <= data.length) {
      this.compress(data.subarray(off, off + 64))
      off += 64
    }
    if (off < data.length) {
      this.buf.set(data.subarray(off), 0)
      this.bufLen = data.length - off
    }
  }

  private compress(chunk: Uint8Array): void {
    const w = new Uint32Array(64)
    for (let i = 0; i < 16; i++) {
      w[i] = ((chunk[i * 4] << 24) | (chunk[i * 4 + 1] << 16) | (chunk[i * 4 + 2] << 8) | chunk[i * 4 + 3]) >>> 0
    }
    for (let i = 16; i < 64; i++) {
      const x = w[i - 15]
      const y = w[i - 2]
      const s0 = (rotr(x, 7) ^ rotr(x, 18) ^ (x >>> 3)) >>> 0
      const s1 = (rotr(y, 17) ^ rotr(y, 19) ^ (y >>> 10)) >>> 0
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) >>> 0
    }
    let a = this.h[0]
    let b = this.h[1]
    let c = this.h[2]
    let d = this.h[3]
    let e = this.h[4]
    let f = this.h[5]
    let g = this.h[6]
    let hh = this.h[7]
    for (let i = 0; i < 64; i++) {
      const S1 = (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)) >>> 0
      const ch = ((e & f) ^ (~e & g)) >>> 0
      const t1 = (hh + S1 + ch + K[i] + w[i]) >>> 0
      const S0 = (rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)) >>> 0
      const maj = ((a & b) ^ (a & c) ^ (b & c)) >>> 0
      const t2 = (S0 + maj) >>> 0
      hh = g
      g = f
      f = e
      e = (d + t1) >>> 0
      d = c
      c = b
      b = a
      a = (t1 + t2) >>> 0
    }
    this.h[0] = (this.h[0] + a) >>> 0
    this.h[1] = (this.h[1] + b) >>> 0
    this.h[2] = (this.h[2] + c) >>> 0
    this.h[3] = (this.h[3] + d) >>> 0
    this.h[4] = (this.h[4] + e) >>> 0
    this.h[5] = (this.h[5] + f) >>> 0
    this.h[6] = (this.h[6] + g) >>> 0
    this.h[7] = (this.h[7] + hh) >>> 0
  }

  hex(): string {
    const bitLen = this.total * 8
    const tailLen = this.bufLen < 56 ? 56 - this.bufLen : 120 - this.bufLen
    const tail = new Uint8Array(tailLen + 8)
    tail[0] = 0x80
    const hi = Math.floor(bitLen / 0x100000000)
    const lo = bitLen >>> 0
    tail[tailLen] = (hi >>> 24) & 0xff
    tail[tailLen + 1] = (hi >>> 16) & 0xff
    tail[tailLen + 2] = (hi >>> 8) & 0xff
    tail[tailLen + 3] = hi & 0xff
    tail[tailLen + 4] = (lo >>> 24) & 0xff
    tail[tailLen + 5] = (lo >>> 16) & 0xff
    tail[tailLen + 6] = (lo >>> 8) & 0xff
    tail[tailLen + 7] = lo & 0xff

    let off = 0
    if (this.bufLen > 0) {
      const take = Math.min(64 - this.bufLen, tail.length)
      this.buf.set(tail.subarray(0, take), this.bufLen)
      this.bufLen += take
      off = take
      if (this.bufLen === 64) {
        this.compress(this.buf)
        this.bufLen = 0
      }
    }
    while (off + 64 <= tail.length) {
      this.compress(tail.subarray(off, off + 64))
      off += 64
    }
    let out = ''
    for (let i = 0; i < 8; i++) out += this.h[i].toString(16).padStart(8, '0')
    return out
  }
}
