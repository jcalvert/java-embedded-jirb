package com.vetstreet.embedded.jirb;

import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.commons.io.output.NullOutputStream;
import org.jruby.embed.LocalContextScope;
import org.jruby.embed.LocalVariableBehavior;
import org.jruby.embed.ParseFailedException;
import org.jruby.embed.ScriptingContainer;

public class CommandSession extends HashMap {
	
	private static final long serialVersionUID = 3962540500562683641L;

	ScriptingContainer container;
	
	PrintStream printStream;
	
	StringBuilder commandBuffer = new StringBuilder();
	
	List<Character> chardepth = new ArrayList<Character>();
	
//	Pattern f1 = Pattern.compile("{");
//	Pattern f2 = Pattern.compile("}");
//	Pattern f1 = Pattern.compile("{");
//	Pattern f1 = Pattern.compile("{");
		
	public CommandSession(InputStream in, PrintStream printStream,
			PrintStream printStream2) {
		 container = new ScriptingContainer(LocalContextScope.THREADSAFE, LocalVariableBehavior.PERSISTENT);
		 container.setInput(in);
		 this.printStream = printStream;
		 container.setOutput(printStream);
		 container.setError(new PrintStream(NullOutputStream.NULL_OUTPUT_STREAM));		
	}

	public Object execute(String command) throws Exception {
		try{
			Object returnValue = null;
			commandBuffer.append(command);
			returnValue = container.runScriptlet(commandBuffer.toString());		
			container.put("special_thing", returnValue);
			returnValue = container.runScriptlet("p special_thing");
			commandBuffer = new StringBuilder();
			return returnValue;
		}catch(ParseFailedException e)
		{
			if(!e.getMessage().contains("unexpected"))
			{
				throw e;
			}else{
				commandBuffer.append("\n");
			}
			return null;
		}
	}
	
	public void close()
	{
		System.out.println("Terminating.");
		container.terminate();
	}

	public PrintStream getConsole() {
		return printStream;
	}

	@Override
	public Object get(Object key) {
		if(key.equals(Console.PROMPT))
		{
			if(commandBuffer.length() > 0)
			{				
				return "jirb* ";
			}
			return "jirb> ";			
		}
		return super.get(key);
	}

	public void setContainer(ScriptingContainer container) {
		this.container = container;
	}

	public StringBuilder getCommandBuffer() {
		return commandBuffer;
	}

	public void setCommandBuffer(StringBuilder commandBuffer) {
		this.commandBuffer = commandBuffer;
	}

}
