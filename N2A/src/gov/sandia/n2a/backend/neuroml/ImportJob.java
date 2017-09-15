/*
Copyright 2017 National Technology & Engineering Solutions of Sandia, LLC (NTESS).
Under the terms of Contract DE-NA0003525 with NTESS,
the U.S. Government retains certain rights in this software.
*/

package gov.sandia.n2a.backend.neuroml;

import gov.sandia.n2a.db.AppData;
import gov.sandia.n2a.db.MNode;
import gov.sandia.n2a.db.MVolatile;
import gov.sandia.n2a.eqset.MPart;
import gov.sandia.n2a.language.type.Matrix;
import gov.sandia.n2a.language.type.Scalar;
import gov.sandia.n2a.ui.eq.undo.AddDoc;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

public class ImportJob
{
    LinkedList<File>    sources      = new LinkedList<File> ();
    MNode               models       = new MVolatile ();
    String              modelName    = "";
    Set<MNode>          dependents   = new HashSet<MNode> ();        // Nodes which contain a $inherit that refers to a locally defined part rather than a standard part. The local part must either be copied in or converted into a global model.
    Map<String,Node>    morphologies = new HashMap<String,Node> ();  // Map from IDs to top-level morphology blocks.
    Map<String,Node>    biophysics   = new HashMap<String,Node> ();  // Map from IDs to top-level biophysics blocks.
    Map<String,Node>    properties   = new HashMap<String,Node> ();  // Map from IDs to top-level intra- or extra-cellular property blocks.
    Map<String,Cell>    cells        = new HashMap<String,Cell> ();
    Map<String,Network> networks     = new HashMap<String,Network> ();

    Pattern floatParser   = Pattern.compile ("[-+]?(NaN|Infinity|([0-9]*\\.?[0-9]*([eE][-+]?[0-9]+)?))");
    Pattern forbiddenUCUM = Pattern.compile ("[.,;><=!&|+\\-*/%\\^~]");
    static final double epsilon = Math.ulp (1);

    public void process (File source)
    {
        sources.push (source);
        try
        {
            // Open and parse XML document
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance ();
            factory.setCoalescing (true);
            factory.setIgnoringComments (true);
            factory.setIgnoringElementContentWhitespace (true);
            factory.setXIncludeAware (true);  // Doesn't seem to actually include other files, at least on the samples I've tried so far. Must be missing something.
            DocumentBuilder builder = factory.newDocumentBuilder ();
            Document doc = builder.parse (source);

            // Extract models
            process (doc);
        }
        catch (IOException e)
        {
        }
        catch (ParserConfigurationException e)
        {
        }
        catch (SAXException e)
        {
        }
        sources.pop ();
    }

    public void process (Node node)
    {
        if (node.getNodeType () != Node.ELEMENT_NODE)  // For all other node types, recurse into structure, looking for ELEMENT nodes.
        {
            for (Node child = node.getFirstChild (); child != null; child = child.getNextSibling ()) process (child);
            return;
        }

        // Triage the element type
        switch (node.getNodeName ())
        {
            case "morphml":
            case "channelml":
            case "networkml":
            case "neuroml":
            case "Lems":
                neuroml (node);
                break;
        }
    }

    public void neuroml (Node node)
    {
        if (modelName.isEmpty ())
        {
            modelName = getAttribute (node, "id");
            if (modelName.isEmpty ()) modelName = "New Model";
            // Preemptively scan for unique name, so our references to any sibling parts remain valid.
            // IE: a name collision in the main part implies that sibling parts will also have name collisions.
            modelName = AddDoc.uniqueName (modelName);
        }
        MNode model = models.childOrCreate (modelName);

        for (Node child = node.getFirstChild (); child != null; child = child.getNextSibling ())
        {
            if (child.getNodeType () != Node.ELEMENT_NODE) continue;
            switch (child.getNodeName ())
            {
                case "include":
                    // TODO: what if href actually references a web document?
                    File nextSource = new File (sources.getLast ().getParentFile (), getAttribute (child, "href"));
                    process (nextSource);
                    break;

                // NeuroML ---------------------------------------------------
                case "intracellularProperties":
                case "extracellularProperties":
                    properties.put (getAttribute (child, "id"), child);
                    break;
                case "morphology":
                    morphologies.put (getAttribute (child, "id"), child);
                    break;
                case "ionChannel":
                case "ionChannelHH":
                case "ionChannelKS":
                    ionChannel (child);
                    break;
                case "decayingPoolConcentrationModel":
                case "fixedFactorConcentrationModel":
                    MNode part = genericPart (child, model);
                    part.clear ("ion");
                    break;
                case "blockingPlasticSynapse":
                    blockingPlasticSynapse (child);
                    break;
                case "biophysicalProperties":
                    biophysics.put (getAttribute (child, "id"), child);
                    break;
                case "cell":
                    Cell cell = new Cell (child);
                    cells.put (cell.id, cell);
                    break;
                case "spikeArray":
                case "timedSynapticInput":
                    spikeArray (child);
                    break;
                case "network":
                    Network network = new Network (child);
                    networks.put (network.id, network);
                    break;
                default:
                    genericPart (child, model);  // Assume that any top-level element not captured above is an abstract cell type.
                    break;

                // LEMS ------------------------------------------------------
                // Because the default genericPart() above is written for NeuroML, all LEMS tags must be specified, even if we ignore them.
                case "Target":
                    target (child);
                case "Dimension":
                case "Unit":
                    // TODO: generate mapping to UCUM units
                    break;
                case "Constant":
                    new ComponentType (model).genericVariable (child, "");  // Create a bogus component to wrap the top level, so we can add a LEMS constant to it.
                    break;
                case "ComponentType":
                case "Component":
                    new ComponentType (child);
                    break;
            }
        }
    }

    public void addDependency (MNode part, String inherit)
    {
        dependents.add (part);

        MNode component = models.childOrCreate (modelName, inherit);
        int count = component.getInt ("$count");
        component.set ("$count", count + 1);

        if (part.get ().equals (inherit)) component.set ("$connected", "");
    }

    /**
        If not a standard part, then it is probably one defined by this import, so tag the dependency.
    **/
    public void checkDependency (MNode part, String inherit)
    {
        MNode model = AppData.models.child (inherit);
        if (model == null  ||  model.child ("$metadata", "backend.neuroml.part") == null) addDependency (part, inherit);
    }

    public void removeDependency (MNode part, String inherit)
    {
        dependents.remove (part);

        MNode component = models.child (modelName, inherit);
        if (component == null) return;
        int count = component.getInt ("$count") - 1;
        if (count > 0)
        {
            component.set ("$count", count);
        }
        else
        {
            component.clear ("$count");
            component.clear ("$connected");
            if (component.length () == 0) models.clear (modelName, inherit);  // This is a shallow part created because a model is missing from the repo.
        }
    }

    /**
        Perform data manipulations that must wait until all nodes are read.
    **/
    public void postprocess ()
    {
        for (Entry<String,Cell>    e : cells   .entrySet ()) e.getValue ().finish ();
        for (Entry<String,Network> e : networks.entrySet ()) e.getValue ().finish ();

        // Resolve referenced parts
        while (dependents.size () > 0)
        {
            resolve (dependents.iterator ().next ());
        }

        // Move heavy-weight parts into separate models
        Iterator<MNode> it = models.child (modelName).iterator ();
        while (it.hasNext ())
        {
            MNode part = it.next ();
            int count = part.getInt ("$count");
            if (count < 0)
            {
                part.clear ("$count");
                part.clear ("$connected");
                if (count == -3)
                {
                    String partName = part.key ();
                    MNode model = models.childOrCreate (modelName + " " + partName);
                    model.merge (part);
                }
                if (count > -4) it.remove ();
            }
        }

        // Move network items up to top level
        if (networks.size () == 1) networks.entrySet ().iterator ().next ().getValue ().moveUp ();
    }

    public void resolve (MNode dependent)
    {
        dependents.remove (dependent);
        String sourceName = dependent.get ("$inherit").replace ("\"", "");
        if (sourceName.isEmpty ()) sourceName = dependent.get ();  // For connections, the part name might be a direct value.
        MNode source = models.child (modelName, sourceName);
        if (source == null) return;

        resolveChildren (source);  // Ensure that the source part has no unresolved dependencies of its own before using it.
        if (source.length () == 1  ||  (source.length () == 2  &&  source.child ("$connected") != null))  // source contains only special keys, so it is a shallow part, associated with an unresolved external reference.
        {
            source.set ("$inherit", "\"" + source.key () + "\"");
        }

        // Triage
        // -1 == source has exactly one user. Merge and delete immediately.
        // -2 == source is lightweight, but has multiple users. Merge and wait to delete.
        // -3 == source is heavyweight. Keep reference and move out to independent model.
        // -4 == source is the endpoint of a connection. Leave in place.
        int count = source.getInt ("$count");
        boolean connected = source.child ("$connected") != null;
        if (count > 0)  // triage is necessary
        {
            if (count == 1)
            {
                count = -1;
            }
            else  // count > 1, so part could be moved out to its own model
            {
                if (connected)
                {
                    count = -4;
                }
                else
                {
                    // Criterion: If a part has subparts, then it is heavy-weight and should be moved out.
                    // A part that merely sets some parameters on an inherited model is lightweight, and should simply be merged everywhere it is used.
                    boolean heavy = false;
                    for (MNode s : source)
                    {
                        if (MPart.isPart (s))
                        {
                            heavy = true;
                            break;
                        }
                    }
                    if (heavy) count = -3;
                    else       count = -2;
                }
            }
            source.set ("$count", count);
        }
        if (count == -1  ||  count == -2)
        {
            dependent.set ("");
            dependent.clear ("$inherit");
            for (MNode n : source)
            {
                String key = n.key ();
                if (key.equals ("$count"    )) continue;
                if (key.equals ("$connected")) continue;
                if (key.equals ("$inherit"))
                {
                    dependent.set (key, n.get ());
                }
                else
                {
                    MNode c = dependent.child (key);
                    if (c == null) dependent.set (key, n);
                    else           c.mergeUnder (n);
                }
            }
            if (count == -1) models.clear (modelName, sourceName);
        }
        else if (count == -3)
        {
            dependent.set ("$inherit", "\"" + modelName + " " + sourceName + "\"");
        }
    }

    public void resolveChildren (MNode source)
    {
        for (MNode child : source) resolveChildren (child);
        if (dependents.contains (source)) resolve (source);
    }

