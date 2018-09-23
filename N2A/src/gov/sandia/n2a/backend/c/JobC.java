/*
Copyright 2013-2018 National Technology & Engineering Solutions of Sandia, LLC (NTESS).
Under the terms of Contract DE-NA0003525 with NTESS,
the U.S. Government retains certain rights in this software.
*/

package gov.sandia.n2a.backend.c;

import gov.sandia.n2a.backend.internal.InternalBackendData.EventSource;
import gov.sandia.n2a.backend.internal.InternalBackendData.EventTarget;
import gov.sandia.n2a.backend.internal.Simulator;
import gov.sandia.n2a.db.AppData;
import gov.sandia.n2a.db.MNode;
import gov.sandia.n2a.eqset.EquationEntry;
import gov.sandia.n2a.eqset.EquationSet;
import gov.sandia.n2a.eqset.EquationSet.Conversion;
import gov.sandia.n2a.eqset.EquationSet.ConnectionBinding;
import gov.sandia.n2a.eqset.EquationSet.ConnectionMatrix;
import gov.sandia.n2a.eqset.Variable;
import gov.sandia.n2a.eqset.VariableReference;
import gov.sandia.n2a.execenvs.HostSystem;
import gov.sandia.n2a.language.AccessVariable;
import gov.sandia.n2a.language.BuildMatrix;
import gov.sandia.n2a.language.Constant;
import gov.sandia.n2a.language.Function;
import gov.sandia.n2a.language.Operator;
import gov.sandia.n2a.language.Split;
import gov.sandia.n2a.language.Type;
import gov.sandia.n2a.language.Visitor;
import gov.sandia.n2a.language.function.Input;
import gov.sandia.n2a.language.function.Output;
import gov.sandia.n2a.language.function.ReadMatrix;
import gov.sandia.n2a.language.operator.Add;
import gov.sandia.n2a.language.type.Matrix;
import gov.sandia.n2a.language.type.Scalar;
import gov.sandia.n2a.language.type.Text;
import gov.sandia.n2a.plugins.extpoints.Backend;
import gov.sandia.n2a.plugins.extpoints.Backend.AbortRun;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;

public class JobC extends Thread
{
    protected static boolean needRuntime = true;  // always rebuild runtime once per session

    public MNode       job;
    public EquationSet model;

    public Path jobDir;
    public Path runtimeDir;
    public Path gcc;

    public String T;
    public int    eventMode;

    // These values are unique across the whole simulation, so they go here rather than BackendDataC.
    // Where possible, the key is a String. Otherwise, it is an Operator which is specific to one expression.
    public HashMap<Object,String> matrixNames = new HashMap<Object,String> ();
    public HashMap<Object,String> inputNames  = new HashMap<Object,String> ();
    public HashMap<Object,String> outputNames = new HashMap<Object,String> ();
    public HashMap<Object,String> stringNames = new HashMap<Object,String> ();

    public JobC (MNode job)
    {
        super ("C Job");
        this.job = job;
    }

    public void run ()
    {
        jobDir = Paths.get (job.get ()).getParent ();  // assumes the MNode "job" is really an MDoc. In any case, the value of the node should point to a file on disk where it is stored in a directory just for it.
        try {Backend.err.set (new PrintStream (jobDir.resolve ("err").toFile ()));}
        catch (FileNotFoundException e) {}

        try
        {
            Files.createFile (jobDir.resolve ("started"));

            T = job.getOrDefault ("$metadata", "backend.c.type", "float");
            if (T.startsWith ("int")  &&  T.length () > 3)
            {
                T = "int";
                Backend.err.get ().println ("WARNING: Only supported integer type is 'int', which is assumed to be signed 32-bit.");
            }
            if (! T.equals ("int")  &&  ! T.equals ("double")  &&  ! T.equals ("float"))
            {
                T = "float";
                Backend.err.get ().println ("WARNING: Unsupported numeric type. Defaulting to single-precision float.");
            }

            HostSystem env = HostSystem.get (job.getOrDefault ("$metadata", "host", "localhost"));
            Path resourceDir = Paths.get (AppData.properties.get ("resourceDir"));
            gcc              = Paths.get (AppData.state.getOrDefault ("BackendC", "gcc", "g++"));
            runtimeDir       = resourceDir.resolve ("cruntime");
            rebuildRuntime ();

            model = new EquationSet (job);
            digestModel ();
            String duration = model.getNamedValue ("duration");
            if (! duration.isEmpty ()) job.set ("$metadata", "duration", duration);

            eventMode = Simulator.DURING;
            String e = job.get ("$metadata", "backend.internal.event");  // TODO: Need naming convention for parameters shared by more than one backend.
            if (e.equals ("after"))  eventMode = Simulator.AFTER;
            if (e.equals ("before")) eventMode = Simulator.BEFORE;

            System.out.println (model.dump (false));

            Path source = jobDir.resolve ("model.cc");
            generateCode (source);
            String command = env.quotePath (build (source));

            // The C program will append to the same error file, so we need to close the file before submitting.
            PrintStream ps = Backend.err.get ();
            if (ps != System.err)
            {
                ps.close ();
                Backend.err.remove ();
            }

            long pid = env.submitJob (job, command);
            job.set ("$metadata", "pid", pid);
        }
        catch (AbortRun a)
        {
        }
        catch (Exception e)
        {
            e.printStackTrace (Backend.err.get ());
        }

        // If an exception occurred, the error file will still be open.
        PrintStream ps = Backend.err.get ();
        if (ps != System.err) ps.close ();
    }

    public void rebuildRuntime () throws Exception
    {
        // Update runtime source files, if necessary
        boolean changed = false;
        if (needRuntime)
        {
            changed = unpackRuntime
            (
                JobC.class, runtimeDir,
                "fixedpoint.cc", "fixedpoint.h", "fixedpoint.tcc",
                "io.cc", "io.h", "io.tcc",
                "KDTree.h", "String.h",
                "matrix.h", "Matrix.tcc", "MatrixFixed.tcc", "MatrixSparse.tcc", "pointer.h",
                "nosys.h",
                "runtime.cc", "runtime.h", "runtime.tcc"
            );
            needRuntime = false;   // Stop checking files for this session.
        }

        if (changed)
        {
            DirectoryStream.Filter<Path> filter = new DirectoryStream.Filter<Path> ()
            {
                public boolean accept (Path entry) throws IOException
                {
                    return entry.getFileName ().toString ().endsWith (".o");
                }
            };
            try (DirectoryStream<Path> dir = Files.newDirectoryStream (runtimeDir, filter))
            {
                dir.forEach (file ->
                {
                    try
                    {
                        Files.delete (file);
                    }
                    catch (IOException e) {}  // Unfortunately, the Consumer interface does not permit exceptions to percolate up.
                });
            }
            catch (IOException e) {}
        }

        // Compile runtime
        ArrayList<String> sources = new ArrayList<String> ();
        sources.add ("runtime");
        sources.add ("io");
        if (T.equals ("int")) sources.add ("fixedpoint");
        for (String stem : sources)
        {
            Path object = runtimeDir.resolve (stem + "_" + T + ".o");
            if (Files.exists (object)) continue;
            Path source = runtimeDir.resolve (stem + ".cc");
            Path out = runCommand
            (
                gcc.toString (), "-c", "-O3", "-std=c++11",
                "-ffunction-sections", "-fdata-sections",
                "-I" + runtimeDir,
                "-Dn2a_T=" + T,
                (T.equals ("int") ? "-Dn2a_FP" : ""),
                "-o", object.toString (), source.toString ()
            );
            Files.delete (out);
        }
    }

