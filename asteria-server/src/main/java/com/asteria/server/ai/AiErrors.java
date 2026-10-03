package com.asteria.server.ai;

/**
 * AI 调用相关的公共小工具。
 *
 * <p>为什么单独一个类：脱敏逻辑（AiServiceImpl / AiQuestionExtractor 都要用）如果各写一份，
 * 哪天规则改了就会漏改一处，而"把 key 打到日志里"是不能出错的。
 */
public final class AiErrors {

    /** 工具类不需要被实例化 */
    private AiErrors() {
    }

    /**
     * 异常信息脱敏：压成一行、截断 200 字、把 key 替换成 ***。
     *
     * <p>上游报错可能把请求内容回显出来，出栈前统一过一遍；日志和前端都只给这一份。
     */
    public static String mask(Exception e, AiRequestConfig config) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            return e.getClass().getSimpleName();
        }
        msg = msg.replaceAll("\\s+", " ").trim();
        if (msg.length() > 200) {
            msg = msg.substring(0, 200) + "…";
        }
        // config 可能为 null（不涉及 AI 配置的调用点）：这时没什么可脱敏的，直接返回
        String apiKey = config == null ? null : config.apiKey();
        return apiKey == null ? msg : msg.replace(apiKey, "***");
    }
}
