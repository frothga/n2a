/*
Copyright 2013-2020 National Technology & Engineering Solutions of Sandia, LLC (NTESS).
Under the terms of Contract DE-NA0003525 with NTESS,
the U.S. Government retains certain rights in this software.
*/

package gov.sandia.n2a.execenvs;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.pastdev.jsch.DefaultSessionFactory;
import com.pastdev.jsch.SessionFactory;

import gov.sandia.n2a.db.MNode;

public class Connection implements Closeable, AutoCloseable
{
    protected Session        session;
    protected FileSystem     sshfs;
    protected ConnectionInfo passwords = new ConnectionInfo ();
    protected String         hostname;
    protected String         username;
    protected int            port;
    protected String         home;  // Path of user's home directory on remote system. Includes leading slash.

    protected static JSch jsch = new JSch ();
    static
    {
        JSch.setConfig ("max_input_buffer_size", "1048576");  // Memory is cheap these days, so set generous buffer size (1MiB).

        // Load ssh configuration files
        Path homeDir = Paths.get (System.getProperty ("user.home")).toAbsolutePath ();
        Path sshDir = homeDir.resolve (".ssh");  // with dot
        if (! Files.isDirectory (sshDir)) sshDir = homeDir.resolve ("ssh");  // without dot
        try
        {
            if (! Files.isDirectory (sshDir))  // no ssh dir, so create one
            {
                sshDir = homeDir.resolve (".ssh");  // use dot, as this is more universal
                Files.createDirectories (sshDir);
            }

            // Known hosts
            Path known_hosts = sshDir.resolve ("known_hosts");
            if (! Files.exists (known_hosts))  // create empty known_hosts file
            {
                Files.createFile (known_hosts);
            }
            jsch.setKnownHosts (known_hosts.toString ());

            // Identities
            try (DirectoryStream<Path> stream = Files.newDirectoryStream (sshDir))
            {
                for (Path path : stream)
                {
                    if (Files.isDirectory (path)) continue;
                    String name = path.getFileName ().toString ();
                    if (! name.startsWith ("id_")) continue;
                    if (name.contains (".")) continue;  // avoid .pub files
                    jsch.addIdentity (path.toAbsolutePath ().toString ());
                }
            }
        }
        catch (Exception e) {}
    }

    public Connection (MNode config)
    {
        hostname = config.getOrDefault (config.key (),                    "hostname");
        username = config.getOrDefault (System.getProperty ("user.name"), "username");
        port     = config.getOrDefault (22,                               "port");
        home     = config.getOrDefault ("/home/" + username,              "home");
    }

    public void connect () throws JSchException
    {
        if (session != null  &&  session.isConnected ()) return;

        session = jsch.getSession (username, hostname, port);
        session.setUserInfo (passwords);
        session.connect (30000);
    }

    public void close ()
    {
        if (session != null  &&  session.isConnected ()) session.disconnect ();
        session = null;
    }

    /**
        @return A file system bound to the remote host. Default directory for relative paths
        is the user's home. Absolute paths are with respect to the usual root directory.
        @throws JSchException
    **/
    public FileSystem getFileSystem () throws Exception
    {
        connect ();
        if (sshfs == null)
        {
            SingleSessionFactory factory = new SingleSessionFactory ();
            Map<String,Object> environment = new HashMap<String,Object> ();
            environment.put ("defaultSessionFactory", factory);
            sshfs = FileSystems.newFileSystem (new URI ("ssh.unix://" + hostname + home), environment);
        }
        return sshfs;
    }

    public RemoteProcessBuilder build (String... command) throws JSchException
    {
        return new RemoteProcessBuilder (command);
    }

    public class RemoteProcessBuilder
    {
        protected RemoteProcess process;
        protected Path          fileIn;
        protected Path          fileOut;
        protected Path          fileErr;

        public RemoteProcessBuilder (String... command) throws JSchException
        {
            String combined = "";
            if (command.length > 0) combined = command[0];
            for (int i = 1; i < command.length; i++) combined += " " + command[i];
            process = new RemoteProcess (combined);
        }

