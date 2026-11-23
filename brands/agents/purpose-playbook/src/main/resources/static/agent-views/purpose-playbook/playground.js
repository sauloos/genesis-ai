/**
 * Purpose Playbook agent's Playground view.
 *
 * Deliberately NOT built on `_shared/slotPlayground.js`. That runner is brief-shaped — it owns a
 * derived brief, a creative direction and upstream source runs — and this agent is upstream of all
 * three: it runs once per engagement, before any direction exists, and its only input is a stored
 * questionnaire response. So the view's job is to let a facilitator pick a response, see exactly
 * what the agent will be given (including what is absent), run it, and read the document back.
 *
 * Everything is resolved server-side from the response id: the view never assembles signals
 * itself, because the active-question set depends on the condition grammar and the signal keys are
 * the contract between questionnaire, agents and the client's RAG context.
 */
const HEADERS = { 'Content-Type': 'application/json', 'X-Api-Key': 'genesis-ai-key' };

const PILLARS = {
  cover: 'Cover',
  situation: 'Where you are',
  belief: 'Belief',
  belong: 'Belong',
  best: 'Best',
  beyond: 'Beyond',
  strategy: 'What we recommend',
};

function esc(s) {
  if (s === null || s === undefined || s === '') return '';
  return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

async function get(path) {
  const res = await fetch(path, { headers: HEADERS });
  if (!res.ok) throw new Error((await res.text().catch(() => '')) || `HTTP ${res.status}`);
  return res.json();
}

async function post(path, body) {
  const res = await fetch(path, { method: 'POST', headers: HEADERS, body: JSON.stringify(body ?? {}) });
  if (!res.ok) throw new Error((await res.text().catch(() => '')) || `HTTP ${res.status}`);
  return res.json();
}

function when(iso) {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' }) + ' · '
    + d.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });
}

/** The pillar a section key belongs to — `belief.mission` and `belief` both land under Belief. */
function pillarOf(key) {
  const root = String(key).split('.')[0];
  return PILLARS[root] || root;
}

function signalCount(signals) {
  return signals && typeof signals === 'object' ? Object.keys(signals).length : 0;
}

