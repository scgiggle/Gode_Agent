const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const source = fs.readFileSync(path.join(__dirname, '..', 'submission-observer.js'), 'utf8')
const flush = () => new Promise((resolve) => setImmediate(resolve))

function setup() {
  const messages = []
  const listeners = new Map()
  const responses = []
  const requests = []
  class XHR {
    open(method, url) { this.method = method; this.url = url }
    send(body) { this.body = body }
    addEventListener(name, listener) { this.listener = listener }
    finish(result) { this.status = 200; this.responseType = 'json'; this.response = result; this.listener() }
  }
  const window = {
    location: { href: 'https://leetcode.cn/problems/two-sum/', origin: 'https://leetcode.cn' },
    fetch: async (url, options) => {
      requests.push({ url, options })
      const data = responses.shift()
      return { ok: true, json: async () => data, clone() { return this } }
    },
    addEventListener: (name, listener) => listeners.set(name, listener),
    postMessage: (message) => messages.push(message),
    // 不运行补查计时器；本测试显式模拟力扣页面的 check 响应。
    setTimeout() {},
  }
  const sandbox = { window, XMLHttpRequest: XHR, URL, AbortSignal, console }
  vm.createContext(sandbox)
  vm.runInContext(source, sandbox)
  const ready = () => listeners.get('message')({ source: window, origin: window.location.origin,
    data: { type: 'GODE_SUBMISSION_WATCH_READY' } })
  return { window, responses, messages, requests, ready, XHR }
}

test('fetch 提交失败使用请求代码快照，保留真实判题结果', async () => {
  const app = setup(); app.ready()
  app.responses.push({ submission_id: 123 })
  await app.window.fetch('/problems/two-sum/submit/', { method: 'POST', body: JSON.stringify({ lang: 'java', typed_code: 'OLD CODE' }) })
  await flush()
  // 即使用户随后修改编辑器或切换页面，也只分析当次提交快照。
  app.window.location.href = 'https://leetcode.cn/problems/3sum/'
  app.responses.push({ state: 'SUCCESS', status_code: 11, status_msg: 'Wrong Answer',
    last_testcase: '[3,3]\n6', expected_output: '[0,1]', code_output: '[0,0]' })
  await app.window.fetch('/submissions/detail/123/check/')
  await flush()
  assert.equal(app.messages.length, 1)
  const payload = app.messages[0].payload
  assert.equal(payload.submittedCode, 'OLD CODE')
  assert.equal(payload.problemSlug, 'two-sum')
  assert.equal(payload.verdict, 'WRONG_ANSWER')
  assert.ok(payload.failCase.includes('[0,1]'))
  assert.equal(payload.submissionId, undefined)
  assert.equal(payload.cookie, undefined)
})

test('通过提交、普通运行和平台未知错误不触发错题分析', async () => {
  for (const result of [{ state: 'SUCCESS', status_code: 10 }, { state: 'SUCCESS', status_code: 16 }]) {
    const app = setup(); app.ready()
    app.responses.push({ submission_id: 1 })
    await app.window.fetch('/problems/two-sum/submit/', { method: 'POST', body: JSON.stringify({ lang: 'java', typed_code: 'CODE' }) })
    await flush()
    app.responses.push(result)
    await app.window.fetch('/submissions/detail/1/check/'); await flush()
    assert.equal(app.messages.length, 0)
  }
  const app = setup(); app.ready()
  app.responses.push({ interpret_id: 2 })
  await app.window.fetch('/problems/two-sum/interpret_solution/', { method: 'POST', body: '{}' }); await flush()
  assert.equal(app.messages.length, 0)
})

test('XHR 提交与编译失败也能被采集，内容脚本准备前的事件可以回放', async () => {
  const app = setup()
  const submit = new app.XHR()
  submit.open('POST', '/problems/two-sum/submit/')
  submit.send(JSON.stringify({ lang: 'cpp', typed_code: 'BROKEN CODE' }))
  submit.finish({ submission_id: 9 })
  const check = new app.XHR()
  check.open('GET', '/submissions/detail/9/check/'); check.send()
  check.finish({ state: 'SUCCESS', status_code: 20, compile_error: 'missing semicolon' })
  assert.equal(app.messages.length, 0)
  app.ready()
  assert.equal(app.messages[0].payload.verdict, 'COMPILE_ERROR')
  assert.ok(app.messages[0].payload.failCase.includes('missing semicolon'))
})

test('判题未结束或未捕获代码时，不拿当前编辑器代码拼凑分析', async () => {
  const app = setup(); app.ready()
  app.responses.push({ submission_id: 3 })
  await app.window.fetch('/problems/two-sum/submit/', { method: 'POST', body: '{}' }); await flush()
  app.responses.push({ state: 'SUCCESS', status_code: 11 })
  await app.window.fetch('/submissions/detail/3/check/'); await flush()
  assert.equal(app.messages.length, 0)
})
