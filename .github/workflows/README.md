# CI 签名密钥配置

本文件只补充仓库根 [README.md](../../README.md) 未覆盖的一件事：**如何把本地 release 签名密钥交给 GitHub Actions**。CI 构建流程与产物说明见根 README。

## 需要配置的 GitHub Secrets

在仓库 `Settings → Secrets and variables → Actions → New repository secret` 添加 4 个：

| Secret 名称 | 取值 |
|---|---|
| `MINIMECODE_KEYSTORE_BASE64` | `app/minimecode.jks` 文件的 base64 编码（见下） |
| `MINIMECODE_KEYSTORE_PASSWORD` | keystore 的 `storePassword` |
| `MINIMECODE_KEY_ALIAS` | 签名 key 的 `keyAlias` |
| `MINIMECODE_KEY_PASSWORD` | key 的 `keyPassword`（通常与 keystore 口令相同） |

> `GITHUB_TOKEN` 由 Actions 自动注入，无需手动配置。

## 生成 keystore 的 base64

仓库根目录用 PowerShell 复制到剪贴板：

```powershell
[System.Convert]::ToBase64String([System.IO.File]::ReadAllBytes("app\minimecode.jks")) | Set-Clipboard
```

粘贴到 `MINIMECODE_KEYSTORE_BASE64`。

若还没有 keystore，先用 keytool 生成（别名/口令自定，回填到对应 Secret）：

```powershell
keytool -genkeypair -v -keystore app\minimecode.jks -alias minimecode -keyalg RSA -keysize 2048 -validity 10000
```

> `minimecode.jks` 与 `keystore.properties` 都在 `.gitignore` 中，不会入仓库，本地签名不受影响。
