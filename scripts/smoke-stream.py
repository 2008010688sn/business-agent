#!/usr/bin/env python3
import json
import urllib.error
import urllib.request

def post(url, payload, timeout=25, sse=False):
    data = json.dumps(payload).encode()
    headers = {"Content-Type": "application/json"}
    if sse:
        headers["Accept"] = "text/event-stream"
    req = urllib.request.Request(url, data=data, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.getheader("Content-Type"), resp.read(2000)
    except urllib.error.HTTPError as exc:
        return exc.code, exc.headers.get("Content-Type"), exc.read()[:2000]


status, ctype, raw = post("http://127.0.0.1:10108/ai/chat/sessions/create", {"agentId": 1})
print("create", status, ctype, raw[:400])
session = json.loads(raw.decode("utf-8", "replace")).get("data") or {}
thread_id = str(session.get("id") or "")
print("threadId", thread_id)
status, ctype, raw = post(
    "http://127.0.0.1:10108/ai/stream/search",
    {"agentId": "1", "threadId": thread_id, "query": "hello"},
    sse=True,
)
print("stream", status, ctype)
print(raw[:1200].decode("utf-8", "replace"))
