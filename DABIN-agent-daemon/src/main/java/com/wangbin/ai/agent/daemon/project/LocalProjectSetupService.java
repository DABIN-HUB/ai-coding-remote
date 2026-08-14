package com.wangbin.ai.agent.daemon.project;

import com.wangbin.ai.agent.contract.enums.AgentType;
import com.wangbin.ai.agent.daemon.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class LocalProjectSetupService {

    private static final Logger log = LoggerFactory.getLogger(LocalProjectSetupService.class);
    private static final String OPTION_PROJECT_PATH = "projectPath";
    private static final String OPTION_PATH = "path";
    private static final String OPTION_PROJECT_NAME = "projectName";
    private static final String OPTION_NAME = "name";
    private static final String OPTION_SETUP_PROJECTS = "setupProjects";
    private static final String OPTION_REMOVE_PROJECT = "removeProject";

    private final AuthorizedProjectStore projectStore;
    private final WorkspaceManager workspaceManager;

    public LocalProjectSetupService(AuthorizedProjectStore projectStore, WorkspaceManager workspaceManager) {
        this.projectStore = projectStore;
        this.workspaceManager = workspaceManager;
    }

    public void configureProjects(ApplicationArguments args, boolean promptIfEmpty) {
        List<AuthorizedProjectState> cliProjects = toAuthorizedProjects(projectPaths(args), projectNames(args));
        if (!cliProjects.isEmpty()) {
            projectStore.addProjects(cliProjects);
            log.info("authorized {} local project(s) from --projectPath", cliProjects.size());
            return;
        }
        boolean setupRequested = args.containsOption(OPTION_SETUP_PROJECTS);
        if (setupRequested || (promptIfEmpty && projectStore.load().isEmpty())) {
            List<AuthorizedProjectState> selected = selectProjectsFromLocalDesktop();
            if (!selected.isEmpty()) {
                projectStore.addProjects(selected);
                log.info("authorized {} local project(s) from desktop chooser", selected.size());
            }
        }
    }

    public void listProjects() {
        List<AuthorizedProjectState> projects = projectStore.load();
        if (projects.isEmpty()) {
            System.out.println("No local projects are authorized.");
            return;
        }
        for (AuthorizedProjectState project : projects) {
            System.out.printf("%s\t%s\t%s\t%s%n", project.localProjectId(), project.projectName(),
                    project.agentType(), project.workspacePath());
        }
    }

    public void removeProjects(ApplicationArguments args) {
        List<String> selectors = args.getOptionValues(OPTION_REMOVE_PROJECT);
        if (selectors == null || selectors.isEmpty()) {
            selectors = projectPaths(args);
        }
        if (selectors == null || selectors.isEmpty()) {
            throw new IllegalArgumentException("missing --removeProject=<localProjectId|workspacePath>");
        }
        List<AuthorizedProjectState> remaining = projectStore.removeProjects(selectors);
        log.info("removed local project selector(s): count={}, remaining={}", selectors.size(), remaining.size());
    }

    private List<String> projectPaths(ApplicationArguments args) {
        return mergedOptionValues(args, OPTION_PROJECT_PATH, OPTION_PATH);
    }

    private List<String> projectNames(ApplicationArguments args) {
        return mergedOptionValues(args, OPTION_PROJECT_NAME, OPTION_NAME);
    }

    private List<String> mergedOptionValues(ApplicationArguments args, String primaryName, String aliasName) {
        List<String> values = new ArrayList<>();
        List<String> primary = args.getOptionValues(primaryName);
        if (primary != null) {
            values.addAll(primary);
        }
        List<String> alias = args.getOptionValues(aliasName);
        if (alias != null) {
            values.addAll(alias);
        }
        return values;
    }

    private List<AuthorizedProjectState> toAuthorizedProjects(List<String> workspacePaths, List<String> projectNames) {
        if (workspacePaths == null || workspacePaths.isEmpty()) {
            return List.of();
        }
        List<AuthorizedProjectState> projects = new ArrayList<>();
        for (int index = 0; index < workspacePaths.size(); index++) {
            String workspacePath = workspacePaths.get(index);
            String projectName = projectNames != null && index < projectNames.size() ? projectNames.get(index) : null;
            try {
                projects.add(toAuthorizedProject(workspacePath, projectName));
            } catch (RuntimeException ex) {
                log.warn("skip invalid local project path: path={}, reason={}", workspacePath, ex.getMessage());
            }
        }
        return projects;
    }

    private List<AuthorizedProjectState> selectProjectsFromLocalDesktop() {
        if (GraphicsEnvironment.isHeadless()) {
            log.info("local project chooser skipped because current environment is headless");
            return List.of();
        }
        AtomicReference<File[]> selectedFiles = new AtomicReference<>(new File[0]);
        try {
            SwingUtilities.invokeAndWait(() -> {
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle("Select code project folder(s) for DABIN Agent");
                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                chooser.setMultiSelectionEnabled(true);
                int result = chooser.showOpenDialog(null);
                if (result == JFileChooser.APPROVE_OPTION) {
                    selectedFiles.set(chooser.getSelectedFiles());
                }
            });
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("local project chooser was interrupted");
            return List.of();
        } catch (InvocationTargetException ex) {
            log.warn("local project chooser failed: {}", ex.getTargetException().getMessage());
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        for (File file : selectedFiles.get()) {
            if (file != null) {
                paths.add(file.getAbsolutePath());
            }
        }
        return toAuthorizedProjects(paths, null);
    }

    private AuthorizedProjectState toAuthorizedProject(String workspacePath, String projectName) {
        Path realWorkspace = workspaceManager.validateWorkspace(workspacePath);
        return new AuthorizedProjectState(LocalProjectIdFactory.stableLocalProjectId(realWorkspace),
                resolvedProjectName(realWorkspace, projectName), realWorkspace.toString(), AgentType.CODEX);
    }

    private String resolvedProjectName(Path realWorkspace, String projectName) {
        return projectName == null || projectName.isBlank() ? defaultProjectName(realWorkspace) : projectName;
    }

    private String defaultProjectName(Path realWorkspace) {
        Path fileName = realWorkspace.getFileName();
        return fileName == null ? realWorkspace.toString() : fileName.toString();
    }
}
