import copy
import json
from pathlib import Path
import tempfile
import unittest

import experiment as e


def draft():
    return {"title": "대기행렬", "supported": True, "reason": "", "assumptions": [],
            "nodes": [{"id": "source", "name": "생성기", "role": "SOURCE"},
                      {"id": "processor", "name": "처리기", "role": "PROCESSOR"},
                      {"id": "sink", "name": "집계기", "role": "SINK"}],
            "connections": [{"from": "source", "to": "processor"}, {"from": "processor", "to": "sink"}]}


class ContractsTest(unittest.TestCase):
    def setUp(self):
        self.model = e.read(e.ROOT / "catalog.json")

    def test_model_required_parameter_becomes_open_ses_slot(self):
        ses, template = e.compile_ses(draft(), self.model, "test")
        processor = next(n for n in ses["tree"]["axes"][0]["components"] if n["id"] == "processor")
        self.assertEqual(["serviceTime"], [v["name"] for v in processor["vars"]])
        self.assertIsNone(processor["vars"][0]["value"])
        self.assertIsNone(processor["vars"][0]["defaultValue"])
        self.assertEqual({"arrivalInterval", "totalJobs", "serviceTime"}, {s["name"] for s in template["slots"]})

    def test_unsupported_request_is_not_silently_replaced(self):
        d = draft()
        d["supported"] = False
        with self.assertRaises(ValueError):
            e.validate_draft(d)

    def test_cycles_and_bypasses_rejected(self):
        for target in ("source", "sink"):
            d = draft()
            d["connections"][0]["to"] = target
            with self.assertRaises(ValueError):
                e.validate_draft(d)

    def test_duplicate_roles_or_ids_rejected(self):
        for field in ("role", "id", "name"):
            d = draft()
            d["nodes"][1][field] = d["nodes"][0][field]
            with self.assertRaises(ValueError):
                e.validate_draft(d)

    def test_wrong_model_units_rejected(self):
        self.model["ports"]["in"]["unit"] = "kg"
        with self.assertRaises(ValueError):
            e.compile_ses(draft(), self.model, "test")

    def test_unknown_model_parameter_rejected_before_questioning(self):
        self.model["parameters"][0]["name"] = "shellCommand"
        with self.assertRaises(ValueError):
            e.validate_model(self.model)

    def test_bounds_booleans_nonfinite_and_fractional_counts(self):
        for key, value in [("serviceTime", 0), ("arrivalInterval", float("nan")), ("totalJobs", 101),
                           ("totalJobs", 1.2), ("totalJobs", True), ("serviceTime", float("inf"))]:
            values = {"arrivalInterval": 2, "totalJobs": 5, "serviceTime": 3, key: value}
            with self.assertRaises(ValueError):
                e.validate_parameters(values)

    def test_sink_rejects_lost_jobs(self):
        with self.assertRaises(ValueError):
            e.collect([{"id": "j1", "at": 2}], {"modelId": e.MODEL_ID, "timeUnit": "분", "departures": []}, 3)

    def test_sink_checks_causality_and_fifo(self):
        response = {"modelId": e.MODEL_ID, "timeUnit": "분", "finishedAt": 5,
                    "departures": [{"id": "j1", "arrivedAt": 2, "startedAt": 2, "departedAt": 5, "wait": 0}]}
        self.assertEqual(0, e.collect([{"id": "j1", "at": 2}], response, 3)["meanWait"])
        response["departures"][0]["departedAt"] = 1
        with self.assertRaises(ValueError):
            e.collect([{"id": "j1", "at": 2}], response, 3)

    def test_real_stdio_mcp_handshake_discovery_and_unknown_model(self):
        with tempfile.TemporaryDirectory() as directory:
            with e.Mcp(Path(directory)) as mcp:
                self.assertEqual([], mcp.call("search_models", capability="unsupported")["models"])
                model = mcp.call("describe_model", modelId=e.MODEL_ID)
                self.assertEqual("simpy", model["source"]["package"])
                with self.assertRaises(RuntimeError):
                    mcp.call("provision_model", modelId="untrusted-image")
            records = [json.loads(line) for line in (Path(directory) / "mcp.jsonl").read_text(encoding="utf-8").splitlines()]
            self.assertTrue(any(r["message"].get("method") == "notifications/initialized" for r in records))


if __name__ == "__main__":
    unittest.main()
