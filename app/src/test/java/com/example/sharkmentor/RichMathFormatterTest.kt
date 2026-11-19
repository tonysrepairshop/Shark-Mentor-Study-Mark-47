package com.example.sharkmentor

import com.example.sharkmentor.ui.components.SolutionBlock
import com.example.sharkmentor.ui.components.cleanAndFormatMath
import com.example.sharkmentor.ui.components.formatMathAsPlainText
import com.example.sharkmentor.ui.components.parseSolutionBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RichMathFormatterTest {

    @Test
    fun cleanAndFormatMath_handlesNestedFractionsExponentsAndRoots() {
        val nested = cleanAndFormatMath("""\frac{x^{2} + 1}{\sqrt{x}}""")
        assertEquals("(x² + 1) / √x", nested)
        assertFalse(nested.contains("\\"))

        val quadratic = cleanAndFormatMath("""x = \frac{-b \pm \sqrt{b^{2} - 4ac}}{2a}""")
        assertEquals("x = (-b ± √(b² - 4ac)) / 2a", quadratic)
        assertFalse(quadratic.contains("\\"))

        val simpleFrac = cleanAndFormatMath("""\frac{1}{2} + \frac{\pi}{4}""")
        assertEquals("1/2 + π/4", simpleFrac)

        val cubeRoot = cleanAndFormatMath("""\sqrt[3]{27} = 3, \sqrt[n]{x + 1}""")
        assertEquals("³√27 = 3, ⁿ√(x + 1)", cubeRoot)
    }

    @Test
    fun cleanAndFormatMath_formatsGeometrySymbolsAndDegrees() {
        val geo = cleanAndFormatMath(
            """\angle ABC = 90^\circ, \widehat{DEF} = 60^{\circ}, \triangle ABC \cong \triangle DEF, d_{1} \parallel d_{2}, AB \perp CD"""
        )
        assertTrue(geo.contains("∠ABC = 90°"))
        assertTrue(geo.contains("∠DEF = 60°"))
        assertTrue(geo.contains("△ABC ≅ △DEF"))
        assertTrue(geo.contains("d₁ ∥ d₂"))
        assertTrue(geo.contains("AB ⊥ CD"))
        assertFalse(geo.contains("\\"))
    }

    @Test
    fun cleanAndFormatMath_formatsCalculusSetsAndGreekLetters() {
        val integral = cleanAndFormatMath("""\int_{0}^{\pi} \sin(x) \, dx = 2""")
        assertEquals("∫₀^π sin(x) dx = 2", integral)
        assertFalse(integral.contains("\\"))

        val limit = cleanAndFormatMath("""\lim_{x \to 0} \frac{\sin x}{x} = 1""")
        assertTrue(limit.contains("lim_(x → 0)"))
        assertTrue(limit.contains("(sin x) / x = 1"))
        assertFalse(limit.contains("\\"))

        val sets = cleanAndFormatMath("""x \in \mathbb{R}, A \cup B \subseteq C, A \cap B = \emptyset""")
        assertEquals("x ∈ ℝ, A ∪ B ⊆ C, A ∩ B = ∅", sets)

        val greek = cleanAndFormatMath("""\Delta x \cdot \alpha + \beta \leq \gamma \neq \theta""")
        assertEquals("Δ x · α + β ≤ γ ≠ θ", greek)
    }

    @Test
    fun cleanAndFormatMath_preventsMangledCompoundSuperscriptsAndPreservesTurkish() {
        val sup = cleanAndFormatMath("""x^{a+b} = x^{a} \cdot x^{b}""")
        assertEquals("x^(a+b) = x^a · x^b", sup)

        val turkish = cleanAndFormatMath(
            """Çözüm: Üçgenin iç açıları toplamı $180^\circ$ olduğundan $\alpha + \beta = 90^\circ$ bulunur. Fiyat \$50 veya $50 ile $100 arası."""
        )
        assertTrue(turkish.contains("Çözüm: Üçgenin iç açıları toplamı 180°"))
        assertTrue(turkish.contains("α + β = 90°"))
        assertTrue(turkish.contains("$50"))
        assertTrue(turkish.contains("$100"))
        assertFalse(turkish.contains("\\"))

        val boxed = cleanAndFormatMath("""\boxed{42} ve \binom{5}{2} = 10""")
        assertEquals("**42** ve C(5, 2) = 10", boxed)
    }

    @Test
    fun cleanAndFormatMath_stripsActionTagsWithoutPrefixCollisions() {
        val withActions = cleanAndFormatMath(
            """Toplam: \sum_{i=1}^{n} i \to \infty #ACTION:NAV_PLANS #ACTION:{"id":1}#"""
        )
        assertFalse(withActions.contains("#ACTION"))
        assertTrue(withActions.contains("∑ᵢ₌₁ⁿ i → ∞"))
    }

    @Test
    fun parseSolutionBlocks_extractsStructuredStepsFormulasBulletsAndFinalAnswer() {
        val raw = """
            ### İkinci Dereceden Denklem Çözümü
            **1. Adım:** Diskriminant formülünü yazalım:
            $$\Delta = b^{2} - 4ac = (-5)^{2} - 4 \cdot 1 \cdot 6 = 1$$
            - Kökler: $x_{1} = 2$ ve $x_{2} = 3$
            - Kökler toplamı $5$ olur.

            **Doğru Cevap:** C
        """.trimIndent()

        val blocks = parseSolutionBlocks(raw)
        assertTrue(blocks.any { it is SolutionBlock.Heading && it.text.contains("İkinci Dereceden") })
        assertTrue(blocks.any { it is SolutionBlock.Step && it.label.contains("1. Adım") })
        val formulaBlock = blocks.filterIsInstance<SolutionBlock.DisplayFormula>().firstOrNull()
        assertTrue(formulaBlock != null && formulaBlock.formula.contains("Δ = b² - 4ac"))
        assertEquals(2, blocks.filterIsInstance<SolutionBlock.Bullet>().size)
        val answerBlock = blocks.filterIsInstance<SolutionBlock.FinalAnswer>().firstOrNull()
        assertTrue(answerBlock != null && answerBlock.content == "C")

        val wholeBoldAnswer = parseSolutionBlocks("**Doğru Cevap: D**")
            .filterIsInstance<SolutionBlock.FinalAnswer>()
            .firstOrNull()
        assertTrue(wholeBoldAnswer != null && wholeBoldAnswer.content == "D")
    }

    @Test
    fun formatMathAsPlainText_stripsMarkdownMarkersWhileKeepingUnicodeMath() {
        val plain = formatMathAsPlainText("""### Özet\n**1. Adım:** `$x^{2} + y^{2} = r^{2}$` ve \boxed{25}""".replace("\\n", "\n"))
        assertFalse(plain.contains("**"))
        assertFalse(plain.contains("`"))
        assertFalse(plain.contains("###"))
        assertTrue(plain.contains("x² + y² = r²"))
        assertTrue(plain.contains("25"))
    }
}
