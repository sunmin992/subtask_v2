"""Additional real Docker, dialogue and artifact checks after experiment.py demo."""
import argparse
import copy
import math
from pathlib import Path

import experiment as e


def verify(output):
    checks = []
    manifest = e.read(output / "simulator.json")
    e.validate_manifest(manifest)
    tampered = copy.deepcopy(manifest)
    tampered["connections"][0]["toEntity"] = tampered["connections"][0]["fromEntity"]
    try:
        e.validate_manifest(tampered)
    except ValueError:
        checks.append("tampered-wiring-rejected")
    else:
        raise AssertionError("Tampered wiring was accepted")
    prior = e.read(output / "result.json")
    # Replay the assembled artifact without calling the LLM or rebuilding the image.
    replay = e.execute(output, provision=False)
    assert prior == replay, (prior, replay)
    checks.append("same-artifact-identical-result")
    with e.Mcp(output) as mcp:
        for period, count, service, expected_wait, expected_end in [(5, 5, 3, 0, 28), (10, 100, 10, 0, 1010), (.01, 3, .01, 0, .04)]:
            arrivals = [{"id": str(i), "at": period * (i + 1)} for i in range(count)]
            response = mcp.call("run_processor", modelId=e.MODEL_ID, payload={"arrivals": arrivals, "serviceTime": service})
            stats = e.collect(arrivals, response, service)
            assert math.isclose(stats["meanWait"], expected_wait, abs_tol=1e-8), stats
            assert math.isclose(stats["finishedAt"], expected_end, abs_tol=1e-8), stats
            checks.append(f"docker-golden-period{period}-jobs{count}-service{service}")
            e.save(output / f"docker-check-{count}.json", response)
        try:
            mcp.call("run_processor", modelId=e.MODEL_ID, payload={"arrivals": [{"id": "x", "at": 2}], "serviceTime": 0})
        except RuntimeError:
            checks.append("docker-invalid-service-time-rejected")
        else:
            raise AssertionError("Invalid service time accepted")
    state = e.read(output / "state.json")
    api = state["api"]
    template = e.read(output / "template.json")
    session = e.http(api + "/sessions", {"request": "대기행렬 재질문 검증", "templateId": template["id"]})
    rejected = e.http(f"{api}/sessions/{session['sessionId']}/answers", {"answers": {"serviceTime": 0}})
    assert rejected["outcome"] == "REASK" and any(i["code"] == "OUT_OF_RANGE" for i in rejected["issues"]), rejected
    e.save(output / "invalid-answer-check.json", rejected)
    checks.append("spring-reasks-invalid-answer")
    try:
        e.http(f"{api}/sessions/{session['sessionId']}/scenario", {})
    except RuntimeError:
        checks.append("spring-refuses-incomplete-scenario")
    else:
        raise AssertionError("Incomplete scenario accepted")
    e.save(output / "regression.json", {"passed": True, "checks": checks})
    with (output / "REPORT.md").open("a", encoding="utf-8") as report:
        report.write("\n## 추가 실측 검증\n\n")
        for check in checks:
            report.write(f"- PASS: {check}\n")
        report.write("\n[검증 원본](regression.json)\n")
    print("LIVE CHECKS PASSED:", len(checks))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("output")
    verify(Path(parser.parse_args().output).resolve())
