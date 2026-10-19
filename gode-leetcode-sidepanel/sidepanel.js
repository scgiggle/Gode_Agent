const DEFAULT_SETTINGS = {
  apiBaseUrl: 'http://localhost:8091/api/v1',
  agentId: 'demo-agent',
}

const DEFAULT_SUGGESTIONS = [
  { label: '帮我分析解题思路', prompt: '帮我分析解题思路' },
  { label: '检查我的代码问题', prompt: '检查我的代码问题' },
  { label: '提示一个关键点', prompt: '提示一个关键点' },
]
const SUGGESTION_TIMEOUT_MS = 25000

const AGENT_LABELS = {
  problemAnalyzer: '题目分析专家',
  solutionCoach: '解题辅导专家',
  finalResponder: '最终回答专家',
}

const state = {
  settings: { ...DEFAULT_SETTINGS },
  userId: '',
  problem: null,
  sessionId: null,
  sessionTitle: '新对话',
  sessionBusy: false,
  sessionOperationController: null,
  sessionRecords: [],
  sessionListVersion: 0,
  sessionListController: null,
  sessionListHasMore: false,
  sessionListOffset: 0,
  sessionListLoading: false,
  savedMessageCount: null,
  historyHasMore: false,
  historyBeforeId: null,
  historyServerMessages: [],
  messages: [],
  busy: false,
  abortController: null,
  conversationVersion: 0,
  problemLoadVersion: 0,
  problemPending: false,
  suggestions: DEFAULT_SUGGESTIONS,
  suggestionVersion: 0,
  suggestionController: null,
  activeView: 'chat',
  mistakeFilter: 'due',
  mistakeQuery: '',
  mistakePage: 1,
  mistakeRecords: [],
  mistakeTotal: 0,
  mistakeTotalPages: 1,
  mistakeLoading: false,
  mistakeError: '',
  mistakeDetail: null,
  mistakeReviews: [],
  selectedMistakeReview: 0,
  mistakeHistoryExpanded: false,
  mistakeActionBusy: false,
  pendingReview: null,
  reviewAbortController: null,
  reviewFeedback: null,
  reviewEditingKey: '',
  notesConfigBusy: false,
  notesConfigVersion: 0,
  notesConfigController: null,
}

const elements = Object.fromEntries([
  'connection-status', 'refresh-button', 'settings-button', 'problem-card',
  'problem-number', 'problem-title', 'problem-difficulty', 'problem-tags',
  'empty-state', 'settings-panel', 'close-settings', 'api-base-url', 'agent-id',
  'save-settings', 'active-agent', 'new-chat-button', 'message-list',
  'quick-prompts', 'include-code', 'message-input', 'send-button', 'composer-hint',
  'chat-tab', 'chat-area', 'mistake-book-tab', 'mistake-book-count', 'mistake-book',
  'mistake-search-input', 'mistake-filters', 'mistake-book-list', 'mistake-pagination',
  'mistake-prev-page', 'mistake-page-info', 'mistake-next-page', 'mistake-detail',
  'mistake-notice', 'mistake-add-current', 'mistake-refresh',
  'submission-review-banner',
  'session-title', 'history-chat-button', 'session-drawer', 'close-session-drawer',
  'session-list', 'session-list-more', 'session-refresh', 'session-notice', 'load-older-messages',
  'leetcode-session-cookie', 'save-notes-cookie', 'clear-notes-cookie', 'notes-cookie-status',
].map((id) => [id, document.getElementById(id)]))

const appShell = document.querySelector('.app-shell')
const MISTAKE_PAGE_SIZE = 10
const MISTAKE_HISTORY_PREVIEW_SIZE = 5

function escapeHtml(value) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;')
}

function safeUrl(value) {
  try {
    const url = new URL(value)
    return ['http:', 'https:'].includes(url.protocol) ? url.href : ''
  } catch {
    return ''
  }
}

function renderInlineMarkdown(value) {
  const tokens = []
  const saveToken = (html) => {
    const key = `GODEMDTOKEN${tokens.length}X`
    tokens.push(html)
    return key
  }

  let source = String(value ?? '')
  source = source.replace(/`([^`\n]+)`/g, (_match, code) => {
    const containsLatex = /\\(?:leq?|geq?|neq|times|cdot|frac|sqrt|log|sum|min|max|infty|Theta|Omega)(?![A-Za-z])/.test(code)
    return saveToken(containsLatex
      ? `<span class="math-inline">${renderMath(code, false)}</span>`
      : `<code>${escapeHtml(code)}</code>`)
  })
  source = source.replace(/\\\(([^\n]+?)\\\)/g, (_match, math) =>
    saveToken(`<span class="math-inline">${renderMath(math, false)}</span>`))
  source = source.replace(/\$([^$\n]+?)\$/g, (_match, math) =>
    saveToken(`<span class="math-inline">${renderMath(math, false)}</span>`))
  source = source.replace(/\[([^\]]+)]\(([^\s)]+)(?:\s+["']([^"']*)["'])?\)/g,
    (_match, label, href, title) => {
      const url = safeUrl(href)
      if (!url) return `${label} (${href})`
      const titleAttribute = title ? ` title="${escapeHtml(title)}"` : ''
      return saveToken(`<a href="${escapeHtml(url)}" target="_blank" rel="noreferrer"${titleAttribute}>${escapeHtml(label)}</a>`)
    })

  let html = escapeHtml(source)
  html = html
    .replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>')
    .replace(/__([^_\n]+)__/g, '<strong>$1</strong>')
    .replace(/~~([^~\n]+)~~/g, '<del>$1</del>')
    .replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>')
    .replace(/\n/g, '<br>')

  return html.replace(/GODEMDTOKEN(\d+)X/g, (_match, index) => tokens[Number(index)] ?? '')
}

function renderMath(value, displayMode) {
  const latex = String(value ?? '').trim()
  if (!latex) return '<span class="math-empty">公式内容为空</span>'

  try {
    if (globalThis.katex?.renderToString) {
      return globalThis.katex.renderToString(latex, {
        displayMode,
        throwOnError: false,
        strict: 'ignore',
        trust: false,
        output: 'htmlAndMathml',
      })
    }
  } catch {
    // 外部公式引擎不可用时，继续使用下方的内置安全渲染器。
  }

  return renderBasicLatex(latex)
}

function renderBasicLatex(value) {
  const tokens = []
  const saveToken = (html) => {
    const key = `GODEMATHTOKEN${tokens.length}X`
    tokens.push(html)
    return key
  }

  let source = String(value ?? '')
    .replace(/\\begin\{(?:aligned|align|array|cases)\}/g, '')
    .replace(/\\end\{(?:aligned|align|array|cases)\}/g, '')
    .replace(/\\left|\\right/g, '')

  for (let pass = 0; pass < 4; pass += 1) {
    const previous = source
    source = source.replace(/\\frac\s*\{([^{}]*)\}\s*\{([^{}]*)\}/g,
      (_match, top, bottom) => saveToken(
        `<span class="math-frac"><span>${renderBasicLatex(top)}</span><span>${renderBasicLatex(bottom)}</span></span>`,
      ))
    source = source.replace(/\\sqrt\s*\{([^{}]*)\}/g,
      (_match, body) => saveToken(`<span class="math-root">√<span>${renderBasicLatex(body)}</span></span>`))
    source = source.replace(/\\(?:text|mathrm|operatorname)\s*\{([^{}]*)\}/g,
      (_match, body) => saveToken(`<span class="math-text">${escapeHtml(body)}</span>`))
    source = source.replace(/\^\{([^{}]*)\}/g,
      (_match, body) => saveToken(`<sup>${renderBasicLatex(body)}</sup>`))
    source = source.replace(/_\{([^{}]*)\}/g,
      (_match, body) => saveToken(`<sub>${renderBasicLatex(body)}</sub>`))
    source = source.replace(/\^([A-Za-z0-9+-])/g,
      (_match, body) => saveToken(`<sup>${escapeHtml(body)}</sup>`))
    source = source.replace(/_([A-Za-z0-9+-])/g,
      (_match, body) => saveToken(`<sub>${escapeHtml(body)}</sub>`))
    if (source === previous) break
  }

  const symbols = {
    times: '×', cdot: '·', le: '≤', leq: '≤', ge: '≥', geq: '≥', neq: '≠',
    approx: '≈', infty: '∞', sum: '∑', prod: '∏', min: 'min', max: 'max', log: 'log',
    ln: 'ln', theta: 'θ', Theta: 'Θ', omega: 'ω', Omega: 'Ω', alpha: 'α', beta: 'β',
    gamma: 'γ', delta: 'δ', lambda: 'λ', mu: 'μ', pi: 'π', sigma: 'σ', phi: 'φ',
    to: '→', rightarrow: '→', leftarrow: '←', in: '∈', notin: '∉', emptyset: '∅',
    quad: '  ', qquad: '    ', space: ' ',
  }

  let html = escapeHtml(source)
  html = html
    .replace(/\\\\/g, '<br>')
    .replace(/&amp;/g, '')
    .replace(/\\([A-Za-z]+)(?![A-Za-z])/g, (_match, command) => symbols[command] ?? command)
    .replace(/\\[,;! ]/g, ' ')
    .replace(/[{}]/g, '')

  let previous
  do {
    previous = html
    html = html.replace(/GODEMATHTOKEN(\d+)X/g, (_match, index) => tokens[Number(index)] ?? '')
  } while (html !== previous && /GODEMATHTOKEN\d+X/.test(html))

  return html
}

function splitTableRow(line) {
  return line.trim()
    .replace(/^\|/, '')
    .replace(/\|$/, '')
    .split('|')
    .map((cell) => cell.trim())
}

const PLAIN_TEXT_FENCE_LANGUAGES = new Set(['text', 'plaintext', 'txt'])
const SINGLE_LINE_FENCE_LANGUAGES = [
  'javascript', 'typescript', 'plaintext', 'powershell', 'markdown',
  'kotlin', 'python', 'csharp', 'shell', 'java', 'rust', 'json',
  'yaml', 'html', 'bash', 'text', 'cpp', 'sql', 'xml', 'css',
  'jsx', 'tsx', 'yml', 'txt', 'ps1', 'sh', 'py', 'js', 'ts',
  'cs', 'go', 'c++', 'c#', 'c',
].sort((a, b) => b.length - a.length)

function renderFencedCode(language, code) {
  const normalizedLanguage = String(language ?? '').toLowerCase()
  const showLanguage = normalizedLanguage && !PLAIN_TEXT_FENCE_LANGUAGES.has(normalizedLanguage)
  const languageLabel = showLanguage ? `<span>${escapeHtml(normalizedLanguage)}</span>` : ''
  return `<pre data-language="${escapeHtml(normalizedLanguage)}">${languageLabel}<code>${escapeHtml(code)}</code></pre>`
}

function parseSingleLineFence(body) {
  const source = String(body ?? '')
  const lowerSource = source.toLowerCase()
  const language = SINGLE_LINE_FENCE_LANGUAGES
    .find((item) => lowerSource.startsWith(item)
      // 单字母语言不得把 console、class 等无标签代码的首字母吞掉。
      && (item.length > 1 || source.length === item.length || !/[a-z\d_]/i.test(source[item.length]))) || ''
  const code = language ? source.slice(language.length).trimStart() : source
  return { language, code }
}

function parseCodeFenceOpening(body) {
  const source = String(body ?? '').trimStart()
  const parsed = parseSingleLineFence(source)
  if (parsed.language) return parsed
  // 标准独立语言行也允许列表中未列出的语言，但有语句符号时保留为代码。
  if (/^[\w#+.-]+\s*$/.test(source)) return { language: source.trim(), code: '' }
  return { language: '', code: source }
}

function markdownToHtml(markdown) {
  const lines = String(markdown ?? '').replace(/\r\n?/g, '\n').split('\n')
  const output = []
  const paragraph = []

  const flushParagraph = () => {
    if (!paragraph.length) return
    output.push(`<p>${renderInlineMarkdown(paragraph.join('\n'))}</p>`)
    paragraph.length = 0
  }

  for (let index = 0; index < lines.length;) {
    const line = lines[index]
    const trimmed = line.trim()

    if (!trimmed) {
      flushParagraph()
      index += 1
      continue
    }

    // 同时兼容标准围栏、语言与首行代码粘连，以及闭合围栏紧跟最后一行代码。
    // 从原始文本逐次解析，流式尚未闭合的代码块也能展示，不改写保存的聊天内容。
    const fence = trimmed.match(/^(`{3,}|~{3,})(.*)$/)
    if (fence) {
      flushParagraph()
      const marker = fence[1]
      const closePattern = new RegExp(`${marker[0]}{${marker.length},}\\s*$`)
      const sameLineClose = fence[2].match(closePattern)
      if (sameLineClose) {
        const { language, code } = parseSingleLineFence(fence[2].slice(0, sameLineClose.index))
        output.push(renderFencedCode(language, code))
        index += 1
        continue
      }
      const { language, code } = parseCodeFenceOpening(fence[2])
      const codeLines = []
      if (code) codeLines.push(code)
      index += 1
      while (index < lines.length) {
        const closing = lines[index].match(closePattern)
        if (closing) {
          const finalCode = lines[index].slice(0, closing.index)
          if (finalCode.trim()) codeLines.push(finalCode)
          index += 1
          break
        }
        codeLines.push(lines[index])
        index += 1
      }
      output.push(renderFencedCode(language, codeLines.join('\n')))
      continue
    }

    if (trimmed === '\\[' || trimmed === '$$') {
      flushParagraph()
      const closing = trimmed === '\\[' ? '\\]' : '$$'
      const mathLines = []
      index += 1
      while (index < lines.length && lines[index].trim() !== closing) {
        mathLines.push(lines[index])
        index += 1
      }
      if (index < lines.length) index += 1
      output.push(`<div class="math-block">${renderMath(mathLines.join(' '), true)}</div>`)
      continue
    }

    const sameLineDollarMath = trimmed.match(/^\$\$([\s\S]+)\$\$$/)
    if (sameLineDollarMath) {
      flushParagraph()
      output.push(`<div class="math-block">${renderMath(sameLineDollarMath[1], true)}</div>`)
      index += 1
      continue
    }

    const nextLine = lines[index + 1] ?? ''
    const tableDivider = splitTableRow(nextLine)
    if (line.includes('|')
        && tableDivider.length > 0
        && tableDivider.every((cell) => /^:?-{3,}:?$/.test(cell))) {
      flushParagraph()
      const headings = splitTableRow(line)
      const alignments = tableDivider.map((cell) => {
        if (cell.startsWith(':') && cell.endsWith(':')) return 'center'
        if (cell.endsWith(':')) return 'right'
        return 'left'
      })
      index += 2
      const rows = []
      while (index < lines.length && lines[index].includes('|') && lines[index].trim()) {
        rows.push(splitTableRow(lines[index]))
        index += 1
      }
      const headerHtml = headings.map((cell, cellIndex) =>
        `<th style="text-align:${alignments[cellIndex] || 'left'}">${renderInlineMarkdown(cell)}</th>`).join('')
      const bodyHtml = rows.map((row) => `<tr>${headings.map((_heading, cellIndex) =>
        `<td style="text-align:${alignments[cellIndex] || 'left'}">${renderInlineMarkdown(row[cellIndex] ?? '')}</td>`).join('')}</tr>`).join('')
      output.push(`<div class="table-scroll"><table><thead><tr>${headerHtml}</tr></thead><tbody>${bodyHtml}</tbody></table></div>`)
      continue
    }

    const singleLineMath = trimmed.match(/^\\\[([\s\S]+)\\]$/)
    if (singleLineMath) {
      flushParagraph()
      output.push(`<div class="math-block">${renderMath(singleLineMath[1], true)}</div>`)
      index += 1
      continue
    }

    const heading = line.match(/^\s*(#{1,6})\s+(.+)$/)
    if (heading) {
      flushParagraph()
      const level = heading[1].length
      output.push(`<h${level}>${renderInlineMarkdown(heading[2])}</h${level}>`)
      index += 1
      continue
    }

    if (/^\s*(?:-{3,}|\*{3,}|_{3,})\s*$/.test(line)) {
      flushParagraph()
      output.push('<hr>')
      index += 1
      continue
    }

    if (/^\s*>\s?/.test(line)) {
      flushParagraph()
      const quoteLines = []
      while (index < lines.length && /^\s*>\s?/.test(lines[index])) {
        quoteLines.push(lines[index].replace(/^\s*>\s?/, ''))
        index += 1
      }
      output.push(`<blockquote>${renderInlineMarkdown(quoteLines.join('\n'))}</blockquote>`)
      continue
    }

    const unordered = line.match(/^\s*[-*+]\s+(.+)$/)
    if (unordered) {
      flushParagraph()
      const items = []
      while (index < lines.length) {
        const item = lines[index].match(/^\s*[-*+]\s+(.+)$/)
        if (!item) break
        items.push(`<li>${renderInlineMarkdown(item[1])}</li>`)
        index += 1
      }
      output.push(`<ul>${items.join('')}</ul>`)
      continue
    }

    const ordered = line.match(/^\s*\d+[.)]\s+(.+)$/)
    if (ordered) {
      flushParagraph()
      const items = []
      while (index < lines.length) {
        const item = lines[index].match(/^\s*\d+[.)]\s+(.+)$/)
        if (!item) break
        items.push(`<li>${renderInlineMarkdown(item[1])}</li>`)
        index += 1
      }
      output.push(`<ol>${items.join('')}</ol>`)
      continue
    }

    paragraph.push(line)
    index += 1
  }

  flushParagraph()
  return output.join('')
}

