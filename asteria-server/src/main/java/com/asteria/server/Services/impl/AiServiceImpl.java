package com.asteria.server.Services.impl;

import com.asteria.common.exception.BusinessException;
import com.asteria.common.result.ApiResponse;
import com.asteria.pojo.entity.Bank;
import com.asteria.pojo.entity.DTO.AiSummaryDTO;
import com.asteria.pojo.entity.KnowledgeSummary;
import com.asteria.pojo.entity.Question;
import com.asteria.pojo.entity.VO.AiSummaryVo;
import com.asteria.pojo.entity.WrongQuestion;
import com.asteria.pojo.enums.QuestionType;
import com.asteria.server.Services.AiService;
import com.asteria.server.ai.AiChatModelFactory;
import com.asteria.server.ai.AiErrors;
import com.asteria.server.ai.AiJsonRepair;
import com.asteria.server.ai.AiRequestConfig;
import com.asteria.server.ai.AiTestResult;
import com.asteria.server.mapper.BankMapper;
import com.asteria.server.mapper.KnowledgeSummaryMapper;
import com.asteria.server.mapper.QuestionMapper;
import com.asteria.server.mapper.WrongQuestionMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI 能力实现（基于 Spring AI）。
 *
 * <p>安全约定（整个 AI 模块都适用）：
 * <ul>
 *   <li>key / baseUrl / model 都是每个请求从请求头现取的，用完即弃：不落库、不缓存、不常驻内存</li>
 *   <li>日志只打 provider / model / baseUrl，<b>永远不打 key</b></li>
 *   <li>上游异常信息出栈前统一脱敏（压成一行 + 截断 + key 换成 ***）</li>
 * </ul>
 */
@Service
@Slf4j
public class AiServiceImpl implements AiService {

    /** 探测用的提示词，短到不能再短 */
    private static final String PING = "hi";

    /** 一次最多喂给 AI 多少道题：大题库全塞进去会超上下文，又慢又贵 */
    private static final int MAX_SUMMARY_QUESTIONS = 300;

    /** 易错点材料最多带多少道错题：按"错得最多"排序，取前 N 道就够说明问题 */
    private static final int MAX_MISTAKE_QUESTIONS = 100;

    /** 总结要的是"照材料提炼"，不是发挥 → 温度压低（按功能写死，不给用户配） */
    private static final double SUMMARY_TEMPERATURE = 0.3;

    /** 造 client 的活统一交给工厂：测试 / 题目解析 / 聊天 / 总结四处共用 */
    @Autowired
    private AiChatModelFactory chatModelFactory;

    /** 生成知识点总结要读题库和题目 */
    @Autowired
    private BankMapper bankMapper;
    @Autowired
    private QuestionMapper questionMapper;

    /** 总结的读缓存 / 写缓存 */
    @Autowired
    private KnowledgeSummaryMapper summaryMapper;

    /** 易错点不看题库、只看这个题库里的真实错题 */
    @Autowired
    private WrongQuestionMapper wrongQuestionMapper;

    /** 5 个数组要转成 JSON 字符串才能进 json 列（手写字符串会报 Invalid JSON text） */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * AI 直接吐回来的结构：只放 AI 能给的东西。
     * id / bankName / generatedAt 这些我们自己填（让 AI 填它只会瞎编）。
     */
    public record AiSummaryResult(
            List<String> highlights,
            List<String> keyPoints,
            List<String> hotTopics,
            List<String> studySuggestions) {
    }

    /**
     * 易错点的返回结构（另一次 AI 调用的产物）。
     * 易错点不再由主总结"猜"，而是照学生的真实错题总结，所以单独一个 record。
     */
    public record AiMistakeResult(List<String> easyMistakes) {
    }

