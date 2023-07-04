package com.microsoft.buildserver.adapter;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Pattern;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.buildship.core.internal.CorePlugin;
import org.eclipse.buildship.core.internal.GradlePluginsRuntimeException;
import org.eclipse.buildship.core.internal.configuration.GradleProjectNature;
import org.eclipse.buildship.core.internal.workspace.EclipseVmUtil;
import org.eclipse.buildship.core.internal.workspace.GradleNatureAddedEvent;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jdt.core.IClasspathAttribute;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.internal.core.ClasspathEntry;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.jdt.ls.core.internal.JSONUtility;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.ProjectUtils;
import org.eclipse.jdt.ls.core.internal.ResourceUtils;
import org.eclipse.jdt.ls.core.internal.managers.GradleProjectImporter;
import org.eclipse.jdt.ls.core.internal.managers.IBuildSupport;
import org.eclipse.jdt.ls.core.internal.managers.ProjectsManager.CHANGE_TYPE;

import com.microsoft.buildserver.adapter.bsp4j.extended.JvmBuildTargetExt;

import ch.epfl.scala.bsp4j.BuildServer;
import ch.epfl.scala.bsp4j.BuildTarget;
import ch.epfl.scala.bsp4j.BuildTargetIdentifier;
import ch.epfl.scala.bsp4j.BuildTargetTag;
import ch.epfl.scala.bsp4j.DependencyModule;
import ch.epfl.scala.bsp4j.DependencyModulesItem;
import ch.epfl.scala.bsp4j.DependencyModulesParams;
import ch.epfl.scala.bsp4j.DependencyModulesResult;
import ch.epfl.scala.bsp4j.MavenDependencyModule;
import ch.epfl.scala.bsp4j.MavenDependencyModuleArtifact;
import ch.epfl.scala.bsp4j.OutputPathItem;
import ch.epfl.scala.bsp4j.OutputPathsParams;
import ch.epfl.scala.bsp4j.OutputPathsResult;
import ch.epfl.scala.bsp4j.ResourcesItem;
import ch.epfl.scala.bsp4j.ResourcesParams;
import ch.epfl.scala.bsp4j.ResourcesResult;
import ch.epfl.scala.bsp4j.SourceItem;
import ch.epfl.scala.bsp4j.SourcesItem;
import ch.epfl.scala.bsp4j.SourcesParams;
import ch.epfl.scala.bsp4j.SourcesResult;
import ch.epfl.scala.bsp4j.WorkspaceBuildTargetsResult;

/**
 * @author Fred Bricon
 *
 */
public class BspGradleBuildSupport implements IBuildSupport {

    public static final Pattern GRADLE_FILE_EXT = Pattern.compile("^.*\\.gradle(\\.kts)?$");
    public static final String GRADLE_PROPERTIES = "gradle.properties";

    private static final IClasspathAttribute testAttribute = JavaCore.newClasspathAttribute(IClasspathAttribute.TEST, "true");
    private static final IClasspathAttribute optionalAttribute = JavaCore.newClasspathAttribute(IClasspathAttribute.OPTIONAL, "true");

    @Override
    public boolean applies(IProject project) {
        return BspUtils.isBspGradleProject(project);
    }

