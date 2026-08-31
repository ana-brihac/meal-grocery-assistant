from sentence_transformers import SentenceTransformer

_MODEL_NAME = "all-MiniLM-L6-v2"

_model: SentenceTransformer | None = None

def get_model() -> SentenceTransformer:
    global _model
    if _model is None:
        _model = SentenceTransformer(_MODEL_NAME)
    return _model
