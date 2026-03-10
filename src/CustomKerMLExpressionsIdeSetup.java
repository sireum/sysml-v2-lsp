import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.eclipse.xtext.ide.editor.syntaxcoloring.ISemanticHighlightingCalculator;
import org.eclipse.xtext.util.Modules2;
import org.omg.kerml.expressions.xtext.KerMLExpressionsRuntimeModule;
import org.omg.kerml.expressions.xtext.ide.KerMLExpressionsIdeModule;
import org.omg.kerml.expressions.xtext.ide.KerMLExpressionsIdeSetup;

/**
 * Custom KerML Expressions IDE setup that adds semantic highlighting support.
 */
public class CustomKerMLExpressionsIdeSetup extends KerMLExpressionsIdeSetup {

    @Override
    public Injector createInjector() {
        return Guice.createInjector(Modules2.mixin(
                new KerMLExpressionsRuntimeModule(),
                new KerMLExpressionsIdeModule(),
                new AbstractModule() {
                    @Override
                    protected void configure() {
                        bind(ISemanticHighlightingCalculator.class)
                                .to(SysMLSemanticHighlightingCalculator.class);
                    }
                }));
    }
}
