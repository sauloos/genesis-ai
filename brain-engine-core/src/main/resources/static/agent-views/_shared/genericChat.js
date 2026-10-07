/**
 * Shared Playground/Live view for the generic chatBased core agents (Support/FAQ,
 * Policy/Compliance Q&A) — a single ongoing thread with no conversation tabs/switcher
 * (unlike Consultant, which manages many named conversations). `basePath` selects the
 * backend surface: '/api/agent-chat' (Playground, stateless context) or
 * '/api/client/agent-chat' (Live, customer-aware context resolved server-side).
 */
const HEADERS = { 'X-Api-Key': 'genesis-ai-key' };

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

async function parseErr(res) {
  const text = await res.text().catch(() => '');
  const err = new Error(text || `HTTP ${res.status}`);
  err.status = res.status;
  return err;
}

async function fetchHistory(basePath, agentId) {
  const res = await fetch(`${basePath}/${agentId}/history`, { headers: HEADERS });
  if (!res.ok) throw await parseErr(res);
  return res.json();
}

async function streamMessage(basePath, agentId, message, onToken) {
  const res = await fetch(`${basePath}/${agentId}/chat`, {
    method: 'POST',
    headers: { ...HEADERS, 'Content-Type': 'application/json' },
    body: JSON.stringify({ message }),
  });
  if (!res.ok) throw await parseErr(res);
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let sepIdx;
    while ((sepIdx = buffer.indexOf('\n\n')) !== -1) {
      const rawEvent = buffer.slice(0, sepIdx);
      buffer = buffer.slice(sepIdx + 2);
      const dataLines = rawEvent.split('\n')
        .filter(line => line.startsWith('data:'))
        .map(line => line.slice(5).replace(/^ /, ''));
      if (dataLines.length) onToken(dataLines.join('\n'));
    }
  }
}

function appendMessage(list, role, text) {
  const bubble = document.createElement('div');
  bubble.className = `gha-msg gha-msg-${role}`;
  bubble.innerHTML = '<div class="gha-msg-bubble"></div>';
  bubble.querySelector('.gha-msg-bubble').textContent = text;
  list.appendChild(bubble);
  list.scrollTop = list.scrollHeight;
  return bubble;
}

function renderGate(container, text) {
  container.innerHTML = `<div class="gha-gate">${esc(text)}</div>`;
}

export async function mountChatView(container, { agentId, basePath, emptyText, placeholder }) {
  container.className = 'gha-view';
  container.innerHTML = '<div class="gha-gate">Loading…</div>';

  let history;
  try {
    history = await fetchHistory(basePath, agentId);
  } catch (err) {
    if (err.status === 401) {
      renderGate(container, 'Sign in to chat.');
    } else {
      container.innerHTML = `<div class="gha-gate">Could not load chat: ${esc(err.message)}</div>`;
    }
    return;
  }

  container.innerHTML = `
    <div class="gha-messages"></div>
    <form class="gha-composer">
      <input class="gha-input" type="text" placeholder="${esc(placeholder)}" autocomplete="off" />
      <button class="gha-send" type="submit">Send</button>
    </form>
  `;

  const list = container.querySelector('.gha-messages');
  const form = container.querySelector('.gha-composer');
  const input = container.querySelector('.gha-input');
  const sendBtn = container.querySelector('.gha-send');
  let sending = false;

  if (history.length === 0) {
    const empty = document.createElement('div');
    empty.className = 'gha-empty';
    empty.textContent = emptyText;
    list.appendChild(empty);
  } else {
    history.forEach(m => appendMessage(list, m.role === 'user' ? 'user' : 'assistant', m.content));
  }

  form.onsubmit = async (e) => {
    e.preventDefault();
    const message = input.value.trim();
    if (!message || sending) return;

    sending = true;
    list.querySelector('.gha-empty')?.remove();
    appendMessage(list, 'user', message);
    input.value = '';
    input.disabled = true;
    sendBtn.disabled = true;

    const assistantBubble = appendMessage(list, 'assistant', '');
    const textEl = assistantBubble.querySelector('.gha-msg-bubble');
    let full = '';
    try {
      await streamMessage(basePath, agentId, message, (token) => {
        full += token;
        textEl.textContent = full;
        list.scrollTop = list.scrollHeight;
      });
      if (!full) assistantBubble.remove();
    } catch (err) {
      textEl.textContent = '';
      assistantBubble.classList.add('gha-msg-error');
      textEl.textContent = err.message || 'Something went wrong — please try again.';
    } finally {
      sending = false;
      input.disabled = false;
      sendBtn.disabled = false;
      input.focus();
    }
  };

  input.focus();
}
