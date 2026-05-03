package com.codex.remote;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
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
import android.widget.Toast;

import org.json.JSONArray;
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
    private static final String NOTIFICATION_CHANNEL = "codex_task_status";
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
    private TextView projectTitle;
    private TextView projectPathLabel;
    private TextView projectSetupStatus;
    private TextView chatContextLabel;
    private TextView accessModeHint;
    private TextView securityStatus;
    private EditText projectNameInput;
    private LinearLayout projectList;
    private LinearLayout slashCommandList;
    private LinearLayout mentionList;
    private ProgressBar progressBar;
    private Button unlockButton;
    private Button runButton;
    private Button copyButton;
    private Button autoSecurityButton;
    private Button homeOnlySecurityButton;
    private SharedPreferences prefs;
    private String serverUrl = "";
    private String token = "";
    private String lastOutput = "";
    private String selectedProjectPath = "";
    private String selectedProjectName = "Default workspace";
    private String accessMode = "auto";
    private JSONArray loadedProjects = new JSONArray();
    private JSONArray loadedSlashCommands = new JSONArray();
    private JSONArray loadedMentions = new JSONArray();
    private int chatNumber = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        configureWindow();
        configureNotifications();
        buildLayout();
        accessMode = "local".equals(prefs.getString("access_mode", "auto")) ? "local" : "auto";
        updateAccessModeUi();
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

    private void configureNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL,
                "Codex task status",
                NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription("Alerts when Codex finishes a phone-started task.");
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            manager.createNotificationChannel(channel);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 42);
        }
    }

    private void notifyTaskDone(String title, String message) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        android.app.Notification notification = new android.app.Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build();
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(1001, notification);
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

        TextView subtitle = body("Native Android control for the Codex app-server on your Mac.");
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = matchWrap();
        subtitleParams.topMargin = dp(14);
        subtitleParams.bottomMargin = dp(32);
        connectScreen.addView(subtitle, subtitleParams);

        accessModeHint = caption("Use your home Wi-Fi URL here. If you are away, use your secure link.");
        accessModeHint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams modeHintParams = matchWrap();
        modeHintParams.bottomMargin = dp(18);
        connectScreen.addView(accessModeHint, modeHintParams);

        connectScreen.addView(formLabel("Server URL"));
        String savedServer = prefs.getString("server", getString(R.string.default_server_url));
        if (savedServer.contains("192.168.18.182")) savedServer = getString(R.string.default_server_url);
        serverInput = input(savedServer, false, getString(R.string.default_server_url));
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
        TextView statusText = body("Choose a project, start a chat, then send focused Codex tasks to your Mac.");
        LinearLayout.LayoutParams statusTextParams = matchWrap();
        statusTextParams.topMargin = dp(12);
        statusCard.addView(statusText, statusTextParams);

        chatContextLabel = caption("Chat 1 · No project selected");
        LinearLayout.LayoutParams chatContextParams = matchWrap();
        chatContextParams.topMargin = dp(10);
        statusCard.addView(chatContextLabel, chatContextParams);

        LinearLayout securityCard = miniPanel();
        LinearLayout.LayoutParams securityParams = matchWrap();
        securityParams.topMargin = dp(14);
        workspaceScreen.addView(securityCard, securityParams);

        securityCard.addView(sectionTitle("Security"), matchWrap());

        LinearLayout securityButtons = new LinearLayout(this);
        securityButtons.setOrientation(LinearLayout.HORIZONTAL);
        securityButtons.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams securityButtonsParams = matchWrap();
        securityButtonsParams.topMargin = dp(12);
        securityCard.addView(securityButtons, securityButtonsParams);

        autoSecurityButton = quietButton("Auto");
        autoSecurityButton.setOnClickListener(view -> setAccessMode("auto"));
        LinearLayout.LayoutParams autoParams = new LinearLayout.LayoutParams(0, dp(46), 1);
        autoParams.rightMargin = dp(8);
        securityButtons.addView(autoSecurityButton, autoParams);

        homeOnlySecurityButton = quietButton("Home only");
        homeOnlySecurityButton.setOnClickListener(view -> setAccessMode("local"));
        LinearLayout.LayoutParams homeOnlyParams = new LinearLayout.LayoutParams(0, dp(46), 1);
        homeOnlyParams.leftMargin = dp(8);
        securityButtons.addView(homeOnlySecurityButton, homeOnlyParams);

        securityStatus = caption("Auto works at home and with your secure link.");
        LinearLayout.LayoutParams securityStatusParams = matchWrap();
        securityStatusParams.topMargin = dp(10);
        securityCard.addView(securityStatus, securityStatusParams);
        updateAccessModeUi();

        LinearLayout projectsCard = panel();
        LinearLayout.LayoutParams projectsParams = matchWrap();
        projectsParams.topMargin = dp(18);
        workspaceScreen.addView(projectsCard, projectsParams);

        LinearLayout projectsHeader = new LinearLayout(this);
        projectsHeader.setGravity(Gravity.CENTER_VERTICAL);
        projectsCard.addView(projectsHeader, matchWrap());
        TextView projectsLabel = sectionTitle("Project Sidebar");
        projectsHeader.addView(projectsLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button refreshProjects = quietButton("Sync");
        refreshProjects.setOnClickListener(view -> loadProjects());
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(dp(72), dp(42));
        refreshParams.rightMargin = dp(8);
        projectsHeader.addView(refreshProjects, refreshParams);

        Button newChat = quietButton("New Chat");
        newChat.setOnClickListener(view -> startNewChat());
        projectsHeader.addView(newChat, new LinearLayout.LayoutParams(dp(108), dp(42)));

        projectTitle = body("Default workspace");
        projectTitle.setTextColor(TEXT);
        projectTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams projectTitleParams = matchWrap();
        projectTitleParams.topMargin = dp(14);
        projectsCard.addView(projectTitle, projectTitleParams);

        projectPathLabel = caption("Commands run in the server workspace until you pick a project.");
        LinearLayout.LayoutParams projectPathParams = matchWrap();
        projectPathParams.topMargin = dp(4);
        projectsCard.addView(projectPathLabel, projectPathParams);

        LinearLayout setupRow = new LinearLayout(this);
        setupRow.setOrientation(LinearLayout.HORIZONTAL);
        setupRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams setupParams = matchWrap();
        setupParams.topMargin = dp(14);
        projectsCard.addView(setupRow, setupParams);

        projectNameInput = input("", false, "New project name");
        setupRow.addView(projectNameInput, new LinearLayout.LayoutParams(0, dp(52), 1));

        Button createProject = quietButton("Create");
        createProject.setOnClickListener(view -> createProjectFromInput());
        LinearLayout.LayoutParams createParams = new LinearLayout.LayoutParams(dp(92), dp(52));
        createParams.leftMargin = dp(10);
        setupRow.addView(createProject, createParams);

        projectSetupStatus = caption("Create a folder, then run Codex inside it.");
        LinearLayout.LayoutParams setupStatusParams = matchWrap();
        setupStatusParams.topMargin = dp(8);
        projectsCard.addView(projectSetupStatus, setupStatusParams);

        projectList = new LinearLayout(this);
        projectList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams projectListParams = matchWrap();
        projectListParams.topMargin = dp(12);
        projectsCard.addView(projectList, projectListParams);
        renderProjectLoading();

        TextView promptLabel = formLabel("Command");
        LinearLayout.LayoutParams promptLabelParams = matchWrap();
        promptLabelParams.topMargin = dp(20);
        workspaceScreen.addView(promptLabel, promptLabelParams);

        promptInput = input("", false, "Ask Codex to inspect, edit, summarize, or run a task.");
        promptInput.setSingleLine(false);
        promptInput.setMinLines(4);
        promptInput.setGravity(Gravity.TOP | Gravity.START);
        workspaceScreen.addView(promptInput, tallFieldParams());

        LinearLayout slashCard = miniPanel();
        LinearLayout.LayoutParams slashParams = matchWrap();
        slashParams.topMargin = dp(12);
        workspaceScreen.addView(slashCard, slashParams);
        slashCard.addView(sectionTitle("Slash commands"), matchWrap());
        slashCommandList = new LinearLayout(this);
        slashCommandList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams slashListParams = matchWrap();
        slashListParams.topMargin = dp(10);
        slashCard.addView(slashCommandList, slashListParams);
        renderSlashCommandLoading();

        LinearLayout mentionCard = miniPanel();
        LinearLayout.LayoutParams mentionParams = matchWrap();
        mentionParams.topMargin = dp(12);
        workspaceScreen.addView(mentionCard, mentionParams);
        mentionCard.addView(sectionTitle("@ Plugins and files"), matchWrap());
        mentionList = new LinearLayout(this);
        mentionList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams mentionListParams = matchWrap();
        mentionListParams.topMargin = dp(10);
        mentionCard.addView(mentionList, mentionListParams);
        renderMentionLoading();

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
                prefs.edit()
                    .putString("server", serverUrl)
                    .putString("token", token)
                    .putString("access_mode", accessMode)
                    .apply();
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
                runOnUiThread(() -> {
                    setResult(resultTitleText, resultOutputText, !ok);
                    notifyTaskDone(resultTitleText, ok ? "Codex finished on your Mac." : "Codex needs attention.");
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setResult("Connection failed", error.getMessage(), true);
                    notifyTaskDone("Codex command failed", error.getMessage());
                });
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
        setAccessHeaders(connection);
        JSONObject body = new JSONObject();
        body.put("prompt", prompt);
        if (!selectedProjectPath.trim().isEmpty()) body.put("cwd", selectedProjectPath);
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
        setAccessHeaders(connection);
        int status = connection.getResponseCode();
        if (status == 401) throw new Exception("Token rejected.");
        if (status < 200 || status >= 300) throw new Exception("Server returned " + status + ".");
        JSONObject response = new JSONObject(readAll(connection.getInputStream()));
        if (!response.optBoolean("ok", false)) throw new Exception("Server did not confirm access.");
    }

    private void loadProjects() {
        if (serverUrl.trim().isEmpty() || token.trim().isEmpty()) return;
        renderProjectLoading();

        new Thread(() -> {
            try {
                JSONArray projects = getProjects();
                runOnUiThread(() -> renderProjects(projects));
            } catch (Exception error) {
                runOnUiThread(() -> renderProjectError(error.getMessage()));
            }
        }).start();
    }

    private JSONArray getProjects() throws Exception {
        URL url = new URL(serverUrl + "/api/projects");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(20000);
        setAccessHeaders(connection);
        int status = connection.getResponseCode();
        String response = readAll(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
        if (status == 401) throw new Exception("Token rejected.");
        if (status < 200 || status >= 300) throw new Exception(response.trim().isEmpty() ? "Server returned " + status + "." : response);
        JSONObject object = new JSONObject(response);
        return object.optJSONArray("projects") == null ? new JSONArray() : object.optJSONArray("projects");
    }

    private void createProjectFromInput() {
        String name = projectNameInput.getText().toString().trim();
        if (name.isEmpty()) {
            setProjectSetupStatus("Enter a project name.", true);
            return;
        }
        setProjectSetupStatus("Creating project on your Mac...", false);

        new Thread(() -> {
            try {
                JSONObject project = createProject(name);
                String projectName = project.optString("name", name);
                String projectPath = project.optString("path", "");
                runOnUiThread(() -> {
                    projectNameInput.setText("");
                    selectProject(projectName, projectPath);
                    setProjectSetupStatus("Project ready. Commands now run there.", false);
                    loadProjects();
                });
            } catch (Exception error) {
                runOnUiThread(() -> setProjectSetupStatus(error.getMessage(), true));
            }
        }).start();
    }

    private JSONObject createProject(String name) throws Exception {
        URL url = new URL(serverUrl + "/api/projects");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(20000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        setAccessHeaders(connection);
        JSONObject body = new JSONObject();
        body.put("name", name);
        body.put("brief", "Created from Codex Relay mobile setup.");
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(payload);
        }

        int status = connection.getResponseCode();
        String response = readAll(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
        if (status == 401) throw new Exception("Token rejected.");
        if (status < 200 || status >= 300) throw new Exception(response.trim().isEmpty() ? "Server returned " + status + "." : response);
        JSONObject object = new JSONObject(response);
        return object.optJSONObject("project") == null ? new JSONObject() : object.optJSONObject("project");
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
        if (!getIntent().getBooleanExtra("demo_dashboard", false)) loadProjects();
        loadSlashCommands();
        loadMentions();
        setResult("Result", "No command has been sent yet.", false);
    }

    private void showDemoDashboard() {
        serverUrl = "http://192.168.18.182:8787";
        showWorkspace();
        selectProject("New project 5", "/Users/himanshu/Documents/New project 5");
        renderDemoProjects();
        renderMentions(defaultMentions());
        promptInput.setText("Summarize this repo and list the next three improvements.");
        renderSlashCommands(defaultSlashCommands());
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

    private void setAccessMode(String mode) {
        accessMode = "local".equals(mode) ? "local" : "auto";
        prefs.edit().putString("access_mode", accessMode).apply();
        updateAccessModeUi();
        if (workspaceScreen != null && workspaceScreen.getVisibility() == View.VISIBLE) {
            Toast.makeText(this, "Security changed. Reconnect to apply.", Toast.LENGTH_LONG).show();
            showConnect();
        }
    }

    private void updateAccessModeUi() {
        boolean autoSelected = !"local".equals(accessMode);
        if (autoSecurityButton != null) {
            autoSecurityButton.setTextColor(autoSelected ? Color.rgb(3, 4, 7) : TEXT);
            autoSecurityButton.setBackground(rounded(
                autoSelected ? ACCENT : PANEL_2,
                autoSelected ? Color.argb(180, 167, 243, 208) : Color.argb(40, 250, 250, 250),
                1,
                20
            ));
        }
        if (homeOnlySecurityButton != null) {
            boolean selected = "local".equals(accessMode);
            homeOnlySecurityButton.setTextColor(selected ? Color.rgb(3, 4, 7) : TEXT);
            homeOnlySecurityButton.setBackground(rounded(
                selected ? ACCENT : PANEL_2,
                selected ? Color.argb(180, 167, 243, 208) : Color.argb(40, 250, 250, 250),
                1,
                20
            ));
        }
        if (securityStatus != null) {
            securityStatus.setText(autoSelected
                ? "Auto works at home and with your secure link."
                : "Home only blocks connections outside your trusted Wi-Fi.");
        }
        if (accessModeHint != null) {
            accessModeHint.setText("Use your home Wi-Fi URL here. If you are away, use your secure link.");
        }
    }

    private void setAccessHeaders(HttpURLConnection connection) {
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("X-Codex-Access-Mode", accessMode);
    }

    private void setResult(String title, String body, boolean error) {
        resultTitle.setText(title);
        resultTitle.setTextColor(error ? ERROR : TEXT);
        resultBody.setText(body);
        lastOutput = body;
        copyButton.setEnabled(!body.trim().isEmpty() && !"No output yet.".equals(body));
    }

    private void startNewChat() {
        chatNumber += 1;
        promptInput.setText("");
        setResult("New chat", "No command has been sent yet.", false);
        setBusy(false);
        updateChatContext();
        setProjectSetupStatus("New chat started in " + selectedProjectName + ".", false);
    }

    private void setProjectSetupStatus(String message, boolean error) {
        if (projectSetupStatus == null) return;
        projectSetupStatus.setText(message);
        projectSetupStatus.setTextColor(error ? ERROR : SOFT);
    }

    private void renderProjectLoading() {
        if (projectList == null) return;
        projectList.removeAllViews();
        projectList.addView(projectRow("Loading projects...", "Reading folders from your Mac", false, null), matchWrap());
    }

    private void loadSlashCommands() {
        if (serverUrl.trim().isEmpty() || token.trim().isEmpty()) {
            renderSlashCommands(defaultSlashCommands());
            return;
        }
        renderSlashCommandLoading();

        new Thread(() -> {
            try {
                JSONArray commands = getSlashCommands();
                runOnUiThread(() -> renderSlashCommands(commands));
            } catch (Exception error) {
                runOnUiThread(() -> renderSlashCommands(defaultSlashCommands()));
            }
        }).start();
    }

    private void loadMentions() {
        if (serverUrl.trim().isEmpty() || token.trim().isEmpty()) {
            renderMentions(defaultMentions());
            return;
        }
        renderMentionLoading();

        new Thread(() -> {
            try {
                JSONArray mentions = getMentions();
                runOnUiThread(() -> renderMentions(mentions));
            } catch (Exception error) {
                runOnUiThread(() -> renderMentionError(error.getMessage()));
            }
        }).start();
    }

    private JSONArray getMentions() throws Exception {
        String urlValue = serverUrl + "/api/mentions";
        if (!selectedProjectPath.trim().isEmpty()) {
            urlValue += "?cwd=" + Uri.encode(selectedProjectPath);
        }
        URL url = new URL(urlValue);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(20000);
        setAccessHeaders(connection);
        int status = connection.getResponseCode();
        String response = readAll(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
        if (status == 401) throw new Exception("Token rejected.");
        if (status < 200 || status >= 300) throw new Exception(response.trim().isEmpty() ? "Server returned " + status + "." : response);
        JSONObject object = new JSONObject(response);
        return object.optJSONArray("mentions") == null ? new JSONArray() : object.optJSONArray("mentions");
    }

    private void renderMentionLoading() {
        if (mentionList == null) return;
        mentionList.removeAllViews();
        mentionList.addView(caption("Loading plugins and files..."), matchWrap());
    }

    private void renderMentionError(String message) {
        if (mentionList == null) return;
        mentionList.removeAllViews();
        mentionList.addView(caption(message), matchWrap());
    }

    private void renderMentions(JSONArray mentions) {
        if (mentionList == null) return;
        loadedMentions = mentions;
        mentionList.removeAllViews();
        int count = Math.min(mentions.length(), 10);
        if (count == 0) {
            mentionList.addView(caption("No plugins or files found."), matchWrap());
            return;
        }
        for (int index = 0; index < count; index++) {
            JSONObject mention = mentions.optJSONObject(index);
            if (mention == null) continue;
            String label = mention.optString("label", "");
            String detail = mention.optString("detail", "Mention");
            View row = mentionRow(label, detail);
            LinearLayout.LayoutParams params = matchWrap();
            if (index > 0) params.topMargin = dp(8);
            mentionList.addView(row, params);
        }
    }

    private View mentionRow(String label, String detail) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackground(rounded(Color.rgb(4, 5, 8), Color.argb(34, 250, 250, 250), 1, 18));
        row.setOnClickListener(view -> appendPromptToken(label));

        TextView mentionName = new TextView(this);
        mentionName.setText(label);
        mentionName.setTextColor(TEXT);
        mentionName.setTextSize(13);
        mentionName.setSingleLine(true);
        mentionName.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        row.addView(mentionName, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView mentionDetail = caption(detail);
        mentionDetail.setGravity(Gravity.END);
        row.addView(mentionDetail, new LinearLayout.LayoutParams(dp(104), LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private JSONArray getSlashCommands() throws Exception {
        URL url = new URL(serverUrl + "/api/slash-commands");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(20000);
        setAccessHeaders(connection);
        int status = connection.getResponseCode();
        String response = readAll(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
        if (status == 401) throw new Exception("Token rejected.");
        if (status < 200 || status >= 300) throw new Exception(response.trim().isEmpty() ? "Server returned " + status + "." : response);
        JSONObject object = new JSONObject(response);
        return object.optJSONArray("commands") == null ? defaultSlashCommands() : object.optJSONArray("commands");
    }

    private void renderSlashCommandLoading() {
        if (slashCommandList == null) return;
        slashCommandList.removeAllViews();
        slashCommandList.addView(caption("Loading command palette..."), matchWrap());
    }

    private void renderSlashCommands(JSONArray commands) {
        if (slashCommandList == null) return;
        loadedSlashCommands = commands;
        slashCommandList.removeAllViews();
        int count = Math.min(commands.length(), 12);
        if (count == 0) {
            slashCommandList.addView(caption("No commands available."), matchWrap());
            return;
        }
        for (int index = 0; index < count; index++) {
            JSONObject command = commands.optJSONObject(index);
            if (command == null) continue;
            String name = command.optString("name", "");
            String detail = command.optString("description", "Codex command");
            View row = slashCommandRow(name, detail);
            LinearLayout.LayoutParams params = matchWrap();
            if (index > 0) params.topMargin = dp(8);
            slashCommandList.addView(row, params);
        }
    }

    private View slashCommandRow(String name, String detail) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackground(rounded(Color.rgb(4, 5, 8), Color.argb(34, 250, 250, 250), 1, 18));
        row.setOnClickListener(view -> {
            appendPromptToken(name);
        });

        TextView commandName = new TextView(this);
        commandName.setText(name);
        commandName.setTextColor(TEXT);
        commandName.setTextSize(14);
        commandName.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        row.addView(commandName, new LinearLayout.LayoutParams(dp(92), LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView commandDetail = caption(detail);
        row.addView(commandDetail, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    private JSONArray defaultSlashCommands() {
        JSONArray commands = new JSONArray();
        addSlashCommand(commands, "/help", "Show Codex slash commands.");
        addSlashCommand(commands, "/model", "Switch the active model.");
        addSlashCommand(commands, "/approvals", "Change approval behavior.");
        addSlashCommand(commands, "/status", "Show session status.");
        addSlashCommand(commands, "/mcp", "Inspect MCP servers.");
        addSlashCommand(commands, "/diff", "Review code changes.");
        addSlashCommand(commands, "/compact", "Compact the conversation.");
        addSlashCommand(commands, "/new", "Start a fresh thread.");
        addSlashCommand(commands, "/init", "Create project instructions.");
        addSlashCommand(commands, "/review", "Run a code review.");
        addSlashCommand(commands, "/quit", "Exit Codex.");
        addSlashCommand(commands, "/exit", "Exit Codex.");
        return commands;
    }

    private void addSlashCommand(JSONArray commands, String name, String description) {
        JSONObject command = new JSONObject();
        try {
            command.put("name", name);
            command.put("description", description);
            commands.put(command);
        } catch (Exception ignored) {
        }
    }

    private JSONArray defaultMentions() {
        JSONArray mentions = new JSONArray();
        addMention(mentions, "@vercel", "Codex plugin");
        addMention(mentions, "@github", "Codex plugin");
        addMention(mentions, "@browser-use", "Bundled plugin");
        addMention(mentions, "@README.md", "Project file");
        addMention(mentions, "@AGENTS.md", "Project file");
        addMention(mentions, "@package.json", "Project file");
        return mentions;
    }

    private void addMention(JSONArray mentions, String label, String detail) {
        JSONObject mention = new JSONObject();
        try {
            mention.put("label", label);
            mention.put("path", label.startsWith("@") ? label.substring(1) : label);
            mention.put("detail", detail);
            mentions.put(mention);
        } catch (Exception ignored) {
        }
    }

    private void appendPromptToken(String token) {
        String current = promptInput.getText().toString();
        String separator = current.trim().isEmpty() || current.endsWith(" ") ? "" : " ";
        promptInput.setText(current + separator + token + " ");
        promptInput.setSelection(promptInput.getText().length());
        promptInput.requestFocus();
    }

    private void renderProjectError(String message) {
        if (projectList == null) return;
        projectList.removeAllViews();
        projectList.addView(projectRow("Unable to load projects", message, false, null), matchWrap());
    }

    private void renderProjects(JSONArray projects) {
        if (projectList == null) return;
        loadedProjects = projects;
        projectList.removeAllViews();
        if (projects.length() == 0) {
            projectList.addView(projectRow("No projects found", "Add folders under Documents, Desktop, or CODEX_PROJECT_ROOTS.", false, null), matchWrap());
            return;
        }

        int count = Math.min(projects.length(), 8);
        if (selectedProjectPath.trim().isEmpty()) {
            JSONObject firstProject = projects.optJSONObject(0);
            if (firstProject != null) {
                applyProjectSelection(
                    firstProject.optString("name", "Project"),
                    firstProject.optString("path", "")
                );
            }
        }

        for (int index = 0; index < count; index++) {
            JSONObject project = projects.optJSONObject(index);
            if (project == null) continue;
            String name = project.optString("name", "Project");
            String path = project.optString("path", "");
            String meta = project.optString("parent", "");
            JSONArray tags = project.optJSONArray("tags");
            if (tags != null && tags.length() > 0) {
                meta = joinTags(tags);
            }
            boolean selected = path.equals(selectedProjectPath);
            View row = projectRow(name, meta, selected, () -> selectProject(name, path));
            LinearLayout.LayoutParams params = matchWrap();
            if (index > 0) params.topMargin = dp(8);
            projectList.addView(row, params);
        }
    }

    private void renderDemoProjects() {
        if (projectList == null) return;
        projectList.removeAllViews();
        projectList.addView(projectRow("New project 5", "Git  /  Node  /  Agents", true, () -> selectProject("New project 5", "/Users/himanshu/Documents/New project 5")), matchWrap());
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(8);
        projectList.addView(projectRow("Website experiments", "Node  /  Docs", false, () -> selectProject("Website experiments", "/Users/himanshu/Documents/Website experiments")), params);
    }

    private View projectRow(String name, String detail, boolean selected, Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackground(rounded(
            selected ? Color.rgb(7, 42, 32) : Color.rgb(4, 5, 8),
            selected ? Color.argb(100, 52, 211, 153) : Color.argb(34, 250, 250, 250),
            1,
            18
        ));
        if (onClick != null) row.setOnClickListener(view -> onClick.run());

        TextView folderIcon = new TextView(this);
        folderIcon.setText(selected ? "●" : "○");
        folderIcon.setTextColor(selected ? ACCENT : SOFT);
        folderIcon.setTextSize(18);
        row.addView(folderIcon, new LinearLayout.LayoutParams(dp(28), LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView title = new TextView(this);
        title.setText(name);
        title.setTextColor(TEXT);
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        texts.addView(title);

        TextView subtitle = caption(detail);
        LinearLayout.LayoutParams subtitleParams = matchWrap();
        subtitleParams.topMargin = dp(2);
        texts.addView(subtitle, subtitleParams);
        return row;
    }

    private void selectProject(String name, String path) {
        applyProjectSelection(name, path);
        setProjectSetupStatus("Selected project. Commands now run there.", false);
        if (loadedProjects.length() > 0) renderProjects(loadedProjects);
    }

    private void applyProjectSelection(String name, String path) {
        selectedProjectName = name;
        selectedProjectPath = path;
        if (projectTitle != null) projectTitle.setText(name);
        if (projectPathLabel != null) projectPathLabel.setText(path);
        if (metaLabel != null) metaLabel.setText(name);
        updateChatContext();
        loadMentions();
    }

    private void updateChatContext() {
        if (chatContextLabel != null) {
            chatContextLabel.setText("Chat " + chatNumber + " · " + selectedProjectName);
        }
    }

    private String joinTags(JSONArray tags) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < tags.length(); i++) {
            String tag = tags.optString(i, "");
            if (tag.trim().isEmpty()) continue;
            if (builder.length() > 0) builder.append("  /  ");
            builder.append(tag);
        }
        return builder.toString();
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

    private TextView formLabel(String value) {
        TextView text = caption(value);
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(14);
        text.setLayoutParams(params);
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