    public void ionChannel (Node node)
    {
        String id      = getAttribute (node, "id");
        String type    = getAttribute (node, "type");
        String species = getAttribute (node, "species");
        String inherit;
        if (type.isEmpty ()) inherit = node.getNodeName ();
        else                 inherit = type;
        MNode part = models.childOrCreate (modelName, id);  // Expect to always create this part rather than fetch an existing child.
        part.set ("$inherit", "\"" + inherit + "\"");
        checkDependency (part, inherit);
        if (! species.isEmpty ()) part.set ("$metadata", "species", species);

        NamedNodeMap attributes = node.getAttributes ();
        int count = attributes.getLength ();
        for (int i = 0; i < count; i++)
        {
            Node a = attributes.item (i);
            String name = a.getNodeName ();
            if (name.equals ("id")) continue;
            if (name.equals ("type")) continue;
            if (name.equals ("species")) continue;
            part.set (name, biophysicalUnits (a.getNodeValue ()));  // biophysicalUnits() will only modify text if there is a numeric value
        }

        for (Node child = node.getFirstChild (); child != null; child = child.getNextSibling ())
        {
            if (child.getNodeType () != Node.ELEMENT_NODE) continue;
            String name = child.getNodeName ();
            if      (name.startsWith ("q10"   )) q10ConductanceScaling (child, part);
            else if (name.equals     ("gateKS")) gateKS                (child, part);
            else if (name.startsWith ("gate"  )) gate                  (child, part);
            else                                 genericPart           (child, part);
        }
    }

    public void q10ConductanceScaling (Node node, MNode part)
    {
        for (Node child = node.getFirstChild (); child != null; child = child.getNextSibling ())
        {
            if (child.getNodeType () == Node.ELEMENT_NODE) part.set (child.getNodeName (), child.getNodeValue ());
        }
    }

    public void gateKS (Node node, MNode container)
    {
        String id = getAttribute (node, "id");
        MNode part = container.set (id, "");
        part.set ("$inherit", "\"gateKS\"");

        NamedNodeMap attributes = node.getAttributes ();
        int count = attributes.getLength ();
        for (int i = 0; i < count; i++)
        {
            Node a = attributes.item (i);
            String name = a.getNodeName ();
            if (name.equals ("id")) continue;
            part.set (name, biophysicalUnits (a.getNodeValue ()));
        }

        int openState   =  1;
        int closedState = -1;
        for (Node child = node.getFirstChild (); child != null; child = child.getNextSibling ())
        {
            if (child.getNodeType () != Node.ELEMENT_NODE) continue;
            String name = child.getNodeName ();
            if      (name.equals   ("openState"  )) part.set (getAttribute (child, "id"), openState++);
            else if (name.equals   ("closedState")) part.set (getAttribute (child, "id"), closedState--);
            else if (name.equals   ("notes"      )) part.set ("$metadata", "notes", getText (child));
            else if (name.endsWith ("Transition" )) transition (child, part);
            else                                    rate       (child, part);
        }
    }

    public void transition (Node node, MNode container)
    {
        String id = getAttribute (node, "id");
        MNode part = container.set (id, "");
        part.set ("$inherit", "\"" + node.getNodeName () + "\"");

        NamedNodeMap attributes = node.getAttributes ();
        int count = attributes.getLength ();
        for (int i = 0; i < count; i++)
        {
            Node a = attributes.item (i);
            String name = a.getNodeName ();
            if (name.equals ("id")) continue;
            part.set (name, a.getNodeValue ());
        }

        for (Node child = node.getFirstChild (); child != null; child = child.getNextSibling ())
        {
            if (child.getNodeType () == Node.ELEMENT_NODE) rate (child, part);
        }
    }

    public void rate (Node node, MNode container)
    {
        String type = getAttribute (node, "type");
        MNode part = container.set (node.getNodeName (), "");
        part.set ("$inherit", "\"" + type + "\"");
        checkDependency (part, type);

        NamedNodeMap attributes = node.getAttributes ();
        int count = attributes.getLength ();
        for (int i = 0; i < count; i++)
        {
            Node a = attributes.item (i);
            String name = a.getNodeName ();
            if (name.equals ("type")) continue;
            part.set (name, biophysicalUnits (a.getNodeValue ()));
        }
    }

    public void gate (Node node, MNode container)
    {
        String id   = getAttribute (node, "id");
        String type = getAttribute (node, "type");
        String inherit;
        if (type.isEmpty ()) inherit = node.getNodeName ();
        else                 inherit = type;
        MNode part = container.set (id, "");
        part.set ("$inherit", "\"" + inherit + "\"");
        checkDependency (part, inherit);

        NamedNodeMap attributes = node.getAttributes ();
        int count = attributes.getLength ();
        for (int i = 0; i < count; i++)
        {
            Node a = attributes.item (i);
            String name = a.getNodeName ();
            if (name.equals ("id")) continue;
            if (name.equals ("type")) continue;
            part.set (name, biophysicalUnits (a.getNodeValue ()));
        }

        for (Node child = node.getFirstChild (); child != null; child = child.getNextSibling ())
        {
            if (child.getNodeType () != Node.ELEMENT_NODE) continue;
            String name = child.getNodeName ();
            if      (name.equals ("subGate")) gate (child, part);
            else if (name.equals ("notes"))   part.set ("$metadata", "notes", getText (child));
            else                              rate (child, part);
        }
    }

    public void blockingPlasticSynapse (Node node)
    {
        String id = getAttribute (node, "id");
        MNode part = models.childOrCreate (modelName, id);
        part.set ("$inherit", "\"blockingPlasticSynapse\"");

        NamedNodeMap attributes = node.getAttributes ();
        int count = attributes.getLength ();
        for (int i = 0; i < count; i++)
        {
            Node a = attributes.item (i);
            String name = a.getNodeName ();
            if (name.equals ("id")) continue;
            part.set (name, biophysicalUnits (a.getNodeValue ()));
        }

        for (Node child = node.getFirstChild (); child != null; child = child.getNextSibling ())
        {
            if (child.getNodeType () != Node.ELEMENT_NODE) continue;
            genericPart (child, part);
            String name = child.getNodeName ();
            if (name.endsWith ("Mechanism"))
            {
                MNode c = part.child (name);  // retrieve the part we just made
                removeDependency (c, name);  // We're about to change the $inherit value, so get rid of the dependency created in genericPart().
                String type = c.get ("type");
                c.clear ("type");
                c.set ("$inherit", "\"" + type + "\"");
                checkDependency (c, type);
            }
        }
    }

    public class Cell
    {
        String               id;
        MNode                cell;
        List<Node>           cellularProperties = new ArrayList<Node> ();
        Map<Integer,Segment> segments           = new HashMap<Integer,Segment> ();
        MatrixBoolean        G                  = new MatrixBoolean ();
        MatrixBoolean        O                  = new MatrixBoolean ();
        MatrixBoolean        M                  = new MatrixBoolean ();
        Map<Integer,String>  groupIndex         = new HashMap<Integer,String> ();

        public Cell (Node node)
        {
            id   = getAttribute (node, "id");
            cell = models.childOrCreate (modelName, id);
            cell.set ("$inherit", "\"cell\"");

            for (Node child = node.getFirstChild (); child != null; child = child.getNextSibling ())
            {
                if (child.getNodeType () != Node.ELEMENT_NODE) continue;
                switch (child.getNodeName ())
                {
                    case "morphology"           : morphology            (child); break;
                    case "biophysicalProperties": biophysicalProperties (child); break;
                    default                     : genericPart           (child, cell);  // Also handles generic metadata elements.
                }
            }

            // Alternate attribute-based method for pulling in morphology and biophysics
            // This only works if such sections appear before this cell node. It seems that the NeuroML spec guarantees this.
            String include = getAttribute (node, "morphology");
            if (! include.isEmpty ())
            {
                Node child = morphologies.get (include);
                if (child != null) morphology (child);
            }
            include = getAttribute (node, "biophysicalProperties");
            if (! include.isEmpty ())
            {
                Node child = biophysics.get (include);
                if (child != null) biophysicalProperties (child);
            }
        }

        /**
            Add the contents of an intracellularProperties or extracellularProperties node.
        **/
        public void addCellularProperties (Node node)
        {
            for (Node child = node.getFirstChild (); child != null; child = child.getNextSibling ())
            {
                if (child.getNodeType () == Node.ELEMENT_NODE)
                {
                    cellularProperties.add (child);
                }
            }
        }
  
        public void morphology (Node node)
        {
            for (Node child = node.getFirstChild (); child != null; child = child.getNextSibling ())
            {
                if (child.getNodeType () != Node.ELEMENT_NODE) continue;
                switch (child.getNodeName ())
                {
                    case "segment":
                        Segment s = new Segment (child);
                        segments.put (s.id, s);
                        break;
                    case "segmentGroup":
                        segmentGroup (child);
                        break;
                }
            }
        }

        public void segmentGroup (Node node)
        {
            MNode groups = cell.childOrCreate ("$group");
            int c = groups.length ();
            String groupName = getAttribute (node, "id");
            MNode part = groups.childOrCreate (groupName);
            part.set ("$G", c);
            groupIndex.put (c, groupName);

            NamedNodeMap attributes = node.getAttributes ();
            int count = attributes.getLength ();
            for (int i = 0; i < count; i++)
            {
                Node a = attributes.item (i);
                String name = a.getNodeName ();
                if (name.equals ("id")) continue;
                part.set ("$metadata", name, a.getNodeValue ());
            }

            for (Node child = node.getFirstChild (); child != null; child = child.getNextSibling ())
            {
                if (child.getNodeType () != Node.ELEMENT_NODE) continue;
                switch (child.getNodeName ())
                {
                    case "member":
                        G.set (getAttribute (child, "segment", 0), c);
                        break;
                    case "include":
                        String include = part.get ("$include");  // not $inherit
                        if (! include.isEmpty ()) include += ",";
                        include += getAttribute (child, "segmentGroup");
                        part.set ("$include", include);
                        break;
                    case "path":
                    case "subTree":
                        segmentPath (child, part);
                        break;
                    case "inhomogeneousParameter":
                        inhomogeneousParameter (child, part);
                        break;
                }
            }
        }

        public void segmentPath (Node node, MNode group)
        {
            int from = -1;
            int to   = -1;
            for (Node child = node.getFirstChild (); child != null; child = child.getNextSibling ())
            {
                if (child.getNodeType () != Node.ELEMENT_NODE) continue;
                switch (child.getNodeName ())
                {
                    case "from": from = getAttribute (child, "segment", 0); break; 
                    case "to"  : to   = getAttribute (child, "segment", 0); break; 
                }
            }
            if (from >= 0)
            {
                MNode paths = group.childOrCreate ("$paths");
                int index = paths.length ();
                if (to >= 0) paths.set (index, from + "," + to);
                else         paths.set (index, from);
            }
        }

