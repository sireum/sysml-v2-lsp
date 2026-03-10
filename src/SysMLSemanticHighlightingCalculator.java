import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.xtext.AbstractRule;
import org.eclipse.xtext.EnumLiteralDeclaration;
import org.eclipse.xtext.Keyword;
import org.eclipse.xtext.RuleCall;
import org.eclipse.xtext.TerminalRule;
import org.eclipse.xtext.ide.editor.syntaxcoloring.IHighlightedPositionAcceptor;
import org.eclipse.xtext.ide.editor.syntaxcoloring.ISemanticHighlightingCalculator;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.ILeafNode;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.util.CancelIndicator;
import org.omg.sysml.lang.sysml.*;

import java.util.List;

/**
 * Semantic highlighting calculator for SysML v2 / KerML.
 *
 * Provides LSP semantic tokens for:
 * - Keywords, operators
 * - Comments, strings, numbers
 * - Definition names (as "type")
 * - Usage names (as "variable")
 * - Package names (as "namespace")
 * - Enumeration definition names (as "enum")
 * - Port definition names (as "interface")
 * - Action/calculation definition names (as "function")
 * - Prefix metadata (as "decorator")
 */
public class SysMLSemanticHighlightingCalculator implements ISemanticHighlightingCalculator {

    @Override
    public void provideHighlightingFor(
            XtextResource resource,
            IHighlightedPositionAcceptor acceptor,
            CancelIndicator cancelIndicator) {

        if (resource == null || resource.getParseResult() == null) {
            return;
        }

        ICompositeNode rootNode = resource.getParseResult().getRootNode();

        // Walk parse tree for syntactic tokens
        for (INode node : rootNode.getAsTreeIterable()) {
            if (cancelIndicator.isCanceled()) return;
            if (!(node instanceof ILeafNode leaf)) continue;

            EObject grammarElement = node.getGrammarElement();

            if (grammarElement instanceof Keyword kw) {
                highlightKeyword(acceptor, leaf, kw);
            } else if (grammarElement instanceof EnumLiteralDeclaration) {
                // Enum keywords: private, public, protected, in, out, inout, etc.
                acceptor.addPosition(leaf.getOffset(), leaf.getLength(), "keyword");
            } else if (grammarElement instanceof RuleCall
                    && !leaf.isHidden()
                    && leaf.getSemanticElement() instanceof Relationship
                    && !(leaf.getSemanticElement() instanceof Feature)) {
                // Cross-references: type refs, feature refs, imports, redefinitions, etc.
                highlightCrossReference(acceptor, leaf);
            } else {
                highlightTerminal(acceptor, leaf, grammarElement);
            }
        }

        // Walk semantic model for element names
        EObject root = resource.getParseResult().getRootASTElement();
        if (root == null) return;

        highlightElementName(root, acceptor);
        TreeIterator<EObject> allContents = root.eAllContents();
        while (allContents.hasNext()) {
            if (cancelIndicator.isCanceled()) return;
            EObject obj = allContents.next();
            highlightElementName(obj, acceptor);
        }
    }

    private void highlightKeyword(
            IHighlightedPositionAcceptor acceptor, ILeafNode node, Keyword kw) {
        String value = kw.getValue();
        if (value == null || value.isEmpty()) return;

        char first = value.charAt(0);
        if (Character.isLetter(first) || first == '_') {
            acceptor.addPosition(node.getOffset(), node.getLength(), "keyword");
        } else if (isOperator(value)) {
            acceptor.addPosition(node.getOffset(), node.getLength(), "operator");
        }
        // Skip punctuation: { } ; , ( ) [ ]
    }

    private void highlightTerminal(
            IHighlightedPositionAcceptor acceptor, ILeafNode node, EObject grammarElement) {
        String terminalName = getTerminalRuleName(grammarElement);
        if (terminalName == null) return;

        switch (terminalName) {
            case "ML_NOTE":
            case "SL_NOTE":
                acceptor.addPosition(node.getOffset(), node.getLength(), "comment");
                break;
            case "STRING_VALUE":
                acceptor.addPosition(node.getOffset(), node.getLength(), "string");
                break;
            case "DECIMAL_VALUE":
            case "EXP_VALUE":
                acceptor.addPosition(node.getOffset(), node.getLength(), "number");
                break;
        }
    }

