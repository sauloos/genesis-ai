/**
 * Generic client-side agent-view loader — same convention as loader.js, applied to
 * agents instead of widgets. An agent's Live Dashboard view lives at
 * /agent-views/<agentId>/live.js (+ optional live.css), and its Playground view at
 * /agent-views/<agentId>/playground.js (+ optional playground.css), both resolved
 * purely by agentId convention — no registry to edit to add a new agent's view, on
 * the core team's part or a tenant's: drop the files at that path and they're usable.
 *
 * Extend, don't copy: another module changes an agent's view by registering an
 * AgentViewExtension bean (listed at /agent-views/extensions.json), never by shipping a
 * file at the base view's path. Each extension module exports
 * mount(container, agent, ctx, base) and calls base(container, agent, ctx) to mount the
 * layer beneath it; extensions chain innermost first.
 */
const modulePromises = new Map();
const cssLoaded = new Set();
let extensionsPromise = null;

function loadCss(href) {
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
    extensionsPromise = fetch('/agent-views/extensions.json')
      .then(r => (r.ok ? r.json() : {}))
      .catch(() => ({}));
  }
  return extensionsPromise;
}

/**
 * @param {string} view 'live' or 'playground'
 * @param {string} agentId
 * @param {HTMLElement} container
 * @param {{agentId: string, engagementId?: string, direction?: string}} agent
 * @param {{simulate?: boolean, onClose?: () => void, onAssetGenerated?: () => void}} ctx
 */
export async function mountAgentView(view, agentId, container, agent, ctx) {
  loadCss(`/agent-views/${agentId}/${view}.css`);
  const [baseModule, extensions] = await Promise.all([
    loadModule(`/agent-views/${agentId}/${view}.js`),
    loadExtensions(),
  ]);
  let mount = (c, a, x) => baseModule.mount(c, a, x || {});
  for (const url of extensions[agentId]?.[view] || []) {
    loadCss(url.replace(/\.js$/, '.css'));
    const ext = await loadModule(url);
    const inner = mount;
    mount = (c, a, x) => ext.mount(c, a, x || {}, inner);
  }
  return mount(container, agent, ctx || {});
}

/**
 * @param {string} agentId
 * @param {HTMLElement} container
 * @param {{agentId: string, engagementId: string, direction: string}} agent
 * @param {{simulate?: boolean, onClose?: () => void, onAssetGenerated?: () => void}} ctx
 */
export async function mountAgentLiveView(agentId, container, agent, ctx) {
  return mountAgentView('live', agentId, container, agent, ctx);
}

/**
 * @param {string} agentId
 * @param {HTMLElement} container
 * @param {{agentId: string}} agent   the agent's admin catalog entry (displayName, customOptions, …)
 * @param {{sessionId?: string}} ctx   sessionId deep-links to a saved Playground run
 */
export async function mountAgentPlaygroundView(agentId, container, agent, ctx) {
  return mountAgentView('playground', agentId, container, agent, ctx);
}
