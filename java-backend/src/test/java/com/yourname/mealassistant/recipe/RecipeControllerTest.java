package com.yourname.mealassistant.recipe;

import com.yourname.mealassistant.common.exception.NotFoundException;
import com.yourname.mealassistant.recipe.dto.RecipeDetailResponse;
import com.yourname.mealassistant.recipe.dto.RecipeSearchRequest;
import com.yourname.mealassistant.recipe.dto.RecipeSearchResponse;
import com.yourname.mealassistant.recipe.dto.RecipeUpsertRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Direct-invocation controller test (no MockMvc). Raw DTO bodies; POST "" -> 201, the rest 200.
// RecipeService is covered by RecipeServiceTest; here: shapes, status, and request-building.
@ExtendWith(MockitoExtension.class)
class RecipeControllerTest {

    @Mock RecipeService service;

    @InjectMocks RecipeController controller;

    private static RecipeDetailResponse detail(long id, String name) {
        return new RecipeDetailResponse(id, name, "steps", "app", List.of());
    }

    @Test
    void search_returns200_withRecipeSearchResponseBody_andBuildsRequestFromIngredientsAndRankBy() {
        RecipeSearchResponse dto = new RecipeSearchResponse(List.of());
        when(service.searchRecipes(any())).thenReturn(dto);

        ResponseEntity<RecipeSearchResponse> res =
                controller.search(List.of("tomato", "pasta"), "mealHistory");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isSameAs(dto);

        ArgumentCaptor<RecipeSearchRequest> req = ArgumentCaptor.forClass(RecipeSearchRequest.class);
        verify(service).searchRecipes(req.capture());
        assertThat(req.getValue().ingredients()).containsExactly("tomato", "pasta");
        assertThat(req.getValue().rankBy()).isEqualTo("mealHistory");
    }

    @Test
    void search_nullRankBy_buildsRequestWithNullRankBy() {
        when(service.searchRecipes(any())).thenReturn(new RecipeSearchResponse(List.of()));

        controller.search(List.of("egg"), null);

        ArgumentCaptor<RecipeSearchRequest> req = ArgumentCaptor.forClass(RecipeSearchRequest.class);
        verify(service).searchRecipes(req.capture());
        assertThat(req.getValue().rankBy()).isNull();
    }

    @Test
    void list_returns200_withTheRecipeDetailListAsBody() {
        List<RecipeDetailResponse> all = List.of(detail(1, "A"), detail(2, "B"));
        when(service.listRecipes()).thenReturn(all);

        ResponseEntity<List<RecipeDetailResponse>> res = controller.list();

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isSameAs(all);
    }

    @Test
    void get_returns200_withTheRecipeDetailAsBody_andForwardsPathId() {
        RecipeDetailResponse dto = detail(5, "Oatmeal");
        when(service.getRecipe(5L)).thenReturn(dto);

        ResponseEntity<RecipeDetailResponse> res = controller.get(5L);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isSameAs(dto);
        verify(service).getRecipe(5L);
    }

    @Test
    void get_unknownId_serviceThrows_propagatesNotFound() {
        when(service.getRecipe(999L)).thenThrow(new NotFoundException("Recipe not found: 999"));

        assertThatThrownBy(() -> controller.get(999L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void create_returns201Created_withTheRecipeDetailAsBody_andForwardsTheUpsertRequest() {
        RecipeUpsertRequest request = new RecipeUpsertRequest("Oatmeal", "cook", "app", List.of());
        RecipeDetailResponse dto = detail(42, "Oatmeal");
        when(service.createRecipe(request)).thenReturn(dto);

        ResponseEntity<RecipeDetailResponse> res = controller.create(request);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody()).isSameAs(dto);
        verify(service).createRecipe(request);
    }

    @Test
    void update_returns200_withTheRecipeDetailAsBody_andForwardsPathIdAndUpsertRequest() {
        RecipeUpsertRequest request = new RecipeUpsertRequest("New", "steps", "app", List.of());
        RecipeDetailResponse dto = detail(7, "New");
        when(service.updateRecipe(7L, request)).thenReturn(dto);

        ResponseEntity<RecipeDetailResponse> res = controller.update(7L, request);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isSameAs(dto);
        verify(service).updateRecipe(7L, request);
    }
}
