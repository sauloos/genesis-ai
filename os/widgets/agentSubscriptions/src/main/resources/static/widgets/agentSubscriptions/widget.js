const HEADERS = { 'Content-Type': 'application/json', 'X-Api-Key': 'genesis-ai-key' };
const BASE = '/api/client/standing';

const CADENCE = { DAILY: 'Every day', WEEKLY: 'Every week', FORTNIGHTLY: 'Every two weeks', MONTHLY: 'Every month' };
const WEEKDAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];

// Shown in the admin preview instead of calling the API.
const SAMPLE = [{
  agentId: 'client-pulse', displayName: 'Client Pulse', icon: null,
  description: 'A weekly read on your brand: what moved, what is waiting on you, and what to do next.',
  cadences: ['WEEKLY', 'FORTNIGHTLY', 'MONTHLY'], defaultCadence: 'WEEKLY', maxRunsPerMonth: 8,
  options: [
    { key: 'focus', label: 'Focus on', type: 'MULTI_SELECT', allowedValues: ['Brand health', 'Pending decisions', 'Content ideas'], defaultValue: 'Brand health,Pending decisions', locked: false },
    { key: 'tone', label: 'Tone', type: 'SELECT', allowedValues: ['Concise', 'Detailed'], defaultValue: 'Concise', locked: false }
  ],
  subscription: { active: true, cadence: 'WEEKLY', dayOfWeek: 1, dayOfMonth: 1, timeOfDay: '09:00', timezone: 'Europe/London',
    options: { focus: 'Brand health,Pending decisions', tone: 'Concise' }, nextRunAt: new Date(Date.now() + 3 * 864e5).toISOString(), runsThisMonth: 2 },
  triggers: ['SCHEDULE'], eventFields: []
}, {
  agentId: 'lead-proposal', displayName: 'Lead to proposal', icon: null,
  description: 'Send it a new lead or RFP and it qualifies it against what you sell and drafts your reply and proposal.',
  cadences: [], defaultCadence: 'WEEKLY', maxRunsPerMonth: 40, triggers: ['EVENT'], eventLabel: 'Add a lead',
  options: [
    { key: 'offer', label: 'What you offer', type: 'TEXT', defaultValue: '', helpText: 'Your services or packages, with prices if you quote them.', locked: false },
    { key: 'tone', label: 'Tone', type: 'SELECT', allowedValues: ['Warm', 'Formal', 'Brief'], defaultValue: 'Warm', locked: false }
  ],
  eventFields: [
    { key: 'name', label: 'Name', type: 'STRING', defaultValue: '' },
    { key: 'company', label: 'Company', type: 'STRING', defaultValue: '' },
    { key: 'message', label: 'What they asked for', type: 'TEXT', defaultValue: '', helpText: 'Paste the enquiry, the email thread or the RFP.' }
  ],
  subscription: { active: true, cadence: 'WEEKLY', timezone: 'Europe/London', options: { offer: 'Brand refresh: $4,000', tone: 'Warm' }, runsThisMonth: 3 }
}];

// An agent without a schedule runs only on what the client sends it.
const scheduled = offer => !offer.triggers || offer.triggers.includes('SCHEDULE');
const takesEvents = offer => (offer.triggers || []).includes('EVENT') && (offer.eventFields || []).length > 0;

function el(tag, cls, text) {
  const node = document.createElement(tag);
  if (cls) node.className = cls;
  if (text != null) node.textContent = text;
  return node;
}

