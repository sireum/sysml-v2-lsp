import org.eclipse.emf.ecore.EOperation;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.omg.sysml.delegate.invocation.OperationInvocationDelegateFactory;
import org.omg.sysml.delegate.setting.DerivedPropertySettingDelegateFactory;
import org.omg.sysml.lang.sysml.SysMLPackage;
import org.omg.sysml.lang.types.TypesPackage;

/**
 * Custom server launcher for the SysML v2 LSP server that performs
 * the necessary EMF EPackage registrations before starting the
 * standard Xtext LSP server.
 *
 * In Eclipse, these registrations happen via plugin.xml extension points.
 * In standalone/headless mode (like an LSP server JAR), they must be
 * done programmatically before the Xtext infrastructure tries to
 * resolve EMF model references.
 */
public class SysMLServerLauncher {

    public static void main(String[] args) {
        // Register the SysML EPackage (https://www.omg.org/spec/SysML/20250201)
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

        // Delegate to the standard Xtext LSP ServerLauncher
        org.eclipse.xtext.ide.server.ServerLauncher.main(args);
    }
}
