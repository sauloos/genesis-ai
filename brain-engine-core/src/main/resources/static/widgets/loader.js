/**
 * Generic client-side widget loader. A widget's UI+logic lives at
 * /widgets/<widgetType>/widget.js (+ optional widget.css), resolved purely by
 * widgetType convention — no registry to edit here to add a new widget, on the
 * core team's part or a tenant's: drop the two files at that path and it's usable.
 */
const modulePromises = new Map();
const cssLoaded = new Set();

function loadWidgetCss(widgetType) {
  if (cssLoaded.has(widgetType)) return;
  cssLoaded.add(widgetType);
  const link = document.createElement('link');
  link.rel = 'stylesheet';
  link.href = `/widgets/${widgetType}/widget.css`;
  link.onerror = () => link.remove(); // widget.css is optional
  document.head.appendChild(link);
}

function loadWidgetModule(widgetType) {
  let promise = modulePromises.get(widgetType);
  if (!promise) {
    promise = import(`/widgets/${widgetType}/widget.js`);
    modulePromises.set(widgetType, promise);
  }
  return promise;
}

/**
 * @param {string} widgetType
 * @param {HTMLElement} container
 * @param {{id?: string, slotKey?: string, orderInSlot?: number, widgetType?: string, config: object}} widget
 * @param {{simulate?: boolean, onOutcome?: (key?: string) => void}} ctx
 */
export async function mountWidget(widgetType, container, widget, ctx) {
  loadWidgetCss(widgetType);
  const mod = await loadWidgetModule(widgetType);
  return mod.mount(container, widget, ctx || {});
}
