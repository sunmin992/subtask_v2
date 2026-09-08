"""Local LLM -> constrained SES -> existing dialogue API -> MCP -> Docker simulator.

Standard-library client; the only downloaded simulation dependency runs inside Docker.
The execution adapter is a feed-forward event-batch composition, not distributed DEVS.
"""
import argparse
from contextlib import AbstractContextManager
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import queue
import subprocess
import sys
import threading
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import uuid

ROOT = Path(__file__).resolve().parent
MODEL_ID = "external-simpy-fifo"
PARAMS = {
    "arrivalInterval": {"role": "SOURCE", "type": "DOUBLE", "unit": "분", "range": {"min": 0.01, "max": 10, "allowed": []}, "question": "작업이 몇 분 간격으로 도착하나요?"},
    "totalJobs": {"role": "SOURCE", "type": "INT", "unit": "건", "range": {"min": 1, "max": 100, "allowed": []}, "question": "총 몇 건의 작업을 생성할까요?"},
}
DRAFT_SCHEMA = {
    "type": "object", "additionalProperties": False,
    "required": ["title", "supported", "reason", "nodes", "connections", "assumptions"],
    "properties": {
        "title": {"type": "string"}, "supported": {"type": "boolean"}, "reason": {"type": "string"},
        "nodes": {"type": "array", "minItems": 3, "maxItems": 3, "items": {
            "type": "object", "additionalProperties": False, "required": ["id", "name", "role"],
            "properties": {"id": {"type": "string"}, "name": {"type": "string"}, "role": {"type": "string", "enum": ["SOURCE", "PROCESSOR", "SINK"]}}}},
        "connections": {"type": "array", "minItems": 2, "maxItems": 2, "items": {
            "type": "object", "additionalProperties": False, "required": ["from", "to"],
            "properties": {"from": {"type": "string"}, "to": {"type": "string"}}}},
        "assumptions": {"type": "array", "items": {"type": "string"}}
    }
}


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False), encoding="utf-8")


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def http(url, body=None, timeout=30):
    data = None if body is None else json.dumps(body, ensure_ascii=False, allow_nan=False).encode("utf-8")
    try:
        with urlopen(Request(url, data=data, headers={"Content-Type": "application/json; charset=utf-8"}), timeout=timeout) as response:
            return json.load(response)
    except HTTPError as error:
        raise RuntimeError(f"HTTP {error.code} {url}: {error.read().decode('utf-8')}") from error


class Mcp(AbstractContextManager):
    def __init__(self, output):
        self.output = output
        self.process = subprocess.Popen([sys.executable, str(ROOT / "mcp_server.py")], stdin=subprocess.PIPE,
                                        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding="utf-8")
        self.replies = queue.Queue()
        self.counter = 0
        threading.Thread(target=self._read, daemon=True).start()
        result = self.request("initialize", {"protocolVersion": "2025-11-25", "capabilities": {},
                                            "clientInfo": {"name": "ses-queue-experiment", "version": "1.0.0"}})
        if result["protocolVersion"] != "2025-11-25":
            raise ValueError("Unsupported MCP version")
        self.send({"jsonrpc": "2.0", "method": "notifications/initialized"})
        self.request("tools/list", {})

    def _read(self):
        for line in self.process.stdout:
            self.replies.put(line)
        self.replies.put(None)

    def send(self, value):
        with (self.output / "mcp.jsonl").open("a", encoding="utf-8") as log:
            log.write(json.dumps({"direction": "request", "message": value}, ensure_ascii=False) + "\n")
        self.process.stdin.write(json.dumps(value, ensure_ascii=False) + "\n")
        self.process.stdin.flush()

    def request(self, method, params, timeout=650):
        self.counter += 1
        self.send({"jsonrpc": "2.0", "id": self.counter, "method": method, "params": params})
        try:
            line = self.replies.get(timeout=timeout)
        except queue.Empty as error:
            raise TimeoutError("MCP response timed out") from error
        if line is None:
            raise RuntimeError("MCP server exited")
        reply = json.loads(line)
        with (self.output / "mcp.jsonl").open("a", encoding="utf-8") as log:
            log.write(json.dumps({"direction": "response", "message": reply}, ensure_ascii=False) + "\n")
        if reply.get("id") != self.counter or "error" in reply:
            raise RuntimeError(f"Invalid MCP response: {reply}")
        return reply["result"]

    def call(self, name, **arguments):
        result = self.request("tools/call", {"name": name, "arguments": arguments})
        if result.get("isError"):
            raise RuntimeError(result["content"][0]["text"])
        return result["structuredContent"]

    def __exit__(self, *args):
        self.process.stdin.close()
        try:
            self.process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait()
        self.process.stdout.close()
        self.process.stderr.close()


