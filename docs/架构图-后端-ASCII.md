# Asteria 后端架构图（ASCII）

> 范围：**仅后端**。前端（`asteria-ai/`）暂时不纳入，只在「打包链路」一节标注其作为构建产物的接入点。
>
> 依据当前代码静态阅读绘制：后端 55 个 Java 文件（`main` 50 + `test` 5）、4 个 `pom.xml`、2 个 `yml`、8 个 `db/*.sql`。
> ⚠️ 本图未经运行验证——绘制期间工作区 shell 被沙箱拒绝（`SetNamedSecurityInfoW failed (Win32 5)`），
> 无法执行 `mvn` / 跑测试 / 起服务做交叉验证。

---

## 一、系统全景（后端运行时视角）

```
                        ┌──────────────────────────────────────────────┐
                        │         HTTP 客户端（浏览器 / curl / 前端）      │
                        └───────────────────────┬──────────────────────┘
                                                │
                                                ▼
        ╔═══════════════════════════════════════════════════════════════════════════╗
        ║              Spring Boot 3.5.16 · 内嵌 Tomcat · 端口 8080                  ║
        ║              Maven 由 spring-boot-maven-plugin 打成可执行 fat jar          ║
        ╠═══════════════════════════════════════════════════════════════════════════╣
        ║                                                                           ║
        ║   ┌───────────────────────────────┐   ┌───────────────────────────────┐   ║
        ║   │       /api/**                 │   │        其它所有路径             │   ║
        ║   │  DispatcherServlet            │   │  SpaWebConfig 静态资源处理      │   ║
        ║   │  → 7 个 @RestController       │   │  → classpath:/static/         │   ║
        ║   │  → 26 个端点                   │   │  → 回退 index.html            │   ║
        ║   └───────────────┬───────────────┘   └───────────────────────────────┘   ║
        ║                   │                                                       ║
        ║                   ▼                                                       ║
        ║     ┌─────────────────────────────────────────────────────────────┐       ║
        ║     │              Service 层（业务主干）                            │       ║
        ║     │   题库导入 · 刷题 · 知识点总结 · AI 聊天 · 考试倒计时            │       ║
        ║     └───────┬──────────────────────┬──────────────────────┬───────┘       ║
        ║             │                      │                      │               ║
        ║             ▼                      ▼                      ▼               ║
        ║     ┌───────────────┐     ┌────────────────┐     ┌────────────────┐       ║
        ║     │  MyBatis-Plus │     │  解析引擎       │     │  Spring AI     │       ║
        ║     │  12 个 Mapper │     │  QuestionParser│     │  BYOK 现造模型  │       ║
        ║     └───────┬───────┘     └────────────────┘     └───────┬────────┘       ║
        ╚═════════════╪══════════════════════════════════════════════╪════════════════╝
                      │                                              │
          ┌───────────┴───────────┐                     ┌────────────┴────────────┐
          ▼                       ▼                     ▼                         ▼
  ┌───────────────┐   ┌───────────────────┐   ┌──────────────────┐   ┌──────────────────┐
  │  MySQL 8      │   │  uploads/         │   │  外部 LLM        │   │  classpath:      │
  │  finaltext    │   │  {taskId}.{ext}   │   │  OpenAI 兼容协议  │   │  static/         │
  │  12 张表       │   │  相对 CWD 解析     │   │  key 由请求头带入 │   │  （前端构建产物）  │
  └───────────────┘   └───────────────────┘   └──────────────────┘   └──────────────────┘
```

---

## 二、工程结构（Maven 多模块）

```
asteria/  ······································ 父工程：packaging=pom，不含业务代码
│                                                  parent: spring-boot-starter-parent 3.5.16
│                                                  java.version=21 · spring-ai.version=1.1.8
│                                                  dependencyManagement 导入 spring-ai-bom
│
├── asteria-common/  ··························· 公共模块 · 6 个类 · 无 Spring 依赖
│   │                                              pom 只引 lombok(provided)
│   └── src/main/java/com/asteria/common/
│       ├── result/     ApiResponse ············· 统一响应体 {code, message, data}
│       ├── exception/  BusinessException ······· RuntimeException + int code
│       └── Tool/       QuestionParser ·········· ★ 题目切分引擎（纯正则，395 行）
│                       RawQuestion ············· 解析中间产物
│                       │                          rawType / rawStem / rawOptions
│                       │                          rawAnswer / chapterName / startLine / suspicious
│                       QuestionOption ·········· {key, text}
│                       FileTextReader ·········· UTF-8 严格解码失败 → 回退 GBK，去 BOM
│
├── asteria-pojo/  ····························· 模型模块 · 49 个类
│   │                                              pom 只引 mybatis-plus-annotation + lombok
│   └── src/main/java/com/asteria/pojo/
│       ├── entity/        12 个实体 ············ @TableName 映射 12 张表
│       │                  Bank / Chapter / Question / BankImport / PracticeSession /
│       │                  PracticeSessionQuestion / PracticeRecord / WrongQuestion /
│       │                  KnowledgeSummary / ChatSession / ChatMessage / TextDateTime
│       │                  （QuestionPage、AiChatBody 为额外非表类）
│       ├── entity/DTO/     9 个入参
│       ├── entity/VO/     23 个出参
│       └── enums/          4 个 ················ QuestionType / ImportStatus /
│                                                 ExamLevel / TrueFalseAnswer
│
├── asteria-server/  ··························· 可执行模块 · main 50 + test 5 = 55 个类
│   └── src/main/java/com/asteria/server/
│       ├── AsteriaServerApplication.java ······· ★ 唯一 main；无 @MapperScan
│       ├── controller/   7 个 ·················· HTTP 入口，26 个端点
│       ├── Services/     11 个 ················· 接口 5 + impl 5 + BanksImportTransactional
│       ├── mapper/      12 个 ·················· 全部 extends BaseMapper，零 XML
│       ├── config/       3 个 ·················· MybatisPlusConfig / ToolConfig / SpaWebConfig
│       ├── handler/      1 个 ·················· GlobalExceptionHandler
│       ├── ai/           8 个 ·················· AI 接入层
│       ├── parser/       3 个 ·················· QuestionClassifier / AnswerNormalizer / AnswerTexts
│       └── tool/         2 个 ·················· DocxTextReader / PdfTextReader
│
├── uploads/  ·································· 上传落盘目录（.gitignore 忽略）
├── docs/    ··································· 文档
└── README.md ·································· 417 行（⚠ 部分已与代码漂移，见第九节）
```

