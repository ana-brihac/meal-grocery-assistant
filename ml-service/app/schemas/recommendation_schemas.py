from datetime import datetime
from typing import List

from pydantic import BaseModel

class RecipeIngredient(BaseModel):
    name: str
    quantity: float
    unit: str


class RecipeCandidate(BaseModel):
    id: int
    name: str
    ingredients: List[RecipeIngredient]


class MealHistoryEntry(BaseModel):
    name: str
    logged_at: datetime


class RecommendationRequest(BaseModel):
    candidates: List[RecipeCandidate]
    meal_history: List[MealHistoryEntry]


class RecipeScore(BaseModel):
    id: int
    name: str
    score: float


class RecommendationResponse(BaseModel):
    results: List[RecipeScore]