def validate_draft(draft):
    if draft.get("supported") is not True:
        raise ValueError("요청이 실험 범위를 벗어납니다: " + str(draft.get("reason")))
    nodes = draft.get("nodes", [])
    if len(nodes) != 3 or {n.get("role") for n in nodes} != {"SOURCE", "PROCESSOR", "SINK"}:
        raise ValueError("Exactly one source, FIFO processor and sink are required")
    ids = [n.get("id") for n in nodes]
    if len(set(ids)) != 3 or any(not isinstance(i, str) or not i.isascii() or not i.replace("-", "").isalnum() or i in {"queue-root", "queue-components"} for i in ids):
        raise ValueError("Node IDs must be unique safe identifiers")
    names = [n.get("name") for n in nodes]
    if len(set(names)) != 3 or any(not isinstance(n, str) or not n.strip() or len(n) > 60 or any(c in n for c in '/[]#') for n in names):
        raise ValueError("Node names must be unique path-safe names")
    roles = {n["role"]: n for n in nodes}
    expected = {(roles["SOURCE"]["id"], roles["PROCESSOR"]["id"]), (roles["PROCESSOR"]["id"], roles["SINK"]["id"])}
    links = draft.get("connections", [])
    if len(links) != 2 or {(c.get("from"), c.get("to")) for c in links} != expected:
        raise ValueError("Only SOURCE -> PROCESSOR -> SINK is supported; cycles and bypasses rejected")
    return roles


def validate_model(model):
    if model.get("modelId") != MODEL_ID or model.get("capability") != "fifo-single-server" or model.get("timeUnit") != "분":
        raise ValueError("Model capability or time unit mismatch")
    for direction in ("in", "out"):
        if model["ports"][direction] != {"name": direction, "dataType": "job", "unit": "건"}:
            raise ValueError("Model port contract mismatch")
    if model.get("limits") != {"maxJobs": 100, "servers": 1, "discipline": "FIFO", "termination": "drain-all-jobs"}:
        raise ValueError("Model execution semantics mismatch")
    params = model["parameters"]
    if len(params) != 1 or params[0]["name"] != "serviceTime" or params[0]["type"] != "DOUBLE" or params[0]["unit"] != "분" or params[0]["required"] is not True:
        raise ValueError("Unsupported model parameter contract")
    if params[0]["range"] != {"min": 0.01, "max": 10, "allowed": []}:
        raise ValueError("Unsupported model parameter range")


