# abi-diff — 跨发行版 ABI 影响比较服务

库维护者从不同平台的构建流水线（nm/readelf、dumpbin、llvm-readobj 等）拿到**导出符号、
调用约定、版本标记与类型布局 JSON**，在本地比较两个发行版之间的 ABI 影响。本应用**不解析
真实 ELF / Mach-O / PE**；平台流水线负责抽取，应用消费统一的中间 JSON 模型。

## 构建与演示

```bash
# 安装（跳过测试快速打包）
mvn -q -DskipTests package

# 测试 + 在 5347 端口演示
mvn -q test && mvn -q spring-boot:run -Dspring-boot.run.arguments=--server.port=5347

# 浏览器访问
open http://127.0.0.1:5347
```

启动时会在 SQLite 中播种 4 个内置规则集与 3 个平台 × 2 个版本（1.0.0 → 1.1.0）的
fixture 快照。数据库默认位于 `./data/abi-snapshot.db` 与 `./data/abi-decision.db`
（可用 `--abidiff.snapshot-db` / `--abidiff.decision-db` 覆盖）。

页面包含：

- **差异比较**：按组件列出新增 / 删除 / 改名 / 布局 / 签名 / 可见性变化，逐条标注
  严重性、规则判定、有效严重性（含例外后）和沿引用图回溯出的**受影响公开入口**；顶部
  可并排查看 linux/windows/macos 的不同结论；
- **引用图**：右侧快照的符号引用图（原生 SVG 力导向布局，无外部 CDN），从变化符号到
  公开入口的受影响路径高亮；
- **快照 / 导入**：粘贴任意提取器 JSON 导入，重复内容自动哈希去重；
- **平台规则**：查看每个平台/架构的规则集及其并存修订。

## 中间模型（Extractor JSON）

根文档携带 **extractor 与平台来源**（`name`/`version`/`source`、`platform`、`arch`），
然后是组件、符号、别名和引用边。完整字段见
`src/main/java/com/example/abidiff/json/Docs.java`，示例见
`src/main/resources/fixtures/*.json`。

- 组件：`stable_id`、名称、`kind`（LIB/HEADER/MODULE/OBJECT）、`boundary`（PUBLIC/PRIVATE）。
- 符号（`stable_id` 是**显式跨平台身份**，`name` 是平台拼写）：
  - `FUNCTION`：参数列表、返回类型、`calling_convention`（C/SYSV/STDCALL/FASTCALL/AAPCS/
    VECTORCALL/...）、**显式 `variadic` 状态**；
  - `VARIABLE`：类型、大小；
  - `TYPE_RECORD`：结构体/联合，字段含 `offset`/`size`/`align`、`reserved`+`reserved_id`
    （保留槽）、**显式位域状态**（`bitfield`/`bitfield_offset_bits`/`bitfield_width_bits`）、
    `tail`（私有尾部字段），类型级带 `tail_padding`/`tail_padding_bytes`；
  - `TYPE_ENUM`：底层类型与枚举值；`TYPE_ZERO`：**零大小类型**，带显式 `zero_sized`。
  - 可见性 `DEFAULT/HIDDEN/PROTECTED/EXPORT/STATIC_LOCAL`，绑定 `GLOBAL/LOCAL/WEAK`。
- 别名 `aliases`：`EXPLICIT` / `WEAK_ALIAS` / `PLATFORM_NAME` / `SONAME_LINK`，把一个
  stable_id 关联到另一个平台名字。
- 引用边 `refs`：`CALL/PARAM/RETURN/FIELD/DERIVES/REEXPORT`，构成符号引用图。

### 身份与改名（没有证据不靠猜）

- 两个快照之间，符号首先按 **stable_id** 对应；不同平台名字不同但 stable_id 相同视为同一符号。
- 只有存在**显式别名证据**（某侧声明了旧名/新名的 `EXPLICIT`/`WEAK_ALIAS`/`PLATFORM_NAME`
  别名）时，新增+缺失才被升级为 `SYMBOL_RENAMED`，并在 finding 里记录证据来源。
- **绝不使用编辑距离或名称相似度**宣布改名；无证据的一对就是一个删除 + 一个新增。

## 判定规则（平台 × 架构 × 公开/私有）

规则集按 `(platform, arch)` 存储，并带单调递增的 **revision**。每个规则集是
`boundary → change-kind → severity` 的映射，severity 为
`BREAKING / COMPATIBLE / INFO / ALLOWED`。内置集合（`DefaultRuleSets`）：

| 规则集 | 关键立场 |
|---|---|
| `rs-linux-x86_64-1` | 基线：公开符号的任何 ABI 变化都 BREAKING |
| `rs-linux-x86_64-2`（active） | 细化：**复用已声明保留槽**与**追加私有尾部字段**为 COMPATIBLE；中间插入、尾部填充被吞掉仍 BREAKING |
| `rs-windows-x86_64-1` | Win64：调用约定/布局属契约；私有尾部字段仍 BREAKING；保留槽复用兼容 |
| `rs-macos-aarch64-1` | AAPCS；有显式别名证据的改名 COMPATIBLE；保留槽/私有尾部可兼容 |

布局语义边界（`LayoutDiffer`）：

- **尾部追加私有字段**会改变 `sizeof`，但产生独立的 `LAYOUT_TAIL_PRIVATE_ADDED`，
  规则可单独判为兼容（linux/macos 兼容，windows 仍破坏）。
