package org.springframework.cloud.release.internal.docs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.github.jknack.handlebars.Template;
import com.google.common.collect.ImmutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.cloud.release.internal.ReleaserProperties;
import org.springframework.cloud.release.internal.ReleaserPropertiesAware;
import org.springframework.cloud.release.internal.git.ProjectGitHandler;
import org.springframework.cloud.release.internal.pom.ProjectVersion;
import org.springframework.cloud.release.internal.pom.Projects;
import org.springframework.cloud.release.internal.tech.HandlebarsHelper;
import org.springframework.util.StringUtils;

/**
 * @author Marcin Grzejszczak
 */
class ReleaseTrainContentsUpdater implements ReleaserPropertiesAware {

	private static final Logger log = LoggerFactory
			.getLogger(ReleaseTrainContentsUpdater.class);

	private ReleaserProperties properties;
	private final ReleaseTrainContentsGitHandler handler;
	private final ReleaseTrainContentsParser parser;
	private final ReleaseTrainContentsGenerator generator;

	ReleaseTrainContentsUpdater(ReleaserProperties properties, ProjectGitHandler handler) {
		this.properties = properties;
		this.handler = new ReleaseTrainContentsGitHandler(handler);
		this.parser = new ReleaseTrainContentsParser();
		this.generator = new ReleaseTrainContentsGenerator(properties);
	}

	/**
	 * Updates the project page if current release train version is greater or equal
	 * than the one stored in the repo.
	 *
	 * @param projects
	 * @return {@link File cloned temporary directory} - {@code null} if wrong version is used or the switch is turned off
	 */
	File updateProjectRepo(Projects projects) {
		if (!this.properties.getGit().isUpdateSpringProject()) {
			log.info("Will not update the Spring Project cause the switch is turned off. Set [releaser.git.update-spring-project=true].");
			return null;
		}
		File releaseTrainProject = this.handler.cloneSpringDocProject();
		File index = new File(releaseTrainProject, "index.html");
		ReleaseTrainContents contents = this.parser.parse(index);
		String newContents = this.generator.releaseTrainContents(contents, projects);
		if (StringUtils.isEmpty(newContents)) {
			log.info("No changes to commit to the Spring Project page.");
			return releaseTrainProject;
		}
		return pushNewContents(projects, releaseTrainProject, index, newContents);
	}

