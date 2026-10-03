# 实施计划：AI 解析的部分失败处理

> 状态：**待实现** · 起点 commit：`dfddb57`（工作区干净，`mvn test` **87 个用例全绿**）
> 范围：后端（`asteria-server` / `asteria-pojo`）+ 前端（`asteria-ai`）

---

## 一、要解决什么问题

用户上传题库时勾选「AI 解析」，程序会**逐题调模型**给每道题生成解析。

**现在的问题是：界面不告诉用户"有多少题没解析成功"**，所以用户看到有些题没有解析时，会以为系统坏了。

### 需求（来自用户原话）

1. **让用户知道有多少题没有解析** —— 成功的题照常能用，失败的题只是"没解析"，用户**仍可正常答题**
2. **如果是余额不足 / 限流，要在前端直接展示出来** —— 这类是"账号级"问题，用户需要知道去充值或稍后再试

### 关键设计判断（**这是本计划的核心，别改**）

| 失败类型 | 处理方式 | 为什么 |
|---|---|---|
| **单题失败**（模型偶尔抽风、这道题超时） | 跳过，继续跑完，任务照常成功 | 一道题失败不该毁掉整批，用户能用剩下的题 |
| **账号级失败**（余额不足/限流/Key 失效） | **立刻停手**，任务标失败并说明原因 | 继续跑下去**每道题都会失败**，纯粹白等白花钱 |

### 明确不做（避免过度设计）

- ❌ **不做"续跑"接口**（不加 `POST /resume`）
- ❌ **不做启动时收尾孤儿任务**（不加 `@EventListener`）
- ❌ **前端不加"继续解析"按钮**
- ❌ **数据库不加任何列**（`question.ai_status` 已经在记录每题成败，够用）

> 背景：这些方案曾经实现过一版，但因为"为了省几道题的 token 引入了整套机制"，**复杂度不划算**，已被撤销。本计划是简化版。

---

## 二、现状（已实现的部分，不要重复做）

起点 `dfddb57` 时，**下面的基础设施都已完成**：

| 已有的东西 | 位置 | 作用 |
|---|---|---|
| `question.ai_status` 列 | `db/question_ai_columns.sql` | 每题状态：PENDING / DONE / FAILED |
| `question.ai_error` 列 | 同上 | 失败原因（已脱敏、截断 500 字） |
| `question.ai_retry_count` 列 | 同上 | 重试次数 |
| `question.answer_source` 列 | 同上 | 答案来源 FILE / AI / MANUAL |
| `AiStatus` 枚举 | `pojo/enums/AiStatus.java` | PENDING / DONE / FAILED |
| `AnswerSource` 枚举 | `pojo/enums/AnswerSource.java` | FILE / AI / MANUAL |
| 并发解析（虚拟线程 + 信号量） | `BanksServiceImpl.aiEnrich()` | 已完成 |
| 逐题落库（成功标 DONE、失败标 FAILED） | `BanksServiceImpl.enrichOne()` / `markEnrichFailed()` | 已完成 |
| 失败原因脱敏 | `AiErrors.mask(e, config)` | 已完成，**复用，不要另写** |
| 解析结果自检（答案越界等） | `parser/QuestionQualityCheck` | 已完成，与本任务无关 |

**当前 `aiEnrich` 的行为**（你要改的就是它）：

```java
// BanksServiceImpl.java:374 附近
private void aiEnrich(String taskId, Long bankId, AiRequestConfig aiConfig) {
    List<Question> questions = ...;              // 只捞 ai_status != DONE 的题
    for (Question question : questions) {
        executor.submit(() -> {
            try {
                enrichOne(question, aiConfig);
            } catch (Exception e) {
                failed.incrementAndGet();
                markEnrichFailed(question, aiConfig, e);   // ← 失败只记账 + 标 FAILED
            } finally {
                limiter.release();
            }
            // 更新进度（内存）
            synchronized (progressLock) {
                updateTask(taskId, AI_PROCESSING, progress, total, bankId);
                if (finished % 5 == 0 || finished == total) {
                    bankImportMapper.updateById(...);       // ← 这个周期落库要删掉（见任务 4）
                }
            }
        });
    }
}
```

调用处（`BanksServiceImpl.java:282` 附近）：