### 依赖方向（严格单向）

```
      ┌───────────────────────────┐
      │  asteria-common  (6 类)    │   统一响应 / 异常 / 解析工具
      │  不依赖 Spring             │
      └─────────────┬─────────────┘
                    │
                    │  被依赖              ┌───────────────────────────┐
                    │                      │  asteria-pojo  (49 类)     │
                    │                      │  实体 / DTO / VO / 枚举     │
                    │                      └─────────────┬─────────────┘
                    │                                    │  被依赖
                    ▼                                    ▼
      ┌─────────────────────────────────────────────────────────────────┐
      │                asteria-server  (50 类)                          │
      │  唯一持有 main()；引 mybatis-plus / mysql / poi / pdfbox /       │
      │  spring-ai-openai / starter-web / devtools                      │
      └─────────────────────────────────────────────────────────────────┘

      asteria-common  ⊥  asteria-pojo        两者互不相识

      └──► 直接后果：QuestionOption（common，解析用）与 QuestionOptionVO（pojo，展示用）
           是同一结构写了两份；common 里的 FileTextReader 无 @Component，
           只能靠 ToolConfig 手工注册成 Bean。
```

### 打包链路（前端仅作为产物出现在这里）

```
  Maven 生命周期：                       asteria-server/pom.xml 的插件
  ──────────────────────────────────────────────────────────────────────
  compile  ──►  target/classes/
  test     ──►  surefire 跑 5 个测试类
  prepare-package ──► maven-resources-plugin:copy-resources
                      ${project.basedir}/../asteria-ai/dist
                              │  拷贝（filtering=false）
                              ▼
                      target/classes/static/      ← 不改 src/main/resources
  package  ──►  spring-boot-maven-plugin 打成可执行 fat jar
                      target/asteria-server-0.0.1-SNAPSHOT.jar
  ──────────────────────────────────────────────────────────────────────
  ⚠ dist 不存在时插件跳过并告警，不会让构建失败
  ⚠ mvn package 必须带 clean：copy-resources 只覆盖同名文件，不删除上一次
    留在 target/classes/static 里的旧 chunk，结果会一个 jar 塞两份前端
```

---

## 三、后端分层全景（★ 主图）

