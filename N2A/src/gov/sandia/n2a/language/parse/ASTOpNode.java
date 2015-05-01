/*
Copyright 2013 Sandia Corporation.
Under the terms of Contract DE-AC04-94AL85000 with Sandia Corporation,
the U.S. Government retains certain rights in this software.
Distributed under the BSD-3 license. See the file LICENSE for details.
*/

/* Generated By:JJTree: Do not edit this line. ASTOpNode.java Version 4.3 */
/*
 * JavaCCOptions:MULTI=true,NODE_USES_PARSER=false,VISITOR=true,TRACK_TOKENS=false,NODE_PREFIX=AST,
 * NODE_EXTENDS=,NODE_FACTORY=,SUPPORT_CLASS_VISIBILITY_PUBLIC=true
 */

package gov.sandia.n2a.language.parse;

import gov.sandia.n2a.language.EvaluationContext;
import gov.sandia.n2a.language.EvaluationException;
import gov.sandia.n2a.language.Function;
import gov.sandia.n2a.language.Type;


public class ASTOpNode extends ASTNodeBase {


    ////////////////////
    // AUTO-GENERATED //
    ////////////////////

    public ASTOpNode(Object value) {
        super(value, ExpressionParserTreeConstants.JJTOPNODE);
    }

    public ASTOpNode(int id) {
        super(id);
    }

    public ASTOpNode(ExpressionParser p, int id) {
        super(p, id);
    }

    /** Accept the visitor. **/
    @Override
    public Object jjtAccept(ExpressionParserVisitor visitor, Object data) throws ParseException {
        return visitor.visit(this, data);
    }


    ////////////
    // CUSTOM //
    ////////////

    public Function getFunction() {
        return (Function) getValue();
    }

    @Override
    public String toString() {
        return getValue().toString();
    }

    @Override
    public String render(ASTRenderingContext context)
    {
        Object value = getValue ();

        // Long rendering
        if (!context.shortMode)
        {
            String ret = "";
            if (getCount () == 1)
            {
                ret += value.toString();
                ret += context.render (getChild (0));
            }
            else
            {
                if (!(getParent() instanceof ASTStart)) ret += "(";
                ret += context.render (getChild (0));
                ret += " " + value.toString() + " ";
                ret += context.render (getChild (1));
                if (!(getParent() instanceof ASTStart)) ret += ")";
            }
            return ret;
        }

        // Short rendering
        String ret = "";
        if (getCount () == 1)
        {
            ret += value.toString ();
            ret += context.render (getChild (0));
        }
        else
        {
            boolean useParens;
            Function f = (Function) value;

            // Left-hand child
            useParens = false;
            if (getChild (0) instanceof ASTOpNode)
            {
                Function left = (Function) getChild (0).getValue ();
                useParens =    f.precedence < left.precedence   // read "<" as "comes before" rather than "less"
                            ||    f.precedence == left.precedence
                               && f.associativity == Function.Associativity.RIGHT_TO_LEFT;
            }
            if (useParens) ret += "(";
            ret += context.render (getChild (0));
            if (useParens) ret += ")";

            ret += " " + value + " ";

            // Right-hand child
            useParens = false;
            if (getChild (1) instanceof ASTOpNode)
            {
                Function right = (Function) getChild (1).getValue ();
                useParens =    f.precedence < right.precedence
                            ||    f.precedence == right.precedence
                               && f.associativity == Function.Associativity.LEFT_TO_RIGHT;
            }
            if (useParens) ret += "(";
            ret += context.render (getChild (1));
            if (useParens) ret += ")";
        }
        return ret;
    }


    ////////////////
    // EVALUATION //
    ////////////////

    @Override
    public Type eval (EvaluationContext context) throws EvaluationException
    {
        Function func = (Function) getValue ();
        int count = getCount ();
        Type[] params = new Type[count];
        for (int c = 0; c < count; c++) params[c] = getChild (c).eval (context);
        return func.eval (params);
    }
}
/* JavaCC - OriginalChecksum=fdb37a0358564ff530164781a4b1ce67 (do not edit this line) */
