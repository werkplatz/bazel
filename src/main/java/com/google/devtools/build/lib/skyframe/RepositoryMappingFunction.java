// Copyright 2018 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.skyframe;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleResolutionValue;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionId;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey;
import com.google.devtools.build.lib.bazel.bzlmod.SingleExtensionEvalValue;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.packages.BuildFileContainsErrorsException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.starlark.java.eval.StarlarkSemantics;

/** {@link SkyFunction} for {@link RepositoryMappingValue}s. */
public class RepositoryMappingFunction implements SkyFunction {

  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws SkyFunctionException, InterruptedException {
    StarlarkSemantics starlarkSemantics = PrecomputedValue.STARLARK_SEMANTICS.get(env);
    if (starlarkSemantics == null) {
      return null;
    }
    RepositoryName repositoryName = ((RepositoryMappingValue.Key) skyKey).repoName();

    BazelModuleResolutionValue bazelModuleResolutionValue = null;
    if (starlarkSemantics.getBool(BuildLanguageOptions.ENABLE_BZLMOD)) {
      if (StarlarkBuiltinsValue.isBuiltinsRepo(repositoryName)) {
        // Builtins .bzl files should use the repo mapping of @bazel_tools, to get access to repos
        // such as @platforms.
        RepositoryMappingValue bazelToolsMapping =
            (RepositoryMappingValue)
                env.getValue(RepositoryMappingValue.key(RepositoryName.BAZEL_TOOLS));
        if (bazelToolsMapping == null) {
          return null;
        }
        // We need to make sure that @_builtins maps to @_builtins too.
        return RepositoryMappingValue.withMapping(
            RepositoryMapping.create(
                    ImmutableMap.of(
                        StarlarkBuiltinsValue.BUILTINS_NAME,
                        StarlarkBuiltinsValue.BUILTINS_REPO,
                        // TODO(wyv): Google internal tests that have blzmod enabled fail because
                        //  they try to access cpp tools targets in the main repo from inside the
                        //  @_builtin repo. This is just a workaround and needs a proper way to
                        //  inject this mapping for google internal tests only.
                        "",
                        RepositoryName.MAIN),
                    StarlarkBuiltinsValue.BUILTINS_REPO)
                .withAdditionalMappings(bazelToolsMapping.getRepositoryMapping()));
      }

      bazelModuleResolutionValue =
          (BazelModuleResolutionValue) env.getValue(BazelModuleResolutionValue.KEY);
      if (bazelModuleResolutionValue == null) {
        return null;
      }

      if (repositoryName.isMain()
          && ((RepositoryMappingValue.Key) skyKey).rootModuleShouldSeeWorkspaceRepos()) {
        // The root module should be able to see repos defined in WORKSPACE. Therefore, we find all
        // workspace repos and add them as extra visible repos in root module's repo mappings.
        SkyKey externalPackageKey = PackageValue.key(LabelConstants.EXTERNAL_PACKAGE_IDENTIFIER);
        PackageValue externalPackageValue = (PackageValue) env.getValue(externalPackageKey);
        if (env.valuesMissing()) {
          return null;
        }
        Map<String, RepositoryName> additionalMappings =
            externalPackageValue.getPackage().getTargets().entrySet().stream()
                // We need to filter out the non repository rule targets in the //external package.
                .filter(
                    entry ->
                        entry.getValue().getAssociatedRule() != null
                            && !entry.getValue().getAssociatedRule().getRuleClass().equals("bind"))
                .collect(
                    Collectors.toMap(
                        Entry::getKey, entry -> RepositoryName.createUnvalidated(entry.getKey())));
        return RepositoryMappingValue.withMapping(
            computeForBazelModuleRepo(repositoryName, bazelModuleResolutionValue)
                .get()
                // We need to map the workspace name to the main repo (without this, it would map to
                // itself, which is a local_repository with path="." -- this is very problematic).
                // See https://github.com/bazelbuild/bazel/issues/15657 for more info.
                .withAdditionalMappings(
                    ImmutableMap.of(
                        externalPackageValue.getPackage().getWorkspaceName(), RepositoryName.MAIN))
                .withAdditionalMappings(additionalMappings));
      }

      // Try and see if this is a repo generated from a Bazel module.
      Optional<RepositoryMapping> mapping =
          computeForBazelModuleRepo(repositoryName, bazelModuleResolutionValue);
      if (mapping.isPresent()) {
        return RepositoryMappingValue.withMapping(mapping.get());
      }

      // Now try and see if this is a repo generated from a module extension.
      Optional<ModuleExtensionId> moduleExtensionId =
          maybeGetModuleExtensionForRepo(repositoryName, bazelModuleResolutionValue);

      if (moduleExtensionId.isPresent()) {
        SingleExtensionEvalValue extensionEvalValue =
            (SingleExtensionEvalValue)
                env.getValue(SingleExtensionEvalValue.key(moduleExtensionId.get()));
        if (extensionEvalValue == null) {
          return null;
        }
        return RepositoryMappingValue.withMapping(
            computeForModuleExtensionRepo(
                repositoryName,
                moduleExtensionId.get(),
                extensionEvalValue,
                bazelModuleResolutionValue));
      }
    }

    SkyKey externalPackageKey = PackageValue.key(LabelConstants.EXTERNAL_PACKAGE_IDENTIFIER);
    PackageValue externalPackageValue = (PackageValue) env.getValue(externalPackageKey);
    if (env.valuesMissing()) {
      return null;
    }

    return computeFromWorkspace(repositoryName, externalPackageValue, bazelModuleResolutionValue);
  }