```
╔══════════════════════════════════════════════════════════════════════════════════════════════╗
║                                    HTTP 请求（浏览器 / curl）                                  ║
╚══════════════════════════════════════════════════════════════════════════════════════════════╝
                                            │
                                            ▼
┌──────────────────────────────────────────────────────────────────────────────────────────────┐
│ 【0】Web 接入层 ······ SpaWebConfig.java:20-44（全项目唯一实现 WebMvcConfigurer 的类）          │
│     ├─ 真实静态文件优先                                                                        │
│     ├─ path 以 "api/" 开头 → null（保持 404 语义，绝不回退 HTML）                                │
│     ├─ path 含 "."      → null（静态资源缺失就是 404）                                          │
│     └─ 其余            → classpath:/static/index.html（SPA 深链可刷）                          │
│                                                                                              │
│   ✗ 无 Filter   ✗ 无 Interceptor   ✗ 无 CORS   ✗ 无鉴权   ✗ 无请求日志/traceId                  │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
                                            │
                                            ▼
┌──────────────────────────────────────────────────────────────────────────────────────────────┐
│ 【1】Controller 层 ······ 7 个类 / 26 个端点                                                   │
│                                                                                              │
│  ┌────────────────────────────────────────────────────────────────────────────────────────┐  │
│  │ BanksController      /api/banks      POST /import         上传并异步导入（multipart）     │  │
│  │                                     GET  /import/{taskId} 轮询导入进度                    │  │
│  │                                     GET  /                题库分页（keyword/page/pageSize）│  │
│  │                                     GET  /{id}            题库详情（章节 + 题型统计）      │  │
│  │                                     DELETE /{id}          删题库（级联 + 删磁盘文件）      │  │
│  ├────────────────────────────────────────────────────────────────────────────────────────┤  │
│  │ QuestionsController  /api/banks      GET  /{bankId}/questions  题目分页/筛选/搜索          │  │
│  │                      ↑ 与 BanksController 共用前缀，路由排查需跨两个类                      │  │
│  ├────────────────────────────────────────────────────────────────────────────────────────┤  │
│  │ PracticeController   /api/practice   POST /sessions             开始刷题（顺序/随机）      │  │
│  │                                     POST /sessions/wrong       错题重刷                   │  │
│  │                                     GET  /sessions              最近会话（limit 默认 5）  │  │
│  │                                     GET  /sessions/{id}         取当前题（防偷看）          │  │
│  │                                     POST /sessions/{id}/answers 提交答案                   │  │
│  │                                     GET  /sessions/{id}/result  本次结果                   │  │
│  │                                     GET  /stats                 总览统计                   │  │
│  │                                     GET  /wrong-stats           错题统计                   │  │
│  ├────────────────────────────────────────────────────────────────────────────────────────┤  │
│  │ AiChatController     /api/chat       POST /sessions             新建会话                   │  │
│  │                                     GET  /sessions              会话列表                   │  │
│  │                                     DELETE /sessions/{id}       删会话（消息级联）          │  │
│  │                                     GET  /sessions/{id}/messages 历史消息                 │  │
│  │                                     POST /sessions/{id}/messages ★ SSE 流式对话           │  │
│  │                                     ✗ 越权：在 SSE 回调里直接写库 + 调 AiErrors.mask       │  │
│  ├────────────────────────────────────────────────────────────────────────────────────────┤  │
│  │ AiController        /api/ai          POST /test    测试 AI 连通性（无 body，配置全在头）    │  │
│  ├────────────────────────────────────────────────────────────────────────────────────────┤  │
│  │ AiSummaryController /api/knowledge-summary                                                     │
│  │                                     POST /   生成知识点总结（缓存优先）                    │  │
│  │                                     GET  /   读缓存（没生成过返回 data:null）             │  │
│  │                                     ✗ 越权：Service 直接返回 ApiResponse                  │  │
│  ├────────────────────────────────────────────────────────────────────────────────────────┤  │
│  │ TextDateController  /api/config      POST   /exams              新增考试信息               │  │
│  │                                     GET    /exams              列表（现算 daysLeft/level）│  │
│  │                                     PUT    /exams/{id}         修改                      │  │
│  │                                     DELETE /exams/{id}         删除                      │  │
│  └────────────────────────────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
                                            │
                                            ▼
┌──────────────────────────────────────────────────────────────────────────────────────────────┐
│ 【2】统一契约层（横切）                                                                        │
│   ApiResponse<T>{code, message, data}    业务失败 = HTTP 200 + code≠0；系统错误才 4xx/5xx      │
│                                                                                              │
│    0      成功              40010 参数不合法        40402 刷题会话不存在                        │
│    40001  空文件            40011 该条件下没题目     40403 题目不存在                           │
│    40002  文件类型非法       40012 没有待攻克错题     40404 聊天会话不存在                        │
│    40003  超 20MB           40013 题目不属于本会话    50000 服务器内部错误 / 总结失败            │
│    40020  未配置 AI         40400 接口不存在         50001 存盘失败 / AI 抽取失败               │
│    40401  题库不存在                                 50002 任务登记失败                         │
│                                                                                              │
│   GlobalExceptionHandler.java:17-46                                                          │
│     BusinessException         → HTTP 200 + 业务 code（前端只认 body.code）                    │
│     NoResourceFoundException  → HTTP 404 + 40400                                             │
│     Exception（兜底）          → HTTP 500 + 50000（堆栈只进日志）                              │
│   ✗ 无 Bean Validation 处理器 → 缺参数 / 坏 JSON 一律退化成 500/50000                          │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
                                            │
                                            ▼
┌──────────────────────────────────────────────────────────────────────────────────────────────┐
│ 【3】AI 接入层 ······ server/ai/ ×8（BYOK：每个请求现场造模型，用完即弃）                         │
│  ┌────────────────────┬──────────────────────────────────────────────────────────────┐       │
│  │ AiHeaders          │ from(req) / require(req)；取 X-AI-Provider / Key / Base-Url /  │       │
│  │                    │ Model；缺项 → require 抛 40020                                │       │
│  │ AiRequestConfig    │ record(provider, apiKey, baseUrl, model)，不可变以支持跨线程；   │       │
│  │                    │ usable() / endpoint(path) / toString() 给 key 打码             │       │
│  ├────────────────────┼──────────────────────────────────────────────────────────────┤       │
│  │ AiChatModelFactory │ create(config, temp[, extraBody][, responseFormat])           │       │
│  │                    │   ├ 关掉 Spring AI 默认 10 次重试（RetryTemplate.maxAttempts=1）│       │
│  │                    │   ├ createForImport：强制 response_format=json_object        │       │
│  │                    │   └ 只对 deepseek-flash / v4-flash / v4-pro 加 thinking=disabled│      │
│  ├────────────────────┼──────────────────────────────────────────────────────────────┤       │
│  │ AiErrors           │ mask(e, config)：压一行 → 截 200 字 → key 替换 ***（3 处复用）  │       │
│  ├────────────────────┼──────────────────────────────────────────────────────────────┤       │
│  │ QuestionBankTools  │ ★ 3 个 @Tool，每个方法内部一律 try/catch（否则整条 SSE 变 error）│       │
│  │                    │   listBanks()                                                  │       │
│  │                    │   searchQuestions(keyword, bankName?, type?, limit?)           │       │
│  │                    │     关键词≥2 字 · limit 默认 5 上限 20 · 题干超 1000 字截断      │       │
│  │                    │   getQuestionDetail(questionId)                                │       │
│  │                    │     返回给模型的紧凑文本，不是 JSON                              │       │
│  ├────────────────────┼──────────────────────────────────────────────────────────────┤       │
│  │ QuestionAiEnricher │ 补解析/知识点；温度 0.3；BeanOutputConverter 结构化输出；        │       │
│  │                    │ 缺答案时按题型校验 AI 答案（判断题收敛到 A/B）                   │       │
│  ├────────────────────┼──────────────────────────────────────────────────────────────┤       │
│  │ AiQuestionExtractor│ ★ 规则吃不下时的兜底：按题目边界分块(2000字/硬上限4000)         │       │
│  │                    │   → 每块最多 2 次尝试 → 检测 finish_reason=length 截断          │       │
│  │                    │   → JSON 解析（容忍 markdown 围栏）→ 逐题校验：                │       │
│  │                    │      空题干丢弃 · 非法/重复选项丢弃 · 答案字母不在选项里只丢答案  │       │
│  ├────────────────────┼──────────────────────────────────────────────────────────────┤       │
│  │ AiTestResult       │ record(boolean ok, String message)                             │       │
│  └────────────────────┴──────────────────────────────────────────────────────────────┘       │
│                                                                                              │
│  ⚠ 提示词全部内联为 Java text block，无外部资源文件：                                            │
│     ChatServiceImpl:83-131（16 条规则）· AiQuestionExtractor:68-85 ·                          │
│     QuestionAiEnricher:54-60 · AiServiceImpl:190-201 / 283-291                                │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
                                            │
                                            ▼
┌──────────────────────────────────────────────────────────────────────────────────────────────┐
│ 【4】业务服务层 ······ Services/ ×11（接口 5 + impl 5 + BanksImportTransactional）              │
│                                                                                              │
│  ┌───────────────────────┬──────────────────────┬────────────────────────────────────┐      │
│  │ BanksService          │ QuestionsService     │ PracticeService                    │      │
│  │  ├ importBanks 异步    │  └ PageQuestions     │  ├ createSession                   │      │
│  │  ├ Message 进度三级回落 │    （只读）            │  ├ createWrongSession              │      │
│  │  ├ pageQuery          │                      │  ├ getSessionDetail（防偷看）        │      │
│  │  ├ getBankDetail      │                      │  ├ submitAnswer ★ 判分 + 错题本      │      │
│  │  ├ deleteBank         │                      │  ├ getSessionResult                │      │
│  │  └ （私有）processImport / aiEnrich / briefReason                                │      │
│  │                       │                      │  ├ getWrongStats / getStudyStats    │      │
│  │                       │                      │  └ listRecentSessions              │      │
│  ├───────────────────────┼──────────────────────┼────────────────────────────────────┤      │
│  │ AiService             │ ChatService          │ TextDateService                    │      │
│  │  ├ testConnection     │  ├ createSession     │  └ addText / listAll /             │      │
│  │  ├ createSummary 缓存 │  ├ deleteSession     │     updateText / deleteText        │      │
│  │  ├ getSummary         │  ├ getSessions       │                                    │      │
│  │  └ ✗ 返回 ApiResponse │  ├ getMessages       │                                    │      │
│  │                       │  ├ sendMessage→Flux  │                                    │      │
│  │                       │  └ saveMessage       │                                    │      │
│  ├───────────────────────┴──────────────────────┴────────────────────────────────────┤      │
│  │ BanksImportTransactional ← 单独成 Bean 只为让 @Transactional 生效                   │      │
│  │   ├ saveImport  @Transactional(rollbackFor=Exception.class)                       │      │
│  │   │    插 bank → 按章节名 LinkedHashMap 建 chapter(sort=首现序)                     │      │
│  │   │    → 逐题：空题干跳过 / 判型 null 跳过 / 答案 null 用空串占位 / insert           │      │
│  │   │    → 一题都没入库 → 抛 IllegalStateException 整体回滚                           │      │
│  │   └ markFailed  独立事务（写在入库事务里会被回滚一起抹掉 → 任务永久卡 PARSING）        │      │
│  └────────────────────────────────────────────────────────────────────────────────────┘      │
│                                                                                              │
│   事务边界（全项目仅此 5 处 @Transactional）：                                                   │
│     saveImport · markFailed · createSession · createWrongSession · submitAnswer              │
│   ✗ 无事务的写操作：importBanks 的任务登记 · ChatServiceImpl.saveMessage（两次写）·              │
│     AiServiceImpl.saveSummary（写后回读）· aiEnrich 的逐题更新 · TextDateServiceImpl 全部       │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
                    ┌───────────────────────┴────────────────────────┐
                    ▼                                                ▼
┌───────────────────────────────────────────────┐  ┌───────────────────────────────────────────┐
│ 【5】解析引擎层（纯规则，不依赖模型）              │  │ 【5'】文本抽取层（server/tool/）             │
│                                               │  │                                           │
│  QuestionParser ···· common/Tool（395 行）     │  │  DocxTextReader ···· POI 5.2.5            │
│    ├ 题号：【第N题】/（N）/ N、N. N．             │  │    └ 把 \t 换成换行                        │
│    │       题号后可【直接跟题干】                  │  │       （Word 里 A．B．C．D．同段的处理）    │
│    ├ 小节：1．选择题（整行匹配，防误伤              │  │                                           │
│    │       「1．判断题的做法是…」）                │  │  PdfTextReader ···· PDFBox 2.0.31         │
│    ├ 章节：【第N章】/ 习 题 N 直接认               │  │    └ setSortByPosition(true)              │
│    │       裸写「第N章」加双限制                  │  │       （扫描件抽不出文字）                 │
│    │       （≤30 字 · 不含句末标点）              │  │                                           │
│    ├ 选项：A. / A．（兼容全角）                  │  │  FileTextReader ···· common/Tool          │
│    ├ 答案：正确答案 / 参考答案                    │  │    └ UTF-8 → GBK 回退 · 去 BOM · 统一换行  │
│    │       故意不认单独的「答案」                  │  │                                           │
│    │       （以免吞掉「我的答案」）                │  │  ⚠ 三个 Reader 中两个是 @Component、        │
│    └ 裸答案行：必须是本题选项字母                  │  │     一个靠 ToolConfig 手工注册              │
│                    │                          │  └───────────────────────────────────────────┘
│                    ▼                          │
│  QuestionClassifier ···· server/parser        │
│    ① 题型：中文映射（QuestionType.ofChinese）   │
│    ② 选项恰好 2 个且都是对错词 → TRUE_FALSE     │
│    ③ 有选项 + 答案字母数 > 1   → MULTIPLE      │
│    ④ 有选项 + 单个字母         → SINGLE        │
│    ⑤ 无选项 + 答案 ≤ 20 字     → FILL_BLANK    │
│    ⑥ 无选项 + 更长             → ESSAY         │
│    ⑦ 都判不出 → null（调用方跳过该题）           │
│                    │                          │
│                    ▼                          │
│  AnswerNormalizer ···· server/parser          │
│    ├ SINGLE  取首字母                          │
│    ├ MULTIPLE 去重排序                         │
│    ├ TRUE_FALSE 先查选项文本 → 再判语义 → A/B   │
│    └ FILL_BLANK / ESSAY 原样 trim              │
│                    │                          │
│  AnswerTexts（包私有）字母抽取 / 全角→半角归一      │
└───────────────────────────────────────────────┘
                                            │
                                            ▼
┌──────────────────────────────────────────────────────────────────────────────────────────────┐
│ 【6】数据访问层 ······ mapper/ ×12 @Mapper extends BaseMapper（零 XML）                          │
│                                                                                              │
│   BankMapper ......................... bank                                                   │
│   ChapterMapper ....................... chapter          ＋ countGroupByBank                  │
│                                                          ＋ selectChaptersWithCount           │
│   QuestionMapper ...................... question         ＋ countGroupByBankAndType           │
│   BanksImportMapper ................... import_task                                           │
│   PracticeSessionMapper ............... practice_session ＋ refreshCounts                     │
│                                                          ＋ selectStudyStats / selectRecent   │
│   PracticeSessionQuestionMapper ....... practice_session_question                             │
│                                                          ＋ insertBatch / countByType         │
│   PracticeRecordMapper ................ practice_record  ＋ selectWrongItems                  │
│   WrongQuestionMapper ................. wrong_question   ＋ countGroupByType /                │
│                                                          countGroupByChapter / countByBank    │
│   KnowledgeSummaryMapper .............. knowledge_summary                                     │
│   ChatSessionMapper ................... chat_session                                          │
│   ChatMessageMapper ................... chat_message                                          │
│   TextDateTimeMapper .................. textdatetime                                          │
│                                                                                              │
│   MybatisPlusConfig                                                                          │
│     ├ PaginationInnerInterceptor(DbType.MYSQL) + maxLimit(500)                                │
│     └ MetaObjectHandler                                                                       │
│         strictInsertFill → createdAt / updatedAt                                              │
│         strictUpdateFill → updatedAt（⚠ 只在字段为 null 时才填）                                │
│         ⚠ 按字段名生效，实体漏写注解就静默不填 → 靠 DB DEFAULT CURRENT_TIMESTAMP 兜底（双真相源）  │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
                                            │
                                            ▼
╔══════════════════════════════════════════════════════════════════════════════════════════════╗
║                        MySQL 8 · finaltext · 12 张表 · utf8mb4_0900_ai_ci                     ║
╚══════════════════════════════════════════════════════════════════════════════════════════════╝
```

