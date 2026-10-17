// Read-only. Detail of label nodes. Only whitelisted UI strings are returned verbatim.
(() => {
  const SAFE = /^(ad|sponsored|suggested for you|reels|people you may know|join|follow|add friend|open app|get app|not now|see all|memories|on this day|your memories|suggested groups?|groups you might like|see more information|hide|•)$/i;
  const clean = s => s.replace(/[\uE000-\uF8FF]|[\u{F0000}-\u{FFFFD}]/gu, '').trim();
  const desc = el => {
    if (!el) return null;
    const r = el.getBoundingClientRect();
    return { tag: el.tagName.toLowerCase(), mc: el.dataset.mcomponent, type: el.dataset.type,
      attrs: [...el.attributes].map(a => a.name).filter(n => !['style','class'].includes(n)).join(' '),
      x: Math.round(r.x), w: Math.round(r.width), h: Math.round(r.height),
      raw: [...el.textContent].map(c => c.codePointAt(0) > 0xFFFF || /[\uE000-\uF8FF]/.test(c) ? 'U+' + c.codePointAt(0).toString(16) : c).join('|').slice(0, 80) };
  };
  const depthIn = (el, root) => { let d = 0; while (el && el !== root) { el = el.parentElement; d++; } return d; };
  const vs = document.querySelector('[data-type="vscroller"]');
  const units = [...vs.children].filter(u => u.hasAttribute('data-tracking-duration-id'));
  const out = {};
  // 1) every text node in the document whose cleaned text is a whitelisted label
  out.labels = [...document.querySelectorAll('[data-mcomponent$="TextArea"], [role], span, div')]
    .filter(e => e.children.length === 0 || e.dataset.mcomponent?.endsWith('TextArea'))
    .map(e => [e, clean(e.textContent)]).filter(([e, t]) => SAFE.test(t))
    .map(([e, t]) => ({ t, inUnit: units.findIndex(u => u.contains(e)), depth: units.find(u => u.contains(e)) ? depthIn(e, units.find(u => u.contains(e))) : null, ...desc(e) }));
  // 2) aria-labels that are whitelisted (verbatim) or short (length + charcode shape)
  out.arias = [...document.querySelectorAll('[aria-label]')].map(e => [e, e.getAttribute('aria-label')])
    .filter(([e, a]) => SAFE.test(clean(a)) || /sponsor|advert|\bad\b|suggest|app/i.test(a))
    .map(([e, a]) => ({ a: SAFE.test(clean(a)) ? a : a.replace(/[A-Z][a-z]+/g, 'Xx'), inUnit: units.findIndex(u => u.contains(e)), role: e.getAttribute('role') }));
  // 3) fixed / sticky elements (app banners live here)
  out.fixed = [...document.querySelectorAll('body *')].filter(e => ['fixed','sticky'].includes(getComputedStyle(e).position))
    .map(e => ({ ...desc(e), pos: getComputedStyle(e).position, top: Math.round(e.getBoundingClientRect().top), text: clean(e.textContent).split(/\s+/).filter(w => SAFE.test(w) || /app|facebook|get|open|not|now/i.test(w)).join(' ').slice(0,60) }));
  return out;
})()
