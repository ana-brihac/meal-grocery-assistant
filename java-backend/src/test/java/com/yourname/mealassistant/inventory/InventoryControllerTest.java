package com.yourname.mealassistant.inventory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Direct-invocation controller test (no MockMvc / Spring context — matches the rest of this
// suite). Pins the CURRENT response shape: raw DTO bodies, ProblemDetail errors, 201 on create.
@ExtendWith(MockitoExtension.class)
class InventoryControllerTest {

    @Mock InventoryService service;

    @InjectMocks InventoryController controller;

    private static InventoryItem item(String name) {
        return new InventoryItem(name, BigDecimal.ONE, BigDecimal.ZERO);
    }

    @Test
    void listAll_returns200_withTheServiceListAsTheBody() {
        List<InventoryItem> rows = List.of(item("milk"), item("bread"));
        when(service.getAllItems()).thenReturn(rows);

        ResponseEntity<List<InventoryItem>> res = controller.listAll();

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isSameAs(rows);
    }

    @Test
    void listAll_emptyInventory_returns200_withAnEmptyListBody() {
        when(service.getAllItems()).thenReturn(List.of());

        ResponseEntity<List<InventoryItem>> res = controller.listAll();

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull().isEmpty();
    }

    @Test
    void addItem_returns201_withTheSavedItemAsBody_andPassesRequestNameAndQuantityToTheService() {
        InventoryItem saved = item("Milk");
        when(service.addItem("Milk", new BigDecimal("2"))).thenReturn(saved);

        ItemRequest request = new ItemRequest();
        request.setName("Milk");
        request.setQuantity(new BigDecimal("2"));

        ResponseEntity<InventoryItem> res = controller.addItem(request);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody()).isSameAs(saved);
        verify(service).addItem("Milk", new BigDecimal("2"));
    }

    @Test
    void addItem_nullFieldsInRequest_arePassedThroughUnchanged() {
        ItemRequest request = new ItemRequest();
        request.setName("Salt");
        request.setQuantity(null);
        when(service.addItem("Salt", null)).thenReturn(item("Salt"));

        ResponseEntity<InventoryItem> res = controller.addItem(request);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(service).addItem("Salt", null);
    }
}
