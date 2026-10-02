package com.gitdroid.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 极简 git 控制台外壳：解包原生 git，配置环境，逐条执行命令。 */
public class MainActivity extends Activity {

    private static final int C_BG = 0xFF0D1117;
    private static final int C_FG = 0xFFD5DCE6;
    private static final int C_DIM = 0xFF8B949E;
    private static final int C_ACC = 0xFFF05032;
    private static final int MAX_OUT = 160000;
    private static final int REQ_PERM = 1001;

    private TextView out;
    private TextView live;
    private TextView cwdView;
    private EditText input;
    private ScrollView scroll;
    private Button runBtn;

    private final StringBuilder committed = new StringBuilder();
    private final StringBuilder partial = new StringBuilder();
    private boolean flushPosted;

    private Process proc;
    private volatile boolean busy;
    private File cwd;
    private final List<String> history = new ArrayList<String>();
    private int histPos;

    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("gitdroid", MODE_PRIVATE);
        cwd = new File(prefs.getString("cwd", defaultWorkspace()));
        if (!cwd.isDirectory()) {
            cwd = new File(defaultWorkspace());
        }
        buildUi();
        requestStorage();
        prepareRuntime();
    }

    // ---------------- 界面 ----------------

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(10), dp(8), dp(10), 0);

        TextView title = new TextView(this);
        title.setText("GitDroid");
        title.setTextColor(C_ACC);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        head.addView(title);

        cwdView = new TextView(this);
        cwdView.setTextColor(C_DIM);
        cwdView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        cwdView.setSingleLine(true);
        cwdView.setPadding(dp(10), 0, 0, 0);
        cwdView.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showCwdDialog();
            }
        });
        head.addView(cwdView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(head);

        HorizontalScrollView barScroll = new HorizontalScrollView(this);
        barScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(6), dp(4), dp(6), dp(4));
        bar.addView(btn("配置", new View.OnClickListener() {
            public void onClick(View v) {
                showConfigDialog();
            }
        }));
        bar.addView(btn("诊断", new View.OnClickListener() {
            public void onClick(View v) {
                diagnostics();
            }
        }));
        bar.addView(btn("复制", new View.OnClickListener() {
            public void onClick(View v) {
                copyOutput();
            }
        }));
        bar.addView(btn("清屏", new View.OnClickListener() {
            public void onClick(View v) {
                clearScreen();
            }
        }));
        bar.addView(btn("停止", new View.OnClickListener() {
            public void onClick(View v) {
                stopProcess();
            }
        }));
        barScroll.addView(bar);
        root.addView(barScroll);

        scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(10), dp(4), dp(10), dp(4));

        out = new TextView(this);
        out.setTextColor(C_FG);
        out.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        out.setTypeface(Typeface.MONOSPACE);
        out.setTextIsSelectable(true);
        col.addView(out, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        live = new TextView(this);
        live.setTextColor(C_ACC);
        live.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        live.setTypeface(Typeface.MONOSPACE);
        col.addView(live, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll.addView(col);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        HorizontalScrollView quickScroll = new HorizontalScrollView(this);
        quickScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout quick = new LinearLayout(this);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        quick.setPadding(dp(6), 0, dp(6), 0);
        quick.addView(btn("状态", new View.OnClickListener() {
            public void onClick(View v) {
                runCommand("git status -sb");
            }
        }));
        quick.addView(btn("日志", new View.OnClickListener() {
            public void onClick(View v) {
                runCommand("git log --oneline --graph -15");
            }
        }));
        quick.addView(btn("拉取", new View.OnClickListener() {
            public void onClick(View v) {
                fill("git pull");
            }
        }));
        quick.addView(btn("推送", new View.OnClickListener() {
            public void onClick(View v) {
                fill("git push");
            }
        }));
        quick.addView(btn("克隆", new View.OnClickListener() {
            public void onClick(View v) {
                fill("git clone ");
            }
        }));
        quick.addView(btn("提交", new View.OnClickListener() {
            public void onClick(View v) {
                fill("git add -A; git commit -m \"update\"");
            }
        }));
        quick.addView(btn("文件", new View.OnClickListener() {
            public void onClick(View v) {
                runCommand("ls -la");
            }
        }));
        quickScroll.addView(quick);
        root.addView(quickScroll);

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding(dp(6), dp(4), dp(6), dp(8));

        input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("输入 git 命令，或任意 shell 命令");
        input.setHintTextColor(C_DIM);
        input.setTextColor(C_FG);
        input.setTypeface(Typeface.MONOSPACE);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE
                        || actionId == EditorInfo.IME_ACTION_GO
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    runCommand(input.getText().toString());
                    return true;
                }
                return false;
            }
        });
        inputRow.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        inputRow.addView(btn("↑", new View.OnClickListener() {
            public void onClick(View v) {
                historyUp();
            }
        }));

        runBtn = btn("运行", new View.OnClickListener() {
            public void onClick(View v) {
                runCommand(input.getText().toString());
            }
        });
        inputRow.addView(runBtn);

        root.addView(inputRow);
        setContentView(root);
        updateCwdView();
        updateRunState();
    }

    private Button btn(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setAllCaps(false);
        b.setMinimumWidth(0);
        b.setMinWidth(0);
        b.setPadding(dp(10), dp(2), dp(10), dp(2));
        b.setOnClickListener(l);
        return b;
    }

    private void updateCwdView() {
        cwdView.setText(cwd.getAbsolutePath());
    }

    private void updateRunState() {
        runBtn.setEnabled(!busy);
        runBtn.setText(busy ? "运行中" : "运行");
    }

    private void fill(String text) {
        input.setText(text);
        input.setSelection(input.getText().length());
    }

    private void historyUp() {
        if (history.isEmpty()) {
            return;
        }
        histPos = Math.max(0, histPos - 1);
        fill(history.get(histPos));
    }

    // ---------------- 运行时准备 ----------------

    private void requestStorage() {
        if (checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {
                    "android.permission.READ_EXTERNAL_STORAGE",
                    "android.permission.WRITE_EXTERNAL_STORAGE" }, REQ_PERM);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_PERM) {
            String ws = defaultWorkspace();
            if (!ws.equals(cwd.getAbsolutePath()) && !canWrite(cwd)) {
                changeDir(ws);
            }
            render("存储权限：" + (results.length > 0 && results[0] == 0 ? "已授权" : "被拒绝") + "\n");
        }
    }

    private boolean canWrite(File dir) {
        File probe = new File(dir, ".gitdroid_probe");
        try {
            if (probe.createNewFile()) {
                probe.delete();
                return true;
            }
        } catch (IOException ignored) {
        }
        return false;
    }

    private String defaultWorkspace() {
        File target = new File(Environment.getExternalStorageDirectory(), "GitRepos");
        if (target.isDirectory() || target.mkdirs()) {
            return target.getAbsolutePath();
        }
        File priv = new File(getFilesDir(), "workspace");
        GitEnv.mkdirs(priv);
        return priv.getAbsolutePath();
    }

    private void prepareRuntime() {
        render("正在准备 git 运行时…\n");
        new Thread(new Runnable() {
            public void run() {
                String note;
                try {
                    note = GitEnv.install(MainActivity.this);
                    GitEnv.writeDefaults(MainActivity.this);
                } catch (Throwable t) {
                    note = "安装失败：" + t;
                }
                final String n = note;
                ui.post(new Runnable() {
                    public void run() {
                        welcome(n);
                    }
                });
            }
        }).start();
    }

    private void welcome(String note) {
        render("GitDroid 1.0   工作目录 " + cwd.getAbsolutePath() + "\n");
        render("git: " + GitEnv.gitFile(this).getAbsolutePath() + "\n");
        if (note != null && note.length() > 0) {
            render("本次解包：" + note + "\n");
        }
        render("先在「配置」里填 user.name / user.email 与 GitHub Token；非 git 开头的命令会交给 /system/bin/sh。点「诊断」可自检。\n");
        List<String> cmds = new ArrayList<String>();
        cmds.add("git --version");
        runSequence(cmds, false);
    }

    // ---------------- 输出渲染 ----------------

    private void render(String s) {
        boolean need = false;
        synchronized (this) {
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (ch == '\n') {
                    committed.append(partial).append('\n');
                    partial.setLength(0);
                    need = true;
                } else if (ch == '\r') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '\n') {
                        i++;
                        committed.append(partial).append('\n');
                    }
                    partial.setLength(0);
                    need = true;
                } else {
                    partial.append(ch);
                    need = true;
                }
            }
        }
        if (need) {
            scheduleFlush();
        }
    }

    private void scheduleFlush() {
        synchronized (this) {
            if (flushPosted) {
                return;
            }
            flushPosted = true;
        }
        ui.postDelayed(new Runnable() {
            public void run() {
                doFlush();
            }
        }, 100);
    }

    private void doFlush() {
        String c;
        String p;
        synchronized (this) {
            c = committed.toString();
            committed.setLength(0);
            p = partial.toString();
            flushPosted = false;
        }
        if (c.length() > 0) {
            appendOut(c);
        }
        String shown = live.getText().toString();
        if (!p.equals(shown)) {
            live.setText(p);
        }
    }

    private void appendOut(CharSequence s) {
        if (out.length() > MAX_OUT) {
            CharSequence t = out.getText();
            out.setText(t.subSequence(t.length() - MAX_OUT / 2, t.length()));
        }
        out.append(s);
        scroll.post(new Runnable() {
            public void run() {
                scroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private void clearScreen() {
        out.setText("");
        live.setText("");
        synchronized (this) {
            committed.setLength(0);
            partial.setLength(0);
        }
    }

    private void copyOutput() {
        String text = out.getText().toString() + live.getText().toString();
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("GitDroid", text));
        Toast.makeText(this, "已复制 " + text.length() + " 字符", Toast.LENGTH_SHORT).show();
    }

    // ---------------- 命令执行 ----------------

    private void runCommand(String text) {
        String line = text == null ? "" : text.trim();
        if (line.length() == 0) {
            return;
        }
        history.add(line);
        histPos = history.size();
        input.setText("");
        render("$ " + line + "\n");
        if (line.equals("clear") || line.equals("cls")) {
            clearScreen();
            return;
        }
        if (line.equals("cd") || line.startsWith("cd ") || line.startsWith("cd\t")) {
            changeDir(line.length() > 2 ? line.substring(2).trim() : "");
            return;
        }
        if (busy) {
            render("(上一条命令还在运行，可点「停止」中断)\n");
            return;
        }
        List<String> one = new ArrayList<String>();
        one.add(line);
        runSequence(one, true);
    }

    private void changeDir(String target) {
        if (target.length() == 0 || target.equals("~")) {
            target = GitEnv.homeDir(this).getAbsolutePath();
        } else if (target.startsWith("~/")) {
            target = GitEnv.homeDir(this).getAbsolutePath() + target.substring(1);
        }
        File f = new File(target);
        if (!f.isAbsolute()) {
            f = new File(cwd, target);
        }
        try {
            f = f.getCanonicalFile();
        } catch (IOException ignored) {
        }
        if (f.isDirectory()) {
            cwd = f;
            updateCwdView();
            prefs.edit().putString("cwd", cwd.getAbsolutePath()).apply();
        } else {
            render("目录不存在：" + f.getAbsolutePath() + "\n");
        }
    }

    private void runSequence(final List<String> commands, final boolean focusAfter) {
        busy = true;
        updateRunState();
        new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < commands.size(); i++) {
                    String line = commands.get(i);
                    if (i > 0) {
                        render("$ " + line + "\n");
                    }
                    int code = -1;
                    try {
                        code = execProcess(line);
                    } catch (Throwable t) {
                        render("[错误] " + t + "\n");
                    }
                    flushPartial();
                    render("[退出码 " + code + "]\n");
                }
                busy = false;
                ui.post(new Runnable() {
                    public void run() {
                        updateRunState();
                        if (focusAfter) {
                            input.requestFocus();
                        }
                    }
                });
            }
        }).start();
    }

    private void flushPartial() {
        synchronized (this) {
            if (partial.length() > 0) {
                committed.append(partial).append('\n');
                partial.setLength(0);
            }
        }
        scheduleFlush();
    }

    private int execProcess(String line) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(argvFor(line));
        pb.redirectErrorStream(true);
        pb.directory(cwd);
        Map<String, String> env = pb.environment();
        env.clear();
        env.putAll(GitEnv.environment(this, cwd));
        Process p = pb.start();
        proc = p;
        try {
            p.getOutputStream().close();
        } catch (IOException ignored) {
        }
        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"), 8192);
        try {
            char[] buf = new char[4096];
            int n;
            while ((n = br.read(buf)) > 0) {
                render(new String(buf, 0, n));
            }
        } finally {
            GitEnv.closeQuietly(br);
        }
        int code = p.waitFor();
        proc = null;
        return code;
    }

    private List<String> argvFor(String line) {
        List<String> argv = new ArrayList<String>();
        String t = line.trim();
        boolean direct = (t.equals("git") || t.startsWith("git ") || t.startsWith("git\t"))
                && !hasShellMeta(t);
        if (direct) {
            argv.addAll(tokenize(t));
            argv.set(0, GitEnv.gitFile(this).getAbsolutePath());
        } else {
            argv.add("/system/bin/sh");
            argv.add("-c");
            argv.add(line);
        }
        return argv;
    }

    private static boolean hasShellMeta(String s) {
        String meta = ";|&$><`()*\n";
        for (int i = 0; i < s.length(); i++) {
            if (meta.indexOf(s.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static List<String> tokenize(String s) {
        List<String> r = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        boolean started = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\'' || ch == '"') {
                quoted = !quoted;
                started = true;
                continue;
            }
            if (!quoted && (ch == ' ' || ch == '\t')) {
                if (started) {
                    r.add(cur.toString());
                    cur.setLength(0);
                    started = false;
                }
                continue;
            }
            cur.append(ch);
            started = true;
        }
        if (started) {
            r.add(cur.toString());
        }
        return r;
    }

    private void stopProcess() {
        Process p = proc;
        if (p == null) {
            render("(当前没有运行中的命令)\n");
            return;
        }
        try {
            p.destroyForcibly();
        } catch (Throwable ignored) {
        }
        render("(已发送停止信号)\n");
    }

    // ---------------- 配置 / 诊断 ----------------

    private EditText field(String value, String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value == null ? "" : value);
        e.setSingleLine(true);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        e.setTextColor(C_FG);
        e.setHintTextColor(C_DIM);
        return e;
    }

    private static String shq(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private void showConfigDialog() {
        File cfg = GitEnv.configFile(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(8), dp(12), dp(8));

        final EditText name = field(GitEnv.readValue(cfg, "user", "name"), "user.name");
        final EditText mail = field(GitEnv.readValue(cfg, "user", "email"), "user.email");
        final EditText ghUser = field(prefs.getString("ghUser", ""), "GitHub 用户名");
        final EditText token = field(prefs.getString("ghToken", ""), "GitHub Token（写入 ~/.git-credentials）");
        final EditText work = field(cwd.getAbsolutePath(), "工作目录");
        final CheckBox verify = new CheckBox(this);
        verify.setText("校验 HTTPS 证书（取消勾选=不安全）");
        verify.setChecked(prefs.getBoolean("sslVerify", true));

        box.addView(name);
        box.addView(mail);
        box.addView(ghUser);
        box.addView(token);
        box.addView(work);
        box.addView(verify);

        ScrollView wrap = new ScrollView(this);
        wrap.addView(box);

        new AlertDialog.Builder(this)
                .setTitle("GitDroid 配置")
                .setView(wrap)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        saveConfig(name.getText().toString().trim(),
                                mail.getText().toString().trim(),
                                ghUser.getText().toString().trim(),
                                token.getText().toString().trim(),
                                work.getText().toString().trim(),
                                verify.isChecked());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void saveConfig(String name, String email, String ghUser, String token,
            String work, boolean sslVerify) {
        List<String> cmds = new ArrayList<String>();
        if (name.length() > 0) {
            cmds.add("git config --global user.name " + shq(name));
        }
        if (email.length() > 0) {
            cmds.add("git config --global user.email " + shq(email));
        }
        cmds.add("git config --global init.defaultBranch main");
        cmds.add("git config --global safe.directory '*'");
        cmds.add("git config --global core.quotePath false");
        if (sslVerify) {
            cmds.add("git config --global --unset http.sslVerify");
        } else {
            cmds.add("git config --global http.sslVerify false");
        }

        prefs.edit().putString("ghUser", ghUser)
                .putString("ghToken", token)
                .putBoolean("sslVerify", sslVerify).apply();

        if (token.length() > 0) {
            String user = ghUser.length() > 0 ? ghUser : "x-access-token";
            try {
                File f = GitEnv.credentialFile(this);
                GitEnv.writeText(f, "https://" + user + ":" + token + "@github.com\n");
                GitEnv.chmod600(f);
                cmds.add("git config --global credential.helper store");
                render("已写入凭据（仅本机应用私有目录）：" + f.getAbsolutePath() + "\n");
            } catch (IOException e) {
                render("写凭据失败：" + e + "\n");
            }
        }

        changeDir(work);
        runSequence(cmds, false);
    }

    private void showCwdDialog() {
        final EditText e = field(cwd.getAbsolutePath(), "工作目录绝对路径");
        new AlertDialog.Builder(this)
                .setTitle("切换工作目录")
                .setView(e)
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        changeDir(e.getText().toString().trim());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void diagnostics() {
        if (busy) {
            render("(正在运行其他命令)\n");
            return;
        }
        List<String> cmds = new ArrayList<String>();
        cmds.add("git --version");
        cmds.add("git --exec-path");
        cmds.add("ls -l \"$GIT_EXEC_PATH\"");
        cmds.add("echo \"HOME=$HOME TMPDIR=$TMPDIR SSL_CERT_DIR=$SSL_CERT_DIR\"; ls \"$SSL_CERT_DIR\" 2>/dev/null | wc -l");
        cmds.add("git config --global --list");
        cmds.add("touch /sdcard/.gitdroid_probe && echo sdcard-writable && rm -f /sdcard/.gitdroid_probe");
        cmds.add("git ls-remote https://github.com/git/git HEAD");
        runSequence(cmds, false);
    }
}