function browserZone() {
  try { return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'; } catch (_) { return 'UTC'; }
}

// In the subscription's own zone, so it reads alongside "at 09:00".
function nextRunText(sub) {
  const opts = { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' };
  try { return new Date(sub.nextRunAt).toLocaleString(undefined, { ...opts, timeZone: sub.timezone }); }
  catch (_) { return new Date(sub.nextRunAt).toLocaleString(undefined, opts); }
}

function scheduleText(sub) {
  const at = sub.timeOfDay || '09:00';
  switch (sub.cadence) {
    case 'DAILY': return `Every day at ${at}`;
    case 'WEEKLY': return `Every ${WEEKDAYS[(sub.dayOfWeek || 1) - 1]} at ${at}`;
    case 'FORTNIGHTLY': return `Every other ${WEEKDAYS[(sub.dayOfWeek || 1) - 1]} at ${at}`;
    case 'MONTHLY': return `Monthly on day ${sub.dayOfMonth || 1} at ${at}`;
    default: return CADENCE[sub.cadence] || sub.cadence;
  }
}

async function call(path, init = {}) {
  const res = await fetch(BASE + path, { credentials: 'include', headers: HEADERS, ...init });
  if (!res.ok) {
    let message = res.status === 401 ? 'Please sign in again.' : `Something went wrong (${res.status}).`;
    try { message = (await res.json()).message || message; } catch (_) {}
    throw new Error(message);
  }
  return res.status === 204 ? null : res.json();
}

export function mount(container, widget, ctx = {}) {
  container.classList.add('asub');
  const simulate = !!ctx.simulate;
  let offers = [];
  let openId = null;
  let eventId = null;

  const panel = el('div', 'asub-panel');
  panel.appendChild(el('div', 'asub-label', 'Your agents'));
  const list = el('div', 'asub-list');
  panel.appendChild(list);
  container.appendChild(panel);

  async function load() {
    if (simulate) {
      offers = structuredClone(SAMPLE);
      render();
      return;
    }
    try {
      offers = await call('/offers');
      render();
    } catch (err) {
      list.replaceChildren(el('div', 'asub-empty', err.message));
    }
  }

  function render() {
    if (!offers.length) {
      list.replaceChildren(el('div', 'asub-empty', 'No agents are available to you yet.'));
      return;
    }
    list.replaceChildren(...offers.map(renderOffer));
  }

  function renderOffer(offer) {
    const sub = offer.subscription;
    const card = el('div', 'asub-card');
    card.dataset.agentId = offer.agentId;
    const head = el('div', 'asub-head');
    const info = el('div', 'asub-info');
    info.appendChild(el('div', 'asub-name', offer.displayName));
    if (offer.description) info.appendChild(el('div', 'asub-desc', offer.description));
    const state = el('div', 'asub-state');
    if (!sub) {
      state.textContent = 'Not set up';
    } else if (!sub.active) {
      state.textContent = sub.pausedReason === 'ADMIN' ? 'Paused by your account manager' : 'Paused';
      state.classList.add('asub-state-paused');
    } else if (!scheduled(offer)) {
      state.textContent = `On · runs on what you send it${sub.runsThisMonth ? ` · ${sub.runsThisMonth} this month` : ''}`;
      state.classList.add('asub-state-on');
    } else {
      state.textContent = scheduleText(sub)
        + (sub.nextRunAt ? ` · next ${nextRunText(sub)}` : '');
      state.classList.add('asub-state-on');
    }
    info.appendChild(state);
    head.appendChild(info);

    const actions = el('div', 'asub-actions');
    const toggle = el('button', 'asub-btn', sub ? (openId === offer.agentId ? 'Close' : 'Settings') : 'Set up');
    toggle.type = 'button';
    toggle.addEventListener('click', () => { openId = openId === offer.agentId ? null : offer.agentId; eventId = null; render(); });
    actions.appendChild(toggle);
    if (sub && sub.active && takesEvents(offer)) {
      const send = el('button', 'asub-btn', eventId === offer.agentId ? 'Close' : (offer.eventLabel || 'Send'));
      send.type = 'button';
      send.addEventListener('click', () => { eventId = eventId === offer.agentId ? null : offer.agentId; openId = null; render(); });
      actions.appendChild(send);
    }
    if (sub && sub.active && scheduled(offer)) {
      const run = el('button', 'asub-btn', 'Run now');
      run.type = 'button';
      run.addEventListener('click', async () => {
        run.disabled = true;
        try {
          if (!simulate) await call(`/subscriptions/${encodeURIComponent(offer.agentId)}/run-now`, { method: 'POST' });
          flash(card, 'Started. The result will arrive in your inbox in a minute or two.');
        } catch (err) {
          flash(card, err.message, true);
        } finally {
          run.disabled = false;
        }
      });
      actions.appendChild(run);
    }
    head.appendChild(actions);
    card.appendChild(head);
    if (openId === offer.agentId) card.appendChild(renderForm(offer));
    if (eventId === offer.agentId) card.appendChild(renderEventForm(offer, card));
    return card;
  }

  // Generic over the agent's declared event fields, like the settings form over its options.
  function renderEventForm(offer, card) {
    const form = el('form', 'asub-form');
    const values = {};
    const fields = el('div', 'asub-fields');
    for (const f of offer.eventFields) {
      values[f.key] = f.defaultValue || '';
      fields.appendChild(optionField({ ...f, locked: false }, values[f.key], v => { values[f.key] = v; }));
    }
    form.appendChild(fields);
    const status = el('div', 'asub-status');
    const buttons = el('div', 'asub-form-actions');
    const send = el('button', 'asub-btn asub-btn-primary', offer.eventLabel || 'Send');
    send.type = 'submit';
    buttons.appendChild(send);
    form.append(buttons, status);
    form.addEventListener('submit', async ev => {
      ev.preventDefault();
      // Text inputs report on change; read them all once more so a value still being typed counts.
      form.querySelectorAll('[data-key]').forEach(input => { values[input.dataset.key] = input.value; });
      if (!Object.values(values).some(v => String(v).trim())) { status.textContent = 'Fill in at least one field.'; return; }
      send.disabled = true;
      try {
        if (!simulate) {
          await call(`/subscriptions/${encodeURIComponent(offer.agentId)}/events`, { method: 'POST', body: JSON.stringify({ fields: values }) });
        }
        eventId = null;
        render();
        const fresh = [...list.children].find(c => c.dataset.agentId === offer.agentId) || card;
        flash(fresh, 'Sent. The drafts will arrive in your inbox in a minute or two.');
      } catch (err) {
        status.textContent = err.message;
      } finally {
        send.disabled = false;
      }
    });
    return form;
  }

  function renderForm(offer) {
    const sub = offer.subscription;
    const form = el('form', 'asub-form');
    const values = { ...(sub ? sub.options : {}) };
    const fields = el('div', 'asub-fields');

    const cadence = select(offer.cadences.map(c => [c, CADENCE[c] || c]), sub ? sub.cadence : offer.defaultCadence);
    const cadenceField = field('How often', cadence);
    fields.appendChild(cadenceField);
    const weekday = select(WEEKDAYS.map((d, i) => [String(i + 1), d]), String(sub ? sub.dayOfWeek : 1));
    const weekdayField = field('On', weekday);
    fields.appendChild(weekdayField);
    const monthDay = el('input', 'asub-input');
    Object.assign(monthDay, { type: 'number', min: 1, max: 31, value: sub ? sub.dayOfMonth : 1 });
    const monthDayField = field('Day of the month', monthDay, 'Shorter months use their last day.');
    fields.appendChild(monthDayField);
    const time = el('input', 'asub-input');
    Object.assign(time, { type: 'time', value: sub && sub.timeOfDay ? sub.timeOfDay : '09:00', required: scheduled(offer) });
    const timeField = field('At', time, `Your time zone: ${sub ? sub.timezone : browserZone()}`);
    fields.appendChild(timeField);
    const syncDayFields = () => {
      cadenceField.hidden = timeField.hidden = !scheduled(offer);
      weekdayField.hidden = !scheduled(offer) || !['WEEKLY', 'FORTNIGHTLY'].includes(cadence.value);
      monthDayField.hidden = !scheduled(offer) || cadence.value !== 'MONTHLY';
    };
    cadence.addEventListener('change', syncDayFields);
    syncDayFields();

    for (const option of offer.options) {
      const current = values[option.key] ?? option.defaultValue ?? '';
      fields.appendChild(optionField(option, current, v => { values[option.key] = v; }));
    }
    let notifyByEmail = sub ? sub.notifyByEmail !== false : true;
    fields.appendChild(optionField({ type: 'BOOLEAN', label: 'Email me when results arrive' }, String(notifyByEmail),
      v => { notifyByEmail = v === 'true'; }));
    form.appendChild(fields);

    const status = el('div', 'asub-status');
    const buttons = el('div', 'asub-form-actions');
    const save = el('button', 'asub-btn asub-btn-primary', sub ? 'Save changes' : 'Start');
    save.type = 'submit';
    buttons.appendChild(save);
    if (sub) {
      if (sub.active || sub.pausedReason !== 'ADMIN') {
        const pause = el('button', 'asub-btn', sub.active ? 'Pause' : 'Resume');
        pause.type = 'button';
        pause.addEventListener('click', () => act(pause, async () => {
          if (!simulate) await call(`/subscriptions/${encodeURIComponent(offer.agentId)}/${sub.active ? 'pause' : 'resume'}`, { method: 'POST' });
          else sub.active = !sub.active;
        }));
        buttons.appendChild(pause);
      }
      const stop = el('button', 'asub-btn asub-btn-quiet', 'Unsubscribe');
      stop.type = 'button';
      stop.addEventListener('click', () => {
        if (!confirm(`Stop ${offer.displayName}? Items already in your inbox stay there.`)) return;
        act(stop, async () => {
          if (!simulate) await call(`/subscriptions/${encodeURIComponent(offer.agentId)}`, { method: 'DELETE' });
          else offer.subscription = null;
          openId = null;
        });
      });
      buttons.appendChild(stop);
    }
    form.appendChild(buttons);
    form.appendChild(status);

    form.addEventListener('submit', ev => {
      ev.preventDefault();
      const body = {
        cadence: scheduled(offer) ? cadence.value : null,
        dayOfWeek: Number(weekday.value),
        dayOfMonth: Number(monthDay.value) || 1,
        timeOfDay: time.value || '09:00',
        timezone: sub ? sub.timezone : browserZone(),
        options: values,
        notifyByEmail
      };
      act(save, async () => {
        if (!simulate) await call(`/subscriptions/${encodeURIComponent(offer.agentId)}`, { method: 'PUT', body: JSON.stringify(body) });
        else offer.subscription = { ...(sub || {}), ...body, cadence: body.cadence || offer.defaultCadence, active: true, nextRunAt: scheduled(offer) ? new Date(Date.now() + 864e5).toISOString() : null };
        openId = null;
      }, true, status);
    });
    return form;
  }

  function optionField(option, current, set) {
    const help = option.locked ? 'Set by your account manager.' : option.helpText;
    if (option.type === 'BOOLEAN') {
      const label = el('label', 'asub-check');
      const box = el('input');
      Object.assign(box, { type: 'checkbox', checked: current === 'true', disabled: option.locked });
      box.addEventListener('change', () => set(String(box.checked)));
      label.append(box, document.createTextNode(' ' + option.label));
      const wrap = el('div', 'asub-field');
      wrap.appendChild(label);
      if (help) wrap.appendChild(el('div', 'asub-help', help));
      return wrap;
    }
    if (option.type === 'MULTI_SELECT') {
      const chosen = new Set(String(current).split(',').filter(Boolean));
      const group = el('div', 'asub-chips');
      for (const value of option.allowedValues) {
        const chip = el('button', 'asub-chip', value);
        chip.type = 'button';
        chip.disabled = option.locked;
        chip.setAttribute('aria-pressed', String(chosen.has(value)));
        chip.addEventListener('click', () => {
          chosen.has(value) ? chosen.delete(value) : chosen.add(value);
          chip.setAttribute('aria-pressed', String(chosen.has(value)));
          set(option.allowedValues.filter(v => chosen.has(v)).join(','));
        });
        group.appendChild(chip);
      }
      return field(option.label, group, help);
    }
    let input;
    if (option.type === 'SELECT') {
      input = select(option.allowedValues.map(v => [v, v]), current);
    } else if (option.type === 'TEXT') {
      input = el('textarea', 'asub-input');
      input.value = current;
      input.rows = 3;
    } else {
      input = el('input', 'asub-input');
      input.type = option.type === 'NUMBER' ? 'number' : 'text';
      if (option.type === 'NUMBER' && option.allowedValues?.length === 2) [input.min, input.max] = option.allowedValues;
      input.value = current;
    }
    input.disabled = option.locked;
    if (option.key) input.dataset.key = option.key;
    input.addEventListener('change', () => set(input.value));
    return field(option.label, input, help);
  }

  function field(labelText, control, help) {
    const wrap = el('div', 'asub-field');
    const label = el('label', 'asub-field-label', labelText);
    const id = `asub-${widget.id || 'w'}-${Math.random().toString(36).slice(2, 8)}`;
    if (control.matches('input, select, textarea')) { control.id = id; label.htmlFor = id; }
    wrap.append(label, control);
    if (help) wrap.appendChild(el('div', 'asub-help', help));
    return wrap;
  }

  function select(pairs, value) {
    const s = el('select', 'asub-input');
    for (const [v, text] of pairs) {
      const o = el('option', null, text);
      o.value = v;
      if (v === value) o.selected = true;
      s.appendChild(o);
    }
    return s;
  }

  async function act(button, fn, reload = true, statusEl) {
    button.disabled = true;
    try {
      await fn();
      if (reload) simulate ? render() : await load();
    } catch (err) {
      if (statusEl) statusEl.textContent = err.message;
      else alert(err.message);
    } finally {
      button.disabled = false;
    }
  }

  function flash(card, message, isError = false) {
    card.querySelector('.asub-flash')?.remove();
    const note = el('div', 'asub-flash', message);
    if (isError) note.classList.add('asub-flash-error');
    note.setAttribute('role', 'status');
    card.appendChild(note);
    setTimeout(() => note.remove(), 8000);
  }

  load();
}
