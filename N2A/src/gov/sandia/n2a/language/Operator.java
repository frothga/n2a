/*
Copyright 2013-2017 National Technology & Engineering Solutions of Sandia, LLC (NTESS).
Under the terms of Contract DE-NA0003525 with NTESS,
the U.S. Government retains certain rights in this software.
*/

package gov.sandia.n2a.language;

import gov.sandia.n2a.eqset.Variable;
import gov.sandia.n2a.language.function.AbsoluteValue;
import gov.sandia.n2a.language.function.Cosine;
import gov.sandia.n2a.language.function.Event;
import gov.sandia.n2a.language.function.Exp;
import gov.sandia.n2a.language.function.Floor;
import gov.sandia.n2a.language.function.Gaussian;
import gov.sandia.n2a.language.function.Grid;
import gov.sandia.n2a.language.function.Input;
import gov.sandia.n2a.language.function.Log;
import gov.sandia.n2a.language.function.Max;
import gov.sandia.n2a.language.function.Min;
import gov.sandia.n2a.language.function.Norm;
import gov.sandia.n2a.language.function.Pulse;
import gov.sandia.n2a.language.function.ReadMatrix;
import gov.sandia.n2a.language.function.Round;
import gov.sandia.n2a.language.function.Signum;
import gov.sandia.n2a.language.function.Sine;
import gov.sandia.n2a.language.function.Tangent;
import gov.sandia.n2a.language.function.Output;
import gov.sandia.n2a.language.function.Uniform;
import gov.sandia.n2a.language.operator.AND;
import gov.sandia.n2a.language.operator.Add;
import gov.sandia.n2a.language.operator.Divide;
import gov.sandia.n2a.language.operator.EQ;
import gov.sandia.n2a.language.operator.GE;
import gov.sandia.n2a.language.operator.GT;
import gov.sandia.n2a.language.operator.LE;
import gov.sandia.n2a.language.operator.LT;
import gov.sandia.n2a.language.operator.Modulo;
import gov.sandia.n2a.language.operator.Multiply;
import gov.sandia.n2a.language.operator.MultiplyElementwise;
import gov.sandia.n2a.language.operator.NE;
import gov.sandia.n2a.language.operator.NOT;
import gov.sandia.n2a.language.operator.Negate;
import gov.sandia.n2a.language.operator.OR;
import gov.sandia.n2a.language.operator.Power;
import gov.sandia.n2a.language.operator.Subtract;
import gov.sandia.n2a.language.operator.Transpose;
import gov.sandia.n2a.language.parse.ASTConstant;
import gov.sandia.n2a.language.parse.ASTIdentifier;
import gov.sandia.n2a.language.parse.ASTList;
import gov.sandia.n2a.language.parse.ASTMatrix;
import gov.sandia.n2a.language.parse.SimpleNode;
import gov.sandia.n2a.language.parse.ASTOperator;
import gov.sandia.n2a.language.parse.ExpressionParser;
import gov.sandia.n2a.language.type.Instance;
import gov.sandia.n2a.plugins.ExtensionPoint;
import gov.sandia.n2a.plugins.PluginManager;

import java.util.List;
import java.util.TreeMap;

public class Operator implements Cloneable, Comparable<Operator>
{
    public interface Factory extends ExtensionPoint
    {
        public String   name ();  ///< Unique string for searching in the table of registered operators. Used explicitly by parser.
        public Operator createInstance ();  ///< Operators may be instantiated with specific operands. The operands must be set separately based on category (Unary, Binary, Function)
    }

    /// Example implementation of function to register Operator
    public static Factory factory ()
    {
        return new Factory ()
        {
            public String name ()
            {
                return "unknown";
            }

            public Operator createInstance ()
            {
                return new Operator ();
            }
        };
    }

    public void getOperandsFrom (SimpleNode node) throws ParseException
    {
    }

    public Operator deepCopy ()
    {
        Operator result = null;
        try
        {
            result = (Operator) this.clone ();
        }
        catch (CloneNotSupportedException e)
        {
        }
        return result;
    }

    public enum Associativity
    {
        LEFT_TO_RIGHT,
        RIGHT_TO_LEFT
    }

    public Associativity associativity ()
    {
        return Associativity.LEFT_TO_RIGHT;
    }

    public int precedence ()
    {
        return 1;
    }

    /// Indicates if we produce some side effect that informs the user.
    public boolean isOutput ()
    {
        return false;
    }

    public void visit (Visitor visitor)
    {
        visitor.visit (this);
    }

    public Operator transform (Transformer transformer)
    {
        Operator result = transformer.transform (this);
        if (result != null) return result;
        return this;
    }

