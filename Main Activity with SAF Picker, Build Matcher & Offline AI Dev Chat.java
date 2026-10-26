package com.buildgenie;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.buildgenie.analysis.BuildMatcher;
import com.buildgenie.analysis.BuildMatcher.BuildSuggestion;
import com.buildgenie.chat.LocalAiChatEngine;
import com.buildgenie.chat.LocalAiChatEngine.ChatMessage;
import com.buildgenie.ingestion.TextExtractor;
import com.buildgenie.ingestion.TextExtractor.ExtractedContent;
import com.buildgenie.service.FileWatchService;
import com.buildgenie.utils.PdfExporter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Self-contained MainActivity featuring:
 * 1. SAF Inbox Folder Picker & Local Demo File Seeder
 * 2. Live Build Suggestions List with 1-Tap Zero-Metadata PDF Export
 * 3. Built-in Offline AI Dev Chat Drawer
 */
public class MainActivity extends AppCompatActivity {

    private final List<ExtractedContent> ingestedFiles = new ArrayList<>();
    private List<BuildSuggestion> currentSuggestions = new ArrayList<>();
    private LocalAiChatEngine aiChatEngine;
    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();

    private TextView statusBanner;
    private LinearLayout filesContainer;
    private LinearLayout suggestionsContainer;
    private LinearLayout chatMessagesContainer;
    private EditText chatInput;

