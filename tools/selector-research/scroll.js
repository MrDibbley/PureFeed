// Read-only apart from scrolling. Scrolls the feed to load more units, then classifies them.
(async () => {
  const sleep = ms => new Promise(r => setTimeout(r, ms));
  const clean = s => s.replace(/[\uE000-\uF8FF]|[\u{F0000}-\u{FFFFD}]/gu, '').replace(/\s+/g, ' ').trim();
  const vs = () => document.querySelector('[data-type="vscroller"]');
  const seenIds = new Map();   // tracking id -> summary, survives virtualisation
  const textFreq = {};
  const snapshot = () => {
    [...vs().children].forEach(u => {
      const id = u.getAttribute('data-tracking-duration-id');
      if (!id || seenIds.has(id)) return;
      const texts = [...u.querySelectorAll('[data-mcomponent$="TextArea"]')].map(t => clean(t.textContent)).filter(t => t && t.length <= 40);
      new Set(texts).forEach(t => textFreq[t] = (textFreq[t] || 0) + 1);
      seenIds.set(id, { texts: new Set(texts), h2: [...u.querySelectorAll('h1,h2,h3')].map(h => clean(h.textContent)),
        h: Math.round(u.getBoundingClientRect().height), filled: u.querySelectorAll('*').length });
    });
  };
  const before = vs().children.length;
  for (let i = 0; i < 14; i++) { snapshot(); window.scrollBy(0, innerHeight * 1.5); await sleep(1600); }
  snapshot();
  // virtualisation check: how many current children are empty placeholders?
  const kids = [...vs().children].filter(u => u.hasAttribute('data-tracking-duration-id'));
  const placeholders = kids.filter(u => u.querySelectorAll('*').length < 5).length;
  const common = Object.entries(textFreq).filter(([t, n]) => n >= 3).sort((a, b) => b[1] - a[1]).map(([t, n]) => `${t} x${n}`);
  const KNOWN = /^(Ad|Sponsored|Reels|People you may know|Join|Follow|Suggested for you|Memories|On this day|Add friend|See all|Groups you might like|Suggested groups|Your memories)$/i;
  const units = [...seenIds.values()].map(s => ({ h: s.h, labels: [...s.texts].filter(t => KNOWN.test(t) || textFreq[t] >= 3).slice(0, 8),
    h2: s.h2.map(t => (KNOWN.test(t) || textFreq[t] >= 2) ? t : `<${t.length}>`) }));
  return { before, after: vs().children.length, trackedUnitsSeen: seenIds.size, currentTracked: kids.length, placeholders, scrollY, common, units };
})()
