package com.yourname.mealassistant.receipt;

import com.yourname.mealassistant.inventory.InventoryItem;
import com.yourname.mealassistant.receipt.parser.ReceiptParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ReceiptParser parses UNTRUSTED Gemini output. docs/known-issues.md flags it as the
// highest-risk untested area: it catches Exception broadly, logs to stderr, and returns whatever
// it accumulated so far — a malformed response silently becomes "0 items added" (or a truncated
// list). These tests document that behaviour; they are not a fix for the silent swallow.
//
// ReceiptParser has no dependencies — new ReceiptParser(), no Mockito.
class ReceiptParserTest {

    private final ReceiptParser parser = new ReceiptParser();

    // ---- shapes ReceiptParser explicitly supports ----

    @Test
    void parse_bareJsonArray_returnsOneItemPerElement() {
        List<InventoryItem> items = parser.parseReceiptText(
                "[{\"name\":\"Milk\",\"quantity\":1,\"price\":2.5},"
                        + "{\"name\":\"Bread\",\"quantity\":2,\"price\":1.99}]");

        assertThat(items).hasSize(2);
        assertThat(items.get(0).getName()).isEqualTo("Milk");
        assertThat(items.get(0).getQuantity()).isEqualByComparingTo("1");
        assertThat(items.get(0).getPrice()).isEqualByComparingTo("2.5");
        assertThat(items.get(1).getName()).isEqualTo("Bread");
        assertThat(items.get(1).getQuantity()).isEqualByComparingTo("2");
        assertThat(items.get(1).getPrice()).isEqualByComparingTo("1.99");
    }

    @Test
    void parse_objectWithItemsArray_usesThatArray() {
        List<InventoryItem> items = parser.parseReceiptText(
                "{\"items\":[{\"name\":\"Eggs\",\"quantity\":12,\"price\":3.20}]}");

        assertThat(items).hasSize(1);
        assertThat(items.get(0).getName()).isEqualTo("Eggs");
        assertThat(items.get(0).getQuantity()).isEqualByComparingTo("12");
        assertThat(items.get(0).getPrice()).isEqualByComparingTo("3.20");
    }

    @Test
    void parse_markdownFencedJson_stripsTheJsonFenceBeforeParsing() {
        List<InventoryItem> items = parser.parseReceiptText(
                "```json\n[{\"name\":\"Milk\",\"quantity\":1,\"price\":2}]\n```");

        assertThat(items).hasSize(1);
        assertThat(items.get(0).getName()).isEqualTo("Milk");
    }

    @Test
    void parse_elementWithDescriptionButNoName_usesDescriptionAsName() {
        List<InventoryItem> items = parser.parseReceiptText(
                "[{\"description\":\"Organic Bananas\",\"quantity\":1,\"price\":1.10}]");

        assertThat(items).hasSize(1);
        assertThat(items.get(0).getName()).isEqualTo("Organic Bananas");
    }

    @Test
    void parse_elementMissingNameAndDescription_defaultsToUnknownItem() {
        List<InventoryItem> items = parser.parseReceiptText("[{\"quantity\":1,\"price\":2.00}]");

        assertThat(items).hasSize(1);
        assertThat(items.get(0).getName()).isEqualTo("Unknown Item");
    }

    @Test
    void parse_elementMissingQuantity_defaultsQuantityToOne() {
        List<InventoryItem> items = parser.parseReceiptText("[{\"name\":\"Salt\",\"price\":0.80}]");

        assertThat(items).hasSize(1);
        assertThat(items.get(0).getQuantity()).isEqualByComparingTo("1");
    }

    @Test
    void parse_elementMissingPrice_defaultsPriceToZero() {
        List<InventoryItem> items = parser.parseReceiptText("[{\"name\":\"Salt\",\"quantity\":1}]");

        assertThat(items).hasSize(1);
        assertThat(items.get(0).getPrice()).isEqualByComparingTo("0");
    }

    // ---- malformed / unexpected Gemini output ----

    @Test
    void parse_notJsonAtAll_returnsEmptyListAndSwallowsTheError() {
        // Documents the silent-swallow behaviour flagged in known-issues.md.
        assertThat(parser.parseReceiptText("I could not read the receipt, sorry!")).isEmpty();
    }