    private final ActivityResultLauncher<Uri> safTreeLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri != null) {
                    getContentResolver().takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    statusBanner.setText("SAF Inbox Linked. Syncing files offline...");
                    syncAndAnalyzeDirectory();
                }
            });

    private final BroadcastReceiver ingestionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshStagingDirectory();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        aiChatEngine = new LocalAiChatEngine(this);

        // Programmatic responsive UI so it compiles immediately without brittle XML IDs
        ScrollView rootScroll = new ScrollView(this);
        rootScroll.setFillViewport(true);

        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        mainLayout.setPadding(pad, pad, pad, pad);
        rootScroll.addView(mainLayout);

        // Header
        TextView title = new TextView(this);
        title.setText("BuildGenie • Offline Builder & AI Dev Chat");
        title.setTextSize(20f);
        title.setPadding(0, 0, 0, dp(4));
        mainLayout.addView(title);

        statusBanner = new TextView(this);
        statusBanner.setText("100% On-Device • Zero Telemetry • Zero Network Permission");
        statusBanner.setTextSize(13f);
        statusBanner.setPadding(0, 0, 0, dp(12));
        mainLayout.addView(statusBanner);

        // Action Bar Buttons
        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);

        Button pickFolderBtn = new Button(this);
        pickFolderBtn.setText("1. Pick SAF Inbox");
        pickFolderBtn.setOnClickListener(v -> safTreeLauncher.launch(null));
        buttonRow.addView(pickFolderBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button seedSampleBtn = new Button(this);
        seedSampleBtn.setText("2. Seed Sample Files");
        seedSampleBtn.setOnClickListener(v -> seedSampleStagingFiles());
        buttonRow.addView(seedSampleBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mainLayout.addView(buttonRow);

        // Ingested Files Section
        TextView filesHeader = sectionHeader("Ingested Local Files & Schemas");
        mainLayout.addView(filesHeader);
        filesContainer = new LinearLayout(this);
        filesContainer.setOrientation(LinearLayout.VERTICAL);
        mainLayout.addView(filesContainer);

        // Suggested Builds Section
        TextView buildsHeader = sectionHeader("Suggested Builds (Tap to Export Zero-PII PDF)");
        mainLayout.addView(buildsHeader);
        suggestionsContainer = new LinearLayout(this);
        suggestionsContainer.setOrientation(LinearLayout.VERTICAL);
        mainLayout.addView(suggestionsContainer);

        // Offline AI Dev Chat Section
        TextView chatHeader = sectionHeader("Offline AI Dev Chat (Local Context & Code Gen)");
        mainLayout.addView(chatHeader);

        chatMessagesContainer = new LinearLayout(this);
        chatMessagesContainer.setOrientation(LinearLayout.VERTICAL);
        chatMessagesContainer.setPadding(dp(8), dp(8), dp(8), dp(8));
        mainLayout.addView(chatMessagesContainer);

        LinearLayout chatInputRow = new LinearLayout(this);
        chatInputRow.setOrientation(LinearLayout.HORIZONTAL);
        chatInputRow.setGravity(Gravity.CENTER_VERTICAL);

        chatInput = new EditText(this);
        chatInput.setHint("Ask AI about schemas, Python scripts, or CLI flags...");
        chatInput.setInputType(InputType.TYPE_CLASS_TEXT);
        chatInputRow.addView(chatInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button sendBtn = new Button(this);
        sendBtn.setText("Ask AI");
        sendBtn.setOnClickListener(v -> handleChatSubmit());
        chatInputRow.addView(sendBtn);

        mainLayout.addView(chatInputRow);
        setContentView(rootScroll);

        // Start foreground file watcher service safely
        Intent serviceIntent = new Intent(this, FileWatchService.class);
        ContextCompat.startForegroundService(this, serviceIntent);

        renderChatMessages();
        refreshStagingDirectory();
    }

    private void handleChatSubmit() {
        String prompt = chatInput.getText().toString().trim();
        if (prompt.isEmpty()) return;
        chatInput.setText("");
        aiChatEngine.askQuestion(prompt, ingestedFiles, currentSuggestions);
        renderChatMessages();
    }

    private void renderChatMessages() {
        chatMessagesContainer.removeAllViews();
        for (ChatMessage msg : aiChatEngine.getHistory()) {
            TextView tv = new TextView(this);
            tv.setText(msg.sender + ":\n" + msg.message);
            tv.setPadding(dp(10), dp(8), dp(10), dp(8));
            tv.setTextSize(msg.isCodeBlock ? 12f : 14f);
            if (msg.isCodeBlock) {
                tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            }
            chatMessagesContainer.addView(tv);
        }
    }

    private void seedSampleStagingFiles() {
        bgExecutor.submit(() -> {
            try {
                File staging = new File(getFilesDir(), "staging");
                if (!staging.exists()) staging.mkdirs();

                File csv = new File(staging, "support_tickets.csv");
                java.nio.file.Files.write(csv.toPath(),
                        "text,label,category\n\"App crashes on launch\",bug,mobile\n\"Add dark mode\",feature,ui\n".getBytes());

                File jsonl = new File(staging, "finetune_prompts.jsonl");
                java.nio.file.Files.write(jsonl.toPath(),
                        "{\"prompt\": \"Summarize doc\", \"completion\": \"Done.\"}\n".getBytes());

                File yaml = new File(staging, "training_config.yaml");
                java.nio.file.Files.write(yaml.toPath(),
                        "learning_rate: 0.0002\nepochs: 3\nbatch_size: 8\n".getBytes());

                refreshStagingDirectory();
            } catch (Exception ignored) {}
        });
    }

    private void syncAndAnalyzeDirectory() {
        refreshStagingDirectory();
    }

    private void refreshStagingDirectory() {
        bgExecutor.submit(() -> {
            File staging = new File(getFilesDir(), "staging");
            if (!staging.exists()) staging.mkdirs();

            List<ExtractedContent> extracted = new ArrayList<>();
            File[] files = staging.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile()) {
                        extracted.add(TextExtractor.extract(getApplicationContext(), f));
                    }
                }
            }

            int buildsResId = getResources().getIdentifier("builds", "raw", getPackageName());
            List<BuildSuggestion> matched = buildsResId != 0
                    ? BuildMatcher.matchBuilds(getApplicationContext(), extracted, buildsResId)
                    : new ArrayList<>();

            runOnUiThread(() -> {
                ingestedFiles.clear();
                ingestedFiles.addAll(extracted);
                currentSuggestions = matched;
                renderFilesAndSuggestions();
            });
        });
    }

    private void renderFilesAndSuggestions() {
        filesContainer.removeAllViews();
        if (ingestedFiles.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No files staged yet. Tap '2. Seed Sample Files' or pick a folder.");
            filesContainer.addView(empty);
        } else {
            for (ExtractedContent ec : ingestedFiles) {
                TextView item = new TextView(this);
                item.setText("• " + ec.fileName + " — " + ec.detectedSchema + " " + ec.detectedSignals);
                item.setPadding(0, dp(4), 0, dp(4));
                filesContainer.addView(item);
            }
        }

        suggestionsContainer.removeAllViews();
        for (BuildSuggestion s : currentSuggestions) {
            Button buildBtn = new Button(this);
            buildBtn.setAllCaps(false);
            buildBtn.setText(s.name + " (" + s.confidencePercent + "% Match • " + s.difficulty + ")");
            buildBtn.setOnClickListener(v -> exportPdfGuide(s));
            suggestionsContainer.addView(buildBtn);
        }
    }

    private void exportPdfGuide(BuildSuggestion suggestion) {
        statusBanner.setText("Generating zero-metadata PDF for " + suggestion.name + "...");
        bgExecutor.submit(() -> {
            try {
                String savedPath = PdfExporter.generateBuildGuidePdf(
                        getApplicationContext(),
                        suggestion.buildId,
                        suggestion.templatePath,
                        ingestedFiles
                );
                runOnUiThread(() -> statusBanner.setText("Saved clean PDF: " + savedPath));
            } catch (Exception e) {
                runOnUiThread(() -> statusBanner.setText("PDF Export note: Ensure template asset exists (" + suggestion.templatePath + ")"));
            }
        });
    }

    private TextView sectionHeader(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(16f);
        tv.setPadding(0, dp(16), 0, dp(6));
        return tv;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter("com.buildgenie.ACTION_FILE_INGESTED");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(ingestionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(ingestionReceiver, filter);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(ingestionReceiver);
    }
}