---

## 四、数据模型（12 张表 ER）

```
   ┌──────────────────────────────┐
   │         import_task          │  task_id VARCHAR(36) PK（应用发 UUID）
   │  文件导入任务：进度 / 失败原因  │  status / progress / total_count / error_message
   │                              │  bank_id —— 逻辑关联，故意不建 FK（删题库保留任务）
   └──────────────┬───────────────┘
                  │  一次成功导入产出 1 个题库
                  ▼
   ┌─────────────┐      ┌──────────────────────┐      ┌──────────────────────────────┐
   │   chapter   │      │         bank         │      │          question            │
   │ 章节分组      │◄─────┤  题库                 ├─────►│  题目                          │
   │ name / sort  │ 1:N  │ name / file_name     │ 1:N  │  type · stem · options(JSON)  │
   │              │      │ file_type            │      │  answer · analysis · 知识点(JSON)│
   └──────┬──────┘      └──────────┬───────────┘      │  ★ bank_id 冗余列（无 FK）      │
          │                        │                   └───────────┬──────────────────┘
          │ FK CASCADE             │ FK CASCADE                    │
          ▼                        ▼                               │
   ┌──────────────────────────────┐  ┌──────────────────────┐      │
   │ practice_session_question    │  │  practice_session    │      │
   │ 会话题目清单                   │  │ mode / question_type │      │
   │ sort                          │  │ chapter_id（SET NULL）│      │
   │ UNIQUE(session_id,question_id)│  │ total/answered/correct│     │
   └──────────┬───────────────────┘  │ status / session_source│     │
              │ FK CASCADE           └──────────┬───────────┘      │
              ▼                                   ▼                  │
   ┌──────────────────────────────┐  ┌──────────────────────┐      │
   │      practice_record         │  │    wrong_question    │◄─────┘
   │ user_answer / is_correct     │  │ 错题本                 │
   │ UNIQUE(session_id,question_id)│  │ wrong_count          │
   │ （同一题重答 = UPDATE 覆盖）   │  │ resolved             │
   └──────────────────────────────┘  │ ⚠ 无时间列            │
                                      └──────────────────────┘
   ┌──────────────────────────────┐  ┌──────────────────────┐  ┌──────────────────────┐
   │     knowledge_summary        │  │     chat_session     │  │    textdatetime      │
   │ UNIQUE(bank_id)              │  │ title / agent_mode   │  │ 考试倒计时             │
   │ 5 个 JSON 列                  │  │ message_count（冗余）  │  │ name / date(DATE)    │
   │  （AI 总结缓存，命中不调模型）   │  └──────────┬───────────┘  └──────────────────────┘
   └──────────────────────────────┘             │ FK CASCADE
                                      ┌──────────▼───────────┐
                                      │     chat_message     │
                                      │ role / content       │
                                      │   MEDIUMTEXT         │
                                      │ interrupted TINYINT  │
                                      └──────────────────────┘

   时间戳统一由 MetaObjectHandler 自动填充（created_at 插入时填，updated_at 插入与更新时填）
```

