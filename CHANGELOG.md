# 更新日志

本文件记录项目的**每次修复与改造**，按时间倒序排列（最新的在最上面）。

写这个文件的原因：改动背后的**取舍和踩过的坑**比"改了什么"更有价值，但它们散落在代码注释和提交记录里，时间一长就找不回来了。

---

## 2026-10-04 · 题库解析：补正则，多认几种写法

### 背景

格式对不上正则的文件会整份掉进 AI 兜底，token 烧得厉害。规则解析是免费的、AI 是花钱的，所以**每多覆盖一种写法，那部分成本直接归零**。这次把常见但漏掉的几种补进 `QuestionParser`。

### 改了什么

**1. 题号多认四种写法**

`1)` `1）`（旧版分隔符只有 `、.．`）、裸写 `第12题`（旧版只认 `【第N题】`）、`【1】` `[1]`，以及 Markdown 前缀 `**1.**` `## 1.` `- 1.`。

**2. 题干提取跟题号正则同步扩（这条其实是在修 bug）**

题号行（`QUESTION_HEADER`）和题干提取（`NUMBERED_STEM_PATTERN`）是**两套正则**。旧版题号行认 `【第1题】`，题干提取那条**不认** —— 于是 `【第1题】题干内容` 这种写法，题号被切成一个块、题干一个字提不出来，整题报废。现在两条同步了。

**3. 选项认括号包裹和冒号分隔**

`（A）甲` `(A)甲` `【A】甲` `[A]甲`，以及 `A：甲` `A:甲`。

**4. 答案认超星/学习通导出的写法**

`【答案】B` `【正确答案】B`，以及简答题的 `答：B`。

**5. 行内选项**

`1. 下列哪个是语言？ A. 中文 B. 英文 C. 法文 D. 德文` 这种题干选项挤一行的写法，在 txt/pdf 里极常见。旧版把整串当题干，选项一个都提不出来（等于废题）。新增 `splitInlineOptions()` 拆分；末选项粘着的答案（`… D. 丁 答案：D`）也一并剥出来。

**6. Markdown 加粗残留**

从网页或笔记里复制出来的题库常带 `**`。加粗的**选项行原来完全认不出**（选项全丢，题会被误判成填空/简答），加粗答案会残留成 `B**`。现在行首前缀交给 `LINE_PREFIX` 吃掉，收尾的星号由新增的 `stripBold()` 剥掉。

### 防误伤（这块比扩展本身更要紧）

正则放宽最怕把正文切碎，那比不解析更糟。所以：

- 行内拆分有三重门槛：① 至少 2 个标记；② 字母必须从 A 开始且**严格连续**；③ 每个标记后面内容非空
- 再加两条保护：标记必须在**行首或空白之后**（挡住 `e.g.`、`U.S.`、`答A.` 这类词内点号）；含 `{ } ; // def printf(` 的代码行**直接不拆**
- `stripBold()` **只剥成对的两个 `**`，不碰单个 `*` 和 `_`** —— 不然会把 Python 选项里的 `*args`、`__init__` 误伤成 `args`、`init`
- 裸「答案：」仍然**只在选项末尾**这个位置认（行首只认"正确答案/参考答案"），免得把"我的答案"当成标准答案

### 顺手修的

| 项 | 说明 |
|---|---|
| `NUMBER_CORE` 是死代码 | 注释写着"供题号行和题干提取两处复用"，实际从没被引用，两边各抄了一遍。抽出 `WRAPPED_NUMBER` 让它真正复用 |
| `matchChapter` 重复注释 | 同一段话写了两遍 |

### 怎么验证的

```
mvn -pl asteria-server -am test  →  91 个用例全绿（改动前 87 个）
```

三类都覆盖到了：该认的必须认、**不该拆的必须纹丝不动**（单个标记 / 字母不连续 / 词内字母 / 代码行）、老格式回归。

### 踩过的坑

- **题号行和题干提取必须同步扩**：这次修的就是它们不同步造成的整题报废。以后加题号写法，两处一起加。
- **`LINE_PREFIX` 只挂在题号行上，漏了选项行**：上一版加 `**1.**` 支持时没同时给选项行加，结果是"题号认得出、选项全丢"，比重不了解析更隐蔽，复查时才揪出来。
- `QUESTION_HEADER` 是 `public`，`AiQuestionExtractor` 分块要用它，可见性不能动。

### 涉及文件

```
改动  asteria-common/src/main/java/com/asteria/common/Tool/QuestionParser.java
```

### 下一步

- [ ] **答案集中区识别**：`1.【答案】A` 会被题号行命中，答案区被切成"幽灵题"，真题目反而全部缺答案
- [ ] `RawQuestion` 保留题号 → 顺带解锁跳号检测和截断检测
- [ ] 中文数字题号 `一、`、带圈 `①`（实测整份文件 0 题）—— 但 `一、` 也常作大节标题，得先确认真实文件的用法
- [ ] 括号字母内联 `(A) 甲 (B) 乙`（字母前是 `(` 不是空白，不触发拆分）
- [ ] 答案解析里含拉丁字母会被 `AnswerTexts.letters()` 抽成多选（如 `正确答案：B。因为 A 和 C 不对`）

