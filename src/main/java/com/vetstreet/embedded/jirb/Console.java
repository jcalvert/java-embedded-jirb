package com.vetstreet.embedded.jirb;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.CharArrayWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

//import jline.AnsiWindowsTerminal;
import jline.ConsoleReader;
import jline.Terminal;
import jline.UnsupportedTerminal;
//import org.apache.felix.gogo.commands.CommandException;
//import org.apache.jirb.shell.console.CloseShellException;
//import org.apache.jirb.shell.console.Completer;
//import org.apache.jirb.shell.console.completer.CommandsCompleter;
//import org.fusesource.jansi.Ansi;
//import org.osgi.service.command.CommandProcessor;
//import org.osgi.service.command.CommandSession;
//import org.osgi.service.command.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Console implements Runnable
{

    public static final String SHELL_INIT_SCRIPT = "jirb.shell.init.script";
    public static final String PROMPT = "PROMPT";
    public static final String DEFAULT_PROMPT = "${USER}";
    public static final String PRINT_STACK_TRACES = "jirb.printStackTraces";
    public static final String LAST_EXCEPTION = "jirb.lastException";

    private static final Logger LOGGER = LoggerFactory.getLogger(Console.class);

    protected CommandSession session;
    private ConsoleReader reader;
    private BlockingQueue<Integer> queue;
    private boolean interrupt;
    private Thread pipe;
    volatile private boolean running;
    volatile private boolean eof;
    private Runnable closeCallback;
    private Terminal terminal;
    private InputStream consoleInput;
    private InputStream in;
    private PrintStream out;
    private PrintStream err;
    private Thread thread;

    public Console(CommandProcessor processor,
                   InputStream in,
                   PrintStream out,
                   PrintStream err,
                   Terminal term,
                   Runnable closeCallback) throws Exception
    {
        this.in = in;
        this.out = out;
        this.err = err;
        this.queue = new ArrayBlockingQueue<Integer>(1024);
        this.terminal = term == null ? new UnsupportedTerminal() : term;
        this.consoleInput = new ConsoleInputStream();
        this.session = processor.createSession(this.consoleInput, this.out, this.err);
        this.closeCallback = closeCallback;

        reader = new ConsoleReader(this.consoleInput,
                                   new PrintWriter(this.out),
                                   getClass().getResourceAsStream("keybinding.properties"),
                                   this.terminal);

        File file = new File(System.getProperty("jirb.history",
                             new File(System.getProperty("user.home"), ".jirb_history").toString()));
        file.getParentFile().mkdirs();
        reader.getHistory().setHistoryFile(file);
        session.put(".jline.history", reader.getHistory());
//        Completer completer = createCompleter();
//        if (completer != null) {
//            reader.addCompletor(new CompleterAsCompletor(completer));
//        }
        if (Boolean.getBoolean("jline.nobell")) {
            reader.setBellEnabled(false);
        }
        pipe = new Thread(new Pipe());
        pipe.setName("gogo shell pipe thread");
        pipe.setDaemon(true);
    }

    public CommandSession getSession() {
        return session;
    }

    public void close() {
        System.err.println("Closing");
    	if(session != null)
    		session.close();
        running = false;
        pipe.interrupt();
    }

    public void run()
    {
        thread = Thread.currentThread();
        running = true;
        pipe.start();
        welcome();
        setSessionProperties();
        String scriptFileName = System.getProperty(SHELL_INIT_SCRIPT);
        if (scriptFileName != null) {
            Reader r = null;
            try {
                File scriptFile = new File(scriptFileName);
                r = new InputStreamReader(new FileInputStream(scriptFile));
                CharArrayWriter w = new CharArrayWriter();
                int n;
                char[] buf = new char[8192];
                while ((n = r.read(buf)) > 0) {
                    w.write(buf, 0, n);
                }
                session.execute(new String(w.toCharArray()));
            } catch (Exception e) {
                LOGGER.debug("Error in initialization script", e);
                System.err.println("Error in initialization script: " + e.getMessage());
            } finally {
                if (r != null) {
                    try {
                        r.close();
                    } catch (IOException e) {
                        // Ignore
                    }
                }
            }
        }
        while (running) {
            try {
                checkInterrupt();
                String line = reader.readLine(getPrompt());    
                if (line == null)
                {
                    break;
                }
                if(line.equals("exit"))
                {
                	session.close();
                	break;
                }
                //session.getConsole().println("Executing: " + line);
                Object result = session.execute(line);
                if (result != null)
                {
                    session.getConsole().println(result);
                }
            }
            catch (InterruptedIOException e)
            {
                //System.err.println("^C");
                // TODO: interrupt current thread
            }
            catch (Throwable t)
            {
                try {
                    LOGGER.info("Exception caught while executing command", t);
                    session.put(LAST_EXCEPTION, t);
                    if (t instanceof CommandException) {
                        session.getConsole().println(((CommandException) t));
                    }
                    if ( isPrintStackTraces()) {
                        session.getConsole().print(Ansi.ansi().fg(Ansi.Color.RED).toString());
                        t.printStackTrace(session.getConsole());
                        session.getConsole().print(Ansi.ansi().fg(Ansi.Color.DEFAULT).toString());
                    }
                    else if (!(t instanceof CommandException)) {
                        session.getConsole().print(Ansi.ansi().fg(Ansi.Color.RED).toString());
                        session.getConsole().println("Error executing command: "
                                + (t.getMessage() != null ? t.getMessage() : t.getClass().getName()));
                        session.getConsole().print(Ansi.ansi().fg(Ansi.Color.DEFAULT).toString());
                    }
                } catch (Exception ignore) {
                        // ignore
                }
            }
        }
        close();
        //System.err.println("Exiting console...");
        if (closeCallback != null)
        {
            closeCallback.run();
        }
    }

    protected boolean isPrintStackTraces() {
        Object s = session.get(PRINT_STACK_TRACES);
        if (s == null) {
            s = System.getProperty(PRINT_STACK_TRACES);
        }
        if (s == null) {
            return false;
        }
        if (s instanceof Boolean) {
            return (Boolean) s;
        }
        return Boolean.parseBoolean(s.toString());
    }

    protected void welcome() {
        Properties props = loadBrandingProperties();
        String welcome = props.getProperty("welcome");
        if (welcome != null && welcome.length() > 0) {
            session.getConsole().println(welcome);
        }
    }

    protected void setSessionProperties() {
        Properties props = loadBrandingProperties();
        for (Map.Entry<Object, Object> entry : props.entrySet()) {
            String key = (String) entry.getKey();
            if (key.startsWith("session.")) {
                session.put(key.substring("session.".length()), entry.getValue());
            }
        }
    }

//    protected Completer createCompleter() {
//        return new CommandsCompleter(session);
//    }

    protected Properties loadBrandingProperties() {
        Properties props = new Properties();
        return props;
    }

    protected void loadProps(Properties props, String resource) {
        InputStream is = null;
        try {
            is = getClass().getClassLoader().getResourceAsStream(resource);
            if (is != null) {
                props.load(is);
            }
        } catch (IOException e) {
            // ignore
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
    }

    protected String getPrompt() {
        try {
            String prompt;
            try {
                Object p = session.get(PROMPT);
                prompt = p != null ? p.toString() : DEFAULT_PROMPT;
            } catch (Throwable t) {
                prompt = DEFAULT_PROMPT;
            }
            Matcher matcher = Pattern.compile("\\$\\{([^}]+)\\}").matcher(prompt);
            while (matcher.find()) {
                Object rep = session.get(matcher.group(1));
                if (rep != null) {
                    prompt = prompt.replace(matcher.group(0), rep.toString());
                    matcher.reset(prompt);
                }
            }
            return prompt;
        } catch (Throwable t) {
            return "$ ";
        }
    }

    private void checkInterrupt() throws IOException {
        if (Thread.interrupted() || interrupt) {
            interrupt = false;
            throw new InterruptedIOException("Keyboard interruption");
        }
    }

    private void interrupt() {
        interrupt = true;
        thread.interrupt();
    }

    private class ConsoleInputStream extends InputStream
    {
        private int read(boolean wait) throws IOException
        {
            if (!running) {
                return -1;
            }
            checkInterrupt();
            if (eof && queue.isEmpty()) {
                return -1;
            }
            Integer i;
            if (wait) {
                try {
                    i = queue.take();
                } catch (InterruptedException e) {
                    throw new InterruptedIOException();
                }
                checkInterrupt();
            } else {
                i = queue.poll();
            }
            if (i == null) {
                return -1;
            }
            return i;
        }

        @Override
        public int read() throws IOException
        {
            return read(true);
        }

        @Override
        public int read(byte b[], int off, int len) throws IOException
        {
            if (b == null) {
                throw new NullPointerException();
            } else if (off < 0 || len < 0 || len > b.length - off) {
                throw new IndexOutOfBoundsException();
            } else if (len == 0) {
                return 0;
            }

            int nb = 1;
            int i = read(true);
            if (i < 0) {
                return -1;
            }
            b[off++] = (byte) i;
            while (nb < len) {
                i = read(false);
                if (i < 0) {
                    return nb;
                }
                b[off++] = (byte) i;
                nb++;
            }
            return nb;
        }
    }

    private class Pipe implements Runnable
    {
        public void run()
        {
            try {
                while (running)
                {
                    try
                    {
                        int c;
                        c = terminal.readCharacter(in);
                        if (c == -1)
                        {
                            return;
                        }
                        else if (c == 4)
                        {
                            err.println("^D");
                        }
                        else if (c == 3)
                        {
                            err.println("^C");
                            reader.getCursorBuffer().clearBuffer();
                            interrupt();
                        }
                        queue.put(c);
                    }
                    catch (Throwable t) {
                        return;
                    }
                }
            }
            finally
            {
                eof = true;
                try
                {
                    queue.put(-1);
                }
                catch (InterruptedException e)
                {
                }
            }
        }
    }

}
