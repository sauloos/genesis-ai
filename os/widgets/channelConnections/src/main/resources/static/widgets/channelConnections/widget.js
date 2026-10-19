const HEADERS = { 'Content-Type': 'application/json', 'X-Api-Key': 'genesis-ai-key' };
const BASE = '/api/client/channels';

// Shown in the admin preview instead of calling the API.
const SAMPLE = {
  ready: true,
  providers: [
    { providerId: 'meta', displayName: 'Facebook & Instagram', configured: true,
      networks: [{ id: 'facebook', label: 'Facebook Page' }, { id: 'instagram', label: 'Instagram' }] },
    { providerId: 'linkedin', displayName: 'LinkedIn', configured: true, networks: [{ id: 'linkedin', label: 'LinkedIn' }] }
  ],
  accounts: [
    { id: 's1', provider: 'meta', network: 'facebook', networkLabel: 'Facebook Page', displayName: 'Northwind Coffee',
      status: 'CONNECTED', connectedAt: new Date(Date.now() - 12 * 864e5).toISOString() },
    { id: 's2', provider: 'meta', network: 'instagram', networkLabel: 'Instagram', displayName: '@northwindcoffee',
      status: 'CONNECTED', connectedAt: new Date(Date.now() - 12 * 864e5).toISOString() },
    { id: 's3', provider: 'linkedin', network: 'linkedin', networkLabel: 'LinkedIn', displayName: 'Ada Lovelace',
      status: 'EXPIRED', lastError: 'LinkedIn asked to sign in again.', connectedAt: new Date(Date.now() - 70 * 864e5).toISOString() }
  ]
};

function el(tag, cls, text) {
  const node = document.createElement(tag);
  if (cls) node.className = cls;
  if (text != null) node.textContent = text;
  return node;
}

async function call(path, init = {}) {
  const res = await fetch(BASE + path, { credentials: 'include', headers: HEADERS, ...init });
  if (!res.ok) {
    let message = res.status === 401 ? 'Please sign in again.' : `Something went wrong (${res.status}).`;
    try { message = (await res.json()).message || message; } catch (_) {}
    throw new Error(message);
  }
  return res.status === 204 ? null : res.json();
}

// The OAuth callback sends the browser back here with ?channels=connected|cancelled|error.
function takeReturnNotice() {
  const url = new URL(location.href);
  const result = url.searchParams.get('channels');
  if (!result) return null;
  const message = url.searchParams.get('channelsMessage') || '';
  url.searchParams.delete('channels');
  url.searchParams.delete('channelsMessage');
  history.replaceState(history.state, '', url.pathname + url.search + url.hash);
  return { error: result === 'error', message: message || (result === 'connected' ? 'Connected.' : 'The connection was cancelled.') };
}

export function mount(container, widget, ctx = {}) {
  container.classList.add('chcon');
  const simulate = !!ctx.simulate;
  let data = null;

  const panel = el('div', 'chcon-panel');
  panel.appendChild(el('div', 'chcon-label', 'Social accounts'));
  const notice = el('div', 'chcon-notice');
  const body = el('div', 'chcon-body');
  panel.append(notice, body);
  container.appendChild(panel);

  const back = simulate ? null : takeReturnNotice();
  if (back) flash(back.message, back.error);

  function flash(message, isError) {
    notice.textContent = message;
    notice.classList.toggle('chcon-notice-error', !!isError);
  }

  async function load() {
    if (simulate) {
      data = structuredClone(SAMPLE);
      render();
      return;
    }
    try {
      data = await call('');
      render();
    } catch (err) {
      body.replaceChildren(el('div', 'chcon-empty', err.message));
    }
  }

  function render() {
    const providers = (data.providers || []).filter(p => p.configured);
    if (!data.ready || !providers.length) {
      body.replaceChildren(el('div', 'chcon-empty', 'Publishing to social accounts is not available yet.'));
      return;
    }
    const nodes = [];
    nodes.push(el('p', 'chcon-intro', 'Connect the accounts your approved posts should go to. Nothing is posted until you choose to publish it.'));
    const list = el('div', 'chcon-list');
    const accounts = data.accounts || [];
    if (!accounts.length) list.appendChild(el('div', 'chcon-empty', 'No accounts connected yet.'));
    accounts.forEach(a => list.appendChild(renderAccount(a)));
    nodes.push(list);

    const connect = el('div', 'chcon-connect');
    providers.forEach(p => {
      const btn = el('button', 'chcon-btn', `Connect ${p.displayName}`);
      btn.type = 'button';
      btn.addEventListener('click', () => startConnect(p, btn));
      connect.appendChild(btn);
    });
    nodes.push(connect);
    body.replaceChildren(...nodes);
  }

  function renderAccount(a) {
    const row = el('div', 'chcon-row');
    const info = el('div', 'chcon-info');
    info.appendChild(el('div', 'chcon-name', a.displayName || a.networkLabel));
    const meta = el('div', 'chcon-meta');
    meta.appendChild(el('span', 'chcon-network', a.networkLabel || a.network));
    const state = el('span', 'chcon-state');
    if (a.status === 'CONNECTED') {
      state.textContent = 'Connected';
      state.classList.add('chcon-state-on');
    } else {
      state.textContent = 'Needs reconnecting';
      state.classList.add('chcon-state-off');
    }
    meta.appendChild(state);
    info.appendChild(meta);
    if (a.status !== 'CONNECTED' && a.lastError) info.appendChild(el('div', 'chcon-error', a.lastError));
    row.appendChild(info);

    const btn = el('button', 'chcon-btn chcon-btn-quiet', 'Disconnect');
    btn.type = 'button';
    btn.addEventListener('click', () => disconnect(a, btn));
    row.appendChild(btn);
    return row;
  }

  async function startConnect(provider, btn) {
    if (simulate) {
      flash(`Preview: this would open ${provider.displayName} to sign in.`);
      return;
    }
    btn.disabled = true;
    try {
      const returnTo = location.pathname + location.search;
      const { url } = await call(`/${encodeURIComponent(provider.providerId)}/connect?returnTo=${encodeURIComponent(returnTo)}`);
      location.assign(url);
    } catch (err) {
      btn.disabled = false;
      flash(err.message, true);
    }
  }

  async function disconnect(account, btn) {
    if (!confirm(`Disconnect ${account.displayName}? Posts waiting to go to it are cancelled.`)) return;
    if (simulate) {
      data.accounts = data.accounts.filter(a => a.id !== account.id);
      flash('Disconnected.');
      render();
      return;
    }
    btn.disabled = true;
    try {
      await call(`/accounts/${encodeURIComponent(account.id)}`, { method: 'DELETE' });
      flash('Disconnected.');
      await load();
    } catch (err) {
      btn.disabled = false;
      flash(err.message, true);
    }
  }

  load();
}
