package com.campusclaw.mcp;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mcp")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "MCP 工具管理", description = "仅教师可管理当前 JWT 班级的服务、工具缓存与调用审计")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "配置或参数不合法"),
        @ApiResponse(responseCode = "401", description = "未登录"),
        @ApiResponse(responseCode = "403", description = "仅教师可用"),
        @ApiResponse(responseCode = "404", description = "服务不存在或不属于本班"),
        @ApiResponse(responseCode = "409", description = "配置并发变化或审计阻止删除"),
        @ApiResponse(responseCode = "502", description = "远端 MCP 连接或协议失败"),
        @ApiResponse(responseCode = "503", description = "本地存储或凭据不可用")
})
public class McpServerController {
    private final McpServerService service;
    private final CurrentUserService currentUsers;

    public McpServerController(McpServerService service, CurrentUserService currentUsers) {
        this.service = service;
        this.currentUsers = currentUsers;
    }

    @GetMapping("/servers")
    @Operation(summary = "列出本班 MCP 服务")
    public List<McpServerView> list() { return service.list(currentUsers.requireTeacher()); }

    @PostMapping("/servers")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "登记 MCP 服务")
    public McpServerView create(@Valid @RequestBody McpCreateServerRequest request) {
        return service.create(request, currentUsers.requireTeacher());
    }

    @PutMapping("/servers/{id}")
    @Operation(summary = "修改 MCP 服务")
    public McpServerView update(@PathVariable Long id, @Valid @RequestBody McpUpdateServerRequest request) {
        return service.update(id, request, currentUsers.requireTeacher());
    }

    @DeleteMapping("/servers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "删除无审计的 MCP 服务")
    public void delete(@PathVariable Long id) { service.delete(id, currentUsers.requireTeacher()); }

    @PostMapping("/servers/{id}/connect")
    @Operation(summary = "连接服务并发现工具")
    public McpServerView connect(@PathVariable Long id) {
        return service.connect(id, currentUsers.requireTeacher());
    }

    @PostMapping("/servers/{id}/disconnect")
    @Operation(summary = "断开服务并清空工具缓存")
    public McpServerView disconnect(@PathVariable Long id) {
        return service.disconnect(id, currentUsers.requireTeacher());
    }

    @GetMapping("/servers/{id}/tools")
    @Operation(summary = "查看最近发现的工具")
    public List<McpToolView> tools(@PathVariable Long id) {
        return service.listTools(id, currentUsers.requireTeacher());
    }

    @GetMapping("/tool-calls")
    @Operation(summary = "查看本班 MCP 调用审计")
    public List<McpToolCallView> calls(@RequestParam(defaultValue = "50") int limit) {
        return service.listCalls(limit, currentUsers.requireTeacher());
    }
}