    @Override
    public AiTestResult testConnection(AiRequestConfig config) {
        // ① 配置不齐就别发请求了
        if (config == null || !config.usable()) {
            return new AiTestResult(false, "请先在「设置」页配置 AI 服务（API Key / 模型 / Base URL）");
        }

        // ② 日志只打 provider / model / baseUrl —— 永远不打 key
        log.info("测试 AI 配置：provider={}, model={}, baseUrl={}",
                config.provider(), config.model(), config.baseUrl());

        try {
            // ③ 现造一套 client（key 不进缓存、不过夜）；温度传 null = 用服务商默认值
            OpenAiChatModel chatModel = chatModelFactory.create(config, null);

            // ④ 同步调用：没抛异常 = 有回信 = key / baseUrl / 模型名全对
            chatModel.call(new Prompt(PING));

            log.info("AI 配置可用：provider={}, model={}", config.provider(), config.model());
            return new AiTestResult(true, "连接正常，模型已响应");

        } catch (Exception e) {
            // ⑤ 失败统一走这里，只回显脱敏后的一行字
            String reason = AiErrors.mask(e, config);
            log.warn("AI 连通性测试失败：{} - {}", e.getClass().getSimpleName(), reason);
            return new AiTestResult(false, "调用失败：" + reason);
        }
    }

    @Override
    public ApiResponse<AiSummaryVo> createSummary(AiSummaryDTO dto, AiRequestConfig aiConfig) {
        // ===== ① 校验：参数 → 题库存在 =====
        if (dto == null || dto.getBankId() == null) {
            throw new BusinessException(40010, "缺少题库 id");
        }
        Bank bank = bankMapper.selectById(dto.getBankId());
        if (bank == null) {
            throw new BusinessException(40401, "题库不存在：" + dto.getBankId());
        }

        // ===== ② 缓存优先：force 不为 true 且已有总结 → 直接返回，一趟 AI 都不调 =====
        //     这一步必须排在"校验 AI 配置"之前：读缓存根本不需要 AI
        boolean force = Boolean.TRUE.equals(dto.getForce());
        if (!force) {
            KnowledgeSummary cached = findCached(dto.getBankId());
            if (cached != null) {
                log.info("命中知识点总结缓存：bankId={}, 生成于={}", dto.getBankId(), cached.getUpdatedAt());
                return ApiResponse.ok(toVo(cached, bank.getName(), true));
            }
        }

        // ===== ③ 到这里才真的要用 AI：校验配置 → 校验题库里有题 =====
        if (aiConfig == null || !aiConfig.usable()) {
            throw new BusinessException(40020, "请先在「设置」页配置 AI 服务（API Key / 模型 / Base URL）");
        }
        // 先数一遍总数：既用来判断"有没有题"，也用来判断"是不是被截断了"
        long totalInBank = questionMapper.selectCount(
                Wrappers.<Question>lambdaQuery().eq(Question::getBankId, dto.getBankId()));
        if (totalInBank == 0) {
            throw new BusinessException(40011, "该题库还没有题目，无法生成总结");
        }

        // ===== ④ 取材料：题型 + 题干 + 答案（最多前 300 道）=====
        // 不取 options：总结用不上选项，几百道题的选项 JSON 白占内存
        // 固定按 id 升序：同一个题库每次喂给 AI 的材料顺序一致，结果才稳定
        List<Question> questions = questionMapper.selectList(
                Wrappers.<Question>lambdaQuery()
                        .select(Question::getId, Question::getType, Question::getStem, Question::getAnswer)
                        .eq(Question::getBankId, dto.getBankId())
                        .orderByAsc(Question::getId)
                        .last("LIMIT " + MAX_SUMMARY_QUESTIONS));
        if (totalInBank > questions.size()) {
            log.warn("题库题目较多（共 {} 题），本次只通读前 {} 题（上限 {}）",
                    totalInBank, questions.size(), MAX_SUMMARY_QUESTIONS);
        }

        log.info("生成知识点总结：bankId={}, bankName={}, 取题数={}, force={}",
                bank.getId(), bank.getName(), questions.size(), dto.getForce());

        // ===== ⑤ 拼材料：题目对象 → 一段文字（AI 只认文字）=====
        String material = buildMaterial(questions);

        // ===== ⑥ 组装并调用 AI：材料 + 规矩 + 格式说明书 =====
        OpenAiChatModel chatModel = chatModelFactory.create(aiConfig, SUMMARY_TEMPERATURE);
        BeanOutputConverter<AiSummaryResult> converter = new BeanOutputConverter<>(AiSummaryResult.class);

        String system = """
                你是一位资深的学科老师，正在为学生做题库的知识点梳理。
                请阅读下面的题目清单，总结这门课的重点。要求：
                1. 用中文，每个数组给 4~6 条，每条是一句短话（不超过 30 字），不要长段落。
                2. highlights（重点摘要）：这门课整体在考什么；
                   keyPoints（核心知识点）：必须掌握的概念和原理；
                   hotTopics（高频考点）：反复出现的题型或知识点；
                   studySuggestions（学习建议）：按什么顺序复习、重点刷哪类题。
                3. 只依据题目清单说话，不要编造清单里没有的内容。
                4. 不要复述题干，不要写"第几题"，要提炼成知识点。
                5. 只输出 JSON，不要任何额外文字，不要 markdown 代码块。
                """;

        String user = "以下是题库里的 " + questions.size() + " 道题：\n\n"
                + material
                + "\n请按上面的要求总结这门课的知识点。\n"
                + converter.getFormat();

        long start = System.currentTimeMillis();
        AiSummaryResult result;
        try {
            ChatResponse response = chatModel.call(new Prompt(List.of(
                    new SystemMessage(system),
                    new UserMessage(user))));
            // 文本 → 对象；模型没按 JSON 回时这里会抛。
            // 先做反斜杠修复：总结里可能出现公式（\frac 之类），裸反斜杠会让 JSON 解析失败
            result = converter.convert(AiJsonRepair.repairBackslashes(
                    response.getResult().getOutput().getText()));
        } catch (Exception e) {
            // key 无效 / 限流 / 超时 / 模型没按格式回 —— 统一翻译成一句人话
            throw new BusinessException(50000, "AI 生成总结失败：" + AiErrors.mask(e, aiConfig));
        }
        if (result == null) {
            throw new BusinessException(50000, "AI 没有返回可用的总结内容");
        }
        log.info("知识点总结生成完成：bankId={}, 取题数={}, 耗时={}ms",
                bank.getId(), questions.size(), System.currentTimeMillis() - start);

        // ===== ⑦ 易错点：不看题库，只看这个题库里的【真实错题】=====
        //    没有待攻克的错题 → 直接存空数组（不调 AI、不花钱）
        List<String> easyMistakes = buildMistakes(dto.getBankId(), aiConfig);

        // ===== ⑧ 写库 + 回读 + 组装 VO =====
        //    存下来，下次同一个题库直接命中缓存（省两次 API 调用，也省 20~30 秒）
        KnowledgeSummary saved = saveSummary(dto.getBankId(), result, easyMistakes);

        return ApiResponse.ok(toVo(saved, bank.getName(), false));
    }

