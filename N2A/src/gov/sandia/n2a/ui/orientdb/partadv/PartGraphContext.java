/*
Copyright 2013 Sandia Corporation.
Under the terms of Contract DE-AC04-94AL85000 with Sandia Corporation,
the U.S. Government retains certain rights in this software.
Distributed under the BSD-3 license. See the file LICENSE for details.
*/

package gov.sandia.n2a.ui.orientdb.partadv;

import gov.sandia.umf.platform.connect.orientdb.ui.NDoc;

import java.util.Stack;

import javax.swing.event.ChangeListener;

public interface PartGraphContext {
    public NDoc getCenteredPart();
    public NDoc getDivedPart();
    public void addPartChangeListener(ChangeListener listener);
    public Stack<DiveCenterStep> getSteps();
}
