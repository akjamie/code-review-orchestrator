package org.akj.reviewer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ReviewerApplication {

    public static void main(String[] args) {
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("win")) {
            System.setProperty("spring.ai.mcp.client.stdio.connections.github-mcp.command", "npx.cmd");
            System.out.println(">>> Detected Windows OS: Overridden github-mcp command to 'npx.cmd'");
        }
        SpringApplication.run(ReviewerApplication.class, args);
    }
}