    @Override
    public void update(IProject project, boolean force, IProgressMonitor monitor) throws CoreException {
        if (!applies(project)) {
            return;
        }
        JavaLanguageServerPlugin.logInfo("Starting Gradle update for " + project.getName());

        File buildFile = project.getFile(BspGradleProjectImporter.BUILD_GRADLE_DESCRIPTOR).getLocation().toFile();
        File settingsFile = project.getFile(BspGradleProjectImporter.SETTINGS_GRADLE_DESCRIPTOR).getLocation().toFile();
        File buildKtsFile = project.getFile(BspGradleProjectImporter.BUILD_GRADLE_KTS_DESCRIPTOR).getLocation().toFile();
        File settingsKtsFile = project.getFile(BspGradleProjectImporter.SETTINGS_GRADLE_KTS_DESCRIPTOR).getLocation().toFile();
        boolean shouldUpdate = force || (buildFile.exists() && BuildServerAdapter.getDigestStore().updateDigest(buildFile.toPath()))
                || (settingsFile.exists() && BuildServerAdapter.getDigestStore().updateDigest(settingsFile.toPath()))
                || (buildKtsFile.exists() && BuildServerAdapter.getDigestStore().updateDigest(buildKtsFile.toPath()))
                || (settingsKtsFile.exists() && BuildServerAdapter.getDigestStore().updateDigest(settingsKtsFile.toPath()));
        if (shouldUpdate) {
            BuildServer buildServer = BuildServerAdapter.getBuildServer();
            if (buildServer == null) {
                return;
            }
            buildServer.workspaceReload().join();
            WorkspaceBuildTargetsResult workspaceBuildTargetsResult = buildServer.workspaceBuildTargets().join();
            List<BuildTarget> buildTargets = workspaceBuildTargetsResult.getTargets();
            Map<String, List<BuildTarget>> buildTargetMap = BspUtils.mapBuildTargetsByUri(buildTargets);
            for (Entry<String, List<BuildTarget>> entrySet : buildTargetMap.entrySet()) {
                String baseDir = entrySet.getKey();
                if (baseDir == null) {
                    JavaLanguageServerPlugin.logError("The base directory of the build target is null.");
                    continue;
                }
                List<BuildTarget> targets = entrySet.getValue();
                if (targets == null || targets.isEmpty()) {
                    continue;
                }
                URI Uri = null;
                try {
                    Uri = new URI(baseDir);
                } catch (URISyntaxException e) {
                    JavaLanguageServerPlugin.logException(e);
                    continue;
                }
                IProject prj = ProjectUtils.getProjectFromUri(Uri.toString());
                BuildServerTargetsManager.getInstance().setBuildTargets(prj, targets);
            }
            updateClassPath(project, true, monitor);
        }
    }

