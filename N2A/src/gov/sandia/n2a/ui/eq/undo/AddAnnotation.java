/*
Copyright 2016 Sandia Corporation.
Under the terms of Contract DE-AC04-94AL85000 with Sandia Corporation,
the U.S. Government retains certain rights in this software.
Distributed under the BSD-3 license. See the file LICENSE for details.
*/

package gov.sandia.n2a.ui.eq.undo;

import java.awt.FontMetrics;
import java.util.List;

import javax.swing.JTree;
import javax.swing.tree.TreePath;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoableEdit;

import gov.sandia.n2a.eqset.MPart;
import gov.sandia.n2a.ui.eq.Do;
import gov.sandia.n2a.ui.eq.FilteredTreeModel;
import gov.sandia.n2a.ui.eq.ModelEditPanel;
import gov.sandia.n2a.ui.eq.NodeBase;
import gov.sandia.n2a.ui.eq.tree.NodeAnnotation;
import gov.sandia.n2a.ui.eq.tree.NodeAnnotations;
import gov.sandia.n2a.ui.eq.tree.NodePart;

public class AddAnnotation extends Do
{
    protected List<String> path;  // to parent of $metadata node
    protected int          index; // where to insert among siblings
    protected String       name;
    protected String       value;
    public    NodeBase     createdNode;  ///< Used by caller to initiate editing. Only valid immediately after call to redo().

    /**
        @param parent Must be the node that contains $metadata, not the $metadata node itself.
        @param index Position in the unfiltered tree where the node should be inserted.
    **/
    public AddAnnotation (NodeBase parent, int index)
    {
        path = parent.getKeyPath ();
        this.index = index;

        // Determine unique name
        MPart metadata = (MPart) parent.source.child ("$metadata");
        int suffix = 1;
        if (metadata != null)
        {
            while (metadata.child ("a" + suffix) != null) suffix++;
        }
        name = "a" + suffix;
    }

    public void undo ()
    {
        super.undo ();
        destroy (path, name);
    }

    public static void destroy (List<String> path, String name)
    {
        // Retrieve created node
        NodeBase parent = locateParent (path);
        if (parent == null) throw new CannotUndoException ();
        NodeBase container = parent;
        if (parent instanceof NodePart) container = parent.child ("$metadata");
        NodeBase createdNode = container.child (name);

        ModelEditPanel mep = ModelEditPanel.instance;
        JTree tree = mep.panelEquations.tree;
        FilteredTreeModel model = (FilteredTreeModel) tree.getModel ();
        FontMetrics fm = createdNode.getFontMetrics (tree);

        boolean containerIsVisible = true;
        TreePath containerPath = new TreePath (container.getPath ());
        int filteredIndex = container.getIndexFiltered (createdNode);

        MPart metadata = (MPart) parent.source.child ("$metadata");
        metadata.clear (name);
        if (metadata.child (name) == null)  // There is no overridden value, so this node goes away completely.
        {
            model.removeNodeFromParent (createdNode);
            if (container.getChildCount () == 0  &&  container != parent)  // $metadata block is now empty
            {
                parent.source.clear ("$metadata");
                model.removeNodeFromParent (container);
                // No need to update tabs in grandparent, because $metadata node doesn't participate.
                containerIsVisible = false;
            }
        }
        else  // Just exposed an overridden value, so update display.
        {
            if (container.visible (model.filterLevel))  // We are always visible, but our parent could disappear.
            {
                createdNode.updateColumnWidths (fm);
            }
            else
            {
                ((NodeBase) container.getParent ()).hide (container, model);
                containerIsVisible = false;
            }
        }

        if (containerIsVisible)
        {
            container.updateTabStops (fm);
            container.allNodesChanged (model);
        }
        mep.panelEquations.updateAfterDelete (containerPath, filteredIndex);
    }

    public void redo ()
    {
        super.redo ();
        createdNode = create (path, index, name, value);
    }

    public static NodeBase create (List<String> path, int index, String name, String value)
    {
        NodeBase parent = locateParent (path);
        if (parent == null) throw new CannotRedoException ();
        MPart metadata = (MPart) parent.source.childOrCreate ("$metadata");

        ModelEditPanel mep = ModelEditPanel.instance;
        JTree tree = mep.panelEquations.tree;
        FilteredTreeModel model = (FilteredTreeModel) tree.getModel ();
        NodeBase container = parent;  // If this is a variable, then mix metadata with equations and references
        if (parent instanceof NodePart)  // If this is a part, then display metadata in a special sub-node 
        {
            if (metadata.length () == 0)  // empty implies the node is absent
            {
                container = new NodeAnnotations (metadata);
                model.insertNodeIntoUnfiltered (container, parent, 0);
            }
            else  // the node is present, so retrieve it
            {
                container = parent.child ("$metadata");
            }
        }

        NodeBase createdNode = new NodeAnnotation ((MPart) metadata.set (value, name));
        FontMetrics fm = createdNode.getFontMetrics (tree);
        if (container.getChildCount () > 0)
        {
            NodeBase firstChild = (NodeBase) container.getChildAt (0);
            if (firstChild.needsInitTabs ()) firstChild.initTabs (fm);
        }

        if (value == null) createdNode.setUserObject ("");  // pure create, so about to go into edit mode
        createdNode.updateColumnWidths (fm);  // preempt initialization; uses actual name, not user value
        model.insertNodeIntoUnfiltered (createdNode, container, index);
        if (value != null)  // create merged with change name/value
        {
            container.updateTabStops (fm);
            container.allNodesChanged (model);
            tree.setSelectionPath (new TreePath (createdNode.getPath ()));
            mep.panelEquations.repaintSouth (new TreePath (container.getPath ()));
        }

        return createdNode;
    }

    public boolean addEdit (UndoableEdit edit)
    {
        if (value == null  &&  edit instanceof ChangeAnnotation)
        {
            ChangeAnnotation change = (ChangeAnnotation) edit;
            if (name.equals (change.nameBefore))
            {
                name  = change.nameAfter;
                value = change.valueAfter;
                return true;
            }
        }
        return false;
    }
}
