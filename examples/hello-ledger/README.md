# Hello Ledger Example Add-on

This minimal Fabric add-on demonstrates how to bootstrap against MinCore’s APIs:

* Listen for `PlayerRegisteredEvent` to grant a welcome bonus.
* Use idempotent wallet operations to avoid duplicate deposits.
* Localise messages and respect player timezones.

## Project Layout

```
examples/hello-ledger/
├─ build.gradle.kts        # Minimal Loom build (see snippet below)
├─ src/main/java/dev/mincore/example/HelloLedgerMod.java
├─ src/main/resources/
│  ├─ fabric.mod.json
│  └─ assets/helloledger/lang/en_us.json
└─ README.md (this file)
```

The example is not wired into the root Gradle build to keep the core lean—copy the folder beside
your Fabric mod workspace and import it as a separate module.

## Key Snippets

### Gradle Build Script (`build.gradle.kts`)

```kotlin
plugins {
  id("fabric-loom") version "1.11-SNAPSHOT"
}

java.sourceCompatibility = JavaVersion.VERSION_21
java.targetCompatibility = JavaVersion.VERSION_21

repositories {
  mavenCentral()
  maven("https://maven.fabricmc.net/")
}

dependencies {
  minecraft("com.mojang:minecraft:${rootProject.extra["minecraft_version"]}")
  mappings("net.fabricmc:yarn:${rootProject.extra["yarn_mappings"]}:v2")
  modImplementation("net.fabricmc:fabric-loader:${rootProject.extra["loader_version"]}")
  modImplementation("net.fabricmc.fabric-api:fabric-api:${rootProject.extra["fabric_version"]}")
  modImplementation(files("../mincore/build/libs/mincore-${rootProject.version}.jar"))
}
```

### Mod Initialiser (`HelloLedgerMod.java`)

```java
public final class HelloLedgerMod implements ModInitializer {
  private static final String ADDON_ID = "hello-ledger";

  @Override
  public void onInitialize() {
    MinCoreApi.events()
        .onPlayerRegistered(
            event -> {
              UUID player = event.player();
              String reason = "hello-ledger:welcome";
              String idem = "hello-ledger:welcome:" + player;
              Wallets.OperationResult result =
                  MinCoreApi.wallets().depositResult(player, 100L, reason, idem);
              if (result.ok()) {
                MinCoreApi.ledger()
                    .log(
                        ADDON_ID,
                        "welcome-bonus",
                        null,
                        player,
                        100L,
                        reason,
                        true,
                        null,
                        "hello-ledger",
                        idem,
                        null);
              }
            });
  }
}
```

### Localisation (`assets/helloledger/lang/en_us.json`)

```json
{
  "helloledger.msg.bonus": "Welcome to the server! A 100 unit bonus was added to your wallet."
}
```

Use `MinCoreApi.players()` to resolve names for notifications and
`Timezones.resolve(source, services)` if you need to render timestamps for players.
