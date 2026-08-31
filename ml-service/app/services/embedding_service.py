from typing import List

import numpy as np

from app.models import embedding_model
from app.schemas.recommendation_schemas import MealHistoryEntry, RecipeCandidate

def embed_recipe_text(recipe: RecipeCandidate) -> np.ndarray:
    ingredients = ", ".join(f"{ing.name} {ing.quantity} {ing.unit}" for ing in recipe.ingredients)
    s = recipe.name + ": " + ingredients
    model = embedding_model.get_model()
    return model.encode(s)


def embed_meal_history(meal_history: List[MealHistoryEntry]) -> np.ndarray:
    model = embedding_model.get_model()

    if not meal_history:
        return np.zeros(model.get_sentence_embedding_dimension())

    names = [entry.name for entry in meal_history]
    embeddings = model.encode(names)
    return np.mean(embeddings, axis=0)
