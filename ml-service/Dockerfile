# syntax=docker/dockerfile:1
#
# ml-service (FastAPI recommendation endpoint).
#
# Single-stage: there is nothing to "build" for a pure-Python service, and a
# multi-stage copy of the site-packages tree would save little here (the bulk is
# torch, which we need at runtime anyway).

FROM python:3.12-slim AS runtime

# - PYTHONUNBUFFERED: logs show up immediately in `docker compose logs`.
# - HF_HOME: where sentence-transformers / huggingface_hub cache the model. Set
#   it to a fixed path we control so the baked-in model (see below) is found at
#   runtime regardless of which user the process runs as.
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    HF_HOME=/opt/models/hf \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

# ---------------------------------------------------------------------------
# Dependencies.
#
# sentence-transformers pulls in torch. PyPI's default `torch` wheel for linux
# bundles the CUDA runtime (~2 GB unpacked) which is dead weight on a CPU-only
# free-tier VM. Install the CPU build from PyTorch's own index first so the
# resolver in the requirements.txt step sees torch already satisfied.
# ---------------------------------------------------------------------------
RUN pip install --index-url https://download.pytorch.org/whl/cpu torch

COPY requirements.txt .
RUN pip install -r requirements.txt

# ---------------------------------------------------------------------------
# Model: bake all-MiniLM-L6-v2 (~80 MB) into the image at build time.
#
# DECISION — bake vs. download-on-first-use. Chosen: bake.
#   Pro: container startup and the first /recommendations call need no network
#        and are not blocked on an 80 MB Hugging Face download; the service is
#        self-contained and works if HF is unreachable. Restarts are cheap.
#   Con: the image is ~80 MB larger and this build step needs network access
#        (the build already needs network for pip, so no new requirement).
# This suits a small always-on VM: build once, run/restart many times, and never
# have a runtime dependency on huggingface.co. app/main.py's lifespan then loads
# this baked copy into memory at startup, so the first /recommendations call is
# not cold either.
# To switch to lazy download instead, delete this RUN line — get_model() in
# app/models/embedding_model.py will fetch on first use and cache under HF_HOME
# (make HF_HOME a volume in that case so the download survives `docker compose
# down`).
RUN python -c "from sentence_transformers import SentenceTransformer; SentenceTransformer('all-MiniLM-L6-v2')" \
    && chmod -R a+rX /opt/models

COPY app ./app

# Run as a non-root user.
RUN useradd --create-home --uid 10001 appuser
USER appuser

EXPOSE 8000

# /ping has no model dependency, but app/main.py's lifespan loads the baked-in model into
# memory before the server starts serving, so first-healthy can take a while on a small VM.
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://localhost:8000/ping').status==200 else 1)"

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
