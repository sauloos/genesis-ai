/**
 * Generic client-side agent-view loader — same convention as loader.js, applied to
 * agents instead of widgets. An agent's Live Dashboard view lives at
 * /agent-views/<agentId>/live.js (+ optional live.css), resolved purely by agentId
 * convention — no registry to edit to add a new agent's view, on the core team's
 * part or a tenant's: drop the two files at that path and it's usable. A future
 * Playground view would follow the same pattern at /agent-views/<agentId>/playground.js.
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
 * @param {string} agentId
 * @param {HTMLElement} container
 * @param {{agentId: string, engagementId: string, direction: string}} agent
 * @param {{simulate?: boolean, onClose?: () => void, onAssetGenerated?: () => void}} ctx
 */
export async function mountAgentLiveView(agentId, container, agent, ctx) {
  loadAgentViewCss(agentId, 'live');
  const mod = await loadAgentViewModule(agentId, 'live');
  return mod.mount(container, agent, ctx || {});
}
