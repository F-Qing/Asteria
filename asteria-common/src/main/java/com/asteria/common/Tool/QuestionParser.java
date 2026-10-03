package com.asteria.common.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class QuestionParser {
    /** 题号行：① 【第 1 题】 ② （1）/（一） ③ 1、 或 1. 开头 */
    public static final Pattern QUESTION_HEADER =
            Pattern.compile("^\\s*【\\s*第\\s*\\d+\\s*题\\s*】|^\\s*[（(]\\s*\\d+\\s*[）)]|^\\s*\\d+\\s*[、.．]\\s*\\S");

    /**
     * 题号行**自带题干**的写法：<b>（1）Python语言属于以下哪种语言？</b> / <b>1．下列不属于…</b>
     *
     * <p>教材类 Word 很少写「题目：」这个标签，题干就紧跟在题号后面；没有这条规则，
     * 题号行只能落进"可疑行"，题干永远提不出来（整份文件一道题都入不了库）。
     */
    private static final Pattern NUMBERED_STEM_PATTERN =
            Pattern.compile("^\\s*(?:[（(]\\s*\\d+\\s*[）)]|\\d+\\s*[、.．])\\s*(.+)$");

    /**
     * 题型小节行：<b>1．选择题 / 2、简答题 / 三、判断题</b>
     *
     * <p>它是"下面这节是什么题型"的标签，**不是题号**。不认得它就会被当成题号，
     * 把一整节的题全吞进同一个块里（块里没有题干 → 全军覆没）。
     * 整行匹配（{@code $} 收尾），所以「1．判断题的做法是…」这种真题干不会被误伤。
     */
    private static final Pattern SECTION_TYPE_PATTERN =
            Pattern.compile("^\\s*(?:\\d+|[一二三四五六七八九十]+)\\s*[、.．]?\\s*"
                    + "(选择题|单选题|多选题|判断题|填空题|简答题|编程题|程序设计题|阅读程序题?|操作题)\\s*$");

    /**
     * 习题小节标题行：<b>习  题  1</b>（整行只有它）。教材里用它划分章节，
     * 认出来就能按「习 题 N」分章，而不是所有题都堆进「默认章节」。
     */
    private static final Pattern EXERCISE_HEADER =
            Pattern.compile("^\\s*习\\s*题\\s*[0-9一二三四五六七八九十]+\\s*$");

    /**
     * 章节标题行（AI 整理后的标准写法）：<b>【第 1 章】计算机网络概述</b>
     *
     * <p>方括号 + 明确带「章」字，和题号行（带「题」字）在正则上完全不冲突。
     */
    private static final Pattern CHAPTER_HEADER =
            Pattern.compile("^\\s*[【\\[]\\s*第\\s*[0-9一二三四五六七八九十百零]+\\s*章\\s*[】\\]]\\s*(.*)$");

    /**
     * 章节标题行（原始文件里的裸写法）：<b>第一章 绪论</b> / <b>第1章：绪论</b>
     *
     * <p>注意它是"弱特征"：句子「第一章讲了什么？」也能匹配上后半段，
     * 所以 {@link #matchChapter} 里加了长度和标点两重限制，避免把题干当成章节标题吞掉。
     */
    private static final Pattern CHAPTER_HEADER_BARE =
            Pattern.compile("^\\s*第\\s*[0-9一二三四五六七八九十百零]+\\s*章\\s*[:：、.．]?\\s*(\\S.*)$");

    /** 裸章节标题行的最大长度：超过这个长度就当成正文，不当标题 */
    private static final int BARE_CHAPTER_MAX_LENGTH = 30;

    /** 题型：xxx（常和题号写在同一行） */
    private static final Pattern TYPE_PATTERN =
            Pattern.compile("题型[：:]\\s*(\\S+)");

    /** 题干：题目：xxx / 题干：xxx */
    private static final Pattern STEM_PATTERN =
            Pattern.compile("^\\s*(?:题目|题干)\\s*[:：]\\s*(.+)$");

    /** 选项标签行：选项 / 选项： / 选项:（冒号后可能有多余内容，如“选项：c”） */
    private static final Pattern OPTION_LABEL_PATTERN =
            Pattern.compile("^\\s*选项\\s*[:：]?\\s*(.*)$");

    /** 选项行：A. xxx / A、xxx / Ａ．xxx（兼容全角字母） */
    private static final Pattern OPTION_PATTERN =
            Pattern.compile("^\\s*([A-Za-zＡ-Ｚａ-ｚ])\\s*[.、．)）]\\s*(.+)$");

    /** 我的答案：xxx —— 直接忽略（做题人写的，不是标准答案） */
    private static final Pattern MY_ANSWER_PATTERN =
            Pattern.compile("^\\s*我的答案\\s*[:：].*$");

    /** 带标签的答案行：正确答案 / 参考答案 / 答案，取冒号后**整段原文**（不截断、不归一化） */
    private static final Pattern ANSWER_LABEL_PATTERN =
            Pattern.compile("^\\s*(?:正确答案|参考答案|答案)\\s*[:：]\\s*(.+)$");

    /**
     * 行内找答案：兼容"我的答案：xxx    正确答案：yyy"写在同一行的情况（用 find 搜索，不要求行首）。
     * <p>只认"正确答案/参考答案"，**故意不认单独的"答案"** ——
     * 因为"我的答案："里也含"答案"两个字，认了就会把做题人的答案当成标准答案。
     */
    private static final Pattern ANSWER_INLINE_PATTERN =
            Pattern.compile("(?:正确答案|参考答案)\\s*[:：]\\s*(.+)$");

    /** 分隔线：------ / ====== / ~~~~~~ */
    private static final Pattern SEPARATOR_PATTERN =
            Pattern.compile("^\\s*[-=~_*]{3,}\\s*$");

    /** 裸答案行（对错版）：整行只有对错词 */
    private static final Pattern BARE_TRUE_FALSE_PATTERN =
            Pattern.compile("(?i)^(?:对|错|正确|错误|是|否|√|×|✓|✗|T|F|TRUE|FALSE)$");

    /** 裸答案行（字母版）：整行只有选项字母和分隔符 */
    private static final Pattern BARE_LETTERS_PATTERN =
            Pattern.compile("^[A-Za-zＡ-Ｚａ-ｚ]+(?:\\s*[、,，;；/]\\s*[A-Za-zＡ-Ｚａ-ｚ]+)*$");

    /** 裸答案不会超过这个长度（再长肯定不是答案） */
    private static final int BARE_ANSWER_MAX_LENGTH = 20;

    /** 入口一：整份文本 → 题目列表 */
    public List<RawQuestion> parse(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return parseLines(text.lines().toList());
    }




    /** 入口二：按行切块（核心） */
    public List<RawQuestion> parseLines(List<String> lines) {
        List<RawQuestion> questions = new ArrayList<>();   // 已收好的题（结果）
        List<String> block = new ArrayList<>();            // 手里的夹子（当前这一道题）
        int blockStartLine = 0;                            // 当前题从第几行开始；0 = 还没进入题目区
        String currentChapter = null;                      // 最近一个章节标题；null = 还没遇到章节
        String currentSectionLabel = null;                 // 最近一个"题型小节"的原始标签（如「阅读程序」）；null = 本节没写

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);

            // ① 章节标题行：切换"当前章节"，这行本身不进题目块、也不记可疑
            String chapterTitle = matchChapter(line);
            if (chapterTitle != null) {
                // 手里还夹着题 → 它属于【上一个】章节，先交出去再换章节
                if (!block.isEmpty()) {
                    questions.add(buildQuestion(block, blockStartLine, currentChapter, currentSectionLabel));
                    block.clear();
                    blockStartLine = 0;
                }
                currentChapter = chapterTitle.isEmpty() ? null : chapterTitle;
                currentSectionLabel = null;     // 换章了，上一章的题型小节跟着失效
                continue;
            }

            // ①.5 题型小节行（1．选择题 / 2、简答题）：只切换"本节题型"，它自己不是题目
            String sectionLabel = matchSectionLabel(line);
            if (sectionLabel != null) {
                if (!block.isEmpty()) {
                    questions.add(buildQuestion(block, blockStartLine, currentChapter, currentSectionLabel));
                    block.clear();
                    blockStartLine = 0;
                }
                currentSectionLabel = sectionLabel;
                continue;
            }

            boolean isHeader = QUESTION_HEADER.matcher(line).find();

            if (isHeader) {
                // ② 手里还有上一道题 → 先交出去
                if (!block.isEmpty()) {
                    questions.add(buildQuestion(block, blockStartLine, currentChapter, currentSectionLabel));
                    block.clear();                          // 腾空夹子
                }
                // 记住新这道题从第几行开始（行号从 1 开始）
                blockStartLine = i + 1;
            }

            // ③ 已经进入题目区 → 这行夹进当前这道题
            if (blockStartLine > 0) {
                block.add(line);
            }
        }

        // ④ 循环结束后，最后一道题还在夹子里，补收一次
        if (!block.isEmpty()) {
            questions.add(buildQuestion(block, blockStartLine, currentChapter, currentSectionLabel));
        }
        return questions;
    }

    /**
     * 这一行是不是"题型小节行"。
     *
     * @return 不是小节行 → <b>null</b>；是小节行 → 小节标签原文（如「选择题」「阅读程序」）
     */
    private String matchSectionLabel(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        var m = SECTION_TYPE_PATTERN.matcher(line.trim());
        return m.matches() ? m.group(1) : null;
    }

    /**
     * 小节标签 → 写进 {@link RawQuestion#getRawType()} 的中文题型名。
     *
     * <p>为什么要带上选项：同一个标签下面可能混着两种题——「阅读程序」既有带 A/B/C/D 的代码阅读题，
     * 也有只有代码、没有选项的问答题。带选项的要留给判型器（否则选项白解析），
     * 没选项的才用"简答题"兜底，免得整节被丢掉。
     *
     * @return 空串 = 不写死题型，交给判型器按选项/答案判
     */
    private String sectionTypeOf(String label, List<QuestionOption> options) {
        return switch (label) {
            case "单选题" -> "单选题";
            case "多选题" -> "多选题";
            case "判断题" -> "判断题";
            case "填空题" -> "填空题";
            case "简答题", "编程题", "程序设计题", "操作题" -> "简答题";
            case "阅读程序", "阅读程序题" -> options.isEmpty() ? "简答题" : "";
            // 「选择题」里单选多选混排：不写死，交给判型器
            default -> "";
        };
    }

    /**
     * 这一行是不是章节标题行。
     *
     * @return 不是章节行 → <b>null</b>；是章节行 → 章节名（可能是空串，表示标题只有「第 N 章」没有文字）
     */
    private String matchChapter(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        // 强特征：带方括号的【第 N 章】，无脑认
        var bracketed = CHAPTER_HEADER.matcher(line);
        if (bracketed.matches()) {
            return bracketed.group(1).trim();
        }

        // 弱特征：裸写的"第一章 绪论"。加两重限制，否则题干会被吞掉：
        //   ① 整行不能太长（标题不会是一整句话）
        //   ② 不能带句末标点（带问号的多半是题干）
        String text = line.trim();

        // 教材式：「习  题  1」整行只有它 → 当成章节标题（章节名把中间的空格收成一个，显示成「习 题 1」）
        if (EXERCISE_HEADER.matcher(text).matches()) {
            return text.replaceAll("\\s+", " ");
        }

        // 弱特征：裸写的"第一章 绪论"。加两重限制，否则题干会被吞掉：
        if (text.length() > BARE_CHAPTER_MAX_LENGTH
                || text.contains("。") || text.contains("？") || text.contains("！") || text.contains("?")) {
            return null;
        }
        var bare = CHAPTER_HEADER_BARE.matcher(text);
        return bare.matches() ? bare.group(1).trim() : null;
    }

    /** 把"一道题的所有行"提取成 RawQuestion（阶段一：只取原始材料，不判型、不归一化答案） */
    private RawQuestion buildQuestion(List<String> block, int startLine, String chapterName, String sectionLabel) {
        String rawType = null;
        String rawStem = null;
        List<QuestionOption> rawOptions = new ArrayList<>();
        String rawAnswer = null;
        List<String> suspicious = new ArrayList<>();

        for (String line : block) {
            // 0) 空行 / 分隔线：跳过，不算可疑
            if (line.isBlank() || SEPARATOR_PATTERN.matcher(line).matches()) {
                continue;
            }

            // 1) 题型（常和题号同一行）
            if (rawType == null) {
                var m = TYPE_PATTERN.matcher(line);
                if (m.find()) {
                    rawType = m.group(1).trim();
                    continue;
                }
            }

            // 2) 题干
            if (rawStem == null) {
                var m = STEM_PATTERN.matcher(line);
                if (m.matches()) {
                    rawStem = m.group(1).trim();
                    continue;
                }
            }

            // 2.5) 题号行自带题干：（1）xxx / 1．xxx —— 教材里最常见的写法，题干就在题号后面
            if (rawStem == null) {
                var m = NUMBERED_STEM_PATTERN.matcher(line);
                if (m.matches()) {
                    rawStem = m.group(1).trim();
                    continue;
                }
            }

            // 3) 选项标签行：只是个"标签"，不当开关用（有的文件根本没有"选项："这一行）
            //    冒号后若有多余内容（如"选项：c"）记一条可疑
            var labelMatcher = OPTION_LABEL_PATTERN.matcher(line);
            if (labelMatcher.matches()) {
                String rest = labelMatcher.group(1).trim();
                if (!rest.isEmpty()) {
                    suspicious.add("选项标签行有异常内容：" + line.trim());
                }
                continue;
            }

            // 4) 选项行：靠"字母 + 分隔符"这个特征识别，不依赖"选项："标签
            var optionMatcher = OPTION_PATTERN.matcher(line);
            if (optionMatcher.matches()) {
                rawOptions.add(new QuestionOption(normalizeLetter(optionMatcher.group(1)),
                        optionMatcher.group(2).trim()));
                continue;
            }

            // 5) 带标签的答案：先找（有的文件把"正确答案"和"我的答案"写在**同一行**）
            if (rawAnswer == null) {
                var inline = ANSWER_INLINE_PATTERN.matcher(line);
                if (inline.find()) {
                    rawAnswer = inline.group(1).trim();
                    continue;
                }
                var anchored = ANSWER_LABEL_PATTERN.matcher(line);
                if (anchored.matches()) {
                    rawAnswer = anchored.group(1).trim();
                    continue;
                }
            }

            // 6) 只剩"我的答案"的行（做题人写的，不可信）：跳过
            if (MY_ANSWER_PATTERN.matcher(line).matches()) {
                continue;
            }

            // 7) 裸答案行：选项块之后，整行只有答案（字母必须在本题选项里）
            if (rawAnswer == null && !rawOptions.isEmpty() && isBareAnswer(line.trim(), rawOptions)) {
                rawAnswer = line.trim();
                continue;
            }

            // 8) 以上都不匹配 → 记入可疑
            suspicious.add(line);
        }

        // 9) 题内没写「题型：」→ 用所属"题型小节"的题型兜底（1．简答题 下面的题就是简答题）
        if (rawType == null && sectionLabel != null) {
            String fromSection = sectionTypeOf(sectionLabel, rawOptions);
            if (!fromSection.isEmpty()) {
                rawType = fromSection;
            }
        }

        return new RawQuestion(rawType, rawStem, rawOptions, rawAnswer, chapterName, startLine, suspicious);
    }

    /** 是不是"裸答案行"：整行只有对错词，或整行只有本题选项里的字母 */
    private boolean isBareAnswer(String text, List<QuestionOption> options) {
        if (text.isEmpty() || text.length() > BARE_ANSWER_MAX_LENGTH) {
            return false;
        }
        if (BARE_TRUE_FALSE_PATTERN.matcher(text).matches()) {
            return true;
        }
        if (!BARE_LETTERS_PATTERN.matcher(text).matches() || options.isEmpty()) {
            return false;
        }
        // 字母必须都在本题选项里，防止把正文（如 "HTML"）误当答案
        List<String> keys = new ArrayList<>();
        for (QuestionOption option : options) {
            keys.add(option.getKey());
        }
        for (char c : text.toCharArray()) {
            if (Character.isLetter(c) && !keys.contains(normalizeLetter(String.valueOf(c)))) {
                return false;
            }
        }
        return true;
    }

    /** 全角字母转半角并统一大写：Ａ→A、a→A */
    private String normalizeLetter(String letter) {
        char c = letter.charAt(0);
        if (c >= 'Ａ' && c <= 'Ｚ') {
            c = (char) (c - 'Ａ' + 'A');
        } else if (c >= 'ａ' && c <= 'ｚ') {
            c = (char) (c - 'ａ' + 'a');
        }
        return String.valueOf(Character.toUpperCase(c));
    }
}
