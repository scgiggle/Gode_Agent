async function configureSidePanel() {
  if (!chrome.sidePanel?.setPanelBehavior) return
  await chrome.sidePanel.setPanelBehavior({ openPanelOnActionClick: true })
}

chrome.runtime.onInstalled.addListener(() => {
  void configureSidePanel()
})

chrome.runtime.onStartup.addListener(() => {
  void configureSidePanel()
})

chrome.tabs.onActivated.addListener(() => {
  void chrome.runtime.sendMessage({ type: 'GODE_ACTIVE_TAB_CHANGED' }).catch(() => {})
})

chrome.tabs.onUpdated.addListener((_tabId, changeInfo) => {
  if (changeInfo.url || changeInfo.status === 'complete') {
    void chrome.runtime.sendMessage({ type: 'GODE_ACTIVE_TAB_CHANGED' }).catch(() => {})
  }
})

void configureSidePanel()

// 仅暂存每个标签页最近一次待复盘提交。浏览器会话结束即清理，不保存 Cookie。
const REVIEW_STORAGE_KEY = 'godePendingSubmissionReviews'
let reviewWrites = Promise.resolve()
const failureVerdicts = new Set(['WRONG_ANSWER', 'TIME_LIMIT_EXCEEDED', 'MEMORY_LIMIT_EXCEEDED',
  'OUTPUT_LIMIT_EXCEEDED', 'RUNTIME_ERROR', 'COMPILE_ERROR'])

async function readReviewCandidates() {
  const stored = await chrome.storage.session.get(REVIEW_STORAGE_KEY)
  return stored[REVIEW_STORAGE_KEY] || {}
}

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message?.type === 'GODE_SUBMISSION_FAILED') {
    if (!sender.tab?.id || !/^https:\/\/leetcode\.cn\/problems\//.test(sender.url || '')) return false
    const payload = message.payload
    if (!payload?.problem?.slug || typeof payload.submittedCode !== 'string'
        || payload.submittedCode.length > 60000 || !failureVerdicts.has(payload.verdict)) return false
    reviewWrites = reviewWrites.catch(() => {}).then(async () => {
      const candidates = await readReviewCandidates()
      const candidate = {
        key: crypto.randomUUID(), tabId: sender.tab.id, windowId: sender.tab.windowId,
        capturedAt: Date.now(),
        payload: {
          problem: payload.problem, submittedCode: payload.submittedCode,
          language: payload.language, verdict: payload.verdict, failCase: payload.failCase,
        },
      }
      candidates[String(sender.tab.id)] = candidate
      const entries = Object.entries(candidates).sort((a, b) => b[1].capturedAt - a[1].capturedAt).slice(0, 8)
      await chrome.storage.session.set({ [REVIEW_STORAGE_KEY]: Object.fromEntries(entries) })
      void chrome.runtime.sendMessage({ type: 'GODE_REVIEW_CANDIDATE', tabId: candidate.tabId, key: candidate.key }).catch(() => {})
      sendResponse({ success: true })
    }).catch(() => sendResponse({ success: false }))
    return true
  }
  // 只有插件页面可读取或确认草稿，力扣内容脚本不能读取其他标签页的代码。
  if (sender.tab || sender.id !== chrome.runtime.id) return false
  if (message?.type === 'GODE_GET_PENDING_REVIEW') {
    void reviewWrites.catch(() => {}).then(readReviewCandidates)
      .then((candidates) => sendResponse({ candidate: candidates[String(message.tabId)] || null }))
      .catch(() => sendResponse({ candidate: null }))
    return true
  }
  if (message?.type === 'GODE_ACK_REVIEW_CANDIDATE') {
    reviewWrites = reviewWrites.catch(() => {}).then(async () => {
      const candidates = await readReviewCandidates()
      if (candidates[String(message.tabId)]?.key === message.key) {
        delete candidates[String(message.tabId)]
        await chrome.storage.session.set({ [REVIEW_STORAGE_KEY]: candidates })
      }
      sendResponse({ success: true })
    }).catch(() => sendResponse({ success: false }))
    return true
  }
  return false
})

chrome.tabs.onRemoved.addListener((tabId) => {
  reviewWrites = reviewWrites.catch(() => {}).then(async () => {
    const candidates = await readReviewCandidates()
    delete candidates[String(tabId)]
    await chrome.storage.session.set({ [REVIEW_STORAGE_KEY]: candidates })
  }).catch(() => {})
})
