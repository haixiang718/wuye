package com.java110.utils.factory;

/** Original service identity while several business modules share one JVM. */
public final class LogicalServiceContext {
    public static final String HEADER = "X-Java110-Logical-Service";
    private static final ThreadLocal<String> SERVICE = new ThreadLocal<>();

    private LogicalServiceContext() { }

    public static boolean isBusinessService(String value) {
        return value != null && value.matches("(user|community|fee|acct|store|common|report)-service");
    }

    public static String current() { return SERVICE.get(); }

    public static String resolve(String physicalName) {
        return SERVICE.get() == null ? physicalName : SERVICE.get();
    }

    public static Scope enter(String service) {
        if (!isBusinessService(service)) throw new IllegalArgumentException("Unknown logical business service");
        String previous = SERVICE.get();
        SERVICE.set(service);
        return () -> {
            if (previous == null) SERVICE.remove();
            else SERVICE.set(previous);
        };
    }

    public interface Scope extends AutoCloseable {
        @Override void close();
    }
}
