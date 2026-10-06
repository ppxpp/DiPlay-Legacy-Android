# 公开开发签名

此密钥及密码有意公开，仅供开发测试，禁止用于正式发布或可信更新。
与 C 桌面正式签名无关，不能覆盖由其他密钥签名的同包名应用。

在项目根目录执行：

```bash
source dev-signing/signing-env.sh
bash gradlew :mobile:assembleRelease
```

如需内置 CarPlay 运行时认证文件，还须单独设置 `DIPLAY_AUTH_ASSETS_DIR`。
`release-signing.properties` 中的 `storeFile` 相对于本目录；当前 Gradle 使用环境变量，执行上述脚本加载即可。
