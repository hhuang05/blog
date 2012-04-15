package blog.absyn;

public class SymbolEvidence extends Evidence {
	public ImplicitSetExpr left;
	public ExplicitSetExpr right;

	public SymbolEvidence(int p, ImplicitSetExpr s, ExplicitSetExpr t) {
		pos = p;
		left = s;
		right = t;
	}

	@Override
	void printTree(Printer pr, int d) {
		pr.indent(d);
		pr.sayln("SymbolEvidence(");
		if (left != null) {
			left.printTree(pr, d + 1);
		}
		if (right != null) {
			pr.sayln(",");
			right.printTree(pr, d + 1);
		}
		pr.say(")");
	}
}
