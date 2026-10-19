/**
 * Generic client-side widget loader. A widget's UI+logic lives at
 * /widgets/<widgetType>/widget.js (+ optional widget.css), resolved purely by
 * widgetType convention — no registry to edit here to add a new widget, on the
 * core team's part or a tenant's: drop the two files at that path and it's usable.
 *
 * Extend, don't copy: another module changes a widget by registering a WidgetExtension
 * bean (listed at /widgets/extensions.json), never by shipping a file at the widget's
 * path. Each extension module exports mount(container, widget, ctx, base) and calls
 * base(container, widget, ctx) to mount the layer beneath it; extensions chain innermost
 * first.
 */
const modulePromises = new Map();
const cssLoaded = new Set();
let extensionsPromise = null;

// Token defaults (status colours, artwork surface) every widget stylesheet may rely on.
function loadTokens() {
  if (document.querySelector('link[data-widget-tokens]')) return;
  const link = document.createElement('link');
  link.rel = 'stylesheet';
  link.href = '/widgets/tokens.css';
  link.dataset.widgetTokens = '';
  document.head.appendChild(link);
}

function loadCss(href) {
  loadTokens();
  if (cssLoaded.has(href)) return;
  cssLoaded.add(href);
  const link = document.createElement('link');
  link.rel = 'stylesheet';
  link.href = href;
  link.onerror = () => link.remove(); // stylesheets are optional
  document.head.appendChild(link);
}

function loadModule(url) {
  let promise = modulePromises.get(url);
  if (!promise) {
    promise = import(url);
    modulePromises.set(url, promise);
  }
  return promise;
}

function loadExtensions() {
  if (!extensionsPromise) {
    extensionsPromise = fetch('/widgets/extensions.json')
      .then(r => (r.ok ? r.json() : {}))
      .catch(() => ({}));
  }
  return extensionsPromise;
}

/**
 * @param {string} widgetType
 * @param {HTMLElement} container
 * @param {{id?: string, slotKey?: string, orderInSlot?: number, widgetType?: string, config: object}} widget
 * @param {{
 *   simulate?: boolean,
 *   outcomes?: {key: string, label: string}[],
 *   requireCompletion?: () => void,
 *   complete?: () => void,
 *   onOutcome?: (key?: string) => void,
 *   advance?: (key: string) => void,
 *   session?: {token: string, fetch: (path: string, init?: RequestInit) => Promise<Response>},
 *   saveContext?: (patch: object) => Promise<Response>
 * }} ctx  host services; flow-runtime.html supplies all of them (see
 *   docs/widget-modularisation-plan.md "Mount contract"), other hosts any subset.
 */
export async function mountWidget(widgetType, container, widget, ctx) {
  loadCss(`/widgets/${widgetType}/widget.css`);
  const [baseModule, extensions] = await Promise.all([
    loadModule(`/widgets/${widgetType}/widget.js`),
    loadExtensions(),
  ]);
  let mount = (c, w, x) => baseModule.mount(c, w, x || {});
  for (const url of extensions[widgetType] || []) {
    loadCss(url.replace(/\.js$/, '.css'));
    const ext = await loadModule(url);
    const inner = mount;
    mount = (c, w, x) => ext.mount(c, w, x || {}, inner);
  }
  return mount(container, widget, ctx || {});
}