---

## 五、端到端流程

### ① 题库导入（异步 + 三级进度回落）

```
客户端      BanksController   BanksServiceImpl   Reader        QuestionParser   BanksImportTransactional   MySQL
   │              │                  │             │                │                    │              │
   │ POST /import │                  │             │                │                    │              │
   ├─────────────►│ aiParse? require(AiHeaders)    │                │                    │              │
   │              │   ⚠ 先取 AI 配置，再交 Service  │                │                    │              │
   │              ├─────────────────►│ 校验：空文件 40001 · 扩展名 40002 · >20MB 40003  │              │
   │              │                  ├─ 落盘 uploads/{taskId}.{ext}                    │              │
   │              │                  ├─ INSERT import_task = PENDING ──────────────────┼─────────────►│
   │              │                  ├─ importTasks.put(taskId, 快照)                    │              │
   │              │                  ├─ new Thread("import-" + taskId).start()          │              │
   │◄─────────────┤                  │  ⚡ 主线程立即返回 {taskId, PENDING}              │              │
   │              │                  │                                                │              │
   │              │        ┌─────────┴════════ 后台线程 ════════┴───────────────────────┤              │
   │              │        │ Docx/Pdf/FileTextReader 抽文本 → new QuestionParser().parse│              │
   │              │        │   ⚠ 手工 new，不是 Spring Bean                             │              │
   │              │        │                                                        │              │
   │              │        │ looksUsable? ──否──► AiQuestionExtractor.extract()       │              │
   │              │        │   （勾了 AI 解析才有；没勾 → 40020）                       │              │
   │              │        │   逐题校验后返回 List<RawQuestion>                       │              │
   │              │        │                                                        │              │
   │              │        ├─ saveImport()  @Transactional ★ 一个事务                  │              │
   │              │        │    INSERT bank ─────────────────────────────────────────┼─────────────►│
   │              │        │    INSERT chapter（LinkedHashMap，sort = 首现序）          │              │
   │              │        │    逐题：空题干跳过 → classify null 跳过 → normalize      │              │
   │              │        │          → INSERT question ────────────────────────────┼─────────────►│
   │              │        │    inserted==0 → 抛异常整体回滚（不留空题库）              │              │
   │              │        │    UPDATE import_task（aiParse ? AI_PROCESSING : SUCCESS）│              │
   │              │        │                                                        │              │
   │              │        ├─ aiParse? aiEnrich() 温度 0.3 逐题补解析，每 5 题落库     │              │
   │              │        │                                                        │              │
   │              │        └─ catch → 内存置 FAILED + markFailed()【独立事务】         │              │
   │              │                  ⚠ 写在入库事务里会被回滚一起抹掉                   │              │
   │              │                  + deleteQuietly(磁盘文件)                        │              │
   │              │                  │                                                │              │
   │ GET /import/{id} 轮询            │                                                │              │
   ├─────────────►│ Message(taskId)  │                                                │              │
   │              │  三级回落：① 内存 ConcurrentHashMap  ② import_task 表  ③ FAILED   │              │
   │◄─────────────┤ {status, progress, totalCount, bankId, errorMessage}              │              │
```

