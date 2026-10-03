package com.asteria.server.Services.impl;

import com.asteria.common.Tool.FileTextReader;
import com.asteria.common.Tool.QuestionParser;
import com.asteria.common.Tool.RawQuestion;
import com.asteria.common.exception.BusinessException;
import com.asteria.pojo.entity.Bank;
import com.asteria.pojo.entity.BankImport;
import com.asteria.pojo.entity.DTO.BankCountDTO;
import com.asteria.pojo.entity.Question;
import com.asteria.pojo.entity.VO.BankDetailVO;
import com.asteria.pojo.entity.VO.BankResultVO;
import com.asteria.pojo.entity.VO.BanksVO;
import com.asteria.pojo.entity.VO.ChapterVO;
import com.asteria.pojo.entity.VO.PageResultVO;
import com.asteria.pojo.enums.AiStatus;
import com.asteria.pojo.enums.AnswerSource;
import com.asteria.pojo.enums.ImportStatus;
import com.asteria.server.Services.BanksImportTransactional;
import com.asteria.server.Services.BanksService;
import com.asteria.server.ai.AiQuestionExtractor;
import com.asteria.server.ai.AiErrors;
import com.asteria.server.ai.AiRequestConfig;
import com.asteria.server.ai.QuestionAiEnricher;
import com.asteria.server.mapper.BankMapper;
import com.asteria.server.mapper.BanksImportMapper;
import com.asteria.server.mapper.ChapterMapper;
import com.asteria.server.mapper.QuestionMapper;
import com.asteria.server.mapper.WrongQuestionMapper;
import com.asteria.server.parser.QuestionQualityCheck;
import com.asteria.server.tool.DocxTextReader;
import com.asteria.server.tool.PdfTextReader;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
public class BanksServiceImpl implements BanksService {

    /** 与接口文档约定一致：docx / pdf / txt、单文件 ≤ 20MB */
    private static final Set<String> ALLOWED_EXT = Set.of("docx", "pdf", "txt");
    private static final long MAX_SIZE_BYTES = 20L * 1024 * 1024;

    /**
     * AI 解析的并发度：同时最多有几个模型请求在飞。
     *
     * <p>为什么是 6 而不是更大：每个线程处理完要从连接池拿一条数据库连接写回结果。
     * HikariCP 默认池大小是 10，翻译进度、写题目、写任务状态都要占连接 ——
     * 开到 6 既能让模型请求充分并发（瓶颈在网络往返，不在本地），又给数据库留了余量。
     *
     * <p>调大之前先确认：① 服务商允许的并发/限流额度 ② 连接池是否跟着调大了。
     */
    private static final int AI_CONCURRENCY = 6;
    /** 存进 error_message 的摘要长度上限（列宽 500，这里留足余量） */
    private static final int MAX_REASON_LENGTH = 200;
    /** 分页查询单页上限，防止有人传 pageSize=100000 拉全表 */
    private static final int MAX_PAGE_SIZE = 200;

    /**
     * 「账号/服务商级失败」的识别词。
     *
     * <p>为什么用「关键词匹配消息」而不是解析上游错误码：AI 服务商五花八门（OpenAI 兼容网关、
     * 各家云厂商），错误体格式各不相同，真正稳定的信号是消息文本本身。
     * 宁可漏判（漏判只是多跑几道注定失败的题），不可乱判 —— 所以词表只收
     * 「出现就说明整个账号不可用」的词，不收"超时""格式错"这类可能只是单题问题的词。
     *
     * <p>英文词一律小写：比较前会把消息转成小写。中文词不受影响。
     * 401/402/429 是 HTTP 语义里最硬的一组信号：鉴权失效 / 欠费 / 限流。
     */
    private static final List<String> SYSTEMIC_AI_KEYWORDS = List.of(
            // 英文：余额不足 / 配额 / 限流 / 请求过多 / 余额
            "insufficient", "quota", "rate limit", "too many requests", "balance",
            // 中文：国内网关（DeepSeek、通义等）常见的说法
            "余额", "欠费", "限流", "频率",
            // 状态码
            "401", "402", "429");

    /**
     * 沿 {@code getCause()} 链最多看几层。
     *
     * <p>自引用（{@code t.getCause() == t}）当场就能发现，但 A→B→A 这种环只有靠层数上限才停得下来；
     * 异常链本来也不会很深，10 层足够覆盖所有正常的包装（Spring AI 一般包 2~3 层）。
     */
    private static final int MAX_CAUSE_DEPTH = 10;

    /** 任务快照（taskId → 进度）。轮询接口优先读它，重启后回落到数据库 */
    private final ConcurrentHashMap<String, BankResultVO> importTasks = new ConcurrentHashMap<>();

    /** 上传目录可在配置里改，缺省为项目根下 uploads/ */
    @Value("${asteria.upload-dir:uploads}")
    private String uploadDir;

    @Autowired
    private BanksImportMapper bankImportMapper;
    @Autowired
    private FileTextReader fileTextReader;
    @Autowired
    private DocxTextReader docxTextReader;
    @Autowired
    private PdfTextReader pdfTextReader;
    @Autowired
    private BanksImportTransactional importTransactional;
    @Autowired
    private BankMapper bankMapper;
    @Autowired
    private QuestionMapper questionMapper;
    @Autowired
    private ChapterMapper chapterMapper;
    @Autowired
    private WrongQuestionMapper wrongQuestionMapper;

