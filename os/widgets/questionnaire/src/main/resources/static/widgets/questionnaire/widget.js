/**
 * Questionnaire widget — embeds an existing Questionnaire's full question set on one PageFlow
 * page. The visitor steps through every question inside the widget via /api/questionnaires/**;
 * the page only advances once they submit. Blocks the page's outcome buttons until then.
 */
const KEY = 'genesis-ai-key';
const HEADERS = { 'Content-Type': 'application/json', 'X-Api-Key': KEY };

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
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

async function saveAnswer(state, questionId, value) {
  await fetch(`/api/questionnaires/responses/${state.responseId}/answers/${questionId}`, {
    method: 'PUT', headers: HEADERS, body: JSON.stringify({ value })
  });
}

export async function mount(container, widget, ctx) {
  ctx.requireCompletion?.();
  const state = {
    questionnaireId: (widget.config || {}).questionnaireId, questions: [],
    responseId: null, index: 0, mediaRecorder: null, isRecording: false,
    pendingImageFile: null, answers: {}
  };
  // Element handles for the current step, replaced on every render.
  let refs = {};

  const release = (message) => {
    ctx.complete?.();
    renderStatus(container, message);
  };

  const renderTextStep = (body, q) => {
    const row = el('div', 'q-embed-text-row');
    const field = document.createElement(q.type === 'LONG_TEXT' ? 'textarea' : 'input');
    if (q.type !== 'LONG_TEXT') field.type = 'text';
    field.value = state.answers[q.id] || '';
    row.appendChild(field);
    refs.textField = field;

    if (q.allowVoice) {
      const micBtn = el('button', 'q-embed-mic-btn');
      micBtn.type = 'button';
      micBtn.title = 'Record answer';
      micBtn.appendChild(buildMicIcon());
      micBtn.onclick = () => toggleRecording();
      row.appendChild(micBtn);
      refs.micBtn = micBtn;
    }
    body.appendChild(row);

    if (q.allowVoice) {
      refs.voiceStatus = el('div', 'q-embed-voice-status');
      body.appendChild(refs.voiceStatus);
    }
  };

  const renderImageStep = (body, q) => {
    state.pendingImageFile = null;
    const existing = state.answers[q.id];
    const existingBlob = existing && existing.blobPath;

    const fileInput = document.createElement('input');
    fileInput.type = 'file';
    fileInput.accept = 'image/*';
    fileInput.style.display = 'none';
    body.appendChild(fileInput);

    const dropZone = el('div', 'q-embed-dropzone', 'Click to upload an image');
    dropZone.style.display = existingBlob ? 'none' : 'block';
    dropZone.onclick = () => fileInput.click();
    body.appendChild(dropZone);

    const previewWrap = el('div', 'q-embed-image-preview-wrap');
    previewWrap.style.display = existingBlob ? 'block' : 'none';
    const img = el('img', 'q-embed-image-preview');
    img.src = existingBlob || '';
    previewWrap.appendChild(img);

    const removeBtn = el('button', 'q-embed-nav-btn q-embed-nav-ghost', 'Remove');
    removeBtn.type = 'button';
    removeBtn.onclick = () => {
      state.pendingImageFile = null;
      delete state.answers[q.id];
      fileInput.value = '';
      dropZone.style.display = 'block';
      previewWrap.style.display = 'none';
    };
    previewWrap.appendChild(removeBtn);
    body.appendChild(previewWrap);

    fileInput.onchange = (e) => {
      const file = e.target.files[0];
      if (!file) return;
      state.pendingImageFile = file;
      const reader = new FileReader();
      reader.onload = () => {
        img.src = reader.result;
        dropZone.style.display = 'none';
        previewWrap.style.display = 'block';
      };
      reader.readAsDataURL(file);
    };
  };

  const renderSliderStep = (body, q) => {
    const config = q.configJson ? JSON.parse(q.configJson) : {};
    const min = config.min ?? 0, max = config.max ?? 100, step = config.step ?? 1;
    const existing = state.answers[q.id];
    const val = existing !== undefined ? existing : Math.round((min + max) / 2);

    const wrap = el('div', 'q-embed-slider-wrap');
    const input = document.createElement('input');
    input.type = 'range';
    input.min = min; input.max = max; input.step = step; input.value = val;
    refs.slider = input;

    const valueEl = el('div', 'q-embed-slider-value', String(val));
    input.oninput = () => { valueEl.textContent = input.value; };
    wrap.appendChild(input);

    const labels = el('div', 'q-embed-slider-labels');
    labels.append(el('span', null, config.minLabel || String(min)), el('span', null, config.maxLabel || String(max)));
    wrap.append(labels, valueEl);
    body.appendChild(wrap);
  };

  const renderChoiceStep = (body, q) => {
    const config = q.configJson ? JSON.parse(q.configJson) : {};
    const options = config.options || [];
    const existing = state.answers[q.id];
    const selected = q.type === 'MULTI_SELECT' ? (existing || []) : (existing !== undefined ? [existing] : []);

    const list = el('div', 'q-embed-choice-list');
    options.forEach(opt => {
      const btn = el('button', 'q-embed-choice-btn' + (selected.includes(opt) ? ' selected' : ''), opt);
      btn.type = 'button';
      btn.dataset.opt = opt;
      btn.onclick = () => {
        if (q.type === 'SINGLE_SELECT') {
          list.querySelectorAll('.q-embed-choice-btn').forEach(b => b.classList.remove('selected'));
          btn.classList.add('selected');
        } else {
          btn.classList.toggle('selected');
        }
      };
      list.appendChild(btn);
    });
    body.appendChild(list);
  };

  // Saves the current step's answer; false when a required answer is missing.
  const persistStep = async () => {
    const q = state.questions[state.index];

    if (q.type === 'SHORT_TEXT' || q.type === 'LONG_TEXT') {
      const value = refs.textField.value.trim();
      if (q.required && !value) { alert('This question requires an answer.'); return false; }
      if (value) {
        state.answers[q.id] = value;
        await saveAnswer(state, q.id, value);
      }
    } else if (q.type === 'IMAGE_UPLOAD') {
      if (state.pendingImageFile) {
        const form = new FormData();
        form.append('image', state.pendingImageFile);
        const res = await fetch(`/api/questionnaires/responses/${state.responseId}/answers/${q.id}/image`, {
          method: 'POST', headers: { 'X-Api-Key': KEY }, body: form
        }).then(r => r.json());
        state.answers[q.id] = JSON.parse(res.valueJson);
        state.pendingImageFile = null;
      } else if (q.required && !state.answers[q.id]) {
        alert('This question requires an image.'); return false;
      }
    } else if (q.type === 'SLIDER') {
      const value = Number(refs.slider.value);
      state.answers[q.id] = value;
      await saveAnswer(state, q.id, value);
    } else if (q.type === 'SINGLE_SELECT') {
      const btn = container.querySelector('.q-embed-choice-btn.selected');
      if (q.required && !btn) { alert('Please choose an option.'); return false; }
      if (btn) {
        state.answers[q.id] = btn.dataset.opt;
        await saveAnswer(state, q.id, btn.dataset.opt);
      }
    } else if (q.type === 'MULTI_SELECT') {
      const selected = Array.from(container.querySelectorAll('.q-embed-choice-btn.selected')).map(b => b.dataset.opt);
      if (q.required && !selected.length) { alert('Please choose at least one option.'); return false; }
      state.answers[q.id] = selected;
      await saveAnswer(state, q.id, selected);
    }
    return true;
  };

  const appendFooter = () => {
    const footer = el('div', 'q-embed-footer');
    const last = state.index === state.questions.length - 1;

    const backBtn = el('button', 'q-embed-nav-btn q-embed-nav-ghost', 'Back');
    backBtn.type = 'button';
    backBtn.disabled = state.index === 0;
    backBtn.onclick = () => { state.index--; renderStep(); };

    const nextBtn = el('button', 'q-embed-nav-btn q-embed-nav-primary', last ? 'Submit' : 'Next');
    nextBtn.type = 'button';
    nextBtn.onclick = async () => {
      nextBtn.disabled = true;
      const ok = await persistStep();
      nextBtn.disabled = false;
      if (!ok) return;

      if (last) {
        await fetch(`/api/questionnaires/responses/${state.responseId}/submit`, { method: 'POST', headers: HEADERS });
        ctx.complete?.();
        container.innerHTML = '';
        container.appendChild(el('div', 'q-embed-done', '✓ Completed — thank you.'));
        ctx.advance?.('next');
        return;
      }
      state.index++;
      renderStep();
    };
    footer.append(backBtn, nextBtn);
    container.appendChild(footer);
  };

  const renderStep = () => {
    container.innerHTML = '';
    refs = {};
    const q = state.questions[state.index];

    container.appendChild(el('div', 'q-embed-progress', 'Question ' + (state.index + 1) + ' of ' + state.questions.length));
    container.appendChild(el('div', 'q-embed-prompt', q.prompt + (q.required ? ' *' : '')));
    if (q.helpText) container.appendChild(el('div', 'q-embed-help', q.helpText));

    const body = el('div', 'q-embed-body');
    container.appendChild(body);

    if (q.type === 'SHORT_TEXT' || q.type === 'LONG_TEXT') renderTextStep(body, q);
    else if (q.type === 'IMAGE_UPLOAD') renderImageStep(body, q);
    else if (q.type === 'SLIDER') renderSliderStep(body, q);
    else if (q.type === 'SINGLE_SELECT' || q.type === 'MULTI_SELECT') renderChoiceStep(body, q);

    appendFooter();
  };

  const toggleRecording = async () => {
    const { micBtn, voiceStatus } = refs;
    if (state.isRecording) {
      state.mediaRecorder.stop();
      state.isRecording = false;
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
        const q = state.questions[state.index];
        const form = new FormData();
        form.append('audio', blob, 'answer.webm');
        try {
          const res = await fetch(`/api/questionnaires/responses/${state.responseId}/answers/${q.id}/voice`, {
            method: 'POST', headers: { 'X-Api-Key': KEY }, body: form
          }).then(r => r.json());
          const transcript = JSON.parse(res.valueJson);
          if (refs.textField) refs.textField.value = transcript;
          state.answers[q.id] = transcript;
          voiceStatus.textContent = 'Transcribed — feel free to edit before continuing.';
        } catch (err) {
          voiceStatus.textContent = 'Transcription failed — you can type your answer instead.';
        }
      };
      state.mediaRecorder.start();
      state.isRecording = true;
      micBtn.classList.add('recording');
      voiceStatus.textContent = 'Recording… click to stop.';
    } catch (err) {
      voiceStatus.textContent = 'Microphone access denied.';
    }
  };

  renderStatus(container, 'Loading…');
  try {
    const detail = await fetch(`/api/questionnaires/${state.questionnaireId}`, { headers: HEADERS }).then(r => r.json());
    state.questions = detail.questions || [];
    if (!state.questions.length) {
      release('This questionnaire has no questions configured.');
      return;
    }
    const startRes = await fetch(`/api/questionnaires/${state.questionnaireId}/responses${ctx.simulate ? '?simulated=true' : ''}`, {
      method: 'POST', headers: HEADERS
    }).then(r => r.json());
    state.responseId = startRes.id;
    try {
      await ctx.saveContext?.({ ['__questionnaireResponse:' + widget.id]: state.responseId });
    } catch (e) { /* best effort — the server can still find the response by its id */ }
    renderStep();
  } catch (e) {
    release('This questionnaire is not available right now.');
  }
}
