/*
Copyright 2013,2016 Sandia Corporation.
Under the terms of Contract DE-AC04-94AL85000 with Sandia Corporation,
the U.S. Government retains certain rights in this software.
Distributed under the BSD-3 license. See the file LICENSE for details.
*/

package gov.sandia.n2a.ui.jobs;

import java.awt.Component;

import gov.sandia.n2a.plugins.extpoints.RecordHandler;
import gov.sandia.n2a.ui.images.ImageUtil;

import javax.swing.ImageIcon;

public class RunHandler implements RecordHandler
{
    @Override
    public ImageIcon getIcon ()
    {
        return ImageUtil.getImage ("run.gif");
    }

    @Override
    public String getName ()
    {
        return "Runs";
    }

    @Override
    public Component getPanel ()
    {
        return new RunPanel ();
    }

    @Override
    public Component getInitialFocus (Component panel)
    {
        return ((RunPanel) panel).tree;
    }
}