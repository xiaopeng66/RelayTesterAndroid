package com.relaytester.app

import com.relaytester.app.feature.fingerprint.parseAnimationDocument
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * 用真实供应商产出的原文核对解析与预览，而不是用合成样例。
 *
 * 这份文档是 freeapi.site / deepseek-ai/deepseek-v4.1-flash 在 2026-10-10 实际返回的答案
 * （存在 tmp/pelican-real.html），形态是"一句前言 + ```html 围栏 + 完整文档 + 尾部说明"。
 * 它正是让旧解析器报"返回内容不是完整的 HTML 文档"的那一份，所以它是这次修复最有说服力的
 * 证据：合成样例只能证明"我以为的形态能过"，真实原文能证明"供应商真的会这么答"。
 *
 * 文件不在时跳过而不是失败：tmp/ 是临时目录，测试套件不该因为清理过临时文件而变红。
 */
class RealAnswerParseRegressionTest {

    private fun realAnswer(): String? {
        val candidates = listOf(
            File("../tmp/pelican-real.html"),
            File("E:/AI/Zcode/tmp/pelican-real.html"),
        )
        return candidates.firstOrNull { it.isFile }?.readText()
    }

    @Test
    fun `the real supplier answer with prose and a fence parses into the document`() {
        val raw = realAnswer()
        Assume.assumeTrue("tmp/pelican-real.html 不在，跳过真实原文核对", raw != null)

        val result = parseAnimationDocument(raw!!, truncated = false)
        val html = result.getOrNull()?.html
        if (html == null) {
            throw AssertionError(
                "真实答案被拒: ${result.exceptionOrNull()?.message}",
            )
        }

        // 预览拿到的必须是文档本身：前言与尾部说明都要去掉。
        if (!html.startsWith("<!DOCTYPE html") && !html.startsWith("<html")) {
            throw AssertionError("预览内容没有从文档开头开始: ${html.take(60)}")
        }
        if (!html.trimEnd().endsWith("</html>")) {
            throw AssertionError("预览内容没有在 </html> 结束: ${html.takeLast(60)}")
        }
        // 前言那句中文解说不能混进页面里。
        if (html.contains("这是一个可直接在浏览器中打开的HTML页面")) {
            throw AssertionError("解说混进了预览页面")
        }
        if (!html.contains("<svg")) {
            throw AssertionError("真实答案里应该有内联 SVG")
        }
    }
}
