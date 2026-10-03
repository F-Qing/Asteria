package com.asteria.server.ai;

import com.asteria.common.Tool.QuestionOption;
import com.asteria.common.Tool.QuestionParser;
import com.asteria.common.Tool.RawQuestion;
import com.asteria.common.exception.BusinessException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * 用 AI 把「规则解析吃不下的题库原文」抽成**结构化题目**（兜底路径）。
 *
 * <p>和上一版的根本区别：不再让模型输出"标准格式的文本、再拿正则去解析"，而是让它按
 * {@code response_format=json_object} 直接输出 JSON 数组，程序反序列化 + 逐题校验后直接用。
 *
 * <p>为什么这样更稳（都是上一版踩过的坑）：
 * <ul>
 *   <li><b>格式由协议保证</b>：文本形态下，模型漏写「题目：」标签、把选项并进题干、包一层
 *       markdown 代码块，都会让正则解析出残题；JSON 里字段缺了就是缺了，程序一眼看得出来。</li>
 *   <li><b>不再要求"逐字复述整篇"</b>：抽取比改写容易得多，模型不用默写一万字再插图标签。</li>
 *   <li><b>每道题单独校验</b>：题干空 → 丢弃；选项字母非法/重复 → 丢弃该选项；
 *       答案字母不在选项里 → 丢弃这个答案（宁可没答案，也不要错答案）。</li>
 *   <li><b>编号不用它管</b>：不再让模型排「第 N 题」——分块之后它根本看不到全局，
 *       排出来的编号一定是错的。题目顺序由块顺序决定。</li>
 * </ul>
 *
 * <p>分块切在【题目边界】上（{@link QuestionParser#QUESTION_HEADER} / 空行），
 * 不切在题目中间，避免前后两块各拿到半道题。
 *
 * <p>失败策略：每块最多试 {@value #MAX_ATTEMPTS_PER_CHUNK} 次，仍失败就整批失败并报出
 * 「原文第 X~Y 行」——不静默跳过（那是数据缺失），也不把半成品塞进库（那是数据污染）。
 */
@Component
@Slf4j
public class AiQuestionExtractor {

    /** 每块目标字数：真正的切点是[题目开头 / 空行]，这个数字只是"攒够了就准备切" */
    private static final int CHUNK_CHARS = 2000;

    /** 硬上限：整篇没有可识别的题号行时（正是最需要 AI 的情况）强制切开，防止块无限大 */
    private static final int CHUNK_HARD_LIMIT = CHUNK_CHARS * 2;

    /** 抽取只允许"照抄 + 归类"，不允许发挥 → 温度压到最低 */
    private static final double TEMPERATURE = 0.1;

    /** 每块最多尝试次数（第一次失败多半是模型偶发抽风，重试一次通常就好了） */
    private static final int MAX_ATTEMPTS_PER_CHUNK = 2;

    /** 答案里认得的字母范围（选项一般不会超过 A~H） */
    private static final String LETTERS = "ABCDEFGH";

    private static final String SYSTEM_PROMPT = """
            你的任务：把下面这段题库原文里的题目，抽取成 JSON。只抽结构，不要改写文字。

            【必须遵守】
            1. 题干、选项、答案一律**照抄原文**：不要改写、不要精简、不要翻译、不要补充。
            2. 原文没给答案的，answer 写空字符串 ""。**绝对不要自己推断或编造答案。**
            3. 原文没有的信息不要编：没有章节就 chapter 写 ""；判断不出题型就 type 写 ""。
            4. 只处理给你的这段文字。开头或结尾如果是被切断的半道题（只剩选项、只剩几行代码、
               只有半句话），直接丢掉，不要把它当成一道题，也不要给它补题干。
            5. type 只能是这五个之一：单选题、多选题、判断题、填空题、简答题。
            6. 没有选项的题（判断/填空/简答，或原文本来就没列选项），options 写 []。
               判断题如果原文给了「正确/错误」这类选项才写，否则写 []。
            7. **不要输出题号、不要重新编号**，题目按原文先后顺序排列即可。
            8. 原文里有公式（LaTeX，如 \\frac、\\alpha、\\times）时，**反斜杠必须写成两个**：
               写 "\\\\frac{1}{2}" 而不是 "\\frac{1}{2}"。这是 JSON 的转义要求，
               少写一个反斜杠会让公式丢失、甚至让整段 JSON 解析失败。
            9. 只输出 JSON 本身：不要解释文字，不要 markdown 代码块。

            【输出格式】
            {"questions":[{"chapter":"","type":"单选题","stem":"题干原文","options":[{"key":"A","content":"选项原文"}],"answer":"A"}]}
            """;

    @Autowired
    private AiChatModelFactory chatModelFactory;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 把整份原文抽成题目。
     *
     * @param rawText    文件抽出来的纯文本
     * @param config     用户的 AI 配置（key / baseUrl / model）
     * @param onProgress 进度回调（0~100），可为 null
     * @throws BusinessException 某一块重试后仍然失败（整批失败，消息里带出行号范围）
     */
    public List<RawQuestion> extract(String rawText, AiRequestConfig config, IntConsumer onProgress) {
        List<Chunk> chunks = splitIntoChunks(rawText);
        log.info("AI 结构化抽取开始：原文 {} 字，切成 {} 块", rawText.length(), chunks.size());

        List<RawQuestion> all = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            all.addAll(extractChunk(chunks.get(i), config));
            if (onProgress != null) {
                onProgress.accept((i + 1) * 100 / chunks.size());
            }
            log.info("AI 结构化抽取进度：{}/{} 块，累计 {} 题", i + 1, chunks.size(), all.size());
        }
        log.info("AI 结构化抽取完成：共 {} 题", all.size());
        return all;
    }

    // ========== 单块 ==========

    /** 抽一块；失败重试，仍失败则整批失败（带行号，方便用户回去看原文） */
    private List<RawQuestion> extractChunk(Chunk chunk, AiRequestConfig config) {
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS_PER_CHUNK; attempt++) {
            try {
                return callOnce(chunk, config);
            } catch (Exception e) {
                last = e;
                log.warn("AI 抽取第 {}~{} 行失败（第 {}/{} 次）：{}",
                        chunk.startLine(), chunk.endLine(), attempt, MAX_ATTEMPTS_PER_CHUNK,
                        AiErrors.mask(e, config));
            }
        }
        throw new BusinessException(50001, "AI 抽取题目失败（原文第 " + chunk.startLine() + "~"
                + chunk.endLine() + " 行）：" + AiErrors.mask(last, config));
    }

    /** 调一次模型 → 解析 JSON → 逐题校验 */
    private List<RawQuestion> callOnce(Chunk chunk, AiRequestConfig config) throws Exception {
        // 导入专用：JSON 输出 + DeepSeek 上关掉思考（机械活，思考只会更慢更贵还让 temperature 失效）
        OpenAiChatModel chatModel = chatModelFactory.createForImport(config, TEMPERATURE);
        ChatResponse response = chatModel.call(new Prompt(List.of(
                new SystemMessage(SYSTEM_PROMPT),
                new UserMessage("题库原文：\n" + chunk.text()))));

        String text = response.getResult().getOutput().getText();
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("AI 返回了空内容");
        }

        // 被服务商截断：JSON 一定是残缺的，别浪费一次反序列化
        var metadata = response.getResult().getMetadata();
        String finishReason = metadata == null ? null : metadata.getFinishReason();
        if (finishReason != null && "length".equalsIgnoreCase(finishReason)) {
            throw new IllegalStateException("AI 输出被截断（finish_reason=length，本块原文 "
                    + chunk.text().length() + " 字）");
        }

        List<AiQuestion> raw = parseQuestions(text);

        Parsed parsed = toQuestions(raw, chunk.startLine());
        List<RawQuestion> questions = parsed.questions();

        // 原文里明明有题号，却一道都没抽出来 → 当作失败（让外层重试/报错），别让"空结果"冒充成功
        if (questions.isEmpty() && hasQuestionHeader(chunk.text())) {
            throw new IllegalStateException("这一块原文里有题号，但 AI 没抽出任何题目（返回 "
                    + text.length() + " 字）：" + brief(text));
        }

        log.info("AI 抽出一块（第 {}~{} 行）：原文 {} 字 → {} 题；丢弃 无题干 {} / 非法选项 {} / 无效答案 {}；finish={}",
                chunk.startLine(), chunk.endLine(), chunk.text().length(), questions.size(),
                parsed.noStem(), parsed.badOption(), parsed.badAnswer(), finishReason);
        return questions;
    }

    /**
     * 模型返回的 JSON 文本 → 题目列表（含逐题校验）。抽出来单独一个方法是为了能单测：
     * 这里不碰网络，纯"文本进、题目出"。
     */
    Parsed parseAndValidate(String modelOutput, int lineHint) throws Exception {
        return toQuestions(parseQuestions(modelOutput), lineHint);
    }

    /** 逐题校验 + 组装；计数用于日志 */
    private Parsed toQuestions(List<AiQuestion> raw, int lineHint) {
        Stats stats = new Stats();
        List<RawQuestion> questions = new ArrayList<>();
        for (AiQuestion q : raw) {
            RawQuestion rq = toRawQuestion(q, lineHint, stats);
            if (rq != null) {
                questions.add(rq);
            }
        }
        return new Parsed(questions, stats.noStem, stats.badOption, stats.badAnswer);
    }

    /** 一次抽取的结果：留下来的题 + 三类丢弃计数（只用于日志/测试断言） */
    record Parsed(List<RawQuestion> questions, int noStem, int badOption, int badAnswer) {
    }

    // ========== JSON 解析 ==========

    /**
     * 把模型返回的 JSON 解成题目列表。
     *
     * <p>不去假设字段名：模型把数组放在 {@code questions} / {@code data} / {@code items} 里都行，
     * 只要根是数组、或根对象里有且仅有一个数组字段，就取它。返回里混着多余文字也容忍。
     */
    private List<AiQuestion> parseQuestions(String text) throws Exception {
        String json = stripToJsonAndRepair(text);
        JsonNode root = objectMapper.readTree(json);

        JsonNode array = null;
        if (root.isArray()) {
            array = root;
        } else if (root.isObject()) {
            for (JsonNode child : root) {
                if (child.isArray()) {
                    array = child;
                    break;
                }
            }
        }
        if (array == null) {
            throw new IllegalStateException("AI 返回的 JSON 里没有题目数组：" + brief(json));
        }
        return objectMapper.convertValue(array, new TypeReference<List<AiQuestion>>() {
        });
    }

    /** 去掉 markdown 代码块围栏和 JSON 之前的说明文字 */
    private String stripToJson(String text) {
        String t = text.trim();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            if (firstNewline > 0) {
                t = t.substring(firstNewline + 1);
            }
            int lastFence = t.lastIndexOf("```");
            if (lastFence >= 0) {
                t = t.substring(0, lastFence);
            }
            t = t.trim();
        }
        int start = -1;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '{' || c == '[') {
                start = i;
                break;
            }
        }
        return start > 0 ? t.substring(start).trim() : t;
    }

    /**
     * {@link #stripToJson} 之后再补一步反斜杠修复。
     *
     * <p>数学题的题干里会有 {@code \frac} 这类 LaTeX，而 JSON 把反斜杠当转义符：
     * {@code \f} 是合法转义（换页符）→ 静默解析成"换页符 + rac"，题干被悄悄改坏；
     * {@code \alpha} 不是合法转义 → 直接抛异常，整块抽取失败。
     * 修复细节见 {@link AiJsonRepair}。
     */
    private String stripToJsonAndRepair(String text) {
        return AiJsonRepair.repairBackslashes(stripToJson(text));
    }

    // ========== 逐题校验 ==========

    /**
     * AI 返回的一道题 → {@link RawQuestion}；不合格返回 null（丢弃）。
     *
     * <p>这里就是"不塞垃圾"的闸门：题干空的题、选项字母非法的题直接不要；
     * 答案字母不在选项里的，只丢答案、保留题目（宁可没答案，也不要错答案）。
     */
    private RawQuestion toRawQuestion(AiQuestion q, int lineHint, Stats stats) {
        String stem = trimToNull(q.stem());
        if (stem == null) {
            stats.noStem++;
            return null;
        }

        List<QuestionOption> options = normalizeOptions(q.options(), stats);

        String answer = trimToNull(q.answer());
        if (answer != null && !options.isEmpty() && looksLikeLetters(answer)) {
            Set<String> keys = new HashSet<>();
            for (QuestionOption option : options) {
                keys.add(option.getKey());
            }
            boolean allInOptions = lettersOf(answer).stream().allMatch(keys::contains);
            if (!allInOptions) {
                stats.badAnswer++;
                answer = null;
            }
        }

        return new RawQuestion(trimToNull(q.type()), stem, options, answer,
                trimToNull(q.chapter()), lineHint, List.of());
    }

    /** 选项规范化：字母转半角大写、内容空的丢掉、字母重复的丢掉 */
    private List<QuestionOption> normalizeOptions(List<AiOption> raw, Stats stats) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<QuestionOption> options = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (AiOption option : raw) {
            if (option == null) {
                continue;
            }
            String key = normalizeKey(option.key());
            String content = trimToNull(option.content());
            if (key == null || content == null || !seen.add(key)) {
                stats.badOption++;
                continue;
            }
            options.add(new QuestionOption(key, content));
        }
        return options;
    }

    /** 选项字母：全角转半角、转大写；不是单个 A~Z 就返回 null */
    private String normalizeKey(String key) {
        String k = trimToNull(key);
        if (k == null) {
            return null;
        }
        k = k.toUpperCase();
        if (k.length() == 1) {
            char c = k.charAt(0);
            if (c >= 'Ａ' && c <= 'Ｚ') {
                k = String.valueOf((char) (c - 'Ａ' + 'A'));
            }
        }
        if (k.length() != 1) {
            return null;
        }
        char c = k.charAt(0);
        return (c >= 'A' && c <= 'Z') ? k : null;
    }

    /** 这串答案看起来是不是"选项字母"（如 A / AC / A、C），而不是一段文字 */
    private boolean looksLikeLetters(String answer) {
        if (answer.length() > 20) {
            return false;
        }
        for (char c : answer.toCharArray()) {
            char up = Character.toUpperCase(c);
            boolean ok = LETTERS.indexOf(up) >= 0
                    || c == '、' || c == ',' || c == '，' || c == ';' || c == '；'
                    || c == '/' || c == ' ' || c == '\t';
            if (!ok) {
                return false;
            }
        }
        return !lettersOf(answer).isEmpty();
    }

    /** 取出答案里的选项字母（去重、保序） */
    private List<String> lettersOf(String text) {
        List<String> letters = new ArrayList<>();
        for (char c : text.toCharArray()) {
            char up = Character.toUpperCase(c);
            String s = String.valueOf(up);
            if (LETTERS.contains(s) && !letters.contains(s)) {
                letters.add(s);
            }
        }
        return letters;
    }

    // ========== 分块 ==========

    /** 一块原文 + 它在原文里的行号范围（报错和日志用） */
    private record Chunk(String text, int startLine, int endLine) {
    }

    /**
     * 按【题目边界】切块，而不是单纯按字数切。
     *
     * <p>切点落在题目中间时，前后两块各拿到半道题：前一块末尾的题少一半、后一块开头是一堆
     * 没头没尾的选项/代码。模型没法补全（提示词也不许它补全），只能产出残题或直接丢掉。
     * 实测那份《课后习题》：按字数切 5 块里有 2 处正好劈在选项和 Python 代码中间。
     *
     * <p>切点优先级：① 题目开头行（最理想）→ ② 空行 → ③ 字数硬上限（兜底）。
     */
    private List<Chunk> splitIntoChunks(String text) {
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int startLine = 1;
        int lineNo = 0;

        for (String line : text.split("\n")) {
            lineNo++;
            boolean blank = line.isBlank();
            boolean questionStart = QuestionParser.QUESTION_HEADER.matcher(line).find();

            if (current.length() >= CHUNK_CHARS && (questionStart || blank)) {
                chunks.add(new Chunk(current.toString(), startLine, lineNo - 1));
                current.setLength(0);
                startLine = lineNo;
                if (blank) {
                    continue;                   // 空行本身不进下一块，免得块头先空一行
                }
            } else if (current.length() >= CHUNK_HARD_LIMIT) {
                chunks.add(new Chunk(current.toString(), startLine, lineNo - 1));
                current.setLength(0);
                startLine = lineNo;
            }
            current.append(line).append('\n');
        }
        if (current.length() > 0) {
            chunks.add(new Chunk(current.toString(), startLine, lineNo));
        }
        return chunks;
    }

    // ========== 小工具 ==========

    private boolean hasQuestionHeader(String text) {
        return text.lines().anyMatch(l -> QuestionParser.QUESTION_HEADER.matcher(l).find());
    }

    private String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** 取一段输出放进报错/日志，方便一眼看出模型到底回了什么 */
    private String brief(String text) {
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 120) + "…";
    }

    /** 一次抽取的丢弃计数（只用于日志） */
    private static final class Stats {
        private int noStem;
        private int badOption;
        private int badAnswer;
    }

    // ========== 模型返回的结构（宽松定义，多余字段忽略） ==========

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AiQuestion(String chapter, String type, String stem, List<AiOption> options, String answer) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AiOption(String key, String content) {
    }
}