function renderMarkdownInto(element, markdown, allowApply = false) {
  element.innerHTML = markdownToHtml(markdown)
  if (allowApply) addApplyButtons(element)
}

function addApplyButtons(container) {
  for (const codeElement of container.querySelectorAll('pre > code')) {
    const code = codeElement.textContent ?? ''
    if (!code.trim()) continue
    const language = codeElement.parentElement?.dataset.language?.toLowerCase() || ''
    if (PLAIN_TEXT_FENCE_LANGUAGES.has(language)) continue

    const button = document.createElement('button')
    button.type = 'button'
    button.className = 'apply-code-button'
    button.textContent = '应用到力扣'
    button.title = '替换当前力扣编辑器中的代码'
    button.disabled = state.busy
    button.addEventListener('click', () => void applyCodeToLeetCode(code, button))
    codeElement.parentElement.appendChild(button)
  }
}

async function applyCodeToLeetCode(code, button) {
  const confirmed = window.confirm('确定用这段代码替换当前力扣编辑器中的全部代码吗？\n\n替换后可以在力扣编辑器中按 Ctrl+Z 撤销。')
  if (!confirmed) return

  const originalText = button.textContent
  button.disabled = true
  button.textContent = '正在应用…'

  try {
    const tab = await activeLeetCodeTab()
    const response = await chrome.tabs.sendMessage(tab.id, {
      type: 'GODE_SET_LEETCODE_CODE',
      code,
    })
    if (!response?.success) throw new Error(response?.message || '应用代码失败')
    button.textContent = '已应用 ✓'
    button.classList.add('success')
    elements['composer-hint'].textContent = '代码已应用到当前力扣编辑器，可按 Ctrl+Z 撤销'
    window.setTimeout(() => {
      button.textContent = originalText
      button.classList.remove('success')
      button.disabled = state.busy
    }, 2200)
  } catch (error) {
    button.textContent = '应用失败'
    elements['composer-hint'].textContent = error instanceof Error ? error.message : '应用代码失败'
    window.setTimeout(() => {
      button.textContent = originalText
      button.disabled = state.busy
    }, 2200)
  }
}

function storageGet(keys) {
  return chrome.storage.local.get(keys)
}

function storageSet(value) {
  return chrome.storage.local.set(value)
}

function chatIdentity() {
  return state.settings.apiBaseUrl + ':' + state.settings.agentId + ':' + state.userId
}

function conversationKey(sessionId) {
  return 'gode-sidepanel-session:' + chatIdentity() + ':' + sessionId
}

function activeConversationKey() {
  return 'gode-sidepanel-active:' + chatIdentity()
}

function agentLabel(name) {
  return name ? (AGENT_LABELS[name] ?? name) : 'Gode AI'
}

function setConnectionStatus(text, ok = false) {
  elements['connection-status'].textContent = text
  elements['connection-status'].style.color = ok ? '#71d6a6' : ''
}

function addMessage(role, content, meta, extraClass = '') {
  const message = {
    id: crypto.randomUUID(),
    role,
    content,
    meta,
    extraClass,
    problemSlug: state.problem?.slug ?? null,
  }
  state.messages.push(message)
  renderMessage(message)
  void saveConversation()
  return message
}

function renderMessage(message) {
  const article = document.createElement('article')
  article.className = `message ${message.role} ${message.extraClass || ''}`.trim()
  article.dataset.messageId = message.id

  if (message.meta) {
    const meta = document.createElement('div')
    meta.className = 'message-meta'
    meta.textContent = message.meta
    article.appendChild(meta)
  }

  const content = document.createElement('div')
  content.className = 'message-content'
  renderMarkdownInto(content, message.content, message.role === 'assistant')
  article.appendChild(content)
  elements['message-list'].appendChild(article)
  scrollMessages()
}

function updateMessage(message, chunk) {
  setMessageContent(message, message.content + chunk)
}

function setMessageContent(message, content) {
  message.content = content
  const node = elements['message-list'].querySelector(`[data-message-id="${message.id}"] .message-content`)
  if (node) renderMarkdownInto(node, message.content, message.role === 'assistant')
  scrollMessages()
}

function scrollMessages() {
  elements['message-list'].scrollTop = elements['message-list'].scrollHeight
}

function showThinking() {
  const article = document.createElement('article')
  article.id = 'thinking-message'
  article.className = 'message assistant'
  const meta = document.createElement('div')
  meta.className = 'message-meta'
  meta.textContent = '正在思考'
  const content = document.createElement('div')
  content.className = 'message-content thinking'
  content.innerHTML = '<i></i><i></i><i></i>'
  article.append(meta, content)
  elements['message-list'].appendChild(article)
  scrollMessages()
}

function hideThinking() {
  document.getElementById('thinking-message')?.remove()
}

function renderAllMessages() {
  elements['message-list'].replaceChildren()
  for (const message of state.messages) renderMessage(message)
}

let assistantMessageQueue = Promise.resolve()

// 使用普通助手消息保存接口；串行提交，保持消息先后顺序。
function appendAssistantMessage(content) {
  if (!state.sessionId) return Promise.resolve(false)
  const identity = chatIdentity()
  const baseUrl = state.settings.apiBaseUrl
  const body = { ...sessionIdentityParams(), sessionId: state.sessionId, content }
  const operation = assistantMessageQueue.then(async () => {
    try {
      const saved = await sessionRequest('/chat/message', { method: 'POST', body, baseUrl })
      if (!saved?.id || saved.role !== 'assistant' || typeof saved.content !== 'string') throw new Error('后端返回的消息不完整')
      if (identity !== chatIdentity() || body.sessionId !== state.sessionId) return false
      const message = historyMessages([saved])[0]
      state.messages.push(message)
      state.historyServerMessages.push(message)
      renderMessage(message)
      if (Number.isInteger(state.savedMessageCount)) state.savedMessageCount++
      state.historyBeforeId ??= message.databaseId
      await saveConversation()
      return true
    } catch (error) {
      if (identity === chatIdentity() && body.sessionId === state.sessionId) {
        sessionNotice('消息保存失败：' + error.message, true)
      }
      return false
    }
  })
  assistantMessageQueue = operation
  return operation
}

function recordProblemConnection() {
  if (!state.problem) return Promise.resolve(false)
  return appendAssistantMessage(`已连接《${state.problem.title}》。你可以直接询问解题思路，也可以让我检查力扣编辑器中的当前代码。`)
}

async function saveConversation() {
  if (!state.sessionId) return
  const key = conversationKey(state.sessionId)
  const activeKey = activeConversationKey()
  // 在 await 前固定 ID 和消息快照，避免切换后把旧请求保存到新会话。
  const snapshot = {
    sessionId: state.sessionId, title: state.sessionTitle,
    savedMessageCount: state.savedMessageCount,
    messages: state.messages.slice(-80).map((message) => ({ ...message })),
  }
  await storageSet({ [key]: snapshot, [activeKey]: snapshot.sessionId })
}

function sessionNotice(message = '', error = false) {
  elements['session-notice'].textContent = message
  elements['session-notice'].classList.toggle('hidden', !message)
  elements['session-notice'].classList.toggle('error', error)
}

function renderSessionHeading() {
  elements['session-title'].textContent = state.sessionTitle || '新对话'
  elements['load-older-messages'].classList.toggle('hidden', !state.historyHasMore || !state.sessionId)
  elements['load-older-messages'].disabled = state.sessionBusy || state.busy
}

async function sessionRequest(path, { method = 'GET', params, body, signal, baseUrl = state.settings.apiBaseUrl } = {}) {
  const controller = new AbortController()
  const abort = () => controller.abort()
  if (signal?.aborted) abort()
  signal?.addEventListener('abort', abort, { once: true })
  const timer = window.setTimeout(abort, 15000)
  try {
    const url = new URL(baseUrl.replace(/\/+$/, '') + path)
    if (params) for (const [key, value] of Object.entries(params)) {
      if (value != null) url.searchParams.set(key, String(value))
    }
    const response = await withAbort(fetch(url.href, {
      method, headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
      body: body == null ? undefined : JSON.stringify(body), signal: controller.signal,
    }), controller.signal)
    const result = await withAbort(response.json(), controller.signal)
    if (!response.ok || result.code !== '0000') {
      const error = new Error(result.info || '会话操作失败（HTTP ' + response.status + '）')
      error.code = result.code
      throw error
    }
    return result.data
  } finally {
    clearTimeout(timer)
    signal?.removeEventListener('abort', abort)
  }
}

