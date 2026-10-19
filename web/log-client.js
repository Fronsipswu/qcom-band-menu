(function (root) {
  'use strict';
  root.QbmClient = { create() {
    let dataset = null, worker = null, sequence = 0, disposed = false;
    const pending = new Map();
    const client = { onProgress: null };
    const workerText = 'const Tests=(' + root.QbmTests.factorySource + ')();const Core=(' + root.QbmLog.factorySource + ')(Tests);let data=null;' +
      'self.onmessage=async({data:m})=>{try{let result;if(m.method==="loadFiles"){data=await Core.readFiles(m.args,p=>postMessage({progress:p}));result=data.summary();}' +
      'else if(m.method==="loadDemo"){data=Core.parseText(Core.demoText());result=data.summary();}' +
      'else {if(!data)throw Error("Open a log first.");if(m.method==="exportFile"){const r=data.exportData(m.args);result={blob:new Blob([r.text],{type:r.mime}),filename:r.filename,rowCount:r.rowCount};}' +
      'else result=data[m.method](m.args); }postMessage({id:m.id,result});}catch(e){postMessage({id:m.id,error:e.message||String(e)});}};';
    let url = null;
    try {
      url = URL.createObjectURL(new Blob([workerText], { type: 'text/javascript' })); worker = new Worker(url);
      worker.onmessage = event => { const m = event.data; if (m.progress) { client.onProgress?.(m.progress); return; } const p = pending.get(m.id); if (!p) return; pending.delete(m.id); if (m.error) p.reject(new Error(m.error)); else p.resolve(m.result); };
      worker.onerror = () => { for (const p of pending.values()) p.reject(new Error('The log worker stopped. Open fewer segments or reload the viewer.')); pending.clear(); };
    } catch (_) { worker = null; }
    function call(method, args) {
      if (disposed) return Promise.reject(new Error('Viewer task was cancelled.'));
      if (worker) return new Promise((resolve, reject) => { const id = ++sequence; pending.set(id, { resolve, reject }); worker.postMessage({ id, method, args }); });
      return (async () => {
        if (method === 'loadFiles') { dataset = await root.QbmLog.readFiles(args, p => client.onProgress?.(p)); return dataset.summary(); }
        if (method === 'loadDemo') { dataset = root.QbmLog.parseText(root.QbmLog.demoText()); return dataset.summary(); }
        if (!dataset) throw new Error('Open a log first.');
        if (method === 'exportFile') { const r = dataset.exportData(args); return { blob: new Blob([r.text], { type: r.mime }), filename: r.filename, rowCount: r.rowCount }; }
        return dataset[method](args);
      })();
    }
    for (const method of ['loadFiles', 'loadDemo', 'frame', 'series', 'previewExport', 'exportFile', 'testResults']) client[method] = args => call(method, args);
    client.dispose = () => { disposed = true; worker?.terminate(); if (url) URL.revokeObjectURL(url); for (const p of pending.values()) p.reject(new Error('Viewer task was cancelled.')); pending.clear(); dataset = null; };
    return client;
  } };
})(globalThis);