---

## 2026-10-03 · 修复 LaTeX 反斜杠破坏 AI 解析 + 解析自检

### 修了什么

**1. AI 路径的 JSON 解析遇到 LaTeX 公式会坏掉（重点修复）**

数学题的题干里有 `\frac{1}{2}` 这类公式，模型把它原样写进 JSON 后，反斜杠被当成 JSON 转义符，造成两种结果：

- `\f`、`\b`、`\v` 在 JSON 里是**合法**转义 → 被静默解析成控制字符，题干悄悄变样（`\frac` → `rac`），**程序不报错，用户看不出来**
- `\alpha`、`\cdot` 是**非法**转义 → Jackson 抛异常 → 整块抽取失败 → 整批导入失败

三条 AI 路径都受影响：结构化抽取、逐题补解析、知识点总结。

- 新增 `AiJsonRepair`：逐字符判断"这个反斜杠是不是合法转义的开始"，只给**非法转义**和**假控制转义**补双反斜杠
  - 判据：`\f`/`\b`/`\v` 后面紧跟小写字母 → 当 LaTeX 命令（真控制字符后面不会正好接字母）
  - 幂等：已经是合法 JSON 的文本进出一致
- `AiQuestionExtractor`、`QuestionAiEnricher`、`AiServiceImpl` 三处接入
- 两个系统提示词补充要求：LaTeX 反斜杠要写成 `\\`（从源头减少问题）
- **已知局限**：`\times`、`\nabla` 不修 —— `\t`、`\n` 在正文里是真会用的字符，按同样规则修会误伤正常文本。只能靠提示词约束，已在注释里写明

**2. 新增解析结果自检（`QuestionQualityCheck`）**

切题切错了程序是不知道的。加了六条确定性检查，**只打标、不改数据、不阻断导入**：

无题干 · 缺答案 · 选项不足 · **答案越界** · 选项重复 · 有可疑行

其中「答案越界」（答案字母不在选项里）是最可靠的问题信号 —— 这种情况几乎必然是切题切错了，相当于一个**免费的错误探针**，跑一次导入就知道数据健康度。

同时第一次真正用上了 `RawQuestion.suspicious`（之前是"只写不读"的死字段）。

**3. 扫描件 PDF 给出准确提示**

扫描件抽不出文字，结果和"空文件"无法区分，用户不知道该改格式还是检查文件。`PdfTextReader` 增加文字层体检（每页少于 30 字判定为扫描件），命中时给出明确提示；并在规则解析失败后**立刻拦截**——扫描件没有文字层，AI 也救不了，不拦会白跑一次模型调用。

**4. 死代码清理（11 处）**

| 清理项 | 说明 |
|---|---|
| `QuestionParser.debugHeader()` | 只有两个 `System.out.println`，唯一引用已被注释 |
| `test/Text.java` | **整个文件**：0 断言 + 硬编码本机绝对路径，换机器必然失败 |
| `AiChatModelFactory` 的三参 `create()` 重载 | 无人调用（两参和四参都直接调四参版） |
| `TrueFalseAnswer.getLabel()` + `label` 字段 | 引用 0 次 |
| `AiServiceImpl.mask()` | 私有转发方法，5 处调用改为直接调 `AiErrors.mask` |
| `AiController`、`AiSummaryController` 的 `@Slf4j` | 两个类里 `log.` 出现 0 次 |
| `QuestionsServiceImpl` 的 `@RequiredArgsConstructor` | 字段全是非 final + `@Autowired`，Lombok 生成的是**无参**构造，与注释说的"构造函数注入"相反 |
| `ChatMessage` 的无用 `import AllArgsConstructor` | 类上根本没有该注解，是残留 |
| `BanksServiceImplTest` 的无用 import ×2 | — |
| `QuestionParser.CHAPTER_HEADER` 可见性 | `public` → `private`（只有类内用） |

注：`QuestionParser.QUESTION_HEADER` 保持 `public` —— `AiQuestionExtractor` 分块时要用它。

### 顺手修的

- **`AiErrors.mask()` 的 NPE**：`config` 为 null 时会抛 NullPointerException。在一个"处理异常"的工具里抛异常是很难排查的，加了 null 判断

### 怎么验证的

```
mvn test  →  65 个用例全绿（改之前 31 个）
```

新增 34 个用例：

- `AiJsonRepairTest`（16）：修复规则 + "不该动的不能动"（真控制字符、正常转义、幂等），以及**断言 bug 修复前确实存在**
- `QuestionQualityCheckTest`（14）：每条检查项 + 一组"正常数据不报问题"的误报测试

### 踩过的坑

- **Java 的 `\u` 转义先于注释处理**（JLS 3.3）：第一版代码编译不过，报 5 个「非法的 Unicode 转义」，位置全在我自己写的 Javadoc 里 —— 为了举例写了 `\uXXXX`，编译器真去解析它
- **第一版修复方案是错的**：只修"非法转义"，`\frac` 照样坏（因为 `\f` 合法）。这让我想明白：这不是转义合法性问题，而是 **JSON 转义语义与 LaTeX 命令语法天然冲突**，只要尊重 JSON 语义，`\frac` 就一定会坏
- **砍掉了「题号跳号」检查**：`QuestionParser` 切题时已把题号剥掉，`RawQuestion` 也没保留原始题号，拿不到两个题号比较。硬用行号差推断会产生大量误报，反而让探针失去意义 —— 选择砍掉并写明原因

