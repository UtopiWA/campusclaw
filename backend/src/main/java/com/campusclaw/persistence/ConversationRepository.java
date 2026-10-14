package com.campusclaw.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {
    Optional<Conversation> findByIdAndUserIdAndClassId(Long id, Long userId, Long classId);
    List<Conversation> findAllByUserIdAndClassIdOrderByUpdatedAtDesc(Long userId, Long classId);
}
