package com.hfj.fitagent.modules.coach.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * chat_conversation（V18）：教练会话头。
 *
 * <p>只有契约里的四个业务列：标题由服务端从首个问题截取（§11.1 的创建请求体是 {@code \{\}}），
 * {@code lastMessageAt} 由消息分配序号的同一路径写入，客户端都不传。
 */
@TableName("chat_conversation")
@Getter
@Setter
public class ChatConversationEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String title;

    /** ACTIVE / ARCHIVED。 */
    private String status;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Instant lastMessageAt;

    @Version
    private Integer lockVersion;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Instant updatedAt;
}
