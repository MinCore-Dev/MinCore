/* Holarki © 2025 Holarki Devs — MIT */
package dev.holarki.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import dev.holarki.HolarkiMod;
import dev.holarki.core.Config;
import dev.holarki.core.TestConfigFactory;
import dev.holarki.core.Services;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.text.TextContent;
import net.minecraft.text.TranslatableTextContent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

final class AdminCommandsExportDisabledTest {

  @Test
  void exportCommandReturnsDisabledMessageWhenBackupOff() throws Exception {
    Path backupDir = Files.createTempDirectory("holarki-export-disabled");
    Config baseConfig = TestConfigFactory.create("holarki_test", backupDir);

    Config.Backup baseBackup = baseConfig.jobs().backup();
    Config.Backup disabledBackup =
        new Config.Backup(
            false,
            baseBackup.schedule(),
            baseBackup.outDir(),
            baseBackup.onMissed(),
            baseBackup.gzip(),
            baseBackup.prune());
    Config.Jobs disabledJobs = new Config.Jobs(disabledBackup, baseConfig.jobs().cleanup());
    Config.SchedulerModule schedulerModule =
        new Config.SchedulerModule(baseConfig.modules().scheduler().enabled(), disabledJobs);
    Config.Modules disabledModules =
        new Config.Modules(
            baseConfig.modules().ledger(), schedulerModule, baseConfig.modules().timezone());

    Constructor<Config> ctor =
        Config.class.getDeclaredConstructor(
            Config.Db.class,
            Config.Runtime.class,
            Config.Time.class,
            Config.I18n.class,
            Config.Modules.class,
            Config.Log.class);
    ctor.setAccessible(true);
    Config disabledConfig =
        ctor.newInstance(
            baseConfig.db(),
            baseConfig.runtime(),
            baseConfig.time(),
            baseConfig.i18n(),
            disabledModules,
            baseConfig.log());

    Field configField = HolarkiMod.class.getDeclaredField("CONFIG");
    configField.setAccessible(true);
    Config previousConfig = (Config) configField.get(null);
    configField.set(null, disabledConfig);

    try {
      Services services = mock(Services.class);
      ServerCommandSource src = mock(ServerCommandSource.class);

      Method method =
          AdminCommands.class.getDeclaredMethod(
              "cmdExportAll", ServerCommandSource.class, Services.class, String.class);
      method.setAccessible(true);

      int result = (int) method.invoke(null, src, services, "");
      assertEquals(0, result);

      ArgumentCaptor<Supplier<Text>> feedbackCaptor = ArgumentCaptor.forClass(Supplier.class);
      verify(src).sendFeedback(feedbackCaptor.capture(), eq(false));
      verify(services, never()).scheduler();

      Text feedback = feedbackCaptor.getValue().get();
      assertTranslatableKey(feedback, "holarki.cmd.backup.fail");
      assertEquals("disabled", extractFirstArg(feedback));
    } finally {
      configField.set(null, previousConfig);
      Files.deleteIfExists(backupDir);
    }
  }

  private static void assertTranslatableKey(Text text, String expectedKey) {
    if (!(text instanceof MutableText mutable)) {
      throw new AssertionError("Expected MutableText but found " + text.getClass().getName());
    }
    TextContent content = mutable.getContent();
    if (!(content instanceof TranslatableTextContent translatable)) {
      throw new AssertionError(
          "Expected TranslatableTextContent but found " + content.getClass().getName());
    }
    if (!expectedKey.equals(translatable.getKey())) {
      throw new AssertionError(
          "Expected key " + expectedKey + " but found " + translatable.getKey());
    }
  }

  private static Object extractFirstArg(Text text) {
    MutableText mutable = (MutableText) text;
    TranslatableTextContent content = (TranslatableTextContent) mutable.getContent();
    Object[] args = content.getArgs();
    if (args.length == 0) {
      throw new AssertionError("Expected at least one translation argument");
    }
    return args[0];
  }
}
