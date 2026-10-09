package com.spotlink.advisor.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/** 服务端检索所得原文快照；编号不能由模型自行生成。 */
public record AdvisorKnowledgeReference(
        @JsonSerialize(using = ToStringSerializer.class) Long chunkId,
        String docCode, String title, String version, String source, int chunkIndex, String content) { }