```java
if (aiParse) {
    aiEnrich(taskId, outcome.bankId(), aiConfig);   // ← 现在不接收返回值
}
```

---

## 三、实现任务

### 任务 1：`aiEnrich` 返回"这一轮的结果"

**目标**：把失败题数和系统性失败原因传出来。

1. 新增一个私有 record：

```java
/** AI 解析这一轮的结果 */
private record EnrichOutcome(int failedCount, String systemicReason) {
    boolean abortedBySystemIssue() { return systemicReason != null; }
}
```

2. `aiEnrich` 签名改为 `private EnrichOutcome aiEnrich(...)`，末尾 `return new EnrichOutcome(failed.get(), systemicReason.get())`。

3. 用一个 `AtomicReference<String> systemicReason = new AtomicReference<>();` 记录首次出现的系统性失败原因。

### 任务 2：识别"账号级失败"并立刻停手

**目标**：余额不足 / 限流 / Key 失效时不再傻跑完整本题库。

1. 新增判断方法（private）：

```java
/**
 * 这个异常是不是账号/服务商级别的问题（不是我某道题的问题）。
 * 命中这些时下一道题也一定会失败，应该立刻停手。
 */
private boolean isSystemicAiFailure(Throwable e)
```

**判据**：沿着 `e.getCause()` 链逐层看 `getMessage()`，命中任一即算：

- 英文（转小写后 contains）：`insufficient`、`quota`、`rate limit`、`too many requests`、`balance`
- 中文（contains）：`余额`、`欠费`、`限流`、`频率`
- 状态码（contains）：`401`、`402`、`429`

⚠️ **必须防止 cause 自引用导致死循环**（`if (t == t.getCause()) break;`）。

2. 在 `enrichOne` 的 `catch` 里，标 FAILED 之后判断：

```java
} catch (Exception e) {
    failed.incrementAndGet();
    markEnrichFailed(question, aiConfig, e);
    String reason = AiErrors.mask(e, aiConfig);      // ★ 复用现有的脱敏工具
    if (isSystemicAiFailure(e)) {
        systemicReason.compareAndSet(null, reason);   // 只记第一次
        log.error("AI 解析遇到账号级问题，本次提前结束：taskId={}, 原因={}", taskId, reason);
    }
}
```

**关于"立刻停手"的说明**：这里只设标记，不强行打断已提交的任务。
因为剩余任务会在拿信号量时排队，**很快都会失败并各自记账**；真正避免浪费的是**下一轮不再重复请求**（任务 3 会把它们改回 PENDING），
以及**任务 6 会让界面立刻告诉用户原因**。如果实现时发现"提前停手"可以做得更干净（比如检查标记后直接 return 不再调模型），**可以做，但不要为此引入复杂机制**。

### 任务 3：账号级失败时，把 FAILED 改回 PENDING

**目标**：这些题**失败的原因是账号，不是题目本身**，标 FAILED 会误导用户以为题目有问题。

```java
/** 把因账号问题而失败的题改回「未解析」（它们是可重试的，不是坏题） */
private void unmarkRetryableFailures(List<Question> questions)
```

- 只改**本次处理过、且当前状态还是 FAILED** 的题（`UPDATE ... WHERE id IN (...) AND ai_status = 'FAILED'`）
- 把 `ai_status` 置为 `PENDING`、`ai_error` 清空
- 整段用 `try/catch` 包住，**失败只记日志不影响主流程**

在 `aiEnrich` 末尾、`return` 之前调用（仅当 `systemicReason != null` 时）。

### 任务 4：删掉周期性进度落库

`BanksServiceImpl` 中这段**删掉**：

```java
if (finished % 5 == 0 || finished == total) {
    bankImportMapper.updateById(BankImport.builder()
            .taskId(taskId)
            .progress(progress)
            .build());
}
```

**理由**（已论证）：运行期间没人读库里的这个进度（前端轮询优先读内存），而"跑到哪了"可以随时从 `question.ai_status` 现算。
500 题就是要少写 100 次数据库。

`synchronized (progressLock)` 保留（`updateTask` 改的是共享 VO，虽然内部是 `ConcurrentHashMap`，但保留锁避免后续改动踩坑）；
如果实现者确认去掉锁也安全，**可以去掉并说明理由**。

### 任务 5：调用处根据结果决定任务终态

