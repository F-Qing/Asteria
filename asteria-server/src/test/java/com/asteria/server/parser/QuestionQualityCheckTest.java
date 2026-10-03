package com.asteria.server.parser;

import com.asteria.common.Tool.QuestionOption;
import com.asteria.common.Tool.RawQuestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 输出侧自检的守护测试。
 *
 * <p>这个类的价值全在「答案越界」那一条：它是**能自动发现切题切错**的信号，
 * 所以测试重点是"该报的必须报、不该报的不能报"（误报会让这个探针失去意义）。
 */
class QuestionQualityCheckTest {

    private final QuestionQualityCheck check = new QuestionQualityCheck();

    /** 造一道正常的单选题 */
    private static RawQuestion single(String stem, String answer, String... optionKeys) {
        List<QuestionOption> options = new ArrayList<>();
        for (String k : optionKeys) {
            options.add(new QuestionOption(k, k + " 的选项内容"));
        }
        return new RawQuestion("单选题", stem, options, answer, "第一章", 1, new ArrayList<>());
    }

    private static List<String> kinds(QuestionQualityCheck.Report report) {
        return report.issues().stream().map(QuestionQualityCheck.Issue::kind).toList();
    }

    @Nested
    @DisplayName("正常数据不报问题（误报会让探针失去意义）")
    class NoFalsePositive {

        @Test
        @DisplayName("一道规矩的单选题 → 0 条问题")
        void should_reportNothing_forHealthyQuestion() {
            QuestionQualityCheck.Report r = check.check(List.of(single("1+1=?", "A", "A", "B", "C", "D")));

            assertEquals(1, r.total());
            assertTrue(r.issues().isEmpty(), "正常题不该报问题，实际报了：" + kinds(r));
        }

        @Test
        @DisplayName("多选题答案两个字母都在选项里 → 不报")
        void should_reportNothing_forValidMultiple() {
            RawQuestion q = new RawQuestion("多选题", "以下哪些是语言？",
                    List.of(new QuestionOption("A", "Java"), new QuestionOption("B", "Python"),
                            new QuestionOption("C", "HTML")),
                    "A,B", "第一章", 1, new ArrayList<>());

            assertTrue(check.check(List.of(q)).issues().isEmpty());
        }

        @Test
        @DisplayName("判断题没有选项、答案是「对」→ 不报（本来就没选项）")
        void should_reportNothing_forTrueFalse() {
            RawQuestion q = new RawQuestion("判断题", "地球是圆的", List.of(), "对", "第一章", 1, new ArrayList<>());

            assertTrue(check.check(List.of(q)).issues().isEmpty());
        }

        @Test
        @DisplayName("空列表 → 直接返回空报告")
        void should_handleEmptyList() {
            QuestionQualityCheck.Report r = check.check(List.of());

            assertEquals(0, r.total());
            assertTrue(r.issues().isEmpty());
        }

        @Test
        @DisplayName("null → 不抛异常")
        void should_handleNull() {
            assertEquals(0, check.check(null).total());
        }
    }

    @Nested
    @DisplayName("答案越界：最可靠的问题信号")
    class AnswerOutOfRange {

        @Test
        @DisplayName("答案 D 但只有 A/B/C → 报「答案越界」")
        void should_report_whenAnswerLetterNotInOptions() {
            QuestionQualityCheck.Report r = check.check(List.of(single("题干", "D", "A", "B", "C")));

            assertTrue(kinds(r).contains("答案越界"), "应报答案越界，实际：" + kinds(r));
        }

        @Test
        @DisplayName("多选答案里混进一个越界字母 → 也报")
        void should_report_whenOneLetterOfMultipleIsOutOfRange() {
            RawQuestion q = new RawQuestion("多选题", "题干",
                    List.of(new QuestionOption("A", "甲"), new QuestionOption("B", "乙")),
                    "A,B,D", "第一章", 1, new ArrayList<>());

            QuestionQualityCheck.Report r = check.check(List.of(q));

            assertTrue(kinds(r).contains("答案越界"), "实际：" + kinds(r));
        }

        @Test
        @DisplayName("答案是小写字母 → 归一化后能对上，不报")
        void should_caseInsensitive() {
            QuestionQualityCheck.Report r = check.check(List.of(single("题干", "b", "A", "B", "C")));

            assertTrue(r.issues().isEmpty(), "小写答案应能匹配，实际报了：" + kinds(r));
        }

        @Test
        @DisplayName("答案是「字母；答案全文」形式 → 只校验开头的字母，全文里的英文不算")
        void should_onlyCheckLeadingLetters_notTextInAnswer() {
            // 学习通导出的真实形态：答案是「D；标签和其属性构成了HTML元素;」
            // 「HTML」这四个字母曾经被当成答案字母，报出 [H,T,M,L] 四个假越界
            RawQuestion q = new RawQuestion("单选题", "以下关于HTML标签叙述错误的是",
                    List.of(new QuestionOption("A", "可以单独出现"), new QuestionOption("B", "必须正确嵌套"),
                            new QuestionOption("C", "标签可以带有属性"), new QuestionOption("D", "标签和其属性构成HTML元素")),
                    "D；标签和其属性构成了HTML元素;", "第一章", 1, new ArrayList<>());

            QuestionQualityCheck.Report r = check.check(List.of(q));

            assertTrue(r.issues().isEmpty(),
                    "答案里的「HTML」不是选项字母，不该报越界。实际报了：" + kinds(r));
        }

