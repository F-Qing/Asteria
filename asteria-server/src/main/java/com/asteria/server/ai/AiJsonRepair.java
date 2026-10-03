package com.asteria.server.ai;

/**
 * 模型返回的 JSON 文本的容错修复。
 *
 * <p><b>为什么需要它</b>：题库里有数学/物理题，模型会忠实地把公式原样写进 JSON，
 * 例如 {@code {"stem": "求 \frac{1}{2} + \frac{1}{3} 的值"}}。但 JSON 规范里
 * <b>反斜杠是转义符</b>，这串文本会以两种方式坏掉：
 *
 * <ul>
 *   <li><b>静默损坏</b>：{@code \f} 在 JSON 里是合法转义（换页符 U+000C），
 *       于是 Jackson 不报错，把 {@code \frac} 解析成「换页符 + rac」——
 *       题干被悄悄改坏，用户看不出来。{@code \b}（\begin）、{@code \t}（\times）
 *       同理。</li>
 *   <li><b>直接抛异常</b>：{@code \alpha}、{@code \cdot} 这类不是合法转义序列，
 *       Jackson 抛 JsonParseException。整块抽取失败 → 重试一次仍失败 →
 *       整个分块报错、整批导入失败。</li>
 * </ul>
 *
 * <p>两种结果都很糟：前者是数据污染，后者是数据缺失。
 *
 * <p><b>为什么不能简单地把所有反斜杠都翻倍</b>：{@code \n}、{@code \t}、{@code \r}、
 * {@code \b}、{@code \f}、{@code \"}、{@code \\}、{@code \/}，以及 {@code \} + {@code u}
 * 开头的四位十六进制（Unicode 转义）—— 这些是<b>合法</b>转义，翻倍会破坏它们
 * （{@code \n} 会变成字面量「反斜杠 + n」两个字符）。
 * 所以必须逐字符判断"这个反斜杠是不是合法转义的开始"，只有不是的才补成 {@code \\}。
 * 这正是这个 bug 容易漏掉的原因。
 *
 * <p>Spring AI 的 {@code BeanOutputConverter} 自带的清理器只做 markdown 围栏和空白处理
 * （{@code MarkdownCodeBlockCleaner} / {@code WhitespaceCleaner}），<b>不碰转义</b>，
 * 所以两条 AI 路径都得自己修。
 */
public final class AiJsonRepair {

    /** JSON 规范里合法的转义字符。反斜杠后面跟这些字符时，说明它是"真转义"，不能动 */
    private static final String VALID_ESCAPES = "\"\\/bfnrt";

    private AiJsonRepair() {
    }

    /**
     * 把 JSON 文本里"当作字面量用的反斜杠"补成合法转义（{@code \} → {@code \\}）。
     *
     * <p>规则（全部在字符串内部判定，不区分是否在引号里 —— 因为模型输出的 JSON 里，
     * 裸反斜杠只会出现在字符串值中，键名不可能有反斜杠）：
     * <ol>
     *   <li>{@code \\} —— 已经是转义反斜杠，跳过两个字符</li>
     *   <li>{@code \"} —— 转义引号，跳过两个字符</li>
     *   <li>{@code \} + {@code u} + 四位十六进制（Unicode 转义）—— 合法则跳过六个字符</li>
     *   <li>{@code \n \t \r \/ \" \\} —— 正常转义，原样保留</li>
     *   <li>{@code \f \b \v} 后面紧跟小写字母 —— 当成 LaTeX 命令，补成 {@code \\f} 等
     *       （真的控制字符后面不会正好接字母，所以这条判据安全）</li>
     *   <li>其余 {@code \x}（如 {@code \frac} / {@code \alpha}）—— 补成 {@code \\x}</li>
     *   <li>末尾孤立的 {@code \} —— 补成 {@code \\}</li>
     * </ol>
     *
     * <p>已经是合法 JSON 的文本进这个方法<b>不会有任何改变</b>（幂等）。
     *
     * <p><b>已知覆盖不到的情况（只能靠提示词约束）</b>：{@code \times}（`\t` + 字母）、
     * {@code \nabla}（`\n` + 字母）—— 因为 {@code \t} {@code \n} 在正文里是<b>真会用到的</b>
     * 字符（制表、换行），无法用"后面跟字母"来区分，强行修会误伤正常文本。
     * 这类只能靠提示词要求模型把 LaTeX 反斜杠写成 {@code \\}。
     *
     * @param text 模型返回的、可能含裸反斜杠的 JSON 文本
     * @return 反斜杠已修复的文本；入参为 null 时返回 null
     */
    public static String repairBackslashes(String text) {
        if (text == null || text.indexOf('\\') < 0) {
            return text;           // 没有反斜杠就直接返回，绝大多数题库走这条路，零开销
        }
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            // 走到这里说明遇到反斜杠：判断它是不是合法转义的开始
            if (i + 1 >= text.length()) {
                sb.append("\\\\");         // 末尾孤立的反斜杠
                continue;
            }
            char next = text.charAt(i + 1);
            if (next == 'u') {
                // Unicode 转义：合法就整体拷贝，不合法（后面不是 4 位十六进制）就当字面量
                if (i + 5 < text.length() && isHex4(text, i + 2)) {
                    sb.append(text, i, i + 6);
                    i += 5;
                } else {
                    sb.append("\\\\");
                }
                continue;
            }
            if (VALID_ESCAPES.indexOf(next) >= 0) {
                // 合法转义。但有个坑：\f \b \v 在 JSON 里合法，在 LaTeX 里却是命令开头。
                // 模型很可能想写 \frac / \beta，而被 Jackson 解析成了「换页符 + rac」。
                // 判据：控制转义后面紧跟一个小写字母 → 几乎不可能是真的控制字符
                // （真的 form feed 后面不会正好接字母），当 LaTeX 命令补成 \\x。
                if (isControlEscape(next) && i + 2 < text.length() && isAsciiLower(text.charAt(i + 2))) {
                    sb.append("\\\\").append(next);
                    i++;
                    continue;
                }
                sb.append(c).append(next);  // 真转义：原样保留
                i++;
            } else {
                sb.append("\\\\");          // 非法的字面量反斜杠：补成 \\
            }
        }
        return sb.toString();
    }

    /** JSON 里合法、但同时也是 LaTeX 命令开头的控制转义字符 */
    private static boolean isControlEscape(char c) {
        return c == 'f' || c == 'b' || c == 'v';
    }

    /** ASCII 小写字母 */
    private static boolean isAsciiLower(char c) {
        return c >= 'a' && c <= 'z';
    }

    /** text[from] 起连续 4 位是不是十六进制 */
    private static boolean isHex4(String text, int from) {
        if (from + 4 > text.length()) {
            return false;
        }
        for (int i = from; i < from + 4; i++) {
            if (Character.digit(text.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }
}
