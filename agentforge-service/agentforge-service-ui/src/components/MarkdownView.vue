<template>
  <div class="markdown-body" v-html="html"></div>
</template>

<script>
import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js'

const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
  highlight(str, lang) {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return `<pre class="hljs"><code>${hljs.highlight(str, { language: lang }).value}</code></pre>`
      } catch (e) {
        /* ignore */
      }
    }
    return `<pre class="hljs"><code>${md.utils.escapeHtml(str)}</code></pre>`
  }
})

const defaultLinkOpen = md.renderer.rules.link_open || function (tokens, idx, options, env, self) {
  return self.renderToken(tokens, idx, options)
}
md.renderer.rules.link_open = function (tokens, idx, options, env, self) {
  tokens[idx].attrSet('target', '_blank')
  tokens[idx].attrSet('rel', 'noopener noreferrer')
  return defaultLinkOpen(tokens, idx, options, env, self)
}

export default {
  name: 'MarkdownView',
  props: {
    content: { type: String, default: '' }
  },
  computed: {
    html() {
      return md.render(this.content || '')
    }
  }
}
</script>

<style>
.markdown-body {
  font-size: 14px;
  line-height: 1.72;
  color: #24292f;
  word-break: break-word;
}
.markdown-body > *:first-child { margin-top: 0; }
.markdown-body > *:last-child { margin-bottom: 0; }
.markdown-body p { margin: 8px 0; }
.markdown-body h1, .markdown-body h2, .markdown-body h3, .markdown-body h4 {
  margin: 16px 0 8px;
  font-weight: 600;
  line-height: 1.3;
}
.markdown-body h1 { font-size: 20px; border-bottom: 1px solid #eaecef; padding-bottom: 6px; }
.markdown-body h2 { font-size: 18px; border-bottom: 1px solid #eaecef; padding-bottom: 5px; }
.markdown-body h3 { font-size: 16px; }
.markdown-body table { border-collapse: collapse; width: 100%; margin: 10px 0; display: block; overflow-x: auto; }
.markdown-body th, .markdown-body td { border: 1px solid #dfe2e5; padding: 6px 10px; }
.markdown-body th { background: #f6f8fa; font-weight: 600; }
.markdown-body blockquote { margin: 8px 0; padding: 4px 12px; color: #6a737d; border-left: 4px solid #dfe2e5; background: #fafbfc; }
.markdown-body code { background: rgba(27, 31, 35, 0.06); border-radius: 4px; padding: 2px 5px; font-family: 'SFMono-Regular', Consolas, monospace; font-size: 12.5px; }
.markdown-body pre { background: #f6f8fa; border-radius: 8px; padding: 12px; overflow: auto; margin: 10px 0; }
.markdown-body pre code { background: transparent; padding: 0; font-size: 12.5px; }
.markdown-body ul, .markdown-body ol { padding-left: 22px; margin: 8px 0; }
.markdown-body li { margin: 3px 0; }
.markdown-body a { color: #0969da; text-decoration: none; }
.markdown-body a:hover { text-decoration: underline; }
.markdown-body img { max-width: 100%; }
.markdown-body hr { border: 0; border-top: 1px solid #eaecef; margin: 14px 0; }
</style>