function sessionIdentityParams() {
  return { agentId: state.settings.agentId, userId: state.userId }
}

function historyMessages(messages) {
  return (Array.isArray(messages) ? messages : [])
    .filter((message) => ['user', 'assistant'].includes(message.role))
    .map((message) => {
      let content = String(message.content ?? '')
      if (message.role === 'user') {
        const marker = content.lastIndexOf('用户问题：')
        if (marker >= 0) content = content.slice(marker + '用户问题：'.length).trim()
      }
      return { id: 'history-' + message.id, role: message.role, content,
        meta: message.role === 'user' ? '你' : 'Gode AI', databaseId: message.id }
    })
}

function resetHistoryPage() {
  state.historyHasMore = false
  state.historyBeforeId = null
  state.historyServerMessages = []
  state.savedMessageCount = null
  renderSessionHeading()
}

async function restoreConversation(sessionId, version = state.conversationVersion) {
  const signal = state.sessionOperationController?.signal
  const detail = await sessionRequest('/chat/session_detail', {
    params: { ...sessionIdentityParams(), sessionId, limit: 50 },
    signal,
  })
  if (version !== state.conversationVersion) return false
  assertCurrentChat(signal, version)
  if (detail?.session?.sessionId !== sessionId || !Array.isArray(detail.messages)) {
    throw new Error('后端返回的会话详情不完整')
  }
  const key = conversationKey(sessionId)
  const stored = signal ? await withAbort(storageGet(key), signal) : await storageGet(key)
  if (version !== state.conversationVersion) return false
  assertCurrentChat(signal, version)
  const cached = stored[key]
  state.sessionId = sessionId
  state.sessionTitle = detail.session.title || '新对话'
  state.savedMessageCount = detail.session.messageCount
  state.historyServerMessages = historyMessages(detail.messages)
  // 本地缓存只在消息计数与后端一致时使用，以保留流式 Agent 分组。
  state.messages = cached?.savedMessageCount === detail.session.messageCount
      && Array.isArray(cached.messages) && cached.messages.length
    ? cached.messages : [...state.historyServerMessages]
  state.historyHasMore = Boolean(detail.hasMore)
  state.historyBeforeId = detail.beforeMessageId ?? null
  renderAllMessages()
  renderSessionHeading()
  await storageSet({ [activeConversationKey()]: sessionId })
  return true
}

async function restoreActiveConversation() {
  if (state.sessionBusy || state.sessionId) return
  resetChatContext()
  const key = activeConversationKey()
  const version = state.conversationVersion
  const controller = new AbortController()
  state.sessionOperationController = controller
  state.sessionBusy = true
  setBusy(false)
  sessionNotice('正在准备对话…')
  const timer = window.setTimeout(() => controller.abort(), 15000)
  let ready = false
  try {
    const stored = await withAbort(storageGet(key), controller.signal)
    assertCurrentChat(controller.signal, version)
    const id = stored[key]
    if (id) {
      try {
        ready = await restoreConversation(id, version)
      } catch (error) {
        // 旧指针指向已删除/不再属于当前用户的会话时，再查询列表。
        // 网络或数据库错误不能当成“没有历史”而创建新会话。
        if (error.code !== '0002') throw error
      }
    }
    assertCurrentChat(controller.signal, version)
    if (!ready) {
      const page = await sessionRequest('/chat/sessions', {
        params: { ...sessionIdentityParams(), offset: 0, limit: 20 }, signal: controller.signal,
      })
      assertCurrentChat(controller.signal, version)
      if (!Array.isArray(page?.sessions)) throw new Error('后端返回的会话列表不完整')
      state.sessionRecords = page.sessions
      state.sessionListHasMore = Boolean(page.hasMore)
      state.sessionListOffset = page.nextOffset ?? page.sessions.length
      if (page.sessions.length) {
        const latestId = page.sessions[0]?.sessionId
        if (!latestId) throw new Error('后端返回的会话ID不完整')
        ready = await restoreConversation(latestId, version)
      } else {
        if (page.hasMore) throw new Error('后端返回的会话分页不完整')
        const sessionId = await requestNewSession(controller.signal, version)
        state.sessionId = sessionId
        state.sessionTitle = '新对话'
        state.messages = []
        resetHistoryPage()
        state.savedMessageCount = 0
        renderAllMessages()
        if (state.problem && !state.problemPending) await recordProblemConnection()
        await saveConversation()
        ready = true
      }
    }
    assertCurrentChat(controller.signal, version)
    sessionNotice()
  } catch (error) {
    if (version === state.conversationVersion) sessionNotice(
      error.name === 'AbortError' ? '准备对话已取消或超时，请点击“新对话”重试。' : error.message, true)
  } finally {
    clearTimeout(timer)
    if (version === state.conversationVersion) {
      state.sessionBusy = false
      state.sessionOperationController = null
      setBusy(false)
      renderSessionHeading()
      renderSessionList()
      if (ready) void refreshSessionList()
    }
  }
}

function renderSessionList() {
  elements['session-list'].innerHTML = state.sessionRecords.length
    ? state.sessionRecords.map((session) =>
      '<div class="session-row ' + (session.sessionId === state.sessionId ? 'active' : '') + '">' +
        '<button class="session-open" data-switch-session="' + escapeHtml(session.sessionId) + '" ' + (state.sessionBusy ? 'disabled' : '') + '>' +
          '<strong>' + escapeHtml(session.title || '新对话') + '</strong>' +
          '<small>' + escapeHtml(session.updatedAt ? new Date(session.updatedAt).toLocaleString() : '') +
          (session.sessionId === state.sessionId ? ' · 当前对话' : '') + '</small></button>' +
        '<button class="session-delete" data-delete-session="' + escapeHtml(session.sessionId) + '" title="删除对话" ' +
          (state.sessionBusy ? 'disabled' : '') + '>×</button></div>').join('')
    : '<p class="session-empty">' + (state.sessionListLoading ? '正在读取历史对话…' : '暂无历史对话，点击“新对话”开始。') + '</p>'
  elements['session-list-more'].classList.toggle('hidden', !state.sessionListHasMore)
  elements['session-list-more'].disabled = state.sessionListLoading
}

async function refreshSessionList(append = false, syncVersion = null) {
  const identity = chatIdentity()
  const currentId = state.sessionId
  const version = ++state.sessionListVersion
  state.sessionListController?.abort()
  const controller = new AbortController()
  state.sessionListController = controller
  state.sessionListLoading = true
  renderSessionList()
  try {
    const page = await sessionRequest('/chat/sessions', {
      params: { ...sessionIdentityParams(), offset: append ? state.sessionListOffset : 0, limit: 20 },
      signal: controller.signal,
    })
    if (identity !== chatIdentity() || version !== state.sessionListVersion) return
    if (!Array.isArray(page?.sessions)) throw new Error('后端返回的会话列表不完整')
    const rows = append ? [...state.sessionRecords, ...page.sessions] : page.sessions
    state.sessionRecords = [...new Map(rows.map((row) => [row.sessionId, row])).values()]
    state.sessionListHasMore = Boolean(page.hasMore)
    state.sessionListOffset = page.nextOffset ?? state.sessionRecords.length
    const current = state.sessionRecords.find((row) => row.sessionId === state.sessionId)
    if (current && !state.busy && !state.sessionBusy && syncVersion === state.conversationVersion && currentId === state.sessionId) {
      state.sessionTitle = current.title || '新对话'
      await syncCurrentHistory(currentId, syncVersion)
      renderSessionHeading()
    }
  } catch (error) {
    if (version === state.sessionListVersion && error.name !== 'AbortError') sessionNotice(error.message, true)
  } finally {
    if (version === state.sessionListVersion) {
      state.sessionListLoading = false
      state.sessionListController = null
      renderSessionList()
    }
  }
}

// 本轮写入完成后刷新数据库消息索引；只更新本会话缓存，不重绘流式 Agent 分组。
async function syncCurrentHistory(sessionId, version) {
  const detail = await sessionRequest('/chat/session_detail', {
    params: { ...sessionIdentityParams(), sessionId, limit: 50 },
  })
  if (version !== state.conversationVersion || sessionId !== state.sessionId || state.busy || state.sessionBusy) return
  if (detail?.session?.sessionId !== sessionId || !Array.isArray(detail.messages)) return
  const alreadyOlder = state.historyBeforeId != null && detail.beforeMessageId != null
    && state.historyBeforeId < detail.beforeMessageId
  state.historyServerMessages = [...new Map([...state.historyServerMessages, ...historyMessages(detail.messages)]
    .map((message) => [message.id, message])).values()].sort((a, b) => a.databaseId - b.databaseId)
  if (!alreadyOlder) state.historyHasMore = Boolean(detail.hasMore)
  state.historyBeforeId = state.historyServerMessages[0]?.databaseId ?? null
  state.savedMessageCount = detail.session.messageCount
  state.sessionTitle = detail.session.title || state.sessionTitle
  await saveConversation()
  renderSessionHeading()
}

async function switchConversation(sessionId, closeDrawer = true) {
  if (state.sessionBusy || !sessionId) return
  resetChatContext()
  const version = state.conversationVersion
  const controller = new AbortController()
  state.sessionOperationController = controller
  state.sessionBusy = true
  setBusy(false)
  sessionNotice()
  try {
    await saveConversation()
    if (version !== state.conversationVersion) return
    if (!await restoreConversation(sessionId, version)) return
    if (closeDrawer) elements['session-drawer'].classList.add('hidden')
    await saveConversation()
  } catch (error) {
    if (version === state.conversationVersion && error.name !== 'AbortError') sessionNotice(error.message, true)
  } finally {
    if (version === state.conversationVersion) {
      state.sessionBusy = false
      state.sessionOperationController = null
      setBusy(false)
      renderSessionHeading()
      renderSessionList()
    }
  }
}

async function loadOlderMessages() {
  if (!state.sessionId || !state.historyHasMore || state.busy || state.sessionBusy) return
  const version = state.conversationVersion
  const sessionId = state.sessionId
  const controller = new AbortController()
  state.sessionOperationController = controller
  state.sessionBusy = true
  setBusy(false)
  try {
    const detail = await sessionRequest('/chat/session_detail', {
      params: { ...sessionIdentityParams(), sessionId, beforeMessageId: state.historyBeforeId, limit: 50 },
      signal: controller.signal,
    })
    if (version !== state.conversationVersion || sessionId !== state.sessionId) return
    if (detail?.session?.sessionId !== sessionId || !Array.isArray(detail.messages)) throw new Error('历史消息格式不完整')
    state.historyServerMessages = [...historyMessages(detail.messages), ...state.historyServerMessages]
    state.messages = [...new Map(state.historyServerMessages.map((message) => [message.id, message])).values()]
    state.historyHasMore = Boolean(detail.hasMore)
    state.historyBeforeId = detail.beforeMessageId ?? null
    renderAllMessages()
    await saveConversation()
  } catch (error) {
    if (version === state.conversationVersion && error.name !== 'AbortError') sessionNotice(error.message, true)
  } finally {
    if (version === state.conversationVersion) {
      state.sessionBusy = false
      state.sessionOperationController = null
      setBusy(false)
      renderSessionHeading()
    }
  }
}

async function deleteConversation(sessionId) {
  if (state.sessionBusy || !sessionId) return
  if (state.busy) { sessionNotice('请先停止当前生成，等待后端处理结束后再删除对话。', true); return }
  if (!window.confirm('确定删除这个对话及其全部消息吗？\n\n此操作不可恢复；不会删除长期记忆和错题本。')) return
  const current = state.sessionId === sessionId
  if (current) resetChatContext()
  const version = state.conversationVersion
  const controller = new AbortController()
  state.sessionOperationController = controller
  state.sessionBusy = true
  setBusy(state.busy)
  sessionNotice()
  const key = conversationKey(sessionId)
  const activeKey = activeConversationKey()
  try {
    const result = await sessionRequest('/chat/session_delete', {
      method: 'POST', body: { ...sessionIdentityParams(), sessionId }, signal: controller.signal,
    })
    if (version !== state.conversationVersion) return
    if (result !== true) throw new Error('后端未确认删除成功')
    await chrome.storage.local.remove(key)
    if (version !== state.conversationVersion) return
    if (current && state.sessionId === sessionId) {
      state.sessionId = null
      state.sessionTitle = '新对话'
      state.messages = []
      resetHistoryPage()
      renderAllMessages()
      await storageSet({ [activeKey]: null })
      if (version !== state.conversationVersion) return
    }
    state.sessionRecords = state.sessionRecords.filter((row) => row.sessionId !== sessionId)
    sessionNotice('对话已删除，长期记忆和错题本已保留。')
    renderSessionList()
  } catch (error) {
    if (version === state.conversationVersion && error.name !== 'AbortError') sessionNotice(error.message, true)
  } finally {
    if (version === state.conversationVersion) {
      state.sessionBusy = false
      state.sessionOperationController = null
      setBusy(state.busy)
      renderSessionHeading()
      void refreshSessionList()
    }
  }
}

