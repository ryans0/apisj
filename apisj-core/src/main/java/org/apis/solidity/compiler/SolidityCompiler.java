/*
 * Copyright (c) [2016] [ <ether.camp> ]
 * This file is part of the ethereumJ library.
 *
 * The ethereumJ library is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The ethereumJ library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with the ethereumJ library. If not, see <http://www.gnu.org/licenses/>.
 */
package org.apis.solidity.compiler;

import com.google.common.base.Joiner;
import org.apis.config.SystemProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.util.stream.Collectors.toList;

@Component
public class SolidityCompiler {

    private Solc solc;

    private static SolidityCompiler INSTANCE;

    @Autowired
    public SolidityCompiler(SystemProperties config) {
        solc = new Solc(config);
    }

    public static Result compile(File sourceDirectory, boolean combinedJson, Option... options) throws IOException {
        return getInstance().compileSrc(sourceDirectory, false, combinedJson, options);
    }

    /**
     * This class is mainly here for backwards compatibility; however we are now reusing it making it the solely public
     * interface listing all the supported options.
     */
    public static final class Options {
        public static final OutputOption AST = OutputOption.AST;
        public static final OutputOption BIN = OutputOption.BIN;
        public static final OutputOption INTERFACE = OutputOption.INTERFACE;
        public static final OutputOption ABI = OutputOption.ABI;
        public static final OutputOption METADATA = OutputOption.METADATA;
        public static final OutputOption ASTJSON = OutputOption.ASTJSON;

        private static final NameOnlyOption OPTIMIZE = NameOnlyOption.OPTIMIZE;
        private static final NameOnlyOption VERSION = NameOnlyOption.VERSION;

        private static class CombinedJson extends ListOption {
            private CombinedJson(List values) {
                super("combined-json", values);
            }
        }
        public static class AllowPaths extends ListOption {
            public AllowPaths(List values) {
                super("allow-paths", values);
            }
        }
    }

    public interface Option extends Serializable {
        String getValue();
        String getName();
    }

    private static class ListOption implements Option {
        private String name;
        private List values;

        private ListOption(String name, List values) {
            this.name = name;
            this.values = values;
        }

        @Override public String getValue() {
            StringBuilder result = new StringBuilder();
            for (Object value : values) {
                if (OutputOption.class.isAssignableFrom(value.getClass())) {
                    result.append((result.length() == 0) ? ((OutputOption) value).getName() : ',' + ((OutputOption) value).getName());
                } else if (Path.class.isAssignableFrom(value.getClass())) {
                    result.append((result.length() == 0) ? ((Path) value).toAbsolutePath().toString() : ',' + ((Path) value).toAbsolutePath().toString());
                } else if (File.class.isAssignableFrom(value.getClass())) {
                    result.append((result.length() == 0) ? ((File) value).getAbsolutePath() : ',' + ((File) value).getAbsolutePath());
                } else if (String.class.isAssignableFrom(value.getClass())) {
                    result.append((result.length() == 0) ? value : "," + value);
                } else {
                    throw new UnsupportedOperationException("Unexpected type, value '" + value + "' cannot be retrieved.");
                }
            }
            return result.toString();
        }
        @Override public String getName() { return name; }
        @Override public String toString() { return name; }
    }

    private enum NameOnlyOption implements Option {
        OPTIMIZE("optimize"),
        VERSION("version");

        private String name;

        NameOnlyOption(String name) {
            this.name = name;
        }

        @Override public String getValue() { return ""; }
        @Override public String getName() { return name; }
        @Override public String toString() {
            return name;
        }
    }

    private enum OutputOption implements Option {
        AST("ast"),
        BIN("bin"),
        INTERFACE("interface"),
        ABI("abi"),
        METADATA("metadata"),
        ASTJSON("ast-json");

        private String name;

        OutputOption(String name) {
            this.name = name;
        }

        @Override public String getValue() { return ""; }
        @Override public String getName() { return name; }
        @Override public String toString() {
            return name;
        }
    }

    public static class Result {
        public String errors;
        public String output;
        private boolean success;

        public Result(String errors, String output, boolean success) {
            this.errors = errors;
            this.output = output;
            this.success = success;
        }

        public boolean isFailed() {
            return !success;
        }
    }

    private static class ParallelReader extends Thread {

        private InputStream stream;
        private StringBuilder content = new StringBuilder();

        ParallelReader(InputStream stream) {
            this.stream = stream;
        }

        public String getContent() {
            return getContent(true);
        }