    @Override
    public AiSummaryVo getSummary(Long bankId) {
        if (bankId == null) {
            throw new BusinessException(40010, "缺少题库 id");
        }
        KnowledgeSummary cached = findCached(bankId);
        if (cached == null) {
            return null;                     // 从没生成过 —— 前端按"空态"处理，这不是错误
        }
        // bankName 不落库（DDL 约定：题库改名后总结里的旧名字会对不上），查询时现取
        Bank bank = bankMapper.selectById(bankId);
        return toVo(cached, bank == null ? null : bank.getName(), true);
    }

    // ========== 易错点：错题本 → AI ==========

    /**
     * 易错点：从错题本里读这个题库的错题，让 AI 总结学生的易错规律。
     *
     * <p>和主总结的区别：主总结是"照题库提炼知识点"，这个是"照学生的真实错题找规律" ——
     * 有"错答了什么、错了多少次"做依据，比让 AI 猜"哪些容易错"准得多。
     *
     * @return 没有待攻克的错题时返回空数组（列是 NOT NULL 塞不了 null，前端也靠空数组显示"暂无内容"）
     */
    private List<String> buildMistakes(Long bankId, AiRequestConfig aiConfig) {
        List<WrongQuestion> wrongs = wrongQuestionMapper.selectList(
                Wrappers.<WrongQuestion>lambdaQuery()
                        .eq(WrongQuestion::getBankId, bankId)
                        .eq(WrongQuestion::getResolved, 0)          // 与错题统计同一口径：只算待攻克
                        .orderByDesc(WrongQuestion::getWrongCount)  // 错得多的排前面
                        .last("LIMIT " + MAX_MISTAKE_QUESTIONS));
        if (wrongs.isEmpty()) {
            log.info("题库没有待攻克的错题，易错点存空数组：bankId={}", bankId);
            return List.of();
        }

        // 错题本里只有 question_id，题干/答案得回题目表批量取
        List<Long> questionIds = wrongs.stream().map(WrongQuestion::getQuestionId).toList();
        Map<Long, Question> questionMap = questionMapper.selectList(
                        Wrappers.<Question>lambdaQuery().in(Question::getId, questionIds))
                .stream().collect(Collectors.toMap(Question::getId, q -> q));

        String material = buildMistakeMaterial(wrongs, questionMap);
        OpenAiChatModel chatModel = chatModelFactory.create(aiConfig, SUMMARY_TEMPERATURE);
        BeanOutputConverter<AiMistakeResult> converter = new BeanOutputConverter<>(AiMistakeResult.class);

        String system = """
                你是一位老师，正在分析这位学生的错题，帮他总结「易错点」。要求：
                1. 用中文，4~6 条，每条一句短话（不超过 30 字）。
                2. 要提炼错题的规律和共性，不要复述某一道题。
                   反例：第 1 题考的是 a 标签。　正例：容易把 <link> 与 <a> 标签混用。
                3. 优先讲错得次数多的。
                4. 只依据给出的错题说话，不要编造。
                5. 只输出 JSON，不要任何额外文字，不要 markdown 代码块。
                """;

        String user = "以下是这位学生在这个题库里的错题：\n\n"
                + material
                + "\n请按上面的要求总结他的易错点。\n"
                + converter.getFormat();

        long start = System.currentTimeMillis();
        try {
            ChatResponse response = chatModel.call(new Prompt(List.of(
                    new SystemMessage(system),
                    new UserMessage(user))));
            AiMistakeResult mistakeResult = converter.convert(AiJsonRepair.repairBackslashes(
                    response.getResult().getOutput().getText()));
            List<String> easyMistakes = mistakeResult == null
                    ? List.of()
                    : emptyIfNull(mistakeResult.easyMistakes());
            log.info("易错点生成完成：bankId={}, 错题数={}, 条数={}, 耗时={}ms",
                    bankId, wrongs.size(), easyMistakes.size(), System.currentTimeMillis() - start);
            return easyMistakes;
        } catch (Exception e) {
            // ★ 易错点是"锦上添花"：它失败不该让已经调成功的主总结一起作废
            log.warn("易错点生成失败，按空数组处理：bankId={}, 原因={}", bankId, AiErrors.mask(e, aiConfig));
            return List.of();
        }
    }