    /** 单题 AI 解析器（内部自己造 ChatModel，这里不用管 key/baseUrl） */
    @Autowired
    private QuestionAiEnricher questionAiEnricher;

    /** 解析结果的输出侧自检：只打标不改数据，用来发现"切题切错了"这类静默问题 */
    @Autowired
    private QuestionQualityCheck questionQualityCheck;

    /** 解析不出题目时的兜底：让 AI 按 JSON schema 把原文抽成结构化题目（内部按题目边界分块 + 逐题校验） */
    @Autowired
    private AiQuestionExtractor aiQuestionExtractor;

    /**
     * 上传入口：校验 → 存盘 → 登记任务 → 启动后台线程 → 立刻返回 taskId。
     *
     * <p>这里【不加】事务注解：只剩一条 insert，自动提交本身就是原子的；
     * 而且必须先提交，后台线程才更新得到这条任务记录。
     */
    @Override
    public BanksVO importBanks(MultipartFile file, String bankName, AiRequestConfig aiConfig) throws IOException {
        // 1) 校验：空文件 / 扩展名 / 大小
        if (file == null || file.isEmpty()) {
            throw new BusinessException(40001, "请选择要上传的文件");
        }
        String originalName = file.getOriginalFilename();
        String ext = extOf(originalName);
        if (!ALLOWED_EXT.contains(ext)) {
            throw new BusinessException(40002, "仅支持 docx/pdf/txt 文件，收到：" + originalName);
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new BusinessException(40003, "文件超过 20MB 上限");
        }

        // 2) taskId：同时充当磁盘存储名（不使用原始文件名，防路径穿越/重名覆盖）
        String taskId = UUID.randomUUID().toString();

        // 3) 存盘
        Path destDir = Paths.get(uploadDir).toAbsolutePath().normalize();
        Path destFile = destDir.resolve(taskId + "." + ext);
        if (bankName == null || bankName.isBlank()) {
            bankName = taskId;                      // 前端没传题库名时兜底
        }
        try {
            Files.createDirectories(destDir);
            file.transferTo(destFile.toFile());
        } catch (IOException e) {
            log.error("文件存盘失败：taskId={}, dest={}", taskId, destFile, e);
            throw new BusinessException(50001, "文件保存失败，请重试");
        }

        // 4) 登记任务（PENDING）
        try {
            bankImportMapper.insert(BankImport.builder()
                    .taskId(taskId)
                    .bankName(bankName)
                    .bankId(null)
                    .fileName(originalName)
                    .fileSize(file.getSize())
                    .status(ImportStatus.PENDING.name())
                    .progress(0)
                    .build());
        } catch (Exception e) {
            log.error("任务登记失败：taskId={}", taskId, e);
            deleteQuietly(destFile);
            throw new BusinessException(50002, "任务登记失败，请重试");
        }

        // 5) 内存快照（前端马上就会来轮询）
        importTasks.put(taskId, BankResultVO.builder()
                .taskId(taskId)
                .status(ImportStatus.PENDING.name())
                .fileName(originalName)
                .fileSize(file.getSize())
                .progress(0)
                .bankId(null)
                .build());

        log.info("导入任务已创建：taskId={}, fileName={}, bankName={}, aiParse={}",
                taskId, originalName, bankName, aiConfig != null);

        // 6) 后台处理（解析 + 入库 [+ AI 解析]），主线程立刻返回
        String finalBankName = bankName;
        new Thread(() -> processImport(taskId, destFile, finalBankName, originalName, ext, aiConfig),
                "import-" + taskId).start();

        BanksVO vo = new BanksVO();
        vo.setTaskId(taskId);
        vo.setStatus(ImportStatus.PENDING.name());
        return vo;
    }

    // ========== 后台异步处理 ==========

