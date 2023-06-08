package com.microsoft.buildserver.adapter;

import static org.eclipse.jdt.ls.core.internal.handlers.MapFlattener.getValue;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.ls.core.internal.AbstractProjectImporter;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.ProjectUtils;
import org.eclipse.jdt.ls.core.internal.managers.BasicFileDetector;
import org.eclipse.jdt.ls.core.internal.preferences.Preferences;

import com.microsoft.buildserver.adapter.builder.BspBuilder;

import ch.epfl.scala.bsp4j.BuildClientCapabilities;
import ch.epfl.scala.bsp4j.BuildServer;
import ch.epfl.scala.bsp4j.BuildTarget;
import ch.epfl.scala.bsp4j.InitializeBuildParams;
import ch.epfl.scala.bsp4j.InitializeBuildResult;
import ch.epfl.scala.bsp4j.WorkspaceBuildTargetsResult;

@SuppressWarnings("restriction")
public class BspGradleProjectImporter extends AbstractProjectImporter {

    private static final String JAVA_BUILD_SERVER_GRADLE_ENABLED = "java.buildServer.gradle.enabled";
    public static final String BUILD_GRADLE_DESCRIPTOR = "build.gradle";
    public static final String BUILD_GRADLE_KTS_DESCRIPTOR = "build.gradle.kts";
    public static final String SETTINGS_GRADLE_DESCRIPTOR = "settings.gradle";
    public static final String SETTINGS_GRADLE_KTS_DESCRIPTOR = "settings.gradle.kts";

    /* (non-Javadoc)
     * @see org.eclipse.jdt.ls.core.internal.managers.IProjectImporter#applies(org.eclipse.core.runtime.IProgressMonitor)
     */
    @Override
    public boolean applies(IProgressMonitor monitor) throws CoreException {
        if (rootFolder == null) {
            return false;
        }

        Preferences preferences = getPreferences();
        if (!preferences.isImportGradleEnabled()) {
            return false;
        }

        Object bspImporterEnabled = getValue(preferences.asMap(), JAVA_BUILD_SERVER_GRADLE_ENABLED);
        if (bspImporterEnabled == null) {
            return false;
        }

        if (!(boolean) bspImporterEnabled) {
            return false;
        }

        if (directories == null) {
            BasicFileDetector gradleDetector = new BasicFileDetector(rootFolder.toPath(), BUILD_GRADLE_DESCRIPTOR,
                    SETTINGS_GRADLE_DESCRIPTOR, BUILD_GRADLE_KTS_DESCRIPTOR, SETTINGS_GRADLE_KTS_DESCRIPTOR)
                    .includeNested(false)
                    .addExclusions("**/build") //default gradle build dir
                    .addExclusions("**/bin");
            directories = gradleDetector.scan(monitor);
        }

        for (Path directory : directories) {
            IProject project = ProjectUtils.getProjectFromUri(directory.toUri().toString());
            if (project == null) {
                return true;
            }

            if (BspUtils.isBspGradleProject(project)) {
                return true;
            }
        }
        return false;
    }

