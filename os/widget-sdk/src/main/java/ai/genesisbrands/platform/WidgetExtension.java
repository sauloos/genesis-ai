package ai.genesisbrands.platform;

/**
 * Extension point: wraps a widget's module instead of replacing it. A module that wants to
 * change how another module's widget looks or behaves (e.g. a tenant adding its own copy or
 * fields to the core login widget) registers one of these beans and ships the extension
 * module; it never ships a file at the base widget's own path.
 * <p>
 * The client loader ({@code /widgets/loader.js}) chains every extension for a widget type,
 * lowest {@link #order()} innermost. An extension module exports
 * {@code mount(container, widget, ctx, base)}, where {@code base(container, widget, ctx)}
 * mounts the layer beneath it — typically called with an enriched {@code widget.config} or
 * {@code ctx}, then decorated. An optional stylesheet next to the module (same name,
 * {@code .css}) is loaded alongside, under the same token-only rule as {@code widget.css}.
 */
public interface WidgetExtension {

    /** The widget type this extends. */
    String widgetType();

    /** Absolute URL path of the extension's ES module, e.g. {@code /widgets/login/ext/brands.js}. */
    String module();

    /** Chain position: lower wraps closer to the base widget. */
    default int order() {
        return 0;
    }
}
