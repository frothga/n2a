package gov.sandia.n2a.db;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
    Manages all user data associated with the application.
    This singleton contains several MDir objects which wrap various categories
    of records. We are responsible for loading all data, providing it to the
    rest of the app, and saving it. Saves occur at shutdown, and also on a
    regular interval (every 30 seconds) during operation.
**/
public class AppData
{
    // TODO: combine these as children of a proper root MNode
    public static MDir  models;
    public static MDir  references;
    public static MDir  runs;
    public static MDoc  state;
    public static MNode properties;

    protected static boolean stop;
    protected static Thread saveThread;

    static
    {
        File root = new File (System.getProperty ("user.home"), "n2a").getAbsoluteFile ();
        properties = new MVolatile ();
        properties.set (root.getAbsolutePath (), "resourceDir");

        models     = new MDir (new File (root, "models"));
        references = new MDir (new File (root, "references"));
        runs       = new MDir (new File (root, "jobs"), "model");  // "model" is our internal housekeeping data, in MNode serialization form. Backend output generally goes into a simulator-specific file.
        state      = new MDoc (new File (root, "client.state").getAbsolutePath ());

        stop = false;
        saveThread = new Thread ("Save AppData")
        {
            public void run ()
            {
                while (! stop)
                {
                    try
                    {
                        sleep (30000);
                        AppData.save ();
                    }
                    catch (InterruptedException e)
                    {
                    }
                }
            };
        };
        saveThread.setDaemon (true);  // This thread should be killed gracefully by a call to quit() before the app shuts down. But if not, we don't want it to keep the VM alive.
        saveThread.start ();
    }

    public static void checkInitialDB ()
    {
        if (models.length () > 0) return;

        // Unfortunately, this list must be maintained to match the initial set of models.
        // It is possible to scan the directory, but it requires sifting through the whole jar file.
        // The method used here is more time efficient.
        String[] sourceFiles = new String []
        {
            "Channel",
            "Channel K",
            "Channel Na",
            "Coupling Voltage",
            "Example Hodgkin-Huxley Cable",
            "Neuron",
            //"Neuron Brette",
            "Neuron Hodgkin-Huxley",
            "Neuron Izhikevich",
            "Neuron Izhikevich 2007",
            "Neuron LIF",
            //"Synapse Exponential",
            "Synapse Voltage Step"
        };
        try
        {
            for (String s : sourceFiles)
            {
                MDoc doc = (MDoc) models.set ("", s);
                BufferedReader reader = new BufferedReader (new InputStreamReader ((AppData.class.getResource ("init/" + s).openStream ())));
                reader.readLine ();  // dispose of schema line
                doc.read (reader);
                reader.close ();
            }
        }
        catch (IOException e)
        {
            System.err.println ("Unable to load some or all of initial DB");
        }
    }

    public synchronized static void save ()
    {
        models.save ();
        references.save ();
        runs.save ();  // The reason to save runs is if we record data in them about process status. If no data is changed, could get rid of this save.
        state.save ();
    }

    public static void backup (File destination)
    {
        save ();

        // Assemble file list
        String stem = properties.get ("resourceDir");
        List<String> paths = new LinkedList<String> ();
        for (String f : models    .root.list ()) paths.add (new File ("models",     f).getPath ());
        for (String f : references.root.list ()) paths.add (new File ("references", f).getPath ());

        // Dump to zip
        try
        {
            FileOutputStream fos = new FileOutputStream (destination);
            ZipOutputStream zos = new ZipOutputStream (fos);
            try
            {
                for (String path : paths)
                {
                    zos.putNextEntry (new ZipEntry (path));
                    Files.copy (Paths.get (stem, path), zos);
                }
            }
            finally
            {
                zos.closeEntry ();
                zos.close ();
                fos.close ();
            }
        }
        catch (IOException error)
        {
            System.err.println (error.toString ());
        }
    }

    public static void restore (File source, boolean removeAdded)
    {
        // Purge existing files
        if (removeAdded)
        {
            models.clear ();
            references.clear ();
        }

        // Read the zip file
        try
        {
            String stem = properties.get ("resourceDir");
            ZipFile zipFile = new ZipFile (source);
            Enumeration<? extends ZipEntry> entries = zipFile.entries ();
            while (entries.hasMoreElements ())
            {
                ZipEntry entry = entries.nextElement ();
                InputStream stream = zipFile.getInputStream (entry);
                Files.copy (stream, Paths.get (stem, entry.getName ()), StandardCopyOption.REPLACE_EXISTING);
            }
            zipFile.close ();
        }
        catch (IOException error)
        {
            System.err.println (error.toString ());
        }

        models    .fireChanged ();
        references.fireChanged ();
    }

    public static void quit ()
    {
        stop = true;
        saveThread.interrupt ();
        save ();
    }
}