const HEADERS = { 'X-Api-Key': 'genesis-ai-key' };

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

async function fetchHistory() {
  const res = await fetch('/api/client/consultant/chat/history', { headers: HEADERS });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    const err = new Error(text || `HTTP ${res.status}`);
    err.status = res.status;
    throw err;
  }
  return res.json();
}

async function streamChat(message, onToken) {
  const res = await fetch('/api/client/consultant/chat', {
    method: 'POST',
    headers: { ...HEADERS, 'Content-Type': 'application/json' },
    body: JSON.stringify({ message }),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    const err = new Error(text || `HTTP ${res.status}`);
    err.status = res.status;
    throw err;
  }
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
  bubble.className = `cc-msg cc-msg-${role}`;
  bubble.innerHTML = `<div class="cc-msg-bubble"></div>`;
  bubble.querySelector('.cc-msg-bubble').textContent = text;
  list.appendChild(bubble);
  list.scrollTop = list.scrollHeight;
  return bubble;
}

function renderGate(container, text) {
  container.innerHTML = `<div class="cc-gate">${esc(text)}</div>`;
}

export async function mount(container, agent, ctx) {
  container.className = 'av-view cc-view';
  container.innerHTML = '<div class="av-value">Loading your consultant…</div>';

  let history;
  try {
    history = await fetchHistory();
  } catch (err) {
    if (err.status === 401) {
      renderGate(container, 'Sign in to chat with your consultant.');
    } else if (err.status === 409) {
      renderGate(container, 'Finish your brand journey to chat with your consultant.');
    } else {
      container.innerHTML = `<div class="av-error">Could not load your consultant: ${esc(err.message)}</div>`;
    }
    return;
  }

  container.innerHTML = `
    <div class="cc-messages"></div>
    <form class="cc-composer">
      <input class="cc-input" type="text" placeholder="Ask your consultant about your brand…" autocomplete="off" />
      <button class="cc-send" type="submit">Send</button>
    </form>
  `;
  const list = container.querySelector('.cc-messages');
  const form = container.querySelector('.cc-composer');
  const input = container.querySelector('.cc-input');
  const sendBtn = container.querySelector('.cc-send');

  if (history.length === 0) {
    const empty = document.createElement('div');
    empty.className = 'cc-empty';
    empty.textContent = 'Ask anything about your brand — your consultant knows your tagline, tone, palette, and logo.';
    list.appendChild(empty);
  } else {
    history.forEach(m => appendMessage(list, m.role === 'user' ? 'user' : 'assistant', m.content));
  }

  form.onsubmit = async (e) => {
    e.preventDefault();
    const message = input.value.trim();
    if (!message) return;

    list.querySelector('.cc-empty')?.remove();
    appendMessage(list, 'user', message);
    input.value = '';
    input.disabled = true;
    sendBtn.disabled = true;

    const assistantBubble = appendMessage(list, 'assistant', '');
    const textEl = assistantBubble.querySelector('.cc-msg-bubble');
    let full = '';
    try {
      await streamChat(message, (token) => {
        full += token;
        textEl.textContent = full;
        list.scrollTop = list.scrollHeight;
      });
      if (!full) assistantBubble.remove();
    } catch (err) {
      textEl.textContent = '';
      assistantBubble.classList.add('cc-msg-error');
      textEl.textContent = err.message || 'Something went wrong — please try again.';
    } finally {
      input.disabled = false;
      sendBtn.disabled = false;
      input.focus();
    }
  };

  input.focus();
}
