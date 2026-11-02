# CAIS 核心功能与可复用代码提取

来源：CAIS 主仓库提交 `67bc110c5e44f4fddb5602e6e38607805e76a52c`（2026-11-02 本地快照）。`manifest.json` 记录逐文件 SHA-256；所有代码和现有测试均为逐字节复制。CAIS 主仓库源码、模型适配器及其接口均未修改。

## 可直接复用的代码

| 能力 | 文件 | 运行条件与使用边界 |
| --- | --- | --- |
| 文件处理任务幂等键 | `backend/cais-common/src/main/java/com/cais/common/idempotency/IdempotencyKeyBuilder.java` | JDK 17；按文件 SHA-256、引擎类型、参数生成键。调用方仍需在存储层实现唯一约束和重复请求处理。 |
| 外部依赖有限重试 | `backend/cais-common/src/main/java/com/cais/common/integration/`（3 文件） | JDK 17；只有抛出 `RetryableIntegrationException` 才重试。调用方须确认操作可安全重放。 |
| 英文检测类别归一及匹配 | `backend/cais-orchestrator/src/main/java/com/cais/orchestrator/support/LabelNames.java` | JDK 17；COCO 英文标签转中文、去轨迹编号、与规则词匹配。采用包含匹配，使用前应检查具体规则误命中风险。 |
| 浏览器分块 SHA-256 | `frontend/src/core/sha256.ts` | TypeScript、`Uint8Array`；适合大文件分块处理，调用方负责读取 Blob 的各块。 |
| 文件头识别与中文文本解码 | `frontend/src/core/textDecode.ts` | 浏览器 `Blob` / `TextDecoder`；识别 PDF、ZIP/Office、OLE 与 UTF-8、UTF-16、GB18030。ZIP 内部识别仅扫描已读文件头，不是完整解压校验。 |
| CSV 单元格与时间导出 | `frontend/src/core/csv.ts` | TypeScript；时间字段使用 Excel/WPS 文本公式写法，导入到其他 CSV 消费端前应确认其公式语义。 |
| UTC 转本地展示 | `frontend/src/core/localTime.ts` | TypeScript；依赖运行环境本地时区。 |
| 瞬时错误重试 | `frontend/src/core/retry.ts` | TypeScript；只用于服务端支持幂等的请求，错误判定含状态码和消息模式。 |
| 总结文本提取 | `frontend/src/core/summaryText.ts` | TypeScript；处理模型返回的纯文本、代码块、嵌套或截断 JSON。它是展示清理函数，不是通用 JSON 解析器。 |
| 检测标签中文显示 | `frontend/src/core/objectLabels.ts` | TypeScript；仅翻译已知 COCO 类别，未知模型标签保留原文。 |

复制原有的 5 个前端测试文件位于 `frontend/tests/`。Java 类保留原包名，迁入其他工程时可改包名和引用；本提取目录的文件为源码快照，修改副本不影响主仓库。

## 快速验证

在 `frontend/` 中运行：

```powershell
node --experimental-strip-types --test tests/csv.test.mjs tests/localTime.test.mjs tests/textDecode.test.mjs tests/summaryText.test.mjs tests/retry.test.mjs
```

在本目录运行（编译输出到忽略目录）：

```powershell
$files = Get-ChildItem .\backend -Filter '*.java' -Recurse -File | ForEach-Object FullName
javac -encoding UTF-8 -d .\.verify-java $files
```

验证记录：前端原有测试 22/22 通过；5 个 Java 文件以 JDK 17 编译通过；17 个复制文件与源文件 SHA-256 一致；分块 SHA-256 对 `abc` 分两块计算得到标准摘要。标签匹配与幂等键的独立运行示例尚未作为本目录测试交付，使用时可结合主仓库测试与业务输入补充校验。

## 未作为独立组件提取的代码

图片、视频、文档与向量的完整服务涉及数据库表、模型绑定、对象存储、网关鉴权及任务状态。其入口、依赖和拆分建议见 `核心功能索引.md`。模型适配器 `algorithms/model_adapter_api.py` 本次只列索引，未复制或改动，避免产生一个难以与镜像版本对应的孤立副本。
