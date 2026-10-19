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
  return {
    innerHTML: '', textContent: '', value: '', disabled: false, dataset: {}, style: {}, listeners: {},
    classList: {
      add: (...names) => names.forEach((name) => classes.add(name)),
      remove: (...names) => names.forEach((name) => classes.delete(name)),
      contains: (name) => classes.has(name),
      toggle(name, force = !classes.has(name)) { force ? classes.add(name) : classes.delete(name) },
    },
    addEventListener(name, listener) { this.listeners[name] = listener },
    querySelectorAll() { return [] }, querySelector() { return null },
    appendChild() {}, focus() {}, remove() {}, hasAttribute() { return false },
  }
}

function setup({ realMarkdown = false } = {}) {
  const nodes = new Map([...html.matchAll(/id="([^"]+)"/g)].map((match) => [match[1], element()]))
  // 详情中的动态节点只用于检查文本输出，不模拟浏览器布局。
  nodes.set('mistake-ai-analysis', element())
  nodes.set('mistake-action-plan', element())
  nodes.set('mistake-fail-reason', element())
  nodes.set('mistake-fail-case', element())
  nodes.set('mistake-error-summary', element())
  nodes.set('mistake-form-error', element())
  nodes.get('composer-hint').parentElement = element()
  const requests = []
  const updates = []
  const record = {
    id: 71, problemId: '1', problemSlug: 'two-sum', title: '两数之和', difficulty: '简单',
    errorSummary: '不能复用同一元素', status: '待复习', tags: '数组, 哈希表', reviewCount: 0,
  }
  const sandbox = {
    URL, AbortController, setTimeout, clearTimeout, console, crypto: require('node:crypto').webcrypto,
    document: { getElementById: (id) => nodes.get(id), querySelector: () => element(), createElement: element },
    window: { setTimeout, confirm: () => true },
    FormData: class { constructor(form) { this.form = form } entries() { return Object.entries(this.form.values) } },
    chrome: {
      storage: { local: { get: async () => ({ godeSidepanelUserId: 'extension-test' }), set: async () => {} } },
      runtime: { onMessage: { addListener() {} } },
      tabs: {
        query: async () => [{ id: 8, url: 'https://leetcode.cn/problems/two-sum/' }],
        update: async (id, properties) => updates.push({ id, ...properties }),
        sendMessage: async () => ({ success: true, context: { problem: {
          frontendId: '1', slug: 'two-sum', title: '两数之和', difficulty: '简单', tags: ['数组', '哈希表'],
        } } }),
      },
    },
  }
  let deleted = false
  sandbox.fetch = async (url, options) => {
    const request = { url: new URL(url), options, body: options.body ? JSON.parse(options.body) : null }
    requests.push(request)
    const action = request.url.pathname.split('/').at(-1)
    let data
    if (action === 'page') data = deleted ? { records: [], total: 0, totalPages: 0 }
      : { records: [record], total: 12, totalPages: 2 }
    if (action === 'detail') data = { mistake: record, latestReview: { aiAnalysis: '**先查再放**' } }
    if (action === 'save') data = record.id
    if (action === 'start_review') { record.status = '复习中'; data = true }
    if (action === 'mark_mastered') { record.status = '已掌握'; record.reviewCount++; data = true }
    if (action === 'delete') { deleted = true; data = true }
    return { ok: true, json: async () => ({ code: '0000', data }) }
  }
  vm.createContext(sandbox)
  vm.runInContext(source.slice(0, source.lastIndexOf('void (async () => {')), sandbox)
  vm.runInContext("state.userId = 'extension-test'; state.activeView = 'mistake-book'", sandbox)
  if (!realMarkdown) vm.runInContext('renderMarkdownInto = (node, text) => { node.textContent = text }', sandbox)
  const state = vm.runInContext('state', sandbox)
  return { sandbox, state, nodes, requests, updates, record, run: (code) => vm.runInContext(code, sandbox) }
}

test('每个静态 DOM 引用都有对应元素，原型数据已移除', () => {
  const ids = source.match(/const elements = Object.fromEntries\(\[([\s\S]*?)\]\.map/)[1]
  for (const match of ids.matchAll(/'([^']+)'/g)) assert.ok(html.includes(`id="${match[1]}"`), match[1])
  assert.ok(!source.includes('DEMO_MISTAKES'))
})

test('首次打开直接请求真实数据，10 条分页和总数', async () => {
  const app = setup()
  await app.run('loadMistakeBook()')
  const page = app.requests[0].url.searchParams
  assert.equal(page.get('userId'), 'extension-test')
  assert.equal(page.get('status'), '待复习')
  assert.equal(page.get('pageSize'), '10')
  assert.equal(app.nodes.get('mistake-book-count').textContent, '12')
  assert.ok(app.nodes.get('mistake-book-list').innerHTML.includes('两数之和'))
  assert.equal(app.nodes.get('mistake-next-page').disabled, false)
})

test('标签、搜索、页码传给后端，数量查询不携带筛选', async () => {
  const app = setup()
  app.state.mistakeFilter = 'hash'
  app.state.mistakeQuery = '重复 元素'
  app.state.mistakePage = 2
  await app.run('loadMistakeBook()')
  const params = app.requests[0].url.searchParams
  assert.equal(params.get('tag'), '哈希表')
  assert.equal(params.get('keyword'), '重复 元素')
  assert.equal(params.get('page'), '2')
  assert.equal(params.get('status'), null)
  assert.equal(app.requests[1].url.searchParams.get('tag'), null)
})

test('详情使用数据库 ID，展示分析，关闭期间的旧响应不打开详情', async () => {
  const app = setup()
  await app.run('showMistakeDetail(71)')
  assert.equal(app.requests[0].url.searchParams.get('mistakeId'), '71')
  assert.equal(app.nodes.get('mistake-ai-analysis').textContent, '**先查再放**')
  let resolve
  app.sandbox.fetch = () => new Promise((done) => { resolve = done })
  const loading = app.run('showMistakeDetail(71)')
  app.run('closeMistakeDetail()')
  resolve({ ok: true, json: async () => ({ code: '0000', data: { mistake: app.record } }) })
  await loading
  assert.equal(app.state.mistakeDetail, null)
  assert.ok(app.nodes.get('mistake-detail').classList.contains('hidden'))
})

function useDetailResponse(app, data) {
  const originalFetch = app.sandbox.fetch
  app.sandbox.fetch = async (url, options) => {
    if (new URL(url).pathname.endsWith('/detail')) {
      app.requests.push({ url: new URL(url), options })
      return { ok: true, json: async () => ({ code: '0000', data }) }
    }
    return originalFetch(url, options)
  }
}

test('旧接口仍显示最新分析，明确说明历史未提供，不冒充只有一次复盘', async () => {
  const app = setup()
  await app.run('showMistakeDetail(71)')
  const markup = app.nodes.get('mistake-detail').innerHTML
  assert.ok(markup.includes('接口尚未提供历史列表'))
  assert.ok(!markup.includes('共 1 次复盘'))
  assert.ok(markup.includes('时间未记录'))
  assert.equal(app.state.mistakeReviews.length, 1)
  assert.equal(app.nodes.get('mistake-ai-analysis').textContent, '**先查再放**')
  assert.ok(!markup.includes(app.record.errorSummary), '不使用主表摘要冒充复盘错因')
})

test('复盘按日期、ID 倒序，默认选最新且不修改原数组', async () => {
  const app = setup()
  const reviews = [
    { id: 11, createdAt: '2026-10-08T10:00:00', aiAnalysis: '较早' },
    { id: 9, createdAt: '2026-10-09T10:00:00', aiAnalysis: '同时间较小ID' },
    { id: 10, createdAt: '2026-10-09T10:00:00', aiAnalysis: '最新分析' },
  ]
  useDetailResponse(app, { mistake: app.record, reviews })
  await app.run('showMistakeDetail(71)')
  assert.deepEqual(Array.from(app.state.mistakeReviews, (review) => review.id), [10, 9, 11])
  assert.deepEqual(reviews.map((review) => review.id), [11, 9, 10])
  assert.equal(app.state.selectedMistakeReview, 0)
  assert.equal(app.nodes.get('mistake-ai-analysis').textContent, '最新分析')
  assert.ok(app.nodes.get('mistake-detail').innerHTML.includes('共 3 次复盘'))
  assert.ok(!app.nodes.get('mistake-detail').innerHTML.includes('接口尚未提供'))
})

test('点击历史记录只切换该次错因、用例、分析和 Markdown 方案，不请求后端', async () => {
  const app = setup()
  useDetailResponse(app, { mistake: app.record, reviews: [
    { id: 2, failReason: '新错因', failCase: '新用例', aiAnalysis: '**新分析**', actionPlan: '1. 新计划' },
    { id: 1, failReason: '旧错因', failCase: '旧用例', aiAnalysis: '**旧分析**', actionPlan: '1. 旧计划' },
  ] })
  await app.run('showMistakeDetail(71)')
  app.nodes.get('mistake-detail').scrollTop = 33
  const historyButton = { dataset: { selectMistakeReview: '1' } }
  app.nodes.get('mistake-detail').listeners.click({ target: {
    closest: (selector) => selector === '[data-select-mistake-review]' ? historyButton : null,
  } })
  const body = app.nodes.get('mistake-detail').innerHTML.split('aria-label="所选复盘内容"')[1]
  assert.equal(app.nodes.get('mistake-fail-reason').textContent, '旧错因')
  assert.equal(app.nodes.get('mistake-fail-case').textContent, '旧用例')
  assert.ok(!body.includes('新错因') && !body.includes('新用例'))
  assert.equal(app.nodes.get('mistake-ai-analysis').textContent, '**旧分析**')
  assert.equal(app.nodes.get('mistake-action-plan').textContent, '1. 旧计划')
  assert.equal(app.nodes.get('mistake-detail').scrollTop, 33)
  assert.equal(app.requests.length, 1)
  assert.ok(body.includes('data-review-mistake="71"') && body.includes('data-master-mistake="71"'))
})

test('四块复盘内容统一白色卡片，实际 Markdown 渲染支持粗体、行内代码、代码块和列表', async () => {
  const app = setup({ realMarkdown: true })
  useDetailResponse(app, { mistake: app.record, reviews: [
    { id: 2, failReason: '**重复下标**：先执行 `map.put(3, 0)`。',
      failCase: ['判题结果：**Wrong Answer**', '', '```text', '[3,2,4]', '6', '```'].join('\n'),
      aiAnalysis: '## 问题定位\n\n应先查询，再插入。', actionPlan: '1. 检查重复元素\n2. 验证不同下标' },
    { id: 1, failReason: '**旧错因**', failCase: '旧输入：`[3,3]`', aiAnalysis: '旧分析', actionPlan: '- 旧计划' },
  ] })
  await app.run('showMistakeDetail(71)')
  const markup = app.nodes.get('mistake-detail').innerHTML
  assert.equal((markup.match(/class="message-content mistake-review-content"/g) || []).length, 4)
  assert.ok(app.nodes.get('mistake-fail-reason').innerHTML.includes('<strong>重复下标</strong>'))
  assert.ok(app.nodes.get('mistake-fail-reason').innerHTML.includes('<code>map.put(3, 0)</code>'))
  assert.ok(app.nodes.get('mistake-fail-case').innerHTML.includes('<strong>Wrong Answer</strong>'))
  assert.ok(app.nodes.get('mistake-fail-case').innerHTML.includes('<code>[3,2,4]\n6</code>'))
  assert.ok(app.nodes.get('mistake-ai-analysis').innerHTML.includes('<h2>问题定位</h2>'))
  assert.ok(app.nodes.get('mistake-action-plan').innerHTML.includes('<ol><li>检查重复元素</li>'))
  app.run('selectMistakeReview(1)')
  assert.ok(app.nodes.get('mistake-fail-reason').innerHTML.includes('<strong>旧错因</strong>'))
  assert.ok(!app.nodes.get('mistake-fail-reason').innerHTML.includes('重复下标'))
  assert.ok(app.nodes.get('mistake-fail-case').innerHTML.includes('<code>[3,3]</code>'))
  assert.ok(app.nodes.get('mistake-action-plan').innerHTML.includes('<ul><li>旧计划</li>'))
})

test('旧判题纯文本保留输入换行，无复盘时摘要也使用 Markdown', async () => {
  const app = setup({ realMarkdown: true })
  useDetailResponse(app, { mistake: app.record, latestReview: { failCase: '失败输入：[3,2,4]\n6\n实际输出：[0,0]' } })
  await app.run('showMistakeDetail(71)')
  assert.ok(app.nodes.get('mistake-fail-case').innerHTML.includes('[3,2,4]<br>6<br>实际输出：[0,0]'))
  assert.equal(app.nodes.get('mistake-fail-reason').innerHTML, '<p>暂无记录</p>')
  app.record.errorSummary = '**下标复用**，检查 `i`。'
  useDetailResponse(app, { mistake: app.record, reviews: [] })
  await app.run('showMistakeDetail(71)')
  assert.ok(app.nodes.get('mistake-error-summary').innerHTML.includes('<strong>下标复用</strong>'))
  assert.ok(app.nodes.get('mistake-error-summary').innerHTML.includes('<code>i</code>'))
})

test('Markdown 渲染四个字段均转义 HTML，不执行脚本或生成危险链接', async () => {
  const app = setup({ realMarkdown: true })
  const untrusted = '<img src=x onerror=alert(1)>\n<script>alert(1)</script>\n[危险](javascript:alert)'
  useDetailResponse(app, { mistake: app.record, reviews: [
    { id: 1, failReason: untrusted, failCase: untrusted, aiAnalysis: untrusted, actionPlan: untrusted },
  ] })
  await app.run('showMistakeDetail(71)')
  for (const id of ['mistake-fail-reason', 'mistake-fail-case', 'mistake-ai-analysis', 'mistake-action-plan']) {
    const rendered = app.nodes.get(id).innerHTML
    assert.ok(!rendered.includes('<img') && !rendered.includes('<script') && !rendered.includes('href="javascript:'))
    assert.ok(rendered.includes('&lt;img') && rendered.includes('&lt;script'))
  }
})

test('默认最近五次，展开全部，收起较早选择时回最新，重新打开重置', async () => {
  const app = setup()
  const reviews = Array.from({ length: 8 }, (_, index) => ({ id: index + 1, aiAnalysis: '分析' + (index + 1) }))
  useDetailResponse(app, { mistake: app.record, reviews })
  await app.run('showMistakeDetail(71)')
  const visibleCount = () => (app.nodes.get('mistake-detail').innerHTML.match(/data-select-mistake-review=/g) || []).length
  assert.equal(visibleCount(), 5)
  assert.ok(app.nodes.get('mistake-detail').innerHTML.includes('展开全部 8 次复盘'))
  app.nodes.get('mistake-detail').listeners.click({ target: {
    closest: (selector) => selector === '[data-toggle-mistake-history]' ? {} : null,
  } })
  assert.equal(visibleCount(), 8)
  app.run('selectMistakeReview(7)')
  assert.equal(app.nodes.get('mistake-ai-analysis').textContent, '分析1')
  assert.ok(app.nodes.get('mistake-detail').innerHTML.includes('收起并回到最新复盘'))
  app.run('toggleMistakeHistory()')
  assert.equal(visibleCount(), 5)
  assert.equal(app.state.selectedMistakeReview, 0)
  assert.equal(app.nodes.get('mistake-ai-analysis').textContent, '分析8')
  app.run('toggleMistakeHistory(); selectMistakeReview(6); closeMistakeDetail()')
  assert.equal(app.state.mistakeReviews.length, 0)
  await app.run('showMistakeDetail(71)')
  assert.equal(visibleCount(), 5)
  assert.equal(app.state.selectedMistakeReview, 0)
})

test('空历史与未提供历史区分，缺少复盘时单独显示主表摘要', async () => {
  const app = setup()
  useDetailResponse(app, { mistake: app.record, reviews: [], latestReview: null })
  await app.run('showMistakeDetail(71)')
  let markup = app.nodes.get('mistake-detail').innerHTML
  assert.ok(markup.includes('暂无复盘记录') && markup.includes('共 0 次复盘'))
  assert.ok(markup.includes('当前错误摘要'))
  assert.equal(app.nodes.get('mistake-error-summary').textContent, app.record.errorSummary)
  assert.ok(!markup.includes('接口尚未提供') && !markup.includes('所选复盘内容'))
  useDetailResponse(app, { mistake: app.record, latestReview: null })
  await app.run('showMistakeDetail(71)')
  markup = app.nodes.get('mistake-detail').innerHTML
  assert.ok(markup.includes('接口尚未提供') && markup.includes('接口未返回最新复盘'))
  assert.ok(!markup.includes('共 0 次复盘'))
})

test('历史错因和时间转义，无效记录过滤，旧复盘缺错因不回退主表最新摘要', async () => {
  const app = setup()
  useDetailResponse(app, { mistake: app.record, reviews: [null, 'invalid', [],
    { id: 2, failReason: '<img src=x onerror=alert(1)>', createdAt: '<svg onload=alert(1)>' },
    { id: 1, aiAnalysis: '旧分析' },
  ] })
  await app.run('showMistakeDetail(71)')
  const markup = app.nodes.get('mistake-detail').innerHTML
  assert.equal(app.state.mistakeReviews.length, 2)
  assert.ok(!markup.includes('<img') && !markup.includes('<svg'))
  assert.ok(markup.includes('&lt;img') && markup.includes('&lt;svg'))
  assert.ok(markup.includes('暂无错因摘要') && !markup.includes(app.record.errorSummary))
  app.run('selectMistakeReview(1)')
  assert.ok(!app.nodes.get('mistake-detail').innerHTML.includes(app.record.errorSummary))
})

test('操作中禁止切换历史，非法索引不改变选择', async () => {
  const app = setup()
  useDetailResponse(app, { mistake: app.record, reviews: [{ id: 2 }, { id: 1 }] })
  await app.run('showMistakeDetail(71)')
  app.run('selectMistakeReview(-1); selectMistakeReview(2); selectMistakeReview(NaN); selectMistakeReview(0.5)')
  assert.equal(app.state.selectedMistakeReview, 0)
  app.state.mistakeActionBusy = true
  app.run('selectMistakeReview(1); toggleMistakeHistory()')
  assert.equal(app.state.selectedMistakeReview, 0)
  assert.equal(app.state.mistakeHistoryExpanded, false)
})

test('开始复习先写后端，再用 slug 跳转，不切换到对话', async () => {
  const app = setup()
  app.state.mistakeDetail = { mistake: app.record }
  await app.run('startMistakeReview("71")')
  assert.equal(app.requests[0].url.pathname, '/api/v1/mistake/start_review')
  assert.equal(app.requests[0].body.mistakeId, 71)
  assert.equal(app.requests[0].body.userId, 'extension-test')
  assert.equal(app.updates[0].url, 'https://leetcode.cn/problems/two-sum/')
  assert.equal(app.state.activeView, 'mistake-book')
  assert.equal(app.state.mistakeActionBusy, false)
})

test('状态更新失败不跳转，并解除操作锁', async () => {
  const app = setup()
  app.state.mistakeDetail = { mistake: app.record }
  app.sandbox.fetch = async () => ({ ok: true, json: async () => ({ code: '0002', info: '错题不存在' }) })
  await assert.rejects(app.run('startMistakeReview("71")'), /错题不存在/)
  assert.equal(app.updates.length, 0)
  assert.equal(app.state.mistakeActionBusy, false)
})

test('已掌握接口更新后重新查询列表和详情', async () => {
  const app = setup()
  await app.run('markMistakeMastered("71")')
  assert.equal(app.requests[0].url.pathname, '/api/v1/mistake/mark_mastered')
  assert.equal(app.state.mistakeDetail.mistake.status, '已掌握')
  assert.equal(app.state.mistakeDetail.mistake.reviewCount, 1)
})

test('删除前二次确认，取消不请求后端且保留详情', async () => {
  const app = setup()
  await app.run('showMistakeDetail(71)')
  assert.ok(app.nodes.get('mistake-detail').innerHTML.includes('data-delete-mistake="71"'))
  let confirmation
  app.sandbox.window.confirm = (message) => { confirmation = message; return false }
  const before = app.requests.length
  await app.run('deleteMistake("71")')
  assert.ok(confirmation.includes('两数之和') && confirmation.includes('全部复盘历史') && confirmation.includes('无法恢复'))
  assert.equal(app.requests.length, before)
  assert.equal(app.state.mistakeDetail.mistake.id, 71)
  assert.equal(app.state.mistakeActionBusy, false)
})

test('确认删除发送用户和主记录 ID，清理详情及已保存提示并刷新数量', async () => {
  const app = setup()
  await app.run('showMistakeDetail(71)')
  app.state.reviewFeedback = { id: 71, message: '已保存' }
  await app.run('deleteMistake("71")')
  const request = app.requests.find((request) => request.url.pathname.endsWith('/delete'))
  assert.equal(request.options.method, 'POST')
  assert.deepEqual(request.body, { userId: 'extension-test', mistakeId: 71 })
  assert.equal(app.state.mistakeDetail, null)
  assert.equal(app.state.mistakeReviews.length, 0)
  assert.equal(app.state.reviewFeedback, null)
  assert.ok(app.nodes.get('mistake-detail').classList.contains('hidden'))
  assert.equal(app.nodes.get('mistake-book-count').textContent, '0')
  assert.equal(app.state.mistakeTotal, 0)
  assert.equal(app.state.activeView, 'mistake-book')
})

test('删除失败保留详情和复盘，解除锁允许重试；不接受未确认的成功值', async () => {
  for (const response of [
    { ok: false, status: 500 },
    { ok: true, json: async () => ({ code: '0002', info: '错题不属于当前用户' }) },
    { ok: true, json: async () => ({ code: '0000', data: false }) },
  ]) {
    const app = setup()
    await app.run('showMistakeDetail(71)')
    app.sandbox.fetch = async () => response
    await assert.rejects(app.run('deleteMistake("71")'))
    assert.equal(app.state.mistakeDetail.mistake.id, 71)
    assert.equal(app.state.mistakeReviews.length, 1)
    assert.equal(app.state.mistakeActionBusy, false)
    assert.ok(!app.nodes.get('mistake-detail').classList.contains('hidden'))
  }
})

test('点击删除按钮接通请求，失败提示展示在详情内', async () => {
  const app = setup()
  await app.run('showMistakeDetail(71)')
  const hints = []
  app.nodes.get('mistake-detail').appendChild = (node) => hints.push(node)
  app.sandbox.fetch = async () => ({ ok: false, status: 500 })
  app.nodes.get('mistake-detail').listeners.click({ target: {
    closest: (selector) => selector === '[data-delete-mistake]' ? { dataset: { deleteMistake: '71' } } : null,
  } })
  await new Promise(setImmediate)
  assert.ok(hints.some((node) => node.className.includes('mistake-action-error') && node.textContent.includes('HTTP 500')))
  assert.equal(app.state.mistakeDetail.mistake.id, 71)
  assert.equal(app.state.mistakeActionBusy, false)
})

test('删除请求中禁止重复发送，错误 ID 不触发确认或删除', async () => {
  const app = setup()
  await app.run('showMistakeDetail(71)')
  let confirms = 0
  app.sandbox.window.confirm = () => { confirms++; return true }
  await app.run('deleteMistake("99")')
  assert.equal(confirms, 0)
  const originalFetch = app.sandbox.fetch
  let resolve
  let deletes = 0
  app.sandbox.fetch = (url, options) => url.endsWith('/delete')
    ? (deletes++, new Promise((done) => { resolve = done })) : originalFetch(url, options)
  const pending = app.run('deleteMistake("71")')
  assert.equal(app.state.mistakeActionBusy, true)
  await app.run('deleteMistake("71")')
  assert.equal(deletes, 1)
  assert.equal(confirms, 1)
  resolve({ ok: true, json: async () => ({ code: '0000', data: true }) })
  await pending
  assert.equal(app.state.mistakeActionBusy, false)
})

test('末页最后一题删除后自动回到有效页码，保留筛选和搜索', async () => {
  const app = setup()
  await app.run('showMistakeDetail(71)')
  app.state.mistakePage = 2
  app.state.mistakeFilter = 'all'
  app.state.mistakeQuery = '两数'
  await app.run('deleteMistake("71")')
  assert.equal(app.state.mistakePage, 1)
  assert.equal(app.state.mistakeFilter, 'all')
  assert.equal(app.state.mistakeQuery, '两数')
  assert.equal(app.state.mistakeTotalPages, 1)
  assert.equal(app.state.mistakeRecords.length, 0)
  assert.equal(app.requests.filter((request) => request.url.pathname.endsWith('/page')).length, 4)
})

test('保存当前题确认表单，请求带身份和复盘，随后刷新详情', async () => {
  const app = setup()
  app.state.problem = { slug: 'two-sum' }
  app.state.sessionId = 'session-a'
  app.state.messages = [{ role: 'assistant', meta: '分析专家', content: '先查再放', problemSlug: 'two-sum' }]
  await app.run('showMistakeForm()')
  const markup = app.nodes.get('mistake-detail').innerHTML
  assert.ok(markup.includes('先查再放') && markup.includes('session-a'))
  app.sandbox.form = { values: {
    problemId: '1', problemSlug: 'two-sum', title: '两数之和', difficulty: '简单',
    errorSummary: '重复使用元素', failReason: '', failCase: 'nums=[3,3]',
    aiAnalysis: '先查再放', actionPlan: '检查下标', tags: '数组,哈希表', sourceSessionId: 'session-a',
  } }
  await app.run('saveMistakeForm(form)')
  assert.equal(app.requests[0].body.userId, 'extension-test')
  assert.equal(app.requests[0].body.failReason, '重复使用元素')
  assert.equal(app.requests[0].body.sourceSessionId, 'session-a')
  assert.equal(app.state.mistakeFilter, 'all')
  assert.equal(app.state.mistakeDetail.mistake.id, 71)
})

test('后端失败展示错误和重试，不伪装为空列表或演示数据', async () => {
  const app = setup()
  app.sandbox.fetch = async () => ({ ok: false, status: 500 })
  await app.run('loadMistakeBook()')
  assert.ok(app.nodes.get('mistake-book-list').innerHTML.includes('HTTP 500'))
  assert.ok(app.nodes.get('mistake-book-list').innerHTML.includes('data-retry-mistakes'))
  assert.equal(app.nodes.get('mistake-book-count').textContent, '—')
})

test('快速切换筛选，晚到的旧结果不能覆盖新结果', async () => {
  const app = setup()
  const pending = []
  app.sandbox.fetch = () => new Promise((resolve) => pending.push(resolve))
  const oldRequest = app.run('loadMistakeBook()')
  app.state.mistakeFilter = 'mastered'
  const newRequest = app.run('loadMistakeBook()')
  const result = (title) => ({ ok: true, json: async () => ({ code: '0000', data: {
    records: [{ ...app.record, title }], total: 1, totalPages: 1,
  } }) })
  pending[2](result('新结果')); pending[3](result('新结果'))
  await newRequest
  pending[0](result('旧结果')); pending[1](result('旧结果'))
  await oldRequest
  assert.equal(app.state.mistakeRecords[0].title, '新结果')
  assert.equal(app.state.mistakeLoading, false)
})

test('列表转义后端文本，避免 HTML 注入', async () => {
  const app = setup()
  app.record.title = '<img src=x onerror=alert(1)>'
  await app.run('loadMistakeBook()')
  const markup = app.nodes.get('mistake-book-list').innerHTML
  assert.ok(!markup.includes('<img'))
  assert.ok(markup.includes('&lt;img'))
})

function candidate() {
  return { key: 'candidate-a', tabId: 8, payload: {
    problem: { frontendId: '1', slug: 'two-sum', title: '两数之和', difficulty: '简单',
      tags: ['数组'], description: '题目描述' },
    submittedCode: 'SUBMITTED CODE', language: 'java', verdict: 'WRONG_ANSWER', failCase: 'expected=[0,1]',
  } }
}

function configureReview(app) {
  const review = candidate()
  const draft = { problemId: '1', problemSlug: 'two-sum', title: '两数之和', difficulty: '简单',
    tags: '数组', errorSummary: '下标复用', failReason: '先插入再查询', aiAnalysis: '**先查后放**',
    failCase: 'expected=[0,1]', actionPlan: '检查重复元素' }
  const originalFetch = app.sandbox.fetch
  app.sandbox.fetch = async (url, options) => {
    if (url.endsWith('/analyze_submission')) {
      app.requests.push({ url: new URL(url), options, body: JSON.parse(options.body) })
      return { ok: true, json: async () => ({ code: '0000', data: draft }) }
    }
    return originalFetch(url, options)
  }
  let acknowledged = false
  app.sandbox.chrome.runtime.sendMessage = async (message) => {
    if (message.type === 'GODE_GET_PENDING_REVIEW') return { candidate: acknowledged ? null : review }
    if (message.type === 'GODE_ACK_REVIEW_CANDIDATE') { acknowledged = true; return { success: true } }
  }
  return { review, draft, isAcknowledged: () => acknowledged }
}

test('失败提交自动生成草稿，但用户确认前绝不保存', async () => {
  const app = setup(); configureReview(app)
  app.state.activeView = 'chat'
  await app.run('refreshPendingSubmissionReview()')
  await new Promise((resolve) => setImmediate(resolve))
  assert.equal(app.state.pendingReview.status, 'ready')
  assert.equal(app.requests.length, 1)
  assert.ok(app.requests[0].url.pathname.endsWith('/analyze_submission'))
  assert.equal(app.requests[0].body.submittedCode, 'SUBMITTED CODE')
  assert.equal(app.requests[0].body.userId, 'extension-test')
  assert.equal(app.state.activeView, 'chat')
  assert.ok(app.nodes.get('submission-review-banner').innerHTML.includes('确认保存'))
})

test('一键确认调用已有 save，清理待处理提示，并刷新错题本', async () => {
  const app = setup(); const review = configureReview(app)
  app.state.pendingReview = { candidate: review.review, status: 'ready', draft: review.draft }
  await app.run('confirmGeneratedReview()')
  assert.ok(app.requests[0].url.pathname.endsWith('/save'))
  assert.equal(app.requests[0].body.aiAnalysis, '**先查后放**')
  assert.equal(app.state.pendingReview, null)
  assert.ok(review.isAcknowledged())
  assert.equal(app.state.reviewFeedback.id, 71)
})

test('忽略草稿只清理提示，不写数据库', async () => {
  const app = setup(); const review = configureReview(app)
  app.state.pendingReview = { candidate: review.review, status: 'ready', draft: review.draft }
  await app.run('ignoreGeneratedReview()')
  assert.equal(app.requests.length, 0)
  assert.equal(app.state.pendingReview, null)
  assert.ok(review.isAcknowledged())
})

test('分析失败提供重试与手动填写，不假装生成成功', async () => {
  const app = setup(); const review = configureReview(app)
  app.state.pendingReview = { candidate: review.review, status: 'loading', draft: null }
  app.sandbox.fetch = async () => ({ ok: true, json: async () => ({ code: '0001', info: '模型返回格式异常' }) })
  await app.run('analyzePendingSubmission(state.pendingReview)')
  assert.equal(app.state.pendingReview.status, 'error')
  assert.ok(app.nodes.get('submission-review-banner').innerHTML.includes('手动填写'))
  assert.ok(!app.nodes.get('submission-review-banner').innerHTML.includes('data-confirm-generated-review'))
})

test('保存失败保留生成草稿，重试保存不重复分析', async () => {
  const app = setup(); const review = configureReview(app)
  app.state.pendingReview = { candidate: review.review, status: 'ready', draft: review.draft }
  app.sandbox.fetch = async () => ({ ok: false, status: 500 })
  await app.run('confirmGeneratedReview()')
  assert.equal(app.state.pendingReview.status, 'ready')
  assert.equal(app.state.pendingReview.draft.aiAnalysis, '**先查后放**')
  assert.equal(app.state.mistakeActionBusy, false)
  assert.ok(!review.isAcknowledged())
})

test('草稿可以先查看编辑，再通过原有表单保存', async () => {
  const app = setup(); const review = configureReview(app)
  app.state.pendingReview = { candidate: review.review, status: 'ready', draft: review.draft }
  await app.run('previewGeneratedReview()')
  const markup = app.nodes.get('mistake-detail').innerHTML
  assert.ok(markup.includes('确认提交复盘') && markup.includes('下标复用'))
  assert.ok(markup.includes('data-review-key="candidate-a"'))
  assert.equal(app.state.activeView, 'mistake-book')
})
