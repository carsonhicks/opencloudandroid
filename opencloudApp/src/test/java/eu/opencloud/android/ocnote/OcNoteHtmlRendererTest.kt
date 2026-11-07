package eu.opencloud.android.ocnote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcNoteHtmlRendererTest {

    private fun doc(vararg nodes: String) = """{"type":"doc","content":[${nodes.joinToString(",")}]}"""
    private fun para(text: String, marks: String = ""): String {
        val marksJson = if (marks.isEmpty()) "" else ""","marks":[$marks]"""
        return """{"type":"paragraph","attrs":{"textAlign":null},"content":[{"type":"text","text":"$text"$marksJson}]}"""
    }

    @Test
    fun `renders paragraph and task list from a real note`() {
        val json = """{"type":"doc","content":[
          {"type":"paragraph","attrs":{"textAlign":null},"content":[{"type":"text","text":"This is a note!"}]},
          {"type":"paragraph","attrs":{"textAlign":null}},
          {"type":"taskList","content":[
            {"type":"taskItem","attrs":{"checked":false},"content":[{"type":"paragraph","content":[{"type":"text","text":"Task 1"}]}]},
            {"type":"taskItem","attrs":{"checked":true},"content":[{"type":"paragraph","content":[{"type":"text","text":"Task 2"}]}]}
          ]}]}"""
        val html = OcNoteHtmlRenderer.render(json)
        assertTrue(html.contains("<p>This is a note!</p>"))
        assertTrue(html.contains("&#9744; </span><div><p>Task 1</p></div>"))
        assertTrue(html.contains("&#9745; </span><div><p>Task 2</p></div>"))
    }

    @Test
    fun `marks are nested correctly and link and colors are kept`() {
        val html = OcNoteHtmlRenderer.render(
            doc(
                para(
                    "hi",
                    """{"type":"bold"},{"type":"italic"},{"type":"link","attrs":{"href":"https://example.org"}},""" +
                        """{"type":"textStyle","attrs":{"color":"#ff0000","fontSize":"20px"}}"""
                )
            )
        )
        assertTrue(
            html,
            html.contains(
                "<a href=\"https://example.org\"><span style=\"color:#ff0000;font-size:20px;\"><strong><em>hi</em></strong></span></a>"
            )
        )
    }

    @Test
    fun `text is escaped and dangerous values are dropped`() {
        val html = OcNoteHtmlRenderer.render(
            doc(
                para(
                    "<script>alert(1)</script>",
                    """{"type":"link","attrs":{"href":"javascript:alert(1)"}},""" +
                        """{"type":"textStyle","attrs":{"color":"red;background:url(http://x)"}}"""
                )
            )
        )
        assertFalse(html.contains("<script>alert"))
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
        assertFalse(html.contains("javascript:alert"))
        assertFalse(html.contains("url(http://x)"))
    }

    @Test
    fun `only embedded raster images are shown, remote ones become a placeholder`() {
        val html = OcNoteHtmlRenderer.render(
            doc(
                """{"type":"image","attrs":{"src":"https://tracker.example/x.png","alt":"remote"}}""",
                """{"type":"image","attrs":{"src":"data:image/png;base64,AAAA","alt":"inline"}}""",
                """{"type":"image","attrs":{"src":"data:image/svg+xml;base64,AAAA"}}"""
            )
        )
        assertFalse(html.contains("tracker.example"))
        assertTrue(html.contains("[image: remote]"))
        assertTrue(html.contains("<img src=\"data:image/png;base64,AAAA\""))
        assertFalse(html.contains("svg+xml"))
    }

    @Test
    fun `unknown nodes degrade to their text and tables render`() {
        val html = OcNoteHtmlRenderer.render(
            doc(
                """{"type":"futureThing","content":[{"type":"text","text":"kept"}]}""",
                """{"type":"table","content":[{"type":"tableRow","content":[
                    {"type":"tableHeader","attrs":{"colspan":2},"content":[{"type":"paragraph","content":[{"type":"text","text":"H"}]}]}]}]}"""
            )
        )
        assertTrue(html.contains("kept"))
        assertTrue(html.contains("<th colspan=\"2\"><p>H</p></th>"))
    }

    @Test(expected = OcNoteHtmlRenderer.InvalidNoteException::class)
    fun `invalid json is reported`() {
        OcNoteHtmlRenderer.render("not json")
    }

    @Test(expected = OcNoteHtmlRenderer.InvalidNoteException::class)
    fun `non document json is reported`() {
        OcNoteHtmlRenderer.render("""{"type":"paragraph"}""")
    }
}
