/*
Copyright 2013 National Technology & Engineering Solutions of Sandia, LLC (NTESS).
Under the terms of Contract DE-NA0003525 with NTESS,
the U.S. Government retains certain rights in this software.
*/

package gov.sandia.n2a.language.operator;

import gov.sandia.n2a.eqset.Variable;
import gov.sandia.n2a.language.Constant;
import gov.sandia.n2a.language.Operator;
import gov.sandia.n2a.language.OperatorBinary;
import gov.sandia.n2a.language.Type;
import gov.sandia.n2a.language.type.Instance;
import gov.sandia.n2a.language.type.Scalar;

public class OR extends OperatorBinary
{
    public static Factory factory ()
    {
        return new Factory ()
        {
            public String name ()
            {
                return "||";
            }

            public Operator createInstance ()
            {
                return new OR ();
            }
        };
    }

    public int precedence ()
    {
        return 9;
    }

    public Operator simplify (Variable from)
    {
        Operator result = super.simplify (from);
        if (result != this) return result;

        if (operand0 instanceof Constant)
        {
            Type c0 = ((Constant) operand0).value;
            if (c0 instanceof Scalar)
            {
                double value = ((Scalar) c0).value;
                if (value == 0) return operand1;
                else            return new Constant (new Scalar (1));
            }
        }
        else if (operand1 instanceof Constant)
        {
            Type c1 = ((Constant) operand1).value;
            if (c1 instanceof Scalar)
            {
                double value = ((Scalar) c1).value;
                if (value == 0) return operand0;
                else            return new Constant (new Scalar (1));
            }
        }
        return this;
    }

    public Type eval (Instance context)
    {
        return operand0.eval (context).OR (operand1.eval (context));
    }

    public String toString ()
    {
        return "||";
    }
}
