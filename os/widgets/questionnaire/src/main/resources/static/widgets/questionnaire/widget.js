/**
 * Questionnaire widget — embeds an existing Questionnaire's question set on one PageFlow page.
 * The visitor steps through it inside the widget via /api/questionnaires/**; the page only
 * advances once they submit. Blocks the page's outcome buttons until then.
 *
 * Three things shape this file:
 *
 * A step is a group of questions, not one question. Questions sharing a `stepKey` render on
 * one screen, because some things only make sense answered together — a value proposition's
 * six blanks are one thought, not six interrogations.
 *
 * Which questions apply is decided by the server, never here. After every saved step the
 * widget re-asks /active-questions rather than evaluating conditions itself, so there is one
 * implementation of the skip rules and the client is not the thing deciding what a brief is
 * allowed to be missing. Position is therefore tracked by question id, not by index: the list
 * can grow or shrink underneath us between steps.
 *
 * Every answer type lives in one TYPES entry — how it renders, how its value is collected.
 * A type with no entry is reported rather than silently skipped.
 */
const KEY = 'genesis-ai-key';
const HEADERS = { 'Content-Type': 'application/json', 'X-Api-Key': KEY };

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function parseConfig(q) {
  if (!q.configJson) return {};
  try {
    return JSON.parse(q.configJson) || {};
  } catch (e) {
    return {};
  }
}

/** An answer the client has not given. Zero and false are answers; empty containers are not. */
function isEmpty(value) {
  if (value === undefined || value === null) return true;
  if (typeof value === 'string') return value.trim() === '';
  if (Array.isArray(value)) return value.length === 0;
  if (typeof value === 'object') return Object.keys(value).length === 0;
  return false;
}

function buildMicIcon() {
  const svgNS = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(svgNS, 'svg');
  svg.setAttribute('width', '14');
  svg.setAttribute('height', '14');
  svg.setAttribute('viewBox', '0 0 16 16');
  svg.setAttribute('fill', 'none');
  const rect = document.createElementNS(svgNS, 'rect');
  rect.setAttribute('x', '5');
  rect.setAttribute('y', '1');
  rect.setAttribute('width', '6');
  rect.setAttribute('height', '9');
  rect.setAttribute('rx', '3');
  rect.setAttribute('fill', 'currentColor');
  const path = document.createElementNS(svgNS, 'path');
  path.setAttribute('d', 'M3 7v1a5 5 0 0010 0V7M8 13v2');
  path.setAttribute('stroke', 'currentColor');
  path.setAttribute('stroke-width', '1.5');
  path.setAttribute('stroke-linecap', 'round');
  svg.append(rect, path);
  return svg;
}

function renderStatus(container, message) {
  container.innerHTML = '';
  container.appendChild(el('div', 'q-embed-status', message));
}

/** Consecutive questions sharing a step key are one screen; everything else is its own. */
function buildSteps(questions) {
  const steps = [];
  questions.forEach(q => {
    const last = steps[steps.length - 1];
    if (q.stepKey && last && last[0].stepKey === q.stepKey) {
      last.push(q);
    } else {
      steps.push([q]);
    }
  });
  return steps;
}