改 `BanksServiceImpl.java:282` 附近：

```java
boolean aiParse = aiConfig != null;

// ②.5 输出侧自检（保持原样）
questionQualityCheck.check(rawQuestions);

BanksImportTransactional.Outcome outcome =
        importTransactional.saveImport(taskId, bankName, originalName, ext, rawQuestions, aiParse);

// ③ AI 解析阶段
BanksServiceImpl.EnrichOutcome aiOutcome = null;
if (aiParse) {
    aiOutcome = aiEnrich(taskId, outcome.bankId(), aiConfig);
}

// ④ 判断终态：账号级问题 → 任务失败；否则成功（失败几道题不影响成功）
if (aiOutcome != null && aiOutcome.abortedBySystemIssue()) {
    // 账号/服务商问题：把原因告诉用户，让他去充值/稍后重试。
    // 注意：已完成解析的题仍然是 DONE，数据不丢，用户也能照常刷题。
    throw new BusinessException(40021,
            "AI 解析中断：" + aiOutcome.systemicReason()
            + "。已完成 " + (outcome.totalCount() - aiOutcome.failedCount())
            + " 道题的解析（这些题可以正常使用），请处理后重新上传以补齐剩余的 "
            + aiOutcome.failedCount() + " 道。");
}
// 否则走原来的成功路径（内存 + 数据库都标 SUCCESS）
```

⚠️ **注意 `throw` 会被外层 `catch` 捕获**，走 `markFailed` 把任务标 FAILED 并把原因写进 `error_message`。
**确认这条路径符合预期**：任务失败、原因可见、题库数据保留（题库和题目在 `saveImport` 事务里已经提交，不会被回滚）。

**错误码 `40021` 需要在 `docs/改造计划.md` 或 README 的错误码表里补一行**（新增码）。如果觉得不必新增码，也可以用 `50000`，但**优先新码**，语义更清楚。

### 任务 6：前端展示"有多少题没解析"

#### 6.1 后端：让 `Message()` 带上计数

`BankResultVO` 加三个字段（**不落库，实时算**）：

```java
/** AI 解析已完成的题数 */
private Integer aiDoneCount;
/** 还需要解析的题数 */
private Integer aiPendingCount;
/** 解析失败的题数 */
private Integer aiFailedCount;
```

> ⚠️ **重要**：`BankResultVO` 上有 `@Builder`。加了字段后，**所有用 `BankResultVO.builder()` 的地方都不用改**（没设的字段默认 null），
> 但**必须检查**有没有用 `@AllArgsConstructor` 全参构造的地方，有的话会编译失败。

在 `BanksServiceImpl.Message()` 里，**两处**都要填这些计数：
- 内存命中时（第 1 级）
- 数据库兜底时（第 2 级）

**实现建议**：抽一个私有方法 `fillAiCounts(BankResultVO vo, Long bankId)`，内部用 `questionMapper.selectCount(...)` 查三次（DONE 数 / FAILED 数 / 总数）。
**必须 try/catch**，统计失败只记 warn，不能影响 `Message()` 主流程。

> 为什么实时算而不是落库：`ai_status` 是唯一真相源，多存一份就会有两个真相源，写时机不对就会对不上。

#### 6.2 前端：类型 + 展示

1. `asteria-ai/src/types/index.ts` 的 `ImportTask` 加三个可选字段：

```ts
aiDoneCount?: number | null
aiPendingCount?: number | null
aiFailedCount?: number | null
```

2. `asteria-ai/src/views/banks/BankImportView.vue` 的**成功卡片**（`task.status === 'SUCCESS'` 那个分支）：

现在写的是：

```vue
<p class="text-muted">共识别 {{ task.totalCount }} 道题目，已整理入库</p>
```

改成：**有失败题时补一行提示**（没有失败就保持原样，不要多显示噪音）：

```vue
<p class="text-muted">共识别 {{ task.totalCount }} 道题目，已整理入库</p>
<p v-if="task.aiFailedCount" class="ai-warn">
  其中 {{ task.aiFailedCount }} 道题未生成解析，不影响做题
</p>
```

- 新增的 `.ai-warn` 样式：**暖色/警示色**（用已有的 token，比如 `var(--apricot)` 或 `var(--warning)`，**不要硬编码色值**）
- **不要加按钮**、不要加交互，就是一行提示

