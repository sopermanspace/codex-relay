package com.codex.remote;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.net.ssl.SSLSocketFactory;

public class MainActivity extends Activity {
    private FrameLayout root;
    private LinearLayout connectPanel;
    private LinearLayout terminalPanel;
    private EditText serverInput;
    private EditText tokenInput;
    private EditText commandInput;
    private TextView statusLabel;
    private TextView terminalOutput;
    private ScrollView terminalScroll;
    private ProgressBar progressBar;
    private Button unlockButton;
    private Button sendButton;
    private NativeCodexSocket socket;
    private String serverUrl = "";
    private String token = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        buildLayout();
        showConnect();
    }

    @Override
    protected void onDestroy() {
        if (socket != null) {
            socket.close();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (terminalPanel.getVisibility() == View.VISIBLE) {
            if (socket != null) socket.close();
            showConnect();
            return;
        }
        super.onBackPressed();
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.rgb(9, 9, 11));
        window.getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) controller.hide(WindowInsets.Type.statusBars());
        }
    }

    private void buildLayout() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(9, 9, 11));
        setContentView(root);
        buildConnectPanel();
        buildTerminalPanel();

        progressBar = new ProgressBar(this);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(dp(42), dp(42), Gravity.TOP | Gravity.END);
        progressParams.setMargins(0, dp(24), dp(24), 0);
        root.addView(progressBar, progressParams);
    }

    private void buildConnectPanel() {
        connectPanel = new LinearLayout(this);
        connectPanel.setOrientation(LinearLayout.VERTICAL);
        connectPanel.setGravity(Gravity.CENTER_HORIZONTAL);
        connectPanel.setPadding(dp(24), dp(42), dp(24), dp(24));
        root.addView(connectPanel, fullScreen());

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.codex_remote_mark);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(104), dp(104));
        logoParams.bottomMargin = dp(22);
        connectPanel.addView(logo, logoParams);

        TextView eyebrow = text("NATIVE ANDROID CLIENT", 12, Color.rgb(52, 211, 153), Typeface.BOLD);
        connectPanel.addView(eyebrow);

        TextView title = text("Connect to Codex", 36, Color.WHITE, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = matchWrap();
        titleParams.topMargin = dp(8);
        connectPanel.addView(title, titleParams);

        TextView subtitle = text("Direct WebSocket control. No browser shell.", 16, Color.rgb(212, 212, 216), Typeface.NORMAL);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = matchWrap();
        subtitleParams.topMargin = dp(8);
        subtitleParams.bottomMargin = dp(28);
        connectPanel.addView(subtitle, subtitleParams);

        connectPanel.addView(label("Server URL"));
        serverInput = input(getString(R.string.default_server_url), false);
        connectPanel.addView(serverInput, fieldParams());

        connectPanel.addView(label("Remote token"));
        tokenInput = input("", true);
        tokenInput.setHint("Remote token");
        connectPanel.addView(tokenInput, fieldParams());

        unlockButton = primaryButton("Unlock");
        unlockButton.setOnClickListener(view -> startNativeSession());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        buttonParams.topMargin = dp(18);
        connectPanel.addView(unlockButton, buttonParams);

        statusLabel = text("Locked", 13, Color.rgb(161, 161, 170), Typeface.NORMAL);
        statusLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.topMargin = dp(16);
        connectPanel.addView(statusLabel, statusParams);
    }

    private void buildTerminalPanel() {
        terminalPanel = new LinearLayout(this);
        terminalPanel.setOrientation(LinearLayout.VERTICAL);
        terminalPanel.setPadding(dp(14), dp(22), dp(14), dp(14));
        terminalPanel.setVisibility(View.GONE);
        root.addView(terminalPanel, fullScreen());

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(0, 0, 0, dp(12));
        terminalPanel.addView(header, matchWrap());

        TextView title = text("Codex Remote", 20, Color.WHITE, Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        header.addView(title, titleParams);

        Button close = quietButton("Close");
        close.setOnClickListener(view -> {
            if (socket != null) socket.close();
            showConnect();
        });
        header.addView(close, new LinearLayout.LayoutParams(dp(88), dp(44)));

        terminalScroll = new ScrollView(this);
        terminalScroll.setFillViewport(true);
        terminalScroll.setBackground(rounded(Color.rgb(5, 5, 5), Color.argb(44, 250, 250, 250), 1, 12));
        terminalOutput = text("", 13, Color.rgb(244, 244, 245), Typeface.NORMAL);
        terminalOutput.setTypeface(Typeface.MONOSPACE);
        terminalOutput.setPadding(dp(14), dp(14), dp(14), dp(14));
        terminalScroll.addView(terminalOutput, fullScreen());
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        terminalPanel.addView(terminalScroll, scrollParams);

        LinearLayout quickKeys = new LinearLayout(this);
        quickKeys.setOrientation(LinearLayout.HORIZONTAL);
        quickKeys.setPadding(0, dp(10), 0, dp(8));
        terminalPanel.addView(quickKeys, matchWrap());
        addKey(quickKeys, "Esc", "\u001b");
        addKey(quickKeys, "Tab", "\t");
        addKey(quickKeys, "Up", "\u001b[A");
        addKey(quickKeys, "Down", "\u001b[B");
        addKey(quickKeys, "Ctrl+C", "\u0003");

        commandInput = input("", false);
        commandInput.setHint("Command");
        terminalPanel.addView(commandInput, fieldParams());

        sendButton = primaryButton("Send");
        sendButton.setOnClickListener(view -> sendCommand());
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        sendParams.topMargin = dp(10);
        terminalPanel.addView(sendButton, sendParams);
    }

    private void startNativeSession() {
        serverUrl = serverInput.getText().toString().trim();
        token = tokenInput.getText().toString().trim();
        if (!isValidHttpUrl(serverUrl)) {
            setStatus("Enter a valid HTTP or HTTPS server URL.", true);
            return;
        }
        if (token.isEmpty()) {
            setStatus("Remote token is required.", true);
            return;
        }

        progressBar.setVisibility(View.VISIBLE);
        unlockButton.setEnabled(false);
        setStatus("Connecting", false);

        new Thread(() -> {
            try {
                String sessionId = createSession(serverUrl, token);
                runOnUiThread(() -> showTerminal(sessionId));
                socket = new NativeCodexSocket(serverUrl, token, sessionId, new NativeCodexSocket.Listener() {
                    @Override
                    public void onOpen() {
                        appendTerminal("Connected to Codex.\n");
                    }

                    @Override
                    public void onOutput(String output) {
                        appendTerminal(stripAnsi(output));
                    }

                    @Override
                    public void onClosed(String reason) {
                        appendTerminal("\nDisconnected: " + reason + "\n");
                    }

                    @Override
                    public void onError(Exception error) {
                        appendTerminal("\nConnection error: " + error.getMessage() + "\n");
                    }
                });
                socket.connect();
            } catch (Exception error) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    unlockButton.setEnabled(true);
                    setStatus(error.getMessage(), true);
                });
            }
        }).start();
    }

    private String createSession(String baseUrl, String tokenValue) throws Exception {
        URL url = new URL(trimSlash(baseUrl) + "/api/session");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + tokenValue);
        byte[] body = "{\"cols\":80,\"rows\":28}".getBytes(StandardCharsets.UTF_8);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body);
        }
        int status = connection.getResponseCode();
        if (status == 401) throw new Exception("Token rejected.");
        if (status < 200 || status >= 300) throw new Exception("Server returned " + status + ".");
        String response = readAll(connection.getInputStream());
        return new JSONObject(response).getString("id");
    }

    private void showTerminal(String sessionId) {
        progressBar.setVisibility(View.GONE);
        unlockButton.setEnabled(true);
        terminalOutput.setText("Session " + sessionId.substring(0, Math.min(8, sessionId.length())) + "\n");
        connectPanel.setVisibility(View.GONE);
        terminalPanel.setVisibility(View.VISIBLE);
    }

    private void showConnect() {
        progressBar.setVisibility(View.GONE);
        connectPanel.setVisibility(View.VISIBLE);
        terminalPanel.setVisibility(View.GONE);
        unlockButton.setEnabled(true);
        setStatus("Locked", false);
    }

    private void sendCommand() {
        String command = commandInput.getText().toString();
        if (command.trim().isEmpty() || socket == null) return;
        socket.sendInput(command + "\n");
        commandInput.setText("");
    }

    private void addKey(LinearLayout parent, String label, String value) {
        Button button = quietButton(label);
        button.setOnClickListener(view -> {
            if (socket != null) socket.sendInput(value);
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(42), 1);
        params.setMargins(dp(3), 0, dp(3), 0);
        parent.addView(button, params);
    }

    private void appendTerminal(String value) {
        runOnUiThread(() -> {
            terminalOutput.append(value);
            terminalScroll.post(() -> terminalScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void setStatus(String value, boolean error) {
        statusLabel.setText(value);
        statusLabel.setTextColor(error ? Color.rgb(252, 165, 165) : Color.rgb(161, 161, 170));
    }

    private boolean isValidHttpUrl(String value) {
        Uri uri = Uri.parse(value);
        String scheme = uri.getScheme();
        return uri.getHost() != null && ("http".equals(scheme) || "https".equals(scheme));
    }

    private EditText input(String value, boolean password) {
        EditText input = new EditText(this);
        input.setText(value);
        input.setSingleLine(false);
        input.setTextSize(16);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.rgb(113, 113, 122));
        input.setPadding(dp(16), 0, dp(16), 0);
        input.setBackground(rounded(Color.rgb(5, 5, 5), Color.argb(42, 250, 250, 250), 1, 12));
        if (password) {
            input.setSingleLine(true);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        } else {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        }
        return input;
    }

    private TextView label(String value) {
        TextView label = text(value, 13, Color.rgb(161, 161, 170), Typeface.NORMAL);
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(14);
        label.setLayoutParams(params);
        return label;
    }

    private Button primaryButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextColor(Color.rgb(4, 19, 13));
        button.setTextSize(16);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(rounded(Color.rgb(52, 211, 153), Color.argb(90, 255, 255, 255), 1, 12));
        return button;
    }

    private Button quietButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextColor(Color.rgb(244, 244, 245));
        button.setTextSize(13);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(rounded(Color.rgb(39, 39, 42), Color.argb(34, 250, 250, 250), 1, 10));
        return button;
    }

    private TextView text(String value, int sizeSp, int color, int style) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sizeSp);
        text.setTextColor(color);
        text.setTypeface(Typeface.DEFAULT, style);
        text.setIncludeFontPadding(true);
        return text;
    }

    private LinearLayout.LayoutParams fieldParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        params.topMargin = dp(8);
        return params;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private FrameLayout.LayoutParams fullScreen() {
        return new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private GradientDrawable rounded(int fill, int stroke, int strokeWidthDp, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(strokeWidthDp), stroke);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String readAll(InputStream stream) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) builder.append(line);
        return builder.toString();
    }

    private static String trimSlash(String value) {
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static String stripAnsi(String value) {
        return value.replaceAll("\\u001B\\[[0-9;?]*[ -/]*[@-~]", "");
    }

    private static final class NativeCodexSocket {
        interface Listener {
            void onOpen();
            void onOutput(String output);
            void onClosed(String reason);
            void onError(Exception error);
        }

        private final String baseUrl;
        private final String token;
        private final String sessionId;
        private final Listener listener;
        private Socket socket;
        private InputStream input;
        private OutputStream output;
        private volatile boolean open;

        NativeCodexSocket(String baseUrl, String token, String sessionId, Listener listener) {
            this.baseUrl = baseUrl;
            this.token = token;
            this.sessionId = sessionId;
            this.listener = listener;
        }

        void connect() {
            new Thread(() -> {
                try {
                    Uri uri = Uri.parse(baseUrl);
                    boolean secure = "https".equals(uri.getScheme());
                    int port = uri.getPort() != -1 ? uri.getPort() : (secure ? 443 : 80);
                    String host = uri.getHost();
                    if (secure) {
                        socket = SSLSocketFactory.getDefault().createSocket(host, port);
                    } else {
                        socket = new Socket(host, port);
                    }
                    input = new BufferedInputStream(socket.getInputStream());
                    output = new BufferedOutputStream(socket.getOutputStream());
                    handshake(host, port, uri.getPath());
                    open = true;
                    listener.onOpen();
                    sendJson("{\"type\":\"resize\",\"cols\":80,\"rows\":28}");
                    readLoop();
                } catch (Exception error) {
                    listener.onError(error);
                    close();
                }
            }).start();
        }

        void sendInput(String data) {
            sendJson("{\"type\":\"input\",\"data\":" + JSONObject.quote(data) + "}");
        }

        void close() {
            open = false;
            try {
                if (socket != null) socket.close();
            } catch (Exception ignored) {
            }
        }

        private void handshake(String host, int port, String path) throws Exception {
            String key = makeWebSocketKey();
            String requestPath = (path == null || path.isEmpty() ? "/" : path)
                + "?session=" + Uri.encode(sessionId)
                + "&token=" + Uri.encode(token);
            String request = "GET " + requestPath + " HTTP/1.1\r\n"
                + "Host: " + host + ":" + port + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
            output.write(request.getBytes(StandardCharsets.US_ASCII));
            output.flush();

            String response = readHeaders(input);
            if (!response.startsWith("HTTP/1.1 101")) {
                throw new Exception("WebSocket upgrade failed.");
            }
            String accept = headerValue(response, "Sec-WebSocket-Accept");
            String expected = expectedAccept(key);
            if (!expected.equals(accept)) {
                throw new Exception("WebSocket handshake rejected.");
            }
        }

        private void readLoop() throws Exception {
            while (open) {
                int first = input.read();
                if (first == -1) break;
                int second = input.read();
                if (second == -1) break;
                int opcode = first & 0x0F;
                long length = second & 0x7F;
                if (length == 126) {
                    length = ((input.read() & 0xFF) << 8) | (input.read() & 0xFF);
                } else if (length == 127) {
                    length = 0;
                    for (int i = 0; i < 8; i++) length = (length << 8) | (input.read() & 0xFF);
                }
                byte[] payload = new byte[(int) length];
                int offset = 0;
                while (offset < payload.length) {
                    int read = input.read(payload, offset, payload.length - offset);
                    if (read == -1) throw new Exception("Socket closed.");
                    offset += read;
                }
                if (opcode == 8) break;
                if (opcode == 1) handleMessage(new String(payload, StandardCharsets.UTF_8));
            }
            listener.onClosed("socket closed");
        }

        private void handleMessage(String json) throws Exception {
            JSONObject event = new JSONObject(json);
            String type = event.optString("type");
            if ("output".equals(type)) {
                listener.onOutput(event.optString("data"));
            } else if ("exit".equals(type)) {
                listener.onClosed("Codex exited " + event.optInt("exitCode"));
            }
        }

        private synchronized void sendJson(String json) {
            if (!open || output == null) return;
            try {
                byte[] data = json.getBytes(StandardCharsets.UTF_8);
                ByteArrayOutputStream frame = new ByteArrayOutputStream();
                frame.write(0x81);
                if (data.length < 126) {
                    frame.write(0x80 | data.length);
                } else if (data.length <= 65535) {
                    frame.write(0x80 | 126);
                    frame.write((data.length >> 8) & 0xFF);
                    frame.write(data.length & 0xFF);
                } else {
                    frame.write(0x80 | 127);
                    frame.write(ByteBuffer.allocate(8).putLong(data.length).array());
                }
                byte[] mask = new byte[4];
                new SecureRandom().nextBytes(mask);
                frame.write(mask);
                for (int i = 0; i < data.length; i++) {
                    frame.write(data[i] ^ mask[i % 4]);
                }
                output.write(frame.toByteArray());
                output.flush();
            } catch (Exception error) {
                listener.onError(error);
            }
        }

        private static String makeWebSocketKey() {
            byte[] random = new byte[16];
            new SecureRandom().nextBytes(random);
            return Base64.getEncoder().encodeToString(random);
        }

        private static String expectedAccept(String key) throws Exception {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hashed = digest.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(hashed);
        }

        private static String headerValue(String response, String name) {
            String prefix = name.toLowerCase() + ":";
            String[] lines = response.split("\\r?\\n");
            for (String line : lines) {
                if (line.toLowerCase().startsWith(prefix)) {
                    return line.substring(line.indexOf(':') + 1).trim();
                }
            }
            return "";
        }

        private static String readHeaders(InputStream input) throws Exception {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int previous3 = -1;
            int previous2 = -1;
            int previous1 = -1;
            int current;
            while ((current = input.read()) != -1) {
                buffer.write(current);
                if (previous3 == '\r' && previous2 == '\n' && previous1 == '\r' && current == '\n') {
                    break;
                }
                previous3 = previous2;
                previous2 = previous1;
                previous1 = current;
            }
            return buffer.toString(StandardCharsets.US_ASCII.name());
        }
    }
}