def compile_ses(draft, model, key):
    """LLM chooses named components and wiring; compiler supplies reviewed SES contracts."""
    roles = validate_draft(draft)
    validate_model(model)
    specs = dict(PARAMS)
    specs.update({p["name"]: {**p, "role": "PROCESSOR"} for p in model["parameters"]})
    components, slots = [], []
    refs = {"SOURCE": "queue-local-source", "PROCESSOR": MODEL_ID, "SINK": "queue-local-sink"}
    for node in draft["nodes"]:
        variables = []
        for name, spec in specs.items():
            if spec["role"] != node["role"]:
                continue
            variables.append({"name": name, "type": spec["type"], "unit": spec["unit"], "value": None,
                              "defaultValue": None, "inferable": False, "range": spec["range"]})
            slots.append({"kind": "VALUE", "name": name,
                          "anchor": {"entityPath": f"대기행렬/{node['name']}", "axis": "VARIABLE", "targetNodeId": node["id"], "varName": name},
                          "type": spec["type"], "unit": spec["unit"], "range": spec["range"], "inferable": False,
                          "question": {"text": spec["question"], "hint": "기본값 없이 답변으로 확정합니다.", "reaskText": spec["question"]}, "dependsOn": []})
        ports = []
        if node["role"] != "SOURCE":
            ports.append({"name": "in", "direction": "IN", "dataType": "job", "unit": "건"})
        if node["role"] != "SINK":
            ports.append({"name": "out", "direction": "OUT", "dataType": "job", "unit": "건"})
        components.append({"nodeType": "ENTITY", "id": node["id"], "name": node["name"], "vars": variables,
                           "axes": [], "modelRef": refs[node["role"]], "ports": ports, "couplings": [], "aliases": []})
    links = [{"kind": "IC", "fromEntity": c["from"], "fromPort": "out", "toEntity": c["to"], "toPort": "in"} for c in draft["connections"]]
    ses = {"id": key, "domain": "queue-experiment", "version": "1.0.0", "tree": {
        "nodeType": "ENTITY", "id": "queue-root", "name": "대기행렬", "vars": [], "ports": [], "couplings": [],
        "axes": [{"nodeType": "ASPECT", "id": "queue-components", "name": "구성", "components": components, "couplings": links}]}}
    template = {"id": key, "version": "1.0.0", "name": draft["title"], "slots": slots,
                "routing": {"triggerPatterns": ["대기행렬"], "intentDescription": "단일 FIFO 대기행렬 실험", "priority": 1, "prerequisites": []},
                "binding": {"sesDefinitionId": key, "rootEntity": "대기행렬", "pruningRules": {}, "extraCouplings": []},
                "dialogue": {"maxTurns": 10, "maxQuestionsPerTurn": 2, "unfilledPolicy": "ASK_AGAIN"},
                "execution": {"mcpTools": ["search_models", "describe_model", "provision_model", "run_processor"], "simulator": {"engine": "queue-mcp-batch", "horizon": 2000, "timeResolution": 0.01,
                    "seed": 42, "mode": "SINGLE", "replications": 1, "timeoutMs": 60000}}}
    return ses, template


def design(args):
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    if (output / "state.json").exists():
        raise ValueError("Output already contains a session; choose a new --output directory")
    prompt = """사용자 요청으로 SES 설계 초안을 구성하세요. 지원 범위는 유한 작업, 일정 도착간격, 일정 처리시간,
단일 서버, 무한 대기실, FIFO, 생성기->처리기->집계기의 피드포워드 시스템입니다.
요청이 다중 서버, 우선순위, 확률분포, 피드백, 유한 대기실을 명시하면 supported=false로 표시하세요.
지원하면 정확히 3개 노드를 SOURCE, PROCESSOR, SINK 역할로 구성하고 실제 연결 2개를 제안하세요.
각 노드 id는 영문/숫자/하이픈, name은 서로 다른 한국어 이름으로 쓰세요. title은 짧은 한국어입니다.
수치나 실행 코드, URL, Docker 명령을 만들지 마세요. 부족한 수치는 서버가 질문합니다.
assumptions에 위 범위와 모든 작업 완료 시 종료한다는 가정을 명시하세요. JSON 스키마를 따르세요."""
    body = {"model": args.model, "stream": False, "format": DRAFT_SCHEMA, "options": {"temperature": 0, "seed": 42, "num_predict": 1200},
            "messages": [{"role": "system", "content": prompt}, {"role": "user", "content": args.request}]}
    save(output / "llm-request.json", body)
    print(f"로컬 LLM {args.model}: SES 초안 생성 중...", flush=True)
    response = http(args.ollama.rstrip("/") + "/api/chat", body, timeout=240)
    save(output / "llm-response.json", response)
    draft = json.loads(response["message"]["content"])
    validate_draft(draft)
    save(output / "draft.json", draft)
    with Mcp(output) as mcp:
        matches = mcp.call("search_models", capability="fifo-single-server")
        if not any(m["modelId"] == MODEL_ID for m in matches["models"]):
            raise ValueError("No compatible processing model")
        model = mcp.call("describe_model", modelId=MODEL_ID)
    key = "queue-" + uuid.uuid4().hex[:12]
    ses, template = compile_ses(draft, model, key)
    save(output / "model.json", model)
    save(output / "ses.json", ses)
    save(output / "template.json", template)
    api = args.server.rstrip("/") + "/api/v1"
    save(output / "ses-registration.json", http(api + "/ses-definitions", ses))
    save(output / "template-registration.json", http(api + "/templates", template))
    response = http(api + "/sessions", {"request": args.request, "templateId": key})
    save(output / "turn-01.json", response)
    save(output / "state.json", {"api": api, "sessionId": response["sessionId"], "lastResponse": response,
                                 "capture": 1, "model": args.model, "request": args.request})
    show_questions(response)
    return output