        public void applyPaths (MNode group)
        {
            MNode paths = group.child ("$paths");
            if (paths == null) return;

            int c = group.getInt ("$G");
            for (MNode p : paths)
            {
                String[] pieces = p.get ().split (",");
                if (pieces.length == 1)  // subtree
                {
                    int from = Integer.valueOf (pieces[0]);
                    applyPath (segments.get (from), c);
                }
                else  // path
                {
                    int from = Integer.valueOf (pieces[0]);
                    int to   = Integer.valueOf (pieces[1]);
                    if (from > to)
                    {
                        for (int r = to; r <= from; r++) G.set (r, c);
                    }
                    else
                    {
                        for (int r = from; r <= to; r++) G.set (r, c);
                    }
                }
            }

            group.clear ("$paths");
        }

        public void applyPath (Segment s, int c)
        {
            G.set (s.id, c);
            for (Segment t : s.children) applyPath (t, c);
        }

        public void includeGroups (MNode groups, MNode g)
        {
            String include = g.get ("$include");
            if (include.isEmpty ()) return;
            g.clear ("$include");

            int columnG = g.getInt ("$G");
            String[] pieces = include.split (",");
            for (String i : pieces)
            {
                MNode p = groups.child (i);
                if (p == null) continue;
                includeGroups (groups, p);
                // Merge segment members
                int columnP = p.getInt ("$G");
                G.OR (columnG, columnP);
                // Don't merge metadata from included groups, only members.
                // All metadata will get combined when groups are finalized in cell().
                // If it is merged here, it gets combined twice, and some items can leak into groups they are not really related to.
            }
        }

        public void inhomogeneousParameter (Node node, MNode group)
        {
            //String id       = getAttribute (node, "id");
            String variable = getAttribute (node, "variable");

            String translationStart = "0";
            String normalizationEnd = "1";
            for (Node child = node.getFirstChild (); child != null; child = child.getNextSibling ())
            {
                if (child.getNodeType () != Node.ELEMENT_NODE) continue;
                switch (child.getNodeName ())
                {
                    case "proximal": translationStart = getAttribute (child, "translationStart"); break;
                    case "distal"  : normalizationEnd = getAttribute (child, "normalizationEnd"); break;
                }
            }

            // TODO: What is the correct interpretation of translationStart and normalizationEnd?
            // Here we assume they are a linear transformation of the path length: variable = pathLength * normalizatonEnd + translationStart
            group.set (variable, "$metadata", "backend.neuroml.param",   "pathLength");
            group.set (variable, "$metadata", "backend.neuroml.param.a", normalizationEnd);
            group.set (variable, "$metadata", "backend.neuroml.param.b", translationStart);
        }

        public void biophysicalProperties (Node node)
        {
            for (Node child = node.getFirstChild (); child != null; child = child.getNextSibling ())
            {
                if (child.getNodeType () != Node.ELEMENT_NODE) continue;
                String name = child.getNodeName ();
                if (name.startsWith ("membrane"))
                {
                    membraneProperties (child);
                }
                else if (name.endsWith ("Properties"))
                {
                    addCellularProperties (child);
                }
            }
        }

        public void membraneProperties (Node node)
        {
            for (Node child = node.getFirstChild (); child != null; child = child.getNextSibling ())
            {
                if (child.getNodeType () != Node.ELEMENT_NODE) continue;

                MNode property = allocateProperty (child);
                String name = child.getNodeName ();
                if (name.startsWith ("channel"))
                {
                    channel (child, property);
                }
                else
                {
                    String value = biophysicalUnits (getAttribute (child, "value"));
                    switch (name)
                    {
                        case "spikeThresh":
                            property.set ("Vpeak", value);
                            break;
                        case "specificCapacitance":
                            property.set ("C", value);
                            break;
                        case "initMembPotential":
                            property.set ("V", value + "@$init");
                            break;
                    }
                }
            }
        }

        public MNode allocateProperty (Node node)
        {
            MNode  properties = cell.childOrCreate ("$properties");
            String id         = getAttribute (node, "id");
            String group      = getAttribute (node, "segmentGroup");
            if (group.isEmpty ())
            {
                group = getAttribute (node, "segment");
                if (group.isEmpty ())
                {
                    group = "[all]";
                }
                else
                {
                    int r = Integer.parseInt (group);
                    group = "[" + group + "]";  // proper name will be assigned later, during post-processing
                    if (cell.child ("$group", group) == null)
                    {
                        MNode groups = cell.childOrCreate ("$group");
                        int c = groups.length ();
                        G.set (r, c);
                        groups.set (group, "$G", c);
                        groupIndex.put (c, group);
                    }
                }
            }
            MNode result = properties.set (String.valueOf (properties.length ()), group);
            if (id.isEmpty ()) return result;

            // Create a subpart with the given name
            MNode subpart = result.set (id, "");  
            NamedNodeMap attributes = node.getAttributes ();
            int count = attributes.getLength ();
            for (int i = 0; i < count; i++)
            {
                Node a = attributes.item (i);
                String name = a.getNodeName ();
                if (name.equals ("id")) continue;
                if (name.equals ("segment")) continue;
                if (name.equals ("segmentGroup")) continue;
                if (name.equals ("value")) continue;  // Caller will extract this directly from XML node.
                if (name.equals ("ion")) continue;  // never used
                subpart.set (name, biophysicalUnits (a.getNodeValue ()));
            }
            return result;
        }

        public void channel (Node node, MNode property)
        {
            MNode subpart = property.iterator ().next ();  // retrieve the first (and only) subpart

            String name = node.getNodeName ();
            subpart.set ("$inherit", "\"" + name + "\"");

            String ionChannel = subpart.get ("ionChannel");
            subpart.clear ("ionChannel");
            subpart.set ("ionChannel", "$inherit", "\"" + ionChannel + "\"");

            String parentGroup = property.get ();
            Map<String,MNode> groupProperty = new TreeMap<String,MNode> ();
            groupProperty.put (parentGroup, property);
            for (Node child = node.getFirstChild (); child != null; child = child.getNextSibling ())
            {
                if (child.getNodeType () != Node.ELEMENT_NODE  ||  ! child.getNodeName ().equals ("variableParameter")) continue;
                String parameter    = getAttribute (child, "parameter");
                String segmentGroup = getAttribute (child, "segmentGroup");

                MNode clone = property;
                if (! segmentGroup.equals (parentGroup))
                {
                    if (parentGroup.equals ("[all]"))  // segment group was not or should not be assigned at level of channel part
                    {
                        groupProperty.remove (parentGroup);
                        groupProperty.put (segmentGroup, property);
                        property.set (segmentGroup);  // assumes that the target group will exist when needed
                        parentGroup = segmentGroup;
                    }
                    else  // different than previously assigned group, so may need to clone the property
                    {
                        clone = groupProperty.get (segmentGroup);
                        if (clone == null)
                        {
                            MNode properties = cell.child ("$properties");
                            clone = properties.set (String.valueOf (properties.length ()), segmentGroup);
                            clone.merge (property);
                            clone.set (segmentGroup);
                            groupProperty.put (segmentGroup, clone);
                        }
                    }
                    subpart = clone.iterator ().next ();
                    subpart.set (parameter, variableParameter (child));
                }
            }
        }

        public String variableParameter (Node node)
        {
            for (Node child = node.getFirstChild (); child != null; child = child.getNextSibling ())
            {
                if (child.getNodeType () == Node.ELEMENT_NODE  &&  child.getNodeName ().equals ("inhomogeneousValue")) return getAttribute (child, "value");
            }
            return "";
        }

