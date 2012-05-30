package com.intellij.lang.javascript.flex.actions.airpackage;

import com.intellij.ide.actions.ShowFilePathAction;
import com.intellij.lang.javascript.flex.FlexBundle;
import com.intellij.lang.javascript.flex.FlexModuleType;
import com.intellij.lang.javascript.flex.actions.ExternalTask;
import com.intellij.lang.javascript.flex.build.FlexCompiler;
import com.intellij.lang.javascript.flex.projectStructure.model.*;
import com.intellij.lang.javascript.flex.projectStructure.options.BuildConfigurationNature;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationGroup;
import com.intellij.notification.NotificationListener;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.compiler.CompileContext;
import com.intellij.openapi.compiler.CompileScope;
import com.intellij.openapi.compiler.CompileStatusNotification;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.ModuleType;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.Consumer;
import com.intellij.util.Function;
import com.intellij.util.PathUtil;
import gnu.trove.THashSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.event.HyperlinkEvent;
import java.io.File;
import java.util.*;

import static com.intellij.lang.javascript.flex.actions.airpackage.AirPackageProjectParameters.DesktopPackageType;

public class AirPackageAction extends DumbAwareAction {
  public static final NotificationGroup NOTIFICATION_GROUP = NotificationGroup.balloonGroup("AIR Packaging");

  public void update(final AnActionEvent e) {
    final Project project = e.getProject();

    boolean flexModulePresent = false;
    boolean airAppPresent = false;

    if (project != null) {
      final FlexModuleType flexModuleType = FlexModuleType.getInstance();

      MODULES_LOOP:
      for (Module module : ModuleManager.getInstance(project).getModules()) {
        if (ModuleType.get(module) == flexModuleType) {
          flexModulePresent = true;

          for (FlexIdeBuildConfiguration bc : FlexBuildConfigurationManager.getInstance(module).getBuildConfigurations()) {
            final BuildConfigurationNature nature = bc.getNature();
            if (nature.isApp() && !nature.isWebPlatform()) {
              airAppPresent = true;
              break MODULES_LOOP;
            }
          }
        }
      }
    }

    e.getPresentation().setVisible(flexModulePresent);
    e.getPresentation().setEnabled(airAppPresent &&
                                   !CompilerManager.getInstance(project).isCompilationActive() &&
                                   !AirPackageProjectParameters.getInstance(project).isPackagingInProgress());
  }

  public void actionPerformed(final AnActionEvent e) {
    final Project project = e.getProject();
    if (project == null) return;

    final AirPackageDialog dialog = new AirPackageDialog(project);
    dialog.show();

    if (!dialog.isOK()) return;

    final Collection<Pair<Module, FlexIdeBuildConfiguration>> modulesAndBCs = dialog.getSelectedBCs();
    final Set<Module> modules = new THashSet<Module>();
    for (Pair<Module, FlexIdeBuildConfiguration> bc : modulesAndBCs) {
      modules.add(bc.first);
    }

    final CompilerManager compilerManager = CompilerManager.getInstance(project);
    final CompileScope compileScope = compilerManager.createModulesCompileScope(modules.toArray(new Module[modules.size()]), false);
    compileScope.putUserData(FlexCompiler.MODULES_AND_BCS_TO_COMPILE, modulesAndBCs);

    compilerManager.make(compileScope, new CompileStatusNotification() {
      public void finished(final boolean aborted, final int errors, final int warnings, final CompileContext compileContext) {
        if (!aborted && errors == 0) {
          createPackages(project, modulesAndBCs, dialog.getPasswords());
        }
      }
    });
  }

  private static void createPackages(final Project project,
                                     final Collection<Pair<Module, FlexIdeBuildConfiguration>> modulesAndBCs,
                                     final PasswordStore passwords) {
    final Collection<Pair<ExternalTask, String>> tasksAndPackagePaths = new ArrayList<Pair<ExternalTask, String>>();

    final AirPackageProjectParameters params = AirPackageProjectParameters.getInstance(project);

    for (Pair<Module, FlexIdeBuildConfiguration> moduleAndBC : modulesAndBCs) {
      final FlexIdeBuildConfiguration bc = moduleAndBC.second;
      final String outputFolder = PathUtil.getParentPath(bc.getActualOutputFilePath());

      if (bc.getTargetPlatform() == TargetPlatform.Desktop) {
        if (bc.getAirDesktopPackagingOptions().getSigningOptions().isUseTempCertificate() &&
            !AirPackageUtil.ensureCertificateExists(project, bc.getSdk())) {
          return;
        }

        final DesktopPackageType packageType = params.desktopPackageType;
        final ExternalTask task = AirPackageUtil.createAirDesktopTask(moduleAndBC.first, bc, packageType, passwords);
        final String packagePath = outputFolder + "/" +
                                   bc.getAirDesktopPackagingOptions().getPackageFileName() + packageType.getFileExtension();
        tasksAndPackagePaths.add(Pair.create(task, packagePath));
      }
      else {
        if (bc.getAndroidPackagingOptions().isEnabled()) {
          final AndroidPackagingOptions packagingOptions = bc.getAndroidPackagingOptions();
          if (packagingOptions.getSigningOptions().isUseTempCertificate() &&
              !AirPackageUtil.ensureCertificateExists(project, bc.getSdk())) {
            return;
          }

          final ExternalTask task = AirPackageUtil.createAndroidPackageTask(moduleAndBC.first, bc, params.androidPackageType,
                                                                            params.apkCaptiveRuntime, params.apkDebugListenPort, passwords);
          final String packagePath = outputFolder + "/" + packagingOptions.getPackageFileName() + ".apk";
          tasksAndPackagePaths.add(Pair.create(task, packagePath));
        }

        if (bc.getIosPackagingOptions().isEnabled()) {
          final IosPackagingOptions packagingOptions = bc.getIosPackagingOptions();
          final ExternalTask task = AirPackageUtil.createIOSPackageTask(moduleAndBC.first, bc, params.iosPackageType,
                                                                        params.iosFastPackaging, params.iosSDKPath, passwords);
          final String packagePath = outputFolder + "/" + packagingOptions.getPackageFileName() + ".ipa";
          tasksAndPackagePaths.add(Pair.create(task, packagePath));
        }
      }
    }

    createPackages(project, tasksAndPackagePaths);
  }