    /** 错题材料：一行一道（题型 + 题干 + 正确答案 + 学生答案 + 错次） */
    private String buildMistakeMaterial(List<WrongQuestion> wrongs, Map<Long, Question> questionMap) {
        StringBuilder sb = new StringBuilder();
        int no = 0;
        for (WrongQuestion wrong : wrongs) {
            Question question = questionMap.get(wrong.getQuestionId());
            if (question == null) {
                continue;       // 题目被删了（外键级联一般不会出现，兜一下）
            }
            no++;
            sb.append(no).append(". [").append(chineseType(question.getType())).append("] ")
              .append(question.getStem())
              .append("　正确答案：").append(blankToDash(question.getAnswer()))
              .append("　学生答案：").append(blankToDash(wrong.getUserAnswer()))
              .append("　答错 ").append(wrong.getWrongCount()).append(" 次")
              .append('\n');
        }
        return sb.toString();
    }

    /** 空值显示成「（空）」，免得材料里出现 "正确答案：　学生答案：A" 这种看不懂的行 */
    private String blankToDash(String text) {
        return (text == null || text.isBlank()) ? "（空）" : text;
    }

    // ========== 知识点总结的缓存读写 ==========

    /** 查这个题库的总结（唯一键保证最多一行） */
    private KnowledgeSummary findCached(Long bankId) {
        return summaryMapper.selectOne(
                Wrappers.<KnowledgeSummary>lambdaQuery().eq(KnowledgeSummary::getBankId, bankId));
    }