def show_questions(response):
    print(f"{response['outcome']} / {response['phase']} / turn {response['turn']}")
    for q in response.get("questions", []):
        print(f"  {q['slot']}: {q['text']} ({q.get('unit', '')}, {q.get('range', {})})")
    for issue in response.get("issues", []):
        print("  검증:", issue["message"])


def answer(output, answers):
    state = read(output / "state.json")
    result = http(f"{state['api']}/sessions/{state['sessionId']}/answers", {"answers": answers})
    state["capture"] += 1
    save(output / f"answer-{state['capture']:02}.json", answers)
    save(output / f"turn-{state['capture']:02}.json", result)
    state["lastResponse"] = result
    save(output / "state.json", state)
    show_questions(result)
    return result


def validate_parameters(values):
    for name, low, high in [("arrivalInterval", 0.01, 10), ("totalJobs", 1, 100), ("serviceTime", 0.01, 10)]:
        v = values.get(name)
        if isinstance(v, bool) or not isinstance(v, (float, int)) or not math.isfinite(v) or not low <= v <= high:
            raise ValueError(f"Invalid {name}")
    if int(values["totalJobs"]) != values["totalJobs"]:
        raise ValueError("totalJobs must be an integer")


def assemble(output):
    state = read(output / "state.json")
    current = http(f"{state['api']}/sessions/{state['sessionId']}")
    if current.get("questions") or current.get("progress", {}).get("open", 0) != 0 or current["phase"] not in {"READY", "DONE", "BUILDING"}:
        raise ValueError("Finish questions before assembly")
    scenario = http(f"{state['api']}/sessions/{state['sessionId']}/scenario", {})
    save(output / "scenario.json", scenario)
    draft, model = read(output / "draft.json"), read(output / "model.json")
    validate_draft(draft)
    validate_model(model)
    nodes = scenario["pes"]["children"]
    mapping = {n["entityId"]: n for n in nodes}
    roles = validate_draft(draft)
    if set(mapping) != {n["id"] for n in roles.values()}:
        raise ValueError("PES nodes differ from validated draft")
    expected = {(c["from"], c["to"], "out", "in") for c in draft["connections"]}
    links = scenario["pes"]["couplings"]
    if len(links) != 2 or {(c["fromEntity"], c["toEntity"], c["fromPort"], c["toPort"]) for c in links} != expected:
        raise ValueError("PES wiring differs from validated draft")
    values = {}
    for n in nodes:
        values.update(n["params"])
    validate_parameters(values)
    refs = {"SOURCE": "queue-local-source", "PROCESSOR": MODEL_ID, "SINK": "queue-local-sink"}
    for role, n in roles.items():
        if mapping[n["id"]]["modelRef"] != refs[role]:
            raise ValueError("Unexpected executable model reference")
    manifest = {"format": "ses-queue-simulator-v1", "scenarioId": scenario["scenarioId"],
                "execution": "feed-forward-event-batch", "termination": "drain-all-jobs", "timeUnit": "분",
                "maxSimulationTime": scenario["simConfig"]["horizon"],
                "parameters": values, "nodes": nodes, "connections": links, "externalModel": model,
                "assumptions": ["단일 서버 / FIFO / 무한 대기실", "일정 도착간격 / 일정 처리시간", "첫 도착은 도착간격 시점", "모든 작업이 완료될 때까지 실행"],
                "createdAt": datetime.now(timezone.utc).isoformat()}
    save(output / "simulator.json", manifest)
    return manifest


