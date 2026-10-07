# AgentForge 本地发布工具

`release.sh` 用于一键构建、GPG 签名并将 AgentForge 正式版构件推送到 Maven Central Portal。脚本只处理公开库模块，不发布 examples / service。

## 前置条件

1. GPG 私钥已生成，公钥已上传到公钥服务器。
2. Maven `settings.xml` 已配置 `<server id="central">` 的 Portal User Token。
3. `settings.xml` 位于 `~/.m2/settings.xml`，或通过 `--settings` 传入绝对路径。
4. 正式版本号尚未在 Maven Central 发布。

## 一键预演（推荐先执行）

构建并生成真实 GPG 签名，但不上传：

```bash
bin/local/release.sh 1.0.0 \
  --settings "/absolute/path/settings.xml" \
  --dry-run
```

## 一键上传（Portal 手动发布）

适合首次发布。脚本上传后会停在 Central Portal 待审核状态：

```bash
bin/local/release.sh 1.0.0 \
  --settings "/absolute/path/settings.xml"
```

上传成功后，打开 <https://central.sonatype.com/publishing/deployments> 检查构件，然后手动点击 **Publish**。

## 一键自动发布

确认流程稳定后再使用：

```bash
bin/local/release.sh 1.0.0 \
  --settings "/absolute/path/settings.xml" \
  --auto-publish
```

## 可选参数

| 参数 | 作用 |
| --- | --- |
| `--settings <file>` | 指定 Maven `settings.xml` |
| `--dry-run` | 真实构建与签名，不上传 |
| `--auto-publish` | 上传后由 Central 自动发布 |
| `--skip-tests` | 跳过测试，正式发布不建议使用 |
| `-h`, `--help` | 显示帮助 |

## 安全说明

- 脚本不包含 Central Token、GPG 私钥或 Passphrase。
- 未设置 `MAVEN_GPG_PASSPHRASE` 时，脚本会在终端中隐藏输入 GPG Passphrase。
- 脚本会自动探测 MacPorts / Homebrew 的 GPG 绝对路径，避免 GUI Terminal 的 `PATH` 缺失导致签名失败。
- Maven 启动前会先对临时文件执行签名与验签；口令或 GPG 配置不正确时会立即输出原始错误，不再等到 Maven 模块构建中途才失败。
- 不要将 `settings.xml`、私钥导出文件或口令提交到 Git。
- Maven Central 已发布版本不可覆盖，重试前必须确认版本号。

## `损坏的密码` / `Bad passphrase`

该报错表示输入的口令与当前 GPG 私钥不匹配。请输入**创建这把 GPG 密钥时设置的 Passphrase**，不是 Central Token、KEY_ID、密钥指纹或 macOS 登录密码。

如果只是输入错误，重新执行原命令即可。如果已忘记 GPG Passphrase，无法从私钥反向找回；需要生成新密钥、上传新公钥，再用新密钥发布。
