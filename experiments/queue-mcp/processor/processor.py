"""External SimPy adapter. One immutable job batch per container; no network API."""
import json
import math
import sys

import simpy


def simulate(payload):
    service = payload.get("serviceTime")
    if isinstance(service, bool) or not isinstance(service, (int, float)) or not math.isfinite(service) or not 0.01 <= service <= 10:
        raise ValueError("serviceTime must be finite and in [0.01, 10] minutes")
    arrivals = payload.get("arrivals")
    if not isinstance(arrivals, list) or not 1 <= len(arrivals) <= 100:
        raise ValueError("arrivals must contain 1..100 jobs")
    seen, previous = set(), -1
    for item in arrivals:
        at = item.get("at")
        if isinstance(at, bool) or not isinstance(at, (int, float)) or not math.isfinite(at) or not 0 <= at <= 1000 or at < previous:
            raise ValueError("arrival times must be finite, bounded and sorted")
        if not isinstance(item.get("id"), str) or item["id"] in seen:
            raise ValueError("job IDs must be unique strings")
        seen.add(item["id"])
        previous = at
    env = simpy.Environment()
    server = simpy.Resource(env, capacity=1)
    departures = []

    def job(item):
        yield env.timeout(item["at"])
        with server.request() as ticket:
            yield ticket
            started = env.now
            yield env.timeout(service)
            departures.append({"id": item["id"], "arrivedAt": item["at"], "startedAt": started,
                               "departedAt": env.now, "wait": started - item["at"]})

    for item in arrivals:
        env.process(job(item))
    env.run()
    return {"modelId": "external-simpy-fifo", "library": "simpy", "libraryVersion": simpy.__version__,
            "timeUnit": "분", "departures": departures, "finishedAt": env.now}


if __name__ == "__main__":
    try:
        print(json.dumps(simulate(json.load(sys.stdin)), ensure_ascii=False, allow_nan=False))
    except (ValueError, TypeError, KeyError) as error:
        print(json.dumps({"error": str(error)}), file=sys.stderr)
        sys.exit(1)

