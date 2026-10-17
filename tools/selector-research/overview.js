// Read-only: structural statistics only, no text content.
(() => {
  const all = document.querySelectorAll('*');
  const attrCount = {};
  for (const el of all) for (const a of el.attributes) {
    const k = a.name.startsWith('data-') || ['role','aria-label','id'].includes(a.name) ? a.name : null;
    if (k) attrCount[k] = (attrCount[k] || 0) + 1;
  }
  const valCount = (sel, attr) => {
    const m = {};
    document.querySelectorAll(sel).forEach(e => { const v = e.getAttribute(attr); m[v] = (m[v]||0)+1; });
    return Object.entries(m).sort((a,b)=>b[1]-a[1]).slice(0,40);
  };
  return {
    path: location.pathname, ua: navigator.userAgent, elements: all.length,
    scrollY: scrollY, docHeight: document.documentElement.scrollHeight, innerHeight,
    attrs: Object.entries(attrCount).sort((a,b)=>b[1]-a[1]).slice(0,40),
    roles: valCount('[role]', 'role'),
    dataType: valCount('[data-type]', 'data-type'),
    mcomponent: valCount('[data-mcomponent]', 'data-mcomponent'),
    sigil: valCount('[data-sigil]', 'data-sigil'),
  };
})()
