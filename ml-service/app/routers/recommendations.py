from fastapi import APIRouter, HTTPException

from app.schemas.recommendation_schemas import RecommendationRequest, RecommendationResponse
from app.services import embedding_service, recommendation_service

router = APIRouter()


@router.post("/recommendations", response_model=RecommendationResponse)
async def get_recommendations(request: RecommendationRequest) -> RecommendationResponse:
    try:
        candidate_embeddings = [embedding_service.embed_recipe_text(c) for c in request.candidates]
        history_embedding = embedding_service.embed_meal_history(request.meal_history)
        results = recommendation_service.recommend(request.candidates, candidate_embeddings, history_embedding)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

    return RecommendationResponse(results=results)
