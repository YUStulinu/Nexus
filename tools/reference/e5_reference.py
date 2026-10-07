"""
Reference outputs of multilingual-e5-small (Hugging Face tokenizers + transformers, PyTorch, fp32),
used by the Java tests to check NEXUS's own tokenizer and BERT implementation.

    python tools/reference/e5_reference.py ~/.nexus/models/multilingual-e5-small nexus-ml/src/test/resources/e5-reference.json
"""
import json
import sys

import torch
from tokenizers import Tokenizer
from transformers import AutoModel

model_dir, out = sys.argv[1], sys.argv[2]
texts = [
    "query: Cine se ocupă de grafice?",
    "passage: Inteligența artificială este un domeniu al informaticii care studiază cum pot fi construite sisteme.",
    "Hello world! How are you doing today?",
    "  multiple   spaces\tand\ttabs\nand newlines  ",
    "Ştefan şi Ţara (cedilla) vs Ștefan și Țara (comma) — „ghilimele” «franceze» … 1234,56 lei",
    "Emoji 🙂🚀 and symbols © ® ™ ½ ﬁ ① Ｆｕｌｌｗｉｄｔｈ",
    "日本語のテキストと한국어 텍스트 и русский текст",
    "art. 1350 Cod civil; v2.3.1-beta; user@example.com; https://ro.wikipedia.org/wiki/AI",
    "",
]
tok = Tokenizer.from_file(model_dir + "/tokenizer.json")
model = AutoModel.from_pretrained(model_dir, torch_dtype=torch.float32).eval()
records = []
with torch.no_grad():
    for t in texts:
        ids = tok.encode(t).ids
        x = torch.tensor([ids])
        hidden = model(input_ids=x, attention_mask=torch.ones_like(x)).last_hidden_state[0]
        mean = hidden.mean(0)
        emb = torch.nn.functional.normalize(mean, dim=0)
        records.append({"text": t, "ids": ids, "embedding": [round(v, 6) for v in emb.tolist()],
                        "hidden_first": [round(v, 5) for v in hidden[0, :16].tolist()],
                        "hidden_last": [round(v, 5) for v in hidden[-1, :16].tolist()]})
json.dump({"model": "intfloat/multilingual-e5-small", "records": records}, open(out, "w", encoding="utf-8"), ensure_ascii=False)
for r in records:
    print(len(r["ids"]), r["ids"][:12])
