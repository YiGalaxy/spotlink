package com.spotlink.advisor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spotlink.advisor.entity.AdvisorMessage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;

public interface AdvisorMessageMapper extends BaseMapper<AdvisorMessage> {
    default List<AdvisorMessage> findConversationMessages(Long conversationId) {
        return selectList(Wrappers.<AdvisorMessage>lambdaQuery().eq(AdvisorMessage::getConversationId, conversationId).orderByAsc(AdvisorMessage::getId));
    }

    default List<AdvisorMessage> findRecentMessages(Long conversationId, int limit) {
        return selectList(Wrappers.<AdvisorMessage>lambdaQuery().eq(AdvisorMessage::getConversationId, conversationId).orderByDesc(AdvisorMessage::getId)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 100)));
    }
}