export async function mount(container, agent, ctx) {
  container.classList.add('ppb-view');
  container.innerHTML = '<div class="ppb-status">Loading questionnaires…</div>';

  let questionnaires = [];
  try {
    questionnaires = await get('/api/questionnaires');
  } catch (err) {
    container.innerHTML = `<div class="ppb-error">${esc(err.message || 'Could not load questionnaires.')}</div>`;
    return;
  }

  if (!questionnaires.length) {
    container.innerHTML = '<div class="ppb-empty">No questionnaires exist yet. The Purpose Playbook is '
      + 'written from a discovery response, so there is nothing for this agent to read.</div>';
    return;
  }

  container.innerHTML = `
    <div class="ppb-panel">
      <div class="ppb-panel-title">Discovery response</div>
      <p class="ppb-note">This agent reads one completed questionnaire response. It has no brief —
        it is the document a brief is derived from.</p>
      <label class="ppb-label" for="ppb-questionnaire">Questionnaire</label>
      <select class="ppb-select" id="ppb-questionnaire"></select>
      <label class="ppb-label" for="ppb-response">Response</label>
      <select class="ppb-select" id="ppb-response"></select>
      <div class="ppb-signals"></div>
    </div>

    <div class="ppb-panel">
      <div class="ppb-panel-title">Facilitator additions <span class="ppb-optional">optional</span></div>
      <label class="ppb-label" for="ppb-instructions">Specific instructions — one per line</label>
      <textarea class="ppb-textarea" id="ppb-instructions" rows="3"
        placeholder="Press harder on the value proposition ladder — the answers are thinner than they look."></textarea>
      <label class="ppb-label" for="ppb-context">Additional context</label>
      <textarea class="ppb-textarea" id="ppb-context" rows="3"
        placeholder="Anything said in the session that the questionnaire did not capture."></textarea>
    </div>

    <div class="ppb-actions">
      <label class="ppb-check">
        <input type="checkbox" id="ppb-evaluate" checked>
        <span>Run the evaluation loop</span>
      </label>
      <button class="ppb-run" type="button">Write the playbook</button>
      <span class="ppb-status"></span>
    </div>

    <div class="ppb-result"></div>
  `;

  const questionnaireSelect = container.querySelector('#ppb-questionnaire');
  const responseSelect = container.querySelector('#ppb-response');
  const signalsBox = container.querySelector('.ppb-signals');
  const instructions = container.querySelector('#ppb-instructions');
  const additional = container.querySelector('#ppb-context');
  const evaluate = container.querySelector('#ppb-evaluate');
  const runButton = container.querySelector('.ppb-run');
  const status = container.querySelector('.ppb-status');
  const result = container.querySelector('.ppb-result');

  questionnaireSelect.innerHTML = questionnaires
    .map(q => `<option value="${esc(q.id)}">${esc(q.title)}</option>`).join('');

  async function loadResponses() {
    responseSelect.innerHTML = '<option value="">Loading…</option>';
    signalsBox.innerHTML = '';
    let responses = [];
    try {
      responses = await get(`/api/questionnaires/${encodeURIComponent(questionnaireSelect.value)}/responses`);
    } catch (err) {
      responseSelect.innerHTML = '<option value="">Could not load responses</option>';
      return;
    }
    if (!responses.length) {
      responseSelect.innerHTML = '<option value="">No responses yet</option>';
      runButton.disabled = true;
      return;
    }
    // Submitted first: an abandoned part-way response makes a thin playbook, not a wrong one, so
    // it stays selectable — it is simply not the obvious choice.
    const sorted = [...responses].sort((a, b) => Number(!!b.submittedAt) - Number(!!a.submittedAt));
    responseSelect.innerHTML = sorted.map(r => {
      const label = (r.submittedAt ? `Submitted ${when(r.submittedAt)}` : `In progress — started ${when(r.startedAt)}`)
        + (r.simulated ? ' · simulated' : '');
      return `<option value="${esc(r.id)}">${esc(label)}</option>`;
    }).join('');
    runButton.disabled = false;
    await loadSignals();
  }

  async function loadSignals() {
    const responseId = responseSelect.value;
    if (!responseId) {
      signalsBox.innerHTML = '';
      return;
    }
    signalsBox.innerHTML = '<span class="ppb-faint">Reading answers…</span>';
    try {
      const [signals, active] = await Promise.all([
        get(`/api/questionnaires/responses/${encodeURIComponent(responseId)}/signals`),
        get(`/api/questionnaires/responses/${encodeURIComponent(responseId)}/active-questions`),
      ]);
      const answered = signalCount(signals);
      const asked = active.filter(q => q.signalKey).length;
      signalsBox.innerHTML = `
        <div class="ppb-signal-count">
          <strong>${answered}</strong> of <strong>${asked}</strong> signals answered
        </div>
        <div class="ppb-faint">The agent is told which of the rest were skipped, which were answered
          in three words or fewer, and which the document needs but this questionnaire never asked.</div>`;
    } catch (err) {
      signalsBox.innerHTML = `<span class="ppb-faint">Could not read this response's answers.</span>`;
    }
  }

  questionnaireSelect.onchange = loadResponses;
  responseSelect.onchange = loadSignals;

  runButton.onclick = async () => {
    const responseId = responseSelect.value;
    if (!responseId) return;
    runButton.disabled = true;
    status.textContent = evaluate.checked
      ? 'Writing, then evaluating. This takes a few minutes…'
      : 'Writing. This takes a few minutes…';
    result.innerHTML = '';
    const body = {
      trainingInstructions: instructions.value.split('\n').map(s => s.trim()).filter(Boolean),
      additionalContext: additional.value.trim() || null,
    };
    try {
      const path = `/api/agents/purpose-playbook/from-response/${encodeURIComponent(responseId)}`
        + (evaluate.checked ? '/with-evaluation' : '');
      const payload = await post(path, body);
      // The evaluated path returns a RefinementResult wrapper; the plain one returns the document.
      if (payload && payload.output) {
        renderDocument(result, payload.output, payload);
      } else {
        renderDocument(result, payload, null);
      }
    } catch (err) {
      result.innerHTML = `<div class="ppb-error">${esc(err.message || 'Generation failed.')}</div>`;
    } finally {
      status.textContent = '';
      runButton.disabled = false;
    }
  };

  await loadResponses();
}

function renderDocument(host, output, refinement) {
  if (!output) {
    host.innerHTML = '<div class="ppb-error">The agent returned nothing.</div>';
    return;
  }
  const parts = [];

  if (refinement) {
    const rounds = (refinement.rounds || []).length;
    parts.push(`
      <div class="ppb-verdict ${refinement.accepted ? 'ppb-accepted' : 'ppb-rejected'}">
        ${refinement.accepted ? 'Accepted' : 'Not accepted'} after
        ${rounds} evaluation ${rounds === 1 ? 'round' : 'rounds'}
      </div>`);
    (refinement.rounds || []).forEach((round, i) => {
      const verdict = round.evaluation?.verdict || round.verdict || '';
      const summary = round.evaluation?.summary || round.summary || '';
      const issues = round.evaluation?.revision?.specificIssues || round.revision?.specificIssues || [];
      parts.push(`
        <details class="ppb-round">
          <summary>Round ${i + 1} — ${esc(verdict)}</summary>
          <p>${esc(summary)}</p>
          ${issues.length ? `<ul>${issues.map(x => `<li>${esc(x)}</li>`).join('')}</ul>` : ''}
        </details>`);
    });
  }

  parts.push(renderLookTerritory(output.lookTerritory));
  parts.push(renderSections(output.sections));
  parts.push(renderConfidence(output.confidence));

  if (output.reasoning) {
    parts.push(`
      <div class="ppb-block">
        <div class="ppb-block-title">How it was written</div>
        <p class="ppb-prose">${esc(output.reasoning)}</p>
      </div>`);
  }

  host.innerHTML = parts.filter(Boolean).join('');
}

