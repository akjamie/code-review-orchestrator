package org.akj.reviewer.webhook;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.akj.reviewer.agent.AgentContext;
import org.akj.reviewer.agent.McpReviewAgent;
import org.akj.reviewer.github.GitHubDiffFetcher;
import org.akj.reviewer.github.GitHubReviewPoster;
import org.akj.reviewer.orchestrator.ReviewPipeline;
import org.akj.reviewer.synthesizer.ReviewResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class ReviewTriggerControllerTest {

    @Mock
    private GitHubDiffFetcher diffFetcher;

    @Mock
    private ReviewPipeline pipeline;

    @Mock
    private GitHubReviewPoster reviewPoster;

    @Mock
    private McpReviewAgent mcpReviewAgent;

    @Test
    void testTriggerMcpReviewSuccess() throws Exception {
        var controller = new ReviewTriggerController(diffFetcher, pipeline, reviewPoster, mcpReviewAgent);
        var request = new ReviewTriggerController.UrlReviewRequest("https://github.com/spring-projects/spring-boot/pull/12345");

        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(mcpReviewAgent).reviewPr("spring-projects/spring-boot", 12345);

        ResponseEntity<String> response = controller.reviewPrUrl(request);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().contains("Review triggered successfully"));

        // Wait for async execution
        boolean completed = latch.await(2, TimeUnit.SECONDS);
        assertTrue(completed, "McpReviewAgent should have been called asynchronously");

        verify(mcpReviewAgent).reviewPr("spring-projects/spring-boot", 12345);
        verifyNoInteractions(diffFetcher, pipeline, reviewPoster);
    }

    @Test
    void testTriggerClassicReviewSuccess() throws Exception {
        var controller = new ReviewTriggerController(diffFetcher, pipeline, reviewPoster, null);
        var request = new ReviewTriggerController.UrlReviewRequest("https://github.com/spring-projects/spring-boot/pull/12345");

        var mockContext = mock(AgentContext.class);
        var mockResult = new ReviewResult("Review content", List.of());

        when(diffFetcher.fetchContext("spring-projects/spring-boot", 12345)).thenReturn(mockContext);
        when(pipeline.execute(mockContext)).thenReturn(mockResult);

        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(reviewPoster).postReview(eq("spring-projects/spring-boot"), eq(12345), anyString(), anyList());

        ResponseEntity<String> response = controller.reviewPrUrl(request);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().contains("Review triggered successfully"));

        // Wait for async execution
        boolean completed = latch.await(2, TimeUnit.SECONDS);
        assertTrue(completed, "Classic review pipeline should have completed and posted");

        verify(diffFetcher).fetchContext("spring-projects/spring-boot", 12345);
        verify(pipeline).execute(mockContext);
        verify(reviewPoster).postReview("spring-projects/spring-boot", 12345, "Review content", List.of());
    }

    @Test
    void testRejectsInvalidUrl() {
        var controller = new ReviewTriggerController(diffFetcher, pipeline, reviewPoster, mcpReviewAgent);

        // Invalid domain
        var request1 = new ReviewTriggerController.UrlReviewRequest("https://gitlab.com/owner/repo/pull/123");
        ResponseEntity<String> response1 = controller.reviewPrUrl(request1);
        assertEquals(400, response1.getStatusCode().value());
        assertTrue(response1.getBody().contains("Invalid GitHub PR URL format"));

        // Invalid path
        var request2 = new ReviewTriggerController.UrlReviewRequest("https://github.com/owner/repo/issues/123");
        ResponseEntity<String> response2 = controller.reviewPrUrl(request2);
        assertEquals(400, response2.getStatusCode().value());
        assertTrue(response2.getBody().contains("Invalid GitHub PR URL format"));

        // Missing URL
        var request3 = new ReviewTriggerController.UrlReviewRequest("");
        ResponseEntity<String> response3 = controller.reviewPrUrl(request3);
        assertEquals(400, response3.getStatusCode().value());
        assertTrue(response3.getBody().contains("URL is required"));
    }
}
