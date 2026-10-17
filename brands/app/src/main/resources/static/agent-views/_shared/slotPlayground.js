/**
 * Shared Playground runner for the Genesis Brands SLOT agents (Copy, Visual Identity, Logo,
 * Playbook, Brand Book). Playground has no owning Engagement, so this view owns the brief:
 * questionnaire-driven derivation (or a manual brief), creative direction, run options
 * (A/B compare, evaluation loop, the agent's declared custom options), the run itself,
 * Current/History/Audit results and training feedback capture.
 *
 * Each agent's own playground.js is a thin wrapper supplying only what is genuinely
 * agent-specific — how to render its output, which upstream runs it assembles from, any
 * extra actions (e.g. Brand Book's PDF download). The host page (playground.html) is pure
 * chrome: it mounts this through agent-loader.js like any other agent's Playground view.
 *
 * @param {HTMLElement} container
 * @param {object} config
 * @param {string} config.agentId
 * @param {string} config.resultField   field of a DirectionOutput holding this agent's output (Audit tab)
 * @param {(o: object, h: object) => string} config.renderOutput   HTML for one output
 * @param {(o: object, h: object) => string} [config.renderAudit]  compact HTML for an Audit row
 * @param {{field: string, agentId: string, label: string}[]} [config.sourceRuns]
 *        upstream Playground runs assembled into the request payload as { brief, [field]: output }
 * @param {Object<string, (btn: HTMLElement, h: object) => any>} [config.actions]
 *        handlers for data-action buttons the agent's own renderOutput emits
 * @param {{sessionId?: string}} [ctx]  sessionId deep-links straight to a History run
 */
const KEY = 'genesis-ai-key';
const HEADERS = { 'Content-Type': 'application/json', 'X-Api-Key': KEY };
const DIRECTIONS = ['ANCHORED', 'EVOLVED', 'DISRUPTIVE'];

// Sample answers for the "Brand Discovery — Quick Start" questionnaire (Groundwork example).
// Keyed by question UUID — gracefully skipped if IDs change.
const SAMPLE_ANSWERS = {
  '76515d36-4618-45d9-a742-20c751cd3053': 'Groundwork builds project management software for small construction businesses — site logs, client updates, and job tracking without the admin overhead.',
  'c53faefc-816b-4c4e-a136-457bcf6a7ed1': 'I spent years on construction sites watching projects fall apart because of WhatsApp threads and paper forms. Builders were drowning in admin while the actual work suffered. There was no software built for how trades actually work — so I built it.',
  '0ae69c18-9da2-4095-9fbc-d2a81ddd5ac9': 'Technology',
  '662d3f01-0ec4-4fbd-9ec5-f49253a82f5a': ['Corporate', 'Cheap'],
  'f64f85fb-d009-4c8e-a818-c3096a9917a5': 60,
};

const DEFAULT_BRIEF = {
  name: 'Groundwork',
  industry: 'Specialty coffee roaster',
  audience: 'Urban professionals aged 25-40 who value craft and provenance over convenience',
  offer: 'Small-batch roasted specialty coffee, subscription and retail',
  diff: 'Exceptional coffee without the snobbery',
  personality: 'direct, unpretentious, confident',
  tone: 'bold, plainspoken, a little irreverent',
  training: 'Lean into bold, unexpected language. Avoid coffee cliches like "awakens your senses" or "perfect blend".\nThis brand challenges the idea that good coffee has to be precious or intimidating.',
  context: 'Groundwork is entering a market saturated with pretentious third-wave coffee brands. Their angle: exceptional coffee without the snobbery.',
};

const METHOD_LABELS = { DALLE: 'DALL-E image', SVG_CONCEPT: 'SVG concept', IDEOGRAM: 'Ideogram' };

let mountSeq = 0;

export function esc(s) {
  if (s === null || s === undefined || s === '') return '';
  return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

export function block(label, value, isTagline) {
  if (value === null || value === undefined || value === '') return '';
  return `
    <div class="slp-block${isTagline ? ' slp-tagline' : ''}">
      <div class="slp-label">${esc(label)}</div>
      <div class="slp-value">${esc(value)}</div>
    </div>`;
}

export function bulletBlock(label, items) {
  if (!items || !items.length) return '';
  return `
    <div class="slp-block">
      <div class="slp-label">${esc(label)}</div>
      <ul class="slp-bullets">${items.map(a => `<li>${esc(a)}</li>`).join('')}</ul>
    </div>`;
}

/** A framed visual asset; images inside it are offered for attachment in feedback. */
export function assetFrame(innerHtml) {
  return `<div class="slp-asset-frame">${innerHtml}</div>`;
}

/** Unwraps a saved session's result to the Genesis AI output (method fan-out / compare / eval). */
export function extractGenesisOutput(session, result) {
  let r = result;
  if (session.method === 'BOTH' && r && r.methods && r.results) r = r.results[0];
  if (session.compareMode) r = r.genesisAi;
  if (session.evaluateMode) r = r.output;
  return r;
}

async function api(path, opts = {}) {
  const res = await fetch(path, { ...opts, headers: { ...HEADERS, ...(opts.headers || {}) } });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    let message = null;
    try { message = JSON.parse(text).message; } catch (_) { /* not JSON */ }
    throw new Error(message || `HTTP ${res.status}: ${text.slice(0, 200)}`);
  }
  return res.status === 204 ? null : res.json();
}

