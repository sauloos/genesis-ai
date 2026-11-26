/**
 * Logo assessment widget (Genesis Brands). Runs on the page after discovery: assesses the logo the
 * client uploaded, shows the score, verdict and what we would keep and change, and asks whether to
 * keep, evolve or replace it. The choice is saved server-side and the flow moves on. A client with
 * no logo goes straight on; a file the audit cannot read is left for the team to review by hand.
 * In simulate mode it shows a sample assessment and never calls the model.
 */
const HEADERS = { 'Content-Type': 'application/json', 'X-Api-Key': 'genesis-ai-key' };

const VERDICTS = {
  KEEP: { label: 'Keep', tone: 'good', choice: 'keep' },
  EVOLVE: { label: 'Evolve', tone: 'warn', choice: 'evolve' },
  REPLACE: { label: 'Replace', tone: 'bad', choice: 'fresh' }
};

const CHOICES = [
  { key: 'keep', title: 'Keep it as it is',
    body: 'Your logo stays exactly as it is in the anchored direction. The other two directions still explore further.' },
  { key: 'evolve', title: 'Evolve it',
    body: 'We keep what works, fix what holds it back, and develop it in the anchored and evolved directions.' },
  { key: 'fresh', title: 'Start fresh',
    body: 'We design new logos in all three directions. What we learned from this one still informs them.' }
];

/** The questionnaire's own "what should we do with it?" answer, as one of our choice keys. */
const INTENT_CHOICE = { 'keep it': 'keep', 'evolve it': 'evolve', 'start fresh': 'fresh' };

const SAMPLE = {
  status: 'ASSESSED',
  logoUrl: null,
  intent: 'evolve it',
  assessment: {
    score: 62, verdict: 'EVOLVE',
    headline: 'A recognisable mark held back by its lettering',
    summary: 'The circle-and-leaf symbol is distinctive and worth keeping. The script wordmark is hard to read small, and the two greens fight each other.',
    criteria: [
      { key: 'distinctiveness', label: 'Distinctiveness', weight: 'critical', score: 7, note: 'The leaf-in-circle shape is ownable in its category.' },
      { key: 'legibility', label: 'Legibility at small sizes', weight: 'critical', score: 4, note: 'The script breaks up below 24px.' },
      { key: 'fit', label: 'Fit with the brand', weight: 'high', score: 7, note: 'Calm and natural, as the brand describes itself.' },
      { key: 'craft', label: 'Craft', weight: 'high', score: 5, note: 'Uneven stroke weights and a loose kern.' }
    ],
    keep: ['The leaf-in-circle symbol', 'The deep green'],
    change: ['Replace the script with a sturdy sans', 'One green, not two'],
    confidence: 'HIGH', confidenceNote: null
  }
};

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined && text !== null) node.textContent = text;
  return node;
}