        public void finish ()
        {
            // Add intra- and extra-cellular properties
            // TODO: Does it matter whether it is intra or extra? It seems all Species have internal and external concentrations.
            for (Node p : cellularProperties)
            {
                MNode property = allocateProperty (p);
                String name = p.getNodeName ();
                switch (name)
                {
                    case "species":
                        MNode subpart = property.iterator ().next ();
                        String concentrationModel = subpart.get ("concentrationModel");
                        subpart.clear ("concentrationModel");
                        subpart.set ("$inherit", "\"" + concentrationModel + "\"");
                        // Dependency will be tagged when this property is added to segments.
                        break;
                    case "resistivity":
                        property.set ("R", biophysicalUnits (getAttribute (p, "value")));
                        break;
                }
            }

            // Finalize segment structures
            for (Entry<Integer,Segment> e : segments.entrySet ())
            {
                Segment s = e.getValue ();
                if (s.parentID >= 0  &&  s.parent == null)
                {
                    s.parent = segments.get (s.parentID);
                    s.parent.children.add (s);
                }
            }
            for (Entry<Integer,Segment> e : segments.entrySet ()) e.getValue ().resolveProximal ();
            MNode groups = cell.child ("$group");
            if (groups != null)
            {
                for (MNode g : groups) applyPaths (g);
                for (MNode g : groups) includeGroups (groups, g);
            }

            // If no segment groups have been defined, create a single catch-all group.
            if (groups == null  &&  segments.size () > 0)
            {
                int smallestID = Integer.MAX_VALUE;
                for (Entry<Integer,Segment> e : segments.entrySet ())
                {
                    int ID = Integer.valueOf (e.getKey ());
                    smallestID = Math.min (ID, smallestID);
                    G.set (ID, 0);
                }
                String name = segments.get (smallestID).name;  // Name the group after the first segment, if it has a name.
                if (name.isEmpty ()) name = "segments";
                cell.set ("$group", name, "$G", 0);
                groupIndex.put (0, name);
            }

            MNode properties = cell.child ("$properties");
            if (properties != null)
            {
                for (MNode p : properties)
                {
                    String groupName = p.get ();
                    if (groupName.startsWith ("[")  &&  ! groupName.equals ("[all]"))
                    {
                        // Determine better name for segment-specific part.
                        int length = groupName.length ();
                        int r = Integer.parseInt (groupName.substring (1, length-1));
                        int currentColumn = cell.getInt ("$group", groupName, "$G");
                        String newName = segments.get (r).name;
                        if (newName.isEmpty ()  ||  cell.child ("$group", newName) != null)
                        {
                            int bestColumn = currentColumn;
                            int bestCount  = 1;
                            for (int c = 0; c < G.columns (); c++)
                            {
                                if (c == currentColumn) continue;
                                if (! G.get (r, c)) continue;

                                int count = G.columnNorm0 (c);
                                if (count > bestCount)
                                {
                                    bestColumn = c;
                                    bestCount  = count;
                                }
                            }
                            if (bestColumn == currentColumn) newName = "segment_" + r;
                            else                             newName = groupIndex.get (bestColumn) + "_" + r;
                        }
                        cell.child ("$group").move (groupName, newName);
                        groupIndex.put (currentColumn, newName);
                        p.set (newName);
                        groupName = newName;
                    }

                    // Distribute properties to original segment groups
                    if (groupName.equals ("[all]"))
                    {
                        // If it is a simple variable, then apply it to the containing cell.
                        MNode subpart = p.iterator ().next ();
                        if (subpart.length () == 0)
                        {
                            cell.set (subpart.key (), subpart.get ());
                        }
                        else  // Otherwise, distribute it to all groups
                        {
                            for (MNode part : cell.child ("$group"))
                            {
                                part.merge (p);
                                part.set ("");
                            }
                        }
                    }
                    else
                    {
                        MNode part = cell.child ("$group", groupName);
                        if (part != null)  // Missing group is a symptom of an ill-formed file.
                        {
                            part.merge (p);
                            part.set ("");
                        }
                    }
                }
            }


            // Create subparts for all combinations of parameters.
            // Each subpart will hold a set of segments that is mutually exclusive of the other sets.
            // Ideally, the sets exactly match the segment groups, but in general there may be more sets.
            // If a set exactly matches an original segment group, it gets that group's name.
            // Then, the set that has the largest overlap with an unclaimed segment group, without exceeding it, gets its name.
            // All remaining sets get a name formed from a concatenation of each group it overlaps with.

            G.foldRows (M, O);

            // Scan for exact matches
            int columnsG = G.columns ();
            int columnsM = M.columns ();
            Set<Integer> newIndices = new HashSet<Integer>  (columnsM);
            Map<Integer,String> finalNames = new HashMap<Integer,String> (columnsM);
            for (int i = 0; i < columnsM; i++)
            {
                MatrixBoolean A = M.column (i);
                boolean found = false;
                for (int j = 0; j < columnsG; j++)
                {
                    if (A.equals (G.column (j)))
                    {
                        found = true;
                        String name = groupIndex.get (j);  // maps index to group name
                        cell.set (name, "");
                        finalNames.put (i, name);
                        break;
                    }
                }
                if (! found) newIndices.add (i);
            }

            // Scan for largest overlaps
            Iterator<Integer> it = newIndices.iterator ();
            while (it.hasNext ())
            {
                int i = it.next ();
                boolean[] columnA = M.data.get (i);
                int bestCount = 0;
                int bestIndex = -1;
                for (int j = 0; j < columnsG; j++)
                {
                    int count = 0;
                    boolean subset = true;
                    boolean[] columnG = G.data.get (j);
                    int rows = Math.min (columnA.length, columnG.length);
                    int r = 0;
                    for (; r < rows; r++)
                    {
                        if (columnA[r])
                        {
                            if (columnG[r])
                            {
                                count++;
                            }
                            else
                            {
                                subset = false;
                                break;
                            }
                        }
                    }
                    for (; r < columnA.length  &&  subset; r++) if (columnA[r]) subset = false;
                    if (subset  &&  count > bestCount)
                    {
                        bestCount = count;
                        bestIndex = j;
                    }
                }
                if (bestCount > 0)
                {
                    it.remove ();

                    String name = groupIndex.get (bestIndex);
                    cell.set (name, "");
                    finalNames.put (i, name);
                }
            }

            // Build concatenated parts
            for (int i : newIndices)
            {
                String name = "";
                for (int j = 0; j < columnsG; j++)
                {
                    if (O.get (i, j))
                    {
                        String groupName = groupIndex.get (j);
                        if (! name.isEmpty ()) name = name + "_";
                        name = name + groupName;
                    }
                }
                cell.set (name, "");
                finalNames.put (i, name);
            }

            // Add segments and properties to the parts
            for (Entry<Integer,String> e : finalNames.entrySet ())
            {
                int c = e.getKey ();  // column of M, the mapping from segments to new groups
                String currentName = e.getValue ();
                MNode part = cell.child (currentName);

                // Rename any part with a single segment to the name of that segment, provided it is unique.
                if (M.columnNorm0 (c) == 1)
                {
                    int r = M.firstNonzeroRow (c);
                    String newName = segments.get (r).name;
                    // Note that all named parts have already been added to cell, so we can do a lookup there for uniqueness.
                    if (! newName.isEmpty ()  &&  ! newName.equals (currentName)  &&  cell.child (newName) == null)
                    {
                        cell.move (currentName, newName);
                        e.setValue (newName);
                        currentName = newName;
                    }
                }

                // Merge original groups into new part
                //   A heuristic is that smaller groups (ones with fewer segments) are more specific, so their metadata should take precedence.
                //   Thus, we sort the relevant original groups by size and apply them in that order.
                class ColumnSize implements Comparable<ColumnSize>
                {
                    public int column;
                    public int size;  // number of rows in column
                    public ColumnSize (int column, int size)
                    {
                        this.column = column;
                        this.size   = size;
                    }
                    public int compareTo (ColumnSize o)
                    {
                        int result = size - o.size;
                        if (result != 0) return result;
                        return column - o.column;  // Just a tie breaker. Favors lower-numbered column.
                    }
                }
                TreeSet<ColumnSize> sortedColumns = new TreeSet<ColumnSize> ();
                //   rows of O are new parts (columns of M); columns of O are original segment groups
                for (int i = 0; i < columnsG; i++) if (O.get (c, i)) sortedColumns.add (new ColumnSize (i, G.columnNorm0 (i)));
                for (ColumnSize cs : sortedColumns)
                {
                    String groupName = groupIndex.get (cs.column);  // a name from the original set of groups, not the new groups
                    part.mergeUnder (cell.child ("$group", groupName));
                }
                for (MNode property : part)
                {
                    String inherit = property.get ("$inherit").replace ("\"", "");
                    if (! inherit.isEmpty ()) checkDependency (property, inherit);
                    for (MNode m : property)
                    {
                        inherit = m.get ("$inherit").replace ("\"", "");
                        if (inherit.isEmpty ()) continue;
                        checkDependency (m, inherit);
                    }
                }

                // Collect inhomogeneous variables
                String pathLength = "";
                for (MNode v : part)
                {
                    if (! v.get ("$metadata", "backend.neuroml.param").equals ("pathLength")) continue;
                    if (pathLength.isEmpty ())
                    {
                        pathLength = "pathLength";
                        int count = 2;
                        while (part.child (pathLength) != null) pathLength = "pathLength" + count++;
                    }
                    double a = v.getDouble ("$metadata", "backend.neuroml.param.a");
                    double b = v.getDouble ("$metadata", "backend.neuroml.param.b");
                    String value = pathLength;
                    if (a != 1) value += "*" + a;
                    if (b != 0) value += "+" + b;
                    v.set (value);
                }

                // Add segments
                int n = M.columnNorm0 (c);
                if (n > 1) part.set ("$n", n);
                int index = 0;
                for (int r = 0; r < M.rows (); r++)
                {
                    if (! M.get (r, c)) continue;
                    Segment s = segments.get (r);
                    
                    if (n > 1)
                    {
                        if (! s.name.isEmpty ()) part.set ("$metadata", "name" + index, s.name);
                        if (! pathLength.isEmpty ()) part.set (pathLength, "@$index==" + index, s.pathLength (0.5));  // mid-point method. TODO: add way to subdivide segments for more precise modeling of varying density
                        s.output (part, index++);
                    }
                    else
                    {
                        if (! s.name.isEmpty ()  &&  ! s.name.equals (currentName)) part.set ("$metadata", "name", s.name);
                        if (! pathLength.isEmpty ()) part.set (pathLength, s.pathLength (0.5));
                        s.output (part, -1);
                        break;  // Since we've already processed the only segment.
                    }
                }
            }

            // Create connections to complete the cables
            // Note that all connections are explicit, even within the same group.
            // TODO: Convert these to unary connections directly from child segment to parent segment.
            for (Entry<Integer,Segment> e : segments.entrySet ())
            {
                Segment s = e.getValue ();
                if (s.parent == null) continue;

                int childN = s.part.getOrDefaultInt ("$n", "1");
                String connectionName = s.parent.part.key () + "_to_" + s.part.key ();
                MNode connection = cell.child (connectionName);
                if (connection == null)
                {
                    connection = cell.set (connectionName, "");
                    connection.set ("$inherit", "\"Coupling Voltage\"");
                    connection.set ("A", s.parent.part.key ());
                    connection.set ("B", s       .part.key ());
                    connection.set ("R", "$kill");  // Force use of container's value.

                    int parentN = s.parent.part.getOrDefaultInt ("$n", "1");
                    if (parentN > 1)
                    {
                        String parentName = "parent";
                        int suffix = 2;
                        while (s.part.child (parentName) != null) parentName = "parent" + suffix++;
                        connection.set ("$parent", parentName);  // temporary memo

                        connection.set ("$p", "A.$index==B." + parentName);
                        if (childN > 1)
                        {
                            s.part.set (parentName, ":");
                            s.part.set (parentName, "@", "-1");
                        }
                    }
                    else if (childN > 1)
                    {
                        connection.set ("$p", "B.$index==" + s.index);
                    }
                }

                String parentName = connection.get ("$parent");
                if (! parentName.isEmpty ())
                {
                    if (childN > 1) s.part.set (parentName, "@$index==" + s.index, s.parent.index);
                    else            s.part.set (parentName, s.parent.index);
                }
            }

            // Clean up temporary nodes.
            cell.clear ("$properties");
            cell.clear ("$group");
            for (MNode part : cell)
            {
                part.clear ("$parent");
                part.clear ("$G");
                for (MNode v : part)
                {
                    v.clear ("$metadata", "backend.neuroml.param.a");
                    v.clear ("$metadata", "backend.neuroml.param.b");
                }
            }
            groupIndex = finalNames;
        }
    }

    public class Segment implements Comparable<Segment>
    {
        int          id;  // NeuroML id; row number in G matrix
        String       name;
        int          parentID           = -1;
        Segment      parent;
        Set<Segment> children           = new TreeSet<Segment> ();

        MNode        part;   // compartment of which this segment is an instance
        int          index;  // $index within part

        double       fractionAlong      = 1;
        Matrix       proximal;
        Matrix       distal;
        double       proximalDiameter   = -1;
        double       distalDiameter     = -1;
        double       proximalPathLength = -1;  // Path length from proximal end to root. Used to calculate pathLength().

