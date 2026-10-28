"""Writes known-answer vectors from the Talaria plugin's own e2e.py, for the app.

Usage: e2e_vectors.py PATH_TO_E2E_PY E2E_VECTORS_JSON LOCATE_VECTOR_JSON
Nonces are fixed so the ciphertexts are reproducible.
"""
import importlib.util
import json
import sys

spec = importlib.util.spec_from_file_location("e2e", sys.argv[1])
e2e = importlib.util.module_from_spec(spec)
sys.modules["e2e"] = e2e
spec.loader.exec_module(e2e)

KID = "0123456789abcdef0123456789abcdef"
SECRET = bytes(range(32))
INSTALLATION = "11111111-2222-3333-4444-555555555555"
GATEWAY = "8c0e9f52-3b6d-4c1a-9e47-2f5d6a7b8c90"
IAT = 1_761_000_000

keys = e2e.derive_keys(KID, SECRET)
counter = [0]


def fixed_nonce(n):
    counter[0] += 1
    return bytes([counter[0]] * n)


e2e.os.urandom = fixed_nonce

events = [
    {"event_id": "response:0123456789abcdef", "type": "response.ready", "session_id": "sess-1",
     "stored_session_id": "20261021_120000_abcdef", "profile": "default", "title": "Refactor the parser",
     "body": "Done. " + "The parser now streams tokens and keeps line numbers. " * 6},
    {"event_id": "turn:fedcba9876543210", "type": "turn.failed", "body": "Zq9!"},
    {"event_id": "input:0123456789abcdef", "type": "input.needed", "session_id": "sess-1", "profile": "default",
     "body": "Deploy to prod?", "decision": {"kind": "clarify", "request_id": "talaria-push-abc123def456",
                                             "question": "Deploy to prod?", "choices": ["Yes", "No"]}},
    {"event_id": "approval:0123456789abcdef", "type": "approval.needed", "session_id": "sess-1", "profile": "default",
     "title": "Approval needed · Infra", "body": "rm -rf build/",
     "decision": {"kind": "approval", "session_key": "sess-1", "description": "rm -rf build/",
                  "choices": ["once", "session", "always", "deny"]}},
    {"event_id": "call:0123456789abcdef01234567", "type": "call.requested", "session_id": "sess-1",
     "stored_session_id": "20261021_120000_abcdef", "profile": "default", "title": "Hermes wants to talk",
     "body": "\u201cCheck the server\u201d finished.",
     "call": {"id": "0123456789abcdef01234567", "kind": "done", "title": "Check the server",
              "session_ids": ["sess-1", "20261021_120000_abcdef"], "reason": "The deploy is live."}},
]

sealed = []
for event in events:
    outgoing = e2e.seal_event(event, keys, installation_id=INSTALLATION, gateway_id=GATEWAY, now=IAT)
    inner = e2e.open_event(outgoing, keys, installation_id=INSTALLATION, gateway_id=GATEWAY)
    sealed.append({"type": outgoing["type"], "envelope": outgoing["e2e"], "inner": inner})

# Hermes asking for the location now, sealed the same way.
locate_event = {"event_id": "locate:l_vectorLocate0123456789", "type": "location.requested", "profile": "default",
                "locate": {"id": "l_vectorLocate0123456789"}}
locate_out = e2e.seal_event(locate_event, keys, installation_id=INSTALLATION, gateway_id=GATEWAY, now=1_792_800_000)
locate = {"type": locate_out["type"], "envelope": locate_out["e2e"],
          "inner": e2e.open_event(locate_out, keys, installation_id=INSTALLATION, gateway_id=GATEWAY)}

answer = e2e.seal_answer("Yes", keys, installation_id=INSTALLATION, gateway_id=GATEWAY,
                         request_id="talaria-push-abc123def456", question_id="q1")

out = {
    "source": "talaria-plugin e2e.py",
    "kid": KID,
    "secret": e2e.b64u(SECRET),
    "installation_id": INSTALLATION,
    "gateway_id": GATEWAY,
    "keys": {"push": keys.push.hex(), "answer": keys.answer.hex(), "meta": keys.meta.hex()},
    "thread_token": {"seed": "sess-1", "token": e2e.thread_token(keys, "sess-1")},
    "keyed_event_id": {"event_id": "response:0123456789abcdef",
                       "keyed": e2e.keyed_event_id(keys, "response:0123456789abcdef")},
    "events": sealed,
    "answer": {"text": "Yes", "request_id": "talaria-push-abc123def456", "question_id": "q1", "sealed": answer},
}
assert {item["envelope"]["z"] for item in sealed} == {0, 1}, "want both compressed and plain vectors"
with open(sys.argv[2], "w", encoding="utf-8") as f:
    json.dump(out, f, indent=2, ensure_ascii=False)
    f.write("\n")
with open(sys.argv[3], "w", encoding="utf-8") as f:
    json.dump(locate, f, indent=2, ensure_ascii=False)
    f.write("\n")
print("events:", [(item["type"], item["envelope"]["z"], item["envelope"]["req"]) for item in sealed])
