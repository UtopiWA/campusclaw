package com.campusclaw.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusclaw.auth.JwtTokenService;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;

class JwtPropertiesAndTokenTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void validPropertiesBindAndIssuedTokenContainsOnlyStableIdentity() {
        JwtProperties properties = new JwtProperties("unit-test-jwt-secret-at-least-32-bytes", Duration.ofHours(8));
        JwtConfig config = new JwtConfig();
        var key = config.jwtSecretKey(properties);
        JwtTokenService service = new JwtTokenService(config.jwtEncoder(key), properties);

        Jwt decoded = config.jwtDecoder(key).decode(service.issue(42L).value());

        assertThat(decoded.getSubject()).isEqualTo("42");
        assertThat(decoded.getClaimAsString("iss")).isEqualTo(JwtConfig.ISSUER);
        assertThat(decoded.getClaims()).doesNotContainKeys("username", "role", "classId", "class_id");
    }

    @Test
    void expiredTokenIsRejected() {
        JwtProperties properties = new JwtProperties("unit-test-jwt-secret-at-least-32-bytes", Duration.ofHours(8));
        JwtConfig config = new JwtConfig();
        var key = config.jwtSecretKey(properties);
        var encoder = config.jwtEncoder(key);
        Instant now = Instant.now();
        JwtClaimsSet expiredClaims = JwtClaimsSet.builder()
                        .issuer(JwtConfig.ISSUER)
                        .subject("42")
                        .issuedAt(now.minusSeconds(120))
                        .expiresAt(now.minusSeconds(60))
                        .build();
        String expired = encoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(), expiredClaims))
                .getTokenValue();

        assertThatThrownBy(() -> config.jwtDecoder(key).decode(expired))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void shortSecretFailsBinding() {
        runner.withPropertyValues("app.auth.jwt.secret=too-short", "app.auth.jwt.ttl=PT8H")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void nonPositiveTtlFailsBinding() {
        runner.withPropertyValues(
                        "app.auth.jwt.secret=unit-test-jwt-secret-at-least-32-bytes",
                        "app.auth.jwt.ttl=PT0S")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JwtProperties.class)
    static class PropertiesConfiguration {
    }
}
