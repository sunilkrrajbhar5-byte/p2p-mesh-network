import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';

const pages = Object.fromEntries(await Promise.all(['index.html','desktop.html'].map(async name =>
  [name, await readFile(new URL('../docs/' + name, import.meta.url), 'utf8')])));
function redirect(page, width, search='', hash='') {
  let result = null;
  const html = pages[page];
  const match = html.match(/<script id="viewport-routing">([\s\S]*?)<\/script>/);
  assert.ok(match && html.indexOf(match[0]) < html.indexOf('<style>'));
  vm.runInNewContext(match[1], {URLSearchParams, window: {innerWidth: width,
    location: {search, hash, replace: value => {result = value;}}}});
  return result;
}
test('automatic routing has an exact 768px boundary and no redirect loop', () => {
  for (const width of [320,390,767,768,1024,1920]) {
    assert.equal(redirect('index.html',width),width >= 768 ? 'desktop.html' : null);
    assert.equal(redirect('desktop.html',width),width < 768 ? 'index.html' : null);
  }
});
test('explicit footer overrides stay on the chosen layout', () => {
  assert.equal(redirect('index.html',1440,'?force=mobile'),null);
  assert.equal(redirect('desktop.html',390,'?force=desktop'),null);
  assert.match(pages['index.html'], /href="desktop.html\?force=desktop"/);
  assert.match(pages['desktop.html'], /href="index.html\?force=mobile"/);
  assert.equal(redirect('index.html',1440,'?other=force=mobile'),'desktop.html?other=force=mobile');
});
test('routing preserves gateway parameters and section anchors', () => {
  const query='?gateway=https%3A%2F%2F192.168.1.1%3A8443';
  assert.equal(redirect('index.html',1024,query,'#gateway'),'desktop.html'+query+'#gateway');
  assert.equal(redirect('desktop.html',390,query,'#download'),'index.html'+query+'#download');
});
test('redesigned desktop retains secure gateway and official download entry points', () => {
  const desktop=pages['desktop.html'];
  assert.match(desktop, /id="gateway-form"/);
  assert.match(desktop, /id="paste"/);
  assert.match(desktop, /id="scan"/);
  assert.match(desktop, /url.protocol!==\'https:\'/);
  assert.match(desktop, /url.username \|\| url.password/);
  assert.match(desktop, /releases\/latest\/download\/SR-Mesh\.apk/);
  assert.match(desktop, /downloads\/SR-Mesh-Windows-Setup-v1\.0\.2\.exe/);
  assert.match(desktop, /\.dmg not published/);
});
