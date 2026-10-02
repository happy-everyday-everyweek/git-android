# GitDroid

把 Git 移植到 Android：用 Android NDK 把 Git 交叉编译成 **aarch64 原生可执行文件**，再套一个极简的 Java 控制台外壳打成 APK。所有编译都在 GitHub Actions 上完成，本机不参与构建。

## 结构

```
ci/build-deps.sh    交叉编译 zlib / expat / OpenSSL / libcurl（静态）
ci/build-git.sh     交叉编译 git、git-remote-http、git-upload-pack
ci/package-apk.sh   javac + d8 + aapt2 + zipalign + apksigner 手工打包 APK
app/                Android 外壳（纯 Java，无 AndroidX、无 Kotlin）
.github/workflows/  GitHub Actions：一条流水线出 APK 并发布到 Release
```

## 运行原理

APK 里没有 `.so`，git 二进制以 assets 形式打包（压缩存放）。首次启动时外壳把 `git`、`git-remote-http`、`git-upload-pack` 解包到应用私有目录 `files/rootfs/bin`，授予可执行权限，并建立 `git-remote-https` 别名（git 通过它访问 HTTP(S) 远端）。

外壳把环境变量配好（`GIT_EXEC_PATH`、`GIT_TEMPLATE_DIR`、`HOME`、`TMPDIR`、`SSL_CERT_DIR` 指向系统 CA 目录等），然后直接 exec 这些二进制。目标 SDK 保持 28，才能从应用私有目录执行文件（Termux 同款取舍），同时 `WRITE_EXTERNAL_STORAGE` 可以访问 `/sdcard`。

## 安装与首次使用

1. 安装 `GitDroid-*.apk`（arm64-v8a，Android 8+）。
2. 首次启动授权存储权限，然后点「配置」填 `user.name`、`user.email`，以及 GitHub 用户名 + Token（会写进 `~/.gitconfig` 与 `~/.git-credentials`，走 credential.helper=store）。
3. 在输入框敲命令，例如：

```
git clone https://github.com/octocat/Hello-World
git status -sb
git commit -am "update"
git push
```

任何不以 `git` 开头的行会交给 `/system/bin/sh -c` 执行，所以 `ls`、`cd`、`mkdir`、`rm` 都能用。

## 已知限制

没有分配伪终端，因此交互式命令不可用：`git commit`（不跟 `-m`）不会弹出编辑器，`git rebase -i`、`git add -p` 也无法交互，需要凭据时请在「配置」里预先填好 Token。

为了控制体积，只打包 https/http 传输所需的三个二进制，不含 `ssh`、`less`、`man`、`git-daemon` 等服务端组件。

## 构建

推送到 `main` 触发，或在 Actions 页面手动 `workflow_dispatch`。产物同时上传为 artifact，并覆盖发布到 Release `gitdroid-latest`。

签名使用仓库内固定密钥库 `keystore/gitdroid-release.p12`（别名与口令都是 `gitdroid`），便于连续升级覆盖安装。