### 涉及文件

```
新增  asteria-server/src/main/java/com/asteria/server/ai/AiJsonRepair.java
新增  asteria-server/src/main/java/com/asteria/server/parser/QuestionQualityCheck.java
新增  asteria-server/src/test/java/com/asteria/server/ai/AiJsonRepairTest.java
新增  asteria-server/src/test/java/com/asteria/server/parser/QuestionQualityCheckTest.java
删除  asteria-server/src/test/java/com/asteria/server/Text.java

改动  AiQuestionExtractor.java    解析前修复 + 提示词第 8 条
改动  QuestionAiEnricher.java     解析前修复 + 提示词第 4 条
改动  AiServiceImpl.java          两处解析前修复 + 删除 mask 转发
改动  AiErrors.java               config 为 null 的 NPE
改动  BanksServiceImpl.java       接入解析自检 + PDF 体检
改动  PdfTextReader.java          文字层体检（readWithDiagnostics）
改动  AiChatModelFactory.java     删除未使用的三参重载
改动  QuestionsServiceImpl.java   删除无效的 @RequiredArgsConstructor
改动  AiController.java           删除无用的 @Slf4j
改动  AiSummaryController.java    删除无用的 @Slf4j
改动  QuestionParser.java         删除 debugHeader + CHAPTER_HEADER 降为 private
改动  ChatMessage.java            删除残留 import
改动  BanksServiceImplTest.java   删除无用 import
改动  TrueFalseAnswer.java        删除未使用的 getLabel/label
```

> 注：`RawQuestion.java` 本次**没有改动** —— `suspicious` 字段本来就存在，这次是让自检第一次真正读它。

### 下一步

按 [`docs/改造计划.md`](docs/改造计划.md) 的阶段划分，第 0 步已完成。后续待办：

- [ ] 建立 golden 数据集 + 字段级识别率评测器（所有量化改进的前提）
- [ ] AI 解析批量/并发改造 + 断点续跑 + 数据血缘（`answer_source`）
- [ ] 抽题策略升级（加权/分层/可复现 seed）
- [ ] 解析器重构（Handler 预处理链 + 选项锚点回溯切题）
- [ ] 数据库索引与表结构收口

---

## 2026-10-03 · 数据库索引：补 practice_record 的 (session_id, is_correct)

先把 12 张表的索引逐个对着**真实库**核对了一遍（不只是看 DDL 文件），结论是**只缺一处**。

### 加的索引

```sql
ALTER TABLE practice_record ADD KEY idx_pr_session_correct (session_id, is_correct);
```

**为什么需要**：答题时有两处查询按 `(session_id, is_correct)` 过滤，而且**每提交一题就跑一遍**：

- `PracticeSessionMapper.refreshCounts` —— 重算会话的已答/答对数（两遍 COUNT 带 `is_correct`）
- `PracticeRecordMapper.selectWrongItems` —— 查本次会话的错题明细（`is_correct = 0`）

原有索引是 `uk_pr_session_question (session_id, question_id)`：能用上 `session_id` 前缀，
但 **`is_correct` 不在索引里** —— 所以要把该会话的全部记录逐行取回来再过滤。

一个 500 题的会话，每答一题扫 500 行，整个会话累计约 **12.5 万行**；
加上这个索引后可以直接跳到匹配项。

**列顺序有讲究**：`session_id` 在前（等值过滤、区分度高），`is_correct` 在后（会话内部再收窄）。

### 验证

```
EXPLAIN SELECT COUNT(*) FROM practice_record WHERE session_id=14 AND is_correct=1;

  key: idx_pr_session_correct | ref: const,const | Extra: Using index
                                                  ↑ 覆盖索引，连回表都省了
```

**诚实说明**：本地库 `practice_record` 只有 7 行，所以另一条查询
（`selectWrongItem` 的 `is_correct = 0`）优化器仍选了旧索引 ——
数据量小的时候索引基数太低，优化器判断不准，这是正常行为，不是索引没用。
**这个索引的收益在当前数据量下无法实测**，是按查询模式推算的。

### 核对后决定**不加**的索引（不是遗漏）

| 候选 | 为什么不加 |
|---|---|
| `question.bank_id` | 已有 `idx_question_bank_chapter(bank_id, chapter_id)` 和 `idx_question_bank_type(bank_id, type)`，按最左前缀就能走索引；单加一个只会多占空间、拖慢写入 |
| `import_task.status` / `created_at` | 任务只按主键 `task_id` 查，不按状态扫表 |
| `chat_session.title` / `message_count` | 区分度极低，且没有查询按它们过滤 |
| `practice_record.is_correct` 单列 | 布尔列区分度只有 2，没意义；必须和 `session_id` 组合才有用（就是上面加的那个） |

