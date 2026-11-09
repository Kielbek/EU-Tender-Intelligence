package eu.tenderintel.reference;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CpvSearchWordsTest {

    @Test
    void cutsInflectedEndingsButKeepsShortWords() {
        assertThat(ReferenceQueries.stem("drogi")).isEqualTo("drog");
        assertThat(ReferenceQueries.stem("remont")).isEqualTo("remo");
        assertThat(ReferenceQueries.stem("oświetlenie")).isEqualTo("oświetlen");
        assertThat(ReferenceQueries.stem("most")).isEqualTo("most");
    }

    @Test
    void splitsOnAnythingButLettersAndDigits() {
        assertThat(ReferenceQueries.words(" roboty, drogowe/'mosty%  ")).containsExactly("roboty", "drogowe", "mosty");
        assertThat(ReferenceQueries.words("a b c d e f g h")).hasSize(ReferenceQueries.MAX_WORDS);
    }

    @Test
    void addsSynonymsForEverydayWords() {
        assertThat(ReferenceQueries.alternatives("Remontu")).containsExactly("remon", "remont", "napraw", "konserwac",
                "renowac", "modernizac");
        assertThat(ReferenceQueries.alternatives("sprzątanie")).containsExactly("sprzatan", "sprzat", "czyszcz");
        assertThat(ReferenceQueries.alternatives("IT")).contains("informaty");
        assertThat(ReferenceQueries.alternatives("item")).containsExactly("item");
    }

    @Test
    void significantPrefixKeepsTheDivision() {
        assertThat(CpvOpenCounts.significant("45000000")).isEqualTo("45");
        assertThat(CpvOpenCounts.significant("45233120")).isEqualTo("4523312");
        assertThat(CpvOpenCounts.significant("30000000")).isEqualTo("30");
    }
}