async function activeLeetCodeTab() {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true })
  if (!tab?.id || !tab.url?.startsWith('https://leetcode.cn/problems/')) {
    throw new Error('当前标签页不是力扣题目页面')
  }
  return tab
}

async function readLeetCodeContext(includeCode = false) {
  const tab = await activeLeetCodeTab()
  try {
    const response = await chrome.tabs.sendMessage(tab.id, {
      type: 'GODE_GET_LEETCODE_CONTEXT',
      includeCode,
    })
    if (!response?.success) throw new Error(response?.message || '读取力扣页面失败')
    return response.context
  } catch (error) {
    const reason = error instanceof Error ? error.message : String(error)
    if (reason.includes('Receiving end does not exist')) {
      throw new Error('插件尚未注入力扣页面，请刷新当前力扣页面后重试')
    }
    throw error
  }
}

async function loadCurrentProblem() {
  if (state.sessionBusy) invalidateSuggestions()
  else resetChatContext()
  const loadVersion = ++state.problemLoadVersion
  state.problemPending = true
  setConnectionStatus('正在读取当前题目…')
  try {
    const context = await readLeetCodeContext(false)
    if (loadVersion !== state.problemLoadVersion) return
    state.problem = context.problem
    elements['problem-number'].textContent = `${state.problem.frontendId}.`
    elements['problem-title'].textContent = state.problem.title
    elements['problem-difficulty'].textContent = state.problem.difficulty
    elements['problem-difficulty'].dataset.level = state.problem.difficulty
    elements['problem-tags'].textContent = state.problem.tags.slice(0, 3).join(' · ')
    elements['problem-card'].classList.toggle('hidden', state.activeView !== 'chat')
    elements['empty-state'].classList.add('hidden')
    setConnectionStatus('已连接当前力扣题目', true)
    await recordProblemConnection()
  } catch (error) {
    if (loadVersion !== state.problemLoadVersion) return
    state.problem = null
    elements['problem-card'].classList.add('hidden')
    elements['empty-state'].classList.toggle('hidden', state.activeView !== 'chat')
    setConnectionStatus(error instanceof Error ? error.message : '未连接力扣')
  } finally {
    if (loadVersion === state.problemLoadVersion) {
      state.problemPending = false
      renderSuggestions()
    }
  }
}

async function requestNewSession(signal, version, title = '新对话') {
  const response = await withAbort(fetch(state.settings.apiBaseUrl + '/create_session', {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
    body: JSON.stringify({ ...sessionIdentityParams(), title }), signal,
  }), signal)
  const result = await withAbort(response.json(), signal)
  assertCurrentChat(signal, version)
  if (!response.ok || result.code !== '0000' || !result.data?.sessionId) {
    throw new Error(result.info || '创建会话失败（HTTP ' + response.status + '）')
  }
  return result.data.sessionId
}

async function createSession(signal, version) {
  const title = [...state.messages].reverse().find((message) => message.role === 'user')?.content?.slice(0, 40) || '新对话'
  const id = await requestNewSession(signal, version, title)
  state.sessionId = id
  state.sessionTitle = title
  state.savedMessageCount = 0
  await saveConversation()
  renderSessionHeading()
  return id
}

function buildPrompt(question, context) {
  const sections = [
    `当前力扣题目：${context.problem.frontendId}. ${context.problem.title}`,
    `当前题目 titleSlug：${context.problem.slug}`,
    `难度：${context.problem.difficulty}`,
    `题目内容：\n${context.problem.description}`,
  ]
  if (context.code?.trim()) sections.push(`力扣编辑器中的当前代码：\n${context.code}`)
  sections.push(`用户问题：${question}`)
  return sections.join('\n\n')
}

function parseNdjsonLine(line, streamState) {
  if (!line.trim()) return
  const streamEvent = JSON.parse(line)
  const eventType = streamEvent.event ?? streamEvent.type

  if (eventType === 'error') {
    throw new Error(streamEvent.content || 'ReAct 对话失败')
  }

  if (eventType === 'tool_call') {
    hideThinking()
    const toolName = streamEvent.toolName || '未知工具'
    const toolCallId = streamEvent.toolCallId
    const message = addMessage('assistant', `正在执行 ${toolName}…`, '工具调用', 'tool-event')
    if (toolCallId) streamState.toolMessages.set(toolCallId, { message, toolName })
    elements['active-agent'].textContent = `正在执行 ${toolName}`
    return
  }

  if (eventType === 'tool_result') {
    hideThinking()
    const toolCallId = streamEvent.toolCallId
    const pending = toolCallId ? streamState.toolMessages.get(toolCallId) : null
    const toolName = pending?.toolName || streamEvent.toolName || '工具'
    const failed = ['error', 'failed'].includes(String(streamEvent.status || '').toLowerCase())
    const result = failed && streamEvent.content
      ? `：${String(streamEvent.content).slice(0, 300)}`
      : ''
    const content = `${toolName}${failed ? '执行失败' : '执行完成'}${result}`
    if (pending) {
      setMessageContent(pending.message, content)
      streamState.toolMessages.delete(toolCallId)
    } else {
      addMessage('assistant', content, '工具结果', 'tool-event')
    }
    return
  }

  if (eventType === 'round_end') {
    const step = streamEvent.stepInfo?.currentStep
    if (step) elements['active-agent'].textContent = `第 ${step} 步已完成`
    return
  }

  if (eventType === 'done') {
    streamState.completed = true
    hideThinking()
    elements['active-agent'].textContent = '回答完成'
    if (streamState.agentMessages.size === 0 && streamEvent.content) {
      let finalText = String(streamEvent.content)
      try {
        const result = JSON.parse(finalText)
        finalText = result?.finalResponse || ''
      } catch {
        // done.content can also be plain text.
      }
      if (finalText) {
        const message = addMessage('assistant', finalText, agentLabel(streamEvent.agentName))
        streamState.agentMessages.set(streamEvent.agentName || 'assistant', message)
      }
    }
    return
  }

  if ((eventType !== 'text' && eventType !== 'chunk') || !streamEvent.content) return

  const key = streamEvent.agentName || 'assistant'
  elements['active-agent'].textContent = `${agentLabel(streamEvent.agentName)} 正在回答`
  hideThinking()

  let message = streamState.agentMessages.get(key)
  if (!message) {
    message = addMessage('assistant', '', agentLabel(streamEvent.agentName))
    streamState.agentMessages.set(key, message)
  }
  updateMessage(message, streamEvent.content)
}

