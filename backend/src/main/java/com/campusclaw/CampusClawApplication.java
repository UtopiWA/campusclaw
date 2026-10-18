package com.campusclaw;

import com.campusclaw.config.AppProperties;
import com.campusclaw.config.JwtProperties;
import com.campusclaw.config.McpProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties({AppProperties.class, JwtProperties.class, McpProperties.class})
@EnableAsync
@EnableScheduling
public class CampusClawApplication {

    public static void main(String[] args) {
        SpringApplication.run(CampusClawApplication.class, args);
    }
}
