# ABI Compare — 跨发行版 ABI 影响比较服务

库维护者从不同平台（Linux/Windows/macOS）与架构（aarch64/amd64/arm64）的构建流水线
导出符号、调用约定、版本标记与类型布局 JSON。本服务在本地比较两个发行版之间的
ABI 影响：**不解析真实 ELF / Mach-O / PE**，只消费提取器（extractor）产出的中间模型。

- Spring Boot 3.5 + Java 17+，SQLite 持久化（`sqlite-jdbc`，无外部数据库）。
- 浏览器三页：快照与比较首页、差异页面、符号引用图页面（原生 SVG，无 CDN 依赖）。
- 快照按内容哈希去重；比较发布是原子且幂等的操作；并发决议基于比较版本做乐观并发，
  冲突返回符号级明细。

## 构建与演示

```bash
# 只打包（跳过测试）
mvn -q -DskipTests package

# 完整流程：跑测试，然后在 5347 端口演示
mvn -q test && mvn -q spring-boot:run -Dspring-boot.run.arguments=--server.port=5347
```

打开 <http://127.0.0.1:5347>。首次启动会自动导入 `src/main/resources/fixtures/` 下的
4 份提取器快照并预建 4 组比较（Linux v1/v2 规则、Linux 私有边界、Windows 规则），
另外种入一条 **已过期例外** 与一条 **按平台的不同结论**。

SQLite 文件默认是工作目录下的 `abi-compare.db`，可用 `ABI_DB=/path/to.db` 覆盖。

## 中间模型（extractor JSON）

顶层 `Snapshot`：

| 字段 | 含义 |
| --- | --- |
| `schemaVersion` | 模型版本（当前 `1`） |
| `component` / `release` | 组件与发行版（语义化版本字符串，按数字点号比较） |
| `platform` / `arch` | 平台与架构，规则集据此选择 |
| `extractor` | `{name, version}`，保留提取器来源（如 `readelf-abi 2.1`、`dumpbin-abi 14.3`） |
| `symbols[]` | 导出/导入符号 |
| `types[]` | 结构体/联合体/typedef 布局 |
| `contentHash` | 服务端计算的规范 SHA-256，导入时忽略请求中携带的值 |

`symbols[]`（`AbiSymbol`）：`stableId`（跨平台逻辑 ID，**唯一**可作为对应证据）、
`name`（平台原名/修饰名）、`kind`（FUNCTION/DATA）、`linkage`（EXPORTED/IMPORTED）、
`visibility`（PUBLIC/HIDDEN/PROTECTED）、`callingConvention`（aarch64-aapcs/ms/stdcall/…）、
`variadic`（可变参数显式状态）、`returnType`、`parameters[]`、`versionTag`（版本映射/标记）、
`aliases[]`（弱别名、symver、PE 转发名等平台别名）、`references[]`（引用到的 stableId）。

`types[]`（`AbiType`）：`stableId`、`kind`、`name`、`size`、`alignment`、
`tailPadding`（尾部填充字节，缺省时按最后一个字段末端推算）、`zeroSized`（零大小类型显式状态）、
`fields[]`。字段（`Field`）含 `name`、`type`、`offset`、`size`，位域显式给出
`bitOffset`/`bitWidth`，另有 `privateField` 与 `reservedPadding` 两个边界标记。

### 对应关系：只认 stable_id

- 两个符号/类型只有在 `stableId` 相等时才配对；名字不同记为 `SYMBOL_RENAMED`，
  证据字段写明 `stable_id=...`。
- **没有证据时绝不按编辑距离猜改名**。配对不上的近似名字只会分别成为
  `SYMBOL_REMOVED` 与 `SYMBOL_ADDED`（测试 `SymbolDiffTest.nameSimilarityWithoutStableIdIsNeverARename`）。
- 不同平台的修饰名（如 `widget_init` 与 `?widget_init@@YA...Z`）通过同一 stableId 对应。

## 判定规则与边界

差异引擎发出的事件（`ChangeKind`）涵盖：

- 符号：新增、删除、改名、别名增删、可见性、导出/导入 linkage、调用约定、
  可变参数、参数个数/类型、返回类型、版本标记。
- 类型：`sizeof`、对齐、尾部填充、字段偏移、字段类型、位域、零大小状态，以及三种被
  **刻意区分** 的字段插入：
  - `FIELD_APPENDED_PRIVATE_TAIL`：尾部新增 **私有** 字段，`sizeof` 增长但不移动任何成员；
  - `PADDING_REUSED`：新字段的字节全部落在旧布局中未被真实成员占用的保留/内部填充上，
    没有真实成员被挤走（保留填充标记本身可以顺移）；
  - `FIELD_INSERTED_MIDDLE`：新字段占用了旧布局中真实成员的字节，等于中间插入。
- 零大小类型、位域、可变参数、调用约定都必须有显式状态变化才会产出事件。

规则集（`RuleRegistry`）按 **平台 × 架构 × 公开/私有边界** 区分，给每种 change kind
映射一个 `Severity`（BREAKING/WARNING/COMPATIBLE/INFO）。内置：

