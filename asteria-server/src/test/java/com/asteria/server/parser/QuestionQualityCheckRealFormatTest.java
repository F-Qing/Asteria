package com.asteria.server.parser;

import com.asteria.common.Tool.QuestionParser;
import com.asteria.common.Tool.RawQuestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拿**真实格式**的资料跑一遍解析 + 自检，看看实际会报出什么。
 *
 * <p>为什么要用真实格式：自检的价值全在于"它能不能发现真实问题"。
 * 用编造的样例测，测的是我想象中的问题，不是文档里真有的问题。
 *
 * <p>下面的文本就是学习通导出的样子（答案行是「我的答案：X；文本;」这种，
 * 判断题的答案写「对/错」而不是字母）。
 */
class QuestionQualityCheckRealFormatTest {

    private final QuestionParser parser = new QuestionParser();
    private final QuestionQualityCheck check = new QuestionQualityCheck();

    /** 一段学习通导出的原文（单选 2 道 + 判断 1 道） */
    private static final String CHAOXING_SAMPLE = """
            ============================================================
            学习通题库导出
            导出时间：2026-09-06 00:01:06
            共 3 题
            ============================================================

            【第 1 题】题型：单选题
            题目：网页是由HTML语言来实现的，HTML语言是
            选项：
              A. 大型数据库
              B. 网络通信协议
              C. 超文本标记语言
              D. 网页源文件
            我的答案：C；超文本标记语言;
            正确答案：C；超文本标记语言;

            ------------------------------------------------------------

            【第 2 题】题型：单选题
            题目：以下关于HTML标签叙述错误的是
            选项：
              A. 可以单独出现，也可以成对出现
              B. 必须正确嵌套
              C. 标签可以带有属性
              D. 标签和其属性构成了HTML元素
            我的答案：D；标签和其属性构成了HTML元素;
            正确答案：D；标签和其属性构成了HTML元素;

            ------------------------------------------------------------

            【第 3 题】题型：判断题
            题目：HTML的标签一定是成对出现的
            选项：
              A. 对
              B. 错
            我的答案：错
            正确答案：错
            """;

    @Test
    @DisplayName("真实格式：能切出 3 道题，且自检不报致命问题")
    void should_parseRealFormat() {
        List<RawQuestion> questions = parser.parse(CHAOXING_SAMPLE);

        assertEquals(3, questions.size(), "应该切出 3 道题");

        // 题干都提出来了
        assertTrue(questions.get(0).getRawStem().contains("网页是由HTML语言"));
        assertTrue(questions.get(2).getRawStem().contains("HTML的标签一定是成对出现"));

        // 答案都提出来了
        assertEquals("C；超文本标记语言;", questions.get(0).getRawAnswer());
        assertEquals("错", questions.get(2).getRawAnswer(), "判断题答案是文字「错」");
    }

    @Test
    @DisplayName("自检对真实格式的输出：看清它到底报了哪些、没报哪些")
    void should_reportWhatOnRealFormat() {
        List<RawQuestion> questions = parser.parse(CHAOXING_SAMPLE);
        QuestionQualityCheck.Report report = check.check(questions);

        Map<String, Long> byKind = report.issues().stream()
                .collect(Collectors.groupingBy(QuestionQualityCheck.Issue::kind, Collectors.counting()));

        // 把实际结果打印出来 —— 这就是导入时日志里那行「解析自检」背后的明细
        System.out.println("========== 自检结果 ==========");
        System.out.println("总题数：" + report.total());
        for (RawQuestion q : questions) {
            System.out.println("题目类型=" + q.getRawType()
                    + " | 答案=[" + q.getRawAnswer() + "]"
                    + " | 选项=" + q.getRawOptions().stream().map(o -> o.getKey()).toList()
                    + " | 可疑行数=" + (q.getSuspicious() == null ? 0 : q.getSuspicious().size()));
        }
        System.out.println("问题分布：" + byKind);
        report.issues().forEach(i ->
                System.out.println("  [第 " + i.sequence() + " 题] " + i.kind() + " —— " + i.detail()));
        System.out.println("==============================");

        // 不该报的：题干、选项、答案都好好的
        assertTrue(!byKind.containsKey("无题干"), "真实格式题干应该都提出来了，实际：" + byKind);
        assertTrue(!byKind.containsKey("答案越界"), "答案字母都在选项里，不该报越界，实际：" + byKind);
        assertTrue(!byKind.containsKey("选项不足"), "每道题选项都够，实际：" + byKind);
    }

    @Test
    @DisplayName("判断题的答案写成文字「错」→ 答案越界检查不该因此误报")
    void should_notMisreportTrueFalseTextAnswer() {
        List<RawQuestion> questions = parser.parse(CHAOXING_SAMPLE);
        RawQuestion tf = questions.get(2);

        assertEquals("判断题", tf.getRawType());
        assertEquals("错", tf.getRawAnswer(), "答案是文字，不是字母");
        assertEquals(2, tf.getRawOptions().size(), "判断题带 A/B 两个选项");

        // 「错」不是字母，所以"答案越界"检查对它天然不适用 —— 不该报
        QuestionQualityCheck.Report report = check.check(List.of(tf));
        assertTrue(report.issues().stream().noneMatch(i -> i.kind().equals("答案越界")),
                "文字答案不该被当成越界字母，实际：" + report.issues());
    }
}
