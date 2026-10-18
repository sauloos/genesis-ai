/**
 * Logout widget — a "Sign out" button, shown only while a client is signed in. Signing out
 * also unlinks the client from this flow session, then reloads the page as a visitor.
 */
const HEADERS = { 'Content-Type': 'application/json', 'X-Api-Key': 'genesis-ai-key' };

export async function mount(container, widget, ctx) {
  try {
    const res = await fetch('/api/auth/me', { headers: HEADERS });
    if (!res.ok) return; // not signed in — render nothing
  } catch (e) {
    return;
  }

  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'wlo-btn';
  btn.textContent = (widget.config || {}).label || 'Sign out';
  btn.onclick = async () => {
    if (ctx.simulate) return; // an admin preview never signs anyone out
    btn.disabled = true;
    btn.textContent = 'Signing out…';
    try {
      await fetch('/api/auth/logout', { method: 'POST', headers: HEADERS });
      await ctx.session?.fetch('/unlink-user', { method: 'POST' });
    } catch (e) { /* best effort — the visitor is leaving this state regardless */ }
    window.location.reload();
  };
  container.appendChild(btn);
}