| 规则集 ID | 版本 | 特点 |
| --- | --- | --- |
| `linux-aarch64-public` | 1.0 | 默认基线 |
| `linux-aarch64-public-v2` | 2.0 | 规则演进：`PADDING_REUSED` / `TAIL_PADDING_CHANGED` 升级为 BREAKING |
| `linux-aarch64-private` | 1.0 | 私有边界：尾部私有字段与 sizeof 变化容忍 |
| `windows-amd64-public` | 1.0 | 公共结构 sizeof 增长即 BREAKING；调用约定敏感 |
| `darwin-arm64-public` | 1.0 | macOS/arm64 基线 |

**同一快照对在不同规则集下的结果分别持久化、可并存且可重现**：比较行的唯一键是
`(old_snapshot_hash, new_snapshot_hash, rule_set_id)`，diff JSON 内也固化了
`ruleSetId/ruleSetVersion`。

## 例外（决议）生命周期

决议（`decisions` 表）与原始快照（`snapshots` 表）严格分开存储。

- `verdict`：`ACCEPT`（批准例外）或 `REJECT`（确认破坏）。
- `platform`：`*` 表示全局，也可以填具体平台；因此 **两个平台可以保留不同结论**
  （全局/平台行之间仍按符号级冲突处理）。
- 有效范围：`effectiveFrom` 起生效，`expiresAfterVersion` 到期；按新发行版版本计算：
  - 版本 < `effectiveFrom` → `PENDING`
  - 版本 > `expiresAfterVersion` → `EXPIRED`，不再标注到变化上
  - 其间 → `ACTIVE`
- **规则变更不会自动延长旧例外**：到期判断只看发行版版本；即使后来在 v2 规则集下
  重新比较，1.x 批的例外照样过期。
- 过期例外不会抑制变化（diff 中变化仍在，只是展示 `EXPIRED` 状态）。

### 并发决议

每个比较有单调递增的 `version`。提交决议必须带所审阅的 `baseVersion`：

- 若同一 `(comparison, stableId, changeKind)` 已存在 `base_version >= 客户端base`
  且平台范围重叠（`*` 与任意平台互相冲突）的新决议 → HTTP 409
  `DECISION_CONFLICT`，body 返回 `currentVersion` 与符号级冲突列表；
- 通过后比较行 `version + 1`（条件更新兜底），保证原子性。

比较发布本身运行在 `SERIALIZABLE` 事务中，命中唯一约束时返回既有结果，因此是
**原子 + 幂等** 的。

## 引用图与受影响入口

`ImpactAnalyzer` 合并两个快照的 `references[]`（删除的符号也保留边），并加上
“函数参数/返回值 → 类型”的使用边。从每个发生变化的符号/类型做反向遍历，所有可达的
**公开 EXPORTED 入口**（PUBLIC/PROTECTED）都列入 `affectedPublicEntries`，并携带
具体影响路径（例如 `type:Config ← config_validate ← widget_init`）。引用图页面用
力导向 SVG 渲染，受影响路径以橙色高亮。

## REST API

| 方法与路径 | 说明 |
| --- | --- |
| `GET  /api/rules` | 规则集列表 |
| `GET  /api/snapshots` · `GET /api/snapshots/{hash}` | 列出/读取快照 |
| `POST /api/snapshots` | 上传提取器 JSON（内容哈希去重，返回 `{contentHash, deduped}`） |
| `POST /api/comparisons` | body `{oldSnapshotHash,newSnapshotHash,ruleSetId}`，原子幂等比较 |
| `GET  /api/comparisons` · `GET /api/comparisons/{id}` | 列表/完整差异（含决议视图） |
| `POST /api/comparisons/{id}/decisions` | 提交决议；冲突返回 409 符号级明细 |

## 持久化模型（SQLite）

- `snapshots(content_hash PRIMARY KEY, component, release, platform, arch,
  extractor_name, extractor_version, payload, imported_at)` — 内容哈希去重。
- `comparisons(id PRIMARY KEY, old/new_snapshot_hash, rule_set_id, rule_set_version,
  diff_payload, version, …, UNIQUE(old_snapshot_hash,new_snapshot_hash,rule_set_id))`。
- `decisions(id, comparison_id, stable_id, change_kind, platform, verdict, rationale,
  effective_from, expires_after_version, base_version, decided_at,
  UNIQUE(comparison_id,stable_id,change_kind,platform))`。

## 测试

`mvn test` 共 17 个用例，分组覆盖：

- `TypeLayoutTest`：中间插入 vs 复用保留填充 vs 尾部私有/公有追加、对齐与尾部填充分离、
  位域与零大小类型；
- `SymbolDiffTest`：stable_id 改名证据、禁止编辑距离改名、调用约定/变参、别名、参数；
- `RuleSetTest`：同一快照对在不同规则集下的并存结论与规则版本演进；
- `ImpactGraphTest`：沿引用图传播到公开入口；
- `ContentHashTest`：内容哈希忽略已存哈希、内容变化哈希变化；
- `PersistenceIntegrationTest`：Spring Boot + 临时 SQLite，验证去重、原子幂等比较、
  过期例外不抑制变化、按平台并存结论、陈旧 base 返回符号级 409 冲突。
