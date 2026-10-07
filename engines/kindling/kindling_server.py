"""An OpenAI-compatible server for Kindling, the small Romanian GPT, so that NEXUS can drive it like
any other language model engine (chat nodes, model duels, the GPU memory broker).

Kindling is a base model: it continues text, it does not follow instructions. The server therefore
takes the last user message as the beginning of a text and streams the model's continuation.

    python kindling_server.py --kindling ../../../Kindling --ckpt runs/rope/best.pt --port 8092

Endpoints:
    GET  /health                 200 once the model is loaded
    POST /v1/chat/completions    OpenAI chat format; "stream": true sends server-sent events
    GET  /api/stats              tokens per second of the last answer, requests served, GPU memory

Only the Python standard library is used besides PyTorch and Kindling's own code.
"""

import argparse
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--kindling", required=True, help="the Kindling project folder")
    ap.add_argument("--ckpt", default="runs/rope/best.pt", help="checkpoint, relative to the Kindling folder")
    ap.add_argument("--port", type=int, default=8092)
    ap.add_argument("--device", default="auto")
    args = ap.parse_args()

    root = os.path.abspath(args.kindling)
    sys.path.insert(0, root)
    os.chdir(root)

    import torch
    from generate import generate
    from tinyllm.runtime import autocast_ctx, load_model, pick_device, pick_precision
    from tinyllm.tokenizer import EOT, BPETokenizer

    device = pick_device(args.device)
    model, ckpt = load_model(args.ckpt, device)
    tok = BPETokenizer.load(os.path.join(ckpt["train_cfg"]["data_dir"], "tokenizer.json"))
    ctx = autocast_ctx(device, pick_precision("auto", device))
    name = "kindling-" + os.path.basename(os.path.dirname(os.path.abspath(args.ckpt)))
    lock = threading.Lock()                       # one model on one GPU: answers are generated in turn
    stats = {"requests": 0, "tokens_per_second": 0.0, "last_tokens": 0}
    print(f"{name}: {model.num_params() / 1e6:.1f}M parameters on {device}", flush=True)

    def complete(messages, max_tokens, temperature, seed, emit):
        """Generates a continuation; calls emit(piece) for every piece of text; returns (text, prompt_tokens, n, seconds)."""
        prompt = ""
        for m in messages:
            if m.get("role") == "user":
                prompt = m.get("content") or ""
        ids = tok.encode(prompt) if prompt else [tok.special[EOT]]
        idx = torch.tensor([ids], dtype=torch.long, device=device)
        gen = torch.Generator(device=device).manual_seed(seed) if seed else None
        out, printed, n = [], 0, 0
        t0 = time.time()
        first = None
        with lock, ctx:
            for t in generate(model, idx, max_tokens, max(temperature, 0.0), 50, 0.95, stop_id=tok.special[EOT], generator=gen):
                if first is None:
                    first = time.time()
                if t == tok.special[EOT]:
                    break
                n += 1
                out.append(t)
                text = tok.decode(out)
                if not text.endswith("�"):    # never send half of a UTF-8 character
                    emit(text[printed:])
                    printed = len(text)
        text = tok.decode(out)
        if len(text) > printed:
            emit(text[printed:])
        dt = time.time() - t0
        stats["requests"] += 1
        stats["last_tokens"] = n
        stats["tokens_per_second"] = n / dt if dt > 0 else 0.0
        return text, len(ids), n, dt, (first or time.time()) - t0

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *a):
            pass

        def send_json(self, code, obj):
            body = json.dumps(obj).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.path == "/health":
                self.send_json(200, {"status": "ok", "model": name})
            elif self.path == "/api/stats":
                mem = {}
                if device.type == "cuda":
                    free, total = torch.cuda.mem_get_info(device)
                    mem = {"device_free_mib": free // 2**20, "device_total_mib": total // 2**20,
                           "allocated_mib": torch.cuda.memory_allocated(device) // 2**20}
                self.send_json(200, {"model": name, "requests": stats["requests"], "tokens_per_second": stats["tokens_per_second"],
                                     "memory": mem})
            elif self.path == "/v1/models":
                self.send_json(200, {"object": "list", "data": [{"id": name, "object": "model"}]})
            else:
                self.send_json(404, {"error": "not found"})

        def do_POST(self):
            if self.path != "/v1/chat/completions":
                self.send_json(404, {"error": "not found"})
                return
            req = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
            messages = req.get("messages", [])
            max_tokens = int(req.get("max_tokens") or 200)
            temperature = float(req.get("temperature", 0.8))
            seed = req.get("seed")
            created = int(time.time())
            if not req.get("stream"):
                text, pt, n, dt, _ = complete(messages, max_tokens, temperature, seed, lambda piece: None)
                self.send_json(200, {"id": "kindling", "object": "chat.completion", "created": created, "model": name,
                                     "choices": [{"index": 0, "message": {"role": "assistant", "content": text}, "finish_reason": "stop"}],
                                     "usage": {"prompt_tokens": pt, "completion_tokens": n, "total_tokens": pt + n}})
                return
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "close")
            self.end_headers()

            def send(obj):
                self.wfile.write(b"data: " + json.dumps(obj).encode("utf-8") + b"\n\n")
                self.wfile.flush()

            def chunk(delta, finish=None):
                send({"id": "kindling", "object": "chat.completion.chunk", "created": created, "model": name,
                      "choices": [{"index": 0, "delta": delta, "finish_reason": finish}]})

            try:
                chunk({"role": "assistant"})
                text, pt, n, dt, ttft = complete(messages, max_tokens, temperature, seed, lambda piece: chunk({"content": piece}))
                chunk({}, "stop" if n < max_tokens else "length")
                if (req.get("stream_options") or {}).get("include_usage"):
                    send({"id": "kindling", "object": "chat.completion.chunk", "created": created, "model": name, "choices": [],
                          "usage": {"prompt_tokens": pt, "completion_tokens": n, "total_tokens": pt + n}})
                self.wfile.write(b"data: [DONE]\n\n")
                self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                pass          # the client stopped reading (a cancelled node)
            self.close_connection = True

    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"listening on http://127.0.0.1:{args.port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
