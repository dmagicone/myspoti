// A sample of every block (docs/extensions/API.md §6). Counts the plays you tap, nothing more.
function counts() { return myspoti.storage.get('counts') || {}; }

function count(uri, title) {
  var c = counts();
  var e = c[uri] || { title: title, n: 0 };
  e.n += 1;
  c[uri] = e;
  myspoti.storage.set('counts', c);
  myspoti.notify('Counted: ' + title + ' (' + e.n + ')');
  myspoti.ui.refresh('home');
  myspoti.page.post(c);
}

myspoti.provide('home', function () {
  var c = counts();
  var uris = Object.keys(c).filter(function (u) { return u.indexOf('spotify:track:') === 0; }).slice(0, 10);
  if (uris.length === 0) return [{ type: 'section', title: 'Counted here', blocks: [{ type: 'text', text: 'Tap "Count a play" in a song menu.', style: 'caption' }] }];
  return [{ type: 'section', title: 'Counted here', blocks: [
    { type: 'tracks', items: uris.map(function (u) { return { uri: u, title: c[u].title, subtitle: c[u].n + ' plays' }; }) },
    { type: 'button', label: 'Reset', icon: 'refresh', action: 'reset' }
  ] }];
});

myspoti.provide('search', function (q) {
  return [{ type: 'text', text: 'Blocks demo: nothing to find for "' + q.query + '".', style: 'caption' }];
});

myspoti.provide('menu', function (target) {
  if (target.kind !== 'track') return [];
  return [{ type: 'menu', label: 'Count a play', icon: 'add', action: 'count' }];
});

myspoti.provide('player', function (track) {
  return [{ type: 'button', label: 'Count', icon: 'check', action: 'count' }];
});

myspoti.provide('trackinfo', function (track) {
  var e = counts()[track.uri];
  return [{ type: 'text', text: e ? 'Counted ' + e.n + ' times with the blocks demo.' : 'Not counted yet.' }];
});

myspoti.provide('tap', function (e) {
  if (e.action === 'reset') { myspoti.storage.set('counts', {}); myspoti.ui.refresh('home'); return; }
  if (e.action === 'count' && e.context && e.context.uri) count(e.context.uri, e.context.title || 'this song');
});

myspoti.provide('page', function () {
  return { title: 'Counted plays', html:
    '<body style="font-family:sans-serif;background:#121212;color:#eee"><h3>Counted plays</h3><ul id="l"></ul>' +
    '<button onclick="MyspotiPage.postMessage(JSON.stringify({ask:\'list\'}))">Refresh</button>' +
    '<script>function show(c){var l=document.getElementById("l");l.innerHTML="";Object.keys(c).forEach(function(u){var li=document.createElement("li");li.textContent=c[u].title+": "+c[u].n;l.appendChild(li);});}' +
    'window.addEventListener("message",function(e){show(e.data);});MyspotiPage.postMessage(JSON.stringify({ask:"list"}));</script></body>' };
});

myspoti.provide('message', function (data) {
  return counts();
});
