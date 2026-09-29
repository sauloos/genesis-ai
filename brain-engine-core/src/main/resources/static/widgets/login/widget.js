const HEADERS = { 'Content-Type': 'application/json', 'X-Api-Key': 'genesis-ai-key' };

function configBool(value, defaultVal) {
  if (value === undefined || value === null || value === '') return defaultVal;
  return value === true || value === 'true';
}

let googleScriptPromise = null;
function loadGoogleScript() {
  if (googleScriptPromise) return googleScriptPromise;
  googleScriptPromise = new Promise((resolve, reject) => {
    if (window.google && window.google.accounts && window.google.accounts.id) { resolve(); return; }
    const s = document.createElement('script');
    s.src = 'https://accounts.google.com/gsi/client';
    s.async = true; s.defer = true;
    s.onload = () => resolve();
    s.onerror = () => reject(new Error('Failed to load Google Identity script'));
    document.head.appendChild(s);
  });
  return googleScriptPromise;
}

async function handleGoogleCredential(response, errEl, onOutcome) {
  try {
    const res = await fetch('/api/auth/google', {
      method: 'POST', headers: HEADERS, body: JSON.stringify({ idToken: response.credential })
    });
    const body = await res.json();
    if (!res.ok || body.error) {
      if (errEl) errEl.textContent = body.error || 'Google sign-in failed. Please try again.';
      return;
    }
    onOutcome();
  } catch (e) {
    if (errEl) errEl.textContent = 'Google sign-in failed. Please try again.';
  }
}

function renderForm(container, config, onOutcome) {
  container.innerHTML = '';
  let mode = config.initialTab === 'signup' ? 'signup' : 'signin';

  const tabs = document.createElement('div');
  tabs.className = 'wl-tabs';
  const signinTab = document.createElement('button');
  signinTab.type = 'button'; signinTab.className = 'wl-tab' + (mode === 'signin' ? ' active' : ''); signinTab.textContent = 'Sign in';
  const signupTab = document.createElement('button');
  signupTab.type = 'button'; signupTab.className = 'wl-tab' + (mode === 'signup' ? ' active' : ''); signupTab.textContent = 'Create account';
  tabs.appendChild(signinTab); tabs.appendChild(signupTab);
  container.appendChild(tabs);

  const nameField = document.createElement('div');
  nameField.className = 'wl-field'; nameField.style.display = mode === 'signup' ? 'flex' : 'none';
  const nameLabel = document.createElement('label'); nameLabel.textContent = 'Name';
  const nameInput = document.createElement('input'); nameInput.type = 'text'; nameInput.placeholder = 'Your name';
  nameField.appendChild(nameLabel); nameField.appendChild(nameInput);
  container.appendChild(nameField);

  const emailField = document.createElement('div');
  emailField.className = 'wl-field';
  const emailLabel = document.createElement('label'); emailLabel.textContent = 'Email';
  const emailInput = document.createElement('input'); emailInput.type = 'email'; emailInput.placeholder = 'your@email.com';
  emailField.appendChild(emailLabel); emailField.appendChild(emailInput);
  container.appendChild(emailField);

  const passwordField = document.createElement('div');
  passwordField.className = 'wl-field';
  const passwordLabel = document.createElement('label'); passwordLabel.textContent = 'Password';
  const passwordInput = document.createElement('input'); passwordInput.type = 'password'; passwordInput.placeholder = 'Min 8 characters';
  passwordField.appendChild(passwordLabel); passwordField.appendChild(passwordInput);
  container.appendChild(passwordField);

  const errEl = document.createElement('div');
  errEl.className = 'wl-err';
  container.appendChild(errEl);

  const submitBtn = document.createElement('button');
  submitBtn.type = 'button';
  submitBtn.className = 'wl-submit-btn';
  submitBtn.textContent = mode === 'signup' ? 'Create account' : 'Sign in';
  container.appendChild(submitBtn);

  signinTab.onclick = () => {
    mode = 'signin';
    signinTab.classList.add('active'); signupTab.classList.remove('active');
    nameField.style.display = 'none';
    submitBtn.textContent = 'Sign in';
    errEl.textContent = '';
  };
  signupTab.onclick = () => {
    mode = 'signup';
    signupTab.classList.add('active'); signinTab.classList.remove('active');
    nameField.style.display = 'flex';
    submitBtn.textContent = 'Create account';
    errEl.textContent = '';
  };

  submitBtn.onclick = async () => {
    errEl.textContent = '';
    const email = emailInput.value.trim();
    const password = passwordInput.value;
    const name = nameInput.value.trim();
    if (!email || !password) { errEl.textContent = 'Please fill in email and password.'; return; }
    if (mode === 'signup' && !name) { errEl.textContent = 'Please enter your name.'; return; }
    if (mode === 'signup' && password.length < 8) { errEl.textContent = 'Password must be at least 8 characters.'; return; }

    submitBtn.disabled = true;
    submitBtn.textContent = mode === 'signup' ? 'Creating account…' : 'Signing in…';
    try {
      const endpoint = mode === 'signup' ? '/api/auth/register' : '/api/auth/login';
      const res = await fetch(endpoint, {
        method: 'POST', headers: HEADERS, body: JSON.stringify({ email, name, password })
      });
      const body = await res.json();
      if (!res.ok || body.error) {
        errEl.textContent = body.error || 'Something went wrong. Please try again.';
        submitBtn.disabled = false;
        submitBtn.textContent = mode === 'signup' ? 'Create account' : 'Sign in';
        return;
      }
      onOutcome();
    } catch (e) {
      errEl.textContent = 'Something went wrong. Please try again.';
      submitBtn.disabled = false;
      submitBtn.textContent = mode === 'signup' ? 'Create account' : 'Sign in';
    }
  };

  if (configBool(config.googleEnabled, true) && window.__PF_GOOGLE_CLIENT_ID__) {
    const divider = document.createElement('div');
    divider.className = 'wl-divider';
    divider.textContent = 'or';
    container.appendChild(divider);

    const googleContainer = document.createElement('div');
    googleContainer.className = 'wl-google-btn';
    container.appendChild(googleContainer);

    loadGoogleScript().then(() => {
      window.google.accounts.id.initialize({
        client_id: window.__PF_GOOGLE_CLIENT_ID__,
        callback: (response) => handleGoogleCredential(response, errEl, onOutcome)
      });
      window.google.accounts.id.renderButton(googleContainer, { theme: 'outline', size: 'large', width: 280 });
    }).catch(() => { /* Google button simply doesn't render — email/password still works */ });
  }
}

export async function mount(container, widget, ctx) {
  const config = widget.config || {};
  const onOutcome = () => (ctx.onOutcome || (() => {}))('next');

  try {
    const res = await fetch('/api/auth/me', { headers: HEADERS });
    if (res.ok) { onOutcome(); return; }
  } catch (e) { /* fall through to the form */ }
  renderForm(container, config, onOutcome);
}
