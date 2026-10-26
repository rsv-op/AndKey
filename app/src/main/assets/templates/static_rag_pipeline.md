# Build Guide: Static RAG Pipeline (FAISS + ONNX Embeddings)

Generated for BuildGenie Offline — Zero-Network, Zero-Metadata.

## Overview
Index your local PDFs / Markdown / DOCX into a FAISS vector store using the quantized `all-MiniLM-L6-v2` ONNX embeddings. Query fully offline via `LocalAiChatEngine`.

## Your Files
{{file_list}}

## Detected Schemas
{{schema}}

## Primary Document
- Primary source: `./{{primary_csv}}` (or first PDF/MD/DOCX in staging)

## Steps

### 1. Extract & Chunk
```python
from pathlib import Path
docs = [Path('./{{primary_csv}}').name]
print(f'Indexing {len(docs)} local document(s) into FAISS...')

# Chunk to 400 chars with 40 overlap for retrieval
def chunk(text, size=400, overlap=40):
    return [text[i:i+size] for i in range(0, len(text), size-overlap)]
```

### 2. Embed Offline (No API Keys)
```bash
python3 -m venv .venv && source .venv/bin/activate
pip install --no-cache-dir faiss-cpu onnxruntime transformers
python3 embed_offline.py --input ./{{primary_csv}} --model ./models/all-MiniLM-L6-v2-quant.onnx --offline
```

### 3. Query Locally
```python
# Local RAG Synthesis Engine (no network)
# Uses getFilesDir()/models/all-MiniLM-L6-v2-quant.onnx if present
query = "Summarize the key risks from my docs"
# Top-k retrieval + grounded answer
```

### 4. Android Integration
- Drop `faiss_index.bin` + `model_quantized.onnx` into `app/src/main/assets/models/` or `/Download/BuildGenie/models/`
- `LocalAiChatEngine` auto-detects `local_llm_q4.onnx` and switches to `[Local ONNX Int4 Runtime Active]`

---
*Built 100% offline — embeddings stay on device. Template: `static_rag_pipeline.md`*
