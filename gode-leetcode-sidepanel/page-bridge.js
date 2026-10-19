window.addEventListener('message', (event) => {
  if (event.source !== window) return

  if (event.data?.type === 'GODE_READ_LEETCODE_EDITOR') {
    let code = ''
    try {
      const models = window.monaco?.editor?.getModels?.() ?? []
      const model = models.find((item) => item.getLanguageId?.() !== 'plaintext') ?? models[0]
      code = model?.getValue?.() ?? ''
    } catch {
      code = ''
    }

    window.postMessage({
      type: 'GODE_LEETCODE_EDITOR_RESULT',
      requestId: event.data.requestId,
      code,
    }, '*')
    return
  }

  if (event.data?.type === 'GODE_WRITE_LEETCODE_EDITOR') {
    try {
      const models = window.monaco?.editor?.getModels?.() ?? []
      const model = models.find((item) => item.getLanguageId?.() !== 'plaintext') ?? models[0]
      if (!model) throw new Error('没有找到力扣代码编辑器')

      const code = typeof event.data.code === 'string' ? event.data.code : ''
      if (!code.trim()) throw new Error('不能应用空代码')

      model.pushStackElement?.()
      if (model.pushEditOperations && model.getFullModelRange) {
        model.pushEditOperations([], [{
          range: model.getFullModelRange(),
          text: code,
          forceMoveMarkers: true,
        }], () => null)
      } else {
        model.setValue(code)
      }
      model.pushStackElement?.()

      window.postMessage({
        type: 'GODE_LEETCODE_EDITOR_WRITE_RESULT',
        requestId: event.data.requestId,
        success: true,
      }, '*')
    } catch (error) {
      window.postMessage({
        type: 'GODE_LEETCODE_EDITOR_WRITE_RESULT',
        requestId: event.data.requestId,
        success: false,
        message: error instanceof Error ? error.message : '写入力扣编辑器失败',
      }, '*')
    }
  }
})