        public Segment (Node node)
        {
            id   = Integer.parseInt (getAttribute (node, "id", "0"));
            name =                   getAttribute (node, "name"); 
            for (Node child = node.getFirstChild (); child != null; child = child.getNextSibling ())
            {
                if (child.getNodeType () != Node.ELEMENT_NODE) continue;
                switch (child.getNodeName ())
                {
                    case "parent":
                        parentID      = getAttribute (child, "segment",       -1);
                        fractionAlong = getAttribute (child, "fractionAlong", 1.0);
                        break;
                    case "proximal":
                        proximal = new Matrix (3, 1);
                        proximal.set   (0, Matrix.convert (morphologyUnits (getAttribute (child, "x"))));
                        proximal.set   (1, Matrix.convert (morphologyUnits (getAttribute (child, "y"))));
                        proximal.set   (2, Matrix.convert (morphologyUnits (getAttribute (child, "z"))));
                        proximalDiameter = Matrix.convert (morphologyUnits (getAttribute (child, "diameter")));
                        break;
                    case "distal":
                        distal = new Matrix (3, 1);
                        distal.set   (0, Matrix.convert (morphologyUnits (getAttribute (child, "x"))));
                        distal.set   (1, Matrix.convert (morphologyUnits (getAttribute (child, "y"))));
                        distal.set   (2, Matrix.convert (morphologyUnits (getAttribute (child, "z"))));
                        distalDiameter = Matrix.convert (morphologyUnits (getAttribute (child, "diameter")));
                        break;
                }
            }
        }

        /**
            Requires that parent be resolved already.
        **/
        public void resolveProximal ()
        {
            if (proximal != null  ||  parent == null) return;
            if (fractionAlong == 1)
            {
                proximal = parent.distal;
                proximalDiameter = parent.distalDiameter;
            }
            else
            {
                parent.resolveProximal ();
                Matrix A = parent.proximal;
                Matrix B = parent.distal;
                proximal = (Matrix) B.subtract (A).multiply (new Scalar (fractionAlong)).add (A);

                double a = parent.proximalDiameter;
                double b = parent.distalDiameter;
                proximalDiameter = (b - a) * fractionAlong + a;
            }
        }

        public double pathLength (double fraction)
        {
            if (proximalPathLength < 0)
            {
                if (parent == null) proximalPathLength = 0;
                else                proximalPathLength = parent.pathLength (fractionAlong);
            }
            return proximalPathLength + ((Matrix) distal.subtract (proximal)).norm (2) * fraction;
        }

        public void output (MNode part, int index)
        {
            this.part = part;
            if (index < 0)  // only one instance, so make values unconditional
            {
                this.index = 0;
                if (distal           != null) part.set ("$xyz",      format     (distal));
                if (proximal         != null) part.set ("xyz0",      format     (proximal));
                if (distalDiameter   >= 0   ) part.set ("diameter",  formatUnit (distalDiameter));
                if (proximalDiameter >= 0   ) part.set ("diameter0", formatUnit (proximalDiameter));
            }
            else  // multiple instances
            {
                this.index = index;
                if (distal           != null) part.set ("$xyz",      "@$index==" + index, format     (distal));
                if (proximal         != null) part.set ("xyz0",      "@$index==" + index, format     (proximal));
                if (distalDiameter   >= 0   ) part.set ("diameter",  "@$index==" + index, formatUnit (distalDiameter));
                if (proximalDiameter >= 0   ) part.set ("diameter0", "@$index==" + index, formatUnit (proximalDiameter));
            }
        }

        public String formatUnit (double a)
        {
            a *= 1e6;
            double i = Math.round (a);
            if (Math.abs (i - a) < epsilon)  // This is an integer
            {
                if (i == 0) return "0";
                return ((long) i) + "um";
            }
            return a + "um";
        }

        public String format (double a)
        {
            a *= 1e6;
            double i = Math.round (a);
            if (Math.abs (i - a) < epsilon) return String.valueOf ((long) i);
            return String.valueOf (a);
        }

        public String format (Matrix A)
        {
            double x = A.getDouble (0);
            double y = A.getDouble (1);
            double z = A.getDouble (2);
            if (x == 0  &&  y == 0  &&  z == 0) return "[0;0;0]";
            return "[" + format (x) + ";" + format (y) + ";" + format (z) + "]*1um";
        }

        public int compareTo (Segment o)
        {
            return id - o.id;
        }
    }

    /**
        Convert the given value to be in appropriate units, in the context of a morphology section.
    **/
    public String morphologyUnits (String value)
    {
        value = value.trim ();
        int unitIndex = findUnits (value);
        if (unitIndex == 0) return value;  // no number
        if (unitIndex >= value.length ()) return value + "um";  // default morphology units are micrometers

        String units = value.substring (unitIndex).trim ();
        value        = value.substring (0, unitIndex);

        return value + cleanupUnits (units);
    }

    public int findUnits (String value)
    {
        Matcher m = floatParser.matcher (value);
        m.find ();
        return m.end ();
    }

    public String cleanupUnits (String units)
    {
        units = units.replace ("_per_", "/");
        units = units.replace ("per_",  "/");
        units = units.replace ("_",     ".");
        units = units.replace (" ",     ".");
        units = units.replace ("ohm",   "Ohm");
        units = units.replace ("KOhm",  "kOhm");
        units = units.replace ("degC",  "Cel");
        if (units.equals ("M" )) units = "mol";
        if (units.equals ("mM")) units = "mmol";
        if (forbiddenUCUM.matcher (units).find ()) return "(" + units + ")";
        return units;
    }

    /**
        Convert the given value to be in appropriate units, in the context of a physiology section.
    **/
    public String biophysicalUnits (String value)
    {
        value = value.trim ();
        int unitIndex = findUnits (value);
        if (unitIndex == 0) return value;  // no number
        if (unitIndex >= value.length ()) return value;  // no units; need to apply defaults here

        String units = value.substring (unitIndex).trim ();
        value        = value.substring (0, unitIndex);

        return value + cleanupUnits (units);
    }

    public void spikeArray (Node node)
    {
        String id = getAttribute (node, "id");
        MNode part = models.childOrCreate (modelName, id);
        part.set ("$inherit", "\"" + node.getNodeName () + "\"");

        NamedNodeMap attributes = node.getAttributes ();
        int count = attributes.getLength ();
        for (int i = 0; i < count; i++)
        {
            Node a = attributes.item (i);
            String name = a.getNodeName ();
            if (name.equals ("id")) continue;
            part.set (name, biophysicalUnits (a.getNodeValue ()));
        }

        // TODO: sort the spikes, to guarantee monotonicity
        String spikes = "[";
        for (Node child = node.getFirstChild (); child != null; child = child.getNextSibling ())
        {
            if (child.getNodeType () == Node.ELEMENT_NODE  &&  child.getNodeName ().equals ("spike"))
            {
                if (spikes.length () > 1) spikes += ";";
                spikes += biophysicalUnits (getAttribute (child, "time"));
            }
        }
        part.set ("spikes", spikes + "]");
    }

    public class Network
    {
        String id;
        MNode network;
        List<Node> extracellularProperties = new ArrayList<Node> ();
        List<Node> projections             = new ArrayList<Node> ();

        public Network (Node node)
        {
            id                 = getAttribute (node, "id");
            String temperature = getAttribute (node, "temperature");

            network = models.childOrCreate (modelName, id);
            if (! temperature.isEmpty ()) network.set ("temperature", biophysicalUnits (temperature));

            for (Node child = node.getFirstChild (); child != null; child = child.getNextSibling ())
            {
                if (child.getNodeType () != Node.ELEMENT_NODE) continue;
                switch (child.getNodeName ())
                {
                    case "space":
                        space (child);
                        break;
                    case "region":
                        String spaceID = getAttribute (child, "space");
                        network.set ("$region", child.getNodeValue (), spaceID);  // Region is little more than an alias of a space, as of NeuroML 2 beta 4.
                        break;
                    case "extracellularProperties":
                        extracellularProperties.add (child);
                        break;
                    case "population":
                        population (child);
                        break;
                    // TODO: cellSet -- Ignoring. Has one attribute and no elements. Attribute is ill-defined.
                    // TODO: synapticConnection -- Appears to be deprecated.
                    case "projection":
                    case "continuousProjection":
                    case "electricalProjection":
                    case "inputList":
                        projections.add (child);
                        break;
                    case "explicitInput":
                        explicitInput (child);
                        break;
                }
            }
        }

        public void space (Node node)
        {
            String id = getAttribute (node, "id");

            for (Node child = node.getFirstChild (); child != null; child = child.getNextSibling ())
            {
                // The standard as written allows more than one structure element, but not sure how to make sense of that.
                // This code simply overwrites the data if more than one such element exists.
                if (child.getNodeType () == Node.ELEMENT_NODE  &&  child.getNodeName ().equals ("structure"))
                {
                    double sx = getAttribute (child, "xSpacing", 1.0);
                    double sy = getAttribute (child, "ySpacing", 1.0);
                    double sz = getAttribute (child, "zSpacing", 1.0);
                    double ox = getAttribute (child, "xStart",   0.0);
                    double oy = getAttribute (child, "yStart",   0.0);
                    double oz = getAttribute (child, "zStart",   0.0);
                    MNode p = network.childOrCreate ("$space", id);
                    p.set ("scale",  "[" + sx + ";" + sy + ";" + sz + "]");
                    p.set ("offset", "[" + ox + ";" + oy + ";" + oz + "]");
                }
            }
        }

