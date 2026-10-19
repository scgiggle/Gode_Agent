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
  let resolve
  const promise = new Promise((r) => { resolve = r })
  return { promise, resolve }
}
function element() {
  const classes = new Set()
  return {
    textContent: '', innerHTML: '', value: '', disabled: false, dataset: {}, style: {}, listeners: {}, children: [],
    classList: {
      add: (...keys) => keys.forEach((key) => classes.add(key)),
      remove: (...keys) => keys.forEach((key) => classes.delete(key)),
      contains: (key) => classes.has(key),
      toggle(key, on = !classes.has(key)) { on ? classes.add(key) : classes.delete(key) },
    },
    addEventListener(key, fn) { this.listeners[key] = fn }, setAttribute() {},
    querySelectorAll() { return [] }, querySelector() { return null },
    appendChild(node) { this.children.push(node) }, replaceChildren(...nodes) { this.children = nodes },
    focus() {}, remove() {},
  }
}
const response = (data, code = '0000', info = '') => ({ ok: true, json: async () => ({ code, info, data }) })
function setup() {
  const nodes = new Map([...html.matchAll(/id="([^"]+)"/g)].map((m) => [m[1], element()]))
  nodes.get('composer-hint').parentElement = element()
  const storage = {}, removed = [], requests = [], sessions = new Map(), histories = new Map(), timers = new Map()
  let nextId = 2, timerId = 0, route
  sessions.set('a', { sessionId: 'a', title: '讨论A', messageCount: 2 })
  sessions.set('b', { sessionId: 'b', title: '讨论B', messageCount: 2 })
  histories.set('a', [{ id: 1, role: 'user', content: '当前力扣题目：两数之和\n用户问题：问题A' }, { id: 2, role: 'assistant', content: '回答A' }])
  histories.set('b', [{ id: 3, role: 'user', content: '问题B' }, { id: 4, role: 'assistant', content: '回答B' }])
  const fetch = async (address, options = {}) => {
    const url = new URL(address), body = options.body && JSON.parse(options.body)
    requests.push({ url, body })
    if (url.pathname.endsWith('/create_session')) {
      const id = 'new-' + ++nextId
      sessions.set(id, {sessionId: id, title: body.title || '新对话', messageCount: 0})
      histories.set(id, [])
      return response({sessionId: id})
    }
    if (url.pathname.endsWith('/chat/sessions')) {
      const rows = [...sessions.values()], offset = Number(url.searchParams.get('offset')), limit = Number(url.searchParams.get('limit'))
      return response({sessions: rows.slice(offset, offset + limit), hasMore: rows.length > offset + limit, nextOffset: Math.min(rows.length, offset + limit)})
    }
    if (url.pathname.endsWith('/chat/session_detail')) {
      const id = url.searchParams.get('sessionId'), session = sessions.get(id)
      if (!session) return response(null, '0002', '会话不存在')
      const before = url.searchParams.get('beforeMessageId'), limit = Number(url.searchParams.get('limit'))
      const all = histories.get(id).filter((m) => !before || m.id < Number(before))
      const page = all.slice(-limit)
      return response({session, messages: page, hasMore: all.length > page.length, beforeMessageId: page[0]?.id ?? null})
    }
    if (url.pathname.endsWith('/chat/message')) {
      const session = sessions.get(body.sessionId)
      if (!session) return response(null, '0002', '会话不存在')
      const all = histories.get(body.sessionId)
      const id = Math.max(0, ...[...histories.values()].flat().map((m) => m.id)) + 1
      const message = {id, role: 'assistant', content: body.content}
      all.push(message)
      session.messageCount++
      return response(message)
    }
    if (url.pathname.endsWith('/chat/session_delete')) {
      sessions.delete(body.sessionId); histories.delete(body.sessionId)
      return response(true)
    }
    throw new Error('Unexpected API: ' + url.pathname)
  }
  const sandbox = {
    URL, DOMException, AbortController, TextDecoder, console, crypto: require('node:crypto').webcrypto, fetch,
    setTimeout(fn, ms) { timers.set(++timerId, {fn, ms}); return timerId }, clearTimeout(id) { timers.delete(id) },
    document: { getElementById: (id) => nodes.get(id), createElement: element, querySelector: element },
    chrome: {
      storage: {local:{
        async get(keys) { return Object.fromEntries((Array.isArray(keys) ? keys : [keys]).map((key) => [key, storage[key]])) },
        async set(values) { Object.assign(storage, JSON.parse(JSON.stringify(values))) },
        async remove(key) { removed.push(key); delete storage[key] },
      }},
      runtime: { onMessage: { addListener(fn) { route = fn } } },
    },
  }
  sandbox.window = { setTimeout: sandbox.setTimeout, confirm: () => true }
  vm.createContext(sandbox)
  vm.runInContext(source.slice(0, source.lastIndexOf('void (async () => {')), sandbox)
  const run = (code) => vm.runInContext(code, sandbox)
  run("state.userId='extension-test';state.sessionId='a';state.sessionTitle='讨论A';state.savedMessageCount=2;state.problem={slug:'two-sum',title:'两数之和',frontendId:'1',tags:[]};state.messages=[{id:'m1',role:'user',content:'问题A'},{id:'m2',role:'assistant',content:'回答A',meta:'分析专家',problemSlug:'two-sum'}];renderMessage=()=>{};renderAllMessages=()=>{};showThinking=()=>{};hideThinking=()=>{};readLeetCodeContext=async()=>({problem:state.problem});refreshPendingSubmissionReview=async()=>{}")
  return {sandbox, run, nodes, storage, sessions, histories, removed, requests, state: run('state'), route: (m) => route(m),
    fire(ms) { for (const [id, timer] of [...timers]) if (timer.ms === ms) { timers.delete(id); timer.fn() } }, fetch}
}

test('新建真正请求新ID；同题多个聊天分别保存，旧对话不被覆盖', async () => {
  const app = setup()
  await app.run('startNewChat()')
  const firstId = app.state.sessionId
  assert.notEqual(firstId, 'a')
  assert.equal(app.storage[app.run("conversationKey('a')")].messages[0].content, '问题A')
  await app.run('startNewChat()')
  assert.notEqual(app.state.sessionId, firstId)
  assert.ok(app.storage[app.run('conversationKey(state.sessionId)')])
  assert.equal(app.requests.filter((r) => r.url.pathname.endsWith('/create_session')).length, 2)
  assert.ok(app.requests.filter((r) => r.url.pathname.endsWith('/create_session')).every((r) => !('problemSlug' in r.body)))
})

test('创建失败保留旧会话和消息，不假装清空成功', async () => {
  const app = setup()
  app.sandbox.fetch = async () => response(null, '0001', '数据库失败')
  await app.run('startNewChat()')
  assert.equal(app.state.sessionId, 'a')
  assert.equal(app.state.messages[0].content, '问题A')
  assert.equal(app.state.sessionBusy, false)
  assert.ok(app.nodes.get('session-notice').textContent.includes('数据库失败'))
})

test('切题追加连接记录，不创建会话；读取失败也不丢会话', async () => {
  const app = setup()
  app.run("readLeetCodeContext=async()=>({problem:{slug:'three-sum',title:'三数之和',tags:[]}})")
  await app.run('loadCurrentProblem()')
  assert.equal(app.state.problem.slug, 'three-sum')
  assert.equal(app.state.sessionId, 'a')
  assert.equal(app.state.messages[0].content, '问题A')
  assert.equal(app.requests.length, 1)
  assert.ok(app.requests[0].url.pathname.endsWith('/chat/message'))
  app.run("readLeetCodeContext=async()=>{throw new Error('非力扣页面')}")
  await app.run('loadCurrentProblem()')
  assert.equal(app.state.problem, null)
  assert.equal(app.state.sessionId, 'a')
})

test('切题追加并入库连接消息，第一条提示和已有聊天保持不变', async () => {
  const app = setup()
  await app.run('recordProblemConnection()')
  const first = {...app.state.messages[2]}
  app.run("readLeetCodeContext=async()=>({problem:{slug:'three-sum',title:'三数之和',tags:[]}})")
  await app.run('loadCurrentProblem()')
  assert.equal(app.state.messages[2].content, first.content)
  assert.equal(app.state.messages[2].id, first.id)
  assert.ok(app.state.messages[3].content.includes('已连接《三数之和》'))
  assert.ok(app.state.messages[3].databaseId > first.databaseId)
  assert.equal(app.state.messages[0].content, '问题A')
  assert.equal(app.state.messages[1].content, '回答A')
  assert.equal(app.state.sessionId, 'a')
  assert.equal(app.histories.get('a').length, 4)
  assert.equal(app.sessions.get('a').messageCount, 4)
  assert.equal(app.storage[app.run("conversationKey('a')")].savedMessageCount, 4)
})

test('重新打开恢复普通消息，同题每次读取也直接追加新消息', async () => {
  const app = setup()
  await app.run('recordProblemConnection()')
  const original = app.state.messages[2].content
  delete app.storage[app.run("conversationKey('a')")]
  app.state.sessionId = null
  app.state.messages = []
  await app.run('restoreActiveConversation()')
  await app.run('loadCurrentProblem()')
  await app.run('loadCurrentProblem()')
  assert.equal(app.state.messages[2].content, original)
  assert.equal(app.state.messages.length, 5)
  assert.equal(app.histories.get('a').length, 5)
  app.run("readLeetCodeContext=async()=>({problem:{slug:'three-sum',title:'三数之和',tags:[]}})")
  await app.run('loadCurrentProblem()')
  assert.equal(app.state.messages[2].content, original)
  assert.ok(app.state.messages[5].content.includes('三数之和'))
  assert.equal(app.state.sessionId, 'a')
})

test('A→B→A 每次追加；离开题目页不改动历史连接消息', async () => {
  const app = setup()
  await app.run('recordProblemConnection()')
  app.run("readLeetCodeContext=async()=>({problem:{slug:'three-sum',title:'三数之和',tags:[]}})")
  await app.run('loadCurrentProblem()')
  app.run("readLeetCodeContext=async()=>({problem:{slug:'two-sum',title:'两数之和',tags:[]}})")
  await app.run('loadCurrentProblem()')
  const content = app.state.messages.map((m) => m.content)
  assert.deepEqual(Array.from(app.state.messages.slice(2), (m) => m.content.match(/《(.+?)》/)[1]), ['两数之和', '三数之和', '两数之和'])
  app.run("readLeetCodeContext=async()=>{throw new Error('非力扣页面')}")
  await app.run('loadCurrentProblem()')
  assert.equal(app.state.problem, null)
  assert.deepEqual(app.state.messages.map((m) => m.content), content)
  assert.equal(app.state.sessionId, 'a')
})

test('快速切题的普通消息按 A→B→A 顺序保存', async () => {
  const app = setup(), pending = deferred()
  let dispatched = 0
  app.sandbox.fetch = async (url, options) => {
    if (url.endsWith('/chat/message') && ++dispatched === 1) await pending.promise
    return app.fetch(url, options)
  }
  const first = app.run('recordProblemConnection()')
  app.state.problem = {slug:'three-sum',title:'三数之和'}
  const second = app.run('recordProblemConnection()')
  app.state.problem = {slug:'two-sum',title:'两数之和'}
  const third = app.run('recordProblemConnection()')
  await tick()
  assert.equal(dispatched, 1)
  pending.resolve()
  await Promise.all([first, second, third])
  assert.deepEqual(Array.from(app.state.messages.slice(2), (m) => m.content.match(/《(.+?)》/)[1]), ['两数之和','三数之和','两数之和'])
  assert.equal(app.sessions.get('a').messageCount, 5)
  assert.equal(app.nodes.get('send-button').disabled, false)
})

test('提示保存失败不显示为成功、不改动历史，刷新可以重试', async () => {
  const app = setup()
  app.sandbox.fetch = async () => response(null, '0001', '数据库失败')
  assert.equal(await app.run('recordProblemConnection()'), false)
  assert.equal(app.state.messages.length, 2)
  assert.equal(app.histories.get('a').length, 2)
  assert.ok(app.nodes.get('session-notice').textContent.includes('保存失败'))
  app.sandbox.fetch = app.fetch
  assert.equal(await app.run('recordProblemConnection()'), true)
  assert.equal(app.state.messages.length, 3)
  assert.equal(app.histories.get('a').length, 3)
})

test('迟到的提示只入原会话，切换后端后不污染新聊天', async () => {
  const app = setup(), pending = deferred()
  app.sandbox.fetch = async (url, options) => {
    if (url.endsWith('/chat/message')) await pending.promise
    return app.fetch(url, options)
  }
  const saving = app.run('recordProblemConnection()')
  await tick()
  app.run("resetChatContext();state.settings.apiBaseUrl='http://localhost:9000/api/v1';state.sessionId='b';state.messages=[{content:'新后端的聊天'}]")
  pending.resolve()
  await saving
  assert.equal(app.state.messages.length, 1)
  assert.equal(app.state.messages[0].content, '新后端的聊天')
  assert.equal(app.histories.get('a').length, 3)
  assert.equal(app.requests[0].url.port, '8091')
})

test('更早的普通消息按分页恢复，新连接消息直接追加到末尾', async () => {
  const app = setup()
  const note = {id:1,role:'assistant',content:'已连接《两数之和》。'}
  app.histories.set('b',[note,...Array.from({length:60},(_,i)=>({id:i+2,role:i%2?'assistant':'user',content:'消息'+i}))])
  app.sessions.get('b').messageCount = 61
  await app.run("switchConversation('b')")
  assert.equal(app.state.messages.length, 50)
  await app.run('recordProblemConnection()')
  assert.equal(app.state.messages.length, 51)
  assert.equal(app.histories.get('b').length, 62)
  await app.run('loadOlderMessages()')
  assert.equal(app.state.messages.length, 62)
  assert.equal(app.state.messages[0].databaseId, 1)
  assert.equal(app.state.messages.at(-1).databaseId, 62)
})

test('初始化之外发送消息不会自行创建会话，失败时保留输入', async () => {
  const app = setup()
  app.state.sessionId = null
  app.nodes.get('message-input').value = '给我提示'
  await app.run('sendCurrentMessage()')
  assert.equal(app.requests.length, 0)
  assert.equal(app.nodes.get('message-input').value, '给我提示')
  assert.ok(app.nodes.get('session-notice').textContent.includes('新对话'))
})

test('切换恢复目标会话及当前指针，但不跳转或绑定题目', async () => {
  const app = setup()
  await app.run("switchConversation('b')")
  assert.equal(app.state.sessionId, 'b')
  assert.equal(app.state.messages[0].content, '问题B')
  assert.equal(app.state.problem.slug, 'two-sum')
  assert.equal(app.storage[app.run('activeConversationKey()')], 'b')
  assert.equal(app.storage[app.run("conversationKey('a')")].messages[0].content, '问题A')
})

test('本地Agent分组仅在后端计数一致时恢复，陈旧缓存回退数据库', async () => {
  const app = setup()
  const key = app.run("conversationKey('b')")
  app.storage[key] = {savedMessageCount:2,messages:[{id:'local',role:'assistant',content:'分组回答',meta:'题目分析专家'}]}
  await app.run("switchConversation('b')")
  assert.equal(app.state.messages[0].meta, '题目分析专家')
  app.sessions.get('b').messageCount = 3
  await app.run("switchConversation('b')")
  assert.equal(app.state.messages[0].content, '问题B')
})

test('迟到旧聊天不会写入切换后的会话，也不会覆盖消息列表', async () => {
  const app = setup(), old = deferred()
  app.sandbox.fetch = (url, options) => url.endsWith('/chat_react_stream') ? old.promise : app.fetch(url, options)
  app.nodes.get('message-input').value = '旧问题'
  const sending = app.run('sendCurrentMessage()')
  await tick()
  await app.run("switchConversation('b')")
  await sending
  old.resolve({ok:true,body:{getReader:()=>({read:async()=>({done:true}),releaseLock(){},cancel:async()=>{}})}})
  await tick()
  assert.equal(app.state.sessionId, 'b')
  assert.ok(!app.state.messages.some((m) => m.content === '旧问题'))
})

test('长历史按cursor向前加载，无重复；历史用户文本只展示真实提问', async () => {
  const app = setup()
  app.histories.set('b', Array.from({length:52},(_,i)=>({id:i+1,role:i%2?'assistant':'user',content:i===2?'当前力扣题目：A\n用户问题：只显示我':'消息'+i})))
  app.sessions.get('b').messageCount = 52
  await app.run("switchConversation('b')")
  assert.equal(app.state.messages.length, 50, app.nodes.get('session-notice').textContent)
  assert.equal(app.state.messages[0].content, '只显示我')
  await app.run('loadOlderMessages()')
  assert.equal(app.state.messages.length, 52)
  assert.equal(new Set(app.state.messages.map((m)=>m.id)).size,52)
  assert.equal(app.state.historyHasMore, false)
  const query = app.requests.find((r)=>r.url.searchParams.has('beforeMessageId')).url.searchParams
  assert.equal(query.get('beforeMessageId'), '3')
})

test('删除当前会话只清自己的缓存和指针，不自动新建，不清长期记忆或错题', async () => {
  const app = setup()
  await app.run('saveConversation()')
  app.storage['other-learning-memory'] = '保留'
  await app.run("deleteConversation('a')")
  await tick()
  assert.equal(app.sessions.has('a'), false)
  assert.equal(app.state.sessionId, null)
  assert.equal(app.storage[app.run('activeConversationKey()')], null)
  assert.equal(app.storage['other-learning-memory'], '保留')
  assert.deepEqual(app.removed, [app.run("conversationKey('a')")])
  assert.ok(app.requests.every((r)=>!r.url.pathname.endsWith('/create_session')))
})

test('删除非当前会话不改变当前对话；失败或取消确认不清记录', async () => {
  const app = setup()
  await app.run("deleteConversation('b')")
  assert.equal(app.state.sessionId, 'a')
  assert.equal(app.state.messages[0].content, '问题A')
  app.sandbox.fetch = async () => response(null, '0002', '仍在生成，请稍后')
  await app.run("deleteConversation('a')")
  assert.equal(app.state.sessionId, 'a')
  assert.equal(app.sessions.has('a'), true)
  const calls = app.requests.length
  app.sandbox.window.confirm = () => false
  await app.run("deleteConversation('a')")
  assert.equal(app.requests.length,calls)
})

test('会话列表分页和文本转义；列表刷新不把陈旧本地消息标记成已同步', async () => {
  const app = setup()
  for(let i=0;i<24;i++)app.sessions.set('s'+i,{sessionId:'s'+i,title:i===0?'<img src=x>':'对话'+i,messageCount:0})
  app.sessions.get('a').messageCount = 20
  await app.run('refreshSessionList()')
  assert.equal(app.state.sessionRecords.length, 20)
  assert.equal(app.state.savedMessageCount, 2)
  assert.ok(app.nodes.get('session-list').innerHTML.includes('&lt;img'))
  await app.run('refreshSessionList(true)')
  assert.equal(app.state.sessionRecords.length, 26)
  assert.equal(app.state.sessionListHasMore, false)
})

test('重新打开按用户/Agent/后端身份恢复会话，不依赖当前题目', async () => {
  const app = setup()
  app.storage[app.run('activeConversationKey()')] = 'b'
  app.state.sessionId = null
  app.state.messages = []
  await app.run('restoreActiveConversation()')
  assert.equal(app.state.sessionId,'b')
  const key = app.run('activeConversationKey()')
  app.state.settings.agentId = 'another'
  assert.notEqual(app.run('activeConversationKey()'),key)
})

test('创建超时及时解锁，晚到的新ID不能覆盖旧聊天', async () => {
  const app = setup(), pending = deferred()
  app.sandbox.fetch = () => pending.promise
  const creating = app.run('startNewChat()')
  await tick()
  app.fire(15000)
  await creating
  assert.equal(app.state.sessionId,'a')
  assert.equal(app.state.sessionBusy,false)
  pending.resolve(response({sessionId:'late'}))
  await tick()
  assert.equal(app.state.sessionId,'a')
})

test('创建期间切题不会取消与题目无关的会话操作', async () => {
  const app = setup(), pending = deferred()
  app.sandbox.fetch = (url, options) => url.endsWith('/create_session') ? pending.promise : app.fetch(url, options)
  const creating = app.run('startNewChat()')
  await tick()
  app.route({type:'GODE_LEETCODE_ROUTE_CHANGED'})
  app.run("readLeetCodeContext=async()=>({problem:{slug:'three-sum',title:'三数之和',tags:[]}})")
  await app.run('loadCurrentProblem()')
  pending.resolve(response({sessionId:'created'}))
  await creating
  assert.equal(app.state.sessionId,'created')
  assert.equal(app.state.problem.slug,'three-sum')
})

test('恢复当前指针期间切换用户或后端，旧存储响应不能恢复旧会话', async () => {
  const app = setup(), pending = deferred()
  app.state.sessionId = null
  app.sandbox.chrome.storage.local.get = () => pending.promise
  const oldKey = app.run('activeConversationKey()')
  const restoring = app.run('restoreActiveConversation()')
  app.run("resetChatContext();state.settings.agentId='another';state.sessionId='new-scope'")
  pending.resolve({[oldKey]:'b'})
  await restoring
  assert.equal(app.state.sessionId,'new-scope')
  assert.equal(app.requests.length,0)
})

test('删除成功后清本地缓存期间切换身份，不能清理新身份的聊天视图', async () => {
  const app = setup(), pending = deferred()
  app.sandbox.chrome.storage.local.remove = () => pending.promise
  const deleting = app.run("deleteConversation('a')")
  await tick()
  app.run("resetChatContext();state.settings.agentId='another';state.sessionId='a';state.messages=[{content:'新身份消息'}];state.sessionRecords=[{sessionId:'a',title:'新身份会话'}]")
  pending.resolve()
  await deleting
  assert.equal(app.state.sessionId,'a')
  assert.equal(app.state.messages[0].content,'新身份消息')
  assert.equal(app.state.sessionRecords.length,1)
})

test('无历史首次打开自动创建一个会话，保留输入且重复初始化不重复创建', async () => {
  const app = setup()
  app.state.sessionId = null
  app.sessions.clear()
  app.histories.clear()
  app.nodes.get('message-input').value = '你好'
  const opening = app.run('restoreActiveConversation()')
  assert.equal(app.state.sessionBusy,true)
  await app.run('restoreActiveConversation()')
  await opening
  await app.run('restoreActiveConversation()')
  assert.ok(app.state.sessionId.startsWith('new-'))
  assert.equal(app.requests.filter((r)=>r.url.pathname.endsWith('/create_session')).length,1)
  assert.equal(app.storage[app.run('activeConversationKey()')],app.state.sessionId)
  assert.equal(app.state.savedMessageCount,1)
  assert.equal(app.state.sessionBusy,false)
  assert.equal(app.nodes.get('message-input').value,'你好')
  assert.equal(app.nodes.get('session-notice').textContent,'')
})

test('无本地指针但有历史时恢复最近会话，不能创建多余空会话', async () => {
  const app = setup()
  app.state.sessionId = null
  await app.run('restoreActiveConversation()')
  assert.equal(app.state.sessionId,'a')
  assert.equal(app.state.messages[0].content,'问题A')
  assert.equal(app.requests.filter((r)=>r.url.pathname.endsWith('/create_session')).length,0)
})

test('旧指针对应会话已删除时先查历史，确实为空才自动新建', async () => {
  const app = setup()
  app.state.sessionId = null
  app.storage[app.run('activeConversationKey()')] = 'deleted'
  app.sessions.clear()
  app.histories.clear()
  await app.run('restoreActiveConversation()')
  assert.ok(app.state.sessionId.startsWith('new-'))
  assert.equal(app.requests[0].url.pathname.endsWith('/chat/session_detail'),true)
  assert.equal(app.requests[1].url.pathname.endsWith('/chat/sessions'),true)
  assert.equal(app.requests[2].url.pathname.endsWith('/create_session'),true)
})

test('历史查询网络、数据库错误或非法响应不当成空列表，不自动创建', async () => {
  for (const fail of [async()=>{throw new TypeError('网络断开')},async()=>response(null,'0001','数据库失败'),async()=>response({})]) {
    const app = setup()
    app.state.sessionId = null
    app.sandbox.fetch = async (url,options) => {
      assert.equal(url.includes('/create_session'),false)
      return fail()
    }
    await app.run('restoreActiveConversation()')
    assert.equal(app.state.sessionId,null)
    assert.equal(app.state.sessionBusy,false)
    assert.ok(app.nodes.get('session-notice').textContent)
  }
})

test('自动创建超时后解除锁，晚到ID不能覆盖会话', async () => {
  const app = setup(), pending = deferred()
  app.state.sessionId = null
  app.sessions.clear()
  app.sandbox.fetch = (url,options) => url.endsWith('/create_session') ? pending.promise : app.fetch(url,options)
  const opening = app.run('restoreActiveConversation()')
  await tick()
  app.fire(15000)
  await opening
  assert.equal(app.state.sessionBusy,false)
  assert.equal(app.state.sessionId,null)
  pending.resolve(response({sessionId:'late'}))
  await tick()
  assert.equal(app.state.sessionId,null)
})

test('自动初始化期间切题仍能创建会话，不清除正在输入的问题', async () => {
  const app = setup(), pending = deferred()
  app.state.sessionId = null
  app.sessions.clear()
  app.sandbox.fetch = (url,options) => url.endsWith('/create_session') ? pending.promise : app.fetch(url,options)
  const opening = app.run('restoreActiveConversation()')
  await tick()
  app.route({type:'GODE_LEETCODE_ROUTE_CHANGED'})
  app.run("readLeetCodeContext=async()=>({problem:{slug:'three-sum',title:'三数之和',tags:[]}})")
  await app.run('loadCurrentProblem()')
  pending.resolve(response({sessionId:'auto-created'}))
  await opening
  assert.equal(app.state.sessionId,'auto-created')
  assert.equal(app.state.problem.slug,'three-sum')
})

test('旧指针失效但仍有历史时恢复最近聊天，而不是创建新聊天', async () => {
  const app = setup()
  app.state.sessionId = null
  app.storage[app.run('activeConversationKey()')] = 'deleted'
  await app.run('restoreActiveConversation()')
  assert.equal(app.state.sessionId,'a')
  assert.equal(app.requests.some((r)=>r.url.pathname.endsWith('/create_session')),false)
})

test('恢复缓存超时后解锁，迟到缓存不能重新选中旧会话', async () => {
  const app = setup(), pending = deferred()
  app.state.sessionId = null
  const activeKey = app.run('activeConversationKey()')
  app.sandbox.chrome.storage.local.get = async (key) => key === activeKey ? {[key]:'b'} : pending.promise
  const opening = app.run('restoreActiveConversation()')
  await tick()
  app.fire(15000)
  await opening
  assert.equal(app.state.sessionBusy,false)
  assert.equal(app.state.sessionId,null)
  pending.resolve({})
  await tick()
  assert.equal(app.state.sessionId,null)
  assert.equal(app.requests.some((r)=>r.url.pathname.endsWith('/create_session')),false)
})
