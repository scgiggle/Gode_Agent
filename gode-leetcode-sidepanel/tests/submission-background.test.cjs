const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

function setup() {
  let onMessage
  let onRemoved
  const stored = {}
  const broadcasts = []
  const chrome = {
    runtime: { id: 'extension-id', onMessage: { addListener(fn) { onMessage = fn } },
      onInstalled: { addListener() {} }, onStartup: { addListener() {} },
      sendMessage: async (message) => broadcasts.push(message) },
    tabs: { onActivated: { addListener() {} }, onUpdated: { addListener() {} },
      onRemoved: { addListener(fn) { onRemoved = fn } } },
    storage: { session: { get: async (key) => ({ [key]: stored[key] }), set: async (values) => Object.assign(stored, values) } },
    sidePanel: { setPanelBehavior: async () => {} },
  }
  const sandbox = { chrome, crypto: require('node:crypto').webcrypto, console }
  vm.createContext(sandbox)
  vm.runInContext(fs.readFileSync(path.join(__dirname, '..', 'background.js'), 'utf8'), sandbox)
  function send(message, sender = { id: 'extension-id' }) {
    return new Promise((resolve) => {
      if (!onMessage(message, sender, resolve)) resolve(null)
    })
  }
  return { stored, broadcasts, send, remove: (id) => onRemoved(id) }
}

const payload = { problem: { slug: 'two-sum', title: '两数之和' }, submittedCode: 'CODE',
  language: 'java', verdict: 'WRONG_ANSWER', failCase: 'expected=[0,1]', cookie: 'must-not-forward' }
const sender = { id: 'extension-id', url: 'https://leetcode.cn/problems/two-sum/', tab: { id: 8, windowId: 2 } }

test('侧边栏关闭时提交仍暂存，重新打开可以恢复，且不转发 Cookie', async () => {
  const app = setup()
  assert.equal((await app.send({ type: 'GODE_SUBMISSION_FAILED', payload }, sender)).success, true)
  const candidate = (await app.send({ type: 'GODE_GET_PENDING_REVIEW', tabId: 8 })).candidate
  assert.equal(candidate.payload.submittedCode, 'CODE')
  assert.equal(candidate.payload.cookie, undefined)
  assert.equal(app.broadcasts[0].type, 'GODE_REVIEW_CANDIDATE')
  assert.equal((await app.send({ type: 'GODE_GET_PENDING_REVIEW', tabId: 9 })).candidate, null)
})

test('保存/忽略的确认只清理对应候选，旧提示不能删除较新的失败提交', async () => {
  const app = setup()
  await app.send({ type: 'GODE_SUBMISSION_FAILED', payload }, sender)
  const first = (await app.send({ type: 'GODE_GET_PENDING_REVIEW', tabId: 8 })).candidate
  await app.send({ type: 'GODE_SUBMISSION_FAILED', payload }, sender)
  await app.send({ type: 'GODE_ACK_REVIEW_CANDIDATE', tabId: 8, key: first.key })
  const latest = (await app.send({ type: 'GODE_GET_PENDING_REVIEW', tabId: 8 })).candidate
  assert.notEqual(first.key, latest.key)
  await app.send({ type: 'GODE_ACK_REVIEW_CANDIDATE', tabId: 8, key: latest.key })
  assert.equal((await app.send({ type: 'GODE_GET_PENDING_REVIEW', tabId: 8 })).candidate, null)
})

test('网页内容脚本不能读取其他标签页候选，成功提交不加入候选', async () => {
  const app = setup()
  assert.equal(await app.send({ type: 'GODE_GET_PENDING_REVIEW', tabId: 8 }, sender), null)
  assert.equal(await app.send({ type: 'GODE_SUBMISSION_FAILED', payload: { ...payload, verdict: 'ACCEPTED' } }, sender), null)
  assert.equal((await app.send({ type: 'GODE_GET_PENDING_REVIEW', tabId: 8 })).candidate, null)
})
