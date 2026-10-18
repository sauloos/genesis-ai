/**
 * Question widget — captures one text or choice answer into the flow session context,
 * keyed by this widget's id. A required question blocks the page's outcome buttons
 * until it has an answer.
 */
function parseChoices(json) {
  try {
    const arr = JSON.parse(json || '[]');
    return Array.isArray(arr) ? arr : [];
  } catch (e) {
    return [];
  }
}

export function mount(container, widget, ctx) {
  const config = widget.config || {};
  const required = config.required === true || config.required === 'true';
  const questionType = config.questionType || 'short_text';
  const inputId = 'q-' + widget.id;

  const save = (value) => {
    const patch = {};
    patch[widget.id] = value;
    ctx.saveContext?.(patch)?.catch(() => { /* best effort — gating reflects local state */ });
    const hasValue = Array.isArray(value) ? value.length > 0 : !!(value && String(value).trim());
    if (hasValue) ctx.complete?.();
    else if (required) ctx.requireCompletion?.();
  };

  const label = document.createElement('label');
  label.className = 'q-prompt';
  label.textContent = config.prompt || '';
  label.htmlFor = inputId;
  container.appendChild(label);

  if (config.helpText) {
    const help = document.createElement('div');
    help.className = 'q-help';
    help.textContent = config.helpText;
    container.appendChild(help);
  }

  if (required) ctx.requireCompletion?.();

  if (questionType === 'long_text') {
    const ta = document.createElement('textarea');
    ta.id = inputId;
    ta.rows = 4;
    ta.onchange = () => save(ta.value);
    container.appendChild(ta);
  } else if (questionType === 'single_select' || questionType === 'multi_select') {
    const single = questionType === 'single_select';
    const wrap = document.createElement('div');
    wrap.className = 'q-choices';
    parseChoices(config.choicesJson).forEach((choice, i) => {
      const row = document.createElement('label');
      row.className = 'q-choice';
      const input = document.createElement('input');
      input.type = single ? 'radio' : 'checkbox';
      input.name = inputId;
      input.id = inputId + '-' + i;
      input.value = choice;
      input.onchange = () => save(single
        ? choice
        : Array.from(wrap.querySelectorAll('input:checked')).map(el => el.value));
      row.append(input, document.createTextNode(choice));
      wrap.appendChild(row);
    });
    container.appendChild(wrap);
  } else {
    const input = document.createElement('input');
    input.type = 'text';
    input.id = inputId;
    input.onchange = () => save(input.value);
    container.appendChild(input);
  }
}
