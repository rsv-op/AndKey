# Build Guide: LLM Document Fine-Tune (Offline)

Generated for BuildGenie Offline — Zero-Network, Zero-Metadata.

## Overview
Fine-tune a local instruction model on your private documents. All training stays on-device or in your private terminal. No data leaves your device.

## Your Files
{{file_list}}

## Detected Schemas
{{schema}}

## Primary Dataset
- Primary CSV: `./{{primary_csv}}`
- Expected columns: {{expected_columns}}

## Steps

### 1. Environment
```bash
python3 -m venv .venv && source .venv/bin/activate
pip install --no-cache-dir pandas transformers datasets accelerate onnxruntime torch --index-url https://download.pytorch.org/whl/cpu
```

### 2. Chunk & Tokenize (Offline RAG)
```python
from pathlib import Path
import pandas as pd

df = pd.read_csv('./{{primary_csv}}')
print('Schema:', df.columns.tolist())
# Chunk documents to 512 tokens for fine-tuning
# Use local tokenizer from app/src/main/assets/models/vocab.txt
```

- Input: `./{{primary_csv}}` must have `text` and `label` or `prompt`/`completion` columns.
- Output: `chunks.jsonl` with `{prompt, completion}` pairs.

### 3. Train (QLoRA / CPU)
```bash
python3 run_finetune.py --input ./{{primary_csv}} --model ./models/all-MiniLM-L6-v2-quant.onnx --epochs 3 --batch_size 8 --offline
```

### 4. Evaluate
Check `val_loss` < 1.5 and manual spot-check 10 completions.

### 5. Export for Android
Quantize to `ONNX INT4` or `GGUF Q4_0` and drop into `app/src/main/assets/models/` or `/Download/BuildGenie/models/` for `LocalAiChatEngine` to pick up.

---
*Built 100% offline — no telemetry, no network. Template: `llm_doc_finetune.md`*
