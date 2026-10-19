const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const directory = path.join(__dirname, '..')
const source = fs.readFileSync(path.join(directory, 'sidepanel.js'), 'utf8')
const html = fs.readFileSync(path.join(directory, 'sidepanel.html'), 'utf8')
const tick = () => new Promise(setImmediate)
const deferred = () => {
  let resolve, reject
  const promise = new Promise((ok, fail) => { resolve = ok; reject = fail })
  return { promise, resolve, reject }
}
function element() {
  const classes = new Set()
  return {
    textContent: '', value: '', disabled: false, dataset: {}, listeners: {}, children: [], attributes: {},
    style: {}, classList: {
      add: (...names) => names.forEach((name) => classes.add(name)),
      remove: (...names) => names.forEach((name) => classes.delete(name)),
      toggle(name, force = !classes.has(name)) { force ? classes.add(name) : classes.delete(name) },
      contains: (name) => classes.has(name),
    },
    addEventListener(name, fn) { this.listeners[name] = fn },
    replaceChildren(...nodes) { this.children = nodes },
    appendChild(node) { this.children.push(node) }, append(...nodes) { this.children.push(...nodes) },
    setAttribute(name, value) { this.attributes[name] = value },
    querySelectorAll() { return [] }, querySelector() { return null },
    focus() {}, remove() {},
  }
}
function setup() {
  const nodes = new Map([...html.matchAll(/id="([^"]+)"/g)].map((match) => [match[1], element()]))
  const timers = new Map()
  let timerId = 0, routeListener
  const sandbox = {
    URL, DOMException, AbortController, TextDecoder, console,
    crypto: require('node:crypto').webcrypto,
    setTimeout: (fn, ms) => { timers.set(++timerId, { fn, ms }); return timerId },
    clearTimeout: (id) => timers.delete(id),
    document: { getElementById: (id) => nodes.get(id), createElement: element, querySelector: element },
    chrome: {
      storage: { local: { get: async () => ({}), set: async () => {} } },
      runtime: { onMessage: { addListener(fn) { routeListener = fn } } },
    },
  }
  sandbox.window = { setTimeout: sandbox.setTimeout }
  vm.createContext(sandbox)
  vm.runInContext(source.slice(0, source.lastIndexOf('void (async () => {')), sandbox)
  vm.runInContext(`
    state.userId = 'extension-test'; state.sessionId = 'session-a';
    state.problem = { slug: 'two-sum', title: '两数之和', frontendId: '1', tags: [] };
    state.messages = [ {role:'user',content:'给个提示'}, {role:'assistant',content:'先查补数'} ];
    renderMessage = () => {}; renderAllMessages = () => {}; showThinking = () => {}; hideThinking = () => {};
    setMessageContent = (message, content) => { message.content = content };
    updateMessage = (message, content) => { message.content += content };
    readLeetCodeContext = async () => ({problem: state.problem});
    recordProblemConnection = async () => true;
    renderSuggestions();
  `, sandbox)
  return {
    sandbox, nodes, timers, state: vm.runInContext('state', sandbox),
    run: (code) => vm.runInContext(code, sandbox), route: (message) => routeListener(message),
    fire(ms) { for (const [id, timer] of [...timers]) if (timer.ms === ms) { timers.delete(id); timer.fn() } },
  }
}
const suggestions = (prefix = '新') => [1, 2, 3].map((n) => ({ label: `${prefix}建议${n}`, prompt: `${prefix}完整问题${n}？` }))
const response = (data = suggestions()) => ({ ok: true, json: async () => ({ code: '0000', data }) })
const labels = (app) => app.nodes.get('quick-prompts').children.map((button) => button.textContent)
function stream(events) {
  let sent = false
  return { ok: true, body: { getReader: () => ({
    read: async () => sent ? { done: true } : (sent = true, {
      done: false, value: new TextEncoder().encode(events.map((event) => JSON.stringify(event)).join('\n') + '\n'),
    }), cancel: async () => {}, releaseLock() {},
  }) } }
}

test('成功推荐渲染三个纯文本按钮，点击只填入完整问题，不自动发送', async () => {
  const app = setup()
  const data = suggestions()
  data[0].label = '<img src=x>'
  let calls = 0
  app.sandbox.fetch = async () => { calls++; return response(data) }
  await app.run('refreshSuggestions()')
  assert.equal(labels(app).length, 3)
  const button = app.nodes.get('quick-prompts').children[0]
  assert.equal(button.textContent, '<img src=x>')
  assert.equal(button.innerHTML, undefined)
  app.nodes.get('quick-prompts').listeners.click({ target: { closest: () => button } })
  assert.equal(app.nodes.get('message-input').value, data[0].prompt)
  assert.equal(calls, 1)
  app.run('setBusy(true)')
  assert.ok(app.nodes.get('quick-prompts').children.every((node) => node.disabled))
})

test('请求只携带近期正常对话及当前题目和身份，不携带工具和欢迎提示', async () => {
  const app = setup()
  app.state.messages = [{ role: 'system', content: 'system' }, {role:'assistant', content:'welcome', welcome:true},
    ...Array.from({length:8}, (_, i) => ({role: i % 2 ? 'assistant':'user',content:`消息${i}`})),
    {role:'assistant',content:'tool',extraClass:'tool-event'}, {role:'assistant',content:'error',extraClass:'error'}]
  let body, url
  app.sandbox.fetch = async (address, options) => { url = address; body = JSON.parse(options.body); return response() }
  await app.run('refreshSuggestions()')
  assert.ok(url.endsWith('/api/v1/chat/suggestions'))
  assert.equal(body.userId, 'extension-test')
  assert.equal(body.sessionId, 'session-a')
  assert.equal(body.problemSlug, 'two-sum')
  assert.equal(body.messages.length, 6)
  assert.equal(body.messages[0].content, '消息2')
  assert.equal(body.messages.at(-1).content, '消息7')
})

test('25秒超时及时结束等待，晚到推荐不能覆盖兜底，聊天仍可用', async () => {
  const app = setup(), pending = deferred()
  app.sandbox.fetch = () => pending.promise
  const work = app.run('refreshSuggestions()')
  app.fire(25000)
  await work
  assert.equal(app.state.suggestionController, null)
  assert.equal(app.state.busy, false)
  pending.resolve(response(suggestions('过期')))
  await tick()
  assert.equal(labels(app)[0], '帮我分析解题思路')
})

test('取消推荐后，即使网络忽略AbortSignal并返回，也不更新按钮', async () => {
  const app = setup(), pending = deferred()
  app.sandbox.fetch = () => pending.promise
  const work = app.run('refreshSuggestions()')
  app.run('invalidateSuggestions()')
  pending.resolve(response(suggestions('旧')))
  await work
  assert.equal(labels(app)[0], '帮我分析解题思路')
})

test('连续推荐请求：旧成功、旧失败和旧finally均不能覆盖新结果或清除新请求', async () => {
  for (const failed of [false, true]) {
    const app = setup(), old = deferred(), current = deferred()
    app.sandbox.fetch = () => old.promise
    const first = app.run('refreshSuggestions()')
    app.sandbox.fetch = () => current.promise
    const second = app.run('refreshSuggestions()')
    const controller = app.state.suggestionController
    if (failed) old.reject(new Error('old failed'))
    else old.resolve(response(suggestions('旧')))
    await first
    assert.equal(app.state.suggestionController, controller)
    current.resolve(response(suggestions('新')))
    await second
    assert.deepEqual(labels(app), ['新建议1', '新建议2', '新建议3'])
  }
})

test('切题通知立即作废推荐，不等250ms读取题目；旧题加载不能覆盖新题', async () => {
  const app = setup(), pending = deferred()
  app.sandbox.fetch = () => pending.promise
  const recommendation = app.run('refreshSuggestions()')
  app.run('refreshPendingSubmissionReview = async () => {}')
  app.route({ type: 'GODE_LEETCODE_ROUTE_CHANGED' })
  assert.equal(app.state.problemPending, true)
  assert.equal(app.state.suggestionController, null)
  pending.resolve(response(suggestions('旧题')))
  await recommendation
  assert.equal(labels(app)[0], '帮我分析解题思路')
  const old = deferred(), latest = deferred()
  app.sandbox.readOld = () => old.promise
  app.run('readLeetCodeContext = readOld')
  const first = app.run('loadCurrentProblem()')
  app.sandbox.readNew = () => latest.promise
  app.run('readLeetCodeContext = readNew')
  const second = app.run('loadCurrentProblem()')
  latest.resolve({problem:{slug:'three-sum',title:'三数之和',tags:[],difficulty:'中等'}})
  await second
  old.resolve({problem:{slug:'two-sum',title:'两数之和',tags:[],difficulty:'简单'}})
  await first
  assert.equal(app.state.problem.slug, 'three-sum')
  assert.equal(app.state.problemPending, false)
})

test('新会话和更改设置取消旧推荐，会话创建不能跨上下文写回', async () => {
  const app = setup(), pending = deferred()
  app.sandbox.fetch = () => pending.promise
  const first = app.run('refreshSuggestions()')
  app.sandbox.fetch = async (url) => url.endsWith('/create_session')
    ? response({sessionId:'session-new'}) : response({sessions:[],hasMore:false,nextOffset:0})
  await app.run('startNewChat()')
  pending.resolve(response(suggestions('旧会话')))
  await first
  assert.equal(app.state.sessionId, 'session-new')
  assert.equal(labels(app)[0], '帮我分析解题思路')
  app.state.sessionId = 'session-new'
  const late = deferred()
  app.sandbox.fetch = (url) => url.endsWith('/chat/suggestions') ? late.promise
    : Promise.resolve(url.endsWith('/create_session') ? response({sessionId:'settings-session'})
      : response({sessions:[],hasMore:false,nextOffset:0}))
  const second = app.run('refreshSuggestions()')
  app.run('loadMistakeBook = async () => {}')
  app.nodes.get('agent-id').value = 'different-agent'
  await app.run('saveSettings()')
  late.resolve(response(suggestions('旧设置')))
  await second
  assert.equal(app.state.settings.agentId, 'different-agent')
  assert.equal(labels(app)[0], '帮我分析解题思路')
  const session = deferred()
  app.sandbox.fetch = () => session.promise
  const creating = app.run('createSession(new AbortController().signal, state.conversationVersion)')
  app.sandbox.fetch = async (url) => url.endsWith('/create_session')
    ? response({sessionId:'session-newer'}) : response({sessions:[],hasMore:false,nextOffset:0})
  await app.run('startNewChat()')
  session.resolve({ok:true,json:async()=>({code:'0000',data:{sessionId:'old-created'}})})
  await assert.rejects(creating, { name:'AbortError' })
  assert.equal(app.state.sessionId, 'session-newer')
})

test('完整done后才异步请求一次推荐，不占聊天锁；新提问作废上一轮推荐', async () => {
  const app = setup(), pending = deferred(), requests = []
  app.sandbox.fetch = async (url) => {
    requests.push({url,busy:app.state.busy})
    if (url.endsWith('/chat/suggestions')) return pending.promise
    return stream([{type:'text',content:'先查询',agentName:'assistant'}, {type:'text',content:'再插入',agentName:'assistant'}, {type:'done'}])
  }
  app.nodes.get('message-input').value = '为什么'
  await app.run('sendCurrentMessage()')
  assert.equal(requests.filter((r)=>r.url.endsWith('/chat/suggestions')).length, 1)
  assert.equal(requests.at(-1).busy, false)
  assert.equal(app.state.busy, false)
  const firstController = app.state.suggestionController
  app.nodes.get('message-input').value = '再给个提示'
  await app.run('sendCurrentMessage()')
  assert.equal(firstController.signal.aborted, true)
  assert.equal(requests.filter((r)=>r.url.endsWith('/chat/suggestions')).length, 2)
  app.run('invalidateSuggestions()')
  pending.resolve(response())
  await tick()
})

test('缺done、空回答、错误事件、取消生成均不请求推荐', async () => {
  for (const events of [[{type:'text',content:'部分回答'}], [{type:'done'}], [{type:'error',content:'失败'}]]) {
    const app = setup(), urls = []
    app.sandbox.fetch = async (url) => { urls.push(url); return stream(events) }
    app.nodes.get('message-input').value = '提示'
    await app.run('sendCurrentMessage()')
    assert.equal(urls.filter((url) => url.endsWith('/chat/suggestions')).length, 0)
    assert.equal(app.state.suggestionController, null)
    assert.equal(app.state.busy, false)
  }
  const app = setup(), pending = deferred()
  app.sandbox.fetch = () => pending.promise
  app.nodes.get('message-input').value = '提示'
  const work = app.run('sendCurrentMessage()')
  await tick()
  app.run('cancelGeneration()')
  await work
  pending.resolve(stream([{type:'text',content:'旧回复'}, {type:'done'}]))
  await tick()
  assert.equal(app.state.busy, false)
  assert.equal(app.state.suggestionController, null)
  assert.ok(!app.state.messages.some((message)=>message.content==='旧回复'))
})

test('旧对话finally不能解锁新对话，过期存储恢复不能覆盖当前会话', async () => {
  const app = setup(), old = deferred(), latest = deferred()
  app.sandbox.fetch = () => old.promise
  app.nodes.get('message-input').value = '旧问题'
  const first = app.run('sendCurrentMessage()')
  await tick()
  app.sandbox.fetch = async (url) => url.endsWith('/create_session')
    ? response({sessionId:'new-session'}) : response({sessions:[],hasMore:false,nextOffset:0})
  await app.run('startNewChat()')
  app.state.sessionId = 'new-session'
  app.sandbox.fetch = () => latest.promise
  app.nodes.get('message-input').value = '新问题'
  const second = app.run('sendCurrentMessage()')
  await first
  assert.equal(app.state.busy, true)
  app.run('cancelGeneration()')
  await second
  old.resolve(stream([{type:'done',content:'过期回答'}]))
  latest.resolve(stream([{type:'done',content:'取消的回答'}]))
  const storage = deferred()
  app.sandbox.chrome.storage.local.get = () => storage.promise
  app.sandbox.fetch = async (url) => url.includes('/chat/session_detail')
    ? response({session:{sessionId:'obsolete',messageCount:1},messages:[{id:1,role:'user',content:'过期存储'}]})
    : url.endsWith('/create_session') ? response({sessionId:'newest-session'}) : response({sessions:[],hasMore:false,nextOffset:0})
  const restoring = app.run("restoreConversation('obsolete')")
  await tick()
  await app.run('startNewChat()')
  storage.resolve({[app.run("conversationKey('obsolete')")]:{sessionId:'obsolete',messages:[{role:'user',content:'过期存储'}]}})
  await restoring
  assert.equal(app.state.sessionId, 'newest-session')
})

test('旧后端404、错误业务码、非法JSON及重复建议均安静回退默认按钮', async () => {
  for (const result of [
    {ok:false,status:404}, {ok:true,json:async()=>({code:'0001'})},
    {ok:true,json:async()=>{throw new SyntaxError('invalid json')}}, response([{label:'A',prompt:'x'}, {label:'A',prompt:'y'}]),
  ]) {
    const app = setup()
    app.sandbox.fetch = async () => result
    await app.run('refreshSuggestions()')
    assert.equal(labels(app)[0], '帮我分析解题思路')
    assert.equal(app.state.messages.length, 2)
    assert.equal(app.state.suggestionController, null)
  }
})

test('取消正在读取的流立即释放聊天锁，迟到的字块不写入新会话', async () => {
  const app = setup(), reading = deferred(), cancelled = deferred()
  let reads = 0
  app.sandbox.fetch = async () => ({ok:true,body:{getReader:()=>({
    read:async()=> ++reads === 1 ? {done:false,value:new TextEncoder().encode('{"type":"text","content":"已有内容"}\n')}
      : reading.promise,
    cancel:()=>cancelled.promise, releaseLock() {},
  })}})
  app.nodes.get('message-input').value = '给提示'
  const work = app.run('sendCurrentMessage()')
  await tick()
  assert.ok(app.state.messages.some((message)=>message.content==='已有内容'))
  app.run('cancelGeneration()')
  await work
  assert.equal(app.state.busy, false)
  app.sandbox.fetch = async (url) => url.endsWith('/create_session')
    ? response({sessionId:'new-session'}) : response({sessions:[],hasMore:false,nextOffset:0})
  await app.run('startNewChat()')
  reading.resolve({done:false,value:new TextEncoder().encode('{"type":"text","content":"过期字块"}\n{"type":"done"}\n')})
  cancelled.resolve()
  await tick()
  assert.equal(app.state.suggestionController, null)
  assert.ok(!app.state.messages.some((message)=>message.content.includes('过期字块')))
})

test('新会话保存尚未完成时阻止提问，完成后解除会话操作锁', async () => {
  const app = setup(), saving = deferred()
  let requests = 0
  app.sandbox.chrome.storage.local.set = ()=>saving.promise
  app.sandbox.fetch = async (url) => {
    requests++
    return url.endsWith('/create_session') ? response({sessionId:'new-session'}) : response({sessions:[],hasMore:false,nextOffset:0})
  }
  const resetting = app.run('startNewChat()')
  app.nodes.get('message-input').value = '新问题'
  await app.run('sendCurrentMessage()')
  assert.equal(requests, 0)
  assert.equal(app.state.sessionBusy, true)
  saving.resolve()
  await resetting
  assert.equal(app.state.sessionId, 'new-session')
  assert.equal(app.state.sessionBusy, false)
  assert.equal(app.state.busy, false)
})
