/*
 * Copyright (c) 2012, 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package test.util;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import junit.framework.AssertionFailedError;
import org.junit.Assert;

/**
 * Utility methods for life-cycle testing
 */
public class Util {

    // Test timeout value in milliseconds
    public static final int TIMEOUT = 10000;

    private static interface Future {
        public abstract boolean await(long timeout, TimeUnit unit);
    }

    public static void throwError(Throwable testError) {
        if (testError != null) {
            if (testError instanceof Error) {
                throw (Error)testError;
            } else if (testError instanceof RuntimeException) {
                throw (RuntimeException)testError;
            } else {
                AssertionFailedError err = new AssertionFailedError("Unknown exception");
                err.initCause(testError.getCause());
                throw err;
            }
        } else {
            AssertionFailedError err = new AssertionFailedError("Unexpected exception");
            throw err;
        }
    }

    public static void sleep(long msec) {
        try {
            Thread.sleep(msec);
        } catch (InterruptedException ex) {}
    }

    public static boolean await(final CountDownLatch latch) {
        try {
            return latch.await(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            throw new AssertionError(ex);
        }
    }

    private static Future submit(final Runnable r, final CountDownLatch delayLatch) {
        final Throwable[] testError = new Throwable[1];
        final CountDownLatch latch = new CountDownLatch(1);

        Platform.runLater(() -> {
            try {
                if (delayLatch != null) {
                    delayLatch.await();
                }
                r.run();
            } catch (Throwable th) {
                testError[0] = th;
            } finally {
                latch.countDown();
            }
        });

        Future future = (timeout, unit) -> {
            try {
                if (!latch.await(timeout, unit)) {
                    return false;
                }
            } catch (InterruptedException ex) {
                AssertionFailedError err = new AssertionFailedError("Unexpected exception");
                err.initCause(ex);
                throw err;
            }

            if (testError[0] != null) {
                if (testError[0] instanceof Error) {
                    throw (Error)testError[0];
                } else if (testError[0] instanceof RuntimeException) {
                    throw (RuntimeException)testError[0];
                } else {
                    AssertionFailedError err = new AssertionFailedError("Unknown execution exception");
                    err.initCause(testError[0].getCause());
                    throw err;
                }
            }

            return true;
        };

        return future;
    }

    public static void runAndWait(Runnable... runnables) {
        runAndWait(false, runnables);
    }

    public static void runAndWait(boolean delay, Runnable... runnables) {
        List<Future> futures = new ArrayList(runnables.length);
        int i = 0;
        CountDownLatch delayLatch = delay ? new CountDownLatch(1) : null;
        for (Runnable r : runnables) {
            futures.add(submit(r, delayLatch));
        }
        if (delayLatch != null) {
            delayLatch.countDown();
        }

        int count = TIMEOUT / 100;
        while (!futures.isEmpty() && count-- > 0) {
            Iterator<Future> it = futures.iterator();
            while (it.hasNext()) {
                Future future = it.next();
                if (future.await(0, TimeUnit.MILLISECONDS)) {
                    it.remove();
                }
            }
            if (!futures.isEmpty()) {
                Util.sleep(100);
            }
        }

        if (!futures.isEmpty()) {
            throw new AssertionFailedError("Exceeded timeout limit of " + TIMEOUT + " msec");
        }
    }

    public static ArrayList<String> createApplicationLaunchCommand(
            String testAppName,
            String testPldrName,
            String testPolicy) throws IOException {

        return createApplicationLaunchCommand(testAppName, testPldrName, testPolicy, null);
    }

    public static ArrayList<String> createApplicationLaunchCommand(
            String testAppName,
            String testPldrName,
            String testPolicy,
            String[] jvmArgs) throws IOException {

        final boolean isJar = testAppName.endsWith(".jar");

        /*
         * note: the "worker" properties are tied into build.gradle
         */
        final String workerJavaCmd = System.getProperty("worker.java.cmd");
        final String workerPatchModuleFile = System.getProperty("worker.patchmodule.file");
        final String workerPatchPolicy = System.getProperty("worker.patch.policy");
        final String workerClassPath = System.getProperty("worker.classpath.file");
        final Boolean workerDebug = Boolean.getBoolean("worker.debug");

        final ArrayList<String> cmd = new ArrayList<>(30);

        if (workerJavaCmd != null) {
            cmd.add(workerJavaCmd);
        } else {
            cmd.add("java");
        }

        if (workerPatchModuleFile != null) {
            cmd.add("@" + workerPatchModuleFile);
        } else {
            System.out.println("Warning: no worker.patchmodule passed to unit test");
        }

        // This is a "minimum" set, rather than the full @addExports
        cmd.add("--add-exports=javafx.graphics/com.sun.javafx.application=ALL-UNNAMED");
        cmd.add("--add-exports=javafx.controls/com.sun.javafx.scene.control=ALL-UNNAMED");

        if (workerClassPath != null) {
            cmd.add("@" + workerClassPath);
        }

        if (testPldrName != null) {
            cmd.add("-Djavafx.preloader=" + testPldrName);
        }

        if (testPolicy != null) {

            cmd.add("-Djava.security.manager");

            try {
                if (workerPatchPolicy != null) {
                    // with Jigsaw, we need to create a merged java.policy
                    // file that contains the permissions for the patchmodule classes
                    // as well as the permissions needed for this test

                    File wpp = new File(workerPatchPolicy);
                    if (!wpp.exists()) {
                        throw new RuntimeException("Missing workerPatchPolicy");
                    }

                    File tempFile = null;
                    if (workerDebug) {
                        String baseAppName = isJar
                                ? testAppName.substring(0, testAppName.length() - 4)
                                : testAppName;
                        final int lastSlashIdx = baseAppName.lastIndexOf("/");
                        if (lastSlashIdx >= 0) {
                            baseAppName = baseAppName.substring(lastSlashIdx + 1);
                        }
                        tempFile = new File(workerPatchPolicy +
                                "_" + baseAppName);
                    } else {
                        tempFile = File.createTempFile("java", "policy");
                        tempFile.deleteOnExit();
                    }

                    BufferedWriter writer = new BufferedWriter(new FileWriter(tempFile));

                    BufferedReader reader1 = new BufferedReader(new FileReader(wpp));
                    URL url = new URL(testPolicy);
                    BufferedReader reader2 = new BufferedReader(new FileReader(url.getFile()));

                    String line = null;
                    while ((line = reader1.readLine()) != null) {
                        writer.write(line);
                        writer.newLine();
                    }
                    while ((line = reader2.readLine()) != null) {
                        writer.write(line);
                        writer.newLine();
                    }
                    writer.close();
                    cmd.add("-Djava.security.policy=" +
                        tempFile.getAbsolutePath().replaceAll("\\\\","/"));
                } else {
                    cmd.add("-Djava.security.policy=" + testPolicy);
                }
            } catch (IOException e) {
                throw e;
            }

        }

        if (jvmArgs != null) {
            for (String arg : jvmArgs) {
                cmd.add(arg);
            }
        }

        if (isJar) {
            cmd.add("-jar");
        }
        cmd.add(testAppName);

        if (workerDebug) {
            System.err.println("Child cmd is");
            cmd.stream().forEach((s) -> {
                System.err.println(" " + s);
            });
            System.err.println("Child cmd: end");
        }

        return cmd;
    }
}
