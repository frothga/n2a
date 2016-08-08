/*
Copyright 2013 Sandia Corporation.
Under the terms of Contract DE-AC04-94AL85000 with Sandia Corporation,
the U.S. Government retains certain rights in this software.
Distributed under the BSD-3 license. See the file LICENSE for details.
*/


package gov.sandia.n2a.ui.eq.tree;

import java.util.Enumeration;
import java.util.TreeMap;

import gov.sandia.n2a.eqset.MPart;
import gov.sandia.n2a.eqset.Variable;
import gov.sandia.n2a.ui.eq.EquationTreePanel;
import gov.sandia.umf.platform.db.MNode;
import gov.sandia.umf.platform.ui.images.ImageUtil;

import javax.swing.ImageIcon;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

public class NodeVariable extends NodeBase
{
    protected static ImageIcon iconVariable = ImageUtil.getImage ("delta.png");
    protected static ImageIcon iconBinding  = ImageUtil.getImage ("connect.gif");

    protected boolean isBinding;

    public NodeVariable (MPart source)
    {
        this.source = source;
    }

    public void build (DefaultTreeModel model)
    {
        setUserObject (source.key () + "=" + source.get ());
        removeAllChildren ();

        for (MNode n : source)
        {
            String key = n.key ();
            if (key.startsWith ("@")) model.insertNodeInto (new NodeEquation ((MPart) n), this, getChildCount ());
        }

        MPart metadata = (MPart) source.child ("$metadata");
        if (metadata != null)
        {
            for (MNode m : metadata) model.insertNodeInto (new NodeAnnotation ((MPart) m), this, getChildCount ());
        }

        MPart references = (MPart) source.child ("$reference");
        if (references != null)
        {
            for (MNode r : references) model.insertNodeInto (new NodeReference ((MPart) r), this, getChildCount ());
        }
    }

    /**
        Examines a fully-built tree to determine the value of the isBinding member.
    **/
    public void findConnections ()
    {
        isBinding = false;

        NodePart parent = (NodePart) getParent ();
        String value = source.get ().trim ();
        if (value.contains ("$connect"))
        {
            isBinding = true;
        }
        else
        {
            // Determine if our LHS has the right form.
            String name = source.key ().trim ();
            if (name.endsWith ("'")) return;

            // Determine if our RHS has the right form. If so, scan for the referent.
            if (value.matches ("[a-zA-Z_$][a-zA-Z0-9_$.]*"))
            {
                NodeBase referent = parent.resolveName (value);
                if (referent instanceof NodePart) isBinding = true;
            }
        }

        if (isBinding) parent.isConnection = true;
    }

    @Override
    public void prepareRenderer (DefaultTreeCellRenderer renderer, boolean selected, boolean expanded, boolean hasFocus)
    {
        if (isBinding) renderer.setIcon (iconBinding);
        else           renderer.setIcon (iconVariable);
        setFont (renderer, false, false);
        // TODO: set color based on override status
    }

    @Override
    public NodeBase add (String type, EquationTreePanel panel)
    {
        if (type.isEmpty ())
        {
            if (getChildCount () == 0  ||  panel.tree.isCollapsed (new TreePath (getPath ()))) return ((NodeBase) getParent ()).add ("Variable", panel);
            type = "Equation";
        }

        NodeBase result;
        if (type.equals ("Equation"))
        {
            TreeMap<String,MNode> equations = new TreeMap<String,MNode> ();
            for (MNode n : source)
            {
                String key = n.key ();
                if (key.startsWith ("@")) equations.put (key.substring (1), n);
            }

            // The minimum number of equations is 2. There should never be exactly 1 equation, because that is single-line form, which should have no child equations at all.
            if (equations.size () == 0)  // We are about to switch from single-line form to multi-conditional, so make a tree node for the existing equation.
            {
                Variable.ParsedValue pieces = new Variable.ParsedValue (source.get ());
                source.set (pieces.combiner);
                setUserObject (source.key () + "=" + pieces.combiner);
                MPart equation = (MPart) source.set (pieces.expression, "@" + pieces.conditional);
                equations.put (pieces.conditional, equation);
                panel.model.insertNodeInto (new NodeEquation (equation), this, 0);
            }

            int suffix = equations.size ();
            String conditional;
            while (true)
            {
                conditional = String.valueOf (suffix);
                if (equations.get (conditional) == null) break;
                suffix++;
            }
            MPart equation = (MPart) source.set (conditional, "@" + conditional);
            result = new NodeEquation (equation);
            result.setUserObject ("");
            panel.model.insertNodeInto (result, this, 0);
        }
        else if (type.equals ("Annotation"))
        {
            // Determine index at which to insert new annotation
            int firstReference = 0;
            while (firstReference < getChildCount ()  &&  ! (getChildAt (firstReference) instanceof NodeReference)) firstReference++;

            // Determine a unique key for the annotation
            MPart metadata = (MPart) source.childOrCreate ("$metadata");
            int suffix = 1;
            while (metadata.child ("a" + suffix) != null) suffix++;

            result = new NodeAnnotation ((MPart) metadata.set ("", "a" + suffix));
            result.setUserObject ("");
            panel.model.insertNodeInto (result, this, firstReference);
        }
        else if (type.equals ("Reference"))
        {
            MPart references = (MPart) source.childOrCreate ("$reference");
            int suffix = 1;
            while (references.child ("r" + suffix) != null) suffix++;

            result = new NodeReference ((MPart) references.set ("", "r" + suffix));
            result.setUserObject ("");
            panel.model.insertNodeInto (result, this, getChildCount ());
        }
        else
        {
            return ((NodeBase) getParent ()).add (type, panel);  // refer all other requests up the tree
        }
        return result;
    }