> 索引不是越多越好：每个索引都会拖慢写入、占空间，且**基数是自增统计的，加了低区分度索引反而可能误导优化器**。

### 涉及文件

```
新增  asteria-server/src/main/resources/db/practice_record_index.sql   可重复执行的迁移脚本
改动  docs/改造计划.md                                                  阶段 4 标注 DB1 已完成
```

---

## 2026-10-03 · 决定：随机抽题保持原样（不做策略化改造）

**决定**：`PracticeServiceImpl` 的随机抽题**保持现状**（全量取 id + `Collections.shuffle`），
不做加权采样 / 分层抽样 / 可复现 seed 那一套。

**理由**（用户拍板）：均匀随机在几千题规模下完全够用，加策略是**过度设计** ——
复杂度上去了，用户能感知到的差别很小。项目定位是本地个人使用，够用即可。

**影响**：`docs/演进方向-总体方案.md` 里的「线 4」整节标注为**不做**（内容保留作资料备查），
「亮点池」里对应的简历条目划掉，「推进顺序」不再包含它。

**什么情况下再回头看**：题库到几万题、或真需要"模拟考试可复现"时。
那三条已知的"可以更好"记在方案文档里，不是 bug、不再作为待办。

> 顺带记一条同类判断：**"能做得更好"不等于"该做"**。
> 这次和之前的「不做续跑接口」「不做启动收尾」是同一类取舍 ——
> 复杂度要花在用户能感知到的地方。

---

## 2026-10-03 · 错误信息不再把服务商报文甩给用户

### 起因

用户实际跑了一次（用一个无效的 AI Key），界面显示：

```
AI 解析中断：已完成 0 道，还有 50 道未解析（已完成的题目可以正常使用）。请检查
AI 账号后重新上传以补齐。原因：401 - {"error":{"message":"Authentication Fails,
Your api key: *****5e65 is invalid (request_id: 07158b0d-d8d4-4fef-ac49-a9f6678ce6d6)"}}
```

两个问题：

1. **那串报文对用户没有信息量** —— 他不知道 `401` 是什么、`request_id` 是干什么的、该点哪里。
   而且里面还带着 Key 片段，虽然已脱敏，但没必要显示。
2. **末行还写着「数据已回滚，不会产生脏数据」** —— 这是 40021（题库已入库），
   说"已回滚"会让用户以为题库没了。**我上一批修过这个文案，但没生效**（原因见下）。

### 改了什么

**1. 把"服务商报文"换成"用户能执行的一句话"**

新增 `classifyAiFailure(Throwable)`，把账号级失败归成四类，每类给一句**能照着做**的话：

| 情况 | 给用户看的话 |
|---|---|
| Key 无效/失效（401/403/Authentication） | AI 账号鉴权失败，请到「设置」页检查 API Key 是否填写正确 |
| 余额/配额（insufficient/quota/balance/余额/欠费） | AI 账户余额不足或配额已用完，请充值后重试 |
| 限流（rate limit/429/频率/限流） | AI 请求过于频繁被限流，请稍等几分钟再重试 |
| 认不出来 | **返回 null，当作单题失败处理**（见下面的坑） |

**原始报文仍然进 `log.error`**（排查要用），但不出现在界面上。

⚠️ **判断顺序有讲究**：DeepSeek 对"余额不足"返回的是 **429** 而不是 402，报文里写的是
`Insufficient Balance`。所以必须**先按报文关键词判，最后才用状态码兜底** ——
否则"欠费"会被误报成"请求太频繁"，把用户引向错误的处理方式（等几分钟，而实际该去充值）。
这条有专门的测试用例盯着。

**2. ⚠️ 修掉一个我自己在改造中引入的严重 bug**

第一版 `classifyAiFailure` 的兜底分支返回了一句笼统的话（"AI 服务暂时不可用…"）而不是 `null`。
后果：**任何一次普通失败**（模型抽风、JSON 格式错、这道题超时）都会被升级成"账号级问题"，
进而**把整批中断** —— 用户会遇到"50 道题只解析了 1 道就全停了"，比原来严重得多。

**这是测试抓出来的**（3 个用例同时红）。教训正是我在学习笔记里写过的那句：
**宁可漏判（多跑几道注定失败的题），不可乱判（把单题问题升级成整批失败）**。
现在兜底明确返回 `null`，并把这条理由写进了代码注释。

**3. 补上漏判的真实场景**

原来的关键词表**没有** `authentication` / `401`，而用户遇到的恰恰就是这种报文。
如果不补，这次的真实故障**根本不会被识别成账号级失败** —— 会傻跑完 50 道题、全部失败。
现在补上了。

### ⚠️ 一个教训：构建产物没更新，修复就"没生效"

用户看到的末行仍是「数据已回滚」，但**源码里早就改好了**（`dist` 里没有新文案，实测 `False`）。

**原因**：`asteria-ai/dist` 是**被 git 跟踪的构建产物**，而我在上一批为了让提交"干净"，
**把它还原了** —— 用户部署用的却是 `dist`，于是跑的是**旧前端 + 新后端**。

**这个判断是错的**：这个项目的约定就是 `dist` 随仓库提交、可直接部署（README 里写明了），
"为了 diff 干净"而还原它，代价是**用户拿到的东西没有修复**。

