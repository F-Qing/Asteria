package com.asteria.server.parser;

import com.asteria.common.Tool.QuestionOption;
import com.asteria.common.Tool.QuestionParser;
import com.asteria.common.Tool.RawQuestion;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 解析结果的**输出侧自检**：不改数据、只打标。
 *
 * <p><b>为什么需要它</b>：切题切错了，程序自己不知道 —— 一道题可以"看起来入库成功"，
 * 实际上题干被切成了两半、答案配到了别的题上。这份自检用几条**确定性的、零成本的**规则
 * 把可疑的地方标出来，其中最有价值的是「答案越界」：
 *
 * <pre>
 *   答案字母不在选项里（答案 D，但这道题只有 A/B/C）
 * </pre>
 *
 * 这种情况几乎必然是**切题错误**，所以它相当于一个**免费的错误探针**：
 * 不用人工逐题核对，就能知道这批数据的健康度。
 *
 * <p>检查项（都来自真实题库里反复出现的出错形态）：
 * <ol>
 *   <li><b>无题干</b>：题干空 → 入库阶段会被跳过，这里提前报出来</li>
 *   <li><b>缺答案</b>：没提取到答案 → 不开 AI 时无法判分</li>
 *   <li><b>选项不足</b>：判成单选/多选却少于 2 个选项</li>
 *   <li><b>答案越界</b>：答案字母不在选项字母集合里（最可靠的问题信号）</li>
 *   <li><b>选项重复</b>：同一题里出现两个 A</li>
 *   <li><b>有可疑行</b>：{@link QuestionParser} 收块时没认出来的行 ——
 *       原本 {@code RawQuestion.suspicious} 是"只写不读"的字段，这里第一次真正利用它</li>
 * </ol>
 *
 * <p><b>没做「题号跳号」检查</b>：{@link QuestionParser} 切题时已经把题号从题干里剥掉了，
 * {@code RawQuestion} 也没保留原始题号，所以拿不到两个题号做比较。
 * 要做得先让 {@code RawQuestion} 带上题号 —— 属于后续改造，不是这里能补的。
 *
 * <p>注意：这里只<b>记录</b>，不决定"要不要入库"。入库门槛仍在
 * {@code BanksImportTransactional}（题干空 / 判不出题型的题才跳过）。
 */
@Component
@Slf4j
public class QuestionQualityCheck {

    /** 单选/多选至少要有这么多选项，少于它肯定切错了 */
    private static final int MIN_OPTIONS_FOR_CHOICE = 2;

    /** 可疑行摘要的最大长度，避免把整行原文灌进日志 */
    private static final int SUSPICIOUS_BRIEF_LENGTH = 60;

    /**
     * 检查一批解析结果。
     *
     * @param questions 解析出来的题（规则解析或 AI 抽取的结果都能用）
     * @return 检查报告
     */
    public Report check(List<RawQuestion> questions) {
        if (questions == null || questions.isEmpty()) {
            return new Report(0, List.of());
        }
        List<Issue> issues = new ArrayList<>();
        int noStem = 0;
        int noAnswer = 0;
        int tooFewOptions = 0;
        int answerOutOfRange = 0;
        int duplicateOption = 0;
        int suspiciousLine = 0;

        for (int i = 0; i < questions.size(); i++) {
            RawQuestion q = questions.get(i);
            int seq = i + 1;

            // ① 解析器留下的可疑行（这道题内部有"认不出来的行"）
            List<String> suspicious = q.getSuspicious();
            if (suspicious != null && !suspicious.isEmpty()) {
                suspiciousLine++;
                issues.add(new Issue(seq, "有可疑行",
                        "原文第 " + q.getStartLine() + " 行起的这道题里，有 " + suspicious.size()
                                + " 行没被识别（可能是被截断的题干或异常选项）：" + brief(suspicious.get(0))));
            }

            // ② 无题干
            if (q.getRawStem() == null || q.getRawStem().isBlank()) {
                noStem++;
                issues.add(new Issue(seq, "无题干", "没有提取到题干，入库时会被跳过"));
                continue;                          // 题干都没有，后面的检查没意义
            }

            List<QuestionOption> options = q.getRawOptions() == null ? List.of() : q.getRawOptions();
            String answer = q.getRawAnswer() == null ? "" : q.getRawAnswer().trim();

            // ③ 缺答案
            if (answer.isEmpty()) {
                noAnswer++;
                issues.add(new Issue(seq, "缺答案", "没提取到答案，不开 AI 时无法判分"));
            } else if (!options.isEmpty()) {
                Set<String> keys = new HashSet<>();
                boolean dup = false;
                for (QuestionOption o : options) {
                    if (!keys.add(o.getKey())) {
                        dup = true;
                    }
                }
                // ④ 选项重复
                if (dup) {
                    duplicateOption++;
                    issues.add(new Issue(seq, "选项重复", "同一题里出现了重复的选项字母：" + keys));
                }

                // ⑤ 答案越界 —— 最有价值的一条
                List<String> bad = new ArrayList<>();
                for (char c : answer.toCharArray()) {
                    if (Character.isLetter(c)) {
                        String up = String.valueOf(Character.toUpperCase(c));
                        if (!keys.contains(up)) {
                            bad.add(up);
                        }
                    }
                }
                if (!bad.isEmpty()) {
                    answerOutOfRange++;
                    issues.add(new Issue(seq, "答案越界",
                            "答案字母 " + bad + " 不在选项 " + keys + " 里（多半是切题切错了）"));
                }
            }

            // ⑥ 选项不足
            String type = q.getRawType() == null ? "" : q.getRawType();
            if ((type.contains("单选") || type.contains("多选")) && options.size() < MIN_OPTIONS_FOR_CHOICE) {
                tooFewOptions++;
                issues.add(new Issue(seq, "选项不足",
                        "题型判定为「" + type + "」但只有 " + options.size() + " 个选项"));
            }
        }

        Report report = new Report(questions.size(), issues);
        log.info("解析自检：共 {} 题 → 无题干 {} · 缺答案 {} · 选项不足 {} · 答案越界 {} · 选项重复 {} · 有可疑行 {}",
                questions.size(), noStem, noAnswer, tooFewOptions, answerOutOfRange, duplicateOption, suspiciousLine);
        if (answerOutOfRange > 0) {
            log.warn("解析自检发现 {} 处「答案越界」——答案字母不在选项里，通常意味着切题切错了，建议抽查原文", answerOutOfRange);
        }
        return report;
    }

    /** 行摘要，避免把整行原文灌进日志 */
    private static String brief(String text) {
        if (text == null) {
            return "";
        }
        String t = text.replaceAll("\\s+", " ").trim();
        return t.length() > SUSPICIOUS_BRIEF_LENGTH ? t.substring(0, SUSPICIOUS_BRIEF_LENGTH) + "…" : t;
    }

    /**
     * 一条自检问题。
     *
     * @param sequence 这批题里的第几道（从 1 开始，不是原文行号）
     * @param kind     问题类型：无题干 / 缺答案 / 选项不足 / 答案越界 / 选项重复 / 有可疑行
     * @param detail   人话描述
     */
    public record Issue(int sequence, String kind, String detail) {
    }

    /**
     * 自检报告。
     *
     * @param total  这批一共多少题
     * @param issues 发现的可疑点（空列表 = 全部检查通过）
     */
    public record Report(int total, List<Issue> issues) {
    }
}