def validate_manifest(manifest):
    if (manifest.get("format") != "ses-queue-simulator-v1" or manifest.get("termination") != "drain-all-jobs"
            or manifest.get("execution") != "feed-forward-event-batch" or manifest.get("timeUnit") != "분"
            or manifest.get("maxSimulationTime") != 2000):
        raise ValueError("Unsupported simulator execution contract")
    validate_model(manifest["externalModel"])
    values = manifest["parameters"]
    validate_parameters(values)
    nodes = manifest["nodes"]
    refs = {"queue-local-source", MODEL_ID, "queue-local-sink"}
    if len(nodes) != 3 or {n.get("modelRef") for n in nodes} != refs or len({n["entityId"] for n in nodes}) != 3:
        raise ValueError("Unsupported simulator components")
    by_model = {n["modelRef"]: n for n in nodes}
    source, processor, sink = (by_model[r] for r in ("queue-local-source", MODEL_ID, "queue-local-sink"))
    if (source["params"] != {"arrivalInterval": values["arrivalInterval"], "totalJobs": values["totalJobs"]}
            or processor["params"] != {"serviceTime": values["serviceTime"]} or sink["params"]):
        raise ValueError("Executable parameters differ from PES")
    links = manifest["connections"]
    expected = {(source["entityId"], processor["entityId"]), (processor["entityId"], sink["entityId"])}
    if (len(links) != 2 or {(c["fromEntity"], c["toEntity"]) for c in links} != expected
            or any(c.get("kind") != "IC" or c["fromPort"] != "out" or c["toPort"] != "in" for c in links)):
        raise ValueError("Unsupported simulator wiring")
    if any(n.get("children") or n.get("couplings") for n in nodes):
        raise ValueError("Nested simulator models are unsupported")
    return source, processor, sink


def collect(arrivals, response, service):
    """Local sink validates causal job flow and aggregates external model events."""
    events = response["departures"]
    if response.get("modelId") != MODEL_ID or response.get("timeUnit") != "분" or len(events) != len(arrivals):
        raise ValueError("Model identity/unit/job conservation violation")
    previous_end = 0
    for arrival, event in zip(arrivals, events):
        expected_start = max(arrival["at"], previous_end)
        for key, expected in (("arrivedAt", arrival["at"]), ("startedAt", expected_start),
                              ("departedAt", expected_start + service), ("wait", expected_start - arrival["at"])):
            if not isinstance(event.get(key), (int, float)) or not math.isclose(event[key], expected, abs_tol=1e-7):
                raise ValueError(f"FIFO/causality violation: {key}")
        if event["id"] != arrival["id"]:
            raise ValueError("Job order/identity violation")
        previous_end = event["departedAt"]
    if not math.isclose(response["finishedAt"], previous_end, abs_tol=1e-7):
        raise ValueError("Finish time inconsistent")
    return {"generated": len(arrivals), "completed": len(events), "lost": 0,
            "meanWait": sum(e["wait"] for e in events) / len(events), "maxWait": max(e["wait"] for e in events),
            "finishedAt": previous_end, "utilizationFromTimeZero": service * len(events) / previous_end,
            "timeUnit": "분"}


