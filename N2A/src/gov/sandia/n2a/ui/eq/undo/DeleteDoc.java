/*
Copyright 2016,2017 National Technology & Engineering Solutions of Sandia, LLC (NTESS).
Under the terms of Contract DE-NA0003525 with NTESS,
the U.S. Government retains certain rights in this software.
*/

package gov.sandia.n2a.ui.eq.undo;

import javax.swing.undo.UndoableEdit;

import gov.sandia.n2a.db.MDoc;
import gov.sandia.n2a.db.MVolatile;
import gov.sandia.n2a.ui.Undoable;
import gov.sandia.n2a.ui.eq.PanelModel;

public class DeleteDoc extends Undoable
{
    protected MVolatile saved;
    protected boolean   neutralized;
    protected int       index;  ///< Position of this doc in the search panel before it was deleted.
    protected boolean   fromSearchPanel;
    protected boolean   wasShowing;

    public DeleteDoc (MDoc doc)
    {
        saved = new MVolatile (doc.key (), "");
        saved.merge (doc);  // in-memory copy of the entire document

        PanelModel mep = PanelModel.instance;
        fromSearchPanel = mep.panelSearch.list.isFocusOwner ();
        index           = mep.panelSearch.indexOf (doc);
        wasShowing      = mep.panelEquations.record == doc;
    }

    public void undo ()
    {
        super.undo ();
        AddDoc.create (saved.key (), saved, index, fromSearchPanel, wasShowing);
    }

    public void redo ()
    {
        super.redo ();
        AddDoc.destroy (saved.key (), fromSearchPanel);
    }

    public boolean replaceEdit (UndoableEdit edit)
    {
        if (edit instanceof AddDoc  &&  saved.key ().equals (((AddDoc) edit).name))
        {
            neutralized = true;
            return true;
        }
        return false;
    }

    public boolean anihilate ()
    {
        return neutralized;
    }
}
