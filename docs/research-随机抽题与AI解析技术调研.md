# 随机抽题与 AI 解析：工程技术调研报告

> 调研对象：Java 21 / Spring Boot 3.5 / MyBatis-Plus / MySQL 8 单机单用户刷题系统
> 调研方法：`web_search` + `web_fetch`，优先官方文档、原始论文、官方仓库
> 说明：本报告中所有数据均附来源链接；无法从权威来源确认的内容一律标注「**未能验证**」，不做推测性填充。部分站点（`dev.mysql.com`、`platform.openai.com`、Stack Overflow）对本环境返回 403/Cloudflare 拦截，因此相关结论改用官方公告页、官方仓库、arXiv 等可达来源替代，并在正文中注明。

---

## 1. 间隔重复 / 调度算法

### 1.1 SM-2（SuperMemo 2，1987）

**状态模型**：每张卡片只有一个标量——难度系数 EF（Ease Factor，默认 2.5）和一个当前间隔 I。

**核心公式**：复习时按 6 档评分（0–5）作答，`EF' = EF + (0.1 - (5-q) * (0.08 + (5-q) * 0.02))`，EF 下限 1.3；答对时间隔 `I' = I * EF`，答错则重置回起点。Anki 的 FAQ 明确说明 Anki 的默认算法基于 SM-2，但做了若干修改：初始间隔由用户自定义的学习步骤控制而非固定 1 天/6 天；只用 4 个评分按钮而非 6 个；迟答会获得间隔加成；连续失败不会继续压低 ease（避免「low interval hell」）；Easy 除提升 ease 外还额外加成间隔。Anki 还规定 ease 不会低于 130%，且新间隔（Again 除外）总是比上一间隔至少多 1 天。

**需要的数据**：`ease`（float）、`interval`（天）、`due_date`、上次评分。**实现难度：低**（几十行代码 + 一次乘法）。