function renderSections(sections) {
  const keys = sections ? Object.keys(sections) : [];
  if (!keys.length) return '<div class="ppb-error">The document has no sections.</div>';

  const groups = new Map();
  keys.forEach(key => {
    const pillar = pillarOf(key);
    if (!groups.has(pillar)) groups.set(pillar, []);
    groups.get(pillar).push(key);
  });

  const chars = keys.reduce((n, k) => n + (sections[k].body || '').length, 0);

  const body = [...groups.entries()].map(([pillar, groupKeys]) => `
    <div class="ppb-pillar">
      <div class="ppb-pillar-title">${esc(pillar)}</div>
      ${groupKeys.map(key => renderSection(key, sections[key])).join('')}
    </div>`).join('');

  return `
    <div class="ppb-block">
      <div class="ppb-block-title">
        The document
        <span class="ppb-faint">${keys.length} sections · ${chars.toLocaleString()} characters</span>
      </div>
      ${body}
    </div>`;
}

function renderSection(key, section) {
  const confidence = (section.confidence || '').toLowerCase();
  const sources = section.sources || [];
  return `
    <details class="ppb-section">
      <summary>
        <span class="ppb-section-title">${esc(section.title || key)}</span>
        <span class="ppb-key">${esc(key)}</span>
        ${confidence ? `<span class="ppb-pill ppb-${esc(confidence)}">${esc(confidence)}</span>` : ''}
      </summary>
      <div class="ppb-prose">${String(section.body || '').split(/\n{2,}/).map(p => `<p>${esc(p)}</p>`).join('')}</div>
      ${sources.length
        ? `<div class="ppb-sources"><span class="ppb-faint">From</span> ${sources.map(s => `<code>${esc(s)}</code>`).join(' ')}</div>`
        : '<div class="ppb-sources ppb-unsourced">No sources named — this section cannot be audited.</div>'}
    </details>`;
}

function renderLookTerritory(territory) {
  if (!territory) return '';
  const row = (label, value) => value
    ? `<div class="ppb-look-row"><span class="ppb-label-inline">${esc(label)}</span><span>${esc(value)}</span></div>`
    : '';
  const words = territory.summaryWords || [];
  const tensions = territory.tensions || [];
  return `
    <div class="ppb-block ppb-look">
      <div class="ppb-block-title">
        Look territory
        <span class="ppb-faint">read directly by the Logo and Visual Identity agents</span>
      </div>
      ${words.length ? `<div class="ppb-words">${words.map(w => `<span class="ppb-word">${esc(w)}</span>`).join('')}</div>` : ''}
      ${row('Posture', territory.posture)}
      ${row('Palette', territory.palette)}
      ${row('Typography', territory.typography)}
      ${row('Logo', territory.logo)}
      ${row('Imagery', territory.imagery)}
      ${tensions.length ? `
        <div class="ppb-look-row">
          <span class="ppb-label-inline">Tensions</span>
          <span><ul class="ppb-tensions">${tensions.map(t => `<li>${esc(t)}</li>`).join('')}</ul></span>
        </div>` : ''}
      ${territory.confidence ? `<div class="ppb-look-row"><span class="ppb-label-inline">Confidence</span>
        <span class="ppb-pill ppb-${esc(String(territory.confidence).toLowerCase())}">${esc(territory.confidence)}</span></div>` : ''}
    </div>`;
}

function renderConfidence(gaps) {
  if (!gaps || !gaps.length) {
    return `<div class="ppb-block"><div class="ppb-block-title">What we are not sure of</div>
      <div class="ppb-unsourced">Empty. This section is required on every engagement — there is always
      something resting on a single answer.</div></div>`;
  }
  return `
    <div class="ppb-block">
      <div class="ppb-block-title">
        What we are not sure of
        <span class="ppb-faint">read before the client meeting</span>
      </div>
      <table class="ppb-table">
        <thead><tr><th>Field</th><th>Confidence</th><th>What would raise it</th></tr></thead>
        <tbody>
          ${gaps.map(g => `
            <tr>
              <td><code>${esc(g.field)}</code></td>
              <td><span class="ppb-pill ppb-${esc(String(g.confidence || '').toLowerCase())}">${esc(g.confidence)}</span></td>
              <td>${esc(g.whatWouldRaiseIt)}</td>
            </tr>`).join('')}
        </tbody>
      </table>
    </div>`;
}
