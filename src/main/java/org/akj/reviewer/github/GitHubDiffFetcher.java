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
    private final ObjectMapper objectMapper;

    @Autowired
    public GitHubDiffFetcher(org.akj.reviewer.config.GitHubConfig gitHubConfig, ObjectMapper objectMapper) {
        this(gitHubConfig, HttpClient.newHttpClient(), objectMapper);
    }

    // Package-private constructor for testing
    GitHubDiffFetcher(org.akj.reviewer.config.GitHubConfig gitHubConfig, HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.token = gitHubConfig.getToken();
        this.objectMapper = objectMapper;
    }


    public record PrDetails(String title, String body, String author) {}

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

        var root = objectMapper.readTree(response.body());
        String title = root.path("title").asText("");
        String body = root.path("body").asText("");
        String author = root.path("user").path("login").asText("unknown");
        return new PrDetails(title, body, author);
    }

    public AgentContext fetchContext(String repoFullName, int prNumber) {
        try {
            PrDetails details = fetchPrDetails(repoFullName, prNumber);
            return fetchContext(repoFullName, prNumber, details.title(), details.body(), details.author());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to fetch PR details for " + repoFullName + "#" + prNumber, e);
        }
    }

    public AgentContext fetchContext(String repoFullName, int prNumber,
                                     String prTitle, String prDescription, String prAuthor) {
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
                prAuthor,
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
            var root = objectMapper.readTree(jsonBody);
            return root.findValuesAsText("filename");
        } catch (Exception e) {
            log.warn("Failed to parse changed files JSON, returning empty list", e);
            return List.of();
        }
    }

    public boolean hasUnresolvedThreads(String repoFullName, int prNumber) {
        String[] parts = repoFullName.split("/", 2);
        if (parts.length < 2) {
            log.warn("Invalid repository name: {}", repoFullName);
            return false;
        }
        String owner = parts[0];
        String name = parts[1];

        String query = """
            query($owner: String!, $name: String!, $pr: Int!) {
              repository(owner: $owner, name: $name) {
                pullRequest(number: $pr) {
                  reviewThreads(first: 100) {
                    nodes {
                      isResolved
                    }
                  }
                }
              }
            }
            """;

        try {
            var variables = objectMapper.createObjectNode();
            variables.put("owner", owner);
            variables.put("name", name);
            variables.put("pr", prNumber);

            var bodyNode = objectMapper.createObjectNode();
            bodyNode.put("query", query);
            bodyNode.set("variables", variables);

            String requestBody = objectMapper.writeValueAsString(bodyNode);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.github.com/graphql"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("GraphQL query failed with status {}: {}", response.statusCode(), response.body());
                return false;
            }

            var root = objectMapper.readTree(response.body());
            var errors = root.path("errors");
            if (!errors.isMissingNode() && errors.isArray() && !errors.isEmpty()) {
                log.warn("GraphQL errors returned: {}", errors);
            }

            var nodes = root.path("data")
                .path("repository")
                .path("pullRequest")
                .path("reviewThreads")
                .path("nodes");

            if (nodes.isArray()) {
                for (var node : nodes) {
                    if (!node.path("isResolved").asBoolean(false)) {
                        log.info("Found unresolved review thread in {}#{}", repoFullName, prNumber);
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to check for unresolved review threads for PR {}#{}", repoFullName, prNumber, e);
        }
        return false;
    }
}