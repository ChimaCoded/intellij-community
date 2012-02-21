package com.jetbrains.python.inspections;

import com.intellij.codeInspection.LocalInspectionToolSession;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.util.Function;
import com.jetbrains.python.codeInsight.stdlib.PyStdlibUtil;
import com.jetbrains.python.packaging.*;
import com.jetbrains.python.psi.*;
import com.jetbrains.python.psi.impl.PyQualifiedName;
import com.jetbrains.python.psi.resolve.PyResolveUtil;
import com.jetbrains.python.sdk.PythonSdkType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * @author vlan
 */
public class PyPackageRequirementsInspection extends PyInspection {
  @NotNull
  @Override
  public String getDisplayName() {
    return "Package requirements";
  }

  @NotNull
  @Override
  public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder,
                                        boolean isOnTheFly,
                                        @NotNull LocalInspectionToolSession session) {
    return new Visitor(holder, session);
  }

  private static class Visitor extends PyInspectionVisitor {
    public Visitor(@Nullable ProblemsHolder holder, @NotNull LocalInspectionToolSession session) {
      super(holder, session);
    }

    @Override
    public void visitPyFile(PyFile node) {
      final Module module = ModuleUtil.findModuleForPsiElement(node);
      if (module != null) {
        if (isRunningPackagingTasks(module)) {
          return;
        }
        final Sdk sdk = PythonSdkType.findPythonSdk(module);
        if (sdk != null) {
          final List<PyRequirement> unsatisfied = findUnsatisfiedRequirements(module, sdk);
          if (unsatisfied != null && !unsatisfied.isEmpty()) {
            final boolean plural = unsatisfied.size() > 1;
            String msg = String.format("Package requirement%s %s %s not satisfied",
                                       plural ? "s" : "",
                                       requirementsToString(unsatisfied),
                                       plural ? "are" : "is");
            registerProblem(node, msg, new InstallRequirementsFix(null, module, sdk, unsatisfied));
          }
        }
      }
    }

    @Override
    public void visitPyFromImportStatement(PyFromImportStatement node) {
      final PyReferenceExpression expr = node.getImportSource();
      if (expr != null) {
        checkPackageNameInRequirements(expr);
      }
    }

    @Override
    public void visitPyImportStatement(PyImportStatement node) {
      for (PyImportElement element : node.getImportElements()) {
        final PyReferenceExpression expr = element.getImportReferenceExpression();
        if (expr != null) {
          checkPackageNameInRequirements(expr);
        }
      }
    }

    private void checkPackageNameInRequirements(@NotNull PyQualifiedExpression importedExpression) {
      final List<PyExpression> expressions = PyResolveUtil.unwindQualifiers(importedExpression);
      if (!expressions.isEmpty()) {
        final PyExpression packageReference = expressions.get(0);
        final String packageName = packageReference.getName();
        if (packageName != null) {
          final Collection<String> stdlibPackages = PyStdlibUtil.getPackages();
          if (stdlibPackages != null) {
            for (String name : stdlibPackages) {
              if (packageName.equals(name)) {
                return;
              }
            }
          }
          final Module module = ModuleUtil.findModuleForPsiElement(packageReference);
          if (module != null) {
            final List<PyRequirement> requirements = PyPackageManager.getRequirements(module);
            if (requirements != null) {
              for (PyRequirement req : requirements) {
                if (packageName.equalsIgnoreCase(req.getName())) {
                  return;
                }
              }
              final PyQualifiedName packageQName = PyQualifiedName.fromComponents(packageName);
              for (String name : PyPackageUtil.getPackageNames(module)) {
                final PyQualifiedName qname = PyQualifiedName.fromDottedString(name);
                if (qname.matchesPrefix(packageQName)) {
                  return;
                }
              }
              // TODO: User-adjustable ignore settings for this inspection: maybe the "Ignore imported package 'foo'" quickfix
              registerProblem(packageReference, String.format("Package '%s' is not listed in project requirements", packageName),
                              new AddToRequirementsFix(module, packageName, LanguageLevel.forElement(importedExpression)));
            }
          }
        }
      }
    }
  }

  @NotNull
  private static String requirementsToString(@NotNull List<PyRequirement> requirements) {
    return StringUtil.join(requirements, new Function<PyRequirement, String>() {
      @Override
      public String fun(PyRequirement requirement) {
        return String.format("'%s'", requirement.toString());
      }
    }, ", ");
  }

  @Nullable
  private static List<PyRequirement> findUnsatisfiedRequirements(@NotNull Module module, @NotNull Sdk sdk) {
    final PyPackageManager manager = PyPackageManager.getInstance(sdk);
    List<PyRequirement> requirements = PyPackageManager.getRequirements(module);
    if (requirements != null) {
      final List<PyPackage> packages;
      try {
        packages = manager.getPackages();
      }
      catch (PyExternalProcessException ignored) {
        return null;
      }
      final List<PyRequirement> unsatisfied = new ArrayList<PyRequirement>();
      for (PyRequirement req : requirements) {
        if (!req.match(packages)) {
          unsatisfied.add(req);
        }
      }
      return unsatisfied;
    }
    return null;
  }

  private static void setRunningPackagingTasks(@NotNull Module module, boolean value) {
    module.putUserData(PyPackageManager.RUNNING_PACKAGING_TASKS, value);
  }

  private static boolean isRunningPackagingTasks(@NotNull Module module) {
    final Boolean value = module.getUserData(PyPackageManager.RUNNING_PACKAGING_TASKS);
    return value != null && value;
  }

  public static class InstallRequirementsFix implements LocalQuickFix {
    @NotNull private String myName;
    @NotNull private final Module myModule;
    @NotNull private Sdk mySdk;
    @NotNull private final List<PyRequirement> myUnsatisfied;

    public InstallRequirementsFix(@Nullable String name, @NotNull Module module, @NotNull Sdk sdk,
                                  @NotNull List<PyRequirement> unsatisfied) {
      myName = name != null ? name : "Install requirements";
      myModule = module;
      mySdk = sdk;
      myUnsatisfied = unsatisfied;
    }

    @NotNull
    @Override
    public String getName() {
      return myName;
    }

    @NotNull
    @Override
    public String getFamilyName() {
      return myName;
    }

    @Override
    public void applyFix(@NotNull final Project project, @NotNull ProblemDescriptor descriptor) {
      final PyPackageManager.UI ui = new PyPackageManager.UI(project, mySdk, new PyPackageManager.UI.Listener() {
        @Override
        public void started() {
          setRunningPackagingTasks(myModule, true);
        }

        @Override
        public void finished(@Nullable PyExternalProcessException exception) {
          setRunningPackagingTasks(myModule, false);
        }
      });
      ui.install(myUnsatisfied, Collections.<String>emptyList());
    }
  }

  private static class AddToRequirementsFix implements LocalQuickFix {
    @Nullable private final PyListLiteralExpression myInstallRequires;
    @Nullable private final Document myRequirementsTxt;
    @Nullable private final PyArgumentList mySetupArgumentList;
    @NotNull private final String myPackageName;
    private final LanguageLevel myLanguageLevel;

    private AddToRequirementsFix(@NotNull Module module, @NotNull String packageName, LanguageLevel languageLevel) {
      myPackageName = packageName;
      myLanguageLevel = languageLevel;
      myRequirementsTxt = PyPackageUtil.findRequirementsTxt(module);
      myInstallRequires = PyPackageUtil.findSetupPyInstallRequires(module);
      final PyFile setupPy = PyPackageUtil.findSetupPy(module);
      if (setupPy != null) {
        final PyCallExpression setupCall = PyPackageUtil.findSetupCall(setupPy);
        if (setupCall != null) {
          mySetupArgumentList = setupCall.getArgumentList();
        }
        else {
          mySetupArgumentList = null;
        }
      }
      else {
        mySetupArgumentList = null;
      }
    }

    @NotNull
    @Override
    public String getName() {
      final String target;
      if (myRequirementsTxt != null) {
        target = "requirements.txt";
      }
      else if (myInstallRequires != null || mySetupArgumentList != null) {
        target = "setup.py";
      }
      else {
        target = "project requirements";
      }
      return String.format("Add requirement '%s' to %s", myPackageName, target);
    }

    @NotNull
    @Override
    public String getFamilyName() {
      return getName();
    }

    @Override
    public void applyFix(@NotNull final Project project, @NotNull ProblemDescriptor descriptor) {
      CommandProcessor.getInstance().executeCommand(project, new Runnable() {
        @Override
        public void run() {
          ApplicationManager.getApplication().runWriteAction(new Runnable() {
            @Override
            public void run() {
              if (myRequirementsTxt != null) {
                if (myRequirementsTxt.isWritable()) {
                  myRequirementsTxt.insertString(0, myPackageName + "\n");
                }
              }
              else {
                final PyElementGenerator generator = PyElementGenerator.getInstance(project);
                if (myInstallRequires != null) {
                  if (myInstallRequires.getContainingFile().isWritable()) {
                    final PyStringLiteralExpression literal = generator.createStringLiteralFromString(myPackageName);
                    myInstallRequires.add(literal);
                  }
                }
                else if (mySetupArgumentList != null) {
                  final PyKeywordArgument installRequiresArg = generateInstallRequiresKwarg(generator);
                  if (installRequiresArg != null) {
                    mySetupArgumentList.addArgument(installRequiresArg);
                  }
                }
              }
            }

            @Nullable
            private PyKeywordArgument generateInstallRequiresKwarg(PyElementGenerator generator) {
              final String text = String.format("foo(install_requires=[\"%s\"])", myPackageName);
              final PyExpression generated = generator.createExpressionFromText(myLanguageLevel, text);
              PyKeywordArgument installRequiresArg = null;
              if (generated instanceof PyCallExpression) {
                final PyCallExpression foo = (PyCallExpression)generated;
                for (PyExpression arg : foo.getArguments()) {
                  if (arg instanceof PyKeywordArgument) {
                    final PyKeywordArgument kwarg = (PyKeywordArgument)arg;
                    if ("install_requires".equals(kwarg.getKeyword())) {
                      installRequiresArg = kwarg;
                    }
                  }
                }
              }
              return installRequiresArg;
            }
          });
        }
      }, getName(), null);
    }
  }
}
