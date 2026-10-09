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
        var update = Wrappers.<Conversation>lambdaUpdate().eq(Conversation::getId, id);
        // MySQL 的 SET 按从左到右执行：先按旧计数决定标题，再递增。
        if (firstTitle != null) update.setSql("title = CASE WHEN message_count = 0 THEN {0} ELSE title END", firstTitle);
        update.setSql("message_count = message_count + 2").set(Conversation::getLastMessageAt, now);
        return update(null, update);
    }
}
