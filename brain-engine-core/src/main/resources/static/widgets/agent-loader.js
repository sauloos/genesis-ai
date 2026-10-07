/**
 * Generic client-side agent-view loader — same convention as loader.js, applied to
 * agents instead of widgets. An agent's Live Dashboard view lives at
 * /agent-views/<agentId>/live.js (+ optional live.css), and its Playground view at
 * /agent-views/<agentId>/playground.js (+ optional playground.css), both resolved
 * purely by agentId convention — no registry to edit to add a new agent's view, on
 * the core team's part or a tenant's: drop the files at that path and they're usable.
 */
const modulePromises = new Map();
const cssLoaded = new Set();

function loadAgentViewCss(agentId, view) {
  const key = `${agentId}/${view}`;
  if (cssLoaded.has(key)) return;
  cssLoaded.add(key);
  const link = document.createElement('link');
  link.rel = 'stylesheet';
  link.href = `/agent-views/${agentId}/${view}.css`;
  link.onerror = () => link.remove(); // <view>.css is optional
  document.head.appendChild(link);
}

function loadAgentViewModule(agentId, view) {
  const key = `${agentId}/${view}`;
  let promise = modulePromises.get(key);
  if (!promise) {
    promise = import(`/agent-views/${agentId}/${view}.js`);
    modulePromises.set(key, promise);
  }
  return promise;
}

/**
 * @param {string} view 'live' or 'playground'
 * @param {string} agentId
 * @param {HTMLElement} container
 * @param {{agentId: string, engagementId?: string, direction?: string}} agent
 * @param {{simulate?: boolean, onClose?: () => void, onAssetGenerated?: () => void}} ctx
 */
export async function mountAgentView(view, agentId, container, agent, ctx) {
  loadAgentViewCss(agentId, view);
  const mod = await loadAgentViewModule(agentId, view);
  return mod.mount(container, agent, ctx || {});
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
 * @param {{agentId: string}} agent
 * @param {{}} ctx
 */
export async function mountAgentPlaygroundView(agentId, container, agent, ctx) {
  return mountAgentView('playground', agentId, container, agent, ctx);
}
