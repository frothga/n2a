/*
Copyright 2013 Sandia Corporation.
Under the terms of Contract DE-AC04-94AL85000 with Sandia Corporation,
the U.S. Government retains certain rights in this software.
Distributed under the BSD-3 license. See the file LICENSE for details.
*/


package gov.sandia.n2a.ui.eq.tree;

import gov.sandia.n2a.eqset.MPart;
import gov.sandia.umf.platform.ui.images.ImageUtil;

import javax.swing.ImageIcon;
import javax.swing.JTree;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;

public class NodeReference extends NodeBase
{
    protected static ImageIcon icon = ImageUtil.getImage ("book.gif");

    public NodeReference (MPart source)
    {
        this.source = source;
        setUserObject ();
    }

    public void setUserObject ()
    {
        setUserObject (source.key () + "--" + source.get ());
    }

    @Override
    public void prepareRenderer (DefaultTreeCellRenderer renderer, boolean selected, boolean expanded, boolean hasFocus)
    {
        renderer.setIcon (icon);
        setFont (renderer, false, false);
    }

    @Override
    public NodeBase add (String type, JTree tree)
    {
        NodeBase parent = (NodeBase) getParent ();
        if (type.isEmpty ()) return parent.add ("Reference", tree);  // By context, we assume the user wants to add another reference.
        else                 return parent.add (type, tree);
    }

    @Override
    public void applyEdit (JTree tree)
    {
        String input = (String) getUserObject ();
        String[] parts = input.split ("=", 2);
        String name = parts[0];
        String value;
        if (parts.length > 1) value = parts[1];
        else                  value = "";

        NodeBase existingReference = null;
        String oldKey = source.key ();
        NodeBase parent = (NodeBase) getParent ();
        if (! name.equals (oldKey)) existingReference = parent.child (name);

        DefaultTreeModel model = (DefaultTreeModel) tree.getModel ();
        if (name.equals (oldKey))  // Name is the same
        {
            source.set (value);
        }
        else if (name.isEmpty ()  ||  existingReference != null)  // Name change is forbidden 
        {
            source.set (value);
            setUserObject ();
            model.nodeChanged (this);
        }
        else  // Name is changed
        {
            MPart p = source.getParent ();
            MPart newPart = (MPart) p.set (value, name);
            p.clear (oldKey);
            if (p.child (oldKey) == null) source = newPart;  // We were not associated with an override, so we can re-use this tree node.
            else model.insertNodeInto (new NodeReference (newPart), parent, parent.getChildCount ());  // Make a new tree node, and leave this one to present the non-overridden value.
        }
    }

    @Override
    public void delete (JTree tree)
    {
        if (! source.isFromTopDocument ()) return;

        MPart mparent = source.getParent ();
        String key = source.key ();
        mparent.clear (key);  // If this merely clears an override, then our source object retains its identity.
        if (mparent.child (key) == null)  // but we do need to test if it is still in the tree
        {
            NodeBase parent = (NodeBase) getParent ();
            ((DefaultTreeModel) tree.getModel ()).removeNodeFromParent (this);
            if (parent.getChildCount () == 0) parent.delete (tree);
        }
        else
        {
            setUserObject ();
        }
    }
}
