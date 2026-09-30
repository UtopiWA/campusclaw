package com.campusclaw.auth;

import com.campusclaw.persistence.ClassRoomRepository;
import com.campusclaw.persistence.Role;
import com.campusclaw.persistence.UserAccount;
import com.campusclaw.persistence.UserAccountRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/** 将 JWT 中的最小用户标识解析为数据库当前用户、角色和班级上下文。 */
@Service
public class CurrentUserService {
    private final UserAccountRepository users;
    private final ClassRoomRepository classRooms;

    public CurrentUserService(UserAccountRepository users, ClassRoomRepository classRooms) {
        this.users = users;
        this.classRooms = classRooms;
    }

    public UserAccount requireUser() {
        // JWT 不固化业务权限；每次请求回查数据库，使禁用、角色和班级变更立即生效。
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new AuthenticationCredentialsNotFoundException("Authentication required");
        }
        try {
            return requireUser(Long.valueOf(jwt.getSubject()));
        } catch (NumberFormatException exception) {
            throw new AuthenticationCredentialsNotFoundException("Authentication required");
        }
    }

    public UserAccount requireUser(Long userId) {
        return users.findById(userId)
                .filter(UserAccount::isEnabled)
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Authentication required"));
    }

    public UserAccount requireTeacher() {
        UserAccount user = requireUser();
        if (user.getRole() != Role.TEACHER) {
            throw new AccessDeniedException("Teacher role required");
        }
        return user;
    }

    public CurrentUserView view(UserAccount user) {
        String className = classRooms.findById(user.getClassId()).map(room -> room.getName()).orElse("未知班级");
        return new CurrentUserView(
                user.getId(), user.getUsername(), user.getRole().name().toLowerCase(), user.getClassId(), className);
    }

    public record CurrentUserView(Long id, String username, String role, Long classId, String className) {
    }
}
