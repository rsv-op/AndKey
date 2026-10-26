package com.buildgenie.analysis;

import android.content.Context;

import com.buildgenie.ingestion.TextExtractor.ExtractedContent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Matches ingested files against build ontology (res/raw/builds.json)
 * using signal-based scoring. 100% offline.
 */
public class BuildMatcher {

    public static class BuildSuggestion {
        public final String buildId;
        public final String name;
        public final String difficulty;
        public final String templatePath;
        public final String description;
        public final int confidencePercent;

        public BuildSuggestion(String buildId, String name, String difficulty, String templatePath, String description, int confidencePercent) {
            this.buildId = buildId;
            this.name = name;
            this.difficulty = difficulty;
            this.templatePath = templatePath;
            this.description = description;
            this.confidencePercent = confidencePercent;
        }
    }

    /**
     * Loads builds.json from raw resources and scores each build against extracted signals.
     * No network, no reflection.
     */
    public static List<BuildSuggestion> matchBuilds(Context context, List<ExtractedContent> extracted, int buildsResId) {
        List<BuildSuggestion> result = new ArrayList<>();
        try {
            String jsonStr = loadRawResource(context, buildsResId);
            JSONArray builds = new JSONArray(jsonStr);

            // Collect all signals from all files
            Set<String> allSignals = new HashSet<>();
            for (ExtractedContent ec : extracted) {
                allSignals.addAll(ec.detectedSignals);
            }

            for (int i = 0; i < builds.length(); i++) {
                JSONObject build = builds.getJSONObject(i);
                String buildId = build.getString("buildId");
                String name = build.getString("name");
                String difficulty = build.getString("difficulty");
                String templatePath = build.getString("templatePath");
                String description = build.optString("description", "");

                JSONObject scoring = build.getJSONObject("scoring");
                int maxScore = 0;
                int achievedScore = 0;

                // Sum scoring: each signal key in scoring corresponds to a weight
                for (int s = 0; s < scoring.length(); s++) {
                    String signalKey = scoring.names().getString(s);
                    int weight = scoring.getInt(signalKey);
                    maxScore += weight;
                    if (allSignals.contains(signalKey)) {
                        achievedScore += weight;
                    }
                }

                int confidence = maxScore > 0 ? Math.round((achievedScore * 100f) / maxScore) : 0;

                // Also consider expected extensions match as bonus
                JSONArray expectedExts = build.getJSONArray("expectedExtensions");
                for (ExtractedContent ec : extracted) {
                    for (int e = 0; e < expectedExts.length(); e++) {
                        if (ec.extension.equalsIgnoreCase(expectedExts.getString(e))) {
                            confidence = Math.min(100, confidence + 5);
                            break;
                        }
                    }
                }

                int minConf = build.optInt("minConfidence", 0);
                if (confidence >= minConf) {
                    result.add(new BuildSuggestion(buildId, name, difficulty, templatePath, description, confidence));
                }
            }

            // Sort descending by confidence
            Collections.sort(result, new Comparator<BuildSuggestion>() {
                @Override
                public int compare(BuildSuggestion a, BuildSuggestion b) {
                    return Integer.compare(b.confidencePercent, a.confidencePercent);
                }
            });

            // If no strong match, add custom pipeline fallback at 30%
            if (result.isEmpty() && !extracted.isEmpty()) {
                result.add(new BuildSuggestion("custom_pipeline", "Custom Offline Pipeline", "Advanced", "templates/static_rag_pipeline.md", "Generic offline script tailored to your files.", 30));
            }

        } catch (Exception e) {
            // Fallback to hardcoded suggestions if JSON fails
            if (!extracted.isEmpty()) {
                result.add(new BuildSuggestion("ml_text_classifier", "Offline Text Classifier", "Beginner", "templates/ml_text_classifier.md", "TF-IDF classifier fallback.", 60));
                result.add(new BuildSuggestion("static_rag_pipeline", "Static RAG Pipeline", "Intermediate", "templates/static_rag_pipeline.md", "RAG fallback.", 55));
            }
        }
        return result;
    }

    private static String loadRawResource(Context context, int resId) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (InputStream is = context.getResources().openRawResource(resId);
             BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }
}
