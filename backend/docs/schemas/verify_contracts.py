"""Validate Phase 0 schemas and positive/negative fixtures offline.

Dependencies: pip install -r docs/schemas/requirements-test.txt
This does not start the BE, connect to MQTT, or verify physical hardware.
"""

import json
import re
from collections import Counter
from pathlib import Path
from urllib.parse import urldefrag, urljoin

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource


ROOT = Path(__file__).resolve().parent


def load_json(path):
    def reject_constant(value):
        raise ValueError(f"Non-JSON constant {value} in {path}")

    def unique_keys(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"Duplicate JSON key {key} in {path}")
            result[key] = value
        return result

    return json.loads(path.read_text(encoding="utf-8"),
                      parse_constant=reject_constant, object_pairs_hook=unique_keys)


def check_refs(node, owner_id, by_id):
    if isinstance(node, dict):
        if "$ref" in node:
            target, pointer = urldefrag(urljoin(owner_id, node["$ref"]))
            if target not in by_id:
                raise AssertionError(f"Unregistered reference: {node['$ref']}")
            resolved = by_id[target]
            if pointer:
                if not pointer.startswith("/"):
                    raise AssertionError(f"Unsupported reference anchor: {pointer}")
                for token in pointer[1:].split("/"):
                    token = token.replace("~1", "/").replace("~0", "~")
                    resolved = resolved[int(token)] if isinstance(resolved, list) else resolved[token]
        for child in node.values():
            check_refs(child, owner_id, by_id)
    elif isinstance(node, list):
        for child in node:
            check_refs(child, owner_id, by_id)


def main():
    paths = sorted(ROOT.glob("*.schema.json")) + [ROOT / "be-step-contracts.json"]
    schemas = {path.name: load_json(path) for path in paths}
    by_id = {schema["$id"]: schema for schema in schemas.values()}
    assert len(by_id) == len(schemas), "Duplicate schema IDs"
    for filename, schema in schemas.items():
        Draft202012Validator.check_schema(schema)
        assert schema["x-provenance"]["status"] in {
            "RECONSTRUCTED_UNVERIFIED_PI", "PLANNED_BE_SCHEMA_3", "IMPLEMENTED_BE_SCHEMA_3_CALLBACK", "IMPLEMENTED_BE_SCHEMA_3_ARTIFACT"
        }
        if schema["x-provenance"]["status"] == "IMPLEMENTED_BE_SCHEMA_3_CALLBACK":
            assert filename == "staff-step-response.schema.json", "Only the BE callback is implemented in Phase 4"
        if schema["x-provenance"]["status"] == "IMPLEMENTED_BE_SCHEMA_3_ARTIFACT":
            assert filename == "mapping-artifact.schema.json", "Phase 12 artifact/receipt BE implementation"
        check_refs(schema, schema["$id"], by_id)

    # All resources are registered locally. Unknown references fail, never fetch over HTTP.
    registry = Registry().with_resources(
        (schema_id, Resource.from_contents(schema)) for schema_id, schema in by_id.items()
    )
    checker = FormatChecker()
    assert not checker.conforms("2026-10-12T00:00:00", "date-time"), "date-time checker missing"
    validators = {
        name: Draft202012Validator(schema, registry=registry, format_checker=checker)
        for name, schema in schemas.items()
    }

    cases = load_json(ROOT / "fixtures" / "contract-cases.json")
    names = set()
    counts = Counter()
    failures = []
    for case in cases:
        identity = (case["schema"], case["name"])
        assert identity not in names, f"Duplicate fixture: {identity}"
        names.add(identity)
        assert isinstance(case["valid"], bool), f"Missing expected outcome: {identity}"
        errors = list(validators[case["schema"]].iter_errors(case["instance"]))
        counts["valid" if case["valid"] else "invalid"] += 1
        if (not errors) != case["valid"]:
            detail = errors[0].message if errors else "invalid fixture unexpectedly accepted"
            failures.append(f"{identity}: {detail}")

    expected_types = {
        "MOVE", "INTERACT", "DATA_SERVICE", "AUTHENTICATE", "CONTROL_HARDWARE",
        "HANDLE_PAYLOAD", "EXECUTE_SENSING", "NOTIFY", "WAIT", "CHECK_CONDITION", "SET_CONTEXT"
    }
    enum_path = ROOT.parents[1] / "src/main/java/com/mediassistrover/backend/enums/StepType.java"
    runtime_types = set(re.findall(r"^\s+([A-Z_]+)\(", enum_path.read_text(encoding="utf-8"), re.M))
    assert runtime_types == expected_types, f"Runtime StepType baseline changed: {runtime_types}"
    covered = {
        case["instance"]["stepType"] for case in cases
        if case["valid"] and case["schema"] in {
            "pi-step-inputs.schema.json", "be-step-contracts.json"
        }
    }
    assert covered == expected_types, f"StepType coverage mismatch: {covered}"
    executable_schemas = set(schemas) - {"common.schema.json"}
    for name in executable_schemas:
        assert any(c["schema"] == name and c["valid"] for c in cases), f"No positive fixture: {name}"
        assert any(c["schema"] == name and not c["valid"] for c in cases), f"No negative fixture: {name}"
    if failures:
        raise AssertionError("\n".join(failures))
    print(f"PASS: {len(schemas)} schemas, {len(cases)} fixtures "
          f"({counts['valid']} positive, {counts['invalid']} negative), 11 StepTypes; offline refs/formats checked.")


if __name__ == "__main__":
    main()
