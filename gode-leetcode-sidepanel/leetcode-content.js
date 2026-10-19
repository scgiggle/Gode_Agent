const QUESTION_QUERY = `
  query questionData($titleSlug: String!) {
    question(titleSlug: $titleSlug) {
      questionId
      questionFrontendId
      title
      translatedTitle
      titleSlug
      content
      translatedContent
      difficulty
      topicTags {
        name
        translatedName
      }
    }
  }
`

let cachedProblem = null
let observedSlug = ''

function currentTitleSlug() {
  return window.location.pathname.match(/^\/problems\/([^/]+)/)?.[1] ?? ''
}

function normalizeText(value) {
  return String(value ?? '')
    .replace(/\u00a0/g, ' ')
    .replace(/[ \t]+/g, ' ')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}

function htmlToText(html) {
  const documentValue = new DOMParser().parseFromString(html || '', 'text/html')
  return normalizeText(documentValue.body.textContent)
}

function translateDifficulty(difficulty) {
  return { Easy: '简单', Medium: '中等', Hard: '困难' }[difficulty] ?? difficulty ?? ''
}

async function fetchCurrentProblem(titleSlug = currentTitleSlug()) {
  if (!titleSlug) throw new Error('当前标签页不是力扣题目页面')
  if (cachedProblem?.slug === titleSlug) return cachedProblem

  const response = await fetch('https://leetcode.cn/graphql/', {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      operationName: 'questionData',
      variables: { titleSlug },
      query: QUESTION_QUERY,
    }),
  })

  if (!response.ok) throw new Error(`读取力扣题目失败（HTTP ${response.status}）`)
  const payload = await response.json()
  if (payload.errors?.length) throw new Error(payload.errors[0].message || '读取题目失败')
  const question = payload.data?.question
  if (!question) throw new Error('没有读取到当前题目，请确认力扣登录状态')

  const problem = {
    questionId: question.questionId,
    frontendId: question.questionFrontendId,
    title: question.translatedTitle || question.title,
    slug: question.titleSlug,
    difficulty: translateDifficulty(question.difficulty),
    tags: (question.topicTags ?? [])
      .map((tag) => tag.translatedName || tag.name)
      .filter(Boolean),
    description: htmlToText(question.translatedContent || question.content),
    sourceUrl: window.location.href,
  }
  if (currentTitleSlug() === titleSlug) cachedProblem = problem
  return problem
}

function injectPageBridge() {
  if (document.documentElement.dataset.godePageBridge === 'ready') return
  document.documentElement.dataset.godePageBridge = 'ready'
  const script = document.createElement('script')
  script.src = chrome.runtime.getURL('page-bridge.js')
  script.onload = () => script.remove()
  ;(document.head || document.documentElement).appendChild(script)
}

function readEditorCode(timeoutMs = 700) {
  injectPageBridge()
  return new Promise((resolve) => {
    const requestId = crypto.randomUUID()
    const timer = window.setTimeout(() => {
      window.removeEventListener('message', onResult)
      resolve('')
    }, timeoutMs)

    function onResult(event) {
      if (event.source !== window
          || event.data?.type !== 'GODE_LEETCODE_EDITOR_RESULT'
          || event.data.requestId !== requestId) return
      window.clearTimeout(timer)
      window.removeEventListener('message', onResult)
      resolve(typeof event.data.code === 'string' ? event.data.code : '')
    }

    window.addEventListener('message', onResult)
    window.postMessage({ type: 'GODE_READ_LEETCODE_EDITOR', requestId }, '*')
  })
}

function writeEditorCode(code, timeoutMs = 1200) {
  injectPageBridge()
  return new Promise((resolve, reject) => {
    const requestId = crypto.randomUUID()
    const timer = window.setTimeout(() => {
      window.removeEventListener('message', onResult)
      reject(new Error('写入力扣编辑器超时，请刷新力扣页面后重试'))
    }, timeoutMs)

    function onResult(event) {
      if (event.source !== window
          || event.data?.type !== 'GODE_LEETCODE_EDITOR_WRITE_RESULT'
          || event.data.requestId !== requestId) return
      window.clearTimeout(timer)
      window.removeEventListener('message', onResult)
      if (event.data.success) {
        resolve()
      } else {
        reject(new Error(event.data.message || '写入力扣编辑器失败'))
      }
    }

    window.addEventListener('message', onResult)
    window.postMessage({ type: 'GODE_WRITE_LEETCODE_EDITOR', requestId, code }, '*')
  })
}

async function getCurrentContext(includeCode) {
  const problem = await fetchCurrentProblem()
  const code = includeCode ? await readEditorCode() : ''
  return { problem, code }
}

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (message?.type === 'GODE_GET_LEETCODE_CONTEXT') {
    void getCurrentContext(Boolean(message.includeCode))
      .then((context) => sendResponse({ success: true, context }))
      .catch((error) => sendResponse({ success: false, message: error.message }))
    return true
  }

  if (message?.type === 'GODE_SET_LEETCODE_CODE') {
    void writeEditorCode(message.code)
      .then(() => sendResponse({ success: true }))
      .catch((error) => sendResponse({ success: false, message: error.message }))
    return true
  }

  return false
})

injectPageBridge()
observedSlug = currentTitleSlug()

// 仅转发提交时的快照，不重新读取编辑器，且不会转发登录 Cookie。
window.addEventListener('message', (event) => {
  if (event.source !== window || event.origin !== window.location.origin
      || event.data?.type !== 'GODE_FAILED_SUBMISSION_CAPTURED') return
  const submission = event.data.payload
  if (!submission || typeof submission.problemSlug !== 'string'
      || typeof submission.submittedCode !== 'string' || !submission.submittedCode.trim()
      || typeof submission.language !== 'string') return
  void (async () => {
    try {
      const problem = await fetchCurrentProblem(submission.problemSlug)
      await chrome.runtime.sendMessage({ type: 'GODE_SUBMISSION_FAILED', payload: {
        problem, submittedCode: submission.submittedCode, language: submission.language,
        verdict: submission.verdict, failCase: submission.failCase,
      } })
    } catch (error) {
      console.warn('[Gode] 读取失败提交上下文失败，请使用手动添加入口', error.message)
    }
  })()
})
window.postMessage({ type: 'GODE_SUBMISSION_WATCH_READY' }, window.location.origin)

window.setInterval(() => {
  const nextSlug = currentTitleSlug()
  if (!nextSlug || nextSlug === observedSlug) return
  observedSlug = nextSlug
  cachedProblem = null
  void chrome.runtime.sendMessage({ type: 'GODE_LEETCODE_ROUTE_CHANGED' }).catch(() => {})
}, 700)
