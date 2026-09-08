"""Bounded MCP stdio server (2025-11-25). Only a reviewed fixture model is executable.

JSON-RPC stdout contains protocol messages only; Docker output is captured, never leaked.
This catalog demonstrates external-library provisioning, not public internet model search.
"""
import json
from pathlib import Path
import subprocess
import sys
import uuid

ROOT = Path(__file__).resolve().parent
CATALOG = json.loads((ROOT / "catalog.json").read_text(encoding="utf-8"))
IMAGE = CATALOG["runtime"]["image"]
PROTOCOL = "2025-11-25"


def tool(name, description, properties, required):
    return {"name": name, "description": description, "inputSchema": {
        "type": "object", "properties": properties, "required": required, "additionalProperties": False}}


MODEL = {"type": "string", "enum": [CATALOG["modelId"]]}
TOOLS = [
    tool("search_models", "Search the experiment model catalog by capability", {"capability": {"type": "string"}}, ["capability"]),
    tool("describe_model", "Read ports, units, required parameters and provenance", {"modelId": MODEL}, ["modelId"]),
    tool("provision_model", "Build reviewed Docker adapter; obtain pinned SimPy from PyPI", {"modelId": MODEL}, ["modelId"]),
    tool("run_processor", "Run provisioned model in a bounded, network-disabled container",
         {"modelId": MODEL, "payload": {"type": "object"}}, ["modelId", "payload"]),
]


def docker(*args, input_text=None, timeout=120):
    result = subprocess.run(["docker", *args], input=input_text, text=True, encoding="utf-8",
                            capture_output=True, timeout=timeout, shell=False)
    if result.returncode:
        raise RuntimeError(f"docker {args[0]} failed: {result.stderr[-5000:]}")
    return result.stdout + result.stderr if args[0] == "build" else result.stdout


def call_tool(name, args):
    schema = next((t["inputSchema"] for t in TOOLS if t["name"] == name), None)
    if schema is None:
        raise ValueError("Unknown tool")
    if set(args) - set(schema["properties"]) or not set(schema["required"]).issubset(args):
        raise ValueError("Unexpected or missing arguments")
    if name == "search_models":
        return {"scope": "local-experiment-catalog", "models": [CATALOG] if args["capability"] == CATALOG["capability"] else []}
    if args["modelId"] != CATALOG["modelId"]:
        raise ValueError("Model is not in the execution allowlist")
    if name == "describe_model":
        return CATALOG
    if name == "provision_model":
        log = docker("build", "--label", "ses.experiment=queue-mcp", "-t", IMAGE, str(ROOT / "processor"), timeout=600)
        info = json.loads(docker("image", "inspect", IMAGE))[0]
        return {"image": IMAGE, "imageId": info["Id"], "source": CATALOG["source"], "buildOutput": log[-3000:]}
    # Use immutable image ID and verify provenance label, never trust an LLM-supplied command.
    info = json.loads(docker("image", "inspect", IMAGE))[0]
    if (info.get("Config", {}).get("Labels") or {}).get("ses.experiment") != "queue-mcp":
        raise ValueError("Image has not been provisioned by this experiment")
    payload = json.dumps(args["payload"], ensure_ascii=False, allow_nan=False)
    if len(payload.encode("utf-8")) > 200000:
        raise ValueError("Payload too large")
    container_name = "ses-queue-" + uuid.uuid4().hex
    try:
        output = docker("run", "--rm", "--name", container_name, "-i", "--network=none", "--read-only", "--cap-drop=ALL",
                        "--security-opt=no-new-privileges", "--memory=128m", "--cpus=1", "--pids-limit=32",
                        info["Id"], input_text=payload, timeout=60)
    except subprocess.TimeoutExpired:
        # Stopping the CLI alone does not stop the daemon's container.
        docker("rm", "--force", container_name, timeout=15)
        raise
    result = json.loads(output)
    result["imageId"] = info["Id"]
    return result


def serve():
    initialized = False
    for line in sys.stdin:
        req = None
        try:
            req = json.loads(line)
            if not isinstance(req, dict) or req.get("jsonrpc") != "2.0":
                raise ValueError("Invalid JSON-RPC request")
            method = req.get("method")
            if "id" not in req:
                continue
            if method == "initialize":
                initialized = True
                result = {"protocolVersion": PROTOCOL, "capabilities": {"tools": {"listChanged": False}},
                          "serverInfo": {"name": "queue-experiment-models", "version": "1.0.0"}}
            elif method == "ping":
                result = {}
            elif not initialized:
                raise ValueError("Initialize first")
            elif method == "tools/list":
                result = {"tools": TOOLS}
            elif method == "tools/call":
                try:
                    value = call_tool(req["params"]["name"], req["params"].get("arguments", {}))
                    result = {"content": [{"type": "text", "text": json.dumps(value, ensure_ascii=False)}],
                              "structuredContent": value, "isError": False}
                except (ValueError, RuntimeError, subprocess.TimeoutExpired, KeyError, OSError) as error:
                    result = {"content": [{"type": "text", "text": str(error)}], "isError": True}
            else:
                print(json.dumps({"jsonrpc": "2.0", "id": req["id"], "error": {"code": -32601, "message": "Method not found"}}), flush=True)
                continue
            reply = {"jsonrpc": "2.0", "id": req["id"], "result": result}
        except (ValueError, KeyError, TypeError) as error:
            reply = {"jsonrpc": "2.0", "id": req.get("id") if isinstance(req, dict) else None,
                     "error": {"code": -32600, "message": str(error)}}
        print(json.dumps(reply, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    sys.stdin.reconfigure(encoding="utf-8")
    sys.stdout.reconfigure(encoding="utf-8")
    serve()
