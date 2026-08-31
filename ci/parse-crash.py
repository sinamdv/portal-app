#!/usr/bin/env python3
"""Print an iOS .ips crash report in a form a human can act on.

An .ips is a JSON metadata line followed by a JSON body. The body's frames
carry an `imageIndex`, which is an index into `usedImages` — printing the index
alone (as an earlier version of the CI step did) says nothing. Resolving it to
the binary name is the whole point: it tells you whether the fault is in our
code, in Capacitor, or inside WebKit.
"""
import json
import sys


def main(path: str) -> int:
    raw = open(path, encoding="utf-8", errors="replace").read()
    parts = raw.split("\n", 1)
    if len(parts) < 2:
        print("unexpected .ips layout — no JSON body")
        return 0
    body = json.loads(parts[1])

    print("exception  :", json.dumps(body.get("exception", {})))
    print("termination:", json.dumps(body.get("termination", {})))
    print("asi        :", json.dumps(body.get("asi", {}))[:400])

    images = body.get("usedImages", [])
    threads = body.get("threads", [])
    faulting = body.get("faultingThread", 0)

    for idx, thread in enumerate(threads):
        if idx != faulting and not thread.get("triggered"):
            continue
        print("\n--- thread %d%s ---" % (idx, "  (FAULTING)" if idx == faulting else ""))
        for frame in thread.get("frames", [])[:30]:
            i = frame.get("imageIndex", -1)
            name = images[i].get("name", "?") if 0 <= i < len(images) else "?"
            symbol = frame.get("symbol", "")
            print("  %-28s +%-10s %s" % (name, frame.get("imageOffset", "?"), symbol))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
