package ocean.plugin.run;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.CommandLineState;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessTerminatedListener;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.roots.OrderEnumerator;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import ocean.plugin.settings.OceanSettingsState;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

public class OceanRunProfileState extends CommandLineState {
    private final OceanRunConfiguration myConfiguration;

    public OceanRunProfileState(ExecutionEnvironment environment, OceanRunConfiguration configuration) {
        super(environment);
        this.myConfiguration = configuration;
        addConsoleFilters(new OceanConsoleFilter(configuration.getProject()));
    }

    @Override
    protected @NotNull ProcessHandler startProcess() throws ExecutionException {
        String oceanFilePath = myConfiguration.getOceanFilePath();
        if (oceanFilePath == null || oceanFilePath.isEmpty()) {
            throw new ExecutionException("Ocean file path is not specified");
        }

        File file = new File(oceanFilePath);
        if (!file.exists()) {
            throw new ExecutionException("Ocean file does not exist: " + oceanFilePath);
        }

        Project project = myConfiguration.getProject();
        String projectPath = project.getBasePath();

        String oceanHome = OceanSettingsState.getInstance().oceanHome;
        String oceanExecutable = resolveOceanExecutable(oceanHome);

        GeneralCommandLine commandLine = new GeneralCommandLine();
        commandLine.setExePath(oceanExecutable);
        commandLine.addParameter(file.getAbsolutePath());

        // Lean execution: only collect and pass classpath if the file has external Java dependencies
        if (needsClasspath(file)) {
            Set<String> uniquePaths = collectClasspath(project, file);
            if (!uniquePaths.isEmpty()) {
                String cpString = String.join(File.pathSeparator, uniquePaths);
                if (cpString.length() < 500) {
                    commandLine.addParameter("-cp");
                    commandLine.addParameter(cpString);
                } else {
                    // Prevent "The command line is too long" via temporary argfile and environment variable
                    try {
                        File tempFile = File.createTempFile("ocean_cp_", ".txt");
                        Files.writeString(tempFile.toPath(), cpString, StandardCharsets.UTF_8);
                        tempFile.deleteOnExit();
                        commandLine.addParameter("-cp");
                        commandLine.addParameter("@" + tempFile.getAbsolutePath());
                        commandLine.withEnvironment("OCEAN_CLASSPATH", cpString);
                    } catch (Exception e) {
                        commandLine.withEnvironment("OCEAN_CLASSPATH", cpString);
                    }
                }
            }
        }

        commandLine.setWorkDirectory(projectPath);

        OSProcessHandler processHandler = new OSProcessHandler(commandLine);
        ProcessTerminatedListener.attach(processHandler);
        return processHandler;
    }

    public static boolean needsClasspath(File file) {
        if (file == null || !file.exists()) return false;
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("import ")) {
                    String imported = trimmed.substring(7).replace(";", "").trim();
                    if (imported.startsWith("static ")) {
                        imported = imported.substring(7).trim();
                    }
                    if (imported.startsWith("ocean.") || imported.startsWith("java.lang.")) {
                        continue;
                    }
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    public static Set<String> collectClasspath(Project project, File file) {
        Set<String> uniquePaths = new LinkedHashSet<>();
        if (project == null) return uniquePaths;

        VirtualFile vFile = VfsUtil.findFileByIoFile(file, true);
        Module targetModule = (vFile != null) ? ModuleUtilCore.findModuleForFile(vFile, project) : null;

        if (targetModule != null) {
            String cp = OrderEnumerator.orderEntries(targetModule).withoutSdk().recursively().classes().getPathsList().getPathsString();
            if (!cp.isEmpty()) {
                uniquePaths.addAll(Arrays.asList(cp.split(File.pathSeparator)));
            }
        } else {
            for (Module m : ModuleManager.getInstance(project).getModules()) {
                String cp = OrderEnumerator.orderEntries(m).withoutSdk().recursively().classes().getPathsList().getPathsString();
                if (!cp.isEmpty()) {
                    uniquePaths.addAll(Arrays.asList(cp.split(File.pathSeparator)));
                }
            }
        }
        return uniquePaths;
    }

    public static String resolveOceanExecutable(String oceanHome) {
        if (oceanHome == null || oceanHome.trim().isEmpty()) {
            oceanHome = System.getenv("OCEAN_HOME");
        }
        return resolveOceanExecutable(oceanHome, findOSName());
    }

    public static String resolveOceanExecutable(String oceanHome, String osName) {
        boolean isWindows = osName != null && osName.toLowerCase(java.util.Locale.ROOT).contains("windows");
        if (oceanHome != null && !oceanHome.trim().isEmpty()) {
            File home = new File(oceanHome);
            String[] candidates = isWindows
                    ? new String[] {"ocean.bat", "ocean.cmd", "ocean.exe", "ocean", "bin/ocean.bat", "bin/ocean.cmd", "bin/ocean.exe", "bin/ocean"}
                    : new String[] {"ocean", "bin/ocean"};
            for (String c : candidates) {
                File f = new File(home, c);
                if (f.isFile()) return f.getAbsolutePath();
            }
        }
        return isWindows ? "ocean.bat" : "ocean";
    }

    public static String findOSName(){
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
    }
}
