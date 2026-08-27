from __future__ import annotations

import base64
import hashlib
import hmac
import json
import re
import secrets
import time
from collections.abc import Callable


class ServiceJwtProvider:
    """Issues the short-lived service identity accepted by the Java control plane.

    A new token is generated for every internal request.  The original user bearer
    token is never present in this process.
    """

    def __init__(
        self,
        *,
        secret: str,
        subject: str = "workflow-sidecar",
        ttl_seconds: int = 55,
        clock: Callable[[], float] = time.time,
    ) -> None:
        encoded = secret.encode("utf-8")
        if len(encoded) < 32:
            raise ValueError("internal JWT secret must contain at least 32 UTF-8 bytes")
        if not re.fullmatch(r"[A-Za-z0-9_.@:-]{1,128}", subject):
            raise ValueError("service JWT subject must contain 1-128 characters")
        if not 1 <= ttl_seconds <= 60:
            raise ValueError("service JWT ttl must be between 1 and 60 seconds")
        self._secret = encoded
        self._subject = subject
        self._ttl_seconds = ttl_seconds
        self._clock = clock

    def issue(self) -> str:
        now = int(self._clock())
        header = {"alg": "HS256", "typ": "JWT"}
        claims = {
            "iss": "deepresearch-workflow",
            "aud": "deepresearch-internal",
            "sub": self._subject,
            "iat": now,
            "jti": secrets.token_hex(16),
            "exp": now + self._ttl_seconds,
        }
        encoded_header = _b64_json(header)
        encoded_claims = _b64_json(claims)
        signed = f"{encoded_header}.{encoded_claims}"
        signature = hmac.new(self._secret, signed.encode("ascii"), hashlib.sha256).digest()
        return f"{signed}.{_b64(signature)}"

    def authorization_header(self) -> str:
        return f"Bearer {self.issue()}"


def _b64_json(value: dict[str, object]) -> str:
    raw = json.dumps(value, separators=(",", ":"), sort_keys=True).encode("utf-8")
    return _b64(raw)


def _b64(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")
