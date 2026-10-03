package com.asteria.pojo.enums;

/**
 * 一道题的 AI 解析状态。
 *
 * <p>为什么要有它：AI 解析是**要花钱**的（每道题一次模型调用），而且**模型调用不是幂等的** ——
 * 失败重试真的要再付一次钱。所以必须把"这道题到底解析过没有"记在库里，
 * 否则一旦崩了就只能全部重跑，把已经花掉的钱再花一遍。
 *
 * <p>断点续跑就是靠它：只捞 {@link #PENDING} 和 {@link #FAILED} 的题。
 */
public enum AiStatus {

    /** 还没解析过（新导入的题默认值） */
    PENDING,

    /** 解析成功 */
    DONE,

    /** 解析失败，原因记在 question.ai_error */
    FAILED
}