    /**
     * 后台线程的三段式：解析（事务外）→ 入库（事务内）→ 更新内存（事务外）。
     * 失败时：内存标 FAILED + 独立事务写数据库 + 删掉磁盘文件。
     */
    private void processImport(String taskId, Path destFile, String bankName, String originalName,
                               String ext, AiRequestConfig aiConfig) {
        try {
            // ① 解析：读文件 + 切块 + 提取（纯计算，不碰数据库，所以放在事务外面）
            // 按文件类型选"取文本"的方式：docx 用 POI、pdf 用 PDFBox、txt 直接按编码读
            // 三种方式抽出的都是"纯文本"，后面切块/判型/归一化的逻辑完全共用
            // PDF 额外留一份体检结果：扫描件会给出一句准确的提示，而不是笼统的"识别不出"
            String content;
            String pdfHint = null;
            if ("pdf".equals(ext)) {
                PdfTextReader.Result pdf = pdfTextReader.readWithDiagnostics(destFile);
                content = pdf.text();
                pdfHint = pdf.scannedHint();
            } else if ("docx".equals(ext)) {
                content = docxTextReader.read(destFile);
            } else {
                content = fileTextReader.read(destFile);
            }

            List<RawQuestion> rawQuestions = new QuestionParser().parse(content);
            log.info("文件解析完成：taskId={}, 共{}条原始题", taskId, rawQuestions.size());

            importTasks.compute(taskId, (k, v) -> {
                if (v != null) {
                    v.setStatus(ImportStatus.PARSING.name());
                }
                return v;
            });

            // ①.5 【兜底】规则解析不出可用题目 → 交给 AI 做【结构化抽取】（不是"改写成标准文本再正则解析"）
            //      这是"救乱格式的文件"，不是默认路径：格式正常的文件永远不会走到这里
            if (!looksUsable(rawQuestions)) {
                // 扫描件没有文字层，AI 也救不了（它拿到的同样是空文本），
                // 直接给准确提示，别白跑一次模型调用
                if (pdfHint != null) {
                    throw new BusinessException(40020, pdfHint);
                }
                if (aiConfig == null) {
                    throw new BusinessException(40020,
                            "文件格式无法自动识别；请先在「设置」页配置 AI 服务后重试，或按「题目格式要求」整理后再上传");
                }
                log.warn("taskId={} 直接解析不出可用题目，改用 AI 结构化抽取", taskId);

                importTasks.compute(taskId, (k, v) -> {
                    if (v != null) {
                        v.setStatus(ImportStatus.AI_FORMATTING.name());
                        v.setProgress(0);
                    }
                    return v;
                });

                rawQuestions = aiQuestionExtractor.extract(content, aiConfig, percent ->
                        importTasks.compute(taskId, (k, v) -> {
                            if (v != null) {
                                v.setProgress(percent);
                            }
                            return v;
                        }));

                log.info("AI 结构化抽取完成：taskId={}, 共{}条题", taskId, rawQuestions.size());
                if (!looksUsable(rawQuestions)) {
                    throw new BusinessException(50001, "AI 没能从文件里抽出题目，请检查文件内容是否真的是题库");
                }
            }

            // ② 入库：题库 + 章节 + 题目 + 任务状态，同一个事务（跨 Bean 调用，事务才生效）
            //    多传一个 aiParse：为 true 时任务状态会停在 AI_PROCESSING 而不是 SUCCESS
            boolean aiParse = aiConfig != null;

            // ②.5 输出侧自检：只打日志、不改数据。
            //      「答案越界」这类信号能证明切题切错了 —— 是发现解析问题的免费探针。
            //      放在入库前，这样日志里的问题能和紧接着的入库统计对上。
            questionQualityCheck.check(rawQuestions);

            BanksImportTransactional.Outcome outcome =
                    importTransactional.saveImport(taskId, bankName, originalName, ext, rawQuestions, aiParse);

            // ③ AI 解析阶段：事务外、后台线程里逐题跑，进度写内存给前端轮询。
            //    aiEnrich 会把「失败几道题 / 有没有账号级问题」带回来 —— 决定任务终态要用它。
            EnrichOutcome aiOutcome = null;
            if (aiParse) {
                aiOutcome = aiEnrich(taskId, outcome.bankId(), aiConfig);
            }

            // ④ 判断终态：账号级问题（余额不足/限流/Key 失效）→ 整批任务失败。
            //    为什么退化成失败：这类问题下每道题都会失败，继续跑没有意义；
            //    用户必须知道"是账号的问题"，否则只会以为系统坏了。
            //    抛异常会被外层 catch 接住 → markFailed 把任务标 FAILED 并把原因写进 error_message。
            //    注意：题库和题目在 saveImport 事务里【已经提交】，回滚不了也不会被回滚 ——
            //    所以已完成解析的题数据不丢，用户照样能刷题（前端失败卡片会说明这一点）。
            if (aiOutcome != null && aiOutcome.abortedBySystemIssue()) {
                // ⚠️ 前端 BankImportView.vue 靠 "AI 解析中断：" 这个前缀区分"真回滚"和"题库已入库"，
                //    改这句文案必须同步改那边的判断（changing either side breaks the other）
                //
                // ⚠️ 为什么「已完成 N 道」要写在原因【前面】：
                //    这条消息最终会经过 briefReason() 截断到 MAX_REASON_LENGTH(200) 字。
                //    而 systemicReason 是服务商原样回显的报文（可能上百字），
                //    如果把它放前面，截断时会把「已完成 N 道 / 还差 M 道」这些**用户真正要的信息**吃掉。
                //    把长文本放最后，它被截掉不影响用户知道"解析到哪了、下一步干什么"。
                int completed = outcome.totalCount() - aiOutcome.failedCount();
                throw new BusinessException(40021,
                        "AI 解析中断：已完成 " + completed + " 道，还有 " + aiOutcome.failedCount()
                                + " 道未解析（已完成的题目可以正常使用）。"
                                + "请检查 AI 账号后重新上传以补齐。原因："
                                + aiOutcome.systemicReason());
            }

            // ⑤ 内存状态：成功（内存不属于数据库事务，所以在事务外更新；AI 跑完才到这里）
            //    失败几道题不算失败：那些题只是"没解析"，用户能用剩下的题，
            //    前端会在成功卡片上补一行"其中 N 道题未生成解析"，所以这里照常标 SUCCESS。
            importTasks.compute(taskId, (k, v) -> {
                if (v != null) {
                    v.setStatus(ImportStatus.SUCCESS.name());
                    v.setProgress(100);
                    v.setTotalCount(outcome.totalCount());
                    v.setBankId(outcome.bankId());
                }
                return v;
            });

            // ⑥ 【修】数据库也要一起收尾：AI 路径下 saveImport 写的是 AI_PROCESSING，
            //    而内存里的最终状态不会自动落库 —— 不补这一笔，服务重启后 Message() 回落到
            //    数据库就会永远显示"AI 解析中"。（非 AI 路径 saveImport 里已经写过 SUCCESS）
            if (aiParse) {
                bankImportMapper.updateById(BankImport.builder()
                        .taskId(taskId)
                        .status(ImportStatus.SUCCESS.name())
                        .progress(100)
                        .build());
            }

            log.info("导入完成：taskId={}, bankId={}, 入库{}题, 跳过{}题",
                    taskId, outcome.bankId(), outcome.totalCount(), outcome.skippedCount());

        } catch (Exception e) {
            log.error("导入失败：taskId={}", taskId, e);
            String reason = briefReason(e);

            // 内存状态：失败
            importTasks.compute(taskId, (k, v) -> {
                if (v != null) {
                    v.setStatus(ImportStatus.FAILED.name());
                    v.setErrorMessage(reason);
                }
                return v;
            });

            // 数据库：用【独立事务】记失败（写在入库事务里会被回滚抹掉）
            try {
                importTransactional.markFailed(taskId, reason);
            } catch (Exception ex) {
                // 连"记失败"都失败了：只能记日志，绝不能再往外抛（否则线程直接死掉）
                log.error("写入失败状态时又出错：taskId={}", taskId, ex);
            }

            // 清理落盘文件（文件系统不受事务保护，得手动删）
            deleteQuietly(destFile);
        }
    }