    // todo: refactor the method
    public void updateClassPath(IProject project, boolean updateProjectDependency, IProgressMonitor monitor) throws CoreException {
        BuildServer buildServer = BuildServerAdapter.getBuildServer();
        if (buildServer == null) {
            return;
        }

        IJavaProject javaProject = JavaCore.create(project);
        List<IClasspathEntry> classpath = new LinkedList<>();

        Set<MavenDependencyModule> mainDependencies = new HashSet<>();
        Set<MavenDependencyModule> testDependencies = new HashSet<>();

        List<BuildTarget> buildTargets = BuildServerTargetsManager.getInstance().getBuildTargets(project);
        if (buildTargets == null) {
            JavaLanguageServerPlugin.logError("Cannot find build targets for project " + project.getName());
            return;
        }
        // Sort build targets so that test targets are last, this is to avoid duplicated source roots.
        buildTargets.sort((bt1, bt2) -> {
            if (bt1.getTags().contains(BuildTargetTag.TEST) && !bt2.getTags().contains(BuildTargetTag.TEST)) {
                return 1;
            }
            if (!bt1.getTags().contains(BuildTargetTag.TEST) && bt2.getTags().contains(BuildTargetTag.TEST)) {
                return -1;
            }
            return 0;
        });
        for (BuildTarget buildTarget : buildTargets) {
            boolean isTest = buildTarget.getTags().contains(BuildTargetTag.TEST);

            OutputPathsResult outputResult = buildServer.buildTargetOutputPaths(new OutputPathsParams(Arrays.asList(buildTarget.getId()))).join();
            List<OutputPathItem> outputPaths = outputResult.getItems().get(0).getOutputPaths();
            String sourceOutputUriString = outputPaths.get(0).getUri();
            IPath sourceOutputPath = ResourceUtils.filePathFromURI(sourceOutputUriString);
            File outputDirectory = sourceOutputPath.toFile();
            if (!outputDirectory.exists()) {
                outputDirectory.mkdirs();
            }
            IPath relativeSourceOutputPath = sourceOutputPath.makeRelativeTo(project.getLocation());
            IPath sourceOutputFullPath = project.getFolder(relativeSourceOutputPath).getFullPath();

            SourcesResult sourcesResult = buildServer.buildTargetSources(new SourcesParams(Arrays.asList(buildTarget.getId()))).join();
            for (SourcesItem item : sourcesResult.getItems()) {
                if (!Objects.equals(buildTarget.getId(), item.getTarget())) {
                    continue;
                }
                for (SourceItem source : item.getSources()) {
                    IPath sourcePath = ResourceUtils.filePathFromURI(source.getUri());
                    if (!sourcePath.toFile().exists() && !source.getGenerated()) {
                        continue;
                    }
                    IPath projectLocation = project.getLocation();
                    IPath sourceFullPath;
                    if (projectLocation.isPrefixOf(sourcePath)) {
                        IPath relativeSourcePath = sourcePath.makeRelativeTo(project.getLocation());
                        sourceFullPath = project.getFolder(relativeSourcePath).getFullPath();
                        
                    } else {
                        // if the source path is not relative to the project location, we need to create a linked folder for it.
                        IPath baseDirectory = ResourceUtils.filePathFromURI(buildTarget.getBaseDirectory());
                        IPath relativeSourcePath = sourcePath.makeRelativeTo(baseDirectory);
                        if (relativeSourcePath.isAbsolute()) {
                            JavaLanguageServerPlugin.logError("The source path is not relative to the workspace root: " + relativeSourcePath);
                            continue;
                        }
                        IFolder linkFolder = project.getFolder(relativeSourcePath.toString().replace(IPath.SEPARATOR, '_'));
                        if (!linkFolder.exists()) {
                            linkFolder.createLink(sourcePath, IResource.REPLACE, monitor);
                        }
                        sourceFullPath = linkFolder.getFullPath();
                    }
                    // skip the duplicated source root. Since main source set comes first than test
                    // source set, skipping here is safe.
                    if (classpath.stream().anyMatch(entry -> entry.getPath().equals(sourceFullPath))) {
                        continue;
                    }
                    List<IClasspathAttribute> classpathAttributes = new LinkedList<>();
                    if (isTest) {
                        classpathAttributes.add(testAttribute);
                    }
                    if (source.getGenerated()) {
                        classpathAttributes.add(optionalAttribute);
                    }
                    classpath.add(JavaCore.newSourceEntry(sourceFullPath, null, null, sourceOutputFullPath, classpathAttributes.toArray(new IClasspathAttribute[0])));
                }
            }

            if (!classpath.isEmpty()) {
                addJavaNature(project, monitor);
            }

            if (outputPaths.size() > 1) {
                // TODO: should iterate over all items
                // handle resource output
                String resourceOutputUriString = outputResult.getItems().get(0).getOutputPaths().get(1).getUri();
                IPath resourceOutputPath = ResourceUtils.filePathFromURI(resourceOutputUriString);
                File resourceOutputDirectory = resourceOutputPath.toFile();
                if (!resourceOutputDirectory.exists()) {
                    resourceOutputDirectory.mkdirs();
                }
                IPath relativeResourceOutputPath = resourceOutputPath.makeRelativeTo(project.getLocation());
                IPath resourceOutputFullPath = project.getFolder(relativeResourceOutputPath).getFullPath();

                ResourcesResult resourcesResult = buildServer.buildTargetResources(new ResourcesParams(Arrays.asList(buildTarget.getId()))).join();
                for (ResourcesItem item : resourcesResult.getItems()) {
                    if (!Objects.equals(buildTarget.getId(), item.getTarget())) {
                        continue;
                    }

                    for (String resourceUri : item.getResources()) {
                        IPath resourcePath = ResourceUtils.filePathFromURI(resourceUri);
                    if (!resourcePath.toFile().exists()) {
                        continue;
                    }
                    IPath relativeResourcePath = resourcePath.makeRelativeTo(project.getLocation());
                    IPath resourceFullPath = project.getFolder(relativeResourcePath).getFullPath();
                    List<IClasspathAttribute> classpathAttributes = new LinkedList<>();
                    if (isTest) {
                        classpathAttributes.add(testAttribute);
                    }
                    classpathAttributes.add(optionalAttribute);
                    classpath.add(JavaCore.newSourceEntry(resourceFullPath, null, null, resourceOutputFullPath, classpathAttributes.toArray(new IClasspathAttribute[0])));
                    }
                }
            }

            DependencyModulesResult dependencyModuleResult = buildServer.buildTargetDependencyModules(new DependencyModulesParams(Arrays.asList(buildTarget.getId()))).join();
            for (DependencyModulesItem item : dependencyModuleResult.getItems()) {
                if (!Objects.equals(buildTarget.getId(), item.getTarget())) {
                    continue;
                }
                for (DependencyModule module : item.getModules()) {
                    MavenDependencyModule mavenModule = JSONUtility.toModel(module.getData(), MavenDependencyModule.class);
                    if (isTest) {
                        testDependencies.add(mavenModule);
                    } else {
                        mainDependencies.add(mavenModule);
                    }
                }
            }
        }

        JvmBuildTargetExt jvmBuildTarget = getHighestJavaVersion(buildTargets);
        String javaVersion = getEclipseCompatibleVersion(jvmBuildTarget.getTargetBytecodeVersion());
        IVMInstall vm = EclipseVmUtil.findOrRegisterStandardVM(javaVersion, new File(jvmBuildTarget.getJavaHome()));
        classpath.add(JavaCore.newContainerEntry(JavaRuntime.newJREContainerPath(vm)));

        testDependencies = testDependencies.stream().filter(t -> {
            return !mainDependencies.contains(t);
        }).collect(Collectors.toSet());

        addModuleDependenciesToClasspath(classpath, mainDependencies, false);
        addModuleDependenciesToClasspath(classpath, testDependencies, true);

        javaProject.setRawClasspath(classpath.toArray(IClasspathEntry[]::new), javaProject.getOutputLocation(), monitor);
        // refresh to let JDT be aware of the output folders.
        project.refreshLocal(IResource.DEPTH_INFINITE, monitor);

        if (updateProjectDependency) {
            addProjectDependencies(project, monitor);
        }

        JavaLanguageServerPlugin.getInstance().getClientConnection().sendNotification("_java.buildServer.registerFileWatcher", Collections.emptyList());
    }

