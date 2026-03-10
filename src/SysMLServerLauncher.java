import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.util.Modules;
import org.eclipse.emf.ecore.EOperation;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.xtext.ide.server.IMultiRootWorkspaceConfigFactory;
import org.eclipse.xtext.ide.server.ServerModule;
import org.omg.sysml.delegate.invocation.OperationInvocationDelegateFactory;
import org.omg.sysml.delegate.setting.DerivedPropertySettingDelegateFactory;
import org.omg.sysml.lang.sysml.SysMLPackage;
import org.omg.sysml.lang.sysml.util.SysMLLibraryUtil;
import org.omg.sysml.lang.types.TypesPackage;

/**
 * Custom server launcher for the SysML v2 LSP server that performs
 * the necessary EMF EPackage registrations and configures the
 * standard library path before starting the standard Xtext LSP server.
 *
 * The library is located by checking (in order):
 * 1. --library &lt;path&gt; command-line argument
 * 2. SYSML_LIBRARY_PATH environment variable
 * 3. sysml.library directory next to the JAR file
 * 4. Libraries embedded inside the JAR (extracted to a temp directory)
 *
 * When a library path is found, it is added as a source folder to each
 * workspace project so that library types resolve without requiring
 * symlinks or copies in each workspace folder.
 */
public class SysMLServerLauncher {

    private static final String EMBEDDED_LIB_PREFIX = "sysml-libraries/";

    private static volatile String libraryPath;
    private static volatile Path extractedLibDir;

    /** Returns the configured sysml.library path, or null if not set. */
    public static String getLibraryPath() {
        return libraryPath;
    }

    public static void main(String[] args) {
        // Register the SysML EPackage
        if (!EPackage.Registry.INSTANCE.containsKey(SysMLPackage.eNS_URI)) {
            EPackage.Registry.INSTANCE.put(SysMLPackage.eNS_URI, SysMLPackage.eINSTANCE);
        }

        // Register the UML PrimitiveTypes EPackage
        if (!EPackage.Registry.INSTANCE.containsKey(TypesPackage.eNS_URI)) {
            EPackage.Registry.INSTANCE.put(TypesPackage.eNS_URI, TypesPackage.eINSTANCE);
        }

        // Register the derived property setting delegate factory
        EStructuralFeature.Internal.SettingDelegate.Factory.Registry.INSTANCE.put(
                DerivedPropertySettingDelegateFactory.SYSML_ANNOTATION,
                new DerivedPropertySettingDelegateFactory());

        // Register the operation invocation delegate factory
        EOperation.Internal.InvocationDelegate.Factory.Registry.INSTANCE.put(
                OperationInvocationDelegateFactory.SYSML_ANNOTATION,
                new OperationInvocationDelegateFactory());

        // Find and set the standard library path
        List<String> filteredArgs = new ArrayList<>();
        String libPath = null;
        for (int i = 0; i < args.length; i++) {
            if ("--library".equals(args[i]) && i + 1 < args.length) {
                libPath = args[++i];
            } else {
                filteredArgs.add(args[i]);
            }
        }
        if (libPath == null) {
            libPath = System.getenv("SYSML_LIBRARY_PATH");
        }
        if (libPath == null) {
            try {
                File jarFile = new File(
                        SysMLServerLauncher.class.getProtectionDomain()
                                .getCodeSource().getLocation().toURI());
                File siblingLib = new File(jarFile.getParentFile(), "sysml.library");
                if (siblingLib.isDirectory()) {
                    libPath = siblingLib.getAbsolutePath();
                }
            } catch (Exception ignored) {}
        }
        // Fallback: extract embedded libraries from the JAR
        if (libPath == null) {
            libPath = extractEmbeddedLibraries();
        }

        if (libPath != null) {
            SysMLLibraryUtil.setModelLibraryDirectory(libPath);
            libraryPath = libPath;
            System.err.println("[SysML LSP] Library path: " + libPath);
        }

        // Use a custom module that overrides the workspace config factory
        // to inject the library as a source folder into each project
        Module module = Modules.override(new ServerModule()).with(new AbstractModule() {
            @Override
            protected void configure() {
                bind(IMultiRootWorkspaceConfigFactory.class)
                        .to(SysMLWorkspaceConfigFactory.class);
            }
        });

        org.eclipse.xtext.ide.server.ServerLauncher.launch(
                SysMLServerLauncher.class.getName(),
                filteredArgs.toArray(new String[0]),
                module);
    }

    /**
     * Extracts the embedded sysml-libraries/ from inside the JAR to a
     * temp directory.  Returns the path to the extracted sysml.library,
     * or null if no embedded libraries are found.
     */
    private static String extractEmbeddedLibraries() {
        try {
            // Check if embedded libraries exist
            if (SysMLServerLauncher.class.getResource("/" + EMBEDDED_LIB_PREFIX + "sysml.library/") == null) {
                return null;
            }

            // Get the JAR file location
            URI jarUri = SysMLServerLauncher.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI();
            Path jarPath = Path.of(jarUri);
            if (!jarPath.toString().endsWith(".jar")) {
                // Running from exploded classes (e.g., during development)
                Path devLib = jarPath.resolve(EMBEDDED_LIB_PREFIX).resolve("sysml.library");
                if (Files.isDirectory(devLib)) {
                    return devLib.toAbsolutePath().toString();
                }
                return null;
            }

            Path tempDir = Files.createTempDirectory("sysml-lsp-libraries-");
            extractedLibDir = tempDir;
            System.err.println("[SysML LSP] Extracting embedded libraries to: " + tempDir);

            // Clean up on JVM shutdown
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    deleteRecursively(tempDir);
                } catch (IOException ignored) {}
            }));

            // Walk the JAR filesystem and extract sysml-libraries/**
            URI fsUri = URI.create("jar:" + jarUri);
            try (FileSystem jarFs = FileSystems.newFileSystem(fsUri, Collections.emptyMap())) {
                Path libRoot = jarFs.getPath("/" + EMBEDDED_LIB_PREFIX);
                if (!Files.isDirectory(libRoot)) {
                    return null;
                }
                Files.walkFileTree(libRoot, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                            throws IOException {
                        Path rel = libRoot.relativize(dir);
                        Files.createDirectories(tempDir.resolve(rel.toString()));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                            throws IOException {
                        Path rel = libRoot.relativize(file);
                        try (InputStream in = Files.newInputStream(file)) {
                            Files.copy(in, tempDir.resolve(rel.toString()),
                                    StandardCopyOption.REPLACE_EXISTING);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }

            Path sysmlLib = tempDir.resolve("sysml.library");
            if (Files.isDirectory(sysmlLib)) {
                return sysmlLib.toAbsolutePath().toString();
            }
        } catch (Exception e) {
            System.err.println("[SysML LSP] Failed to extract embedded libraries: " + e);
        }
        return null;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
