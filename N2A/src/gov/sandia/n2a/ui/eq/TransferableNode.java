/*
Copyright 2019-2020 National Technology & Engineering Solutions of Sandia, LLC (NTESS).
Under the terms of Contract DE-NA0003525 with NTESS,
the U.S. Government retains certain rights in this software.
*/

package gov.sandia.n2a.ui.eq;

import gov.sandia.n2a.ui.eq.tree.NodeBase;

import java.awt.Component;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.ClipboardOwner;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;

@SuppressWarnings("deprecation")
public class TransferableNode implements Transferable, ClipboardOwner
{
    public String         data;
    public List<NodeBase> sources;
    public boolean        drag;
    public String         newPartName; // If set non-null by the receiver (nasty hack), then this transfer resulted in the creation of a new part.
    public Component      panel;       // The panel instance that originated the drag. Used to defend against self-drop.
    public int            modifiers;   // If DnD, then record of modifiers from event that started the drag.
    public List<String>   selection;   // The selection path in search panel where the drag started. Only non-null if drag is actually from search panel.

    /**
        A data flavor that lets PanelSearch extract a TransferableNode instance for the purpose of adding info to it for our local exportDone().
        This is necessary because Swing packs the Transferable into a proxy object which is sewn shut.
    **/
    public static final DataFlavor nodeFlavor = new DataFlavor (TransferableNode.class, null);

    public TransferableNode (String data, List<NodeBase> sources, boolean drag, String newPartName)
    {
        this.data        = data;
        this.sources     = sources;
        this.drag        = drag;
        this.newPartName = newPartName;
    }

    @Override
    public void lostOwnership (Clipboard clipboard, Transferable contents)
    {
    }

    @Override
    public DataFlavor[] getTransferDataFlavors ()
    {
        DataFlavor[] result = new DataFlavor[3];
        result[0] = DataFlavor.stringFlavor;
        result[1] = DataFlavor.plainTextFlavor;
        result[2] = nodeFlavor;
        return result;
    }

    @Override
    public boolean isDataFlavorSupported (DataFlavor flavor)
    {
        if (flavor.equals (DataFlavor.stringFlavor   )) return true;
        if (flavor.equals (DataFlavor.plainTextFlavor)) return true;
        if (flavor.equals (nodeFlavor                )) return true;
        return false;
    }

    @Override
    public Object getTransferData (DataFlavor flavor) throws UnsupportedFlavorException, IOException
    {
        if (flavor.equals (DataFlavor.stringFlavor   )) return data;
        if (flavor.equals (DataFlavor.plainTextFlavor)) return new StringReader (data);
        if (flavor.equals (nodeFlavor                )) return this;
        throw new UnsupportedFlavorException (flavor);
    }
}
