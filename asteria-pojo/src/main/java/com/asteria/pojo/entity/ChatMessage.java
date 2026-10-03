package com.asteria.pojo.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 聊天消息实体，对应 finaltext.chat_message 表。
 *
 * <p><b>这张表没有 updated_at</b>：消息正文是不可变的（删掉"反馈"功能后更是），
 * 所以实体里不要写 updatedAt —— 写了 MyBatis-Plus 会去操作一个不存在的列，启动/插入直接报错。
 */
@Data
@TableName("chat_message")
public class ChatMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属会话 ID（外键 → chat_session.id，删会话时消息级联删除） */
    private Long sessionId;

    /** 角色：user 用户 / assistant AI / system 系统（用字符串，和接口契约一致，不做枚举转换） */
    private String role;

    /** 正文；流式中断时保存【已经生成的那部分】，不能丢 */
    private String content;

    /** 是否被用户中途停止：false 否 / true 是（库列是 tinyint(1)，JDBC 自动当布尔处理） */
    private Boolean interrupted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
