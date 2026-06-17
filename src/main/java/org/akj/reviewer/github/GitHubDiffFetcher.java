package org.akj.reviewer.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.akj.reviewer.agent.AgentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class GitHubDiffFetcher {

    private static final Logger log = LoggerFactory.getLogger(GitHubDiffFetcher.class);
    private static final int MAX_DIFF_CHARS = 80_000;

    // File extension → language name mapping
    private static final Map<String, String> EXTENSION_MAP = Map.ofEntries(
        Map.entry(".java", "Java"),
        Map.entry(".py", "Python"),
        Map.entry(".js", "JavaScript"),
        Map.entry(".ts", "TypeScript"),
        Map.entry(".kt", "Kotlin"),
        Map.entry(".kts", "Kotlin"),
        Map.entry(".go", "Go"),
        Map.entry(".rs", "Rust"),
        Map.entry(".rb", "Ruby"),
        Map.entry(".php", "PHP"),
        Map.entry(".cs", "C#"),
        Map.entry(".cpp", "C++"),
        Map.entry(".c", "C"),
        Map.entry(".swift", "Swift"),
        Map.entry(".scala", "Scala"),
        Map.entry(".sql", "SQL"),
        Map.entry(".yaml", "YAML"),
        Map.entry(".yml", "YAML"),
        Map.entry(".xml", "XML"),
        Map.entry(".json", "JSON"),
        Map.entry(".md", "Markdown"),
        Map.entry(".sh", "Shell"),
        Map.entry(".tf", "Terraform"),
        Map.entry(".gradle", "Gradle"),
        Map.entry(".gradle.kts", "Gradle")
    );

    private final HttpClient httpClient;
    private final String token;

    @Autowired
    public GitHubDiffFetcher(org.akj.reviewer.config.GitHubConfig gitHubConfig) {
        this(gitHubConfig, HttpClient.newHttpClient());
    }

    // Package-private constructor for testing
    GitHubDiffFetcher(org.akj.reviewer.config.GitHubConfig gitHubConfig, HttpClient httpClient) {
        this.httpClient = httpClient;
        this.token = gitHubConfig.getToken();
    }


    public record PrDetails(String title, String body) {}

    public PrDetails fetchPrDetails(String repoFullName, int prNumber) throws IOException, InterruptedException {
        String url = "https://api.github.com/repos/" + repoFullName + "/pulls/" + prNumber;
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/vnd.github.v3+json")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("GitHub API returned " + response.statusCode() + " when fetching PR details: " + response.body());
        }

        var mapper = new ObjectMapper();
        var root = mapper.readTree(response.body());
        String title = root.path("title").asText("");
        String body = root.path("body").asText("");
        return new PrDetails(title, body);
    }

    public AgentContext fetchContext(String repoFullName, int prNumber) {
        try {
            PrDetails details = fetchPrDetails(repoFullName, prNumber);
            return fetchContext(repoFullName, prNumber, details.title(), details.body());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to fetch PR details for " + repoFullName + "#" + prNumber, e);
        }
    }

    public AgentContext fetchContext(String repoFullName, int prNumber,
                                     String prTitle, String prDescription) {
        try {
            String rawDiff = fetchDiff(repoFullName, prNumber);
            List<String> changedFiles = fetchChangedFiles(repoFullName, prNumber);
            Set<String> languages = detectLanguages(changedFiles);
            boolean isPartial = rawDiff.length() > MAX_DIFF_CHARS;
            String diff = isPartial ? rawDiff.substring(0, MAX_DIFF_CHARS) : rawDiff;

            return new AgentContext(
                repoFullName,
                prNumber,
                prTitle,
                prDescription,
                diff,
                changedFiles,
                isPartial,
                languages
            );
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to fetch PR diff for " + repoFullName + "#" + prNumber, e);
        }
    }

    private String fetchDiff(String repoFullName, int prNumber) throws IOException, InterruptedException {
        String url = "https://api.github.com/repos/" + repoFullName + "/pulls/" + prNumber;
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/vnd.github.v3.diff")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("GitHub API returned " + response.statusCode() + ": " + response.body());
        }

        return response.body();
    }

    private List<String> fetchChangedFiles(String repoFullName, int prNumber) throws IOException, InterruptedException {
        String url = "https://api.github.com/repos/" + repoFullName + "/pulls/" + prNumber + "/files";
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/vnd.github.v3+json")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("GitHub API returned " + response.statusCode() + " for files endpoint");
        }

        return parseFilenames(response.body());
    }

    public Set<String> detectLanguages(List<String> files) {
        Set<String> languages = new HashSet<>();
        for (String file : files) {
            // Try longest suffix first (e.g. .gradle.kts before .kts)
            String matched = EXTENSION_MAP.entrySet().stream()
                .filter(e -> file.endsWith(e.getKey()))
                .findFirst()
                .map(Map.Entry::getValue)
                .orElse(null);
            if (matched != null) {
                languages.add(matched);
            }
        }
        return languages;
    }

    private List<String> parseFilenames(String jsonBody) {
        try {
            var mapper = new ObjectMapper();
            var root = mapper.readTree(jsonBody);
            return root.findValuesAsText("filename");
        } catch (Exception e) {
            log.warn("Failed to parse changed files JSON, returning empty list", e);
            return List.of();
        }
    }
}