def execute(output, provision=True):
    manifest = read(output / "simulator.json")
    source, processor, sink = validate_manifest(manifest)
    values = manifest["parameters"]
    arrivals = [{"id": f"job-{i + 1}", "at": (i + 1) * values["arrivalInterval"]} for i in range(int(values["totalJobs"]))]
    save(output / "source-events.json", arrivals)
    with Mcp(output) as mcp:
        if provision:
            print("MCP: 외부 SimPy 처리기 Docker 이미지 준비 중...", flush=True)
            save(output / "provision.json", mcp.call("provision_model", modelId=MODEL_ID))
        result = mcp.call("run_processor", modelId=MODEL_ID, payload={"serviceTime": values["serviceTime"], "arrivals": arrivals})
    save(output / "processor-result.json", result)
    stats = collect(arrivals, result, values["serviceTime"])
    if stats["finishedAt"] > manifest["maxSimulationTime"]:
        raise ValueError("Model exceeded simulation time bound")
    save(output / "connections-executed.json", [
        {"from": source["entityId"], "to": processor["entityId"], "port": "out -> in", "events": len(arrivals), "artifact": "source-events.json"},
        {"from": processor["entityId"], "to": sink["entityId"], "port": "out -> in", "events": len(result["departures"]), "artifact": "processor-result.json"}])
    stats.update({"imageId": result["imageId"], "libraryVersion": result["libraryVersion"],
                  "manifestSha256": hashlib.sha256((output / "simulator.json").read_bytes()).hexdigest()})
    save(output / "result.json", stats)
    write_report(output, manifest, stats)
    print(json.dumps(stats, ensure_ascii=False, indent=2))
    return stats


