# Multimodal-Plaatform

多模态数据处理的核心组件集合，覆盖文件识别与解码、分块哈希、结果展示、任务幂等和瞬时故障重试。仓库提供 Java 与 TypeScript 源码、测试用例以及文件校验清单，适合按需集成到图片、视频、文档和向量数据处理流程中。

> 当前仓库提供可复用组件，不包含可独立启动的 Web 页面、完整后端服务、数据库、模型权重或部署镜像。

## 目录

- [功能概览](#功能概览)
- [项目结构](#项目结构)
- [环境要求](#环境要求)
- [快速使用](#快速使用)
- [测试与校验](#测试与校验)
- [集成边界](#集成边界)

## 功能概览

### 文件接入与内容处理

| 功能 | 作用 | 主要代码 |
| --- | --- | --- |
| 分块 SHA-256 | 对大型图片、视频或文档按块计算哈希，避免一次读入整个文件 | `frontend/src/core/sha256.ts` |
| 文件容器识别 | 根据文件头区分 PDF、ZIP、旧版 Office 容器和普通文本；可进一步识别常见 Office ZIP 类型 | `frontend/src/core/textDecode.ts` |
| 中文文本解码 | 识别 UTF-8、UTF-16、GB18030，返回清理后的文本及二进制判定 | `frontend/src/core/textDecode.ts` |
| CSV 生成 | 转义逗号、引号和换行，并提供时间列的文本导出格式 | `frontend/src/core/csv.ts` |

### 多模态结果展示

| 功能 | 作用 | 主要代码 |
| --- | --- | --- |
| 目标类别归一 | 将常见英文检测类别映射为中文、去除轨迹编号，并与规则中的目标词匹配 | `backend/cais-orchestrator/src/main/java/com/cais/orchestrator/support/LabelNames.java` |
| 检测结果中文显示 | 转换已知 COCO 类别及结果文本中的英文标签；未知标签保留原文 | `frontend/src/core/objectLabels.ts` |
| 内容总结清理 | 从普通文本、代码块、嵌套或截断的 `summary` JSON 中提取正文 | `frontend/src/core/summaryText.ts` |
| 时间本地化 | 将 UTC 时间字符串按运行环境的本地时区格式化展示 | `frontend/src/core/localTime.ts` |

### 任务可靠性

| 功能 | 作用 | 主要代码 |
| --- | --- | --- |
| 幂等键生成 | 根据文件哈希、引擎类型和规范化参数生成 SHA-256 任务键 | `backend/cais-common/src/main/java/com/cais/common/idempotency/IdempotencyKeyBuilder.java` |
| 后端有限重试 | 仅对明确标记为可恢复的外部依赖异常按策略重试 | `backend/cais-common/src/main/java/com/cais/common/integration/` |
| 前端瞬时错误重试 | 识别部分网络或服务端瞬时错误，按次数和间隔重试异步操作 | `frontend/src/core/retry.ts` |

这些组件支持典型处理链路：接入文件时识别真实类型并计算哈希；提交任务时生成幂等键；外部调用短暂失败时受控重试；结果返回后统一展示时间、目标类别和内容总结。业务编排与持久化由集成方实现。

## 项目结构

```text
.
├── backend/
│   ├── cais-common/src/main/java/com/cais/common/
│   │   ├── idempotency/           # 参数规范化与任务幂等键
│   │   └── integration/           # 外部依赖重试策略和执行器
│   └── cais-orchestrator/src/main/java/com/cais/orchestrator/support/
│       └── LabelNames.java        # 检测标签归一与规则匹配
├── frontend/
│   ├── src/core/                  # 文件、文本、展示与重试工具
│   └── tests/                     # Node.js 测试
├── manifest.json                  # 源码及测试文件的 SHA-256 清单
└── 核心功能索引.md                  # 功能入口与拆分建议
```

Java 文件保留各自的包名；TypeScript 文件使用命名导出。当前目录没有 Maven `pom.xml` 或 npm `package.json`，可以将所需文件接入现有工程，或按下方命令单独验证。

## 环境要求

- **Java：** JDK 17 或更高版本。
- **前端运行：** 支持 `Uint8Array`、`Blob` 和 `TextDecoder` 的现代浏览器。GB18030 解码还需要运行环境提供相应的 `TextDecoder` 编码支持。
- **前端测试：** 支持 `--experimental-strip-types` 的 Node.js 22；测试使用内置的 `node:test`，无需安装 npm 依赖。

## 快速使用

下列示例展示公开接口；集成到工程后请按实际目录调整导入路径。

### 1. 识别文件并读取文本

```ts
import { readHead, sniffContainer, decodeTextBuffer } from './frontend/src/core/textDecode.ts'

async function readTextFile(file: File): Promise<string> {
  const head = await readHead(file)
  if (sniffContainer(head) !== 'text') throw new Error('请选择文本文件')

  const result = decodeTextBuffer(await file.arrayBuffer())
  if (result.binary) throw new Error('文件内容不是可读文本')
  return result.text
}
```

`readHead` 默认只读取前 64 KB；`decodeTextBuffer` 需要完整的 `ArrayBuffer`。对较大的文件，应先限制大小或使用适合业务的流式读取方案。Office ZIP 类型识别只扫描已读取的文件头，不能代替完整格式校验。

### 2. 分块计算文件哈希

```ts
import { Sha256 } from './frontend/src/core/sha256.ts'

async function hashFile(file: File): Promise<string> {
  const sha = new Sha256()
  const chunkSize = 4 * 1024 * 1024
  for (let offset = 0; offset < file.size; offset += chunkSize) {
    const chunk = new Uint8Array(
      await file.slice(offset, offset + chunkSize).arrayBuffer(),
    )
    sha.update(chunk)
  }
  return sha.hex()
}
```

每个 `Sha256` 实例用于一次计算；全部数据传入后调用一次 `hex()`，得到 64 位小写十六进制摘要。

### 3. 生成任务幂等键

```java
import com.cais.common.idempotency.IdempotencyKeyBuilder;
import java.util.Map;

String key = IdempotencyKeyBuilder.build(
    fileSha256,
    "VISION",
    Map.of("model", "detector-v1", "threshold", 0.6)
);
```

参数 Map 会按键排序，嵌套 Map/List 递归规范化。任务去重仍需业务存储层的唯一约束或查询逻辑；生成键本身不会保存任务。

### 4. 对可恢复故障进行有限重试

```java
import com.cais.common.integration.IntegrationRetryExecutor;
import com.cais.common.integration.IntegrationRetryPolicy;

String result = IntegrationRetryExecutor.execute(
    "model-service",
    new IntegrationRetryPolicy(3, 200),
    () -> callModelService()
);
```

只有调用方抛出 `RetryableIntegrationException` 才会重试；参数错误、权限错误等不应包装为可重试异常。前端的 `withRetry` 也只适用于可安全重复执行的请求：

```ts
import { withRetry } from './frontend/src/core/retry.ts'

const result = await withRetry(() => submitIdempotentTask(), {
  attempts: 2,
  delayMs: 1000,
})
```

### 5. 统一展示分析结果

```ts
import { objectLabelZh } from './frontend/src/core/objectLabels.ts'
import { cleanSummaryText } from './frontend/src/core/summaryText.ts'
import { localDateTime } from './frontend/src/core/localTime.ts'

const label = objectLabelZh('cell phone') // 手机
const summary = cleanSummaryText('{"summary":"画面中有一部手机"}')
const createdAt = localDateTime('2026-11-02T05:00:00Z')
```

`createdAt` 的具体显示时间取决于本地时区。后端规则匹配可使用 `LabelNames.normalize` 与 `LabelNames.matches`。

## 测试与校验

在仓库根目录运行前端测试：

```powershell
node --experimental-strip-types --test frontend/tests/*.test.mjs
```

在 PowerShell 中编译 Java 文件：

```powershell
$files = Get-ChildItem .\backend -Filter '*.java' -Recurse -File | ForEach-Object FullName
javac -encoding UTF-8 -d .\.verify-java $files
```

`.verify-java/` 已被 Git 忽略。当前包含 5 个前端测试文件，覆盖 CSV、时间显示、文本解码、总结清理和瞬时错误重试。分块 SHA-256 可用标准测试向量 `abc` 校验，期望摘要为 `ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad`。

`manifest.json` 列出了 17 个源码和测试文件的 SHA-256，可用于检查文件是否变化。文档不在该清单内。

## 集成边界

- 本仓库提供工具类与函数，不提供数据库持久化、对象存储、模型推理或任务调度服务。
- 类别翻译以已知 COCO 标签为主；未知自定义标签保留原文。规则匹配采用包含关系，接入前应结合领域标签检查误命中。
- 文本解码会返回 `binary` 标记，调用方应根据标记拒绝非文本输入；文件头识别不能代替安全扫描。
- CSV 时间单元格采用表格软件的文本公式格式；若下游不是 Excel/WPS，应确认其公式处理方式。
- 重试和幂等键需配合服务端幂等实现使用，避免对不可重放操作造成重复处理。

更多功能入口与拆分建议见 [核心功能索引](核心功能索引.md)。
