package com.spotlink.advisor.dto;

import jakarta.validation.constraints.Size;

/**
 * 标题是可选的。省略时会话以「新对话」开始，随后从第一个问题重命名，所以客户端不必在
 * 什么都还没问之前，先替它编一个名字。
 */
public record CreateConversationRequest(

        @Size(max = 128, message = "标题过长")
        String title,
        @jakarta.validation.constraints.Pattern(regexp = "spring-ai|langchain", message = "未知顾问引擎") String engine
) {
}
