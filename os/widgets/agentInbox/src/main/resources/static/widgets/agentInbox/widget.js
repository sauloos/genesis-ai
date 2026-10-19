const HEADERS = { 'Content-Type': 'application/json', 'X-Api-Key': 'genesis-ai-key' };
const BASE = '/api/client/standing';
const POLL_MS = 30000;

// Shown in the admin preview instead of calling the API.
const SAMPLE = [
  { id: 's1', agentName: 'Client Pulse', kind: 'REPORT', status: 'PENDING', createdAt: new Date(Date.now() - 36e5).toISOString(),
    title: 'Your week: two decisions waiting', summary: 'Your logo draft is ready for review, and the brand book is waiting on payment.',
    body: 'What moved\n- A new logo draft for the Evolved direction is ready.\n\nWaiting on you\n- Approve or reject the logo draft.\n\nNext step\n- Pick the direction to take into the brand book.' },
  { id: 's2', agentName: 'Client Pulse', kind: 'REPORT', status: 'APPROVED', createdAt: new Date(Date.now() - 8 * 864e5).toISOString(),
    decidedAt: new Date(Date.now() - 7 * 864e5).toISOString(), feedback: 'Shorter next time, please.',
    title: 'Your week: brand book delivered', summary: 'The brand book for your chosen direction is ready to download.', body: 'The brand book is ready.' }
];

const STATUS_TEXT = { PENDING: 'Waiting for you', APPROVED: 'Approved', REJECTED: 'Rejected' };

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
  return res.json();
}

function ago(iso) {
  const minutes = Math.round((Date.now() - new Date(iso).getTime()) / 60000);
  if (minutes < 1) return 'just now';
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} h ago`;
  return new Date(iso).toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
}

export function mount(container, widget, ctx = {}) {
  container.classList.add('ainb');
  const simulate = !!ctx.simulate;
  let filter = 'PENDING';
  let items = [];
  let openId = null;
  let pending = 0;

  const panel = el('div', 'ainb-panel');
  const head = el('div', 'ainb-head');
  head.appendChild(el('div', 'ainb-label', 'Inbox'));
  const tabs = el('div', 'ainb-tabs');
  tabs.setAttribute('role', 'group');
  tabs.setAttribute('aria-label', 'Show');
  const tabButtons = [['PENDING', 'Waiting'], ['ALL', 'All']].map(([key, text]) => {
    const b = el('button', 'ainb-tab', text);
    b.type = 'button';
    b.dataset.key = key;
    b.addEventListener('click', () => { filter = key; openId = null; load(); });
    tabs.appendChild(b);
    return b;
  });
  head.appendChild(tabs);
  panel.appendChild(head);
  const list = el('div', 'ainb-list');
  panel.appendChild(list);
  container.appendChild(panel);

  async function load() {
    tabButtons.forEach(b => b.setAttribute('aria-pressed', String(b.dataset.key === filter)));
    if (simulate) {
      items = SAMPLE.filter(i => filter === 'ALL' || i.status === filter);
      pending = SAMPLE.filter(i => i.status === 'PENDING').length;
      render();
      return;
    }
    try {
      const [rows, count] = await Promise.all([call(`/inbox?status=${filter}&limit=30`), call('/inbox/count')]);
      items = rows;
      pending = count.pending;
      render();
    } catch (err) {
      list.replaceChildren(el('div', 'ainb-empty', err.message));
    }
  }

  function render() {
    tabButtons[0].textContent = pending ? `Waiting (${pending})` : 'Waiting';
    if (!items.length) {
      list.replaceChildren(el('div', 'ainb-empty', filter === 'PENDING'
        ? 'Nothing is waiting for you. New results from your agents arrive here.'
        : 'Nothing here yet. Set up an agent and its results arrive here.'));
      return;
    }
    list.replaceChildren(...items.map(renderItem));
  }

  function renderItem(item) {
    const row = el('article', 'ainb-item');
    if (item.status === 'PENDING') row.classList.add('ainb-item-pending');
    const toggle = el('button', 'ainb-item-head');
    toggle.type = 'button';
    toggle.setAttribute('aria-expanded', String(openId === item.id));
    const meta = el('div', 'ainb-meta');
    meta.append(el('span', 'ainb-agent', item.agentName), el('span', null, ' · ' + ago(item.createdAt)));
    const status = el('span', `ainb-status ainb-status-${item.status.toLowerCase()}`, STATUS_TEXT[item.status] || item.status);
    const titleRow = el('div', 'ainb-title-row');
    titleRow.append(el('div', 'ainb-title', item.title), status);
    toggle.append(meta, titleRow);
    if (item.summary) toggle.appendChild(el('div', 'ainb-summary', item.summary));
    toggle.addEventListener('click', () => { openId = openId === item.id ? null : item.id; render(); });
    row.appendChild(toggle);
    if (openId === item.id) row.appendChild(renderDetail(item));
    return row;
  }

  function renderDetail(item) {
    const detail = el('div', 'ainb-detail');
    if (item.body) detail.appendChild(el('div', 'ainb-body', item.body));
    if (item.status !== 'PENDING') {
      if (item.feedback) detail.appendChild(el('div', 'ainb-feedback', `Your note: ${item.feedback}`));
      return detail;
    }
    const note = el('textarea', 'ainb-note');
    note.id = `ainb-note-${item.id}`;
    note.rows = 2;
    note.placeholder = 'Optional note for the agent, e.g. "shorter next time"';
    const label = el('label', 'ainb-note-label', 'Note');
    label.htmlFor = note.id;
    const error = el('div', 'ainb-error');
    const actions = el('div', 'ainb-actions');
    for (const [decision, text] of [['approve', 'Approve'], ['reject', 'Reject']]) {
      const b = el('button', `ainb-btn ainb-btn-${decision}`, text);
      b.type = 'button';
      b.addEventListener('click', async () => {
        actions.querySelectorAll('button').forEach(x => { x.disabled = true; });
        try {
          if (simulate) {
            Object.assign(SAMPLE.find(s => s.id === item.id), { status: decision === 'approve' ? 'APPROVED' : 'REJECTED', feedback: note.value.trim() || null });
          } else {
            await fetch(`${BASE}/inbox/${encodeURIComponent(item.id)}/${decision}`, {
              method: 'POST', credentials: 'include', headers: HEADERS, body: JSON.stringify({ feedback: note.value.trim() || null })
            }).then(async r => { if (!r.ok) throw new Error((await r.json().catch(() => ({}))).message || `Something went wrong (${r.status}).`); });
          }
          openId = null;
          await load();
        } catch (err) {
          error.textContent = err.message;
          actions.querySelectorAll('button').forEach(x => { x.disabled = false; });
        }
      });
      actions.appendChild(b);
    }
    detail.append(label, note, actions, error);
    return detail;
  }

  load();
  if (!simulate) {
    // Results arrive in the background; refresh when the count of waiting items changes.
    const timer = setInterval(async () => {
      if (!container.isConnected) { clearInterval(timer); return; }
      if (document.hidden || openId) return;
      try {
        const { pending: now } = await call('/inbox/count');
        if (now !== pending) load();
      } catch (_) { /* keep the list as it is */ }
    }, POLL_MS);
  }
}
