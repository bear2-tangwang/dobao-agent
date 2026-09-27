import { Marked } from 'marked'
import { markedHighlight } from 'marked-highlight'
import hljs from 'highlight.js'
import DOMPurify from 'dompurify'
import type { Reference } from '@/types'

/** 配置 Markdown(解析期完成代码高亮,无需渲染后再高亮处理) */
const marked = new Marked(
  markedHighlight({
    langPrefix: 'hljs language-',
    highlight(code, lang) {
      if (lang && hljs.getLanguage(lang)) {
        try {
          return hljs.highlight(code, { language: lang }).value
        } catch (err) {
          // 忽略单个语言高亮失败,回退自动检测
        }
      }
      return hljs.highlightAuto(code).value
    }
  })
)

marked.setOptions({ breaks: true, gfm: true })

/** 渲染 Markdown(带 DOMPurify XSS 防护) */
export const renderMarkdown = (content: string): string => {
  if (!content) return ''

  // 处理各种换行符
  const processedContent = content
    .replace(/\\n/g, '\n')
    .replace(/\\r\\n/g, '\n')
    .replace(/\\r/g, '\n')

  return DOMPurify.sanitize(marked.parse(processedContent) as string)
}

/** 处理参考来源数据(统一多种后端格式) */
export const processReferences = (refsData: unknown): Reference[] => {
  if (!refsData) return []

  let references: unknown = refsData

  // 如果是字符串,尝试解析
  if (typeof references === 'string') {
    try {
      references = JSON.parse(references)
    } catch (e) {
      return []
    }
  }

  // 检查嵌套格式 {data: {content: "..."}}
  const dataObj = references as { data?: { content?: unknown } }
  if (references && dataObj.data && dataObj.data.content) {
    const contentData = dataObj.data.content
    if (typeof contentData === 'string') {
      try {
        references = JSON.parse(contentData)
      } catch (e) {
        return []
      }
    } else {
      references = contentData
    }
  }

  // 检查后端返回的格式 {type: 'reference', content: "[...]"}
  const refObj = references as { type?: string; content?: unknown }
  if (references && refObj.type === 'reference' && typeof refObj.content === 'string') {
    try {
      references = JSON.parse(refObj.content)
    } catch (e) {
      return []
    }
  }

  // 确保是数组
  if (!Array.isArray(references)) {
    return []
  }

  // 转换为统一格式
  return references
    .filter((ref: unknown) => ref != null)
    .map((ref: unknown): Reference => {
      let linkUrl: string | undefined
      let displayTitle: string | undefined
      let content = ''

      if (typeof ref === 'string') {
        try {
          const parsed = JSON.parse(ref) as { url?: string; link?: string; title?: string; content?: string }
          linkUrl = parsed.url || parsed.link
          displayTitle = parsed.title || parsed.url || parsed.link || '无标题'
          content = parsed.content || ''
        } catch {
          linkUrl = ref
          displayTitle = ref
          content = ''
        }
      } else if (typeof ref === 'object' && ref !== null) {
        const obj = ref as { url?: string; link?: string; title?: string; content?: string }
        linkUrl = obj.url || obj.link
        displayTitle = obj.title || obj.url || obj.link || '无标题'
        content = obj.content || ''
      }

      // 确保 URL 是绝对路径
      if (linkUrl && !linkUrl.startsWith('http://') && !linkUrl.startsWith('https://')) {
        linkUrl = 'https://' + linkUrl
      }

      return { url: linkUrl!, title: displayTitle!, content }
    })
    .filter((ref: Reference) => ref.url)
}

/** 处理推荐问题数据 */
export const processRecommendations = (recommendData: unknown): string[] => {
  if (!recommendData) return []

  let recommendations: unknown = recommendData

  // 如果是字符串,尝试解析
  if (typeof recommendations === 'string') {
    try {
      recommendations = JSON.parse(recommendations)
    } catch (e) {
      return []
    }
  }

  // 确保是数组
  if (!Array.isArray(recommendations)) {
    return []
  }

  return recommendations as string[]
}