    /**
     * 这批原始题"有没有救"：至少要有一道题干非空。
     *
     * <p>为什么用题干判断：题干是入库的硬门槛（stem 列 NOT NULL），
     * 而题干只可能从「题目/题干：」标签或题号行里提取出来。一道都没有，
     * 说明这份文件的写法不在这套解析规则覆盖范围内 —— 值得用 AI 兜底整理一次。
     */
    private boolean looksUsable(List<RawQuestion> rawQuestions) {
        return rawQuestions.stream()
                .anyMatch(raw -> raw.getRawStem() != null && !raw.getRawStem().isBlank());
    }

    // ========== AI 解析阶段 ==========

    /**
     * AI 解析这一轮的结果。
     *
     * <p>为什么要把"失败几道题"和"为什么失败"传出去：只有调用方知道任务该怎么收尾 ——
     * 单题失败不影响成功（用户能用剩下的题），账号级失败却必须让整批任务失败并说明原因。
     *
     * @param failedCount    本轮没解析成功的题数（含被账号问题带崩的那些）
     * @param systemicReason 账号/服务商级失败的原因（已脱敏）；null = 没有这类问题
     */
    record EnrichOutcome(int failedCount, String systemicReason) {

        /** 是不是被账号级问题打断的 —— 是的话任务终态是失败，而不是成功 */
        boolean abortedBySystemIssue() {
            return systemicReason != null;
        }
    }