    public boolean unpackRuntime (Class<?> from, Path runtimeDir, String... names) throws Exception
    {
        boolean changed = false;
        Files.createDirectories (runtimeDir);
        for (String s : names)
        {
            URL url = from.getResource ("runtime/" + s);
            long resourceModified = url.openConnection ().getLastModified ();
            Path f = runtimeDir.resolve (s);
            long fileModified = HostSystem.lastModified (f);
            if (resourceModified > fileModified)
            {
                changed = true;
                Files.copy (url.openStream (), f, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return changed;
    }

    public Path build (Path source) throws Exception
    {
        String stem = source.getFileName ().toString ().split ("\\.", 2)[0];
        Path binary = source.getParent ().resolve (stem + ".bin");

        Path out = runCommand
        (
            gcc.toString (), "-O3", "-std=c++11",
            "-ffunction-sections", "-fdata-sections", "-Wl,--gc-sections",
            "-I" + runtimeDir,
            "-Dn2a_T=" + T,
            (T.equals ("int") ? "-Dn2a_FP" : ""),
            runtimeDir.resolve ("runtime_" + T + ".o").toString (),
            runtimeDir.resolve ("io_"      + T + ".o").toString (),
            (T.equals ("int") ? runtimeDir.resolve ("fixedpoint_" + T + ".o").toString () : ""),
            "-o", binary.toString (), source.toString ()
        );
        Files.delete (out);

        return binary;
    }

    public Path runCommand (String... command) throws Exception
    {
        // Useful for debugging. The dumped command can be used directly in a terminal to diagnose stalled builds.
        for (String s : command) System.out.print (s + " ");
        System.out.println ();

        // Remove empty strings from command. This is a convenience to the caller,
        // allowing arguments to be conditionally omitted with the ternary operator.
        int count = 0;
        for (String s : command) if (! s.isEmpty ()) count++;
        String[] cleanedCommand = new String[count];
        int i = 0;
        for (String s : command) if (! s.isEmpty ()) cleanedCommand[i++] = s;

        Path out = jobDir.resolve ("compile.out");
        Path err = jobDir.resolve ("compile.err");

        ProcessBuilder b = new ProcessBuilder (cleanedCommand);
        b.redirectOutput (out.toFile ());  // Should truncate existing files.
        b.redirectError  (err.toFile ());
        Process p = b.start ();
        p.waitFor ();
        if (p.exitValue () != 0)
        {
            PrintStream ps = Backend.err.get ();
            ps.println ("Failed to compile:");
            ps.print (HostSystem.streamToString (Files.newInputStream (err)));
            Files.delete (out);
            Files.delete (err);
            throw new Backend.AbortRun ();
        }
        Files.delete (err);
        return out;
    }

    public void digestModel () throws Exception
    {
        model.resolveConnectionBindings ();
        model.flatten ("backend.c");
        model.addGlobalConstants ();
        model.addSpecials ();  // $connect, $index, $init, $n, $t, $t', $type
        model.addAttribute ("global",      0, false, new String[] {"$max", "$min", "$k", "$n", "$radius"});
        model.addAttribute ("preexistent", 0, true,  new String[] {"$index", "$t'", "$t"});  // Technically, $index is not pre-existent, but rather always receives special handling which has the same effect.
        model.fillIntegratedVariables ();
        model.findIntegrated ();
        model.resolveLHS ();
        model.resolveRHS ();
        model.findConstants ();
        model.determineTraceVariableName ();
        model.collectSplits ();
        model.findDeath ();
        addImplicitDependencies (model);
        model.removeUnused ();  // especially get rid of unneeded $variables created by addSpecials()
        createBackendData (model);
        findPathToContainer (model);
        model.findAccountableConnections ();
        model.findTemporary ();  // for connections, makes $p and $project "temporary" under some circumstances. TODO: make sure this doesn't violate evaluation order rules
        model.determineOrder ();
        model.findDerivative ();
        model.makeConstantDtInitOnly ();
        model.findInitOnly ();  // propagate initOnly through ASTs
        model.purgeInitOnlyTemporary ();
        model.setAttributesLive ();
        model.forceTemporaryStorageForSpecials ();
        findLiveReferences (model);
        model.determineTypes ();
        model.determineDuration ();
        if (T.equals ("int"))
        {
            double duration = 0;
            String durationString = model.getNamedValue ("duration");
            if (! durationString.isEmpty ()) duration = Double.valueOf (durationString);
            model.determineExponents (duration);
        }
        model.findConnectionMatrix ();
        analyzeEvents (model);
        analyze (model);
    }

    /**
        Depends on the results of: addAttributes(), findDeath()
    **/
    public void addImplicitDependencies (EquationSet s)
    {
        if (T.equals ("int"))
        {
            // Force top-level model to keep $t', so it can retrieve time exponent.
            Variable dt = s.find (new Variable ("$t", 1));
            dt.addUser (s);
        }
        addImplicitDependenciesRecursive (s);
    }

    public void addImplicitDependenciesRecursive (EquationSet s)
    {
        for (EquationSet p : s.parts)
        {
            addImplicitDependenciesRecursive (p);
        }
    
        final Variable dt = s.find (new Variable ("$t", 1));

        if (s.lethalP)
        {
            Variable p = s.find (new Variable ("$p", 0));  // Which should for sure exist, since lethalP implies it.
            p.addDependencyOn (dt);
        }

        class VisitorDt extends Visitor
        {
            public Variable from;
            public boolean visit (Operator op)
            {
                if (op instanceof Input)
                {
                    Input i = (Input) op;
                    String mode = "";
                    if      (i.operands.length == 2) mode = i.operands[1].getString ();
                    else if (i.operands.length >= 4) mode = i.operands[3].getString ();
                    if (mode.contains ("time")  &&  ! from.hasAttribute ("global")  &&  ! T.equals ("int"))
                    {
                        from.addDependencyOn (dt);
                    }
                }
                return true;
            }
        }
        VisitorDt visitor = new VisitorDt ();
    
        for (Variable v : s.variables)
        {
            visitor.from = v;
            v.visit (visitor);
            if (v.derivative != null) v.addDependencyOn (dt);
        }
    }

    public void createBackendData (EquationSet s)
    {
        if (! (s.backendData instanceof BackendDataC)) s.backendData = new BackendDataC ();
        for (EquationSet p : s.parts) createBackendData (p);
    }

    public void findPathToContainer (EquationSet s)
    {
        for (EquationSet p : s.parts)
        {
            findPathToContainer (p);
        }

        if (s.connectionBindings != null)
        {
            for (ConnectionBinding c : s.connectionBindings)
            {
                if (c.endpoint.container == s.container)
                {
                    BackendDataC bed = (BackendDataC) s.backendData;
                    bed.pathToContainer = c.alias;
                    break;
                }
            }
        }
    }

    public void findLiveReferences (EquationSet s)
    {
        for (EquationSet p : s.parts)
        {
            findLiveReferences (p);
        }

        if (s.lethalConnection  ||  s.lethalContainer)
        {
            ArrayList<Object>         resolution     = new ArrayList<Object> ();
            NavigableSet<EquationSet> touched        = new TreeSet<EquationSet> ();
            if (! (s.backendData instanceof BackendDataC)) s.backendData = new BackendDataC ();
            findLiveReferences (s, resolution, touched, ((BackendDataC) s.backendData).localReference, false);
        }
    }

    @SuppressWarnings("unchecked")
    public void findLiveReferences (EquationSet s, ArrayList<Object> resolution, NavigableSet<EquationSet> touched, List<VariableReference> localReference, boolean terminate)
    {
        if (terminate)
        {
            Variable live = s.find (new Variable ("$live"));
            if (live == null  ||  live.hasAttribute ("constant")) return;
            if (live.hasAttribute ("initOnly"))
            {
                if (touched.add (s))
                {
                    VariableReference result = new VariableReference ();
                    result.variable = live;
                    result.resolution = (ArrayList<Object>) resolution.clone ();
                    localReference.add (result);
                    s.referenced = true;
                }
                return;
            }
            // The third possibility is "accessor", in which case we fall through ...
        }

        // Recurse up to container
        if (s.lethalContainer)
        {
            resolution.add (s.container);
            findLiveReferences (s.container, resolution, touched, localReference, true);
            resolution.remove (resolution.size () - 1);
        }

        // Recurse into connections
        if (s.lethalConnection)
        {
            for (ConnectionBinding c : s.connectionBindings)
            {
                resolution.add (c);
                findLiveReferences (c.endpoint, resolution, touched, localReference, true);
                resolution.remove (resolution.size () - 1);
            }
        }
    }

    public void analyzeEvents (EquationSet s) throws Backend.AbortRun
    {
        BackendDataC bed = (BackendDataC) s.backendData;
        bed.analyzeEvents (s);
        for (EquationSet p : s.parts) analyzeEvents (p);
    }

    public void analyze (EquationSet s)
    {
        BackendDataC bed = (BackendDataC) s.backendData;
        bed.analyze (s);
        for (EquationSet p : s.parts) analyze (p);
        bed.analyzeLastT (s);
    }

    public void generateCode (Path source) throws Exception
    {
        StringBuilder result = new StringBuilder ();
        RendererC context;
        if (T.equals ("int")) context = new RendererCfp (this, result);
        else                  context = new RendererC   (this, result);

        result.append ("#include \"" + runtimeDir.resolve ("runtime.h") + "\"\n");
        result.append ("\n");
        result.append ("#include <iostream>\n");
        result.append ("#include <vector>\n");
        result.append ("#include <cmath>\n");
        result.append ("\n");
        result.append ("using namespace std;\n");
        result.append ("using namespace fl;\n");
        result.append ("\n");
        generateStatic (context, model);
        result.append ("\n");
        generateClassList (model, result);
        result.append ("class Wrapper;\n");
        result.append ("\n");
        generateDeclarations (model, result);
        result.append ("class Wrapper : public WrapperBase<" + T + ">\n");
        result.append ("{\n");
        result.append ("public:\n");
        result.append ("  " + prefix (model) + "_Population " + mangle (model.name) + ";\n");
        result.append ("\n");
        result.append ("  Wrapper ()\n");
        result.append ("  {\n");
        result.append ("    population = &" + mangle (model.name) + ";\n");
        result.append ("    " + mangle (model.name) + ".container = this;\n");
        result.append ("  }\n");
        result.append ("};\n");
        result.append ("\n");
        generateDefinitions (context, model);

        // Main
        result.append ("int main (int argc, char * argv[])\n");
        result.append ("{\n");
        result.append ("  try\n");
        result.append ("  {\n");
        if (T.equals ("int"))
        {
            Variable dt = model.find (new Variable ("$t", 1));
            result.append ("    Event<int>::exponent = " + dt.exponent + ";\n");
        }
        String integrator = model.getNamedValue ("c.integrator", "Euler");
        if (integrator.equalsIgnoreCase ("RungeKutta")) integrator = "RungeKutta";
        else                                            integrator = "Euler";
        result.append ("    Simulator<" + T + ">::instance.integrator = new " + integrator + "<" + T + ">;\n");
        result.append ("    Wrapper wrapper;\n");
        result.append ("    Simulator<" + T + ">::instance.run (wrapper);\n");
        result.append ("    outputClose ();\n");
        result.append ("  }\n");
        result.append ("  catch (const char * message)\n");
        result.append ("  {\n");
        result.append ("    cerr << \"Exception: \" << message << endl;\n");
        result.append ("    return 1;\n");
        result.append ("  }\n");
        result.append ("  return 0;\n");
        result.append ("}\n");

        Files.copy (new ByteArrayInputStream (result.toString ().getBytes ("UTF-8")), source);
    }

    public void generateClassList (EquationSet s, StringBuilder result)
    {
        for (EquationSet p : s.parts) generateClassList (p, result);
        result.append ("class " + prefix (s) + ";\n");
        result.append ("class " + prefix (s) + "_Population;\n");
    }

    public void generateStatic (RendererC context, EquationSet s)
    {
        for (EquationSet p : s.parts) generateStatic (context, p);

        context.setPart (s);
        BackendDataC  bed    = context.bed;
        StringBuilder result = context.result;

        class CheckStatic extends Visitor
        {
            public boolean global;
            public boolean visit (Operator op)
            {
                if (op instanceof Constant)
                {
                    Type m = ((Constant) op).value;
                    if (m instanceof Matrix)
                    {
                        Matrix A = (Matrix) m;
                        int rows = A.rows ();
                        int cols = A.columns ();
                        String matrixName = "Matrix" + matrixNames.size ();
                        matrixNames.put (op, matrixName);
                        result.append ("MatrixFixed<" + T + "," + rows + "," + cols + ">" + matrixName + " = {");
                        String initializer = "";
                        for (int c = 0; c < cols; c++)
                        {
                            for (int r = 0; r < rows; r++)
                            {
                                initializer += context.print (A.get (r, c), op.exponent) + ", ";
                            }
                        }
                        if (initializer.length () > 2) initializer = initializer.substring (0, initializer.length () - 2);
                        result.append (initializer + "};\n");
                    }
                    return false;  // Don't try to descend tree from here
                }
                if (op instanceof Function)
                {
                    Function f = (Function) op;
                    if (f instanceof Output  &&  f.operands.length < 3)  // We need to auto-generate the column name.
                    {
                        String stringName = "columnName" + stringNames.size ();
                        stringNames.put (op, stringName);
                        if (global)
                        {
                            bed.setGlobalNeedPath (s);
                            bed.globalColumns.add (stringName);
                        }
                        else
                        {
                            bed.setLocalNeedPath  (s);
                            bed.localColumns.add (stringName);
                        }
                    }
                    // Detect functions that need static handles
                    if (f.operands.length > 0)
                    {
                        Operator operand0 = f.operands[0];
                        if (operand0 instanceof Constant)
                        {
                            Constant c = (Constant) operand0;
                            Type o = c.value;
                            if (o instanceof Text)
                            {
                                String fileName = ((Text) o).value;
                                if (op instanceof ReadMatrix)
                                {
                                    if (! matrixNames.containsKey (fileName))
                                    {
                                        String matrixName = "Matrix" + matrixNames.size ();
                                        matrixNames.put (fileName, matrixName);
                                        result.append ("MatrixInput<" + T + "> * " + matrixName + " = matrixHelper<" + T + "> (\"" + fileName + "\");\n");
                                    }
                                }
                                else if (f instanceof Input)
                                {
                                    if (! inputNames.containsKey (fileName))
                                    {
                                        String inputName = "Input" + inputNames.size ();
                                        inputNames.put (fileName, inputName);
                                        result.append ("InputHolder<" + T + "> * " + inputName + " = inputHelper<" + T + "> (\"" + fileName + "\");\n");
                                    }
                                }
                                else if (f instanceof Output)
                                {
                                    if (! outputNames.containsKey (fileName))
                                    {
                                        String outputName = "Output" + outputNames.size ();
                                        outputNames.put (fileName, outputName);
                                        result.append ("OutputHolder<" + T + "> * " + outputName + " = outputHelper<" + T + "> (\"" + fileName + "\");\n");
                                    }
                                }
                            }
                        }
                        else  // Dynamic file name (no static handle)
                        {
                            if (f instanceof ReadMatrix)
                            {
                                matrixNames.put (op,       "Matrix"   + matrixNames.size ());
                                stringNames.put (operand0, "fileName" + stringNames.size ());
                            }
                            else if (f instanceof Input)
                            {
                                inputNames .put (op,       "Input"    + inputNames .size ());
                                stringNames.put (operand0, "fileName" + stringNames.size ());
                            }
                            else if (f instanceof Output)
                            {
                                outputNames.put (op,       "Output"   + outputNames.size ());
                                stringNames.put (operand0, "fileName" + stringNames.size ());
                            }
                        }
                    }
                    return true;   // Functions could be nested, so continue descent.
                }
                return true;
            }
        }
        CheckStatic checkStatic = new CheckStatic ();
        for (Variable v : s.ordered)
        {
            checkStatic.global = v.hasAttribute ("global");
            v.visit (checkStatic);
        }
    }

    /**
        Declares all classes, along with their member variables and functions.

        For each part, generates two classes: one for the instances ("local")
        and one for the population as a whole ("global"). Within each class,
        declares buffer classes for integration and derivation, then member
        variables, and finally member functions as appropriate.
    **/
    public void generateDeclarations (EquationSet s, StringBuilder result)
    {
        for (EquationSet p : s.parts) generateDeclarations (p, result);
        generateDeclarationsLocal (s, result);
        generateDeclarationsGlobal (s, result);
    }

    public void generateDeclarationsGlobal (EquationSet s, StringBuilder result)
    {
        BackendDataC bed = (BackendDataC) s.backendData;

        // Population class header
        result.append ("class " + prefix (s) + "_Population : public Population<" + T + ">\n");
        result.append ("{\n");
        result.append ("public:\n");

        // Population buffers
        if (bed.globalDerivative.size () > 0)
        {
            result.append ("  class Derivative\n");
            result.append ("  {\n");
            result.append ("  public:\n");
            for (Variable v : bed.globalDerivative)
            {
                result.append ("    " + type (v) + " " + mangle (v) + ";\n");
            }
            result.append ("    Derivative * next;\n");
            result.append ("  };\n");
            result.append ("\n");
        }
        if (bed.needGlobalPreserve)
        {
            result.append ("  class Preserve\n");
            result.append ("  {\n");
            result.append ("  public:\n");
            for (Variable v : bed.globalIntegrated)
            {
                result.append ("    " + type (v) + " " + mangle (v) + ";\n");
            }
            for (Variable v : bed.globalDerivativePreserve)
            {
                result.append ("    " + type (v) + " " + mangle (v) + ";\n");
            }
            for (Variable v : bed.globalBufferedExternalWriteDerivative)
            {
                result.append ("    " + type (v) + " " + mangle ("next_", v) + ";\n");
            }
            result.append ("  };\n");
            result.append ("\n");
        }

        // Population variables
        if (bed.singleton)
        {
            result.append ("  " + prefix (s) + " instance;\n");
        }
        else
        {
            if (bed.n != null)
            {
                result.append ("  int n;\n");
            }
            if (bed.trackInstances)
            {
                result.append ("  vector<" + prefix (s) + " *> instances;\n");
            }
            else if (bed.index != null)  // The instances vector can supply the next index, so only declare nextIndex if instances was not declared.
            {
                result.append ("  int nextIndex;\n");
            }
            if (bed.newborn >= 0)
            {
                result.append ("  int firstborn;\n");
            }
        }
        if (bed.globalDerivative.size () > 0)
        {
            result.append ("  Derivative * stackDerivative;\n");
        }
        if (bed.needGlobalPreserve)
        {
            result.append ("  Preserve * preserve;\n");
        }
        for (Variable v : bed.globalMembers)
        {
            result.append ("  " + type (v) + " " + mangle (v) + ";\n");
        }
        for (Variable v : bed.globalBufferedExternal)
        {
            result.append ("  " + type (v) + " " + mangle ("next_", v) + ";\n");
        }
        for (String columnName : bed.globalColumns)
        {
            result.append ("  String " + columnName + ";\n");
        }
        if (! bed.globalFlagType.isEmpty ())
        {
            // This should come last, because it can affect alignment.
            result.append ("  " + bed.globalFlagType + " flags;\n");
        }
        result.append ("\n");

        // Population functions
        if (bed.needGlobalCtor)
        {
            result.append ("  " + prefix (s) + "_Population ();\n");
        }
        if (bed.needGlobalDtor)
        {
            result.append ("  virtual ~" + prefix (s) + "_Population ();\n");
        }
        if (! bed.singleton)
        {
            result.append ("  virtual Part<" + T + "> * create ();\n");
            if (bed.index != null)
            {
                result.append ("  virtual void add (Part<" + T + "> * part);\n");
                if (bed.trackInstances)
                {
                    result.append ("  virtual void remove (Part<" + T + "> * part);\n");
                }
            }
        }
        result.append ("  virtual void init ();\n");
        if (bed.globalIntegrated.size () > 0)
        {
            result.append ("  virtual void integrate ();\n");
        }
        if (bed.globalUpdate.size () > 0)
        {
            result.append ("  virtual void update ();\n");
        }
        if (bed.needGlobalFinalize)
        {
            result.append ("  virtual bool finalize ();\n");
        }
        if (bed.canResize)
        {
            result.append ("  virtual void resize (int n);\n");
        }
        if (bed.n != null  &&  ! bed.singleton)
        {
            result.append ("  virtual int getN ();\n");
        }
        if (bed.globalDerivativeUpdate.size () > 0)
        {
            result.append ("  virtual void updateDerivative ();\n");
        }
        if (bed.globalBufferedExternalDerivative.size () > 0)
        {
            result.append ("  virtual void finalizeDerivative ();\n");
        }
        if (bed.needGlobalPreserve)
        {
            result.append ("  virtual void snapshot ();\n");
            result.append ("  virtual void restore ();\n");
        }
        if (bed.globalDerivative.size () > 0)
        {
            result.append ("  virtual void pushDerivative ();\n");
            result.append ("  virtual void multiplyAddToStack (" + T + " scalar);\n");
            result.append ("  virtual void multiply (" + T + " scalar);\n");
            result.append ("  virtual void addToMembers ();\n");
        }
        if (bed.newborn >= 0)
        {
            result.append ("  virtual void clearNew ();\n");
        }
        if (s.connectionBindings != null)
        {
            result.append ("  virtual ConnectIterator<" + T + "> * getIterators ();\n");
            result.append ("  virtual ConnectPopulation<" + T + "> * getIterator (int i);\n");
        }
        if (bed.needGlobalPath)
        {
            result.append ("  virtual void path (String & result);\n");
        }

        // Population class trailer
        result.append ("};\n");
        result.append ("\n");
    }

    public void generateDeclarationsLocal (EquationSet s, StringBuilder result)
    {
        BackendDataC bed = (BackendDataC) s.backendData;

        // Unit class
        result.append ("class " + prefix (s) + " : public PartTime<" + T + ">\n");
        result.append ("{\n");
        result.append ("public:\n");

        // Unit buffers
        if (bed.localDerivative.size () > 0)
        {
            result.append ("  class Derivative\n");
            result.append ("  {\n");
            result.append ("  public:\n");
            for (Variable v : bed.localDerivative)
            {
                result.append ("    " + type (v) + " " + mangle (v) + ";\n");
            }
            result.append ("    Derivative * next;\n");
            result.append ("  };\n");
            result.append ("\n");
        }
        if (bed.needLocalPreserve)
        {
            result.append ("  class Preserve\n");
            result.append ("  {\n");
            result.append ("  public:\n");
            for (Variable v : bed.localIntegrated)
            {
                result.append ("    " + type (v) + " " + mangle (v) + ";\n");
            }
            for (Variable v : bed.localDerivativePreserve)
            {
                result.append ("    " + type (v) + " " + mangle (v) + ";\n");
            }
            for (Variable v : bed.localBufferedExternalWriteDerivative)
            {
                result.append ("    " + type (v) + " " + mangle ("next_", v) + ";\n");
            }
            result.append ("  };\n");
            result.append ("\n");
        }

        // Unit variables
        if (bed.localDerivative.size () > 0)
        {
            result.append ("  Derivative * stackDerivative;\n");
        }
        if (bed.needLocalPreserve)
        {
            result.append ("  Preserve * preserve;\n");
        }
        if (bed.pathToContainer == null)
        {
            result.append ("  " + prefix (s.container) + " * container;\n");
        }
        if (s.connectionBindings != null)
        {
            for (ConnectionBinding c : s.connectionBindings)
            {
                // we should be able to assume that s.container is non-null; ie: a connection should always operate in some larger container
                result.append ("  " + prefix (c.endpoint) + " * " + mangle (c.alias) + ";\n");
            }
        }
        if (s.accountableConnections != null)
        {
            for (EquationSet.AccountableConnection ac : s.accountableConnections)
            {
                result.append ("  int " + prefix (ac.connection) + "_" + mangle (ac.alias) + "_count;\n");
            }
        }
        if (bed.refcount)
        {
            result.append ("  int refcount;\n");
        }
        if (bed.index != null)
        {
            result.append ("  int __24index;\n");
        }
        if (bed.lastT)
        {
            result.append ("  " + T + " lastT;\n");  // $lastT is for internal use only, so no need for __24 prefix.
        }
        for (Variable v : bed.localMembers)
        {
            result.append ("  " + type (v) + " " + mangle (v) + ";\n");
        }
        for (Variable v : bed.localBufferedExternal)
        {
            result.append ("  " + type (v) + " " + mangle ("next_", v) + ";\n");
        }
        for (EquationSet p : s.parts)
        {
            result.append ("  " + prefix (p) + "_Population " + mangle (p.name) + ";\n");
        }
        for (String columnName : bed.localColumns)
        {
            result.append ("  String " + columnName + ";\n");
        }
        for (EventSource es : bed.eventSources)
        {
            result.append ("  std::vector<Part<" + T + "> *> " + "eventMonitor_" + prefix (es.target.container) + ";\n");
        }
        for (EventTarget et : bed.eventTargets)
        {
            if (et.track != null  &&  et.track.name.startsWith ("eventAux"))
            {
                result.append ("  " + T + " " + et.track.name + ";\n");
            }
            if (et.timeIndex >= 0)
            {
                result.append ("  " + T + " eventTime" + et.timeIndex + ";\n");
            }
        }
        if (! bed.localFlagType.isEmpty ())
        {
            result.append ("  " + bed.localFlagType + " flags;\n");
        }
        result.append ("\n");

        // Unit functions
        if (bed.needLocalCtor  ||  s.parts.size () > 0)
        {
            result.append ("  " + prefix (s) + " ();\n");
        }
        if (bed.needLocalDtor)
        {
            result.append ("  virtual ~" + prefix (s) + " ();\n");
        }
        if (bed.localMembers.size () > 0)
        {
            result.append ("  virtual void clear ();\n");
        }
        if (s.container == null)
        {
            result.append ("  virtual void setPeriod (" + T + " dt);\n");
        }
        if (bed.needLocalDie)
        {
            result.append ("  virtual void die ();\n");
        }
        if (bed.localReference.size () > 0)
        {
            result.append ("  virtual void enterSimulation ();\n");
        }
        result.append ("  virtual void leaveSimulation ();\n");
        if (bed.refcount)
        {
            result.append ("  virtual bool isFree ();\n");
        }
        if (bed.needLocalInit  ||  s.parts.size () > 0)
        {
            result.append ("  virtual void init ();\n");
        }
        if (bed.localIntegrated.size () > 0  ||  s.parts.size () > 0)
        {
            result.append ("  virtual void integrate ();\n");
        }
        if (bed.localUpdate.size () > 0  ||  s.parts.size () > 0)
        {
            result.append ("  virtual void update ();\n");
        }
        if (bed.needLocalFinalize  ||  s.parts.size () > 0)
        {
            result.append ("  virtual bool finalize ();\n");
        }
        if (bed.localDerivativeUpdate.size () > 0  ||  s.parts.size () > 0)
        {
            result.append ("  virtual void updateDerivative ();\n");
        }
        if (bed.localBufferedExternalDerivative.size () > 0  ||  s.parts.size () > 0)
        {
            result.append ("  virtual void finalizeDerivative ();\n");
        }
        if (bed.needLocalPreserve  ||  s.parts.size () > 0)
        {
            result.append ("  virtual void snapshot ();\n");
            result.append ("  virtual void restore ();\n");
        }
        if (bed.localDerivative.size () > 0  ||  s.parts.size () > 0)
        {
            result.append ("  virtual void pushDerivative ();\n");
            result.append ("  virtual void multiplyAddToStack (" + T + " scalar);\n");
            result.append ("  virtual void multiply (" + T + " scalar);\n");
            result.append ("  virtual void addToMembers ();\n");
        }
        if (bed.live != null  &&  ! bed.live.hasAttribute ("constant"))
        {
            result.append ("  virtual " + T + " getLive ();\n");
        }
        if (s.connectionBindings == null)
        {
            if (bed.xyz != null)
            {
                result.append ("  virtual void getXYZ (MatrixFixed<" + T + ",3,1> & xyz);\n");
            }
        }
        else
        {
            if (bed.p != null)
            {
                result.append ("  virtual " + T + " getP ();\n");
            }
            if (bed.hasProject)
            {
                result.append ("  virtual void getProject (int i, MatrixFixed<" + T + ",3,1> & xyz);\n");
            }
            result.append ("  virtual void setPart (int i, Part<" + T + "> * part);\n");
            result.append ("  virtual Part<" + T + "> * getPart (int i);\n");
        }
        if (bed.newborn >= 0)
        {
            result.append ("  virtual bool getNewborn ();\n");
        }
        if (s.connectionMatrix != null)
        {
            result.append ("  virtual int mapIndex (int i, int rc);\n");
        }
        if (bed.eventTargets.size () > 0)
        {
            result.append ("  virtual bool eventTest (int i);\n");
            if (bed.needLocalEventDelay)
            {
                result.append ("  virtual " + T + " eventDelay (int i);\n");
            }
            result.append ("  virtual void setLatch (int i);\n");
            if (bed.eventReferences.size () > 0)
            {
                result.append ("  virtual void finalizeEvent ();\n");
            }
        }
        if (bed.accountableEndpoints.size () > 0)
        {
            result.append ("  virtual int getCount (int i);\n");
        }
        if (bed.needLocalPath)
        {
            result.append ("  virtual void path (String & result);\n");
        }

        // Conversions
        Set<Conversion> conversions = s.getConversions ();
        for (Conversion pair : conversions)
        {
            EquationSet source = pair.from;
            EquationSet dest   = pair.to;
            result.append ("  void " + mangle (source.name) + "_2_" + mangle (dest.name) + " (" + mangle (source.name) + " * from, int " + mangle ("$type") + ");\n");
        }

        // Unit class trailer
        result.append ("};\n");
        result.append ("\n");
    }

    public void generateDefinitions (RendererC context, EquationSet s) throws Exception
    {
        for (EquationSet p : s.parts) generateDefinitions (context, p);

        context.setPart (s);
        generateDefinitionsLocal (context);
        generateDefinitionsGlobal (context);
    }

    public void generateDefinitionsGlobal (RendererC context) throws Exception
    {
        EquationSet   s      = context.part;
        BackendDataC  bed    = context.bed;
        StringBuilder result = context.result;
        context.global = true;
        String ns = prefix (s) + "_Population::";  // namespace for all functions associated with part s

        // Population ctor
        if (bed.needGlobalCtor)
        {
            result.append (ns + prefix (s) + "_Population ()\n");
            result.append ("{\n");
            if (! bed.singleton)
            {
                if (bed.n != null)
                {
                    result.append ("  n = 0;\n");
                }
                if (! bed.trackInstances  &&  bed.index != null)
                {
                    result.append ("  nextIndex = 0;\n");
                }
                if (bed.newborn >= 0)
                {
                    result.append ("  firstborn = 0;\n");
                }
            }
            if (bed.globalDerivative.size () > 0)
            {
                result.append ("  stackDerivative = 0;\n");
            }
            if (bed.needGlobalPreserve)
            {
                result.append ("  preserve = 0;\n");
            }
            result.append ("}\n");
            result.append ("\n");
        }

        // Population dtor
        if (bed.needGlobalDtor)
        {
            result.append (ns + "~" + prefix (s) + "_Population ()\n");
            result.append ("{\n");
            if (bed.globalDerivative.size () > 0)
            {
                result.append ("  while (stackDerivative)\n");
                result.append ("  {\n");
                result.append ("    Derivative * temp = stackDerivative;\n");
                result.append ("    stackDerivative = stackDerivative->next;\n");
                result.append ("    delete temp;\n");
                result.append ("  }\n");
            }
            if (bed.needGlobalPreserve)
            {
                result.append ("  if (preserve) delete preserve;\n");
            }
            result.append ("}\n");
            result.append ("\n");
        }

        // Population create
        if (! bed.singleton)  // In the case of a singleton, this will remain a pure virtual function, and throw an exception if called.
        {
            result.append ("Part<" + T + "> * " + ns + "create ()\n");
            result.append ("{\n");
            result.append ("  " + prefix (s) + " * p = new " + prefix (s) + ";\n");
            if (bed.pathToContainer == null) result.append ("  p->container = (" + prefix (s.container) + " *) container;\n");
            result.append ("  return p;\n");
            result.append ("}\n");
            result.append ("\n");
        }

        // Population add / remove
        if (bed.index != null  &&  ! bed.singleton)
        {
            result.append ("void " + ns + "add (Part<" + T + "> * part)\n");
            result.append ("{\n");
            result.append ("  " + prefix (s) + " * p = (" + prefix (s) + " *) part;\n");
            if (bed.trackInstances)
            {
                result.append ("  if (p->__24index < 0)\n");
                result.append ("  {\n");
                result.append ("    p->__24index = instances.size ();\n");
                result.append ("    instances.push_back (p);\n");
                result.append ("  }\n");
                result.append ("  else\n");
                result.append ("  {\n");
                result.append ("    instances[p->__24index] = p;\n");
                result.append ("  }\n");
                if (bed.newborn >= 0)
                {
                    result.append ("  p->flags = (" + bed.localFlagType + ") 0x1 << " + bed.newborn + ";\n");
                    result.append ("  firstborn = min (firstborn, p->__24index);\n");
                }
            }
            else
            {
                result.append ("  if (p->__24index < 0) p->__24index = nextIndex++;\n");
            }
            result.append ("}\n");
            result.append ("\n");

            if (bed.trackInstances)
            {
                result.append ("void " + ns + "remove (Part<" + T + "> * part)\n");
                result.append ("{\n");
                result.append ("  " + prefix (s) + " * p = (" + prefix (s) + " *) part;\n");
                result.append ("  instances[p->__24index] = 0;\n");
                result.append ("  Population<" + T + ">::remove (part);\n");
                result.append ("}\n");
                result.append ("\n");
            }
        }

        // Population init
        result.append ("void " + ns + "init ()\n");
        result.append ("{\n");
        s.setInit (1);
        //   Zero out members
        for (Variable v : bed.globalMembers)
        {
            result.append ("  " + zero (mangle (v), v) + ";\n");
        }
        for (Variable v : bed.globalBufferedExternal)
        {
            result.append ("  " + clearAccumulator (mangle ("next_", v), v, context) + ";\n");
        }
        if (! bed.globalFlagType.isEmpty ())
        {
            result.append ("  flags = 0;\n");
        }
        //   Compute variables
        if (bed.nInitOnly)  // $n is not stored, so we need to declare a local variable to receive its value.
        {
            result.append ("  " + type (bed.n) + " " + mangle (bed.n) + ";\n");
        }
        List<Variable> buffered = bed.globalBuffered;
        bed.globalBuffered = new ArrayList<Variable> ();  // Trick multiconditional() and its subroutines into directly updating members.
        for (Variable v : bed.globalInit)
        {
            multiconditional (v, context, "  ");
        }
        bed.globalBuffered = buffered;
        //   create instances
        if (bed.singleton)
        {
            result.append ("  instance.enterSimulation ();\n");
            result.append ("  container->getEvent ()->enqueue (&instance);\n");
            result.append ("  instance.init ();\n");
        }
        else
        {
            if (bed.n != null)
            {
                result.append ("  resize (" + resolve (bed.n.reference, context, bed.nInitOnly));
                if (context.useExponent) result.append (context.printShift (bed.n.exponent - Operator.MSB));
                result.append (");\n");
            }
        }
        //   make connections
        if (s.connectionBindings != null)
        {
            result.append ("  Simulator<" + T + ">::instance.connect (this);\n");  // queue to evaluate our connections
        }
        s.setInit (0);
        result.append ("};\n");
        result.append ("\n");

        // Population integrate
        if (bed.globalIntegrated.size () > 0)
        {
            result.append ("void " + ns + "integrate ()\n");
            result.append ("{\n");
            result.append ("  EventStep<" + T + "> * event = getEvent ();\n");
            context.hasEvent = true;
            result.append ("  " + T + " dt = event->dt;\n");
            result.append ("  if (preserve)\n");
            result.append ("  {\n");
            for (Variable v : bed.globalIntegrated)
            {
                result.append ("    " + resolve (v.reference, context, false) + " = preserve->" + mangle (v) + " + ");
                // For fixed-point:
                // raw result = exponentDerivative+exponentTime-MSB
                // shift = raw-exponentVariable = exponentDerivative+exponentTime-MSB-exponentVariable
                int shift = v.derivative.exponent + bed.dt.exponent - Operator.MSB - v.exponent;
                if (shift != 0  &&  T.equals ("int"))
                {
                    result.append ("(int) ((int64_t) " + resolve (v.derivative.reference, context, false) + " * dt" + context.printShift (shift) + ");\n");
                }
                else
                {
                    result.append (                      resolve (v.derivative.reference, context, false) + " * dt;\n");
                }
            }
            result.append ("  }\n");
            result.append ("  else\n");
            result.append ("  {\n");
            for (Variable v : bed.globalIntegrated)
            {
                result.append ("    " + resolve (v.reference, context, false) + " += ");
                int shift = v.derivative.exponent + bed.dt.exponent - Operator.MSB - v.exponent;
                if (shift != 0  &&  T.equals ("int"))
                {
                    result.append ("(int) ((int64_t) " + resolve (v.derivative.reference, context, false) + " * dt" + context.printShift (shift) + ");\n");
                }
                else
                {
                    result.append (                      resolve (v.derivative.reference, context, false) + " * dt;\n");
                }
            }
            result.append ("  }\n");
            context.hasEvent = false;
            result.append ("};\n");
            result.append ("\n");
        }

        // Population update
        if (bed.globalUpdate.size () > 0)
        {
            result.append ("void " + ns + "update ()\n");
            result.append ("{\n");

            for (Variable v : bed.globalBufferedInternalUpdate)
            {
                result.append ("  " + type (v) + " " + mangle ("next_", v) + ";\n");
            }
            for (Variable v : bed.globalUpdate)
            {
                multiconditional (v, context, "  ");
            }
            for (Variable v : bed.globalBufferedInternalUpdate)
            {
                result.append ("  " + mangle (v) + " = " + mangle ("next_", v) + ";\n");
            }

            result.append ("};\n");
            result.append ("\n");
        }

        // Population finalize
        if (bed.needGlobalFinalize)
        {
            result.append ("bool " + ns + "finalize ()\n");
            result.append ("{\n");

            if (bed.canResize  &&  bed.n.derivative == null  &&  bed.canGrowOrDie)  // $n shares control with other specials, so must coordinate with them
            {
                // $n may be assigned during the regular update cycle, so we need to monitor it.
                result.append ("  if (" + mangle ("$n") + " != " + mangle ("next_", "$n") + ") Simulator<" + T + ">::instance.resize (this, " + mangle ("next_", "$n"));
                if (context.useExponent) result.append (context.printShift (bed.n.exponent - Operator.MSB));
                result.append (");\n");
                result.append ("  else Simulator<" + T + ">::instance.resize (this, -1);\n");
            }

            for (Variable v : bed.globalBufferedExternal)
            {
                result.append ("  " + mangle (v) + " = " + mangle ("next_", v) + ";\n");
            }
            for (Variable v : bed.globalBufferedExternalWrite)
            {
                result.append ("  " + clearAccumulator (mangle ("next_", v), v, context) + ";\n");
            }

            // Return value is generally ignored, except for top-level population.
            boolean returnN = bed.needGlobalFinalizeN;
            if (bed.canResize)  
            {
                if (bed.canGrowOrDie)
                {
                    if (bed.n.derivative != null)  // $n' exists
                    {
                        // the rate of change in $n is pre-determined, so it relentlessly overrides any other structural dynamics
                        if (returnN)
                        {
                            result.append ("  if (n == 0) return false;\n");
                            returnN = false;
                        }
                        result.append ("  Simulator<" + T + ">::instance.resize (this, " + mangle ("$n"));
                        if (context.useExponent) result.append (context.printShift (bed.n.exponent - Operator.MSB));
                        result.append (");\n");
                    }
                }
                else  // $n is the only kind of structural dynamics, so simply do a resize() when needed
                {
                    if (returnN)
                    {
                        result.append ("  if (n == 0) return false;\n");
                        returnN = false;
                    }
                    result.append ("  int floorN = ");
                    if (context.useExponent) result.append (mangle ("$n") + context.printShift (bed.n.exponent - Operator.MSB) + ";");
                    else                     result.append ("(int) " + mangle ("$n"));
                    result.append ("  if (n != floorN) Simulator<" + T + ">::instance.resize (this, floorN);\n");
                }
            }

            if (returnN)
            {
                result.append ("  return n;\n");
            }
            else
            {
                result.append ("  return true;\n");
            }
            result.append ("};\n");
            result.append ("\n");
        }

        // Population resize()
        if (bed.canResize)
        {
            result.append ("void " + ns + "resize (int n)\n");
            result.append ("{\n");
            if (bed.canGrowOrDie  &&  bed.n.derivative == null)
            {
                result.append ("  if (n < 0)\n");
                result.append ("  {\n");
                result.append ("    " + mangle ("$n") + " = this->n;\n");
                result.append ("    return;\n");
                result.append ("  }\n");
                result.append ("\n");
            }
            result.append ("  Population<" + T + ">::resize (n);\n");
            result.append ("\n");
            result.append ("  for (int i = instances.size () - 1; this->n > n  &&  i >= 0; i--)\n");
            result.append ("  {\n");
            result.append ("    Part * p = instances[i];\n");
            result.append ("    if (p  &&  p->getLive ()) p->die ();\n");
            result.append ("  }\n");
            result.append ("};\n");
            result.append ("\n");
        }

        // Population getN
        if (bed.n != null  &&  ! bed.singleton)
        {
            result.append ("int " + ns + "getN ()\n");
            result.append ("{\n");
            result.append ("  return n;\n");
            result.append ("};\n");
            result.append ("\n");
        }

        // Population updateDerivative
        if (bed.globalDerivativeUpdate.size () > 0)
        {
            result.append ("void " + ns + "updateDerivative ()\n");
            result.append ("{\n");
            for (Variable v : bed.globalBufferedInternalDerivative)
            {
                result.append ("  " + type (v) + " " + mangle ("next_", v) + ";\n");
            }
            for (Variable v : bed.globalDerivativeUpdate)
            {
                multiconditional (v, context, "  ");
            }
            for (Variable v : bed.globalBufferedInternalDerivative)
            {
                result.append ("  " + mangle (v) + " = " + mangle ("next_", v) + ";\n");
            }
            result.append ("};\n");
            result.append ("\n");
        }

        // Population finalizeDerivative
        if (bed.globalBufferedExternalDerivative.size () > 0)
        {
            result.append ("void " + ns + "finalizeDerivative ()\n");
            result.append ("{\n");
            for (Variable v : bed.globalBufferedExternalDerivative)
            {
                result.append ("  " + mangle (v) + " = " + mangle ("next_", v) + ";\n");
            }
            for (Variable v : bed.globalBufferedExternalWriteDerivative)
            {
                result.append ("  " + clearAccumulator (mangle ("next_", v), v, context) + ";\n");
            }
            result.append ("};\n");
            result.append ("\n");
        }

        if (bed.needGlobalPreserve)
        {
            // Population snapshot
            result.append ("void " + ns + "snapshot ()\n");
            result.append ("{\n");
            result.append ("  preserve = new Preserve;\n");
            for (Variable v : bed.globalIntegrated)
            {
                result.append ("  preserve->" + mangle (v) + " = " + mangle (v) + ";\n");
            }
            for (Variable v : bed.globalDerivativePreserve)
            {
                result.append ("  preserve->" + mangle (v) + " = " + mangle (v) + ";\n");
            }
            for (Variable v : bed.globalBufferedExternalWriteDerivative)
            {
                result.append ("  preserve->" + mangle ("next_", v) + " = " + mangle ("next_", v) + ";\n");
                result.append ("  " + clearAccumulator (mangle ("next_", v), v, context) + ";\n");
            }
            result.append ("};\n");
            result.append ("\n");

            // Population restore
            result.append ("void " + ns + "restore ()\n");
            result.append ("{\n");
            for (Variable v : bed.globalDerivativePreserve)
            {
                result.append ("  " + mangle (v) + " = preserve->" + mangle (v) + ";\n");
            }
            for (Variable v : bed.globalBufferedExternalWriteDerivative)
            {
                result.append ("  " + mangle ("next_", v) + " = preserve->" + mangle ("next_", v) + ";\n");
            }
            result.append ("  delete preserve;\n");
            result.append ("  preserve = 0;\n");
            result.append ("};\n");
            result.append ("\n");
        }

        if (bed.globalDerivative.size () > 0)
        {
            // Population pushDerivative
            result.append ("void " + ns + "pushDerivative ()\n");
            result.append ("{\n");
            result.append ("  Derivative * temp = new Derivative;\n");
            result.append ("  temp->_next = stackDerivative;\n");
            result.append ("  stackDerivative = temp;\n");
            for (Variable v : bed.globalDerivative)
            {
                result.append ("  temp->" + mangle (v) + " = " + mangle (v) + ";\n");
            }
            result.append ("};\n");
            result.append ("\n");

            // Population multiplyAddToStack
            result.append ("void " + ns + "multiplyAddToStack (" + T + " scalar)\n");
            result.append ("{\n");
            for (Variable v : bed.globalDerivative)
            {
                result.append ("  stackDerivative->" + mangle (v) + " += ");
                if (T.equals ("int"))
                {
                    result.append ("(int) ((int64_t) " + mangle (v) + " * scalar >> " + (Operator.MSB - 1) + ");\n");
                }
                else
                {
                    result.append (                      mangle (v) + " * scalar;\n");
                }
            }
            result.append ("};\n");
            result.append ("\n");

            // Population multiply
            result.append ("void " + ns + "multiply (" + T + " scalar)\n");
            result.append ("{\n");
            for (Variable v : bed.globalDerivative)
            {
                if (T.equals ("int"))
                {
                    result.append ("  " + mangle (v) + " = (int64_t) " + mangle (v) + " * scalar >> " + (Operator.MSB - 1) + ";\n");
                }
                else
                {
                    result.append ("  " + mangle (v) + " *= scalar;\n");
                }
            }
            result.append ("};\n");
            result.append ("\n");

            // Population addToMembers
            result.append ("void " + ns + "addToMembers ()\n");
            result.append ("{\n");
            for (Variable v : bed.globalDerivative)
            {
                result.append ("  " + mangle (v) + " += stackDerivative->" + mangle (v) + ";\n");
            }
            result.append ("  Derivative * temp = stackDerivative;\n");
            result.append ("  stackDerivative = stackDerivative->next;\n");
            result.append ("  delete temp;\n");
            result.append ("};\n");
            result.append ("\n");
        }

        // Population clearNew
        if (bed.newborn >= 0)
        {
            result.append ("void " + ns + "clearNew ()\n");
            result.append ("{\n");
            result.append ("  flags &= ~((" + bed.globalFlagType + ") 0x1 << " + bed.clearNew + ");\n");  // Reset our clearNew flag
            if (bed.singleton)
            {
                result.append ("  instance.flags &= ~((" + bed.localFlagType + ") 0x1 << " + bed.newborn + ");\n");
            }
            else
            {
                result.append ("  int count = instances.size ();\n");
                result.append ("  for (int i = firstborn; i < count; i++)\n");
                result.append ("  {\n");
                result.append ("    " + prefix (s) + " * p = instances[i];\n");
                result.append ("    if (p) p->flags &= ~((" + bed.localFlagType + ") 0x1 << " + bed.newborn + ");\n");
                result.append ("  }\n");
                result.append ("  firstborn = count;\n");
            }
            result.append ("}\n");
            result.append ("\n");
        }

        // Population getIterators
        if (s.connectionBindings != null)
        {
            class ConnectionHolder
            {
                public Operator      k;
                public Operator      min;
                public Operator      max;
                public Operator      radius;
                public boolean       hasProject;
                public EquationSet   endpoint;
                public List<Integer> indices = new ArrayList<Integer> ();
                public List<Object>  resolution;

                public boolean equivalent (Operator a, Operator b)
                {
                    if (a == b) return true;
                    if (a == null  ||  b == null) return false;
                    return a.equals (b);
                }

                public boolean equals (Object o)
                {
                    ConnectionHolder that = (ConnectionHolder) o;  // This is a safe assumption, since this is a local class.
                    return    equivalent (k,      that.k)
                           && equivalent (min,    that.min)
                           && equivalent (max,    that.max)
                           && equivalent (radius, that.radius)
                           && hasProject == that.hasProject
                           && endpoint   == that.endpoint;
                }

                public void emit ()
                {
                    for (Integer index : indices)
                    {
                        result.append ("    case " + index + ":\n");
                    }
                    result.append ("    {\n");
                    if (k == null  &&  radius == null)
                    {
                        result.append ("      result = new ConnectPopulation<" + T + "> (i);\n");
                    }
                    else
                    {
                        result.append ("      result = new ConnectPopulationNN<" + T + "> (i);\n");  // Pulls in KDTree dependencies, for full NN support.
                    }

                    boolean testK      = false;
                    boolean testRadius = false;
                    boolean constantKR = false;

                    if (k != null)
                    {
                        result.append ("      result->k = ");
                        k.render (context);
                        result.append (";\n");
                        testK = true;
                        if (k instanceof Constant)
                        {
                            Constant c = (Constant) k;
                            if (c.value instanceof Scalar  &&  ((Scalar) c.value).value != 0) constantKR = true;
                        }
                    }
                    if (max != null)
                    {
                        result.append ("      result->Max = ");
                        max.render (context);
                        result.append (";\n");
                    }
                    if (min != null)
                    {
                        result.append ("      result->Min = ");
                        min.render (context);
                        result.append (";\n");
                    }
                    if (radius != null)
                    {
                        result.append ("      result->radius = ");
                        radius.render (context);
                        result.append (";\n");
                        testRadius = true;
                        if (radius instanceof Constant)
                        {
                            Constant c = (Constant) radius;
                            if (c.value instanceof Scalar  &&  ((Scalar) c.value).value != 0) constantKR = true;
                        }
                    }
                    if (hasProject)
                    {
                        result.append ("      result->rank += 1;");
                    }
                    if (constantKR)
                    {
                        result.append ("      result->rank -= 2;\n");
                    }
                    else
                    {
                        if (testK  &&  testRadius)
                        {
                            result.append ("      if (result->k > 0  ||  result->radius > 0) result->rank -= 2;\n");
                        }
                        else if (testK)
                        {
                            result.append ("      if (result->k > 0) result->rank -= 2;\n");
                        }
                        else if (testRadius)
                        {
                            result.append ("      if (result->radius > 0) result->rank -= 2;\n");
                        }
                    }

                    assembleInstances (s, "", resolution, 0, "      ", result);
                    result.append ("      result->size = result->instances->size ();\n");

                    result.append ("      break;\n");
                    result.append ("    }\n");
                }
            }

            List<ConnectionHolder> connections = new ArrayList<ConnectionHolder> ();
            boolean needNN = false;  // TODO: Should determine this across the entire simulation, so that only one of getIteratorsSimple() or getIteratorsNN() is linked.
            for (ConnectionBinding c : s.connectionBindings)
            {
                ConnectionHolder h = new ConnectionHolder ();

                Variable v = s.find (new Variable (c.alias + ".$k"));
                EquationEntry e = null;
                if (v != null) e = v.equations.first ();
                if (e != null) h.k = e.expression;

                v = s.find (new Variable (c.alias + ".$max"));
                e = null;
                if (v != null) e = v.equations.first ();
                if (e != null) h.max = e.expression;

                v = s.find (new Variable (c.alias + ".$min"));
                e = null;
                if (v != null) e = v.equations.first ();
                if (e != null) h.min = e.expression;

                v = s.find (new Variable (c.alias + ".$radius"));
                e = null;
                if (v != null) e = v.equations.first ();
                if (e != null) h.radius = e.expression;

                h.hasProject = s.find (new Variable (c.alias + ".$project")) != null;
                h.endpoint = c.endpoint;

                int i = connections.indexOf (h);
                if (i < 0)
                {
                    connections.add (h);
                    h.resolution = c.resolution;
                }
                else
                {
                    h = connections.get (i);
                }
                h.indices.add (c.index);

                if (h.k != null  ||  h.radius != null) needNN = true;
            }


            result.append ("ConnectIterator<" + T + "> * " + ns + "getIterators ()\n");
            result.append ("{\n");
            if (s.connectionMatrix == null)
            {
                if (needNN)
                {
                    result.append ("  return getIteratorsNN ();\n");
                }
                else
                {
                    result.append ("  return getIteratorsSimple ();\n");
                }
            }
            else
            {
                ConnectionMatrix cm = s.connectionMatrix;
                result.append ("  ConnectPopulation<" + T + "> * rows = getIterator (" + cm.rows.index + ");\n");
                result.append ("  ConnectPopulation<" + T + "> * cols = getIterator (" + cm.cols.index + ");\n");

                String matrixName = matrixNames.get (cm.A.operands[0].toString ());
                result.append ("  IteratorNonzero<" + T + "> * it = " + matrixName + "->getIterator ();\n");

                result.append ("  Part<" + T + "> * dummy = create ();\n");
                result.append ("  return new ConnectMatrix<" + T + "> (rows, cols, it, dummy);\n");
            }
            result.append ("}\n");
            result.append ("\n");


            result.append ("ConnectPopulation<" + T + "> * " + ns + "getIterator (int i)\n");
            result.append ("{\n");
            result.append ("  ConnectPopulation<" + T + "> * result = 0;\n");
            result.append ("  switch (i)\n");
            result.append ("  {\n");
            for (ConnectionHolder h : connections) h.emit ();
            result.append ("  }\n");
            result.append ("  return result;\n");
            result.append ("}\n");
            result.append ("\n");
        }

        // Population path
        if (bed.needGlobalPath)
        {
            result.append ("void " + ns + "path (String & result)\n");
            result.append ("{\n");
            if (((BackendDataC) s.container.backendData).needLocalPath)  // Will our container provide a non-empty path?
            {
                result.append ("  container->path (result);\n");
                result.append ("  result += \"." + s.name + "\";\n");
            }
            else
            {
                result.append ("  result = \"" + s.name + "\";\n");
            }
            result.append ("}\n");
            result.append ("\n");
        }
    }

    public void generateDefinitionsLocal (RendererC context) throws Exception
    {
        EquationSet   s      = context.part;
        BackendDataC  bed    = context.bed;
        StringBuilder result = context.result;
        context.global = false;
        String ns = prefix (s) + "::";

        // Unit ctor
        if (bed.needLocalCtor  ||  s.parts.size () > 0)
        {
            result.append (ns + prefix (s) + " ()\n");
            result.append ("{\n");
            if (bed.localDerivative.size () > 0)
            {
                result.append ("  stackDerivative = 0;\n");
            }
            if (bed.needLocalPreserve)
            {
                result.append ("  preserve = 0;\n");
            }
            for (EquationSet p : s.parts)
            {
                result.append ("  " + mangle (p.name) + ".container = this;\n");
                BackendDataC pbed = (BackendDataC) p.backendData;
                if (pbed.singleton)
                {
                    result.append ("  " + mangle (p.name) + ".instance.container = this;\n");
                }
            }
            if (s.accountableConnections != null)
            {
                for (EquationSet.AccountableConnection ac : s.accountableConnections)
                {
                    result.append ("  int " + prefix (ac.connection) + "_" + mangle (ac.alias) + "_count = 0;\n");
                }
            }
            if (bed.refcount)
            {
                result.append ("  refcount = 0;\n");
            }
            if (bed.index != null)
            {
                result.append ("  __24index = -1;\n");  // -1 indicates that an index needs to be assigned. This should only be done once.
            }
            if (bed.localMembers.size () > 0)
            {
                result.append ("  clear ();\n");
            }
            result.append ("}\n");
            result.append ("\n");
        }

        // Unit dtor
        if (bed.needLocalDtor)
        {
            result.append (ns + "~" + prefix (s) + " ()\n");
            result.append ("{\n");
            if (bed.localDerivative.size () > 0)
            {
                result.append ("  while (stackDerivative)\n");
                result.append ("  {\n");
                result.append ("    Derivative * temp = stackDerivative;\n");
                result.append ("    stackDerivative = stackDerivative->next;\n");
                result.append ("    delete temp;\n");
                result.append ("  }\n");
            }
            if (bed.needLocalPreserve)
            {
                result.append ("  if (preserve) delete preserve;\n");
            }
            result.append ("}\n");
            result.append ("\n");
        }

        // Unit clear
        if (bed.localMembers.size () > 0)
        {
            result.append ("void " + ns + "clear ()\n");
            result.append ("{\n");
            for (Variable v : bed.localMembers)
            {
                result.append ("  " + zero (mangle (v), v) + ";\n");
            }
            result.append ("}\n");
            result.append ("\n");
        }

        // Unit setPeriod
        if (s.container == null)  // instance of top-level population, so set period on wrapper whenever our period changes
        {
            result.append ("void " + ns + "setPeriod (" + T + " dt)\n");
            result.append ("{\n");
            result.append ("  PartTime<" + T + ">::setPeriod (dt);\n");
            result.append ("  if (container->visitor->event != visitor->event) container->setPeriod (dt);\n");
            result.append ("}\n");
            result.append ("\n");
        }

        // Unit die
        if (bed.needLocalDie)
        {
            result.append ("void " + ns + "die ()\n");
            result.append ("{\n");

            // tag part as dead
            if (bed.liveFlag >= 0)  // $live is stored in this part
            {
                result.append ("  flags &= ~((" + bed.localFlagType + ") 0x1 << " + bed.liveFlag + ");\n");
            }

            // instance counting
            if (bed.n != null  &&  ! bed.singleton) result.append ("  container->" + mangle (s.name) + ".n--;\n");

            for (String alias : bed.accountableEndpoints)
            {
                result.append ("  " + mangle (alias) + "->" + prefix (s) + "_" + mangle (alias) + "_count--;\n");
            }

            // release event monitors
            for (EventTarget et : bed.eventTargets)
            {
                for (EventSource es : et.sources)
                {
                    String part = "";
                    if (es.reference != null) part = resolveContainer (es.reference, context, "");
                    result.append ("  removeMonitor (" + part + "eventMonitor_" + prefix (s) + ", this);\n");
                }
            }

            result.append ("}\n");
            result.append ("\n");
        }

        // Unit enterSimulation
        if (bed.localReference.size () > 0)
        {
            result.append ("void " + ns + "enterSimulation ()\n");
            result.append ("{\n");
            TreeSet<String> touched = new TreeSet<String> ();  // String rather than EquationSet, because we may have references to several different instances of the same EquationSet, and all must be accounted
            for (VariableReference r : bed.localReference)
            {
                String container = resolveContainer (r, context, "");
                if (touched.add (container)) result.append ("  " + container + "refcount++;\n");
            }
            result.append ("}\n");
            result.append ("\n");
        }

        // Unit leaveSimulation
        {
            result.append ("void " + ns + "leaveSimulation ()\n");
            result.append ("{\n");
            if (! bed.singleton)
            {
                result.append ("  " + containerOf (s, false, "") + mangle (s.name) + ".remove (this);\n");
            }
            TreeSet<String> touched = new TreeSet<String> ();
            for (VariableReference r : bed.localReference)
            {
                String container = resolveContainer (r, context, "");
                if (touched.add (container)) result.append ("  " + container + "refcount--;\n");
            }
            result.append ("}\n");
            result.append ("\n");
        }

        // Unit isFree
        if (bed.refcount)
        {
            result.append ("bool " + ns + "isFree ()\n");
            result.append ("{\n");
            result.append ("  return refcount == 0;\n");
            result.append ("}\n");
            result.append ("\n");
        }

        // Unit init
        if (bed.needLocalInit  ||  s.parts.size () > 0)
        {
            result.append ("void " + ns + "init ()\n");
            result.append ("{\n");
            s.setInit (1);

            for (Variable v : bed.localBufferedExternal)
            {
                result.append ("  " + clearAccumulator (mangle ("next_", v), v, context) + ";\n");
            }
            for (EventTarget et : bed.eventTargets)
            {
                if (et.track != null  &&  et.track.name.startsWith ("eventAux"))
                {
                    result.append ("  " + et.track.name + " = 0;\n");
                }
                if (et.timeIndex >= 0)
                {
                    result.append ("  eventTime" + et.timeIndex + " = 10;\n");  // Normal values are modulo 1 second. This initial value guarantees no match.
                }
            }
            if (! bed.localFlagType.isEmpty ())
            {
                if (bed.liveFlag >= 0)
                {
                    if (bed.newborn >= 0)
                    {
                        result.append ("  flags |= (" + bed.localFlagType + ") 0x1 << " + bed.liveFlag + ";\n");
                    }
                    else
                    {
                        result.append ("  flags = (" + bed.localFlagType + ") 0x1 << " + bed.liveFlag + ";\n");
                    }
                }
                else
                {
                    if (bed.newborn < 0)
                    {
                        result.append ("  flags = 0;\n");
                    }
                    // else flags has already been initialized by Population::add()
                }
            }

            // Compute variables
            if (bed.localBuffered.contains (bed.dt))
            {
                result.append ("  EventStep<" + T + "> * event = getEvent ();\n");
                context.hasEvent = true;
                result.append ("  " + type (bed.dt) + " " + mangle (bed.dt) + ";\n");
            }
            if (bed.lastT)
            {
                result.append ("  lastT = Simulator<" + T + ">::instance.currentEvent->t;\n");
            }
            //   The following code tricks multiconditional() into treating all variables
            //   as unbuffered and non-accumulating.
            List<Variable> buffered = bed.localBuffered;
            bed.localBuffered = new ArrayList<Variable> ();
            for (Variable v : bed.localInit)
            {
                int assignment = v.assignment;
                v.assignment = Variable.REPLACE;
                multiconditional (v, context, "  ");
                v.assignment = assignment;
            }
            bed.localBuffered = buffered;
            if (bed.localBuffered.contains (bed.dt))
            {
                result.append ("  if (" + mangle (bed.dt) + " != event->dt) setPeriod (" + mangle (bed.dt) + ");\n");
            }

            // instance counting
            if (bed.n != null  &&  ! bed.singleton) result.append ("  " + containerOf (s, false, "") + mangle (s.name) + ".n++;\n");

            for (String alias : bed.accountableEndpoints)
            {
                result.append ("  " + mangle (alias) + "->" + prefix (s) + "_" + mangle (alias) + "_count++;\n");
            }

            // Request event monitors
            for (EventTarget et : bed.eventTargets)
            {
                for (EventSource es : et.sources)
                {
                    String part = "";
                    if (es.reference != null) part = resolveContainer (es.reference, context, "");
                    result.append ("  " + part + "eventMonitor_" + prefix (s) + ".push_back (this);\n");
                }
            }

            // contained populations
            for (EquationSet e : s.parts)
            {
                result.append ("  " + mangle (e.name) + ".init ();\n");
            }

            s.setInit (0);
            context.hasEvent = false;
            result.append ("}\n");
            result.append ("\n");
        }

        // Unit integrate
        if (bed.localIntegrated.size () > 0  ||  s.parts.size () > 0)
        {
            result.append ("void " + ns + "integrate ()\n");
            result.append ("{\n");
            if (bed.localIntegrated.size () > 0)
            {
                if (bed.lastT)
                {
                    result.append ("  " + T + " dt = Simulator<" + T + ">::instance.currentEvent->t - lastT;\n");
                }
                else
                {
                    result.append ("  EventStep<" + T + "> * event = getEvent ();\n");
                    context.hasEvent = true;
                    result.append ("  " + T + " dt = event->dt;\n");
                }
                // Note the resolve() call on the left-hand-side below has lvalue==false.
                // Integration always takes place in the primary storage of a variable.
                result.append ("  if (preserve)\n");
                result.append ("  {\n");
                for (Variable v : bed.localIntegrated)
                {
                    result.append ("    " + resolve (v.reference, context, false) + " = preserve->" + mangle (v) + " + ");
                    int shift = v.derivative.exponent + bed.dt.exponent - Operator.MSB - v.exponent;
                    if (shift != 0  &&  T.equals ("int"))
                    {
                        result.append ("(int) ((int64_t) " + resolve (v.derivative.reference, context, false) + " * dt" + context.printShift (shift) + ");\n");
                    }
                    else
                    {
                        result.append (                      resolve (v.derivative.reference, context, false) + " * dt;\n");
                    }
                }
                result.append ("  }\n");
                result.append ("  else\n");
                result.append ("  {\n");
                for (Variable v : bed.localIntegrated)
                {
                    result.append ("    " + resolve (v.reference, context, false) + " += ");
                    int shift = v.derivative.exponent + bed.dt.exponent - Operator.MSB - v.exponent;
                    if (shift != 0  &&  T.equals ("int"))
                    {
                        result.append ("(int) ((int64_t) " + resolve (v.derivative.reference, context, false) + " * dt" + context.printShift (shift) + ");\n");
                    }
                    else
                    {
                        result.append (                      resolve (v.derivative.reference, context, false) + " * dt;\n");
                    }
                }
                result.append ("  }\n");
            }
            // contained populations
            for (EquationSet e : s.parts)
            {
                result.append ("  " + mangle (e.name) + ".integrate ();\n");
            }
            context.hasEvent = false;
            result.append ("}\n");
            result.append ("\n");
        }

        // Unit update
        if (bed.localUpdate.size () > 0  ||  s.parts.size () > 0)
        {
            result.append ("void " + ns + "update ()\n");
            result.append ("{\n");
            for (Variable v : bed.localBufferedInternalUpdate)
            {
                result.append ("  " + type (v) + " " + mangle ("next_", v) + ";\n");
            }
            for (Variable v : bed.localUpdate)
            {
                multiconditional (v, context, "  ");
            }
            for (Variable v : bed.localBufferedInternalUpdate)
            {
                result.append ("  " + mangle (v) + " = " + mangle ("next_", v) + ";\n");
            }
            // contained populations
            for (EquationSet e : s.parts)
            {
                result.append ("  " + mangle (e.name) + ".update ();\n");
            }
            result.append ("}\n");
            result.append ("\n");
        }

        // Unit finalize
        if (bed.needLocalFinalize  ||  s.parts.size () > 0)
        {
            result.append ("bool " + ns + "finalize ()\n");
            result.append ("{\n");

            // contained populations
            for (EquationSet e : s.parts)
            {
                result.append ("  " + mangle (e.name) + ".finalize ();\n");  // ignore return value
            }

            // Early-out if we are already dead
            if (bed.liveFlag >= 0)  // $live is stored in this part
            {
                result.append ("  if (! (flags & (" + bed.localFlagType + ") 0x1 << " + bed.liveFlag + ")) return false;\n");  // early-out if we are already dead, to avoid another call to die()
            }

            // Preemptively fetch current event
            boolean needT = bed.eventSources.size () > 0  ||  s.lethalP;
            for (Variable v : bed.localBufferedExternal)
            {
                if (v == bed.dt) needT = true;
            }
            if (needT)
            {
                result.append ("  EventStep<" + T + "> * event = getEvent ();\n");
                context.hasEvent = true;
            }

            // Events
            boolean declaredFire = false;
            for (EventSource es : bed.eventSources)
            {
                EventTarget et = es.target;
                String eventMonitor = "eventMonitor_" + prefix (et.container);

                if (es.testEach)
                {
                    result.append ("  for (Part * p : " + eventMonitor + ")\n");
                    result.append ("  {\n");
                    result.append ("    if (! p  ||  ! p->eventTest (" + et.valueIndex + ")) continue;\n");
                    eventGenerate ("    ", et, context, false);
                    result.append ("  }\n");
                }
                else  // All monitors share same condition, so only test one.
                {
                    if (declaredFire)
                    {
                        result.append ("  fire = false;\n");
                    }
                    else
                    {
                        result.append ("  bool fire = false;\n");
                        declaredFire = true;
                    }
                    result.append ("  for (auto p : " + eventMonitor + ")\n");  // Find first non-null part.
                    result.append ("  {\n");
                    result.append ("    if (p)\n");
                    result.append ("    {\n");
                    result.append ("      fire = p->eventTest (" + et.valueIndex + ");\n");
                    result.append ("      break;\n");
                    result.append ("    }\n");
                    result.append ("  }\n");
                    result.append ("  if (fire)\n");
                    result.append ("  {\n");
                    if (es.delayEach)  // Each target instance may require a different delay.
                    {
                        result.append ("    for (auto p : " + eventMonitor + ")\n");
                        result.append ("    {\n");
                        result.append ("      if (! p) continue;\n");
                        eventGenerate ("      ", et, context, false);
                        result.append ("    }\n");
                    }
                    else  // All delays are the same.
                    {
                        eventGenerate ("    ", et, context, true);
                    }
                    result.append ("  }\n");
                }
            }
            int eventCount = bed.eventTargets.size ();
            if (eventCount > 0)
            {
                result.append ("  flags &= ~(" + bed.localFlagType + ") 0 << " + eventCount + ";\n");
            }

            // Finalize variables
            if (bed.lastT)
            {
                result.append ("  lastT = Simulator<" + T + ">::instance.currentEvent.t;\n");
            }
            for (Variable v : bed.localBufferedExternal)
            {
                if (v == bed.dt)
                {
                    result.append ("  if (" + mangle ("next_", v) + " != event->dt) setPeriod (" + mangle ("next_", v) + ");\n");
                }
                else
                {
                    result.append ("  " + mangle (v) + " = " + mangle ("next_", v) + ";\n");
                }
            }
            for (Variable v : bed.localBufferedExternalWrite)
            {
                result.append ("  " + clearAccumulator (mangle ("next_", v), v, context) + ";\n");
            }

            if (bed.type != null)
            {
                result.append ("  switch (" + mangle ("$type") + ")\n");
                result.append ("  {\n");
                // Each "split" is one particular set of new parts to transform into.
                // Each combination requires a separate piece of code. Thus, the outer
                // structure here is a switch statement. Each case within the switch implements
                // a particular combination of new parts. At this point, $type merely indicates
                // which combination to process. Afterward, it will be set to an index within that
                // combination, per the N2A language document.
                int countSplits = s.splits.size ();
                for (int i = 0; i < countSplits; i++)
                {
                    ArrayList<EquationSet> split = s.splits.get (i);

                    // Check if $type = me. Ignore this particular case, since it is a null operation
                    if (split.size () == 1  &&  split.get (0) == s)
                    {
                        continue;
                    }

                    result.append ("    case " + i + ":\n");
                    result.append ("    {\n");
                    boolean used = false;  // indicates that this instance is one of the resulting parts
                    int countParts = split.size ();
                    for (int j = 0; j < countParts; j++)
                    {
                        EquationSet to = split.get (j);
                        if (to == s  &&  ! used)
                        {
                            used = true;
                            result.append ("      " + mangle ("$type") + " = " + (j + 1) + ";\n");
                        }
                        else
                        {
                            result.append ("      " + containerOf (s, false, "") + mangle (s.name) + "_2_" + mangle (to.name) + " (this, " + (j + 1) + ");\n");
                        }
                    }
                    if (used)
                    {
                        result.append ("      break;\n");
                    }
                    else
                    {
                        result.append ("      die ();\n");
                        result.append ("      return false;\n");
                    }
                    result.append ("    }\n");
                }
                result.append ("  }\n");
            }

            if (s.lethalP)
            {
                // lethalP implies that $p exists, so no need to check for null
                if (bed.p.hasAttribute ("constant"))
                {
                    double pvalue = ((Scalar) ((Constant) bed.p.equations.first ().expression).value).value;
                    if (pvalue != 0)
                    {
                        result.append ("  if (pow (" + resolve (bed.p.reference, context, false)+ ", " + resolve (bed.dt.reference, context, false));
                        if (context.useExponent)
                        {
                            result.append (context.printShift (bed.dt.exponent - 15));  // second operand must have exponent=15
                            result.append (", " + bed.p.exponent);  // exponentA
                            result.append (", " + bed.p.exponent);  // exponentResult
                        }
                        result.append (") < uniform<" + T + "> ()");
                        if (context.useExponent) result.append (context.printShift (-1 - bed.p.exponent));  // -1 is hard-coded from the Uniform function.
                        result.append (")\n");
                    }
                }
                else
                {
                    if (bed.p.hasAttribute ("temporary"))
                    {
                        // Generate any temporaries needed by $p
                        for (Variable t : s.variables)
                        {
                            if (t.hasAttribute ("temporary")  &&  bed.p.dependsOn (t) != null)
                            {
                                multiconditional (t, context, "  ");
                            }
                        }
                        multiconditional (bed.p, context, "  ");
                    }

                    result.append ("  if (" + mangle ("$p") + " <= 0  ||  " + mangle ("$p") + " < " + context.print (1, bed.p.exponent) + "  &&  pow (" + mangle ("$p") + ", " + resolve (bed.dt.reference, context, false));
                    if (context.useExponent)
                    {
                        result.append (context.printShift (bed.dt.exponent - 15));
                        result.append (", " + bed.p.exponent);
                        result.append (", " + bed.p.exponent);
                    }
                    result.append (") < uniform<" + T + "> ()");
                    if (context.useExponent) result.append (context.printShift (-1 - bed.p.exponent));
                    result.append (")\n");
                }
                result.append ("  {\n");
                result.append ("    die ();\n");
                result.append ("    return false;\n");
                result.append ("  }\n");
            }

            if (s.lethalConnection)
            {
                for (ConnectionBinding c : s.connectionBindings)
                {
                	VariableReference r = s.resolveReference (c.alias + ".$live");
                	if (! r.variable.hasAttribute ("constant"))
                	{
                        result.append ("  if (" + resolve (r, context, false, "", true) + " == 0)\n");
                        result.append ("  {\n");
                        result.append ("    die ();\n");
                        result.append ("    return false;\n");
                        result.append ("  }\n");
                	}
                }
            }

            if (s.lethalContainer)
            {
                VariableReference r = s.resolveReference ("$up.$live");
                if (! r.variable.hasAttribute ("constant"))
                {
                    result.append ("  if (" + resolve (r, context, false, "", true) + " == 0)\n");
                    result.append ("  {\n");
                    result.append ("    die ();\n");
                    result.append ("    return false;\n");
                    result.append ("  }\n");
                }
            }

            result.append ("  return true;\n");
            context.hasEvent = false;
            result.append ("}\n");
            result.append ("\n");
        }

        // Unit updateDerivative
        if (bed.localDerivativeUpdate.size () > 0  ||  s.parts.size () > 0)
        {
            result.append ("void " + ns + "updateDerivative ()\n");
            result.append ("{\n");
            for (Variable v : bed.localBufferedInternalDerivative)
            {
                result.append ("  " + type (v) + " " + mangle ("next_", v) + ";\n");
            }
            for (Variable v : bed.localDerivativeUpdate)
            {
                multiconditional (v, context, "  ");
            }
            for (Variable v : bed.localBufferedInternalDerivative)
            {
                result.append ("  " + mangle (v) + " = " + mangle ("next_", v) + ";\n");
            }
            // contained populations
            for (EquationSet e : s.parts)
            {
                result.append ("  " + mangle (e.name) + ".updateDerivative ();\n");
            }
            result.append ("}\n");
            result.append ("\n");
        }

        // Unit finalizeDerivative
        if (bed.localBufferedExternalDerivative.size () > 0  ||  s.parts.size () > 0)
        {
            result.append ("void " + ns + "finalizeDerivative ()\n");
            result.append ("{\n");
            for (Variable v : bed.localBufferedExternalDerivative)
            {
                result.append ("  " + mangle (v) + " = " + mangle ("next_", v) + ";\n");
            }
            for (Variable v : bed.localBufferedExternalWriteDerivative)
            {
                result.append ("  " + clearAccumulator (mangle ("next_", v), v, context) + ";\n");
            }
            // contained populations
            for (EquationSet e : s.parts)
            {
                result.append ("  " + mangle (e.name) + ".finalizeDerivative ();\n");
            }
            result.append ("}\n");
            result.append ("\n");
        }

        if (bed.needLocalPreserve  ||  s.parts.size () > 0)
        {
            // Unit snapshot
            result.append ("void " + ns + "snapshot ()\n");
            result.append ("{\n");
            if (bed.needLocalPreserve)
            {
                result.append ("  preserve = new Preserve;\n");
                for (Variable v : bed.localIntegrated)
                {
                    result.append ("  preserve->" + mangle (v) + " = " + mangle (v) + ";\n");
                }
                for (Variable v : bed.localDerivativePreserve)
                {
                    result.append ("  preserve->" + mangle (v) + " = " + mangle (v) + ";\n");
                }
                for (Variable v : bed.localBufferedExternalWriteDerivative)
                {
                    result.append ("  preserve->" + mangle ("next_", v) + " = " + mangle ("next_", v) + ";\n");
                    result.append ("  " + clearAccumulator (mangle ("next_", v), v, context) + ";\n");
                }
            }
            for (EquationSet e : s.parts)
            {
                result.append ("  " + mangle (e.name) + ".snapshot ();\n");
            }
            result.append ("}\n");
            result.append ("\n");

            // Unit restore
            result.append ("void " + ns + "restore ()\n");
            result.append ("{\n");
            if (bed.needLocalPreserve)
            {
                for (Variable v : bed.localDerivativePreserve)
                {
                    result.append ("  " + mangle (v) + " = preserve->" + mangle (v) + ";\n");
                }
                for (Variable v : bed.localBufferedExternalWriteDerivative)
                {
                    result.append ("  " + mangle ("next_", v) + " = preserve->" + mangle ("next_", v) + ";\n");
                }
                result.append ("  delete preserve;\n");
                result.append ("  preserve = 0;\n");
            }
            for (EquationSet e : s.parts)
            {
                result.append ("  " + mangle (e.name) + ".restore ();\n");
            }
            result.append ("}\n");
            result.append ("\n");
        }

        if (bed.localDerivative.size () > 0  ||  s.parts.size () > 0)
        {
            // Unit pushDerivative
            result.append ("void " + ns + "pushDerivative ()\n");
            result.append ("{\n");
            if (bed.localDerivative.size () > 0)
            {
                result.append ("  Derivative * temp = new Derivative;\n");
                result.append ("  temp->next = stackDerivative;\n");
                result.append ("  stackDerivative = temp;\n");
                for (Variable v : bed.localDerivative)
                {
                    result.append ("  temp->" + mangle (v) + " = " + mangle (v) + ";\n");
                }
            }
            for (EquationSet e : s.parts)
            {
                result.append ("  " + mangle (e.name) + ".pushDerivative ();\n");
            }
            result.append ("}\n");
            result.append ("\n");

            // Unit multiplyAddToStack
            result.append ("void " + ns + "multiplyAddToStack (" + T + " scalar)\n");
            result.append ("{\n");
            for (Variable v : bed.localDerivative)
            {
                result.append ("  stackDerivative->" + mangle (v) + " += ");
                if (T.equals ("int"))
                {
                    result.append ("(int) ((int64_t) " + mangle (v) + " * scalar >> " + (Operator.MSB - 1) + ");\n");
                }
                else
                {
                    result.append (                      mangle (v) + " * scalar;\n");
                }
            }
            for (EquationSet e : s.parts)
            {
                result.append ("  " + mangle (e.name) + ".multiplyAddToStack (scalar);\n");
            }
            result.append ("}\n");
            result.append ("\n");

            // Unit multiply
            result.append ("void " + ns + "multiply (" + T + " scalar)\n");
            result.append ("{\n");
            for (Variable v : bed.localDerivative)
            {
                if (T.equals ("int"))
                {
                    result.append ("  " + mangle (v) + " = (int64_t) " + mangle (v) + " * scalar >> " + (Operator.MSB - 1) + ";\n");
                }
                else
                {
                    result.append ("  " + mangle (v) + " *= scalar;\n");
                }
            }
            for (EquationSet e : s.parts)
            {
                result.append ("  " + mangle (e.name) + ".multiply (scalar);\n");
            }
            result.append ("}\n");
            result.append ("\n");

            // Unit addToMembers
            result.append ("void " + ns + "addToMembers ()\n");
            result.append ("{\n");
            if (bed.localDerivative.size () > 0)
            {
                for (Variable v : bed.localDerivative)
                {
                    result.append ("  " + mangle (v) + " += stackDerivative->" + mangle (v) + ";\n");
                }
                result.append ("  Derivative * temp = stackDerivative;\n");
                result.append ("  stackDerivative = stackDerivative->next;\n");
                result.append ("  delete temp;\n");
            }
            for (EquationSet e : s.parts)
            {
                result.append ("  " + mangle (e.name) + ".addToMembers ();\n");
            }
            result.append ("}\n");
            result.append ("\n");
        }

        // Unit setPart
        if (s.connectionBindings != null)
        {
            result.append ("void " + ns + "setPart (int i, Part * part)\n");
            result.append ("{\n");
            result.append ("  switch (i)\n");
            result.append ("  {\n");
            for (ConnectionBinding c : s.connectionBindings)
            {
                result.append ("    case " + c.index + ": " + mangle (c.alias) + " = (" + prefix (c.endpoint) + " *) part; return;\n");
            }
            result.append ("  }\n");
            result.append ("}\n");
            result.append ("\n");
        }

        // Unit getPart
        if (s.connectionBindings != null)
        {
            result.append ("Part<" + T + "> * " + ns + "getPart (int i)\n");
            result.append ("{\n");
            result.append ("  switch (i)\n");
            result.append ("  {\n");
            for (ConnectionBinding c : s.connectionBindings)
            {
                result.append ("    case " + c.index + ": return " + mangle (c.alias) + ";\n");
            }
            result.append ("  }\n");
            result.append ("  return 0;\n");
            result.append ("}\n");
            result.append ("\n");
        }

        // Unit getCount
        if (bed.accountableEndpoints.size () > 0)
        {
            result.append ("int " + ns + "getCount (int i)\n");
            result.append ("{\n");
            result.append ("  switch (i)\n");
            result.append ("  {\n");
            for (ConnectionBinding c : s.connectionBindings)
            {
                if (bed.accountableEndpoints.contains (c.alias))
                {
                    result.append ("    case " + c.index + ": return " + mangle (c.alias) + "->" + prefix (s) + "_" + mangle (c.alias) + "_count;\n");
                }
            }
            result.append ("  }\n");
            result.append ("  return 0;\n");
            result.append ("}\n");
            result.append ("\n");
        }

        // Unit getProject
        if (bed.hasProject)
        {
            result.append ("void " + ns + "getProject (int i, MatrixFixed<" + T + ",3,1> & xyz)\n");
            result.append ("{\n");

            // $project is evaluated similar to $p. The result is not stored.
            s.setConnect (1);

            result.append ("  switch (i)\n");
            result.append ("  {\n");
            boolean needDefault = false;
            for (ConnectionBinding c : s.connectionBindings)
            {
                result.append ("    case " + c.index + ":");
                Variable project = s.find (new Variable (c.alias + ".$project"));
                if (project == null)  // fetch $xyz from endpoint
                {
                    VariableReference fromXYZ = s.resolveReference (c.alias + ".$xyz");
                    if (fromXYZ.variable == null)
                    {
                        needDefault = true;
                    }
                    else
                    {
                        if (fromXYZ.variable.hasAttribute ("temporary"))  // calculated value
                        {
                            result.append (" " + mangle (c.alias) + "->getXYZ (xyz); break;\n");
                        }
                        else  // stored value or "constant"
                        {
                            result.append (" xyz = " + resolve (fromXYZ, context, false) + "; break;\n");
                        }
                    }
                }
                else  // compute $project
                {
                    result.append ("\n");  // to complete the "case" line
                    result.append ("    {\n");
                    if (project.hasAttribute ("temporary"))  // it could also be "constant", but no other type
                    {
                        for (Variable t : s.variables)
                        {
                            if (t.hasAttribute ("temporary")  &&  project.dependsOn (t) != null)
                            {
                                multiconditional (t, context, "      ");
                            }
                        }
                        multiconditional (project, context, "      ");
                    }
                    result.append ("      xyz = " + resolve (project.reference, context, false) + ";\n");
                    result.append ("      break;\n");
                    result.append ("    }\n");
                }
            }
            if (needDefault)
            {
                result.append ("    default:\n");
                result.append ("      xyz[0] = 0;\n");
                result.append ("      xyz[1] = 0;\n");
                result.append ("      xyz[2] = 0;\n");
            }
            result.append ("  }\n");
            result.append ("}\n");

            s.setConnect (0);
        }

        // Unit mapIndex
        if (s.connectionMatrix != null)
        {
            result.append ("int " + ns + "mapIndex (int i, int rc)\n");
            result.append ("{\n");

            Variable rc = new Variable ("rc", 0);
            rc.reference = new VariableReference ();
            rc.reference.variable = rc;
            rc.container = s;
            rc.addAttribute ("preexistent");
            AccessVariable av = new AccessVariable ();
            av.reference = rc.reference;

            ConnectionMatrix cm = s.connectionMatrix;
            cm.rowMapping.replaceRC (av);
            cm.colMapping.replaceRC (av);

            result.append ("  if (i == 0) return ");
            cm.rowMapping.rhs.render (context);
            result.append (";\n");
            result.append ("  return ");
            cm.colMapping.rhs.render (context);
            result.append (";\n");
            result.append ("}\n");
            result.append ("\n");
        }

        // Unit getNewborn
        if (bed.newborn >= 0)
        {
            result.append ("bool " + ns + "getNewborn ()\n");
            result.append ("{\n");
            result.append ("  return flags & (" + bed.localFlagType + ") 0x1 << " + bed.newborn + ";\n");
            result.append ("}\n");
            result.append ("\n");
        }

        // Unit getLive
        if (bed.live != null  &&  ! bed.live.hasAttribute ("constant"))
        {
            result.append (T + " " + ns + "getLive ()\n");
            result.append ("{\n");
            if (! bed.live.hasAttribute ("accessor"))  // "accessor" indicates whether or not $value is actually stored
            {
                result.append ("  if (" + resolve (bed.live.reference, context, false, "", true) + " == 0) return 0;\n");
            }
            if (s.lethalConnection)
            {
                for (ConnectionBinding c : s.connectionBindings)
                {
                    VariableReference r = s.resolveReference (c.alias + ".$live");
                    if (! r.variable.hasAttribute ("constant"))
                    {
                        result.append ("  if (" + resolve (r, context, false, "", true) + " == 0) return 0;\n");
                    }
                }
            }
            if (s.lethalContainer)
            {
                VariableReference r = s.resolveReference ("$up.$live");
                if (! r.variable.hasAttribute ("constant"))
                {
                    result.append ("  if (" + resolve (r, context, false, "", true) + " == 0) return 0;\n");
                }
            }
            result.append ("  return 1;\n");
            result.append ("}\n");
            result.append ("\n");
        }

        // Unit getP
        if (s.connectionBindings != null)
        {
            if (bed.p != null)
            {
                result.append (T + " " + ns + "getP ()\n");
                result.append ("{\n");
                s.setConnect (1);
                if (! bed.p.hasAttribute ("constant"))
                {
                    // Generate any temporaries needed by $p
                    for (Variable t : s.variables)
                    {
                        if (t.hasAttribute ("temporary")  &&  bed.p.dependsOn (t) != null)
                        {
                            multiconditional (t, context, "  ");
                        }
                    }
                    multiconditional (bed.p, context, "  ");  // $p is always calculated, because we are in a pseudo-init phase
                }
                result.append ("  return " + resolve (bed.p.reference, context, false) + ";\n");
                s.setConnect (0);
                result.append ("}\n");
                result.append ("\n");
            }
        }

        // Unit getXYZ
        if (s.connectionBindings == null)  // Connections can also have $xyz, but only compartments need to provide an accessor.
        {
            if (bed.xyz != null)
            {
                result.append ("void " + ns + "getXYZ (MatrixFixed<" + T + ",3,1> & xyz)\n");
                result.append ("{\n");
                // $xyz is either stored, "temporary", or "constant"
                // If "temporary", then we compute it on the spot.
                // If "constant", then we use the static matrix created during variable analysis
                // If stored, then simply copy into the return value.
                if (bed.xyz.hasAttribute ("temporary"))
                {
                    // Generate any temporaries needed by $xyz
                    for (Variable t : s.variables)
                    {
                        if (t.hasAttribute ("temporary")  &&  bed.xyz.dependsOn (t) != null)
                        {
                            multiconditional (t, context, "    ");
                        }
                    }
                    multiconditional (bed.xyz, context, "    ");
                }
                result.append ("  xyz = " + resolve (bed.xyz.reference, context, false) + ";\n");
                result.append ("}\n");
                result.append ("\n");
            }
        }

        // Unit events
        if (bed.eventTargets.size () > 0)
        {
            result.append ("bool " + ns + "eventTest (int i)\n");
            result.append ("{\n");
            result.append ("  switch (i)\n");
            result.append ("  {\n");
            for (EventTarget et : bed.eventTargets)
            {
                result.append ("    case " + et.valueIndex + ":\n");
                result.append ("    {\n");
                for (Variable v : et.dependencies)
                {
                    multiconditional (v, context, "      ");
                }
                if (et.edge != EventTarget.NONZERO)
                {
                    result.append ("      " + T + " before = ");
                    if (et.trackOne) result.append (resolve (et.track.reference, context, false));
                    else             result.append (et.track.name);
                    result.append (";\n");
                }
                if (et.trackOne)  // This is a single variable, so check its value directly.
                {
                    result.append ("      " + T + " after = " + resolve (et.track.reference, context, true) + ";\n");
                }
                else  // This is an expression, so use our private auxiliary variable.
                {
                    result.append ("      " + T + " after = ");
                    et.event.operands[0].render (context);
                    result.append (";\n");
                    if (et.edge != EventTarget.NONZERO)
                    {
                        result.append ("      " + et.track.name + " = after;\n");
                    }
                }
                switch (et.edge)
                {
                    case EventTarget.NONZERO:
                        if (et.timeIndex >= 0)
                        {
                            // Guard against multiple events in a given cycle.
                            // Note that other trigger types don't need this because they set the auxiliary variable,
                            // so the next test in the same cycle will no longer see change.
                            result.append ("      if (after == 0) return false;\n");
                            if (T.equals ("int"))
                            {
                                result.append ("      " + T + " moduloTime = Simulator<" + T + ">::instance.currentEvent->t;\n");  // No need for modulo arithmetic. Rather, int time should be wrapped elsewhere.
                            }
                            else  // float, double
                            {
                                result.append ("      " + T + " moduloTime = (" + T + ") fmod (Simulator<" + T + ">::instance.currentEvent->t, 1);\n");  // Wrap time at 1 second, to fit in float precision.
                            }
                            result.append ("      if (eventTime" + et.timeIndex + " == moduloTime) return false;\n");
                            result.append ("      eventTime" + et.timeIndex + " = moduloTime;\n");
                            result.append ("      return true;\n");
                        }
                        else
                        {
                            result.append ("      return after != 0;\n");
                        }
                        break;
                    case EventTarget.CHANGE:
                        result.append ("      return before != after;\n");
                        break;
                    case EventTarget.FALL:
                        result.append ("      return before != 0  &&  after == 0;\n");
                        break;
                    case EventTarget.RISE:
                    default:
                        result.append ("      return before == 0  &&  after != 0;\n");
                }
                result.append ("    }\n");
            }
            result.append ("  }\n");
            result.append ("}\n");
            result.append ("\n");

            if (bed.needLocalEventDelay)
            {
                result.append (T + " " + ns + "eventDelay (int i)\n");
                result.append ("{\n");
                result.append ("  switch (i)\n");
                result.append ("  {\n");
                for (EventTarget et : bed.eventTargets)
                {
                    if (et.delay >= -1) continue;

                    // Need to evaluate expression
                    result.append ("    case " + et.valueIndex + ":\n");
                    result.append ("    {\n");
                    for (Variable v : et.dependencies)
                    {
                        multiconditional (v, context, "      ");
                    }
                    result.append ("      " + T + " result = ");
                    et.event.operands[1].render (context);
                    result.append (";\n");
                    result.append ("      if (result < 0) return -1;\n");
                    result.append ("      return result;\n");
                    result.append ("    }\n");
                }
                result.append ("  }\n");
                result.append ("}\n");
                result.append ("\n");
            }

            result.append ("void " + ns + "setLatch (int i)\n");
            result.append ("{\n");
            result.append ("  flags |= (" + bed.localFlagType + ") 0x1 << i;\n");
            result.append ("}\n");
            result.append ("\n");

            if (bed.eventReferences.size () > 0)
            {
                result.append ("void " + ns + "finalizeEvent ()\n");
                result.append ("{\n");
                for (Variable v : bed.eventReferences)
                {
                    String current  = resolve (v.reference, context, false);
                    String buffered = resolve (v.reference, context, true);
                    result.append ("  " + current);
                    switch (v.assignment)
                    {
                        case Variable.ADD:
                            result.append (" += " + buffered + ";\n");
                            result.append ("  " + zero (buffered, v) + ";\n");
                            break;
                        case Variable.MULTIPLY:
                        case Variable.DIVIDE:
                        {
                            // The current and buffered values of the variable have the same exponent.
                            // raw = exponentV + exponentV - MSB
                            // shift = raw - exponentV = exponentV - MSB
                            int shift = v.exponent - Operator.MSB;
                            if (shift != 0  &&  T.equals ("int"))
                            {
                                result.append (" = (int64_t) " + current + " * " + buffered + context.printShift (shift) + ";\n");
                            }
                            else
                            {
                                result.append (" *= " + buffered + ";\n");
                            }
                            result.append ("  " + clear (buffered, v, 1, context) + ";\n");
                            break;
                        }
                        case Variable.MIN:
                            result.append (" = min (" + current + ", " + buffered + ");\n");  // TODO: Write elementwise min() and max() for matrices.
                            result.append ("  " + clear (buffered, v, Double.POSITIVE_INFINITY, context) + ";\n");
                            break;
                        case Variable.MAX:
                            result.append (" = max (" + current + ", " + buffered + ");\n");
                            result.append ("  " + clear (buffered, v, Double.NEGATIVE_INFINITY, context) + ";\n");
                            break;
                        default:  // REPLACE
                            result.append (" = " + buffered + ";\n");
                            break;
                    }
                }
                result.append ("}\n");
                result.append ("\n");
            }
        }

        // Unit path
        if (bed.needLocalPath)
        {
            result.append ("void " + ns + "path (String & result)\n");
            result.append ("{\n");
            if (s.connectionBindings == null)
            {
                // We assume that result is passed in as the empty string.
                if (s.container != null)
                {
                    if (((BackendDataC) s.container.backendData).needLocalPath)  // Will our container provide a non-empty path?
                    {
                        result.append ("  container->path (result);\n");
                        result.append ("  result += \"." + s.name + "\";\n");
                    }
                    else
                    {
                        result.append ("  result = \"" + s.name + "\";\n");
                    }
                }
                if (bed.index != null) result.append ("  result += __24index;\n");
            }
            else
            {
                boolean first = true;
                boolean temp  = false;
                for (ConnectionBinding c : s.connectionBindings)
                {
                    if (first)
                    {
                        result.append ("  " + mangle (c.alias) + "->path (result);\n");
                        first = false;
                    }
                    else
                    {
                        if (! temp)
                        {
                            result.append ("  String temp;\n");
                            temp = true;
                        }
                        result.append ("  " + mangle (c.alias) + "->path (temp);\n");
                        result.append ("  result += \"-\";\n");
                        result.append ("  result += temp;\n");
                    }
                }
            }
            result.append ("}\n");
            result.append ("\n");
        }

        // Unit conversions
        Set<Conversion> conversions = s.getConversions ();
        for (Conversion pair : conversions)
        {
            EquationSet source = pair.from;
            EquationSet dest   = pair.to;
            boolean connectionSource = source.connectionBindings != null;
            boolean connectionDest   = dest  .connectionBindings != null;
            if (connectionSource != connectionDest)
            {
                Backend.err.get ().println ("Can't change $type between connection and non-connection.");
                throw new Backend.AbortRun ();
                // Why not? Because a connection *must* know the instances it connects, while
                // a compartment cannot know those instances. Thus, one can never be converted
                // to the other.
            }

            // The "2" functions only have local meaning, so they are never virtual.
            // Must do everything init() normally does, including increment $n.
            // Parameters:
            //   from -- the source part
            //   visitor -- the one managing the source part
            //   $type -- The integer index, in the $type expression, of the current target part. The target part's $type field will be initialized with this number (and zeroed after one cycle).
            result.append ("void " + ns + mangle (source.name) + "_2_" + mangle (dest.name) + " (" + mangle (source.name) + " * from, int " + mangle ("$type") + ")\n");
            result.append ("{\n");
            result.append ("  " + mangle (dest.name) + " * to = " + mangle (dest.name) + ".allocate ();\n");
            if (connectionDest)
            {
                // Match connection bindings
                for (ConnectionBinding c : dest.connectionBindings)
                {
                    ConnectionBinding d = source.findConnection (c.alias);
                    if (d == null)
                    {
                        Backend.err.get ().println ("Unfulfilled connection binding during $type change.");
                        throw new Backend.AbortRun ();
                    }
                    result.append ("  to->" + mangle (c.alias) + " = from->" + mangle (c.alias) + ";\n");
                }
            }
            result.append ("  to->enterSimulation ();\n");
            result.append ("  getEvent ()->enqueue (to);\n");
            result.append ("  to->init ();\n");  // sets all variables, so partially redundant with the following code ...
            // TODO: Convert contained populations from matching populations in the source part?

            // Match variables between the two sets.
            // TODO: a match between variables should be marked as a dependency. This might change some "dummy" variables into stored values.
            String [] forbiddenAttributes = new String [] {"global", "constant", "accessor", "reference", "temporary", "dummy", "preexistent"};
            for (Variable v : dest.variables)
            {
                if (v.name.equals ("$type"))
                {
                    result.append ("  to->" + mangle (v) + " = " + mangle ("$type") + ";\n");  // initialize new part with its position in the $type split
                    continue;
                }
                if (v.hasAny (forbiddenAttributes))
                {
                    continue;
                }
                Variable v2 = source.find (v);
                if (v2 != null  &&  v2.equals (v))
                {
                    result.append ("  to->" + mangle (v) + " = " + resolve (v2.reference, context, false, "from->", false) + ";\n");
                }
            }

            result.append ("}\n");
            result.append ("\n");
        }
    }

    public void eventGenerate (String pad, EventTarget et, RendererC context, boolean multi)
    {
        String eventSpike = "EventSpike";
        if (multi) eventSpike += "Multi";
        else       eventSpike += "Single";
        String eventSpikeLatch = eventSpike + "Latch<" + T + ">";
        eventSpike += "<" + T + ">";

        StringBuilder result = context.result;
        if (et.delay >= -1)  // delay is a constant, so do all tests at the Java level
        {
            if (et.delay < 0)  // timing is no-care
            {
                result.append (pad + eventSpike + " * spike = new " + eventSpikeLatch + ";\n");
                result.append (pad + "spike->t = event->t;\n");  // queue immediately after current cycle, so latches get set for next full cycle
            }
            else if (et.delay == 0)  // process as close to current cycle as possible
            {
                result.append (pad + eventSpike + " * spike = new " + eventSpike + ";\n");  // fully execute the event (not latch it)
                result.append (pad + "spike->t = event->t;\n");  // queue immediately
            }
            else
            {
                // Is delay an quantum number of $t' steps?
                result.append (pad + T + " delay = " + context.print (et.delay, context.bed.t.exponent) + ";\n");
                result.append (pad + eventSpike + " * spike;\n");
                eventGenerate (pad, et, context, eventSpike, eventSpikeLatch);
            }
        }
        else  // delay must be evaluated, so emit tests at C level
        {
            result.append (pad + T + " delay = p->eventDelay (" + et.valueIndex + ");\n");
            result.append (pad + eventSpike + " * spike;\n");
            result.append (pad + "if (delay < 0)\n");
            result.append (pad + "{\n");
            result.append (pad + "  " + eventSpike + " * spike = new " + eventSpikeLatch + ";\n");
            result.append (pad + "  spike->t = event->t;\n");
            result.append (pad + "}\n");
            result.append (pad + "else if (delay == 0)\n");
            result.append (pad + "{\n");
            result.append (pad + "  " + eventSpike + " * spike = new " + eventSpike + ";\n");
            result.append (pad + "  spike->t = event->t;\n");
            result.append (pad + "}\n");
            result.append (pad + "else\n");
            result.append (pad + "{\n");
            eventGenerate (pad + "  ", et, context, eventSpike, eventSpikeLatch);
            result.append (pad + "}\n");
        }

        result.append (pad + "spike->latch = " + et.valueIndex + ";\n");
        if (multi) result.append (pad + "spike->targets = &eventMonitor_" + prefix (et.container) + ";\n");
        else       result.append (pad + "spike->target = p;\n");
        result.append (pad + "Simulator<" + T + ">::instance.queueEvent.push (spike);\n");
    }

    public void eventGenerate (String pad, EventTarget et, RendererC context, String eventSpike, String eventSpikeLatch)
    {
        StringBuilder result = context.result;

        // Is delay close enough to a time-quantum?
        if (T.equals ("int"))
        {
            result.append (pad + "int step = (delay + event->dt / 2) / event->dt;\n");
            result.append (pad + "int quantizedTime = step * event->dt;\n");
            result.append (pad + "if (quantizedTime == delay)\n");  // All fractional bits are zero. Usually there are no more than 10 fractional bits (~1/1000 of a time step).
        }
        else
        {
            result.append (pad + T + " ratio = delay / event->dt;\n");
            result.append (pad + "int step = (int) round (ratio);\n");
            result.append (pad + "if (abs (ratio - step) < 1e-3)\n");
        }
        result.append (pad + "{\n");
        if (eventMode == Simulator.DURING)
        {
            result.append (pad + "  spike = new " + eventSpikeLatch + ";\n");
        }
        else
        {
            result.append (pad + "  spike = new " + eventSpike + ";\n");
        }
        if (eventMode == Simulator.AFTER)
        {
            if (T.equals ("int"))
            {
                result.append (pad + "  delay = quantizedTime + 1;\n");
            }
            else
            {
                result.append (pad + "  delay = (step + (" + T + ") 1e-6) * event->dt;\n");
            }
        }
        else
        {
            if (T.equals ("int"))
            {
                result.append (pad + "  delay = quantizedTime - 1;\n");
            }
            else
            {
                result.append (pad + "  delay = (step - (" + T + ") 1e-6) * event->dt;\n");
            }
        }
        result.append (pad + "}\n");
        result.append (pad + "else\n");
        result.append (pad + "{\n");
        result.append (pad + "  spike = new " + eventSpike + ";\n");
        result.append (pad + "}\n");
        result.append (pad + "spike->t = event->t + delay;\n");
    }

    public void multiconditional (Variable v, RendererC context, String pad) throws Exception
    {
        boolean connect = context.part.getConnect ();
        boolean init    = context.part.getInit ();
        boolean isType = v.name.equals ("$type");

        if (v.hasAttribute ("temporary")) context.result.append (pad + type (v) + " " + mangle (v) + ";\n");

        // Select the default equation
        EquationEntry defaultEquation = null;
        for (EquationEntry e : v.equations)
        {
            // TODO: also handle $init==1, or any other equivalent expression
            // Best approach is to deep-copy the equation set and optimize for each execution phase.
            // In that case, the phase indicator will be optimized away completely, leaving only an
            // unconditional equation.
            if (init  &&  e.ifString.equals ("$init"))
            {
                defaultEquation = e;
                break;
            }
            if (connect  &&  e.ifString.equals ("$connect"))
            {
                defaultEquation = e;
                break;
            }
            if (e.ifString.isEmpty ()) defaultEquation = e;
        }

        // Initialize static objects, and dump dynamic objects needed by conditions
        for (EquationEntry e : v.equations)
        {
            if (init)
            {
                prepareStaticObjects (e.expression, context, pad);
                if (e.condition != null) prepareStaticObjects (e.condition, context, pad);
            }
            if (e.condition != null) prepareDynamicObjects (e.condition, context, init, pad);
        }

        // Write the conditional equations
        boolean haveIf = false;
        String padIf = pad;
        for (EquationEntry e : v.equations)
        {
            if (e == defaultEquation) continue;  // Must skip the default equation, as it will be emitted last.

            // Skip cases where the condition will never fire.
            if (init)
            {
                if (e.ifString.isEmpty ()) continue;
                if (e.ifString.equals ("$connect")) continue;
            }
            else if (connect)
            {
                if (e.ifString.isEmpty ()) continue;
                if (e.ifString.equals ("$init")) continue;
            }
            else
            {
                if (e.ifString.equals ("$connect")) continue;
                if (e.ifString.equals ("$init")) continue;
            }

            if (e.condition != null)
            {
                String ifString;
                if (haveIf)
                {
                    ifString = "else if (";
                }
                else
                {
                    ifString = "if (";
                    haveIf = true;
                    padIf = pad + "  ";
                }
                context.result.append (pad + ifString);
                e.condition.render (context);
                context.result.append (")\n");
                context.result.append (pad + "{\n");
            }
            if (isType)
            {
                // Set $type to an integer index indicating which of the splits statements in this equation set
                // was actually triggered. During finalize(), this will select a piece of code that implements
                // this particular split. Afterward, $type will be set to an appropriate index within the split,
                // per the N2A language document.
                if (! (e.expression instanceof Split))
                {
                    Backend.err.get ().println ("Unexpected expression for $type");
                    throw new Backend.AbortRun ();
                }
                int index = context.part.splits.indexOf (((Split) e.expression).parts);
                context.result.append (padIf + resolve (v.reference, context, true) + " = " + (index + 1) + ";\n");
            }
            else
            {
                prepareDynamicObjects (e.expression, context, init, pad);
                context.result.append (padIf);
                renderEquation (context, e);
            }
            if (haveIf) context.result.append (pad + "}\n");
        }

        // Write the default equation
        if (defaultEquation == null)
        {
            String defaultValue = null;
            if (isType)
            {
                defaultValue = "0";  // always reset $type to 0
            }
            else if (connect  &&  v.name.equals ("$p"))
            {
                defaultValue = "1";
            }
            else
            {
                // External-write variables with a combiner get reset during finalize.
                // However, buffered variables with simple assignment (REPLACE) need
                // to copy forward the current buffered value.
                if (   v.assignment == Variable.REPLACE
                    && v.reference.variable == v
                    && v.equations.size () > 0
                    && v.hasAny ("cycle", "externalRead")
                    && ! v.hasAttribute ("initOnly"))
                {
                    defaultValue = resolve (v.reference, context, false);  // copy previous value
                }
            }

            if (defaultValue != null)
            {
                if (haveIf)
                {
                    context.result.append (pad + "else\n");
                    context.result.append (pad + "{\n");
                }
                context.result.append (padIf + resolve (v.reference, context, true) + " = " + defaultValue + ";\n");
                if (haveIf) context.result.append (pad + "}\n");
            }
        }
        else
        {
            if (haveIf)
            {
                context.result.append (pad + "else\n");
                context.result.append (pad + "{\n");
            }
            if (isType)
            {
                ArrayList<EquationSet> split = ((Split) defaultEquation.expression).parts;
                int index = context.part.splits.indexOf (split);
                context.result.append (padIf + resolve (v.reference, context, true) + " = " + (index + 1) + ";\n");
            }
            else
            {
                prepareDynamicObjects (defaultEquation.expression, context, init, pad);
                context.result.append (padIf);
                renderEquation (context, defaultEquation);
            }
            if (haveIf) context.result.append (pad + "}\n");
        }
    }

    public void renderEquation (RendererC context, EquationEntry e)
    {
        StringBuilder result = context.result;
        if (e.variable.hasAttribute ("dummy"))
        {
            e.expression.render (context);
        }
        else
        {
            String LHS = resolve (e.variable.reference, context, true);
            result.append (LHS);
            int shift = 0;
            switch (e.variable.assignment)
            {
                case Variable.REPLACE:
                    result.append (" = ");
                    break;
                case Variable.ADD:
                    result.append (" += ");
                    break;
                case Variable.MULTIPLY:
                    // raw exponent = exponentV + exponentExpression - MSB
                    // shift = raw - exponentV = expnentExpression - MSB
                    shift = e.expression.exponentNext - Operator.MSB;
                    if (shift != 0  &&  T.equals ("int"))
                    {
                        if (shift < 0) result.append (" = (int64_t) " + LHS + " * ");
                        else           result.append (" = "           + LHS + " * ");
                    }
                    else
                    {
                        result.append (" *= ");
                    }
                    break;
                case Variable.DIVIDE:
                    // raw = exponentV - exponentExpression + MSB
                    // shift = raw - exponentV = MSB - exponentExpression
                    shift = Operator.MSB - e.expression.exponentNext;
                    if (shift != 0  &&  T.equals ("int"))
                    {
                        if (shift > 0) result.append (" = ((int64_t) " + LHS + context.printShift (shift) + ") / ");
                        else           result.append (" = "            + LHS                              +  " / ");
                    }
                    else
                    {
                        result.append (" /= ");
                    }
                    break;
                case Variable.MIN:
                    result.append (" = min (" + LHS + ", ");
                    break;
                case Variable.MAX:
                    result.append (" = max (" + LHS + ", ");
            }

            e.expression.render (context);

            if (e.variable.assignment == Variable.MAX  ||  e.variable.assignment == Variable.MIN)
            {
                result.append (")");
            }
            if (shift != 0  &&  T.equals ("int"))
            {
                result.append (context.printShift (shift));
            }
        }
        result.append (";\n");
    }

    public void prepareStaticObjects (Operator op, final RendererC context, final String pad) throws Exception
    {
        final BackendDataC bed = context.bed;
        Visitor visitor = new Visitor ()
        {
            public boolean visit (Operator op)
            {
                if (op instanceof Output)
                {
                    Output o = (Output) op;
                    if (o.operands.length < 3)  // column name is generated
                    {
                        String stringName = stringNames.get (op);
                        BackendDataC bed = (BackendDataC) context.part.backendData;
                        if (context.global ? bed.needGlobalPath : bed.needLocalPath)
                        {
                            context.result.append (pad + "path (" + stringName + ");\n");
                            context.result.append (pad + stringName + " += \"." + o.variableName + "\";\n");
                        }
                        else
                        {
                            context.result.append (pad + stringName + " = \"" + o.variableName + "\";\n");
                        }
                    }
                    if (o.operands[0] instanceof Constant)
                    {
                        String outputName = outputNames.get (o.operands[0].toString ());
                        if (o.operands.length >= 4  &&  o.operands[3].getString ().contains ("raw"))
                        {
                            context.result.append (pad + outputName + "->raw = true;\n");
                        }
                    }
                    return true;  // Continue to drill down, because I/O functions can be nested.
                }
                if (op instanceof Input)
                {
                    Input i = (Input) op;
                    if (i.operands[0] instanceof Constant)
                    {
                        String inputName = inputNames.get (i.operands[0].toString ());
                        if (T.equals ("int"))
                        {
                            context.result.append (pad + inputName + "->exponent = " + i.exponent + ";\n");
                        }

                        // Detect time flag
                        String mode = "";
                        if      (i.operands.length == 2) mode = i.operands[1].getString ();
                        else if (i.operands.length >= 4) mode = i.operands[3].getString ();
                        if (mode.contains ("time"))
                        {
                            context.result.append (pad + inputName + "->time = true;\n");
                            if (! context.global  &&  ! T.equals ("int"))  // Note: In the case of T==int, we don't need to set epsilon because it is already set to 1 by the constructor.
                            {
                                // Read $t' as an lvalue, to ensure we get any newly-set frequency.
                                context.result.append (pad + inputName + "->epsilon = " + resolve (bed.dt.reference, context, true) + " / 1000;\n");
                            }
                        }
                    }
                    return true;
                }
                return true;
            }
        };
        op.visit (visitor);
    }

    /**
        Build complex sub-expressions into a single local variable that can be referenced by the equation.
    **/
    public void prepareDynamicObjects (Operator op, final RendererC context, final boolean init, final String pad) throws Exception
    {
        final BackendDataC bed = context.bed;

        // Pass 1 -- Strings and matrix expressions
        Visitor visitor1 = new Visitor ()
        {
            public boolean visit (Operator op)
            {
                if (op instanceof BuildMatrix)
                {
                    BuildMatrix m = (BuildMatrix) op;
                    int rows = m.getRows ();
                    int cols = m.getColumns ();

                    String matrixName = "Matrix" + matrixNames.size ();
                    matrixNames.put (m, matrixName);
                    context.result.append (pad + "MatrixFixed<" + T + "," + rows + "," + cols + "> " + matrixName + ";\n");
                    for (int r = 0; r < rows; r++)
                    {
                        if (cols == 1)
                        {
                            context.result.append (pad + matrixName + "[" + r + "] = ");
                            m.operands[0][r].render (context);
                            context.result.append (";\n");
                        }
                        else
                        {
                            for (int c = 0; c < cols; c++)
                            {
                                context.result.append (pad + matrixName + "(" + r + "," + c + ") = ");
                                m.operands[c][r].render (context);
                                context.result.append (";\n");
                            }
                        }
                    }
                    return false;
                }
                if (op instanceof Add)
                {
                    Add a = (Add) op;
                    String stringName = stringNames.get (a);
                    if (stringName != null)
                    {
                        context.result.append (pad + "String " + stringName + ";\n");
                        for (Operator o : flattenAdd (a))
                        {
                            context.result.append (pad + stringName + " += ");
                            o.render (context);
                            context.result.append (";\n");
                        }
                        return false;
                    }
                }
                return true;
            }
        };
        op.visit (visitor1);

        // Pass 2 -- I/O functions
        Visitor visitor2 = new Visitor ()
        {
            public boolean visit (Operator op)
            {
                if (op instanceof ReadMatrix)
                {
                    ReadMatrix r = (ReadMatrix) op;
                    if (! (r.operands[0] instanceof Constant))
                    {
                        String matrixName = matrixNames.get (r);
                        String stringName = stringNames.get (r.operands[0]);
                        context.result.append (pad + "MatrixInput<" + T + "> * " + matrixName + " = matrixHelper<" + T + "> (" + stringName + ", " + r.exponent + ");\n");
                    }
                    return false;
                }
                if (op instanceof Input)
                {
                    Input i = (Input) op;
                    if (! (i.operands[0] instanceof Constant))
                    {
                        String inputName = inputNames.get (i);
                        String stringName = stringNames.get (i.operands[0]);
                        context.result.append (pad + "InputHolder<" + T + "> * " + inputName + " = inputHelper<" + T + "> (" + stringName + ");\n");
                        if (T.equals ("int"))
                        {
                            context.result.append (pad + inputName + "->exponent = " + i.exponent + ";\n");
                        }

                        // Detect time flag
                        String mode = "";
                        if      (i.operands.length == 2) mode = i.operands[1].getString ();
                        else if (i.operands.length >= 4) mode = i.operands[3].getString ();
                        if (mode.contains ("time"))
                        {
                            context.result.append (pad + inputName + "->time = true;\n");
                            if (! context.global  &&  ! T.equals ("int"))
                            {
                                context.result.append (pad + inputName + "->epsilon = " + resolve (bed.dt.reference, context, true) + " / 1000;\n");
                            }
                        }
                    }
                    return true;  // I/O functions can be nested
                }
                if (op instanceof Output)
                {
                    Output o = (Output) op;
                    if (! (o.operands[0] instanceof Constant))
                    {
                        String outputName = outputNames.get (o);
                        String stringName = stringNames.get (o.operands[0]);
                        context.result.append (pad + "OutputHolder<" + T + "> * " + outputName + " = outputHelper<" + T + "> (" + stringName + ");\n");

                        if (o.operands.length >= 4  &&  o.operands[3].getString ().contains ("raw"))
                        {
                            context.result.append (pad + outputName + "->raw = true;\n");
                        }
                    }
                    return true;
                }
                return true;
            }
        };
        op.visit (visitor2);
    }

    public List<Operator> flattenAdd (Add add)
    {
        ArrayList<Operator> result = new ArrayList<Operator> ();
        if (add.operand0 instanceof Add) result.addAll (flattenAdd ((Add) add.operand0));
        else                             result.add (add.operand0);
        if (add.operand1 instanceof Add) result.addAll (flattenAdd ((Add) add.operand1));
        else                             result.add (add.operand1);
        return result;
    }

    public static String mangle (Variable v)
    {
        return mangle (v.nameString ());
    }

    public static String mangle (String prefix, Variable v)
    {
        return mangle (prefix, v.nameString ());
    }

    public static String mangle (String input)
    {
        return mangle ("_", input);
    }

    public static String mangle (String prefix, String input)
    {
        StringBuilder result = new StringBuilder (prefix);
        for (char c : input.toCharArray ())
        {
            // Even though underscore (_) is a legitimate character in a C identifier,
            // we don't use it.  Instead they are used as an escape for unicode.
            // We use variable length unicode values because there is no need to parse
            // the identifiers back into wide characters.
            if (   ('a' <= c && c <= 'z')
                || ('A' <= c && c <= 'Z')
                || ('0' <= c && c <= '9'))
            {
                result.append (c);
            }
            else
            {
                result.append ("_" + Integer.toHexString (c));
            }
        }
        return result.toString ();
    }

    public String type (Variable v)
    {
        if (v.type instanceof Matrix)
        {
            Matrix m = (Matrix) v.type;
            return "MatrixFixed<" + T + "," + m.rows () + "," + m.columns () + ">";
        }
        if (v.type instanceof Text) return "String";
        return T;
    }

    public static String zero (String name, Variable v) throws Exception
    {
        if      (v.type instanceof Scalar) return name + " = 0";
        else if (v.type instanceof Matrix) return "::clear (" + name + ")";
        else if (v.type instanceof Text  ) return name + ".clear ()";
        else
        {
            Backend.err.get ().println ("Unknown Type");
            throw new Backend.AbortRun ();
        }
    }

    public static String clear (String name, Variable v, double value, RendererC context) throws Exception
    {
        String p = context.print (value, v.exponent);
        if      (v.type instanceof Scalar) return name + " = " + p;
        else if (v.type instanceof Matrix) return "::clear (" + name + ", " + p + ")";
        else if (v.type instanceof Text  ) return name + ".clear ()";
        else
        {
            Backend.err.get ().println ("Unknown Type");
            throw new Backend.AbortRun ();
        }
    }

    public static String clearAccumulator (String name, Variable v, RendererC context) throws Exception
    {
        switch (v.assignment)
        {
            case Variable.MULTIPLY:
            case Variable.DIVIDE:   return clear (name, v, 1, context);
            case Variable.MIN:      return clear (name, v, Double.POSITIVE_INFINITY, context);
            case Variable.MAX:      return clear (name, v, Double.NEGATIVE_INFINITY, context);
            case Variable.ADD:
            default:                return zero (name, v);
        }
    }

    public static String prefix (EquationSet t)
    {
        if (t == null) return "Wrapper";
        String result = mangle (t.name);
        while (t != null)
        {
            t = t.container;
            if (t != null) result = mangle (t.name) + "_" + result;
        }
        return result;
    }

    public String resolve (VariableReference r, RendererC context, boolean lvalue)
    {
        return resolve (r, context, lvalue, "", false);
    }

    /**
        @param v A variable to convert into C++ code that can access it at runtime.
        @param context For the AST rendering system.
        @param lvalue Indicates that this will receive a value assignment. The other case is an rvalue, which will simply be read.
        @param base Injects a pointer at the beginning of the resolution path.
        @param logical The intended use is in a boolean expression, such as an if-test.
    **/
    public String resolve (VariableReference r, RendererC context, boolean lvalue, String base, boolean logical)
    {
        if (r == null  ||  r.variable == null) return "unresolved";

        if (r.variable.hasAttribute ("constant"))
        {
            EquationEntry e = r.variable.equations.first ();
            StringBuilder temp = context.result;
            StringBuilder result = new StringBuilder ();
            context.result = result;
            e.expression.render (context);
            context.result = temp;
            return result.toString ();
        }

        String containers = resolveContainer (r, context, base);

        if (r.variable.name.equals ("(connection)"))
        {
            return stripDereference (containers);
        }

        String name = "";
        BackendDataC bed = (BackendDataC) r.variable.container.backendData;  // NOT context.bed !
        if (r.variable.hasAttribute ("preexistent"))
        {
            String vname = r.variable.name;
            if (vname.startsWith ("$"))
            {
                int vorder = r.variable.order;
                if (vname.equals ("$t"))
                {
                    if (! lvalue)
                    {
                        if      (vorder == 0) name = "Simulator<" + T + ">::instance.currentEvent->t";
                        else if (vorder == 1)
                        {
                            if (context.hasEvent) name = "event->dt";
                            else                  name = "getEvent ()->dt";
                        }
                        // Higher orders of $t should not be "preexistent". They are handled by the main case below.
                    }
                    // for lvalue, fall through to the main case below
                }
                else if (vname.equals ("$n"))
                {
                    if (! lvalue  &&  vorder == 0)
                    {
                        name = "n";
                    }
                }
            }
            else
            {
                return vname;  // most likely a local variable, for example "rc" in mapIndex()
            }
        }
        if (r.variable.name.equals ("$live"))
        {
            if (r.variable.hasAttribute ("accessor"))
            {
                if (lvalue) return "unresolved";
                name = "getLive ()";
            }
            else  // not "constant" or "accessor", so must be direct access
            {
                if (logical) return "(" + containers + "flags & (" + bed.localFlagType + ") 0x1 << " + bed.liveFlag + ")";
                else return "((" + containers + "flags & (" + bed.localFlagType + ") 0x1 << " + bed.liveFlag + ") ? 1 : 0)";
            }
        }
        else if (r.variable.hasAttribute ("accessor"))
        {
            return "unresolved";  // At present, only $live can have "accessor" attribute.
        }
        if (r.variable.name.endsWith (".$count"))
        {
            if (lvalue) return "unresolved";
            String alias = r.variable.name.substring (0, r.variable.name.lastIndexOf ("."));
            name = mangle (alias) + "->" + prefix (r.variable.container) + "_" + mangle (alias) + "_count";
        }
        if (name.length () == 0)
        {
            if (lvalue  &&  (bed.globalBuffered.contains (r.variable)  ||  bed.localBuffered.contains (r.variable)))
            {
                name = mangle ("next_", r.variable);
            }
            else
            {
                name = mangle (r.variable);
            }
        }
        return containers + name;
    }

    /**
        Compute a series of pointers to get from current part to r.
        Result does not include the variable name itself.
    **/
    public String resolveContainer (VariableReference r, RendererC context, String base)
    {
        String containers = base;
        EquationSet current = context.part;
        boolean global = context.global;
        int last = r.resolution.size () - 1;
        for (int i = 0; i <= last; i++)
        {
            Object o = r.resolution.get (i);
            if (o instanceof EquationSet)  // We are following the containment hierarchy.
            {
                EquationSet s = (EquationSet) o;
                if (s.container == current)  // descend into one of our contained populations
                {
                    if (i == last  &&  r.variable.hasAttribute ("global"))  // descend to the population object
                    {
                        // No need to cast the population instance, because it is explicitly typed
                        containers += mangle (s.name) + ".";
                        global = true;
                    }
                    else  // descend to a singleton instance of the population.
                    {
                        BackendDataC bed = (BackendDataC) s.backendData;
                        if (! bed.singleton)
                        {
                            Backend.err.get ().println ("ERROR: Down-reference to population with more than one instance is ambiguous.");
                            throw new AbortRun ();
                        }
                        containers += mangle (s.name) + ".instance.";
                        global = false;
                    }
                }
                else  // ascend to our container
                {
                    containers = containerOf (current, i == 0  &&  context.global, containers);
                    global = false;
                }
                current = s;
            }
            else if (o instanceof ConnectionBinding)  // We are following a part reference (which means we are a connection)
            {
                ConnectionBinding c = (ConnectionBinding) o;
                containers += mangle (c.alias) + "->";
                current = c.endpoint;
                global = false;
            }
        }

        if (r.variable.hasAttribute ("global")  &&  ! global)
        {
            // Must ascend to our container and then descend to our population object.
            containers = containerOf (current, false, containers);
            containers += mangle (current.name) + ".";
        }

        return containers;
    }

    /**
        We either have direct access to our container, or we are a connection using indirect access through an endpoint.
        @param global Indicates that the current context is a population class. Because Population::container
        is declared as a generic part in runtime.h, access requires a typecast.
    **/
    public String containerOf (EquationSet s, boolean global, String base)
    {
        BackendDataC bed = (BackendDataC) s.backendData;
        if (bed.pathToContainer != null  &&  ! global) base += mangle (bed.pathToContainer) + "->";
        base += "container";
        if (global) return "((" + prefix (s.container) + " *) " + base + ")->";
        return base + "->";
    }

    public String stripDereference (String containers)
    {
        if (containers.endsWith ("->")) return containers.substring (0, containers.length () - 2);
        if (containers.endsWith ("." )) return containers.substring (0, containers.length () - 1);
        return containers;
    }

    /**
        Generate code to enumerate all instances of a connection endpoint. Handles deep hierarchical
        embedding.

        <p>A connection resolution can take 3 kinds of step:
        <ul>
        <li>Up to container
        <li>Down to a population
        <li>Through another connection
        </ul>

        @param current EquationSet associated with the context of the current step of resolution.
        @param pointer Name of a pointer to the context for the current step of resolution. Can
        be a chain of pointers. Can be empty if the code is to be emitted in the current context.
        @param depth Position in the resolution array of our next step.
        @param prefix Spaces to insert in front of each line to maintain nice indenting.
    **/
    public void assembleInstances (EquationSet current, String pointer, List<Object> resolution, int depth, String prefix, StringBuilder result)
    {
        int last = resolution.size () - 1;
        for (int i = depth; i <= last; i++)
        {
            Object r = resolution.get (i);
            if (r instanceof EquationSet)
            {
                EquationSet s = (EquationSet) r;
                if (r == current.container)  // ascend to parent
                {
                    pointer = containerOf (current, i == 0, pointer);
                }
                else  // descend to child
                {
                    pointer += mangle (s.name) + ".";
                    if (i < last)  // Enumerate the instances of child population.
                    {
                        if (depth == 0)
                        {
                            result.append (prefix + "result->instances = new vector<Part<" + T + "> *>;\n");
                            result.append (prefix + "result->deleteInstances = true;\n");
                        }
                        String it = "it" + i;
                        result.append (prefix + "for (auto " + it + " : " + pointer + "instances)\n");
                        result.append (prefix + "{\n");
                        assembleInstances (s, it + "->", resolution, i+1, prefix + "  ", result);
                        result.append (prefix + "}\n");
                        return;
                    }
                }
                current = s;
            }
            else if (r instanceof ConnectionBinding)
            {
                ConnectionBinding c = (ConnectionBinding) r;
                pointer += mangle (c.alias) + "->";
                current = c.endpoint;
            }
            // else something is broken. This case should never occur.
        }

        // "pointer" now references the target population.
        // Collect its instances.
        BackendDataC bed = (BackendDataC) current.backendData;
        if (bed.singleton)
        {
            result.append (prefix + "bool newborn = " + pointer + "instance.flags & (" + bed.localFlagType + ") 0x1 << " + bed.newborn + ";\n");
            if (depth == 0)
            {
                result.append (prefix + "result->instances = new vector<Part<" + T + "> *> (1);\n");
                result.append (prefix + "result->deleteInstances = true;\n");
            }
            result.append (prefix + "if (result->firstborn == INT_MAX  &&  newborn) result->firstborn = result->instances->size ();\n");
            result.append (prefix + "result->instances->push_back (& " + pointer + "instance);\n");
        }
        else
        {
            if (depth == 0)  // No enumerations occurred during the resolution, so no list was created.
            {
                // Simply reference the existing list of instances.
                result.append (prefix + "result->firstborn = " + pointer + "firstborn;\n");
                result.append (prefix + "result->instances = (vector<Part<" + T + "> *> *) & " + pointer + "instances;\n");
            }
            else  // Enumerations occurred, so we are already accumulating a list.
            {
                // Append instances to accumulating list.
                result.append (prefix + "if (result->firstborn == INT_MAX  &&  " + pointer + "firstborn < " + pointer + "instances.size ()) result->firstborn = result->instances->size () + " + pointer + "firstborn;\n");
                result.append (prefix + "result->instances->insert (result->instances->end (), " + pointer + "instances.begin (), " + pointer + "instances.end ());\n");
            }
        }

        // Schedule the population to have its newborn flags cleared.
        // We assume that any newborn flags along the path to this population are either unimportant
        // or will get cleared elsewhere.
        result.append (prefix + "if (! (" + pointer + "flags & (" + bed.globalFlagType + ") 0x1 << " + bed.clearNew + "))\n");
        result.append (prefix + "{\n");
        result.append (prefix + "  " + pointer + "flags |= (" + bed.globalFlagType + ") 0x1 << " + bed.clearNew + ";\n");
        pointer = stripDereference (pointer);
        if (pointer.isEmpty ()) pointer = "this";
        else                    pointer = "& " + pointer;
        result.append (prefix + "  Simulator<" + T + ">::instance.clearNew (" + pointer + ");\n");
        result.append (prefix + "}\n");
    }
}
