package ocean.plugin.annotator;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.ExternalAnnotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.colors.CodeInsightColors;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.roots.OrderEnumerator;
import ocean.plugin.inspections.OceanInspectionHolder;
import ocean.plugin.run.OceanRunProfileState;
import ocean.plugin.settings.OceanSettingsState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import java.util.concurrent.TimeUnit;

public class OceanExternalAnnotator extends ExternalAnnotator<PsiFile, List<OceanExternalAnnotator.ErrorInfo>> {

    public static class ErrorInfo {
        public String level;
        public String file;
        public int line;
        public int column;
        public String code;
        public String text;
        public String context;
    }

    @Nullable
    @Override
    public PsiFile collectInformation(@NotNull PsiFile file) {
        return file;
    }

    @Nullable
    @Override
    public List<ErrorInfo> doAnnotate(PsiFile file) {
        if (file == null || file.getProject().isDisposed()) return null;

        Project project = file.getProject();
        String projectPath = project.getBasePath();
        if (projectPath == null) return null;

        VirtualFile vFile = file.getVirtualFile();
        if (vFile == null) return null;

        // Ensure in-memory document changes are saved before compiler runs
        Document doc = PsiDocumentManager.getInstance(project).getDocument(file);
        if (doc != null && FileDocumentManager.getInstance().isDocumentUnsaved(doc)) {
            ApplicationManager.getApplication().invokeAndWait(() -> FileDocumentManager.getInstance().saveDocument(doc));
        }

        String oceanHome = OceanSettingsState.getInstance().oceanHome;
        String oceanExecutable = OceanRunProfileState.resolveOceanExecutable(oceanHome);

        try {
            List<String> command = new ArrayList<>();
            command.add(oceanExecutable);
            command.add(vFile.getPath());
            command.add("--json");
            command.add("-c");

            Set<String> uniquePaths = new LinkedHashSet<>();
            for (Module m : ModuleManager.getInstance(project).getModules()) {
                String cp = OrderEnumerator.orderEntries(m).withoutSdk().recursively().classes().getPathsList().getPathsString();
                if (!cp.isEmpty()) {
                    String[] paths = cp.split(File.pathSeparator);
                    uniquePaths.addAll(Arrays.asList(paths));
                }
            }
            if (!uniquePaths.isEmpty()) {
                command.add("-cp");
                command.add(String.join(File.pathSeparator, uniquePaths));
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new File(projectPath));
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = getReportJson(process);

            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }

            String jsonStr = output.toString().trim();
            if (!jsonStr.startsWith("[")) {
                return null;
            }

            Type listType = new TypeToken<ArrayList<ErrorInfo>>() {}.getType();
            return new Gson().fromJson(jsonStr, listType);

        } catch (Exception ignored) {
            return null;
        }
    }

    private static @NotNull StringBuilder getReportJson(Process process) throws IOException {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            boolean jsonStarted = false;
            while ((line = reader.readLine()) != null) {
                if (line.trim().startsWith("[")) jsonStarted = true;
                if (jsonStarted) output.append(line);
                if (line.trim().endsWith("]")) break;
            }
        }
        return output;
    }

    @Override
    public void apply(@NotNull PsiFile file, List<ErrorInfo> annotationResult, @NotNull AnnotationHolder holder) {
        if (annotationResult == null) return;
        if (holder.isBatchMode() && !(holder instanceof OceanInspectionHolder)) {
            return;
        }
        VirtualFile vFile = file.getVirtualFile();
        if (vFile == null) return;

        for (ErrorInfo error : annotationResult) {
            if (error.file != null) {
                String errFile = error.file.replace('\\', '/');
                String currentFile = vFile.getPath().replace('\\', '/');
                if (!errFile.equalsIgnoreCase(currentFile) && !vFile.getName().equalsIgnoreCase(new File(error.file).getName())) {
                    continue;
                }
            }
            
            TextRange range = getRange(file, error.line, error.column);
            HighlightSeverity severity = "ERROR".equalsIgnoreCase(error.level) ? HighlightSeverity.ERROR : HighlightSeverity.WARNING;
            
            String displayMsg = (error.code != null && !error.code.trim().isEmpty())
                    ? "[" + error.code.trim() + "] " + error.text
                    : error.text;

            var builder = holder.newAnnotation(severity, displayMsg).range(range);
            if (severity == HighlightSeverity.ERROR && ("E0009".equalsIgnoreCase(error.code) || (error.text != null && (error.text.toLowerCase().contains("unresolved reference") || error.text.toLowerCase().contains("undefined symbol"))))) {
                builder.textAttributes(CodeInsightColors.WRONG_REFERENCES_ATTRIBUTES);
            }
            builder.create();
        }
    }

    private TextRange getRange(PsiFile file, int line, int col) {
        Document doc = PsiDocumentManager.getInstance(file.getProject()).getDocument(file);
        int textLength = file.getTextLength();
        if (doc == null || line <= 0 || line > doc.getLineCount()) {
            return file.getTextRange();
        }

        int lineStart = doc.getLineStartOffset(line - 1);
        int lineEnd = doc.getLineEndOffset(line - 1);
        int offset = Math.min(lineStart + Math.max(0, col - 1), lineEnd);

        String text = file.getText();
        int end = offset;
        while (end < textLength && Character.isJavaIdentifierPart(text.charAt(end))) {
            end++;
        }
        if (end == offset && offset < textLength) {
            end++;
        }

        return new TextRange(Math.min(offset, textLength), Math.min(Math.max(offset, end), textLength));
    }
}
