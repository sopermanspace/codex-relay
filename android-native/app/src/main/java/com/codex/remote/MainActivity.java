package com.codex.remote;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final String PREFS = "codex_remote";
    private static final int BG = Color.rgb(6, 7, 10);
    private static final int PANEL = Color.rgb(12, 13, 17);
    private static final int PANEL_2 = Color.rgb(21, 23, 28);
    private static final int TEXT = Color.rgb(250, 250, 250);
    private static final int MUTED = Color.rgb(190, 193, 201);
    private static final int SOFT = Color.rgb(132, 137, 148);
    private static final int ACCENT = Color.rgb(52, 211, 153);
    private static final int ACCENT_DARK = Color.rgb(9, 92, 64);
    private static final int ERROR = Color.rgb(252, 165, 165);

    private FrameLayout root;
    private LinearLayout shell;
    private LinearLayout connectScreen;
    private LinearLayout workspaceScreen;
    private EditText serverInput;
    private EditText tokenInput;
    private EditText promptInput;
    private TextView statusPill;
    private TextView connectionStatus;
    private TextView resultTitle;
    private TextView resultBody;
    private TextView metaLabel;
    private ProgressBar progressBar;
    private Button unlockButton;
    private Button runButton;
    private Button copyButton;
    private SharedPreferences prefs;
    private String serverUrl = "";
    private String token = "";
    private String lastOutput = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        configureWindow();
        buildLayout();
        if (getIntent().getBooleanExtra("demo_dashboard", false)) {
            showDemoDashboard();
        } else {
            showConnect();
        }
    }

    @Override
    public void onBackPressed() {
        if (workspaceScreen.getVisibility() == View.VISIBLE) {
            showConnect();
            return;
        }
        super.onBackPressed();
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(BG);
        window.setNavigationBarColor(BG);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void buildLayout() {
        root = new FrameLayout(this);
        root.setBackground(gradient(BG, Color.rgb(8, 13, 12)));
        setContentView(root);

        root.addView(new AmbientGradientView(this), fullFrame());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, fullFrame());

        shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(22), dp(28), dp(22), dp(28));
        scroll.addView(shell, new ScrollView.LayoutParams(
            ScrollView.LayoutParams.MATCH_PARENT,
            ScrollView.LayoutParams.WRAP_CONTENT
        ));

        buildConnectScreen();
        buildWorkspaceScreen();

        progressBar = new ProgressBar(this);
        progressBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(dp(38), dp(38), Gravity.TOP | Gravity.END);
        progressParams.setMargins(0, dp(24), dp(24), 0);
        root.addView(progressBar, progressParams);
    }

    private void buildConnectScreen() {
        connectScreen = new LinearLayout(this);
        connectScreen.setOrientation(LinearLayout.VERTICAL);
        connectScreen.setGravity(Gravity.CENTER_HORIZONTAL);
        shell.addView(connectScreen, matchWrap());

        LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(dp(68), dp(68));
        markParams.topMargin = dp(18);
        connectScreen.addView(new CommandMarkView(this), markParams);

        TextView eyebrow = labelCaps("CODEX RELAY");
        LinearLayout.LayoutParams eyebrowParams = centerWrap();
        eyebrowParams.topMargin = dp(26);
        connectScreen.addView(eyebrow, eyebrowParams);

        TextView title = heroTitle("Codex Relay");
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = matchWrap();
        titleParams.topMargin = dp(8);
        connectScreen.addView(title, titleParams);

        TextView subtitle = body("Native Android control for the Codex app-server on your Mac.");
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = matchWrap();
        subtitleParams.topMargin = dp(12);
        subtitleParams.bottomMargin = dp(28);
        connectScreen.addView(subtitle, subtitleParams);

        TextView traits = quietCaps("NATIVE ANDROID  /  PRIVATE RELAY  /  DIRECT API");
        LinearLayout.LayoutParams traitsParams = centerWrap();
        traitsParams.bottomMargin = dp(22);
        connectScreen.addView(traits, traitsParams);

        connectScreen.addView(formLabel("Server URL"));
        serverInput = input(prefs.getString("server", getString(R.string.default_server_url)), false, "http://192.168.18.182:8787");
        connectScreen.addView(serverInput, fieldParams());

        connectScreen.addView(formLabel("Remote token"));
        tokenInput = input(prefs.getString("token", ""), true, "Paste token");
        connectScreen.addView(tokenInput, fieldParams());

        unlockButton = primaryButton("Connect to Codex");
        unlockButton.setOnClickListener(view -> connect());
        LinearLayout.LayoutParams unlockParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58));
        unlockParams.topMargin = dp(24);
        connectScreen.addView(unlockButton, unlockParams);

        connectionStatus = caption("Ready");
        connectionStatus.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.topMargin = dp(18);
        connectScreen.addView(connectionStatus, statusParams);
    }

    private void buildWorkspaceScreen() {
        workspaceScreen = new LinearLayout(this);
        workspaceScreen.setOrientation(LinearLayout.VERTICAL);
        workspaceScreen.setVisibility(View.GONE);
        shell.addView(workspaceScreen, matchWrap());

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        workspaceScreen.addView(header, matchWrap());

        CommandMarkView appMark = new CommandMarkView(this);
        LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        markParams.rightMargin = dp(12);
        header.addView(appMark, markParams);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        header.addView(titles, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        titles.addView(sectionTitle("Codex Relay"));
        metaLabel = caption("Connected");
        titles.addView(metaLabel);

        Button close = quietButton("Lock");
        close.setOnClickListener(view -> showConnect());
        header.addView(close, new LinearLayout.LayoutParams(dp(92), dp(48)));

        LinearLayout statusCard = miniPanel();
        LinearLayout.LayoutParams statusCardParams = matchWrap();
        statusCardParams.topMargin = dp(18);
        workspaceScreen.addView(statusCard, statusCardParams);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusCard.addView(statusRow, matchWrap());
        statusPill = chip("Online");
        statusRow.addView(statusPill, new LinearLayout.LayoutParams(dp(92), dp(36)));
        TextView mode = caption("Direct command API");
        LinearLayout.LayoutParams modeParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        modeParams.leftMargin = dp(12);
        statusRow.addView(mode, modeParams);
        TextView statusText = body("Send a task and get clean output from the Codex agent on your Mac.");
        LinearLayout.LayoutParams statusTextParams = matchWrap();
        statusTextParams.topMargin = dp(12);
        statusCard.addView(statusText, statusTextParams);

        TextView promptLabel = formLabel("Command");
        LinearLayout.LayoutParams promptLabelParams = matchWrap();
        promptLabelParams.topMargin = dp(20);
        workspaceScreen.addView(promptLabel, promptLabelParams);

        promptInput = input("", false, "Ask Codex to inspect, edit, summarize, or run a task.");
        promptInput.setSingleLine(false);
        promptInput.setMinLines(4);
        promptInput.setGravity(Gravity.TOP | Gravity.START);
        workspaceScreen.addView(promptInput, tallFieldParams());

        runButton = primaryButton("Send command");
        runButton.setOnClickListener(view -> runCommand());
        LinearLayout.LayoutParams runParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58));
        runParams.topMargin = dp(14);
        workspaceScreen.addView(runButton, runParams);

        LinearLayout resultCard = panel();
        LinearLayout.LayoutParams resultParams = matchWrap();
        resultParams.topMargin = dp(20);
        workspaceScreen.addView(resultCard, resultParams);

        LinearLayout resultHeader = new LinearLayout(this);
        resultHeader.setGravity(Gravity.CENTER_VERTICAL);
        resultCard.addView(resultHeader, matchWrap());
        resultTitle = sectionTitle("Result");
        resultHeader.addView(resultTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        copyButton = quietButton("Copy");
        copyButton.setEnabled(false);
        copyButton.setOnClickListener(view -> copyLastOutput());
        resultHeader.addView(copyButton, new LinearLayout.LayoutParams(dp(86), dp(42)));

        resultBody = mono("No command has been sent yet.");
        LinearLayout.LayoutParams bodyParams = matchWrap();
        bodyParams.topMargin = dp(14);
        resultCard.addView(resultBody, bodyParams);
    }

    private void connect() {
        String nextServer = serverInput.getText().toString().trim();
        String nextToken = tokenInput.getText().toString().trim();
        if (!isValidHttpUrl(nextServer)) {
            setConnectStatus("Enter a valid HTTP or HTTPS URL.", true);
            return;
        }
        if (nextToken.isEmpty()) {
            setConnectStatus("Remote token is required.", true);
            return;
        }

        serverUrl = trimSlash(nextServer);
        token = nextToken;
        progressBar.setVisibility(View.VISIBLE);
        unlockButton.setEnabled(false);
        setConnectStatus("Checking secure connection...", false);

        new Thread(() -> {
            try {
                verifyAuth();
                prefs.edit().putString("server", serverUrl).putString("token", token).apply();
                runOnUiThread(this::showWorkspace);
            } catch (Exception error) {
                runOnUiThread(() -> setConnectStatus(error.getMessage(), true));
            } finally {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    unlockButton.setEnabled(true);
                });
            }
        }).start();
    }

    private void runCommand() {
        String prompt = promptInput.getText().toString().trim();
        if (prompt.isEmpty()) {
            setResult("Task required", "Write a task for Codex first.", true);
            return;
        }

        setBusy(true);
        setResult("Running", "Codex is working on your Mac...", false);

        new Thread(() -> {
            long started = System.currentTimeMillis();
            try {
                JSONObject response = postCommand(prompt);
                long seconds = Math.max(1, (System.currentTimeMillis() - started) / 1000);
                boolean ok = response.optBoolean("ok", false);
                String output = response.optString("output", "");
                if (output.trim().isEmpty()) output = ok ? "Done." : "No output returned.";
                final String resultTitleText = ok ? "Completed in " + seconds + "s" : "Finished with exit code " + response.optInt("exitCode", -1);
                final String resultOutputText = output;
                runOnUiThread(() -> setResult(resultTitleText, resultOutputText, !ok));
            } catch (Exception error) {
                runOnUiThread(() -> setResult("Connection failed", error.getMessage(), true));
            } finally {
                runOnUiThread(() -> setBusy(false));
            }
        }).start();
    }

    private JSONObject postCommand(String prompt) throws Exception {
        URL url = new URL(serverUrl + "/api/command");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(600000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + token);
        JSONObject body = new JSONObject();
        body.put("prompt", prompt);
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(payload);
        }

        int status = connection.getResponseCode();
        String response = readAll(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
        if (status == 401) throw new Exception("Token rejected.");
        if (status < 200 || status >= 300) {
            String message = response.trim().isEmpty() ? "Server returned " + status + "." : response;
            throw new Exception(message);
        }
        return new JSONObject(response);
    }

    private void verifyAuth() throws Exception {
        URL url = new URL(serverUrl + "/api/auth");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        int status = connection.getResponseCode();
        if (status == 401) throw new Exception("Token rejected.");
        if (status < 200 || status >= 300) throw new Exception("Server returned " + status + ".");
        JSONObject response = new JSONObject(readAll(connection.getInputStream()));
        if (!response.optBoolean("ok", false)) throw new Exception("Server did not confirm access.");
    }

    private void showConnect() {
        connectScreen.setVisibility(View.VISIBLE);
        workspaceScreen.setVisibility(View.GONE);
        progressBar.setVisibility(View.GONE);
        setConnectStatus("Ready", false);
    }

    private void showWorkspace() {
        connectScreen.setVisibility(View.GONE);
        workspaceScreen.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.GONE);
        metaLabel.setText(serverUrl.replace("http://", "").replace("https://", ""));
        setResult("Result", "No command has been sent yet.", false);
    }

    private void showDemoDashboard() {
        serverUrl = "http://192.168.18.182:8787";
        showWorkspace();
        promptInput.setText("Summarize this repo and list the next three improvements.");
        setResult(
            "Completed in 18s",
            "1. App-server command API is live.\n2. Android dashboard renders clean output.\n3. Next: signed release build.",
            false
        );
    }

    private void setBusy(boolean busy) {
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        runButton.setEnabled(!busy);
        runButton.setText(busy ? "Running..." : "Send command");
        statusPill.setText(busy ? "Running" : "Online");
    }

    private void setConnectStatus(String message, boolean error) {
        connectionStatus.setText(message);
        connectionStatus.setTextColor(error ? ERROR : SOFT);
    }

    private void setResult(String title, String body, boolean error) {
        resultTitle.setText(title);
        resultTitle.setTextColor(error ? ERROR : TEXT);
        resultBody.setText(body);
        lastOutput = body;
        copyButton.setEnabled(!body.trim().isEmpty() && !"No output yet.".equals(body));
    }

    private void copyLastOutput() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Codex output", lastOutput));
        resultTitle.setText("Copied");
    }

    private boolean isValidHttpUrl(String value) {
        Uri uri = Uri.parse(value);
        String scheme = uri.getScheme();
        return uri.getHost() != null && ("http".equals(scheme) || "https".equals(scheme));
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(18), dp(18), dp(18));
        panel.setBackground(rounded(PANEL, Color.argb(34, 250, 250, 250), 1, 28));
        return panel;
    }

    private LinearLayout miniPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(14), dp(14), dp(14));
        panel.setBackground(rounded(Color.rgb(10, 12, 16), Color.argb(28, 250, 250, 250), 1, 24));
        return panel;
    }

    private LinearLayout metricRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = matchWrap();
        rowParams.topMargin = dp(6);
        rowParams.bottomMargin = dp(6);
        row.setLayoutParams(rowParams);

        TextView left = caption(label);
        row.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView right = new TextView(this);
        right.setText(value);
        right.setTextColor(TEXT);
        right.setTextSize(13);
        right.setGravity(Gravity.END);
        right.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        row.addView(right, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    private void addMiniChip(LinearLayout parent, String value) {
        TextView chip = new TextView(this);
        chip.setText(value);
        chip.setTextColor(Color.rgb(204, 251, 241));
        chip.setTextSize(13);
        chip.setGravity(Gravity.CENTER);
        chip.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        chip.setBackground(rounded(Color.rgb(8, 38, 31), Color.argb(74, 52, 211, 153), 1, 18));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(38), 1);
        params.setMargins(dp(4), 0, dp(4), 0);
        parent.addView(chip, params);
    }

    private EditText input(String value, boolean password, String hint) {
        EditText input = new EditText(this);
        input.setText(value);
        input.setHint(hint);
        input.setTextSize(16);
        input.setTextColor(TEXT);
        input.setHintTextColor(Color.rgb(112, 116, 126));
        input.setPadding(dp(18), 0, dp(18), 0);
        input.setBackground(rounded(Color.rgb(3, 4, 7), Color.argb(42, 250, 250, 250), 1, 22));
        input.setSingleLine(!password);
        input.setInputType(password
            ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
            : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        return input;
    }

    private Button primaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(Color.rgb(3, 4, 7));
        button.setTextSize(16);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(rounded(Color.WHITE, Color.argb(160, 255, 255, 255), 1, 24));
        return button;
    }

    private Button quietButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(TEXT);
        button.setTextSize(13);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(rounded(PANEL_2, Color.argb(40, 250, 250, 250), 1, 20));
        return button;
    }

    private TextView labelCaps(String value) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(ACCENT);
        text.setTextSize(12);
        text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        text.setLetterSpacing(0.08f);
        return text;
    }

    private TextView quietCaps(String value) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(Color.rgb(126, 132, 142));
        text.setTextSize(11);
        text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        text.setLetterSpacing(0.05f);
        text.setGravity(Gravity.CENTER);
        return text;
    }

    private TextView formLabel(String value) {
        TextView text = caption(value);
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(14);
        text.setLayoutParams(params);
        return text;
    }

    private TextView title(String value) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(TEXT);
        text.setTextSize(34);
        text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        text.setIncludeFontPadding(false);
        return text;
    }

    private TextView heroTitle(String value) {
        TextView text = title(value);
        text.setTextSize(38);
        return text;
    }

    private TextView sectionTitle(String value) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(TEXT);
        text.setTextSize(18);
        text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    private TextView body(String value) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(MUTED);
        text.setTextSize(15);
        text.setLineSpacing(dp(2), 1f);
        return text;
    }

    private TextView caption(String value) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(SOFT);
        text.setTextSize(12);
        return text;
    }

    private TextView chip(String value) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(Color.rgb(187, 247, 208));
        text.setTextSize(13);
        text.setGravity(Gravity.CENTER);
        text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        text.setBackground(rounded(ACCENT_DARK, Color.argb(78, 52, 211, 153), 1, 18));
        return text;
    }

    private TextView mono(String value) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(Color.rgb(232, 238, 246));
        text.setTextSize(13);
        text.setTypeface(Typeface.MONOSPACE);
        text.setLineSpacing(dp(3), 1f);
        text.setPadding(dp(14), dp(14), dp(14), dp(14));
        text.setBackground(rounded(Color.rgb(2, 3, 5), Color.argb(30, 250, 250, 250), 1, 20));
        return text;
    }

    private LinearLayout.LayoutParams fieldParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        params.topMargin = dp(7);
        return params;
    }

    private LinearLayout.LayoutParams tallFieldParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(142));
        params.topMargin = dp(8);
        return params;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams centerWrap() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.CENTER_HORIZONTAL;
        return params;
    }

    private FrameLayout.LayoutParams fullFrame() {
        return new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private GradientDrawable rounded(int fill, int stroke, int strokeWidthDp, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(strokeWidthDp), stroke);
        return drawable;
    }

    private GradientDrawable gradient(int start, int end) {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{start, end});
        drawable.setDither(true);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) builder.append(line).append('\n');
        return builder.toString().trim();
    }

    private static String trimSlash(String value) {
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static final class AmbientGradientView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        AmbientGradientView(Context context) {
            super(context);
            setAlpha(0.88f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int width = getWidth();
            int height = getHeight();

            paint.setShader(new RadialGradient(
                width * 0.2f,
                height * 0.05f,
                width * 0.72f,
                new int[]{Color.argb(82, 16, 185, 129), Color.argb(18, 16, 185, 129), Color.TRANSPARENT},
                new float[]{0f, 0.42f, 1f},
                Shader.TileMode.CLAMP
            ));
            canvas.drawCircle(width * 0.2f, height * 0.05f, width * 0.72f, paint);

            paint.setShader(new RadialGradient(
                width * 0.88f,
                height * 0.48f,
                width * 0.52f,
                new int[]{Color.argb(42, 255, 255, 255), Color.argb(12, 255, 255, 255), Color.TRANSPARENT},
                new float[]{0f, 0.36f, 1f},
                Shader.TileMode.CLAMP
            ));
            canvas.drawCircle(width * 0.88f, height * 0.48f, width * 0.52f, paint);

            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(42, 255, 255, 255));
            float base = Math.max(1f, width * 0.004f);
            for (int i = 0; i < 18; i++) {
                float x = ((i * 37) % 100) / 100f * width;
                float y = (0.12f + (((i * 53) % 78) / 100f)) * height;
                float radius = base * (1f + (i % 3) * 0.45f);
                canvas.drawCircle(x, y, radius, paint);
            }
        }
    }

    private final class CommandMarkView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        CommandMarkView(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float inset = Math.max(2f, w * 0.04f);

            rect.set(inset, inset, w - inset, h - inset);
            paint.setShader(new RadialGradient(
                w * 0.28f,
                h * 0.18f,
                w * 0.8f,
                new int[]{Color.rgb(27, 45, 39), Color.rgb(8, 10, 14)},
                null,
                Shader.TileMode.CLAMP
            ));
            canvas.drawRoundRect(rect, w * 0.26f, w * 0.26f, paint);

            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, w * 0.025f));
            paint.setColor(Color.argb(150, 52, 211, 153));
            canvas.drawRoundRect(rect, w * 0.26f, w * 0.26f, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(w * 0.07f);
            paint.setColor(Color.rgb(110, 231, 183));
            float left = w * 0.33f;
            float mid = w * 0.43f;
            float right = w * 0.33f;
            canvas.drawLine(left, h * 0.36f, mid, h * 0.5f, paint);
            canvas.drawLine(mid, h * 0.5f, right, h * 0.64f, paint);
            canvas.drawLine(w * 0.56f, h * 0.62f, w * 0.72f, h * 0.62f, paint);

            paint.setStyle(Paint.Style.FILL);
        }
    }
}
