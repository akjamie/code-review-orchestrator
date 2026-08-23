package org.akj.reviewer.github;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.akj.reviewer.config.GitHubConfig;
import org.akj.reviewer.orchestrator.ReviewPipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GitHubApiMonitorTest {

    @Mock
    private GitHubConfig gitHubConfig;

    @Mock
    private SeenPrTracker seenPrTracker;

    @Mock
    private ReviewPipeline pipeline;

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<String> httpResponse;

    @Mock
    private GitHubDiffFetcher diffFetcher;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        when(gitHubConfig.getToken()).thenReturn("mock-token");
    }

    @Test
    void testPollTriggersAgentForNewPr() throws Exception {
        String json = """
            [
              {
                "number": 42,
                "title": "Add new feature",
                "head": {
                  "sha": "sha123456"
                }
              }
            ]
            """;

        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(json);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        when(seenPrTracker.hasBeenSeen("owner/repo", 42, "sha123456")).thenReturn(false);
        when(diffFetcher.hasUnresolvedThreads("owner/repo", 42)).thenReturn(false);

        // CountDownLatch to coordinate virtual threads during test
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(pipeline).reviewPr("owner/repo", 42);

        var monitor = new GitHubApiMonitor(gitHubConfig, seenPrTracker, pipeline, objectMapper, diffFetcher, httpClient, "owner/repo", true);
        monitor.poll();

        // Wait for virtual thread to invoke the pipeline
        boolean completed = latch.await(2, TimeUnit.SECONDS);
        assertTrue(completed, "pipeline should have been invoked asynchronously");

        verify(seenPrTracker).markSeen("owner/repo", 42, "sha123456");
        verify(pipeline).reviewPr("owner/repo", 42);
    }

    @Test
    void testPollSkipsAlreadySeenPr() throws Exception {
        String json = """
            [
              {
                "number": 42,
                "title": "Add new feature",
                "head": {
                  "sha": "sha123456"
                }
              }
            ]
            """;

        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(json);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        when(seenPrTracker.hasBeenSeen("owner/repo", 42, "sha123456")).thenReturn(true);

        var monitor = new GitHubApiMonitor(gitHubConfig, seenPrTracker, pipeline, objectMapper, diffFetcher, httpClient, "owner/repo", true);
        monitor.poll();

        // Should check, but not trigger agent or mark seen again
        verify(seenPrTracker, never()).markSeen(anyString(), anyInt(), anyString());
        verify(pipeline, never()).reviewPr(anyString(), anyInt());
    }

    @Test
    void testPollSkipsPrWithUnresolvedComments() throws Exception {
        String json = """
            [
              {
                "number": 42,
                "title": "Add new feature",
                "head": {
                  "sha": "sha123456"
                }
              }
            ]
            """;

        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(json);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        when(seenPrTracker.hasBeenSeen("owner/repo", 42, "sha123456")).thenReturn(false);
        when(diffFetcher.hasUnresolvedThreads("owner/repo", 42)).thenReturn(true);

        var monitor = new GitHubApiMonitor(gitHubConfig, seenPrTracker, pipeline, objectMapper, diffFetcher, httpClient, "owner/repo", true);
        monitor.poll();

        // Should check, but not trigger agent or mark seen because of unresolved comments
        verify(seenPrTracker, never()).markSeen(anyString(), anyInt(), anyString());
        verify(pipeline, never()).reviewPr(anyString(), anyInt());
    }
}
