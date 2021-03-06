/*
Copyright 2017-2020 National Technology & Engineering Solutions of Sandia, LLC (NTESS).
Under the terms of Contract DE-NA0003525 with NTESS,
the U.S. Government retains certain rights in this software.
*/

package gov.sandia.n2a.ui.eq.undo;

import java.util.List;

import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.UndoableEdit;

import gov.sandia.n2a.eqset.MPart;
import gov.sandia.n2a.ui.eq.FilteredTreeModel;
import gov.sandia.n2a.ui.eq.PanelEquationTree;
import gov.sandia.n2a.ui.eq.tree.NodeBase;
import gov.sandia.n2a.ui.eq.tree.NodeEquation;
import gov.sandia.n2a.ui.eq.tree.NodeVariable;

public class ChangeEquation extends UndoableView
{
    protected List<String> path;
    protected String       nameBefore;
    protected String       combinerBefore;
    protected String       valueBefore;
    protected String       nameAfter;
    protected String       combinerAfter;
    protected String       valueAfter;
    protected List<String> replacePath;
    protected boolean      multi;
    protected boolean      multiLast;

    /**
        @param variable The direct container of the node being changed.
    **/
    public ChangeEquation (NodeVariable variable, String nameBefore, String combinerBefore, String valueBefore, String nameAfter, String combinerAfter, String valueAfter)
    {
        path = variable.getKeyPath ();

        this.nameBefore     = "@" + nameBefore;
        this.valueBefore    = valueBefore;
        this.combinerBefore = combinerBefore;
        this.nameAfter      = "@" + nameAfter;
        this.valueAfter     = valueAfter;
        this.combinerAfter  = combinerAfter;
    }

    public ChangeEquation (NodeVariable variable, String nameBefore, String combinerBefore, String valueBefore, String nameAfter, String combinerAfter, String valueAfter, List<String> replacePath)
    {
        this (variable, nameBefore, combinerBefore, valueBefore, nameAfter, combinerAfter, valueAfter);
        this.replacePath = replacePath;
    }

    public void setMulti (boolean value)
    {
        multi = value;
    }

    public void setMultiLast (boolean value)
    {
        multiLast = value;
    }

    public void undo ()
    {
        super.undo ();
        apply (nameAfter, nameBefore, combinerBefore, valueBefore, multi, multiLast);
    }

    public void redo ()
    {
        super.redo ();
        apply (nameBefore, nameAfter, combinerAfter, valueAfter, multi, multiLast);
    }

    public void apply (String nameBefore, String nameAfter, String combinerAfter, String valueAfter, boolean multi, boolean multiLast)
    {
        NodeBase parent = NodeBase.locateNode (path);
        if (parent == null) throw new CannotRedoException ();
        NodeBase nodeBefore = parent.child (nameBefore);
        if (nodeBefore == null) throw new CannotRedoException ();

        PanelEquationTree pet = parent.getTree ();
        FilteredTreeModel model = (FilteredTreeModel) pet.tree.getModel ();

        NodeBase nodeAfter;
        if (nameBefore.equals (nameAfter))
        {
            nodeAfter = nodeBefore;
            nodeAfter.source.set (valueAfter);
        }
        else
        {
            // Update the database
            MPart mparent = parent.source;
            MPart newPart = (MPart) mparent.set (valueAfter, nameAfter);
            mparent.clear (nameBefore);
            MPart oldPart = (MPart) mparent.child (nameBefore);

            // Update GUI
            nodeAfter = parent.child (nameAfter);
            if (oldPart == null)
            {
                if (nodeAfter == null)
                {
                    nodeAfter = nodeBefore;
                    nodeAfter.source = newPart;
                }
                else
                {
                    model.removeNodeFromParent (nodeBefore);
                }
            }
            else
            {
                if (nodeAfter == null)
                {
                    int index = parent.getIndex (nodeBefore);
                    nodeAfter = new NodeEquation (newPart);
                    model.insertNodeIntoUnfiltered (nodeAfter, parent, index);
                }
                if (nodeBefore.visible ()) model.nodeChanged (nodeBefore);
                else                       parent.hide (nodeBefore, model);
            }
        }

        if (! parent.source.get ().equals (combinerAfter))
        {
            parent.source.set (combinerAfter);
            parent.setUserObject ();
            NodeBase grandparent = (NodeBase) parent.getParent ();
            grandparent.invalidateColumns (model);
        }

        nodeAfter.setUserObject ();
        parent.invalidateColumns (null);
        TreeNode[] afterPath = nodeAfter.getPath ();
        boolean killed = valueAfter.isEmpty ();
        boolean setSelection;
        if (killed) setSelection =  ! multi  ||  multiLast;  // Revoke, which hides the node, so like delete.
        else        setSelection =  ! multi;
        pet.updateVisibility (afterPath, -2, setSelection);
        if (multi  &&  ! killed) pet.tree.addSelectionPath (new TreePath (afterPath));
        parent.allNodesChanged (model);
        pet.animate ();
    }

    public boolean replaceEdit (UndoableEdit edit)
    {
        if (edit instanceof AddVariable)
        {
            AddVariable av = (AddVariable) edit;
            if (! av.nameIsGenerated) return false;
            return av.fullPath ().equals (replacePath);
        }

        return false;
    }
}
