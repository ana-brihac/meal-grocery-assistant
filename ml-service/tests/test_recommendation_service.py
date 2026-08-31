import numpy as np
import pytest

from app.schemas.recommendation_schemas import RecipeCandidate, RecipeIngredient
from app.services import embedding_service, recommendation_service


def test_embedding_produces_consistent_length_vectors():
    short_recipe = RecipeCandidate(
        id=1,
        name="Toast",
        ingredients=[RecipeIngredient(name="bread", quantity=2, unit="slice")],
    )
    long_recipe = RecipeCandidate(
        id=2,
        name="Chicken Stir Fry",
        ingredients=[
            RecipeIngredient(name="chicken", quantity=200, unit="g"),
            RecipeIngredient(name="rice", quantity=150, unit="g"),
            RecipeIngredient(name="soy sauce", quantity=30, unit="ml"),
            RecipeIngredient(name="garlic", quantity=2, unit="clove"),
        ],
    )

    short_embedding = embedding_service.embed_recipe_text(short_recipe)
    long_embedding = embedding_service.embed_recipe_text(long_recipe)

    assert short_embedding.shape == long_embedding.shape


def test_recommend_ranks_similar_recipe_higher_than_dissimilar():
    # recommend() takes already-computed embeddings (decided contract), so this hand-picks vectors
    # directly instead of mocking embedding_service — no need to touch the real model at all.
    similar = RecipeCandidate(id=1, name="Similar", ingredients=[])
    dissimilar = RecipeCandidate(id=2, name="Dissimilar", ingredients=[])

    history_embedding = np.array([1.0, 0.0])
    candidate_embeddings = [
        np.array([1.0, 0.0]),   # same direction as history
        np.array([-1.0, 0.0]),  # opposite direction
    ]

    results = recommendation_service.recommend([similar, dissimilar], candidate_embeddings, history_embedding)

    assert [r.id for r in results] == [1, 2]
    assert results[0].score == pytest.approx(1.0)
    assert results[1].score == pytest.approx(-1.0)


def test_recommend_handles_empty_candidate_list():
    result = recommendation_service.recommend([], [], np.array([1.0, 0.0]))

    assert result == []