    /**
     * 写库：一个题库一行 —— 有就更新，没有就插入。
     *
     * <p>写完【回读一次】：id / created_at / updated_at 一律以库里的值为准。
     * 因为 generatedAt 取的是 updated_at（DDL 里定的约定），
     * 不能假设"Java 对象里 set 的时间"就是库里最终存的值。
     */
    private KnowledgeSummary saveSummary(Long bankId, AiSummaryResult result, List<String> easyMistakes) {
        KnowledgeSummary entity = findCached(bankId);
        boolean isNew = (entity == null);
        if (isNew) {
            entity = new KnowledgeSummary();
            entity.setBankId(bankId);
        }
        try {
            // json 列必须由 ObjectMapper 生成合法 JSON，手写字符串会报 Invalid JSON text
            entity.setHighlights(objectMapper.writeValueAsString(emptyIfNull(result.highlights())));
            entity.setKeyPoints(objectMapper.writeValueAsString(emptyIfNull(result.keyPoints())));
            entity.setHotTopics(objectMapper.writeValueAsString(emptyIfNull(result.hotTopics())));
            entity.setEasyMistakes(objectMapper.writeValueAsString(emptyIfNull(easyMistakes)));
            entity.setStudySuggestions(objectMapper.writeValueAsString(emptyIfNull(result.studySuggestions())));
        } catch (JsonProcessingException e) {
            // List<String> 序列化几乎不可能失败；这里兜一下，
            // 免得把受检异常一路抛到 Service 接口上（接口签名不想带 throws）
            throw new BusinessException(50000, "保存知识点总结失败：内容无法序列化");
        }

        if (isNew) {
            summaryMapper.insert(entity);       // 自增 id 回填；created_at/updated_at 由 MetaObjectHandler 填
        } else {
            summaryMapper.updateById(entity);   // 不要先删后插：那样 id 会变、首次生成时间会丢
        }
        return findCached(bankId);
    }

    /** 库里的行 → VO（5 个 JSON 字符串转回 List） */
    private AiSummaryVo toVo(KnowledgeSummary entity, String bankName, boolean fromCache) {
        AiSummaryVo vo = new AiSummaryVo();
        vo.setId(entity.getId());
        vo.setBankId(entity.getBankId());
        vo.setBankName(bankName);
        vo.setHighlights(readList(entity.getHighlights()));
        vo.setKeyPoints(readList(entity.getKeyPoints()));
        vo.setHotTopics(readList(entity.getHotTopics()));
        vo.setEasyMistakes(readList(entity.getEasyMistakes()));
        vo.setStudySuggestions(readList(entity.getStudySuggestions()));
        vo.setGeneratedAt(entity.getUpdatedAt());       // 按 DDL 约定：generatedAt = updated_at
        vo.setFromCache(fromCache);
        return vo;
    }

    /** JSON 数组字符串 → List&lt;String&gt;；脏数据按空数组处理，不让它把接口搞挂 */
    private List<String> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            // 必须用 TypeReference：泛型被擦除后 Jackson 不知道要转成 List<String>
            return objectMapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (JsonProcessingException e) {
            log.warn("知识点总结的 JSON 列解析失败，按空数组处理：{}", e.getMessage());
            return List.of();
        }
    }

    /** 把题目拼成给 AI 看的材料：一行一题（编号 + 题型 + 题干 + 答案） */
    private String buildMaterial(List<Question> questions) {
        StringBuilder sb = new StringBuilder();
        int no = 0;
        for (Question question : questions) {
            no++;
            sb.append(no).append(". [").append(chineseType(question.getType())).append("] ")
              .append(question.getStem());

            String answer = question.getAnswer();
            if (answer == null || answer.isBlank()) {
                // 导入时允许"没答案也入库"，这里必须说清楚，
                // 否则 AI 会当成"答案就是空的"，总结出的易错点会跑偏
                sb.append("（本题未提供答案）");
            } else {
                sb.append("　答案：").append(answer);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 枚举名 → 中文题型名（SINGLE → 单选题）；认不出来就原样返回，不抛异常 */
    private String chineseType(String type) {
        if (type == null) {
            return "未知题型";
        }
        try {
            return QuestionType.valueOf(type).getChineseName();
        } catch (IllegalArgumentException e) {
            return type;
        }
    }

    /** AI 可能漏给某个数组，兜成空数组，免得前端拿到 null 崩掉 */
    private List<String> emptyIfNull(List<String> list) {
        return list == null ? List.of() : list;
    }
}