def write_report(output, manifest, stats):
    draft = read(output / "draft.json")
    lines = ["# 로컬 LLM · SES · MCP · Docker 대기행렬 실험", "",
             "실제 Ollama 응답, 기존 Spring 서버의 질문·PES 생성, MCP 도구 호출, Docker 안의 SimPy 실행을 기록한 결과입니다.", "",
             "## 범위", "", "일정 도착간격·일정 처리시간·단일 서버·FIFO·무한 대기실입니다. 첫 도착은 도착간격 시점이며 모든 작업 완료 시 종료합니다. 최대 100건, 시간 입력 0.01~10분입니다.",
             "서버 시나리오의 horizon 2000분은 실행 상한입니다. 실험 어댑터는 그보다 먼저 모든 작업이 끝나면 종료합니다.", "",
             "LLM이 노드 이름·역할·연결을 제안하고 결정론적 컴파일러가 SES 형식·모델 계약을 부여합니다. 지원하는 토폴로지는 생성기 → FIFO 처리기 → 집계기 한 가지입니다.",
             "MCP 검색 대상은 실험용 카탈로그 1건입니다. 어댑터는 이 저장소에서 작성했고 외부에서 실제로 내려받는 것은 Python 베이스 이미지와 PyPI의 SimPy 4.1.1입니다.",
             "실행은 피드포워드 이벤트 일괄 전달이며 Java DEVS 엔진의 분산 동시 실행이나 임의 외부 모델 자동 탐색은 포함하지 않습니다.", "",
             "## LLM 설계 초안", "", "```json", json.dumps(draft, ensure_ascii=False, indent=2), "```", "",
             "## 실제 질문", ""]
    if (output / "demo-answers.json").exists():
        lines.extend(["아래 답변은 자동 시연용 실험 입력입니다. 사용자가 실제 제출한 답변이나 LLM 추정값이 아닙니다.", ""])
    for path in sorted(output.glob("turn-*.json")):
        turn = read(path)
        lines.extend([f"### {path.name} / {turn['outcome']}", ""])
        for q in turn.get("questions", []):
            lines.append(f"- `{q['slot']}`: {q['text']} / {q.get('unit', '')} / {q.get('range', {})}")
        answer_path = output / path.name.replace("turn-", "answer-")
        if answer_path.exists():
            lines.extend(["", "이 응답을 만든 입력: `" + json.dumps(read(answer_path), ensure_ascii=False) + "`"])
        if not turn.get("questions"):
            lines.append("질문 없음 — 시나리오 생성 조건 충족.")
        lines.append("")
    lines.extend(["## 모델 연결과 결과", "", "```text", "로컬 생성기 --도착 이벤트--> MCP / Docker SimPy 처리기 --완료 이벤트--> 로컬 집계기", "```", "",
                  "확정 파라미터: `" + json.dumps(manifest["parameters"], ensure_ascii=False) + "`", "",
                  "| 지표 | 실측 |", "|---|---|"])
    for key in ("generated", "completed", "lost", "meanWait", "maxWait", "finishedAt", "utilizationFromTimeZero"):
        lines.append(f"| {key} | {stats[key]} |")
    lines.extend(["", "## 재현 근거", "", f"- Docker 이미지: `{stats['imageId']}`", f"- SimPy: `{stats['libraryVersion']}`",
                  f"- 실행 명세 SHA256: `{stats['manifestSha256']}`", "- [LLM 원본 응답](llm-response.json)", "- [등록 SES](ses.json)",
                  "- [질문 템플릿](template.json)", "- [서버 시나리오](scenario.json)", "- [조립한 시뮬레이터 명세](simulator.json)",
                  "- [MCP 원본 교환 기록](mcp.jsonl)", "- [Docker 처리기 이벤트](processor-result.json)", "- [연결 실행 기록](connections-executed.json)", "- [집계 결과](result.json)", ""])
    (output / "REPORT.md").write_text("\n".join(lines), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["design", "answer", "assemble", "run", "demo"])
    parser.add_argument("--output", default=str(ROOT / "runs" / datetime.now().strftime("%Y%m%d-%H%M%S")))
    parser.add_argument("--server", default="http://localhost:18080")
    parser.add_argument("--ollama", default="http://localhost:11434")
    parser.add_argument("--model", default="qwen2.5:7b")
    parser.add_argument("--request", default="작업들이 들어와 한 처리기 앞에서 기다리는 간단한 대기행렬 시뮬레이터를 만들고 싶어. 평균 대기시간을 확인하고 싶어.")
    parser.add_argument("--answers", help="JSON object, e.g. {\"arrivalInterval\":2}")
    parser.add_argument("--skip-provision", action="store_true")
    args = parser.parse_args()
    output = Path(args.output).resolve()
    if args.command in {"design", "demo"}:
        design(args)
    if args.command == "answer":
        if not args.answers:
            parser.error("answer requires --answers")
        answer(output, json.loads(args.answers))
    if args.command == "demo":
        # Explicit experiment fixture answers, never presented as user-provided values.
        fixture = {"arrivalInterval": 2, "totalJobs": 5, "serviceTime": 3}
        save(output / "demo-answers.json", {"source": "experiment-fixture", "answers": fixture})
        for _ in range(8):
            state = read(output / "state.json")
            if state["lastResponse"]["outcome"] == "COMPLETE":
                break
            questions = state["lastResponse"].get("questions", [])
            if not questions:
                raise ValueError("Dialogue has no questions but is incomplete")
            answer(output, {q["slot"]: fixture[q["slot"]] for q in questions})
        else:
            raise ValueError("Dialogue did not complete")
    if args.command in {"assemble", "demo"}:
        assemble(output)
    if args.command in {"run", "demo"}:
        stats = execute(output, provision=not args.skip_provision)
        if args.command == "demo":
            if stats["completed"] != 5 or not math.isclose(stats["meanWait"], 2) or not math.isclose(stats["finishedAt"], 17):
                raise AssertionError("Golden queue result mismatch")
            save(output / "verification.json", {"passed": True, "expected": {"completed": 5, "meanWait": 2, "finishedAt": 17},
                                                 "actual": stats, "llm": "live-ollama", "processor": "live-docker-simpy"})
    print("기록:", output)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    try:
        main()
    except Exception as error:
        print(f"실험 실패: {error}", file=sys.stderr)
        sys.exit(1)

