"""TestClient-level tests for POST /recommendations.

tests/test_recommendation_service.py exercises the service functions directly; this file drives
the HTTP endpoint: request validation (422s), the router's try/except -> HTTP 500 mapping, the
response_model contract, and method routing. The Java-side MlServiceClientTest covers the
caller's view (request serialization + parsing {"results": [...]} + the 5 s timeout), not any
of this.

The endpoint calls embedding_service.embed_recipe_text / embed_meal_history, which would load
the real sentence-transformers model. These tests monkeypatch that layer with fixed numpy
vectors, so they are fast and need no network / model download. recommendation_service.recommend
is pure numpy and runs for real unless a test needs to force an error.
"""

import numpy as np
import pytest
from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


@pytest.fixture
def valid_body():
    return {
        "candidates": [
            {"id": 1, "name": "Toast", "ingredients": [{"name": "bread", "quantity": 2, "unit": "slice"}]}
        ],
        "meal_history": [{"name": "toast", "logged_at": "2026-08-01T08:00:00"}],
    }


@pytest.fixture
def fake_embeddings(monkeypatch):
    monkeypatch.setattr("app.services.embedding_service.embed_recipe_text",
                        lambda c: np.array([1.0, 0.0]))
    monkeypatch.setattr("app.services.embedding_service.embed_meal_history",
                        lambda h: np.array([1.0, 0.0]))


def test_post_recommendations_valid_request_returns_200_and_recommendation_response_shape(valid_body, fake_embeddings):
    resp = client.post("/recommendations", json=valid_body)

    assert resp.status_code == 200
    data = resp.json()
    assert set(data.keys()) == {"results"}
    assert len(data["results"]) == 1
    assert set(data["results"][0].keys()) == {"id", "name", "score"}
    assert data["results"][0]["id"] == 1
    assert data["results"][0]["name"] == "Toast"
    assert isinstance(data["results"][0]["score"], float)


def test_post_recommendations_orders_results_by_score_descending(monkeypatch):
    monkeypatch.setattr("app.services.embedding_service.embed_recipe_text",
                        lambda c: np.array([1.0, 0.0]) if c.id == 1 else np.array([-1.0, 0.0]))
    monkeypatch.setattr("app.services.embedding_service.embed_meal_history",
                        lambda h: np.array([1.0, 0.0]))
    body = {
        "candidates": [
            {"id": 1, "name": "Similar", "ingredients": []},
            {"id": 2, "name": "Dissimilar", "ingredients": []},
        ],
        "meal_history": [{"name": "x", "logged_at": "2026-08-01T00:00:00"}],
    }

    resp = client.post("/recommendations", json=body)

    assert resp.status_code == 200
    assert [r["id"] for r in resp.json()["results"]] == [1, 2]
    assert resp.json()["results"][0]["score"] == pytest.approx(1.0)
    assert resp.json()["results"][1]["score"] == pytest.approx(-1.0)


def test_post_recommendations_empty_candidates_returns_200_with_empty_results(monkeypatch):
    monkeypatch.setattr("app.services.embedding_service.embed_meal_history",
                        lambda h: np.array([1.0, 0.0]))
    body = {"candidates": [], "meal_history": [{"name": "x", "logged_at": "2026-08-01T00:00:00"}]}

    resp = client.post("/recommendations", json=body)

    assert resp.status_code == 200
    assert resp.json() == {"results": []}


def test_post_recommendations_empty_meal_history_returns_200_scores_all_zero(monkeypatch):
    monkeypatch.setattr("app.services.embedding_service.embed_recipe_text",
                        lambda c: np.array([1.0, 0.0]))
    monkeypatch.setattr("app.services.embedding_service.embed_meal_history",
                        lambda h: np.zeros(2))
    body = {
        "candidates": [
            {"id": 1, "name": "Toast", "ingredients": [{"name": "bread", "quantity": 2, "unit": "slice"}]}
        ],
        "meal_history": [],
    }

    resp = client.post("/recommendations", json=body)

    assert resp.status_code == 200
    assert all(r["score"] == 0.0 for r in resp.json()["results"])


def test_post_recommendations_missing_candidates_field_returns_422():
    resp = client.post("/recommendations", json={"meal_history": []})

    assert resp.status_code == 422


def test_post_recommendations_candidate_missing_required_id_returns_422():
    resp = client.post("/recommendations", json={
        "candidates": [{"name": "X", "ingredients": []}],
        "meal_history": [],
    })

    assert resp.status_code == 422


def test_post_recommendations_ingredient_missing_required_fields_returns_422():
    resp = client.post("/recommendations", json={
        "candidates": [{"id": 1, "name": "X", "ingredients": [{"name": "bread"}]}],
        "meal_history": [],
    })

    assert resp.status_code == 422


def test_post_recommendations_non_numeric_quantity_returns_422():
    resp = client.post("/recommendations", json={
        "candidates": [{"id": 1, "name": "X",
                        "ingredients": [{"name": "bread", "quantity": "a lot", "unit": "slice"}]}],
        "meal_history": [],
    })

    assert resp.status_code == 422


def test_post_recommendations_invalid_logged_at_datetime_returns_422():
    resp = client.post("/recommendations", json={
        "candidates": [],
        "meal_history": [{"name": "x", "logged_at": "last tuesday"}],
    })

    assert resp.status_code == 422


def test_post_recommendations_unknown_extra_fields_in_body_are_ignored(valid_body, fake_embeddings):
    body = dict(valid_body)
    body["bogus_top"] = 123
    body["candidates"] = [dict(c, bogus_inner="x") for c in body["candidates"]]

    resp = client.post("/recommendations", json=body)

    # Pydantic v2 default: unknown fields are ignored, not rejected.
    assert resp.status_code == 200


def test_post_recommendations_downstream_exception_is_mapped_to_http_500_with_detail(valid_body, monkeypatch):
    monkeypatch.setattr("app.services.embedding_service.embed_recipe_text",
                        lambda c: np.array([1.0, 0.0]))
    monkeypatch.setattr("app.services.embedding_service.embed_meal_history",
                        lambda h: np.array([1.0, 0.0]))

    def boom(*args, **kwargs):
        raise RuntimeError("boom")

    monkeypatch.setattr("app.services.recommendation_service.recommend", boom)

    resp = client.post("/recommendations", json=valid_body)

    # The router's only error mapping: `except Exception as e: raise HTTPException(500, str(e))`.
    assert resp.status_code == 500
    assert resp.json() == {"detail": "boom"}


def test_get_recommendations_wrong_method_returns_405():
    resp = client.get("/recommendations")

    assert resp.status_code == 405


def test_post_recommendations_response_model_drops_fields_not_in_recipe_score(valid_body, monkeypatch):
    monkeypatch.setattr("app.services.embedding_service.embed_recipe_text",
                        lambda c: np.array([1.0, 0.0]))
    monkeypatch.setattr("app.services.embedding_service.embed_meal_history",
                        lambda h: np.array([1.0, 0.0]))
    monkeypatch.setattr("app.services.recommendation_service.recommend",
                        lambda *a, **k: [{"id": 1, "name": "Toast", "score": 0.9, "internal_debug": "secret"}])

    resp = client.post("/recommendations", json=valid_body)

    assert resp.status_code == 200
    # RecommendationResponse(results=[...]) coerces each item to RecipeScore, dropping extras.
    assert resp.json()["results"][0] == {"id": 1, "name": "Toast", "score": 0.9}