        public void population (Node node)
        {
            String id        = getAttribute (node, "id");
            int    n         = getAttribute (node, "size", 0);
            String component = getAttribute (node, "component");  // Should always be defined.

            MNode part = network.set (id, "");
            part.set ("$inherit", "\"" + component + "\"");
            addDependency (part, component);
            if (n > 1) part.set ("$n", n);

            // Add intra/extra cellular properties to inherited cell
            Cell cell = cells.get (component);
            if (cell != null)
            {
                List<Node> localProps = new ArrayList<Node> ();
                localProps.addAll (extracellularProperties);
                String exID = getAttribute (node, "extracellularProperties");
                if (! exID.isEmpty ())
                {
                    Node ex = properties.get (exID);
                    if (ex != null) localProps.add (ex);
                }
                for (Node p : localProps) cell.addCellularProperties (p);
            }

            for (Node child = node.getFirstChild (); child != null; child = child.getNextSibling ())
            {
                if (child.getNodeType () != Node.ELEMENT_NODE) continue;
                switch (child.getNodeName ())
                {
                    case "layout"  : populationLayout   (child, part); break;
                    case "instance": populationInstance (child, part); break;
                }
            }

            // Post-process instances, hopefully matching their IDs to their $index values.
            MNode instances = part.child ("$instance");
            if (instances != null)
            {
                int count = instances.length ();
                if (count > 1) part.set ("$n", count);
                int index = 0;
                for (MNode i : instances)
                {
                    String xyz = i.get ("$xyz");
                    String ijk = i.get ("ijk");
                    if (count == 1)
                    {
                        if (! xyz.isEmpty ()) part.set ("$xyz", xyz);
                        if (! ijk.isEmpty ()) part.set ("ijk",  ijk);
                    }
                    else
                    {
                        if (! xyz.isEmpty ()) part.set ("$xyz", "@$index==" + index, xyz);
                        if (! ijk.isEmpty ()) part.set ("ijk",  "@$index==" + index, ijk);
                    }
                    i.set ("$index", index++);
                }
            }
        }

        public void populationLayout (Node node, MNode part)
        {
            String spaceID = getAttribute (node, "space");

            for (Node child = node.getFirstChild (); child != null; child = child.getNextSibling ())
            {
                if (child.getNodeType () == Node.ELEMENT_NODE)
                {
                    String regionID = getAttribute (child, "region");
                    if (! regionID.isEmpty ()) spaceID = network.get ("$region", regionID);
                    MNode space = null;
                    if (! spaceID.isEmpty ()) space = network.child ("$space", spaceID);

                    switch (child.getNodeName ())
                    {
                        case "random":
                            part.set ("$n", getAttribute (child, "number", 1));
                            if (space != null) part.set ("$xyz", "uniform(" + space.get ("scale") + ")+" + space.get ("offset"));
                            break;
                        case "grid":
                            int x = getAttribute (child, "xSize", 1);
                            int y = getAttribute (child, "ySize", 1);
                            int z = getAttribute (child, "zSize", 1);
                            part.set ("$n", x * y * z);
                            if (space == null) part.set ("$xyz", "grid($index," + x + "," + y + "," + z + ")");
                            else               part.set ("$xyz", "grid($index," + x + "," + y + "," + z + ",\"raw\")&" + space.get ("scale") + "+" + space.get ("offset"));
                            break;
                        case "unstructured":
                            part.set ("$n", getAttribute (child, "number", 1));
                            break;
                    }
                }
            }
        }

        public void populationInstance (Node node, MNode part)
        {
            int id = getAttribute (node, "id", 0);
            int i  = getAttribute (node, "i", -1);
            int j  = getAttribute (node, "j", -1);
            int k  = getAttribute (node, "k", -1);
            double x = 0;
            double y = 0;
            double z = 0;
            for (Node child = node.getFirstChild (); child != null; child = child.getNextSibling ())
            {
                // As written, the standard allows multiple xyz positions. Not sure what that means.
                // This code overwrites the position if more than one are specified.
                if (child.getNodeType () == Node.ELEMENT_NODE  &&  child.getNodeName ().equals ("location"))
                {
                    x = getAttribute (child, "x", 0.0);
                    y = getAttribute (child, "y", 0.0);
                    z = getAttribute (child, "z", 0.0);
                }
            }

            part.set ("$instance", id, "$xyz", "[" + x + ";" + y + ";" + z + "]");
            if (i >= 0  ||  j >= 0  ||  k >= 0)
            {
                if (i < 0) i = 0;
                if (j < 0) j = 0;
                if (k < 0) k = 0;
                part.set ("$instance", id, "ijk",  "[" + i + ";" + j + ";" + k + "]");
            }
        }

        /**
            Contains minor hacks to handle InputList along with the 3 projection types.
        **/
        public void projection (Node node)
        {
            String id             = getAttribute  (node, "id");
            String inherit        = getAttribute  (node, "synapse");
            String A              = getAttributes (node, "presynapticPopulation",  "component");
            String B              = getAttributes (node, "postsynapticPopulation", "population");
            String projectionType = node.getNodeName ();

            MNode base = new MVolatile ();
            base.set ("A", A);
            base.set ("B", B);
            if      (projectionType.equals ("continuousProjection")) inherit = "continuousProjection";
            else if (projectionType.equals ("inputList"))            inherit = "Current Injection";

            NamedNodeMap attributes = node.getAttributes ();
            int count = attributes.getLength ();
            for (int i = 0; i < count; i++)
            {
                Node a = attributes.item (i);
                String name = a.getNodeName ();
                if (name.equals ("id"                    )) continue;
                if (name.equals ("synapse"               )) continue;
                if (name.equals ("presynapticPopulation" )) continue;
                if (name.equals ("postsynapticPopulation")) continue;
                if (name.equals ("component"             )) continue;
                if (name.equals ("population"            )) continue;
                base.set (name, biophysicalUnits (a.getNodeValue ()));
            }

            // Children are specific connections.
            // In the case of "continuous" connections, there are pre- and post-synaptic components which can vary
            // from one entry to the next. These must be made into separate connection objects, so try to fold
            // them as much as possible.
            // For other connection types, attributes can be set up as conditional constants.

            MNode instancesA = network.child (A, "$instance");
            MNode instancesB = network.child (B, "$instance");

            boolean postCellSingleton = network.getOrDefaultInt (B, "$n", "1") == 1;  // This assumes that a population always has $n set if it is anything besides 1.
            boolean preCellSingleton;  // A requires more testing, because it could be the "component" of an input list.
            boolean Acomponent = ! getAttribute (node, "component").isEmpty ();
            if (Acomponent) preCellSingleton = models .getOrDefaultInt (modelName, A, "$n", "1") == 1;
            else            preCellSingleton = network.getOrDefaultInt (           A, "$n", "1") == 1;

            List<Connection> connections = new ArrayList<Connection> ();
            for (Node child = node.getFirstChild (); child != null; child = child.getNextSibling ())
            {
                if (child.getNodeType () != Node.ELEMENT_NODE) continue;

                // Collect data and assemble query
                Connection connection = new Connection ();
                int childID        = getAttribute (child, "id", 0);
                connection.inherit = getAttribute (child, "synapse", inherit);

                connection.preComponent  = getAttribute  (child, "preComponent");
                String preCell           = getAttributes (child, "preCell", "preCellId");
                String preSegmentString  = getAttributes (child, "preSegment", "preSegmentId");
                String preFractionString = getAttribute  (child, "preFractionAlong");

                connection.postComponent  = getAttribute  (child, "postComponent");
                String postCell           = getAttributes (child, "postCell", "postCellId", "target");
                String postSegmentString  = getAttributes (child, "postSegment", "postSegmentId", "segmentId");
                String postFractionString = getAttributes (child, "postFractionAlong", "fractionAlong");

                double weight = getAttribute (child, "weight", 1.0);
                String delay  = getAttribute (child, "delay");

                int preSegment = -1;
                if (! preSegmentString.isEmpty ()) preSegment = Integer.valueOf (preSegmentString);
                double preFraction = 0.5;
                if (! preFractionString.isEmpty ()) preFraction = Double.valueOf (preFractionString);

                int postSegment = -1;
                if (! postSegmentString.isEmpty ()) postSegment = Integer.valueOf (postSegmentString);
                double postFraction = 0.5;
                if (! postFractionString.isEmpty ()) postFraction = Double.valueOf (postFractionString);

                preCell  = extractIDfromPath (preCell);
                postCell = extractIDfromPath (postCell);
                if (instancesA != null) preCell  = instancesA.getOrDefault (preCell,  "$index", preCell);  // Map NeuroML ID to assigned N2A $index, falling back on ID if $index has not been assigned.
                if (instancesB != null) postCell = instancesB.getOrDefault (postCell, "$index", postCell);

                int preSegmentIndex = 0;
                boolean preSegmentSingleton = false;
                if (preSegment >= 0)
                {
                    // preSegment is the ID, effectively the row in the segment*group matrix
                    // We must find the column associated with it, so we can map to a group part.
                    String cellID = network.get (A, "$inherit").replace ("\"", "");
                    Cell cell = cells.get (cellID);
                    if (cell != null)
                    {
                        MatrixBoolean M = cell.M;
                        if (M != null)
                        {
                            count = M.columns ();
                            for (int c = 0; c < count; c++)
                            {
                                if (M.get (preSegment, c))
                                {
                                    connection.preGroup = cell.groupIndex.get (c);
                                    preSegmentIndex = M.indexInColumn (preSegment, c);
                                    preSegmentSingleton = models.getOrDefaultInt (modelName, cellID, connection.preGroup, "$n", "1") == 1;
                                    break;
                                }
                            }
                        }
                    }
                }

                int postSegmentIndex = 0;
                boolean postSegmentSingleton = false;
                if (postSegment >= 0)
                {
                    String cellID = network.get (B, "$inherit").replace ("\"", "");
                    Cell cell = cells.get (cellID);
                    if (cell != null)
                    {
                        MatrixBoolean M = cell.M;
                        if (M != null)
                        {
                            count = M.columns ();
                            for (int c = 0; c < count; c++)
                            {
                                if (M.get (postSegment, c))
                                {
                                    connection.postGroup = cell.groupIndex.get (c);
                                    postSegmentIndex = M.indexInColumn (postSegment, c);
                                    postSegmentSingleton = models.getOrDefaultInt (modelName, cellID, connection.postGroup, "$n", "1") == 1;
                                    break;
                                }
                            }
                        }
                    }
                }

                // Choose part
                int match = connections.indexOf (connection);
                if (match >= 0)  // Use existing part.
                {
                    connection = connections.get (match);
                }
                else  // Create new part, cloning relevant info.
                {
                    connections.add (connection);
                    if (network.child (id) == null) connection.part = network.set (id,           base);
                    else                            connection.part = network.set (id + childID, base);  // Another part has already consumed the base name, so augment it with some index. Any index will do, but childID is convenient.

                    if (connection.postGroup.isEmpty ()) connection.part.set ("B", B);
                    else                                 connection.part.set ("B", B + "." + connection.postGroup);
                    if (connection.preGroup.isEmpty ())
                    {
                        connection.part.set ("A", A);
                        if (Acomponent) addDependency (connection.part.child ("A"), A);
                    }
                    else
                    {
                        connection.part.set ("A", A + "." + connection.preGroup);
                    }
                    if (! connection.preComponent.isEmpty ())
                    {
                        connection.part.set ("preComponent", "$inherit", "\"" + connection.preComponent + "\"");
                        addDependency (connection.part.child ("preComponent"), connection.preComponent);
                    }
                    if (! connection.postComponent.isEmpty ())
                    {
                        connection.part.set ("postComponent", "$inherit", "\"" + connection.postComponent + "\"");
                        addDependency (connection.part.child ("postComponent"), connection.postComponent);
                    }
                    if (! connection.inherit.isEmpty ())
                    {
                        connection.part.set ("$inherit", "\"" + connection.inherit + "\"");
                        addDependency (connection.part, connection.inherit);
                    }
                }
               
                // Add conditional info

                String condition = "";
                if (! preCellSingleton)
                {
                    if (connection.preGroup.isEmpty ()) condition = "A.$index=="     + preCell;
                    else                                condition = "A.$up.$index==" + preCell;
                }
                if (! postCellSingleton)
                {
                    if (! condition.isEmpty ()) condition += "&&";
                    if (connection.postGroup.isEmpty ()) condition += "B.$index=="     + postCell;
                    else                                 condition += "B.$up.$index==" + postCell;
                }
                if (! preSegmentSingleton  &&  ! connection.preGroup.isEmpty ())
                {
                    if (! condition.isEmpty ()) condition += "&&";
                    condition += "A.$index==" + preSegmentIndex;
                }
                if (! postSegmentSingleton  &&  ! connection.postGroup.isEmpty ())
                {
                    if (! condition.isEmpty ()) condition += "&&";
                    condition += "B.$index==" + postSegmentIndex;
                }
                if (! condition.isEmpty ())
                {
                    MNode p = connection.part.child ("$p");
                    if (p == null)
                    {
                        connection.part.set ("$p", condition);
                    }
                    else
                    {
                        if (p.length () == 0)  // There is exactly one condition already existing, and we transition to multi-part equation.
                        {
                            p.set ("@", "0");
                            p.set ("@" + p.get (), "1");
                            p.set ("");
                        }
                        p.set ("@" + condition, "1");
                    }
                }

                if (preFraction  != 0.5) connection.add (connection.preFractions,  preFraction,  condition);
                if (postFraction != 0.5) connection.add (connection.postFractions, postFraction, condition);
                if (weight       != 1.0) connection.add (connection.weights,       weight,       condition);
                if (! delay.isEmpty ())  connection.add (connection.delays,        delay,        condition);
            }
            for (Connection c : connections) c.injectConditionalValues ();

            if (connections.size () == 0)  // No connections were added, so add a minimalist projection part.
            {
                MNode part = network.set (id, base);
                if (! inherit.isEmpty ())
                {
                    part.set ("$inherit", "\"" + inherit + "\"");
                    addDependency (part, inherit);
                }
            }
        }

