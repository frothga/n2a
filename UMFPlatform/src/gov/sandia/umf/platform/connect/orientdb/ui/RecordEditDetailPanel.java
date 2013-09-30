/*
Copyright 2013 Sandia Corporation.
Under the terms of Contract DE-AC04-94AL85000 with Sandia Corporation,
the U.S. Government retains certain rights in this software.
Distributed under the BSD-3 license. See the file LICENSE for details.
*/

package gov.sandia.umf.platform.connect.orientdb.ui;

import gov.sandia.umf.platform.ui.HelpLabels;
import gov.sandia.umf.platform.ui.MainFrame;
import gov.sandia.umf.platform.ui.UIController;
import gov.sandia.umf.platform.ui.images.ImageUtil;

import java.awt.Color;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.event.ChangeListener;

import replete.event.ChangeNotifier;
import replete.gui.controls.IconButton;
import replete.util.Lay;

public abstract class RecordEditDetailPanel extends JPanel {


    ////////////
    // FIELDS //
    ////////////

    // Const

    protected static final Color DARK_BLUE = RecordEditPanel.DARK_BLUE;
    protected static final String SPC = RecordEditPanel.SPC;

    // Core

    protected UIController uiController;
    protected NDoc record;

    // Every detail panel has a reference to the shared
    // part object and can edit it at will.


    //////////////
    // NOTIFIER //
    //////////////

    protected ChangeNotifier contentNotifier = new ChangeNotifier(this);
    public void addContentChangedListener(ChangeListener listener) {
        contentNotifier.addListener(listener);
    }
    protected void fireContentChangedNotifier() {
        contentNotifier.fireStateChanged();
    }


    /////////////////
    // CONSTRUCTOR //
    /////////////////

    public RecordEditDetailPanel(UIController uic, NDoc rec) {
        uiController = uic;
        record = rec;
    }


    /////////////
    // MUTATOR //
    /////////////

    public void setRecord(NDoc p) {
        record = p;
        reload();
    }


    /////////////////////////
    // ABSTRACT / SKELETON //
    /////////////////////////

    public abstract void reload();
    public void postLayout() {}


    ////////////////////////////
    // CONVENIENCE UI METHODS //
    ////////////////////////////

    // These methods allow all edit panels to have consistent controls.

    protected JPanel createLabelPanel(String text, String helpKey) {
        return HelpLabels.createLabelPanel(uiController, MainFrame.getInstance(), text, helpKey);
    }

    protected JButton createHelpIcon(String helpKey) {
        return HelpLabels.createHelpIcon(uiController, MainFrame.getInstance(), helpKey);
    }

    protected JPanel createCheckboxPanel(JCheckBox chk, final String helpKey) {
        IconButton btnHelp = new IconButton(ImageUtil.getImage("help3.gif"), "Show Help");
        btnHelp.setFocusable(false);
        btnHelp.toImageOnly();
        btnHelp.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                uiController.showHelp(MainFrame.getInstance(), helpKey);
            }
        });
        return Lay.BL(
            "W", Lay.BL(
                "C", chk,
                "E", Lay.p(btnHelp, "eb=2l")
            ),
            "C", Lay.p()
        );
    }

    // TODO: Old SQL
    protected IconButton createButtonOwnerDetails(NDoc record) {
        final String owner = record.getOwner();
//        Query query = Query.create().eq("UserName", owner);
//        List<Profile> profiles = Profile.get(query);
        String img = "user.png";
//        if(profiles.size() == 1 && profiles.get(0).getGender() == ProfileGender.FEMALE) {
//            img = "user_female.png";
//        }
        IconButton btnOwnerDetails = new IconButton(ImageUtil.getImage(img), "Show User Details");
        btnOwnerDetails.setFocusable(false);
        btnOwnerDetails.toImageOnly();
        btnOwnerDetails.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                uiController.openProfileFromUserId(owner);
            }
        });
        return btnOwnerDetails;
    }
}
