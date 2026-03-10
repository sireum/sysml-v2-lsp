import java.io.File;
import java.net.URI;
import java.util.Set;

import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.xtext.ide.server.MultiRootWorkspaceConfigFactory;
import org.eclipse.xtext.workspace.FileProjectConfig;
import org.eclipse.xtext.workspace.WorkspaceConfig;

/**
 * Custom workspace config factory that adds the SysML standard library
 * and any sibling domain libraries (e.g., aadl.library, hamr.aadl.library)
 * as additional source folders to each workspace project.
 *
 * This allows library types to be resolved from user files without
 * requiring symlinks or copies of the libraries in each workspace folder.
 *
 * Discovery: given --library /path/to/sysml.library, the factory also
 * scans the parent directory for sibling *.library directories and adds
 * them as source folders.
 *
 * Workspace override: if a workspace folder already contains a *.library
 * directory (e.g., aadl.library), the workspace copy is used instead of
 * the bundled one so that existing projects are not broken.
 */
public class SysMLWorkspaceConfigFactory extends MultiRootWorkspaceConfigFactory {

    @Override
    protected void addProjectsForWorkspaceFolder(
            WorkspaceConfig workspaceConfig,
            WorkspaceFolder folder,
            Set<String> projectNames) {
        if (folder == null || folder.getUri() == null) return;

        FileProjectConfig project = new FileProjectConfig(
                getUriExtensions().toUri(folder.getUri()),
                getUniqueProjectName(folder.getName(), projectNames));
        project.addSourceFolder(".");

        // Add the standard library and any sibling *.library directories
        // as additional source folders so that library types are indexed
        // within the same project context and cross-references resolve
        // without needing symlinks.
        String libPath = SysMLServerLauncher.getLibraryPath();
        if (libPath != null) {
            File libDir = new File(libPath);

            // Resolve the workspace folder to a local path for checking overrides
            File wsDir = null;
            try {
                wsDir = new File(new URI(folder.getUri()));
            } catch (Exception ignored) {}

            // Always add the primary sysml.library (never overridden)
            project.addSourceFolder(libDir.getAbsolutePath());

            // Discover sibling *.library directories (e.g., aadl.library)
            File parentDir = libDir.getParentFile();
            if (parentDir != null && parentDir.isDirectory()) {
                File[] siblings = parentDir.listFiles((dir, name) ->
                        name.endsWith(".library") && !name.equals(libDir.getName()));
                if (siblings != null) {
                    for (File sibling : siblings) {
                        if (!sibling.isDirectory()) continue;

                        // Skip if the workspace already contains this library
                        // (use the workspace copy via the "." source folder instead)
                        if (wsDir != null) {
                            File wsOverride = new File(wsDir, sibling.getName());
                            if (wsOverride.isDirectory()) continue;
                        }

                        project.addSourceFolder(sibling.getAbsolutePath());
                    }
                }
            }
        }

        workspaceConfig.addProject(project);
    }
}
