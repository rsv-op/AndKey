# Build Guide: Offline Text Classifier (Scikit-Learn)

Generated for BuildGenie Offline — Zero-Network, Zero-Metadata.

## Overview
Train a classic TF-IDF + Logistic Regression / DistilBERT classifier that runs fully offline. Ideal for support tickets, sentiment, or category labeling.

## Your Files
{{file_list}}

## Detected Schemas
{{schema}}

## Primary Dataset
- Primary CSV: `./{{primary_csv}}`
- Expected columns: {{expected_columns}}  → e.g., `['text','label']`

## Steps

### 1. Inspect
```python
import pandas as pd
df = pd.read_csv('./{{primary_csv}}')
print(df.head())
print(df['label'].value_counts())
```

### 2. Train
```python
from sklearn.model_selection import train_test_split
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import classification_report

df = pd.read_csv('./{{primary_csv}}')
train_df, val_df = train_test_split(df, test_size=0.2, random_state=42)

vec = TfidfVectorizer(max_features=15000, ngram_range=(1,2))
X_train = vec.fit_transform(train_df['text'])
y_train = train_df['label']

clf = LogisticRegression(max_iter=1000, multi_class='auto')
clf.fit(X_train, y_train)

# Evaluate
from sklearn.metrics import accuracy_score
pred = clf.predict(vec.transform(val_df['text']))
print(classification_report(val_df['label'], pred))
```

### 3. Export
```python
import joblib
joblib.dump((vec, clf), 'classifier_offline.joblib')
print('Saved to classifier_offline.joblib — copy to device if needed')
```

### 4. Run Offline CLI
```bash
python3 run_pipeline.py --input ./{{primary_csv}} --offline --model classifier_offline.joblib
```

---
*Built 100% offline — no telemetry. Template: `ml_text_classifier.md`*
