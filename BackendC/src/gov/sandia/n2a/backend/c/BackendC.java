/*
Copyright 2013,2016 Sandia Corporation.
Under the terms of Contract DE-AC04-94AL85000 with Sandia Corporation,
the U.S. Government retains certain rights in this software.
Distributed under the BSD-3 license. See the file LICENSE for details.
*/

package gov.sandia.n2a.backend.c;

import gov.sandia.n2a.backend.internal.InternalBackend;
import gov.sandia.n2a.eqset.EquationSet;
import gov.sandia.umf.platform.db.MNode;
import gov.sandia.umf.platform.plugins.extpoints.Backend;
import gov.sandia.umf.platform.ui.ensemble.domains.Parameter;
import gov.sandia.umf.platform.ui.ensemble.domains.ParameterDomain;

public class BackendC extends Backend
{
    @Override
    public String getName ()
    {
        return "C";
    }

    @Override
    public ParameterDomain getSimulatorParameters ()
    {
        ParameterDomain result = new ParameterDomain ();
        result.addParameter (new Parameter ("duration",     "1.0"  ));  // default is 1 second
        result.addParameter (new Parameter ("c.integrator", "Euler"));  // alt is "RungeKutta"
        return result;
    }

    @Override
    public ParameterDomain getOutputVariables (MNode model)
    {
        try
        {
            if (model == null) return null;
            EquationSet s = new EquationSet (model);
            if (s.name.length () < 1) s.name = "Model";
            s.resolveLHS ();
            return s.getOutputParameters ();
        }
        catch (Exception error)
        {
            return null;
        }
    }

    @Override
    public void execute (MNode job) throws Exception
    {
        new JobC ().execute (job);
    }

    @Override
    public double expectedDuration (MNode job)
    {
        return InternalBackend.getDurationFromP (job);
    }

    @Override
    public double currentSimTime (MNode job)
    {
        return InternalBackend.getSimTimeFromOutput (job);
    }
}
