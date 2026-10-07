/**
 * One-off probe (not part of the app): checks whether the tightened list-item
 * margins are actually load-bearing, i.e. what the thinking list looks like with
 * and without `.thinking-text li > p { margin: 0 }` while `white-space` is normal.
 *
 * Usage: node probe-output/thinking-margin-ab.mjs <cdpPort>
 */
const port = process.argv[2] || '9226'

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

let target = null
for (let i = 0; i < 60; i++) {
  try {
    const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json()
    target = targets.find((t) => t.type === 'page' && t.url.includes('thinking-layout.html'))
    if (target?.webSocketDebuggerUrl) break
  } catch {
    // browser not up yet
  }
  await sleep(250)
}
if (!target) {
  console.error('FAIL: probe page not found in CDP targets')
  process.exit(1)
}

const ws = new WebSocket(target.webSocketDebuggerUrl)
await new Promise((res, rej) => {
  ws.onopen = res
  ws.onerror = rej
})
let nextId = 1
const pending = new Map()
ws.onmessage = (e) => {
  const m = JSON.parse(e.data)
  if (m.id && pending.has(m.id)) {
    pending.get(m.id)(m)
    pending.delete(m.id)
  }
}
const send = (method, params = {}) =>
  new Promise((resolve) => {
    const id = nextId++
    pending.set(id, resolve)
    ws.send(JSON.stringify({ id, method, params }))
  })

await send('Runtime.enable')
await sleep(300)

const expression = `(() => {
  const root = document.getElementById('fixed');
  const items = [...root.querySelectorAll('ol > li')];
  const gap = () => Math.round((items[1].getBoundingClientRect().top - items[0].getBoundingClientRect().bottom) * 10) / 10;
  const height = () => Math.round(root.getBoundingClientRect().height * 10) / 10;
  const withRule = { firstToSecond_gap: gap(), blockHeight: height() };
  // drop the load-bearing rule and re-measure
  const style = document.createElement('style');
  style.textContent = '.thinking-text li > p:first-child{margin-top:12px}.thinking-text li > p:last-child{margin-bottom:12px}';
  document.head.appendChild(style);
  const withoutRule = { firstToSecond_gap: gap(), blockHeight: height() };
  style.remove();
  return JSON.stringify({ withRule, withoutRule }, null, 2);
})()`

const res = await send('Runtime.evaluate', { expression, returnByValue: true })
if (res.result?.exceptionDetails) {
  console.error('FAIL', JSON.stringify(res.result.exceptionDetails))
  process.exit(1)
}
console.log(res.result.result.value)
ws.close()