### ② 刷题会话

```
POST /api/practice/sessions
  → 校验 bankId / mode / questionType（手写 if）
  → checkBankExists ................................. 40401
  → pickQuestionIds：按 bank/type/chapter 只 select(id)；RANDOM 用 Collections.shuffle
       ⚠ 整库题号一次性载入，无数量上限
       └ 空 → 40011
  → saveSession：INSERT practice_session（自增 id 回填）
                 + 一条多值 INSERT 写 N 行 practice_session_question
       ⚠ 无分页语义，大题库 SQL 包体膨胀

GET /api/practice/sessions/{id}
  → 复制会话字段 → 按 sort 取清单 → 一次 IN 查全部题目建索引
  → 填 VO 时【刻意不拷 answer / analysis】  ← 防偷看（BanksService 同类做法）
  → 附上已答记录

POST /api/practice/sessions/{id}/answers
  → 校验 40010 → 会话 40402 → 题目 40403 → 题目属于本会话 40013
  → judge()：SINGLE/TRUE_FALSE 全等 · MULTIPLE 比字母集合 · FILL_BLANK 逐空比
             · ESSAY 返回 null（不判分）
  → saveRecord()：先 selectOne，无则 insert、有则 updateById（同题重答 = 覆盖）
  → updateWrongBook()：答错 → insert 或 wrong_count+1 且 resolved=0
                       答对 → resolved=1；未判分 → 不动
  → refreshCounts()：以 practice_record 为准【全量重算】answered/correct
       ✗ 每答一题一次 COUNT(*) → 单会话 O(N²)
  → 答满 → UPDATE status=COMPLETED + completed_at
```

### ③ 知识点总结（缓存优先）

```
POST /api/knowledge-summary
  → AiHeaders.from()（不是 require —— 命中缓存时允许没配 AI）
  → 校验 bankId 40010 → 题库存在 40401
  → force != true 时先查 knowledge_summary 缓存
       └ 命中 → 直接返回，一次 AI 都不调
  → 才校验 AI 配置 40020 → 题库有题 40011
  → 取材料：题型 + 题干 + 答案，按 id 升序 LIMIT 300
  → 温度 0.3 + BeanOutputConverter + 中文系统提示词
  → 第二次调用生成「易错点」：wrong_question resolved=0 按 wrong_count 降序 LIMIT 100
       （失败按空数组吞掉，不影响主结果）
  → saveSummary：有则 updateById、无则 insert，写完回读；5 个 JSON 列序列化
```

### ④ AI 聊天 SSE（含工具调用）

```
客户端      AiChatController   AiHeaders   ChatServiceImpl   ModelFactory     LLM    QuestionBankTools
  │              │                │             │                │            │            │
  │ POST /sessions/{id}/messages  │             │                │            │            │
  ├─────────────►│ require()      │             │                │            │            │
  │              ├───────────────►│ 缺 X-AI-Key/Model/Base-Url → 40020          │            │
  │              ├──────────────────────────────►│               │            │            │
  │              │                │             ├ loadRecentMessages：          │            │
  │              │                │             │   倒序 LIMIT 100 + 30000 字符预算│           │
  │              │                │             │   Collections.reverse 成正序   │            │
  │              │                │             │   ⚠ 必须先取历史再落库本轮，    │            │
  │              │                │             │     顺序反了本轮会重复          │            │
  │              │                │             │   ⚠ 只保留 user/assistant 文本，│           │
  │              │                │             │     system 历史丢弃；工具调用   │            │
  │              │                │             │     与工具结果不持久化          │            │
  │              │                │             ├ create(config, 0.7) ─────────►│            │
  │              │                │             │   ★ 每次新建 OpenAiChatModel， │            │
  │              │                │             │     用完即弃（BYOK 不能复用单例）│           │
  │              │                │             ├ ChatClient.tools(QuestionBankTools)│        │
  │              │                │             │        .stream().content()     │            │
  │              │                │             │                ├────────────►│            │
  │              │                │             │                │◄─ tool_call ──────────►│
  │              │                │             │                │  listBanks /           │
  │              │                │             │                │  searchQuestions /     │
  │              │                │             │                │  getQuestionDetail     │
  │              │                │             │                │◄─ 结果回灌 ─────────────┤
  │              │                │             │                │            │            │
  │              │ SseEmitter(0L) ★ 0 = 不超时    │                │            │            │
  │◄─event: chunk {"delta":"..."} ─┤◄── Flux<String> ─────────────┤◄───────────┤            │
  │◄─event: chunk {"delta":"..."} ─┤             │                │            │            │
  │◄─event: done  {messageId,content} 落库 + message_count+1（两次写，✗ 无事务）│            │
  │◄─event: error {code,message} ──┤ ★ AiErrors.mask() 脱敏 + 把已生成部分按        │            │
  │                                │   interrupted=1 落库                          │            │
  │                                │ ⚠ SseEmitter 无 onTimeout/onCompletion，      │            │
  │                                │   subscribe 未保留 Disposable → 客户端断开后    │            │
  │                                │   模型仍继续生成（继续计费）                    │            │
```

