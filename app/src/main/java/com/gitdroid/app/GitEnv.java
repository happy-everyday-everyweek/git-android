package com.gitdroid.app;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * 把打包在 assets 里的 git 二进制解包到应用私有目录，并准备 git 需要的运行环境。
 * 目标 SDK 保持 28，才能从应用私有目录直接 exec（Termux 同款取舍）。
 */
final class GitEnv {

    static final String[] BINARIES = { "git", "git-remote-http", "git-upload-pack" };
    private static final String HELPER = "git-remote-http";
    private static final String[] HELPER_ALIASES = { "git-remote-https" };

    private GitEnv() {}

    static File rootDir(Context c)        { return new File(c.getFilesDir(), "rootfs"); }
    static File binDir(Context c)         { return new File(rootDir(c), "bin"); }
    static File homeDir(Context c)        { return new File(rootDir(c), "home"); }
    static File tmpDir(Context c)         { return new File(rootDir(c), "tmp"); }
    static File templatesDir(Context c)   { return new File(rootDir(c), "share/git-core/templates"); }
    static File gitFile(Context c)        { return new File(binDir(c), "git"); }
    static File configFile(Context c)     { return new File(homeDir(c), ".gitconfig"); }
    static File credentialFile(Context c) { return new File(homeDir(c), ".git-credentials"); }

    /** 解包二进制（幂等）。返回本次新解包内容的简述。 */
    static synchronized String install(Context ctx) throws IOException {
        File bin = binDir(ctx);
        mkdirs(bin);
        mkdirs(homeDir(ctx));
        mkdirs(tmpDir(ctx));
        mkdirs(templatesDir(ctx));

        StringBuilder notes = new StringBuilder();
        for (String name : BINARIES) {
            InputStream in;
            try {
                in = ctx.getAssets().open("bin/" + name);
            } catch (IOException missing) {
                continue;
            }
            File out = new File(bin, name);
            long expected = assetSize(ctx, "bin/" + name);
            boolean ok = out.isFile() && out.length() > 0 && out.canExecute()
                    && (expected < 0 || out.length() == expected);
            if (!ok) {
                writeFile(in, out);
                chmodExec(out);
                notes.append(name).append('(').append(out.length() / 1024).append("KB) ");
            }
            closeQuietly(in);
        }

        File helper = new File(bin, HELPER);
        if (helper.isFile()) {
            for (String alias : HELPER_ALIASES) {
                File link = new File(bin, alias);
                if (link.isFile() && link.length() == helper.length()) {
                    continue;
                }
                link.delete();
                if (!hardlink(helper, link)) {
                    copyFile(helper, link);
                }
                chmodExec(link);
            }
        }
        return notes.toString();
    }

