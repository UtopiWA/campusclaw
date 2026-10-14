package com.campusclaw.agent;

import com.campusclaw.auth.CurrentUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/assistants")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "智能体配置")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "请求字段或绑定不合法"),
        @ApiResponse(responseCode = "401", description = "未登录"),
        @ApiResponse(responseCode = "403", description = "学生无权写入"),
        @ApiResponse(responseCode = "404", description = "资源不属于当前班级或不存在")
})
public class AssistantController {
    private final AssistantService assistants;
    private final CurrentUserService currentUsers;

    public AssistantController(AssistantService assistants, CurrentUserService currentUsers) {
        this.assistants = assistants;
        this.currentUsers = currentUsers;
    }

    @GetMapping
    @Operation(summary = "列出本班智能体", description = "学生仅获得已启用智能体的摘要；教师获得完整配置。")
    public List<AssistantView> list() {
        return assistants.list(currentUsers.requireUser());
    }

    @GetMapping("/{id}")
    @Operation(summary = "读取本班智能体")
    public AssistantView get(@PathVariable Long id) {
        return assistants.get(id, currentUsers.requireUser());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "创建智能体", description = "仅教师可用；技能与材料绑定会在同一事务内保存。")
    public AssistantView create(@Valid @RequestBody AssistantConfigRequest request) {
        return assistants.create(request, currentUsers.requireTeacher());
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新智能体", description = "仅教师可用；提交完整配置并原子替换绑定。")
    public AssistantView update(@PathVariable Long id, @Valid @RequestBody AssistantConfigRequest request) {
        return assistants.update(id, request, currentUsers.requireTeacher());
    }
}