        /**
            Handle all the random and inconsistent ways that population member IDs are represented.
        **/
        public String extractIDfromPath (String path)
        {
            if (path.isEmpty ()) return path;

            int i = path.indexOf ('[');
            if (i >= 0)
            {
                String suffix = path.substring (i + 1);
                i = suffix.indexOf (']');
                if (i >= 0) suffix = suffix.substring (0, i);
                return suffix;
            }

            String[] pieces = path.split ("/");
            if (pieces.length < 3) return path;
            return pieces[2];
        }

        public void explicitInput (Node node)
        {
            String input  = getAttribute (node, "input");
            String target = getAttribute (node, "target");
            int index = 0;
            int i = target.indexOf ('[');
            if (i >= 0)
            {
                String suffix = target.substring (i + 1);
                target = target.substring (0, i);
                i = suffix.indexOf (']');
                if (i >= 0) suffix = suffix.substring (0, i);
                index = Integer.valueOf (suffix);
            }

            String name = input + "_to_" + target;
            MNode part = network.childOrCreate (name);
            part.set ("$inherit", "\"Current Injection\"");
            MNode d = part.set ("A", input);
            addDependency (d, input);
            part.set ("B", target);  // Like the input, the target could be folded into this connection part during dependency resolution, but that would actually make the model more ugly.

            MNode targetPart = network.child (target);
            if (targetPart == null  ||  ! targetPart.getOrDefault ("$n", "1").equals ("1"))  // We only have to explicitly set $p if the target part has more than one instance.
            {
                part.set ("$p", "@B.$index==" + index, "1");
                part.set ("$p", "@",                   "0");
            }
        }

        public void finish ()
        {
            for (Node p : projections) projection (p);
            network.clear ("$space");
            network.clear ("$region");
            for (MNode p : network) p.clear ("$instance");
        }

        public void moveUp ()
        {
            for (MNode p : network)
            {
                MNode dest = models.childOrCreate (modelName, p.key ());
                dest.merge (p);
            }
            models.clear (modelName, id);
        }
    }

    public static class Connection
    {
        public String preGroup      = "";
        public String postGroup     = "";
        public String inherit       = "";
        public String preComponent  = "";
        public String postComponent = "";
        public MNode  part;

        public TreeMap<Double,TreeSet<String>> preFractions  = new TreeMap<Double,TreeSet<String>> ();
        public TreeMap<Double,TreeSet<String>> postFractions = new TreeMap<Double,TreeSet<String>> ();
        public TreeMap<Double,TreeSet<String>> weights       = new TreeMap<Double,TreeSet<String>> ();
        public TreeMap<Double,TreeSet<String>> delays        = new TreeMap<Double,TreeSet<String>> ();

        public void add (TreeMap<Double,TreeSet<String>> collection, String value, String condition)
        {
            add (collection, Matrix.convert (value), condition);
        }

        public void add (TreeMap<Double,TreeSet<String>> collection, double value, String condition)
        {
            TreeSet<String> conditions = collection.get (value);
            if (conditions == null)
            {
                conditions = new TreeSet<String> ();
                collection.put (value, conditions);
            }
            conditions.add (condition);
        }

        public void injectConditionalValues ()
        {
            injectConditionalValue ("preFraction",  preFractions,  0.5, false);
            injectConditionalValue ("postFraction", postFractions, 0.5, false);
            injectConditionalValue ("weight",       weights,       1.0, false);
            injectConditionalValue ("delay",        delays,        0.0, true);
        }

        public void injectConditionalValue (String name, TreeMap<Double,TreeSet<String>> collection, double defaultValue, boolean outputSeconds)
        {
            if (collection.size () == 0) return;
            if (collection.size () == 1)
            {
                double value = collection.firstKey ();
                if (value == defaultValue) return;  // Default value should be inherited, so no need to set it.
                TreeSet<String> conditions = collection.firstEntry ().getValue ();
                if (conditions.size () == 1  &&  conditions.first ().isEmpty ())  // Unconditional value. This occurs when both source and destination populations are singletons.
                {
                    if (outputSeconds)
                    {
                        if (value >= 1) part.set (name, value + "s");
                        else            part.set (name, value * 1000 + "ms");
                    }
                    else
                    {
                        part.set (name, value);
                    }
                    return;
                }
            }

            MNode v = part.childOrCreate (name);
            v.set ("@", defaultValue);
            for (Entry<Double,TreeSet<String>> e : collection.entrySet ())
            {
                String condition = "";
                for (String c : e.getValue ())
                {
                    if (! condition.isEmpty ()) condition += "||";
                    condition += c;
                }
                double value = e.getKey ();
                if (outputSeconds)
                {
                    if (value >= 1) part.set (name, value + "s");
                    else            part.set (name, value * 1000 + "ms");
                }
                else
                {
                    v.set ("@" + condition, value);
                }
            }
        }

        @Override
        public boolean equals (Object o)
        {
            if (! (o instanceof Connection)) return false;
            Connection that = (Connection) o;

            if (! preGroup     .equals (that.preGroup     )) return false;
            if (! postGroup    .equals (that.postGroup    )) return false;
            if (! inherit      .equals (that.inherit      )) return false;
            if (! preComponent .equals (that.preComponent )) return false;
            if (! postComponent.equals (that.postComponent)) return false;
            return true;
        }
    }

    /**
        Handles elements in a generic manner, including metadata elements.
        Generic elements get processed into parts under the given container.
        Metadata elements get added to the $metadata node the given container.
    **/
    public MNode genericPart (Node node, MNode container)
    {
        String name = node.getNodeName ();
        if (name.equals ("notes"))
        {
            container.set ("$metadata", "notes", getText (node));
            return container.child ("$metadata", "notes");
        }
        if (name.equals ("property"))
        {
            String tag   = getAttribute (node, "tag");
            String value = getAttribute (node, "value");
            container.set ("$metadata", tag, value);
            return container.child ("$metadata", tag);
        }
        if (name.equals ("annotation")) return null;  // TODO: process annotations

        String id = getAttribute (node, "id", name);
        String stem = id;
        int suffix = 2;
        while (container.child (id) != null) id = stem + suffix++;
        MNode part = container.set (id, "");
        part.set ("$inherit", "\"" + name + "\"");
        checkDependency (part, name);

        NamedNodeMap attributes = node.getAttributes ();
        int count = attributes.getLength ();
        for (int i = 0; i < count; i++)
        {
            Node a = attributes.item (i);
            name = a.getNodeName ();
            if (name.equals ("id")) continue;
            part.set (name, biophysicalUnits (a.getNodeValue ()));
        }

        for (Node child = node.getFirstChild (); child != null; child = child.getNextSibling ())
        {
            if (child.getNodeType () == Node.ELEMENT_NODE) genericPart (child, part);
        }

        return part;
    }

    public void target (Node node)
    {
        String component  = getAttribute (node, "component");
        String reportFile = getAttribute (node, "reportFile");
        String timesFile  = getAttribute (node, "timesFile");
        MNode part = models.set (component, "");  // TODO: the exact mapping of "component" to model is more complex, as several executable models may be generated during an import.
        if (! reportFile.isEmpty ()) part.set ("$metadata", "backend.neuroml.reportFile", reportFile);
        if (! timesFile .isEmpty ()) part.set ("$metadata", "backend.neuroml.timesFile",  timesFile);
    }

    public class ComponentType
    {
        MNode part;

        public ComponentType (MNode part)
        {
            this.part = part;
        }