    @Override
    public void applyEdit (DefaultTreeModel model)
    {
        String input = (String) getUserObject ();
        String[] parts = input.split ("=", 2);
        String name = parts[0];
        String value;
        if (parts.length > 1) value = parts[1];
        else                  value = "";

        NodeBase existingVariable = null;
        String oldKey = source.key ();
        NodeBase parent = (NodeBase) getParent ();
        if (! name.equals (oldKey)) existingVariable = parent.child (name);

        if (name.equals (oldKey)  ||  existingVariable != null)  // No name change, or name change not permitted.
        {
            // Update ourselves. Exact action depends on whether we are single-line or multi-conditional.
            TreeMap<String,NodeEquation> equations = new TreeMap<String,NodeEquation> ();
            Enumeration i = children ();
            while (i.hasMoreElements ())
            {
                Object o = i.nextElement ();
                if (o instanceof NodeEquation)
                {
                    NodeEquation e = (NodeEquation) o;
                    equations.put (e.source.key ().substring (1), e);
                }
            }

            if (equations.size () == 0)
            {
                source.set (value);
            }
            else
            {
                Variable.ParsedValue pieces = new Variable.ParsedValue (value);
                source.set (pieces.combiner);

                NodeEquation e = equations.get (pieces.conditional);
                if (e == null)  // no matching equation
                {
                    MPart equation = (MPart) source.set (pieces.expression, "@" + pieces.conditional);
                    model.insertNodeInto (new NodeEquation (equation), this, 0);
                }
                else  // conditional matched an existing equation, so just replace the expression
                {
                    e.source.set (pieces.expression);
                    e.setUserObject (pieces.expression + e.source.key ());  // key starts with "@"
                    model.nodeChanged (e);
                }
            }

            if (equations.size () > 0  ||  existingVariable != null)  // Necessary to change displayed value
            {
                setUserObject (oldKey + "=" + source.get ());
                model.nodeChanged (this);
            }
        }
        else  // The name was changed. Move the whole tree under us to a new location. This may also expose an overridden variable.
        {
            // Inject the changed equation into the underlying data first, then move and rebuild the displayed nodes as necessary.
            TreeMap<String,MNode> equations = new TreeMap<String,MNode> ();
            for (MNode n : source)
            {
                String key = n.key ();
                if (key.startsWith ("@")) equations.put (key.substring (1), n);
            }
            if (equations.size () == 0)
            {
                source.set (value);
            }
            else
            {
                Variable.ParsedValue pieces = new Variable.ParsedValue (value);
                source.set (pieces.combiner);

                MNode e = equations.get (pieces.conditional);
                if (e == null) source.set (pieces.expression, "@" + pieces.conditional);
                else                e.set (pieces.expression);
            }

            // Change ourselves into the new key=value pair
            MPart p = source.getParent ();
            p.move (oldKey, name);
            MPart newPart = (MPart) p.child (name);
            if (p.child (oldKey) == null)
            {
                // We were not associated with an override, so we can re-use this tree node.
                source = newPart;
            }
            else
            {
                // Make a new tree node, and leave this one to present the non-overridden value.
                // Note that our source is still set to the old part.
                NodeVariable v = new NodeVariable (newPart);
                model.insertNodeInto (v, parent, parent.getIndex (this));
                v.build (model);
            }
            build (model);
        }
    }
}