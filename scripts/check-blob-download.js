// Runs blob_download.js against minimal DOM mocks: blob links go to the bridge, others click normally.
const assert = require('assert');
const fs = require('fs');
const src = fs.readFileSync(__dirname + '/../app/src/main/res/raw/blob_download.js', 'utf8');

const saved = [], clicked = [], failed = [];
let listener;
global.window = {};
global.HTMLAnchorElement = function () {};
HTMLAnchorElement.prototype.click = function () { clicked.push(this.href); };
global.document = { addEventListener: (type, fn, capture) => { assert.equal(type, 'click'); assert(capture); listener = fn; } };
global.fetch = url => url === 'blob:https://outlook.office.com/bad'
  ? Promise.reject(new Error('revoked'))
  : Promise.resolve({ blob: () => Promise.resolve({ type: 'application/pdf', data: 'JVBERi0=' }) });
global.FileReader = function () {
  this.readAsDataURL = b => { this.result = 'data:' + b.type + ';base64,' + b.data; setImmediate(this.onload); };
};
global.OutlookAndroid = { postMessage: s => { const m = JSON.parse(s); m.error ? failed.push(m.error) : saved.push([m.data, m.type, m.name]); } };

new Function(src)();
new Function(src)(); // second injection must not wrap click twice
const link = (href, download) => Object.assign(Object.create(HTMLAnchorElement.prototype), { href, download });

link('blob:https://outlook.office.com/1', 'Offerte.pdf').click();
link('https://example.com/x', '').click();
let prevented = false;
const a = link('blob:https://outlook.office.com/2', 'b.pdf');
listener({ target: { closest: () => a }, preventDefault: () => { prevented = true; } });
link('blob:https://outlook.office.com/bad', 'c.pdf').click();

setImmediate(() => setImmediate(() => {
  assert.deepEqual(clicked, ['https://example.com/x']);
  assert(prevented);
  assert.deepEqual(saved, [['JVBERi0=', 'application/pdf', 'Offerte.pdf'], ['JVBERi0=', 'application/pdf', 'b.pdf']]);
  assert.deepEqual(failed, ['Error: revoked']);
  console.log('blob download ok');
}));