    @Test
    void parse_emptyString_returnsEmptyList() {
        assertThat(parser.parseReceiptText("")).isEmpty();
    }

    @Test
    void parse_nullInput_currentlyThrowsNpe() {
        // Documents today's behaviour: parseReceiptText reaches rawText.replaceAll(...) before
        // the try/catch, so null NPEs. No guard is added here (that would be a code change).
        assertThatThrownBy(() -> parser.parseReceiptText(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void parse_truncatedJson_returnsEmptyList() {
        assertThat(parser.parseReceiptText("[{\"name\":\"Milk\",\"quantity\":1,\"pri")).isEmpty();
    }

    @Test
    void parse_jsonObjectWithoutItemsKey_returnsEmptyList() {
        // Object, not array, no "items" -> itemsArray is a non-array node -> empty.
        assertThat(parser.parseReceiptText("{\"error\":\"blurry image\"}")).isEmpty();
    }

    @Test
    void parse_itemsKeyPresentButNotAnArray_returnsEmptyList() {
        assertThat(parser.parseReceiptText("{\"items\":{\"name\":\"Milk\"}}")).isEmpty();
    }

    @Test
    void parse_jsonNullLiteral_returnsEmptyList() {
        assertThat(parser.parseReceiptText("null")).isEmpty();
    }

    @Test
    void parse_nonNumericQuantity_abortsMidLoop_returningOnlyItemsParsedSoFar() {
        // new BigDecimal("NaN") throws inside the for-loop; the catch is OUTSIDE it, so parsing
        // stops at the bad row and the tail ("AlsoGood") is silently lost.
        List<InventoryItem> items = parser.parseReceiptText(
                "[{\"name\":\"Good\",\"quantity\":1,\"price\":1.00},"
                        + "{\"name\":\"Bad\",\"quantity\":\"NaN\",\"price\":1.00},"
                        + "{\"name\":\"AlsoGood\",\"quantity\":1,\"price\":1.00}]");

        assertThat(items).hasSize(1);
        assertThat(items.get(0).getName()).isEqualTo("Good");
    }

    @Test
    void parse_priceWithCurrencySymbol_abortsThatRowAndTheRest() {
        // new BigDecimal("$2.50") throws -> caught outside the loop -> nothing accumulated.
        assertThat(parser.parseReceiptText("[{\"name\":\"Milk\",\"quantity\":1,\"price\":\"$2.50\"}]"))
                .isEmpty();
    }

    @Test
    void parse_nameFieldIsANumber_isCoercedToStringViaAsText() {
        List<InventoryItem> items = parser.parseReceiptText("[{\"name\":123,\"quantity\":1,\"price\":1.00}]");

        assertThat(items).hasSize(1);
        assertThat(items.get(0).getName()).isEqualTo("123");
    }

    @Test
    void parse_emptyJsonArray_returnsEmptyListWithoutError() {
        assertThat(parser.parseReceiptText("[]")).isEmpty();
    }

    @Test
    void parse_prosePlusFencedJson_failsToParse_becauseSurroundingProseRemains() {
        // The regex only removes the ```json ... ``` markers; the leading/trailing prose stays
        // in cleanJson, so readTree chokes on "Here" and the whole parse is lost -> empty list.
        List<InventoryItem> items = parser.parseReceiptText(
                "Here is the receipt:\n```json\n[{\"name\":\"Milk\",\"quantity\":1,\"price\":2}]\n```\n"
                        + "Let me know if you need more.");

        assertThat(items).isEmpty();
    }

    @Test
    void parse_plainFenceWithoutJsonLanguageTag_isNotStripped_soParseFails() {
        // ReceiptParser's regex is specifically "```json ...": a bare ``` fence is left in place,
        // the backticks break the parse, and it returns empty. A real gap — Gemini sometimes
        // omits the language tag.
        assertThat(parser.parseReceiptText("```\n[{\"name\":\"Milk\",\"quantity\":1,\"price\":2}]\n```"))
                .isEmpty();
    }
}
