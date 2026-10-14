package com.campusclaw.conversation;

import com.campusclaw.common.BadRequestException;
import com.campusclaw.common.NotFoundException;
import com.campusclaw.persistence.Assistant;
import com.campusclaw.persistence.AssistantRepository;
import com.campusclaw.persistence.Conversation;
import com.campusclaw.persistence.ConversationMessage;
import com.campusclaw.persistence.ConversationMessageRepository;
import com.campusclaw.persistence.ConversationRepository;
import com.campusclaw.persistence.MessageRole;
import com.campusclaw.persistence.MessageStatus;
import com.campusclaw.persistence.UserAccount;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 维护会话归属、可恢复消息以及送入模型的有限历史窗口。 */
@Service
public class ConversationService {
    public static final String INITIAL_TITLE = "新的提问";

    private final ConversationRepository conversations;
    private final ConversationMessageRepository messages;
    private final AssistantRepository assistants;
    private final ObjectMapper objectMapper;

    public ConversationService(ConversationRepository conversations,
                               ConversationMessageRepository messages,
                               AssistantRepository assistants,
                               ObjectMapper objectMapper) {
        this.conversations = conversations;
        this.messages = messages;
        this.assistants = assistants;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ConversationView create(Long assistantId, UserAccount user) {
        Assistant assistant = assistants.findByIdAndClassIdAndEnabledTrue(assistantId, user.getClassId())
                .orElseThrow(NotFoundException::new);
        Conversation conversation = conversations.saveAndFlush(
                new Conversation(user.getClassId(), user.getId(), assistant.getId(), INITIAL_TITLE));
        return ConversationView.detail(conversation, assistant.getName(), List.of());
    }

    @Transactional(readOnly = true)
    public List<ConversationView> list(UserAccount user) {
        List<Conversation> visible = conversations.findAllByUserIdAndClassIdOrderByUpdatedAtDesc(
                user.getId(), user.getClassId());
        if (visible.isEmpty()) {
            return List.of();
        }
        Map<Long, Assistant> names = assistants.findAllByIdInAndClassId(
                        visible.stream().map(Conversation::getAssistantId).collect(Collectors.toSet()),
                        user.getClassId()).stream()
                .collect(Collectors.toMap(Assistant::getId, Function.identity()));
        return visible.stream()
                .map(conversation -> ConversationView.summary(
                        conversation, requireAssistantName(names, conversation.getAssistantId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ConversationView get(Long id, UserAccount user) {
        Conversation conversation = requireOwned(id, user);
        Assistant assistant = assistants.findByIdAndClassId(conversation.getAssistantId(), user.getClassId())
                .orElseThrow(NotFoundException::new);
        List<MessageView> history = messages.findAllByConversationIdOrderByIdAsc(id).stream()
                .map(message -> MessageView.from(message, readCitations(message.getCitationsJson())))
                .toList();
        return ConversationView.detail(conversation, assistant.getName(), history);
    }

    @Transactional(readOnly = true)
    public Conversation requireOwned(Long id, UserAccount user) {
        return conversations.findByIdAndUserIdAndClassId(id, user.getId(), user.getClassId())
                .orElseThrow(NotFoundException::new);
    }

    /** 用户消息使用独立事务先落库；之后的检索或模型失败不会抹掉该次尝试。 */
    @Transactional
    public ConversationMessage saveUserMessage(Long conversationId, String question, UserAccount user) {
        String clean = validateQuestion(question);
        Conversation conversation = requireOwned(conversationId, user);
        if (INITIAL_TITLE.equals(conversation.getTitle())) {
            conversation.updateTitle(firstCodePoints(clean, 24));
        } else {
            conversation.touch();
        }
        ConversationMessage message = messages.saveAndFlush(new ConversationMessage(
                conversation.getId(), MessageRole.USER, clean, null, "[]", MessageStatus.COMPLETED));
        conversations.saveAndFlush(conversation);
        return message;
    }

    @Transactional(readOnly = true)
    public List<HistoryMessage> historyBefore(Long conversationId, Long beforeMessageId) {
        List<ConversationMessage> newest = messages
                .findTop6ByConversationIdAndStatusAndIdLessThanOrderByIdDesc(
                        conversationId, MessageStatus.COMPLETED, beforeMessageId);
        List<ConversationMessage> ordered = new ArrayList<>(newest.stream()
                .filter(message -> message.getRole() == MessageRole.USER
                        || message.getRole() == MessageRole.ASSISTANT)
                .filter(message -> message.getContent() != null && !message.getContent().isBlank())
                .toList());
        Collections.reverse(ordered);
        return ordered.stream()
                .map(message -> new HistoryMessage(message.getRole().name().toLowerCase(),
                        firstCodePoints(message.getContent(), 500)))
                .toList();
    }

    /** 助手消息与引用快照在独立事务提交，调用方只能在提交成功后发送 done。 */
    @Transactional
    public ConversationMessage saveAssistantMessage(Long conversationId, Long userId, Long classId,
                                                     String content, String skillName,
                                                     List<ConversationCitation> citations,
                                                     MessageStatus status) {
        if (content == null || content.isBlank()) {
            throw new BadRequestException("助手消息不能为空");
        }
        Conversation conversation = conversations.findByIdAndUserIdAndClassId(conversationId, userId, classId)
                .orElseThrow(NotFoundException::new);
        ConversationMessage message = messages.saveAndFlush(new ConversationMessage(
                conversationId, MessageRole.ASSISTANT, content, skillName,
                writeCitations(citations), status));
        conversation.touch();
        conversations.saveAndFlush(conversation);
        return message;
    }

    private String validateQuestion(String question) {
        String clean = question == null ? "" : question.trim();
        int length = codePoints(clean);
        if (length < 2 || length > 500) {
            throw new BadRequestException("问题必须包含 2 至 500 个字符");
        }
        return clean;
    }

    private List<ConversationCitation> readCitations(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored message citations are invalid", exception);
        }
    }

    private String writeCitations(List<ConversationCitation> citations) {
        try {
            return objectMapper.writeValueAsString(citations == null ? List.of() : citations);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not store message citations", exception);
        }
    }

    private String requireAssistantName(Map<Long, Assistant> assistantsById, Long id) {
        Assistant assistant = assistantsById.get(id);
        if (assistant == null) {
            throw new NotFoundException();
        }
        return assistant.getName();
    }

    private String firstCodePoints(String value, int limit) {
        int count = codePoints(value);
        return count <= limit ? value : value.substring(0, value.offsetByCodePoints(0, limit));
    }

    private int codePoints(String value) { return value.codePointCount(0, value.length()); }
}
