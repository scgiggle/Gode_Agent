const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const directory = path.join(__dirname, '..')
const source = fs.readFileSync(path.join(directory, 'sidepanel.js'), 'utf8')
const html = fs.readFileSync(path.join(directory, 'sidepanel.html'), 'utf8')

function element() {
  const classes = new Set()
  return { value: '', textContent: '', innerHTML: '', disabled: false, listeners: {}, style: {}, dataset: {},
    classList: { add: (...names) => names.forEach(name => classes.add(name)),
      remove: (...names) => names.forEach(name => classes.delete(name)),
      contains: name => classes.has(name),
      toggle(name, enabled = !classes.has(name)) { enabled ? classes.add(name) : classes.delete(name) } },
    addEventListener(name, callback) { this.listeners[name] = callback },
    querySelectorAll() { return [] }, setAttribute() {}, appendChild() {}, focus() {},
  }
}
function setup() {
  const nodes = new Map([...html.matchAll(/id="([^"]+)"/g)].map(match => [match[1], element()]))
  const requests = [], writes = [], timers = new Map()
  let timerId = 0
  const sandbox = {
    URL, AbortController, DOMException, TextDecoder, console,
    crypto: require('node:crypto').webcrypto,
    document: { getElementById: id => nodes.get(id), querySelector: element, createElement: element },
    chrome: { storage: { local: { async get() { return {} }, async set(value) { writes.push(value) } } },
      runtime: { onMessage: { addListener() {} } } },
    setTimeout(fn, ms) { timers.set(++timerId, { fn, ms }); return timerId },
    clearTimeout(id) { timers.delete(id) },
    async fetch(url, options) {
      requests.push({ url, options })
      return { ok: true, status: 200, json: async () => ({ code: '0000', data: {
        enabled: true, configured: true, verified: true, writeToolsAvailable: true,
      } }) }
    },
  }
  sandbox.window = { setTimeout: sandbox.setTimeout, confirm: () => true }
  vm.createContext(sandbox)
  vm.runInContext(source.slice(0, source.lastIndexOf('void (async () => {')), sandbox)
  const run = code => vm.runInContext(code, sandbox)
  run("elements['api-base-url'].value=state.settings.apiBaseUrl;elements['agent-id'].value=state.settings.agentId")
  return { sandbox, nodes, requests, writes, timers, run, state: run('state') }
}

test('Cookie 通过 POST 正文提交，不进入插件存储、状态或 URL；验证后清空输入', async () => {
  const app = setup()
  app.nodes.get('leetcode-session-cookie').value = 'private-cookie-value'
  await app.run("updateNotesConfiguration('save')")
  assert.equal(app.requests.length, 1)
  const request = app.requests[0]
  assert.equal(request.options.method, 'POST')
  assert.equal(JSON.parse(request.options.body).cookie, 'private-cookie-value')
  assert.equal(request.options.headers['X-Gode-Personal-Config'], '1')
  assert.equal(request.options.credentials, 'omit')
  assert.equal(request.options.redirect, 'error')
  assert.ok(!request.url.includes('private-cookie-value'))
  assert.equal(app.writes.length, 0)
  assert.ok(!JSON.stringify(app.state).includes('private-cookie-value'))
  assert.equal(app.nodes.get('leetcode-session-cookie').value, '')
  assert.match(app.nodes.get('notes-cookie-status').textContent, /工具已就绪/)
  assert.equal(app.state.notesConfigBusy, false)
})

test('Cookie 输入框隐藏内容，关闭设置会清空且不保存', () => {
  assert.match(html, /id="leetcode-session-cookie" type="password" autocomplete="off"/)
  const app = setup()
  app.nodes.get('leetcode-session-cookie').value = 'secret'
  app.nodes.get('close-settings').listeners.click()
  assert.equal(app.nodes.get('leetcode-session-cookie').value, '')
  assert.equal(app.writes.length, 0)
})

test('兼容完整 Cookie 字符串，但只将 LEETCODE_SESSION 值传入后端', async () => {
  const app = setup()
  app.nodes.get('leetcode-session-cookie').value = 'other=private; LEETCODE_SESSION=session-value; csrftoken=not-needed'
  await app.run("updateNotesConfiguration('save')")
  assert.equal(JSON.parse(app.requests[0].options.body).cookie, 'session-value')
  assert.ok(!app.requests[0].options.body.includes('not-needed'))
  assert.ok(!app.requests[0].options.body.includes('other=private'))
})

test('拒绝向非本机明文 HTTP、含认证信息的 URL 和未保存的新地址提交 Cookie', async () => {
  for (const address of ['http://example.com/api/v1', 'https://user:password@example.com/api/v1']) {
    const app = setup()
    app.state.settings.apiBaseUrl = address
    app.nodes.get('api-base-url').value = address
    app.nodes.get('leetcode-session-cookie').value = 'secret'
    await app.run("updateNotesConfiguration('save')")
    assert.equal(app.requests.length, 0)
  }
  const app = setup()
  app.nodes.get('api-base-url').value = 'https://new.example.com/api/v1'
  await app.run("updateNotesConfiguration('save')")
  assert.equal(app.requests.length, 0)
  assert.match(app.nodes.get('notes-cookie-status').textContent, /先保存/)
})

test('清除须确认，不带 Cookie，不删除笔记；取消确认不请求', async () => {
  const app = setup()
  app.sandbox.window.confirm = () => false
  await app.run("updateNotesConfiguration('clear')")
  assert.equal(app.requests.length, 0)
  app.sandbox.window.confirm = () => true
  await app.run("updateNotesConfiguration('clear')")
  assert.ok(app.requests[0].url.endsWith('/personal/leetcode-notes/clear'))
  assert.deepEqual(JSON.parse(app.requests[0].options.body), { agentId: 'demo-agent' })
  assert.match(app.nodes.get('notes-cookie-status').textContent, /没有删除/)
})

test('失败不回显上游错误中的 Cookie，也不假装验证成功', async () => {
  const app = setup()
  app.nodes.get('leetcode-session-cookie').value = 'secret-cookie'
  app.sandbox.fetch = async () => ({ ok: true, status: 200,
    json: async () => ({ code: '0002', info: 'bad auth: secret-cookie' }) })
  await app.run("updateNotesConfiguration('save')")
  assert.ok(!app.nodes.get('notes-cookie-status').textContent.includes('secret-cookie'))
  assert.match(app.nodes.get('notes-cookie-status').textContent, /失败/)
  assert.equal(app.state.notesConfigBusy, false)
})

test('配置状态查询没有 Cookie，未验证的启动配置不会显示写入就绪', async () => {
  const app = setup()
  app.sandbox.fetch = async (url, options) => {
    app.requests.push({ url, options })
    return { ok: true, status: 200, json: async () => ({ code: '0000', data: {
      configured: true, verified: false, writeToolsAvailable: false,
    } }) }
  }
  await app.run("updateNotesConfiguration('status')")
  assert.equal(app.requests[0].options.body, undefined)
  assert.match(app.nodes.get('notes-cookie-status').textContent, /尚未验证/)
})

test('关闭设置会取消等待，迟到的验证不能覆盖新状态', async () => {
  const app = setup()
  let finish
  app.sandbox.fetch = () => new Promise(resolve => { finish = resolve })
  app.nodes.get('leetcode-session-cookie').value = 'secret'
  const task = app.run("updateNotesConfiguration('save')")
  await new Promise(setImmediate)
  const controller = app.state.notesConfigController
  app.nodes.get('close-settings').listeners.click()
  app.run("notesConfigurationNotice('新的状态')")
  finish({ ok: true, status: 200, json: async () => ({ code: '0000', data: {
    configured: true, verified: true, writeToolsAvailable: true,
  } }) })
  await task
  assert.equal(controller.signal.aborted, true)
  assert.equal(app.nodes.get('notes-cookie-status').textContent, '新的状态')
  assert.equal(app.nodes.get('leetcode-session-cookie').value, '')
})