        public RemoteProcessBuilder redirectInput (Path file)
        {
            fileIn = file;
            return this;
        }

        public RemoteProcessBuilder redirectOutput (Path file)
        {
            fileOut = file;
            return this;
        }

        public RemoteProcessBuilder redirectError (Path file)
        {
            fileErr = file;
            return this;
        }

        public RemoteProcess start () throws Exception
        {
            // Streams must be configured before connect.
            // A redirected stream is of the opposite type from what we would read directly.
            // IE: stdout (from the perspective of the remote process) must feed into something
            // on our side. We either read it directly, in which case it is an input stream,
            // or we redirect it to file, in which case it is an output stream.
            // Can this get any more confusing?
            // One thing that makes it confusing is that the JSch does not pair get/set methods.
            // For example, getOutputStream() and setOutputStream() do not actually connect the same stream.
            // Instead, getOutputStream() connects stdout, while setOutputStream() connects stdin.
            if (fileIn == null)  process.stdin = process.channel.getOutputStream ();
            else                 process.channel.setInputStream (Files.newInputStream (fileIn));
            if (fileOut == null) process.stdout = process.channel.getInputStream ();
            else                 process.channel.setOutputStream (Files.newOutputStream (fileOut));
            if (fileErr == null) process.stderr = process.channel.getErrStream ();
            else                 process.channel.setErrStream (Files.newOutputStream (fileErr));

            process.channel.connect ();
            return process;
        }
    }

    public class RemoteProcess implements Closeable, AutoCloseable
    {
        protected ChannelExec channel;

        // The following streams are named from the perspective of the remote process.
        // IE: the stdin of the remote process will receive input from us.
        protected OutputStream stdin;  // From our perspective, transmitting data, this needs to be an output stream.
        protected InputStream  stdout;
        protected InputStream  stderr;

        public RemoteProcess (String command) throws JSchException
        {
            connect ();
            channel = (ChannelExec) session.openChannel ("exec");
            channel.setCommand (command);
        }

        public void close ()
        {
            if (channel != null) channel.disconnect ();  // OK to call disconnect() multiple times
        }

        public OutputStream getOutputStream ()
        {
            if (stdin == null) stdin = new NullOutputStream ();
            return stdin;
        }

        public InputStream getInputStream ()
        {
            if (stdout == null) stdout = new NullInputStream ();
            return stdout;
        }

        public InputStream getErrorStream ()
        {
            if (stderr == null) stderr = new NullInputStream ();
            return stderr;
        }

        public boolean isAlive ()
        {
            return ! channel.isClosed ();
        }

        public int waitFor ()
        {
            try
            {
                while (! channel.isClosed ()) Thread.sleep (1000);
            }
            catch (InterruptedException e) {}
            return channel.getExitStatus ();
        }

        public int exitValue ()
        {
            return channel.getExitStatus ();
        }
    }

    // The following null streams were copied from ProcessBuilder.
    // Unfortunately, they are private to that class, so can't be used here.

    public static class NullInputStream extends InputStream
    {
        public int read ()
        {
            return -1;
        }

        public int available ()
        {
            return 0;
        }
    }

    public static class NullOutputStream extends OutputStream
    {
        public void write (int b) throws IOException
        {
            throw new IOException ("Stream closed");
        }
    }

    // Override main connection class in jsch-extension to re-use Connection.session.
    // Most of the work that jsch-extension does is redundant with our Connection class,
    // including process management. However, jsch-nio provides a complete and usable
    // implementation of FileSystem, so we live with its awkward dependencies.
    public class SingleSessionFactory extends DefaultSessionFactory
    {
        public Session newSession () throws JSchException
        {
            connect ();
            return session;
        }

        public SessionFactoryBuilder newSessionFactoryBuilder ()
        {
            // The values passed to the constructor here are irrelevant.
            // The connection will be created outside this machinery.
            return new SessionFactoryBuilder (jsch, username, hostname, port, getProxy (), null, passwords)
            {
                public SessionFactory build ()
                {
                    return SingleSessionFactory.this;
                }
            };
        }
    }
}
