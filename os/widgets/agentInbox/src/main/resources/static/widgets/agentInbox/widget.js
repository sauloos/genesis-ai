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
    title: 'Your week: brand book delivered', summary: 'The brand book for your chosen direction is ready to download.', body: 'The brand book is ready.' },
  { id: 's3', agentName: 'Content', kind: 'DRAFT_POST', status: 'PENDING', createdAt: new Date(Date.now() - 2 * 36e5).toISOString(),
    title: 'Why we roast light', summary: 'Explains the house style ahead of the new single-origin launch.',
    payload: { format: 'Carousel', pillar: 'Education', proposedFor: new Date(Date.now() + 2 * 864e5).toISOString().slice(0, 10),
      variants: [
        { channel: 'instagram', label: 'Instagram', text: 'Light roasts taste of the farm, not the fire.\n\nWe stop early so you can taste the fruit and the place it grew.', hashtags: ['#specialtycoffee', '#lightroast', '#coffeeorigin'] },
        { channel: 'linkedin', label: 'LinkedIn', text: 'Most roasters chase consistency by roasting dark. We chose the harder path.\n\nRoasting light keeps what the farmer worked for.', hashtags: ['#coffee'] }] },
    attachments: [{ index: 0, name: 'frame-1.png' }, { index: 1, name: 'frame-2.png' }] },
  { id: 's4', agentName: 'Brand guardian', kind: 'REPORT', status: 'PENDING', createdAt: new Date(Date.now() - 5 * 36e5).toISOString(),
    title: 'Brand check: 74/100 — Voice is right, the site drifts off-palette',
    summary: 'Your posts sound like you; the website uses two colours that are not in your palette. Swap the button orange first.',
    body: 'Overall: 74/100.',
    payload: { scorecard: { score: 74,
      dimensions: [
        { name: 'Voice and tone', score: 88, note: 'Warm and plain-spoken, as the tone guide asks.' },
        { name: 'Messaging', score: 79, note: 'The farm-first story comes through; the home page hero is generic.' },
        { name: 'Colours', score: 58, note: 'Two off-palette colours carry the main buttons and links.' },
        { name: 'Typography', score: 70, note: 'Headlines use the brand serif; body text falls back to Arial.' }],
      findings: [
        { severity: 'high', area: 'Colours', where: 'https://northwind.example/', issue: 'The main buttons use an orange that is not in the palette.',
          evidence: '#ff7a00 (31 uses)', fix: 'Use Roast Brown #6b3e26 for buttons and Crema #f3e3c3 for their text.' },
        { severity: 'medium', area: 'Messaging', where: 'https://northwind.example/', issue: 'The hero line could belong to any coffee shop.',
          evidence: '"The best coffee in town"', fix: 'Lead with the tagline: "Light roasts that taste of the farm."' },
        { severity: 'low', area: 'Typography', where: 'https://northwind.example/menu', issue: 'Body text is set in Arial instead of Inter.',
          evidence: 'font-family: Arial', fix: 'Load Inter and set it as the body font.' }],
      highlights: ['Instagram captions follow the tone guide closely (Instagram · @northwindcoffee · 2 Oct).'],
      quickFixes: ['Replace #ff7a00 with #6b3e26 in the site theme settings.'],
      sources: [
        { label: 'Website', detail: 'https://northwind.example/', ok: true, result: 'Northwind Coffee Roasters' },
        { label: 'Social', detail: 'Instagram · @northwindcoffee', ok: true, result: '10 posts' },
        { label: 'Website', detail: 'https://northwind.example/shop', ok: false, result: 'could not be read: the site answered with status 404.' }] } } },
  { id: 's5', agentName: 'Occasions', kind: 'DRAFT_POST', status: 'PENDING', createdAt: new Date(Date.now() - 3 * 36e5).toISOString(),
    title: 'Black Friday: Gifts that taste of the farm', summary: 'Gift-buying starts here, and a bag of fresh beans is an easy gift.',
    payload: { format: 'Single image', pillar: 'Occasion', proposedFor: '2026-11-22',
      occasion: { name: 'Black Friday', date: '2026-11-27', why: 'Gift-buying starts here.' },
      variants: [{ channel: 'instagram', label: 'Instagram', text: 'The best gifts smell like a Saturday morning.\n\nOur light roasts come gift-wrapped all weekend. [your offer]', hashtags: ['#coffeegifts', '#blackfriday'] }] },
    attachments: [{ index: 0, name: 'post.png', contentType: 'image/png' }] },
  { id: 's6', agentName: 'Occasions', kind: 'ASSET', status: 'PENDING', createdAt: new Date(Date.now() - 3 * 36e5).toISOString(),
    title: 'Web banner, print flyer for Black Friday', summary: 'For Fri 27 Nov. Download it for your website, shop window or print.',
    body: 'Headline: Gifts that taste of the farm',
    payload: { occasion: { name: 'Black Friday', date: '2026-11-27', why: 'Gift-buying starts here.' }, headline: 'Gifts that taste of the farm',
      copy: 'Find your gift in store this weekend.',
      assets: [{ name: 'banner.png', label: 'Web banner', size: '1536×1024' }, { name: 'flyer.png', label: 'Print flyer', size: '1024×1536' }] },
    attachments: [{ index: 0, name: 'banner.png', contentType: 'image/png' }, { index: 1, name: 'flyer.png', contentType: 'image/png' }] },
  { id: 's7', agentName: 'Market watch', kind: 'REPORT', status: 'PENDING', createdAt: new Date(Date.now() - 6 * 36e5).toISOString(),
    title: 'Market watch: Rival Roasters cut their subscription to $18',
    summary: 'One competitor dropped its monthly price below yours; the others were quiet.',
    body: 'One competitor dropped its monthly price below yours.',
    payload: { brief: { headline: 'Rival Roasters cut their subscription to $18',
      competitors: [
        { name: 'Rival Roasters', url: 'https://rival.example/pricing', status: 'changed',
          changes: ['Monthly subscription now $18, was $22.', 'New line: "Free grinder with a 6-month plan".'],
          meaning: 'Undercuts your $20 plan by $2 for price-led shoppers.' },
        { name: 'Bean & Co', url: 'https://beanco.example/', status: 'quiet', changes: [] }],
      implications: ['Shoppers comparing on price now see you as the dearer option.'],
      moves: [{ action: 'Lead with roast-to-order freshness on your subscription page', why: 'It is the one thing their plan cannot match.' }],
      watchNext: ['Whether the $18 price is a launch offer or permanent.'],
      sources: [
        { label: 'Rival Roasters', detail: 'https://rival.example/pricing', ok: true, result: 'changed' },
        { label: 'Bean & Co', detail: 'https://beanco.example/', ok: true, result: 'unchanged' },
        { label: 'Oak Coffee', detail: 'https://oak.example/', ok: false, result: 'could not be read: the site answered with status 403.' }] } } }
];

