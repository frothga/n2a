/*
Copyright 2013-2020 National Technology & Engineering Solutions of Sandia, LLC (NTESS).
Under the terms of Contract DE-NA0003525 with NTESS,
the U.S. Government retains certain rights in this software.
*/

package gov.sandia.n2a.language;

/**
    A visitor for Operator which replaces the current node with a rewritten tree.
**/
public interface Transformer
{
    /**
        @return The modified Operator, or null if no action was taken. When null is
        returned, the Operator performs its own default action, which is generally
        to recurse down the tree then return itself. An empty implementation would return null.
    **/
    public Operator transform (Operator op);
}
