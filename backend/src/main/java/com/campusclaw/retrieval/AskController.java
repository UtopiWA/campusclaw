package com.campusclaw.retrieval;

import com.campusclaw.auth.CurrentUserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AskController {
    private final AskService askService;
    private final CurrentUserService currentUsers;

    public AskController(AskService askService, CurrentUserService currentUsers) {
        this.askService = askService;
        this.currentUsers = currentUsers;
    }

    @PostMapping("/api/ask")
    public AskResponse ask(@Valid @RequestBody AskRequest request) {
        return askService.ask(request, currentUsers.requireUser().getClassId());
    }
}
