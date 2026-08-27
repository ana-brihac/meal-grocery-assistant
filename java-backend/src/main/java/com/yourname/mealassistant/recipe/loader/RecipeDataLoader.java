package com.yourname.mealassistant.recipe.loader;

import com.yourname.mealassistant.recipe.Recipe;
import com.yourname.mealassistant.recipe.RecipeIngredient;
import com.yourname.mealassistant.recipe.RecipeIngredientRepository;
import com.yourname.mealassistant.recipe.RecipeRepository;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Loads src/main/resources/data/recipes.csv on startup (CommandLineRunner — decided). Expected
// columns, one row per ingredient: recipe_name,instructions,source,ingredient_name,quantity,unit
// — quantity is treated as grams (decided). Consecutive rows sharing the same recipe_name are
// grouped into one Recipe + its RecipeIngredients.
@Component
public class RecipeDataLoader implements CommandLineRunner {

    private static final String DATASET_PATH = "data/recipes.csv";
    private static final int BATCH_SIZE = 200;

    private final RecipeRepository recipeRepository;
    private final RecipeIngredientRepository recipeIngredientRepository;

    public RecipeDataLoader(RecipeRepository recipeRepository, RecipeIngredientRepository recipeIngredientRepository) {
        this.recipeRepository = recipeRepository;
        this.recipeIngredientRepository = recipeIngredientRepository;
    }

    @Override
    public void run(String... args) throws IOException {
        // Idempotency guard (decided) — skip if recipes are already loaded, so restarts don't
        // duplicate the dataset.
        if (recipeRepository.count() > 0) {
            return;
        }

        ClassPathResource resource = new ClassPathResource(DATASET_PATH);
        if (!resource.exists()) {
            // No dataset provided yet — nothing to load. Not an error: recipes.csv is meant to be
            // added later (see the column format in this class's header comment).
            return;
        }

        Map<String, Recipe> recipesByName = new LinkedHashMap<>();
        Map<String, List<RecipeIngredient>> ingredientsByRecipeName = new LinkedHashMap<>();

        CSVFormat format = CSVFormat.Builder.create(CSVFormat.DEFAULT)
                .setHeader()
                .setSkipHeaderRecord(true)
                .build();

        try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8);
             CSVParser parser = format.parse(reader)) {

            for (CSVRecord row : parser) {
                String recipeName = row.get("recipe_name");

                recipesByName.computeIfAbsent(recipeName, name -> {
                    Recipe recipe = new Recipe();
                    recipe.setName(name);
                    recipe.setInstructions(row.get("instructions"));
                    recipe.setSource(row.get("source"));
                    return recipe;
                });

                RecipeIngredient ingredient = new RecipeIngredient();
                ingredient.setIngredientName(row.get("ingredient_name"));
                ingredient.setQuantity(Double.valueOf(row.get("quantity")));
                ingredient.setUnit(row.get("unit"));

                ingredientsByRecipeName.computeIfAbsent(recipeName, key -> new ArrayList<>()).add(ingredient);
            }
        }

        List<Recipe> allRecipes = new ArrayList<>(recipesByName.values());
        saveInBatches(recipeRepository, allRecipes);

        List<RecipeIngredient> allIngredients = new ArrayList<>();
        for (Recipe recipe : allRecipes) {
            for (RecipeIngredient ingredient : ingredientsByRecipeName.get(recipe.getName())) {
                ingredient.setRecipeId(recipe.getId());
                allIngredients.add(ingredient);
            }
        }
        saveInBatches(recipeIngredientRepository, allIngredients);
    }

    private <T> void saveInBatches(org.springframework.data.repository.CrudRepository<T, ?> repository, List<T> items) {
        for (int i = 0; i < items.size(); i += BATCH_SIZE) {
            repository.saveAll(items.subList(i, Math.min(i + BATCH_SIZE, items.size())));
        }
    }
}
