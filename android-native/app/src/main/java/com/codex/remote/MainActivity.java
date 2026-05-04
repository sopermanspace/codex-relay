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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
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
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final String PREFS = "codex_remote";
    private static final String NOTIFICATION_CHANNEL = "codex_task_status";
    private static final int DISCOVERY_PORT = 8788;
    private static final String DISCOVERY_REQUEST = "CODEX_RELAY_DISCOVER_V1";
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
    private EditText pairingCodeInput;
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
    private TextView serverLabel;
    private TextView pairingCodeLabel;
    private TextView securityStatus;
    private TextView chatTitle;
    private TextView composerStatus;
    private EditText projectNameInput;
    private LinearLayout projectList;
    private LinearLayout slashCommandList;
    private LinearLayout mentionList;
    private LinearLayout chatList;
    private LinearLayout projectPanel;
    private LinearLayout securityPanel;
    private LinearLayout suggestionPanel;
    private LinearLayout suggestionList;
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
    private boolean pairingCodeVisible = false;

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
            autoConnectSavedDevice();
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

        TextView subtitle = body("Pair once with your Mac, then reconnect automatically.");
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = matchWrap();
        subtitleParams.topMargin = dp(14);
        subtitleParams.bottomMargin = dp(32);
        connectScreen.addView(subtitle, subtitleParams);

        accessModeHint = caption("Stay near your Mac for first setup. Continue will find Codex on this Wi-Fi and ask your Mac to show a one-time code.");
        accessModeHint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams modeHintParams = matchWrap();
        modeHintParams.bottomMargin = dp(18);
        connectScreen.addView(accessModeHint, modeHintParams);

        serverLabel = formLabel("Server URL");
        connectScreen.addView(serverLabel);
        String savedServer = prefs.getString("server", getString(R.string.default_server_url));
        if (savedServer.contains("192.168.18.182")) savedServer = getString(R.string.default_server_url);
        serverInput = input(savedServer, false, getString(R.string.default_server_url));
        connectScreen.addView(serverInput, fieldParams());
        serverLabel.setVisibility(View.GONE);
        serverInput.setVisibility(View.GONE);

        pairingCodeLabel = formLabel("Pairing code");
        connectScreen.addView(pairingCodeLabel);
        pairingCodeInput = input("", false, "0000 0000");
        pairingCodeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        connectScreen.addView(pairingCodeInput, fieldParams());
        setPairingCodeVisible(false);

        unlockButton = primaryButton("Continue");
        unlockButton.setOnClickListener(view -> continuePairingFlow());
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

        Button menuButton = circleButton("☰");
        menuButton.setOnClickListener(view -> togglePanel(projectPanel));
        LinearLayout.LayoutParams menuParams = new LinearLayout.LayoutParams(dp(56), dp(56));
        header.addView(menuButton, menuParams);

        statusPill = chatStatusPill("Ready");
        LinearLayout.LayoutParams statusHeaderParams = new LinearLayout.LayoutParams(dp(126), dp(48));
        statusHeaderParams.leftMargin = dp(12);
        header.addView(statusPill, statusHeaderParams);

        TextView headerSpacer = new TextView(this);
        header.addView(headerSpacer, new LinearLayout.LayoutParams(0, 1, 1));

        LinearLayout headerActions = new LinearLayout(this);
        headerActions.setOrientation(LinearLayout.HORIZONTAL);
        headerActions.setGravity(Gravity.CENTER_VERTICAL);
        headerActions.setPadding(dp(8), dp(4), dp(8), dp(4));
        headerActions.setBackground(rounded(PANEL_2, Color.argb(54, 250, 250, 250), 1, 28));
        header.addView(headerActions, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(58)));

        Button newChat = iconTextButton("+");
        newChat.setOnClickListener(view -> startNewChat());
        headerActions.addView(newChat, new LinearLayout.LayoutParams(dp(48), dp(48)));

        Button settingsButton = iconTextButton("◌");
        settingsButton.setOnClickListener(view -> togglePanel(securityPanel));
        headerActions.addView(settingsButton, new LinearLayout.LayoutParams(dp(48), dp(48)));

        Button moreButton = iconTextButton("⋯");
        moreButton.setOnClickListener(view -> togglePanel(projectPanel));
        headerActions.addView(moreButton, new LinearLayout.LayoutParams(dp(48), dp(48)));

        chatTitle = sectionTitle("Codex Relay");
        chatTitle.setVisibility(View.GONE);
        metaLabel = caption("Choose a project");
        metaLabel.setVisibility(View.GONE);
        chatContextLabel = caption("Chat 1 · No project selected");
        chatContextLabel.setVisibility(View.GONE);

        projectPanel = panel();
        projectPanel.setVisibility(View.GONE);
        LinearLayout.LayoutParams projectsParams = matchWrap();
        projectsParams.topMargin = dp(16);
        workspaceScreen.addView(projectPanel, projectsParams);

        LinearLayout projectsHeader = new LinearLayout(this);
        projectsHeader.setGravity(Gravity.CENTER_VERTICAL);
        projectPanel.addView(projectsHeader, matchWrap());
        TextView projectsLabel = sectionTitle("Projects");
        projectsHeader.addView(projectsLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button refreshProjects = quietButton("Sync");
        refreshProjects.setOnClickListener(view -> loadProjects());
        projectsHeader.addView(refreshProjects, new LinearLayout.LayoutParams(dp(72), dp(42)));

        projectTitle = body("Default workspace");
        projectTitle.setTextColor(TEXT);
        projectTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams projectTitleParams = matchWrap();
        projectTitleParams.topMargin = dp(14);
        projectPanel.addView(projectTitle, projectTitleParams);

        projectPathLabel = caption("Pick where Codex should work.");
        LinearLayout.LayoutParams projectPathParams = matchWrap();
        projectPathParams.topMargin = dp(4);
        projectPanel.addView(projectPathLabel, projectPathParams);

        LinearLayout setupRow = new LinearLayout(this);
        setupRow.setOrientation(LinearLayout.HORIZONTAL);
        setupRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams setupParams = matchWrap();
        setupParams.topMargin = dp(14);
        projectPanel.addView(setupRow, setupParams);

        projectNameInput = input("", false, "New project name");
        setupRow.addView(projectNameInput, new LinearLayout.LayoutParams(0, dp(52), 1));

        Button createProject = quietButton("Create");
        createProject.setOnClickListener(view -> createProjectFromInput());
        LinearLayout.LayoutParams createParams = new LinearLayout.LayoutParams(dp(92), dp(52));
        createParams.leftMargin = dp(10);
        setupRow.addView(createProject, createParams);

        projectSetupStatus = caption("Create or choose a project for this chat.");
        LinearLayout.LayoutParams setupStatusParams = matchWrap();
        setupStatusParams.topMargin = dp(8);
        projectPanel.addView(projectSetupStatus, setupStatusParams);

        projectList = new LinearLayout(this);
        projectList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams projectListParams = matchWrap();
        projectListParams.topMargin = dp(12);
        projectPanel.addView(projectList, projectListParams);
        renderProjectLoading();

        securityPanel = miniPanel();
        securityPanel.setVisibility(View.GONE);
        LinearLayout.LayoutParams securityParams = matchWrap();
        securityParams.topMargin = dp(12);
        workspaceScreen.addView(securityPanel, securityParams);
        securityPanel.addView(sectionTitle("Security"), matchWrap());

        LinearLayout securityButtons = new LinearLayout(this);
        securityButtons.setOrientation(LinearLayout.HORIZONTAL);
        securityButtons.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams securityButtonsParams = matchWrap();
        securityButtonsParams.topMargin = dp(12);
        securityPanel.addView(securityButtons, securityButtonsParams);

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
        securityPanel.addView(securityStatus, securityStatusParams);
        updateAccessModeUi();

        LinearLayout chatSurface = new LinearLayout(this);
        chatSurface.setOrientation(LinearLayout.VERTICAL);
        chatSurface.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams chatSurfaceParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1
        );
        chatSurfaceParams.topMargin = dp(18);
        chatSurface.setMinimumHeight(dp(430));
        workspaceScreen.addView(chatSurface, chatSurfaceParams);

        chatList = new LinearLayout(this);
        chatList.setOrientation(LinearLayout.VERTICAL);
        chatList.setGravity(Gravity.CENTER);
        chatSurface.addView(chatList, matchWrap());
        resetChatEmpty();

        suggestionPanel = miniPanel();
        suggestionPanel.setVisibility(View.GONE);
        LinearLayout.LayoutParams suggestionParams = matchWrap();
        suggestionParams.topMargin = dp(12);
        workspaceScreen.addView(suggestionPanel, suggestionParams);
        suggestionList = new LinearLayout(this);
        suggestionList.setOrientation(LinearLayout.VERTICAL);
        suggestionPanel.addView(suggestionList, matchWrap());

        LinearLayout composer = new LinearLayout(this);
        composer.setOrientation(LinearLayout.HORIZONTAL);
        composer.setGravity(Gravity.CENTER_VERTICAL);
        composer.setPadding(dp(8), dp(8), dp(8), dp(8));
        composer.setBackground(rounded(Color.rgb(31, 31, 31), Color.argb(58, 250, 250, 250), 1, 32));
        LinearLayout.LayoutParams composerParams = matchWrap();
        composerParams.topMargin = dp(14);
        workspaceScreen.addView(composer, composerParams);

        Button addButton = iconTextButton("+");
        addButton.setOnClickListener(view -> Toast.makeText(this, "Attachments are coming next.", Toast.LENGTH_SHORT).show());
        composer.addView(addButton, new LinearLayout.LayoutParams(dp(48), dp(48)));

        promptInput = chatInput("", "Ask Codex");
        promptInput.setSingleLine(false);
        promptInput.setMinLines(1);
        promptInput.setMaxLines(4);
        promptInput.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        promptInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateSuggestions(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });
        composer.addView(promptInput, new LinearLayout.LayoutParams(0, dp(52), 1));

        composerStatus = caption("Type / for commands or @ for files.");
        composerStatus.setVisibility(View.GONE);

        Button micButton = iconTextButton("mic");
        micButton.setOnClickListener(view -> Toast.makeText(this, "Voice input is not enabled yet.", Toast.LENGTH_SHORT).show());
        composer.addView(micButton, new LinearLayout.LayoutParams(dp(48), dp(48)));

        runButton = sendCircleButton();
        runButton.setOnClickListener(view -> runCommand());
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(dp(52), dp(52));
        sendParams.leftMargin = dp(4);
        composer.addView(runButton, sendParams);

        copyButton = quietButton("Copy last");
        copyButton.setVisibility(View.GONE);
        copyButton.setEnabled(false);
        copyButton.setOnClickListener(view -> copyLastOutput());
        workspaceScreen.addView(copyButton, new LinearLayout.LayoutParams(1, 1));

        resultTitle = sectionTitle("Result");
        resultTitle.setVisibility(View.GONE);
        workspaceScreen.addView(resultTitle, new LinearLayout.LayoutParams(1, 1));
        resultBody = mono("");
        resultBody.setVisibility(View.GONE);
        workspaceScreen.addView(resultBody, new LinearLayout.LayoutParams(1, 1));
    }

    private void continuePairingFlow() {
        String pairingCode = pairingCodeInput.getText().toString().replaceAll("\\D", "");
        if (!pairingCodeVisible && pairingCode.isEmpty()) {
            discoverAndStartPairing();
            return;
        }
        connect();
    }

    private void setPairingCodeVisible(boolean visible) {
        pairingCodeVisible = visible;
        if (pairingCodeLabel != null) pairingCodeLabel.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (pairingCodeInput != null) pairingCodeInput.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (unlockButton != null) unlockButton.setText(visible ? "Pair / Connect" : "Continue");
    }

    private void discoverAndStartPairing() {
        progressBar.setVisibility(View.VISIBLE);
        unlockButton.setEnabled(false);
        unlockButton.setText("Looking...");
        setConnectStatus("Looking for Codex Relay on this Wi-Fi...", false);

        new Thread(() -> {
            try {
                String discoveredUrl = discoverServerUrl();
                serverUrl = trimSlash(discoveredUrl);
                requestPairingCode();
                runOnUiThread(() -> {
                    serverInput.setText(serverUrl);
                    setPairingCodeVisible(true);
                    pairingCodeInput.setText("");
                    pairingCodeInput.requestFocus();
                    unlockButton.setText("Pair / Connect");
                    setConnectStatus("Confirm this phone on your Mac, then enter the 8-digit code shown there.", false);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    serverUrl = "";
                    setPairingCodeVisible(false);
                    unlockButton.setText("Try again");
                    setConnectStatus(error.getMessage(), true);
                });
            } finally {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    unlockButton.setEnabled(true);
                });
            }
        }).start();
    }

    private String discoverServerUrl() throws Exception {
        byte[] request = DISCOVERY_REQUEST.getBytes(StandardCharsets.UTF_8);
        byte[] buffer = new byte[1024];

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(4500);

            DatagramPacket outbound = new DatagramPacket(
                request,
                request.length,
                InetAddress.getByName("255.255.255.255"),
                DISCOVERY_PORT
            );
            socket.send(outbound);

            DatagramPacket inbound = new DatagramPacket(buffer, buffer.length);
            socket.receive(inbound);
            String responseText = new String(inbound.getData(), inbound.getOffset(), inbound.getLength(), StandardCharsets.UTF_8);
            JSONObject response = new JSONObject(responseText);
            if (!"CODEX_RELAY_DISCOVERY_V1".equals(response.optString("type"))) {
                throw new Exception("Found an unknown service on this network.");
            }
            String discoveredUrl = response.optString("url", "");
            if (!isValidHttpUrl(discoveredUrl)) {
                throw new Exception("Codex Relay did not return a reachable address.");
            }
            return discoveredUrl;
        } catch (SocketTimeoutException timeout) {
            throw new Exception("Could not find Codex Relay. Keep your phone and Mac on the same Wi-Fi, then try again.");
        }
    }

    private void requestPairingCode() throws Exception {
        URL url = new URL(serverUrl + "/api/pairing/start");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("X-Codex-Access-Mode", "local");
        JSONObject body = new JSONObject();
        body.put("deviceName", Build.MANUFACTURER + " " + Build.MODEL);
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(payload);
        }

        int status = connection.getResponseCode();
        String response = readAll(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
        if (status == 403) throw new Exception("Pairing only works when this phone and Mac are on the same Wi-Fi.");
        if (status < 200 || status >= 300) {
            throw new Exception(response.trim().isEmpty() ? "Pairing setup failed." : response);
        }
        JSONObject object = new JSONObject(response);
        if (!object.optBoolean("ok", false)) throw new Exception("Codex Relay did not start pairing.");
    }

    private void connect() {
        String nextServer = serverUrl.trim().isEmpty() ? serverInput.getText().toString().trim() : serverUrl.trim();
        String pairingCode = pairingCodeInput.getText().toString().replaceAll("\\D", "");
        if (!isValidHttpUrl(nextServer)) {
            setConnectStatus("Tap Continue near your Mac to find Codex Relay.", true);
            return;
        }
        String savedToken = prefs.getString("device_token", "");
        if (pairingCode.isEmpty() && savedToken.isEmpty()) {
            setConnectStatus("Enter the 8-digit pairing code shown on your Mac.", true);
            return;
        }
        if (!pairingCode.isEmpty() && pairingCode.length() != 8) {
            setConnectStatus("Pairing code must be 8 digits.", true);
            return;
        }

        serverUrl = trimSlash(nextServer);
        token = savedToken;
        progressBar.setVisibility(View.VISIBLE);
        unlockButton.setEnabled(false);
        setConnectStatus(pairingCode.isEmpty() ? "Checking paired device..." : "Pairing device...", false);

        new Thread(() -> {
            try {
                if (!pairingCode.isEmpty()) {
                    token = pairDevice(pairingCode);
                }
                verifyAuth();
                prefs.edit()
                    .putString("server", serverUrl)
                    .putString("device_token", token)
                    .remove("token")
                    .putString("access_mode", accessMode)
                    .apply();
                runOnUiThread(this::showWorkspace);
            } catch (Exception error) {
                runOnUiThread(() -> setConnectStatus(error.getMessage(), true));
            } finally {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    unlockButton.setEnabled(true);
                    unlockButton.setText(pairingCodeVisible ? "Pair / Connect" : "Continue");
                });
            }
        }).start();
    }

    private void autoConnectSavedDevice() {
        String savedServer = prefs.getString("server", "").trim();
        String savedToken = prefs.getString("device_token", "").trim();
        if (!isValidHttpUrl(savedServer) || savedToken.isEmpty()) return;

        serverUrl = trimSlash(savedServer);
        token = savedToken;
        serverInput.setText(serverUrl);
        progressBar.setVisibility(View.VISIBLE);
        unlockButton.setEnabled(false);
        setConnectStatus("Reconnecting to your paired Mac...", false);

        new Thread(() -> {
            try {
                verifyAuth();
                runOnUiThread(this::showWorkspace);
            } catch (Exception error) {
                token = "";
                prefs.edit().remove("device_token").apply();
                runOnUiThread(() -> {
                    serverUrl = "";
                    setPairingCodeVisible(false);
                    unlockButton.setText("Continue");
                    setConnectStatus("Pairing expired. Continue near your Mac to pair again.", true);
                });
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
            composerStatus.setText("Write a message first.");
            composerStatus.setTextColor(ERROR);
            return;
        }

        setBusy(true);
        addMessageBubble(prompt, true, false);
        promptInput.setText("");
        addMessageBubble("Working on it...", false, false);

        new Thread(() -> {
            long started = System.currentTimeMillis();
            try {
                JSONObject response = postCommand(prompt);
                long seconds = Math.max(1, (System.currentTimeMillis() - started) / 1000);
                boolean ok = response.optBoolean("ok", false);
                String output = response.optString("output", "");
                JSONArray artifacts = response.optJSONArray("artifacts");
                int artifactCount = artifacts == null ? 0 : artifacts.length();
                if (output.trim().isEmpty()) {
                    output = ok && artifactCount > 0
                        ? "Image ready."
                        : "Finished, but no visible result came back.";
                }
                final String resultTitleText = ok ? "Completed in " + seconds + "s" : "Finished with exit code " + response.optInt("exitCode", -1);
                final String resultOutputText = output;
                final JSONArray resultArtifacts = artifacts == null ? new JSONArray() : artifacts;
                runOnUiThread(() -> {
                    removeLastAssistantPlaceholder();
                    addAssistantResult(resultOutputText, resultArtifacts, !ok);
                    setResult(resultTitleText, buildResultSummary(resultOutputText, resultArtifacts), !ok);
                    notifyTaskDone(resultTitleText, ok ? "Codex finished on your Mac." : "Codex needs attention.");
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    removeLastAssistantPlaceholder();
                    addMessageBubble(error.getMessage(), false, true);
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
        if (status == 401) throw new Exception("Device is not paired. Enter the newest code from your Mac.");
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
        if (status == 401) throw new Exception("Device is not paired. Enter the newest code from your Mac.");
        if (status < 200 || status >= 300) throw new Exception("Server returned " + status + ".");
        JSONObject response = new JSONObject(readAll(connection.getInputStream()));
        if (!response.optBoolean("ok", false)) throw new Exception("Server did not confirm access.");
    }

    private String pairDevice(String pairingCode) throws Exception {
        URL url = new URL(serverUrl + "/api/pair");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("X-Codex-Access-Mode", accessMode);

        JSONObject body = new JSONObject();
        body.put("code", pairingCode);
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(payload);
        }

        int status = connection.getResponseCode();
        String responseText = readAll(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
        if (status == 401) throw new Exception("Pairing code rejected. Check the newest code on your Mac.");
        if (status == 409) throw new Exception("Approve this phone on your Mac, then tap Pair / Connect again.");
        if (status == 403) throw new Exception("First-time pairing must finish on the same Wi-Fi as your Mac.");
        if (status < 200 || status >= 300) {
            String message = responseText.trim().isEmpty() ? "Server returned " + status + "." : responseText;
            throw new Exception(message);
        }

        JSONObject response = new JSONObject(responseText);
        String nextToken = response.optString("token", "");
        if (nextToken.trim().isEmpty()) throw new Exception("Server did not return a device key.");
        return nextToken;
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
        if (status == 401) throw new Exception("Device pairing expired. Pair again from the Mac.");
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
        if (status == 401) throw new Exception("Device pairing expired. Pair again from the Mac.");
        if (status < 200 || status >= 300) throw new Exception(response.trim().isEmpty() ? "Server returned " + status + "." : response);
        JSONObject object = new JSONObject(response);
        return object.optJSONObject("project") == null ? new JSONObject() : object.optJSONObject("project");
    }

    private void showConnect() {
        connectScreen.setVisibility(View.VISIBLE);
        workspaceScreen.setVisibility(View.GONE);
        progressBar.setVisibility(View.GONE);
        serverUrl = "";
        pairingCodeInput.setText("");
        setPairingCodeVisible(false);
        setConnectStatus("Ready", false);
    }

    private void showWorkspace() {
        connectScreen.setVisibility(View.GONE);
        workspaceScreen.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.GONE);
        metaLabel.setText(selectedProjectName);
        if (!getIntent().getBooleanExtra("demo_dashboard", false)) loadProjects();
        loadSlashCommands();
        loadMentions();
        resetChatEmpty();
    }

    private void showDemoDashboard() {
        serverUrl = "http://192.168.18.182:8787";
        showWorkspace();
        selectProject("New project 5", "/Users/himanshu/Documents/New project 5");
        renderDemoProjects();
        renderMentions(defaultMentions());
        promptInput.setText("Summarize this repo and list the next three improvements.");
        renderSlashCommands(defaultSlashCommands());
        resetChatEmpty();
        addMessageBubble("Summarize this repo and list the next three improvements.", true, false);
        addMessageBubble("1. App-server command API is live.\n2. Android chat surface is ready.\n3. Next: test the APK on your phone.", false, false);
    }

    private void setBusy(boolean busy) {
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        runButton.setEnabled(!busy);
        runButton.setText(busy ? "..." : "↑");
        statusPill.setText(busy ? "Thinking" : "Ready");
        if (composerStatus != null) {
            composerStatus.setText(busy ? "Codex is responding..." : "Type / for commands or @ for files.");
            composerStatus.setTextColor(SOFT);
        }
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
            accessModeHint.setText("Stay near your Mac for first setup. Continue will find Codex on this Wi-Fi and ask your Mac to show a one-time code.");
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
        resetChatEmpty();
        setBusy(false);
        updateChatContext();
        setProjectSetupStatus("New chat started in " + selectedProjectName + ".", false);
    }

    private void togglePanel(View panel) {
        if (panel == null) return;
        panel.setVisibility(panel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

    private void resetChatEmpty() {
        if (chatList == null) return;
        chatList.removeAllViews();
        chatList.setGravity(Gravity.CENTER);
        LinearLayout empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER);
        empty.setTag("empty-state");

        TextView title = new TextView(this);
        title.setText("Today's work");
        title.setTextColor(TEXT);
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        empty.addView(title, matchWrap());

        TextView subtitle = body("Ask for edits, checks, explanations, or project help.");
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = matchWrap();
        subtitleParams.topMargin = dp(10);
        empty.addView(subtitle, subtitleParams);

        chatList.addView(empty, matchWrap());
        lastOutput = "";
    }

    private void addMessageBubble(String message, boolean user, boolean error) {
        if (chatList == null) return;
        if (chatList.getChildCount() == 1 && "empty-state".equals(chatList.getChildAt(0).getTag())) {
            chatList.removeAllViews();
        }
        chatList.setGravity(Gravity.NO_GRAVITY);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(user ? Gravity.END : Gravity.START);

        TextView bubble = user ? chatBubble(message, true, false) : chatBubble(message, false, error);
        LinearLayout.LayoutParams bubbleParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        bubbleParams.topMargin = dp(chatList.getChildCount() == 0 ? 0 : 12);
        row.addView(bubble, bubbleParams);
        chatList.addView(row, matchWrap());
    }

    private void addAssistantResult(String message, JSONArray artifacts, boolean error) {
        String trimmed = message == null ? "" : message.trim();
        if (!trimmed.isEmpty()) {
            addMessageBubble(trimmed, false, error);
        }

        if (artifacts == null) return;
        for (int index = 0; index < artifacts.length(); index++) {
            JSONObject artifact = artifacts.optJSONObject(index);
            if (artifact == null || !"image".equals(artifact.optString("type"))) continue;
            addImageBubble(artifact);
        }
    }

    private void addImageBubble(JSONObject artifact) {
        if (chatList == null) return;
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.START);

        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(8), dp(8), dp(8), dp(10));
        bubble.setBackground(rounded(Color.rgb(14, 16, 21), Color.argb(42, 250, 250, 250), 1, 22));

        ImageView preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setContentDescription("Generated image preview");
        preview.setBackground(rounded(Color.rgb(5, 7, 10), Color.argb(38, 250, 250, 250), 1, 16));
        int maxWidth = getResources().getDisplayMetrics().widthPixels - dp(108);
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(maxWidth, dp(260));
        bubble.addView(preview, imageParams);

        String name = artifact.optString("name", "Generated image");
        TextView caption = caption(name);
        caption.setTextColor(MUTED);
        LinearLayout.LayoutParams captionParams = matchWrap();
        captionParams.topMargin = dp(10);
        bubble.addView(caption, captionParams);

        LinearLayout.LayoutParams bubbleParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        bubbleParams.topMargin = dp(chatList.getChildCount() == 0 ? 0 : 12);
        row.addView(bubble, bubbleParams);
        chatList.addView(row, matchWrap());

        loadArtifactImage(artifact.optString("url", ""), preview);
    }

    private void loadArtifactImage(String relativeUrl, ImageView target) {
        if (relativeUrl.trim().isEmpty()) return;
        new Thread(() -> {
            try {
                URL url = new URL(serverUrl + relativeUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(30000);
                setAccessHeaders(connection);
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) throw new Exception("Image unavailable.");
                Bitmap bitmap = BitmapFactory.decodeStream(connection.getInputStream());
                if (bitmap == null) throw new Exception("Image unavailable.");
                runOnUiThread(() -> target.setImageBitmap(bitmap));
            } catch (Exception error) {
                runOnUiThread(() -> target.setContentDescription("Generated image could not be loaded"));
            }
        }).start();
    }

    private String buildResultSummary(String output, JSONArray artifacts) {
        StringBuilder summary = new StringBuilder(output == null ? "" : output.trim());
        if (artifacts != null && artifacts.length() > 0) {
            if (summary.length() > 0) summary.append("\n\n");
            summary.append("Artifacts:");
            for (int index = 0; index < artifacts.length(); index++) {
                JSONObject artifact = artifacts.optJSONObject(index);
                if (artifact == null) continue;
                summary.append("\n- ").append(artifact.optString("name", "Generated image"));
            }
        }
        return summary.toString();
    }

    private void removeLastAssistantPlaceholder() {
        if (chatList == null || chatList.getChildCount() == 0) return;
        int lastIndex = chatList.getChildCount() - 1;
        View rowView = chatList.getChildAt(lastIndex);
        if (!(rowView instanceof LinearLayout)) return;
        LinearLayout row = (LinearLayout) rowView;
        if (row.getGravity() == Gravity.START) {
            chatList.removeViewAt(lastIndex);
        }
    }

    private TextView chatBubble(String message, boolean user, boolean error) {
        TextView bubble = new TextView(this);
        bubble.setText(message);
        bubble.setTextColor(error ? ERROR : (user ? Color.rgb(3, 4, 7) : TEXT));
        bubble.setTextSize(15);
        bubble.setLineSpacing(dp(3), 1f);
        bubble.setPadding(dp(16), dp(13), dp(16), dp(13));
        bubble.setMaxWidth(getResources().getDisplayMetrics().widthPixels - dp(92));
        bubble.setBackground(rounded(
            user ? ACCENT : Color.rgb(14, 16, 21),
            user ? Color.argb(150, 167, 243, 208) : Color.argb(42, 250, 250, 250),
            1,
            22
        ));
        if (!user) bubble.setTypeface(Typeface.DEFAULT);
        return bubble;
    }

    private void updateSuggestions(String value) {
        if (suggestionPanel == null || suggestionList == null) return;
        String trimmed = value.trim();
        suggestionList.removeAllViews();

        if (trimmed.startsWith("/")) {
            renderSuggestionHeader("Commands");
            int count = 0;
            for (int index = 0; index < loadedSlashCommands.length() && count < 8; index++) {
                JSONObject command = loadedSlashCommands.optJSONObject(index);
                if (command == null) continue;
                String name = command.optString("name", "");
                if (!name.startsWith(trimmed)) continue;
                String detail = command.optString("description", "Codex command");
                addSuggestionRow(name, detail);
                count++;
            }
            suggestionPanel.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
            return;
        }

        int atIndex = trimmed.lastIndexOf("@");
        if (atIndex >= 0) {
            String query = trimmed.substring(atIndex).toLowerCase();
            renderSuggestionHeader("Files and plugins");
            int count = 0;
            for (int index = 0; index < loadedMentions.length() && count < 8; index++) {
                JSONObject mention = loadedMentions.optJSONObject(index);
                if (mention == null) continue;
                String label = mention.optString("label", "");
                if (!label.toLowerCase().startsWith(query)) continue;
                String detail = mention.optString("detail", "Mention");
                addSuggestionRow(label, detail);
                count++;
            }
            suggestionPanel.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
            return;
        }

        suggestionPanel.setVisibility(View.GONE);
    }

    private void renderSuggestionHeader(String title) {
        TextView header = caption(title);
        header.setTextColor(MUTED);
        header.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        suggestionList.addView(header, matchWrap());
    }

    private void addSuggestionRow(String label, String detail) {
        View row = suggestionRow(label, detail);
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(8);
        suggestionList.addView(row, params);
    }

    private View suggestionRow(String label, String detail) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackground(rounded(Color.rgb(5, 7, 10), Color.argb(38, 250, 250, 250), 1, 18));
        row.setOnClickListener(view -> {
            appendPromptToken(label);
            suggestionPanel.setVisibility(View.GONE);
        });

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextColor(TEXT);
        labelView.setTextSize(14);
        labelView.setSingleLine(true);
        labelView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        row.addView(labelView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView detailView = caption(detail);
        detailView.setGravity(Gravity.END);
        row.addView(detailView, new LinearLayout.LayoutParams(dp(112), LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
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
        if (status == 401) throw new Exception("Device pairing expired. Pair again from the Mac.");
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
        loadedMentions = mentions;
        if (mentionList == null) return;
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
        if (status == 401) throw new Exception("Device pairing expired. Pair again from the Mac.");
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
        loadedSlashCommands = commands;
        if (slashCommandList == null) return;
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
        if (projectPanel != null) projectPanel.setVisibility(View.GONE);
    }

    private void applyProjectSelection(String name, String path) {
        selectedProjectName = name;
        selectedProjectPath = path;
        if (projectTitle != null) projectTitle.setText(name);
        if (projectPathLabel != null) projectPathLabel.setText(path.trim().isEmpty() ? "Server workspace" : "Selected workspace");
        if (metaLabel != null) metaLabel.setText(name);
        if (chatTitle != null) chatTitle.setText(name);
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

    private Button circleButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(TEXT);
        button.setTextSize(26);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(rounded(PANEL_2, Color.argb(58, 250, 250, 250), 1, 28));
        return button;
    }

    private Button iconTextButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(TEXT);
        button.setTextSize(text.length() > 1 ? 13 : 28);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(rounded(Color.TRANSPARENT, Color.TRANSPARENT, 0, 24));
        return button;
    }

    private Button sendCircleButton() {
        Button button = new Button(this);
        button.setText("↑");
        button.setAllCaps(false);
        button.setTextColor(Color.rgb(5, 5, 5));
        button.setTextSize(24);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(rounded(Color.rgb(248, 248, 248), Color.argb(230, 255, 255, 255), 1, 26));
        return button;
    }

    private EditText chatInput(String value, String hint) {
        EditText input = new EditText(this);
        input.setText(value);
        input.setHint(hint);
        input.setTextSize(18);
        input.setTextColor(TEXT);
        input.setHintTextColor(Color.rgb(168, 168, 174));
        input.setPadding(dp(12), 0, dp(10), 0);
        input.setBackground(rounded(Color.TRANSPARENT, Color.TRANSPARENT, 0, 0));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        return input;
    }

    private TextView chatStatusPill(String value) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(ACCENT);
        text.setTextSize(20);
        text.setGravity(Gravity.CENTER);
        text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        text.setBackground(rounded(PANEL_2, Color.argb(58, 250, 250, 250), 1, 28));
        return text;
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

    private LinearLayout.LayoutParams compactComposerParams() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(112));
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