**已修正**：本批把 `dist` 一起重新构建并提交。

### 怎么验证的

```
mvn test  →  100 个用例全绿（上一批 99 + 本次调整）
npm run build  →  构建成功，并确认产物里含新文案（含『题目已入库』: True）
```

新增/调整的用例：
- Key 无效的真实报文 → 归到鉴权类，且**断言不出现 `request_id`、不出现 Key 片段**
- **余额不足但状态码是 429 → 必须归到余额类，不能误报成限流**
- 认不出来的普通错误 → **必须返回 null**（这条就是抓出上面那个 bug 的用例）
- 分类结果长度 < 60 字（这样整条消息远低于 `briefReason` 的 200 字截断线）

### 涉及文件

```
改动  asteria-server/.../Services/impl/BanksServiceImpl.java   classifyAiFailure 取代原关键词判定
改动  asteria-server/src/test/.../BanksServiceImplAiEnrichTest.java  调整 + 新增用例
改动  asteria-ai/dist/**                                       重新构建（含前端文案修复）
```

---

## 2026-10-03 · AI 解析的部分失败处理：区分"一道题的错"和"账号的错"

### 起因

用户提出一个很实际的问题：**上传题库跑完 AI 解析后，有几道题没有解析，界面却只说"导入完成"** ——
用户看到空解析的题，**不知道是系统坏了还是自己有问题**。

顺着这个问题往下想，发现了更值得处理的事：**失败其实分两种，处理方式完全相反。**

| | 单题失败 | 账号级失败 |
|---|---|---|
| 例子 | 模型抽风、超时、返回格式不对 | **余额不足**、**限流 429**、**Key 失效** |
| 影响范围 | 就这一道题 | **接下来每道题都会失败** |
| 该怎么办 | 跳过，继续跑完 | **立刻停手**，别白跑 |

### 改了什么

**1. 单题失败只跳过，不再影响整批**

`catch` 里只计数 + 标 FAILED，**不往外抛异常**。50 道题失败 3 道 → 题库照常建成，47 道有解析，
**那 3 道只是没有解析，用户照样能刷题**。

判断依据：用户要的是"能用"，不是"完美"。一道题失败不该毁掉整批。

**2. 识别"账号级失败"并立刻停手**

新增 `isSystemicAiFailure(Throwable)`，沿**异常链**找关键词：

- 英文：`insufficient` / `quota` / `rate limit` / `too many requests` / `balance`
- 中文：`余额` / `欠费` / `限流` / `频率`
- 状态码：`401` / `402` / `429`

**两个坑**（都踩了）：
- **错误码在异常链深处**，只看最外层 message 会漏判 → 必须沿 `getCause()` 往下找
- **异常链可能有环**（`A → B → A`），只判 `cause == self` 防不住，会真死循环 → 加了 `MAX_CAUSE_DEPTH = 10`
  （正常异常链只有 2~3 层），并写了带 `@Timeout` 的用例证明

**3. 账号级失败时，把标错的 FAILED 改回 PENDING**

这些题失败的原因在**账号**，不是题目本身。标 FAILED 会让界面显示"这些题解析失败了"，
误导用户以为题目有问题；标 PENDING 表达的才是实话："只是还没解析"。

改的时候条件必须是 `WHERE id IN (...) AND ai_status = 'FAILED'` —— 保证**只改这次刚失败的，
不误伤之前已成功（DONE）的题**。

**4. 让用户知道"有多少题没解析"**

- `BankResultVO` 加 `aiDoneCount` / `aiPendingCount` / `aiFailedCount`
- 前端成功卡片在 `aiFailedCount > 0` 时多显示一行：「其中 3 道题未生成解析，不影响做题」
- 没失败时不显示 —— 避免噪音

⚠️ **这三个数不落库，每次现算**。因为 `question.ai_status` 已经是唯一真相源，再存一份就有两个真相源，
写的时机稍微对不上界面就说错话。**能算出来的就别存。**

**5. 顺手删掉"每 5 题落一次进度"**

运行期间没人读库里的这个进度（前端轮询优先读内存），而"跑到哪了"可以随时从 `ai_status` 现算。
500 题就是少写 100 次数据库。

### 两个连带发现的坑（都是"改行为"引出来的）

**坑 1：界面文案变成假话了**

失败卡片上写着「数据已回滚，不会产生脏数据」—— 在旧逻辑下这是**真话**（导入失败=事务回滚）。
但新增"账号级中断"这条路之后，**题库和题目其实已经提交了**，同一句话变成了误导：用户会以为题库没了。

修法：按错误信息前缀（`AI 解析中断：`）区分两种情况，显示不同文案。这是**智能体在实现时提问发现的**。

**坑 2：长文本把重要信息挤掉了**

40021 的消息是 `"AI 解析中断：" + 服务商原样报文 + "。已完成 N 道…"`，而它最终会被
`briefReason()` **截断到 200 字**。算一下：固定部分约 49 字，留给报文 151 字，
而真实的 402 报文约 130~140 字 —— **卡在边界上**；报文一长，「已完成 N 道 / 还差 M 道」就被吃掉，
而这恰恰是用户最需要知道的。

