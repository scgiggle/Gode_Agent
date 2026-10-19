// MAIN world：仅观察提交及其判题结果。代码快照来自请求体，不读取后来的编辑器内容。
;(() => {
  if (window.__godeSubmissionObserver) return
  window.__godeSubmissionObserver = true
  const nativeFetch = window.fetch.bind(window)
  const pending = new Map()
  const replay = []
  let contentReady = false
  const codeVerdicts = {
    11: 'WRONG_ANSWER', 12: 'MEMORY_LIMIT_EXCEEDED', 13: 'OUTPUT_LIMIT_EXCEEDED',
    14: 'TIME_LIMIT_EXCEEDED', 15: 'RUNTIME_ERROR', 20: 'COMPILE_ERROR',
  }
  const nameVerdicts = {
    'wrong answer': 'WRONG_ANSWER', 'memory limit exceeded': 'MEMORY_LIMIT_EXCEEDED',
    'output limit exceeded': 'OUTPUT_LIMIT_EXCEEDED', 'time limit exceeded': 'TIME_LIMIT_EXCEEDED',
    'runtime error': 'RUNTIME_ERROR', 'compile error': 'COMPILE_ERROR',
  }

  function route(value) {
    try {
      const url = new URL(value, window.location.href)
      if (url.origin !== window.location.origin) return null
      const submit = url.pathname.match(/^\/problems\/([^/]+)\/submit\/?$/)
      const check = url.pathname.match(/^\/submissions\/detail\/([^/]+)\/check\/?$/)
      return submit ? { kind: 'submit', slug: decodeURIComponent(submit[1]) }
        : check ? { kind: 'check', id: check[1] } : null
    } catch { return null }
  }

  function bodySnapshot(body) {
    try {
      const value = typeof body === 'string' ? JSON.parse(body) : null
      if (!value || typeof value.typed_code !== 'string' || !value.typed_code.trim()) return null
      return { submittedCode: value.typed_code, language: String(value.lang || '') }
    } catch { return null }
  }

  function judgeDetails(result) {
    const fields = [
      ['status_msg', '状态'], ['input', '失败输入'], ['last_testcase', '失败用例'],
      ['expected_output', '预期输出'], ['code_output', '实际输出'], ['std_output', '标准输出'],
      ['compile_error', '编译错误'], ['full_compile_error', '完整编译错误'],
      ['runtime_error', '运行错误'], ['full_runtime_error', '完整运行错误'],
      ['total_correct', '通过用例数'], ['total_testcases', '总用例数'],
    ]
    const lines = fields.filter(([key]) => result[key] !== undefined && result[key] !== null && result[key] !== '')
      .map(([key, label]) => label + '：' + (typeof result[key] === 'object' ? JSON.stringify(result[key]) : String(result[key])))
    const details = lines.join('\n')
    return details.length > 11000 ? details.slice(0, 11000) + '\n[判题详情过长，已截断]' : details
  }

  function publish(payload) {
    if (!contentReady) {
      replay.push(payload)
      if (replay.length > 3) replay.shift()
      return
    }
    window.postMessage({ type: 'GODE_FAILED_SUBMISSION_CAPTURED', payload }, window.location.origin)
  }

  function finish(id, result) {
    const snapshot = pending.get(String(id))
    if (!snapshot || !result || result.state !== 'SUCCESS') return false
    pending.delete(String(id))
    if (Number(result.status_code) === 10 || String(result.status_msg).toLowerCase() === 'accepted') return true
    const verdict = codeVerdicts[result.status_code] || nameVerdicts[String(result.status_msg || '').toLowerCase()]
    // 平台内部错误、未知状态不能误判为用户代码错误。
    if (!verdict) return true
    publish({ ...snapshot, verdict, failCase: judgeDetails(result) })
    return true
  }

  async function poll(id) {
    // 某些页面未使用可观察的 check 请求，使用原生 fetch 只读补查最终结果。
    for (let attempt = 0; attempt < 60 && pending.has(id); attempt++) {
      await new Promise((resolve) => window.setTimeout(resolve, 2000))
      if (!pending.has(id)) return
      try {
        const response = await nativeFetch('/submissions/detail/' + encodeURIComponent(id) + '/check/', {
          credentials: 'include', signal: AbortSignal.timeout(10000),
        })
        if (response.status === 401 || response.status === 403) break
        if (response.ok && finish(id, await response.json())) return
      } catch { /* 网络暂时失败，后续继续只读查询。 */ }
    }
    pending.delete(id)
  }

  function observe(info, snapshot, response) {
    if (!info || !response || typeof response !== 'object') return
    if (info.kind === 'check') {
      finish(info.id, response)
      return
    }
    const id = String(response.submission_id || response.submissionId || '')
    if (!id || !snapshot) return
    // ID 仅用于关联提交与轮询；不传后端、不保存到数据库，也不做业务提交去重。
    pending.set(id, { ...snapshot, problemSlug: info.slug })
    if (pending.size > 8) pending.delete(pending.keys().next().value)
    void poll(id)
  }

  window.fetch = function(input, options) {
    const info = route(typeof input === 'string' || input instanceof URL ? String(input) : input?.url)
    const method = String(options?.method || input?.method || 'GET').toUpperCase()
    let snapshot = Promise.resolve(null)
    if (info?.kind === 'submit' && method === 'POST') {
      if (typeof options?.body === 'string') snapshot = Promise.resolve(bodySnapshot(options.body))
      else if (typeof input?.clone === 'function') snapshot = input.clone().text().then(bodySnapshot).catch(() => null)
    }
    // 立即发送原请求，保持原来的返回值和页面行为；观察逻辑只读响应副本。
    const promise = nativeFetch(input, options)
    if (info) {
      void promise.then(async (response) => {
        if (!response.ok) return
        const result = await response.clone().json()
        observe(info, await snapshot, result)
      }).catch(() => {})
    }
    return promise
  }

  const xhrInfo = new WeakMap()
  const nativeOpen = XMLHttpRequest.prototype.open
  const nativeSend = XMLHttpRequest.prototype.send
  XMLHttpRequest.prototype.open = function(method, url, ...rest) {
    xhrInfo.set(this, { info: route(url), method: String(method).toUpperCase() })
    return nativeOpen.call(this, method, url, ...rest)
  }
  XMLHttpRequest.prototype.send = function(body) {
    const request = xhrInfo.get(this)
    if (request?.info) {
      const snapshot = request.method === 'POST' ? bodySnapshot(body) : null
      this.addEventListener('load', () => {
        try {
          if (this.status < 200 || this.status >= 300) return
          const result = this.responseType === 'json' ? this.response : JSON.parse(this.responseText)
          observe(request.info, snapshot, result)
        } catch { /* 非 JSON 响应不参与采集。 */ }
      }, { once: true })
    }
    return nativeSend.call(this, body)
  }

  window.addEventListener('message', (event) => {
    if (event.source !== window || event.origin !== window.location.origin
        || event.data?.type !== 'GODE_SUBMISSION_WATCH_READY') return
    contentReady = true
    for (const payload of replay.splice(0)) publish(payload)
  })
})()
