package ocean.plugin.run;

import com.intellij.execution.filters.Filter;
import com.intellij.execution.filters.OpenFileHyperlinkInfo;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OceanConsoleFilter implements Filter {
    private static final Pattern ERROR_PATTERN = Pattern.compile("(?:(?:\\[ERROR\\]|\\[WARNING\\]|[a-zA-Z]+:)?\\s*)?(.*?\\.ocean):(\\d+):(\\d+)");
    private final Project myProject;

    public OceanConsoleFilter(Project project) {
        myProject = project;
    }

    @Nullable
    @Override
    public Result applyFilter(@NotNull String line, int entireLength) {
        Matcher matcher = ERROR_PATTERN.matcher(line);
        if (matcher.find()) {
            String fileName = matcher.group(1);
            int lineNumber = Integer.parseInt(matcher.group(2)) - 1;
            int columnNumber = Integer.parseInt(matcher.group(3));

            // Try to find the file in the project
            VirtualFile virtualFile = findFile(fileName);
            if (virtualFile != null) {
                int start = entireLength - line.length() + matcher.start(1);
                int end = entireLength - line.length() + matcher.end(3);
                return new Result(start, end, new OpenFileHyperlinkInfo(myProject, virtualFile, lineNumber, columnNumber));
            }
        }
        return null;
    }

    private VirtualFile findFile(String fileName) {
        // First try as absolute path
        File file = new File(fileName);
        if (file.isAbsolute()) {
            return LocalFileSystem.getInstance().findFileByIoFile(file);
        }
        
        // Then search in project
        String projectPath = myProject.getBasePath();
        if (projectPath != null) {
            File projectFile = new File(projectPath, fileName);
            if (projectFile.exists()) {
                return LocalFileSystem.getInstance().findFileByIoFile(projectFile);
            }
            
            File srcFile = new File(projectPath + File.separator + "src", fileName);
            if (srcFile.exists()) {
                return LocalFileSystem.getInstance().findFileByIoFile(srcFile);
            }
        }
        return null;
    }
}
