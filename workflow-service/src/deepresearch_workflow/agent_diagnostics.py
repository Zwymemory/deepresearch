"""Fixed validation metadata vocabulary; never exception messages or model content."""

REQUIREMENT_ERROR_CODES = frozenset(
    {
        "REQUIREMENTS_CHANGED",
        "REQUIREMENTS_MISSING_OR_LIMIT",
        "REQUIREMENT_ANCHOR_INVALID",
        "REQUIREMENT_BINDING_CHANGED",
        "REQUIREMENT_BINDING_DUPLICATE",
        "REQUIREMENT_BINDING_INVALID",
        "REQUIREMENT_CHECK_BINDING_INVALID",
        "REQUIREMENT_CHECK_CLAIM_REUSED",
        "REQUIREMENT_CHECK_STATE_INVALID",
        "REQUIREMENT_CLAIM_SCOPE_CHANGED",
        "REQUIREMENT_CRITERION_DUPLICATE",
        "REQUIREMENT_CRITERION_INVALID",
        "REQUIREMENT_CRITERION_MISSING",
        "REQUIREMENT_CRITERION_REUSED",
        "REQUIREMENT_DUPLICATE",
        "REQUIREMENT_DUPLICATE_SPAN",
        "REQUIREMENT_EXPECTED_CLAIM_INVALID",
        "REQUIREMENT_IDENTITY_CHANGED",
        "REQUIREMENT_INVALID",
        "REQUIREMENT_MANIFEST_INVALID",
        "REQUIREMENT_ORIGINAL_BINDING_CHANGED",
        "REQUIREMENT_QUESTION_INVALID",
        "REQUIREMENT_QUESTION_LIMIT",
        "REQUIREMENT_QUESTION_REGION_UNASSIGNED",
        "REQUIREMENT_PLANNER_VERSION_INVALID",
        "REQUIREMENT_SEGMENT_INVALID",
        "REQUIREMENT_SEGMENT_DUPLICATE",
        "REQUIREMENT_SEGMENT_UNKNOWN",
        "REQUIREMENT_SEGMENT_BINDING_INVALID",
        "REQUIREMENT_DECLARATION_LIMIT",
        "REQUIREMENT_RUN_INVALID",
        "REQUIREMENT_TASK_INVALID",
        "REQUIREMENT_TASK_LIMIT",
        "REQUIREMENT_UNKNOWN",
    }
)
VALIDATION_STAGES = frozenset(
    {
        "result_schema",
        "domain_validation",
        "planning_decision",
        "planning_requirements",
    }
)
CHECK_ERROR_CODES = frozenset(
    {
        "CHECK_TOO_LARGE",
        "CHECK_JSON_INVALID",
        "CHECK_RESPONSE_INVALID",
        "CHECK_REQUEST_BINDING_INVALID",
        "CHECK_REQUEST_INVALID",
        "CHECK_SNAPSHOT_CHANGED",
        "CHECK_QUOTE_BINDING_INVALID",
        "CHECK_QUOTE_INVALID",
        "CHECK_QUOTE_CONTEXT_INCOMPLETE",
        "CHECK_DUPLICATE_JSON_KEY",
        "CHECK_NONFINITE_JSON",
        "CHECK_CLAIM_BINDING_INVALID",
        "CHECK_EVIDENCE_BINDING_INVALID",
        "CHECK_ACTION_INVALID",
    }
)


def safe_requirement_code(value):
    return value if type(value) is str and value in REQUIREMENT_ERROR_CODES else None


def safe_validation_stage(value):
    return value if type(value) is str and value in VALIDATION_STAGES else None


def safe_domain_code(value):
    return (
        value
        if type(value) is str and value in REQUIREMENT_ERROR_CODES | CHECK_ERROR_CODES
        else None
    )


def safe_check_code(value):
    return value if type(value) is str and value in CHECK_ERROR_CODES else None


def safe_segment_diagnostic(value):
    """Only a bounded server mapping hash/count/ranges; never arbitrary values or IDs."""
    if type(value) is not dict or set(value) != {
        "mapping_version", "mapping_sha256", "missing_count", "missing_ranges"
    }:
        return None
    digest, count, ranges = value["mapping_sha256"], value["missing_count"], value["missing_ranges"]
    if (value["mapping_version"] != "agent-question-segments/1"
            or type(digest) is not str or len(digest) != 64
            or any(c not in "0123456789abcdef" for c in digest)
            or type(count) is not int or not 1 <= count <= 16
            or type(ranges) is not list or len(ranges) != count):
        return None
    previous = -1
    for span in ranges:
        if (type(span) is not dict or set(span) != {"start", "end"}
                or type(span["start"]) is not int or type(span["end"]) is not int
                or not 0 <= span["start"] < span["end"] <= 4000
                or span["start"] < previous):
            return None
        previous = span["end"]
    return {"mapping_version": value["mapping_version"], "mapping_sha256": digest,
            "missing_count": count, "missing_ranges": [dict(span) for span in ranges]}
