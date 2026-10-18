/**
 * Redirect widget — sends the visitor to `targetUrl`. Alone on a page it forwards
 * immediately; next to other widgets it lets the rest of the page show for a moment first.
 * Never redirects in an admin simulation, so the author can still step through the flow.
 */
export function mount(container, widget, ctx) {
  const target = (widget.config || {}).targetUrl;
  if (!target) return;
  if (ctx.simulate) {
    container.textContent = 'Redirects to ' + target;
    return;
  }
  const go = () => window.location.assign(target);
  if ((ctx.page?.widgetCount ?? 1) <= 1) go();
  else setTimeout(go, 1500);
}
