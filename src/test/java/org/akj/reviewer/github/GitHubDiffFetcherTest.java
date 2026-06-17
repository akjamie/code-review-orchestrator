package org.akj.reviewer.github;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;
import org.akj.reviewer.config.GitHubConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GitHubDiffFetcherTest {

    @Mock
    private GitHubConfig gitHubConfig;

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<String> httpResponse;

    @Test
    void detectsJavaFromJavaFiles() {
        var fetcher = new GitHubDiffFetcher(gitHubConfig);
        var files = List.of("src/main/java/com/example/App.java", "src/main/java/com/example/Util.java");
        assertEquals(Set.of("Java"), fetcher.detectLanguages(files));
    }

    @Test
    void detectsPythonFromPyFiles() {
        var fetcher = new GitHubDiffFetcher(gitHubConfig);
        var files = List.of("app.py", "tests/test_app.py", "requirements.txt");
        assertEquals(Set.of("Python"), fetcher.detectLanguages(files));
    }

    @Test
    void detectsMultipleLanguages() {
        var fetcher = new GitHubDiffFetcher(gitHubConfig);
        var files = List.of("src/main/java/App.java", "src/script.py", "frontend/app.js");
        assertEquals(Set.of("Java", "Python", "JavaScript"), fetcher.detectLanguages(files));
    }

    @Test
    void returnsEmptyForUnknownExtensions() {
        var fetcher = new GitHubDiffFetcher(gitHubConfig);
        var files = List.of("Makefile", "Dockerfile", ".gitignore");
        assertEquals(Set.of(), fetcher.detectLanguages(files));
    }

    @Test
    void handlesEmptyFileList() {
        var fetcher = new GitHubDiffFetcher(gitHubConfig);
        assertEquals(Set.of(), fetcher.detectLanguages(List.of()));
    }

    @Test
    void fetchesPrDetailsSuccessfully() throws Exception {
        when(gitHubConfig.getToken()).thenReturn("dummy-token");
        String json = "{\"title\":\"My PR Title\",\"body\":\"My PR Description\"}";

        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(json);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(httpResponse);

        var fetcher = new GitHubDiffFetcher(gitHubConfig, httpClient);
        var details = fetcher.fetchPrDetails("owner/repo", 42);

        assertEquals("My PR Title", details.title());
        assertEquals("My PR Description", details.body());
    }

    @Test
    void fetchPrDetailsThrowsExceptionOnNon200() throws Exception {
        when(gitHubConfig.getToken()).thenReturn("dummy-token");
        when(httpResponse.statusCode()).thenReturn(404);
        when(httpResponse.body()).thenReturn("Not Found");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(httpResponse);

        var fetcher = new GitHubDiffFetcher(gitHubConfig, httpClient);
        assertThrows(IOException.class, () -> fetcher.fetchPrDetails("owner/repo", 42));
    }

    @Test
    @SuppressWarnings("unchecked")
    void fetchContextWithRepoAndPrNumber() throws Exception {
        when(gitHubConfig.getToken()).thenReturn("dummy-token");

        // Mock PR Details response
        var detailsResponse = mock(HttpResponse.class);
        when(detailsResponse.statusCode()).thenReturn(200);
        when(detailsResponse.body()).thenReturn("{\"title\":\"Title\",\"body\":\"Body\"}");

        // Mock Diff response
        var diffResponse = mock(HttpResponse.class);
        when(diffResponse.statusCode()).thenReturn(200);
        when(diffResponse.body()).thenReturn("diff --git a/App.java b/App.java\n...");

        // Mock Files response
        var filesResponse = mock(HttpResponse.class);
        when(filesResponse.statusCode()).thenReturn(200);
        when(filesResponse.body()).thenReturn("[{\"filename\":\"App.java\"}]");

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(detailsResponse)
            .thenReturn(diffResponse)
            .thenReturn(filesResponse);

        var fetcher = new GitHubDiffFetcher(gitHubConfig, httpClient);
        var context = fetcher.fetchContext("owner/repo", 42);

        assertNotNull(context);
        assertEquals("owner/repo", context.repoFullName());
        assertEquals(42, context.prNumber());
        assertEquals("Title", context.prTitle());
        assertEquals("Body", context.prDescription());
        assertEquals("diff --git a/App.java b/App.java\n...", context.diffContent());
        assertEquals(List.of("App.java"), context.changedFiles());
    }
}