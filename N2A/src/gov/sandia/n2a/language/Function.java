/*
Copyright 2013-2020 National Technology & Engineering Solutions of Sandia, LLC (NTESS).
Under the terms of Contract DE-NA0003525 with NTESS,
the U.S. Government retains certain rights in this software.
*/

package gov.sandia.n2a.language;

import gov.sandia.n2a.eqset.EquationSet.ExponentContext;
import gov.sandia.n2a.eqset.Variable;
import gov.sandia.n2a.language.parse.ASTList;
import gov.sandia.n2a.language.parse.SimpleNode;
import gov.sandia.n2a.language.type.Scalar;
import tech.units.indriya.AbstractUnit;

public class Function extends Operator
{
    public Operator[] operands;

    public void getOperandsFrom (SimpleNode node) throws Exception
    {
        if (node.jjtGetNumChildren () == 0)
        {
            operands = new Operator[0];
            return;
        }
        if (node.jjtGetNumChildren () != 1) throw new Error ("AST for function has unexpected form");
        Object o = node.jjtGetChild (0);
        if (! (o instanceof ASTList)) throw new Error ("AST for function has unexpected form");
        ASTList l = (ASTList) o;
        int count = l.jjtGetNumChildren ();
        operands = new Operator[count];
        for (int i = 0; i < count; i++)
        {
            operands[i] = Operator.getFrom ((SimpleNode) l.jjtGetChild (i));
            operands[i].parent = this;
        }
    }

    public Operator deepCopy ()
    {
        Function result = null;
        try
        {
            result = (Function) this.clone ();
            Operator[] newOperands = new Operator[operands.length];
            for (int i = 0; i < operands.length; i++)
            {
                newOperands[i] = operands[i].deepCopy ();
                newOperands[i].parent = result;
            }
            result.operands = newOperands;
        }
        catch (CloneNotSupportedException e)
        {
        }
        return result;
    }

    public boolean isOutput ()
    {
        for (int i = 0; i < operands.length; i++)
        {
            if (operands[i].isOutput ()) return true;
        }
        return false;
    }

    /**
        Indicates that when all parameters of this function are constant, its value can be replaced by a constant at compile time.
        Most basic arithmetic functions fall in this category. Random number generators, inputs and outputs do not.
    **/
    public boolean canBeConstant ()
    {
        return true;
    }

    /**
        Indicates that when all parameters of this function are known during the init cycle and do not change after that,
        this function only needs to be evaluated once.
    **/
    public boolean canBeInitOnly ()
    {
        return canBeConstant ();
    }

    public void visit (Visitor visitor)
    {
        if (! visitor.visit (this)) return;
        for (int i = 0; i < operands.length; i++) operands[i].visit (visitor);
    }

    public Operator transform (Transformer transformer)
    {
        Operator result = transformer.transform (this);
        if (result != null) return result;
        for (int i = 0; i < operands.length; i++) operands[i] = operands[i].transform (transformer);
        return this;
    }

    public Operator simplify (Variable from, boolean evalOnly)
    {
        boolean allConstant = true;
        for (int i = 0; i < operands.length; i++)
        {
            operands[i] = operands[i].simplify (from, evalOnly);
            if (! (operands[i] instanceof Constant)) allConstant = false;
        }
        if (allConstant  &&  canBeConstant ())  // This function can be replaced by a constant.
        {
            from.changed = true;
            Operator result = new Constant (eval (null));  // A function should report canBeConstant() true only if null is safe to pass here.
            result.parent = parent;
            return result;
        }
        return this;
    }

    /**
        Finds the average exponent and center of our inputs, based on the assumption
        that our output naturally matches our inputs.
    **/
    public void determineExponent (ExponentContext context)
    {
        int cent  = 0;
        int pow   = 0;
        int count = 0;
        for (int i = 0; i < operands.length; i++)
        {
            Operator op = operands[i];
            op.determineExponent (context);
            if (op.exponent != UNKNOWN)
            {
                cent += op.center;
                pow  += op.exponent;
                count++;
            }
        }
        if (count > 0)
        {
            cent /= count;
            pow  /= count;
            updateExponent (context, pow, cent);
        }
    }

    /**
        Passes our required output exponent on to the inputs, and assumes that
        that we will naturally output the same exponent as our inputs.
    **/
    public void determineExponentNext ()
    {
        exponent = exponentNext;  // Assumes that we simply output the same exponent as our inputs.
        for (int i = 0; i < operands.length; i++)
        {
            Operator op = operands[i];
            op.exponentNext = exponentNext;  // Passes the required exponent down to operands.
            op.determineExponentNext ();
        }
    }

    public void dumpExponents (String pad)
    {
        super.dumpExponents (pad);
        for (int i = 0; i < operands.length; i++) operands[i].dumpExponents (pad + "  ");
    }

    public void determineUnit (boolean fatal) throws Exception
    {
        unit = null;
        for (int i = 0; i < operands.length; i++)
        {
            Operator op = operands[i];
            op.determineUnit (fatal);
            if (op.unit != null)
            {
                if (unit == null  ||  unit.isCompatible (AbstractUnit.ONE))
                {
                    unit = op.unit;
                }
                else if (fatal  &&  ! op.unit.isCompatible (AbstractUnit.ONE)  &&  ! op.unit.isCompatible (unit))
                {
                    throw new Exception (toString () + "(" + unit + " versus " + op.unit + ")");
                }
            }
        }
    }

    public void render (Renderer renderer)
    {
        if (renderer.render (this)) return;
        renderer.result.append (toString () + "(");
        for (int a = 0; a < operands.length; a++)
        {
            operands[a].render (renderer);
            if (a < operands.length - 1) renderer.result.append (", ");
        }
        renderer.result.append (")");
    }

    public Type getType ()
    {
        if (operands == null  ||  operands.length == 0) return new Scalar ();
        return operands[0].getType ();
    }

    public boolean equals (Object that)
    {
        if (! (that instanceof Function)) return false;
        Function f = (Function) that;

        if (operands.length != f.operands.length) return false;
        for (int i = 0; i < operands.length; i++)
        {
            if (! operands[i].equals (f.operands[i])) return false;
        }

        return true;
    }
}
