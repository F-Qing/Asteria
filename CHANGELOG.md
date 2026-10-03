# 更新日志

本文件记录项目的**每次修复与改造**，按时间倒序排列（最新的在最上面）。

写这个文件的原因：改动背后的**取舍和踩过的坑**比"改了什么"更有价值，但它们散落在代码注释和提交记录里，时间一长就找不回来了。

> 详细的原理讲解见 [`docs/学习笔记-Bug修复.md`](docs/学习笔记-Bug修复.md)。
> 规划中的改造见 [`docs/改造计划.md`](docs/改造计划.md) 与 [`docs/演进方向-总体方案.md`](docs/演进方向-总体方案.md)。

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

## 更早的改动

本文件从 2026-10-03 开始记录。之前的改动见 git 提交历史：

```
git log --oneline
```
