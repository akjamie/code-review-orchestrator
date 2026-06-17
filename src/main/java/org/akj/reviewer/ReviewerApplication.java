package org.akj.reviewer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ReviewerApplication {

    public static void main(String[] args) {
        // Configure proxy from environment variables if present
        configureProxyFromEnv();

        SpringApplication.run(ReviewerApplication.class, args);
    }

    private static void configureProxyFromEnv() {
        String httpsProxy = System.getenv("HTTPS_PROXY");
        if (httpsProxy == null || httpsProxy.isBlank()) {
            httpsProxy = System.getenv("https_proxy");
        }
        if (httpsProxy != null && !httpsProxy.isBlank()) {
            setProxyProperties(httpsProxy, "https");
        }

        String httpProxy = System.getenv("HTTP_PROXY");
        if (httpProxy == null || httpProxy.isBlank()) {
            httpProxy = System.getenv("http_proxy");
        }
        if (httpProxy != null && !httpProxy.isBlank()) {
            setProxyProperties(httpProxy, "http");
        }
    }

    private static void setProxyProperties(String proxyUrl, String protocol) {
        try {
            String cleanUrl = proxyUrl.trim();
            if (!cleanUrl.contains("://")) {
                cleanUrl = protocol + "://" + cleanUrl;
            }
            java.net.URI uri = java.net.URI.create(cleanUrl);
            String host = uri.getHost();
            int port = uri.getPort();
            if (host != null) {
                System.setProperty(protocol + ".proxyHost", host);
                if (port != -1) {
                    System.setProperty(protocol + ".proxyPort", String.valueOf(port));
                }
                System.out.println(">>> Configured " + protocol + " proxy: host=" + host + ", port=" + (port != -1 ? port : "default"));
            }
        } catch (Exception e) {
            System.err.println(">>> Failed to parse proxy URL '" + proxyUrl + "': " + e.getMessage());
        }
    }
}