    /**
     * 给刚入库的题补解析（原本缺答案的顺带补答案）。
     *
     * <p><b>相比上一版改了三件事</b>：
     * <ol>
     *   <li><b>并发</b>：以前是一道一道串行调模型 —— 500 题的库要等 500 次往返。
     *       现在用<b>虚拟线程</b>并发跑，信号量控制同时在飞的请求数。
     *       JDK 21 的虚拟线程专为"大量等待 I/O"设计：一次模型调用大部分时间都在等网络，
     *       虚拟线程在等待时几乎不占操作系统线程，所以开几百个也不贵。</li>
     *   <li><b>可续跑</b>：只捞 {@code ai_status} 还没到 DONE 的题。
     *       模型调用是要花钱的，而且<b>不是幂等的</b>（重试就真的再付一次钱），
     *       所以崩了之后必须只重跑没做完的，不能把已经花钱解析过的题再跑一遍。</li>
     *   <li><b>可追溯</b>：每题记下解析状态、失败原因、以及答案是谁写的（文件还是 AI）。</li>
     * </ol>
     *
     * <p><b>为什么并发度不能随便开大</b>：每个线程都要从连接池拿一条数据库连接来写回结果。
     * 并发度超过连接池大小（HikariCP 默认 10）时，超出的线程会排队等连接，
     * 并发就失去意义了。所以这里用 {@value #AI_CONCURRENCY} 留出余量。
     *
     * <p><b>单题失败只记账不中断</b> —— 不能因为第 37 题超时就让前 36 题的解析白做。
     * 失败的题会被标成 FAILED 并记下原因，下次续跑时自然会被重新捞出来。
     *
     * <p><b>但账号级失败（余额不足/限流/Key 失效）要往上报</b>：这类问题下后面每道题都会失败，
     * 继续跑就是白等白花钱。本方法只负责把这件事记下来交给调用方（由 {@code processImport} 决定任务终态），
     * 不在中途强杀已提交的任务 —— 剩余任务本来就排在信号量上，很快各自失败并记账，
     * 真正的止损点是"下一轮不再无脑重跑"（见 {@link #unmarkRetryableFailures}）。
     *
     * <p>包级可见而不是 private：单元测试要直接拿返回值断言"失败几道题 / 有没有账号级问题"，
     * 通过反射去拆 record 只会让测试更脆。
     *
     * @return 本轮失败题数 + 首次出现的账号级失败原因（没有则为 null）
     */
    EnrichOutcome aiEnrich(String taskId, Long bankId, AiRequestConfig aiConfig) {
        // 只捞"还没解析成功"的题：PENDING（没碰过）+ FAILED（上次失败，重试）
        List<Question> questions = questionMapper.selectList(
                Wrappers.<Question>lambdaQuery()
                        .eq(Question::getBankId, bankId)
                        .ne(Question::getAiStatus, AiStatus.DONE.name()));
        int total = questions.size();
        if (total == 0) {
            log.info("AI 解析：没有需要处理的题，跳过。taskId={}, bankId={}", taskId, bankId);
            return new EnrichOutcome(0, null);
        }
        log.info("AI 解析开始：taskId={}, bankId={}, 待处理{}题, 并发度{}", taskId, bankId, total, AI_CONCURRENCY);

        updateTask(taskId, ImportStatus.AI_PROCESSING.name(), 0, total, bankId);

        // done 用原子类：多个线程会同时 +1
        AtomicInteger done = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        // 账号级失败原因：并发的多个线程可能同时判定出来，用 compareAndSet 只留第一条 ——
        // 它们是同一件事，留第一条既省事又能避免被后面某条截断得更含糊的消息覆盖
        AtomicReference<String> systemicReason = new AtomicReference<>();
        // 进度更新的串行点（见下面任务体里的说明）
        Object progressLock = new Object();

        // 虚拟线程执行器：适合"任务多、每个都在等 I/O"的场景。
        // try-with-resources 的 close() 会等所有任务跑完 —— 这里必须等，不然方法返回了题还没处理完。
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // Semaphore 而不是固定大小线程池：JEP 444 明确建议不要用池来限制虚拟线程的并发，
            // 而是用信号量。这样"限制并发"和"用虚拟线程"两件事解耦。
            Semaphore limiter = new Semaphore(AI_CONCURRENCY);

            for (Question question : questions) {
                executor.submit(() -> {
                    try {
                        limiter.acquire();      // 拿不到名额就在这里等，最多 AI_CONCURRENCY 个在飞
                    } catch (InterruptedException ie) {
                        // 被中断（服务正在关闭）：把中断标志还回去，然后放弃这道题。
                        // 它仍然是 PENDING，下次续跑会重新捞到，不会丢。
                        Thread.currentThread().interrupt();
                        log.warn("AI 解析被中断，放弃剩余任务：questionId={}", question.getId());
                        return;
                    }
                    try {
                        enrichOne(question, aiConfig);
                    } catch (Exception e) {
                        failed.incrementAndGet();
                        markEnrichFailed(question, aiConfig, e);
                        // 再算一次脱敏原因：markEnrichFailed 里那份只写进了题目行，
                        // 账号级失败还要把它带到任务级（前端失败卡片直接显示的就是这句）
                        String reason = AiErrors.mask(e, aiConfig);
                        if (isSystemicAiFailure(e)) {
                            systemicReason.compareAndSet(null, reason);
                            log.error("AI 解析遇到账号级问题，本次提前结束：taskId={}, 原因={}", taskId, reason);
                        }
                    } finally {
                        limiter.release();
                    }

                    int finished = done.incrementAndGet();
                    int progress = (int) (finished * 100L / total);
                    // 进度现在【只】写内存快照。以前这里还每 5 题 update 一次 bank_import.progress：
                    // 运行期间没人读库里的这个进度（前端轮询优先读内存），"跑到哪了"随时能从
                    // question.ai_status 现算出来 —— 500 题因此少写 100 次数据库。
                    //
                    // progressLock 保留：updateTask 现在只剩一次 ConcurrentHashMap.compute
                    // （对同一个 key 本身原子），严格说这层锁已可省；留着是为了让
                    // "进度这类共享快照只有一个写入口"在代码里看得见，
                    // 以后往里加逻辑（比如按批刷库）不会忘了串行化 —— 开销可以忽略。
                    synchronized (progressLock) {
                        updateTask(taskId, ImportStatus.AI_PROCESSING.name(), progress, total, bankId);
                    }
                });
            }
        } catch (Exception e) {
            // 走到这里说明线程池本身出了问题（提交任务失败等），题级异常都在上面被吃掉了
            log.error("AI 解析批处理异常：taskId={}", taskId, e);
        }

        // 被账号问题打断：这些题失败的原因是账号，不是题目本身（见方法上的说明）
        if (systemicReason.get() != null) {
            unmarkRetryableFailures(questions);
        }

