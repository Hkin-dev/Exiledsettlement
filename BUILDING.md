# Building Exiled Settlement

Use a Java 21 JDK. The repository includes the Gradle Wrapper, so a separate Gradle installation is not required. The initial build needs internet access to download the toolchain dependencies.

请使用 Java 21 JDK。仓库包含 Gradle Wrapper，无需另行安装 Gradle；首次构建需要联网下载依赖。

## Windows

```powershell
.\gradlew.bat build
```

## Linux and macOS

```sh
chmod +x gradlew
./gradlew build
```

The compiled mod is written to `build/libs/`.

构建后的模组位于 `build/libs/`。

## Development

```powershell
.\gradlew.bat runClient
```

The build uses Minecraft 1.21.1, NeoForge 21.1.235, Java 21, ModDevGradle 2.0.141, and Gradle 9.2.0. YSM Mapping API is a compile-only compatibility dependency resolved from CurseMaven; neither compatibility mod is bundled.

构建使用 Minecraft 1.21.1、NeoForge 21.1.235、Java 21、ModDevGradle 2.0.141 和 Gradle 9.2.0。YSM Mapping API 是经 CurseMaven 获取的仅编译依赖，两项兼容模组均不打入发布包。
