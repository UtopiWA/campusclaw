package com.campusclaw;

import com.campusclaw.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class CampusClawApplication {

    public static void main(String[] args) {
        SpringApplication.run(CampusClawApplication.class, args);
    }
}