export function mount(container, widget, ctx) {
  container.classList.add('la');
  const config = widget.config || {};
  const heading = config.heading || 'What we see in your logo';
  const intro = config.intro || 'Before we design anything, here is our honest read of the logo you have today.';
  const base = `/api/public/logo-assessment/flow-sessions/${ctx.session?.token}/widgets/${widget.id}`;
  ctx.requireCompletion?.();

  const header = () => [el('h2', 'la-heading', heading), el('p', 'la-intro', intro)];

  const finish = (key) => {
    ctx.complete?.();
    ctx.onOutcome?.(key || 'next');
  };

  const renderWaiting = () => {
    container.replaceChildren(...header(), el('div', 'la-spinner'),
      el('p', 'la-status', 'Reading your logo. This takes about a minute.'));
  };

  const renderError = (message) => {
    const retry = el('button', 'la-retry', 'Try again');
    retry.type = 'button';
    retry.onclick = () => load();
    container.replaceChildren(...header(), el('p', 'la-error', message), retry);
  };

  const renderUnviewable = () => {
    // Not .outcome-btn: the host disables those while this widget blocks the page.
    const next = el('button', 'la-retry', 'Continue');
    next.type = 'button';
    next.onclick = () => finish('next');
    container.replaceChildren(...header(),
      el('p', 'la-status', 'We can’t read this file type automatically yet, so our design team will review your logo by hand. Your directions will still take it into account.'),
      next);
  };

  const criterionRow = (c) => {
    const row = el('li', 'la-criterion');
    const top = el('div', 'la-criterion-top');
    top.append(el('span', 'la-criterion-label', c.label || c.key), el('span', 'la-criterion-score', `${c.score}/10`));
    const bar = el('div', 'la-bar');
    const fill = el('div', 'la-bar-fill');
    fill.style.width = `${Math.max(0, Math.min(10, c.score)) * 10}%`;
    fill.dataset.tone = c.score >= 7 ? 'good' : c.score >= 5 ? 'warn' : 'bad';
    bar.appendChild(fill);
    row.append(top, bar);
    if (c.note) row.appendChild(el('p', 'la-criterion-note', c.note));
    return row;
  };

  const list = (title, items, tone) => {
    if (!items || !items.length) return null;
    const box = el('div', 'la-list');
    box.dataset.tone = tone;
    box.appendChild(el('h3', 'la-list-title', title));
    const ul = el('ul');
    items.forEach(i => ul.appendChild(el('li', null, i)));
    box.appendChild(ul);
    return box;
  };

  const decide = async (choice, buttons) => {
    buttons.forEach(b => { b.disabled = true; });
    if (ctx.simulate) { finish('next'); return; }
    try {
      const res = await fetch(`${base}/decision`, {
        method: 'POST', headers: HEADERS, credentials: 'include', body: JSON.stringify({ decision: choice })
      });
      if (!res.ok) throw new Error(`decision ${res.status}`);
      finish('next');
    } catch (e) {
      buttons.forEach(b => { b.disabled = false; });
      status.textContent = 'We couldn’t save your choice. Please try again.';
    }
  };

  const status = el('p', 'la-status');

  const renderAssessment = (record) => {
    const a = record.assessment || {};
    const verdict = VERDICTS[a.verdict] || VERDICTS.EVOLVE;

    const summary = el('section', 'la-summary');
    const art = el('div', 'la-art');
    if (record.logoUrl) {
      const img = el('img', 'la-logo');
      img.src = record.logoUrl;
      img.alt = 'Your current logo';
      art.appendChild(img);
    } else {
      art.appendChild(el('span', 'la-art-empty', ctx.simulate ? 'Sample logo' : 'Your logo'));
    }
    const score = el('div', 'la-score');
    const number = el('div', 'la-score-number');
    number.append(el('span', null, String(a.score ?? '–')), el('small', null, '/100'));
    const pill = el('span', 'la-verdict', `We recommend: ${verdict.label}`);
    pill.dataset.tone = verdict.tone;
    score.append(number, pill);
    const words = el('div', 'la-words');
    words.append(score, el('h3', 'la-headline', a.headline), el('p', 'la-summary-text', a.summary));
    if (a.confidence && a.confidence !== 'HIGH' && a.confidenceNote) {
      words.appendChild(el('p', 'la-confidence', a.confidenceNote));
    }
    summary.append(art, words);

    const lists = el('div', 'la-lists');
    [list('What we’d keep', a.keep, 'good'), list('What we’d change', a.change, 'bad')]
      .filter(Boolean).forEach(n => lists.appendChild(n));

    const criteria = el('details', 'la-criteria');
    criteria.appendChild(el('summary', null, 'How we scored it'));
    const ul = el('ul', 'la-criteria-list');
    (a.criteria || []).forEach(c => ul.appendChild(criterionRow(c)));
    criteria.appendChild(ul);

    const decision = el('section', 'la-decision');
    decision.appendChild(el('h3', 'la-decision-title', 'What should we do with it?'));
    const recommended = verdict.choice;
    const earlier = INTENT_CHOICE[String(record.intent || '').toLowerCase()];
    const grid = el('div', 'la-choices');
    const buttons = CHOICES.map(c => {
      const b = el('button', 'la-choice');
      b.type = 'button';
      b.appendChild(el('span', 'la-choice-title', c.title));
      const tags = [];
      if (c.key === recommended) tags.push('Our recommendation');
      if (c.key === earlier) tags.push('What you said earlier');
      if (tags.length) b.appendChild(el('span', 'la-choice-tag', tags.join(' · ')));
      b.appendChild(el('span', 'la-choice-body', c.body));
      if (c.key === recommended) b.classList.add('la-choice-recommended');
      b.onclick = () => decide(c.key, buttons);
      return b;
    });
    buttons.forEach(b => grid.appendChild(b));
    decision.append(grid, status);

    container.replaceChildren(...header(), summary, lists, criteria, decision);
  };

  const show = (record) => {
    if (!container.isConnected) return;
    if (record.status === 'NO_LOGO') { finish('next'); return; }
    if (record.status === 'UNVIEWABLE') { renderUnviewable(); return; }
    if (record.decision) { finish('next'); return; } // already chosen (a reload after deciding)
    renderAssessment(record);
  };

  const load = async () => {
    if (ctx.simulate) { renderAssessment(SAMPLE); return; }
    renderWaiting();
    try {
      const res = await fetch(`${base}/assess`, { method: 'POST', headers: HEADERS, credentials: 'include' });
      if (!res.ok) throw new Error(`assess ${res.status}`);
      show(await res.json());
    } catch (e) {
      renderError('We couldn’t assess your logo just now.');
    }
  };

  load();
}
