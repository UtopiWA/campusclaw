package com.campusclaw.auth;

import com.campusclaw.persistence.UserAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contextRepository;
    private final CurrentUserService currentUsers;

    public AuthController(AuthenticationManager authenticationManager,
                          SecurityContextRepository contextRepository,
                          CurrentUserService currentUsers) {
        this.authenticationManager = authenticationManager;
        this.contextRepository = contextRepository;
        this.currentUsers = currentUsers;
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @PostMapping("/login")
    public CurrentUserService.CurrentUserView login(@Valid @RequestBody LoginRequest body,
                                                     HttpServletRequest request,
                                                     HttpServletResponse response) {
        try {
            Authentication verified = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(body.username(), body.password()));
            UserPrincipal verifiedUser = (UserPrincipal) verified.getPrincipal();

            // Rotate an existing ID after authentication to prevent session fixation.
            HttpSession existing = request.getSession(false);
            if (existing != null) {
                request.changeSessionId();
            } else {
                request.getSession(true);
            }

            // Persist only the stable user ID; role, enabled state and class membership are reloaded per request.
            Authentication sessionAuthentication = UsernamePasswordAuthenticationToken.authenticated(
                    new SessionPrincipal(verifiedUser.userId()), null, List.of());
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(sessionAuthentication);
            SecurityContextHolder.setContext(context);
            contextRepository.saveContext(context, request, response);

            UserAccount user = currentUsers.requireUser();
            return currentUsers.view(user);
        } catch (AuthenticationException exception) {
            SecurityContextHolder.clearContext();
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
    }

    @GetMapping("/me")
    public CurrentUserService.CurrentUserView me() {
        return currentUsers.view(currentUsers.requireUser());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }
}
