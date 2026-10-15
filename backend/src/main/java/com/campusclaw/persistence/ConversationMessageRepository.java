package com.campusclaw.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, Long> {
    boolean existsByConversationId(Long conversationId);
    List<ConversationMessage> findAllByConversationIdOrderByIdAsc(Long conversationId);
    List<ConversationMessage> findTop6ByConversationIdAndStatusOrderByIdDesc(
            Long conversationId, MessageStatus status);
    List<ConversationMessage> findTop6ByConversationIdAndStatusAndIdLessThanOrderByIdDesc(
            Long conversationId, MessageStatus status, Long beforeId);
}
