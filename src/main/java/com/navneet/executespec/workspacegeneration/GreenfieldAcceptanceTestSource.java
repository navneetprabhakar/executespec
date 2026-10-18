package com.navneet.executespec.workspacegeneration;

import java.util.List;
import java.util.Locale;

/** Renders immutable server-owned acceptance sources for qualified greenfield adapters. */
final class GreenfieldAcceptanceTestSource {

  private GreenfieldAcceptanceTestSource() {}

  static String pythonAcceptanceTest(
      List<String> requiredPaths, List<String> endpointExpectations) {
    String assertions =
        requiredPaths.stream()
            .map(
                path ->
                    "    assert Path(\""
                        + path
                        + "\").exists(), \"required path missing: "
                        + path
                        + "\"")
            .collect(java.util.stream.Collectors.joining("\n"));
    String entrypoint =
        requiredPaths.stream()
            .filter(path -> path.endsWith(".py"))
            .filter(path -> !path.toLowerCase(Locale.ROOT).contains("test"))
            .findFirst()
            .orElse("main.py");
    String module = entrypoint.substring(0, entrypoint.length() - ".py".length()).replace('/', '.');
    String expectedEndpoints =
        endpointExpectations.stream()
            .map(
                endpoint -> {
                  int split = endpoint.indexOf(' ');
                  return "(\""
                      + endpoint.substring(0, split)
                      + "\", \""
                      + endpoint.substring(split + 1)
                      + "\")";
                })
            .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
    String routeTest =
        endpointExpectations.isEmpty()
            ? ""
            : """


import json
import re
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

APP_TARGET = "%s:app"
EXPECTED_ENDPOINTS = %s


def _canonical(path):
    return re.sub(r"\\{[^/{}]+\\}", "{}", path)


def _concrete(path):
    return re.sub(r"\\{[^/{}]+\\}", "1", path)


def _request(url, method="GET", body=None):
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=data,
        method=method,
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=3) as response:
            return response.status, response.read(65536)
    except urllib.error.HTTPError as failure:
        return failure.code, failure.read(65536)


def _bounded_body(body):
    return body.decode("utf-8", errors="replace")[:4096]


def _application_output(application_log):
    application_log.flush()
    application_log.seek(0)
    return application_log.read(12000)


def _resolve_schema(specification, schema):
    while isinstance(schema, dict) and "$ref" in schema:
        reference = schema["$ref"]
        assert reference.startswith("#/"), f"external schema reference is not executable: {reference}"
        value = specification
        for segment in reference[2:].split("/"):
            value = value[segment.replace("~1", "/").replace("~0", "~")]
        schema = value
    return schema or {}


def _sample_value(specification, schema):
    schema = _resolve_schema(specification, schema)
    if "example" in schema:
        return schema["example"]
    if "default" in schema:
        return schema["default"]
    if schema.get("enum"):
        return schema["enum"][0]
    variants = schema.get("oneOf") or schema.get("anyOf")
    if variants:
        return _sample_value(specification, variants[0])
    value_type = schema.get("type")
    if value_type == "object" or schema.get("properties"):
        properties = schema.get("properties", {})
        required = schema.get("required") or list(properties)
        return {
            name: _sample_value(specification, properties.get(name, {}))
            for name in required
            if not properties.get(name, {}).get("readOnly")
        }
    if value_type == "array":
        return [_sample_value(specification, schema.get("items", {}))]
    if value_type in {"integer", "number"}:
        return max(schema.get("minimum", 1), 1)
    if value_type == "boolean":
        return True
    return "sample"


def _json_schema(operation, section, status=None):
    source = operation.get(section, {})
    if status is not None:
        source = source.get(str(status)) or source.get("default") or {}
    content = source.get("content", {})
    media = content.get("application/json")
    return media.get("schema") if isinstance(media, dict) else None


def _assert_response_shape(specification, payload, schema, location="response"):
    if schema is None:
        return
    schema = _resolve_schema(specification, schema)
    variants = schema.get("oneOf") or schema.get("anyOf")
    if variants:
        assert any(
            _shape_matches(specification, payload, candidate) for candidate in variants
        ), f"{location} does not match any declared schema"
        return
    assert _shape_matches(specification, payload, schema), f"{location} violates declared schema"


def _shape_matches(specification, payload, schema):
    schema = _resolve_schema(specification, schema)
    if schema.get("nullable") and payload is None:
        return True
    value_type = schema.get("type")
    if value_type == "object" or schema.get("properties"):
        if not isinstance(payload, dict):
            return False
        if not all(name in payload for name in schema.get("required", [])):
            return False
        return all(
            name not in payload or _shape_matches(specification, payload[name], property_schema)
            for name, property_schema in schema.get("properties", {}).items()
        )
    if value_type == "array":
        return isinstance(payload, list) and all(
            _shape_matches(specification, value, schema.get("items", {})) for value in payload
        )
    if value_type == "string":
        return isinstance(payload, str)
    if value_type == "integer":
        return isinstance(payload, int) and not isinstance(payload, bool)
    if value_type == "number":
        return isinstance(payload, (int, float)) and not isinstance(payload, bool)
    if value_type == "boolean":
        return isinstance(payload, bool)
    return True


def _live_application():
    listener = socket.socket()
    listener.bind(("127.0.0.1", 0))
    port = listener.getsockname()[1]
    listener.close()
    application_log = tempfile.TemporaryFile(mode="w+t")
    process = subprocess.Popen(
        [
            sys.executable,
            "-m",
            "uvicorn",
            APP_TARGET,
            "--host",
            "127.0.0.1",
            "--port",
            str(port),
            "--log-level",
            "warning",
        ],
        stdout=application_log,
        stderr=subprocess.STDOUT,
        text=True,
    )
    base = f"http://127.0.0.1:{port}"
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        if process.poll() is not None:
            output = _application_output(application_log)
            application_log.close()
            raise AssertionError(f"application exited before readiness: {output}")
        try:
            status, body = _request(base + "/openapi.json")
            if status == 200:
                return process, base, json.loads(body), application_log
        except (OSError, ValueError):
            pass
        time.sleep(0.1)
    process.terminate()
    process.wait(timeout=3)
    output = _application_output(application_log)
    application_log.close()
    raise AssertionError(f"application readiness timed out: {output}")


def test_required_http_routes_run_and_are_reachable():
    process, base, specification, application_log = _live_application()
    try:
        paths = specification.get("paths", {})
        declared = {
            (method.upper(), _canonical(path))
            for path, operations in paths.items()
            for method in operations
            if method.upper() in {"GET", "POST", "PUT", "PATCH", "DELETE"}
        }
        ordered = sorted(
            EXPECTED_ENDPOINTS,
            key=lambda endpoint: {"POST": 0, "PUT": 1, "PATCH": 1, "GET": 2, "DELETE": 3}.get(endpoint[0], 4),
        )
        created_identifier = None
        for method, path in ordered:
            canonical = _canonical(path)
            assert (method, canonical) in declared, f"runtime route missing: {method} {path}"
            declared_path = next(candidate for candidate in paths if _canonical(candidate) == canonical)
            operation = paths[declared_path][method.lower()]
            request_schema = _json_schema(operation, "requestBody")
            body = _sample_value(specification, request_schema) if request_schema else None
            concrete = re.sub(
                r"\\{[^/{}]+\\}",
                str(created_identifier if created_identifier is not None else 1),
                path,
            )
            status, response = _request(base + concrete, method, body)
            print(json.dumps({
                "event": "runtime_request",
                "method": method,
                "path": concrete,
                "request": body,
                "status": status,
                "response": _bounded_body(response),
            }, sort_keys=True), flush=True)
            assert status not in {404, 405}, f"runtime route unreachable: {method} {path}: {status}"
            assert status < 500, f"runtime route crashed: {method} {path}: {status}: {response!r}"
            assert status < 400, f"runtime contract rejected valid synthesized input: {method} {path}: {status}: {response!r}"
            declared_responses = operation.get("responses", {})
            assert str(status) in declared_responses or "default" in declared_responses, (
                f"runtime status is absent from live OpenAPI: {method} {path}: {status}"
            )
            response_schema = _json_schema(operation, "responses", status)
            if response:
                try:
                    payload = json.loads(response)
                except ValueError:
                    assert response_schema is None, f"runtime response is not JSON: {method} {path}"
                else:
                    _assert_response_shape(specification, payload, response_schema, f"{method} {path}")
                    if method == "POST" and isinstance(payload, dict):
                        for key in ("id", "_id", "todo_id", "item_id"):
                            if key in payload:
                                created_identifier = payload[key]
                                break
                    if created_identifier is not None and method == "GET":
                        if canonical == _canonical(next(
                            expected_path
                            for expected_method, expected_path in EXPECTED_ENDPOINTS
                            if expected_method == "POST"
                        )):
                            assert str(created_identifier) in json.dumps(payload), (
                                f"created resource {created_identifier} is absent from list response"
                            )
                        if "{}" in canonical and isinstance(payload, dict):
                            returned_identifier = next(
                                (payload[key] for key in ("id", "_id", "todo_id", "item_id") if key in payload),
                                created_identifier,
                            )
                            assert str(returned_identifier) == str(created_identifier), (
                                f"retrieved resource identity changed: {returned_identifier}"
                            )
            if method == "DELETE" and "{}" in canonical:
                matching_get = any(
                    expected_method == "GET" and _canonical(expected_path) == canonical
                    for expected_method, expected_path in EXPECTED_ENDPOINTS
                )
                if matching_get:
                    absent_status, absent_response = _request(base + concrete, "GET")
                    print(json.dumps({
                        "event": "runtime_absence_check",
                        "method": "GET",
                        "path": concrete,
                        "status": absent_status,
                        "response": _bounded_body(absent_response),
                    }, sort_keys=True), flush=True)
                    assert absent_status in {404, 410}, (
                        f"deleted resource remains retrievable: GET {concrete}: {absent_status}"
                    )
    finally:
        process.terminate()
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=3)
        assert process.poll() is not None, "application process could not be terminated"
        print(json.dumps({
            "event": "runtime_application_log",
            "output": _application_output(application_log),
        }, sort_keys=True), flush=True)
        application_log.close()
"""
                .formatted(module, expectedEndpoints);
    return "from pathlib import Path\n\n\ndef test_required_task_paths_exist():\n"
        + assertions
        + "\n"
        + routeTest;
  }