    public void addProjectDependencies(IProject project, IProgressMonitor monitor) throws JavaModelException {
        BuildServer buildServer = BuildServerAdapter.getBuildServer();
        if (buildServer == null) {
            return;
        }

        IJavaProject javaProject = JavaCore.create(project);
        List<IClasspathEntry> classpath = new LinkedList<>(Arrays.asList(javaProject.getRawClasspath()));
        List<BuildTarget> buildTargets = BuildServerTargetsManager.getInstance().getBuildTargets(project);
        if (buildTargets == null) {
            JavaLanguageServerPlugin.logError("Cannot find build targets for project " + project.getName());
            return;
        }
        Set<BuildTargetIdentifier> projectDependencies = new HashSet<>();
        for (BuildTarget buildTarget : buildTargets) {
            projectDependencies.addAll(buildTarget.getDependencies());
        }

        addProjectDependenciesToClasspath(classpath, projectDependencies);
        javaProject.setRawClasspath(classpath.toArray(IClasspathEntry[]::new), javaProject.getOutputLocation(), monitor);
    }

    private JvmBuildTargetExt getHighestJavaVersion(List<BuildTarget> buildTargets) throws CoreException {
        List<JvmBuildTargetExt> jvmTargets = new ArrayList<>();
        for (BuildTarget buildTarget : buildTargets) {
            JvmBuildTargetExt model = JSONUtility.toModel(buildTarget.getData(), JvmBuildTargetExt.class);
            if (StringUtils.isBlank(model.getTargetBytecodeVersion()) || StringUtils.isBlank(model.getJavaHome())) {
                continue;
            }
            jvmTargets.add(JSONUtility.toModel(buildTarget.getData(), JvmBuildTargetExt.class));
        }

        if (jvmTargets.isEmpty()) {
            throw new CoreException(new Status(IStatus.ERROR, BuildServerAdapter.PLUGIN_ID, "Failed to get Java version."));
        }

        jvmTargets.sort((j1, j2) -> {
            String[] components1 = j1.getTargetBytecodeVersion().split("\\.");
            String[] components2 = j2.getTargetBytecodeVersion().split("\\.");

            int length = Math.max(components1.length, components2.length);
            for (int i = 0; i < length; i++) {
                int version1 = i < components1.length ? Integer.parseInt(components1[i]) : 0;
                int version2 = i < components2.length ? Integer.parseInt(components2[i]) : 0;

                if (version1 != version2) {
                    return Integer.compare(version1, version2);
                }
            }

            return 0; // versions are equal
        });

        return jvmTargets.get(jvmTargets.size() - 1);
    }

    @Override
    public boolean fileChanged(IResource resource, CHANGE_TYPE changeType, IProgressMonitor monitor) throws CoreException {
        if (resource == null || !applies(resource.getProject())) {
            return false;
        }
        return IBuildSupport.super.fileChanged(resource, changeType, monitor) || isBuildFile(resource);
    }

