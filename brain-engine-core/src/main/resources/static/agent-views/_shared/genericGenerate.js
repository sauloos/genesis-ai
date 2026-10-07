/**
 * Shared Playground/Live view for the generic CREATE-shaped core agents (Summarizer,
 * RFP Response, Research & Synthesis, Writer) — a one-shot input -> output form with a
 * simple run history. Each agent's own playground.js/live.js is a thin wrapper that just
 * supplies agentId/placeholder copy; the fetch mechanics and markup live here once.
 */
const HEADERS = { 'Content-Type': 'application/json', 'X-Api-Key': 'genesis-ai-key' };

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

async function parseErr(res) {
  const text = await res.text().catch(() => '');
  const err = new Error(text || `HTTP ${res.status}`);
  err.status = res.status;
  return err;
}

async function runPlaygroundGenerate(agentId, input) {
  const res = await fetch(`/api/agent-generation/${agentId}/generate`, {
    method: 'POST', headers: HEADERS, body: JSON.stringify({ input }),
  });
  if (!res.ok) throw await parseErr(res);
  return (await res.json()).output;
}

async function listPlaygroundRuns(agentId) {
  const res = await fetch(`/api/playground/sessions?agentId=${encodeURIComponent(agentId)}`, { headers: HEADERS });
  if (!res.ok) throw await parseErr(res);
  return res.json();
}

async function getPlaygroundRun(id) {
  const res = await fetch(`/api/playground/sessions/${id}`, { headers: HEADERS });
  if (!res.ok) throw await parseErr(res);
  return res.json();
}

async function savePlaygroundRun(agentId, input, output) {
  const res = await fetch('/api/playground/sessions', {
    method: 'POST', headers: HEADERS,
    body: JSON.stringify({ agentId, method: 'default', compare: false, evaluate: false, brief: { input }, result: { output } }),
  });
  if (!res.ok) throw await parseErr(res);
  return res.json();
}

async function deletePlaygroundRun(id) {
  await fetch(`/api/playground/sessions/${id}`, { method: 'DELETE', headers: HEADERS });
}

async function runLiveGenerate(agentId, input, title) {
  const res = await fetch('/api/client/generated-documents', {
    method: 'POST', headers: HEADERS,
    body: JSON.stringify({ agentId, input, title }),
  });
  if (!res.ok) throw await parseErr(res);
  return res.json();
}

async function listLiveDocuments(agentId) {
  const res = await fetch(`/api/client/generated-documents?agentId=${encodeURIComponent(agentId)}`, { headers: HEADERS });
  if (!res.ok) throw await parseErr(res);
  return res.json();
}

function formatDate(iso) {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' }) + ' · ' +
    d.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });
}

function renderGate(container, text) {
  container.innerHTML = `<div class="gca-gate">${esc(text)}</div>`;
}

/**
 * @param {string} opts.mode 'playground' (stateless, PlaygroundSession history) or
 *                            'live' (customer-aware, GeneratedDocument history).
 * @param {() => void} [opts.onGenerated] called after a successful 'live' generation —
 *                            the convention every Live Dashboard view uses to tell the
 *                            Assets widget something new may be available.
 */
export async function mountGenerateView(container, { agentId, mode, placeholder, onGenerated }) {
  container.className = 'gca-view';
  container.innerHTML = '<div class="gca-status">Loading…</div>';

  let history = [];
  try {
    history = mode === 'live' ? await listLiveDocuments(agentId) : await listPlaygroundRuns(agentId);
  } catch (err) {
    if (mode === 'live' && err.status === 401) {
      renderGate(container, 'Sign in to use this.');
      return;
    }
    history = [];
  }

  container.innerHTML = `
    <div class="gca-label">Input</div>
    <textarea class="gca-input" placeholder="${esc(placeholder)}"></textarea>
    <div class="gca-footer">
      <button class="gca-submit" type="button">Generate</button>
      <span class="gca-status"></span>
    </div>
    <div class="gca-result-wrap"></div>
    <div class="gca-history">
      <div class="gca-history-title">Past generations</div>
      <div class="gca-history-list"></div>
    </div>
  `;

  const input = container.querySelector('.gca-input');
  const submit = container.querySelector('.gca-submit');
  const status = container.querySelector('.gca-status');
  const resultWrap = container.querySelector('.gca-result-wrap');
  const historyList = container.querySelector('.gca-history-list');

  function renderResult(text) {
    resultWrap.innerHTML = '<div class="gca-result"></div>';
    resultWrap.querySelector('.gca-result').textContent = text;
  }

  function renderHistory() {
    historyList.innerHTML = '';
    if (history.length === 0) {
      historyList.innerHTML = '<div class="gca-empty">No past generations yet.</div>';
      return;
    }
    history.forEach(item => {
      const row = document.createElement('div');
      row.className = 'gca-history-item';
      const titleText = mode === 'live' ? (item.title || '(untitled)') : 'Generation';
      const dateText = formatDate(item.createdAt);
      row.innerHTML = `<div class="gca-history-item-title"></div><div class="gca-history-item-date"></div>` +
        (mode === 'playground' ? '<div class="gca-history-delete" title="Delete">&times;</div>' : '');
      row.querySelector('.gca-history-item-title').textContent = titleText;
      row.querySelector('.gca-history-item-date').textContent = dateText;
      row.onclick = async (e) => {
        if (e.target.classList.contains('gca-history-delete')) return;
        if (mode === 'playground') {
          const detail = await getPlaygroundRun(item.id);
          input.value = detail.brief?.input || '';
          renderResult(detail.result?.output || '');
        } else {
          renderResult(item.contentMarkdown || '');
        }
      };
      if (mode === 'playground') {
        row.querySelector('.gca-history-delete').onclick = async (e) => {
          e.stopPropagation();
          await deletePlaygroundRun(item.id);
          history = history.filter(h => h.id !== item.id);
          renderHistory();
        };
      }
      historyList.appendChild(row);
    });
  }

  renderHistory();

  submit.onclick = async () => {
    const value = input.value.trim();
    if (!value) return;
    submit.disabled = true;
    status.textContent = 'Generating…';
    resultWrap.innerHTML = '';
    try {
      if (mode === 'live') {
        const doc = await runLiveGenerate(agentId, value, value.slice(0, 60));
        renderResult(doc.contentMarkdown);
        history = [doc, ...history];
        onGenerated?.();
      } else {
        const output = await runPlaygroundGenerate(agentId, value);
        renderResult(output);
        const saved = await savePlaygroundRun(agentId, value, output);
        history = [saved, ...history];
      }
      renderHistory();
    } catch (err) {
      const errEl = document.createElement('div');
      errEl.className = 'gca-error';
      errEl.textContent = err.message || 'Generation failed.';
      resultWrap.appendChild(errEl);
    } finally {
      status.textContent = '';
      submit.disabled = false;
    }
  };
}