  /**
   * Calculate repo mappings for a repo generated from a Bazel module. Such a repo can see all its
   * {@code bazel_dep}s, as well as any repos generated by an extension it has a {@code use_repo}
   * clause for.
   *
   * @return the repo mappings for the repo if it's generated from a Bazel module, otherwise return
   *     Optional.empty().
   */
  private Optional<RepositoryMapping> computeForBazelModuleRepo(
      RepositoryName repositoryName, BazelModuleResolutionValue bazelModuleResolutionValue) {
    ModuleKey moduleKey =
        bazelModuleResolutionValue.getCanonicalRepoNameLookup().get(repositoryName);
    if (moduleKey == null) {
      return Optional.empty();
    }
    return Optional.of(bazelModuleResolutionValue.getFullRepoMapping(moduleKey));
  }

  /**
   * Calculate repo mappings for a repo generated from a module extension. Such a repo can see all
   * repos generated by the same module extension, as well as all repos that the Bazel module
   * hosting the extension can see (see above).
   *
   * @return the repo mappings for the repo if it's generated from a module extension, otherwise
   *     return Optional.empty().
   */
  private RepositoryMapping computeForModuleExtensionRepo(
      RepositoryName repositoryName,
      ModuleExtensionId extensionId,
      SingleExtensionEvalValue extensionEvalValue,
      BazelModuleResolutionValue bazelModuleResolutionValue) {
    // Find the key of the module containing this extension. This will be used to compute additional
    // mappings -- any repo generated by an extension contained in the module "foo" can additionally
    // see all repos that "foo" can see.
    ModuleKey moduleKey =
        bazelModuleResolutionValue
            .getCanonicalRepoNameLookup()
            .get(extensionId.getBzlFileLabel().getRepository());
    // NOTE(wyv): This means that if "foo" has a bazel_dep with the repo name "bar", and the
    // extension generates an internal repo name "bar", then within a repo generated by the
    // extension, "bar" will refer to the latter. We should explore a way to differentiate between
    // the two to avoid any surprises.
    return RepositoryMapping.create(
            extensionEvalValue.getCanonicalRepoNameToInternalNames().inverse(), repositoryName)
        .withAdditionalMappings(bazelModuleResolutionValue.getFullRepoMapping(moduleKey));
  }

  private SkyValue computeFromWorkspace(
      RepositoryName repositoryName,
      PackageValue externalPackageValue,
      @Nullable BazelModuleResolutionValue bazelModuleResolutionValue)
      throws RepositoryMappingFunctionException {
    Package externalPackage = externalPackageValue.getPackage();
    if (externalPackage.containsErrors()) {
      throw new RepositoryMappingFunctionException();
    }
    if (bazelModuleResolutionValue == null) {
      return RepositoryMappingValue.withMapping(
          RepositoryMapping.createAllowingFallback(
              externalPackage.getRepositoryMapping(repositoryName)));
    }
    // If bzlmod is in play, we need to transform mappings to "foo" into mappings for "foo~1.3" (if
    // there is a module called "foo" in the dep graph and its version is 1.3, that is).
    ImmutableMap<String, ModuleKey> moduleNameLookup =
        bazelModuleResolutionValue.getModuleNameLookup();
    HashMap<String, RepositoryName> mapping = new HashMap<>();
    mapping.putAll(
        Maps.transformValues(
            externalPackage.getRepositoryMapping(repositoryName),
            toRepo -> {
              if (toRepo.isMain()) {
                return toRepo;
              }
              ModuleKey moduleKey = moduleNameLookup.get(toRepo.getName());
              return moduleKey == null ? toRepo : moduleKey.getCanonicalRepoName();
            }));
    // If there's no existing mapping to "foo", we should add a mapping from "foo" to "foo~1.3"
    // anyways.
    for (Map.Entry<String, ModuleKey> entry : moduleNameLookup.entrySet()) {
      mapping.putIfAbsent(entry.getKey(), entry.getValue().getCanonicalRepoName());
    }
    return RepositoryMappingValue.withMapping(
        RepositoryMapping.createAllowingFallback(ImmutableMap.copyOf(mapping)));
  }

  private static Optional<ModuleExtensionId> maybeGetModuleExtensionForRepo(
      RepositoryName repositoryName, BazelModuleResolutionValue bazelModuleResolutionValue) {
    return bazelModuleResolutionValue.getExtensionUniqueNames().entrySet().stream()
        .filter(e -> repositoryName.getName().startsWith(e.getValue() + "~"))
        .map(Entry::getKey)
        .findFirst();
  }

  private static class RepositoryMappingFunctionException extends SkyFunctionException {
    RepositoryMappingFunctionException() {
      super(
          new BuildFileContainsErrorsException(LabelConstants.EXTERNAL_PACKAGE_IDENTIFIER),
          Transience.PERSISTENT);
    }
  }
}