修法：**把长报文挪到最后**。这样即使被截断，用户仍能看到"已完成多少、还差多少、题能用、下一步干啥"。

> 通用原则：**要截断的文本，把不重要的放后面。**

### 新增错误码

| code | 含义 |
|---|---|
| `40021` | AI 解析中断（账号级问题：余额不足 / 限流 / Key 失效） |

### 顺手修掉一个偶发红的测试

跑全量测试时发现 `BanksServiceImplTest` **偶发**报 ERROR，错误信息是：

```
Failed to close extension context
Caused by: IOException: Failed to delete temp directory ...\junit-...
The following paths could not be deleted: <uuid>.txt
```

**根因**：被测的 `importBanks` 会起后台线程去读刚落盘的上传文件，而 JUnit 的 `@TempDir`
在测试方法结束后**立刻递归删目录** —— 此时后台线程可能还攥着那个文件句柄，
Windows 上删不掉，JUnit 就把"清理失败"报成测试 ERROR。

**症状很迷惑人**：断言全过、却偶发失败，而且报的是 JUnit 自己清理目录时报的错，跟被测代码毫无关系。
（已确认：单独跑该测试类 3 次全绿，且它与本批 Java 改动无关。）

**修法**：测试里不用 `@TempDir`，改成整个测试类共用一个临时目录、不做删除。
代价是系统临时目录里留下几个几十字节的小文件，换来的是**测试结果的确定性** ——
一个偶发红的套件没法当回归门禁。

**验证**：连续 3 次全量 `mvn test`，都是 `Tests run: 99, Failures: 0, Errors: 0`。

### 怎么验证的

```
mvn test  →  99 个用例全绿（基线 87 + 新增 12）；连续 3 次运行结果一致
vue-tsc --noEmit  →  退出码 0
npm run build     →  构建成功
```

新增 `BanksServiceImplAiEnrichTest`（12 个用例），覆盖：
单题失败不抛异常、`insufficient balance` 识别、`429`/`rate limit` 识别、
普通错误不误判、FAILED→PENDING 的 SQL 条件与取值、中文「余额不足/限流」、
原因包在 cause 里、**异常链有环不死循环**、aiEnrich 真的触发回改、`Message` 计数、bankId 为 null 不查库、
以及 **40021 的措辞契约**（前缀不能变 + 「已完成 N 道」必须排在服务商报文之前）。

### 明确没做（避免过度设计）

- ❌ 续跑接口（`POST /resume`）
- ❌ 启动时收尾孤儿任务的 `@EventListener`
- ❌ 前端「继续解析」按钮
- ❌ 任何数据库加列（`ai_status` 已够用）

> 说明：这四项曾经实现过一版，但因为"**为省几道题的 token 引入了整套机制**"而复杂度不划算，已撤销。
> 本批是简化版：**单题失败跳过 + 账号级失败停手 + 把数字告诉用户**，这就够了。

### 涉及文件

```
改动  asteria-server/.../Services/impl/BanksServiceImpl.java   （6 项后端逻辑）
改动  asteria-pojo/.../entity/VO/BankResultVO.java             加 3 个计数字段（不落库）
改动  asteria-ai/src/types/index.ts                            ImportTask 加 3 个可选字段
改动  asteria-ai/src/views/banks/BankImportView.vue            成功卡片提示 + 失败卡片文案条件化
改动  asteria-server/src/test/.../BanksServiceImplTest.java     修掉 @TempDir 引发的偶发失败
新增  asteria-server/src/test/.../BanksServiceImplAiEnrichTest.java   12 个用例
新增  docs/实施计划-AI解析部分失败.md                           交接给实现智能体的计划
改动  docs/学习笔记-AI解析优化.md                               新增第 8 节 + Q10~Q16
改动  README.md                                                  新增 40021 错误码 + 功能说明
```

### 已知不足（明确记录，不是疏漏）

- **「续跑」没有界面入口**：后端做到了"已解析的题不重复花钱"，但用户没有按钮触发它，
  只有"重新上传"（会建新题库、全部重新解析）。失败题不多时够用，多了会浪费
- **`Message()` 每次轮询多 3 次 COUNT 查询**：几百题规模无感；上万题 + 多人轮询时应改成
  一次 `GROUP BY ai_status` 聚合
- **`aiPendingCount` 不含 FAILED**：单题 FAILED 的题不会自动重试（没有续跑入口）。
  三个计数加起来恒等于总题数，但"没解析的题"实际要算上 FAILED 那部分

---

## 2026-10-03 · 修复「解析自检」的两个误报（中文答案 + 答案全文）

### 怎么回事

有次被问"`questionQualityCheck.check()` 这个方法怎么不见了"——**方法没丢**（第 277 行一直在调用），
但它只打日志、界面上看不见，所以"存在但没感觉"。

于是写了个测试拿**真实学习通格式**跑一遍自检，结果**当场抓到两个同源误报**：

```
[第 2 题] 答案越界 —— 答案字母 [H, T, M, L] 不在选项 [A, B, C, D] 里
```

**问题一：中文答案被当成字母**

