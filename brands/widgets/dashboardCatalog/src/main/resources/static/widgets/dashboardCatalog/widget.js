// The product catalog is not built yet. Until it is, the widget takes no space on the page
// rather than showing a "coming soon" panel; the page flow keeps it so it appears once real.
export function mount(container) {
  container.hidden = true;
  container.style.display = 'none';
}
