# Asteria · 智能自主刷题系统

![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.16-6DB33F)
![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1.8-6DB33F)
![MyBatis-Plus](https://img.shields.io/badge/MyBatis--Plus-3.5.15-red)
![MySQL](https://img.shields.io/badge/MySQL-8-4479A1)
![License](https://img.shields.io/badge/License-未指定-lightgrey)

基于 **Spring Boot 3.5 + Spring AI** 的智能自主导入题库刷题系统。把 Word / PDF / TXT 题库文档导进来，
自动切题、分章节、判定题型，可选地用 AI 补全解析与知识点；在此之上提供刷题、错题本、知识点总结，
以及一个**能自己去查题库**的 AI 答疑助手（Tool Calling）。

**设计取向**

- **规则优先、AI 兜底** —— 纯文本规则解析零成本；只有规则一道题都切不出来时，才走 AI 结构化抽取，把 token 花在真正需要的地方。
- **BYOK（Bring Your Own Key）** —— 后端不保存任何 API Key，Key 随请求头传入、用完即弃，天然支持多用户各自接自己的模型。
- **单 jar 交付** —— 前端构建产物打进 jar 的 `static/`，`java -jar` 一步起来，不必额外挂 nginx。
- **多模块单向分层** —— 父工程聚合 `common / pojo / server`，依赖只朝一个方向走，边界清晰。

> 前端（Vue 3 + Vite）源码与打包产物在 [`asteria-ai/`](./asteria-ai)，其中 `asteria-ai/dist` 是可直接部署的构建结果。
>
> 题库素材的来源整理，配套有一个独立的个人学习辅助工具，能够提取学习通题库 —— [**ChaoxingQuizTool**](https://github.com/F-Qing/ChaoxingQuizTool)：
> 把你自己账号下可查看的题库页面整理成本系统支持导入的结构化文本（仅供个人学习资料整理使用）。

---

## 目录

- [系统架构](#系统架构)
  - [分层架构](#分层架构)
  - [模块依赖](#模块依赖)
  - [后端包分布](#后端包分布)
  - [关键流程](#关键流程)
- [技术栈](#技术栈)
- [功能](#功能)
- [快速开始](#快速开始)
- [配置说明](#配置说明)
- [接口一览](#接口一览)
- [数据库](#数据库)
- [AI 能力与设计要点](#ai-能力与设计要点)
- [测试](#测试)
- [已知限制](#已知限制)

---

## 系统架构

### 分层架构

自上而下五层，依赖只向下。前端与后端之间只通过 `/api/**` 通信（含 SSE 流）。

```
┌────────────────────────────────────────────────────────────────────────┐
│                          浏览器 / 客户端                                 │
│   Vue 3 + Vite + Element Plus + Pinia        （asteria-ai，产物进 jar）  │
└───────────────────────────────┬────────────────────────────────────────┘
                                │   HTTP / SSE  ·  /api/**
┌───────────────────────────────▼────────────────────────────────────────┐
│ ① 接口层        controller/                                             │
│    Banks · Questions · Practice · AiChat · AiSummary · Ai · TextDate    │
├────────────────────────────────────────────────────────────────────────┤
│ ② 应用服务层    Services/ (接口) + Services/impl/ (实现)                 │
│    导入编排 · 刷题会话 · 题库查询 · 聊天会话 · 知识点总结 · 考试信息        │
├────────────────────────────────────────────────────────────────────────┤
│ ③ 领域 / 智能层                                                          │
│    parser/   题型判定 · 答案归一化 · 输出质量自检                          │
│    ai/       模型工厂 · JSON 结构化抽取 · 工具调用 · 提示词 · 脱敏 · 增强    │
├────────────────────────────────────────────────────────────────────────┤
│ ④ 基础设施层    mapper/ · tool/ · config/ · handler/                     │
│    MyBatis-Plus 数据访问 · POI/PDFBox 文本抽取 · 装配 · 全局异常           │
└───────────────┬────────────────────────────────────┬───────────────────┘
                │ JDBC                               │ HTTPS（OpenAI 兼容协议）
        ┌───────▼────────┐                  ┌────────▼─────────┐
        │    MySQL 8     │                  │  LLM 服务商（外部）│
        │ 题库·刷题·聊天  │                  │ DeepSeek / OpenAI │
        │                │                  │ 通义 / 智谱 / …    │
        └────────────────┘                  └──────────────────┘
```

### 模块依赖

父工程 `asteria` 只做**版本管理与模块聚合**，不含业务代码。三个子模块依赖单向：
`asteria-server → asteria-common / asteria-pojo`；`common` 与 `pojo` **互不相识**。

```
                        ┌──────────────────────────────────────┐
                        │            asteria（父 POM）          │
                        │   packaging=pom · 版本管理 · 模块聚合   │
                        └──────────────────┬───────────────────┘
           ┌───────────────────────────────┼───────────────────────────────┐
           ▼                               ▼                               ▼
┌──────────────────────┐      ┌──────────────────────┐      ┌──────────────────────────┐
│   asteria-common     │      │    asteria-pojo      │      │     asteria-server       │
│   纯工具 · 无 Spring  │      │   数据模型 · 无业务    │      │  可执行服务端（Spring Boot）│
│                      │      │                      │      │                          │
│  Tool/  解析与文本抽取 │      │  entity/ 实体 + DTO   │      │  controller/  service/   │
│  result/ 统一响应体   │      │  entity/VO 视图对象    │      │  ai/  parser/  mapper/   │
│  exception/ 业务异常  │      │  enums/ 枚举          │      │  tool/ config/ handler/  │
└──────────────────────┘      └──────────────────────┘      └────────────┬─────────────┘
           ▲                               ▲                             │
           └───────────────────────────────┴─────────────────────────────┘
                     依赖方向：asteria-server ──▶ common / pojo（单向）

  注：解析用的 QuestionOption（common）与展示用的 QuestionOptionVO（pojo）各留一份，
      正是「common 与 pojo 解耦」的代价与结果。
```

### 后端包分布

`asteria-server` 的完整包结构（重点）。四类职责用 ①②③④ 对应上面的分层编号。

```
asteria-server/src/main/java/com/asteria/server/
│
├── AsteriaServerApplication.java     启动类（scanBasePackages = server + common）
│
├── controller/                        ①  接口层：只负责收参、组响应，不写业务
│   ├── BanksController                 题库：导入 / 导入进度 / 详情 / 删除 / 题目列表
│   ├── QuestionsController             题目
│   ├── PracticeController              刷题：会话建立 / 作答 / 结果 / 统计 / 错题
│   ├── AiChatController                聊天：会话 CRUD + SSE 流式消息
│   ├── AiSummaryController             知识点总结（含缓存读取）
│   ├── AiController                    AI 连通性自测
│   └── TextDateController              考试信息 CRUD
│
├── Services/                          ②  应用服务层：业务编排
│   ├── BanksService        + impl/     导入登记、后台异步编排、查询、级联删除
│   ├── BanksImportTransactional        导入落库的事务边界（独立 Bean，保证 @Transactional 生效）
│   ├── QuestionsService    + impl/
│   ├── PracticeService     + impl/
│   ├── ChatService         + impl/     会话与消息、SSE 事件组装
│   ├── AiService           + impl/
│   └── TextDateService     + impl/
│
├── parser/                            ③  领域层：解析结果的判定与归一化
│   ├── QuestionClassifier              选项数 / 答案 → 题型（单选·多选·判断·简答·填空）
│   ├── AnswerNormalizer                答案字母归一化
│   ├── AnswerTexts                     答案文本工具
│   └── QuestionQualityCheck            输出侧自检：只打日志、不改数据（发现切题错误的探针）
│
├── ai/                                ③  智能层：所有与模型交互的代码集中在此
│   ├── AiChatModelFactory              按请求现场构造模型（BYOK）；导入场景自动关思考模式
│   ├── AiRequestConfig                 四个 X-AI-* 请求头的解析结果
│   ├── AiQuestionExtractor             规则解析失败时的 JSON 结构化抽取兜底
│   ├── QuestionAiEnricher              逐题补全解析 / 知识点
│   ├── QuestionBankTools               暴露给模型的 3 个查库工具
│   ├── AiJsonRepair                    模型 JSON 输出的容错修复
│   ├── AiHeaders                       X-AI-* 请求头常量
│   ├── AiErrors                        上游异常脱敏（压行 / 截断 / Key 替换为 ***）
│   └── AiTestResult                    /ai/test 返回体
│
├── mapper/                            ④  基础设施：MyBatis-Plus 数据访问
│   ├── BankMapper · ChapterMapper · QuestionMapper
│   ├── BanksImportMapper               导入任务
│   ├── PracticeSessionMapper · PracticeSessionQuestionMapper · PracticeRecordMapper
│   ├── WrongQuestionMapper             错题本
│   ├── KnowledgeSummaryMapper          知识点总结缓存
│   ├── ChatSessionMapper · ChatMessageMapper
│   └── TextDateTimeMapper              考试信息
│
├── tool/                              ④  基础设施：文件 → 纯文本
│   ├── DocxTextReader                  Apache POI 抽 .docx
│   └── PdfTextReader                   Apache PDFBox 抽 .pdf（附扫描件体检提示）
│
├── config/                            ④  基础设施：装配
│   ├── MybatisPlusConfig               分页插件 + 公共字段自动填充（MetaObjectHandler）
│   ├── SpaWebConfig                    SPA 路由回退（替代 nginx try_files）
│   └── ToolConfig                      通用工具类 Bean 注册
│
└── handler/
    └── GlobalExceptionHandler          全局异常 → 统一响应体
```

另外两个模块：

```
asteria-common/src/main/java/com/asteria/common/
├── Tool/
│   ├── QuestionParser      ★ 核心解析器：纯文本规则切题、章节识别、题型线索
│   ├── RawQuestion         解析中间产物（题干 / 选项 / 答案 / 章节 / 题型）
│   ├── QuestionOption      解析期选项模型
│   └── FileTextReader      通用编码文本读取（txt）
├── result/ApiResponse      统一响应体 { code, message, data }
└── exception/BusinessException   业务异常（带错误码）

asteria-pojo/src/main/java/com/asteria/pojo/
├── entity/                 数据库实体（Bank / Chapter / Question / …）
│   ├── DTO/                请求入参
│   └── VO/                 响应视图对象
└── enums/                  QuestionType · ImportStatus · AiStatus · AnswerSource · …
```

### 关键流程

**题库导入**：主线程登记任务后立刻返回，解析与入库全部在后台线程完成，前端靠 `taskId` 轮询进度。

```
POST /api/banks/import  (multipart)
        │
        ▼
  BanksController ─► BanksService.importBank
        │  ① 在 import_task 表登记任务（PENDING）
        │  ② 文件落盘 uploads/，写一份内存快照供轮询
        │  ③ new Thread("import-<taskId>") 异步启动，主线程立即返回 taskId
        ▼
┌───────────────── 后台线程：processImport ─────────────────┐
│  ① 抽取文本     docx→POI   pdf→PDFBox   txt→按编码读取      │
│  ② 规则切题     QuestionParser.parse → List<RawQuestion>   │
│  ②.5 兜底       规则不可用？→ AiQuestionExtractor（JSON 抽取）│
│  ③ 输出质检     QuestionQualityCheck（仅日志）              │
│  ④ 事务入库     BanksImportTransactional.saveImport       │
│                  bank / chapter / question 同一事务         │
│  ⑤ AI 增强      QuestionAiEnricher（虚拟线程并发逐题）       │
│                 进度写内存，前端轮询 GET /import/{taskId}    │
└──────────────────────────────────────────────────────────┘
        │
        ▼
   终态：SUCCESS / AI_PROCESSING / FAILED（失败原因落库）
```

**AI 答疑（SSE + 工具调用）**：模型每次请求现场构造，历史手动拼接，工具由模型自主决定是否调用。

```
POST /api/chat/sessions/{id}/messages
        │
        ▼
  AiChatController ─► ChatService
        │  ① 载入历史（最近 100 条 / 30000 字符，两道闸按正序拼接）
        │  ② AiChatModelFactory 用请求头的 Key / baseUrl / model 现场造模型
        ▼
   OpenAiChatModel  ◄──  系统提示词  +  历史消息  +  本轮提问
        │
        │  模型自主决定是否调用工具
        ├──► QuestionBankTools
        │        ├── listBanks()                          列题库
        │        ├── searchQuestions(keyword, …, limit)    按关键词搜题
        │        └── getQuestionDetail(questionId)         取单题完整内容
        │             └─ 查 MySQL，结果回喂给模型，模型据此作答
        ▼
   SSE 回前端： chunk（增量文本） · done（结束 + messageId） · error（已脱敏）
```

---

## 技术栈

| 类别 | 选型 |
|---|---|
| 语言 / 构建 | Java 21、Maven（仓库自带 `mvnw`） |
| 框架 | Spring Boot 3.5.16（Spring MVC / Servlet，非 WebFlux） |
| 持久层 | MyBatis-Plus 3.5.15（含 `mybatis-plus-jsqlparser` 分页插件） |
| 数据库 | MySQL 8 |
| AI | Spring AI 1.1.8（`spring-ai-starter-model-openai`，OpenAI 兼容协议） |
| 文档解析 | Apache POI 5.2.5（.docx）、Apache PDFBox 2.0.31（.pdf） |
| 其它 | Lombok、Reactor（随 Spring AI 引入，用于 SSE 流） |

Spring AI 用的是 **OpenAI 兼容协议**，所以只要服务商支持自定义 `baseUrl`，一套代码就能接
OpenAI / DeepSeek / Moonshot / 通义 / 智谱 / 自建中转站。

---

## 功能

1. **题库导入**：上传 `.docx` / `.pdf` / `.txt`，后台异步解析
   - **题号**：`【第 1 题】`、`1.` / `1、`、`（1）` 三种写法都认；教材式的小节行
     `1．选择题` / `2．简答题` 也会被识别成"本节题型"（题干的 `题目：` / `题干：` 标签可有可无，
     题号后面直接跟题干也行）
   - **按章节归类**：文档里有章节标题（`【第 1 章】绪论`、裸写的 `第一章 绪论`，或教材式的 `习  题  1`）
     就分章入库；没有章节信息的题目统一归到「默认章节」
   - **选项**：Word 里常用 Tab 把 `A．/B．/C．/D．` 排在同一段，抽取文本时会把 Tab 当换行拆开，
     每个选项各占一行再识别
   - 自动识别题型（单选 / 多选 / 判断 / 简答 / 填空）、拆分选项与答案；题干为空的残块会被跳过，
     不影响同批其他题目入库
   - 导入进度可轮询查询，失败原因落库
   - **规则吃不下时**（格式实在乱的题库）：勾上「AI 解析」，由 `AiQuestionExtractor` 按 JSON
     结构化抽取兜底，逐题校验后再入库（见「AI 能力与设计要点」）
2. **AI 解析增强**（可选）：题干 / 答案缺失或格式不标准时，调模型补全并回写
   - **部分失败不影响使用**：个别题解析失败只跳过那几道，题库照常入库、照常能刷；
     导入完成卡片会写明「其中 N 道题未生成解析」
   - **账号级失败会立刻停手**：余额不足 / 限流 / Key 失效时不再白跑完整个题库，
     直接中断并说明原因（`40021`）。已解析的题数据保留，可正常使用
3. **题库管理**：分页查询、详情（含章节与题型统计）、删除（级联删章节与题目）
4. **刷题**：顺序 / 随机出题、错题练习、提交答案、查看结果与统计
5. **知识点总结**：按题库让 AI 汇总知识点与易错点，结果落库做缓存（命中缓存不再调 AI）
6. **AI 答疑**：SSE 流式对话，带**对话记忆**与**工具调用**——AI 可以自己查题库列表、按关键词搜题、取某道题的完整内容（含选项、答案、解析），再像老师一样讲解

---

## 快速开始

### 环境要求

- JDK 21
- MySQL 8
- Maven 3.9+（或直接用仓库里的 `mvnw`；Windows 下是 `mvnw.cmd`）
- Node.js 20+（仅改前端 / 重新构建前端时需要；只跑 jar 不用装）

### 1. 建库建表

```bash
mysql -uroot -p -e "CREATE DATABASE finaltext DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
```

按顺序执行 `asteria-server/src/main/resources/db/` 下的 8 个脚本
（都带 `USE finaltext;`，且**可重复执行**——迁移脚本会先查 `information_schema` 判断对象是否存在）：

```bash
cd asteria-server/src/main/resources/db
mysql -uroot -p < bank_tables.sql
# ...其余脚本同理，按下面表格的顺序
```

| 顺序 | 脚本 | 内容 |
|---|---|---|
| 1 | `bank_tables.sql` | `bank` / `chapter` / `question` |
| 2 | `import_task.sql` | `import_task`（题库导入任务，进度与失败原因） |
| 3 | `practice_tables.sql` | 刷题会话、答题记录、错题本 |
| 4 | `knowledge_summary.sql` | 知识点总结缓存 |
| 5 | `chat_tables.sql` | `chat_session` / `chat_message` |
| 6 | `textdatetime.sql` | 考试信息表 |
| 7 | `question_ai_columns.sql` | **增量迁移**：给 `question` 加 AI 解析状态与答案来源列（`ai_status` / `ai_error` / `ai_retry_count` / `answer_source` / `ai_enriched_at`） |
| 8 | `practice_record_index.sql` | **增量迁移**：给 `practice_record` 加 `(session_id, is_correct)` 索引，加速答题时的计数与错题查询 |

> 第 7、8 个是**后加的增量迁移脚本**：老库直接在原表上 `ALTER`，不需要重建表、不影响已有数据
> （新增列的默认值就是老数据的正确值：`ai_status='PENDING'`、`answer_source='FILE'`）。

### 2. 配置数据库

`application.yml` 默认激活 `dev` profile，数据库账号密码从环境变量读，不设置则使用默认值 root / 123456：

```bash
# Linux / macOS（bash）
export DB_USER=root
export DB_PASSWORD=你的密码
```

```powershell
# Windows PowerShell
$env:DB_USER = "root"
$env:DB_PASSWORD = "你的密码"
```

```bat
:: Windows cmd
set DB_USER=root
set DB_PASSWORD=你的密码
```

也可以直接改 `asteria-server/src/main/resources/application-dev.yml`。

### 3. 启动

```bash
# 在项目根目录
mvn clean package -DskipTests
java -jar asteria-server/target/asteria-server-0.0.1-SNAPSHOT.jar
```

或者用 IDEA 直接运行 `com.asteria.server.AsteriaServerApplication`。

服务默认监听 **8080**。

**确认启动成功**：日志出现 `Started AsteriaServerApplication` 即已就绪；
浏览器打开 `http://localhost:8080` 能看到界面（jar 自带前端，见下一步方式 C）。
起不来最常见的原因是 MySQL 没启动或账号密码不对——看日志里的 `datasource` 报错即可定位。

### 4. 前端

前端源码在 `asteria-ai/`，`asteria-ai/dist` 是已经构建好的静态产物（**已随仓库提交，可直接部署**）。
三种用法：

**方式 A：开发热更新（改前端时最方便）**——在 `asteria-ai/` 下执行 `npm install && npm run dev`，
访问 `http://localhost:5173`；Vite 已配好把 `/api` 代理到 `http://localhost:8080`，后端照常以 jar 或 IDEA 方式运行即可。

**方式 B：单独托管静态产物**——用任意静态服务器（nginx / `npx serve`）托管 `dist`，
把 `/api` 反向代理到 `http://localhost:8080`。

**方式 C：一个 jar 自带前端（打包分发时推荐）**——`asteria-server/pom.xml` 里已经配好
`maven-resources-plugin`，打包时会把 `asteria-ai/dist` 拷进 jar 的 `static/` 目录，
启动后直接访问 `http://localhost:8080` 就是完整界面，不需要 nginx。
SPA 的前端路由回退由 `SpaWebConfig` 在应用内完成（等价于 nginx 的 `try_files`）。

```bash
# 前端源码改过时，必须先重新构建（产物在 asteria-ai/dist）
cd asteria-ai && npm install && npm run build

# 再打包后端 —— 注意必须带 clean！
# 否则 target/classes/static 里会残留上一次构建的旧前端文件，一起进 jar
cd .. && mvn clean package -DskipTests
```

> ⚠️ `mvn package` 前一定要 `clean`。`copy-resources` 只覆盖同名文件，不会删除
> `target/classes/static` 里上一次留下的旧 chunk，结果就是 jar 里塞了两份前端。

---

## 配置说明

| 配置项 | 位置 | 说明 |
|---|---|---|
| `server.port` | `application.yml` | 服务端口，默认 8080 |
| `spring.ai.model.*` | `application.yml` | 全部设为 `none`：**不**在启动时按配置建模型 bean |
| `spring.servlet.multipart.max-file-size` | `application.yml` | 单文件上限 20MB |
| `spring.servlet.multipart.max-request-size` | `application.yml` | 单请求上限 25MB |
| `asteria.upload-dir` | `application.yml` | 上传文件落盘目录，默认 `uploads`（已被 .gitignore 忽略） |
| `DB_USER` / `DB_PASSWORD` | 环境变量 | 数据库账号密码 |
| `mybatis-plus.configuration.log-impl` | `application-dev.yml` | dev 下打印 SQL，生产建议关掉 |

### AI 配置是「随请求带」的（BYOK）

后端**不保存任何 API Key**。前端在设置页填写后存在浏览器本地，每次请求通过 4 个请求头带过来：

| 请求头 | 说明 |
|---|---|
| `X-AI-Provider` | 服务商标识（仅用于展示/日志） |
| `X-AI-Key` | API Key |
| `X-AI-Base-Url` | 接口地址，需包含版本段，例如 `https://api.deepseek.com/v1` |
| `X-AI-Model` | 模型名，例如 `deepseek-flash` |

后端的处理约定：

- Key 只在构造 `OpenAiApi` 的那一处使用，**不落库、不写日志**
- 所有上游异常信息在返回前端前统一经过 `AiErrors.mask()` 脱敏（压成一行、截断、把 Key 替换成 `***`）
- 没配 AI 时，导入、刷题、看历史这些功能照常可用，只有真正要调模型的接口返回 `40020`

---

## 接口一览

统一响应体：

```json
{ "code": 0, "message": "success", "data": {} }
```

业务校验失败返回 **HTTP 200 + `code != 0`**；系统级错误才用 4xx / 5xx。

### 题库

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/banks` | 分页查询题库（`keyword` / `page` / `pageSize`） |
| POST | `/api/banks/import` | 上传并导入题库（multipart） |
| GET | `/api/banks/import/{taskId}` | 查询导入进度与状态 |
| GET | `/api/banks/{id}` | 题库详情（含章节列表、题型统计） |
| DELETE | `/api/banks/{id}` | 删除题库（级联删章节与题目） |
| GET | `/api/banks/{bankId}/questions` | 题目列表 |

### 刷题

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/practice/sessions` | 开始一次刷题（顺序 / 随机） |
| POST | `/api/practice/sessions/wrong` | 开始错题练习 |
| GET | `/api/practice/sessions` | 刷题会话列表 |
| GET | `/api/practice/sessions/{id}` | 取当前题目 |
| POST | `/api/practice/sessions/{id}/answers` | 提交答案 |
| GET | `/api/practice/sessions/{id}/result` | 本次结果 |
| GET | `/api/practice/stats` | 总览统计 |
| GET | `/api/practice/wrong-stats` | 错题统计 |

### AI

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/ai/test` | 测试 AI 配置连通性 |
| POST | `/api/knowledge-summary` | 生成知识点总结（命中缓存则不调 AI） |
| GET | `/api/knowledge-summary?bankId=` | 读已生成的总结，没生成过返回 `data: null` |

### AI 聊天

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/chat/sessions` | 新建会话 |
| GET | `/api/chat/sessions` | 会话列表 |
| DELETE | `/api/chat/sessions/{id}` | 删除会话（消息级联删除） |
| GET | `/api/chat/sessions/{id}/messages` | 历史消息（按时间正序分页） |
| POST | `/api/chat/sessions/{id}/messages` | 发送消息，返回 **SSE 流** |

SSE 事件契约（前端按此解析）：

```
event: chunk    data: {"delta":"..."}                      // 增量文本
event: done     data: {"messageId":123,"content":"..."}     // 结束
event: error    data: {"code":50000,"message":"..."}        // 出错（信息已脱敏）
```

### 其它

| 方法 | 路径 | 说明 |
|---|---|---|
| POST / GET / PUT / DELETE | `/api/config/exams` | 考试信息增删改查 |

### 错误码

| code | 含义 |
|---|---|
| 40010 | 参数不合法 |
| 40011 | 该条件下没有题目 |
| 40012 | 没有待攻克的错题 |
| 40013 | 这道题不属于本次刷题会话 |
| 40020 | 未配置 AI（缺 `X-AI-*` 请求头） |
| 40021 | AI 解析中断（账号级问题：余额不足 / 限流 / Key 失效）。**题目已入库可正常使用**，只是部分题没有解析 |
| 40400 | 接口不存在 |
| 40401 | 题库不存在 |
| 40402 | 刷题会话不存在 |
| 40403 | 题目不存在 |
| 40404 | 聊天会话不存在 |
| 50000 | 服务器内部错误 |

---

## 数据库

表结构都在 `asteria-server/src/main/resources/db/` 里，关键关系：

```
import_task ──> bank                     （成功导入的任务指向产出的题库；删题库时任务记录保留，故不建外键）
bank ──< chapter ──< question
  └──────────────────< question          （question.bank_id 是冗余列，便于按题库直接筛题）

practice_session ──< practice_session_question
        └──────────< practice_record
wrong_question（错题本，与 question 关联）

knowledge_summary（按题库缓存 AI 总结）

chat_session ──< chat_message            （删会话时消息 ON DELETE CASCADE 级联删除）
```

约定：

- **章节由题目自己带**：解析阶段（`QuestionParser`）记住"最近一个章节标题"，写进每道题的
  `RawQuestion.chapterName`；入库时按章节名分组建 `chapter` 行，`sort` 就是章节首次出现的顺序。
  文档里没有章节标题（或题目出现在所有章节标题之前）→ 统一归到「默认章节」
- 时间戳统一由 `MybatisPlusConfig` 里的 `MetaObjectHandler` 自动填充
  （`created_at` 插入时填、`updated_at` 插入与更新时填；注意 `strictUpdateFill` **只在字段为 null 时**才填）
- `chat_session.message_count` 是冗余字段，每插入一条消息 +1
- `chat_message.content` 用 `MEDIUMTEXT`；流式中断时保存**已生成的那部分**并标记 `interrupted = 1`

---

## AI 能力与设计要点

### 1. 导入时的解析增强

文档解析走 `asteria-common` 的 `QuestionParser`（纯文本规则，不依赖模型），
识别不出题干或答案的题交给 `QuestionAiEnricher` 用模型补全；AI 不可用时跳过、正常入库。

**AI 结构化抽取（兜底路径）**：整份文件按规则一道题都切不出来时，才交给 `AiQuestionExtractor`
让模型**直接输出 JSON**（`response_format=json_object`）再反序列化入库（只有勾了「AI 解析」才会走这条路）。
为什么不是"让 AI 改写成标准格式的文本、再拿正则解析"：

- **格式由协议保证**：要的是结构（题干/选项/答案分字段），不是"看起来像标准格式的文本"。
  文本形态下模型漏写「题目：」标签、把选项并进题干、包一层 markdown 代码块，都会解析出残题；
  JSON 里字段缺了就是缺了，程序一眼看得出来。
- **分块按「题目边界」切**，不是按字数切 —— 切点落在题目中间会让前后两块各拿到半道题。
- **逐题校验，不让垃圾进库**：题干空 → 丢掉这道题；选项字母非法/重复/内容空 → 丢掉该选项；
  答案字母不在选项里 → 只丢答案、保留题目（宁可没答案，也不要错答案）。校验在
  `AiQuestionExtractorTest` 里有单测盯着。
- **每块重试 + 失败要报出来**：`finish_reason=length`（被服务商 max_tokens 截断）或整块抽不出题，
  重试一次仍失败就整批失败并报出「原文第 X~Y 行」，既不静默丢题、也不塞半成品。
- **DeepSeek 上关掉思考模式**：`deepseek-flash` / `deepseek-v4-pro`（含 `deepseek-v4-flash`
  这个老名）的 thinking **默认是开的**，而抽取是机械活 —— 开着只是更慢更贵，
  还会让 `temperature` 失效（官方文档：思考模式下 temperature 无效）。
  `AiChatModelFactory.createForImport` 只对这几个模型带 `thinking: {"type":"disabled"}`；
  其它服务商、以及不认这个字段的老模型（`deepseek-chat` / `deepseek-reasoner`）一个字段都不加。
- **分块有硬上限**：目标 2000 字一块、**硬上限 4000 字** —— 即使整篇题号一个都认不出来
  （正是最需要 AI 的情况），块也不会无限大。一份 50 题、1.1 万字的题库实际切成约 6 块、每块约 9 题。

#### 逐题补解析（AI 解析增强）

补解析是**按题调模型**的，所以这一层的关键词是「**别重复花钱**」——模型调用不是幂等的，
重试一次就是真扣一次钱。

- **并发**：用 **JDK 21 虚拟线程**（`Executors.newVirtualThreadPerTaskExecutor()`）并发跑，
  配 `Semaphore(6)` 限制同时在飞的请求数。并发度是照**数据库连接池**反推的
  （HikariCP 默认 10 条连接，进度和任务状态也要写库，所以留余量取 6）。
  用信号量而不是固定大小线程池，是遵循 JEP 444 的建议：*不要把虚拟线程池化来限流*。
- **不重复处理**：只捞 `ai_status != 'DONE'` 的题。已解析成功的题永远不会再被捞出来，
  所以崩溃后重跑**只处理没做完的部分**。
- **可追溯**：`ai_status`（PENDING/DONE/FAILED）+ `ai_error` + `ai_retry_count`，
  失败原因脱敏后落库。
- **数据血缘**：`answer_source` 区分 `FILE`（原文解析）/ `AI`（模型补的）/ `MANUAL`（人工改的）。
  **题目原本有答案时绝不用 AI 结果覆盖** —— 文件里的答案比模型可信。
  没有这个标记，将来想做"人工复核 AI 补的答案"或"回滚 AI 的改动"就无从下手，
  因为原始数据已经被覆盖了。
- **部分失败不毁整批**：单题失败只记账（标 `FAILED`）并继续跑完，题库照常建成、用户照样能刷题；
  导入完成卡片写明「其中 N 道题未生成解析」。
- **账号级失败立刻停手**：余额不足 / 限流 / Key 失效时，**接下来每道题都会失败**，
  所以识别到就中断，不再白跑完整个题库。识别方式是沿**异常链**找关键词
  （`insufficient` / `quota` / `rate limit` / `401` / `402` / `429` / 余额 / 欠费 / 限流），
  并带**深度上限**防止异常链成环时死循环。
  另外会把刚才因账号问题标 `FAILED` 的题**改回 `PENDING`** —— 它们失败的原因在账号、
  不在题目，标成 FAILED 会让人误以为题目有问题。
- **错误信息不甩原文给用户**：服务商的报文（`401 - {"error":...request_id...}`）只进日志，
  界面上是归类后的一句话（"AI 账号鉴权失败，请到「设置」页检查 API Key"）。
  判断顺序是**先看报文关键词、最后才用状态码兜底** —— 因为 DeepSeek 的"余额不足"返回的是
  **429** 而不是 402，按状态码判会误报成"请求太频繁"，把用户引向错误的处理方式。

> **为什么不让 AI 直接当切题主力**：规则解析可复现、零成本、能写断言、出错能定位到具体哪条正则；
> AI 每次结果可能不同、要花钱、出错只能猜。正常格式的文件走规则层就够了，AI 只做"规则接不上时的兜底"
> 和"补解析文案"这两件事。

### 2. 输出侧自检

解析完之后 `QuestionQualityCheck` 会跑一遍确定性检查，**只打日志、不改数据、不阻断入库**：

| 检查项 | 判据 |
|---|---|
| 无题干 | 题干为空（入库阶段本来会跳过，这里提前报出来） |
| 缺答案 | 没提取到答案 |
| 选项不足 | 判成单选/多选却少于 2 个选项 |
| **答案越界** | **答案字母不在选项字母集合里** ← 最可靠的问题信号 |
| 选项重复 | 同一题里出现两个 A |
| 有可疑行 | 解析器收块时没认出来的行（这就是 `RawQuestion.suspicious` 的用途） |

**「答案越界」为什么最有价值**：答案 D 但选项只有 A/B/C —— 这不可能是题目本身如此，
**几乎必然是切题切错了**。所以它相当于一个**免费的错误探针**：跑一次导入就知道这批数据的健康度，
不用人工逐题核对。

> 判定时踩过两个坑，都写进了注释：① 不能用 `Character.isLetter()` 判答案字母——
> 它对中文也返回 `true`，而判断题答案是「对」/「错」，会导致**每道判断题都误报**；
> ② 不能扫整段答案——学习通导出的答案是 `D；标签和其属性构成了HTML元素;` 这种形态，
> 全文里的英文（"HTML"）会被当成答案字母。修法是**只取开头那段选项字母**。

### 3. 知识点总结

取题库内最多 **300 道**题的题干与答案拼成材料，温度 **0.3**（要稳定），
结果写回 `knowledge_summary` 做缓存；再次请求同题库直接读缓存，不再调用模型。

### 4. AI 聊天

- **模型按请求现场构造**：`AiChatModelFactory` 每次用请求头里的 Key / baseUrl / model 造一个
  `OpenAiChatModel`，用完即弃——BYOK 模式下不能复用单例，否则会把 A 的 Key 用到 B 的请求上。
- **对话记忆**：模型是无状态的，每轮请求都要把历史重新拼上去。
  取最近 **100 条 / 30000 字符**（两道闸）按正序拼成 `UserMessage` / `AssistantMessage`。
  不做无限量是因为模型上下文窗口是硬限制，塞爆会直接 400 报错。
  ⚠️ 目前**只持久化 user / assistant 的文本**，不保存工具调用与工具结果。
- **工具调用**：`QuestionBankTools` 暴露 3 个工具给模型自己调用

  | 工具 | 作用 |
  |---|---|
  | `listBanks()` | 列出所有题库（名称 + 题目数） |
  | `searchQuestions(keyword, bankName?, type?, limit?)` | 按关键词搜题干，返回题目 id / 题型 / 题干 / 答案 |
  | `getQuestionDetail(questionId)` | 取某道题的完整内容（选项、答案、解析、知识点、章节） |

  工具方法内部一律 `try/catch`：工具抛异常会让整条 SSE 变成 error 事件，
  转成一句人话回给模型，它还能接着答。返回值是"给模型看的紧凑文本"而不是 JSON，省 token 且不易看错。

- **系统提示词**：核心是把两种知识分开——**题库里的事实（题干/答案/解析）必须先查工具、不许编**；
  **学科知识（原理/推导/举例）可以放心展开讲**。此外约束它不暴露内部 id、
  不罗列超过上限的题目、解析缺失时说明是"我自己的讲解"而非官方解析。

### 5. 安全

- Key 只在造 `OpenAiApi` 时使用，不落库、不进日志
- 上游异常统一 `AiErrors.mask()` 脱敏后再返回前端
- 工具返回的题目内容来自本库，不含用户凭据

---

## 测试

```bash
mvn clean test        # 130 个用例，全部通过（10 个测试类）
```

测试**只覆盖纯逻辑**（不连数据库、不调真实模型），因此能作为回归门禁稳定运行：

| 测试类 | 盯住什么 |
|---|---|
| `QuestionParserTest` | ★ 核心解析器（约 30 个用例）：题号 / 章节 / 选项 / 答案的各种写法，以及**不该被误判的边界**（含"这些写法暂时不认"的显式记录） |
| `QuestionQualityCheckTest` | 输出侧自检的每一条规则，含一组「不该报的不能报」的误报测试 |
| `QuestionQualityCheckRealFormatTest` | **拿真实学习通导出格式**跑一遍自检，确认零误报 |
| `AiQuestionExtractorTest` | AI 抽取的逐题校验闸门（空题干丢弃 / 非法选项丢弃 / 答案越界只丢答案） |
| `AiJsonRepairTest` | LaTeX 反斜杠修复；含「证明修复前确实是坏的」的断言与幂等测试 |
| `QuestionAiEnricherTest` | 补解析的返回值契约；题目原本有答案时**绝不覆盖**；公式不被改坏 |
| `BanksServiceImplAiEnrichTest` | 单题失败不毁整批、账号级失败识别与归类、FAILED→PENDING 善后、异常链成环不死循环 |
| `BanksServiceImplTest` | 上传校验矩阵、任务进度三级回落 |
| `TextDateServiceImplTest` | 考试信息 CRUD 与倒计时等级边界 |

几个测试上的取舍值得一提：

- **AI 相关的测试用 Mockito 替身**注入假的 `OpenAiChatModel`，所以不花 token、不依赖网络，
  但真的走完整条 `enrich()` 逻辑（含 JSON 解析与反斜杠修复）。
- **有一条用例专门断言"修复前确实是坏的"**（`AiJsonRepairTest`）：先证明 `\frac` 会被 Jackson
  解析成「换页符 + rac」，再断言修复后完好。这样万一有人"优化"掉了修复，测试会失败。
- **有防御性用例**（`should_notHangOnCyclicCauseChain`）：异常链成环时靠深度上限停下来，带 `@Timeout` 兜底。

> 测试里不含任何硬编码的本机路径 —— `mvn test` 在任意机器上都应通过
> （早期有一个调试脚本写死了绝对路径，已删除）。

---

## 已知限制

- **没有鉴权**：接口不区分用户，所有会话共享同一个题库空间，适合本地或内网部署
- 单文件上传上限 20MB，单请求 25MB
- 知识点总结最多取 300 题
- 聊天上下文的窗口是 100 条 / 30000 字符，超出部分丢弃
- `uploads/`（上传文件落盘目录）不入版本库
- AI 功能依赖用户自带的 Key 与模型；**部分模型不支持 function calling**，
  此时 AI 不会调用工具，表现为"查不到题"或凭空回答

### 刻意没做的（取舍，不是遗漏）

| 没做 | 原因 |
|---|---|
| **「续跑」没有界面入口** | 后端已做到"已解析的题不重复花钱"，但用户没有按钮触发它，只能重新上传（会建新题库、全部重新解析）。加续跑入口要引入新接口 + 前端按钮 + 任务状态机，**为省几道题的 token 不值得**；失败题不多时，用没解析的部分照样能刷 |
| **批量调用模型**（一次请求多道题） | 能进一步减少请求数，但要先**实测前缀缓存的影响**才能确定是否更划算（前缀缓存要求请求前缀完全一致） |
| **抽题不做加权/分层/可复现** | 均匀随机在几千题规模完全够用，加策略是过度设计 |
| **准确率评测指标** | 单用户本地应用，一个百分比没有决策价值；改为「解析自检」把问题**暴露出来**，而不是追求一个数字 |

### 待改进

- `Message()`（进度轮询）每次会多跑 3 次 `COUNT` 统计解析进度。几百题规模无感，
  单库上万题 + 多人轮询时应改成一次 `GROUP BY ai_status` 聚合查询
- `ai_status = FAILED` 的题目前不会自动重试（因为没有续跑入口），只能重新导入


---

## License

未指定。
