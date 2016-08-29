/*
Copyright 2013 Sandia Corporation.
Under the terms of Contract DE-AC04-94AL85000 with Sandia Corporation,
the U.S. Government retains certain rights in this software.
Distributed under the BSD-3 license. See the file LICENSE for details.
*/

package gov.sandia.umf.platform.ui;

import gov.sandia.umf.platform.ui.ensemble.UIController;
import gov.sandia.umf.platform.ui.images.ImageUtil;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Map;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JPanel;

import replete.gui.controls.IconButton;
import replete.gui.windows.Dialogs;
import replete.util.Lay;

public class HelpLabels
{
    public static Map<String, String[]> popupHelp;

    public static void showHelp (HelpCapableWindow win, String key)
    {
        String[] topicContent = popupHelp.get (key);
        if (topicContent == null) Dialogs.showError("Could not find help for this topic!\n\nSEEK PROFESSIONAL ASSISTANCE INSTEAD", "Your best recourse...");
        else                      win.showHelp (topicContent[0], topicContent[1]);
    }

    public static void setPopupHelp (Map<String, String[]> ph)
    {
        popupHelp = ph;
    }

    public static JPanel createLabelPanel(final HelpCapableWindow win, String text, final String helpKey) {
        return createLabelPanel(win, text, helpKey, null);
    }
    public static JPanel createLabelPanel(final HelpCapableWindow win, String text, final String helpKey, Icon prefix) {

        IconButton btnHelp = new IconButton(ImageUtil.getImage("help3.gif"), "Show Help");
        btnHelp.setFocusable(false);
        btnHelp.toImageOnly();
        btnHelp.addActionListener (new ActionListener ()
        {
            public void actionPerformed (ActionEvent e)
            {
                showHelp (win, helpKey);
            }
        });

        return Lay.BL(
            "W", Lay.BL(
                "C", Lay.lb(text + ":", prefix, "opaque=false"),
                "E", Lay.p(btnHelp, "eb=2l,opaque=false"),
                "opaque=false"
            ),
            "C", Lay.p("opaque=false"),
            "opaque=false"
        );
    }

    public static JButton createHelpIcon(final UIController uiController, final HelpCapableWindow win, final String helpKey) {
        IconButton btnHelp = new IconButton(ImageUtil.getImage("help3.gif"), "Show Help");
        btnHelp.setFocusable(false);
        btnHelp.toImageOnly();
        btnHelp.addActionListener (new ActionListener ()
        {
            public void actionPerformed (ActionEvent e)
            {
                showHelp (win, helpKey);
            }
        });
        return btnHelp;
    }
}
