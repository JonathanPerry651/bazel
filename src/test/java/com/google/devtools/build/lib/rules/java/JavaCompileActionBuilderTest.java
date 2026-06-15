// Copyright 2022 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.rules.java;

import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.rules.java.JavaCompileActionTestHelper.getDirectJars;
import static com.google.devtools.build.lib.rules.java.JavaCompileActionTestHelper.getJavacArguments;

import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.CommandAction;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.testutil.MoreAsserts;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.view.proto.Deps;
import com.google.devtools.build.lib.view.proto.Deps.Dependency.Kind;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link JavaCompileActionBuilder}. */
@RunWith(JUnit4.class)
public final class JavaCompileActionBuilderTest extends BuildViewTestCase {

  @Test
  public void testClassdirIsInBlazeOut() throws Exception {
    scratch.file(
        "java/com/google/test/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_binary")
        java_binary(
            name = "a",
            srcs = ["a.java"],
        )
        """);
    JavaCompileAction action =
        (JavaCompileAction) getGeneratingActionForLabel("//java/com/google/test:a.jar");
    List<String> command = new ArrayList<>();
    command.addAll(getJavacArguments(action));
    MoreAsserts.assertContainsSublist(
        command,
        "--output",
        targetConfig
            .getBinFragment(RepositoryName.MAIN)
            .getRelative("java/com/google/test/a.jar")
            .getPathString());
  }

  @Test
  public void progressMessage() throws Exception {
    scratch.file(
        "java/com/google/test/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "a",
            srcs = [
                "a.java",
                "b.java",
            ],
        )
        """);
    JavaCompileAction action =
        (JavaCompileAction) getGeneratingActionForLabel("//java/com/google/test:liba.jar");
    assertThat(action.getProgressMessage())
        .isEqualTo("Building java/com/google/test/liba.jar (2 source files)");
  }

  @Test
  public void progressMessageWithSourceJars() throws Exception {
    scratch.file(
        "java/com/google/test/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "a",
            srcs = [
                "a.java",
                "archive.srcjar",
                "b.java",
            ],
        )
        """);
    JavaCompileAction action =
        (JavaCompileAction) getGeneratingActionForLabel("//java/com/google/test:liba.jar");
    assertThat(action.getProgressMessage())
        .isEqualTo("Building java/com/google/test/liba.jar (2 source files, 1 source jar)");
  }

  @Test
  public void progressMessageAnnotationProcessors() throws Exception {
    scratch.file(
        "java/com/google/test/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library", "java_plugin")
        java_plugin(
            name = "foo",
            srcs = ["Foo.java"],
            processor_class = "Foo",
        )

        java_plugin(
            name = "bar",
            srcs = ["Bar.java"],
            processor_class = "com.google.Bar",
        )

        java_library(
            name = "a",
            srcs = [
                "a.java",
                "archive.srcjar",
                "b.java",
            ],
            plugins = [
                ":foo",
                ":bar",
            ],
        )
        """);
    JavaCompileAction action =
        (JavaCompileAction) getGeneratingActionForLabel("//java/com/google/test:liba.jar");
    assertThat(action.getProgressMessage())
        .isEqualTo(
            "Building java/com/google/test/liba.jar (2 source files, 1 source jar)"
                + " and running annotation processors (Foo, Bar)");
  }

  @Test
  public void testLocale() throws Exception {
    scratch.file(
        "java/com/google/test/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "a",
            srcs = ["A.java"],
        )
        """);
    JavaCompileAction action =
        (JavaCompileAction) getGeneratingActionForLabel("//java/com/google/test:liba.jar");
    assertThat(action.getIncompleteEnvironmentForTesting())
        .containsEntry("LC_CTYPE", analysisMock.isThisBazel() ? "C.UTF-8" : "en_US.UTF-8");
  }

  @Test
  public void testClasspathReduction() throws Exception {
    scratch.file(
        "java/com/google/test/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "a",
            srcs = ["A.java"],
            deps = [":b"],
        )

        java_library(
            name = "b",
            srcs = ["B.java"],
            deps = [
                ":c",
                ":d",
            ],
        )

        java_library(
            name = "c",
            srcs = ["C.java"],
        )

        java_library(
            name = "d",
            srcs = ["D.java"],
        )
        """);
    Artifact bJdeps =
        getBinArtifact("libb-hjar.jdeps", getConfiguredTarget("//java/com/google/test:b"));
    Artifact cHjar =
        getBinArtifact("libc-hjar.jar", getConfiguredTarget("//java/com/google/test:libc.jar"));
    JavaCompileAction action =
        (JavaCompileAction) getGeneratingActionForLabel("//java/com/google/test:liba.jar");
    JavaCompileActionContext context = new JavaCompileActionContext();
    Deps.Dependency dep =
        Deps.Dependency.newBuilder()
            .setKind(Kind.EXPLICIT)
            .setPath(cHjar.getExecPathString())
            .build();
    context.insertDependencies(bJdeps, Deps.Dependencies.newBuilder().addDependency(dep).build());
    assertThat(
            artifactsToStrings(
                action.getReducedClasspath(new ActionExecutionContextBuilder().build(), context)))
        .containsExactly(
            "bin java/com/google/test/libb-hjar.jar", "bin java/com/google/test/libc-hjar.jar");
  }

  @Test
  public void testTurbineCpuReservation() throws Exception {
    useConfiguration("--java_header_compilation=true", "--experimental_turbine_cpu_reservation=2");
    scratch.file(
        "java/com/google/test/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "a",
            srcs = ["A.java"],
            deps = [":b"],
        )
        java_library(
            name = "b",
            srcs = ["b.java"],
        )
        """);
    JavaCompileAction compileAction =
        (JavaCompileAction) getGeneratingActionForLabel("//java/com/google/test:liba.jar");
    Action action = getTurbineAction(compileAction);

    if (TestConstants.PRODUCT_NAME.equals("bazel")) {
      assertThat(paramFileArgsForAction(action)).contains("-XDnoParallel");
    } else {
      assertThat(paramFileArgsForAction(action)).doesNotContain("-XDnoParallel");
    }
    assertThat(action.getExecutionInfo().keySet().stream().filter(k -> k.startsWith("cpu:")))
        .containsExactly("cpu:2");
  }

  @Test
  public void testNoTurbineCpuReservation() throws Exception {
    useConfiguration("--java_header_compilation=true");
    scratch.file(
        "java/com/google/test/BUILD",
        """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "a",
            srcs = ["A.java"],
            deps = [":b"],
        )
        java_library(
            name = "b",
            srcs = ["b.java"],
        )
        """);
    JavaCompileAction compileAction =
        (JavaCompileAction) getGeneratingActionForLabel("//java/com/google/test:liba.jar");
    Action action = getTurbineAction(compileAction);

    if (TestConstants.PRODUCT_NAME.equals("bazel")) {
      assertThat(paramFileArgsForAction(action)).contains("-XDnoParallel");
    } else {
      assertThat(paramFileArgsForAction(action)).doesNotContain("-XDnoParallel");
    }
    assertThat(action.getExecutionInfo().keySet().stream().filter(k -> k.startsWith("cpu:")))
        .isEmpty();
  }

  @Test
  public void testExtraErrorPronePlugins() throws Exception {
    scratch.file("java/com/google/test/a.java", "package com.google.test; class A {}");
    scratch.file("java/com/google/test/plugin.jar", "");
    scratch.file("java/com/google/test/ep_data.txt", "some data");
    scratch.file(
        "java/com/google/test/rules.bzl",
        """
        load("@rules_java//java/private:java_info.bzl", "JavaInfo", "JavaPluginInfo")

        def _dummy_plugin_impl(ctx):
            jar_java_info = JavaInfo(
                output_jar = ctx.file.jar,
                compile_jar = ctx.file.jar,
            )
            plugin_info = JavaPluginInfo(
                runtime_deps = [jar_java_info],
                processor_class = ctx.attr.processor_class,
                data = ctx.files.data,
            )
            return [plugin_info]

        dummy_plugin = rule(
            implementation = _dummy_plugin_impl,
            attrs = {
                "jar": attr.label(allow_single_file = True, mandatory = True),
                "data": attr.label_list(allow_files = True),
                "processor_class": attr.string(mandatory = True),
            },
        )
        """);
    scratch.file(
        "java/com/google/test/BUILD",
        """
        load("//java/com/google/test:rules.bzl", "dummy_plugin")
        load("@rules_java//java:defs.bzl", "java_library")
        load("@rules_java//java/toolchains:java_toolchain.bzl", "java_toolchain")

        dummy_plugin(
            name = "ep_plugin",
            jar = "plugin.jar",
            processor_class = "com.google.errorprone.EpPlugin",
            data = ["ep_data.txt"],
        )

        java_toolchain(
            name = "custom_toolchain",
            bootclasspath = ["@bazel_tools//tools/jdk:bootclasspath"],
            genclass = "@bazel_tools//tools/jdk:GenClass_deploy.jar",
            header_compiler = "@bazel_tools//tools/jdk:turbine_deploy.jar",
            header_compiler_direct = "@bazel_tools//tools/jdk:TurbineDirect_deploy.jar",
            ijar = "@bazel_tools//tools/jdk:ijar",
            jacocorunner = "@bazel_tools//tools/jdk:JacocoCoverage",
            java_runtime = "@bazel_tools//tools/jdk:host_jdk",
            javabuilder = "@bazel_tools//tools/jdk:JavaBuilder_deploy.jar",
            singlejar = "@bazel_tools//tools/jdk:singlejar",
            source_version = "8",
            target_version = "8",
            extra_errorprone_plugins = [":ep_plugin"],
        )

        toolchain(
            name = "custom_toolchain_reg",
            toolchain = ":custom_toolchain",
            toolchain_type = "@bazel_tools//tools/jdk:toolchain_type",
        )

        java_library(
            name = "a",
            srcs = ["a.java"],
        )
        """);

    useConfiguration("--extra_toolchains=//java/com/google/test:custom_toolchain_reg");

    JavaCompileAction action =
        (JavaCompileAction) getGeneratingActionForLabel("//java/com/google/test:liba.jar");

    // Check processor names
    assertThat(JavaCompileActionTestHelper.getProcessorNames(action))
        .doesNotContain("com.google.errorprone.EpPlugin");

    // Check processor path contains our plugin jar
    boolean foundPluginJar = false;
    for (String path : JavaCompileActionTestHelper.getProcessorpath(action)) {
      if (path.contains("plugin.jar")) {
        foundPluginJar = true;
        break;
      }
    }
    assertThat(foundPluginJar).isTrue();

    // Check inputs contain the extra errorprone plugin's data file
    boolean foundDataFile = false;
    for (Artifact input : action.getInputs().toList()) {
      if (input.getFilename().equals("ep_data.txt")) {
        foundDataFile = true;
        break;
      }
    }
    assertThat(foundDataFile).isTrue();
  }

  private CommandAction getTurbineAction(JavaCompileAction compileAction) throws Exception {
    return (CommandAction)
        getGeneratingAction(getBinArtifacts(compileAction).collect(onlyElement()));
  }

  private static Stream<Artifact> getBinArtifacts(JavaCompileAction compileAction)
      throws Exception {
    return getInputs(compileAction, getDirectJars(compileAction)).stream()
        .filter(a -> a.getFilename().endsWith("-hjar.jar"));
  }
}