    private String getTerminalRuleName(EObject grammarElement) {
        if (grammarElement instanceof RuleCall rc) {
            AbstractRule rule = rc.getRule();
            if (rule instanceof TerminalRule) {
                return rule.getName();
            }
        } else if (grammarElement instanceof TerminalRule tr) {
            return tr.getName();
        }
        return null;
    }

    private void highlightCrossReference(IHighlightedPositionAcceptor acceptor, ILeafNode leaf) {
        EObject semanticElement = leaf.getSemanticElement();
        if (semanticElement instanceof FeatureTyping
                || semanticElement instanceof Subclassification) {
            acceptor.addPosition(leaf.getOffset(), leaf.getLength(), "type");
        } else if (semanticElement instanceof NamespaceImport) {
            acceptor.addPosition(leaf.getOffset(), leaf.getLength(), "namespace");
        } else if (semanticElement instanceof Membership
                && !(semanticElement instanceof Redefinition)
                && !(semanticElement instanceof Subsetting)
                && !(semanticElement instanceof FeatureChaining)) {
            // Value references: enum values, units, etc.
            acceptor.addPosition(leaf.getOffset(), leaf.getLength(), "enumMember");
        } else {
            // Feature references: redefinitions, subsettings, chainings
            Element target = resolveTarget(semanticElement);
            if (target != null) {
                String tokenType = getTokenTypeForElement(target);
                if (tokenType != null) {
                    acceptor.addPosition(leaf.getOffset(), leaf.getLength(), tokenType);
                    return;
                }
            }
            acceptor.addPosition(leaf.getOffset(), leaf.getLength(), "property");
        }
    }

    private Element resolveTarget(EObject rel) {
        if (rel instanceof Redefinition r) {
            return r.getRedefinedFeature();
        } else if (rel instanceof Subsetting s) {
            return s.getSubsettedFeature();
        } else if (rel instanceof FeatureChaining fc) {
            return fc.getChainingFeature();
        } else if (rel instanceof Membership m) {
            return m.getMemberElement();
        }
        return null;
    }

    private void highlightElementName(EObject obj, IHighlightedPositionAcceptor acceptor) {
        String tokenType;
        if (obj instanceof org.omg.sysml.lang.sysml.Package) {
            tokenType = "namespace";
        } else if (obj instanceof Definition) {
            tokenType = "class";
        } else if (obj instanceof Usage) {
            tokenType = "decorator";
        } else {
            return;
        }

        highlightFeature(acceptor, obj, SysMLPackage.Literals.ELEMENT__DECLARED_NAME, tokenType);
        highlightFeature(acceptor, obj, SysMLPackage.Literals.ELEMENT__DECLARED_SHORT_NAME, tokenType);
    }

    private void highlightFeature(IHighlightedPositionAcceptor acceptor,
            EObject obj, org.eclipse.emf.ecore.EStructuralFeature feature, String tokenType) {
        List<INode> nodes = NodeModelUtils.findNodesForFeature(obj, feature);
        for (INode node : nodes) {
            acceptor.addPosition(node.getOffset(), node.getLength(), tokenType);
        }
    }

    private String getTokenTypeForElement(EObject obj) {
        if (obj instanceof org.omg.sysml.lang.sysml.Package) {
            return "namespace";
        }
        // All definitions use "class"
        if (obj instanceof Definition) {
            return "class";
        }
        // Usages: different token types per kind
        if (obj instanceof ConnectionUsage) {
            return "event";
        }
        if (obj instanceof PortUsage || obj instanceof InterfaceUsage) {
            return "interface";
        }
        if (obj instanceof PartUsage) {
            return "variable";
        }
        if (obj instanceof ActionUsage || obj instanceof CalculationUsage) {
            return "method";
        }
        if (obj instanceof EnumerationUsage) {
            return "enumMember";
        }
        if (obj instanceof AttributeUsage) {
            return "property";
        }
        // Other usages (ReferenceUsage, ItemUsage, AllocationUsage, etc.)
        if (obj instanceof Usage) {
            return "macro";
        }
        return null;
    }

    private static boolean isOperator(String value) {
        return switch (value) {
            case ":", ":>", ":>>", ":>=", "=", "==", "!=", "===", "!==",
                 "<", ">", "<=", ">=", "+", "-", "*", "/", "%", "**",
                 "^", "~", "|", "&", "||", "&&", "!", "..", "->", ">>",
                 "@", "@@", "#", "?", "??" -> true;
            default -> false;
        };
    }
}
