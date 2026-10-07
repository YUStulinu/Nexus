package nexus.core.exec;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A small type-keyed registry of application services. The application registers its engines
 * (LLM servers, the GPU broker, storage...) here at startup; nodes from any module look them up by
 * type, so the core does not depend on any of them.
 */
public final class Services {
    private final Map<Class<?>, Object> services = new ConcurrentHashMap<>();

    public <T> Services register(Class<T> type, T service) {
        services.put(type, type.cast(service));
        return this;
    }

    public <T> Optional<T> find(Class<T> type) {
        return Optional.ofNullable(type.cast(services.get(type)));
    }

    public <T> T get(Class<T> type) {
        return find(type).orElseThrow(() -> new IllegalStateException("service " + type.getSimpleName() + " is not available"));
    }
}
