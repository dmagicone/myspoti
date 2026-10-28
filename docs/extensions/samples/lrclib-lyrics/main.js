// A sample lyrics extension (docs/extensions/API.md): LRCLIB's exact match by title, artist,
// album and length, then its search. Returns null when it finds nothing: the app's own sources answer.
myspoti.log('sample lyrics extension loaded');

function parseLrc(lrc) {
  var lines = [];
  lrc.split('\n').forEach(function (row) {
    var m = /^\[(\d+):(\d+)(?:[.:](\d+))?\](.*)$/.exec(row.trim());
    if (!m) return;
    var frac = m[3] ? parseInt((m[3] + '00').slice(0, 3), 10) : 0;
    lines.push({ time: parseInt(m[1], 10) * 60000 + parseInt(m[2], 10) * 1000 + frac, text: m[4].trim() });
  });
  return lines;
}

function answer(o) {
  if (!o) return null;
  if (o.syncedLyrics) return { provider: 'LRCLIB (sample extension)', lines: parseLrc(o.syncedLyrics) };
  if (o.plainLyrics && myspoti.settings.get('plainToo') !== false) return { provider: 'LRCLIB (sample extension)', plain: o.plainLyrics };
  return null;
}

myspoti.provide('lyrics', function (track) {
  var q = { track_name: track.title, artist_name: track.artist, album_name: track.album || null,
            duration: track.durationMs ? Math.round(track.durationMs / 1000) : null };
  var r = myspoti.http.get('https://lrclib.net/api/get?' + myspoti.query(q), { timeout: 5000 });
  if (r.ok) { var a = answer(r.json()); if (a) return a; }
  var s = myspoti.http.get('https://lrclib.net/api/search?' + myspoti.query({ track_name: track.title, artist_name: track.artist }), { timeout: 5000 });
  if (!s.ok) return null;
  var found = s.json();
  for (var i = 0; i < found.length; i++) {
    var d = found[i].duration * 1000;
    if (track.durationMs && Math.abs(d - track.durationMs) > 3000) continue;
    var a2 = answer(found[i]);
    if (a2) return a2;
  }
  return null;
});