// Publishing options for an approved post in the admin preview.
const SAMPLE_PUBLISH = {
  ready: true, approved: true, images: 2, proposedFor: SAMPLE[2].payload.proposedFor,
  variants: [
    { index: 0, channel: 'instagram', label: 'Instagram', accounts: [{ id: 'a1', displayName: '@northwindcoffee', networkLabel: 'Instagram' }] },
    { index: 1, channel: 'linkedin', label: 'LinkedIn', accounts: [{ id: 'a2', displayName: 'Northwind Coffee', networkLabel: 'LinkedIn' }] }
  ],
  jobs: []
};

const JOB_TEXT = { QUEUED: 'Scheduled', RUNNING: 'Publishing', PUBLISHED: 'Published', FAILED: 'Failed', CANCELLED: 'Cancelled' };

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

function when(iso) {
  return new Date(iso).toLocaleString(undefined, { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
}

// <input type="datetime-local"> wants local time without a zone.
function localInputValue(date) {
  const pad = n => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
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
    const post = item.kind === 'DRAFT_POST' && item.payload && Array.isArray(item.payload.variants) && item.payload.variants.length;
    const card = !post && item.kind === 'REPORT' && item.payload && item.payload.scorecard;
    const brief = !post && item.kind === 'REPORT' && item.payload && item.payload.brief;
    const pack = item.kind === 'ASSET' && item.payload && Array.isArray(item.payload.assets) && item.payload.assets.length;
    if (post) detail.appendChild(renderPost(item));
    else if (card) detail.appendChild(renderScorecard(card, item.body));
    else if (brief) detail.appendChild(renderBrief(brief, item.body));
    else if (pack) detail.appendChild(renderAssets(item));
    else if (item.body) detail.appendChild(el('div', 'ainb-body', item.body));
    if (!post && !pack && item.attachments && item.attachments.length) detail.appendChild(renderFiles(item));
    if (item.status !== 'PENDING') {
      if (item.feedback) detail.appendChild(el('div', 'ainb-feedback', `Your note: ${item.feedback}`));
      if (post && item.status === 'APPROVED') detail.appendChild(renderPublish(item));
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

  // A drafted social post: when and what, its images, then one tab per channel with copyable text.
  // A REPORT that scores something: the score, each area's score, then the fixes ranked.
  function renderScorecard(card, body) {
    const wrap = el('div', 'ainb-card');
    if (typeof card.score === 'number') {
      const band = card.score >= 90 ? ['On brand', 'good'] : card.score >= 70 ? ['Mostly on brand', 'good']
        : card.score >= 50 ? ['Needs work', 'warn'] : ['Off brand', 'bad'];
      const head = el('div', 'ainb-card-head');
      const score = el('div', `ainb-score ainb-score-${band[1]}`);
      score.append(el('span', 'ainb-score-num', String(card.score)), el('span', 'ainb-score-of', '/100'));
      head.append(score, el('span', `ainb-band ainb-band-${band[1]}`, band[0]));
      wrap.appendChild(head);
    } else if (body) {
      wrap.appendChild(el('div', 'ainb-body', body));
    }

    const dims = Array.isArray(card.dimensions) ? card.dimensions : [];
    if (dims.length) {
      const list = el('ul', 'ainb-dims');
      dims.forEach(d => {
        const row = el('li', 'ainb-dim');
        const top = el('div', 'ainb-dim-top');
        top.append(el('span', 'ainb-dim-name', d.name), el('span', 'ainb-dim-score', String(d.score)));
        const meter = el('div', 'ainb-meter');
        meter.setAttribute('role', 'img');
        meter.setAttribute('aria-label', `${d.name}: ${d.score} out of 100`);
        const fill = el('div', `ainb-meter-fill ainb-meter-${d.score >= 70 ? 'good' : d.score >= 50 ? 'warn' : 'bad'}`);
        fill.style.width = Math.max(0, Math.min(100, Number(d.score) || 0)) + '%';
        meter.appendChild(fill);
        row.append(top, meter);
        if (d.note) row.appendChild(el('div', 'ainb-dim-note', d.note));
        list.appendChild(row);
      });
      wrap.appendChild(list);
    }

    const findings = Array.isArray(card.findings) ? card.findings : [];
    if (findings.length) {
      wrap.appendChild(el('div', 'ainb-card-label', 'What to fix'));
      const list = el('ol', 'ainb-findings');
      findings.forEach(f => {
        const row = el('li', 'ainb-finding');
        const top = el('div', 'ainb-finding-top');
        top.append(el('span', `ainb-sev ainb-sev-${f.severity || 'medium'}`, f.severity || 'medium'), el('span', 'ainb-finding-issue', f.issue));
        row.appendChild(top);
        if (f.where) row.appendChild(el('div', 'ainb-finding-where', [f.area, f.where].filter(Boolean).join(' · ')));
        if (f.evidence) row.appendChild(labelled('Seen', f.evidence));
        if (f.fix) row.appendChild(labelled('Fix', f.fix));
        list.appendChild(row);
      });
      wrap.appendChild(list);
    }
    bullets(wrap, "What's working", card.highlights);
    bullets(wrap, 'Quick fixes', card.quickFixes);

    const sources = Array.isArray(card.sources) ? card.sources : [];
    if (sources.length) {
      wrap.appendChild(el('div', 'ainb-card-label', 'Checked'));
      const list = el('ul', 'ainb-sources');
      sources.forEach(s => {
        const row = el('li', s.ok ? 'ainb-source' : 'ainb-source ainb-source-failed');
        row.append(el('span', 'ainb-source-detail', `${s.label} · ${s.detail}`), el('span', 'ainb-source-result', s.result || ''));
        list.appendChild(row);
      });
      wrap.appendChild(list);
    }
    return wrap;
  }

  function labelled(label, text) {
    const line = el('div', 'ainb-finding-line');
    line.append(el('span', 'ainb-finding-key', label + ' '), document.createTextNode(text));
    return line;
  }

  function bullets(wrap, label, items) {
    if (!Array.isArray(items) || !items.length) return;
    wrap.appendChild(el('div', 'ainb-card-label', label));
    const list = el('ul', 'ainb-bullets');
    items.forEach(t => list.appendChild(el('li', null, t)));
    wrap.appendChild(list);
  }

  function renderPost(item) {
    const p = item.payload;
    const wrap = el('div', 'ainb-post');
    const facts = el('div', 'ainb-post-facts');
    if (p.occasion && p.occasion.name) facts.appendChild(occasionChip(p.occasion));
    if (p.proposedFor) {
      const day = new Date(p.proposedFor + 'T12:00:00');
      facts.appendChild(el('span', 'ainb-chip', 'For ' + day.toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short' })));
    }
    if (p.format) facts.appendChild(el('span', 'ainb-chip', p.format));
    if (p.pillar) facts.appendChild(el('span', 'ainb-chip', p.pillar));
    wrap.appendChild(facts);

    const frames = (item.attachments || []).filter(a => !a.contentType || a.contentType.startsWith('image/'));
    if (frames.length) {
      const strip = el('div', 'ainb-frames');
      frames.forEach((a, n) => strip.appendChild(frame(a, `Image ${n + 1}`, `${item.title}, image ${n + 1} of ${frames.length}`)));
      wrap.appendChild(strip);
    }

    const variants = p.variants;
    let current = 0;
    const channelTabs = el('div', 'ainb-channels');
    channelTabs.setAttribute('role', 'group');
    channelTabs.setAttribute('aria-label', 'Channel');
    const text = el('div', 'ainb-body ainb-post-text');
    const tags = el('div', 'ainb-hashtags');
    const copy = el('button', 'ainb-btn ainb-btn-small', 'Copy text');
    copy.type = 'button';
    const tabsByIndex = variants.map((v, n) => {
      const b = el('button', 'ainb-tab', v.label || v.channel);
      b.type = 'button';
      b.addEventListener('click', () => { current = n; show(); });
      channelTabs.appendChild(b);
      return b;
    });
    function full(v) {
      return v.text + (v.hashtags && v.hashtags.length ? '\n\n' + v.hashtags.join(' ') : '');
    }
    function show() {
      const v = variants[current];
      tabsByIndex.forEach((b, n) => b.setAttribute('aria-pressed', String(n === current)));
      text.textContent = v.text;
      tags.textContent = (v.hashtags || []).join(' ');
      copy.textContent = 'Copy text';
    }
    copy.addEventListener('click', async () => {
      try {
        await navigator.clipboard.writeText(full(variants[current]));
        copy.textContent = 'Copied';
      } catch (_) {
        copy.textContent = 'Select the text to copy';
      }
    });
    if (variants.length > 1) wrap.appendChild(channelTabs);
    wrap.append(text, tags);
    const tools = el('div', 'ainb-post-tools');
    tools.appendChild(copy);
    if (!simulate) {
      const zip = el('a', 'ainb-btn ainb-btn-small', 'Download post');
      zip.href = `${BASE}/inbox/${encodeURIComponent(item.id)}/download`;
      zip.setAttribute('download', '');
      tools.appendChild(zip);
    }
    wrap.appendChild(tools);
    show();
    return wrap;
  }

  // An image that opens full size, or a labelled placeholder in the preview.
  function frame(a, placeholder, alt) {
    if (simulate || !a.url) return el('div', 'ainb-frame ainb-frame-placeholder', placeholder);
    const link = el('a', 'ainb-frame');
    link.href = a.url;
    link.target = '_blank';
    link.rel = 'noopener';
    const img = el('img');
    img.src = a.url;
    img.alt = alt;
    img.loading = 'lazy';
    link.appendChild(img);
    return link;
  }

  function occasionChip(o) {
    let text = o.name;
    if (o.date) text += ' · ' + new Date(o.date + 'T12:00:00').toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short' });
    return el('span', 'ainb-chip ainb-chip-occasion', text);
  }

  // Ready-made files for one occasion (banner, flyer, story): a preview and a download for each.
  function renderAssets(item) {
    const p = item.payload;
    const wrap = el('div', 'ainb-post');
    if (p.occasion && p.occasion.name) {
      const facts = el('div', 'ainb-post-facts');
      facts.appendChild(occasionChip(p.occasion));
      wrap.appendChild(facts);
    }
    if (p.headline) wrap.appendChild(labelled('Headline', p.headline));
    const byName = new Map((item.attachments || []).map(a => [a.name, a]));
    const grid = el('div', 'ainb-assets');
    p.assets.forEach(asset => {
      const a = byName.get(asset.name);
      if (!a) return;
      const cell = el('figure', 'ainb-asset');
      const [w, h] = String(asset.size || '').split(/[×x]/).map(Number);
      const shot = frame(a, asset.label, `${asset.label} for ${p.occasion ? p.occasion.name : item.title}`);
      if (w && h) shot.style.aspectRatio = `${w} / ${h}`;
      const caption = el('figcaption', 'ainb-asset-caption');
      caption.append(el('span', 'ainb-asset-label', asset.label), el('span', 'ainb-asset-size', asset.size || ''));
      if (!simulate && a.url) {
        const down = el('a', 'ainb-btn ainb-btn-small', 'Download');
        down.href = a.url + '?download=true';
        down.setAttribute('download', '');
        caption.appendChild(down);
      }
      cell.append(shot, caption);
      grid.appendChild(cell);
    });
    wrap.appendChild(grid);
    if (p.copy) {
      wrap.appendChild(el('div', 'ainb-card-label', 'Flyer text'));
      wrap.appendChild(el('div', 'ainb-body', p.copy));
    }
    return wrap;
  }

  // A REPORT that briefs on competitors: each competitor's changes, what they mean, then what to do.
  function renderBrief(brief, body) {
    const wrap = el('div', 'ainb-card');
    if (brief.quiet) {
      if (body) wrap.appendChild(el('div', 'ainb-body', body));
    } else {
      const competitors = Array.isArray(brief.competitors) ? brief.competitors : [];
      if (competitors.length) {
        const list = el('ul', 'ainb-rivals');
        competitors.forEach(c => {
          const row = el('li', 'ainb-rival');
          const top = el('div', 'ainb-finding-top');
          const status = c.status || 'changed';
          top.append(el('span', `ainb-sev ainb-rival-${status}`, status === 'new' ? 'first look' : status), el('span', 'ainb-finding-issue', c.name));
          row.appendChild(top);
          if (c.url) row.appendChild(el('div', 'ainb-finding-where', c.url));
          if (Array.isArray(c.changes) && c.changes.length) {
            const changes = el('ul', 'ainb-bullets');
            c.changes.forEach(t => changes.appendChild(el('li', null, t)));
            row.appendChild(changes);
          }
          if (c.meaning) row.appendChild(labelled('For you', c.meaning));
          list.appendChild(row);
        });
        wrap.appendChild(list);
      } else if (body) {
        wrap.appendChild(el('div', 'ainb-body', body));
      }
      bullets(wrap, 'What it means for you', brief.implications);
      const moves = Array.isArray(brief.moves) ? brief.moves : [];
      if (moves.length) {
        wrap.appendChild(el('div', 'ainb-card-label', 'Moves to consider'));
        const list = el('ol', 'ainb-findings');
        moves.forEach(m => {
          const row = el('li', 'ainb-finding');
          row.appendChild(el('div', 'ainb-finding-issue', m.action));
          if (m.why) row.appendChild(el('div', 'ainb-dim-note', m.why));
          list.appendChild(row);
        });
        wrap.appendChild(list);
      }
      bullets(wrap, 'Watch next', brief.watchNext);
    }
    const sources = Array.isArray(brief.sources) ? brief.sources : [];
    if (sources.length) {
      wrap.appendChild(el('div', 'ainb-card-label', 'Pages read'));
      const list = el('ul', 'ainb-sources');
      sources.forEach(s => {
        const row = el('li', s.ok ? 'ainb-source' : 'ainb-source ainb-source-failed');
        row.append(el('span', 'ainb-source-detail', `${s.label} · ${s.detail}`), el('span', 'ainb-source-result', s.result || ''));
        list.appendChild(row);
      });
      wrap.appendChild(list);
    }
    return wrap;
  }

  // Publish an approved post to the client's connected accounts, now or later. The section
  // removes itself when this app has no publishing (404) or publishing is not set up.
  function renderPublish(item) {
    const section = el('section', 'ainb-publish');
    section.appendChild(el('div', 'ainb-publish-label', 'Publish'));
    const body = el('div', 'ainb-publish-body');
    section.appendChild(body);
    const path = `/inbox/${encodeURIComponent(item.id)}/publish`;

    async function refresh() {
      let options;
      if (simulate) {
        options = SAMPLE_PUBLISH;
      } else {
        const res = await fetch(BASE + path, { credentials: 'include', headers: HEADERS });
        if (res.status === 404) { section.remove(); return; }
        if (!res.ok) { body.replaceChildren(el('div', 'ainb-error', `Publishing options could not be loaded (${res.status}).`)); return; }
        options = await res.json();
      }
      if (!options.ready) { section.remove(); return; }
      draw(options);
    }

    function draw(options) {
      const nodes = [];
      const jobs = options.jobs || [];
      if (jobs.length) nodes.push(renderJobs(jobs));

      const choices = [];
      const targets = el('div', 'ainb-targets');
      options.variants.forEach(v => {
        v.accounts.forEach(a => {
          const id = `ainb-pub-${item.id}-${v.index}-${a.id}`;
          const row = el('label', 'ainb-target');
          row.htmlFor = id;
          const box = el('input');
          box.type = 'checkbox';
          box.id = id;
          const busy = jobs.some(j => j.accountId === a.id && ['QUEUED', 'RUNNING', 'PUBLISHED'].includes(j.status));
          box.disabled = busy;
          box.checked = !busy;
          row.append(box, el('span', 'ainb-target-name', a.displayName), el('span', 'ainb-target-net', v.label));
          targets.appendChild(row);
          choices.push({ box, variant: v.index, accountId: a.id });
        });
      });
      if (!choices.length) {
        nodes.push(el('div', 'ainb-publish-hint', 'Connect a social account for '
          + options.variants.map(v => v.label).join(' or ') + ' to publish this post from here.'));
        body.replaceChildren(...nodes);
        return;
      }
      if (choices.every(c => c.box.disabled)) {
        body.replaceChildren(...nodes);
        return;
      }
      nodes.push(targets);

      const timeId = `ainb-pub-at-${item.id}`;
      const timeLabel = el('label', 'ainb-note-label', 'When');
      timeLabel.htmlFor = timeId;
      const time = el('input', 'ainb-time');
      time.type = 'datetime-local';
      time.id = timeId;
      if (options.proposedFor) {
        const proposed = new Date(options.proposedFor + 'T10:00:00');
        if (proposed.getTime() > Date.now()) time.value = localInputValue(proposed);
      }
      time.min = localInputValue(new Date());
      const timeRow = el('div', 'ainb-time-row');
      timeRow.append(timeLabel, time);
      nodes.push(timeRow);

      const error = el('div', 'ainb-error');
      const actions = el('div', 'ainb-actions');
      const now = el('button', 'ainb-btn', 'Publish now');
      const later = el('button', 'ainb-btn ainb-btn-approve', 'Schedule');
      now.type = later.type = 'button';
      const sync = () => { later.disabled = !time.value; };
      time.addEventListener('input', sync);
      sync();
      async function go(scheduled) {
        const chosen = choices.filter(c => c.box.checked && !c.box.disabled);
        if (!chosen.length) { error.textContent = 'Choose at least one account.'; return; }
        const at = scheduled ? new Date(time.value).toISOString() : null;
        error.textContent = '';
        now.disabled = later.disabled = true;
        try {
          if (simulate) {
            chosen.forEach(c => {
              const v = SAMPLE_PUBLISH.variants[c.variant];
              const a = v.accounts.find(x => x.id === c.accountId);
              SAMPLE_PUBLISH.jobs.push({ id: 'j' + Math.random().toString(36).slice(2), accountId: a.id, accountName: a.displayName,
                networkLabel: v.label, status: scheduled ? 'QUEUED' : 'PUBLISHED', scheduledAt: at || new Date().toISOString(),
                url: scheduled ? null : 'https://example.com/post', createdAt: new Date().toISOString() });
            });
          } else {
            const res = await fetch(BASE + path, { method: 'POST', credentials: 'include', headers: HEADERS,
              body: JSON.stringify({ targets: chosen.map(c => ({ variant: c.variant, accountId: c.accountId })), at }) });
            if (!res.ok) throw new Error((await res.json().catch(() => ({}))).message || `Something went wrong (${res.status}).`);
          }
          await refresh();
        } catch (err) {
          error.textContent = err.message;
          now.disabled = false;
          sync();
        }
      }
      now.addEventListener('click', () => go(false));
      later.addEventListener('click', () => go(true));
      actions.append(now, later);
      if (options.images) nodes.push(el('div', 'ainb-publish-hint', `With ${options.images} image${options.images === 1 ? '' : 's'}. Posts go out within a minute of their time.`));
      nodes.push(actions, error);
      body.replaceChildren(...nodes);
    }

    function renderJobs(jobs) {
      const list = el('ul', 'ainb-jobs');
      jobs.forEach(j => {
        const li = el('li', 'ainb-job');
        const what = el('div', 'ainb-job-what');
        what.append(el('span', 'ainb-target-name', j.accountName || j.networkLabel), el('span', 'ainb-target-net', j.networkLabel));
        const state = el('div', `ainb-job-state ainb-job-${j.status.toLowerCase()}`);
        let text = JOB_TEXT[j.status] || j.status;
        if (j.status === 'QUEUED') text += ' for ' + when(j.scheduledAt);
        if (j.status === 'PUBLISHED' && j.finishedAt) text += ' ' + when(j.finishedAt);
        state.textContent = text;
        if (j.status === 'FAILED' && j.error) state.appendChild(el('div', 'ainb-job-error', j.error));
        li.append(what, state);
        const tools = el('div', 'ainb-job-tools');
        if (j.url) {
          const link = el('a', 'ainb-btn ainb-btn-small', 'View post');
          link.href = j.url;
          link.target = '_blank';
          link.rel = 'noopener';
          tools.appendChild(link);
        }
        if (j.status === 'QUEUED') {
          const cancel = el('button', 'ainb-btn ainb-btn-small', 'Cancel');
          cancel.type = 'button';
          cancel.addEventListener('click', async () => {
            cancel.disabled = true;
            try {
              if (simulate) {
                SAMPLE_PUBLISH.jobs.find(x => x.id === j.id).status = 'CANCELLED';
              } else {
                const res = await fetch(`${BASE}${path}/${encodeURIComponent(j.id)}`, { method: 'DELETE', credentials: 'include', headers: HEADERS });
                if (!res.ok) throw new Error((await res.json().catch(() => ({}))).message || `Something went wrong (${res.status}).`);
              }
              await refresh();
            } catch (err) {
              cancel.disabled = false;
              state.appendChild(el('div', 'ainb-job-error', err.message));
            }
          });
          tools.appendChild(cancel);
        }
        if (tools.childNodes.length) li.appendChild(tools);
        list.appendChild(li);
      });
      return list;
    }

    refresh().catch(err => body.replaceChildren(el('div', 'ainb-error', err.message)));
    return section;
  }

  function renderFiles(item) {
    const files = el('div', 'ainb-post-tools');
    for (const a of item.attachments) {
      if (simulate || !a.url) continue;
      const link = el('a', 'ainb-btn ainb-btn-small', a.name);
      link.href = a.url + '?download=true';
      link.setAttribute('download', '');
      files.appendChild(link);
    }
    return files;
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