        @Test
        @DisplayName("中文答案（判断题的「对」/「错」）→ 不当作字母校验，不报越界")
        void should_notTreatChineseAnswerAsLetters() {
            // Character.isLetter('错') == true，用它判定会让每道判断题都误报一次
            RawQuestion q = new RawQuestion("判断题", "HTML的标签一定是成对出现的",
                    List.of(new QuestionOption("A", "对"), new QuestionOption("B", "错")),
                    "错", "第一章", 1, new ArrayList<>());

            QuestionQualityCheck.Report r = check.check(List.of(q));

            assertTrue(r.issues().isEmpty(),
                    "中文答案不该被当成越界字母。实际报了：" + kinds(r));
        }

        @Test
        @DisplayName("多选答案是「A,C；甲和乙;」→ 逗号两侧的字母都要校验")
        void should_checkBothLetters_inMultipleChoiceWithText() {
            RawQuestion ok = new RawQuestion("多选题", "题干",
                    List.of(new QuestionOption("A", "甲"), new QuestionOption("B", "乙"), new QuestionOption("C", "丙")),
                    "A,C；甲和丙;", "第一章", 1, new ArrayList<>());
            RawQuestion bad = new RawQuestion("多选题", "题干",
                    List.of(new QuestionOption("A", "甲"), new QuestionOption("B", "乙")),
                    "A,D；甲和丁;", "第一章", 1, new ArrayList<>());

            assertTrue(check.check(List.of(ok)).issues().isEmpty(), "A 和 C 都在选项里，不该报");
            assertTrue(kinds(check.check(List.of(bad))).contains("答案越界"),
                    "D 不在选项里，应该报出来");
        }

        @Test
        @DisplayName("真越界还是要能抓到（防止修复过头，把探针修失灵了）")
        void should_stillCatchRealOutOfRange() {
            // 只有 A/B/C 三个选项，答案是 D —— 这是真问题，必须报
            QuestionQualityCheck.Report r = check.check(List.of(single("题干", "D；某个选项;", "A", "B", "C")));

            assertTrue(kinds(r).contains("答案越界"),
                    "真越界必须能抓到，否则这个检查就白做了。实际：" + kinds(r));
        }
    }

    @Nested
    @DisplayName("其余检查项")
    class OtherChecks {

        @Test
        @DisplayName("题干为空 → 报「无题干」，且不再报后面的检查（避免噪音）")
        void should_reportNoStem_andStopFurtherChecks() {
            QuestionQualityCheck.Report r = check.check(List.of(single("   ", "D", "A", "B")));

            assertEquals(List.of("无题干"), kinds(r), "无题干的题只报一条，不要级联报一堆");
        }

        @Test
        @DisplayName("没有答案 → 报「缺答案」")
        void should_reportMissingAnswer() {
            QuestionQualityCheck.Report r = check.check(List.of(single("题干", "", "A", "B", "C")));

            assertTrue(kinds(r).contains("缺答案"), "实际：" + kinds(r));
        }

        @Test
        @DisplayName("判成单选题却只有 1 个选项 → 报「选项不足」")
        void should_reportTooFewOptions() {
            QuestionQualityCheck.Report r = check.check(List.of(single("题干", "A", "A")));

            assertTrue(kinds(r).contains("选项不足"), "实际：" + kinds(r));
        }

        @Test
        @DisplayName("两个 A → 报「选项重复」")
        void should_reportDuplicateOption() {
            QuestionQualityCheck.Report r = check.check(List.of(single("题干", "A", "A", "A", "B")));

            assertTrue(kinds(r).contains("选项重复"), "实际：" + kinds(r));
        }

        @Test
        @DisplayName("解析器留了可疑行 → 报「有可疑行」，并把原文行号带出来")
        void should_reportSuspiciousLines() {
            List<String> suspicious = new ArrayList<>(List.of("这一行没认出来"));
            RawQuestion q = new RawQuestion("单选题", "题干",
                    List.of(new QuestionOption("A", "甲"), new QuestionOption("B", "乙")),
                    "A", "第一章", 42, suspicious);

            QuestionQualityCheck.Report r = check.check(List.of(q));

            assertTrue(kinds(r).contains("有可疑行"), "实际：" + kinds(r));
            String detail = r.issues().stream()
                    .filter(i -> i.kind().equals("有可疑行"))
                    .findFirst().orElseThrow().detail();
            assertTrue(detail.contains("42"), "可疑行应带出原文行号，实际：" + detail);
        }
    }

    @Nested
    @DisplayName("报告结构")
    class ReportShape {

        @Test
        @DisplayName("序号从 1 开始，且与题目顺序一一对应")
        void should_numberIssuesByQuestionOrder() {
            List<RawQuestion> questions = List.of(
                    single("正常题", "A", "A", "B"),
                    single("   ", "A", "A", "B"),          // 第 2 题：无题干
                    single("越界题", "D", "A", "B"));        // 第 3 题：答案越界

            QuestionQualityCheck.Report r = check.check(questions);

            assertEquals(3, r.total());
            assertTrue(r.issues().stream().anyMatch(i -> i.sequence() == 2 && i.kind().equals("无题干")));
            assertTrue(r.issues().stream().anyMatch(i -> i.sequence() == 3 && i.kind().equals("答案越界")));
            assertTrue(r.issues().stream().noneMatch(i -> i.sequence() == 1), "正常的第 1 题不该出现在问题列表里");
        }
    }
}