---

## 六、横切关注点汇总

```
┌──────────────────┬────────────────────────────────────────────────────────────────────────┐
│ 关注点             │ 现状                                                                    │
├──────────────────┼────────────────────────────────────────────────────────────────────────┤
│ 统一响应          │ ApiResponse{code,message,data}；业务失败 = HTTP 200 + code≠0             │
│ 异常处理          │ GlobalExceptionHandler 3 个分支；✗ 缺 Bean Validation 处理器              │
│ 参数校验          │ ✗ 无 spring-boot-starter-validation，全部 Service 手写 if                  │
│ 鉴权              │ ✗ 完全没有（三个 pom 无 security/jwt；无拦截器）→ /api/** 匿名可访问        │
│ 事务              │ 仅 5 处 @Transactional；聊天落库、总结落库、aiEnrich 均无事务               │
│ 日志              │ Lombok @Slf4j；✗ 无 logback 配置、无 traceId、无请求日志切面                 │
│ 异步              │ ✗ new Thread() 裸线程，无池化无上限；@Async/@Scheduled 全项目零使用          │
│ 缓存              │ knowledge_summary 表做 AI 总结缓存；无 Redis、无本地缓存框架               │
│ 文件存储          │ uploads/{taskId}.ext，相对路径基于 CWD；✗ 无清理、无配额                     │
│ 密钥安全          │ BYOK：key 只在造 OpenAiApi 时使用，不落库、不写日志，异常统一 mask           │
│ 配置              │ 仅 1 处 @Value（upload-dir）；无 @ConfigurationProperties                  │
│ Profile           │ ✗ spring.profiles.active: dev 硬编码，无 prod profile 文件               │
└──────────────────┴────────────────────────────────────────────────────────────────────────┘
```

---

## 七、关键设计决策（改动前先理解理由）

```
① 为什么 spring.ai.model.* 全部设成 none？
   → BYOK 模式下 key 由每个请求的头带入，不能有启动时就建好的单例 ChatModel
     （否则会把 A 的 key 用到 B 的请求上）。所以模型对象每次现造现用。
     注意每种模型类型是独立开关（chat/embedding/image/moderation/audio.*），
     漏关一个服务就起不来。

② 为什么解析分「规则」和「AI 兜底」两条路？
   → 规则（QuestionParser）零成本、可预测，覆盖正常题库；只有整份文件一道题都
     切不出来时，才交给模型直吐 JSON。
     不让 AI「改写成标准格式文本再拿正则解析」：格式由协议保证，字段缺了程序一眼
     看得出来；文本形态下模型漏写标签、把选项并进题干都会解析出残题。

③ 为什么 AI 抽取要按「题目边界」分块而不是按字数？
   → 切点落在题目中间会让前后两块各拿到半道题。

④ 为什么空题干必须在入库前拦掉？
   → stem 列是 TEXT NOT NULL 且无默认值，而 MyBatis-Plus 默认字段策略是 NOT_NULL，
     null 时会把该列从 INSERT 列清单里整个剔掉 → MySQL 严格模式报 1364。
     导入是同一个事务，一条脏数据能把整批题目全部回滚。

⑤ 为什么答案取不到不跳过（用空串占位）？
   → answer 列也是 NOT NULL；缺答案的题照样入库，交给后台 AI 阶段补，
     不开 AI 时就一直是空串。宁可没答案，也不要丢题。

⑥ 为什么 QuestionBankTools 每个方法都 try/catch？
   → 工具抛异常会让整条 SSE 变成 error 事件；转成一句人话回给模型，它还能接着答。

⑦ 为什么 getSessionDetail 刻意不拷 answer / analysis？
   → 防偷看：刷题时前端拿不到答案。

⑧ 为什么 BanksImportTransactional 单独成一个 Bean？
   → Spring 的 @Transactional 靠代理生效，同类内部调用（this.xxx）不走代理。
     放到独立 Bean 上由后台线程跨 Bean 调用，事务才真的生效。

⑨ 为什么 markFailed 必须是独立事务？
   → 写在入库事务里的话，事务回滚会把「失败记录」一起抹掉，任务永远卡在 PARSING。

⑩ 为什么 createForImport 只对 deepseek 特定模型关 thinking？
   → deepseek-flash / v4-pro（含老名 v4-flash）的思考模式默认是开的，抽取是机械活，
     开着只是更慢更贵，还会让 temperature 失效。其它服务商、以及不认这个字段的
     老模型（deepseek-chat / deepseek-reasoner）一个字段都不加。
```

---

## 八、代码组织发现

