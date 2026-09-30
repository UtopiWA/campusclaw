package com.campusclaw.retrieval;

import com.campusclaw.auth.CurrentUserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/retrieval")
public class RetrievalController {
    private final RetrievalService retrieval;
    private final CurrentUserService currentUsers;

    public RetrievalController(RetrievalService retrieval, CurrentUserService currentUsers) {
        this.retrieval = retrieval;
        this.currentUsers = currentUsers;
    }

    @PostMapping("/search")
    public SearchResponse search(@Valid @RequestBody SearchRequest request) {
        return retrieval.search(request, currentUsers.requireUser().getClassId());
    }
}