	private File pushNewContents(Projects projects, File releaseTrainProject,
			File index, String newContents) {
		try {
			log.debug("Storing new contents to the page");
			Files.write(index.toPath(), newContents.getBytes());
			log.info("Successfully stored new contents of the page");
			this.handler.commitAndPushChanges(releaseTrainProject,
					this.generator.currentReleaseTrainProject(projects));
			return releaseTrainProject;
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	@Override
	public void setReleaserProperties(ReleaserProperties properties) {
		this.properties = properties;
		this.generator.setReleaserProperties(properties);
	}
}

/**
 * @author Marcin Grzejszczak
 */
class ReleaseTrainContentsGenerator implements ReleaserPropertiesAware {

	private static final Logger log = LoggerFactory
			.getLogger(ReleaseTrainContentsGenerator.class);
	private static final String SPRING_PROJECT_TEMPLATE = "spring-project";

	private ReleaserProperties properties;
	private final File projectOutput;

	ReleaseTrainContentsGenerator(ReleaserProperties properties) {
		this.properties = properties;
		this.projectOutput = new File("target/index.html");
	}

	String releaseTrainContents(ReleaseTrainContents currentContents, Projects projects) {
		String trainProject = this.properties.getMetaRelease().getReleaseTrainProjectName();
		ProjectVersion currentReleaseTrainProject = currentReleaseTrainProject(projects);
		ProjectVersion lastGa = new ProjectVersion(trainProject, currentContents.title.lastGaTrainName);
		ProjectVersion currentGa = new ProjectVersion(trainProject, currentContents.title.currentGaTrainName);
		ReleaseTrainContents newReleaseTrainContents = updateReleaseTrainContentsIfNecessary(currentContents, projects,
				currentReleaseTrainProject, lastGa, currentGa);
		if (!currentContents.equals(newReleaseTrainContents)) {
			Template template = HandlebarsHelper.template(this.properties.getTemplate()
					.getTemplateFolder(), SPRING_PROJECT_TEMPLATE);
			return generate(this.projectOutput, template, newReleaseTrainContents);
		}
		log.warn("Current release train [{}] is neither last [{}] or current [{}] or the projects haven't changed. Will not update the contents",
				currentReleaseTrainProject.version, lastGa, currentGa);
		return "";
	}

	ProjectVersion currentReleaseTrainProject(Projects projects) {
		return projects.forName(this.properties.getMetaRelease().getReleaseTrainProjectName());
	}

	private String generate(File contentOutput, Template template, ReleaseTrainContents releaseTrainContents) {
		try {
			Map<String, Object> map = ImmutableMap.<String, Object>builder()
					.put("lastGaTrainName", releaseTrainContents.title.lastGaTrainName)
					.put("currentGaTrainName", releaseTrainContents.title.currentGaTrainName)
					.put("currentSnapshotTrainName", releaseTrainContents.title.currentSnapshotTrainName)
					.put("projects", releaseTrainContents.rows)
					.build();
			String contents = template.apply(map);
			Files.write(contentOutput.toPath(), contents.getBytes());
			return contents;
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private ReleaseTrainContents updateReleaseTrainContentsIfNecessary(ReleaseTrainContents currentContents, Projects projects,
			ProjectVersion currentReleaseTrainProject, ProjectVersion lastGa, ProjectVersion currentGa) {
		ReleaseTrainContents newReleaseTrainContents = currentContents;
		// current GA is greater than the last GA
		if (greaterMinorOfLastGaReleaseTrain(currentReleaseTrainProject, lastGa)) {
			Title title = new Title(currentReleaseTrainProject.version, currentContents.title.currentGaTrainName,
					currentContents.title.currentSnapshotTrainName);
			return updatedReleaseTrainContents(currentContents, projects, title, true);
		} else if (currentReleaseTrainProject.isSameReleaseTrainName(currentGa.version)) {
			Title title = new Title(currentContents.title.lastGaTrainName, currentReleaseTrainProject.isReleaseOrServiceRelease() ?
					currentReleaseTrainProject.version : currentContents.title.currentGaTrainName, currentReleaseTrainProject.isSnapshot() ?
					currentReleaseTrainProject.version : currentContents.title.currentSnapshotTrainName);
			return updatedReleaseTrainContents(currentContents, projects, title, false);
		}
		return newReleaseTrainContents;
	}

	private boolean greaterMinorOfLastGaReleaseTrain(ProjectVersion currentReleaseTrainProject, ProjectVersion lastGa) {
		return currentReleaseTrainProject.isSameReleaseTrainName(lastGa.version) &&
				currentReleaseTrainProject.isReleaseOrServiceRelease() &&
				currentReleaseTrainProject.compareToReleaseTrainName(lastGa.version) > 0;
	}

	private ReleaseTrainContents updatedReleaseTrainContents(ReleaseTrainContents currentContents, Projects projects,
			Title title, boolean lastGa) {
		List<Row> rows = Row.fromProjects(projects, lastGa);
		return new ReleaseTrainContents(
				title, currentContents.rows.stream().map(current -> {
			Row projectRow = rows.stream().filter(row ->
					current.componentName.equals(row.componentName)).findFirst().orElse(current);
			if (projectRow == current) {
				return projectRow;
			}
			return from(current, projectRow);
		}).collect(Collectors.toCollection(LinkedList::new)));
	}

	private Row from(Row current, Row project) {
		return new Row(current.componentName, StringUtils.hasText(project.lastGaVersion) ?
				project.lastGaVersion : current.lastGaVersion,
				StringUtils.hasText(project.currentGaVersion) ?
						project.currentGaVersion : current.currentGaVersion,
				StringUtils.hasText(project.currentSnapshotVersion) ?
						project.currentSnapshotVersion : current.currentSnapshotVersion);
	}

	@Override
	public void setReleaserProperties(ReleaserProperties properties) {
		this.properties = properties;
	}
}

class ReleaseTrainContentsGitHandler {

	private static final Logger log = LoggerFactory.getLogger(ReleaseTrainContentsGitHandler.class);

	private static final String PROJECT_PAGE_UPDATED_COMMIT_MSG = "Updating project page to release train [%s]";

	private final ProjectGitHandler handler;

	ReleaseTrainContentsGitHandler(ProjectGitHandler handler) {
		this.handler = handler;
	}

	// clones the project and checks out the branch
	File cloneSpringDocProject() {
		return this.handler.cloneSpringDocProject();
	}

	void commitAndPushChanges(File repo, ProjectVersion releaseTrain) {
		log.debug("Committing and pushing changes");
		this.handler.commit(repo, String.format(PROJECT_PAGE_UPDATED_COMMIT_MSG, releaseTrain.version));
		this.handler.pushCurrentBranch(repo);
	}

}

class ReleaseTrainContentsParser {

	private static final Logger log = LoggerFactory
			.getLogger(ReleaseTrainContentsParser.class);

	ReleaseTrainContents parse(File rawHtml) {
		try {
			String contents = new String(Files.readAllBytes(rawHtml.toPath()));
			String[] split = contents.split("<!-- (BEGIN|END) COMPONENTS -->");
			if (split.length != 3) {
				log.warn("The page is missing the components table markers. Please add [<!-- BEGIN COMPONENTS -->] and [<!-- END COMPONENTS -->] to the file.");
				return null;
			}
			String table = split[1];
			String[] components = table.trim().split("\n");
			String[] titleRow = components[0].trim().split("\\|");
			Title title = new Title(titleRow);
			List<Row> rows = new LinkedList<>();
			for (int i = 2; i < components.length; i++) {
				String[] splitRow = components[i].split("\\|");
				rows.add(new Row(splitRow));
			}
			return new ReleaseTrainContents(title, rows);
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}
}