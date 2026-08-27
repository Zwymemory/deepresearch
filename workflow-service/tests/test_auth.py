from __future__ import annotations

import base64
import hashlib
import hmac
import json

from deepresearch_workflow.auth import ServiceJwtProvider


def _decode(value: str) -> dict[str, object]:
    padded = value + "=" * (-len(value) % 4)
    return json.loads(base64.urlsafe_b64decode(padded))


def test_service_jwt_matches_java_contract_and_rotates_jti() -> None:
    secret = "a" * 32
    provider = ServiceJwtProvider(secret=secret, ttl_seconds=55, clock=lambda: 1_700_000_000)

    first = provider.issue()
    second = provider.issue()
    header_part, claims_part, signature = first.split(".")
    claims = _decode(claims_part)

    assert _decode(header_part) == {"alg": "HS256", "typ": "JWT"}
    assert claims["iss"] == "deepresearch-workflow"
    assert claims["aud"] == "deepresearch-internal"
    assert claims["sub"] == "workflow-sidecar"
    assert claims["exp"] - claims["iat"] == 55
    assert _decode(second.split(".")[1])["jti"] != claims["jti"]

    signed = f"{header_part}.{claims_part}"
    expected = base64.urlsafe_b64encode(
        hmac.new(secret.encode(), signed.encode(), hashlib.sha256).digest()
    ).rstrip(b"=").decode()
    assert signature == expected
