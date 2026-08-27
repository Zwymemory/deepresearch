"""FastAPI serving entry point for the base or a locally fine-tuned reranker.

The HTTP contract remains compatible with the original service.  Model loading
is deferred to FastAPI startup so configuration and API tests never need to
download weights.
"""

from __future__ import annotations

from contextlib import asynccontextmanager
from dataclasses import dataclass
from typing import Any, List, Protocol, Sequence

from fastapi import FastAPI, Request
from pydantic import BaseModel, Field

from runtime_config import RerankerRuntimeConfig
from training.text_format import format_pair


class Document(BaseModel):
    id: str
    title: str = ""
    content: str = ""


class RerankRequest(BaseModel):
    query: str
    documents: List[Document] = Field(default_factory=list)


class RerankResult(BaseModel):
    id: str
    score: float


class RerankResponse(BaseModel):
    # Keep the historical field name.  It now carries the logical model id so
    # Java can pin either the base model or an evaluated local artifact.
    model: str
    results: List[RerankResult]


class ScoringEngine(Protocol):
    def score(self, pairs: Sequence[tuple[str, str]]) -> Sequence[float]: ...


@dataclass
class TransformersScoringEngine:
    """Thin wrapper around a Hugging Face sequence-classification model."""

    tokenizer: Any
    model: Any
    torch: Any
    device: Any
    max_length: int
    batch_size: int

    @classmethod
    def load(cls, config: RerankerRuntimeConfig) -> "TransformersScoringEngine":
        # Lazy imports keep offline schema/config tests independent of ML wheels.
        import torch
        from transformers import AutoModelForSequenceClassification, AutoTokenizer

        load_options: dict[str, str] = {}
        if config.remote_revision is not None:
            load_options["revision"] = config.remote_revision
        tokenizer = AutoTokenizer.from_pretrained(config.model_source, **load_options)
        model = AutoModelForSequenceClassification.from_pretrained(
            config.model_source,
            **load_options,
        )
        device = _resolve_device(torch, config.device)
        model = model.to(device)
        model.eval()
        return cls(
            tokenizer=tokenizer,
            model=model,
            torch=torch,
            device=device,
            max_length=config.max_length,
            batch_size=config.batch_size,
        )

    def score(self, pairs: Sequence[tuple[str, str]]) -> Sequence[float]:
        scores: list[float] = []
        with self.torch.no_grad():
            for start in range(0, len(pairs), self.batch_size):
                encoded = self.tokenizer(
                    list(pairs[start : start + self.batch_size]),
                    padding=True,
                    truncation=True,
                    max_length=self.max_length,
                    return_tensors="pt",
                )
                encoded = {name: tensor.to(self.device) for name, tensor in encoded.items()}
                logits = self.model(**encoded, return_dict=True).logits.view(-1)
                scores.extend(logits.detach().float().cpu().tolist())
        return scores


def create_app(
    config: RerankerRuntimeConfig | None = None,
    engine: ScoringEngine | None = None,
) -> FastAPI:
    runtime = config or RerankerRuntimeConfig.from_env()

    @asynccontextmanager
    async def lifespan(application: FastAPI):
        if application.state.engine is None:
            application.state.engine = TransformersScoringEngine.load(runtime)
        yield

    application = FastAPI(
        title="DeepResearch Reranker",
        version="0.2.0",
        lifespan=lifespan,
    )
    application.state.runtime_config = runtime
    application.state.engine = engine

    @application.get("/health")
    def health(request: Request) -> dict[str, object | None]:
        current: RerankerRuntimeConfig = request.app.state.runtime_config
        return {
            "status": "ok",
            # `model` is retained for old clients; the remaining fields make a
            # fine-tuned artifact auditable without exposing a local path.
            "model": current.model_id,
            "modelId": current.model_id,
            "baseModelId": current.base_model_id,
            "baseRevision": current.base_revision,
            "manifestSha256": current.manifest_sha256,
            "maxLength": current.max_length,
            "batchSize": current.batch_size,
            "device": str(getattr(request.app.state.engine, "device", current.device)),
        }

    @application.post("/rerank", response_model=RerankResponse)
    def rerank(payload: RerankRequest, request: Request) -> RerankResponse:
        if not payload.documents:
            return RerankResponse(model=runtime.model_id, results=[])

        pairs = [
            format_pair(payload.query, document.title, document.content)
            for document in payload.documents
        ]
        scores = list(request.app.state.engine.score(pairs))
        if len(scores) != len(payload.documents):
            raise RuntimeError("reranker produced a different number of scores than documents")
        results = [
            RerankResult(id=document.id, score=float(score))
            for document, score in zip(payload.documents, scores)
        ]
        results.sort(key=lambda item: (-item.score, item.id))
        return RerankResponse(model=runtime.model_id, results=results)

    return application


def _resolve_device(torch: Any, requested: str):
    if requested == "cuda":
        if not torch.cuda.is_available():
            raise RuntimeError("RERANK_DEVICE=cuda requested but CUDA is unavailable")
        return torch.device("cuda")
    if requested == "mps":
        if not getattr(torch.backends, "mps", None) or not torch.backends.mps.is_available():
            raise RuntimeError("RERANK_DEVICE=mps requested but MPS is unavailable")
        return torch.device("mps")
    if requested == "cpu":
        return torch.device("cpu")
    if torch.cuda.is_available():
        return torch.device("cuda")
    if getattr(torch.backends, "mps", None) and torch.backends.mps.is_available():
        return torch.device("mps")
    return torch.device("cpu")


app = create_app()
