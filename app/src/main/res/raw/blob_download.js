// Outlook builds some downloads (e.g. "Download" in the attachment preview) as a blob: URL inside
// the page. DownloadManager cannot fetch those, so read the blob here and hand it to the app.
(() => {
  if (window.__outlookBlobSave) return;
  const save = (url, name) => {
    // fetch resolves the blob URL immediately, so a revokeObjectURL right after click is harmless.
    fetch(url)
      .then(r => r.blob())
      .then(b => new Promise((ok, fail) => {
        const f = new FileReader();
        f.onload = () => ok([f.result, b.type]);
        f.onerror = () => fail(f.error);
        f.readAsDataURL(b);
      }))
      .then(([data, type]) => OutlookAndroid.postMessage(JSON.stringify(
        {data: data.slice(data.indexOf(',') + 1), type: type || '', name: name || ''})))
      .catch(e => OutlookAndroid.postMessage(JSON.stringify({error: String(e)})));
  };
  window.__outlookBlobSave = save;
  const blobLink = a => a && typeof a.href === 'string' && a.href.startsWith('blob:');
  // Catches a.click() on links that were never added to the page, where the name lives in a.download.
  const click = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (blobLink(this)) save(this.href, this.download); else click.call(this);
  };
  document.addEventListener('click', e => {
    const a = e.target && e.target.closest && e.target.closest('a[href^="blob:"]');
    if (blobLink(a)) {
      e.preventDefault();
      save(a.href, a.download);
    }
  }, true);
})();
