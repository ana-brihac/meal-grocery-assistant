package com.yourname.mealassistant.inventory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// JUnit 5 + Mockito + AssertJ, no Spring context — same conventions as NutritionServiceTest /
// SpendingServiceTest / IngredientPriceServiceTest. InventoryService is a thin pass-through to
// InventoryRepository; these tests pin exactly that.
@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock InventoryRepository inventoryRepository;

    @InjectMocks InventoryService service;

    private static InventoryItem item(String name) {
        return new InventoryItem(name, BigDecimal.ONE, BigDecimal.ZERO);
    }

    // ---- getAllItems ----

    @Test
    void getAllItems_returnsWhateverTheRepositoryReturns() {
        List<InventoryItem> rows = List.of(item("milk"), item("bread"));
        when(inventoryRepository.findAll()).thenReturn(rows);

        assertThat(service.getAllItems()).isEqualTo(rows);
    }

    @Test
    void getAllItems_emptyRepository_returnsEmptyListNotNull() {
        when(inventoryRepository.findAll()).thenReturn(List.of());

        assertThat(service.getAllItems()).isNotNull().isEmpty();
    }

    @Test
    void getAllItems_returnsUnboundedResult_noPagination() {
        List<InventoryItem> big = IntStream.range(0, 500)
                .mapToObj(i -> item("item-" + i))
                .toList();
        when(inventoryRepository.findAll()).thenReturn(big);

        // Marker for the open "GET /api/inventory has no pagination" issue — every row comes back.
        assertThat(service.getAllItems()).hasSize(500);
    }

    // ---- addItem ----

    @Test
    void addItem_persistsNewItemWithNameAndQuantity_leavesPriceAndExpiryNull() {
        when(inventoryRepository.save(any(InventoryItem.class))).thenAnswer(i -> i.getArgument(0));

        service.addItem("Milk", new BigDecimal("2"));

        ArgumentCaptor<InventoryItem> saved = ArgumentCaptor.forClass(InventoryItem.class);
        verify(inventoryRepository).save(saved.capture());
        InventoryItem row = saved.getValue();
        assertThat(row.getName()).isEqualTo("Milk");
        assertThat(row.getQuantity()).isEqualByComparingTo("2");
        assertThat(row.getPrice()).isNull();
        assertThat(row.getExpiryDate()).isNull();
        assertThat(row.getReceipt()).isNull();
    }

    @Test
    void addItem_returnsThePersistedEntityFromTheRepository() {
        InventoryItem persisted = item("milk");
        persisted.setId(99L);
        when(inventoryRepository.save(any(InventoryItem.class))).thenReturn(persisted);

        assertThat(service.addItem("milk", BigDecimal.ONE)).isSameAs(persisted);
    }

    @Test
    void addItem_nullQuantity_isPassedThroughUnchanged() {
        when(inventoryRepository.save(any(InventoryItem.class))).thenAnswer(i -> i.getArgument(0));

        service.addItem("Salt", null);

        ArgumentCaptor<InventoryItem> saved = ArgumentCaptor.forClass(InventoryItem.class);
        verify(inventoryRepository).save(saved.capture());
        // Documents current behaviour: the service does no validation.
        assertThat(saved.getValue().getQuantity()).isNull();
    }

    @Test
    void addItem_doesNotCallFindByNameOrAnyDedup_alwaysInsertsANewRow() {
        when(inventoryRepository.save(any(InventoryItem.class))).thenAnswer(i -> i.getArgument(0));

        service.addItem("Milk", BigDecimal.ONE);
        service.addItem("Milk", BigDecimal.ONE);

        verify(inventoryRepository, times(2)).save(any(InventoryItem.class));
        verify(inventoryRepository, never()).findByNameContainingIgnoreCase(anyString());
    }
}
