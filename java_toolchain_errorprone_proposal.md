# Design Proposal: Pluggable Error Prone Plugins via `java_package_configuration`

This document proposes extending the `java_package_configuration` API to allow users to dynamically plug in extra Error Prone checks/plugins on a per-package basis.

---

## 1. Preamble

Error Prone is a static analysis tool for Java integrated directly into Bazel's Java compiler wrapper (`JavaBuilder`). While Error Prone comes with a set of built-in bug checkers, developers often need to enforce custom static analysis rules (e.g., custom checks written within a company's internal repository, or open-source check suites like NullAway or EPRecommenders).

Currently, there is no direct mechanism to plug in extra Error Prone plugins per target or package group using the toolchain. To run custom checks, teams either have to:
- Package the plugins globally in their default compiler classpath, making it difficult to control which packages run them.
- Manually configure compilation plugin dependencies on every single target, leading to configuration bloat and maintenance overhead.

Since Error Prone dynamically discovers plugin checks on the compiler's processorpath (via service discovery using `META-INF/services/com.google.errorprone.bugpatterns.BugChecker`), we can leverage the existing `java_package_configuration` API to dynamically inject custom plugin jars into the compilation classpath on a package-by-package basis.

---

## 2. User-Facing API

We propose adding a new attribute `errorprone_plugins` to the `java_package_configuration` rule. This attribute will accept targets supplying compiler plugins (targets that provide `JavaPluginInfo` or `JavaInfo`).

### Example Usage

#### 1. Define custom Error Prone plugin targets
Custom checkers are packaged as standard Java plugins (usually without explicit classnames as Error Prone uses `ServiceLoader` discovery):

```starlark
# //tools/errorprone:BUILD
java_plugin(
    name = "nullaway_plugin",
    generates_api = False,
    deps = ["@maven//:com_uber_nullaway_nullaway"],
)
```

#### 2. Configure package-specific Error Prone checks
Define a `java_package_configuration` rule matching specific packages and including both the `-Xep` options and the pluggable plugin targets:

```starlark
# //tools/errorprone:BUILD
java_package_configuration(
    name = "strict_null_checking",
    packages = [":my_package_group"],
    javacopts = [
        "-Xep:NullAway:ERROR",
        "-XepOpt:NullAway:AnnotatedPackages=com.example.project",
    ],
    errorprone_plugins = [
        ":nullaway_plugin",
    ],
)

package_group(
    name = "my_package_group",
    packages = [
        "//src/main/java/com/example/project/...",
    ],
)
```

#### 3. Register the package configuration with the Toolchain
Operators associate this package configuration with the global `java_toolchain`:

```starlark
# //tools/toolchains:BUILD
java_toolchain(
    name = "my_java_toolchain",
    package_configuration = [
        "//tools/errorprone:strict_null_checking",
    ],
    # ... other toolchain attributes
)
```

---

## 3. Implied Changes in `rules_java`

The `java_package_configuration` rule and its returned provider are defined in Starlark under the `rules_java` repository at `java/common/rules/java_package_configuration.bzl`.

### 3.1. Rule Definition Updates
Add the `errorprone_plugins` attribute to the `java_package_configuration` rule definition:

```starlark
java_package_configuration = rule(
    implementation = _java_package_configuration_impl,
    attrs = {
        "packages": attr.label_list(
            providers = [PackageSpecificationProvider],
            doc = "Package specifications for which configuration should be applied.",
        ),
        "javacopts": attr.string_list(
            doc = "javac options.",
        ),
        "data": attr.label_list(
            allow_files = True,
            doc = "Data files needed for compilation.",
        ),
        "errorprone_plugins": attr.label_list(
            providers = [JavaPluginInfo],
            cfg = "exec",
            doc = "Extra Error Prone plugin targets to be loaded.",
        ),
    },
)
```

### 3.2. Merging & Propagating Plugin Data
Within the rule implementation, extract `JavaPluginInfo` from the plugin targets and merge them into a single `JavaPluginData` structure. The merged structure is then passed inside `JavaPackageConfigurationInfo`:

```starlark
def _java_package_configuration_impl(ctx):
    # Extract JavaPluginInfo plugins
    plugin_data_list = []
    for target in ctx.attr.errorprone_plugins:
        if JavaPluginInfo in target:
            plugin_data_list.append(target[JavaPluginInfo].plugins)

    # Merge individual plugin datasets into a single struct
    merged_plugins = _merge_plugin_data(plugin_data_list)

    return [
        JavaPackageConfigurationInfo(
            package_specs = ctx.attr.packages,
            javac_opts = depset(ctx.attr.javacopts),
            data = depset(ctx.files.data),
            errorprone_plugins = merged_plugins,
        )
    ]

def _merge_plugin_data(plugin_data_list):
    processor_classes = []
    processor_jars = []
    processor_data = []
    for p in plugin_data_list:
        processor_classes.append(p.processor_classes)
        processor_jars.append(p.processor_jars)
        processor_data.append(p.processor_data)

    return struct(
        processor_classes = depset(transitive = processor_classes),
        processor_jars = depset(transitive = processor_jars),
        processor_data = depset(transitive = processor_data),
    )
```

---

## 4. Changes in Core Bazel

Core Bazel must consume the `errorprone_plugins` data from the package configurations and merge it during the compilation action construction.

### 4.1. Expose `errorprone_plugins` in Java Provider wrapper
Update [JavaPackageConfigurationProvider.java](file:///home/jonathanp/github/bazel/src/main/java/com/google/devtools/build/lib/rules/java/JavaPackageConfigurationProvider.java) to parse the `errorprone_plugins` struct from the underlying Starlark info:

```diff
diff --git a/src/main/java/com/google/devtools/build/lib/rules/java/JavaPackageConfigurationProvider.java b/src/main/java/com/google/devtools/build/lib/rules/java/JavaPackageConfigurationProvider.java
--- a/src/main/java/com/google/devtools/build/lib/rules/java/JavaPackageConfigurationProvider.java
+++ b/src/main/java/com/google/devtools/build/lib/rules/java/JavaPackageConfigurationProvider.java
@@ -20,6 +20,7 @@
 import com.google.devtools.build.lib.actions.Artifact;
 import com.google.devtools.build.lib.analysis.ConfiguredTarget;
 import com.google.devtools.build.lib.analysis.PackageSpecificationProvider;
+import com.google.devtools.build.lib.rules.java.JavaPluginInfo.JavaPluginData;
 import com.google.devtools.build.lib.cmdline.Label;
 import com.google.devtools.build.lib.collect.nestedset.Depset;
 import com.google.devtools.build.lib.collect.nestedset.NestedSet;
@@ -87,6 +88,16 @@
     }
   }
 
+  /** Extra Error Prone plugins to run for this configuration. */
+  JavaPluginData errorpronePlugins() throws RuleErrorException {
+    try {
+      Object value = underlying.getValue("errorprone_plugins");
+      return value == null ? JavaPluginData.empty() : JavaPluginData.wrap(value);
+    } catch (EvalException e) {
+      throw new RuleErrorException(e);
+    }
+  }
+
   /**
    * Returns true if this configuration matches the current label: that is, if the label's package
```

### 4.2. Inject Plugins during compilation
Modify [JavaCompilationHelper.java](file:///home/jonathanp/github/bazel/src/main/java/com/google/devtools/build/lib/rules/java/JavaCompilationHelper.java) inside compilation action creation to extract the matching package configuration's plugin data, merging it with the compiled target's existing plugins:

```diff
diff --git a/src/main/java/com/google/devtools/build/lib/rules/java/JavaCompilationHelper.java b/src/main/java/com/google/devtools/build/lib/rules/java/JavaCompilationHelper.java
--- a/src/main/java/com/google/devtools/build/lib/rules/java/JavaCompilationHelper.java
+++ b/src/main/java/com/google/devtools/build/lib/rules/java/JavaCompilationHelper.java
@@ -165,6 +165,19 @@
     ImmutableList<Artifact> sourceJars = attributes.getSourceJars();
     JavaPluginData plugins = attributes.plugins().plugins();
+
+    // Extract and merge extra Error Prone plugins from matching package configurations
+    ImmutableList.Builder<JavaPluginData> packageEpPlugins = ImmutableList.builder();
+    for (JavaPackageConfigurationProvider provider : javaToolchain.packageConfiguration()) {
+      if (provider.matches(ruleContext.getLabel())) {
+        packageEpPlugins.add(provider.errorpronePlugins());
+      }
+    }
+    ImmutableList<JavaPluginData> epPluginsList = packageEpPlugins.build();
+    if (!epPluginsList.isEmpty()) {
+      plugins = JavaPluginData.merge(
+          ImmutableList.<JavaPluginData>builder()
+              .add(plugins)
+              .addAll(epPluginsList)
+              .build());
+    }
+
     List<Artifact> resourceJars = new ArrayList<>();
```

This naturally propagates the custom plugins through the compilation classpath (for both header compilation under turbine and full javac execution via `JavaBuilder`), while also registering the plugin jars as required action inputs.
