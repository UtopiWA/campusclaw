package com.campusclaw.conversation;

import com.campusclaw.auth.CurrentUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/conversations")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "持久化会话")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "请求字段不合法"),
        @ApiResponse(responseCode = "401", description = "未登录"),
        @ApiResponse(responseCode = "403", description = "无权执行操作"),
        @ApiResponse(responseCode = "404", description = "会话或智能体不可见")
})
public class ConversationController {
    private final ConversationService conversations;
    private final CurrentUserService currentUsers;
    private final AgentConversationService agentConversations;
    private final ConversationStreamingService streaming;

    public ConversationController(ConversationService conversations, CurrentUserService currentUsers,
                                  AgentConversationService agentConversations,
                                  ConversationStreamingService streaming) {
        this.conversations = conversations;
        this.currentUsers = currentUsers;
        this.agentConversations = agentConversations;
        this.streaming = streaming;
    }

    @GetMapping
    @Operation(summary = "列出当前用户的会话")
    public List<ConversationView> list() {
        return conversations.list(currentUsers.requireUser());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "创建会话", description = "智能体必须属于当前班级且处于启用状态。")
    public ConversationView create(@Valid @RequestBody CreateConversationRequest request) {
        return conversations.create(request.assistantId(), currentUsers.requireUser());
    }

    @GetMapping("/{id}")
    @Operation(summary = "读取会话详情", description = "返回服务端持久化的完整消息、状态、技能和引用。")
    public ConversationView get(@PathVariable Long id) {
        return conversations.get(id, currentUsers.requireUser());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "删除当前用户的会话")
    public void delete(@PathVariable Long id) {
        conversations.delete(id, currentUsers.requireUser());
    }

    @PostMapping(value = "/{id}/messages", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "发送消息并流式生成回答",
            description = "SSE 事件顺序为 meta、零到多个 delta、done；失败以 error 或连接中断结束。meta.toolCalls 是可选的有序安全摘要，没有 MCP 引用时省略。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "UTF-8 SSE 事件流",
                    content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                            schema = @Schema(type = "string"),
                            examples = @ExampleObject(value = "event: meta\\ndata: {\"status\":\"ready\",\"skillName\":\"数学计算\",\"citations\":[],\"toolCalls\":[{\"ref\":\"calculator@demo\",\"status\":\"ok\",\"detail\":\"(2 + 3) * 4 = 20\"}]}\\n\\nevent: delta\\ndata: {\"text\":\"回答\"}\\n\\nevent: done\\ndata: {\"messageId\":1}\\n\\n"))),
            @ApiResponse(responseCode = "400", description = "问题不合法"),
            @ApiResponse(responseCode = "401", description = "未登录"),
            @ApiResponse(responseCode = "403", description = "无权限"),
            @ApiResponse(responseCode = "404", description = "会话不可见"),
            @ApiResponse(responseCode = "503", description = "检索、模型或流式执行资源暂不可用")
    })
    public ResponseEntity<StreamingResponseBody> send(
            @PathVariable Long id, @Valid @RequestBody SendMessageRequest request) {
        // 所有可能改变 HTTP 状态的准备步骤都在返回 StreamingResponseBody 之前完成。
        AgentTurnPreparation preparation = agentConversations.prepare(
                id, request.content(), currentUsers.requireUser());
        StreamingResponseBody body = output -> streaming.stream(preparation, output);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "event-stream", java.nio.charset.StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noCache())
                .header(HttpHeaders.CONNECTION, "keep-alive")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }
}
