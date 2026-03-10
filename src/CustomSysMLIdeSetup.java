import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.eclipse.xtext.ide.editor.syntaxcoloring.ISemanticHighlightingCalculator;
import org.eclipse.xtext.util.Modules2;
import org.omg.sysml.xtext.SysMLRuntimeModule;
import org.omg.sysml.xtext.ide.SysMLIdeModule;
import org.omg.sysml.xtext.ide.SysMLIdeSetup;

/**
 * Custom SysML IDE setup that adds semantic highlighting support.
 */
public class CustomSysMLIdeSetup extends SysMLIdeSetup {

    @Override
    public Injector createInjector() {
        return Guice.createInjector(Modules2.mixin(
                new SysMLRuntimeModule(),
                new SysMLIdeModule(),
                new AbstractModule() {
                    @Override
                    protected void configure() {
                        bind(ISemanticHighlightingCalculator.class)
                                .to(SysMLSemanticHighlightingCalculator.class);
                    }
                }));
    }
}