```java
Character.isLetter('错')   // → true！中文字符在 Java 眼里也是"字母"
```

判断题的答案是「对」/「错」这种中文，于是**每道判断题都会被报一次"答案越界"**。

**问题二：答案全文里的英文被当成答案字母**

学习通导出的答案是 `D；标签和其属性构成了HTML元素;` 这种形态——**字母后面跟着答案全文**。
原来的代码扫整段答案找字母，于是"HTML"贡献了 H、T、M、L 四个假越界。
（同一个原因，`C；超文本标记语言;` 里的字母倒没触发，因为 C 恰好是合法选项。）

### 修法

不再扫整段答案，改成**只取开头的选项字母部分**：

```
"D；标签...HTML元素;"  →  [D]      只取 D，后面的解释文字不看
"A,C；甲和乙;"        →  [A, C]   多选，逗号是字母之间的分隔符就继续
"错"                  →  []        中文答案没有字母，跳过校验
```

同时把字母判定从 `Character.isLetter()` 改成**只认 ASCII 字母**。

### 为什么这件事值得单独记一笔

这个自检是"发现问题用的探针"，**探针误报满满等于没有探针**。

如果没写这个基于真实格式的测试，它会一直**静默地刷假警报**：
每次导入日志里都写着"答案越界 N 处"，实际一个真问题都没有。
时间一长就再也没人看那行日志了——**工具就废了，而且是无声地废掉**。

### 怎么验证的

```
mvn test  →  87 个用例全绿（本次新增 7 个）
```

新增 `QuestionQualityCheckRealFormatTest`（用真实学习通格式跑）+ 4 个针对性回归用例：

| 用例 | 防的是什么 |
|---|---|
| 「字母；答案全文」形式 | 全文里的英文被当答案字母（本次 bug 二） |
| 中文答案「对」/「错」 | 中文字符被 `isLetter()` 当成字母（本次 bug 一） |
| 多选 `A,C；甲和乙;` | 修复后多选两个字母仍都要校验 |
| **真越界（D 但只有 A/B/C）必须还能抓到** | **防止修复过头把探针修失灵** |

最后一条特别重要：修 bug 时最容易连"探针本来的功能"一起修掉，
所以专门留了个用例确保**真问题还是能报出来**。

修复后对真实格式的输出（`问题分布：{}` 表示零误报）：

```
总题数：3
题目类型=单选题 | 答案=[C；超文本标记语言;] | 选项=[A, B, C, D] | 可疑行数=0
题目类型=单选题 | 答案=[D；标签和其属性构成了HTML元素;] | 选项=[A, B, C, D] | 可疑行数=0
题目类型=判断题 | 答案=[错] | 选项=[A, B] | 可疑行数=0
问题分布：{}
```

### 涉及文件

```
新增  asteria-server/src/test/java/com/asteria/server/parser/QuestionQualityCheckRealFormatTest.java
改动  asteria-server/src/main/java/com/asteria/server/parser/QuestionQualityCheck.java
改动  asteria-server/src/test/java/com/asteria/server/parser/QuestionQualityCheckTest.java
```

---

## 2026-10-03 · AI 解析改造：并发 + 断点续跑 + 数据血缘

### 改了什么

上一批修完 bug 后，AI 解析的三个硬伤开始处理。**核心认知是一条**：模型调用要花钱，而且**不是幂等的**——重试就真的再付一次钱。所以"记录做没做过"比"跑得快"更重要。

**1. 并发解析（原来是一道一道串行）**

原来 500 道题就是 500 次串行网络往返，约 25 分钟，而 CPU 全程闲着（典型的 I/O 密集）。

- 改用 **JDK 21 虚拟线程**（`Executors.newVirtualThreadPerTaskExecutor()`）并发跑
- 用 **`Semaphore(6)`** 限制同时在飞的请求数 —— 并发度按数据库连接池反推：HikariCP 默认 10 条连接，进度和任务状态也要写库，所以留余量取 6
- 为什么不用固定大小线程池：JEP 444 明确建议不要用池来限制虚拟线程的并发，而是用信号量。这两件事应该解耦

**2. 断点续跑（原来崩了要全部重跑）**

- 新增 `question.ai_status`（PENDING / DONE / FAILED）+ `ai_error` + `ai_retry_count`
- 每次只捞**没到 DONE** 的题，所以崩溃后重跑只处理没做完的那部分，不重复付费
- 失败的题标 FAILED 并记下脱敏后的原因，下次续跑自然会被重新捞到

**3. 数据血缘（原来分不清答案是文件写的还是 AI 猜的）**

- 新增 `question.answer_source`（FILE / AI / MANUAL）+ `ai_enriched_at`
- `QuestionAiEnricher.EnrichResult` 加了 `answerFromAi` 标记，把"这个答案是模型给的"显式表达出来（原来只靠 `answer == null` 隐含表达，语义不清）
- 关键保证：**题目原本有答案时，绝不用 AI 结果覆盖** —— 文件里的答案比模型可信
- 存量数据不需要手工处理：`answer_source` 默认 `FILE`，加列之前的答案确实都来自原文

**4. 数据库迁移脚本**