    @Override
    public boolean isBuildFile(IResource resource) {
        if (resource != null && resource.getType() == IResource.FILE && isBuildLikeFileName(resource.getName())
            && ProjectUtils.hasNature(resource.getProject(), BspGradleProjectNature.NATURE_ID)) {
            try {
                if (!ProjectUtils.isJavaProject(resource.getProject())) {
                    return true;
                }
                IJavaProject javaProject = JavaCore.create(resource.getProject());
                IPath outputLocation = javaProject.getOutputLocation();
                return outputLocation == null || !outputLocation.isPrefixOf(resource.getFullPath());
            } catch (JavaModelException e) {
                JavaLanguageServerPlugin.logException(e.getMessage(), e);
            }
        }
        return false;
    }

    @Override
    public boolean isBuildLikeFileName(String fileName) {
        return GRADLE_FILE_EXT.matcher(fileName).matches() || fileName.equals(GRADLE_PROPERTIES);
    }

    /**
     * Get the Eclipse compatible Java version string.
     * <pre>
     * See: <a href="https://github.com/eclipse/buildship/blob/6727c8779029e86b0585e27784ca90b904b7ce35/
       org.eclipse.buildship.core/src/main/java/org/eclipse/buildship/core/internal/util/gradle/JavaVersionUtil.java#L24">
       org.eclipse.buildship.core.internal.util.gradle.JavaVersionUtil</a>
     * </pre>
     */
    String getEclipseCompatibleVersion(String javaVersion) {
        if ("1.9".equals(javaVersion)) {
            return "9";
        } else if ("1.10".equals(javaVersion)) {
            return "10";
        }

        return javaVersion;
    }

    private void addProjectDependenciesToClasspath(List<IClasspathEntry> classpath, Set<BuildTargetIdentifier> projectDependencies) {
        for (BuildTargetIdentifier dependency : projectDependencies) {
            String uriString = dependency.getUri();
            URI uri = null;
            // TODO: extract to util
            try {
                uri = new URI(uriString);
                uri = new URI(uri.getScheme(), uri.getHost(), uri.getPath(), null, uri.getFragment());
            } catch (URISyntaxException e) {
                e.printStackTrace();
            }
            if (uri != null) {
                IProject dependencyProject = ProjectUtils.getProjectFromUri(uri.toString());
                if (dependencyProject != null) {
                    classpath.add(JavaCore.newProjectEntry(dependencyProject.getFullPath()));
                }
            }
        }
    }

    private void addModuleDependenciesToClasspath(List<IClasspathEntry> classpath, Set<MavenDependencyModule> modules, boolean isTest) {
        for (MavenDependencyModule mainDependency : modules) {
            File artifact = null;
            File sourceArtifact = null;
            for (MavenDependencyModuleArtifact a : mainDependency.getArtifacts()) {
                try {
                    if (a.getClassifier() == null) {
                            artifact = new File(new URI(a.getUri()));
                    } else if ("sources".equals(a.getClassifier())) {
                        sourceArtifact = new File(new URI(a.getUri()));
                    }
                } catch (URISyntaxException e) {
                    e.printStackTrace();
                }
            }

            List<IClasspathAttribute> attributes = new LinkedList<>();
            if (isTest) {
                attributes.add(testAttribute);
            }
            if (!artifact.exists()) {
                attributes.add(optionalAttribute);
            }

            classpath.add(JavaCore.newLibraryEntry(
                new org.eclipse.core.runtime.Path(artifact.getAbsolutePath()),
                sourceArtifact == null ? null : new org.eclipse.core.runtime.Path(sourceArtifact.getAbsolutePath()),
                null,
                ClasspathEntry.NO_ACCESS_RULES,
                attributes.size() == 0 ? ClasspathEntry.NO_EXTRA_ATTRIBUTES : attributes.toArray(new IClasspathAttribute[attributes.size()]),
                false
            ));
        }
    }

    private void addJavaNature(IProject project, IProgressMonitor monitor) throws CoreException {
        SubMonitor progress = SubMonitor.convert(monitor, 1);
        // get the description
        IProjectDescription description = project.getDescription();

        // abort if the project already has the nature applied or the nature is not defined
        List<String> currentNatureIds = Arrays.asList(description.getNatureIds());
        if (currentNatureIds.contains(JavaCore.NATURE_ID)) {
            return;
        }

        // add the nature to the project
        List<String> newIds = new LinkedList<>();
        newIds.addAll(currentNatureIds);
        newIds.add(0, JavaCore.NATURE_ID);
        description.setNatureIds(newIds.toArray(new String[newIds.size()]));

        // save the updated description
        project.setDescription(description, IResource.AVOID_NATURE_CONFIG, progress.newChild(1));
    }
}

