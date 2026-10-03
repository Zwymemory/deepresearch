from __future__ import annotations

import os
import socket
from typing import Literal

from pydantic import AliasChoices, Field, SecretStr, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Runtime configuration. Secrets remain wrapped and are never returned by health APIs."""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    database_url: str = Field(
        default="postgresql://deepresearch:deepresearch@postgres:5432/deepresearch",
        validation_alias=AliasChoices("DATABASE_URL", "WORKFLOW_DATABASE_URL"),
    )
    instance_id: str = Field(default_factory=lambda: socket.gethostname())
    runner_enabled: bool = True
    poll_interval_seconds: float = Field(default=1.0, ge=0.05, le=30.0)
    max_concurrent_runs: int = Field(default=2, ge=1, le=8)
    worker_max_concurrency: int = Field(default=2, ge=1, le=2)
    lease_seconds: int = Field(default=30, ge=15, le=300)
    heartbeat_seconds: int = Field(default=10, ge=2, le=60)
    checkpointer_schema: str = "langgraph"
    checkpointer_setup: bool = True

    java_base_url: str = "http://app:8080"
    mcp_url: str = "http://app:8080/mcp/sse"
    internal_jwt_secret: SecretStr = Field(
        default=SecretStr(""),
        validation_alias=AliasChoices(
            "DEEPRESEARCH_INTERNAL_JWT_SECRET", "WORKFLOW_INTERNAL_JWT_SECRET"
        ),
    )
    internal_service_id: str = "workflow-sidecar"
    internal_token_ttl_seconds: int = Field(default=55, ge=1, le=60)
    http_timeout_seconds: float = Field(default=20.0, ge=1.0, le=60.0)

    model_provider: Literal["openai", "disabled"] = "openai"
    model_name: str = "gpt-5-mini"
    agent_result_transport: Literal["function_call", "deepseek_json_object"] = "function_call"
    openai_api_key: SecretStr = Field(
        default=SecretStr(""),
        validation_alias=AliasChoices("OPENAI_API_KEY", "WORKFLOW_OPENAI_API_KEY"),
    )
    openai_base_url: str | None = None
    model_input_cost_per_million: float = Field(default=1.0, ge=0)
    model_output_cost_per_million: float = Field(default=2.0, ge=0)
    model_max_attempts: int = Field(default=2, ge=1, le=3)
    model_retry_backoff_seconds: float = Field(default=0.5, ge=0.0, le=5.0)

    max_tasks: int = Field(default=4, ge=1, le=4)
    max_revision_rounds: int = Field(default=1, ge=0, le=1)
    max_model_calls: int = Field(default=24, ge=1, le=24)
    max_tool_calls: int = Field(default=16, ge=1, le=16)
    max_tokens: int = Field(default=100_000, ge=1_000, le=100_000)
    max_cost_cny: float = Field(default=1.0, ge=0.01, le=1.0)
    default_timeout_seconds: int = Field(default=120, ge=10, le=120)

    @field_validator("checkpointer_schema")
    @classmethod
    def safe_schema_identifier(cls, value: str) -> str:
        if not value.replace("_", "").isalnum() or not value[0].isalpha():
            raise ValueError("checkpointer_schema must be a simple SQL identifier")
        return value

    @property
    def internal_secret(self) -> str:
        return self.internal_jwt_secret.get_secret_value()

    @property
    def openai_key(self) -> str:
        return self.openai_api_key.get_secret_value()

    def agent_transport_supported(self) -> bool:
        return self.agent_result_transport == "function_call" or (
            self.agent_result_transport == "deepseek_json_object"
            and self.model_provider == "openai"
            and self.model_name == "deepseek-flash"
            and self.openai_base_url == "https://api.deepseek.com"
        )

    def validate_runtime(self) -> list[str]:
        errors: list[str] = []
        if not self.agent_transport_supported():
            errors.append("Agent result transport capability configuration is unsupported")
        if self.heartbeat_seconds >= self.lease_seconds:
            errors.append("heartbeat_seconds must be lower than lease_seconds")
        if self.runner_enabled and len(self.internal_secret.encode("utf-8")) < 32:
            errors.append("DEEPRESEARCH_INTERNAL_JWT_SECRET must contain at least 32 UTF-8 bytes")
        if self.runner_enabled and self.model_provider == "openai" and not self.openai_key:
            errors.append("OPENAI_API_KEY is required for the openai model provider")
        if self.runner_enabled and self.model_provider == "disabled":
            errors.append("MODEL_PROVIDER=disabled requires RUNNER_ENABLED=false")
        return errors


def harden_langgraph_deserialization() -> None:
    """Disable permissive checkpoint deserialization before a checkpointer is created."""

    os.environ.setdefault("LANGGRAPH_STRICT_MSGPACK", "true")
