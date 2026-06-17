package org.akj.reviewer.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GitHubConfig {

    @Value("${github.webhook-secret}")
    private String webhookSecret;

    @Value("${github.token}")
    private String token;

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public String getToken() {
        return token;
    }
}