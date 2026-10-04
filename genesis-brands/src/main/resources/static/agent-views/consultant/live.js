const HEADERS = { 'X-Api-Key': 'genesis-ai-key' };

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

async function fetchJson(url) {
  const res = await fetch(url, { headers: HEADERS });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    const err = new Error(text || `HTTP ${res.status}`);
    err.status = res.status;
    throw err;
  }
  return res.json();
}

function fetchConversations() {
  return fetchJson('/api/client/consultant/conversations');
}

function fetchMessages(conversationId) {
  return fetchJson(`/api/client/consultant/conversations/${encodeURIComponent(conversationId)}/messages`);
}

async function streamChat(conversationId, message, onToken) {
  const res = await fetch('/api/client/consultant/chat', {
    method: 'POST',
    headers: { ...HEADERS, 'Content-Type': 'application/json' },
    body: JSON.stringify({ conversationId, message }),
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

function formatDate(iso) {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' }) + ' · ' +
    d.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });
}

export async function mount(container, agent, ctx) {
  container.className = 'av-view cc-view';
  container.innerHTML = '<div class="av-value">Loading your consultant…</div>';

  // Fetching conversations first both doubles as the auth/brand-readiness gate check
  // (401/409) and seeds the History tab — no separate "am I allowed in" call needed.
  let conversations;
  try {
    conversations = await fetchConversations();
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
    <div class="cc-tabs">
      <button class="cc-tab cc-tab-current active" type="button">Current</button>
      <button class="cc-tab cc-tab-history" type="button">History</button>
    </div>
    <div class="cc-panel cc-panel-current">
      <div class="cc-messages"></div>
      <form class="cc-composer">
        <input class="cc-input" type="text" placeholder="Ask your consultant about your brand…" autocomplete="off" />
        <button class="cc-send" type="submit">Send</button>
      </form>
    </div>
    <div class="cc-panel cc-panel-history" hidden>
      <div class="cc-history-toolbar">
        <button class="cc-new-chat" type="button">New chat</button>
      </div>
      <div class="cc-history-list"></div>
    </div>
  `;

  const tabCurrent = container.querySelector('.cc-tab-current');
  const tabHistory = container.querySelector('.cc-tab-history');
  const panelCurrent = container.querySelector('.cc-panel-current');
  const panelHistory = container.querySelector('.cc-panel-history');
  const list = container.querySelector('.cc-messages');
  const form = container.querySelector('.cc-composer');
  const input = container.querySelector('.cc-input');
  const sendBtn = container.querySelector('.cc-send');
  const historyList = container.querySelector('.cc-history-list');
  const newChatBtn = container.querySelector('.cc-new-chat');

  let currentConversationId;

  function showTab(tab) {
    const isCurrent = tab === 'current';
    tabCurrent.classList.toggle('active', isCurrent);
    tabHistory.classList.toggle('active', !isCurrent);
    panelCurrent.hidden = !isCurrent;
    panelHistory.hidden = isCurrent;
  }

  function renderEmptyState() {
    list.innerHTML = '';
    const empty = document.createElement('div');
    empty.className = 'cc-empty';
    empty.textContent = 'Ask anything about your brand — your consultant knows your tagline, tone, palette, and logo.';
    list.appendChild(empty);
  }

  async function loadConversation(conversationId) {
    currentConversationId = conversationId;
    list.innerHTML = '<div class="cc-empty">Loading…</div>';
    try {
      const messages = await fetchMessages(conversationId);
      list.innerHTML = '';
      if (messages.length === 0) {
        renderEmptyState();
      } else {
        messages.forEach(m => appendMessage(list, m.role === 'user' ? 'user' : 'assistant', m.content));
      }
    } catch (err) {
      list.innerHTML = `<div class="av-error">Could not load this conversation: ${esc(err.message)}</div>`;
    }
  }

  function renderHistoryList() {
    historyList.innerHTML = '';
    if (conversations.length === 0) {
      const empty = document.createElement('div');
      empty.className = 'cc-empty';
      empty.textContent = 'No past conversations yet.';
      historyList.appendChild(empty);
      return;
    }
    conversations.forEach(c => {
      const item = document.createElement('button');
      item.type = 'button';
      item.className = 'cc-history-item';
      if (c.conversationId === currentConversationId) item.classList.add('active');
      item.innerHTML = `<div class="cc-history-title"></div><div class="cc-history-date"></div>`;
      item.querySelector('.cc-history-title').textContent = c.title;
      item.querySelector('.cc-history-date').textContent = formatDate(c.lastMessageAt);
      item.onclick = async () => {
        await loadConversation(c.conversationId);
        showTab('current');
      };
      historyList.appendChild(item);
    });
  }

  tabCurrent.onclick = () => showTab('current');
  tabHistory.onclick = () => { renderHistoryList(); showTab('history'); };

  newChatBtn.onclick = () => {
    currentConversationId = crypto.randomUUID();
    renderEmptyState();
    showTab('current');
    input.focus();
  };

  if (conversations.length > 0) {
    await loadConversation(conversations[0].conversationId);
  } else {
    currentConversationId = crypto.randomUUID();
    renderEmptyState();
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
      await streamChat(currentConversationId, message, (token) => {
        full += token;
        textEl.textContent = full;
        list.scrollTop = list.scrollHeight;
      });
      if (!full) assistantBubble.remove();
      // Refresh the conversation list in the background so History reflects this
      // conversation's new/updated title and timestamp next time it's opened.
      conversations = await fetchConversations().catch(() => conversations);
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