  private static void createPackages(final Project project, final Collection<Pair<ExternalTask, String>> tasksAndPackagePaths) {
    final Iterator<Pair<ExternalTask, String>> iterator = tasksAndPackagePaths.iterator();
    final Pair<ExternalTask, String> taskAndPackagePath = iterator.next();
    final String packagePath = taskAndPackagePath.second;
    final Runnable onSuccessRunnable = createOnSuccessRunnable(project, iterator, packagePath, new ArrayList<String>());
    ExternalTask
      .runInBackground(taskAndPackagePath.first, FlexBundle.message("packaging.air.application", PathUtil.getFileName(packagePath)),
                       onSuccessRunnable, createFailureConsumer(project, packagePath));
  }

  private static Runnable createOnSuccessRunnable(final Project project,
                                                  final Iterator<Pair<ExternalTask, String>> iterator,
                                                  final String createdPackagePath,
                                                  final Collection<String> allCreatedPackages) {
    return new Runnable() {
      public void run() {
        allCreatedPackages.add(createdPackagePath);

        if (iterator.hasNext()) {
          final Pair<ExternalTask, String> taskAndPackagePath = iterator.next();
          final String packagePath = taskAndPackagePath.second;
          final Runnable onSuccessRunnable = createOnSuccessRunnable(project, iterator, packagePath, allCreatedPackages);
          ExternalTask
            .runInBackground(taskAndPackagePath.first, FlexBundle.message("packaging.air.application", PathUtil.getFileName(packagePath)),
                             onSuccessRunnable, createFailureConsumer(project, packagePath));
        }
        else {
          final String hrefs = StringUtil.join(allCreatedPackages, new Function<String, String>() {
            public String fun(final String packagePath) {
              return "<a href='" + packagePath + "'>" + PathUtil.getFileName(packagePath) + "</a>";
            }
          }, "<br>");
          final String message = FlexBundle.message("air.application.created", allCreatedPackages.size(), hrefs);

          final NotificationListener listener = new NotificationListener() {
            public void hyperlinkUpdate(@NotNull final Notification notification, @NotNull final HyperlinkEvent event) {
              if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                notification.expire();
                final String packagePath = event.getDescription();
                ShowFilePathAction.openFile(new File(packagePath));
              }
            }
          };

          NOTIFICATION_GROUP.createNotification("", message, NotificationType.INFORMATION, listener).notify(project);
        }
      }
    };
  }

  private static Consumer<List<String>> createFailureConsumer(final Project project, final String packagePath) {
    return new Consumer<List<String>>() {
      public void consume(final List<String> messages) {
        final String reason = StringUtil.join(messages, "<br>");

        NOTIFICATION_GROUP
          .createNotification("", FlexBundle.message("failed.to.create.air.package", PathUtil.getFileName(packagePath), reason),
                              NotificationType.ERROR, null)
          .notify(project);
      }
    };
  }

  @Nullable
  public static PasswordStore getPasswords(final Project project,
                                           final Collection<? extends AirPackagingOptions> allPackagingOptions) {
    final Collection<AirSigningOptions> signingOptionsWithUnknownPasswords = new ArrayList<AirSigningOptions>();

    for (AirPackagingOptions packagingOptions : allPackagingOptions) {
      final AirSigningOptions signingOptions = packagingOptions.getSigningOptions();
      final boolean tempCertificate = !(packagingOptions instanceof IosPackagingOptions) && signingOptions.isUseTempCertificate();
      if (!tempCertificate && !PasswordStore.isPasswordKnown(project, signingOptions)) {
        signingOptionsWithUnknownPasswords.add(signingOptions);
      }
    }

    if (!signingOptionsWithUnknownPasswords.isEmpty()) {
      final KeystorePasswordDialog dialog = new KeystorePasswordDialog(project, signingOptionsWithUnknownPasswords);
      dialog.show();
      return dialog.isOK() ? dialog.getPasswords() : null;
    }

    return PasswordStore.getInstance(project);
  }
}
