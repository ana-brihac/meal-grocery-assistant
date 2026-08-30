from typing import List

import numpy as np

from app.schemas.recommendation_schemas import RecipeCandidate, RecipeScore


# TODO: once budget/calorie-aware ranking lands, this will likely need to combine
# similarity with other UserPreference-derived factors — see java-backend's
# RecipeRankingService.rankRecipes for the equivalent hook on the Java side. This stays
# similarity-only; don't implement budget/calorie weighting here yet.
# HINT: cosine similarity between vector a and vector b is dot(a, b) / (norm(a) * norm(b)). To
# score every candidate at once instead of looping: np.vstack(candidate_embeddings) to get a 2D
# array (n_candidates, embedding_dim), then (stacked @ history_embedding) / (norm per row *
# norm(history_embedding)) gives you all n scores in one shot. Guard against a zero-norm vector
# (division by zero) if that can happen.
def score_candidates(candidate_embeddings: List[np.ndarray], history_embedding: np.ndarray) -> List[float]:
    stacked = np.vstack(candidate_embeddings)
    history_norm = np.linalg.norm(history_embedding)

    if history_norm == 0:
        return [0.0] * len(candidate_embeddings)

    candidate_norms = np.linalg.norm(stacked, axis=1)
    safe_norms = np.where(candidate_norms == 0, 1, candidate_norms)

    scores = (stacked @ history_embedding) / (safe_norms * history_norm)

    return scores.tolist()

def recommend(
    candidates: List[RecipeCandidate],
    candidate_embeddings: List[np.ndarray],
    history_embedding: np.ndarray,
) -> List[RecipeScore]:
    if not candidates:
        return []

    scores = score_candidates(candidate_embeddings, history_embedding)

    results = [
        RecipeScore(id=c.id, name=c.name, score=s)
        for c, s in zip(candidates, scores)
    ]

    return sorted(results, key=lambda r: r.score, reverse=True)
