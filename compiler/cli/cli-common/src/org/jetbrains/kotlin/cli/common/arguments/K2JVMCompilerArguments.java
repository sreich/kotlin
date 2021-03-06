/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.cli.common.arguments;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.cli.common.parser.com.sampullara.cli.Argument;
import org.jetbrains.kotlin.config.JvmTarget;

public class K2JVMCompilerArguments extends CommonCompilerArguments {
    public static final long serialVersionUID = 0L;

    @Argument(value = "d", description = "Destination for generated class files")
    @ValueDescription("<directory|jar>")
    public String destination;

    @Argument(value = "classpath", alias = "cp", description = "Paths where to find user class files")
    @ValueDescription("<path>")
    public String classpath;

    @GradleOption(DefaultValues.BooleanFalseDefault.class)
    @Argument(value = "include-runtime", description = "Include Kotlin runtime in to resulting .jar")
    public boolean includeRuntime;

    @GradleOption(DefaultValues.StringNullDefault.class)
    @Argument(value = "jdk-home", description = "Path to JDK home directory to include into classpath, if differs from default JAVA_HOME")
    @ValueDescription("<path>")
    public String jdkHome;

    @GradleOption(DefaultValues.BooleanFalseDefault.class)
    @Argument(value = "no-jdk", description = "Don't include Java runtime into classpath")
    public boolean noJdk;

    @GradleOption(DefaultValues.BooleanTrueDefault.class)
    @Argument(value = "no-stdlib", description = "Don't include Kotlin runtime into classpath")
    public boolean noStdlib;

    @GradleOption(DefaultValues.BooleanTrueDefault.class)
    @Argument(value = "no-reflect", description = "Don't include Kotlin reflection implementation into classpath")
    public boolean noReflect;

    @Argument(value = "module", description = "Path to the module file to compile")
    @ValueDescription("<path>")
    public String module;

    @Argument(value = "script", description = "Evaluate the script file")
    public boolean script;

    @Argument(value = "script-templates", description = "Script definition template classes")
    @ValueDescription("<fully qualified class name[,]>")
    public String[] scriptTemplates;

    @Argument(value = "kotlin-home", description = "Path to Kotlin compiler home directory, used for runtime libraries discovery")
    @ValueDescription("<path>")
    public String kotlinHome;

    @Argument(value = "module-name", description = "Module name")
    public String moduleName;

    @GradleOption(DefaultValues.JvmTargetVersions.class)
    @Argument(value = "jvm-target", description = "Target version of the generated JVM bytecode (1.6 or 1.8), default is 1.6")
    @ValueDescription("<version>")
    public String jvmTarget;

    @GradleOption(DefaultValues.BooleanFalseDefault.class)
    @Argument(value = "java-parameters", description = "Generate metadata for Java 1.8 reflection on method parameters")
    public boolean javaParameters;

    // Advanced options
    @Argument(value = "Xno-call-assertions", description = "Don't generate not-null assertion after each invocation of method returning not-null")
    public boolean noCallAssertions;

    @Argument(value = "Xno-param-assertions", description = "Don't generate not-null assertions on parameters of methods accessible from Java")
    public boolean noParamAssertions;

    @Argument(value = "Xno-optimize", description = "Disable optimizations")
    public boolean noOptimize;

    @Argument(value = "Xreport-perf", description = "Report detailed performance statistics")
    public boolean reportPerf;

    @Argument(value = "Xmultifile-parts-inherit", description = "Compile multifile classes as a hierarchy of parts and facade")
    public boolean inheritMultifileParts;

    @Argument(value = "Xskip-runtime-version-check", description = "Allow Kotlin runtime libraries of incompatible versions in the classpath")
    public boolean skipRuntimeVersionCheck;

    @Argument(value = "Xdump-declarations-to", description = "Path to JSON file to dump Java to Kotlin declaration mappings")
    @ValueDescription("<path>")
    public String declarationsOutputPath;

    @Argument(value = "Xsingle-module", description = "Combine modules for source files and binary dependencies into a single module")
    public boolean singleModule;

    @Argument(value = "Xadd-compiler-builtins", description = "Add definitions of built-in declarations to the compilation classpath (useful with -no-stdlib)")
    public boolean addCompilerBuiltIns;

    @Argument(value = "Xload-builtins-from-dependencies", description = "Load definitions of built-in declarations from module dependencies, instead of from the compiler")
    public boolean loadBuiltInsFromDependencies;

    @Argument(value = "Xscript-resolver-environment", description = "Script resolver environment in key-value pairs (the value could be quoted and escaped)")
    @ValueDescription("<key=value[,]>")
    public String[] scriptResolverEnvironment;

    // Paths to output directories for friend modules.
    public String[] friendPaths;

    @NotNull
    public static K2JVMCompilerArguments createDefaultInstance() {
        K2JVMCompilerArguments arguments = new K2JVMCompilerArguments();
        arguments.jvmTarget = JvmTarget.DEFAULT.getDescription();
        return arguments;
    }

    @Override
    @NotNull
    public String executableScriptFileName() {
        return "kotlinc-jvm";
    }
}
