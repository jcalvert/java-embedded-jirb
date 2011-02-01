/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.vetstreet.embedded.jirb;

import java.io.Closeable;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;

import jline.Terminal;
//import org.apache.karaf.shell.console.Completer;
//import org.apache.karaf.shell.console.completer.AggregateCompleter;
//import org.apache.karaf.shell.console.jline.Console;
import org.apache.sshd.common.Factory;
import org.apache.sshd.server.Command;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.Signal;
import org.apache.sshd.server.SignalListener;
//import org.osgi.service.blueprint.container.ReifiedType;
//import org.osgi.service.command.CommandProcessor;
//import org.osgi.service.command.CommandSession;

/**
 * SSHD {@link org.apache.sshd.server.Command} factory which provides access to Shell.
 *
 * @version $Rev: 731517 $ $Date: 2009-01-05 11:25:19 +0100 (Mon, 05 Jan 2009) $
 */
public class ShellFactoryImpl implements Factory<Command>
{
    private CommandProcessor commandProcessor;

    public void setCommandProcessor(CommandProcessor commandProcessor) {
        this.commandProcessor = commandProcessor;
    }

    public Command create() {
    	return new ShellImpl();
    }

    public class ShellImpl implements Command
    {
        private InputStream in;

        private OutputStream out;

        private OutputStream err;

        private ExitCallback callback;

        private boolean closed;
        
        private Thread consoleThread;

        public void setInputStream(final InputStream in) {
            this.in = in;
        }

        public void setOutputStream(final OutputStream out) {
            this.out = out;
        }

        public void setErrorStream(final OutputStream err) {
            this.err = err;
        }

        public void setExitCallback(ExitCallback callback) {
            this.callback = callback;
        }

        public void start(final Environment env) throws IOException {
            try {
                final Terminal terminal = new SshTerminal(env);
                Console console = new Console(new CommandProcessor(),
                                              in,
                                              new PrintStream(new LfToCrLfFilterOutputStream(out), true),
                                              new PrintStream(new LfToCrLfFilterOutputStream(err), true),
                                              terminal,
                                              new Runnable() {
                                                  public void run() {
                                                      destroy();
                                                  }
                                              });
                final CommandSession session = console.getSession();
                session.put("APPLICATION", System.getProperty("karaf.name", "r00t"));
                for (Map.Entry<String,String> e : env.getEnv().entrySet()) {
                    session.put(e.getKey(), e.getValue());
                }
                session.put("LINES", Integer.toString(terminal.getTerminalHeight()));
                session.put("COLUMNS", Integer.toString(terminal.getTerminalWidth()));
                env.addSignalListener(new SignalListener() {
                    public void signal(Signal signal) {
                        session.put("LINES", Integer.toString(terminal.getTerminalHeight()));
                        session.put("COLUMNS", Integer.toString(terminal.getTerminalWidth()));
                    }
                }, Signal.WINCH);
                session.put(".jline.terminal", terminal);
                consoleThread = new Thread(console);
                consoleThread.start();
            } catch (Exception e) {
                throw (IOException) new IOException("Unable to start shell").initCause(e);
            }
        }

        public void destroy() {
        	if (!closed) {
                closed = true;
                consoleThread.interrupt();
                ShellFactoryImpl.flush(out, err);
                ShellFactoryImpl.close(in, out, err);
                callback.onExit(0);
            }
        }

    }

    private static void flush(OutputStream... streams) {
        for (OutputStream s : streams) {
            try {
                s.flush();
            } catch (IOException e) {
                // Ignore
            }
        }
    }

    private static void close(Closeable... closeables) {
        for (Closeable c : closeables) {
            try {
                c.close();
            } catch (IOException e) {
                // Ignore
            }
        }
    }



    // TODO: remove this class when sshd use lf->crlf conversion by default
    public class LfToCrLfFilterOutputStream extends FilterOutputStream {

        private boolean lastWasCr;

        public LfToCrLfFilterOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            if (!lastWasCr && b == '\n') {
                out.write('\r');
                out.write('\n');
            } else {
                out.write(b);
            }
            lastWasCr = b == '\r';
        }

    }


}