    /** 首次运行时写入一份最小可用的全局配置（已有配置不覆盖）。 */
    static void writeDefaults(Context ctx) {
        File cfg = configFile(ctx);
        if (cfg.isFile()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[init]\n\tdefaultBranch = main\n");
        sb.append("[safe]\n\tdirectory = *\n");
        sb.append("[core]\n\tquotePath = false\n");
        sb.append("[credential]\n\thelper = store\n");
        try {
            writeText(cfg, sb.toString());
        } catch (IOException ignored) {
        }
    }

    /** git 及其子进程需要的环境变量。 */
    static Map<String, String> environment(Context ctx, File cwd) {
        String bin = binDir(ctx).getAbsolutePath();
        String home = homeDir(ctx).getAbsolutePath();
        String tmp = tmpDir(ctx).getAbsolutePath();
        Map<String, String> e = new HashMap<String, String>();
        e.put("PATH", bin + ":/system/bin:/system/xbin");
        e.put("HOME", home);
        e.put("TMPDIR", tmp);
        e.put("TEMP", tmp);
        e.put("PWD", cwd.getAbsolutePath());
        e.put("SHELL", "/system/bin/sh");
        e.put("GIT_EXEC_PATH", bin);
        e.put("GIT_TEMPLATE_DIR", templatesDir(ctx).getAbsolutePath());
        e.put("GIT_CONFIG_NOSYSTEM", "1");
        e.put("GIT_TERMINAL_PROMPT", "0");
        e.put("GIT_PAGER", "cat");
        e.put("PAGER", "cat");
        e.put("TERM", "dumb");
        e.put("LC_ALL", "C.UTF-8");
        e.put("LANG", "C.UTF-8");
        e.put("ANDROID_ROOT", "/system");
        e.put("ANDROID_DATA", "/data");
        File ca = caDirectory();
        if (ca != null) {
            e.put("SSL_CERT_DIR", ca.getAbsolutePath());
        }
        return e;
    }

    /** OpenSSL 通过 SSL_CERT_DIR 读系统 CA；Android 的 cacerts 是哈希命名，正好符合目录查找格式。 */
    private static File caDirectory() {
        String[] candidates = { "/apex/com.android.conscrypt/cacerts", "/system/etc/security/cacerts" };
        for (String path : candidates) {
            File f = new File(path);
            if (f.isDirectory() && f.canRead()) {
                return f;
            }
        }
        return null;
    }

    static void writeText(File f, String text) throws IOException {
        OutputStream os = new FileOutputStream(f);
        try {
            os.write(text.getBytes("UTF-8"));
            os.flush();
        } finally {
            closeQuietly(os);
        }
    }

    static String readText(File f) {
        if (!f.isFile()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (IOException ignored) {
        } finally {
            closeQuietly(br);
        }
        return sb.toString();
    }

    /** 极简 INI 读取：取 [section] 下的 key，找不到返回空串。 */
    static String readValue(File f, String section, String key) {
        String text = readText(f);
        String want = "[" + section + "]";
        int idx = text.indexOf(want);
        if (idx < 0) {
            return "";
        }
        String[] lines = text.substring(idx + want.length()).split("\n");
        for (int i = 0; i < lines.length; i++) {
            String s = lines[i].trim();
            if (s.startsWith("[")) {
                break;
            }
            int eq = s.indexOf('=');
            if (eq > 0 && s.substring(0, eq).trim().equalsIgnoreCase(key)) {
                return s.substring(eq + 1).trim();
            }
        }
        return "";
    }

    static void chmodExec(File f) {
        f.setReadable(true, false);
        f.setWritable(true, true);
        f.setExecutable(true, false);
    }

    static void chmod600(File f) {
        f.setReadable(true, true);
        f.setWritable(true, true);
        f.setExecutable(false, false);
    }

    static void mkdirs(File d) {
        if (!d.isDirectory()) {
            d.mkdirs();
        }
    }

    static void closeQuietly(Object o) {
        if (o == null) {
            return;
        }
        try {
            if (o instanceof InputStream) {
                ((InputStream) o).close();
            } else if (o instanceof OutputStream) {
                ((OutputStream) o).close();
            } else if (o instanceof BufferedReader) {
                ((BufferedReader) o).close();
            }
        } catch (IOException ignored) {
        }
    }

    private static long assetSize(Context ctx, String name) {
        try {
            return ctx.getAssets().openFd(name).getLength();
        } catch (IOException compressed) {
            return -1;
        }
    }

    private static void writeFile(InputStream in, File out) throws IOException {
        File tmp = new File(out.getParentFile(), out.getName() + ".tmp");
        OutputStream os = new FileOutputStream(tmp);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
            os.flush();
        } finally {
            closeQuietly(os);
        }
        out.delete();
        if (!tmp.renameTo(out)) {
            copyFile(tmp, out);
            tmp.delete();
        }
    }

    private static void copyFile(File src, File dst) {
        InputStream in = null;
        OutputStream os = null;
        try {
            in = new FileInputStream(src);
            os = new FileOutputStream(dst);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
        } catch (IOException ignored) {
        } finally {
            closeQuietly(in);
            closeQuietly(os);
        }
    }

    private static boolean hardlink(File src, File dst) {
        try {
            Files.createLink(dst.toPath(), src.toPath());
            return true;
        } catch (Throwable t) {
            try {
                Files.createSymbolicLink(dst.toPath(), src.toPath());
                return true;
            } catch (Throwable t2) {
                return false;
            }
        }
    }
}