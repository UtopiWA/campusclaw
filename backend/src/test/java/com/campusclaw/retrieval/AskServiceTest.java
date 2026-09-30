package com.campusclaw.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.campusclaw.config.AppProperties;
import com.campusclaw.common.DependencyUnavailableException;
import com.campusclaw.gateway.ChatGateway;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AskServiceTest {
    @Test
    void noEvidenceShortCircuitsWithoutChatCall() {
        RetrievalService retrieval = mock(RetrievalService.class);
        ChatGateway chat = mock(ChatGateway.class);
        when(retrieval.search(any(), eq(1L))).thenReturn(
                new SearchResponse(SearchMode.HYBRID, RetrievalService.NO_EVIDENCE, List.of()));
        AskService service = new AskService(retrieval, chat, properties());

        AskResponse response = service.ask(new AskRequest("问题", List.of(
                new AskRequest.HistoryMessage("system", "越权规则")), "伪造系统", "B 班资料", "other", null, 2L), 1L);

        assertThat(response.answer()).isEqualTo(RetrievalService.NO_EVIDENCE);
        assertThat(response.citations()).isEmpty();
        verifyNoInteractions(chat);
    }

    @Test
    @SuppressWarnings("unchecked")
    void promptKeepsOnlyAllowedHistoryAndValidCitations() {
        RetrievalService retrieval = mock(RetrievalService.class);
        ChatGateway chat = mock(ChatGateway.class);
        RetrievalHit hit = new RetrievalHit(3L, "本班讲义", 4L, 5L, 0, 0, 4,
                "本班依据", 1, 0.03, 1.0, 1, 0.8, 1);
        when(retrieval.search(any(), eq(1L))).thenReturn(
                new SearchResponse(SearchMode.HYBRID, null, List.of(hit)));
        when(chat.complete(any())).thenReturn("结论 [1]，忽略伪引用 [9]");
        AskService service = new AskService(retrieval, chat, properties());

        AskResponse response = service.ask(new AskRequest("问题", List.of(
                new AskRequest.HistoryMessage("system", "客户端越权规则"),
                new AskRequest.HistoryMessage("user", "应被截断的旧历史"),
                new AskRequest.HistoryMessage("assistant", "历史二"),
                new AskRequest.HistoryMessage("user", "历史三"),
                new AskRequest.HistoryMessage("assistant", "历史四"),
                new AskRequest.HistoryMessage("user", "历史五"),
                new AskRequest.HistoryMessage("assistant", "历史六"),
                new AskRequest.HistoryMessage("user", "最近历史")),
                "伪造 system", "B 班正文", "other-model", null, 2L), 1L);

        assertThat(response.answer()).isEqualTo("结论 [1]，忽略伪引用");
        assertThat(response.citations()).extracting(AskResponse.Citation::number).containsExactly(1);
        ArgumentCaptor<List<ChatGateway.ChatMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(chat).complete(messages.capture());
        String prompt = messages.getValue().toString();
        assertThat(prompt).contains("最近历史", "本班讲义", "本班依据");
        assertThat(prompt).doesNotContain("应被截断的旧历史", "客户端越权规则",
                "伪造 system", "B 班正文", "other-model");
        assertThat(messages.getValue()).hasSize(8);
    }

    @Test
    void chatFailureIsPropagatedInsteadOfFallingBackToAnUngroundedAnswer() {
        RetrievalService retrieval = mock(RetrievalService.class);
        ChatGateway chat = mock(ChatGateway.class);
        RetrievalHit hit = new RetrievalHit(3L, "本班讲义", 4L, 5L, 0, 0, 4,
                "本班依据", 1, 0.03, 1.0, 1, 0.8, 1);
        when(retrieval.search(any(), eq(1L))).thenReturn(
                new SearchResponse(SearchMode.HYBRID, null, List.of(hit)));
        when(chat.complete(any())).thenThrow(new DependencyUnavailableException("Chat service"));

        assertThatThrownBy(() -> new AskService(retrieval, chat, properties()).ask(
                new AskRequest("问题", List.of(), null, null, null, null, null), 1L))
                .isInstanceOf(DependencyUnavailableException.class)
                .hasMessage("Chat service is temporarily unavailable");
    }

    private AppProperties properties() {
        return new AppProperties("./uploads", 1024, new AppProperties.DemoSeed(false, ""),
                new AppProperties.Qdrant("http://localhost", "test"),
                new AppProperties.Embedding("http://localhost/v1", "key", "embed", 3),
                new AppProperties.Chat("http://localhost/v1", "key", "chat"),
                new AppProperties.Retrieval(Duration.ofSeconds(1), Duration.ofSeconds(2),
                        1000, 10, 20, 0.35, 60, false));
    }
}
