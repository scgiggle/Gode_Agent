const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const source = fs.readFileSync(path.join(__dirname, '..', 'sidepanel.js'), 'utf8')
const sandbox = { URL, document: { getElementById: () => ({}), querySelector: () => ({}) } }
vm.createContext(sandbox)
// 单独运行实际渲染器，不执行浏览器事件注册或启动请求。
vm.runInContext(source.slice(0, source.indexOf('\nfunction addApplyButtons')), sandbox)
const render = (input) => {
  sandbox.input = input
  return vm.runInContext('markdownToHtml(input)', sandbox)
}

test('截图中的java首行粘连正确入块，解释和后续text用例不被吞进代码块', () => {
  const input = [
    '你的代码执行顺序是：', '', '```javamap.put(nums[i], i);', '',
    'int complement = target - nums[i];', 'if (map.containsKey(complement)) {',
    '    return new int[]{map.get(complement), i};', '}', '```', '',
    '当前元素已经被放进 `map`，所以可能查询到自己。', '', '例如：', '',
    '```textnums = [3,3]', 'target = 6```', '', '必须使用两个不同下标。',
  ].join('\n')
  const html = render(input)
  assert.equal((html.match(/<pre /g) || []).length, 2)
  assert.ok(html.includes('<pre data-language="java"><span>java</span><code>map.put(nums[i], i);'))
  assert.ok(html.includes('int complement = target - nums[i];\nif (map.containsKey(complement)) {'))
  assert.ok(html.includes('}</code></pre><p>当前元素已经被放进 <code>map</code>'))
  assert.ok(html.includes('<p>例如：</p><pre data-language="text"><code>nums = [3,3]\ntarget = 6</code></pre>'))
  assert.ok(html.endsWith('<p>必须使用两个不同下标。</p>'))
  assert.ok(!html.includes('```'))
})

test('标准带语言和无语言代码块保持换行与缩进，行内代码正常渲染', () => {
  assert.equal(render('```java\nif (ok) {\n    return 1;\n}\n```\n\n解释 `ok`。'),
    '<pre data-language="java"><span>java</span><code>if (ok) {\n    return 1;\n}</code></pre><p>解释 <code>ok</code>。</p>')
  assert.equal(render('```\nconsole.log(1);\n```'), '<pre data-language=""><code>console.log(1);</code></pre>')
  assert.ok(render('```ruby\nputs 1\n```').includes('data-language="ruby"'))
})

test('单行围栏、语言与代码间空格、最后一行闭合围栏均兼容', () => {
  assert.equal(render('```textnums = [3,3]```'), '<pre data-language="text"><code>nums = [3,3]</code></pre>')
  assert.ok(render('```java map.put(3, 0);```').includes('<code>map.put(3, 0);</code>'))
  assert.ok(render('```java\nreturn 1;```\n\n后续文字').endsWith('<code>return 1;</code></pre><p>后续文字</p>'))
  assert.ok(render('```text\nnums = [3,3]\ntarget = 6```').includes('<code>nums = [3,3]\ntarget = 6</code>'))
})

test('无标签单行代码不被误识别为单字母c语言，c++与c#标记完整保留', () => {
  assert.equal(render('```console.log(1);```'), '<pre data-language=""><code>console.log(1);</code></pre>')
  assert.ok(render('```c++\nreturn 1;\n```').includes('data-language="c++"'))
  assert.ok(render('```c#\nreturn 1;\n```').includes('data-language="c#"'))
})

test('四反引号与波浪线围栏兼容，较短围栏及另一种围栏留在代码内', () => {
  assert.ok(render('````markdown\n```java\nreturn 1;\n```\n````\n解释')
    .includes('<code>```java\nreturn 1;\n```</code></pre><p>解释</p>'))
  assert.equal(render('~~~text\n```\nexample\n~~~'), '<pre data-language="text"><code>```\nexample</code></pre>')
})

test('代码中的HTML和危险脚本始终转义，引用字符串里的反引号不会提前闭合', () => {
  const html = render('```javascriptconst x = "```";\n<img src=x onerror=alert(1)>\n```\n<script>alert(1)</script>')
  assert.ok(html.includes('<code>const x = &quot;```&quot;;\n&lt;img src=x onerror=alert(1)&gt;</code>'))
  assert.ok(html.endsWith('<p>&lt;script&gt;alert(1)&lt;/script&gt;</p>'))
  assert.ok(!html.includes('<img') && !html.includes('<script'))
})

test('流式每个前缀均可渲染，最终闭合后说明恢复为普通段落，不改写原文', () => {
  const input = '```javamap.put(nums[i], i);\nreturn 1;```\n\n检查 `i`。'
  for (let end = 1; end <= input.length; end++) assert.equal(typeof render(input.slice(0, end)), 'string')
  assert.ok(render('```javamap.put(nums[i], i);\nreturn 1;').includes('<code>map.put(nums[i], i);\nreturn 1;</code>'))
  assert.ok(render(input).endsWith('</code></pre><p>检查 <code>i</code>。</p>'))
  assert.equal(sandbox.input, input)
})
