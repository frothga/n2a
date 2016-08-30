/*
Copyright 2013 Sandia Corporation.
Under the terms of Contract DE-AC04-94AL85000 with Sandia Corporation,
the U.S. Government retains certain rights in this software.
Distributed under the BSD-3 license. See the file LICENSE for details.
*/

package gov.sandia.n2a.eqset;

import gov.sandia.n2a.language.Operator;
import gov.sandia.n2a.language.Renderer;
import gov.sandia.n2a.language.Visitor;
import gov.sandia.umf.platform.db.MNode;

public class EquationEntry implements Comparable<EquationEntry>
{
    public Variable variable;    // Our container
    public String   ifString;    // only for sorting. TODO: get rid of ifString. Instead, convert conditional to a canonical form with well-defined sort order. This will enable us to combine logically equivalent conditions, as well prioritize more restrictive conditions.
    public Operator expression;
    public Operator conditional;

    /**
        @param variable This equation must be explicitly added to variable.equations 
    **/
    public EquationEntry (Variable variable, String ifString)
    {
        this.variable = variable;
        this.ifString = ifString;
    }

    /**
        Construct the equation for a sub-node of a variable, that is, an equation
        that is part of a multiconditional statement.
        We can safely assume that the variable already exists, and that the caller
        will add us to it.
        Note that the formatting of a multiconditional statement is different from
        a single line, in that the condition itself serves as the index. The @
        symbol is included in the stored index (to allow commingling with $metadata
        and $reference).
    **/
    public EquationEntry (MNode source) throws Exception
    {
        expression  = Operator.parse (source.get ());
        String key = source.key ().substring (1);  // The key should always begin with @
        if (key.isEmpty ())
        {
            ifString = "";
        }
        else
        {
            conditional = Operator.parse (key);
            ifString = conditional.render ();
        }
    }

    /**
        Parses the right-hand side of an equation and converts it into an EquationEntry.
        The caller is responsible for adding the equation object to the correct variable.
    **/
    public EquationEntry (String rhs) throws Exception
    {
        String[] parts = rhs.split ("@");
        expression = Operator.parse (parts[0]);
        ifString = "";
        if (parts.length > 1)
        {
            conditional = Operator.parse (parts[1]);
            ifString = conditional.render ();
        }
    }

    public void visit (Visitor visitor)
    {
        if (expression  != null) expression .visit (visitor);
        if (conditional != null) conditional.visit (visitor);
    }

    public void render (Renderer renderer)
    {
        if (expression  != null)
        {
            expression.render (renderer);
        }
        if (conditional != null)
        {
            renderer.result.append (" @ ");
            conditional.render (renderer);
        }
    }

    @Override
    public String toString ()
    {
        Renderer renderer = new Renderer ();
        render (renderer);
        return renderer.result.toString ();
    }

    public int compareTo (EquationEntry that)
    {
        if (ifString.equals (that.ifString)) return  0;
        if (     ifString.isEmpty ())        return  1;
        if (that.ifString.isEmpty ())        return -1;
        if (     ifString.equals ("$init"))  return  1;
        if (that.ifString.equals ("$init"))  return -1;
        return that.ifString.length () - ifString.length ();  // as a heuristic, sort longer ifStrings first
    }

    @Override
    public boolean equals (Object that)
    {
        if (this == that) return true;
        EquationEntry e = (EquationEntry) that;
        if (e == null) return false;
        return compareTo (e) == 0;
    }
}