async function streamChat(prompt, signal, version) {
  const response = await withAbort(fetch(`${state.settings.apiBaseUrl}/chat_react_stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/x-ndjson',
    },
    body: JSON.stringify({
      agentId: state.settings.agentId,
      userId: state.userId,
      sessionId: state.sessionId,
      message: prompt,
    }),
    signal,
  }), signal)
  assertCurrentChat(signal, version)
  if (response.status === 404) {
    throw new Error('后端未提供 /chat_react_stream 接口，请先完成 ReAct Controller 接入')
  }
  if (!response.ok) throw new Error((await response.text()) || `对话失败（HTTP ${response.status}）`)
  if (!response.body) throw new Error('浏览器没有收到流式响应内容')

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  const streamState = {
    completed: false,
    agentMessages: new Map(),
    toolMessages: new Map(),
  }
  let buffer = ''

  try {
    while (true) {
      const { done, value } = await withAbort(reader.read(), signal)
      assertCurrentChat(signal, version)
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split(/\r?\n/)
      buffer = lines.pop() ?? ''
      for (const line of lines) parseNdjsonLine(line, streamState)
    }
    buffer += decoder.decode()
    if (buffer.trim()) parseNdjsonLine(buffer, streamState)
    return { completed: streamState.completed, messageCount: streamState.agentMessages.size }
  } finally {
    // 取消底层读取，避免已作废的流继续占用连接。
    try { void reader.cancel().catch(() => {}) } catch { /* 请求可能已被浏览器取消。 */ }
    reader.releaseLock()
  }
}

function assertCurrentChat(signal, version) {
  if (signal?.aborted || version !== state.conversationVersion) {
    throw new DOMException('请求已取消', 'AbortError')
  }
}

function renderSuggestions() {
  const panel = elements['quick-prompts']
  panel.replaceChildren()
  panel.setAttribute('aria-busy', String(Boolean(state.suggestionController)))
  panel.title = state.suggestionController ? '正在根据对话生成下一步建议…' : '点击建议填入输入框，可编辑后发送'
  for (const suggestion of state.suggestions) {
    const button = document.createElement('button')
    button.type = 'button'
    button.textContent = suggestion.label
    button.dataset.prompt = suggestion.prompt
    button.title = suggestion.prompt
    button.disabled = state.busy || state.problemPending
    panel.appendChild(button)
  }
}

function invalidateSuggestions() {
  state.suggestionVersion++
  state.suggestionController?.abort()
  state.suggestionController = null
  state.suggestions = DEFAULT_SUGGESTIONS
  renderSuggestions()
}

// 切题、设置变化、新会话会使所有旧异步结果失效。
function resetChatContext() {
  state.conversationVersion++
  invalidateSuggestions()
  state.abortController?.abort()
  state.abortController = null
  state.sessionOperationController?.abort()
  state.sessionOperationController = null
  state.sessionBusy = false
  hideThinking()
  setBusy(false)
}

// 即使某个异步实现忽略 signal，也让调用者及时结束等待。
function withAbort(promise, signal) {
  return new Promise((resolve, reject) => {
    const abort = () => reject(new DOMException('请求已取消', 'AbortError'))
    if (signal.aborted) return abort()
    signal.addEventListener('abort', abort, { once: true })
    Promise.resolve(promise).then(resolve, reject).finally(() => signal.removeEventListener('abort', abort))
  })
}

async function refreshSuggestions(version = state.conversationVersion) {
  if (version !== state.conversationVersion || state.busy || state.sessionBusy || state.problemPending || !state.problem || !state.sessionId) return
  invalidateSuggestions()
  const requestVersion = state.suggestionVersion
  const snapshot = {
    agentId: state.settings.agentId, userId: state.userId, sessionId: state.sessionId,
    problemSlug: state.problem.slug, problemTitle: state.problem.title,
  }
  const api = state.settings.apiBaseUrl
  const controller = new AbortController()
  state.suggestionController = controller
  const isCurrent = () => !controller.signal.aborted && requestVersion === state.suggestionVersion
    && version === state.conversationVersion && snapshot.problemSlug === state.problem?.slug
    && snapshot.sessionId === state.sessionId && snapshot.agentId === state.settings.agentId
    && snapshot.userId === state.userId && api === state.settings.apiBaseUrl
  renderSuggestions()
  const timer = setTimeout(() => controller.abort(), SUGGESTION_TIMEOUT_MS)
  try {
    const messages = state.messages.filter((message) => ['user', 'assistant'].includes(message.role)
      && !message.extraClass && !message.welcome && typeof message.content === 'string' && message.content.trim())
      // 先限制请求体大小；最终 3000 字符预算及截断标记交给后端处理。
      .slice(-6).map(({ role, content }) => ({ role, content: content.slice(0, 10000).replace(/[\uD800-\uDBFF]$/, '') }))
    const response = await withAbort(fetch(`${api}/chat/suggestions`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ...snapshot, messages }), signal: controller.signal,
    }), controller.signal)
    if (!response.ok) throw new Error('推荐接口不可用')
    const result = await withAbort(response.json(), controller.signal)
    if (!isCurrent()) return
    if (result.code !== '0000' || !Array.isArray(result.data) || !result.data.length || result.data.length > 3) {
      throw new Error('推荐格式无效')
    }
    const labels = new Set()
    const prompts = new Set()
    const suggestions = result.data.map((item) => {
      if (typeof item?.label !== 'string' || typeof item?.prompt !== 'string') throw new Error('推荐格式无效')
      const label = item.label.trim()
      const prompt = item.prompt.trim()
      const labelKey = label.replace(/\s/g, '').toLowerCase()
      const promptKey = prompt.replace(/\s/g, '').toLowerCase()
      if (!label || /[\r\n]/.test(label) || Array.from(label).length > 12 || !prompt || prompt.length > 300
        || labels.has(labelKey) || prompts.has(promptKey)) throw new Error('推荐格式无效')
      labels.add(labelKey)
      prompts.add(promptKey)
      return { label, prompt }
    })
    state.suggestions = suggestions
  } catch {
    // 超时、取消、旧后端或模型格式异常均保留默认按钮，不干扰对话。
  } finally {
    clearTimeout(timer)
    if (requestVersion === state.suggestionVersion && state.suggestionController === controller) {
      state.suggestionController = null
      renderSuggestions()
    }
  }
}

function setBusy(busy) {
  state.busy = busy
  renderSuggestions()
  elements['message-input'].disabled = busy || state.sessionBusy
  elements['new-chat-button'].disabled = state.sessionBusy
  elements['load-older-messages'].disabled = busy || state.sessionBusy
  elements['send-button'].disabled = state.sessionBusy
  elements['send-button'].textContent = busy ? '■' : '➤'
  elements['send-button'].title = busy ? '停止生成' : '发送'
  elements['send-button'].setAttribute('aria-label', busy ? '停止生成' : '发送消息')
  elements['send-button'].classList.toggle('stopping', busy)
  for (const button of elements['message-list'].querySelectorAll('.apply-code-button')) {
    button.disabled = busy
  }
  if (!busy) {
    elements['active-agent'].textContent = '多 Agent 对话'
    elements['composer-hint'].textContent = 'Enter 发送，Shift + Enter 换行'
  }
}

function cancelGeneration() {
  if (!state.busy || !state.abortController) return
  invalidateSuggestions()
  elements['composer-hint'].textContent = '正在停止生成…'
  elements['send-button'].disabled = true
  state.abortController.abort()
}

async function sendCurrentMessage() {
  const question = elements['message-input'].value.trim()
  if (!question || state.busy || state.sessionBusy || state.problemPending) return
  if (!state.sessionId) {
    sessionNotice('请先点击“新对话”创建聊天。', true)
    return
  }
  if (!state.problem) {
    addMessage('assistant', '请先打开一道力扣题目，再开始对话。', '连接提示', 'error')
    return
  }

  invalidateSuggestions()
  const version = ++state.conversationVersion
  const slug = state.problem.slug
  let recommend = false
  elements['message-input'].value = ''
  addMessage('user', question, '你')
  setBusy(true)
  showThinking()
  const controller = new AbortController()
  state.abortController = controller

  try {
    const context = await withAbort(readLeetCodeContext(elements['include-code'].checked), controller.signal)
    assertCurrentChat(controller.signal, version)
    if (context.problem.slug !== slug) {
      void loadCurrentProblem()
      throw new DOMException('题目已切换', 'AbortError')
    }
    state.problem = context.problem
    assertCurrentChat(controller.signal, version)
    const result = await streamChat(buildPrompt(question, context), controller.signal, version)
    assertCurrentChat(controller.signal, version)
    hideThinking()
    if (result.messageCount === 0) addMessage('assistant', '后端完成了请求，但没有返回可显示的内容。', '系统提示', 'error')
    recommend = result.completed && result.messageCount > 0
    await saveConversation()
  } catch (error) {
    if (version !== state.conversationVersion) return
    hideThinking()
    if (error instanceof DOMException && error.name === 'AbortError') return
    const reason = error instanceof Error ? error.message : String(error)
    const hint = error instanceof TypeError ? '\n\n请确认 gode-server 已在 8091 端口启动。' : ''
    addMessage('assistant', `${reason}${hint}`, '连接失败', 'error')
  } finally {
    if (version === state.conversationVersion && state.abortController === controller) {
      state.abortController = null
      setBusy(false)
      elements['message-input'].focus()
      if (recommend && !controller.signal.aborted) void refreshSuggestions(version)
      if (!controller.signal.aborted) void refreshSessionList(false, version)
    }
  }
}

async function startNewChat() {
  if (state.sessionBusy) return
  resetChatContext()
  const version = state.conversationVersion
  const controller = new AbortController()
  state.sessionOperationController = controller
  state.sessionBusy = true
  setBusy(false)
  sessionNotice()
  const timer = window.setTimeout(() => controller.abort(), 15000)
  try {
    await saveConversation()
    assertCurrentChat(controller.signal, version)
    const id = await requestNewSession(controller.signal, version)
    state.sessionId = id
    state.sessionTitle = '新对话'
    state.messages = []
    resetHistoryPage()
    state.savedMessageCount = 0
    renderAllMessages()
    if (state.problem && !state.problemPending) await recordProblemConnection()
    await saveConversation()
    if (version !== state.conversationVersion) return
    elements['session-drawer'].classList.add('hidden')
    renderSessionHeading()
  } catch (error) {
    if (version === state.conversationVersion) sessionNotice(
      error.name === 'AbortError' ? '创建对话已取消或超时，请重试。' : error.message, true)
  } finally {
    clearTimeout(timer)
    if (version === state.conversationVersion) {
      state.sessionBusy = false
      state.sessionOperationController = null
      setBusy(false)
      void refreshSessionList()
    }
  }
}

async function loadSettings() {
  const stored = await storageGet(['godeSidepanelSettings', 'godeSidepanelUserId'])
  state.settings = { ...DEFAULT_SETTINGS, ...(stored.godeSidepanelSettings ?? {}) }
  state.userId = stored.godeSidepanelUserId || `extension-${crypto.randomUUID()}`
  await storageSet({ godeSidepanelUserId: state.userId })
  elements['api-base-url'].value = state.settings.apiBaseUrl
  elements['agent-id'].value = state.settings.agentId
}

async function saveSettings() {
  cancelNotesConfiguration()
  elements['leetcode-session-cookie'].value = ''
  await saveConversation()
  resetChatContext()
  state.sessionListController?.abort()
  state.sessionListVersion++
  state.sessionRecords = []
  state.sessionId = null
  state.sessionTitle = '新对话'
  state.messages = []
  resetHistoryPage()
  renderAllMessages()
  state.settings = {
    apiBaseUrl: elements['api-base-url'].value.trim().replace(/\/$/, '') || DEFAULT_SETTINGS.apiBaseUrl,
    agentId: elements['agent-id'].value.trim() || DEFAULT_SETTINGS.agentId,
  }
  await storageSet({ godeSidepanelSettings: state.settings })
  await restoreActiveConversation()
  void loadMistakeBook()
  elements['settings-panel'].classList.add('hidden')
  elements['composer-hint'].textContent = '设置已保存'
  window.setTimeout(() => {
    elements['composer-hint'].textContent = 'Enter 发送，Shift + Enter 换行'
  }, 1400)
}

// 个人测试：凭据不进入 state.settings、消息历史或任何 chrome.storage。
function notesConfigurationNotice(message, kind = '') {
  elements['notes-cookie-status'].textContent = message
  elements['notes-cookie-status'].classList.toggle('notes-error', kind === 'error')
  elements['notes-cookie-status'].classList.toggle('notes-success', kind === 'success')
}

function cancelNotesConfiguration() {
  state.notesConfigVersion++
  state.notesConfigController?.abort()
  state.notesConfigController = null
  state.notesConfigBusy = false
  elements['save-notes-cookie'].disabled = false
  elements['clear-notes-cookie'].disabled = false
}

function notesConfigurationTarget() {
  const base = elements['api-base-url'].value.trim().replace(/\/+$/, '') || DEFAULT_SETTINGS.apiBaseUrl
  const agentId = elements['agent-id'].value.trim() || DEFAULT_SETTINGS.agentId
  if (base !== state.settings.apiBaseUrl.replace(/\/+$/, '') || agentId !== state.settings.agentId) {
    throw new Error('请先保存后端地址和 Agent ID，再配置 Cookie')
  }
  const url = new URL(base)
  const local = ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)
  if (url.username || url.password || url.search || url.hash
      || !(url.protocol === 'https:' || (url.protocol === 'http:' && local))) {
    throw new Error('Cookie 只允许发送到 HTTPS 或本机 SSH 隧道地址')
  }
  return { base, agentId }
}

async function updateNotesConfiguration(operation = 'status') {
  if (state.notesConfigBusy) return
  let target
  try { target = notesConfigurationTarget() } catch (error) {
    notesConfigurationNotice(error.message, 'error')
    return
  }
  if (operation === 'clear' && !window.confirm('清除该后端当前的个人笔记登录配置？不会删除力扣笔记。')) return
  let cookie
  if (operation === 'save') {
    cookie = elements['leetcode-session-cookie'].value.trim()
    if (!cookie || cookie.length > 16384 || /[\r\n]/.test(cookie)) {
      notesConfigurationNotice('请填写有效的 LEETCODE_SESSION Cookie，不要填写多行内容', 'error')
      return
    }
    const field = cookie.match(/(?:^|;\s*)LEETCODE_SESSION=([^;\s]+)/)
    if (field) cookie = field[1]
    if (!/^[A-Za-z0-9._~+/%=-]+$/.test(cookie)) {
      notesConfigurationNotice('请填写 LEETCODE_SESSION 的值，不要填写 JSON 或其他凭据', 'error')
      return
    }
  }
  const controller = new AbortController()
  const version = ++state.notesConfigVersion
  state.notesConfigController = controller
  state.notesConfigBusy = true
  elements['save-notes-cookie'].disabled = true
  elements['clear-notes-cookie'].disabled = true
  notesConfigurationNotice(operation === 'save' ? '正在启动笔记连接并进行只读验证…'
    : operation === 'clear' ? '正在清除登录配置…' : '正在检查笔记登录配置…')
  const timer = window.setTimeout(() => controller.abort(), operation === 'save' ? 150000 : 15000)
  try {
    const url = new URL(target.base + '/personal/leetcode-notes' + (operation === 'clear' ? '/clear' : ''))
    if (operation === 'status') url.searchParams.set('agentId', target.agentId)
    const response = await withAbort(fetch(url.href, {
      method: operation === 'status' ? 'GET' : 'POST',
      headers: { 'Content-Type': 'application/json', 'Accept': 'application/json', 'X-Gode-Personal-Config': '1' },
      credentials: 'omit', redirect: 'error', signal: controller.signal,
      body: operation === 'status' ? undefined : JSON.stringify({ agentId: target.agentId,
        ...(operation === 'save' ? { cookie } : {}) }),
    }), controller.signal)
    const result = await withAbort(response.json(), controller.signal)
    if (version !== state.notesConfigVersion) return
    if (!response.ok || result.code !== '0000') {
      // 不回显后端/代理的任意错误正文，避免其中含有提交的凭据。
      const message = response.status === 404 ? '后端尚未更新个人笔记配置接口，请部署新版后端'
        : response.ok && result.info === '后端未启用个人笔记配置，请设置 GODE_PERSONAL_NOTES_ENABLED=true 后重启'
          ? result.info : '笔记配置操作失败，请检查 Cookie、后端是否更新及服务器到力扣的网络'
      throw new Error(message)
    }
    const status = result.data
    if (!status || typeof status.configured !== 'boolean') throw new Error('后端返回的笔记配置状态不完整')
    if (operation === 'save') elements['leetcode-session-cookie'].value = ''
    if (operation === 'clear') {
      elements['leetcode-session-cookie'].value = ''
      notesConfigurationNotice('已清除登录配置；力扣上的笔记没有删除', 'success')
    } else if (status.verified && status.writeToolsAvailable) {
      notesConfigurationNotice('登录验证成功，创建/修改笔记工具已就绪。请在对话中明确要求保存或修改。', 'success')
    } else if (status.configured) {
      notesConfigurationNotice('后端已有启动配置但尚未验证；可填写 Cookie 并点击“保存并验证”')
    } else {
      notesConfigurationNotice('尚未配置力扣登录，暂不能读取或写入个人笔记')
    }
  } catch (error) {
    if (version !== state.notesConfigVersion) return
    // fetch/代理异常可能包含敏感请求信息，不将原始异常放入 UI 或日志。
    const known = ['后端尚未更新个人笔记配置接口，请部署新版后端',
      '后端未启用个人笔记配置，请设置 GODE_PERSONAL_NOTES_ENABLED=true 后重启',
      '笔记配置操作失败，请检查 Cookie、后端是否更新及服务器到力扣的网络', '后端返回的笔记配置状态不完整']
    notesConfigurationNotice(controller.signal.aborted
      ? '验证等待超时；后端操作可能仍在继续，请重新打开设置检查状态'
      : known.includes(error.message) ? error.message : '笔记配置请求失败，请检查后端及 SSH 隧道连接', 'error')
  } finally {
    cookie = undefined
    clearTimeout(timer)
    if (version === state.notesConfigVersion) {
      state.notesConfigController = null
      state.notesConfigBusy = false
      elements['save-notes-cookie'].disabled = false
      elements['clear-notes-cookie'].disabled = false
    }
  }
}

function setActiveView(view) {
  if (state.mistakeActionBusy) return
  cancelNotesConfiguration()
  elements['leetcode-session-cookie'].value = ''
  closeMistakeDetail()
  state.activeView = view
  const isMistakeBook = view === 'mistake-book'

  // 错题本是独立页面：用根节点状态兜底隐藏整个对话界面，避免页面状态变化后残留聊天组件。
  appShell?.classList.toggle('mistake-book-open', isMistakeBook)
  elements['chat-tab'].classList.toggle('active', !isMistakeBook)
  elements['mistake-book-tab'].classList.toggle('active', isMistakeBook)
  elements['mistake-book'].classList.toggle('hidden', !isMistakeBook)
  elements['chat-area'].classList.toggle('hidden', isMistakeBook)
  elements['composer-hint'].parentElement.classList.toggle('hidden', isMistakeBook)
  elements['settings-panel'].classList.add('hidden')

  if (isMistakeBook) {
    elements['session-drawer'].classList.add('hidden')
    elements['problem-card'].classList.add('hidden')
    elements['empty-state'].classList.add('hidden')
    closeMistakeDetail()
    void loadMistakeBook()
    return
  }

  elements['problem-card'].classList.toggle('hidden', !state.problem)
  elements['empty-state'].classList.toggle('hidden', Boolean(state.problem))
}

// 错题本：只使用后端数据，不回退到演示题目。
let mistakeListController = null
let mistakeListVersion = 0
let mistakeDetailVersion = 0
let mistakeSearchTimer = null
let settingsReady

function mistakeNotice(message = '', error = false) {
  elements['mistake-notice'].textContent = message
  elements['mistake-notice'].classList.toggle('hidden', !message)
  elements['mistake-notice'].classList.toggle('error', error)
}

async function mistakeRequest(path, { method = 'GET', params, body, signal, timeoutMs = 30000 } = {}) {
  if (!state.userId) await settingsReady
  if (!state.userId) throw new Error('用户身份尚未初始化，请重新打开插件')
  const url = new URL(state.settings.apiBaseUrl.replace(/\/+$/, '') + '/mistake/' + path)
  if (params) {
    for (const [key, value] of Object.entries(params)) {
      if (value !== null && value !== undefined && value !== '') url.searchParams.set(key, String(value))
    }
  }
  const controller = new AbortController()
  const relayAbort = () => controller.abort()
  signal?.addEventListener('abort', relayAbort, { once: true })
  if (signal?.aborted) controller.abort()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  try {
    const response = await fetch(url.href, {
      method, signal: controller.signal,
      ...(body ? { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) } : {}),
    })
    if (!response.ok) throw new Error('错题本请求失败（HTTP ' + response.status + '）')
    const result = await response.json()
    if (result.code !== '0000') throw new Error(result.info || '错题本操作失败')
    return result.data
  } catch (error) {
    if (signal?.aborted) throw error
    if (error.name === 'AbortError') throw new Error(path === 'analyze_submission'
      ? '复盘生成超时，请重试；当前草稿尚未保存'
      : '请求超时；保存或状态更新可能已生效，请刷新确认后再重试')
    if (error instanceof TypeError) throw new Error('无法连接错题本服务，请确认后端地址、端口及数据库连接')
    throw error
  } finally {
    clearTimeout(timer)
    signal?.removeEventListener('abort', relayAbort)
  }
}

function closeMistakeDetail() {
  if (state.mistakeActionBusy) return
  mistakeDetailVersion++
  state.mistakeDetail = null
  state.reviewEditingKey = ''
  state.mistakeReviews = []
  state.selectedMistakeReview = 0
  state.mistakeHistoryExpanded = false
  elements['mistake-detail'].classList.add('hidden')
  renderSubmissionReviewBanner()
}

function mistakeFilters() {
  return {
    status: { due: '待复习', reviewing: '复习中', mastered: '已掌握' }[state.mistakeFilter],
    tag: { hash: '哈希表', pointer: '双指针' }[state.mistakeFilter],
  }
}

async function loadMistakeBook() {
  const version = ++mistakeListVersion
  mistakeListController?.abort()
  const controller = new AbortController()
  mistakeListController = controller
  state.mistakeLoading = true
  state.mistakeError = ''
  renderMistakeBook()
  try {
    if (!state.userId) await settingsReady
    if (version !== mistakeListVersion) return
    const params = {
      userId: state.userId, ...mistakeFilters(), keyword: state.mistakeQuery.trim(),
      page: state.mistakePage, pageSize: MISTAKE_PAGE_SIZE,
    }
    // 页签显示全部错题数，不随筛选变化。
    const [page, count] = await Promise.all([
      mistakeRequest('page', { params, signal: controller.signal }),
      mistakeRequest('page', { params: { userId: state.userId, page: 1, pageSize: 1 }, signal: controller.signal }),
    ])
    if (version !== mistakeListVersion) return
    if (!Array.isArray(page?.records) || !Number.isFinite(Number(page?.total))) throw new Error('错题本数据格式不正确')
    const pages = Math.max(1, Number(page.totalPages) || Math.ceil(Number(page.total) / MISTAKE_PAGE_SIZE))
    if (state.mistakePage > pages) {
      state.mistakePage = pages
      return await loadMistakeBook()
    }
    state.mistakeRecords = page.records
    state.mistakeTotal = Number(page.total)
    state.mistakeTotalPages = pages
    elements['mistake-book-count'].textContent = String(count.total)
  } catch (error) {
    if (version !== mistakeListVersion || controller.signal.aborted) return
    state.mistakeRecords = []
    state.mistakeError = error.message || '加载错题失败'
    elements['mistake-book-count'].textContent = '—'
  } finally {
    if (version === mistakeListVersion) {
      state.mistakeLoading = false
      renderMistakeBook()
    }
  }
}

function displayMistakeDate(value) {
  if (!value) return '未安排'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleDateString('zh-CN')
}

function mistakeTags(record) {
  return String(record.tags || '').split(/[,，]/).map((tag) => tag.trim()).filter(Boolean)
}

function renderMistakeBook() {
  for (const button of elements['mistake-filters'].querySelectorAll('button')) {
    button.classList.toggle('active', button.dataset.filter === state.mistakeFilter)
  }
  const list = elements['mistake-book-list']
  elements['mistake-pagination'].classList.add('hidden')
  elements['mistake-prev-page'].disabled = true
  elements['mistake-next-page'].disabled = true
  if (state.mistakeLoading) {
    list.innerHTML = '<div class="mistake-empty">正在加载错题…</div>'
    return
  }
  if (state.mistakeError) {
    list.innerHTML = '<div class="mistake-empty">' + escapeHtml(state.mistakeError)
      + '<br><button class="text-button" type="button" data-retry-mistakes>重新加载</button></div>'
    return
  }
  if (!state.mistakeRecords.length) {
    list.innerHTML = '<div class="mistake-empty">'
      + (state.mistakeQuery || state.mistakeFilter !== 'all'
        ? '当前筛选下没有错题，可切换“全部”或清空搜索。'
        : '还没有错题。打开力扣题目后，点击“添加当前题”保存。') + '</div>'
    return
  }
  list.innerHTML = state.mistakeRecords.map((record) => {
    const difficultyClass = record.difficulty === '中等' ? 'medium' : record.difficulty === '困难' ? 'hard' : ''
    const stateClass = record.status === '已掌握' ? 'mastered' : record.status === '复习中' ? 'reviewing' : ''
    return `
      <button class="mistake-card" type="button" data-mistake-id="${escapeHtml(record.id)}">
        <span class="mistake-card-top">
          <span class="mistake-card-number">${escapeHtml(record.problemId)}.</span>
          <span class="mistake-card-title">${escapeHtml(record.title)}</span>
          <span class="difficulty ${difficultyClass}">${escapeHtml(record.difficulty)}</span>
        </span>
        <span class="mistake-reason">错误：${escapeHtml(record.errorSummary || '暂无错误摘要')}</span>
        <span class="mistake-card-bottom">
          <span class="mistake-tags">${escapeHtml(mistakeTags(record).join(' · '))}</span>
          <span class="review-state ${stateClass}">${escapeHtml(record.status)} · ${escapeHtml(record.status === '已掌握' ? '已完成' : displayMistakeDate(record.nextReviewTime))}</span>
        </span>
      </button>
    `
  }).join('')
  elements['mistake-pagination'].classList.toggle('hidden', state.mistakeTotalPages <= 1)
  elements['mistake-page-info'].textContent = state.mistakePage + ' / ' + state.mistakeTotalPages + ' · 共 ' + state.mistakeTotal + ' 题'
  elements['mistake-prev-page'].disabled = state.mistakePage <= 1
  elements['mistake-next-page'].disabled = state.mistakePage >= state.mistakeTotalPages
}

// reviews 为详情接口提供的完整复盘列表；旧接口只返回 latestReview 时不虚构历史。
function normalizeMistakeReviews(detail) {
  const reviews = Array.isArray(detail.reviews) ? detail.reviews : [detail.latestReview]
  const timestamp = (review) => {
    const value = review.createdAt ? new Date(review.createdAt).getTime() : 0
    return Number.isFinite(value) ? value : 0
  }
  return reviews.filter((review) => review && typeof review === 'object' && !Array.isArray(review))
    .slice().sort((left, right) => timestamp(right) - timestamp(left)
      || String(right.id ?? '').localeCompare(String(left.id ?? ''), 'en', { numeric: true }))
}

function displayMistakeReviewDate(value) {
  if (!value) return '时间未记录'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  })
}

function renderMistakeDetail() {
  if (!state.mistakeDetail) return
  const panel = elements['mistake-detail']
  const scrollTop = panel.scrollTop
  const historyScrollTop = panel.querySelector('.mistake-history-list')?.scrollTop || 0
  const record = state.mistakeDetail.mistake
  const reviews = state.mistakeReviews
  const review = reviews[state.selectedMistakeReview]
  const hasHistory = Array.isArray(state.mistakeDetail.reviews)
  const visibleReviews = state.mistakeHistoryExpanded ? reviews : reviews.slice(0, MISTAKE_HISTORY_PREVIEW_SIZE)
  panel.innerHTML = `
    <div class="mistake-detail-header">
      <div><span class="eyebrow">错题复盘</span><h2>${escapeHtml(record.problemId)}. ${escapeHtml(record.title)}</h2></div>
      <button class="icon-button" type="button" data-close-detail title="关闭">×</button>
    </div>
    <h3>学习状态</h3><p>${escapeHtml(record.status)} · 完成复习 ${escapeHtml(record.reviewCount || 0)} 次</p>
    <section class="mistake-history" aria-label="复盘历史">
      <div class="mistake-history-heading"><h3>复盘历史</h3><span>${hasHistory ? '共 ' + reviews.length + ' 次复盘' : '仅最新复盘'}</span></div>
      ${!hasHistory ? '<p class="mistake-history-hint">接口尚未提供历史列表，目前只能查看最新复盘。</p>' : ''}
      <div class="mistake-history-list">
        ${visibleReviews.map((entry, index) => {
          const preview = String(entry.failReason || '暂无错因摘要').replace(/\s+/g, ' ').trim()
          return `<button type="button" class="mistake-history-item${index === state.selectedMistakeReview ? ' selected' : ''}"
            data-select-mistake-review="${index}" aria-pressed="${index === state.selectedMistakeReview}">
            <span class="mistake-history-date">${escapeHtml(displayMistakeReviewDate(entry.createdAt))}${index === 0 ? '<span class="mistake-history-latest">最新</span>' : ''}</span>
            <span class="mistake-history-reason">${escapeHtml(preview.length > 80 ? preview.slice(0, 80) + '…' : preview)}</span>
          </button>`
        }).join('')}
      </div>
      ${reviews.length > MISTAKE_HISTORY_PREVIEW_SIZE ? `<button type="button" class="text-button mistake-history-toggle" data-toggle-mistake-history aria-expanded="${state.mistakeHistoryExpanded}">${state.mistakeHistoryExpanded ? (state.selectedMistakeReview >= MISTAKE_HISTORY_PREVIEW_SIZE ? '收起并回到最新复盘' : '收起历史') : '展开全部 ' + reviews.length + ' 次复盘'}</button>` : ''}
    </section>
    ${review ? `
      <section class="mistake-review-body" aria-label="所选复盘内容">
        <p class="mistake-review-caption">${state.selectedMistakeReview === 0 ? '最新复盘' : '历史复盘'} · ${escapeHtml(displayMistakeReviewDate(review.createdAt))}</p>
        <h3>这次为什么失败</h3><div id="mistake-fail-reason" class="message-content mistake-review-content"></div>
        <h3>失败用例 / 判题结果</h3><div id="mistake-fail-case" class="message-content mistake-review-content"></div>
        <h3>AI 分析</h3><div id="mistake-ai-analysis" class="message-content mistake-review-content"></div>
        <h3>下次这样做</h3><div id="mistake-action-plan" class="message-content mistake-review-content"></div>
      </section>
    ` : `<p class="mistake-history-empty">${hasHistory ? '暂无复盘记录。' : '接口未返回最新复盘。'}</p>
      <h3>当前错误摘要</h3><div id="mistake-error-summary" class="message-content mistake-review-content"></div>`}
    <div class="detail-actions">
      <button type="button" data-review-mistake="${escapeHtml(record.id)}">开始复习</button>
      <button type="button" data-master-mistake="${escapeHtml(record.id)}" ${record.status === '已掌握' ? 'disabled' : ''}>标记已掌握</button>
      <button type="button" class="mistake-delete-button" data-delete-mistake="${escapeHtml(record.id)}">删除错题</button>
      <button type="button" data-close-detail>关闭</button>
    </div>
    <p class="mistake-history-hint">学习状态操作针对这道题，不会修改所选历史复盘。</p>
  `
  if (review) {
    renderMarkdownInto(document.getElementById('mistake-fail-reason'), review.failReason || '暂无记录')
    renderMarkdownInto(document.getElementById('mistake-fail-case'), review.failCase || '暂无记录')
    renderMarkdownInto(document.getElementById('mistake-ai-analysis'), review.aiAnalysis || '暂无记录')
    renderMarkdownInto(document.getElementById('mistake-action-plan'), review.actionPlan || '暂无记录')
  } else {
    renderMarkdownInto(document.getElementById('mistake-error-summary'), record.errorSummary || '暂无记录')
  }
  panel.scrollTop = scrollTop
  const historyList = panel.querySelector('.mistake-history-list')
  if (historyList) historyList.scrollTop = historyScrollTop
  if (state.mistakeActionBusy) setMistakeActionBusy(true)
}

function selectMistakeReview(index) {
  if (state.mistakeActionBusy || !state.mistakeDetail || !Number.isInteger(index)
    || index < 0 || index >= state.mistakeReviews.length) return
  state.selectedMistakeReview = index
  if (index >= MISTAKE_HISTORY_PREVIEW_SIZE) state.mistakeHistoryExpanded = true
  renderMistakeDetail()
  elements['mistake-detail'].querySelector(`[data-select-mistake-review="${index}"]`)?.focus({ preventScroll: true })
}

function toggleMistakeHistory() {
  if (state.mistakeActionBusy || !state.mistakeDetail) return
  state.mistakeHistoryExpanded = !state.mistakeHistoryExpanded
  if (!state.mistakeHistoryExpanded && state.selectedMistakeReview >= MISTAKE_HISTORY_PREVIEW_SIZE) {
    state.selectedMistakeReview = 0
  }
  renderMistakeDetail()
  const historyList = elements['mistake-detail'].querySelector('.mistake-history-list')
  if (historyList) historyList.scrollTop = 0
  elements['mistake-detail'].querySelector('[data-toggle-mistake-history]')?.focus({ preventScroll: true })
}

async function showMistakeDetail(id) {
  const version = ++mistakeDetailVersion
  state.mistakeDetail = null
  const panel = elements['mistake-detail']
  panel.classList.remove('hidden')
  panel.innerHTML = '<button class="icon-button" data-close-detail>×</button><p>正在加载详情…</p>'
  try {
    const detail = await mistakeRequest('detail', { params: { userId: state.userId, mistakeId: id } })
    if (version !== mistakeDetailVersion || state.activeView !== 'mistake-book') return
    if (!detail?.mistake) throw new Error('错题详情为空')
    state.mistakeDetail = detail
    state.mistakeReviews = normalizeMistakeReviews(detail)
    state.selectedMistakeReview = 0
    state.mistakeHistoryExpanded = false
    renderMistakeDetail()
    panel.scrollTop = 0
  } catch (error) {
    if (version !== mistakeDetailVersion) return
    panel.innerHTML = '<button class="icon-button" data-close-detail>×</button><p class="detail-text">'
      + escapeHtml(error.message) + '</p>'
  }
}

function setMistakeActionBusy(busy) {
  state.mistakeActionBusy = busy
  for (const button of elements['mistake-detail'].querySelectorAll('button')) {
    button.disabled = busy || (button.hasAttribute('data-master-mistake') && state.mistakeDetail?.mistake.status === '已掌握')
  }
}

async function startMistakeReview(id) {
  if (state.mistakeActionBusy) return
  const record = state.mistakeDetail?.mistake
  if (!record || String(record.id) !== String(id)) return
  if (!/^[a-zA-Z0-9_-]+$/.test(record.problemSlug || '')) throw new Error('题目 slug 无效，无法跳转力扣')
  setMistakeActionBusy(true)
  try {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true })
    if (!tab?.id) throw new Error('未找到当前浏览器标签页')
    await mistakeRequest('start_review', { method: 'POST', body: { userId: state.userId, mistakeId: record.id } })
    // 跳转使用 problemSlug，而不是数据库主键 id。
    await chrome.tabs.update(tab.id, { url: 'https://leetcode.cn/problems/' + encodeURIComponent(record.problemSlug) + '/' })
    setMistakeActionBusy(false)
    closeMistakeDetail()
    mistakeNotice('已开始复习，侧边栏仍停留在错题本。')
    await loadMistakeBook()
  } finally {
    setMistakeActionBusy(false)
  }
}

async function markMistakeMastered(id) {
  if (state.mistakeActionBusy) return
  setMistakeActionBusy(true)
  try {
    await mistakeRequest('mark_mastered', { method: 'POST', body: { userId: state.userId, mistakeId: id } })
    mistakeNotice('已标记掌握，复习次数已更新。')
    await loadMistakeBook()
    await showMistakeDetail(id)
  } finally {
    setMistakeActionBusy(false)
  }
}

async function deleteMistake(id) {
  if (state.mistakeActionBusy) return
  const record = state.mistakeDetail?.mistake
  if (!record || String(record.id) !== String(id)) return
  if (!window.confirm(`确定删除「${record.problemId}. ${record.title}」吗？\n\n将删除这道错题及全部复盘历史，无法恢复。`)) return
  setMistakeActionBusy(true)
  try {
    const deleted = await mistakeRequest('delete', {
      method: 'POST', body: { userId: state.userId, mistakeId: record.id },
    })
    if (deleted !== true) throw new Error('服务未确认删除成功，请刷新后确认')
    // 删除成功才关闭详情；失败时保留内容，允许用户重试。
    if (String(state.reviewFeedback?.id) === String(record.id)) state.reviewFeedback = null
    setMistakeActionBusy(false)
    closeMistakeDetail()
    mistakeNotice('错题及全部复盘历史已删除。')
    // 沿用现有分页回退逻辑：末页最后一题删除后返回有效页码。
    await loadMistakeBook()
  } finally {
    setMistakeActionBusy(false)
  }
}

async function showMistakeForm(generatedReview = null) {
  if (state.mistakeActionBusy || state.busy) {
    mistakeNotice('请等待当前操作完成后再添加错题。', true)
    return
  }
  const version = ++mistakeDetailVersion
  const panel = elements['mistake-detail']
  state.mistakeDetail = null
  panel.classList.remove('hidden')
  panel.innerHTML = '<button class="icon-button" data-close-detail>×</button><p>正在读取当前题目…</p>'
  try {
    const context = generatedReview ? { problem: generatedReview.candidate.payload.problem } : await readLeetCodeContext(false)
    if (version !== mistakeDetailVersion || state.activeView !== 'mistake-book') return
    const problem = context.problem
    // 同一会话可讨论多道题，只用明确标记属于该题的回答预填分析。
    const sameProblem = state.problem?.slug === problem.slug
    const draft = generatedReview?.draft || {}
    const analysis = generatedReview ? draft.aiAnalysis || '' : sameProblem ? [...state.messages].reverse().find((message) =>
      message.role === 'assistant' && message.problemSlug === problem.slug && message.meta && !message.extraClass && message.content)?.content || '' : ''
    const sessionId = generatedReview ? draft.sourceSessionId || '' : sameProblem ? state.sessionId || '' : ''
    const difficulty = { Easy: '简单', Medium: '中等', Hard: '困难' }[problem.difficulty] || problem.difficulty
    panel.innerHTML = `
      <div class="mistake-detail-header"><h2>${generatedReview ? '确认提交复盘' : '添加当前题'}</h2><button class="icon-button" data-close-detail>×</button></div>
      <form id="mistake-save-form" class="mistake-form" data-review-key="${escapeHtml(generatedReview?.candidate.key || '')}">
        <label>题号<input name="problemId" required maxlength="64" value="${escapeHtml(problem.frontendId)}"></label>
        <label>题目 slug<input name="problemSlug" required maxlength="128" value="${escapeHtml(problem.slug)}"></label>
        <label>题目名称<input name="title" required maxlength="200" value="${escapeHtml(problem.title)}"></label>
        <label>难度<select name="difficulty">${['简单', '中等', '困难'].map((item) => '<option ' + (item === difficulty ? 'selected' : '') + '>' + item + '</option>').join('')}</select></label>
        <label>错误摘要<textarea name="errorSummary" required placeholder="列表中展示的主要错因">${escapeHtml(draft.errorSummary || '')}</textarea></label>
        <label>标签（逗号分隔）<input name="tags" maxlength="500" value="${escapeHtml((problem.tags || []).join(','))}"></label>
        <label>失败原因<textarea name="failReason">${escapeHtml(draft.failReason || '')}</textarea></label>
        <label>失败用例 / 判题结果<textarea name="failCase">${escapeHtml(draft.failCase || generatedReview?.candidate.payload.failCase || '')}</textarea></label>
        <label>分析（可编辑）<textarea name="aiAnalysis" rows="6">${escapeHtml(analysis)}</textarea></label>
        <label>改进方案<textarea name="actionPlan">${escapeHtml(draft.actionPlan || '')}</textarea></label>
        <input type="hidden" name="sourceSessionId" value="${escapeHtml(sessionId)}">
        <p class="form-hint">${generatedReview ? '以下是本次失败提交的复盘草稿，确认保存前不会入库。' : '手动入口不调用 Agent，已有对话分析仅作草稿。'}同一道题重复保存会追加复盘并重置为待复习。</p>
        <p id="mistake-form-error" class="mistake-form-error" role="alert"></p>
        <div class="detail-actions"><button type="submit">确认保存</button><button type="button" data-close-detail>取消</button></div>
      </form>
    `
    state.reviewEditingKey = generatedReview?.candidate.key || ''
    renderSubmissionReviewBanner()
    panel.scrollTop = 0
  } catch (error) {
    if (version !== mistakeDetailVersion) return
    panel.innerHTML = '<button class="icon-button" data-close-detail>×</button><p>' + escapeHtml(error.message) + '</p>'
  }
}

async function saveMistakeForm(form) {
  if (state.mistakeActionBusy) return
  const data = Object.fromEntries(new FormData(form).entries())
  const review = form.dataset?.reviewKey && state.pendingReview?.candidate.key === form.dataset.reviewKey
    ? state.pendingReview : null
  if (!data.failReason.trim()) data.failReason = data.errorSummary
  setMistakeActionBusy(true)
  try {
    const id = await mistakeRequest('save', { method: 'POST', body: { ...data, userId: state.userId } })
    if (id === null || id === undefined) throw new Error('保存返回的错题 ID 为空，请刷新确认')
    if (review) await finishGeneratedReviewSave(review, id)
    state.mistakeFilter = 'all'
    state.mistakeQuery = ''
    state.mistakePage = 1
    elements['mistake-search-input'].value = ''
    mistakeNotice('错题已保存。')
    await loadMistakeBook()
    await showMistakeDetail(id)
  } catch (error) {
    const hint = document.getElementById('mistake-form-error')
    if (hint) hint.textContent = error.message
    else mistakeNotice(error.message, true)
  } finally {
    setMistakeActionBusy(false)
  }
}


// 失败提交 → 自动生成草稿 → 用户确认保存。只处理当前标签页的候选，绝不自动入库。
let reviewCandidateVersion = 0

function verdictLabel(verdict) {
  return { WRONG_ANSWER: '答案错误', TIME_LIMIT_EXCEEDED: '超出时间限制',
    MEMORY_LIMIT_EXCEEDED: '超出内存限制', OUTPUT_LIMIT_EXCEEDED: '超出输出限制',
    RUNTIME_ERROR: '运行错误', COMPILE_ERROR: '编译错误' }[verdict] || verdict
}

function renderSubmissionReviewBanner() {
  const banner = elements['submission-review-banner']
  const review = state.pendingReview
  const feedback = state.reviewFeedback
  banner.classList.toggle('hidden', !review && !feedback)
  banner.classList.toggle('error', review?.status === 'error')
  if (!review) {
    banner.innerHTML = feedback ? '<strong>' + escapeHtml(feedback.message)
      + '</strong><div class="detail-actions"><button data-open-saved-review="' + escapeHtml(feedback.id)
      + '">查看错题</button><button data-dismiss-review-feedback>关闭</button></div>' : ''
    return
  }
  const payload = review.candidate.payload
  const editing = state.reviewEditingKey === review.candidate.key
  const statusText = review.status === 'loading' ? '正在生成复盘…'
    : review.status === 'saving' ? '正在保存…'
    : review.status === 'error' ? '复盘生成失败' : editing ? '正在编辑草稿，请在表单中确认保存' : '复盘已生成，确认后加入错题本'
  const busy = ['loading', 'saving'].includes(review.status)
  banner.innerHTML = `
    <strong>${escapeHtml(payload.problem.title)} · ${escapeHtml(verdictLabel(payload.verdict))}</strong>
    <p>${escapeHtml(statusText)}</p>
    <p>${escapeHtml(review.status === 'error' ? review.error : review.draft?.errorSummary || '分析当次提交代码和判题结果，不会自动保存。')}</p>
    <div class="detail-actions">
      ${review.status === 'ready' && !editing ? '<button data-confirm-generated-review>确认保存</button><button data-preview-generated-review>查看 / 编辑</button>' : ''}
      ${review.status === 'error' ? '<button data-retry-generated-review>重试分析</button><button data-manual-generated-review>手动填写</button>' : ''}
      <button data-ignore-generated-review ${review.status === 'saving' ? 'disabled' : ''}>${busy ? '取消 / 忽略' : '忽略'}</button>
    </div>
  `
}

async function refreshPendingSubmissionReview() {
  if (state.mistakeActionBusy) return
  const version = ++reviewCandidateVersion
  try {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true })
    if (version !== reviewCandidateVersion || !tab?.id) return
    const result = await chrome.runtime.sendMessage({ type: 'GODE_GET_PENDING_REVIEW', tabId: tab.id })
    if (version !== reviewCandidateVersion) return
    const candidate = result?.candidate
    if (!candidate || candidate.key === state.pendingReview?.candidate.key) {
      if (state.pendingReview && state.pendingReview.candidate.tabId !== tab.id) {
        state.reviewAbortController?.abort()
        state.pendingReview = null
        renderSubmissionReviewBanner()
      }
      return
    }
    state.reviewAbortController?.abort()
    const review = { candidate, status: 'loading', draft: null, error: '' }
    state.pendingReview = review
    state.reviewFeedback = null
    renderSubmissionReviewBanner()
    void analyzePendingSubmission(review)
  } catch (error) {
    console.warn('[Gode] 获取待复盘提交失败', error.message)
  }
}

async function analyzePendingSubmission(review) {
  state.reviewAbortController?.abort()
  const controller = new AbortController()
  state.reviewAbortController = controller
  review.status = 'loading'
  review.error = ''
  renderSubmissionReviewBanner()
  try {
    if (!state.userId) await settingsReady
    const payload = review.candidate.payload
    const problem = payload.problem
    const draft = await mistakeRequest('analyze_submission', {
      method: 'POST', signal: controller.signal, timeoutMs: 120000,
      body: {
        agentId: state.settings.agentId, userId: state.userId,
        problemId: problem.frontendId, problemSlug: problem.slug,
        title: problem.title, difficulty: problem.difficulty, tags: (problem.tags || []).join(','),
        problemDescription: problem.description, submittedCode: payload.submittedCode,
        language: payload.language, verdict: payload.verdict, failCase: payload.failCase,
        sourceSessionId: state.problem?.slug === problem.slug ? state.sessionId : null,
      },
    })
    if (state.pendingReview !== review || controller.signal.aborted) return
    if (!draft || draft.problemSlug !== problem.slug
        || ['errorSummary', 'failReason', 'aiAnalysis', 'actionPlan'].some((field) => typeof draft[field] !== 'string' || !draft[field].trim())) {
      throw new Error('模型返回的复盘草稿不完整，请重试或手动填写')
    }
    review.draft = draft
    review.status = 'ready'
  } catch (error) {
    if (state.pendingReview !== review || controller.signal.aborted) return
    review.status = 'error'
    review.error = error.message
  } finally {
    if (state.pendingReview === review) renderSubmissionReviewBanner()
  }
}

async function acknowledgeReviewCandidate(review) {
  const response = await chrome.runtime.sendMessage({
    type: 'GODE_ACK_REVIEW_CANDIDATE', key: review.candidate.key, tabId: review.candidate.tabId,
  })
  if (!response?.success) throw new Error('清理提交提示失败')
}

async function finishGeneratedReviewSave(review, id) {
  try { await acknowledgeReviewCandidate(review) } catch (error) { console.warn('[Gode]', error.message) }
  if (state.pendingReview === review) state.pendingReview = null
  state.reviewEditingKey = ''
  state.reviewFeedback = { id, message: '复盘已加入错题本，状态为待复习。' }
  renderSubmissionReviewBanner()
}

async function confirmGeneratedReview() {
  const review = state.pendingReview
  if (!review || review.status !== 'ready' || state.mistakeActionBusy || state.reviewEditingKey === review.candidate.key) return
  review.status = 'saving'
  setMistakeActionBusy(true)
  renderSubmissionReviewBanner()
  try {
    const id = await mistakeRequest('save', { method: 'POST', body: { ...review.draft, userId: state.userId } })
    if (id === null || id === undefined) throw new Error('保存未返回错题 ID，请刷新确认')
    await finishGeneratedReviewSave(review, id)
    await loadMistakeBook()
  } catch (error) {
    // 保留已生成草稿；保存失败只重试保存，不重复调用模型。
    review.status = 'ready'
    state.reviewFeedback = null
    renderSubmissionReviewBanner()
    const hint = document.createElement('p')
    hint.textContent = error.message
    elements['submission-review-banner'].appendChild(hint)
  } finally {
    setMistakeActionBusy(false)
  }
}

async function ignoreGeneratedReview() {
  const review = state.pendingReview
  if (!review || review.status === 'saving') return
  state.reviewAbortController?.abort()
  await acknowledgeReviewCandidate(review)
  if (document.getElementById('mistake-save-form')?.dataset.reviewKey === review.candidate.key) closeMistakeDetail()
  if (state.pendingReview === review) state.pendingReview = null
  renderSubmissionReviewBanner()
}

async function previewGeneratedReview(manual = false) {
  const review = state.pendingReview
  if (!review || state.mistakeActionBusy) return
  if (!manual && review.status !== 'ready') return
  setActiveView('mistake-book')
  await showMistakeForm(review)
}

elements['refresh-button'].addEventListener('click', () => void loadCurrentProblem())
elements['settings-button'].addEventListener('click', () => {
  if (state.activeView !== 'chat') setActiveView('chat')
  elements['settings-panel'].classList.toggle('hidden')
  if (elements['settings-panel'].classList.contains('hidden')) {
    cancelNotesConfiguration()
    elements['leetcode-session-cookie'].value = ''
  } else {
    void updateNotesConfiguration('status')
  }
})
elements['close-settings'].addEventListener('click', () => {
  cancelNotesConfiguration()
  elements['leetcode-session-cookie'].value = ''
  elements['settings-panel'].classList.add('hidden')
})
elements['save-settings'].addEventListener('click', () => void saveSettings())
elements['save-notes-cookie'].addEventListener('click', () => void updateNotesConfiguration('save'))
elements['clear-notes-cookie'].addEventListener('click', () => void updateNotesConfiguration('clear'))
elements['new-chat-button'].addEventListener('click', () => void startNewChat())
elements['history-chat-button'].addEventListener('click', () => {
  elements['session-drawer'].classList.remove('hidden')
  void refreshSessionList()
})
elements['close-session-drawer'].addEventListener('click', () => elements['session-drawer'].classList.add('hidden'))
elements['session-refresh'].addEventListener('click', () => void refreshSessionList())
elements['session-list-more'].addEventListener('click', () => void refreshSessionList(true))
elements['load-older-messages'].addEventListener('click', () => void loadOlderMessages())
elements['session-list'].addEventListener('click', (event) => {
  const deleted = event.target.closest('[data-delete-session]')
  if (deleted) return void deleteConversation(deleted.dataset.deleteSession)
  const selected = event.target.closest('[data-switch-session]')
  if (selected) void switchConversation(selected.dataset.switchSession)
})
elements['send-button'].addEventListener('click', () => {
  if (state.busy) {
    cancelGeneration()
    return
  }
  void sendCurrentMessage()
})
elements['message-input'].addEventListener('keydown', (event) => {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    void sendCurrentMessage()
  }
})
elements['quick-prompts'].addEventListener('click', (event) => {
  const button = event.target.closest('button')
  if (!button || button.disabled || state.busy || state.problemPending) return
  elements['message-input'].value = button.dataset.prompt || button.textContent
  elements['message-input'].focus()
})
elements['chat-tab'].addEventListener('click', () => setActiveView('chat'))
elements['mistake-book-tab'].addEventListener('click', () => setActiveView('mistake-book'))
elements['mistake-search-input'].addEventListener('input', (event) => {
  state.mistakeQuery = event.target.value
  state.mistakePage = 1
  closeMistakeDetail()
  // 输入即作废旧响应，查询延迟 300ms，避免每个字都请求。
  mistakeListVersion++
  mistakeListController?.abort()
  clearTimeout(mistakeSearchTimer)
  state.mistakeLoading = true
  renderMistakeBook()
  mistakeSearchTimer = setTimeout(() => void loadMistakeBook(), 300)
})
elements['mistake-filters'].addEventListener('click', (event) => {
  const button = event.target.closest('button[data-filter]')
  if (!button || state.mistakeActionBusy) return
  clearTimeout(mistakeSearchTimer)
  state.mistakeFilter = button.dataset.filter || 'all'
  state.mistakePage = 1
  closeMistakeDetail()
  void loadMistakeBook()
})
elements['mistake-prev-page'].addEventListener('click', () => {
  if (state.mistakeLoading || state.mistakePage <= 1) return
  state.mistakePage--
  void loadMistakeBook()
})
elements['mistake-next-page'].addEventListener('click', () => {
  if (state.mistakeLoading || state.mistakePage >= state.mistakeTotalPages) return
  state.mistakePage++
  void loadMistakeBook()
})
elements['mistake-refresh'].addEventListener('click', () => {
  if (state.mistakeActionBusy) return
  closeMistakeDetail()
  mistakeNotice()
  void loadMistakeBook()
})
elements['mistake-add-current'].addEventListener('click', () => void showMistakeForm())
elements['mistake-book-list'].addEventListener('click', (event) => {
  if (event.target.closest('[data-retry-mistakes]')) return void loadMistakeBook()
  const card = event.target.closest('[data-mistake-id]')
  if (card) void showMistakeDetail(card.dataset.mistakeId)
})
elements['mistake-detail'].addEventListener('click', (event) => {
  if (state.mistakeActionBusy) return
  if (event.target.closest('[data-close-detail]')) return closeMistakeDetail()
  const history = event.target.closest('[data-select-mistake-review]')
  if (history) return selectMistakeReview(Number(history.dataset.selectMistakeReview))
  if (event.target.closest('[data-toggle-mistake-history]')) return toggleMistakeHistory()
  const review = event.target.closest('[data-review-mistake]')
  const mastered = event.target.closest('[data-master-mistake]')
  const deleted = event.target.closest('[data-delete-mistake]')
  const action = review ? startMistakeReview(review.dataset.reviewMistake)
    : mastered ? markMistakeMastered(mastered.dataset.masterMistake)
      : deleted ? deleteMistake(deleted.dataset.deleteMistake) : null
  if (action) void action.catch((error) => {
    mistakeNotice(error.message, true)
    // 详情覆盖列表时，也必须能看到失败提示。
    const panel = elements['mistake-detail']
    if (!panel.classList.contains('hidden')) {
      panel.querySelector('.mistake-action-error')?.remove()
      const hint = document.createElement('p')
      hint.className = 'mistake-form-error mistake-action-error'
      hint.textContent = error.message
      panel.appendChild(hint)
    }
  })
})
elements['mistake-detail'].addEventListener('submit', (event) => {
  if (event.target.id !== 'mistake-save-form') return
  event.preventDefault()
  void saveMistakeForm(event.target)
})

chrome.runtime.onMessage.addListener((message) => {
  if (message?.type === 'GODE_REVIEW_CANDIDATE') {
    void refreshPendingSubmissionReview()
    return
  }
  if (message?.type === 'GODE_ACTIVE_TAB_CHANGED' || message?.type === 'GODE_LEETCODE_ROUTE_CHANGED') {
    void saveConversation()
    if (state.sessionBusy) invalidateSuggestions()
    else resetChatContext()
    state.problemLoadVersion++
    state.problemPending = true
    renderSuggestions()
    window.setTimeout(() => void loadCurrentProblem(), 250)
    void refreshPendingSubmissionReview()
  }
})

elements['submission-review-banner'].addEventListener('click', (event) => {
  let action
  if (event.target.closest('[data-confirm-generated-review]')) action = confirmGeneratedReview()
  else if (event.target.closest('[data-preview-generated-review]')) action = previewGeneratedReview()
  else if (event.target.closest('[data-manual-generated-review]')) action = previewGeneratedReview(true)
  else if (event.target.closest('[data-retry-generated-review]') && state.pendingReview) action = analyzePendingSubmission(state.pendingReview)
  else if (event.target.closest('[data-ignore-generated-review]')) action = ignoreGeneratedReview()
  else if (event.target.closest('[data-dismiss-review-feedback]')) {
    state.reviewFeedback = null
    renderSubmissionReviewBanner()
  } else {
    const saved = event.target.closest('[data-open-saved-review]')
    if (saved && !state.mistakeActionBusy) {
      setActiveView('mistake-book')
      action = showMistakeDetail(saved.dataset.openSavedReview)
    }
  }
  if (action) void action.catch((error) => {
    const hint = document.createElement('p')
    hint.textContent = error.message
    elements['submission-review-banner'].appendChild(hint)
  })
})

void (async () => {
  settingsReady = loadSettings()
  await settingsReady
  void loadMistakeBook()
  await restoreActiveConversation()
  await loadCurrentProblem()
  void refreshPendingSubmissionReview()
  elements['message-input'].focus()
})()
