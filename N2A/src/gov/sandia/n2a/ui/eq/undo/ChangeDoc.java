/*
Copyright 2016-2019 National Technology & Engineering Solutions of Sandia, LLC (NTESS).
Under the terms of Contract DE-NA0003525 with NTESS,
the U.S. Government retains certain rights in this software.
*/

package gov.sandia.n2a.ui.eq.undo;

import gov.sandia.n2a.db.AppData;
import gov.sandia.n2a.db.MNode;
import gov.sandia.n2a.ui.Undoable;
import gov.sandia.n2a.ui.eq.PanelEquations;
import gov.sandia.n2a.ui.eq.PanelModel;

public class ChangeDoc extends Undoable
{
    protected String  before;
    protected String  after;
    protected boolean fromSearchPanel;
    protected boolean wasShowing;

    public ChangeDoc (String before, String after)
    {
        this.before = before;
        this.after  = after;

        PanelModel mep = PanelModel.instance;
        fromSearchPanel = mep.panelSearch.list.isFocusOwner ();  // Otherwise, assume focus is on equation tree
        wasShowing      = mep.panelEquations.record != null  &&  mep.panelEquations.record.key ().equals (before);
    }

    public void undo ()
    {
        super.undo ();
        rename (after, before);
    }

    public void redo ()
    {
        super.redo ();
        rename (before, after);
    }

    public boolean anihilate ()
    {
        return before.equals (after);
    }

    public void rename (String A, String B)
    {
        // Update database
        AppData.models.move (A, B);
        MNode doc = AppData.models.child (B);
        String id = doc.get ("$metadata", "id");
        if (! id.isEmpty ()) AppData.set (id, doc);
        if (AppData.state.get ("PanelModel", "lastUsed").equals (A)) AppData.state.set (B, "PanelModel", "lastUsed");

        // Update GUI
        PanelModel pm = PanelModel.instance;
        PanelEquations container = pm.panelEquations;
        if (wasShowing)
        {
            container.load (doc);  // lazy; only loads if not already loaded
            if (container.viewTree)
            {
                container.root.setUserObject ();  // In case it was already loaded, ensure that doc name is updated. 
                container.panelEquationTree.model.nodeChanged (container.root);
                container.panelEquationTree.tree.setSelectionRow (0);
            }
        }
        pm.panelMRU.renamed ();  // Because the change in document name does not directly notify the list model.
        pm.panelSearch.list.repaint ();

        if (fromSearchPanel) pm.panelSearch.list.requestFocusInWindow ();
        else if (wasShowing) container.takeFocus ();
        // else we don't care where focus is
    }
}
