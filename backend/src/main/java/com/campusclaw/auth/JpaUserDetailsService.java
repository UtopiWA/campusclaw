package com.campusclaw.auth;

import com.campusclaw.persistence.UserAccountRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class JpaUserDetailsService implements UserDetailsService {
    private final UserAccountRepository users;

    public JpaUserDetailsService(UserAccountRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return users.findByUsername(username)
                .map(UserPrincipal::from)
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }
}