  static String nodeAcceptanceTest(List<String> requiredPaths) {
    String assertions =
        requiredPaths.stream()
            .map(
                path ->
                    "  assert.ok(existsSync(\""
                        + path
                        + "\"), \"required path missing: "
                        + path
                        + "\");")
            .collect(java.util.stream.Collectors.joining("\n"));
    return "import test from \"node:test\";\n"
        + "import assert from \"node:assert/strict\";\n"
        + "import { existsSync, readdirSync } from \"node:fs\";\n"
        + "import { join } from \"node:path\";\n"
        + "import { spawnSync } from \"node:child_process\";\n\n"
        + "test(\"required task paths exist\", () => {\n"
        + assertions
        + "\n});\n"
        + """

// Execute maintained candidate tests separately; the immutable path test is not
// behavioral evidence. Never recurse into this verifier or dependency directories.
function testsIn(directory) {
  return readdirSync(directory, {withFileTypes: true}).flatMap(entry => {
    if (entry.isSymbolicLink() || entry.name.startsWith('.') ||
        ['node_modules', 'dist', 'build', 'coverage'].includes(entry.name)) return [];
    const path = join(directory, entry.name);
    if (entry.isDirectory()) return testsIn(path);
    return /(?:\\.test|\\.spec)\\.[cm]?js$/.test(entry.name) ||
        /^test_.*\\.[cm]?js$/.test(entry.name) ? [path] : [];
  });
}

test('maintained candidate behavior tests pass', () => {
  const files = testsIn('.').sort();
  assert.ok(files.length > 0, 'No maintained candidate behavior tests found');
            const environment = {...process.env};
            delete environment.NODE_TEST_CONTEXT;
  const result = spawnSync(process.execPath, ['--test', '--test-reporter=tap', ...files], {
              encoding: 'utf8', timeout: 480000, maxBuffer: 4 * 1024 * 1024, env: environment
  });
  assert.ifError(result.error);
  assert.equal(result.status, 0, result.stdout + result.stderr);
  assert.match(result.stdout, /^# pass [1-9][0-9]*$/m,
    'Candidate suite must execute at least one passing behavior test');
});
""";
  }
}
