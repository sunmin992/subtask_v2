"""
로컬 오픈 모델별로 같은 요청을 끝까지 돌려 비교한다.

    python scripts/model-bench.py qwen2.5:7b gemma2:9b llama3:latest

각 모델마다 서버를 새로 띄우고, 요청문 하나로 세션을 열어 <b>실제로 물어본 질문만</b>
답하며 완료까지 간다. 고정된 답변 스크립트를 쓰면 안 된다 — 모델이 이미 채운 슬롯을
다시 답하면 "열려 있지 않은 슬롯" 오류가 나서, 모델 성능이 아니라 스크립트를 재는 셈이 된다.

확인하는 것은 세 가지다.
  1. 키워드가 없는 문장을 라우팅했는가 (라우팅은 LLM 만 할 수 있다)
  2. 몇 번 물어보고 끝났는가 (적을수록 추출을 잘했다는 뜻)
  3. 요청문에 있던 값 세 개를 정확히 뽑았는가
"""

import json
import subprocess
import sys
import time
import urllib.error
import urllib.request

REQUEST = ("산 위에 8인승 곤돌라를 3분 간격으로 놓고, "
           "숙소는 200실짜리 호텔로 해서 손님 흐름 봐줘")

# 요청문에 또렷하게 드러난 값. 모델이 이걸 맞히는지가 핵심이다.
EXPECTED = {
    "리조트/케이블카.정원": 8,
    "리조트/케이블카.운행간격": 3.0,
    "리조트/호텔.객실수": 200,
}

# 모델이 채우지 못해 물어볼 경우에 쓸 답. 사람이 답하는 몫이다.
ANSWERS = {
    "이동설비": "케이블카",
    "숙박시설": "호텔",
    "버스대수": 3,
    "케이블카.정원": 8,
    "케이블카.운행간격": 3.0,
    "호텔.객실수": 200,
    "콘도.객실수": 200,
    "모노레일.정원": 60,
    "모노레일.배차간격": 10.0,
}

JAR = "app/build/libs/ses-scenario-server.jar"
MAX_TURNS = 6


def post(url, body=None, timeout=300):
    data = json.dumps(body).encode() if body is not None else b"{}"
    req = urllib.request.Request(url, data=data, method="POST",
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode())
        except Exception:
            return e.code, {}


def get(url, timeout=60):
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            return json.loads(r.read().decode())
    except Exception:
        return {}


def answer_for(question):
    """질문 슬롯에 줄 답. 인덱스가 붙은 복제본 슬롯도 처리한다."""
    slot = question["slot"]
    if slot in ANSWERS:
        return ANSWERS[slot]
    base = slot.split("[")[0]
    if base in ANSWERS:
        return ANSWERS[base]
    # 모르는 슬롯이면 선택지 첫 항목이나 범위 하한을 쓴다.
    if question.get("options"):
        return question["options"][0]
    rng = question.get("range") or {}
    return rng.get("min", 1)


def run(model, port):
    proc = subprocess.Popen(
        # memory 프로파일을 함께 쓴다 — 모델 비교에 DB 는 필요 없고,
        # ollama 프로파일만 쓰면 PostgreSQL 을 찾다가 기동에 실패한다.
        ["java", "-jar", JAR, "--spring.profiles.active=memory,ollama",
         f"--server.port={port}", f"--llm.model={model}",
         f"--llm.model-by-purpose.ROUTING={model}"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    base = f"http://localhost:{port}"
    try:
        for _ in range(120):
            if get(f"{base}/actuator/health", timeout=2).get("status") == "UP":
                break
            time.sleep(1)
        else:
            return {"model": model, "error": "기동 실패"}

        api = f"{base}/api/v1"
        started = time.time()
        status, first = post(f"{api}/sessions", {"request": REQUEST})
        if "sessionId" not in first:
            return {"model": model, "routed": False,
                    "note": first.get("code", f"HTTP {status}"),
                    "seconds": round(time.time() - started, 1)}

        sid = first["sessionId"]
        turn = first
        asked = 0
        for _ in range(MAX_TURNS):
            if turn.get("outcome") == "COMPLETE":
                break
            questions = turn.get("questions") or []
            if not questions:
                break
            asked += len(questions)
            body = {"answers": {q["slot"]: answer_for(q) for q in questions}}
            _, turn = post(f"{api}/sessions/{sid}/answers", body)

        elapsed = round(time.time() - started, 1)
        result = {"model": model, "routed": True, "asked": asked,
                  "outcome": turn.get("outcome", "-"), "seconds": elapsed}

        if turn.get("outcome") == "COMPLETE":
            _, scenario = post(f"{api}/sessions/{sid}/scenario")
            params = scenario.get("params", {})
            result["correct"] = sum(
                1 for k, v in EXPECTED.items() if params.get(k) == v)
            result["params"] = {k: params.get(k) for k in EXPECTED}
        return result
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=20)
        except subprocess.TimeoutExpired:
            proc.kill()


def main():
    models = sys.argv[1:] or ["qwen2.5:7b"]
    print(f"요청문: {REQUEST}")
    print("        (리조트·케이블카 어느 키워드도 없음 -> 라우팅은 LLM 만 할 수 있다)")
    print()
    print(f"{'모델':<16s} {'라우팅':^7s} {'질문수':^7s} {'결과':^10s} {'값 3개':^8s} {'초':>6s}")
    print("-" * 62)

    rows = []
    for i, model in enumerate(models):
        r = run(model, 8400 + i)
        rows.append(r)
        if r.get("error"):
            print(f"{model:<16s} {'-':^7s} {'-':^7s} {r['error']:^10s} {'-':^8s} {'-':>6s}")
            continue
        routed = "O" if r.get("routed") else "X"
        asked = r.get("asked", "-")
        correct = f"{r['correct']}/3" if "correct" in r else "-"
        note = r.get("outcome") or r.get("note", "-")
        print(f"{model:<16s} {routed:^7s} {str(asked):^7s} {note:^10s} "
              f"{correct:^8s} {r.get('seconds', '-'):>6}")

    print()
    for r in rows:
        if r.get("params"):
            wrong = {k: v for k, v in r["params"].items() if EXPECTED[k] != v}
            if wrong:
                print(f"  {r['model']}: 틀린 값 {wrong}")


if __name__ == "__main__":
    main()
