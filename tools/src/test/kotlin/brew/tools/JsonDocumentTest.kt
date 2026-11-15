package brew.tools

import brew.document.DocumentValidator
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonDocumentTest {
    private val card =
        """
        { "header": { "apiLevel": 8, "width": 400, "height": 120, "densityBehavior": 2 },
          "root": { "column": {
            "modifiers": ["fillMaxSize", { "background": "#FF2E5E4E" }, { "padding": [18, 16] }],
            "children": [
              { "text": {
                  "value": "Happy hour", "fontSize": 30, "fontWeight": 800, "color": "#FFFFFFFF" } },
              { "row": {
                  "modifiers": [
                    { "onclick": { "type": "hostAction", "name": "open_details:mocha" } }
                  ],
                  "children": [{ "text": { "value": "Iced Mocha", "fontSize": 17 } }] } }
            ] } } }
    """

    @Test
    fun `JSON becomes a valid binary document`() {
        val bytes = JsonDocument.toRc(card)
        assertTrue(DocumentValidator.validate(bytes).operations.isNotEmpty())
    }

    @Test
    fun `broken JSON is refused with a reason`() {
        val e = assertThrows(IllegalArgumentException::class.java) { JsonDocument.toRc("{ \"root\": ") }
        assertTrue(e.message, e.message!!.startsWith("not a valid Remote Compose JSON document"))
    }
}
