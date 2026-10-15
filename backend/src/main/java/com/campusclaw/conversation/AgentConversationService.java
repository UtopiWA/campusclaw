package com.campusclaw.conversation;

import com.campusclaw.agent.SkillRouter;
import com.campusclaw.common.NotFoundException;
import com.campusclaw.gateway.ChatGateway;
import com.campusclaw.persistence.Assistant;
import com.campusclaw.persistence.AssistantMaterialRepository;
import com.campusclaw.persistence.AssistantRepository;
import com.campusclaw.persistence.Conversation;
import com.campusclaw.persistence.ConversationMessage;
import com.campusclaw.persistence.Skill;
import com.campusclaw.persistence.UserAccount;
import com.campusclaw.retrieval.RetrievalService;
import com.campusclaw.retrieval.SearchResponse;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/** 在发送首帧前完成授权、配置快照、消息落库、限定检索和上游连接。 */
@Service
public class AgentConversationService {
    private final ConversationService conversations;
    private final AssistantRepository assistants;
    private final AssistantMaterialRepository assistantMaterials;
    private final SkillRouter skillRouter;
    private final RetrievalService retrieval;
    private final AgentPromptBuilder prompts;
    private final ChatGateway chat;

    public AgentConversationService(ConversationService conversations,
                                    AssistantRepository assistants,
                                    AssistantMaterialRepository assistantMaterials,
                                    SkillRouter skillRouter,
                                    RetrievalService retrieval,
                                    AgentPromptBuilder prompts,
                                    ChatGateway chat) {
        this.conversations = conversations;
        this.assistants = assistants;
        this.assistantMaterials = assistantMaterials;
        this.skillRouter = skillRouter;
        this.retrieval = retrieval;
        this.prompts = prompts;
        this.chat = chat;
    }

    public AgentTurnPreparation prepare(Long conversationId, String question, UserAccount user) {
        Conversation conversation = conversations.requireOwned(conversationId, user);
        Assistant assistant = assistants.findByIdAndClassId(conversation.getAssistantId(), user.getClassId())
                .orElseThrow(NotFoundException::new);
        Optional<Skill> selectedSkill = skillRouter.route(assistant.getId(), user.getClassId(), question);
        Set<Long> materialIds = assistantMaterials.findAllByAssistantId(assistant.getId()).stream()
                .map(link -> link.getMaterialId())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        ConversationMessage userMessage = conversations.saveUserMessage(conversationId, question, user);
        SearchResponse search = retrieval.searchBound(question, user.getClassId(), materialIds, 4);
        List<HistoryMessage> history = conversations.historyBefore(conversationId, userMessage.getId());
        AgentTurnPlan plan = prompts.build(assistant, selectedSkill, search.hits(), history, question);
        // 严格 RAG：没有可靠候选时返回本地固定流，不能让模型凭参数知识自由回答。
        ChatGateway.ChatStream stream = plan.citations().isEmpty()
                ? new FixedTextStream(RetrievalService.NO_EVIDENCE)
                : chat.openStream(plan.messages());
        return new AgentTurnPreparation(conversationId, user.getId(), user.getClassId(), plan, stream);
    }

    private static final class FixedTextStream implements ChatGateway.ChatStream {
        private final String text;

        private FixedTextStream(String text) {
            this.text = text;
        }

        @Override
        public void consume(Consumer<String> deltaConsumer) {
            deltaConsumer.accept(text);
        }

        @Override
        public void close() {
            // 本地固定文本没有需要释放的连接。
        }
    }
}