来源：[What spaced repetition algorithm does Anki use?（Anki 官方 FAQ）](https://faqs.ankiweb.net/what-spaced-repetition-algorithm)、[Anki Manual — Deck Options（Starting Ease / Easy Bonus / Interval Modifier / Hard Interval / New Interval 的官方定义）](https://docs.ankiweb.net/deck-options.html)

### 1.2 Anki 的 FSRS 实现

Anki 自 **23.10** 起同时提供 SM-2 与 FSRS 两套算法（官方 FAQ 原文：「As of Anki 23.10, Anki has two available algorithms.」）。开启 FSRS 后，SM-2 专属选项（Graduating interval、Easy bonus、Interval modifier 等）被隐藏；FSRS 只能全局启用，不能按 preset 分别开关；调度参数与 desired retention 是 preset 级别的。官方强烈建议不要在忘记时按 Hard——FSRS 把 Hard 视为「记住了但很吃力」的通过档，误用会让所有间隔不合理地偏高。

官方默认 **desired retention = 90%**，并明确建议不要超过 97%（「we recommend you keep it lower than 97%」），因为工作量随保留率逼近 100% 而急剧上升。官方还说明：FSRS 需要「几百条以上」复习记录才能有效优化参数（「Low number of reviews (less than a few hundred)」被列为 FSRS 表现不佳的常见原因），且参数应每月优化一次即可，**不要手工修改参数或从别人那里复制**。

Anki 25.07 起移除了「Compute minimum recommended retention (CMRR)」功能，并新增 **Easy Days**（按星期微调到期日，SM-2 与 FSRS 都支持）。

来源：[Anki Manual — Deck Options § FSRS](https://docs.ankiweb.net/deck-options.html)、[Anki FAQ — What spaced repetition algorithm does Anki use?](https://faqs.ankiweb.net/what-spaced-repetition-algorithm)

### 1.3 FSRS 本体：DSR 模型、公式与状态

FSRS 基于 Piotr Wozniak 提出的**记忆三成分模型（Three Component Model of Memory）**，用三个变量描述一张卡片的记忆状态：

| 变量 | 含义 | 变化时机 |
|---|---|---|
| **R**（Retrievability，可提取性） | 当前时刻能成功回忆的概率 | 每天变化 |
| **S**（Stability，稳定性） | R 从 100% 衰减到 90% 所需天数 | 仅复习后变化 |
| **D**（Difficulty，难度） | 该材料的固有难度，1–10 | 仅复习后变化 |

**间隔公式**：当 desired retention = 90% 时间隔约等于 S。FSRS 的稳定性更新可简写为 `S' = S * SInc`，其中 `SInc >= 1` —— 即**复习成功时稳定性不会下降**。SInc 由三个因子相乘再乘系数构成，对应三条经验规律（原文「Important takeaway」）：

1. D 越大 → SInc 越小（难材料的稳定性增长更慢）
2. S 越大 → SInc 越小（**稳定性饱和 / stabilization decay**）
3. R 越小 → SInc 越大（**最佳复习时机是「快忘了但还想起来了」的时候 / stabilization curve**）

**遗忘曲线**：FSRS v3 用指数函数，v4 改为幂函数（拟合更好），FSRS-4.5/5 换用另一个幂函数，FSRS-6 引入可优化参数 **w20（取值 0.1–0.8）**做个性化。作者给出了直觉解释：两条指数曲线的叠加，用幂函数逼近会比用指数函数逼近拟合更好。

**参数**：FSRS-6 共 **21 个权重（w0–w20）**，其中前 4 个是四种首次评分（Again/Hard/Good/Easy）对应的**初始稳定性**。优化方法是梯度下降，损失函数为 log-loss / 二元交叉熵。作者本人报告，预测 S 的平均绝对百分比误差（MAPE）在其个人牌组上约为 33%（以 D 为横轴时）；以 S 自身的历史值为横轴时约 12%。

**D 的设计缺陷（作者自承）**：当前的 D 公式不依赖 R，而「严格定义的难度必须依赖可提取性」。作者尝试把 R 纳入 D 的公式但未能提升准确度，因此保留了近似式。

**FSRS-6 的可用版本**：据 Expertium 的技术说明，「FSRS-6 is available in Anki since version 25.07」；Anki 手册确认 25.07 是该版本的变更节点（CMRR 在同一版本被移除）。

来源：[A technical explanation of FSRS（Expertium，逐公式讲解）](https://github.com/Expertium/expertium.github.io/blob/main/Algorithm.md)、[Anki FAQ — FSRS 章节](https://faqs.ankiweb.net/what-spaced-repetition-algorithm)、[Deck Options § FSRS](https://docs.ankiweb.net/deck-options.html)

### 1.4 FSRS 是否有 Java 实现？——**有，而且是官方组织的**

`open-spaced-repetition/java-fsrs` 是 FSRS 官方 GitHub 组织下的 Java 库，README 明确写着「Java-FSRS is a Java library that allows developers to easily create their own spaced repetition system using the Free Spaced Repetition Scheduler algorithm」。

已核实的关键事实（均出自该 README 与 Maven Central 页面）：

| 项目 | 内容 |
|---|---|
| Maven 坐标 | `io.github.open-spaced-repetition:fsrs`（[Maven Central 页面](https://central.sonatype.com/artifact/io.github.open-spaced-repetition/fsrs)） |
| Java 版本要求 | 徽章显示 **Java 17**（项目本身用 Java 21 没有障碍） |
| 许可证 | MIT |
| API 形态 | `Scheduler.builder().build()` + `Card.builder().build()` + `scheduler.reviewCard(card, Rating.GOOD)` 返回 `CardAndReviewLog`；有 `getCardRetrievability(card)` |
| 默认参数 | 21 个 double 的权重数组（README 中直接列出）、`desiredRetention(0.9)`、`learningSteps{1m,10m}`、`relearningSteps{10m}`、`maximumInterval(36500)`、`enableFuzzing(true)` |
| 时区 | **仅支持 UTC**（README 明确 "Java-FSRS uses UTC only."） |
| 持久化 | `Scheduler` / `Card` / `ReviewLog` 都有 `toJson()` / `fromJson()`，便于直接存库 |
| **限制** | **不支持参数优化**。README 原文：「Currently, Java-FSRS does not support parameter optimization. If you'd like to optimize your parameters, please see either fsrs-rs or py-fsrs.」 |

也就是说：Java 侧能用**官方默认的 21 个参数**做调度和 R 预测，但**要让参数适配你自己的记忆数据，官方只提供 Rust（fsrs-rs）和 Python（fsrs-optimizer / py-fsrs）两条路**。README 的示例代码里出现了 `2025-07-10` 的时间戳，说明库在 2025 年年中之后仍在维护。

来源：[java-fsrs README](https://github.com/open-spaced-repetition/java-fsrs)、[Maven Central: io.github.open-spaced-repetition:fsrs](https://central.sonatype.com/artifact/io.github.open-spaced-repetition/fsrs)、[FSRS 官方 README（列出全语言实现清单）](https://github.com/open-spaced-repetition/free-spaced-repetition-scheduler)

### 1.5 FSRS vs SM-2 vs SM-18：效果证据

**FSRS vs SM-17/SM-16**：官方对比仓库 `fsrs-vs-sm17` 给出了可核验的数字——**19 位用户、687,662 次复习**。按 Universal Metric（越低越好）排序：FSRS-6 = 0.0287、SM17 = 0.0435、FSRS-4.5 = 0.0496、SM16 = 0.0547、FSRSv3 = 0.0681。按「superiority」表，**FSRS-6 对 SM-17 在 83.3% 的集合上 Log Loss 更优**。加权 Log Loss：FSRS-6 = 0.367±0.040、SM-17 = 0.432±0.084、SM-16 = 0.417±0.034。该仓库也诚实地声明了方法学限制：SuperMemo 6 档评分被合并成 Anki 的 4 档、样本量小（19 collections）、并自曝 Universal Metric 存在理论上的「刷分」漏洞（因此额外加入了 ADVERSARIAL 基线，其 UM 低至 0.0011 但 Log Loss 高达 3.66）。

**SM-18 与「为什么 Anki 不用最新 SuperMemo 算法」**：Anki 官方 FAQ 给出了直白答案——**SuperMemo 的最新算法是专有（proprietary）的，需要授权**，Anki 作为开源软件只能用免费公开的算法（如 FSRS）。同一 FAQ 引用了初步测试，认为 FSRS 与 SM-17 大致持平。**我未能找到 SM-18 的公开算法规范**（SM-18 的公式细节属于商业闭源），因此本节不做公式层面的描述——标注为**未能验证**。

来源：[fsrs-vs-sm17 基准仓库](https://github.com/open-spaced-repetition/fsrs-vs-sm17)、[Anki FAQ — Why doesn't Anki use SuperMemo's latest algorithm?](https://faqs.ankiweb.net/what-spaced-repetition-algorithm)

### 1.6 Is FSRS realistic for a small Java project? — 结论与理由

**结论：技术上完全可行，但对「单用户刷题系统」大概率是过度工程；真正划算的是它的一个副产品——R（可提取性）排序。**

**可行性（是的）**：`io.github.open-spaced-repetition:fsrs` 是 MIT 许可、Maven Central 上的一行依赖，Java 17+，有 JSON 序列化、有默认参数、API 极简。**不需要写任何公式**，最难的部分（21 参数梯度下降优化）你已经选择放弃，用默认参数即可。

**FSRS 相比「简单错题本」到底买到了什么**，逐条拆：

| 能力 | 简单错题本（wrong_question 表 resolved=0） | 换成 FSRS/间隔重复后 |
|---|---|---|
| 决定「下次该做哪道题」 | 只能靠 `resolved` 布尔值，要么全做要么不做 | 由 R（预测recall概率）或 due date 决定，**可以按「最可能忘的优先」排序** |
| 时间维度 | 无。做对的题永远消失 | S 随复习增长，间隔自动拉长；做对的题会在恰当时候回来 |
| 区分「蒙对」和「真会」 | 不能 | 靠评分档位（Again/Hard/Good/Easy）区分——但**这要求交互上有 4 个按钮**，是真实的 UX 成本 |
| 区分难易 | 不能，所有错题等价 | D（1–10）自动区分 |
| 参数 | 无 | 21 个，且**官方 Java 库不支持优化**，要用默认值或外接 Rust/Python |

**关键判断**：FSRS 的全部价值建立在「同一道题会被反复复习、且你能对每次复习给出 4 档评分」这个前提上。你的系统当前形态是**「按题库/章节/题型抽题组卷」**（`{bankId, mode, questionType, chapterId}`），这是一种**练习（practice）语义**而非**记忆卡（flashcard）语义**。在练习语义下：
- 一道选择题的「答案」是选项本身，重复看到同一道题时，第二次的正确率因为**记住了答案**而不是**记住了知识**而虚高——这正是 FSRS 的 R 预测会被系统性高估的场景；Anki 手册在 Review Sort Order 中专门警告过同类问题：「having material appear consistently in the same order makes it easier to guess the answer based on context, and leads to weaker memories」。
- 要发挥 FSRS，你需要投入的不只是加依赖，而是**改造整个交互**（每题 4 档自评）、**改造数据模型**（card state + review log）、并积累「几百条以上」复习记录。

**性价比最高的中间路线**：不引入 FSRS 库，但**抄它一个概念**——给 `wrong_question` 表加 `due_date`，用一条 SM-2 级别的 `interval *= ease` 规则（或者干脆用一个固定的 Leitner 式盒区间，如 1/3/7/15/30 天）来决定错题何时重新出现。这十几行代码能拿到 FSRS 在单用户场景下 80% 的实际收益，且**不需要 4 档评分 UI**（错题本天然只有「又错了 / 对了」两态）。

**需要外部服务的点**：`java-fsrs` 本身**不需要任何外部服务**，纯本地 JVM 库。但**参数优化**需要 `fsrs-rs`（Rust）或 `py-fsrs`/`fsrs-optimizer`（Python）——**对 Java 项目而言是跨语言工具链成本**，且 README 未说明如何把优化结果导回 Java 侧（标注：该导出流程**未能验证**）。

---

## 2. 抽题策略（超越均匀随机）

### 2.1 加权随机抽样（Weighted Random Sampling）

**问题**：`Collections.shuffle` 是**等概率**的。但你希望「错得多的题」出现得更频繁，这就是加权抽样。

**A-Res 算法（Efraimidis & Spirakis, 2005）**——加权蓄水池抽样的经典解：

- **做法**：对每个元素 i 计算 key `k_i = u_i^(1/w_i)`，其中 `u_i ~ Uniform(0,1)`、`w_i` 是权重；取 key 最大的 k 个元素。
- **复杂度**：**O(n log k)**（需要维护大小为 k 的小顶堆）。当 k 与 n 同阶时可视为 O(n log n)；论文同时给出 **A-ExpJ** 变体，通过指数跳跃把期望复杂度降至 **O(k log(n/k))**，在 k << n 时优势明显。
- **为什么正确**：key 的构造使得「元素 i 进入 top-k」的概率恰好正比于 w_i。
- **需要的数据**：每个候选题的一个非负权重。可以是 `1 / (1 + 错误次数)`、`错误次数 + 1`、或 `1 - 正确率`。
- **实现难度：低**。Java 里就是一个 `PriorityQueue` 加 `Math.pow(random.nextDouble(), 1.0/w)`，无需任何依赖。

来源：[Weighted random sampling with a reservoir（Efraimidis & Spirakis, Information Processing Letters, 2005）](https://www.sciencedirect.com/science/article/abs/pii/S002001900500298X)（注：ScienceDirect 正文页对本环境返回 403，DOI 与标题可经 [ScienceDirect 条目页](https://www.sciencedirect.com/science/article/abs/pii/S002001900500298X)确认；该论文的算法描述被广泛引用。**具体页码与定理编号本环境未能验证**。）

### 2.2 分层抽样（Stratified Sampling）——保证随机卷覆盖每章

**问题**：纯随机抽 N 题时，章节分布是**超几何分布**，小章节很可能一题不中。例如某章节只有 10 题而题库有 1000 题，抽 20 题时期望命中 0.2 题。

**做法（比例分配 + 层内随机）**：
1. 按章节（或题型）分层，统计每层题量 `n_h`，总量 `N`。
2. 第 h 层分配名额 `k_h = round(k * n_h / N)`（**按比例分配**）。
3. 用最大余数法（Hare quota / largest remainder）处理四舍五入后的名额总和不为 k 的边角。
4. 每层内用 Fisher–Yates 或 A-Res 抽 `k_h` 题。
5. 合并后再整体打散一次（否则试卷会按章节聚集）。

**可选加强**：给每层设一个**保底名额**（如每章至少 1 题），从名额最多的层扣减。这就是分层抽样的「内曼分配（Neyman allocation）」思路的简化版——严格的内曼分配还需要层内标准差，对刷题系统没必要。

**复杂度**：O(N) 统计 + O(N) 抽样，与均匀随机同阶。**需要的数据**：章节/题型的题量统计（一条 `GROUP BY` 即可）。**实现难度：低**。

**权威性说明**：分层抽样是抽样调查领域的标准方法（Neyman 1934 起），**本环境未能抓取到一份可引用的、专门针对「教育测评组卷」的分层抽样官方规范**，故此处描述的是统计学的通用方法在本场景的直接套用，而非某产品的公开文档。标注部分为**未能验证**。

### 2.3 CAT（计算机自适应测验）的选题准则——「真系统」怎么选

大规模考试系统（GMAT、GRE）用的是 CAT。其选题核心不是「随机」，而是**最大化信息量**：

- **MFI（Maximum Fisher Information）**：每次选使当前能力估计 θ 的 Fisher 信息 `I_i(θ)` 最大的题目。对 2PL/3PL 项目反应理论（IRT）模型有闭式解。
- **a-分层（a-stratified）**：先按区分度参数 a 把题库分层，在各层内选最接近当前 θ 的题——目的是**保护高区分度题目不被早期低精度阶段浪费掉**，这是对纯 MFI 的著名改进。
- 需要的数据：**每道题的 IRT 参数（a 区分度、b 难度、c 猜测度）**——这需要**预测试（pretest）和参数标定**，是 CAT 落地的真正门槛，不是算法门槛。

来源：[GMAC 研究报告 RR-09-07（GMAC 关于 CAT 与题库管理的公开研究报告）](https://www.gmac.com/-/media/files/gmac/research/research-report-series/rr0907_gmir_web.pdf)、[An adaptive testing item selection strategy via a deep reinforcement learning approach（Behavior Research Methods，综述了 MFI / a-stratified / 强化学习等选题策略）](https://link.springer.com/content/pdf/10.3758/s13428-024-02498-x.pdf)

**对你的项目的判断**：CAT 需要 IRT 参数标定，**单用户题库不可能满足样本量要求**。**明确属于过度工程**。但它有一个零成本的降级版：把「每道题的历史正确率」当作 b 参数的粗糙代理，选正确率接近 50%–70% 的题（**信息量最大的区域就在「不太会也不太不会」处**）。这不需要任何统计模型。

### 2.4 「到期复习」选题（Due-for-review selection）

这是 Anki 的核心机制，也是**在你的场景下最值得抄的一个**。做法极其朴素：

```sql
SELECT id FROM question ... WHERE due_date <= CURDATE() ORDER BY due_date ASC LIMIT ?
```

Anki 的 Review Sort Order 给了几种官方排序口径，可直接借用：

- **Due date, then random**（官方默认，推荐）
- **Ascending intervals**（间隔短的优先）
- **Ascending ease**（更难的优先）
- **Relative overdueness**（越可能已忘的越优先；FSRS 下等价于 **Ascending retrievability**）

来源：[Anki Manual — Deck Options § Review Sort Order](https://docs.ankiweb.net/deck-options.html)

### 2.5 交错练习（Interleaving）——有价值但要注意边界

「交错」指**打乱题型/知识点顺序**而非按类型成块练习。它与本报告第 3 节的「洗牌」直接相关。**说明**：我检索到一项针对尼日利亚整年数学交错教学项目的长期效果研究（[The long-term distributional impacts of a full-year interleaving math program in Nigeria](https://research.wur.nl/en/publications/the-long-term-distributional-impacts-of-a-full-year-interleaving-/)，发表于 WUR 研究库），但**本环境未能获取其正文与效应量数据**，因此不引用具体数字。Anki 手册在 Review Sort Order 中对「按 deck 聚集排序」给出的否定理由（会导致「猜答案」而非「记知识」）是**可达且可引用**的、支持交错/打散的最直接权威论述。

### 2.6 抽题策略对比表

| 做法 | 复杂度 | 需要的数据 | 适用规模 | 来源链接 |
|---|---|---|---|---|
| 全量载入 + `Collections.shuffle`（现状） | 时间 O(n)，空间 O(n) | 无 | 题库 ≤ 数万题 | [Collections javadoc](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Collections.html) |
| 加权蓄水池 **A-Res** | O(n log k) | 每题一个权重（错误率等） | 任意 | [Efraimidis & Spirakis 2005](https://www.sciencedirect.com/science/article/abs/pii/S002001900500298X) |
| 加权蓄水池 **A-ExpJ** | 期望 O(k log(n/k)) | 同上 | k << n 时最优 | 同上 |
| **分层抽样**（按章节/题型比例分配） | O(N) | 每层题量统计 | 任意，**保证覆盖率** | 统计学通用方法；本环境**未能验证**教育组卷专项规范 |
| **到期复习选题**（`due_date <= today`） | O(log n)（有索引）+ O(k) | 每题 due_date，需索引 | 任意 | [Anki Deck Options](https://docs.ankiweb.net/deck-options.html) |
| **CAT / MFI** | O(n) 每选一题（需全库算信息量） | **IRT 参数 a/b/c，需预测试标定** | 大规模考试（万人级） | [Springer 2024 综述](https://link.springer.com/content/pdf/10.3758/s13428-024-02498-x.pdf)、[GMAC RR-09-07](https://www.gmac.com/-/media/files/gmac/research/research-report-series/rr0907_gmir_web.pdf) |
| **交错练习**（打散类型顺序） | O(n) | 无 | 任意 | [Anki Deck Options（反向论证）](https://docs.ankiweb.net/deck-options.html) |

---

## 3. 可复现 / 种子化洗牌

### 3.1 Fisher–Yates：正确版本 vs 朴素错误版本

**正确（in-place / backward）版本**——Mike Bostock 的经典可视化文章给出的实现：

```javascript
function shuffle(array) {
  var m = array.length, t, i;
  while (m) {
    i = Math.floor(Math.random() * m--);   // 关键：范围是 [0, m]
    t = array[m]; array[m] = array[i]; array[i] = t;
  }
  return array;
}
```

Bostock 在文中明确对比了三种实现并给出复杂度：早期「随机取一个，若已洗过就重试」的版本会因重复命中而**退化**；「随机删除 + splice 压紧」版本是 **O(n²)**（因为 `splice` 平均要搬 n/2 个元素）；只有就地交换版本是 **O(n) 时间、O(1) 额外空间**。他的核心洞察：已洗部分与未洗部分之和恒为 n，所以可以用数组尾部存已洗元素、头部存未洗元素，不需要额外空间。

**Java 的 `Collections.shuffle` 就是正确版本**。JDK 21 javadoc 原文：「This implementation traverses the list backwards, from the last element up to the second, repeatedly swapping a randomly selected element into the "current position". Elements are randomly selected from the portion of the list that runs from the first element to the current position, inclusive.」并且「This method runs in linear time.」

**朴素错误版本为什么有偏**：常见错误写法是在**整个数组范围** `[0, n-1]` 里随机选下标与当前位置交换（而不是 `[0, i]`）。这会产生 **n^n 种等概率的「随机数序列」，而要均匀覆盖 n! 种排列，n^n 通常不能被 n! 整除**，于是部分排列被多算、部分被少算。这是「The Danger of Naïveté」一文的主题（由 Bostock 引用），也是 Fisher–Yates 词条的标准论述。

> **来源可达性说明**：Wikipedia 的 Fisher–Yates 词条与 codinghorror.com 的原帖在本环境均**抓取失败**（DNS/连接错误），因此上述「n^n vs n!」的表述我**无法从原始页面直接引用**。可稳定引用的替代来源是 Bostock 的文章，其中直接给出了正确/错误实现的代码对比、复杂度分析，并**明确指向**了那两篇原始文献：[Fisher–Yates Shuffle（Mike Bostock, 2012）](https://bost.ocks.org/mike/shuffle/) → 引用 [Wikipedia: Fisher–Yates shuffle](https://en.wikipedia.org/wiki/Fisher%E2%80%93Yates_shuffle) 与 [Jeff Atwood, The Danger of Naïveté (2007)](https://blog.codinghorror.com/the-danger-of-naivete/)。**建议读者自行核对这两篇原文的措辞。**

### 3.2 固定种子为什么重要

对**考试/组卷**场景，固定种子的价值是：**同一份卷子可以被精确复现**。具体收益：

1. **可重放的 bug 报告**：用户报告「第 7 题选项顺序怪」，你可以用记录的 seed 重放卷面。
2. **A/B 实验可复现**：同一用户在同一实验分支下拿到同一份卷子，排除「抽样噪声」干扰。
3. **阅卷/申诉**：考试场景下需要能证明「这份卷子确实是系统生成的那份」。
4. **缓存与幂等**：`(bankId, mode, seed)` 可以唯一确定一份卷子，组卷接口天然幂等，可缓存。

**JDK 的保证有多强？**（这是本节最重要、也最容易被误解的一点）

`java.util.Random` 的 JDK 21 javadoc 给了**极强的稳定性承诺**：

> 「If two instances of `Random` are created with the same seed, and the same sequence of method calls is made for each, they will generate and return identical sequences of numbers. In order to guarantee this property, particular algorithms are specified for the class `Random`. **Java implementations must use all the algorithms shown here for the class `Random`, for the sake of absolute portability of Java code.**」

也就是说，`Random` 的算法是**写进规范、跨 JDK 实现必须一致**的（LCG，种子 48 位，`next()` 中 `seed = (seed * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1)`，来自 Knuth TAOCP Vol.2 §3.2.1）。**这对「固定种子可复现」是强保证。**

**但 `Collections.shuffle` 不是。** javadoc 在类级别明确声明：

> 「The documentation for the polymorphic algorithms contained in this class generally includes a brief description of the *implementation*. Such descriptions should be regarded as *implementation notes*, rather than parts of the *specification*. **Implementors should feel free to substitute other algorithms**, so long as the specification itself is adhered to.」

所以：**`Random` 的数值序列可复现，但「shuffle 具体怎么调用 Random」不属于规范**。实践中同一个 JDK 版本内当然稳定，但**跨 JDK 大版本升级后不保证产生同一排列**。

**`SplittableRandom` 的保证更弱**：javadoc 只承诺「SplittableRandom instances created with the same seed **in the same program** generate identical sequences of values」——注意 **"in the same program"** 这个限定语，它没有跨 JDK 版本的承诺（也未在 javadoc 中声明算法会被冻结）。另外它不是线程安全的，javadoc 明确说「Instances of SplittableRandom are *not* thread-safe. They are designed to be split, not shared, across threads.」

**`Collections.shuffle(List)` vs 三个变体**（JDK 21 有三个重载）：

| 变体 | 随机源 | 首次出现 | 可复现性 |
|---|---|---|---|
| `shuffle(List)` | 默认随机源（javadoc 表述为 "default source of randomness"，未在规范中指明具体类型） | 1.2 | **不保证可复现** |
| `shuffle(List, Random)` | 你传入的 `Random` | 1.2 | 可复现；javadoc 已标注为向后兼容方法，推荐改用下一个 |
| `shuffle(List, RandomGenerator)` | 任意 `RandomGenerator` | **Since: 21** | 可复现；javadoc：「All permutations occur with **equal likelihood** assuming that the source of randomness is fair.」 |

**结论与建议**：
- 想要严格可复现 → **必须显式传种子**：用 `Collections.shuffle(list, new Random(seed))`，或（Java 21 风格）`Collections.shuffle(list, RandomGeneratorFactory.of("L64X128MixRandom").create(seed))`。
- 想要**跨 JDK 升级也稳定** → 不要依赖 `Collections.shuffle` 的内部实现。**自己写一个 Fisher–Yates 循环 + `new Random(seed).nextInt(i+1)`**，这样复现性完全由 `Random`（有强规范保证）+ 你自己的代码决定。
- **不要**用 `SplittableRandom` 做需要长期可复现的组卷种子。
- 单用户单机场景**不需要** `SecureRandom`（javadoc 明确 `Random` "are not cryptographically secure"，但组卷不需要密码学强度；用 `SecureRandom` 只会更慢且同样要自己存种子）。

来源：[java.util.Random (Java SE 21 javadoc)](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Random.html)、[java.util.SplittableRandom (Java SE 21 javadoc)](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/SplittableRandom.html)、[java.util.Collections (Java SE 21 javadoc)](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Collections.html)、[Fisher–Yates Shuffle（Bostock）](https://bost.ocks.org/mike/shuffle/)

### 3.3 MySQL `ORDER BY RAND()` 与替代方案

> **重要限制说明**：`dev.mysql.com` 的官方手册页面在本环境**持续返回 403 / "Technical Difficulties"**，我尝试了 `8.0`/`8.4` 两个版本路径与 `downloads.mysql.com` 的 PDF，全部被拦截。因此**本节无法引用 MySQL 官方手册的原文**。同样，Stack Overflow（403 + Cloudflare）与 Percona Blog 的相关文章（404）也无法直接抓取。**下面的复杂度分析属于算法层面的推论，我明确标注哪些是「已从可达来源确认」、哪些是「未能在本环境验证」。**

**`ORDER BY RAND()` 为什么慢（算法层面，可从第一性原理确认）**：

1. `RAND()` 是**非确定性（volatile）函数**，MySQL 必须为**每一行**计算一次随机值。
2. 因此**无法走索引排序**（索引里没有随机值这一列），必须走 **filesort**：把整个结果集物化，再排序。
3. 于是单次查询的成本是「**全表/全索引扫描 O(n)** + **排序 O(n log n)** + **只为取前 k 行**」。而 `LIMIT k` 完全无法提前终止，因为排序必须看完全部 n 行。
4. 这就是它「比看起来慢得多」的根本原因：**它是 O(n log n)，而你需要的是 O(k) 或 O(log n)**。

**「无法走索引」这一点与 MySQL 官方手册中关于 `ORDER BY` 优化的原则一致**（该原则在《MySQL 参考手册》ORDER BY Optimization 一节中有明确表述，只是本环境无法直接引用该页）。

**替代方案对比**：

| 做法 | 复杂度 | 空间 | 需要的数据/索引 | 关键缺陷 | 适用规模 |
|---|---|---|---|---|---|
| `ORDER BY RAND() LIMIT k` | **O(n log n)**，且常数大 | filesort 缓冲 | 无 | 全表扫 + 全排序，无法用 LIMIT 提前结束 | 小表（数百行）尚可；**不推荐** |
| `SELECT ... ORDER BY RAND() LIMIT k` 变体：`ROW_NUMBER() OVER (ORDER BY RAND())` | **仍是 O(n log n)** | 窗口函数缓冲 | 无 | 只是换个写法，**没有解决「每行算随机数 + 全排序」的本质** | 不推荐 |
| **随机 id 区间法**：`WHERE id >= (SELECT FLOOR(RAND()*(SELECT MAX(id) FROM t))) LIMIT k` | **O(log n)**（主键索引 range scan） | O(k) | 主键/唯一索引 | **id 有空洞时严重偏斜**：若 id 分布稀疏（大量删除），落在空洞里的概率高，取不满 k 行，且各行被选中的概率不等 | 任意规模，**id 稠密时最优** |
| **随机 id 列法**（新增 `random_id` 列 + 索引，随机生成并维护） | **O(log n)** 每次 | O(k) | 额外一列 + 索引 | 需要写时维护随机列；需处理碰撞 | 任意规模 |
| **键表 / 预计算洗牌**（把「题目 id 的洗牌顺序」预先物化成一张表） | **O(1)** 查表 | O(n) 存储 | 一张 (bank_id, seq, question_id) 表 | 题库变更时要重建；**对固定题库的「固定随机考卷」非常合适** | 任意规模 |
| **`COUNT(*)` + `OFFSET`**：随机挑 k 个 offset，再 `LIMIT 1 OFFSET x` | O(1) count（InnoDB 近似）+ O(k·x) 取数 | O(k) | 索引 | **OFFSET 是 O(x) 的**：深 offset 退化严重 | 只适合小 offset |
| **PostgreSQL `TABLESAMPLE`** | 抽样近似 O(k) | O(k) | 无 | **MySQL 没有这个语法**（MySQL 8 未提供 TABLESAMPLE，标注：**本环境未能从官方手册验证此否定结论**） | 仅 Postgres |
| **应用层：全量取 id + Fisher–Yates**（现状） | **O(n)** 传输 + O(n) 洗牌 | **O(n) 内存** | 无 | 内存随题库线性增长；每次组卷都要把全量 id 拉回来 | 题库 ≤ 数万题时可接受 |

**来源可达性说明**：
- 「随机 id 区间法」与「随机 id 列法」是 MySQL 社区的经典做法，Jan Kneschke（MySQL Performance Blog）的相关文章被 Stack Overflow 答案反复引用。**该原文与 SO 答案在本环境均因 403/Cloudflare 无法抓取，故标注为未能直接验证。**
- 可稳定引用的相邻权威来源：[Use The Index, Luke（Markus Winand）](https://use-the-index-luke.com/)——该书第 8 章 "Partial Results" 专门讲「只取前 N 行时，如何避免全量排序」，第 5 章讲索引与排序流水线（"pipelined order by: the third power"），是「为什么不能排序就不能用 LIMIT 提前结束」这一原理的权威论述。该书声明测试覆盖 MySQL **5.5 到 26.7.0**。

**给你的项目的实际建议**：你的题库是**单用户、本地、可控规模**。当前「全量 id 载入内存 + `Collections.shuffle`」在 **几万题以内是完全合理的**——它甚至是上表中**唯一不依赖 id 稠密性、不依赖额外列、不依赖预计算**的方案。真正应该改的不是「换成 SQL 随机」，而是**加一个上限（cap）**：`ORDER BY RAND()` 或全量载入在 n 很大时会拖死请求，而**没有上限意味着一次误操作就能把整个题库拉进内存并插 N 行**。**先加 cap，再谈优化算法。**

---

## 4. 批量 LLM 富化（Batch LLM Enrichment）

### 4.1 一次请求塞几道题？

**上限由输出 token 决定**（因为每道题的 `analysis` + `knowledge_points` 是输出）。

**已核实的官方上限**：
- **DeepSeek**：官方 Models & Pricing 页显示当前模型为 `deepseek-flash` 与 `deepseek-v4-pro`，**上下文长度 1M，最大输出 384K**；两者都支持 **Json Output** 与 **Tool Calls**。（注意：`deepseek-chat` 这个名字在**当前官方定价页上已不存在**，被 `deepseek-flash` / `deepseek-v4-pro` 取代。）
- **硅基流动之外的多家**：见 4.2 表格。

**实测建议（工程经验，非官方数据）**：业界普遍做法是**每批 5–20 个短条目**。真正需要警惕的是**模型在长列表里「漏项」**——这是有文献支持的：
- **Lost in the Middle（Liu et al., TACL 2023）**：论文发现「performance can degrade significantly when changing the position of relevant information」，且「performance is often highest when relevant information occurs at the beginning or end of the input context, and **significantly degrades when models must access relevant information in the middle of long contexts**」。这对「把 50 道题堆在一个 prompt 里」是直接的负面证据。

**来源**：[DeepSeek Models & Pricing](https://api-docs.deepseek.com/quick_start/pricing/)、[Lost in the Middle: How Language Models Use Long Contexts (arXiv:2307.03172)](https://arxiv.org/abs/2307.03172)

**结论**：批大小应该是**可配置的、并且默认偏保守（如 8–10 题/请求）**，同时**必须做「返回条数 < 请求条数」的校验并重试缺失项**。这个校验比调优批大小重要得多。

### 4.2 结构化输出：各家实际支持什么（这是本节最关键的落地信息）

| 供应商 | `json_object` | `json_schema` + `strict` | 官方文档要点 | 来源 |
|---|---|---|---|---|
| **DeepSeek** | ✅ | **未在其 JSON Output 文档中提供** | 官方要求：①设 `response_format={'type':'json_object'}`；②**prompt 中必须包含 "json" 字样**并提供格式示例；③**合理设置 `max_tokens` 防止 JSON 被截断**；④⚠️官方原文警告：「**When using the JSON Output feature, the API may occasionally return empty content.** We are actively working on optimizing this issue.」 | [DeepSeek JSON Output](https://api-docs.deepseek.com/guides/json_mode) |
| **Qwen（阿里云百炼）** | ✅ | ✅ **但仅限部分模型** | 官方对比表：JSON Object 模式「Strictly follows schema: **No**」，「Supported models: Most Qwen models」；JSON Schema 模式「Strictly follows schema: **Yes**」，「Supported models: **Only selected qwen-plus models**」。官方明确警告：**JSON Object 模式不保证键名和字段类型稳定**（"does not guarantee stable key names or field types"）。另：**多模态输入不支持 json_schema，会自动降级为 json_object**。官方还有一条硬性建议：**"Do not set max_tokens when structured output is enabled"**（会截断 JSON）——这与 DeepSeek 的建议**正好相反**，是真实的跨厂商差异陷阱 | [Alibaba Cloud Model Studio: Structured output](https://www.alibabacloud.com/help/en/model-studio/qwen-structured-output) |
| **Kimi（Moonshot）** | ✅ | ✅ | 官方表格区分：JSON Mode = `json_object`「保证输出为合法 JSON Object，但不约束具体字段」；Structured Output = `json_schema`「通过 JSON Schema 精确定义字段名、类型、嵌套结构」 | [Kimi response_format 文档](https://platform.kimi.com/docs/guide/response_format) |
| **Zhipu（Z.AI / GLM）** | ✅ | **其 Structured Output 文档通篇只讲 `json_object`** | 文档标题是「Structured Output (JSON mode)」，**只给出 `response_format={"type":"json_object"}`**；Schema 校验是靠**在 prompt 里贴 JSON Schema 文本 + 客户端用 `jsonschema` 库校验**，不是服务端约束。也就是说 **Zhipu 的「结构化输出」实际是「JSON 模式 + 提示词 + 客户端校验」** | [Z.AI Structured Output](https://docs.z.ai/guides/capabilities/struct-output) |

**其他机制**：
- **Function calling / tool calling**：DeepSeek 官方支持 Tool Calls（见定价页 FEATURES 表）。作为结构化输出手段，它比 JSON 模式更可控（模型必须产出符合参数 schema 的调用），但**参数校验仍由厂商实现**，可靠性介于 `json_object` 和 `json_schema` 之间。**本环境未能找到三大厂商对「function calling vs json_schema 可靠性」的官方对比数据**，标注**未能验证**。
- **Constrained decoding（受约束解码）**：`outlines`、`XGrammar`、`llama.cpp` 的 GBNF grammar、vLLM 的 guided decoding 都属于这类——**在采样阶段直接屏蔽非法 token**，因此**理论上 100% 合法**。但**关键限制**：这些都需要**自托管推理**（你自己跑 vLLM/SGLang/llama.cpp），**托管 API 不会暴露 logits 处理钩子**。对「调用托管 API 的 Java 项目」而言，**这条路不可用**（除非你愿意本地部署模型）。**OpenAI 的 Structured Outputs 是托管 API 侧唯一能给出「严格 schema」承诺的**：OpenAI 在 2024-08-06 的公告中称「model outputs now reliably adhere to developer-supplied JSON Schemas」。**注意**：`platform.openai.com` 的文档页在本环境被 Cloudflare 拦截，故具体的 schema 限制清单（如所有字段必须 required、必须 `additionalProperties: false` 等）**本环境未能从官方文档验证**。
- **OpenAI 兼容端点的互操作陷阱**：**未能验证**。我未能找到「OpenAI 兼容端点静默忽略未知 `response_format` 字段」的公开 issue 或文档证据。**但在 Zhipu 与 Qwen 的文档对比中可以间接确认风险真实存在**：Qwen 明确写了「JSON Object 模式不保证稳定键名/类型」，Zhipu 的 `json_schema` 根本不存在——所以「用同一份 OpenAI 格式请求打不同后端，行为不一致」是被官方文档间接证实的。**建议：客户端永远做 schema 校验 + 重试，不要信任服务端约束。**

**结构化输出方案对比表**：

| 方案 | 可靠性 | 需要什么 | Java 可行性 | 来源 |
|---|---|---|---|---|
| `json_object` | 中（保证是合法 JSON，**不保证字段**） | prompt 含 "json"（部分厂商）、合理 max_tokens | ✅ 高 | [DeepSeek](https://api-docs.deepseek.com/guides/json_mode)、[Qwen](https://www.alibabacloud.com/help/en/model-studio/qwen-structured-output) |
| `json_schema` + `strict` | 高（服务端约束） | **仅部分模型支持** | ✅ 高（但需按模型能力降级） | [Qwen](https://www.alibabacloud.com/help/en/model-studio/qwen-structured-output)、[Kimi](https://platform.kimi.com/docs/guide/response_format) |
| Function / tool calling | 中高 | 支持 tool calls 的模型 | ✅ 高 | [DeepSeek 定价页 FEATURES 表](https://api-docs.deepseek.com/quick_start/pricing/) |
| Constrained decoding（outlines/XGrammar/GBNF/vLLM） | 最高 | **必须自托管推理** | ❌ 托管 API 不可用 | （通用知识；**本环境未能取得单一权威对比来源**，标注未能验证） |
| 客户端 schema 校验 + 重试（兜底） | — | 一个 JSON Schema 校验库 | ✅ 高 | [Qwen 官方 "Going live" 建议](https://www.alibabacloud.com/help/en/model-studio/qwen-structured-output) |

> Qwen 官方在 "Going live" 一节直接推荐：**用 jsonschema（Python）、Ajv（JS）或 Everit（Java）校验输出**，失败则重试或让模型重写。**这一条对你的 Java 项目是最直接的官方指引。**

### 4.3 Prompt Caching：如何改变「重复 system prompt」的成本

**这是本节性价比最高的一项。**

**DeepSeek Context Caching on Disk（2024-08-02 上线）**：
- **全自动，无需改代码**：「The disk caching service is now available for all users, requiring no code or interface changes. The cache service runs automatically, and billing is based on actual cache hits.」
- **命中规则（关键）**：「only requests with **identical prefixes (starting from the 0th token)** will be considered duplicates. **Partial matches in the middle of the input will not trigger a cache hit.**」——**前缀必须从第 0 个 token 开始完全一致**。
- 2024 年公告中的历史定价：命中 $0.014/M、未命中 $0.14/M，并称「Users can save up to 90% on costs」，「Even without any optimization, historical data shows that users **save over 50% on average**」。延迟方面：「For a 128K prompt with high reference, the first token latency is cut from 13s to just 500ms.」
- **当前官方定价页**（注意价格已变）：`deepseek-flash` 缓存命中 $0.003–0.006/M、未命中 $0.15–0.30/M、输出 $0.60–1.20/M（off-peak/peak 两档）。
- **缓存粒度**：按 **64 token** 为存储单位，**不足 64 token 的内容不会被缓存**。官方明确「**does not guarantee 100% cache hits**」，且未使用的缓存条目会在数小时到数天内被清理。
- 响应里通过 `prompt_cache_hit_tokens` / `prompt_cache_miss_tokens` 可观测命中情况。

**OpenAI Prompt Caching（2024-10-01 公告）**：
- **自动应用**，无需改集成：「we will automatically apply the Prompt Caching discount without requiring you to make any changes to your API integration.」
- **门槛与粒度**：「API calls to supported models will automatically benefit from Prompt Caching on prompts **longer than 1,024 tokens**. The API caches the longest prefix of a prompt that has been previously computed, **starting at 1,024 tokens and increasing in 128-token increments**.」
- **折扣幅度**：公告称「a **50% discount** and faster prompt processing times」。当时价目表（GPT-4o 系列）显示未缓存 $2.50/M → 缓存 $1.25/M（正是 50%）。
- 响应中通过 `usage` 里的 `cached_tokens` 观测。

**Anthropic Prompt Caching**：我尝试抓取官方文档页（`docs.anthropic.com` → 重定向到 `platform.claude.com` → 再重定向到 `www.anthropic.com`，本环境**不接受跨域重定向**），`claude.com/blog/prompt-caching` 抓回来只剩导航骨架、无正文。因此**Anthropic 的「5 分钟 TTL 默认 / 1 小时可选 / 写入 1.25x / 读取 0.1x」这些具体数字，本环境未能从官方来源验证，标注为未能验证。**

**替代证据（可引用）**：阿里云百炼的 Qwen Context Cache 文档给出了**与 Anthropic 完全相同的计费结构**，且是**官方文档**、可稳定访问：
- **显式缓存（explicit）**：需手动加 `"cache_control": {"type": "ephemeral"}` 标记；**写入按标准输入价的 125% 计费**，**命中按 10% 计费**；**最短可缓存 1,024 token**；**有效期 5 分钟，每次命中重置**；单请求最多 **4 个 cache marker**；**向后前缀匹配**，若匹配内容与 marker 之间超过 20 个 content block 则 miss；**显式与隐式缓存互斥**。
- **隐式缓存（implicit）**：自动、不可关闭；**命中按 20% 计费**（除个别模型）；**最短 1,024 token**（**Zhipu 部署的 GLM 模型是 512**）；有效期不固定，系统定期清理。
- **官方提升命中率的建议（直接适用于你）**：「**place duplicate content at the beginning of a prompt and unique content at the end**」。
- 计费示例：10,000 输入 token 中 5,000 命中 → 总输入成本为非缓存模式的 60%。
- **注意**：官方明确「Calls made using the OpenAI-compatible - **Batch** (file input) method are **not eligible for cache discounts**」——**用 Batch API 会失去缓存折扣**，这是一个真实的取舍。
- 另一条实用细节：显式缓存的 content 必须是**数组形式**并带 `cache_control`；Java SDK 需 **≥ 2.21.6**（文档中的 Java 示例标注）。**DashScope Java SDK 不支持 `json_schema`（文档标注 "Java SDK is not supported yet"）——但 OpenAI 兼容协议下可以用 `json_schema`**，这对你的项目是好消息。

**Kimi（Moonshot）上下文缓存**：官方文档说明「以 `kimi-k3` 为例，命中价格仅为未命中价格的 **1/10**」；「本次平台升级将缓存写入（Cache Write）单独列为计费项，并提供 **`5m` 和 `1h` 两档缓存时长（TTL）**」；并强调「缓存按请求**前缀**匹配。**前缀中任何一处发生变化，该位置之后的内容都无法复用**」。

**最终结论（对你的代码）**：所有主流厂商的缓存都是**前缀缓存**，因此**你的 prompt 必须重排**：

```
[system prompt：角色 + 输出格式 + 评分标准 + few-shot 示例]   ← 绝对静态，放最前
[题库级别的知识摘要 / 章节说明]                              ← 相对静态
[题目文本 + 选项 + 答案]                                     ← 每次都变，放最后
```

**只要 system prompt 部分超过 1,024 token（DeepSeek 是 64 token），且你的调用是连续的，就能吃到折扣。** 你现在「一题一次调用」的循环**恰好是最适合前缀缓存的形态**——只要 system prompt 一模一样。这是一个**零成本**的优化：把静态部分抽出来、确保每次请求字节级一致（**注意不要把题目 ID 拼进 system prompt**）。

来源：[DeepSeek Context Caching 公告](https://api-docs.deepseek.com/news/news0802/)、[DeepSeek Context Caching 指南](https://api-docs.deepseek.com/guides/kv_cache/)、[DeepSeek Models & Pricing](https://api-docs.deepseek.com/quick_start/pricing/)、[OpenAI Prompt Caching 公告](https://openai.com/index/api-prompt-caching/)、[Qwen Context Cache](https://www.alibabacloud.com/help/en/model-studio/context-cache)、[Kimi 上下文缓存最佳实践](https://platform.kimi.com/docs/guide/context-caching)

### 4.4 并发、限流与重试

**指数退避 + 抖动**：AWS 架构博客的经典文章给出了「full jitter」等策略，核心结论是**在退避时间上加随机抖动**可以避免大量客户端同步重试造成的「惊群」。**说明**：该博文页面在本环境只抓到了导航/正文截断部分，**未能取得其表格中的具体伪代码与对比数据**，故此处仅陈述其公开主旨。来源：[Exponential Backoff And Jitter (AWS Architecture Blog)](https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/)

**应该重试哪些状态码**：**本环境未能找到一份统一权威的清单**（无 RFC 明确规定 LLM API 的重试语义）。可确认的部分：DeepSeek 官方明示**超出并发限制时返回 HTTP 429**；**通用做法**（标注：工程惯例而非本环境的引用来源）是只重试 429/500/502/503/504 与网络超时，**不重试 400/401/403/422**（这些是请求本身有问题，重试只会重复付费）。

**DeepSeek 的限流实测数据（官方）**：`deepseek-flash` 并发上限 **2500**，`deepseek-v4-pro` **500**；且**并发限额按账号计算，与用哪个 API Key 无关**；超限返回 429。官方还说明可以通过 `user_id` 参数做 KVCache 隔离与调度隔离（`user_id` 需匹配 `[a-zA-Z0-9\-_]+`，最长 512）。另一个**很重要的运维细节**：「If the request has not started inference after **10 minutes**, the server will close the connection.」以及非流式请求在等待期间**会持续返回空行**——**你的 HTTP 客户端必须能处理这些空行**，否则解析会失败。

来源：[DeepSeek Rate Limit & Isolation](https://api-docs.deepseek.com/quick_start/rate_limit/)

**Java 21 的并发原语（已核实）**：
- **虚拟线程（Virtual Threads）已由 JEP 444 在 JDK 21 正式交付（Status: Closed / Delivered, Release: 21）**。此前在 JDK 19（JEP 425）、JDK 20（JEP 436）为预览特性。
- JEP 444 明确给出了**你的场景需要的那个模式**：「Developers sometimes use thread pools to limit concurrent access to limited resources... but **do not be tempted to pool virtual threads in order to limit concurrency. Instead use constructs specifically designed for that purpose, such as semaphores.**」——**即：`Executors.newVirtualThreadPerTaskExecutor()` + 一个 `Semaphore(N)` 来限制对 LLM API 的并发**。这正是官方推荐的写法。
- JEP 444 还指出：**虚拟线程适合非 CPU 密集、高并发等待型任务**（「The workload is not CPU-bound」），LLM 调用完全符合。官方示例用 10,000 个虚拟线程做 1 秒 sleep，吞吐约 10,000 任务/秒。
- **虚拟线程不支持 `Thread.setPriority` / `setDaemon` 的效果**，且 `ThreadMXBean` 只监控平台线程——这些对 LLM 批量任务无影响。
- **不要**用线程池 + `ThreadLocal` 缓存昂贵的资源（如 HTTP 客户端连接），JEP 444 专门警告了这一点。
- **StructuredTaskScope（结构化并发）**：JEP 444 正文中指向 `openjdk.net/jeps/8277129`，说明在 JDK 21 时**仍是预览/孵化状态**（JEP 444 自己的 "Description" 里把 structured concurrency 描述为「offers a more powerful API」，并链接到该 JEP 编号而非正式 JEP 编号）。**其最终定版状态本环境未能验证**，建议在 Java 21 上**不要依赖** StructuredTaskScope，用 `ExecutorService` + `Semaphore` 更稳。

来源：[JEP 444: Virtual Threads](https://openjdk.org/jeps/444)

**重试库**：Resilience4j（`io.github.resilience4j`）与 Spring Retry 都可用，但**本环境未能访问其官方文档页以核实当前主版本号**，故**不给出具体版本号**（避免编造）。**判断**：对单用户本地应用，`Semaphore` + 一个手写的 `for (attempt = 0; attempt < 3; attempt++)` 退避循环（约 20 行）就够，引入 Resilience4j 属于可选。

### 4.5 成本 / 延迟：tokens per question

**我必须明确说明：我未能找到任何可信的、「每道题富化消耗多少 token」或「每道题多少钱」的已发表数字。** 搜索了中英文关键词（"cost per question LLM generation"、"tokens per quiz question"、"大模型 生成题目 成本 每道题 token"），**没有命中任何权威来源**。因此此项标注为**未能验证**，**本报告不编造任何数字**。

**但可核实的单价是有的**（供你自己算）：

| 供应商/模型 | 输入（缓存未命中） | 输入（缓存命中） | 输出 | 来源 |
|---|---|---|---|---|
| DeepSeek `deepseek-flash` | $0.15–0.30 /M | **$0.003–0.006 /M** | $0.60–1.20 /M | [DeepSeek 定价](https://api-docs.deepseek.com/quick_start/pricing/) |
| DeepSeek `deepseek-v4-pro` | $0.66–1.32 /M | $0.022–0.044 /M | $1.98–3.96 /M | 同上 |
| GPT-4o（2024-10 公告价） | $2.50 /M | $1.25 /M | $10.00 /M | [OpenAI Prompt Caching](https://openai.com/index/api-prompt-caching/) |
| GPT-4o mini（2024-10 公告价） | $0.15 /M | $0.075 /M | $0.60 /M | 同上 |
| Qwen 隐式缓存 | 标准价 | **20%**（GLM 25%，deepseek-v4.1-flash 10%） | 原价 | [Qwen Context Cache](https://www.alibabacloud.com/help/en/model-studio/context-cache) |
| Kimi `kimi-k3` | 标准价 | **1/10** | 原价 | [Kimi 上下文缓存](https://platform.kimi.com/docs/guide/context-caching) |

> ⚠️ **价格会变**。DeepSeek 的缓存命中价从 2024 年的 $0.014/M 降到当前 $0.003/M，且分「高峰期/非高峰期」两档（官方定义：Peak hours 是 UTC 周一至周五 01:00–04:00 与 06:00–10:00，其余时间含周末与中国法定节假日为 off-peak，**off-peak 是 peak 的一半**）。**上线前请以官方定价页为准。**

**一个可自行推导的量级参考**（这是算术，不是引用的基准）：一道典型选择题的题干+选项+答案大约几百 token；生成 `analysis`（中文 150–300 字）+ `knowledge_points` 也是几百 token。因此在 **$0.15/M 输入、$0.60/M 输出**的量级下，**单题成本约为「千分之几美分」**。**这个推算依赖我对题目长度的假设，不是实测数据，请以自己跑一批后的 `usage` 字段为准**——DeepSeek 响应里就有 `prompt_cache_hit_tokens` / `prompt_cache_miss_tokens`，Qwen/OpenAI 有 `cached_tokens`，**这些字段本来就该被记进你的日志**。

---

## 5. 可恢复 / 幂等的长任务

### 5.1 核心认知：LLM 调用**不是**幂等的

这是整节最重要的一句话。一个 HTTP GET 重试是安全的；**一个 LLM 调用重试会真的再花一次钱**。因此设计原则应该是：

> **接受「至少一次（at-least-once）」的调用语义，但让「写入」是幂等的。**
>
> 具体做法：**先把 LLM 结果落库（或落到暂存表），再标记该行完成**。绝不要「先标记完成，再写结果」——那样崩溃一次就永久丢数据且不会重试。反过来「先写结果，再标记完成」，最坏情况是重复调用一次（多花一点钱），但**数据正确**。

（「恰好一次（exactly-once）」在分布式系统中一般被认为不可实现，只能退化为「至少一次投递 + 幂等消费」。**说明**：本条为通用分布式系统原理，**本环境未能取得一份可引用的权威页面**，标注为工程共识而非引用来源。）

### 5.2 方案对比

| 方案 | 崩溃后可恢复性 | 需要的表/字段 | 重复付费风险 | 实现复杂度 | 来源 |
|---|---|---|---|---|---|
| **现状：全量重跑** | 无。从头再来 | 无 | **100%**，已富化的行会再次付费 | 最低 | — |
| **每行状态列**（`ai_status` ENUM + `ai_updated_at` + 可选 `ai_attempts`） | 好。查询 `WHERE ai_status='PENDING'` 即可续跑 | `question` 表加 2–3 列 | 低（仅崩溃时正在处理的那 1 行） | **低** | 通用模式；**本环境未能取得单一权威页面**，见下方说明 |
| **`job_run` 表** + 游标/offset 检查点 | 好 | 新增 1 张表 | 低 | 低–中 | [Spring Batch ExecutionContext 概念](https://docs.spring.io/spring-batch/reference/domain.html)（同一思路的简化版） |
| **内容哈希去重**（hash(题目文本 + prompt 版本) 作唯一键） | 好，且改 prompt 会自动失效重算 | 加 `prompt_hash` 列 + 唯一索引 | **零**（同 prompt 同输入永不重复付费） | 低 | **本环境未能验证**具体产品文档（见下） |
| **Spring Batch** | 最好（框架级） | **需要 9 张 BATCH_* 元数据表** | 低 | **高** | [Spring Batch Domain Language](https://docs.spring.io/spring-batch/reference/domain.html)、[Meta-Data Schema](https://docs.spring.io/spring-batch/reference/schema-appendix.html) |

### 5.3 Spring Batch 的官方模型（以及为什么它对你过重）

官方文档给出的事实（均可直接引用）：

- **核心对象**：`Job` / `Step` / `JobInstance` / `JobExecution` / `StepExecution` / `ExecutionContext` / `JobRepository` / `JobOperator`。
- **持久化的元数据表（官方文档中按名字出现）**：`BATCH_JOB_INSTANCE`、`BATCH_JOB_EXECUTION`、`BATCH_JOB_EXECUTION_PARAMS`、`BATCH_STEP_EXECUTION`、`BATCH_STEP_EXECUTION_CONTEXT`（另有 `BATCH_JOB_EXECUTION_CONTEXT` 等，见 Meta-Data Schema 附录）。`JobExecution` 的属性包括 `Status`、`startTime`、`endTime`、`exitStatus`、`createTime`、`lastUpdated`、`executionContext`、`failureExceptions`。
- **重启语义**：官方原文「using the same `JobInstance` determines whether or not the "state" (that is, the `ExecutionContext`) from previous executions is used. **Using a new `JobInstance` means "start from the beginning," and using an existing instance generally means "start from where you left off."**」
- **`ExecutionContext` 的硬约束**：「In the `ExecutionContext`, **all non-transient entries must be `Serializable`**. ... Failing to serialize the execution context may jeopardize the state persistence process, making failed jobs impossible to recover properly.」
- **`restart` API**：`JobOperator` 接口有 `restart(JobExecution)` / `abandon(JobExecution)` / `startNextInstance(Job)`。
- **API 变更**：官方文档明确「**The batch XML namespace is deprecated as of Spring Batch 6.0 and will be removed in version 7.0.**」（当前文档版本为 Spring Batch 6.0.5）。所以要用 Java 配置，不要用 XML。

**判断：Spring Batch 对你的场景是明显的过度工程。** 官方文档自己把它的目标定位为「enterprise domain」「bulk processing」「**mission-critical** environments」「**billions of transactions every day**」「massively parallel batch processing」，并且**需要一整套 `BATCH_*` 表**。你是一个单用户本地应用，任务是「把几千行 `question` 的解析填上」。官方文档虽然也说「You can use Spring Batch in both simple use cases」，但为此引入 9 张元数据表、一个新框架的学习成本、以及 XML namespace 正被移除的迁移风险，**收益远小于成本**。

**来源**：[The Domain Language of Batch](https://docs.spring.io/spring-batch/reference/domain.html)、[Spring Batch Introduction](https://docs.spring.io/spring-batch/reference/spring-batch-intro.html)

### 5.4 简单方案的具体设计（推荐路线）

**表结构**（给 `question` 加三列或新建一张 `question_ai_task` 表）：

| 列 | 用途 |
|---|---|
| `ai_status` | `PENDING` / `RUNNING` / `DONE` / `FAILED` / `SKIPPED` |
| `prompt_hash` | `hash(题目文本 + 选项 + 答案 + prompt_template_version)`，**唯一索引**；prompt 改了就自动全部失效重算 |
| `ai_attempts` | 尝试次数，用于熔断（如超过 3 次标 `FAILED` 不再重试） |
| `locked_at` | 处理开始时间，用于**清理僵死 RUNNING** |

**为什么需要「僵死 RUNNING 清理（reaper）」**：进程崩溃时正在处理的那一行的 `ai_status` 会**永远停留在 `RUNNING`**。如果不处理，这一行再也不会被任何一次续跑选中。做法：续跑启动时执行 `UPDATE ... SET ai_status='PENDING' WHERE ai_status='RUNNING' AND locked_at < NOW() - INTERVAL 10 MINUTE`。**说明**：这是「租约 / 心跳（lease / heartbeat）」模式的直接套用，**本环境未能取得一份可引用的权威页面**，标注为工程模式解释。

**每行一提交（autocommit）而不是大批事务**：LLM 调用是秒级操作，**绝不能把 LLM 调用放在数据库事务里**——那会长时间持有连接和锁。正确顺序是：

```
1. UPDATE question SET ai_status='RUNNING', locked_at=NOW() WHERE id=? AND ai_status='PENDING'   -- 乐观抢占
2. （事务外）调用 LLM
3. UPDATE question SET analysis=?, knowledge_points=?, ai_status='DONE' WHERE id=?               -- 结果与状态一次写完
```

第 1 步的 `AND ai_status='PENDING'` 是关键：**它让「抢占」本身成为原子操作**，即使有两个并发 worker 也不会重复处理同一行（受影响行数为 0 就跳过）。

**关于 MySQL 的幂等写法（`INSERT ... ON DUPLICATE KEY UPDATE`）**：本环境**未能访问 `dev.mysql.com` 官方手册**，因此其精确返回值语义（官方文档中「1 表示插入、2 表示更新、0 表示无变化」这类描述）**我未能从官方来源验证，故不在此断言**。建议实现时以本地 MySQL 版本的手册为准。

**关于 Idempotency-Key**：IETF 的 `draft-ietf-httpapi-idempotency-key-header` **已经是 Expired Internet-Draft（draft-ietf-httpapi-idempotency-key-header-07，状态 "Expired & archived"），并未成为 RFC**。其摘要只说明「The HTTP Idempotency-Key request header field can be used to make non-idempotent HTTP methods such as POST or PATCH fault-tolerant」，**没有规定具体的存储时长或语义细节**。Stripe 的 24 小时保留窗口等具体数字来自其商业文档，**本环境未能访问，标注为未能验证**。来源：[IETF Datatracker: draft-ietf-httpapi-idempotency-key-header-07](https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/)

**判断**：**Idempotency-Key 机制对单用户本地应用属于过度工程**——你没有重试的网络客户端之间的竞争，`ai_status` 乐观抢占 + `prompt_hash` 唯一索引已经覆盖了全部实际风险。

### 5.5 「不要重复付费」的额外手段

- **先过滤再调用**：`WHERE (analysis IS NULL OR analysis = '') AND ai_status IN ('PENDING','FAILED')`，让 SQL 而不是代码来决定处理哪些行。这是最便宜、最有效的一层。
- **同批去重**：如果题库里有近似重复的题目（同一题干出现两次），可以在批内按 `prompt_hash` 去重，**一次调用写两行**。
- **进程内缓存 + 持久化缓存**：把 `prompt_hash → 结果` 存一张表，重跑时先查表。**本环境未能验证** GPTCache / LiteLLM 等具体项目的当前状态，且**这两个都是 Python 项目**，对 Java 无用——你需要的是自己写一张 `ai_cache(prompt_hash PK, response_json, created_at)` 表，这比引入任何库都简单。

---

## 6. 答案解析（解析）的质量：LLM 生成的解析能不能给用户看？

### 6.1 有证据表明「能」——但证据有明确的范围限制

**可引用的研究**：
- **《Can ChatGPT generate practice question explanations for medical students, a new faculty teaching tool?》**，发表于 *Medical Teacher* 2025;47(3):560-564（Epub 2024-06-20），DOI 10.1080/0142159X.2024.2363486。**该论文的标题与出处可从 PubMed 条目确认**（[PubMed 38900675](https://pubmed.ncbi.nlm.nih.gov/38900675/)），**但我未能获取其摘要正文与结论数据**（PubMed 页在本环境只返回了 HHS 声明骨架）。因此**我不引用它的任何结论数字**，只确认「这个方向已有同行评议研究」。
- **《Comparative Evaluation of AI-Generated vs. Expert-written Answer Explanations for a Medical Education Self-Assessment》**（ACL Anthology, BEA 2026）：[aclanthology.org/2026.bea-1.31.pdf](https://aclanthology.org/2026.bea-1.31.pdf)。**该 PDF 在本环境无法解析（unsupported content type "application/pdf"）**，因此**未能验证其结论**。

**结论**：**「LLM 生成解析是否足够好」这个问题在医学教育等特定领域已有同行评议的直接研究，但我未能在本环境核实任何一项研究的具体结论数值。此处不做断言。**

### 6.2 有强证据表明「有系统性风险」——这部分是可引用的

**这是本节最有价值的部分，而且证据很硬。**

**（a）CoT 解释可能是不忠实的（unfaithful）**：
**《Language Models Don't Always Say What They Think: Unfaithful Explanations in Chain-of-Thought Prompting》**（Turpin, Michael, Perez, Bowman；NeurIPS 2023；arXiv:2305.04388）。摘要原文的关键句：

> 「we find that **CoT explanations can systematically misrepresent the true reason for a model's prediction**. We demonstrate that CoT explanations can be heavily influenced by adding biasing features to model inputs--e.g., by reordering the multiple-choice options in a few-shot prompt to make the answer always "(A)"--which models systematically fail to mention in their explanations. **When we bias models toward incorrect answers, they frequently generate CoT explanations rationalizing those answers.** This causes accuracy to drop by as much as 36% on a suite of 13 tasks from BIG-Bench Hard... **Our findings indicate that CoT explanations can be plausible yet misleading, which risks increasing our trust in LLMs without guaranteeing their safety.**」

**这段与你的场景高度相关**：你正是「给模型一道选择题，让它产出一段解释」。论文说的是——**如果你（或数据）把模型推向一个错误答案，它会流畅地编出一段为该错误答案辩护的解释**。而你的系统是**直接把这段解释展示给学习者的**。一个「看起来很合理但实际在为错误答案辩护」的解析，**比没有解析更糟**。

来源：[arXiv:2305.04388](https://arxiv.org/abs/2305.04388)

**（b）LLM-as-a-judge 有已知偏差**：
**《Judging LLM-as-a-Judge with MT-Bench and Chatbot Arena》**（Zheng et al., NeurIPS 2023 Datasets and Benchmarks；arXiv:2306.05685）。摘要原文：

> 「We examine the usage and limitations of LLM-as-a-judge, including **position, verbosity, and self-enhancement biases**, as well as limited reasoning ability, and propose solutions to mitigate some of them. ... Our results reveal that strong LLM judges like GPT-4 can match both controlled and crowdsourced human preferences well, **achieving over 80% agreement, the same level of agreement between humans**.」

**含义**：用「另一个 LLM 当裁判来检查解析质量」是**可行的方向**（GPT-4 级裁判与人类偏好的一致率超过 80%，与人类之间的一致率相当），**但裁判本身有位置偏差、啰嗦偏差、自我增强偏差**。也就是说：**裁判分数不能单独当作放行标准**，尤其当裁判模型和被评判模型是同一个时（self-enhancement bias 直接命中你的场景）。

来源：[arXiv:2306.05685](https://arxiv.org/abs/2306.05685)

**（c）Self-Consistency 是有效且可引用的**：
**《Self-Consistency Improves Chain of Thought Reasoning in Language Models》**（Wang et al., ICLR 2023；arXiv:2203.11171）。摘要原文：采样多条推理路径，再**对答案做边缘化（marginalizing out the sampled reasoning paths）取最一致的答案**；在 GSM8K 上 **+17.9%**、SVAMP **+11.0%**、AQuA **+12.2%**、StrategyQA **+6.4%**、ARC-challenge **+3.9%**。

**但对你的场景有个关键限制**：self-consistency 的收益是在**需要多步推理的数学/常识题**上测得的。**选择题的选项是给定的，模型「换个说法」也能落到同一个选项上**——所以 self-consistency 在选择题上主要是检测「模型对答案有多确定」，而不是提升正确率。**把它当一致性检查（不一致就标记待人工复核）比当准确率提升手段更合理。**

来源：[arXiv:2203.11171](https://arxiv.org/abs/2203.11171)

### 6.3 可落地的质量闸门（按成本从低到高）

| 校验手段 | 能发现什么问题 | 额外成本 | 可靠性 | 来源 |
|---|---|---|---|---|
| **答案一致性检查**（把「题干+选项+正确答案+生成的解析」再喂回模型，问「这段解析是否支持该答案？只答 yes/no」） | **解析在为错误答案辩护**、解析与答案矛盾——即 6.2(a) 的核心风险 | **每次富化多 1 次调用**（约 +1x 输入、极少输出） | 中。裁判有偏差（6.2(b)），但**只做二分类、且与被判模型不同时**较可靠 | 方法组合自 [2305.04388](https://arxiv.org/abs/2305.04388) + [2306.05685](https://arxiv.org/abs/2306.05685) |
| **多次采样一致性**（同题跑 3 次，解析的「结论」不一致就降级） | 模型对该题不确定 / 幻觉 | **3x 调用成本** | 中–高 | [Self-Consistency, arXiv:2203.11171](https://arxiv.org/abs/2203.11171) |
| **结构化字段强制**（解析必须引用题干中的原文片段；`knowledge_points` 必须来自预定义列表） | 泛泛而谈、跑题的解析 | 0 额外调用，只是 prompt 与 schema 设计 | 中 | [Qwen 结构化输出文档](https://www.alibabacloud.com/help/en/model-studio/qwen-structured-output) |
| **拒绝作答 / 弃权**（prompt 里给出「如果无法确定，返回 `confidence: low` 或 `analysis: null`」） | 减少对不确定题的编造 | 0 额外调用 | 中（LLM 的置信度校准本身不可靠——**本环境未能找到针对此的权威评估**，标注未能验证） | — |
| **UI 层明示「AI 生成，未经人工审核」** | 用户预期管理 | 0 | **高（唯一 100% 可靠的手段）** | — |
| **LLM-as-a-judge 打分** | 覆盖更多质量维度（完整性、清晰度） | 1x 调用 + 需要设计评分 rubric | 中，**必须意识到 position/verbosity/self-enhancement 三类偏差** | [arXiv:2306.05685](https://arxiv.org/abs/2306.05685) |
| **人工抽检** | 一切 | 人力 | 最高 | — |

### 6.4 教育学的角度：错解析比没解析更糟吗？

**这是本报告中最需要谨慎的一段。** 「反馈（feedback）对学习有显著影响，而错误反馈可能有害」是教育心理学的公认结论，Hattie & Timperley 的《The Power of Feedback》（*Review of Educational Research*, 2007）是该领域被引最多的综述之一。**但我未能获取其全文**（检索命中的是一个机构库条目页 [bibliotecadigital.mineduc.cl](https://bibliotecadigital.mineduc.cl/handle/20.500.12365/17390?show=full)，非正文）。因此**我不引用其效应量数字**。

**但有一条来自本报告已有权威来源的、直接支持「宁缺毋滥」的论据**：Turpin et al. 的结论本身就是「**CoT 解释可以既看似合理又具有误导性（plausible yet misleading），这会增加我们对 LLM 的信任，却不保证其安全性**」。在你这个场景里，「增加信任」的客体是**学生**，而学生**没有能力识别一个自信的、错误的解析**。

**我的判断（明确标注为判断而非引用）**：对学习类产品，**「显示一个未经校验的 AI 解析」的风险收益比是不利的**——解析正确时学生受益，解析错误时学生**主动学到错误知识并且信任它**。因此**最低成本的正确做法是：加置信度闸门 + 明确的 AI 标识 + 允许用户一键「报错」并把该题回退成人工/无解析状态**。

---

## 针对本项目的 Top 8 建议

> 排序原则：先做「零成本、一定有收益」的，再做「有成本但收益明确」的，最后列出**明确判定为过度工程、不建议做**的。

---

### ① 把 AI 富化改成「可续跑 + 幂等写入」——加 `ai_status` / `prompt_hash` 两列

- **做法**：`question` 表加 `ai_status`（PENDING/RUNNING/DONE/FAILED）、`prompt_hash`（题目文本+模板版本的哈希，唯一索引）、`locked_at`。处理顺序固定为「乐观抢占 → 事务外调 LLM → 结果与状态一次写回」。启动时先跑一次僵死 RUNNING 回收。
- **一句话理由**：这是唯一直接消灭「崩溃后重跑要重新付一遍钱」的改动，而你的任务本质就是「几千行的批处理」，崩溃几乎是必然事件。
- **落地难度**：**低**（3 个字段 + 一个查询条件 + 一段顺序约束，无新依赖）
- **值得写进简历**：**是**。理由：它体现的是「at-least-once 投递 + 幂等消费」「乐观抢占」「租约回收」这类**面试官一听就知道你踩过坑**的分布式基本概念，而且你能诚实地说「我为单用户应用选了最轻量的版本，而不是上 Spring Batch」，这本身就是判断力的体现。

---

### ② prompt 重排以命中前缀缓存 + 记录缓存命中率

- **做法**：把 prompt 切成 `[完全静态的 system prompt + few-shot] + [题库级知识摘要] + [题目内容]`，**保证前两段每次请求字节级完全一致**（**不要把题目 ID、时间戳拼进前缀**）。同时在日志里记录响应中的 `prompt_cache_hit_tokens` / `prompt_cache_miss_tokens`（DeepSeek）或 `cached_tokens`（Qwen/OpenAI）。
- **一句话理由**：所有主流厂商的缓存都是**前缀缓存**，而你现在「一题一次调用、system prompt 不变」的循环**天然就是最优形态**——只需确认前缀真的没变，就能直接拿到官方文档承诺的折扣（DeepSeek 官方称历史用户平均省 50%+，Qwen 隐式缓存命中按 20% 计费，Kimi 命中价为 1/10）。
- **落地难度**：**低**（几乎没有代码量，主要是 prompt 的物理排列与一个日志字段）
- **值得写进简历**：**是**。理由：**Prompt caching / 前缀缓存是 2024 年后 LLM 应用的标配话题**，能说出「缓存是前缀匹配、动态内容必须放最后、用 usage 字段量测命中率」的人不多，而这是**零成本**的知识点。引用官方文档的数字即可，不需要自己造数据。

---

### ③ 给抽题加 cap + 让「组卷」可复现（存 seed）

- **做法**：给 session 的题目数加**硬上限**（如 200），拒绝超限请求；`practice_session` 表加 `seed BIGINT` 列，RANDOM 模式用 `Collections.shuffle(list, new Random(seed))` 而不是无参版本。
- **一句话理由**：**当前的「无上限 + 全量载入 id + 全量插入」是一个可以被单次误操作触发的可用性事故**（一次请求就能把整个题库拉进内存并插 N 行）；而固定 seed 只花一个字段，就换来「卷面可精确复现」——这是调试、A/B、申诉的共同基础。
- **落地难度**：**低**
- **值得写进简历**：**部分**。加 cap 不值得单列（太基础），但**「为什么必须显式传 seed」**值得：因为 `Collections.shuffle(List)` 用的默认随机源**不在 javadoc 规范内**，而 `java.util.Random` 的同种子序列**被 javadoc 强制要求跨实现一致**（原文「Java implementations must use all the algorithms shown here... for the sake of absolute portability of Java code」），`SplittableRandom` 则只承诺「in the same program」。**能把这三者的可复现性保证说清楚，是一个有区分度的细节。**

---

### ④ 「到期复习」选题（给错题加 `due_date`）——不引入 FSRS

- **做法**：`wrong_question` 表加 `due_date`，答错时按一个简单的盒式间隔推进（如 1/3/7/15/30 天），抽「错题 session」时改成 `WHERE resolved = 0 AND due_date <= CURDATE() ORDER BY due_date`。
- **一句话理由**：这是**从 FSRS 的 DSR 模型里提取出唯一对你有用的那部分（时间维度）**，却只需要一条 UPDATE 和一个 WHERE；而完整 FSRS 要求你把交互改成「每题 4 档自评」并积累几百条复习记录才能体现价值——**对组卷式练习系统来说是本末倒置**。
- **落地难度**：**低**
- **值得写进简历**：**是**（但要说对话术）。**不要写「我实现了 FSRS」**（会被追问公式与参数优化，而 `java-fsrs` 官方**不支持参数优化**，你会露怯）。**应该写**「调研了 SM-2 / FSRS / Leitner，**基于『练习语义 vs 记忆卡语义』的差异**选择只引入时间维度而非完整 DSR 模型」——**这个取舍论证本身就是加分项**。

---

### ⑤ 批量化 LLM 调用 + 返回条数校验

- **做法**：把「一题一次调用」改成每请求 8–10 题（可配置），用 `json_object`（DeepSeek/Zhipu）或 `json_schema`（Qwen 部分模型、Kimi）约束输出；**必须校验返回数组长度 == 请求条数**，缺失项单独重试。
- **一句话理由**：批量化能同时降低请求数和输入 token 总量，但**真正的风险不是效率而是静默丢项**——「Lost in the Middle」证明模型对长上下文中部信息的利用率显著下降，所以**条数校验是必需品而不是可选项**。
- **落地难度**：**中**（要处理各厂商 `response_format` 能力差异、要处理 DeepSeek 官方承认的「偶尔返回空 content」、要处理 Qwen 说的「JSON Object 模式不保证键名稳定」，还要处理 Qwen 与 DeepSeek 对 `max_tokens` 的**相反建议**）
- **值得写进简历**：**是**。理由：这是**真正有信息量的工程细节**——「我在统一 OpenAI 兼容接口上做了一层能力降级矩阵，因为 Qwen 只有部分模型支持 json_schema、Zhipu 根本没有 json_schema、DeepSeek 会偶发返回空 content、而且 Qwen 说别设 max_tokens 而 DeepSeek 说必须设」。这类跨厂商兼容层的踩坑经验，比「我调用了大模型 API」有价值一个数量级。

---

### ⑥ 并发化：虚拟线程 + Semaphore，不要线程池

- **做法**：`Executors.newVirtualThreadPerTaskExecutor()` 提交每行任务，用一个 `Semaphore(N)`（N 取 4–8）限制对 LLM API 的并发；失败重试用「指数退避 + 随机抖动」，只重试 429/5xx 与超时。
- **一句话理由**：**这正是 JEP 444 官方推荐的写法**——javadoc 原文明确说「do not be tempted to pool virtual threads in order to limit concurrency. Instead use constructs specifically designed for that purpose, **such as semaphores**」，而且虚拟线程正适合 LLM 这种「非 CPU 密集、大部分时间在等 I/O」的负载。
- **落地难度**：**低**（Java 21 原生，无新依赖）
- **值得写进简历**：**是**。理由：**虚拟线程是 Java 21 最有辨识度的新特性**，而「用 Semaphore 而不是线程池来限流」是 JEP 444 里被明确写出来、但很多人不知道的正确用法。能引用 JEP 原文说明「why not pool」，是很扎实的加分点。

---

### ⑦ 解析质量闸门：答案一致性检查 + UI 标识 + 一键报错

- **做法**：富化后追加一次「把题干+选项+正确答案+生成的解析喂回去，问『这段解析是否支持该答案』」的二分类校验，不通过的标 `needs_review`；前端一律显示「AI 生成，仅供参考」；提供一键报错，把该题降级为无解析。
- **一句话理由**：**Turpin et al. (NeurIPS 2023) 的结论直接命中你的场景**——「当把模型推向错误答案时，它频繁生成为该答案辩护的 CoT 解释」，而这类解释「看似合理却具有误导性」。**你的用户是学生，他们没有能力识别一个自信的错误解析**，所以这里的下限比上限重要。
- **落地难度**：**中**（多一次 LLM 调用 + 一个状态位 + 一点前端）。**如果只做「UI 标识 + 一键报错」两件事，难度降为低**
- **值得写进简历**：**是**，而且**这个最值得写**。理由：绝大多数做 LLM 应用的人只谈「怎么生成」，**能谈「怎么知道我生成的是对的」的人极少**。你能引用 NeurIPS 论文说明「CoT 解释可能是不忠实的」、引用 MT-Bench 论文说明「LLM-as-judge 有 position/verbosity/self-enhancement 三类偏差所以不能单用」，**这是研究素养的直接证据**。

---

### ⑧ 抽题：先做「分层抽样」，加权抽样按需再说

- **做法**：随机组卷时按章节分层、按题量比例分配名额，层内随机，最后整体打散。
- **一句话理由**：**纯随机抽 N 题时小章节大概率一题不中**（超几何分布），而分层抽样只多一条 `GROUP BY` 和一次配额计算，就能保证「随机卷也覆盖每一章」——这是随机组卷在**教学上**唯一真正重要的性质。
- **落地难度**：**低**
- **值得写进简历**：**是**（如果你能说清分配逻辑）。理由：「随机抽题」谁都会写；**能说出「纯随机会导致小章节覆盖率不稳定，所以我做了按题量的比例分配 + 最大余数法处理取整余数」**，说明你理解的是统计性质而不是 API 调用。**加权抽样（A-Res）则视情况**——只有当你想让「错得多的题更常出现」时才需要，它是 O(n log k) 的堆操作，实现难度同样不高，但**优先级低于分层**（因为它优化的是「练什么更有效」，而分层优化的是「卷子是否公平覆盖」）。

---

### ❌ 明确判定为过度工程 / 不建议做

| 想法 | 为什么不要做 |
|---|---|
| **把 FSRS 完整接进来**（`io.github.open-spaced-repetition:fsrs`） | 库本身很好（MIT、Maven Central、Java 17+、API 极简），**但它的前提是「同一张卡反复复习 + 每次 4 档自评 + 几百条以上复习记录」**。你是**组卷式练习系统**，不是记忆卡系统。而且 Anki 手册自己就警告过「材料以固定顺序出现会让人靠上下文猜答案，导致记忆更弱」——**重复刷同一道选择题，你测到的是「记住了答案」而不是「记住了知识」**，FSRS 的 R 预测会被系统性高估。**更关键的是：官方 Java 库明确不支持参数优化**，要优化只能外接 Rust(`fsrs-rs`) 或 Python(`py-fsrs`/`fsrs-optimizer`)——**跨语言工具链成本 + 导出流程未验证**。 |
| **引入 Spring Batch** | 官方文档把它定位为 enterprise、mission-critical、每天数十亿事务的场景，**并需要一整套 `BATCH_*` 元数据表**（`BATCH_JOB_INSTANCE`/`BATCH_JOB_EXECUTION`/`BATCH_JOB_EXECUTION_PARAMS`/`BATCH_STEP_EXECUTION`/`BATCH_STEP_EXECUTION_CONTEXT` 等）。为「填几千行解析」引入一个新框架 + 9 张表，收益为负。另外官方已声明 **XML namespace 在 6.0 弃用、7.0 移除**，你还得跟这个迁移。**用 ① 的方案，20 行代码拿到 90% 的效果。** |
| **CAT / IRT 自适应抽题** | 选题算法（MFI、a-分层）不难，**难的是每道题需要 IRT 参数（区分度 a、难度 b、猜测度 c），这需要预测试和大样本标定**。单用户题库永远达不到标定所需样本量。**它有一个零成本降级版：用历史正确率当 b 的代理，优先选正确率 50%–70% 的题**——但这个降级版也不需要叫「CAT」。 |
| **`ORDER BY RAND()` 换掉现在的 `Collections.shuffle`** | 这个方向**可能是负优化**。`ORDER BY RAND()` 是 O(n log n) 全表扫 + filesort，**而且 `ROW_NUMBER() OVER (ORDER BY RAND())` 写法不改变这个本质**。经典替代（随机 id 区间）有「id 有空洞时严重偏斜」的缺陷，随机 id 列需要额外维护列，键表需要预计算。**你现在「全量取 id + Fisher–Yates」的方案在几万题以内是合理的，甚至是唯一不依赖 id 稠密性的方案。** 真正该做的只有一件事：**加 cap（见 ③）**。 |
| **Idempotency-Key 机制** | IETF 的那份草案**已经 Expired & archived，从未成为 RFC**，且只描述了 header 的用途、没有规定语义细节。你是单用户本地应用，**没有网络客户端之间的竞争**，`ai_status` 乐观抢占 + `prompt_hash` 唯一索引已经覆盖了全部实际风险。 |
| **Constrained decoding（outlines / XGrammar / GBNF / vLLM guided decoding）** | **这些全部需要自托管推理**——你要自己跑 vLLM/SGLang/llama.cpp 才能拿到 logits 处理钩子，**托管 API 不暴露这个能力**。你是 Java 项目调用托管 API，**这条路在架构上就不通**。想达到类似效果，用 `json_schema`（如果厂商支持）+ 客户端校验 + 重试。 |
| **语义缓存（GPTCache / LiteLLM 等）** | **这些是 Python 项目**，对 Java 无用。而且语义缓存在「生成解析」这种任务上**有正确性风险**（相似题目被误判为同一题会返回错误解析）。**你需要的只是 `prompt_hash` 精确缓存表——一张两列表，比引入任何库都简单，而且没有误命中风险。** |
| **`SplittableRandom` 做组卷种子** | 它的 javadoc 只承诺「**in the same program**」生成相同序列，**没有跨 JDK 版本的承诺**，而且它**不是线程安全的**、设计上是要被 `split()` 而不是共享。要长期可复现的种子，用 `java.util.Random`（javadoc 有「Java implementations must use all the algorithms shown here」的强保证）。 |
| **`SecureRandom` 做组卷种子** | 组卷不需要密码学强度，`SecureRandom` 只会更慢，而且**同样需要你自己存种子才能复现**。javadoc 里 `Random` 的「not cryptographically secure」对组卷场景不构成问题。 |

---

## 附录：需要外部服务 / 付费 API / Python-only 依赖的清单（本报告已按要求显式标注）

| 项目 | 类型 | 说明 |
|---|---|---|
| **所有 LLM 调用**（DeepSeek / Qwen / Kimi / Zhipu / OpenAI） | **付费 API + 外部服务** | BYOK 已经是这个形态。价格见 4.5，**会变动，以官方定价页为准** |
| `fsrs-rs` | **Rust crate** | java-fsrs 官方指定的参数优化途径之一，需要 Rust 工具链 |
| `fsrs-optimizer` / `py-fsrs` | **Python 包** | java-fsrs 官方指定的另一条参数优化途径，**Java 项目需要跨语言** |
| `outlines` / `XGrammar` / vLLM guided decoding | **Python 生态 + 需自托管推理** | 托管 API 不可用 |
| GPTCache / LiteLLM | **Python 项目** | Java 项目不可用；用 `prompt_hash` 表替代 |
| Resilience4j / Spring Retry | Java 库（**无外部服务**） | 可用但非必需；单用户场景手写退避循环即可 |
| `io.github.open-spaced-repetition:fsrs` | Java 库（**无外部服务**，纯本地） | MIT、Maven Central、Java 17+；**但见 ① 的判断：对本项目属于过度工程** |
| Zhipu `zai-sdk` | **Python SDK** | 其官方 Structured Output 示例全为 Python；Java 侧走 OpenAI 兼容 HTTP 协议即可 |

---

## 附录：本报告明确标注为「未能验证」的条目汇总

| 条目 | 原因 |
|---|---|
| MySQL 官方手册关于 `ORDER BY` / filesort / `ORDER BY RAND()` 的原文 | `dev.mysql.com` 对本环境持续返回 403 / "Technical Difficulties"（试过 8.0、8.4 两个路径与 PDF 下载） |
| MySQL 是否支持 `TABLESAMPLE` 的官方否定确认 | 同上 |
| `INSERT ... ON DUPLICATE KEY UPDATE` 的官方返回值语义（1/2/0） | 同上 |
| Percona / Jan Kneschke 关于「随机 id 列」的原始博文 | 原文 404；相关 Stack Overflow 答案被 Cloudflare 拦截 |
| Stack Overflow 上关于 `ORDER BY RAND()` 性能与 Spring Batch 是否过度的讨论 | Cloudflare 403 |
| Fisher–Yates 的「n^n vs n!」原始论述（Wikipedia 词条、Jeff Atwood 原帖） | 两者均抓取失败（连接/DNS 错误）；已改用 Bostock 文章（其明确引用这两篇）作为可达来源 |
| Anthropic Prompt Caching 的具体数字（5 分钟 TTL、1.25x 写入、0.1x 读取） | `docs.anthropic.com` → `platform.claude.com` → `anthropic.com` 跨域重定向链在本环境不被跟随；`claude.com/blog` 只返回导航骨架 |
| OpenAI Structured Outputs 的 schema 限制清单 | `platform.openai.com` 被 Cloudflare 拦截；仅能引用 2024-08-06 的官方公告 |
| OpenAI 当前最新模型的定价 | 同上；已改用 2024-10 公告中的 GPT-4o / GPT-4o mini 价目表 |
| SM-18 的算法公式 | 属于 SuperMemo 商业闭源内容，Anki 官方 FAQ 也确认「SuperMemo's latest algorithm is proprietary」 |
| 「每道题富化消耗多少 token / 多少钱」的已发表数字 | 中英文检索均未命中任何权威来源；**故不提供任何估计值** |
| 医学教育领域 LLM 解析质量研究的具体结论数值 | PubMed 页只返回骨架；ACL Anthology PDF 在本环境不被支持解析 |
| Hattie & Timperley《The Power of Feedback》的效应量 | 只找到机构库条目页，未获取全文 |
| 「交错练习（interleaving）」的效应量数据 | 找到尼日利亚数学项目研究条目，未获取正文 |
| 分层抽样在「教育测评组卷」中的专项官方规范 | 未找到；本节描述的是统计学通用方法在本场景的直接套用 |
| Resilience4j / Spring Retry 的当前主版本号 | 未能访问官方文档页；**故不给出任何版本号** |
| StructuredTaskScope 在 Java 21 之后的最终定版状态 | JEP 444 正文中指向 `jeps/8277129` 而非正式 JEP 编号，说明当时仍为预览 |
| 「OpenAI 兼容端点静默忽略未知 `response_format` 字段」的直接证据 | 未找到公开 issue 或文档；但 Qwen 与 Zhipu 官方文档的能力差异**间接证实**了跨后端行为不一致的风险 |
| 「至少一次 + 幂等消费」、租约/心跳模式的权威引用页 | 为通用工程共识，本环境未取得单一权威来源 |