export async function mountSlotPlayground(container, config, ctx = {}) {
  const { agentId } = config;
  const uid = `slp${++mountSeq}`;
  const sourceRuns = config.sourceRuns || [];

  const state = {
    meta: {},
    direction: 'DISRUPTIVE',
    methods: [],            // selected values of the 'method' custom option (max 2)
    methodOption: null,
    derivedBriefs: null,
    questionnaireId: null,
    sessionId: null,        // current saved PlaygroundSession (feedback attaches to it)
    runAssets: [],
    lastRunArgs: null,
    lastInput: null,        // last full request payload (brief + assembled source outputs)
    recorder: null,
  };

  const helpers = {
    esc, block, bulletBlock, assetFrame,
    getDirection: () => state.direction,
    getLastInput: () => state.lastInput,
    headers: HEADERS,
  };

  container.innerHTML = `
    <div class="slp-view">
      <div class="slp-tabs">
        <button class="slp-tab active" data-action="view" data-view="parameters">Parameters</button>
        <button class="slp-tab" data-action="view" data-view="response">Response<span class="slp-dot" data-el="dot" hidden></span></button>
      </div>

      <div data-el="parameters">
        <div class="slp-intro">
          <h2 class="slp-title">${esc(agentId)}</h2>
          <div class="slp-sub" data-el="sub"></div>
        </div>

        ${sourceRuns.length ? `
        <div class="slp-section">
          <div class="slp-section-title">Source runs</div>
          <div class="slp-hint">The accepted ${esc(sourceRuns.map(s => s.label).join(' / '))} runs to assemble from.</div>
          <div class="slp-grid">
            ${sourceRuns.map(s => `
              <div class="slp-field">
                <label for="${uid}-src-${esc(s.field)}">${esc(s.label)} run</label>
                <select id="${uid}-src-${esc(s.field)}" data-source="${esc(s.field)}" data-source-agent="${esc(s.agentId)}"></select>
              </div>`).join('')}
          </div>
        </div>` : ''}

        <div class="slp-section" data-el="questionnaire">
          <div class="slp-q-selector" data-el="q-selector-row" hidden>
            <label for="${uid}-q-selector">Questionnaire</label>
            <select id="${uid}-q-selector" data-el="q-selector"></select>
          </div>
          <div class="slp-hint" data-el="q-none" hidden>No questionnaire found — fill in the brand context manually below.</div>
          <div data-el="q-loaded" hidden>
            <div class="slp-section-head">
              <span class="slp-section-title" data-el="q-title">Questionnaire</span>
              <a href="#" class="slp-link" data-action="toggle-manual" data-el="q-mode-link">Enter manually instead</a>
            </div>
            <div data-el="q-questions"></div>
            <div class="slp-row">
              <button class="slp-btn slp-btn-success" data-action="derive">Derive directions</button>
              <span class="slp-status" data-el="derive-status"></span>
            </div>
          </div>
          <div data-el="derived" hidden>
            <div class="slp-hint">Directions derived — select one to populate the brief below:</div>
            <div class="slp-foundation" data-el="foundation" hidden>
              <div class="slp-foundation-title">Brand foundation</div>
              <div class="slp-foundation-grid" data-el="foundation-grid"></div>
            </div>
            <div class="slp-segmented">
              ${DIRECTIONS.map(d => `<button class="slp-seg" data-action="derived-dir" data-dir="${d}">${d.charAt(0) + d.slice(1).toLowerCase()}</button>`).join('')}
            </div>
            <div class="slp-hint" data-el="derived-summary"></div>
            <a href="#" class="slp-link" data-action="reset-derived">↩ Re-answer questionnaire</a>
          </div>
        </div>

        <div class="slp-section">
          <div class="slp-hint slp-italic" data-el="brief-source">Example brief — edit as needed or derive from the questionnaire above.</div>
          <div class="slp-grid">
            ${field('name', 'Brand name', DEFAULT_BRIEF.name)}
            ${field('industry', 'Industry', DEFAULT_BRIEF.industry)}
            ${field('audience', 'Target audience', DEFAULT_BRIEF.audience, true)}
            ${field('offer', 'Core offer', DEFAULT_BRIEF.offer, true)}
            ${field('diff', 'Differentiator', DEFAULT_BRIEF.diff, true)}
            ${field('personality', 'Personality (comma-separated)', DEFAULT_BRIEF.personality)}
            ${field('tone', 'Tone', DEFAULT_BRIEF.tone)}
            <div class="slp-field slp-full">
              <label>Creative direction</label>
              <div class="slp-segmented" data-el="directions">
                ${DIRECTIONS.map(d => `<button class="slp-seg${d === state.direction ? ' active' : ''}" data-action="direction" data-dir="${d}">${d.charAt(0) + d.slice(1).toLowerCase()}</button>`).join('')}
              </div>
            </div>
            <div class="slp-field slp-full">
              <label for="${uid}-f-training">Training instructions (one per line)</label>
              <textarea id="${uid}-f-training" data-f="training">${esc(DEFAULT_BRIEF.training)}</textarea>
            </div>
            <div class="slp-field slp-full">
              <label for="${uid}-f-context">Additional context</label>
              <textarea id="${uid}-f-context" data-f="context">${esc(DEFAULT_BRIEF.context)}</textarea>
            </div>
          </div>
        </div>

        <div class="slp-section" data-el="options">
          <div class="slp-section-title">Run options</div>
          <div class="slp-options">
            <div class="slp-option" data-el="method-wrap" hidden>
              <div class="slp-label">Generation method <span class="slp-hint-inline">(pick up to 2)</span></div>
              <div class="slp-segmented" data-el="methods"></div>
            </div>
            <div data-el="custom-options"></div>
            <label class="slp-option slp-check" data-el="compare-wrap" hidden>
              <input type="checkbox" id="${uid}-compare" data-el="compare">
              <span><strong>A/B compare vs. plain AI</strong><br>
              Runs the same brief through Genesis AI's orchestrated agent side-by-side with a plain model call given only the raw facts.</span>
            </label>
            <label class="slp-option slp-check">
              <input type="checkbox" id="${uid}-eval" data-el="evaluate">
              <span><strong>Evaluation loop</strong><br>
              Genesis AI reviews the draft against the agent's rubric and sends it back for revision until accepted (or the iteration budget runs out).</span>
            </label>
          </div>
        </div>

        <div class="slp-run-row">
          <button class="slp-btn slp-btn-primary" data-action="run" data-el="run-btn">Run agent</button>
          <span class="slp-status" data-el="run-status"></span>
          <span class="slp-error" data-el="run-error"></span>
        </div>
      </div>

      <div data-el="response" hidden>
        <div class="slp-subtabs">
          <button class="slp-subtab active" data-action="results-tab" data-tab="current">Current</button>
          <button class="slp-subtab" data-action="results-tab" data-tab="history">History</button>
          <button class="slp-subtab" data-action="results-tab" data-tab="audit">Audit</button>
        </div>
        <div data-el="tab-current">
          <div class="slp-empty" data-el="results-empty">Run the agent to see output here.</div>
          <div class="slp-results" data-el="results" hidden></div>
        </div>
        <div data-el="tab-history" hidden>
          <div data-el="history-list"></div>
          <div data-el="history-detail" hidden></div>
        </div>
        <div data-el="tab-audit" hidden>
          <div data-el="audit-list"></div>
          <div data-el="audit-detail" hidden></div>
        </div>
      </div>
    </div>`;

  function field(key, label, value, full) {
    return `<div class="slp-field${full ? ' slp-full' : ''}">
      <label for="${uid}-f-${key}">${esc(label)}</label>
      <input id="${uid}-f-${key}" type="text" data-f="${key}" value="${esc(value)}">
    </div>`;
  }

  const root = container.querySelector('.slp-view');
  const el = name => root.querySelector(`[data-el="${name}"]`);
  const f = key => root.querySelector(`[data-f="${key}"]`);

  // ── Event wiring (delegated — no page globals) ─────────────────────────────
  root.addEventListener('click', e => {
    const btn = e.target.closest('[data-action]');
    if (!btn || !root.contains(btn)) return;
    const action = btn.dataset.action;
    if (btn.tagName === 'A') e.preventDefault();
    switch (action) {
      case 'view': return switchView(btn.dataset.view);
      case 'results-tab': return switchResultsTab(btn.dataset.tab);
      case 'direction': return setDirection(btn.dataset.dir);
      case 'derived-dir': return selectDerivedDir(btn.dataset.dir);
      case 'derive': return deriveDirections(btn);
      case 'toggle-manual': return toggleManual();
      case 'reset-derived': return resetDerived();
      case 'method': return toggleMethod(btn);
      case 'run': return runAgent();
      case 'history-open': return openHistoryItem(btn.dataset.id);
      case 'history-delete': e.stopPropagation(); return deleteHistoryItem(btn.dataset.id);
      case 'history-back': return closeHistoryDetail();
      case 'audit-open': return openAuditItem(btn.dataset.id);
      case 'audit-back': el('audit-list').hidden = false; el('audit-detail').hidden = true; return;
      case 'mark-paid': return markPaid(btn.dataset.id);
      case 'feedback': return toggleFeedbackPanel(btn);
      case 'fb-verdict': return setFbVerdict(btn);
      case 'fb-submit': return submitFeedback(btn.closest('.slp-fb-panel'), null);
      case 'fb-record': return toggleRecording(btn);
      default: {
        const handler = config.actions && config.actions[action];
        if (handler) return handler(btn, helpers);
      }
    }
  });
  el('q-selector').addEventListener('change', e => switchQuestionnaire(e.target.value));

  // ── Catalog meta (display name, A/B, custom options) ───────────────────────
  try {
    const agents = await api('/api/admin/agents');
    state.meta = agents.find(a => a.agentId === agentId) || {};
  } catch (_) { state.meta = {}; }
  root.querySelector('.slp-title').textContent = state.meta.displayName || agentId;
  el('sub').textContent = state.meta.description || '';
  el('compare-wrap').hidden = !(state.meta.supportsABCompare && state.meta.abCompareEnabled);
  renderCustomOptions(state.meta.customOptions || []);

  if (sourceRuns.length) loadSourceRuns();

  if (ctx.sessionId) {
    switchView('response');
    await switchResultsTab('history');
    try {
      await openHistoryItem(ctx.sessionId);
    } catch (err) {
      console.warn('Saved run not found:', ctx.sessionId, err);
    }
  }
  loadQuestionnaire();

  // ── Tabs ───────────────────────────────────────────────────────────────────
  function switchView(view) {
    root.querySelectorAll('.slp-tab').forEach(b => b.classList.toggle('active', b.dataset.view === view));
    el('parameters').hidden = view !== 'parameters';
    el('response').hidden = view !== 'response';
    if (view === 'response') el('dot').hidden = true;
  }

  async function switchResultsTab(tab) {
    root.querySelectorAll('.slp-subtab').forEach(b => b.classList.toggle('active', b.dataset.tab === tab));
    el('tab-current').hidden = tab !== 'current';
    el('tab-history').hidden = tab !== 'history';
    el('tab-audit').hidden = tab !== 'audit';
    if (tab === 'history') await loadHistory();
    if (tab === 'audit') await loadAudit();
  }

  function markResponseReady() {
    if (el('response').hidden) el('dot').hidden = false;
  }

  // ── Custom options ─────────────────────────────────────────────────────────
  // The 'method' option drives an up-to-2 multi-select that fans out into parallel runs;
  // any other declared option renders as a single-value field sent under `options`.
  function renderCustomOptions(options) {
    const methodOption = options.find(o => o.key === 'method' && o.type === 'SELECT');
    state.methodOption = methodOption || null;
    if (methodOption) {
      const def = methodOption.defaultValue || methodOption.allowedValues[0];
      state.methods = [def];
      el('methods').innerHTML = methodOption.allowedValues.map(v =>
        `<button class="slp-seg${v === def ? ' active' : ''}" data-action="method" data-value="${esc(v)}">${esc(METHOD_LABELS[v] || v)}</button>`
      ).join('');
      el('method-wrap').hidden = false;
    }
    el('custom-options').innerHTML = options.filter(o => o !== methodOption).map(o => {
      const id = `${uid}-opt-${esc(o.key)}`;
      if (o.type === 'BOOLEAN') {
        return `<label class="slp-option slp-check"><input type="checkbox" id="${id}" data-key="${esc(o.key)}" ${o.defaultValue === 'true' ? 'checked' : ''}>
          <span>${esc(o.label)}</span></label>`;
      }
      const input = o.type === 'SELECT'
        ? `<select id="${id}" data-key="${esc(o.key)}">${(o.allowedValues || []).map(v =>
            `<option value="${esc(v)}"${v === o.defaultValue ? ' selected' : ''}>${esc(v)}</option>`).join('')}</select>`
        : `<input type="text" id="${id}" data-key="${esc(o.key)}" value="${esc(o.defaultValue || '')}">`;
      return `<div class="slp-option slp-field"><label for="${id}">${esc(o.label)}</label>${input}</div>`;
    }).join('');
  }

  function collectCustomOptions() {
    const result = {};
    el('custom-options').querySelectorAll('[data-key]').forEach(input => {
      result[input.dataset.key] = input.type === 'checkbox' ? input.checked : input.value;
    });
    return result;
  }

  function toggleMethod(btn) {
    const val = btn.dataset.value;
    if (btn.classList.contains('active')) {
      if (state.methods.length > 1) {
        state.methods = state.methods.filter(m => m !== val);
        btn.classList.remove('active');
      }
      return;
    }
    if (state.methods.length >= 2) {
      const removed = state.methods.shift();
      el('methods').querySelector(`[data-value="${CSS.escape(removed)}"]`)?.classList.remove('active');
    }
    state.methods.push(val);
    btn.classList.add('active');
  }

  // ── Brief ──────────────────────────────────────────────────────────────────
  function setDirection(dir) {
    state.direction = dir;
    el('directions').querySelectorAll('.slp-seg').forEach(b => b.classList.toggle('active', b.dataset.dir === dir));
    if (state.derivedBriefs && state.derivedBriefs[dir]) populateFromBrief(state.derivedBriefs[dir]);
  }

  function buildBrief() {
    return {
      engagementId: 'playground-' + Date.now(),
      brand: {
        name: f('name').value.trim(),
        industry: f('industry').value.trim(),
        targetAudience: f('audience').value.trim(),
        coreOffer: f('offer').value.trim(),
        differentiator: f('diff').value.trim(),
        personality: f('personality').value.split(',').map(s => s.trim()).filter(Boolean),
        tone: f('tone').value.trim(),
      },
      direction: state.direction,
      trainingInstructions: f('training').value.split('\n').map(s => s.trim()).filter(Boolean),
      additionalContext: f('context').value.trim(),
    };
  }

  function populateFromBrief(brief) {
    const b = brief.brand || {};
    const fd = brief.foundation || {};
    f('name').value = b.name || '';
    f('industry').value = b.industry || '';
    f('audience').value = fd.targetAudiencePersona || b.targetAudience || '';
    f('offer').value = b.coreOffer || '';
    f('diff').value = fd.differentiator || b.differentiator || '';
    f('personality').value = (b.personality || []).join(', ');
    f('tone').value = b.tone || '';
    f('training').value = (brief.trainingInstructions || []).join('\n');
    f('context').value = brief.additionalContext || '';
  }

  // ── Questionnaire-driven brief derivation ──────────────────────────────────
  async function loadQuestionnaire() {
    try {
      const all = await api('/api/questionnaires');
      if (all.length) {
        el('q-selector').innerHTML = all.map(q =>
          `<option value="${esc(q.id)}">${esc(q.title)}${q.status !== 'PUBLISHED' ? ' (draft)' : ''}</option>`).join('');
        el('q-selector-row').hidden = false;
      }
      const live = all.find(q => q.status === 'PUBLISHED') || all[0];
      if (!live) throw new Error('no questionnaire');
      el('q-selector').value = live.id;
      await loadQuestionnaireById(live.id);
    } catch (_) {
      el('q-none').hidden = false;
    }
  }

  async function loadQuestionnaireById(id) {
    const data = await api(`/api/questionnaires/${id}`);
    state.questionnaireId = data.questionnaire.id;
    el('q-title').textContent = data.questionnaire.title || 'Client questionnaire';
    renderQuestions(data.questions);
    el('q-loaded').hidden = false;
  }

  async function switchQuestionnaire(id) {
    state.derivedBriefs = null;
    el('derived').hidden = true;
    el('q-loaded').hidden = true;
    await loadQuestionnaireById(id);
  }

  function renderQuestions(questions) {
    el('q-questions').innerHTML = questions.map(q => {
      let config = {};
      try { config = q.configJson ? JSON.parse(q.configJson) : {}; } catch (_) { /* ignore */ }
      const sample = SAMPLE_ANSWERS[q.id];
      const name = `${uid}-q-${q.id}`;
      let input;
      if (q.type === 'LONG_TEXT') {
        input = `<textarea data-q="${esc(q.id)}" data-qtype="text" placeholder="${esc(q.helpText || '')}">${esc(sample ?? '')}</textarea>`;
      } else if (q.type === 'SLIDER') {
        const min = config.min ?? 0, max = config.max ?? 100;
        const val = sample ?? Math.round((min + max) / 2);
        input = `<input type="range" data-q="${esc(q.id)}" data-qtype="text" min="${min}" max="${max}" value="${esc(val)}">
          <div class="slp-hint">${esc(config.minLabel || min)} ← → ${esc(config.maxLabel || max)}</div>`;
      } else if (q.type === 'SINGLE_SELECT' || q.type === 'MULTI_SELECT') {
        const multi = q.type === 'MULTI_SELECT';
        const chosen = multi ? (Array.isArray(sample) ? sample : []) : [sample ?? (config.options || [])[0]];
        input = (config.options || []).map(opt => `
          <label class="slp-check-inline"><input type="${multi ? 'checkbox' : 'radio'}" name="${name}" data-q="${esc(q.id)}"
            data-qtype="${multi ? 'multi' : 'single'}" value="${esc(opt)}" ${chosen.includes(opt) ? 'checked' : ''}> ${esc(opt)}</label>`).join('');
      } else {
        input = `<input type="text" data-q="${esc(q.id)}" data-qtype="text" placeholder="${esc(q.helpText || '')}" value="${esc(sample ?? '')}">`;
      }
      return `<div class="slp-q-item">
        <div class="slp-q-label">${esc(q.orderIndex)}. ${esc(q.prompt)}</div>
        ${q.helpText ? `<div class="slp-hint">${esc(q.helpText)}</div>` : ''}
        ${input}
      </div>`;
    }).join('');
  }

  function collectAnswers() {
    const byQ = new Map();
    el('q-questions').querySelectorAll('[data-q]').forEach(input => {
      const qId = input.dataset.q;
      const type = input.dataset.qtype;
      if (type === 'multi') {
        if (!byQ.has(qId)) byQ.set(qId, []);
        if (input.checked) byQ.get(qId).push(input.value);
      } else if (type === 'single') {
        if (input.checked) byQ.set(qId, input.value);
      } else {
        byQ.set(qId, input.value.trim() || input.placeholder || '');
      }
    });
    return Array.from(byQ, ([questionId, v]) => ({ questionId, valueJson: JSON.stringify(v) }));
  }

  async function deriveDirections(btn) {
    const status = el('derive-status');
    btn.disabled = true;
    status.textContent = 'Calling Brain Engine…';
    try {
      const data = await api('/api/playground/derive-briefs', {
        method: 'POST', body: JSON.stringify({ questionnaireId: state.questionnaireId, answers: collectAnswers() }),
      });
      state.derivedBriefs = {};
      data.briefs.forEach(b => { state.derivedBriefs[b.direction] = b; });
      const fd = data.briefs[0] && data.briefs[0].foundation;
      if (fd) {
        el('foundation-grid').innerHTML = [
          ['Differentiator', fd.differentiator], ['Audience', fd.targetAudiencePersona],
          ['Positioning', fd.corePositioning], ['Tone spectrum', fd.toneSpectrum],
        ].map(([k, v]) => `<span class="slp-foundation-key">${k}</span><span class="slp-foundation-val">${esc(v || '')}</span>`).join('');
        el('foundation').hidden = false;
      }
      el('q-loaded').hidden = true;
      el('derived').hidden = false;
      el('brief-source').textContent = 'Populated from questionnaire derivation — edit as needed.';
      selectDerivedDir(state.derivedBriefs.DISRUPTIVE ? 'DISRUPTIVE' : state.derivedBriefs.EVOLVED ? 'EVOLVED' : 'ANCHORED');
      status.textContent = '';
    } catch (err) {
      status.textContent = 'Error: ' + err.message;
    } finally {
      btn.disabled = false;
    }
  }

  function selectDerivedDir(dir) {
    el('derived').querySelectorAll('.slp-seg').forEach(b => b.classList.toggle('active', b.dataset.dir === dir));
    setDirection(dir);
    const b = state.derivedBriefs && state.derivedBriefs[dir];
    if (b) el('derived-summary').textContent = `${b.brand.name} · ${b.brand.industry} · ${(b.additionalContext || '').substring(0, 120)}`;
  }

  function toggleManual() {
    const manual = !el('q-loaded').hidden;
    el('q-loaded').hidden = manual;
    el('brief-source').textContent = manual ? 'Manual brief — edit as needed.'
      : 'Example brief — edit as needed or derive from the questionnaire above.';
    el('q-mode-link').textContent = manual ? '← Back to questionnaire' : 'Enter manually instead';
    if (manual) {
      // keep the link reachable while the questions are hidden
      el('questionnaire').insertBefore(el('q-mode-link'), el('q-loaded'));
    } else {
      el('q-loaded').querySelector('.slp-section-head').appendChild(el('q-mode-link'));
    }
  }

  function resetDerived() {
    state.derivedBriefs = null;
    el('derived').hidden = true;
    el('foundation').hidden = true;
    el('q-loaded').hidden = false;
    el('brief-source').textContent = 'Example brief — edit as needed or derive from the questionnaire above.';
  }

  // ── Source runs (assembly agents) ──────────────────────────────────────────
  async function loadSourceRuns() {
    for (const s of sourceRuns) {
      const sel = root.querySelector(`[data-source="${CSS.escape(s.field)}"]`);
      sel.innerHTML = '<option>Loading…</option>';
      const sessions = await api(`/api/playground/sessions?agentId=${encodeURIComponent(s.agentId)}`).catch(() => []);
      sel.innerHTML = sessions.length
        ? sessions.map(x => `<option value="${esc(x.id)}">${esc(x.label)} — ${new Date(x.createdAt).toLocaleString()}</option>`).join('')
        : '<option value="">No runs yet</option>';
    }
  }

  async function resolveSourceInput(brief, ids) {
    const details = await Promise.all(sourceRuns.map(s => api(`/api/playground/sessions/${ids[s.field]}`)));
    const input = { brief };
    sourceRuns.forEach((s, i) => { input[s.field] = extractGenesisOutput(details[i].session, details[i].result); });
    return input;
  }

  function selectedSourceIds() {
    const ids = {};
    sourceRuns.forEach(s => { ids[s.field] = root.querySelector(`[data-source="${CSS.escape(s.field)}"]`).value; });
    if (sourceRuns.some(s => !ids[s.field])) {
      throw new Error(`Select a ${sourceRuns.map(s => s.label).join(', ')} run to assemble from.`);
    }
    return ids;
  }

  // ── Run ────────────────────────────────────────────────────────────────────
  async function runAgent() {
    const btn = el('run-btn');
    const status = el('run-status');
    const errorEl = el('run-error');
    const compare = !el('compare-wrap').hidden && el('compare').checked;
    const evaluate = el('evaluate').checked;
    const runningLabel = compare ? 'Running both agents…'
      : evaluate ? 'Running evaluation loop… (can take a few minutes for image generation)' : 'Running…';

    btn.disabled = true;
    errorEl.textContent = '';
    status.innerHTML = `<span class="slp-spinner"></span>${runningLabel}`;
    const emptyEl = el('results-empty');
    const resultsEl = el('results');
    emptyEl.innerHTML = `<span class="slp-spinner"></span> ${runningLabel}`;
    emptyEl.hidden = false;
    resultsEl.hidden = true;
    switchView('response');
    switchResultsTab('current');

    const brief = buildBrief();
    const methods = state.methodOption ? state.methods.slice() : [null];
    const endpoint = `/api/agents/${agentId}/` + (compare
      ? (evaluate ? 'compare-with-evaluation' : 'compare')
      : (evaluate ? 'execute-with-evaluation' : 'execute'));

    state.sessionId = null;
    state.runAssets = [];
    state.lastRunArgs = null;

    const t0 = performance.now();
    try {
      let payload = brief;
      let briefToSave = brief;
      if (sourceRuns.length) {
        const ids = selectedSourceIds();
        payload = await resolveSourceInput(brief, ids);
        briefToSave = { ...brief, sourceRuns: ids };
      }
      state.lastInput = payload;
      const customOptions = collectCustomOptions();
      if (Object.keys(customOptions).length) payload = { ...payload, options: customOptions };

      const results = await Promise.all(methods.map(m => api(endpoint, {
        method: 'POST', body: JSON.stringify(m ? { brief: payload, method: m } : payload),
      })));
      status.textContent = `Done in ${((performance.now() - t0) / 1000).toFixed(1)}s`;
      emptyEl.hidden = true;
      resultsEl.innerHTML = renderResults(methods, results, compare, evaluate);
      resultsEl.hidden = false;
      state.runAssets = collectRunAssets(resultsEl);
      state.lastRunArgs = { brief: briefToSave, methods, results, compare, evaluate };
      await saveSession(state.lastRunArgs);
    } catch (err) {
      status.textContent = '';
      errorEl.textContent = err.message || String(err);
      emptyEl.textContent = `Something went wrong: ${err.message || err}`;
      emptyEl.hidden = false;
    } finally {
      btn.disabled = false;
      markResponseReady();
    }
  }

  async function saveSession({ brief, methods, results, compare, evaluate }) {
    try {
      const method = state.methodOption ? (methods.length > 1 ? 'BOTH' : methods[0]) : null;
      const result = methods.length > 1 ? { methods, results } : results[0];
      const saved = await api('/api/playground/sessions', {
        method: 'POST', body: JSON.stringify({ agentId, method, compare, evaluate, brief, result }),
      });
      state.sessionId = saved.id;
    } catch (err) {
      // history/feedback capture is best-effort — a save failure shouldn't disrupt the run
      console.warn('Playground session save failed:', err);
    }
  }

  // ── Rendering ──────────────────────────────────────────────────────────────
  function renderResults(methods, results, compare, evaluate) {
    const one = r => compare ? renderCompare(r, evaluate) : renderSingle(r, evaluate);
    if (methods.length > 1) {
      return methods.map((m, i) => `
        <div class="slp-method-section">
          <div class="slp-section-title">${esc(METHOD_LABELS[m] || m)}</div>
          <div class="slp-cols${compare ? ' dual' : ''}">${one(results[i])}</div>
        </div>`).join('');
    }
    return `<div class="slp-cols${compare ? ' dual' : ''}">${one(results[0])}</div>`;
  }

  function renderSingle(data, evaluate) {
    return genesisCard(evaluate ? data.output : data, null, evaluate ? data.rounds : null, evaluate ? data.accepted : null);
  }

  function renderCompare(r, evaluate) {
    const g = evaluate ? r.genesisAi.output : r.genesisAi;
    return genesisCard(g, r.genesisAiMs, evaluate ? r.genesisAi.rounds : null, evaluate ? r.genesisAi.accepted : null)
      + baselineCard(r.baselineAi, r.baselineAiMs);
  }

  function latencyHtml(ms) {
    return ms != null ? `<span class="slp-latency">${(ms / 1000).toFixed(1)}s</span>` : '';
  }

  function genesisCard(o, ms, rounds, accepted) {
    const badge = rounds
      ? (accepted
        ? `<span class="slp-badge slp-badge-ok">Accepted after ${rounds.length} round${rounds.length > 1 ? 's' : ''}</span>`
        : `<span class="slp-badge slp-badge-warn">Budget exhausted (${rounds.length} rounds)</span>`)
      : '';
    return `
      <div class="slp-card slp-card-genesis">
        <div class="slp-card-head">
          <span class="slp-card-title">GENESIS AI — Brain-orchestrated${badge}</span>
          <span class="slp-card-actions">${latencyHtml(ms)}${feedbackButton(o, 'Genesis AI')}</span>
        </div>
        <div class="slp-card-body">
          ${config.renderOutput(o || {}, helpers)}
          ${o && o.reasoning ? `<div class="slp-block"><div class="slp-label">Reasoning</div><div class="slp-reasoning">${esc(o.reasoning)}</div></div>` : ''}
          ${evalTrail(rounds)}
          <div class="slp-fb-slot"></div>
        </div>
      </div>`;
  }

  function baselineCard(o, ms) {
    return `
      <div class="slp-card slp-card-baseline">
        <div class="slp-card-head">
          <span class="slp-card-title">GENERIC AI REQUEST</span>
          <span class="slp-card-actions">${latencyHtml(ms)}${feedbackButton(o, 'Generic AI')}</span>
        </div>
        <div class="slp-card-body">
          ${config.renderOutput(o || {}, helpers)}
          <div class="slp-fb-slot"></div>
        </div>
      </div>`;
  }

  function evalTrail(rounds) {
    if (!rounds || !rounds.length) return '';
    return `
      <div class="slp-block">
        <div class="slp-label">Evaluation trail — Genesis AI review</div>
        ${rounds.map(r => {
          const v = r.evaluation.verdict;
          const issues = ((r.evaluation.revision && r.evaluation.revision.specificIssues) || []).map(esc).join(' · ');
          return `<div class="slp-round">
            <div class="slp-round-head ${v === 'ACCEPT' ? 'accept' : 'revise'}">Round ${esc(r.iteration)} — ${esc(v)}</div>
            <div class="slp-round-summary">${esc(r.evaluation.summary)}</div>
            ${issues ? `<div class="slp-round-issues">${issues}</div>` : ''}
          </div>`;
        }).join('')}
      </div>`;
  }

  function collectRunAssets(scope) {
    return Array.from(scope.querySelectorAll('.slp-asset-frame img')).map(img => {
      const card = img.closest('.slp-card');
      return { url: img.getAttribute('src'), label: card ? card.querySelector('.slp-card-title').textContent.trim() : 'Asset' };
    });
  }

  // ── History ────────────────────────────────────────────────────────────────
  async function loadHistory() {
    el('history-detail').hidden = true;
    el('history-detail').innerHTML = '';
    const list = el('history-list');
    list.hidden = false;
    list.innerHTML = '<div class="slp-empty">Loading…</div>';
    const sessions = await api(`/api/playground/sessions?agentId=${encodeURIComponent(agentId)}`).catch(() => []);
    if (!sessions.length) {
      list.innerHTML = '<div class="slp-empty">No runs yet for this agent.</div>';
      return;
    }
    list.innerHTML = sessions.map(s => {
      const rounds = s.rounds != null ? (s.accepted ? `Accepted (${s.rounds}r)` : `Exhausted (${s.rounds}r)`) : null;
      const badges = [s.method, s.compareMode ? 'Compare' : null, s.evaluateMode ? 'Eval loop' : null, rounds].filter(Boolean).join(' · ');
      return `<div class="slp-list-item" data-action="history-open" data-id="${esc(s.id)}">
        <div class="slp-list-meta">
          <div class="slp-list-label">${esc(s.label)}</div>
          <div class="slp-list-sub">${new Date(s.createdAt).toLocaleString()}${badges ? ' · ' + esc(badges) : ''}</div>
        </div>
        <button class="slp-icon-btn" data-action="history-delete" data-id="${esc(s.id)}" title="Delete">✕</button>
      </div>`;
    }).join('');
  }

  async function openHistoryItem(id) {
    const detail = await api(`/api/playground/sessions/${id}`);
    const s = detail.session;
    state.sessionId = id;
    state.lastRunArgs = null;

    state.lastInput = detail.brief;
    if (detail.brief && detail.brief.sourceRuns && sourceRuns.length) {
      const { sourceRuns: ids, ...briefRest } = detail.brief;
      try {
        state.lastInput = await resolveSourceInput(briefRest, ids);
      } catch (err) {
        console.warn('Failed to reconstruct run input:', err);
        state.lastInput = null;
      }
    }
    if (detail.brief && detail.brief.direction) setDirection(detail.brief.direction);

    const isMulti = s.method === 'BOTH' && detail.result && detail.result.methods;
    const methods = isMulti ? detail.result.methods : [s.method];
    const results = isMulti ? detail.result.results : [detail.result];
    const detailEl = el('history-detail');
    detailEl.innerHTML = `<button class="slp-back" data-action="history-back">← Back to list</button>`
      + renderResults(methods, results, s.compareMode, s.evaluateMode);
    el('history-list').hidden = true;
    detailEl.hidden = false;
    state.runAssets = collectRunAssets(detailEl);
  }

  function closeHistoryDetail() {
    el('history-detail').hidden = true;
    el('history-list').hidden = false;
    state.sessionId = null;
    state.runAssets = [];
  }

  async function deleteHistoryItem(id) {
    await fetch(`/api/playground/sessions/${id}`, { method: 'DELETE', headers: HEADERS });
    await loadHistory();
  }

  // ── Audit (real client engagements' output for this agent) ─────────────────
  async function loadAudit() {
    const list = el('audit-list');
    el('audit-detail').hidden = true;
    el('audit-detail').innerHTML = '';
    list.hidden = false;
    list.innerHTML = '<div class="slp-empty">Loading…</div>';
    const rows = await api('/api/engagements?source=CLIENT').catch(() => []);
    if (!rows.length) {
      list.innerHTML = '<div class="slp-empty">No client engagements yet. Client submissions appear here.</div>';
      return;
    }
    list.innerHTML = rows.map(e => `
      <div class="slp-list-item" data-action="audit-open" data-id="${esc(e.id)}">
        <div class="slp-list-meta">
          <div class="slp-list-label">${esc(e.clientName || e.clientEmail || 'Anonymous')}</div>
          <div class="slp-list-sub">${esc(e.clientEmail || '')} · ${esc(e.status)} · ${new Date(e.createdAt).toLocaleString()}</div>
        </div>
        ${e.paymentStatus === 'REQUESTED' ? '<span class="slp-badge slp-badge-warn">Payment request</span>' : ''}
      </div>`).join('');
  }

  async function openAuditItem(id) {
    const list = el('audit-list');
    const detailEl = el('audit-detail');
    list.hidden = true;
    detailEl.hidden = false;
    detailEl.innerHTML = '<div class="slp-empty">Loading…</div>';
    const data = await api(`/api/engagements/${id}`);
    const e = data.summary;
    const dirs = (data.results && data.results.directions) || [];
    const render = config.renderAudit || config.renderOutput;
    const dirsHtml = dirs.length
      ? dirs.map(d => `
          <div class="slp-audit-dir">
            <div class="slp-label">${esc(d.direction.charAt(0) + d.direction.slice(1).toLowerCase())}</div>
            ${d[config.resultField] ? render(d[config.resultField], helpers) : '<div class="slp-hint slp-italic">No output.</div>'}
          </div>`).join('')
      : `<div class="slp-hint">${e.status === 'FAILED' ? 'Generation failed.' : 'Generation in progress…'}</div>`;
    detailEl.innerHTML = `
      <button class="slp-back" data-action="audit-back">← Back</button>
      <div class="slp-audit-name">${esc(e.clientName || 'Unknown')}</div>
      <div class="slp-hint">${esc(e.clientEmail || '')} · ${new Date(e.createdAt).toLocaleString()}</div>
      <div class="slp-row slp-wrap">
        <span class="slp-chip">Payment: ${esc(e.paymentStatus)}</span>
        ${e.paymentStatus === 'REQUESTED' ? `<button class="slp-btn slp-btn-success slp-btn-sm" data-action="mark-paid" data-id="${esc(id)}">Mark as paid</button>` : ''}
        <a class="slp-chip slp-chip-link" href="/your-brand/${esc(id)}" target="_blank" rel="noopener">View client page ↗</a>
      </div>
      <div class="slp-section-title">${esc(state.meta.displayName || agentId)} output — all directions</div>
      ${dirsHtml}`;
  }

  async function markPaid(id) {
    await fetch(`/api/engagements/${id}/mark-paid`, { method: 'POST', headers: HEADERS });
    await openAuditItem(id);
  }

  // ── Feedback capture (→ Training review) ───────────────────────────────────
  function feedbackButton(o, source) {
    const parts = [];
    if (o && o.reasoning) parts.push(o.reasoning);
    if (o && o.conceptDescription) parts.push('Concept: ' + o.conceptDescription);
    if (o && o.symbolism) parts.push('Symbolism: ' + o.symbolism);
    const fctx = { source, reasoning: parts.join('\n\n'), imageUrl: (o && o.imageUrl) || null };
    return `<button class="slp-fb-btn" data-action="feedback" data-context="${esc(JSON.stringify(fctx))}">Give feedback</button>`;
  }

  async function toggleFeedbackPanel(btn) {
    const slot = btn.closest('.slp-card').querySelector('.slp-fb-slot');
    if (slot.innerHTML) { slot.innerHTML = ''; return; }
    const fctx = JSON.parse(btn.dataset.context);
    const assets = state.runAssets.map(a => `
      <label class="slp-check-inline"><input type="checkbox" class="slp-fb-asset" value="${esc(a.url)}" ${a.url === fctx.imageUrl ? 'checked' : ''}> ${esc(a.label)}</label>`).join('');
    slot.innerHTML = `
      <div class="slp-fb-panel" data-label="">
        <div class="slp-segmented">
          <button type="button" class="slp-seg" data-action="fb-verdict" data-value="POSITIVE">Good example</button>
          <button type="button" class="slp-seg" data-action="fb-verdict" data-value="NEGATIVE">Bad example</button>
        </div>
        <textarea class="slp-fb-text" placeholder="What should the agent have done differently?"></textarea>
        ${assets ? `<div class="slp-fb-assets">${assets}</div>` : ''}
        <div class="slp-row">
          <button type="button" class="slp-btn slp-btn-primary slp-btn-sm" data-action="fb-submit">Submit</button>
          <button type="button" class="slp-btn slp-btn-sm" data-action="fb-record" title="Record voice feedback">🎙</button>
          <span class="slp-status slp-fb-status"></span>
        </div>
      </div>`;
    const panel = slot.querySelector('.slp-fb-panel');
    panel.dataset.context = JSON.stringify(fctx);
    slot.scrollIntoView({ behavior: 'smooth', block: 'center' });

    if (state.sessionId) return;
    const status = panel.querySelector('.slp-fb-status');
    const buttons = panel.querySelectorAll('[data-action="fb-submit"], [data-action="fb-record"]');
    buttons.forEach(b => { b.disabled = true; });
    status.textContent = 'Saving run…';
    if (state.lastRunArgs) await saveSession(state.lastRunArgs);
    if (state.sessionId) {
      buttons.forEach(b => { b.disabled = false; });
      status.textContent = '';
    } else {
      status.textContent = 'Could not save this run — feedback unavailable. Try running again.';
    }
  }

  function setFbVerdict(btn) {
    const panel = btn.closest('.slp-fb-panel');
    panel.querySelectorAll('[data-action="fb-verdict"]').forEach(b => b.classList.remove('active', 'good', 'bad'));
    btn.classList.add('active', btn.dataset.value === 'POSITIVE' ? 'good' : 'bad');
    panel.dataset.label = btn.dataset.value;
  }

  async function toggleRecording(btn) {
    const panel = btn.closest('.slp-fb-panel');
    const status = panel.querySelector('.slp-fb-status');
    if (state.recorder && state.recorder.state === 'recording') {
      state.recorder.stop();
      btn.classList.remove('recording');
      return;
    }
    if (!panel.dataset.label) { status.textContent = 'Choose Good or Bad example first.'; return; }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      const chunks = [];
      state.recorder = new MediaRecorder(stream);
      state.recorder.ondataavailable = e => chunks.push(e.data);
      state.recorder.onstop = () => {
        stream.getTracks().forEach(t => t.stop());
        submitFeedback(panel, new Blob(chunks, { type: 'audio/webm' }));
      };
      state.recorder.start();
      btn.classList.add('recording');
      status.textContent = 'Recording…';
    } catch (_) {
      status.textContent = 'Microphone access denied.';
    }
  }

  async function submitFeedback(panel, audioBlob) {
    const fctx = JSON.parse(panel.dataset.context);
    const label = panel.dataset.label;
    const text = panel.querySelector('.slp-fb-text').value.trim();
    const assetUrls = Array.from(panel.querySelectorAll('.slp-fb-asset:checked')).map(cb => cb.value);
    const status = panel.querySelector('.slp-fb-status');
    if (!state.sessionId) { status.textContent = 'Run not saved yet — try again in a moment.'; return; }
    if (!label) { status.textContent = 'Choose Good or Bad example first.'; return; }
    if (!text && !audioBlob) { status.textContent = 'Add a note or record voice feedback.'; return; }

    panel.querySelectorAll('button').forEach(b => { b.disabled = true; });
    status.textContent = 'Submitting…';
    try {
      let res;
      if (audioBlob) {
        const form = new FormData();
        form.append('playgroundSessionId', state.sessionId);
        form.append('assetType', agentId);
        form.append('label', label);
        form.append('agentReasoning', fctx.reasoning || '');
        assetUrls.forEach(u => form.append('assetUrls', u));
        form.append('audio', audioBlob, 'feedback.webm');
        res = await fetch('/api/training/feedback/audio', { method: 'POST', headers: { 'X-Api-Key': KEY }, body: form });
      } else {
        res = await fetch('/api/training/feedback', {
          method: 'POST', headers: HEADERS,
          body: JSON.stringify({
            playgroundSessionId: state.sessionId, assetType: agentId, label,
            feedbackText: text, agentReasoning: fctx.reasoning || '', assetUrls,
          }),
        });
      }
      if (!res.ok) throw new Error(await res.text());
      status.textContent = 'Submitted — pending review in Training.';
    } catch (err) {
      status.textContent = 'Failed: ' + (err.message || err);
      panel.querySelectorAll('button').forEach(b => { b.disabled = false; });
    }
  }
}
