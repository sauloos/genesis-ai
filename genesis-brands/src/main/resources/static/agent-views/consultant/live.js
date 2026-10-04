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
    <div class="cc-tabs"></div>
    <div class="cc-panel cc-panel-conversation">
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

  const tabsEl = container.querySelector('.cc-tabs');
  const panelConversation = container.querySelector('.cc-panel-conversation');
  const panelHistory = container.querySelector('.cc-panel-history');
  const list = container.querySelector('.cc-messages');
  const form = container.querySelector('.cc-composer');
  const input = container.querySelector('.cc-input');
  const sendBtn = container.querySelector('.cc-send');
  const historyList = container.querySelector('.cc-history-list');
  const newChatBtn = container.querySelector('.cc-new-chat');

  let currentConversationId;   // backing id for the always-present "Current" tab
  let panelConversationId;     // id the shared conversation panel (+ composer) is bound to right now
  let activeTab = 'current';   // 'current' | 'history' | <conversationId> (an opened history tab)
  let openTabs = [];           // [{ conversationId, title }] — dynamically opened history tabs
  let sending = false;         // guards tab switches while a message is in flight

  function renderEmptyState() {
    list.innerHTML = '';
    const empty = document.createElement('div');
    empty.className = 'cc-empty';
    empty.textContent = 'Ask anything about your brand — your consultant knows your tagline, tone, palette, and logo.';
    list.appendChild(empty);
  }

  function tabTitle(conversationId) {
    const found = openTabs.find(t => t.conversationId === conversationId);
    return found ? found.title : 'Conversation';
  }

  function renderTabs() {
    tabsEl.innerHTML = '';
    tabsEl.appendChild(makeTabButton('current', 'Current', false));
    tabsEl.appendChild(makeTabButton('history', 'History', false));
    openTabs.forEach(t => {
      tabsEl.appendChild(makeTabButton(t.conversationId, t.title, true));
    });
  }

  function makeTabButton(key, label, closable) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'cc-tab' + (key === activeTab ? ' active' : '');
    const labelSpan = document.createElement('span');
    labelSpan.className = 'cc-tab-label';
    labelSpan.textContent = label;
    btn.appendChild(labelSpan);
    if (closable) {
      const closeBtn = document.createElement('span');
      closeBtn.className = 'cc-tab-close';
      closeBtn.textContent = '×';
      closeBtn.onclick = (e) => { e.stopPropagation(); closeTab(key); };
      btn.appendChild(closeBtn);
    }
    btn.onclick = () => selectTab(key);
    return btn;
  }

  async function loadConversationPanel(conversationId) {
    panelConversationId = conversationId;
    list.innerHTML = '<div class="cc-empty">Loading…</div>';
    try {
      const messages = await fetchMessages(conversationId);
      // A tab switch may have moved on while this fetch was in flight.
      if (panelConversationId !== conversationId) return;
      list.innerHTML = '';
      if (messages.length === 0) {
        renderEmptyState();
      } else {
        messages.forEach(m => appendMessage(list, m.role === 'user' ? 'user' : 'assistant', m.content));
      }
    } catch (err) {
      if (panelConversationId !== conversationId) return;
      list.innerHTML = `<div class="av-error">Could not load this conversation: ${esc(err.message)}</div>`;
    }
  }

  async function selectTab(key) {
    if (sending) return;
    activeTab = key;
    renderTabs();
    if (key === 'history') {
      panelConversation.hidden = true;
      panelHistory.hidden = false;
      renderHistoryList();
      return;
    }
    panelHistory.hidden = true;
    panelConversation.hidden = false;
    const conversationId = key === 'current' ? currentConversationId : key;
    await loadConversationPanel(conversationId);
  }

  function closeTab(conversationId) {
    if (sending) return;
    openTabs = openTabs.filter(t => t.conversationId !== conversationId);
    if (activeTab === conversationId) {
      selectTab('current');
    } else {
      renderTabs();
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
      if (c.conversationId === currentConversationId || openTabs.some(t => t.conversationId === c.conversationId)) {
        item.classList.add('open');
      }
      item.innerHTML = `<div class="cc-history-title"></div><div class="cc-history-date"></div>`;
      item.querySelector('.cc-history-title').textContent = c.title;
      item.querySelector('.cc-history-date').textContent = formatDate(c.lastMessageAt);
      item.onclick = async () => {
        if (sending) return;
        if (c.conversationId === currentConversationId) {
          await selectTab('current');
          return;
        }
        if (!openTabs.some(t => t.conversationId === c.conversationId)) {
          openTabs.push({ conversationId: c.conversationId, title: c.title });
        }
        await selectTab(c.conversationId);
      };
      historyList.appendChild(item);
    });
  }

  newChatBtn.onclick = () => {
    if (sending) return;
    currentConversationId = crypto.randomUUID();
    panelConversationId = currentConversationId;
    activeTab = 'current';
    renderTabs();
    panelHistory.hidden = true;
    panelConversation.hidden = false;
    renderEmptyState();
    input.focus();
  };

  // Opening the dialog always starts a brand-new chat — past conversations are
  // reached only by opening them from History, never auto-resumed into Current.
  openTabs = [];
  currentConversationId = crypto.randomUUID();
  panelConversationId = currentConversationId;
  renderTabs();
  renderEmptyState();

  form.onsubmit = async (e) => {
    e.preventDefault();
    const message = input.value.trim();
    if (!message || sending) return;

    const conversationId = panelConversationId;
    sending = true;
    list.querySelector('.cc-empty')?.remove();
    appendMessage(list, 'user', message);
    input.value = '';
    input.disabled = true;
    sendBtn.disabled = true;

    const assistantBubble = appendMessage(list, 'assistant', '');
    const textEl = assistantBubble.querySelector('.cc-msg-bubble');
    let full = '';
    try {
      await streamChat(conversationId, message, (token) => {
        full += token;
        textEl.textContent = full;
        list.scrollTop = list.scrollHeight;
      });
      if (!full) assistantBubble.remove();
      // Refresh the conversation list in the background so History reflects this
      // conversation's new/updated title and timestamp next time it's opened.
      conversations = await fetchConversations().catch(() => conversations);
      const openTab = openTabs.find(t => t.conversationId === conversationId);
      if (openTab) {
        const updated = conversations.find(c => c.conversationId === conversationId);
        if (updated) openTab.title = updated.title;
        renderTabs();
      }
    } catch (err) {
      textEl.textContent = '';
      assistantBubble.classList.add('cc-msg-error');
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