        int okCount = total - failed.get();
        log.info("AI 解析结束：taskId={}, 成功{}题, 失败{}题（失败的题已标 FAILED，下次可续跑）",
                taskId, okCount, failed.get());
        return new EnrichOutcome(failed.get(), systemicReason.get());
    }

    /**
     * 这个异常是不是账号/服务商级别的问题（不是我某道题的问题）。
     * 命中这些时下一道题也一定会失败，应该立刻停手。
     *
     * <p>沿 cause 链逐层看消息：Spring AI 会把上游报错包一两层，
     * 真正写着 "insufficient balance" 的那句往往在最里层 —— 只看最外层的 message 会漏判。
     *
     * <p>包级可见而不是 private：单元测试要直接断言这些关键词的判定结果。
     */
    boolean isSystemicAiFailure(Throwable e) {
        Throwable t = e;
        for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; depth++) {
            String msg = t.getMessage();
            if (msg != null && !msg.isBlank()) {
                String lower = msg.toLowerCase(Locale.ROOT);
                for (String keyword : SYSTEMIC_AI_KEYWORDS) {
                    if (lower.contains(keyword)) {
                        return true;
                    }
                }
            }
            Throwable cause = t.getCause();
            // 自引用（有些包装异常会把自己设成自己的 cause）会让循环停不下来
            if (cause == t) {
                break;
            }
            t = cause;
        }
        return false;
    }

    /**
     * 把因账号问题而失败的题改回「未解析」。
     *
     * <p>为什么必须改回去：它们是<b>可重试</b>的，不是坏题。留着 FAILED 有两层害处 ——
     * 用户在题库详情里看到"解析失败"会以为题目本身有问题，
     * 而后续看到 ai_status 的人也读不出"其实只是账号欠费"这个真实原因。
     *
     * <p>只动"当前还是 FAILED"的题：条件里带 {@code ai_status = FAILED} 是防并发踩踏 ——
     * 万一这期间某道题被别的路径改写成了 DONE，不能被我们倒退回 PENDING。
     *
     * <p>整段吞异常：这是善后动作，它失败不该把"任务为什么失败"这条主流程信息也弄丢。
     */
    void unmarkRetryableFailures(List<Question> questions) {
        List<Long> ids = questions.stream()
                .map(Question::getId)
                .filter(id -> id != null)
                .toList();
        if (ids.isEmpty()) {
            return;
        }
        try {
            questionMapper.update(null, Wrappers.<Question>lambdaUpdate()
                    .in(Question::getId, ids)
                    .eq(Question::getAiStatus, AiStatus.FAILED.name())
                    .set(Question::getAiStatus, AiStatus.PENDING.name())
                    .set(Question::getAiError, null));
            log.info("账号级 AI 失败：{} 道题已从 FAILED 改回 PENDING（可重试，不是坏题）", ids.size());
        } catch (Exception e) {
            log.warn("把账号级失败的题改回 PENDING 时出错（只是善后，不影响主流程）：题数={}", ids.size(), e);
        }
    }

    /**
     * 处理一道题：调模型 → 写回解析（缺答案时连答案一起写）。
     *
     * <p>成功和失败的落库都只有**一次 update**，状态和内容一起写 —— 避免"内容写了但状态没写"
     * 造成下一次续跑重复处理（重复处理就等于重复花钱）。
     */
    private void enrichOne(Question question, AiRequestConfig aiConfig) {
        QuestionAiEnricher.EnrichResult result = questionAiEnricher.enrich(question, aiConfig);

        Question patch = new Question();
        patch.setId(question.getId());
        patch.setAnalysis(result.analysis());
        patch.setAiStatus(AiStatus.DONE.name());
        patch.setAiError(null);                     // 之前失败过的，这次成功了要把错误清掉
        patch.setAiEnrichedAt(LocalDateTime.now());
        if (result.answerFromAi()) {
            // 只有"原本缺答案、这次由模型补出来"才动 answer，并且标明来源是 AI。
            // 原本就有答案的题绝不覆盖 —— 文件里的答案比模型的可信。
            patch.setAnswer(result.answer());
            patch.setAnswerSource(AnswerSource.AI.name());
        }
        questionMapper.updateById(patch);
    }

    /**
     * 记录一道题的失败：状态、脱敏后的原因、重试次数。
     *
     * <p>必须<b>独立于上面的写回</b>：一次模型调用失败不能把题目已有的解析也抹掉，
     * 所以这里只更新状态相关的列。
     *
     * <p>失败原因要脱敏 —— 上游报错可能把请求内容回显出来，里面可能有 API Key。
     */
    private void markEnrichFailed(Question question, AiRequestConfig aiConfig, Exception e) {
        String reason = AiErrors.mask(e, aiConfig);
        log.warn("AI 解析失败：questionId={}, 原因={}", question.getId(), reason);
        try {
            Question patch = new Question();
            patch.setId(question.getId());
            patch.setAiStatus(AiStatus.FAILED.name());
            patch.setAiError(reason);
            // 重试次数 +1：用来识别"一直失败"的题，避免续跑时无限重试烧钱
            patch.setAiRetryCount((question.getAiRetryCount() == null ? 0 : question.getAiRetryCount()) + 1);
            questionMapper.updateById(patch);
        } catch (Exception ex) {
            // 连失败状态都写不进去（数据库也挂了？）：只记日志，不让它把整个批处理带崩
            log.error("写入 AI 失败状态时又出错：questionId={}", question.getId(), ex);
        }
    }

    /** 更新内存里的任务快照（前端轮询读的就是它） */
    private void updateTask(String taskId, String status, int progress, int total, Long bankId) {
        importTasks.compute(taskId, (k, v) -> {
            if (v != null) {
                v.setStatus(status);
                v.setProgress(progress);
                v.setTotalCount(total);
                v.setBankId(bankId);
            }
            return v;
        });
    }

    // ========== 查询接口 ==========

    @Override
    public BankResultVO Message(String taskId) {
        // 1) 先查内存（有实时进度，最新）
        BankResultVO task = importTasks.get(taskId);
        if (task != null) {
            // 内存快照里没有题级计数（它是可变的任务态，不是题的真相源），现算再返回。
            // 每次都算：前端 1.5s 轮询一次，而这三次 COUNT 走 bank_id 索引，比缓存一份计数
            // 再想办法让它和 question.ai_status 保持一致便宜得多。
            fillAiCounts(task, task.getBankId());
            return task;
        }

        // 2) 内存没有 → 查数据库（例如服务重启过）
        BankImport bankImport = bankImportMapper.selectById(taskId);
        if (bankImport != null) {
            BankResultVO vo = BankResultVO.builder()
                    .taskId(bankImport.getTaskId())
                    .status(bankImport.getStatus())
                    .fileName(bankImport.getFileName())
                    .fileSize(bankImport.getFileSize())
                    .progress(bankImport.getProgress())
                    .totalCount(bankImport.getTotalCount())
                    .bankId(bankImport.getBankId())
                    .errorMessage(bankImport.getErrorMessage())
                    .build();
            fillAiCounts(vo, vo.getBankId());
            return vo;
        }

        // 3) 都没有 → 任务不存在
        return BankResultVO.builder()
                .taskId(taskId)
                .status(ImportStatus.FAILED.name())
                .progress(0)
                .errorMessage("任务不存在")
                .build();
    }

    /**
     * 把「AI 解析完成了多少 / 还差多少 / 失败多少」填进任务 VO（实时从 question 表现算）。
     *
     * <p>为什么不落库：{@code question.ai_status} 是每题成败的唯一真相源，任务行上再存一份计数
     * 就有了第二个真相源，写时机稍微对不上（比如失败后重试成功）界面就会说错话，而且没法自查。
     *
     * <p>为什么整段吞异常：这只是给界面补信息的附属查询，查不出来最多是前端不显示那行提示，
     * 绝不能让"查任务进度"这个主接口跟着挂掉。
     */
    private void fillAiCounts(BankResultVO vo, Long bankId) {
        if (bankId == null) {
            // 还没入库/已失败的任务没有题可统计，保持三个字段为 null（前端据此不显示提示）
            return;
        }
        try {
            Long done = questionMapper.selectCount(Wrappers.<Question>lambdaQuery()
                    .eq(Question::getBankId, bankId)
                    .eq(Question::getAiStatus, AiStatus.DONE.name()));
            Long failed = questionMapper.selectCount(Wrappers.<Question>lambdaQuery()
                    .eq(Question::getBankId, bankId)
                    .eq(Question::getAiStatus, AiStatus.FAILED.name()));
            Long all = questionMapper.selectCount(Wrappers.<Question>lambdaQuery()
                    .eq(Question::getBankId, bankId));

            vo.setAiDoneCount(done.intValue());
            vo.setAiFailedCount(failed.intValue());
            // 待解析 = 不是 DONE 也不是 FAILED 的（主要是 PENDING）；
            // 用减法而不是再按 PENDING 查一次，是为了让三个数加起来必定等于总题数
            vo.setAiPendingCount(Math.max(0, all.intValue() - done.intValue() - failed.intValue()));
        } catch (Exception e) {
            log.warn("统计 AI 解析计数失败（不影响任务状态返回）：bankId={}", bankId, e);
        }
    }

    // ========== 题库分页查询 ==========

    @Override
    public PageResultVO<BanksVO> pageQuery(String keyword, Integer page, Integer pageSize) {
        // ===== 参数兜底 =====
        long current = (page == null || page < 1) ? 1 : page;
        long size = (pageSize == null || pageSize < 1) ? 100 : Math.min(pageSize, MAX_PAGE_SIZE);
        boolean hasKeyword = keyword != null && !keyword.isBlank();

        // ===== 第 1 步：分页查题库（只查当前页）=====
        LambdaQueryWrapper<Bank> wrapper = Wrappers.<Bank>lambdaQuery()
                .and(hasKeyword, w -> w.like(Bank::getName, keyword).or().like(Bank::getFileName, keyword))
                .orderByDesc(Bank::getCreatedAt);           // 新导入的排前面

        Page<Bank> pageParam = Page.of(current, size);//创建分页参数
        bankMapper.selectPage(pageParam, wrapper);          // MP 自动执行 COUNT + LIMIT
        List<Bank> banks = pageParam.getRecords();

        if (banks.isEmpty()) {
            return new PageResultVO<>(List.of(), pageParam.getTotal(), current, size);
        }

        // ===== 第 2 步：统计这一页题库的题目数（按题型）和章节数 =====
        List<Long> bankIds = banks.stream().map(Bank::getId).toList();

        // 2.1 题目数：一次查完这一页所有题库 → bankId → (题型 → 数量)
        Map<Long, Map<String, Integer>> typeCountMap = new HashMap<>();
        for (BankCountDTO row : questionMapper.countGroupByBankAndType(bankIds)) {
            Long bankId = row.getBankId();

            // 先看这个题库有没有内层 Map，没有就建一个放进外层
            Map<String, Integer> typeCounts = typeCountMap.get(bankId);
            if (typeCounts == null) {
                typeCounts = new HashMap<>();
                typeCountMap.put(bankId, typeCounts);
            }

            // 再往里放「题型 → 数量」
            typeCounts.put(row.getType(), row.getCnt());
        }

        // 2.2 章节数：一次查完 → bankId → 章节数
        Map<Long, Integer> chapterCountMap = new HashMap<>();
        for (BankCountDTO row : chapterMapper.countGroupByBank(bankIds)) {
            chapterCountMap.put(row.getBankId(), row.getCnt());
        }

        // ===== 第 3 步：组装 VO 列表 =====
        List<BanksVO> list = new ArrayList<>(banks.size());
        for (Bank bank : banks) {
            list.add(toBankVO(bank,
                    typeCountMap.getOrDefault(bank.getId(), Map.of()),
                    chapterCountMap.getOrDefault(bank.getId(), 0)));
        }

        // ===== 第 4 步：返回 list + 总记录数 + 页码 + 每页记录数 =====
        return new PageResultVO<>(list, pageParam.getTotal(), current, size);
    }

    // ========== 题库详情 ==========

    @Override
    public BankDetailVO getBankDetail(Long id) {
        // 1) 题库本身：不存在直接返回 null，由 Controller 翻译成 40401
        Bank bank = bankMapper.selectById(id);
        if (bank == null) {
            return null;
        }

        // 2) 题型统计：复用分页那边的 Mapper 方法（只传一个 id）
        Map<String, Integer> typeCounts = new HashMap<>();
        for (BankCountDTO row : questionMapper.countGroupByBankAndType(List.of(id))) {
            typeCounts.put(row.getType(), row.getCnt());
        }

        // 3) 章节 + 每章题目数（一条 JOIN 查询搞定，已按 sort 排好序）
        List<ChapterVO> chapters = chapterMapper.selectChaptersWithCount(id);

        // 4) 待攻克错题数：错题本 wrong_question 自带 bank_id，单表 COUNT 直接查出
        int wrongCount = wrongQuestionMapper.countByBank(id);

        // 5) 组装：公共字段交给 fillBankFields，再补详情特有的两个
        BankDetailVO detail = new BankDetailVO();
        fillBankFields(detail, bank, typeCounts, chapters.size());
        detail.setChapters(chapters);
        detail.setWrongCount(wrongCount);
        return detail;
    }

    @Override
    public void deleteBank(Long id) {
        // 1) 先找出这个题库对应的导入任务，用来定位磁盘上的源文件
        //    （文件存成 uploads/{taskId}.{ext}，所以要拿 taskId 和原始文件名里的扩展名）
        List<BankImport> tasks = bankImportMapper.selectList(
                Wrappers.<BankImport>lambdaQuery().eq(BankImport::getBankId, id));

        // 2) 删题库：chapter / question 由外键 ON DELETE CASCADE 自动清理
        bankMapper.deleteById(id);

        // 3) 再删磁盘上的源文件
        //    顺序讲究：先保证数据库删掉（业务正确），再删文件；
        //    文件系统不受事务保护，删失败只记日志，不影响接口返回
        Path dir = Paths.get(uploadDir).toAbsolutePath().normalize();
        for (BankImport task : tasks) {
            String storedName = task.getTaskId() + "." + extOf(task.getFileName());
            deleteQuietly(dir.resolve(storedName));
        }
    }

    // ========== 组装辅助 ==========

    /** 列表用：题库实体 + 统计结果 → 列表项 VO */
    private BanksVO toBankVO(Bank bank, Map<String, Integer> typeCounts, int chapterCount) {
        BanksVO vo = new BanksVO();
        fillBankFields(vo, bank, typeCounts, chapterCount);
        return vo;
    }

    /**
     * 填充"列表项"和"详情"共用的字段。
     * 参数类型是父类 BanksVO —— 子类 BankDetailVO 也能直接传进来，所以两边共用这一份逻辑。
     */
    private void fillBankFields(BanksVO vo, Bank bank, Map<String, Integer> typeCounts, int chapterCount) {
        vo.setId(bank.getId());
        vo.setName(bank.getName());
        vo.setFileName(bank.getFileName());
        vo.setFileType(bank.getFileType());
        vo.setCreatedAt(bank.getCreatedAt());
        vo.setChapterCount(chapterCount);

        // 各题型数量：直接按题型 key 取值，这个题库没有该题型就是 0
        // （这些字符串就是 QuestionType 的枚举名）
        vo.setSingleCount(typeCounts.getOrDefault("SINGLE", 0));
        vo.setMultipleCount(typeCounts.getOrDefault("MULTIPLE", 0));
        vo.setTrueFalseCount(typeCounts.getOrDefault("TRUE_FALSE", 0));
        vo.setEssayCount(typeCounts.getOrDefault("ESSAY", 0));
        vo.setFillBlankCount(typeCounts.getOrDefault("FILL_BLANK", 0));

        // 题目总数：Map 里没有"总数"这一项，把所有题型的数量加起来
        // （认不出的题型也会被算进来，总数不会漏）
        int questionCount = typeCounts.values().stream()
                .mapToInt(cnt -> cnt == null ? 0 : cnt)
                .sum();
        vo.setQuestionCount(questionCount);
    }

    // ========== 小工具 ==========

    /**
     * 把异常压成一句人话（≤200 字）。
     * 完整堆栈只进日志；数据库/前端只需要一个简短、可读的原因
     * （也避免把 SQL 报错和表结构泄露出去）。
     */
    private String briefReason(Throwable e) {
        String raw = e.getMessage();
        if (raw == null || raw.isBlank()) {
            raw = e.getClass().getSimpleName();
        }
        // 取第一行【非空】内容：异常信息里常带前导换行（如 SQL 异常的 "### Error updating database..." 之前），
        // 直接取 lines().findFirst() 会拿到空串，前端就变成"失败了但没有原因"。
        String firstLine = raw.lines().map(String::trim).filter(s -> !s.isEmpty()).findFirst().orElse(raw).trim();
        return firstLine.length() > MAX_REASON_LENGTH ? firstLine.substring(0, MAX_REASON_LENGTH) : firstLine;
    }

    /** 删文件失败不影响主流程，所以吞掉异常只记日志 */
    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("清理落盘文件失败：{}", file, e);
        }
    }

    /** 取小写扩展名；没有扩展名返回空串 */
    private String extOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
    }
}