        public ComponentType (Node node)
        {
            String name        = getAttribute (node, "name");
            String inherit     = getAttribute (node, "extends");
            String description = getAttribute (node, "description");
            part = models.childOrCreate (modelName, name);
            if (! inherit    .isEmpty ()) part.set ("$inherit", "\"" + inherit + "\"");
            if (! description.isEmpty ()) part.set ("$metadata", "description", description);

            for (Node child = node.getFirstChild (); child != null; child = child.getNextSibling ())
            {
                if (child.getNodeType () != Node.ELEMENT_NODE) continue;

                String nodeName = child.getNodeName ();
                switch (nodeName)
                {
                    case "Constant":
                    case "Fixed":
                        genericVariable (child, "");
                        break;
                    case "Parameter":
                    case "Requirement":
                        // These could be handled by genericVariable(), but that would require extra testing there for this relatively simple form.
                        name        = getAttribute (child, "name");
                        description = getAttribute (child, "description");
                        if (! description.isEmpty ()) part.set ("$metadata", name, description);
                    case "Child":
                    case "Children":
                    case "ComponentReference":
                    //case "Attachments":  // Ignore. This is equivalent to noting the name/type of a connection within the part that will be connected.
                        name       = getAttribute (child, "name");
                        inherit    = getAttribute (child, "type");
                        String min = getAttribute (child, "min");
                        String max = getAttribute (child, "max");
                        // TODO: How to interpret the "local" field?
                        part.set (name, "$inherit", inherit);
                        if (! min.isEmpty ()) part.set (name, "$metadata", "backend.neuroml.min", min);
                        if (! max.isEmpty ()) part.set (name, "$metadata", "backend.neuroml.max", max);
                        break;
                    case "Link":  // The dual of "Attachments". This defines the reference variable in a connection.
                        name        = getAttribute (child, "name");
                        inherit     = getAttribute (child, "type");
                        description = getAttribute (child, "description");
                        part.set (name, "$connect(" + inherit + ")");
                        if (! description.isEmpty ()) part.set (name, "$metadata", "description", description);
                        break;
                    case "Dynamics":
                        dynamics (child);
                        break;
                    case "Simulation":
                        simulation (child);
                        break;
                }
            }
        }

        public MNode genericVariable (Node node, String condition1)
        {
            String               name = getAttribute (node, "name");
            if (name.isEmpty ()) name = getAttribute (node, "parameter");
            if (name.isEmpty ()) name = getAttribute (node, "variable");
            if (name.isEmpty ()) name = getAttribute (node, "property");
            if (node.getNodeName ().equals ("TimeDerivative")) name += "'";

            String exposure    = getAttribute (node, "exposure");
            String description = getAttribute (node, "description");

            String combiner = "";
            String nodeName = node.getNodeName ();
            if (! nodeName.equals ("StateVariable")  &&  exposure.isEmpty ()) combiner = ":";  // Temporary variable. TODO: verify if it's truly necessary to make exposures into state variables. It might not be if every exposure is associated with injected local code that grabs the value.

            String value = cleanupExpression (getAttribute (node, "value"));
            if (value.isEmpty ())
            {
                value = getAttribute (node, "select");
                if (! value.isEmpty ())
                {
                    // TODO: analyze "select" string more thoroughly
                    String[] pieces = value.split ("/");  // TODO: check if forward-slash works without escape
                    String child    = pieces[0];
                    String variable = pieces[1];

                    String index = "";
                    int i = child.indexOf ('[');
                    if (i >= 0)
                    {
                        index = child.substring (i + 1);
                        child = child.substring (0, i);
                        i = index.indexOf (']');
                        if (i >= 0) index = index.substring (0, i);
                    }

                    String reduce = getAttribute (node, "reduce");
                    if (! index.isEmpty ()  ||  ! reduce.isEmpty ())
                    {
                        value = "";

                        switch (reduce)
                        {
                            case "multiply": combiner = "*";
                            case "add"     : combiner = "+";
                        }

                        String required = getAttribute (node, "required");
                        if (required.equals ("true")) part.set (child, "$up." + name, "$metadata", "backend.neuroml.required", "");

                        // TODO: add condition to select specific instances
                        part.set (child, "$up." + name, combiner + variable);
                    }
                    else
                    {
                        value = value.replace ("..", "$up");
                        value = value.replace ("/", ".");
                    }
                }
            }

            MNode result = part.childOrCreate (name);

            if (value.isEmpty ())  // multi-conditional
            {
                result.set (combiner);
            }
            else  // single condition
            {
                if (condition1.isEmpty ()) result.set (combiner + value);
                else                       result.set (combiner + value + "@" + condition1);
            }
            if (! description.isEmpty ())
            {
                result.set ("$metadata", "description", description);
            }
            if (! exposure.isEmpty ())
            {
                if (exposure.equals (name)) result.set ("$metadata", "exposure", "");
                else                        result.set ("$metadata", "exposure", exposure);
            }

            for (Node child = node.getFirstChild (); child != null; child = child.getNextSibling ())
            {
                if (child.getNodeType () == Node.ELEMENT_NODE  &&  child.getNodeName ().equals ("Case"))
                {
                    value             = cleanupExpression (getAttribute (child, "value"));
                    String condition2 = cleanupExpression (getAttribute (child, "condition"));
                    if (condition1.isEmpty ()) result.set ("@" +                     condition2, value);
                    else                       result.set ("@" + condition1 + "&&" + condition2, value);
                }
            }

            return result;
        }

        public String cleanupExpression (String expression)
        {
            expression = expression.replace (".gt.", ">");
            expression = expression.replace (".lt.", "<");
            expression = expression.replace (" ",    "");
            return expression;
        }

        public void dynamics (Node node)
        {
            for (Node child = node.getFirstChild (); child != null; child = child.getNextSibling ())
            {
                if (child.getNodeType () != Node.ELEMENT_NODE) continue;
                switch (child.getNodeName ())
                {
                    case "StateVariable":
                    case "DerivedVariable":
                    case "ConditionalDerivedVariable":
                    case "TimeDerivative":
                        genericVariable (child, "");
                        break;
                    case "OnStart":
                        onStart (child, "$init");
                        break;
                    case "OnEvent":
                    case "OnCondition":
                        onCondition (child, "");
                        break;
                    case "Regime":
                        regime (child);
                        break;
                    case "KineticScheme":
                        break;
                }
            }
        }

        public void onStart (Node node, String condition)
        {
            for (Node child = node.getFirstChild (); child != null; child = child.getNextSibling ())
            {
                if (child.getNodeType () != Node.ELEMENT_NODE) continue;
                genericVariable (child, condition);
            }
        }

        public void onCondition (Node node, String condition1)
        {
            String condition2 = cleanupExpression (getAttribute (node, "test"));
            if (condition2.isEmpty ())
            {
                condition2 = getAttribute (node, "port");  // TODO: process port to resolve source part
                condition2 = "$event(" + condition2 + ")";
            }
            if (! condition1.isEmpty ()) condition2 = condition1 + "&&" + condition2;

            for (Node child = node.getFirstChild (); child != null; child = child.getNextSibling ())
            {
                if (child.getNodeType () != Node.ELEMENT_NODE) continue;
                switch (child.getNodeName ())
                {
                    case "StateAssignment":
                        genericVariable (child, condition2);
                        break;
                    case "EventOut":
                        String portName = getAttribute (child, "port");
                        MNode portNode = part.child (portName);
                        if (portNode == null)
                        {
                            part.set (portName, "@", "0");
                            part.set (portName, "$metadata", "backend.neuroml.param", "port");
                        }
                        part.set (portName, "@" + condition2, "1");
                        break;
                    case "Transition":
                        String regime = getAttribute (child, "regime");
                        part.set ("regime", regime + "@" + condition2);
                        break;
                }
            }
        }

        public MNode allocateRegime (String name)
        {
            // TODO: postprocess to find non-conflicting names for regime variable and regimes
            MNode regime = part.child ("regime");
            if (regime == null)
            {
                part.set ("regime", "-1@$init");  // No active regime
                part.set ("regime", "$metadata", "backend.neuroml.param", "regime");
            }
            MNode regimeName = part.child (name);
            if (regimeName == null)
            {
                int regimeCount = part.getInt ("$regime");
                part.set (name, regimeCount++);
                part.set ("$regime", regimeCount);
            }
            return regime;
        }

        public void regime (Node node)
        {
            String name    = getAttribute (node, "name");
            String initial = getAttribute (node, "initial");

            MNode regime = allocateRegime (name);
            if (initial.equals ("true"))
            {
                if (regime.length () == 1) part.set ("regime", name + "@$init");  // just the $metadata node, so set single-line value
                else                       part.set ("regime", "@$init", name);
            }

            for (Node child = node.getFirstChild (); child != null; child = child.getNextSibling ())
            {
                if (child.getNodeType () != Node.ELEMENT_NODE) continue;
                switch (child.getNodeName ())
                {
                    case "TimeDerivative":
                        genericVariable (child, "regime==" + name);
                        break;
                    case "OnEntry":
                        onStart (child, "$event(regime==" + name + ")");
                        break;
                    case "OnCondition":
                        onCondition (child, "regime==" + name);
                        break;
                }
            }
        }

        public void simulation (Node node)
        {
            for (Node child = node.getFirstChild (); child != null; child = child.getNextSibling ())
            {
                if (child.getNodeType () != Node.ELEMENT_NODE) continue;
                switch (child.getNodeName ())
                {
                    case "Run":
                        String component = getAttribute (child, "component");
                        String dt        = getAttribute (child, "increment");
                        String total     = getAttribute (child, "total");
                        models.set (component, "$t'", dt);
                        models.set (component, "$p",  "$t<" + total);
                        break;
                }
            }
        }
    }

    public String getText (Node node)
    {
        String result = "";
        for (Node child = node.getFirstChild (); child != null; child = child.getNextSibling ())
        {
            if (child.getNodeType () == Node.TEXT_NODE) result = result + child.getNodeValue ();
        }
        return result;
    }

    public String getAttributes (Node node, String... names)
    {
        for (String name : names)
        {
            String result = getAttribute (node, name);
            if (! result.isEmpty ()) return result;
        }
        return "";
    }

    public String getAttribute (Node node, String name)
    {
        return getAttribute (node, name, "");
    }

    public String getAttribute (Node node, String name, String defaultValue)
    {
        NamedNodeMap attributes = node.getAttributes ();
        Node attribute = attributes.getNamedItem (name);
        if (attribute == null) return defaultValue;
        return attribute.getNodeValue ();
    }

    public int getAttribute (Node node, String name, int defaultValue)
    {
        try
        {
            return Integer.parseInt (getAttribute (node, name, String.valueOf (defaultValue)));
        }
        catch (NumberFormatException e)
        {
            return defaultValue;
        }
    }

    public double getAttribute (Node node, String name, double defaultValue)
    {
        try
        {
            return Double.parseDouble (getAttribute (node, name, String.valueOf (defaultValue)));
        }
        catch (NumberFormatException e)
        {
            return defaultValue;
        }
    }
}
