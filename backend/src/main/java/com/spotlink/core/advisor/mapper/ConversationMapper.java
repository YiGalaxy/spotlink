package com.spotlink.advisor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.spotlink.advisor.entity.Conversation;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.OffsetDateTime;
import java.util.List;

public interface ConversationMapper extends BaseMapper<Conversation> {
    default List<Conversation> findOwned(Long userId, Long enterpriseId, boolean filterEnterprise) {
        var query = Wrappers.<Conversation>lambdaQuery().eq(Conversation::getUserId, userId);
        if (filterEnterprise) {
            if (enterpriseId == null) query.isNull(Conversation::getEnterpriseId);
            else query.eq(Conversation::getEnterpriseId, enterpriseId);
        }
        return selectList(query.orderByDesc(Conversation::getLastMessageAt).orderByDesc(Conversation::getId));
    }

    default int recordCompletedTurn(Long id, OffsetDateTime now, String firstTitle) {
        var update = Wrappers.<Conversation>lambdaUpdate().eq(Conversation::getId, id)
                .setSql("message_count = message_count + 2").set(Conversation::getLastMessageAt, now);
        if (firstTitle != null) update.set(Conversation::getTitle, firstTitle);
        return update(null, update);
    }
}
