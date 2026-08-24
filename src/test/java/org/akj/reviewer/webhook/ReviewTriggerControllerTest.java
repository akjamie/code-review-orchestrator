package org.akj.reviewer.webhook;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.akj.reviewer.orchestrator.ReviewPipeline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class ReviewTriggerControllerTest {

    @Mock
    private ReviewPipeline pipeline;

    @Test
    void testTriggerReviewSuccess() throws Exception {
        var controller = new ReviewTriggerController(pipeline);
        var request = new ReviewTriggerController.UrlReviewRequest("https://github.com/spring-projects/spring-boot/pull/12345");

        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(pipeline).reviewPr("spring-projects/spring-boot", 12345);

        ResponseEntity<String> response = controller.reviewPrUrl(request);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().contains("Review triggered for spring-projects/spring-boot PR #12345"));

        // Wait for async execution
        boolean completed = latch.await(2, TimeUnit.SECONDS);
        assertTrue(completed, "ReviewPipeline.reviewPr should have been called asynchronously");

        verify(pipeline).reviewPr("spring-projects/spring-boot", 12345);
    }

    @Test
    void testRejectsInvalidUrl() {
        var controller = new ReviewTriggerController(pipeline);

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