```
【分层越权】
  ✗ AiService 接口直接返回 common.result.ApiResponse   → Service 无法脱离 HTTP 契约复用
  ✗ AiChatController 在 SSE 回调里写库、调 AiErrors.mask → Web 层承担持久化与脱敏
  ✗ 解析器混用 Spring 管理与手工 new：new QuestionParser() vs @Component 的
     QuestionClassifier / AnswerNormalizer / DocxTextReader / PdfTextReader
  ✗ common 模块无 Spring 依赖，导致 FileTextReader 要靠 ToolConfig 手工注册

【命名一致性】
  ✗ 包名大小写混乱：Services / Tool / entity.DTO / entity.VO 大写，
     而 Services.impl 小写，与 Java 包命名规范冲突，重命名将波及全部 import
  ✗ 方法名大写开头：BanksService.Message()、QuestionsService.PageQuestions()
  ✗ 同一前缀 /api/banks 被 BanksController 与 QuestionsController 瓜分

【重复模型】
  ✗ QuestionOption（common，key/text）vs QuestionOptionVO（pojo）—— 同一结构两份
  ✗ QuestionPage 与 PageResultVO 字段完全相同，各自服务一个接口
  ✗ BanksVO 同时承载导入响应与题库列表项
  ✗ 分页上限四处不一致：Banks 200 / Questions 100 / Chat 200 / Practice limit 20
     （另有分页插件全局 maxLimit 500）

【死代码 / 无效声明】
  ✗ QuestionParser.debugHeader 只用 System.out，唯一调用点已被注释（test/Text.java）
  ✗ AiChatBody.agentMode 服务端从不读取（ChatServiceImpl 只用 content）
  ✗ AiServiceImpl.mask 私有方法只是转发 AiErrors.mask
  ✗ QuestionsServiceImpl 上 @RequiredArgsConstructor 与全 @Autowired 非 final 字段并存
  ✗ chat_session.agent_mode 的 EXTERNAL 值无任何分支使用（未实现的占位）

【错误码与异常】
  ✗ 缺参数 / 坏 JSON → 落兜底变成 500 + 50000，而不是 40010
  ✗ BanksController 先取 AI 配置再校验文件 → 勾了 AI 却没配时，
     非法文件得到 40020 而不是 40002，错误定位被误导

【并发与生命周期】
  ✗ 每上传一个裸线程，无池化、无上限、无排队、无取消
  ✗ 导入进度 Map 永不清理（内存单调增长）
  ✗ 进度 VO 是可变 @Data bean，跨线程原地 setter，Message() 直接返回同一引用
     （无 volatile、无快照 → 读线程可能看到旧值）
  ✗ 进程重启后任务永久卡死：无 @PostConstruct/@Scheduled 恢复机制，
     DB 里停在 PARSING / AI_PROCESSING
  ✗ AI 阶段每 5 题才落一次库 → 崩溃时丢掉这一段进度

【性能】
  ✗ 每答一题全量 COUNT(*) 重算会话计数 → 单会话 O(N²)
  ✗ 抽题把整库题号全量载入并整批写清单，无数量上限
  ✗ 学习统计 = SUM(可变会话行)，题库 FK CASCADE → 删题库即丢历史
  ✗ 聊天会话列表 selectList(null) 全表加载，且排序方向与 DDL 注释相反

【文档与代码漂移】⚠ 重构时按注释理解必然踩坑
  ✗ bank_tables.sql 写「判断 TRUE|FALSE」，代码已统一为 A/B
  ✗ practice_tables.sql / PracticeSession 注释写「提交答案时 +1」，实际是全量重算
  ✗ import_task.sql 的状态列表缺 AI_FORMATTING（ImportStatus 枚举里有）
  ✗ bank_tables.sql 写「bankName 缺省取文件名」，实际兜底成 taskId（且有测试固化）

【测试覆盖】
  ✓ BanksServiceImplTest      上传校验矩阵 + Message 三级回落（只测主线程同步逻辑）
  ✓ TextDateServiceImplTest   日期解析 + 倒计时等级边界 + CRUD 分支
  ✓ AiQuestionExtractorTest   JSON→题目映射 + 逐题校验闸门（不让垃圾进库）
  ✓ AsteriaServerApplicationTests  仅 contextLoads()，需真实 MySQL
  ✗ test/Text.java            写死本机绝对路径、无任何断言 → 不是有效测试
  ✗ 零测试：QuestionParser / QuestionClassifier / AnswerNormalizer / AnswerTexts /
            PracticeServiceImpl（判分·错题本·进度）/ ChatServiceImpl（记忆裁剪）/ 
            AiServiceImpl（缓存读写）/ AiChatModelFactory / 全部 Controller /
            GlobalExceptionHandler / 所有 Mapper SQL
```

---

## 九、图例

```
 ★  关键设计 / 有意为之的写法   →  改动前先读第七节的理由
 ✗  已证实的结构性缺陷
 ⚠  行为陷阱 / 需要人工确认的点
 ✓  当前做得对、值得保留的部分
```

---

## 附：外部依赖清单（仅后端）

```
┌──────────────────────┬─────────────────┬────────────────────────────────────────────────┐
│ 依赖                  │ 版本             │ 用途                                            │
├──────────────────────┼─────────────────┼────────────────────────────────────────────────┤
│ spring-boot-starter  │ 3.5.16（父 POM） │ parent + 版本管理                                │
│   -parent            │                 │                                                │
│ spring-boot          │ 由父 POM 管理     │ Spring MVC（Servlet 栈，非 WebFlux）             │
│   -starter-web       │                 │                                                │
│ spring-boot-devtools │ 由父 POM 管理     │ 开发热重启（runtime, optional）                   │
│ spring-boot          │ 由父 POM 管理     │ 测试（test scope）                               │
│   -starter-test      │                 │                                                │
│ mybatis-plus         │ 3.5.15          │ 持久层（Boot 3 专用 starter）                     │
│   -spring-boot3      │                 │                                                │
│   -starter           │                 │                                                │
│ mybatis-plus         │ 3.5.15          │ 分页插件依赖（3.5.9+ 必须单独引入 JSqlParser）      │
│   -jsqlparser        │                 │                                                │
│ mybatis-plus         │ 3.5.15          │ 实体注解（只在 pojo 模块）                         │
│   -annotation        │                 │                                                │
│ mysql-connector-j    │ 由父 POM 管理     │ MySQL 驱动（runtime）                            │
│ poi-ooxml            │ 5.2.5           │ 抽 .docx 文本（docx 是 ZIP+XML）                  │
│ pdfbox               │ 2.0.31          │ 抽 .pdf 文本（二进制格式）                         │
│ spring-ai-starter    │ 1.1.8（BOM）     │ OpenAI 兼容协议接入                               │
│   -model-openai      │                 │                                                │
│ lombok               │ 由父 POM 管理     │ 日志/构造器（optional / provided）                │
│ jackson-databind     │ 随 starter-web   │ JSON 序列化（options/知识点等 JSON 列）            │
│ reactor-core         │ 随 Spring AI     │ SSE 流（Flux<String>）                           │
└──────────────────────┴─────────────────┴────────────────────────────────────────────────┘

✗ 未引入：security / jwt / sa-token · validation · redis / cache · 
          logback 配置 · swagger / springdoc · actuator · 任何 XML mapper
```