export async function mount(container, widget, ctx) {
  ctx.requireCompletion?.();

  const state = {
    questionnaireId: (widget.config || {}).questionnaireId,
    sections: [],
    allQuestions: [],   // every question, for resolving signal references
    questions: [],      // the ones that currently apply
    steps: [],
    stepIndex: 0,
    responseId: null,
    answers: {},        // by question id
    questionIdBySignal: {},
    mediaRecorder: null,
    recordingFor: null
  };

  // Per-question element handles for the current step, rebuilt on every render.
  let refs = {};

  const release = (message) => {
    ctx.complete?.();
    renderStatus(container, message);
  };

  const signalValue = (signalKey) => state.answers[state.questionIdBySignal[signalKey]];

  const showError = (q, message) => {
    const node = refs[q.id] && refs[q.id].error;
    if (node) node.textContent = message || '';
  };

  const clearErrors = () => Object.values(refs).forEach(r => { if (r.error) r.error.textContent = ''; });

  // ── Renderers ─────────────────────────────────────────────────────────────

  const renderText = (body, q) => {
    const config = parseConfig(q);
    const row = el('div', 'q-embed-text-row');
    const field = document.createElement(q.type === 'LONG_TEXT' ? 'textarea' : 'input');
    if (q.type === 'URL') field.type = 'url';
    else if (q.type !== 'LONG_TEXT') field.type = 'text';
    if (config.placeholder) field.placeholder = config.placeholder;
    if (config.maxLength) field.maxLength = config.maxLength;
    field.value = state.answers[q.id] || '';
    row.appendChild(field);
    refs[q.id].field = field;

    if (q.allowVoice) {
      const micBtn = el('button', 'q-embed-mic-btn');
      micBtn.type = 'button';
      micBtn.title = 'Record answer';
      micBtn.appendChild(buildMicIcon());
      micBtn.onclick = () => toggleRecording(q);
      row.appendChild(micBtn);
      refs[q.id].micBtn = micBtn;
    }
    body.appendChild(row);

    if (q.allowVoice) {
      refs[q.id].voiceStatus = el('div', 'q-embed-voice-status');
      body.appendChild(refs[q.id].voiceStatus);
    }
  };

  const collectText = (q) => refs[q.id].field.value.trim();

  const collectUrl = (q) => {
    const value = refs[q.id].field.value.trim();
    if (!value) return '';
    const withScheme = /^https?:\/\//i.test(value) ? value : 'https://' + value;
    try {
      new URL(withScheme);
    } catch (e) {
      return { error: 'That does not look like a web address.' };
    }
    return withScheme;
  };

  const renderSlider = (body, q) => {
    const config = parseConfig(q);
    const min = config.min ?? 0, max = config.max ?? 100, step = config.step ?? 1;
    const labels = Array.isArray(config.labels) ? config.labels : null;
    const existing = state.answers[q.id];
    const val = existing !== undefined ? existing : Math.round((min + max) / 2);

    const wrap = el('div', 'q-embed-slider-wrap');
    const input = document.createElement('input');
    input.type = 'range';
    input.min = min; input.max = max; input.step = step; input.value = val;
    refs[q.id].slider = input;

    // A number means nothing on "classic … modern"; the word under the handle is the answer
    // the client is actually giving, so show that where the scale has words.
    const describe = (v) => (labels && labels[v - min] !== undefined ? labels[v - min] : String(v));
    const valueEl = el('div', 'q-embed-slider-value', describe(Number(val)));
    input.oninput = () => { valueEl.textContent = describe(Number(input.value)); };
    wrap.appendChild(input);

    // "Warm … cool" means nothing as two words. Where the scale IS a spectrum, the config
    // carries the spectrum's colours and the band shows it — inline, because a theme cannot
    // know a client's artwork and widget CSS may hold no colour of its own.
    if (Array.isArray(config.trackColors) && config.trackColors.length > 1) {
      const band = el('div', 'q-embed-slider-band');
      band.style.background = 'linear-gradient(90deg, ' + config.trackColors.join(', ') + ')';
      wrap.appendChild(band);
    }

    const ends = el('div', 'q-embed-slider-labels');
    ends.append(el('span', null, config.minLabel || describe(min)),
      el('span', null, config.maxLabel || describe(max)));
    wrap.append(ends, valueEl);
    body.appendChild(wrap);
  };

  const renderChoice = (body, q) => {
    const config = parseConfig(q);
    const options = (config.options || []).map(normaliseOption);
    const existing = state.answers[q.id];
    const selected = q.type === 'MULTI_SELECT'
      ? (Array.isArray(existing) ? existing : [])
      : (existing !== undefined ? [existing] : []);

    const list = el('div', 'q-embed-choice-list');
    const chosen = new Set(selected);
    options.forEach(opt => {
      const btn = el('button', 'q-embed-choice-btn' + (chosen.has(opt.value) ? ' selected' : ''), opt.label);
      btn.type = 'button';
      btn.onclick = () => {
        if (q.type === 'SINGLE_SELECT') {
          chosen.clear();
          chosen.add(opt.value);
          list.querySelectorAll('.q-embed-choice-btn').forEach(b => b.classList.remove('selected'));
          btn.classList.add('selected');
        } else if (chosen.has(opt.value)) {
          chosen.delete(opt.value);
          btn.classList.remove('selected');
        } else {
          chosen.add(opt.value);
          btn.classList.add('selected');
        }
      };
      list.appendChild(btn);
    });
    body.appendChild(list);
    refs[q.id].chosen = chosen;
  };

  /**
   * Fill {signal.key} placeholders from what the client has already said. Shared by the
   * synthesis, which shows a whole paragraph back, and by a type specimen, which sets the
   * client's own sentence so they judge their words rather than a typographer's pangram.
   */
  const fillTemplate = (text, missing) =>
    (text || '').replace(/\{([^{}]+)\}/g, (whole, signalKey) => {
      const value = signalValue(signalKey.trim());
      if (isEmpty(value)) return missing;
      return Array.isArray(value) ? joinWords(value) : String(value);
    });

  /** "a", "a and b", "a, b and c" — a template reads a multi-pick back as a sentence, so a
   *  bare comma join ("protect, change people") is wrong where the words are the subject. */
  const joinWords = (values) => {
    const parts = values.map(String).filter((v) => v.trim() !== '');
    if (parts.length < 2) return parts.join('');
    return parts.slice(0, -1).join(', ') + ' and ' + parts[parts.length - 1];
  };

  /** Options may be plain strings or objects; everything downstream wants the object form. */
  function normaliseOption(opt) {
    if (typeof opt === 'string') return { value: opt, label: opt };
    return {
      value: opt.value !== undefined ? opt.value : opt.label,
      label: opt.label !== undefined ? opt.label : String(opt.value),
      icon: opt.icon,
      imageUrl: opt.imageUrl,
      colors: opt.colors,
      fontFamily: opt.fontFamily,
      sampleText: opt.sampleText,
      fallbackText: opt.fallbackText,
      note: opt.note
    };
  }

  const collectChoice = (q) => {
    const chosen = Array.from(refs[q.id].chosen);
    return q.type === 'SINGLE_SELECT' ? (chosen[0] ?? '') : chosen;
  };

  /**
   * A word bank with room to add your own. The bank is a prompt, not a ceiling: a client
   * whose word is missing would otherwise pick the nearest one on the list, which quietly
   * replaces their vocabulary with ours.
   */
  const renderChips = (body, q) => {
    const config = parseConfig(q);
    const max = config.maxSelect || 0;
    const existing = Array.isArray(state.answers[q.id]) ? state.answers[q.id] : [];
    const chosen = new Set(existing);
    const bank = (config.bank || config.options || []).map(o => (typeof o === 'string' ? o : o.label));
    existing.forEach(v => { if (!bank.includes(v)) bank.push(v); });

    const counter = el('div', 'q-embed-chip-counter');
    const list = el('div', 'q-embed-chip-list');

    const updateCounter = () => {
      counter.textContent = max
        ? chosen.size + ' of up to ' + max + ' chosen'
        : chosen.size + ' chosen';
    };

    const addChip = (word) => {
      const chip = el('button', 'q-embed-chip' + (chosen.has(word) ? ' selected' : ''), word);
      chip.type = 'button';
      chip.onclick = () => {
        if (chosen.has(word)) {
          chosen.delete(word);
          chip.classList.remove('selected');
        } else {
          if (max && chosen.size >= max) {
            showError(q, 'That is the most you can pick — deselect one first.');
            return;
          }
          chosen.add(word);
          chip.classList.add('selected');
        }
        showError(q, '');
        updateCounter();
      };
      list.appendChild(chip);
    };

    bank.forEach(addChip);
    body.appendChild(list);

    const addRow = el('div', 'q-embed-chip-add');
    const input = document.createElement('input');
    input.type = 'text';
    input.placeholder = config.addPlaceholder || 'Add your own…';
    const addBtn = el('button', 'q-embed-chip-add-btn', 'Add');
    addBtn.type = 'button';
    const submitWord = () => {
      const word = input.value.trim();
      if (!word) return;
      if (!bank.includes(word)) {
        bank.push(word);
        addChip(word);
      }
      if (!chosen.has(word)) {
        if (max && chosen.size >= max) {
          showError(q, 'That is the most you can pick — deselect one first.');
          return;
        }
        chosen.add(word);
        Array.from(list.children).forEach(c => {
          if (c.textContent === word) c.classList.add('selected');
        });
      }
      input.value = '';
      updateCounter();
    };
    addBtn.onclick = submitWord;
    input.onkeydown = (e) => { if (e.key === 'Enter') { e.preventDefault(); submitWord(); } };
    addRow.append(input, addBtn);
    body.append(addRow, counter);
    updateCounter();

    refs[q.id].chosen = chosen;
  };

  const collectChips = (q) => Array.from(refs[q.id].chosen);

  /**
   * Ranking a set the client picked earlier, resolved through `sourceSignal` — which is why
   * signal keys exist: the ranking question refers to what the chips question captured, not
   * to a question id that could change.
   *
   * Clicking assigns the next place rather than dragging, because dragging is a poor bet on
   * a phone and unusable by keyboard.
   */
  const renderRanking = (body, q) => {
    const config = parseConfig(q);
    const source = config.sourceSignal ? signalValue(config.sourceSignal) : null;
    const candidates = (Array.isArray(source) && source.length ? source
      : (config.options || []).map(o => (typeof o === 'string' ? o : o.label)));
    const limit = config.pick || candidates.length;

    if (!candidates.length) {
      body.appendChild(el('div', 'q-embed-help',
        'Nothing to rank yet — this follows on from an earlier question.'));
      refs[q.id].ranked = [];
      return;
    }

    const existing = Array.isArray(state.answers[q.id]) ? state.answers[q.id] : [];
    const ranked = existing.filter(v => candidates.includes(v));
    const list = el('div', 'q-embed-rank-list');
    const hint = el('div', 'q-embed-chip-counter');

    const paint = () => {
      hint.textContent = ranked.length >= limit
        ? 'In order. Click a choice to take it back out.'
        : 'Click them in order — ' + (limit - ranked.length) + ' to go.';
      Array.from(list.children).forEach(row => {
        const place = ranked.indexOf(row.dataset.value);
        row.classList.toggle('selected', place >= 0);
        row.firstChild.textContent = place >= 0 ? String(place + 1) : '';
      });
    };

    candidates.forEach(value => {
      const row = el('button', 'q-embed-rank-row');
      row.type = 'button';
      row.dataset.value = value;
      row.append(el('span', 'q-embed-rank-place'), el('span', 'q-embed-rank-label', value));
      row.onclick = () => {
        const place = ranked.indexOf(value);
        if (place >= 0) {
          ranked.splice(place, 1);
        } else if (ranked.length < limit) {
          ranked.push(value);
        } else {
          showError(q, 'You have ranked as many as this question takes.');
          return;
        }
        showError(q, '');
        paint();
      };
      list.appendChild(row);
    });

    body.append(list, hint);
    paint();
    refs[q.id].ranked = ranked;
  };

  const collectRanking = (q) => (refs[q.id].ranked || []).slice();

  /**
   * One tile grid behind four answer types — icons, reference imagery, palettes and type
   * specimens all ask the same question ("which of these") and differ only in what a tile
   * shows. Palette colours and specimen faces arrive as config data and are applied inline,
   * because a theme cannot know a client's artwork and widget CSS may hold no colour of its own.
   */
  const renderTiles = (body, q) => {
    const config = parseConfig(q);
    const options = (config.options || []).map(normaliseOption);
    const max = config.maxSelect || 1;
    const existing = state.answers[q.id];
    const chosen = new Set(Array.isArray(existing) ? existing : (existing !== undefined ? [existing] : []));

    const grid = el('div', 'q-embed-tile-grid');
    if (config.columns) grid.style.setProperty('--q-embed-tile-columns', String(config.columns));

    options.forEach(opt => {
      const tile = el('button', 'q-embed-tile' + (chosen.has(opt.value) ? ' selected' : ''));
      tile.type = 'button';
      tile.appendChild(buildTileFace(q.type, opt));
      tile.appendChild(el('span', 'q-embed-tile-label', opt.label));
      if (opt.note) tile.appendChild(el('span', 'q-embed-tile-note', opt.note));
      tile.onclick = () => {
        if (chosen.has(opt.value)) {
          chosen.delete(opt.value);
          tile.classList.remove('selected');
        } else {
          if (max === 1) {
            chosen.clear();
            grid.querySelectorAll('.q-embed-tile').forEach(t => t.classList.remove('selected'));
          } else if (chosen.size >= max) {
            showError(q, 'You can choose up to ' + max + ' — deselect one first.');
            return;
          }
          chosen.add(opt.value);
          tile.classList.add('selected');
        }
        showError(q, '');
      };
      grid.appendChild(tile);
    });

    body.appendChild(grid);
    if (max > 1) body.appendChild(el('div', 'q-embed-chip-counter', 'Choose up to ' + max + '.'));
    refs[q.id].chosen = chosen;
    refs[q.id].single = max === 1;
  };

  function buildTileFace(type, opt) {
    if (type === 'PALETTE_CHOICE') {
      const strip = el('span', 'q-embed-swatch-strip');
      (opt.colors || []).forEach(colour => {
        const swatch = el('span', 'q-embed-swatch');
        swatch.style.background = colour;
        strip.appendChild(swatch);
      });
      return strip;
    }
    if (type === 'TYPE_SPECIMEN') {
      const text = fillTemplate(opt.sampleText, opt.fallbackText || 'Handgloves');
      const specimen = el('span', 'q-embed-specimen', text.trim() || 'Handgloves');
      if (opt.fontFamily) specimen.style.fontFamily = opt.fontFamily;
      return specimen;
    }
    if (type === 'IMAGE_CHOICE' || opt.imageUrl) {
      const frame = el('span', 'q-embed-tile-image');
      if (opt.imageUrl) {
        const img = el('img');
        img.src = opt.imageUrl;
        img.alt = opt.label;
        img.loading = 'lazy';
        frame.appendChild(img);
      }
      return frame;
    }
    return el('span', 'q-embed-tile-icon', opt.icon || '');
  }

  const collectTiles = (q) => {
    const chosen = Array.from(refs[q.id].chosen);
    return refs[q.id].single ? (chosen[0] ?? '') : chosen;
  };

  /**
   * Uploads. An image keeps its single-object answer shape; a file upload is always a list,
   * because "your current logo" arrives as an AI file plus a PDF plus a PNG more often than
   * it arrives as one file.
   */
  const renderUpload = (body, q) => {
    const config = parseConfig(q);
    const multiple = q.type === 'FILE_UPLOAD';
    const existing = state.answers[q.id];

    const fileInput = document.createElement('input');
    fileInput.type = 'file';
    fileInput.accept = config.accept || (multiple ? '' : 'image/*');
    if (multiple) fileInput.multiple = true;
    fileInput.style.display = 'none';
    body.appendChild(fileInput);

    const dropZone = el('div', 'q-embed-dropzone',
      config.cta || (multiple ? 'Click to choose files' : 'Click to upload an image'));
    dropZone.onclick = () => fileInput.click();
    body.appendChild(dropZone);

    const preview = el('div', 'q-embed-upload-list');
    body.appendChild(preview);

    refs[q.id].pending = [];

    const paint = () => {
      preview.innerHTML = '';
      const saved = multiple ? (Array.isArray(existing) ? existing : [])
        : (existing && existing.blobPath ? [existing] : []);

      saved.forEach(item => {
        const row = el('div', 'q-embed-upload-row');
        if (!multiple || /\.(png|jpe?g|gif|webp|svg)$/i.test(item.blobPath || '')) {
          const img = el('img', 'q-embed-upload-thumb');
          img.src = item.blobPath;
          img.alt = item.name || 'uploaded file';
          row.appendChild(img);
        }
        row.appendChild(el('span', 'q-embed-upload-name', item.name || 'Uploaded'));
        const removeBtn = el('button', 'q-embed-nav-btn q-embed-nav-ghost', 'Remove');
        removeBtn.type = 'button';
        removeBtn.onclick = async () => {
          const next = multiple ? saved.filter(s => s !== item) : null;
          await saveAnswer(q.id, multiple ? next : '');
          state.answers[q.id] = multiple ? next : undefined;
          renderStep();
        };
        row.appendChild(removeBtn);
        preview.appendChild(row);
      });

      refs[q.id].pending.forEach(file => {
        const row = el('div', 'q-embed-upload-row');
        row.appendChild(el('span', 'q-embed-upload-name', file.name));
        row.appendChild(el('span', 'q-embed-upload-pending', 'ready to upload'));
        preview.appendChild(row);
      });
    };

    fileInput.onchange = (e) => {
      const picked = Array.from(e.target.files || []);
      if (!picked.length) return;
      const maxFiles = config.maxFiles || 10;
      refs[q.id].pending = multiple ? picked.slice(0, maxFiles) : picked.slice(0, 1);
      if (multiple && picked.length > maxFiles) {
        showError(q, 'Taking the first ' + maxFiles + ' files.');
      }
      paint();
    };

    paint();
  };

  /** Uploads happen on leaving the step, so a client who goes back loses nothing. */
  const saveUpload = async (q) => {
    const pending = refs[q.id].pending || [];
    if (!pending.length) return state.answers[q.id];

    const multiple = q.type === 'FILE_UPLOAD';
    const form = new FormData();
    if (multiple) {
      pending.forEach(file => form.append('files', file));
    } else {
      form.append('image', pending[0]);
    }
    const res = await fetch(
      `/api/questionnaires/responses/${state.responseId}/answers/${q.id}/${multiple ? 'file' : 'image'}`,
      { method: 'POST', headers: { 'X-Api-Key': KEY }, body: form }
    );
    if (!res.ok) return { error: 'That upload did not go through — please try again.' };
    const body = await res.json();
    refs[q.id].pending = [];
    return JSON.parse(body.valueJson);
  };

  /**
   * A synthesis is shown back, not asked. The template is filled from what the client has
   * already said, and they correct the wording — which is the workshop's own move: say it
   * back to them and let them tell you what you got wrong.
   */
  const renderSynthesis = (body, q) => {
    const config = parseConfig(q);
    const filled = fillTemplate(config.template, '…');

    const existing = state.answers[q.id];
    const field = document.createElement('textarea');
    field.className = 'q-embed-synthesis';
    field.value = !isEmpty(existing) ? existing : filled;
    field.rows = config.rows || 4;
    body.appendChild(field);
    body.appendChild(el('div', 'q-embed-help',
      config.note || 'This is in our words — change anything that does not sound like you.'));
    refs[q.id].field = field;
  };

  // How every answer type renders and how its value is read back. `save` replaces `collect`
  // for types whose value only exists after a round trip.
  const TYPES = {
    SHORT_TEXT: { render: renderText, collect: collectText },
    LONG_TEXT: { render: renderText, collect: collectText },
    URL: { render: renderText, collect: collectUrl },
    SLIDER: { render: renderSlider, collect: (q) => Number(refs[q.id].slider.value) },
    SINGLE_SELECT: { render: renderChoice, collect: collectChoice },
    MULTI_SELECT: { render: renderChoice, collect: collectChoice },
    CHIP_PICK: { render: renderChips, collect: collectChips },
    RANKING: { render: renderRanking, collect: collectRanking },
    ICON_GRID: { render: renderTiles, collect: collectTiles },
    IMAGE_CHOICE: { render: renderTiles, collect: collectTiles },
    PALETTE_CHOICE: { render: renderTiles, collect: collectTiles },
    TYPE_SPECIMEN: { render: renderTiles, collect: collectTiles },
    IMAGE_UPLOAD: { render: renderUpload, save: saveUpload },
    FILE_UPLOAD: { render: renderUpload, save: saveUpload },
    SYNTHESIS: { render: renderSynthesis, collect: collectText }
  };

  // ── Saving and navigation ─────────────────────────────────────────────────

  async function saveAnswer(questionId, value) {
    await fetch(`/api/questionnaires/responses/${state.responseId}/answers/${questionId}`, {
      method: 'PUT', headers: HEADERS, body: JSON.stringify({ value })
    });
  }

  /** Saves every answer on the current step; false when one is missing or malformed. */
  const persistStep = async () => {
    clearErrors();
    const step = state.steps[state.stepIndex];

    for (const q of step) {
      const handler = TYPES[q.type];
      if (!handler) {
        if (q.required) {
          showError(q, 'This question cannot be answered in this version of the questionnaire. '
            + 'Please let us know.');
          return false;
        }
        continue;
      }

      let value;
      if (handler.save) {
        value = await handler.save(q);
      } else {
        value = handler.collect(q);
      }

      if (value && value.error) {
        showError(q, value.error);
        return false;
      }
      if (isEmpty(value)) {
        if (q.required) {
          showError(q, 'This one we do need.');
          return false;
        }
        continue;
      }

      state.answers[q.id] = value;
      if (!handler.save) await saveAnswer(q.id, value);
    }
    return true;
  };

  /** Re-asks the server which questions still apply, keeping the client where it was. */
  const refreshActive = async (anchorQuestionId) => {
    const active = await fetch(`/api/questionnaires/responses/${state.responseId}/active-questions`,
      { headers: HEADERS }).then(r => r.json());
    state.questions = Array.isArray(active) ? active : [];
    state.steps = buildSteps(state.questions);

    if (anchorQuestionId) {
      const found = state.steps.findIndex(step => step.some(q => q.id === anchorQuestionId));
      if (found >= 0) state.stepIndex = found;
    }
    if (state.stepIndex > state.steps.length) state.stepIndex = state.steps.length;
  };

  const submit = async () => {
    await fetch(`/api/questionnaires/responses/${state.responseId}/submit`,
      { method: 'POST', headers: HEADERS });
    ctx.complete?.();
    container.innerHTML = '';
    container.appendChild(el('div', 'q-embed-done', '✓ Completed — thank you.'));
    ctx.advance?.('next');
  };

  // ── Chrome ────────────────────────────────────────────────────────────────

  /**
   * Progress is shown by act, not by question count. With conditional questions the total is
   * not knowable up front, and a counter that revises itself downwards mid-questionnaire
   * reads as a fault. The acts are fixed, so they can be stated honestly.
   */
  const appendProgress = () => {
    const step = state.steps[state.stepIndex];
    if (!state.sections.length) {
      container.appendChild(el('div', 'q-embed-progress',
        'Question ' + (state.stepIndex + 1) + ' of ' + state.steps.length));
      return;
    }

    const sectionKey = step[0].sectionKey;
    const current = state.sections.findIndex(s => s.key === sectionKey);
    const section = state.sections[current];

    container.appendChild(el('div', 'q-embed-progress', section
      ? 'Part ' + (current + 1) + ' of ' + state.sections.length + ' · ' + section.title
      : 'Part ' + (state.stepIndex + 1)));

    const track = el('div', 'q-embed-acts');
    state.sections.forEach((s, i) => {
      const bar = el('span', 'q-embed-act'
        + (i < current ? ' done' : '') + (i === current ? ' current' : ''));
      bar.title = s.title;
      track.appendChild(bar);
    });
    container.appendChild(track);

    if (section && section.intent && isFirstStepOfSection(current)) {
      container.appendChild(el('div', 'q-embed-act-intent', section.intent));
    }
  };

  const isFirstStepOfSection = (sectionIndex) => {
    const key = state.sections[sectionIndex].key;
    return state.steps.findIndex(step => step[0].sectionKey === key) === state.stepIndex;
  };

  const appendFooter = () => {
    const footer = el('div', 'q-embed-footer');
    const last = state.stepIndex === state.steps.length - 1;

    const backBtn = el('button', 'q-embed-nav-btn q-embed-nav-ghost', 'Back');
    backBtn.type = 'button';
    backBtn.disabled = state.stepIndex === 0;
    backBtn.onclick = () => { state.stepIndex--; renderStep(); };

    const nextBtn = el('button', 'q-embed-nav-btn q-embed-nav-primary', last ? 'Submit' : 'Next');
    nextBtn.type = 'button';
    nextBtn.onclick = async () => {
      nextBtn.disabled = true;
      let ok = false;
      try {
        ok = await persistStep();
      } finally {
        nextBtn.disabled = false;
      }
      if (!ok) return;

      // Answering this step may have brought later questions into play or taken them out,
      // so the next step is whatever follows *this* step in the list the server just gave us.
      const anchor = state.steps[state.stepIndex][0].id;
      await refreshActive(anchor);
      state.stepIndex++;

      if (state.stepIndex >= state.steps.length) {
        await submit();
        return;
      }
      renderStep();
    };
    footer.append(backBtn, nextBtn);
    container.appendChild(footer);
  };

  const renderStep = () => {
    container.innerHTML = '';
    refs = {};
    const step = state.steps[state.stepIndex];
    if (!step) {
      release('Nothing further to answer.');
      return;
    }

    appendProgress();

    step.forEach((q, i) => {
      refs[q.id] = {};
      const block = el('div', 'q-embed-question' + (i > 0 ? ' stacked' : ''));
      block.appendChild(el('div', 'q-embed-prompt', q.prompt + (q.required ? ' *' : '')));
      if (q.helpText) block.appendChild(el('div', 'q-embed-help', q.helpText));

      const body = el('div', 'q-embed-body');
      block.appendChild(body);
      (TYPES[q.type] ? TYPES[q.type].render : renderUnsupported)(body, q);

      refs[q.id].error = el('div', 'q-embed-error');
      block.appendChild(refs[q.id].error);
      container.appendChild(block);
    });

    appendFooter();
  };

  // A type this build has no renderer for. Says so rather than showing an empty step, because
  // the alternative is a client staring at a prompt with nothing to answer it with.
  const renderUnsupported = (body, q) => {
    body.appendChild(el('div', 'q-embed-help',
      'This question needs a newer version of the questionnaire. Please let us know — '
      + 'question type: ' + q.type));
  };

  const toggleRecording = async (q) => {
    const { micBtn, voiceStatus } = refs[q.id];
    if (state.recordingFor) {
      state.mediaRecorder.stop();
      state.recordingFor = null;
      micBtn.classList.remove('recording');
      return;
    }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      state.mediaRecorder = new MediaRecorder(stream);
      const audioChunks = [];
      state.mediaRecorder.ondataavailable = (e) => audioChunks.push(e.data);
      state.mediaRecorder.onstop = async () => {
        stream.getTracks().forEach(t => t.stop());
        const blob = new Blob(audioChunks, { type: 'audio/webm' });
        voiceStatus.textContent = 'Transcribing…';
        const form = new FormData();
        form.append('audio', blob, 'answer.webm');
        try {
          const res = await fetch(`/api/questionnaires/responses/${state.responseId}/answers/${q.id}/voice`, {
            method: 'POST', headers: { 'X-Api-Key': KEY }, body: form
          }).then(r => r.json());
          const transcript = JSON.parse(res.valueJson);
          if (refs[q.id].field) refs[q.id].field.value = transcript;
          state.answers[q.id] = transcript;
          voiceStatus.textContent = 'Transcribed — feel free to edit before continuing.';
        } catch (err) {
          voiceStatus.textContent = 'Transcription failed — you can type your answer instead.';
        }
      };
      state.mediaRecorder.start();
      state.recordingFor = q.id;
      micBtn.classList.add('recording');
      voiceStatus.textContent = 'Recording… click to stop.';
    } catch (err) {
      voiceStatus.textContent = 'Microphone access denied.';
    }
  };

  // ── Boot ──────────────────────────────────────────────────────────────────

  renderStatus(container, 'Loading…');
  try {
    const detail = await fetch(`/api/questionnaires/${state.questionnaireId}`, { headers: HEADERS })
      .then(r => r.json());

    state.allQuestions = detail.questions || [];
    state.allQuestions.forEach(q => {
      if (q.signalKey) state.questionIdBySignal[q.signalKey] = q.id;
    });
    try {
      const sections = JSON.parse((detail.questionnaire || {}).sectionsJson || '[]');
      if (Array.isArray(sections)) state.sections = sections;
    } catch (e) { /* a questionnaire without acts falls back to a step count */ }

    if (!state.allQuestions.length) {
      release('This questionnaire has no questions configured.');
      return;
    }

    const startRes = await fetch(
      `/api/questionnaires/${state.questionnaireId}/responses${ctx.simulate ? '?simulated=true' : ''}`,
      { method: 'POST', headers: HEADERS }
    ).then(r => r.json());
    state.responseId = startRes.id;

    try {
      await ctx.saveContext?.({ ['__questionnaireResponse:' + widget.id]: state.responseId });
    } catch (e) { /* best effort — the server can still find the response by its id */ }

    await refreshActive();
    if (!state.steps.length) {
      release('This questionnaire has no questions that apply right now.');
      return;
    }
    renderStep();
  } catch (e) {
    release('This questionnaire is not available right now.');
  }
}
