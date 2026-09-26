package com.campusclaw.auth;

import com.campusclaw.persistence.ClassRoomRepository;
import com.campusclaw.persistence.Role;
import com.campusclaw.persistence.UserAccount;
import com.campusclaw.persistence.UserAccountRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** Resolves the minimal session principal into the current database-backed user and class context. */
@Service
public class CurrentUserService {
    private final UserAccountRepository users;
    private final ClassRoomRepository classRooms;

    public CurrentUserService(UserAccountRepository users, ClassRoomRepository classRooms) {
        this.users = users;
        this.classRooms = classRooms;
    }

    public UserAccount requireUser() {
        // Reloading on every request makes account disablement and role/class changes effective immediately.
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof SessionPrincipal principal)) {
            throw new AuthenticationCredentialsNotFoundException("Authentication required");
        }
        return users.findById(principal.userId())
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
