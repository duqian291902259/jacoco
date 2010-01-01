/*******************************************************************************
 * Copyright (c) 2009 Mountainminds GmbH & Co. KG and others
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Marc R. Hoffmann - initial API and implementation
 *    
 * $Id: $
 *******************************************************************************/
package org.jacoco.core.runtime;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.jacoco.core.instr.GeneratorConstants;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;

/**
 * This {@link IRuntime} implementation uses the Java logging API to report
 * coverage data. The advantage is, that the instrumented classes do not get
 * dependencies to other classes than the JRE library itself.
 * <p>
 * 
 * The implementation uses a dedicated log channel. Instrumented classes call
 * {@link Logger#log(Level, String, Object[])} with the class identifier in the
 * first slot of the parameter array. The runtime implements a {@link Handler}
 * for this channel that puts the probe data structure into the first slot of
 * the parameter array.
 * 
 * @author Marc R. Hoffmann
 * @version $Revision: $
 */
public class LoggerRuntime extends AbstractRuntime {

	private static final String CHANNEL = "jacoco-runtime";

	private final String key;

	private final Logger logger;

	private final Handler handler;

	/**
	 * Creates a new runtime.
	 */
	public LoggerRuntime() {
		this.key = Integer.toHexString(hashCode());
		this.logger = configureLogger();
		this.handler = new RuntimeHandler();
	}

	private Logger configureLogger() {
		final Logger l = Logger.getLogger(CHANNEL);
		l.setUseParentHandlers(false);
		l.setLevel(Level.ALL);
		return l;
	}

	public int generateDataAccessor(final long classid,
			final GeneratorAdapter gen) {

		// 1. Create parameter array:

		gen.push(1);
		gen.newArray(Type.getObjectType("java/lang/Object"));

		// Stack[0]: [Ljava/lang/Object;

		gen.dup();

		// Stack[1]: [Ljava/lang/Object;
		// Stack[0]: [Ljava/lang/Object;

		gen.push(0);
		gen.push(classid);

		// Stack[4]: J
		// Stack[3]: .
		// Stack[2]: I
		// Stack[1]: [Ljava/lang/Object;
		// Stack[0]: [Ljava/lang/Object;

		gen.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf",
				"(J)Ljava/lang/Long;");

		// Stack[3]: Ljava/lang/Long;
		// Stack[2]: I
		// Stack[1]: [Ljava/lang/Object;
		// Stack[0]: [Ljava/lang/Object;

		gen.arrayStore(Type.getObjectType("java/lang/Object"));

		// Stack[0]: [Ljava/lang/Object;

		final int param = gen.newLocal(Type.getObjectType("java/lang/Object"));
		gen.storeLocal(param);

		// 2. Call Logger:

		gen.push(CHANNEL);
		gen.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/logging/Logger",
				"getLogger", "(Ljava/lang/String;)Ljava/util/logging/Logger;");

		// Stack[0]: Ljava/util/logging/Logger;

		gen.getStatic(Type.getObjectType("java/util/logging/Level"), "INFO",
				Type.getObjectType("java/util/logging/Level"));

		// Stack[1]: Ljava/util/logging/Level;
		// Stack[0]: Ljava/util/logging/Logger;

		gen.push(key);

		// Stack[2]: Ljava/lang/String;
		// Stack[1]: Ljava/util/logging/Level;
		// Stack[0]: Ljava/util/logging/Logger;

		gen.loadLocal(param);

		// Stack[3]: [Ljava/lang/Object;
		// Stack[2]: Ljava/lang/String;
		// Stack[1]: Ljava/util/logging/Level;
		// Stack[0]: Ljava/util/logging/Logger;

		gen
				.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
						"java/util/logging/Logger", "log",
						"(Ljava/util/logging/Level;Ljava/lang/String;[Ljava/lang/Object;)V");

		// 3. Load data structure from parameter array:

		gen.loadLocal(param);
		gen.push(0);

		// Stack[1]: I
		// Stack[0]: [Ljava/lang/Object;

		gen.arrayLoad(GeneratorConstants.PROBEDATA_TYPE);
		gen.checkCast(GeneratorConstants.PROBEDATA_TYPE);

		// Stack[0]: [Z

		return 5; // Maximum local stack size is 5
	}

	public void startup() {
		this.logger.addHandler(handler);
	}

	public void shutdown() {
		this.logger.removeHandler(handler);
	}

	private class RuntimeHandler extends Handler {

		@Override
		public void publish(final LogRecord record) {
			if (key.equals(record.getMessage())) {
				final Object[] params = record.getParameters();
				final Long id = (Long) params[0];
				synchronized (store) {
					final boolean[] data = store.getData(id);
					if (data == null) {
						throw new IllegalStateException(String.format(
								"Unknown class id %x.", id));
					}
					params[0] = data;
				}
			}
		}

		@Override
		public void flush() {
		}

		@Override
		public void close() throws SecurityException {
			// The Java logging framework removes and closes all handlers on JVM
			// shutdown. As soon as our handler has been removed, all classes
			// that might get instrumented during shutdown (e.g. loaded by other
			// shutdown hooks) will fail to initialize. Therefore we add ourself
			// again here.
			// This is a nasty hack that might fail in some Java
			// implementations.
			logger.addHandler(handler);
		}
	}

}