- **复用保留填充槽**（同一 stable 槽位，`reserved` 标志清除）是
  `LAYOUT_RESERVED_REUSED`，**不同于在中间插入字段**。
- 在中间插入字段（其后存在旧字段）→ `LAYOUT_FIELD_INSERTED` 且所有后续字段
  `LAYOUT_FIELD_OFFSET`。
- 无字段变化但 `tail_padding_bytes` 归零 → `LAYOUT_TAIL_PADDING_ELIDED`。
- 对齐变化独立报 `LAYOUT_ALIGN_CHANGED`；位域宽度/位移变化显式报
  `BITFIELD_LAYOUT_CHANGED`；零大小类型 ↔ 有大小为 `ZST_TO_SIZED` / `SIZED_TO_ZST`；
  可变参数切换为 `CC_VARARGS_TOGGLED`；调用约定切换为 `CC_CHANGED`。

### 同一快照、不同规则集并存且可重现

比较的输入哈希包含**两侧快照内容哈希 + 规则集 id**。因此同一对快照在
`rs-linux-x86_64-1` 与 `rs-linux-x86_64-2` 下得到两个独立 comparison，结论永久并存、
可随时重放；重复请求是幂等的，返回同一 comparison。比较在一个数据库事务内写入，
保证**原子性**（要么全部 finding 可见，要么都不可见）。

## 引用图与受影响入口

比较在右侧快照的反向引用图上，从每个变化符号沿 `CALL/PARAM/FIELD/...` 入边回溯，
收集可达的 PUBLIC 且导出的 FUNCTION/VARIABLE，作为 `affectedRoots` 附在 finding 上。
例如 `wgt_config` 的保留槽被复用，会回溯到 `wgt_config_init`、经由
`wgt_internal_compute` 回溯到 `wgt_compute`。图页高亮这些路径。

## 决议与例外生命周期（与快照分库存储）

决议写入**独立的 SQLite 数据库**（decision DB），与不可变快照严格分离。

- 一条 resolution 键为 `(comparison_id, symbol, platform)`；`platform` 可以是具体平台或
  `*`，因此**两个平台可以对同一符号保留不同结论**。
- `EXCEPTION` 必须带有效范围 `scope_from_version` / `scope_to_version`（含边界，semver）
  和**到期版本** `expires_at_version`（不早于 scope 上界）；`ACCEPT_BREAK`/
  `REJECT_RELEASE` 不需要范围。
- 决议**绑定批准时的规则集 `ruleset_id`**：规则修订升级不会延长旧例外——新 comparison
  引用新规则集时，针对旧修订批准的例外不再适用（`规则变更不自动延长旧例外`）。
- 到期或超出 scope 的例外不再抑制 finding；comparison 视图里 `severity` 是规则原始判定，
  `effectiveSeverity` 在存在适用例外时变为 `ALLOWED`，并附完整例外对象。
- 可通过 `POST /api/decisions/{id}/expire` 提前失效；所有提交/冲突/提前失效进入
  append-only 的 `resolution_event` 审计表。

### 乐观并发与符号级冲突

每个 comparison 维护单调 `revision`。提交决议必须带 `baseComparisonRev`（前端打开弹窗时
看到的版本）。若期间有其它决议落地，提交返回 **409**，body 中给出
`currentRevision` 和**符号级冲突列表**（symbol、platform、现存决定、理由），而不是静默
覆盖。SQLite 连接以 `transaction_mode=IMMEDIATE` 打开，写事务立即取锁，避免写锁升级死锁。

## HTTP API 摘要

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/releases` `/api/snapshots` `/api/snapshots/{id}` | 发行版/快照浏览 |
| POST | `/api/snapshots` | 导入提取器 JSON（`dist`,`version`,`json`），内容哈希去重 |
| GET | `/api/rulesets` | 规则集（含修订） |
| POST | `/api/comparisons` | 原子比较：`leftSnapshotId`,`rightSnapshotId`,`rulesetId?` |
| GET | `/api/comparisons` `/api/comparisons/{id}` | 比较列表/详情（含例外与有效严重性） |
| GET | `/api/comparisons/{id}/graph` | 右侧引用图节点/边与热点 |
| POST | `/api/decisions` | 提交决议（乐观锁；409 返回符号级冲突） |
| GET | `/api/comparisons/{id}/decisions` `/events` | 当前决议与审计事件 |
| POST | `/api/decisions/{resolutionId}/expire` | 提前失效例外 |

## 存储

- `data/abi-snapshot.db`：`release / snapshot / component / symbol / symbol_alias /
  symbol_ref / ruleset / comparison / finding`。snapshot 有 `content_hash UNIQUE`，
  comparison 有 `input_hash UNIQUE`（快照内容+规则集）。
- `data/abi-decision.db`：`resolution / resolution_event / comparison_rev`。

Schema 见 `src/main/resources/db/*.sql`。

## 测试

`mvn -q test` 运行 17 个测试，覆盖：保留槽复用 vs 中间插入、私有尾部字段、尾部填充吞掉、
位域、对齐、ZST、调用约定/可变参数、改名必须有显式别名证据（无编辑距离猜测）、内容哈希
去重、比较原子幂等、规则修订结论并存、引用图回溯、过期例外不豁免、规则修订绑定、平台间
不同结论、乐观锁符号级冲突与跨平台比较拒绝。
