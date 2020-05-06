/*
Copyright 2016-2020 National Technology & Engineering Solutions of Sandia, LLC (NTESS).
Under the terms of Contract DE-NA0003525 with NTESS,
the U.S. Government retains certain rights in this software.
*/

package gov.sandia.n2a.ui.eq.tree;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import gov.sandia.n2a.db.MNode;
import gov.sandia.n2a.eqset.MPart;
import gov.sandia.n2a.ui.Undoable;
import gov.sandia.n2a.ui.eq.FilteredTreeModel;
import gov.sandia.n2a.ui.eq.undo.AddAnnotation;
import gov.sandia.n2a.ui.eq.undo.DeleteAnnotations;
import gov.sandia.n2a.ui.images.ImageUtil;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JTree;
import javax.swing.tree.TreePath;

@SuppressWarnings("serial")
public class NodeAnnotations extends NodeContainer
{
    protected static ImageIcon icon = ImageUtil.getImage ("properties.gif");

    public NodeAnnotations (MPart source)
    {
        this.source = source;
        // This is a non-editable node, so we should never access userObject.
    }

    @Override
    public void build ()
    {
        removeAllChildren ();
        for (MNode c : source)
        {
            NodeAnnotation a = new NodeAnnotation ((MPart) c);
            add (a);
            a.build ();
        }
    }

    @Override
    public boolean visible (int filterLevel)
    {
        if (filterLevel <= FilteredTreeModel.ALL)   return true;
        if (filterLevel == FilteredTreeModel.PARAM) return false;
        // FilteredTreeModel.LOCAL ...
        return source.isFromTopDocument ();
    }

    @Override
    public Icon getIcon (boolean expanded)
    {
        if (expanded) return icon;
        return NodeAnnotation.icon;
    }

    @Override
    public List<String> getColumns (boolean selected, boolean expanded)
    {
        List<String> result = new ArrayList<String> (1);
        result.add ("<html><i>$metadata</i></html>");
        return result;
    }

    @Override
    public NodeBase containerFor (String type)
    {
        if (type.equals ("Annotation")) return this;
        return ((NodeBase) parent).containerFor (type);
    }

    @Override
    public Undoable makeAdd (String type, JTree tree, MNode data, Point location)
    {
        if (type.isEmpty ())
        {
            FilteredTreeModel model = (FilteredTreeModel) tree.getModel ();
            if (model.getChildCount (this) == 0  ||  tree.isCollapsed (new TreePath (getPath ()))) type = "Variable";
            else                                                                                   type = "Annotation";
        }

        if (type.equals ("Annotation"))
        {
            // Add a new annotation to our children
            int index = getChildCount () - 1;
            TreePath path = tree.getLeadSelectionPath ();
            if (path != null)
            {
                NodeBase selected = (NodeBase) path.getLastPathComponent ();
                if (isNodeChild (selected)) index = getIndex (selected);  // unfiltered index
            }
            index++;
            return new AddAnnotation (this, index, data);
        }

        return ((NodeBase) parent).makeAdd (type, tree, data, location);
    }

    @Override
    public boolean allowEdit ()
    {
        return false;
    }

    @Override
    public Undoable makeDelete (boolean canceled)
    {
        if (source.isFromTopDocument ()) return new DeleteAnnotations (this);
        return null;
    }
}