`db/question_ai_columns.sql`：用 `information_schema` 判断列是否存在再决定要不要加，**可重复执行**（MySQL 的 `ADD COLUMN IF NOT EXISTS` 只在 8.0.29+ 支持，这样写兼容更老的 8.0，也不会重复执行报 1060）。

已在本地库执行，5 列 + 1 索引全部就位，存量 133 道题自动拿到正确默认值。

### 并发下的正确性处理

| 问题 | 处理 |
|---|---|
| 多线程同时 +1 | 计数用 `AtomicInteger` |
| 多线程同时写同一行任务进度 → 互相覆盖 | 进度写库加 `synchronized` |
| 信号量名额泄漏 | `release()` 放 `finally` |
| 中断（服务关闭） | 捕获 `InterruptedException`，归还中断标志后放弃该题；该题仍是 PENDING，续跑不丢 |

### 怎么验证的

```
mvn test  →  80 个用例全绿（改造前 65 个）
```

新增 15 个用例（`QuestionAiEnricherTest`）：

- **返回值契约**：`analysisOnly` / `withAnswer` 两个工厂的语义（`answerFromAi` 有没有正确表达血缘）
- **提示词**：已有答案时要带上原答案并声明"以它为准"；缺答案时要求模型解出来；不同题型给不同格式要求
- **答案格式校验**：单选只认单字母、多选去重升序、判断题各种写法收敛到 A/B、填空/简答原样保留
- **端到端**（模型用 Mockito 替身）：缺答案时补出来并标 AI 来源；有答案时绝不覆盖；格式不合法只丢答案保解析；LaTeX 公式不被改坏；解析为空要抛异常
- **回归护栏**：源码里必须还调用 `AiJsonRepair.repairBackslashes`（防止有人顺手删掉上一批的修复）

### 踩过的坑

- **`Semaphore.acquire()` 抛受检异常**：并发改造第一次编译不过，`InterruptedException` 必须处理。修法不是简单吞掉，而是**归还中断标志再退出** —— 吞掉中断会让服务无法优雅关闭
- **Mockito 不允许嵌套 stub**：`when(factory.create(any(), any())).thenReturn(mock(Model.class))` 会抛 `UnfinishedStubbingException`，因为 `when(...)` 参数里又调了 `mock()`。必须先把替身造好再设行为
- **测试假设错了**：我原本断言"多选只给一个字母要丢掉"，但实际代码接受 `A`。想了一下是**测试写错了而不是代码错了**：题型已经由文件确定为多选，模型只是补了个答案，因为"只有一个字母"就丢掉会让这道题永远没答案。改成断言接受，并把理由写进注释
- **新增实体字段必须同步迁移数据库**：给 `Question` 加字段后，MyBatis-Plus 的 SELECT 会带上这些列，**列不存在就全线报错**。所以 DDL 必须和代码一起交付

### 涉及文件

```
新增  asteria-server/src/main/resources/db/question_ai_columns.sql
新增  asteria-pojo/src/main/java/com/asteria/pojo/enums/AiStatus.java
新增  asteria-pojo/src/main/java/com/asteria/pojo/enums/AnswerSource.java
新增  asteria-server/src/test/java/com/asteria/server/ai/QuestionAiEnricherTest.java
新增  docs/学习笔记-AI解析优化.md
新增  docs/学习笔记-并发代码精讲.md

改动  Question.java                加 5 个字段（ai_status/ai_error/ai_retry_count/answer_source/ai_enriched_at）
改动  BanksServiceImpl.java        aiEnrich 改并发 + 只捞未完成；新增 enrichOne / markEnrichFailed
改动  BanksImportTransactional.java 入库时显式写死 ai_status=PENDING、answer_source=FILE
改动  QuestionAiEnricher.java      EnrichResult 加 answerFromAi 标记 + 两个工厂方法
```

### 下一步（未做）

- [ ] 批量调用（一次请求多道题）—— 但要先实测前缀缓存的影响，见 `docs/演进方向-总体方案.md`
- [ ] 失败重试上限（`ai_retry_count` 已经在记，但还没用它做"失败 N 次就不再试"）
- [ ] 解析质量闸门：答案一致性校验 + UI 标"AI 生成" + 用户报错回流
- [ ] 前端暴露 `answer_source`，让用户能看出哪些解析是 AI 补的

### 顺带：调研发现的一个解析器隐患（未修，记录备查）

调研学习通官方文档时发现一条**语义冲突**：

> 超星官方对填空题的定义是「多个空答案**用空格隔开**，一个空有多个答案**用分号『；』隔开**」

而当前 `QuestionParser` 是按 `；` **切分答案**。这意味着：**填空题有多个空时，切出来的结构会和官方语义不一致**——官方用 `；` 表示"同一个空的多种写法"，我们把它当成了"多个空的分隔符"。

用户目前的数据里**一道填空题都没有**（7 个文件全是单选+判断），所以这个隐患还没暴露。等真的要支持填空题时需要重新设计切分规则。详见 `docs/改造计划.md`。

---

## 更早的改动

本文件从 2026-10-03 开始记录。之前的改动见 git 提交历史：

```
git log --oneline
```
