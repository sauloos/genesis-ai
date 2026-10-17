package ai.genesisbrands.connector.platform;

import ai.genesisbrands.platform.connector.PlatformOperation;
import ai.genesisbrands.platform.connector.PlatformOperationFilter;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Platform connector's operation list, collected by convention from Spring MVC's own
 * handler registry — every {@code /api/**} handler of every module, core or tenant, minus
 * what the {@link PlatformOperationFilter}s drop. Nothing is listed by hand, so a new admin
 * page's REST API becomes a Platform connector capability the moment it is deployed.
 * Streaming and file-upload handlers are skipped: they don't fit a JSON call.
 */
@Component
public class PlatformOperationCatalog {

    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}:]+)(?::[^}]*)?}");
    private static final DefaultParameterNameDiscoverer PARAMETER_NAMES = new DefaultParameterNameDiscoverer();

    private final RequestMappingHandlerMapping handlerMapping;
    private final List<PlatformOperationFilter> filters;
    private volatile Map<String, PlatformOperation> operations;

    public PlatformOperationCatalog(@Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping,
                                    List<PlatformOperationFilter> filters) {
        this.handlerMapping = handlerMapping;
        this.filters = filters;
    }

    public List<PlatformOperation> list(String query) {
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        return operations().values().stream()
            .filter(op -> q.isEmpty() || op.id().contains(q) || op.path().toLowerCase(Locale.ROOT).contains(q)
                || (op.summary() != null && op.summary().toLowerCase(Locale.ROOT).contains(q)))
            .toList();
    }

    public Optional<PlatformOperation> find(String id) {
        return Optional.ofNullable(operations().get(id));
    }

    private Map<String, PlatformOperation> operations() {
        Map<String, PlatformOperation> ops = operations;
        if (ops == null) {
            synchronized (this) {
                if (operations == null) operations = collect();
                ops = operations;
            }
        }
        return ops;
    }

    private Map<String, PlatformOperation> collect() {
        List<PlatformOperation> collected = new ArrayList<>();
        Set<String> usedIds = new HashSet<>();
        List<Map.Entry<RequestMappingInfo, HandlerMethod>> handlers = new ArrayList<>(handlerMapping.getHandlerMethods().entrySet());
        handlers.sort(Comparator.comparing(e -> e.getValue().getBeanType().getSimpleName() + "#" + e.getValue().getMethod().getName()
            + "#" + e.getKey().getPatternValues()));

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlers) {
            RequestMappingInfo info = entry.getKey();
            HandlerMethod handler = entry.getValue();
            if (!isJsonCallable(info, handler)) continue;

            for (String path : new TreeSet<>(info.getPatternValues())) {
                if (!path.startsWith("/api/") || path.contains("{*")) continue;
                for (RequestMethod method : new TreeSet<>(info.getMethodsCondition().getMethods())) {
                    if (method == RequestMethod.HEAD || method == RequestMethod.OPTIONS || method == RequestMethod.TRACE) continue;
                    PlatformOperation op = new PlatformOperation(
                        uniqueId(baseId(handler), usedIds),
                        method.name(),
                        path,
                        summary(handler),
                        pathVariables(path),
                        queryParams(handler),
                        acceptsBody(handler),
                        handler.getBeanType().getSimpleName());
                    if (filters.stream().allMatch(f -> f.include(op))) {
                        collected.add(op);
                    } else {
                        usedIds.remove(op.id());
                    }
                }
            }
        }
        Map<String, PlatformOperation> byId = new LinkedHashMap<>();
        collected.stream().sorted(Comparator.comparing(PlatformOperation::id)).forEach(op -> byId.put(op.id(), op));
        return Collections.unmodifiableMap(byId);
    }

    private static boolean isJsonCallable(RequestMappingInfo info, HandlerMethod handler) {
        if (info.getMethodsCondition().getMethods().isEmpty()) return false;
        if (info.getProducesCondition().getProducibleMediaTypes().contains(MediaType.TEXT_EVENT_STREAM)) return false;
        if (info.getConsumesCondition().getConsumableMediaTypes().stream()
            .anyMatch(t -> t.isCompatibleWith(MediaType.MULTIPART_FORM_DATA))) return false;
        Class<?> returnType = handler.getMethod().getReturnType();
        if (SseEmitter.class.isAssignableFrom(returnType) || returnType.getName().startsWith("reactor.core.publisher.")) return false;
        return Arrays.stream(handler.getMethod().getParameterTypes())
            .noneMatch(t -> MultipartFile.class.isAssignableFrom(t)
                || (t.isArray() && MultipartFile.class.isAssignableFrom(t.getComponentType())));
    }

    private static String baseId(HandlerMethod handler) {
        String controller = handler.getBeanType().getSimpleName().replaceAll("Controller$", "");
        String kebab = controller.replaceAll("([a-z0-9])([A-Z])", "$1-$2").replaceAll("([A-Z])([A-Z][a-z])", "$1-$2")
            .toLowerCase(Locale.ROOT);
        String method = handler.getMethod().getName().replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
        return kebab + "." + method;
    }

    private static String uniqueId(String base, Set<String> used) {
        String id = base;
        for (int i = 2; !used.add(id); i++) id = base + "-" + i;
        return id;
    }

    private static String summary(HandlerMethod handler) {
        Operation op = handler.getMethodAnnotation(Operation.class);
        if (op == null) return null;
        String text = !op.summary().isBlank() ? op.summary() : op.description();
        return text.isBlank() ? null : text;
    }

    private static List<String> pathVariables(String path) {
        List<String> names = new ArrayList<>();
        Matcher m = PATH_VARIABLE.matcher(path);
        while (m.find()) names.add(m.group(1));
        return List.copyOf(names);
    }

    private static List<String> queryParams(HandlerMethod handler) {
        List<String> names = new ArrayList<>();
        for (MethodParameter param : handler.getMethodParameters()) {
            RequestParam rp = param.getParameterAnnotation(RequestParam.class);
            if (rp == null || Map.class.isAssignableFrom(param.getParameterType())) continue;
            String name = !rp.name().isBlank() ? rp.name() : rp.value();
            if (name.isBlank()) {
                param.initParameterNameDiscovery(PARAMETER_NAMES);
                name = param.getParameterName();
            }
            if (name != null) names.add(name);
        }
        return List.copyOf(names);
    }

    private static boolean acceptsBody(HandlerMethod handler) {
        return Arrays.stream(handler.getMethodParameters()).anyMatch(p -> p.hasParameterAnnotation(RequestBody.class));
    }
}
