package ai.genesisbrands.platform.connector;

import java.util.List;

/**
 * One admin operation offered by the Platform connector, collected by convention from an
 * existing admin REST handler — whichever module (core or tenant) declared it.
 *
 * @param id          stable name, {@code <controller-kebab>.<method>} (e.g. {@code page-flow.list})
 * @param method      HTTP method
 * @param path        URI template, e.g. {@code /api/admin/page-flows/{id}}
 * @param summary     the handler's OpenAPI summary, when it has one
 * @param pathParams  names of the template variables in {@code path}
 * @param queryParams names of the handler's request parameters
 * @param acceptsBody true when the handler reads a JSON request body
 * @param source      the declaring controller's simple class name
 */
public record PlatformOperation(
    String id,
    String method,
    String path,
    String summary,
    List<String> pathParams,
    List<String> queryParams,
    boolean acceptsBody,
    String source
) {}
