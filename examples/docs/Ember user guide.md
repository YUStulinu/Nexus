# Ember user guide

Ember is a small inference engine for language models, written in C++ and CUDA. It loads Qwen3 models from safetensors, quantises the weights on load and serves an OpenAI-compatible API.

## Starting the server

Start Ember with `ember serve -m MODEL_DIR -q int4 --port 8090`. The `-q` option selects the weight format: `int4` uses the least memory, `int8` is slightly more accurate and `bf16` keeps the original precision. A 1.7 billion parameter model needs about 1.2 GB of GPU memory for its weights in int4.

The key-value cache is allocated once, at start-up. Its size is set with `--kv-cache-mib`, in mebibytes; 640 MiB is enough for several concurrent conversations of a few thousand tokens. If the GPU does not have enough free memory for the cache, Ember exits with the message "not enough GPU memory left for the KV cache" and status code 1.

## Endpoints

`GET /health` answers 200 when the model is loaded and the server accepts requests. `POST /v1/chat/completions` follows the OpenAI chat format, with streaming through server-sent events when `stream` is true. `GET /api/stats` reports the generation speed in tokens per second, the KV-cache occupancy and the free device memory.

## Thinking mode

Qwen3 models can reason before answering. Reasoning is off by default; enable it per request with `chat_template_kwargs: {"enable_thinking": true}`. The reasoning arrives in the `reasoning_content` field of each streamed delta, separately from the answer.

## Performance

On an RTX-class laptop GPU, the 1.7B model generates about 85 tokens per second in int4 for a single conversation, and the 0.6B model about 120 tokens per second. Prompt processing is batched and is roughly twenty times faster than generation.

## Troubleshooting

If requests time out right after start-up, wait for `/health` to return 200: loading and quantising the weights takes a few seconds. If generation becomes slow, check `/api/stats`; a KV cache that is nearly full forces older conversations to be evicted and recomputed.