3. **失败卡片**（`task.status === 'FAILED'`）**已经有**错误文案展示：

```vue
<p class="error-msg">{{ task.errorMessage || '文件解析失败，请检查文件内容' }}</p>
```

任务 5 抛出的异常会把"余额不足"这类原因写进 `errorMessage`，**所以这里会自动显示出来，不用改**。
但请**验证一下**：文案里包含"已完成 N 道"时界面显示是否正常（没有溢出、没有被截断）。

---

## 四、验收标准

### 必须通过

```bash
mvn clean test          # 全绿。起点是 87 个用例，实现后应该 ≥87（新增测试会更多）
```

### 必须新增的测试

在 `asteria-server/src/test/.../Services/impl/` 下**新增**一个测试类（或加进 `BanksServiceImplTest`），**至少覆盖**：

| # | 用例 | 断言 |
|---|---|---|
| 1 | 单题失败 → 任务仍成功 | `aiEnrich` 返回的 `failedCount` = 失败数；**不抛异常** |
| 2 | 余额不足 → 识别为系统性失败 | `isSystemicAiFailure` 对含 `insufficient balance` 的异常返回 true |
| 3 | 限流（429）→ 识别为系统性失败 | 含 `429` 或 `rate limit` 返回 true |
| 4 | 普通单题错误 → **不**误判为系统性失败 | 含 `AI 没有返回可用的解析内容` 返回 false |
| 5 | 系统性失败 → FAILED 被改回 PENDING | 验证 `questionMapper.update` 被调用，且条件里有 `ai_status = FAILED` |
| 6 | 中文原因（余额/限流）也能识别 | 含「余额不足」返回 true |

> `isSystemicAiFailure` 是 private，测试可用 `ReflectionTestUtils.invokeMethod`，或把它放宽到包级私有。

### 必须人工验证的行为

1. **正常路径**：上传一份题库 + 勾 AI 解析 → 成功卡片显示「共 N 道题已入库」，**没有多余的警告行**（因为没有失败题）
2. **部分失败**：构造几道题失败（可以临时 mock）→ 成功卡片多显示一行「其中 X 道题未生成解析，不影响做题」
3. **账号级失败**：把 AI Key 改成一个无效的 → 失败卡片上能看到**具体原因**，且文案里有"已完成 N 道"
4. **失败后题库可用**：账号级失败后，去题库详情页确认**题目在**、能正常刷题

---

## 五、约束与注意事项

### 必须遵守

- **复用现有工具，不要另写**：脱敏用 `AiErrors.mask(e, config)`；状态用 `AiStatus` / `AnswerSource` 枚举，**不要写字符串字面量**
- **不要给数据库加列**。`ai_status` / `ai_error` / `ai_retry_count` 已经够用
- **不要引入新的 Maven 依赖**
- **所有新增/修改的代码都要写中文注释说明"为什么"**，风格与现有代码一致（现有代码注释都在解释理由，不是复述代码）
- **前端不要硬编码颜色**，用 `asteria-ai/src/styles/tokens.css` 里的 token

### 环境（重要）

- **shell 默认会被沙箱拒绝**：直接跑 `pwsh` 会报 `SetNamedSecurityInfoW failed (Win32 5)`。
  需要**提权重试**（一次性授权）才能执行 `mvn`、`git` 等命令。遇到这个报错就提权重试同一条命令。
- **不要用 PowerShell 的 `Set-Content` 改写 Java 源文件**：会写入 BOM，导致 Java 编译报「非法字符: '\ufeff'」。
  用编辑工具改，或写回时用 `UTF8Encoding($false)`（无 BOM）。
- **MySQL 本机可用**（8.0，`localhost:3306`，库 `finaltext`，root/123456）。**但本任务不需要改数据库**。

### 验证要求

- 改完必须跑 `mvn test` 并确认全绿（把实际数字报出来）
- **不要声称"应该没问题"** —— 必须有真实的测试输出

---

## 六、交付物

1. 代码改动（后端 + 前端）
2. 新增的测试
3. 一份简短的实现说明：**改了什么、怎么验证的、遇到什么问题**

**不需要**更新 `CHANGELOG.md` 和 `docs/学习笔记-*.md`（Lead 会统一写）。
