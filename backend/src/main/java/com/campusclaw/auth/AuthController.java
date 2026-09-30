package com.campusclaw.auth;

import com.campusclaw.persistence.UserAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
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
    private final CurrentUserService currentUsers;
    private final JwtTokenService tokens;

    public AuthController(AuthenticationManager authenticationManager,
                          CurrentUserService currentUsers,
                          JwtTokenService tokens) {
        this.authenticationManager = authenticationManager;
        this.currentUsers = currentUsers;
        this.tokens = tokens;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest body) {
        try {
            Authentication verified = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(body.username(), body.password()));
            UserPrincipal verifiedUser = (UserPrincipal) verified.getPrincipal();
            UserAccount user = currentUsers.requireUser(verifiedUser.userId());
            JwtTokenService.IssuedToken token = tokens.issue(user.getId());
            return new LoginResponse("Bearer", token.value(), token.expiresAt(), currentUsers.view(user));
        } catch (AuthenticationException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
    }

    @GetMapping("/me")
    public CurrentUserService.CurrentUserView me() {
        return currentUsers.view(currentUsers.requireUser());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout() {
        // JWT 无服务端会话；保留幂等端点，实际退出由客户端删除令牌完成。
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record LoginResponse(String tokenType, String accessToken, java.time.Instant expiresAt,
                                CurrentUserService.CurrentUserView user) {
    }
}
