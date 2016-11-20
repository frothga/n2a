/*
Copyright 2013 Sandia Corporation.
Under the terms of Contract DE-AC04-94AL85000 with Sandia Corporation,
the U.S. Government retains certain rights in this software.
Distributed under the BSD-3 license. See the file LICENSE for details.
*/

package gov.sandia.umf.platform.plugins;

import gov.sandia.n2a.db.MNode;
import gov.sandia.n2a.parms.ParameterSet;
import gov.sandia.umf.platform.ensemble.params.groupset.ParameterSpecGroupSet;
import gov.sandia.umf.platform.runs.Run;
import gov.sandia.umf.platform.runs.RunEnsemble;

import java.util.List;

/**
    @deprecated
**/
public interface PlatformRecord {
    PlatformRecord copy();
    void save();
    void saveRecursive();
    MNode getSource();
    // run ensembles?
    
    /* 
     * 'groups' is for model and simulation parameters that the framework will manage
     * 'simGroups' is for model parameters that the Simulator will manage 
     */
    RunEnsemble addRunEnsemble(String label, String environment,
            String simulator, ParameterSpecGroupSet groups, ParameterSpecGroupSet simGroups, 
            List<String> outputExpressions);
    Run addRun(ParameterSet modelSet, RunEnsemble re);
}
