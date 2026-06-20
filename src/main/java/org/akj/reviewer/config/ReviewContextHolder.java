package org.akj.reviewer.config;

public class ReviewContextHolder {
    private static final InheritableThreadLocal<ReviewContext> CONTEXT = new InheritableThreadLocal<>();

    public static void set(ReviewContext context) {
        CONTEXT.set(context);
    }

    public static ReviewContext get() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
