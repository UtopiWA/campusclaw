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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/skills")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "技能配置")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "技能原文解析或校验失败"),
        @ApiResponse(responseCode = "401", description = "未登录"),
        @ApiResponse(responseCode = "403", description = "学生无权写入"),
        @ApiResponse(responseCode = "404", description = "资源不属于当前班级或不存在")
})
public class SkillController {
    private final SkillService skills;
    private final CurrentUserService currentUsers;

    public SkillController(SkillService skills, CurrentUserService currentUsers) {
        this.skills = skills;
        this.currentUsers = currentUsers;
    }

    @GetMapping
    @Operation(summary = "列出本班技能", description = "学生响应不包含可编辑的指令和原始文本。")
    public List<SkillView> list() {
        return skills.list(currentUsers.requireUser());
    }

    @GetMapping("/{id}")
    @Operation(summary = "读取本班技能")
    public SkillView get(@PathVariable Long id) {
        return skills.get(id, currentUsers.requireUser());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "从技能文本创建技能", description = "仅教师可用；服务端解析并校验完整原文。")
    public SkillView create(@Valid @RequestBody SkillSourceRequest request) {
        return skills.create(request.sourceText(), currentUsers.requireTeacher());
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新技能原文", description = "仅教师可用；校验成功后才覆盖已有配置。")
    public SkillView update(@PathVariable Long id, @Valid @RequestBody SkillSourceRequest request) {
        return skills.update(id, request.sourceText(), currentUsers.requireTeacher());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "删除技能", description = "仅教师可用；只解除智能体绑定，不删除材料。")
    public void delete(@PathVariable Long id) {
        skills.delete(id, currentUsers.requireTeacher());
    }
}
