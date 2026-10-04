package com.asteria.common.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class QuestionParser {

    /**
     * 题号的"核心写法"（不含 Markdown 前缀 / 包裹符号），供【题号行】和【题号自带题干】两处复用。
     *
     * <p>覆盖：
     * <ul>
     *   <li>{@code 【第 1 题】} / {@code 【1】} / {@code [1]} —— 方括号包裹（AI 整理、教材常见）</li>
     *   <li>{@code 第 1 题} —— 裸写"第 N 题"（超星/学习通导出常见）</li>
     *   <li>{@code （1）} / {@code (1)} —— 圆括号包裹</li>
     *   <li>{@code 1、} {@code 1.} {@code 1．} {@code 1)} {@code 1）} —— 数字 + 分隔符</li>
     * </ul>
     */
    private static final String NUMBER_CORE =
            "(?:"
                    + "【\\s*第\\s*\\d+\\s*题\\s*】"
                    + "|【\\s*\\d+\\s*】"
                    + "|\\[\\s*\\d+\\s*\\]"
                    + "|第\\s*\\d+\\s*题"
                    + "|[（(]\\s*\\d+\\s*[）)]"
                    + "|\\d+\\s*[、.．)）]"
                    + ")";

    /**
     * 行首可选的前缀：Markdown 引用 {@code >}、标题 {@code #}、列表 {@code - / * / +}、加粗 {@code **}。
     *
     * <p>很多从网页/笔记复制出来的题库会带这些标记（{@code **1.** 题干}、{@code ## 1. 题干}），
     * 不剥掉的话题号行认不出来，整份文件直接掉进 AI 兜底。
     */
    private static final String LINE_PREFIX =
            "(?:>\\s*)?(?:#{1,6}\\s*)?(?:[-*+]\\s+)?(?:\\*\\*\\s*)?";

    /**
     * 题号行。
     *
     * <p>三种写法：
     * <ul>
     *   <li>① {@code 【第 1 题】}</li>
     *   <li>② {@code （1）} / {@code (1)} / {@code 第1题} / {@code 【1】} / {@code [1]}</li>
     *   <li>③ {@code 1、} 或 {@code 1.} 开头（数字后必须跟内容，避免把孤零零的 "3." 当题号）</li>
     * </ul>
     *
     * <p>③ 加了 {@code \\S} 收尾：只有分隔符、后面没内容的行不算题号。
     */
    public static final Pattern QUESTION_HEADER =
            Pattern.compile("^\\s*" + LINE_PREFIX
                    + "(?:"
                    + "【\\s*第\\s*\\d+\\s*题\\s*】"
                    + "|【\\s*\\d+\\s*】"
                    + "|\\[\\s*\\d+\\s*\\]"
                    + "|第\\s*\\d+\\s*题"
                    + "|[（(]\\s*\\d+\\s*[）)]"
                    + "|\\d+\\s*[、.．)）]\\s*\\S"
                    + ")");

    /**
     * 题号行**自带题干**的写法：<b>（1）Python语言属于以下哪种语言？</b> / <b>1．下列不属于…</b>
     * / <b>第1题 下列说法正确的是</b> / <b>【1】题干</b>
     *
     * <p>教材类 Word 很少写「题目：」这个标签，题干就紧跟在题号后面；没有这条规则，
     * 题号行只能落进"可疑行"，题干永远提不出来（整份文件一道题都入不了库）。
     *
     * <p>与 {@link #QUESTION_HEADER} 的区别：这条要**捕获**题号后面的题干（group 1），
     * 所以把 {@code \S} 换成 {@code (.+?)}；并且容忍题号后再跟一个分隔符（如「1．：」）。
     */
    private static final Pattern NUMBERED_STEM_PATTERN =
            Pattern.compile("^\\s*" + LINE_PREFIX
                    + "(?:[（(]\\s*\\d+\\s*[）)]|\\d+\\s*[、.．)）]|第\\s*\\d+\\s*题"
                    + "|【\\s*第\\s*\\d+\\s*题\\s*】|【\\s*\\d+\\s*】|\\[\\s*\\d+\\s*\\])"
                    + "\\s*[、.．:：]?\\s*\\*{0,2}\\s*(.+?)\\s*$");

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

    /** 题型：xxx（常和题号写在同一行）；也兼容【题型】xxx */
    private static final Pattern TYPE_PATTERN =
            Pattern.compile("(?:题型\\s*[:：]\\s*|【\\s*题型\\s*】\\s*)(\\S+)");

    /** 题干：题目：xxx / 题干：xxx */
    private static final Pattern STEM_PATTERN =
            Pattern.compile("^\\s*(?:题目|题干)\\s*[:：]\\s*(.+)$");

    /** 选项标签行：选项 / 选项： / 选项:（冒号后可能有多余内容，如“选项：c”） */
    private static final Pattern OPTION_LABEL_PATTERN =
            Pattern.compile("^\\s*选项\\s*[:：]?\\s*(.*)$");

    /**
     * 选项行：<b>A. xxx</b> / <b>A、xxx</b> / <b>A：xxx</b> / <b>（A）xxx</b> / <b>【A】xxx</b>。
     *
     * <p>相比旧版新增两类真实写法：
     * <ul>
     *   <li>字母被括号包住：{@code （A）中文} / {@code (A)中文} / {@code 【A】中文}（教材、教辅常见）</li>
     *   <li>冒号分隔：{@code A：中文} / {@code A:中文}（网课导出常见）</li>
     * </ul>
     * 全角字母 {@code Ａ}、全角分隔符 {@code ．} 依旧兼容。
     */
    private static final Pattern OPTION_PATTERN =
            Pattern.compile("^\\s*" + LINE_PREFIX
                    + "(?:[（(【\\[]\\s*)?\\s*([A-Za-zＡ-Ｚａ-ｚ])\\s*[.、．:：)）\\]】]\\s*(.+)$");

    /** 我的答案：xxx —— 直接忽略（做题人写的，不是标准答案） */
    private static final Pattern MY_ANSWER_PATTERN =
            Pattern.compile("^\\s*我的答案\\s*[:：].*$");

    /**
     * 带标签的答案行，取标签后**整段原文**（不截断、不归一化）。
     *
     * <p>三种写法：
     * <ul>
     *   <li>{@code 正确答案：} / {@code 参考答案：} / {@code 答案：}</li>
     *   <li>{@code 【答案】} / {@code 【正确答案】} —— 超星/学习通导出的标准写法</li>
     *   <li>{@code 答：} —— 简答题常见</li>
     * </ul>
     */
    private static final Pattern ANSWER_LABEL_PATTERN =
            Pattern.compile("^\\s*(?:【\\s*(?:正确答案|参考答案|答案)\\s*】|(?:正确答案|参考答案|答案|答)\\s*[:：])\\s*(.+)$");

    /**
     * 行内找答案：兼容"我的答案：xxx    正确答案：yyy"写在同一行的情况（用 find 搜索，不要求行首）。
     * <p>只认"正确答案/参考答案"和方括号形式，**故意不认单独的"答案/答"** ——
     * 因为"我的答案："里也含"答案"两个字，认了就会把做题人的答案当成标准答案。
     */
    private static final Pattern ANSWER_INLINE_PATTERN =
            Pattern.compile("(?:(?:正确答案|参考答案)\\s*[:：]|【\\s*(?:正确答案|参考答案|答案)\\s*】)\\s*(.+)$");

    /**
     * 粘在选项末尾的答案：{@code D. 丁 答案：D}。
     *
     * <p>为什么单独一条、而不是复用 {@link #ANSWER_INLINE_PATTERN}：这里的位置特殊性——
     * 它出现在**选项内容的末尾**，前面一定是选项文字，所以裸「答案：」可以放心认。
     * 唯一的坑还是"我的答案"，用定长后顾 {@code (?<!我的)} 排掉。
     */
    private static final Pattern TRAILING_ANSWER_PATTERN =
            Pattern.compile("(?<!我的)(?:正确答案|参考答案|答案)\\s*[:：]\\s*(.+)$");

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

    /**
     * 行内选项的"标记"：行首或空白之后，一个字母 + 分隔符。
     *
     * <p>{@code (?:^|\s)} 这个前置断言很关键：它保证字母是"独立开头"的，
     * 不会把 {@code e.g.}、{@code U.S.}、{@code 答A.} 这类词内的点号误判成选项标记。
     */
    private static final Pattern INLINE_OPTION_MARK =
            Pattern.compile("(?:^|\\s)([A-Za-zＡ-Ｚａ-ｚ])\\s*[.、．:：)）]\\s*");

    /** 代码行特征：含这些就别做行内选项拆分，免得把 Python/C 代码里的 {@code A.} 之类误切 */
    private static final Pattern CODE_LIKE_PATTERN =
            Pattern.compile("[{};]|//|\\bdef\\s|\\bprintf\\s*\\(|\\bcout\\b|System\\.out|\\bprint\\s*\\(");

    /** 行内选项最多认到第几个字母（超过 8 个选项八成不是选项，是正文） */
    private static final int MAX_INLINE_OPTIONS = 8;

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
        //   ① 整行不能太长（标题不会是一整句话）
        //   ② 不能带句末标点（带问号的多半是题干）
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

            // 2) 题干：题目：xxx / 题干：xxx
            if (rawStem == null) {
                var m = STEM_PATTERN.matcher(line);
                if (m.matches()) {
                    StemAndOptions split = splitStemAndInlineOptions(stripBold(m.group(1)));
                    rawStem = split.stem();
                    if (rawAnswer == null) {
                        rawAnswer = split.inlineAnswer();
                    }
                    rawOptions.addAll(split.options());
                    continue;
                }
            }

            // 2.5) 题号行自带题干：（1）xxx / 1．xxx / 第1题 xxx / 【1】xxx —— 教材里最常见的写法
            if (rawStem == null) {
                var m = NUMBERED_STEM_PATTERN.matcher(line);
                if (m.matches()) {
                    StemAndOptions split = splitStemAndInlineOptions(stripBold(m.group(1)));
                    rawStem = split.stem();
                    if (rawAnswer == null) {
                        rawAnswer = split.inlineAnswer();
                    }
                    rawOptions.addAll(split.options());
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

            // 4) 选项行：
            //    4.0 先试"一行塞了多个选项"（A. 甲 B. 乙 C. 丙）—— 旧版会把它们当成一个选项
            if (rawStem != null) {
                StemAndOptions split = splitStemAndInlineOptions(line.trim());
                if (!split.options().isEmpty() && split.stem().isEmpty()) {
                    rawOptions.addAll(split.options());
                    if (rawAnswer == null && split.inlineAnswer() != null) {
                        rawAnswer = split.inlineAnswer();
                    }
                    continue;
                }
            }
            //    4.1 单个选项：靠"字母 + 分隔符"这个特征识别，不依赖"选项："标签
            var optionMatcher = OPTION_PATTERN.matcher(line);
            if (optionMatcher.matches()) {
                rawOptions.add(new QuestionOption(normalizeLetter(optionMatcher.group(1)),
                        stripBold(optionMatcher.group(2))));
                continue;
            }

            // 5) 带标签的答案：先找（有的文件把"正确答案"和"我的答案"写在**同一行**）
            if (rawAnswer == null) {
                var inline = ANSWER_INLINE_PATTERN.matcher(line);
                if (inline.find()) {
                    rawAnswer = stripBold(inline.group(1));
                    continue;
                }
                var anchored = ANSWER_LABEL_PATTERN.matcher(line);
                if (anchored.matches()) {
                    rawAnswer = stripBold(anchored.group(1));
                    continue;
                }
            }

            // 6) 只剩"我的答案"的行（做题人写的，不可信）：跳过
            if (MY_ANSWER_PATTERN.matcher(line).matches()) {
                continue;
            }

            // 7) 裸答案行：选项块之后，整行只有答案（字母必须在本题选项里）
            String bareAnswer = stripBold(line);
            if (rawAnswer == null && !rawOptions.isEmpty() && isBareAnswer(bareAnswer, rawOptions)) {
                rawAnswer = bareAnswer;
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

    /**
     * 把"题号后面的整段文字"再拆成：题干 + 内联选项 + 内联答案。
     *
     * <p>为什么需要它：{@code 1. 下列哪个是语言？ A. 中文 B. 英文 C. 法文 D. 德文}
     * 这种"题干和选项挤在同一行"的写法在 txt/pdf 里极常见。旧版会把整串当题干，
     * 选项一个都提不出来（题目入库后没有选项，等于废题）。
     *
     * <p>拆不出来（或不满足连续 A/B/C 的强特征）时，原样当题干返回，绝不动刀 ——
     * 宁可少拆，也不要把正文误切成选项。
     */
    private StemAndOptions splitStemAndInlineOptions(String text) {
        InlineOptions parsed = splitInlineOptions(text);
        if (parsed == null) {
            return new StemAndOptions(text, List.of(), null);
        }
        return new StemAndOptions(parsed.prefix(), parsed.options(), parsed.trailingAnswer());
    }

    /**
     * 从一行里拆内联选项。
     *
     * <p>判定门槛（三重，缺一不可，目的是"宁可漏拆，不可错拆"）：
     * <ol>
     *   <li>至少 2 个标记（一个标记多半只是正文里的字母）；</li>
     *   <li>字母从 <b>A</b> 开始且严格连续（{@code A,B,C,D}），跳号或乱序一律不拆；</li>
     *   <li>每个标记后面都有非空内容（{@code A.} 后面啥都没有 → 不拆）。</li>
     * </ol>
     *
     * @return 不满足门槛 → <b>null</b>；满足 → 前缀（题干）+ 选项列表 + 可能的内联答案
     */
    private InlineOptions splitInlineOptions(String line) {
        if (line == null || line.isBlank() || CODE_LIKE_PATTERN.matcher(line).find()) {
            return null;
        }

        var matcher = INLINE_OPTION_MARK.matcher(line);
        List<Integer> markStarts = new ArrayList<>();    // 每个标记的起点
        List<Integer> contentStarts = new ArrayList<>(); // 每个选项内容的起点（分隔符之后）
        List<String> letters = new ArrayList<>();        // 每个标记的字母（已归一化）

        while (matcher.find()) {
            letters.add(normalizeLetter(matcher.group(1)));
            markStarts.add(matcher.start());
            contentStarts.add(matcher.end());
        }

        if (letters.size() < 2 || letters.size() > MAX_INLINE_OPTIONS) {
            return null;
        }
        // 必须从 A 开始、且严格连续递增
        if (!"A".equals(letters.get(0))) {
            return null;
        }
        for (int i = 1; i < letters.size(); i++) {
            if (letters.get(i).charAt(0) != letters.get(i - 1).charAt(0) + 1) {
                return null;
            }
        }

        List<QuestionOption> options = new ArrayList<>();
        for (int i = 0; i < letters.size(); i++) {
            int from = contentStarts.get(i);
            int to = (i + 1 < letters.size()) ? markStarts.get(i + 1) : line.length();
            String content = line.substring(from, to).trim();
            if (content.isEmpty()) {
                return null;                            // 某个选项是空的 → 不拆
            }
            options.add(new QuestionOption(letters.get(i), content));
        }

        // 末选项内容里可能还粘着答案（A. 甲 B. 乙 答案：B）→ 把它剥出来
        String trailingAnswer = null;
        QuestionOption last = options.get(options.size() - 1);
        var answerMatcher = TRAILING_ANSWER_PATTERN.matcher(last.getText());
        if (answerMatcher.find()) {
            String trimmed = last.getText().substring(0, answerMatcher.start()).trim();
            if (trimmed.isEmpty()) {
                return null;                            // 最后一个选项本来就是空的，不拆
            }
            trailingAnswer = stripBold(answerMatcher.group(1));
            options.set(options.size() - 1, new QuestionOption(last.getKey(), trimmed));
        }

        String prefix = line.substring(0, markStarts.get(0)).trim();
        return new InlineOptions(prefix, options, trailingAnswer);
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

    /**
     * 剥掉 Markdown <b>加粗</b> 残留（行首/行尾的 {@code **}）。
     *
     * <p>从网页/笔记复制出来的题库常带加粗：{@code **A. 甲**}、{@code **【答案】B**}。
     * 前缀交给 {@link #LINE_PREFIX} 吃掉，收尾的这对星号要在取值时剥掉。
     *
     * <p>只认<b>成对的两个星号</b>，不碰单个 {@code *} 和下划线 {@code _} ——
     * 否则会把 Python 选项里的 {@code *args}、{@code __init__} 误伤成 {@code args}、{@code init}。
     */
    private String stripBold(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        if (t.startsWith("**")) {
            t = t.substring(2).trim();
        }
        if (t.endsWith("**")) {
            t = t.substring(0, t.length() - 2).trim();
        }
        return t;
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

    /** 题干拆分结果：题干 + 从题干里拆出来的内联选项 + 可能的内联答案 */
    private record StemAndOptions(String stem, List<QuestionOption> options, String inlineAnswer) {
    }

    /** 内联选项拆分结果：前缀（题干）+ 选项 + 尾部答案 */
    private record InlineOptions(String prefix, List<QuestionOption> options, String trailingAnswer) {
    }
}