        public synchronized String getContent(boolean waitForComplete) {
            if (waitForComplete) {
                while(stream != null) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            return content.toString();
        }

        public void run() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append("\n");
                }
            } catch (IOException ioe) {
                ioe.printStackTrace();
            } finally {
                synchronized (this) {
                    stream = null;
                    notifyAll();
                }
            }
        }
    }

    public static Result compile(byte[] source, boolean combinedJson, Option... options) throws IOException {
        return getInstance().compileSrc(source, false, combinedJson, options);
    }

    public static Result compileOpt(byte[] source, boolean combinedJson, Option... options) throws IOException {
        return getInstance().compileSrc(source, true, combinedJson, options);
    }

    public Result compileSrc(File source, boolean optimize, boolean combinedJson, Option... options) throws IOException {
        List<String> commandParts = prepareCommandOptions(optimize, combinedJson, options);

        commandParts.add(source.getAbsolutePath());

        ProcessBuilder processBuilder = new ProcessBuilder(commandParts)
                .directory(solc.getExecutable().getParentFile());
        processBuilder.environment().put("LD_LIBRARY_PATH",
                solc.getExecutable().getParentFile().getCanonicalPath());

        Process process = processBuilder.start();

        ParallelReader error = new ParallelReader(process.getErrorStream());
        ParallelReader output = new ParallelReader(process.getInputStream());
        error.start();
        output.start();

        try {
            process.waitFor();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        boolean success = process.exitValue() == 0;

        return new Result(error.getContent(), output.getContent(), success);
    }

    private List<String> prepareCommandOptions(boolean optimize, boolean combinedJson, Option... options) throws IOException {
        List<String> commandParts = new ArrayList<>();
        commandParts.add(solc.getExecutable().getCanonicalPath());
        if (optimize) {
            commandParts.add("--" + Options.OPTIMIZE.getName());
        }
        if (combinedJson) {
            Option combinedJsonOption = new Options.CombinedJson(getElementsOf(OutputOption.class, options));
            commandParts.add("--" + combinedJsonOption.getName());
            commandParts.add(combinedJsonOption.getValue());
        } else {
            for (Option option : getElementsOf(OutputOption.class, options)) {
                commandParts.add("--" + option.getName());
            }
        }
        for (Option option : getElementsOf(ListOption.class, options)) {
            commandParts.add("--" + option.getName());
            commandParts.add(option.getValue());
        }
        return commandParts;
    }

    private static <T> List<T> getElementsOf(Class<T> clazz, Option... options) {
        return Arrays.stream(options).filter(clazz::isInstance).map(clazz::cast).collect(toList());
    }

    public Result compileSrc(byte[] source, boolean optimize, boolean combinedJson, Option... options) throws IOException {
        List<String> commandParts = prepareCommandOptions(optimize, combinedJson, options);

        ProcessBuilder processBuilder = new ProcessBuilder(commandParts)
                .directory(solc.getExecutable().getParentFile());
        processBuilder.environment().put("LD_LIBRARY_PATH",
                solc.getExecutable().getParentFile().getCanonicalPath());

        Process process = processBuilder.start();

        try (BufferedOutputStream stream = new BufferedOutputStream(process.getOutputStream())) {
            stream.write(source);
        }

        ParallelReader error = new ParallelReader(process.getErrorStream());
        ParallelReader output = new ParallelReader(process.getInputStream());
        error.start();
        output.start();

        try {
            process.waitFor();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        boolean success = process.exitValue() == 0;

        return new Result(error.getContent(), output.getContent(), success);
    }

    public static String runGetVersionOutput() throws IOException {
        List<String> commandParts = new ArrayList<>();
        commandParts.add(getInstance().solc.getExecutable().getCanonicalPath());
        commandParts.add("--" + Options.VERSION.getName());

        ProcessBuilder processBuilder = new ProcessBuilder(commandParts)
                .directory(getInstance().solc.getExecutable().getParentFile());
        processBuilder.environment().put("LD_LIBRARY_PATH",
                getInstance().solc.getExecutable().getParentFile().getCanonicalPath());

        Process process = processBuilder.start();

        ParallelReader error = new ParallelReader(process.getErrorStream());
        ParallelReader output = new ParallelReader(process.getInputStream());
        error.start();
        output.start();

        try {
            process.waitFor();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        if (process.exitValue() == 0) {
            return output.getContent();
        }

        throw new RuntimeException("Problem getting solc version: " + error.getContent());
    }

    public static SolidityCompiler getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new SolidityCompiler(SystemProperties.getDefault());
        }
        return INSTANCE;
    }
}