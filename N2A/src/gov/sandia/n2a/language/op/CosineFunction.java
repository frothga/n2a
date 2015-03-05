/*
Copyright 2013 Sandia Corporation.
Under the terms of Contract DE-AC04-94AL85000 with Sandia Corporation,
the U.S. Government retains certain rights in this software.
Distributed under the BSD-3 license. See the file LICENSE for details.
*/

package gov.sandia.n2a.language.op;

public class CosineFunction extends Function {
    @Override
    public String getName() {
        return "cos";
    }

    @Override
    public String getDescription() {
        return "cosine";
    }

    @Override
    public ParameterSet[] getAllowedParameterSets() {
        return new ParameterSet[] {
            new ParameterSet(
                "!RET", "val",
                Number.class, Number.class)
        };
    }

    @Override
    protected Object eval(Object[] args, int parameterSetIndex) {
        return Math.cos(((Number) args[0]).doubleValue());
    }
}