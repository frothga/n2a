/*
Copyright 2016 Sandia Corporation.
Under the terms of Contract DE-AC04-94AL85000 with Sandia Corporation,
the U.S. Government retains certain rights in this software.
Distributed under the BSD-3 license. See the file LICENSE for details.
*/

package gov.sandia.n2a.ui.eq.undo;

import java.util.List;

import javax.swing.undo.UndoableEdit;

import gov.sandia.n2a.eqset.MPart;
import gov.sandia.n2a.ui.eq.Do;
import gov.sandia.n2a.ui.eq.NodeBase;
import gov.sandia.n2a.ui.eq.NodeFactory;
import gov.sandia.n2a.ui.eq.tree.NodeReference;
import gov.sandia.n2a.ui.eq.tree.NodeReferences;

public class AddReference extends Do
{
    protected List<String> path;  // to parent of $reference node
    protected int          index; // where to insert among siblings
    protected String       name;
    protected String       value;
    public    NodeBase     createdNode;  ///< Used by caller to initiate editing. Only valid immediately after call to redo().

    /**
        @param parent Must be the node that contains $reference, not the $reference node itself.
        @param index Position in the unfiltered tree where the node should be inserted.
    **/
    public AddReference (NodeBase parent, int index)
    {
        path = parent.getKeyPath ();
        this.index = index;
        name = AddAnnotation.uniqueName (parent, "$reference", "r");
    }

    public void undo ()
    {
        super.undo ();
        AddAnnotation.destroy (path, name, "$reference");
    }

    public void redo ()
    {
        super.redo ();
        NodeFactory factory = new NodeFactory ()
        {
            public NodeBase create (MPart part)
            {
                return new NodeReference (part);
            }
        };
        NodeFactory factoryBlock = new NodeFactory ()
        {
            public NodeBase create (MPart part)
            {
                return new NodeReferences (part);
            }
        };
        createdNode = AddAnnotation.create (path, index, name, value, "$reference", factory, factoryBlock);
    }

    public boolean addEdit (UndoableEdit edit)
    {
        if (value == null  &&  edit instanceof ChangeReference)
        {
            ChangeReference change = (ChangeReference) edit;
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