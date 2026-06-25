package org.akj.reviewer.config;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import org.springframework.stereotype.Component;

@Component
public class TracingObservationPredicate implements ObservationPredicate {

    @Override
    public boolean test(String name, Observation.Context context) {
        return name != null && (name.startsWith("spring.ai.") || name.startsWith("gen_ai.") || name.equals("review-pipeline"));
    }
}
