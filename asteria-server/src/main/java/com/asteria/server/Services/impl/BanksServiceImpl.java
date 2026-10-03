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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

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

            // ③ 【新增】AI 解析阶段：事务外、后台线程里逐题跑，进度写内存给前端轮询
            if (aiParse) {
                aiEnrich(taskId, outcome.bankId(), aiConfig);
            }

            // ④ 内存状态：成功（内存不属于数据库事务，所以在事务外更新；AI 跑完才到这里）
            importTasks.compute(taskId, (k, v) -> {
                if (v != null) {
                    v.setStatus(ImportStatus.SUCCESS.name());
                    v.setProgress(100);
                    v.setTotalCount(outcome.totalCount());
                    v.setBankId(outcome.bankId());
                }
                return v;
            });

            // ⑤ 【修】数据库也要一起收尾：AI 路径下 saveImport 写的是 AI_PROCESSING，
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
     * <p>单题失败只记账不中断 —— 不能因为第 37 题超时就让前 36 题的解析白做。
     * 失败的题会被标成 FAILED 并记下原因，下次续跑时自然会被重新捞出来。
     */
    private void aiEnrich(String taskId, Long bankId, AiRequestConfig aiConfig) {
        // 只捞"还没解析成功"的题：PENDING（没碰过）+ FAILED（上次失败，重试）
        List<Question> questions = questionMapper.selectList(
                Wrappers.<Question>lambdaQuery()
                        .eq(Question::getBankId, bankId)
                        .ne(Question::getAiStatus, AiStatus.DONE.name()));
        int total = questions.size();
        if (total == 0) {
            log.info("AI 解析：没有需要处理的题，跳过。taskId={}, bankId={}", taskId, bankId);
            return;
        }
        log.info("AI 解析开始：taskId={}, bankId={}, 待处理{}题, 并发度{}", taskId, bankId, total, AI_CONCURRENCY);

        updateTask(taskId, ImportStatus.AI_PROCESSING.name(), 0, total, bankId);

        // done 用原子类：多个线程会同时 +1
        AtomicInteger done = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        // 进度写库要串行，否则多个线程同时 update 同一行会互相覆盖
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
                    } finally {
                        limiter.release();
                    }

                    int finished = done.incrementAndGet();
                    int progress = (int) (finished * 100L / total);
                    synchronized (progressLock) {
                        updateTask(taskId, ImportStatus.AI_PROCESSING.name(), progress, total, bankId);
                        // 每 5 题落一次数据库：内存进度是给前端轮询看的，
                        // 落库是为了服务重启后 DB 里也有个大致进度
                        if (finished % 5 == 0 || finished == total) {
                            bankImportMapper.updateById(BankImport.builder()
                                    .taskId(taskId)
                                    .progress(progress)
                                    .build());
                        }
                    }
                });
            }
        } catch (Exception e) {
            // 走到这里说明线程池本身出了问题（提交任务失败等），题级异常都在上面被吃掉了
            log.error("AI 解析批处理异常：taskId={}", taskId, e);
        }

        int okCount = total - failed.get();
        log.info("AI 解析结束：taskId={}, 成功{}题, 失败{}题（失败的题已标 FAILED，下次可续跑）",
                taskId, okCount, failed.get());
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
            return task;
        }

        // 2) 内存没有 → 查数据库（例如服务重启过）
        BankImport bankImport = bankImportMapper.selectById(taskId);
        if (bankImport != null) {
            return BankResultVO.builder()
                    .taskId(bankImport.getTaskId())
                    .status(bankImport.getStatus())
                    .fileName(bankImport.getFileName())
                    .fileSize(bankImport.getFileSize())
                    .progress(bankImport.getProgress())
                    .totalCount(bankImport.getTotalCount())
                    .bankId(bankImport.getBankId())
                    .errorMessage(bankImport.getErrorMessage())
                    .build();
        }

        // 3) 都没有 → 任务不存在
        return BankResultVO.builder()
                .taskId(taskId)
                .status(ImportStatus.FAILED.name())
                .progress(0)
                .errorMessage("任务不存在")
                .build();
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
