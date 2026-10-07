package nexus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import nexus.core.types.TextStream;
import nexus.core.util.Expression;
import nexus.core.util.TextUtil;
import org.junit.jupiter.api.Test;

class ExpressionAndTextTest {
    @Test
    void evaluates_arithmetic_with_precedence_and_functions() {
        assertEquals(7, Expression.evaluate("1 + 2 * 3", Map.of()));
        assertEquals(9, Expression.evaluate("(1 + 2) * 3", Map.of()));
        assertEquals(512, Expression.evaluate("2 ^ 3 ^ 2", Map.of()));           // right-associative
        assertEquals(-4, Expression.evaluate("-2 ^ 2", Map.of()));               // unary minus binds looser than ^
        assertEquals(5, Expression.evaluate("sqrt(a*a + b*b)", Map.of("a", 3.0, "b", 4.0)));
        assertEquals(2, Expression.evaluate("min(max(1, 2), 5)", Map.of()));
        assertEquals(1.5e3, Expression.evaluate("1.5e3", Map.of()));
        assertEquals(Math.PI, Expression.evaluate("pi", Map.of()));
        assertEquals(1, Expression.evaluate("7 % 3", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> Expression.evaluate("1 +", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> Expression.evaluate("x + 1", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> Expression.evaluate("foo(1)", Map.of()));
    }

    @Test
    void splits_romanian_text() {
        String text = "Ana are mere. Mihai are pere! Ce au ei? Au fructe.\n\nAl doilea paragraf.";
        assertEquals(5, TextUtil.sentences(text).size());
        assertEquals(2, TextUtil.paragraphs(text).size());
        assertEquals(List.of("Ștefan", "și-a", "luat", "3", "cărți"), TextUtil.words("Ștefan și-a luat 3 cărți."));
        assertEquals("stefan si-a", TextUtil.fold("Ștefan Și-a"));
    }

    @Test
    void html_to_text_keeps_paragraphs_and_drops_scripts() {
        String html = "<html><head><title>x</title><style>p{}</style></head><body><p>Bună&nbsp;ziua</p><script>alert(1)</script><p>A doua</p></body>";
        assertEquals("Bună ziua\nA doua", TextUtil.htmlToText(html));
    }

    @Test
    void text_streams_replay_history_to_late_subscribers() {
        var s = new TextStream();
        s.append("Bu");
        var seen = new StringBuilder();
        var done = s.subscribe(seen::append);
        s.append("nă");
        s.complete();
        assertEquals("Bună", done.join());
        assertEquals("Bună", seen.toString());
        assertThrows(IllegalStateException.class, () -> s.append("!"));
    }
}