    /**
        Remove operators that have no effect due to specific values of their operands (for example: x*1).
        Replaces constant expressions (including any AccessVariable that points to a Constant) with a single Constant.
        Note: a Transformer could do this work, but a direct implementation is more elegant.
        @param from The Variable that contains the current expression. Used, in conjunction with Variable.visited, to
        prevent infinite recursion. It is safe to pass a value of null, since this terminates recursion check.
    **/
    public Operator simplify (Variable from)
    {
        return this;
    }

    /**
        Remove the dependency of "from" on each variable accessed within the expression tree. 
    **/
    public void releaseDependencies (final Variable from)
    {
        visit (new Visitor ()
        {
            public boolean visit (Operator op)
            {
                if (op instanceof AccessVariable)
                {
                    AccessVariable av = (AccessVariable) op;
                    from.removeDependencyOn (av.reference.variable);
                    return false;
                }
                return true;
            }
        });
    }

    public String render ()
    {
        Renderer renderer = new Renderer ();
        render (renderer);
        return renderer.result.toString ();
    }

    public void render (Renderer renderer)
    {
        if (renderer.render (this)) return;
        renderer.result.append (toString ());
    }

    public Type eval (Instance context) throws EvaluationException
    {
        // If an external simulator provides a function, then its backend must provide the
        // internal simulator with an equivalent capability. Thus, generic functions are
        // not allowed. Every legitimate function must be defined by an extension.
        throw new EvaluationException ("Operator not implemented.");
    }

    public String toString ()
    {
        return "unknown";
    }

    public int compareTo (Operator that)
    {
        Class<? extends Operator> thisClass = getClass ();
        Class<? extends Operator> thatClass = that.getClass ();
        if (thisClass.equals (thatClass)) return 0;
        return thisClass.hashCode () - thatClass.hashCode ();
    }


    // Static interface ------------------------------------------------------

    public static TreeMap<String,Factory> operators = new TreeMap<String,Factory> ();

    public static void register (Factory f)
    {
        operators.put (f.name (), f);
    }

    static
    {
        // Functions
        register (AbsoluteValue.factory ());
        register (Cosine       .factory ());
        register (Event        .factory ());
        register (Exp          .factory ());
        register (Gaussian     .factory ());
        register (Floor        .factory ());
        register (Grid         .factory ());
        register (Input        .factory ());
        register (Log          .factory ());
        register (Max          .factory ());
        register (Min          .factory ());
        register (Norm         .factory ());
        register (Pulse        .factory ());
        register (ReadMatrix   .factory ());
        register (Round        .factory ());
        register (Signum       .factory ());
        register (Sine         .factory ());
        register (Tangent      .factory ());
        register (Output       .factory ());
        register (Uniform      .factory ());

        // Operators
        register (Add                .factory ());
        register (AND                .factory ());
        register (Divide             .factory ());
        register (EQ                 .factory ());
        register (GE                 .factory ());
        register (GT                 .factory ());
        register (LE                 .factory ());
        register (LT                 .factory ());
        register (Modulo             .factory ());
        register (Multiply           .factory ());
        register (MultiplyElementwise.factory ());
        register (NE                 .factory ());
        register (Negate             .factory ());
        register (NOT                .factory ());
        register (OR                 .factory ());
        register (Power              .factory ());
        register (Subtract           .factory ());
        register (Transpose          .factory ());
    }

    public static void initFromPlugins ()
    {
        List<ExtensionPoint> extensions = PluginManager.getExtensionsForPoint (Factory.class);
        for (ExtensionPoint e : extensions) register ((Factory) e);
    }

    public static Operator parse (String line) throws ParseException
    {
        return getFrom (ExpressionParser.parse (line));
    }

    public static Operator getFrom (SimpleNode node) throws ParseException
    {
        Operator result;
        if (node instanceof ASTOperator)
        {
            Factory f = operators.get (node.jjtGetValue ().toString ());
            result = f.createInstance ();
        }
        else if (node instanceof ASTIdentifier)
        {
            String value = node.jjtGetValue ().toString ();
            if (value.endsWith ("()"))
            {
                Factory f = operators.get (value.substring (0, value.length () - 2));
                if (f == null) result = new AccessElement ();  // It's either this or an undefined function. In the second case, variable access will fail.
                else           result = f.createInstance ();
            }
            else
            {
                result = new AccessVariable ();
            }
        }
        else if (node instanceof ASTConstant) result = new Constant ();
        else if (node instanceof ASTMatrix  ) result = new BuildMatrix ();
        else if (node instanceof ASTList)
        {
            if (node.jjtGetNumChildren () == 1) return getFrom ((SimpleNode) node.jjtGetChild (0));
            result = new Split ();  // Lists can exist elsewhere besides a $type split, but they should be processed out by getOperandsFrom(SimpleNode).
        }
        else result = new Operator ();
        result.getOperandsFrom (node);
        return result;
    }
}