    /* (non-Javadoc)
     * @see org.eclipse.jdt.ls.core.internal.managers.IProjectImporter#importToWorkspace(org.eclipse.core.runtime.IProgressMonitor)
     */
    @Override
    public void importToWorkspace(IProgressMonitor monitor) throws CoreException {
        BuildServer buildServer = BuildServerAdapter.getBuildServer();
        if (buildServer == null) {
            return;
        }

        if (BuildServerTargetsManager.getInstance().getInitializeBuildResult(rootFolder) == null) {
            InitializeBuildParams params = new InitializeBuildParams(
                    Constant.CLIENT_NAME,
                    Constant.CLIENT_VERSION,
                    Constant.BSP_VERSION,
                    rootFolder.toPath().toUri().toString(),
                    new BuildClientCapabilities(java.util.Collections.singletonList("java"))
            );
            BuildServerPreferences data = getBuildServerPreferences();
            params.setData(data);
            InitializeBuildResult initializeResult = buildServer.buildInitialize(params).join();
            buildServer.onBuildInitialized();
            BuildServerTargetsManager.getInstance().setInitializeBuildResult(rootFolder, initializeResult);
        }

        WorkspaceBuildTargetsResult workspaceBuildTargetsResult = buildServer.workspaceBuildTargets().join();
        List<BuildTarget> buildTargets = workspaceBuildTargetsResult.getTargets();

        List<IProject> projects = createProjectsIfNotExist(buildTargets, monitor);
        if (projects.isEmpty()) {
            return;
        }

        // store the digest for the imported gradle projects.
        ProjectUtils.getGradleProjects().forEach(p -> {
            File buildFile = p.getFile(BUILD_GRADLE_DESCRIPTOR).getLocation().toFile();
            File settingsFile = p.getFile(SETTINGS_GRADLE_DESCRIPTOR).getLocation().toFile();
            File buildKtsFile = p.getFile(BUILD_GRADLE_KTS_DESCRIPTOR).getLocation().toFile();
            File settingsKtsFile = p.getFile(SETTINGS_GRADLE_KTS_DESCRIPTOR).getLocation().toFile();
            try {
                if (buildFile.exists()) {
                    BuildServerAdapter.getDigestStore().updateDigest(buildFile.toPath());
                } else if (buildKtsFile.exists()) {
                    BuildServerAdapter.getDigestStore().updateDigest(buildKtsFile.toPath());
                }
                if (settingsFile.exists()) {
                    BuildServerAdapter.getDigestStore().updateDigest(settingsFile.toPath());
                } else if (settingsKtsFile.exists()) {
                    BuildServerAdapter.getDigestStore().updateDigest(settingsKtsFile.toPath());
                }
            } catch (CoreException e) {
                JavaLanguageServerPlugin.logException("Failed to update digest for gradle build file", e);
            }
        });

        BspGradleBuildSupport bs = new BspGradleBuildSupport();
        for (IProject project : projects) {
            bs.updateClassPath(project, monitor);
        }
    }

    private List<IProject> createProjectsIfNotExist(List<BuildTarget> buildTargets, IProgressMonitor monitor) throws CoreException {
        List<IProject> projects = new LinkedList<>();
        Map<String, List<BuildTarget>> buildTargetMap = BspUtils.mapBuildTargetsByBaseDir(buildTargets);
        for (String baseDir : buildTargetMap.keySet()) {
            if (baseDir == null) {
                JavaLanguageServerPlugin.logError("The base directory of the build target is null.");
                continue;
            }
            File projectDirectory;
            try {
                projectDirectory = new File(new URI(baseDir));
            } catch (URISyntaxException e) {
                JavaLanguageServerPlugin.logException(e);
                continue;
            }
            IProject[] allProjects = ProjectUtils.getAllProjects();
            Optional<IProject> projectOrNull = Arrays.stream(allProjects).filter(p -> {
                File loc = p.getLocation().toFile();
                return loc.equals(projectDirectory);
            }).findFirst();

            IProject project;
            if (projectOrNull.isPresent()) {
                project = projectOrNull.get();
            } else {
                String projectName = projectDirectory.getName();
                IWorkspace workspace = ResourcesPlugin.getWorkspace();
                IProjectDescription projectDescription = workspace.newProjectDescription(projectName);
                projectDescription.setLocation(org.eclipse.core.runtime.Path.fromOSString(projectDirectory.getPath()));
                projectDescription.setNatureIds(new String[]{BspGradleProjectNature.NATURE_ID});
                ICommand buildSpec = projectDescription.newCommand();
                buildSpec.setBuilderName(BspBuilder.BUILDER_ID);
                projectDescription.setBuildSpec(new ICommand[]{buildSpec});

                project = workspace.getRoot().getProject(projectName);
                project.create(projectDescription, monitor);

                // open the project
                project.open(IResource.NONE, monitor);

            }

            if (project == null || !project.isAccessible()) {
                continue;
            }

            project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
            projects.add(project);
            List<BuildTarget> targets = buildTargetMap.get(baseDir);
            BuildServerTargetsManager.getInstance().setBuildTargets(project, targets);
        }
        
        return projects;
    }

    @Override
    public void reset() {
        // do nothing.
    }

    private BuildServerPreferences getBuildServerPreferences() {
        BuildServerPreferences data = new BuildServerPreferences();
        Preferences jdtlsPreferences = getPreferences();
        data.setGradleArguments(jdtlsPreferences.getGradleArguments());
        data.setGradleHome(jdtlsPreferences.getGradleHome());
        data.setGradleJavaHome(jdtlsPreferences.getGradleJavaHome());
        data.setGradleJvmArguments(jdtlsPreferences.getGradleJvmArguments());
        data.setGradleUserHome(jdtlsPreferences.getGradleUserHome());
        data.setGradleVersion(jdtlsPreferences.getGradleVersion());
        data.setGradleWrapperEnabled(jdtlsPreferences.isGradleWrapperEnabled());
        return data;
    }
}
