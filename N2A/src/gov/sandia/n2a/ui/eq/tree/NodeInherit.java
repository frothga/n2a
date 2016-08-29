/*
Copyright 2013 Sandia Corporation.
Under the terms of Contract DE-AC04-94AL85000 with Sandia Corporation,
the U.S. Government retains certain rights in this software.
Distributed under the BSD-3 license. See the file LICENSE for details.
*/


package gov.sandia.n2a.ui.eq.tree;

import gov.sandia.n2a.eqset.MPart;
import gov.sandia.umf.platform.ui.images.ImageUtil;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JTree;
import javax.swing.tree.DefaultTreeModel;

public class NodeInherit extends NodeBase
{
    protected static ImageIcon icon = ImageUtil.getImage ("inherit.png");

    public NodeInherit (MPart source)
    {
        this.source = source;
        setUserObject (source.key () + "=" + source.get ());
    }

    @Override
    public Icon getIcon (boolean expanded)
    {
        return icon;
    }

    @Override
    public void applyEdit (JTree tree)
    {
        String input = (String) getUserObject ();
        if (input.isEmpty ())
        {
            delete (tree);
            return;
        }

        String[] parts = input.split ("=", 2);
        String value;
        if (parts.length > 1) value = parts[1].trim ();
        else                  value = "";

        String oldValue = source.get ();
        if (value.equals (oldValue)) return;

        source.set (value);  // Complex restructuring happens here.
        NodePart parent = (NodePart) getParent ();
        parent.build ();
        ((DefaultTreeModel) tree.getModel ()).nodeStructureChanged (parent);
    }

    @Override
    public void delete (JTree tree)
    {
        if (! source.isFromTopDocument ()) return;

        MPart mparent = source.getParent ();
        mparent.clear ("$inherit");  // Complex restructuring happens here.

        NodePart parent = (NodePart) getParent ();
        parent.build ();
        ((DefaultTreeModel) tree.getModel ()).nodeStructureChanged (parent);
    }
}
